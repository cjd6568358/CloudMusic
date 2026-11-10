# F4 卡点根因定位（离线静态实证）：`OnDataRequestEnd` 的 kind 槽，不是 2s 等待

> `12 §61.52` / 第七十九批 · 2026-11-09 18:12 · **纯离线**（设备已离线）
> **性质**：静态反汇编 + 原始 logcat 时序交叉验证；**不改任何生产代码**。
> **结论等级**：**高置信假设，待真机 A/B 验证**；不是已证实的根因，不据此收口 F4。

---

## 1. 一句话结论

`UrlDataRequest.sink.onEnd()` 把 **`requestId` 直接传进 `OnDataRequestEnd` 的
首参**，而该首参在 native 侧是 **`OnNetSourceDataDone(int kind, String)` 的
kind 槽**。当 native 为 fMP4 发起第 2 个并发 Range（`id=2`）时，
`OnDataRequestEnd(2, "")` 被解释成 **完成标记 kind=2 →
`PreloadDoneTaskOnWriteThread`**，读侧据此认为预加载完成、但数据并不足以
probe 容器，于是**立即重发同一区间** `Start id=3 offset=327895`，形成重试链，
永远走不到 `Open codec`。这解释了为何 §61.51 的 A/B「去掉 2s 等待」完全无效。

## 2. 一手证据

### 2.1 native 侧 kind 语义（`docs/12 §59.2`，capstone 反汇编实证）

```
OnNetSourceDataDone：kind==2 → PreloadDoneTaskOnWriteThread（GOT @0x4bed0），
                     其余   → OnFlushNetData 任务（GOT @0x4bee8）。
```

同段还钉死 `OnNetSourceData` 的 `kind==2` 会**跳过 `NMMemoryCache::Write`**；
§59.3 已据此把 **数据包** 的 kind 恒改为 `NATIVE_DATA_KIND_NORMAL = 1L`。
但 **`End` 回调没有做同样的校准** —— `UrlDataRequest.kt:96` 至今是：

```kotlin
OnDataRequestEnd(requestId, error ?: "")
```

该类的 KDoc 自己也写明：
「`OnDataRequestEnd(kind, error)` → `OnNetSourceDataDone(int, String)`：
`kind==2` → `PreloadDoneTaskOnWriteThread`（完成通知），否则 → `OnFlushNetData`」。

### 2.2 真机日志时序（设备离线前采集，原始文件在 `analysis/l3-f4-logs/`）

| 日志 | 时序 | 间隔 |
|---|---|---|
| `fast-remote-25s.txt`（基线 2s） | `onEnd id=2 … 17:50:25.074` → `Start id=3 offset=327895 … 17:50:25.075` | **1 ms** |
| `ab-a-remote.txt`（变体 A 立即 onEnd） | `onEnd id=2 … 17:54:41.228` → `Start id=3 offset=327895 … 17:54:41.230` | **2 ms** |
| `recheck-m4a-remote.txt`（连接失败路径） | `onEnd id=2 → Start id=3 → onEnd id=3 → Start id=4 → onEnd id=4 → Start id=5 …` 直到 `id=6`，均 `offset=327895` | **每轮 2 ms** |

→ **`onEnd id=N` 与下一次 `Start` 稳定成对出现，且与 2s 延迟无关**（变体 A
去掉延迟后仍 2ms）。这是「End 回调本身触发 native 重发」的强时序证据。

### 2.3 F3 全部 REMOTE 日志交叉验证（避免把「多 Range」误当原因）

| 格式 | `Start` id 序列 | `onEnd` id 序列 | codec | 结果 |
|---|---|---|---|---|
| mp3 / wma | `[1]` | `[1]` | mp3 / wmav2 | PASS |
| ogg | `[1]` | `[]` | vorbis | PASS |
| **flac / wav** | `[1, 2]` | **`[1]`** | flac / pcm_s16le | **PASS** |
| ape | `[1, 2]` | `[1]` | — | FAIL（另有归因，见 §4） |
| aiff | `[1]` | `[]` | — | FAIL（自造容器，见 §4） |
| **fMP4 faststart** | `[1, 2, 3]` | **`[1, 2]`** | — | **FAIL** |

**关键区分**：多 Range **本身不是问题**（flac/wav 也有 `Start id=2` 却 PASS）。
差别在于：flac/wav/mp3 在**第 1 个 Range 的数据里就能 probe 出 codec**，
随后 `Stop()` 取消了第 2 个请求，因此**从不产生 `onEnd id=2`**；
而 fMP4 必须等 `moov` + 多个 `moof/mdat` 分片都到位才能 probe，
于是 `onEnd id=2` 必然出现 → 触发 kind=2 的完成通知 → 重试链。

