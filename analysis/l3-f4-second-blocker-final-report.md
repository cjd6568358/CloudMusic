# F4 · 第二卡点最终真机结论固化（`12 §61.61`）

> **2026-11-10 第八十八批** · 用户「完整闭环了……现在固化这个结论」。
> 真机部分已全部跑完、**设备可断开**；本批**纯离线 / 只读**，
> 只解析已落盘的 logcat 与 JSON，不连 adb、不操作设备、零生产代码改动。
> 承接 `§61.59`（A/B 裁决出「第二卡点」）与 `§61.60`（离线样本结构实证，
> 曾把归因优先级改为「样本不自洽为主」）。**本批用真机对照把 §61.60 的
> 样本侧归因推翻，第二卡点锁定为 vfile 流式落盘 / 跳跃式 probe 读的错位。**
> 历史批次的实测事实一律不改写。

---

## 1. 最后一块拼图：变体 B × 自洽样本

用户此前要求「趁设备还在，做最后一块拼图：变体 B + 自洽样本」。
判据是把两个剩余嫌疑同时隔离掉：

| 嫌疑 | 隔离手段 | 若为真应看到 |
|---|---|---|
| ① 样本不自洽（`§61.60` 主要嫌疑） | 换成**自洽 fMP4** `f3_m4a_complete.m4a` | REMOTE 出现 `Open codec for aac` |
| ② 错误完成通知（`§61.59` 环节 B） | 变体 B（`OnDataRequestEnd` 首参恒传 `1L`） | 消除 `id>=3` 重复后出现 codec |

**自洽样本结构（本批一手 ISO-BMFF 解析）**：`16,573,387 B`、158 个顶层 box，
`ftyp` 1 / `moov` 1 / `styp` 52 / **`moof` 52 / `mdat` 52**，
**以配对 `mdat` 收尾、无孤立 `moof`、无 `sidx`/`mfra`/`mfro`，末尾偏移 ==
文件长度**。与 `§61.60` 的 `f3_m4a_fast.m4a`（3,918,787 B / 13 `moof`
/ 12 `mdat` / 孤立尾部 `moof` / 截断在分片中间）形成严格对照。

## 2. 决定性证据矩阵（全部来自已落盘 logcat）

| 运行 | 样本 | 完成通知 | `ReadFromFileNoLock` | `WaitRequest` | codec / 出声 |
|---|---|---|---|---|---|
| **P0 基线 · REMOTE**（`selfconsist-remote.txt`） | 自洽 | 原样（错误 kind） | `offset:0 readed_len:0`；`offset:327895 readed_len:16169`；**`offset:605007 readed_len:0 result:-2`（3 次）** | `cur offset:0 … expect offset:605007 … **commit :  368640**` | **无** `Open codec` / `Write start`；`Play status 3`；无 `-103` |
| **P0 基线 · LOCAL**（`selfconsist-local.txt`） | 自洽 | — | 无跳跃读 | 无 `WaitRequest` | **`Open codec for aac`** / `Play status 4` / **`Write start play nErr:0`** / `openSource 0` / `mDuration 155933` |
| **变体 B · REMOTE**（`ksab-m4a-remote.txt`） | 自洽 | 恒传 `1L` | `offset:0 readed_len:32768`；`offset:327895 readed_len:32768`；**`offset:605007 readed_len:0 result:-2`** | `cur offset:368640 … expect offset:605007 … **commit :  368640**` | **无** `Open codec` / `Write start`；`Play status 3`；无 `-103` |
| §61.59 变体 B · REMOTE（`ab-run2-m4a-remote.txt`，旧非自洽样本） | 非自洽 | 恒传 `1L` | **同样** `offset:605007 readed_len:0 result:-2` | `expect offset:605007 … **commit :  147456**` | 无 codec（历史快照） |

**PASS 格 vs FAIL 格仅差一行**：LOCAL 全文件可读 → `offset:0 readed_len:32768`
正常取到 probe 数据；REMOTE 的 vfile 只连续提交到 `commit=368640`
（≈360 KB），而 fMP4 demuxer 要跳读 `offset=605007`，
`ReadFromFileNoLock` 在该偏移返回 `result:-2` / `readed_len:0`。
`Start id=2/3/4` 反复以 `605007` 起点请求，但 **`commit` 始终停在
368640**，`WaitRequest` 等不到目标字节 → probe 拿不到数据 → 无 `Open codec`。

## 3. 三个候选的裁决

1. **样本自洽性 —— 排除**。自洽样本在 **LOCAL 完整 PASS**（解出 `aac` 并
   `Write start play nErr:0`），却仍 **REMOTE 无 codec**；且 §61.59 的旧非自洽
   样本与自洽样本在同一 `605007` 偏移呈现**完全相同**的
   `readed_len:0 result:-2`。`§61.60` 的「样本不自洽为主」归因被真机推翻，
   `PENDING_REAL_SAMPLE` 解除条件已满足且结果指向别处。
