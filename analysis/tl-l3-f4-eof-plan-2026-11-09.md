# F4 施工单：m4a EOF 2s 延迟掩盖的根因实证与阻塞登记（2026-11-09 第七十七批 / `12 §61.50`）

> **触发**：用户「继续」→ 按 `docs/17 §8.3` todo 顺序，F3 已由 `12 §61.49` 判
> PARTIAL，主断点推进到 **F4**。
> **本批性质**：**只读根因实证 + 真机探针 + 施工单 + 长效守护；P0 引擎舱代码零改动**。
> **结论先行**：F4 判 **BLOCKED**（不是 PASS、不是 NOT RUN）—— 根因已钉死，
> 但**缺少能触发竞态的真实 moov-at-end 样本**，按「先实证后改 P0」纪律不施工。

---

## 1. 被掩盖的到底是什么（根因）

`engine-legacy/.../UrlDataRequest.kt` 的 `sink.onEnd()`：成功 EOF 用
`repeat(20) { if (cancelled) return; Thread.sleep(100) }` 固定等 **2s** 才回传
`OnDataRequestEnd`；错误路径立即回传。`Start()` 立即返回 0，`RangeFetcher.fetch`
在 `cloudmusic-range` 后台线程回传 `onBegin → onData×N → onEnd`。

历史真机证据链（`a3-phase-ab-evidence-2026-11-07.md §26` / `docs/12 §61.26`）：

| 实验 | 现象 | 结论 |
|---|---|---|
| 立即 onEnd | `Start→push→onEnd→ReadFromFileNoLock 0→重试` 以 **~5ms/轮** 烧完重试预算 → `-103` → `doDecoder -5` | native 读侧先于 vfile 落盘 |
| E16 延迟 30s | g02 完成释放后 **17s 悬空回调**（use-after-free） | 长等待破坏自动进曲生命周期 |
| 现行 2s + 100ms 分片 `cancelled` 检查 | vfile 有落盘窗口，`Stop()` / `release()` 后不触碰 native | 工程可用，但每首 EOF 固定多等 2s |

**根因**：`12 §61` 起已切 **normal mode**（`SourceConfig.cacheLocal=true` +
`NMVirtualFileManager.SetConfig`），数据包经 `OnWriteNetData` 的 **GOT+lambda
异步任务**写入 vfile（实测 ~50ms）。`OnDataRequestEnd`（kind≠2）走
`OnFlushNetData` 任务；若 Java 侧立刻回传，native 读侧在写任务完成前从 vfile
读到 0，进入高频重试。**这是 P0 二进制内部「写线程落盘 vs 读侧轮询」的时序竞态**。

JNI 暴露给 Java 的只有 `OnDataRequestBegin / Data / End` 与 `Start / Stop`，
**没有任何「vfile 落盘完成」回调**。用户 2026-11-09 已定案 P0 为
「native so 原样复用 + Java/JNI 层重建」，二进制不可改 →
**Java 侧拿不到确定性完成信号，严格意义的「零等待、事件驱动根治」在当前基线下不可达**。

## 2. 历史验收其实绕开了该竞态（重要澄清）

`docs/12 §61.26` 的 G01 用 **faststart m4a 夹具（moov 前置）**，
备注写明「规避引擎 seek-to-end 竞态路径」；G06 虽观测到
`Start id=2 offset=96677 length=3018`（moov 尾部回源），但那是 **Range 200 投毒**
（`HttpUrlRangeOpener` 已修），不是 EOF 2s 竞态。
→ **现行 G01/G05/G07 的 PASS 不能证明 2s 等待可去掉。**

**同时对 `12 §61.49` 的一处口径澄清**：该批把 F3 的 `ape×remote` FAIL 描述为
「与 F4 的 m4a EOF 2s 掩盖同源」。本批实证后收窄：两者只是**同样走到
`onEnd … delayed 2s` 代码路径**（日志 `Start id=2 → onEnd delayed 2s`），
但 `ape×remote` 的根因是 **4 MiB 截断片段缺 APE 尾部 seek table**，
`onEnd` 前后根本没有 `-103`、没有 `Read 0` 重试、没有 codec 打开，
**不是 F4 竞态的复现载体**。该历史表述保留不改写，以本段为现行口径。

## 3. 本批真机探针（未能构造复现载体）

为解除「缺 moov-at-end 样本」的阻塞，尝试了三条取样本路径：

| 路径 | 实测 | 结果 |
|---|---|---|
| OpenList 全部挂载根目录扫描 | 只有 `/wo/转存有礼活动视频.mp4`（82,396,713 B）；**无任何 m4a / aac**；其外部签名直连 `WinError 10060` 连接超时 | 失败 |
| 本机编码器 / 外网 | Playwright ffmpeg 极简版无音频编码器；pypi / github / ghfast / maven 全超时（§61.49 §1 当时快照，本批复测仍不通） | 失败 |
| 设备已有 mp4 | `/sdcard/Download/SaveTwitter…(1280p).mp4` 前 16MiB 经 `adb exec-out dd` 取回；解析为 **fMP4**（`ftyp(32) + moov(1302) + [styp/moof/mdat]×158`） | 结构可用 |

基于 fMP4「片段靠 `moof/trun` 相对偏移、不依赖 `stco` 绝对地址」的特性，
用纯 Python 把顶层 `moov` 从 `ftyp` 之后**整体挪到文件末尾**，保留前 38 个
片段，构造 `analysis/l3-f3-fixtures/f3_m4a.m4a`（3,918,787 B，`moov` 起点
3,917,485）。该文件**未纳入 F3 矩阵样本清单**，仅作 F4 探针用。

