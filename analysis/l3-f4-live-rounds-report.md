# F4 · 三轮真机纯观测汇总（§61.73，2026-11-11）

> **性质**：设备短暂在线窗口内完成的真机取证；本报告为**离线只读汇总**，
> 不连 adb、不采集日志、不重跑实验、不覆盖 §61.71 固化回放证据。
> **F4 仍 BLOCKED**。

## 1. 现场与前提

- 端点：`10.0.0.2:37509`（Redmi K60 `23013RK75C` / `mondrian`，
  `get-state=device`）；用户先给的 `10.0.0.2:40467` 连接超时 10060，
  已 disconnect。全端口 TCP 扫描出现 7848 个「开放」端口系本机
  `10.0.0.3` 网卡的代理假象，**不作证据**。
- app 原本**未安装**（`pm path` 空 / `run-as` unknown package），即
  §61.69 卸载后的**冷缓存**现场。
- 安装 `app/build/outputs/apk/debug/app-debug.apk`（31433878 B / md5 `7c1eb6e6…39`）→
  `adb -s 10.0.0.2:37509 install -r` **Success**（MIUI 本次未阻挡）。
  该 APK 的 dex **无 `nmcache_log_level` 字符串**，是 §61.69 接线之前的
  历史基线；三轮 logcat 中 `SetLoggerCallback` **0 次** → 一手坐实设备
  运行在 `g_level=0`（I/D/W/E 默认全开），符合 §61.70 观测口径。
- **P0 源码未改**：`EngineAdapter.kt` 的 `SetLoggerCallback(INMLogger(), 5)`
  接线仍在，回滚**待用户拍板**；设备上运行的是不含接线的基线 APK，
  即「运行产物 ≠ 当前源码」，如实登记不混淆。
- 设备第三轮后曾掉线，随后用户给新端点 `10.0.0.2:39257` 重新上线，
  `screencap` 抓到播放页 UI：`analysis/l3-f4-live-ui-20261111.png`。

### 用户一手观察与 UI 证据（关于「主动 seek」）

用户明确口述**已执行拖动**，且「进度条也没有时间」。截图核对：确为
播放页（曲目 `f3_m4a_complete` / `回环音源` / `1 首`），进度条左右
**均为 `00:00`（总时长 0）**，播放按钮仍是 ▶（未播放态），红色进度
条约在 1/4 处（拖动留下的位置痕迹），「暂无歌词」。
第三轮 logcat 交叉印证：**无 `mDuration`、无 `Open codec`、仅
`Play status 3`**；冷 / 暖轮虽有 `mDuration 2933` 与 `Open codec for aac`，
但 `Write start play` 仍 0。→ **播放未进入 prepare 完成 / 可 seek 态，
总时长不可用，用户拖动无法转化为有效 seek**；日志中 `onSeek` /
`seekTo` / `Slider` 全 0 与之一致。**seek 判据仍 NOT RUN**，
但原因由「用户未配合」更正为「播放未进入可 seek 态」，不得写成
用户未操作，也不得把 UI 红色进度条当成 seek 已生效的证据。

## 2. 三轮结果

| 轮次 | logcat 行数 | commit | 代次 | W 级行 | W 分支命中 | codec | `Write start play` | `-103` | `result:-2` | onData |
|---|---|---|---|---|---|---|---|---|---|---|
| 冷缓存（全新安装后首轮） | 3892 | [253952] | 1→6 | 3 | 0 | aac | 0 | 1 | 6 | 645 |
| 暖缓存（不清缓存第二轮） | 3680 | [262144] | 1→6 | 1 | 0 | aac | 0 | 1 | 5 | 644 |
| 用户口述已拖动 seek 轮（总时长 00:00，拖动未生效） | 3001 | [262144] | 1→4 | 0 | 0 | 无 | 0 | 0 | 3 | 479 |

- 三轮 `MakeRangeRequestAsync(N)` 代次均**严格递增**、`commit` 各自
  **恒定冻结**（253952 / 262144 / 262144），与 §61.68 的 16384 数值
  不同但**现象一致**：读侧重发使旧代次在途包被丢弃，commit 不再前进。
- 冷缓存轮 W 级 `NMSliceFile Open Index File ... Not Exist Not Cached`
  证实冷起点；冷 / 暖轮各有 3 / 1 行 `W/NMCache` → **W 级日志在默认
  `g_level=0` 下确实输出**，把 §61.70 的离线推断升级为真机一手实证。
- **7 条真实 `.rodata` W 分支格式串三轮全部 0 命中** —— 不是日志被
  门控挡住，而是错误 / 边界分支未触发的**有效负结果**；
  `UnExpect New Offsetcommted` 未出现，故「代次错配 → commit 冻结」
  **仍无直接分支日志证据**，不升级为实证、不自动收口。
- 冷 / 暖轮 `Open codec for aac` 打开但 `Write start play` **0 次**；
  `-103` 各 1 次、`result:-2` 6 / 5 次 → **判据未通过**。
- 第三轮（用户口述已拖动）：3001 行、commit 262144、代次 1→4、
  **无 `mDuration` / 无 `Open codec` / 仅 `Play status 3`**、`-103` 0 次、
  W 级 0 行；日志中 `onSeek` / `seekTo` / `Slider` **全部 0 次**。
  结合 UI 截图总时长 `00:00` → 播放未进入可 seek 态，拖动未转化为
  有效 seek，**seek 判据如实 NOT RUN**。`adb shell input swipe` 已实测
  被 MIUI 以 `SecurityException: INJECT_EVENTS` 拒绝，脚本无法代做。
- 三轮 `restore` 字段全 True（wifiOnly 临时文件 / adb reverse /
  http.server / logcat 子进程均已在 finally 恢复，设备系统设置未变）。
  第三轮结束后设备掉线。

## 3. 实跑核验

- checks：**19 项 / 全 PASS True**；失败项：无
- §61.71 固化回放证据 sha256 `c350b06b…`，
  `mode=replay` / `single_log_verdict=null`，**零改写**。
- UI 截图 `analysis/l3-f4-live-ui-20261111.png`（新端点 39257）在库且
  非空；用户口述 + 截图 + 第三轮日志三者交叉一致，分开登记不互相冒充。
- 新增 3 份 logcat / 3 份 probe JSON / 3 份 live verdict；
  §61.71 回放证据仍为 34 份归档的当时快照，**未重跑覆盖**。

## 4. 诚实边界

- **F4 仍 BLOCKED**：`-103` 判据未通过、`Write start play` 0、
  自动进曲 3 首 / seek / ENDED 仍 NOT RUN、F3 14 格未改写；
  A3 仍 G09 PARTIAL、舱不冻结、`engine_compat_test` 不回填、指纹不回填。
- 不写任何提前收口结论（禁词表见 §61.71 守护）；
  证据等级提升 ≠ 修复 ≠ 验证。
- 零 P0 源码改动、未重编译 / 未重装含接线 APK、so 二进制原样、
  未改用户网络 / 未 `pm grant`、配对码不落盘、
  §61.67~§61.72 历史正文与证据 JSON 一字未改。

> AI生成