### 2.4 A/B 排除 2s（`12 §61.51`）

变体 A（成功 EOF 立即回传）与基线（2s 分片等待）在 fMP4 REMOTE 上
**结果完全相同**：同样 `Start id=3 offset=327895` 重复、无 codec、无 `-103`。
→ 卡点与等待时长无关，§61.50 施工单候选①被证伪；这与「End 的 kind 槽」
的解释一致。

## 3. 可证伪预测与修复方向（**待真机 A/B，尚未实施**）

**预测**：把 `OnDataRequestEnd` 首参由 `requestId` 改为常量
`NATIVE_DONE_KIND_FLUSH = 1L`（让所有 End 都走 `OnFlushNetData` 正常收尾），
`requestId` 只保留在日志里；`f3_m4a_fast.m4a × REMOTE` 应出现
`Open codec for aac`。若不出现 → 本假设证伪，需继续查 native。

**候选改法**（设备重新上线后做 A/B，一次只改一处）：
1. `OnDataRequestEnd(1L, error ?: "")` —— 最小改动，与 §59.3 数据包校准同构。
2. 若 native 确实需要「最后一个请求完成」的 kind=2 语义，则改为**仅在
   `requestId == 最后一个请求` 时传 2**，其余传 1 —— 但需要 native 侧
   语义确认，不能猜。
3. **保留 2s 等待**：A/B 已证明它对该卡点无害也无益；删除它是独立议题，
   须另跑自动进曲 3 首判据，不要与本次修复捆绑。

**验证判据**：`Open codec for aac` → `Write start play nErr:0` →
F3 的 14 格矩阵全量复跑零回归 → 自动进曲连续 3 首无 `-103`、无释放后回调。

## 4. 适用边界（不强行推广）

- **不解释 `ape×REMOTE`**：该日志 `Start=[1,2]` 但 `onEnd=[1]`，第 2 个请求
  从未完成，没有 `onEnd id=2`；§61.49 的归因（4 MiB 截断片段缺 APE 尾部
  seek table）继续有效，不用本假设覆盖。
- **不解释 `aiff` 两格**：`Start=[1]`、`onEnd=[]`、`openSource 2`，是自造
  AIFF 容器未被 demuxer 打开，与 End 回调无关。
- **§59.2 的反例**：原文记「exp6 里两条路径都观测到读侧正常收尾」，即
  kind=2 在 exp6 未必致败（当时是 Memory Mode、请求数/缓存状态不同）。
  因此本结论是**相关性 + 反汇编语义支撑的高置信假设**，不是已证实因果。
- 竞品同样把 requestId 传进 kind 槽却可用（§59 KDoc），可能因 normal mode
  vfile 落盘兜底或请求 id 分配不同 —— **未实证，不写成结论**。

## 5. 对 F4 状态的影响

| 项 | §61.50 | §61.51 | **本批（§61.52）** |
|---|---|---|---|
| 复现载体 | 缺 | 已补（faststart fMP4 对照） | 沿用 |
| 「改 2s」 | 待 A/B 假设 | **A/B 证伪** | 排除 |
| 根因方向 | normal mode 落盘竞态（推测） | 另有机理、未定位 | **定位到 `OnDataRequestEnd` kind 槽（高置信假设）** |
| 解除条件 | 用户提供 moov-at-end 样本 | native 级定位或真实样本 | **不再需要样本：设备上线后做一次 kind 槽 A/B 即可** |
| F4 状态 | BLOCKED | BLOCKED | **仍 BLOCKED**（假设未真机验证、代码未改） |

**本批零代码改动**：`UrlDataRequest.kt` 保持 §61.50 基线（`git diff` 为空）；
零新增依赖 / 权限 / 模块；不连 adb（设备已离线）。
A3 仍 G09 PARTIAL → 舱不冻结、`engine_compat_test` 不回填。

## 6. 交付

- 本报告 `analysis/l3-f4-kind-slot-rootcause.md`。
- `tools/l3_f4_kind_slot_audit.py` → `analysis/l3-f4-kind-slot-audit.json`
  （只读审计：钉死 §59.2 语义锚点、`OnDataRequestEnd(requestId` 现状、
  4 组日志时序、F3 交叉验证表、A/B 证伪、代码零改动）。
- `check_source_contracts.py §61.52` 守护：防伪造 F4 完成、防把假设写成
  已证实根因、防 P0 代码被提前改动、防基线漂移。
- 全量回写 `docs/12 §61.52` / `09 §2·§7` / `16 §5.3` /
  `17 §7 P1★·§8.3·§8.12` / `analysis/task-backlog` / 当日日志 / 工作记忆。
  **未新增 `docs/` 文件** → `09 §3` 计数仍 31。

> AI生成