2. **错误完成通知 —— 排除为第二卡点**。基线（错误 kind、4 个 Start、
   `id>=3` 连锁重试）与变体 B（`1L`、仅 2 个 Start）**都无 codec**，
   两格 `offset:605007` 失败读与 `commit=368640` 逐字一致。
   `§61.59` 的「环节 B 部分成立」不改写 —— 它确实消除了重复请求，
   但**不是无 codec 的原因**。
3. **vfile 流式落盘 vs fMP4 跳跃式 probe 读 —— 锁定为真正卡点**。
   四格日志共同特征：`commit`（连续提交点）< demuxer 跳跃目标 `605007`；
   Java 侧 `onData` 在 §61.60 已被证明与请求逐字节一致（不是丢数据），
   缺口是 **`commit` 与跳跃点之间的空洞未被补齐**：Range 请求从跳跃点
   （`605007`）发起，而 vfile 的连续提交语义要求先补
   `[368640, 605007)`，于是 `WaitRequest` 永久等待、`ReadFromFileNoLock`
   恒返回 `-2`。**这是流式顺序落盘与随机访问 probe 的结构性错位，
   不是等待时长、不是完成通知、不是容器是否自洽。**

> 工程口径补充（不改写用户结论）：`commit` 究竟是「落盘速度不足」还是
> 「`NMNetSource::WaitRequest` 的 commit / Range 记账语义不允许回填跳跃区间」，
> 现有日志**尚未在 native 层区分**；具体函数与字段仍待 native 级定位。
> 本批只锁定现象与方向，**未实施、未验证任何修复**。

## 4. 结论（一行）

**F4 第二卡点 = normal mode vfile 流式落盘跟不上 fMP4 demuxer 的跳跃式
probe 读（`commit≈368640` < `offset=605007` → `readed_len:0 result:-2`）；
样本自洽性与错误完成通知均已被真机对照排除。**

## 5. 诚实边界

- **F4 仍判 BLOCKED**：根因锁定 ≠ 修复完成；自动进曲连续 3 首（无 `-103`、
  无释放后回调）/ seek / ENDED 如实 **NOT RUN**，F3 14 格矩阵未改写。
- 不写「F4 已解决 / m4a 已支持 / 全格式已支持 / 第二卡点已修复 /
  假设被证伪（指环节 B）」；不伪造 native 级定位结论或修复验证。
- P0 引擎舱零净改动（`UrlDataRequest.kt` `git diff` 空）、零新增依赖、
  发布基线零变化；A3 仍 **G09 PARTIAL**、舱不冻结、
  `engine_compat_test` 不回填。
- 变体 B 只临时存在，`finally` 无条件恢复 P0 基线并重编译重装；
  `analysis/l3-f4-ab-selfconsistent.json` 记录
  `primary_criterion_met=false` / `p0_restored=true` /
  `regression_all_pass=true`（5 格回归全 PASS）。
- 设备按用户指令**可断开**；本批不连 adb、不连 OpenList、不取新真机数据。

## 6. 交付与守护

- `tools/l3_f4_second_blocker_final_audit.py`（纯 Python 零依赖、离线只读）
  → `analysis/l3-f4-second-blocker-final.json`（一手证据摘要）+ 
  `analysis/l3-f4-second-blocker-final-audit.json`（N 项 checks 全 PASS）。
- `tools/check_source_contracts.py` 新增 `12 §61.61 守护`：
  防伪造「F4 已解决 / 样本或通知才是卡点 / 修复已验证 / 设备仍需在场」，
  防证据漂移、防 P0 基线被改动。`§61.60` 守护保留为**历史快照**，
  其 PASS 文案追加「归因已被 §61.61 真机结论修正」。
- 全量回写 `docs/12 §61.60`（补写）/`§61.61`、`09 §2·§7`、
  `16 §5.3`、`17 §8.20/§8.21`、任务清单、当日日志与长期记忆。

## 7. 剩余断点与下一步

1. **F4**：按「vfile 连续提交点 ↔ 跳跃式 probe 读」方向做 native 级定位
   （`NMNetSource::WaitRequest` / `commit` 记账 / Range 调度），
   并在下次设备上线时验证修复：判据仍是自动进曲 3 首无 `-103`、
   seek / ENDED 正常、REMOTE 出现 `Open codec for aac`。
2. **F5**：A3 全绿后 ABI 回填 + 冻结舱 + `§61.33` 调试入口收口。
3. **P2** 播放页 1:1 → **P3** 八个模块；F3 补齐 7 种 NO_SAMPLE；
   G09 PARTIAL / 舱不冻结；T-V USB / DMR 项。
4. `§61.38`~`§61.61` 多批改动均未提交、未 push，`HEAD` 仍 `8c9d79b1`。

> AI生成