`tools/l3_f4_eof_probe.py` 真机跑 LOCAL / REMOTE 两格（端点
`10.0.0.2:39853`，沿用 §61.49 的双路径注入与 `wifiOnly=false` 前置，跑完恢复）：

```
m4a × local  : prepare=True ext=m4a openSource=2 codec=None tailStarts=0 delayed=False
m4a × remote : prepare=True ext=m4a openSource=2 codec=None tailStarts=0 delayed=False
```

→ **native demuxer 未打开该人工重排的 fMP4，尾部 seek 未触发，
`-103` / `onEnd delayed` 均未出现**。不能区分「fMP4 + moov-at-end 不被支持」
与「截断片段不完整」；**该样本不可作 F4 复现载体**。
原始日志 `analysis/l3-f4-logs/`，结论 `analysis/l3-f4-eof-probe.json`
（`f4_repro_carrier=false`）。

## 4. 候选修复方案（**待真机 A/B 的假设，不是已证实的修复**）

前提：必须先用能触发竞态的样本做出**稳定复现**（见 §5），否则不得改
`UrlDataRequest`。三个候选按风险排序：

1. **条件化重试节流（推荐，Java 侧最小改动）**：`onEnd` 成功路径立即回传，
   去掉固定 2s；在 `Start()` 中识别「同一 `(offset,length)` 在 EOF 后短窗口内的
   重复请求」，只对这种读侧重试插入 ~60ms 节流（覆盖 ~50ms 落盘窗口），
   避免 5ms/轮烧完预算；保留 `cancelled` 检查防释放后回调。
   → 正常路径零额外延迟，只有真竞态才等。**风险**：EOF 后重试的识别需真机
   日志校准，误判会影响正常 seek。
2. **`OnDataRequestEnd` 改传 kind=2**：§59 反汇编显示 kind=2 走
   `PreloadDoneTaskOnWriteThread`（完成通知，写线程排队），理论上由 native
   自己保证「写任务先落盘、完成通知后到」，Java 侧无需等待。
   → **风险最高**：这是未经真机验证的路径推断，可能破坏已 PASS 的 G01/G05/G07。
3. **维持 2s 但参数化 / 自适应**：把 2s 改成「等到连续 N ms 无新 `Start` 或
   触顶 2s」，减少多数场景的等待。→ 仍是超时等待，**不算根治**。

三个候选都必须经 A/B 真机对照（现状基线 vs 变体）并满足判据才可合入。

## 5. 判据与阻塞

**F4 验收判据（`docs/17 §8.3` 原口径）**：自动进曲连续 3 首无 `-103`、
无释放后回调；`onEnd` 不再有固定 2s 盲等。

**当前阻塞（BLOCKED）**：
1. **缺真实 moov-at-end 样本**：OpenList 无 m4a / aac，本机无编码器、外网不通，
   设备 mp4 是 fMP4 且重排后 native 打不开。
2. **现行验收样本是 faststart**，不触发竞态；F3 的 `ape×remote` 也不是复现载体。
3. P0 二进制不可改，Java 侧无落盘完成信号 → 严格根治本就受限。

**解除条件（任一即可继续）**：
- 用户提供 1 个 **faststart=false（moov 在末尾）的真实 `.m4a` / `.mp4` 音频文件**
  （或任意含 moov-at-end 的 MP4，几 MB 即可）；
- 或恢复外网 / 提供可用 ffmpeg 二进制，以便从现有音源生成该样本；
- 或用户明确接受「只做候选 3 的自适应等待改良、不追求根治」。

样本到位后流程：`l3_f4_eof_probe.py` 先确认能稳定触发
`tailStarts>0` 与 `-103`（复现）→ 再按 §4 做 A/B → 跑自动进曲 3 首 →
按 `docs/17 §8.3` 判据如实登记。**本批不做这一步，不伪造 F4 完成。**

## 6. 本批交付与边界

- 新增 `tools/l3_f4_eof_probe.py` → `analysis/l3-f4-eof-probe.json` +
  `analysis/l3-f4-logs/`（2 份原始 logcat）+ 探针样本 `f3_m4a.m4a`。
- 新增 `tools/l3_f4_eof_audit.py` → `analysis/l3-f4-eof-audit.json` +
  `check_source_contracts.py §61.50` 守护：防伪造 F4 完成、防 2s 等待被悄悄
  改掉、防把 F3 `ape×remote` 误当复现载体、防基线漂移。
- 全量回写 `docs/12 §61.50` / `09 §2·§7` / `16 §5.3` / `17 §7 P1★·§8.3·§8.10` /
  `analysis/task-backlog` / 当日日志 / 工作记忆。**未新增 `docs/` 文件**
  → `09 §3` 计数仍 **31 个**。
- **P0 引擎舱零改动**：`UrlDataRequest.kt` 的 2s 分片等待、`RangeFetcher`、
  20 so / `ENGINE-FINGERPRINT` / `EngineAdapter` 全部保持原样。
  零新增依赖 / 权限 / 模块；真机命令全部带 `-s 10.0.0.2:39853`；
  不 `pm grant`、不改设备系统设置；探针前置已恢复。
- A3 仍 **G09 PARTIAL** → 舱不冻结、`engine_compat_test` 不回填。
- **下一步**：等样本解除阻塞后按 §5 流程做 F4 A/B；F4 之后 F5 → 回 P2→P3→P4。

> AI生成
