# CloudMusic · 文档 ↔ 代码核对与任务清单（2026-11-07 第三十三轮）

> 第三十三轮为只读核对轮（零源码改动），完整断点见 `docs/12 §42`。
> **2026-11-07 更新（T1 / T2 已完成，见 `docs/12 §43`）**：上轮「临时 SDK 不齐、无法实跑 Gradle」是误查
> —— SDK 一直位于 `Temp/cloudmusic-ta1-sdk`（`platforms/android-35` + `build-tools/35.0.0`）。
> 本轮解环后已实跑 `./gradlew test :app:assembleDebug --offline` **BUILD SUCCESSFUL in 1m 33s**，
> Hilt 生成图 `provideMediaControl()` 零参数、**350 测试零回归**。下方差异 / 任务 / 验证状态已同步。
> **2026-11-07 再更新（T4 完成，见 `docs/12 §44`）**：`IMediaControl` 加带默认空实现的
> `onPlaying()` / `onManualPause()`（C-15），`PlaybackService` 回传用户操作 →
> **`03-07 §12 T03` 生产链路成立**；全量构建 **BUILD SUCCESSFUL in 42s**、**353 测试 / 37 套件 / 0 失败**。
> **2026-11-07 终版更新（T3 / T5 / T6 / T7 完成，见 `docs/12 §45`）**：GAIN 三变体逐项映射、
> duck 静态链核实、旧注释清理与文档全量同步完成；**355 测试 / 37 套件 / 0 失败**。
>
> **★ 2026-11-07 第四十一批（真机 A3 深入，见 `docs/12 §50`）**：`§42.3` 任务清单已不是当前主线。
> 真机实证并修复 5 个生产侧 P0（`restore()` 无调用方 / `MediaSession` 无播放态 /
> 媒体键接收器空实现 / 无 mediaButtonReceiver / `SourceConfig.type` 恒 LOCAL），
> 补齐 3 个照搬真类与 5 个类的 native 字段 / 回调，攻破 3 道 CheckJNI abort。
> **native 6 so → PlaybackEngine → setDataSource → prepareAsync → 焦点 GAIN → Play 全链真机跑通、零崩溃**；
> **仍 0 个 active track / 播放期无 HTTP 请求 → 未出声**。
> 剩余工作见 `12 §50.7`：`DataProvider.createDataRequestHandler()` 取流句柄、事件码常量表、
> 本轮平台层改动的 JVM 单测与守护、应用侧权限/数据源后重跑 G01~G09。
> 基线 356 测试 / 37 套件 / 0 失败不变，主节点 4 个 / 硬门槛 2 个。
> `§42.3` 八项任务 **T1~T7 全收口**，仅 T8（真机 / ⑦ C4 决策 / L2 蓝牙）待。
> **2026-11-07 再更新（第三十七批，见 `docs/12 §46`）**：`BECOMING_NOISY` 接入
> `BluetoothOutputPolicy` 生产裁决链（开关 / DLNA 豁免 / 去重 / 设置热更新）；
> 澄清 `BLUETOOTH_CONNECT` 本就在 11 项权限内、V0.1 未运行时申请 → B01 降级。
> **356 测试 / 37 套件 / 0 失败**、5 条守护、零新增权限。L2 仍未收口（蓝牙 ACL 广播需授权流程）。
> **2026-11-07 终版（第三十八批，见 `docs/12 §47`）**：用户确认 D-145 两项 → 61 项素材按
> `legacy_` 前缀导出到 `app/src/main/assets/legacy-player/`（51 文件 / 320,569 字节）、
> `assets-mapping.csv` 空表 → **61 行 `replaced=false`**；C4 改 dev WARN / release FAIL，
> **release 阻断由「仅 C1」变为「C1 + C4」**（用户确认接受）。**⑨ 只验证到素材随 APK
> 打包 + 主工程零回归**；素材全量替换 / `:ui-legacy` / R 引用改写仍属 L3。
> 下表 §5 为 §46 时点快照，§47 验证见 `analysis/c4-assets-delivery-2026-11-07.md`。
> **2026-11-07 终版（第三十九轮，见 `docs/12 §48`）**：核对出 DLNA 输出切换
> 全链路生产调用方为 0、`§46` 的 DLNA 豁免空转；新增联动守护（成对断言）；
> 工作区 82 项分组盘点。**自研侧零决策风险、零回归、真机无关的代码项已穷尽**，
> 剩余四项均需素材 / 权限决策 / 真机或用户指定方向。
> **2026-11-07 真机更新（第四十批，见 `docs/12 §49`）**：用户提供 arm64 真机，
> 推翻上一条静态结论 —— 发现并修复 `PlaybackService` 全代码无启动点的 P0 缺口；
> FGS / MediaSession / 扫描调度 / Room 真机生效。**A3 PARTIAL RUN**，
> G01~G09 仍 NOT RUN（曲库 0 首 / 播放键未送达 / native 引擎未加载 / MIUI 无线 adb 限制授权与注入）。
> 硬门槛收敛为应用侧播放链路 + 真机 G01/G02。
>
> **★ 2026-11-08 第五十四批（见 `docs/12 §61.27`）**：`§42.3` / 上表 §5 快照均已过期。
> 真机 A3 已推进到 **G01~G07 PASS / G08 定向 PASS（2h 长稳未跑满）/ G09 PARTIAL**。
> 本批补做 HEAD `211080c9` 缺失的设备验证（一次性 GET RST 注入 → 连接期重试救回
> 302592B 全量取流、37.8s 完整播放、零 -103），并修复「冷启动首次 ACTION_PLAY
> 被吞、MediaSession 假报 PLAYING 进度恒 0」（`pendingPlay` 兑现）。
> 基线 **399 测试 / 43 套件 / 0 失败**、契约自检 PASS、dev 6/6。完整证据与复现步骤
> 见 `analysis/g08-injection-evidence-2026-11-08.md`。剩余硬门槛收敛为：
> **① USB/亮屏复测 G09；② G08 2h 长稳；③ 收口后恢复 Manifest 生产形态**
> （含 61.23 把 LAUNCHER MainActivity 写 `exported=false` 的口径疑点）。
>
> **★ 2026-11-08 第五十五批（见 `docs/12 §61.28`）**：用户「按照文档计划完成任务」→
> 推进 `§61.27` 下一步③中判据最明确的挂账项「默认仅 WiFi 播放」（`§56` 登记未实现）。
> 四层落地：纯 JVM `NetworkPlaybackPolicy` → `PlayerController` 双入口闸门 →
> `AndroidNetworkPlaybackGate`（ConnectivityManager + 实时 `wifiOnly`，零新增权限）→
> 设置页开关 + `PlaybackService` 同步判定防假报 PLAYING。`09 §539` 的历史挂账收口。
> 基线 **408 测试 / 44 套件 / 0 失败**、dev 6/6、release FAIL(C1,C4)、11 权限不变；
> 真机 WiFi 正向回归 PASS（单次 PLAY 出声）。**负向路径需 USB 环境复测**（无线 adb
> 关 WiFi 会断调试连接），未当结论用。剩余硬门槛仍是 G09 / G08 2h / Manifest 恢复。
>
> **★ 2026-11-08 第五十六批（见 `docs/12 §61.29`）**：用户「继续」→ 推进 `16 §5.3` 的
> **T-L L2 主节点**。按 `03-11 §2` 既有 `SHOW_BLUETOOTH_NAME` 动作落地运行时授权
> （输出弹窗触发、启动零申请）、`permissionGranted` 改运行时真值、授权后才动态注册
> ACL 广播并用纯 JVM `BluetoothBroadcastAction` 翻译成 `BluetoothEvent`；未授权仍按
> `03-09 §8` B01 只保留 `BECOMING_NOISY`，设备地址不入日志（B12）。**零新增权限 /
> 零口径变更**。同步更新 3 条锁着旧口径的历史守护（§46 / §52）+ 新增 4 组 §61.29 守护，
> 并回写 `03-09 §4` / `03-11 §1.1` / `PermissionRequester` 过时注释。
> 基线 **416 测试 / 46 套件 / 0 失败**、契约自检 PASS、dev 6/6、C3 11 权限不变；
> 真机 13:50 降级验证 PASS（`granted=false` → `ACL receiver off`，单次 PLAY 出声零崩溃）。
> **已授权状态下的 ACL 链路未真机实测**（无线 adb 缺 GRANT_RUNTIME_PERMISSIONS、不伪造
> 授权），需 USB 环境复测。剩余：G09 / G08 2h / Manifest 恢复 / T-L L3 / T-V。
>
> **★ 2026-11-08 第五十七批（见 `docs/12 §61.30`）**：用户「继续」→ 先核
> 「一次性 ABI 验收 / 指纹回填」挂账，结论是 **A3 = G01~G09 全绿才冻结舱**
> （`03-06 §6`），当前 G08/G09 未收口，**不回填才是正确状态、不伪造验收结论**；
> A1/A2 与 C1/C6 本就 PASS。随后把设备上自 13:50:49 起持续运行的 LIST_LOOP 双曲
> 循环正式登记为 **G08 2h 长稳监控**（终点 15:50:49），新增可复跑采样器
> `analysis/g08-longrun-sample.py` + `g08-longrun-samples.jsonl`。
> 首次中期样本 `13:50:49→13:59:19`（8.5 分钟；后续已更新至 14:01，见 §61.30）：pid 16859 未变、App 在后台
> （焦点 `com.miui.home`）、灭屏 Dozing、16 次完成过渡、`-103`/`msg=100`/`FATAL`/
> `ETIMEDOUT` 全 0、最大 PSS 106.6 MiB（目标 ≤180）→ **ALIVE + CLEAN（非 2h 结论）**。
> 采样器迭代中按原始数据修正 4 处口径错误（非 UTF-8 解码 / `msg=2` 双日志重复计数 /
> 全量 logcat 混入 Google Play 的 ETIMEDOUT / 重叠窗口汇总重复累加）。
> **下一步**：15:50 后补采样收口 G08；同环境复测 G09 / 非 WiFi 负向 / 已授权 ACL；
> A3 全绿后才回填 ABI 验收与冻结舱；其后 Manifest 恢复、T-L L3、T-V。

> **★ 2026-11-08 第五十八批（见 `docs/12 §61.31`）**：用户「拉取代码…继续任务」→
> 断点由 `§61.30` 的「15:50 补采样收口 G08 2h」改为**用户新决策：20 分钟足够，
> 实际使用有问题再修改**（`01 §10 #5` / `03-06 §6.2` / `03-12 §9` 口径统一）。
> 真机实证：pid 16859 自 13:50:48 连续存活至 14:12:38（**21 分 49 秒**），
> 期间后台灭屏、22 次完成过渡、阻断级异常 0、PSS 峰值 110.3 MiB；终点
> `ApplicationExitInfo reason=10 USER REQUESTED / FORCE STOP / SwipeUpClean`
> = **用户上划清理**，非系统回收、非崩溃 → **G08 = PASS（20 分钟口径）**。
> 修复 `g08-longrun-sample.py` 硬编码 `adb`（本机 adb 不在 PATH，旧版直接
> `[WinError 2]`，文档声称「可复跑」实际跑不起来）：adb 自动探测 / `--adb` /
> `--connect` / `ApplicationExitInfo` 退出原因 / 进程消失不再退化全量 logcat /
> 判据 20 分钟。证据 `analysis/g08-20min-verdict-2026-11-08.md`。
> A3 = G01~G07 PASS / G08 PASS / G09 PARTIAL → **仍未全绿，舱不冻结、指纹不回填**。
> 剩余：G09 复测、非 WiFi 负向、已授权 ACL、Manifest 生产形态、T-L L3、
> T-V 阻断 12 条 / DMR T20/T21。
> 基线：在线 `./gradlew test` **BUILD SUCCESSFUL in 2m4s**；契约自检 PASS；
> dev **6/6**；release **FAIL(C1,C4)**；`--offline` 因本机缺 room/datastore
> 缓存依赖失败（环境差异，非代码缺陷）。本批工作区未 commit / push。

> **★ 2026-11-08 晚 第五十九批（见 `docs/12 §61.32`）**：用户「继续」→ 设备亮屏，
> 推进 `§61.31` 剩余①的 G09 复测（**零源码 / Manifest / 构建改动**）。
> 四组真机实验：① 桌面启动实测成功（`resolve-activity` 可解析、显式组件与
> `monkey -c LAUNCHER` 均拉起；`am start -a/-c/-p` 失败属参数组合，非产品缺陷）；
> ② 前台态 crash 约 100ms 恢复，但触发者是 **Activity 重建**而非 START_STICKY；
> ③ 后台态 crash **亮屏下 206s 仍无服务重启**，日志 `toggled into fg service restriction`
> + `RUN_ANY_IN_BACKGROUND: ignore` → **MIUI OEM 抑制**，推翻旧文档「需 USB/亮屏环境」
> 的归因（USB 不改变 OEM 策略）；④ 用户重新点开 App 约 **957ms** 自动
> `prepare g01-local`、保持 PAUSED，点播放即 `AudioTrack Write start play`。
> **G09 仍 PARTIAL，不判 PASS**；按用户「实际使用有问题再修改」不加自恢复兜底。
> A3 = G01~G08 PASS / G09 PARTIAL → 舱不冻结、指纹不回填。
> 同批修正 **P1 合规声明错误**：`NOTICE.md §3` 原写「二进制与素材尚未引入」
> （实际 18 so 已打包 / 12 JNI 真类已编译 / 61 项素材已导出），按实际状态重写；
> `03-07 §12 T16` 的「后台 2h」同步为 20 分钟；登记 app 无 `res/`、无
> `label`/`icon`（系统默认）的发布阻断 #2 现状。发布阻断 12 条盘点：
> **PASS 5 / 部分 5 / 未满足 2**，自动检查实际阻断 C1 + C4。
> 证据 `analysis/g09-recovery-evidence-2026-11-08.md`；验证结束 `force-stop`
> 恢复设备起点。剩余断点：非 WiFi 负向 / 已授权 ACL / A3 全绿后 ABI 回填 /
> Manifest 生产形态（LAUNCHER `exported` 口径需核实）/ T-L L3 / T-V 剩余项。
> 工作区未 commit / push。

> **★ 2026-11-08 晚 第六十批（见 `docs/12 §61.33`）**：用户「继续」→ 处理
> `§61.32` 剩余④的 LAUNCHER `exported` 口径疑点，**零行为改动**（仅 Manifest 注释 + 文档）。
> 按 Android 官方 `<activity>` 文档定论：带 MAIN/LAUNCHER 的 `MainActivity`
> **必须 `exported=true`**（Launcher 是外部应用，设 false 会抛
> `ActivityNotFoundException`），`§61.23` / `§61.24` 的「恢复 false / 随 Service
> 一并恢复」为错误口径，**作废**；`§61.27` 原标【推断】本批定论。
> 生产形态三组件：`MainActivity=true` / `PlaybackService=false` 且移除
> `ACTION_PLAY` intent-filter / `MediaButtonEventReceiver=true`，
> 施工单与验收步骤已写入 `12 §61.33`，**A3 全绿后才执行**（当前仍需 ACTION_PLAY
> 做真机触发）。同批修 `03-11 §1.1` 表格（Receiver 原写 `exported=false` +
> 「有意空实现」，与 `12 §50/§51` 的 `exported=true` + `MediaKeyAction` 转发矛盾），
> 并把全库现行 **7 处**旧 2 小时判据统一为 20 分钟（`03-07 B10`、`03-09 B11/T12`、
> `03-11 B07`、`10 §4`、`16 §5.3`、`09 §4 R8`；`12` 历史批次表述保留）。
> `tools/check_source_contracts.py` 新增 §61.33 守护（LAUNCHER exported + 2h 残留）。
> A3 状态不变：G01~G08 PASS / G09 PARTIAL → 舱不冻结、指纹不回填。
> 设备未触碰；工作区未 commit / push。

> **★ 2026-11-08 晚 第六十一批（见 `docs/12 §61.34` / C-16）**：用户「继续」→
> 核对发布阻断 #11 的 JVM 覆盖时发现 `DegradationPolicy.evaluate()` 的 **`offline`
> 是从未被读取的死参数**，`03-11 §3`「网络权限/飞行模式」一行只靠恒 false 的
> `showNetworkErrorDialog` 表达，测试里两态断言等价（**假覆盖**）。
> 本批做契约演进 C-16：`DegradationOutcome` 新增
> `networkSourceAvailable: Boolean = true`（默认值，现有调用零破坏），
> `evaluate` 映射 `!offline` 并追加离线信息提示；**硬规则不变**（本地曲库/播放恒可用、
> 不弹网络错误弹窗）。`PermissionGateTest` 新增 2 用例（两态差异 / 五类叠加），
> 把 `hardRuleNeverBlocksLocalPlayback` 的弱断言升级为 `assertEquals(!offline, …)`。
> `./gradlew test --offline` **BUILD SUCCESSFUL in 1m6s，418 测试 / 46 套件 / 0 失败**
> （基线 416 + 2，XML 确认两条 C-16 用例执行）；契约自检四条守护 PASS、
> dev 6/6、release FAIL(C1,C4)。
> 发布阻断 #11：**JVM 侧六行矩阵已全量覆盖**，真机 T05/T06/T14 仍待 T-V，仍为
> 「部分验证」不判全绿；`networkSourceAvailable` 当前无生产消费方，如实登记不伪造接线。
> `check_source_contracts.py` 新增 §61.34 守护防退回死参数。
> A3 状态不变：G01~G08 PASS / G09 PARTIAL → 舱不冻结、指纹不回填。
> 设备未触碰；工作区未 commit / push。

> **★ 2026-11-08 晚 第六十二批（见 `docs/12 §61.35` / C-17）**：用户「继续」→
> 想接上 §61.34 遗留的「`networkSourceAvailable` 无生产消费方」，核对后发现
> **离线事实早有生产实现**：`NetworkPlaybackPolicy` 的 `NetworkKind.NONE` 分支
> 经 `AndroidNetworkPlaybackGate`（ConnectivityManager）由 `PlayerController`
> 在播放前消费；C-16 的 `DegradationPolicy.offline` 是 UI 降级契约，两套口径有分叉风险。
> 本批新增 `isOffline(kind)` 作为**单一真相源** + `NetworkPlaybackPolicyTest`
> 遍历 4 种 `NetworkKind` 的交叉断言 + `check_source_contracts.py` §61.35 守护；
> **零产品行为改动，仍不伪造 UI 接线**（生产消费方依旧为空，如实登记）。
> 真机硬阻塞定性：仅无线 adb 时关 WiFi / 开飞行模式会失联且无法远程恢复
> （要关飞行模式又必须先有 adb），`pm grant` 缺 GRANT_RUNTIME_PERMISSIONS 且约定
> 不伪造授权 → 发布阻断 #10 与非 WiFi 负向 / ACL 属 **USB / 物理在场** 条件。
> `./gradlew test --offline` **419 测试 / 46 套件 / 0 失败**（416+C-16 的 2+C-17 的 1）、
> 契约自检五条守护 PASS、dev 6/6、release FAIL(C1,C4)。
> 本批把 §61.31~§61.35 五个批次按三组结构化**本地提交（未 push）**。
> **剩余收敛为需用户拍板**：G09 走自恢复兜底 / 独立进程隔离 / 接受 PARTIAL
> 为已知 OEM 限制（决定 A3 能否收口），其后 ABI 回填 + 冻结舱 + §61.33 施工单。
> A3 状态不变：G01~G08 PASS / G09 PARTIAL，舱不冻结、指纹不回填。

## 1. 当前进展（文档口径）

- 进度断点：`docs/12 §41`（第三十二批，音频焦点 + `ACTION_AUDIO_BECOMING_NOISY` 平台接线）；`09 §1/§2`、`16 §5.3` 同步到该批。
- 基线：19 模块 / 350 测试 · 37 套件 / 0 失败；自检 36/143/15；dev 6/6；release 仅 C1；C3 11 权限；C4 空表；APK 20 so。
- 剩余：主节点 4 个（T-L L1/L2/L3、T-V）、最小可听 2 个硬门槛（L1 真机 G01/G02 → T-V）。装手机能看能点、仍放不出真实声音。
- ⑦ 的 C4 口径（D-145）连续三轮未被选择，**发布基线保持不变**。

## 2. 关键发现：代码已领先文档一个批次

00:17 改动的 4 个文件已把焦点 / 断连从「直连 `PlaybackRuntime.pause()`」换到 `IMediaControl.onFocusChange(FocusChange.*)` 正式裁决链：

| 文件 | 改动 |
|---|---|
| `app/.../PlaybackService.kt` | 焦点常量映射 `FocusChange.*`；注入 `IMediaControl`；`onFocusChange` 派发 |
| `core-di/.../di/AppBindings.kt` | `provideMediaControl(runtime)` 改为 bind `RuntimeMediaControlHandle(runtime)` |
| `core-di/.../di/playback/RuntimeMediaControlHandle.kt` | 新增桥（`duck/unduck/seekRelative` 为 no-op） |
| `app/build.gradle.kts` | 新增 `implementation(project(":core-media"))` |

代码注释 **5 处**引用 `12 §42`，但 `docs/` 全库检索 `§42` /「第三十三批」**0 命中** → 悬空引用。

## 3. 差异清单（9 项）

| # | 级别 | 差异 | 证据 |
|---|---|---|---|
| 1 | **P0** | **DI 依赖环**：`provideMediaControl(runtime)` → `PlaybackRuntime` → `PlayerController` → `IMediaControl` → 回到起点。Dagger 编译期应报 `DependencyCycle`，该批代码**未证明可编译** | `AppBindings.kt:68-71`、`PlaybackComposition.kt:109-116, 216-232` |
| 2 | **P0** | **重复 bind + 桥冗余**：`PlayerController.init` 已 bind 内部 `HandleAdapter`，且它已实现 `duck/unduck`（`engine.setVolume`）与 `seekRelative`；新句柄三者 no-op，能力退化且互相覆盖 | `PlayerController.kt:49-51, 233-246`、`RuntimeMediaControlHandle.kt:35-40` |
| 3 | P1 | CAN_DUCK 经 `FocusPolicy` 只调 no-op `duck()` → **既不暂停也不降音量**；§41.2「三类 LOSS 一律暂停」与 `03-07 §4`「duck(0.25f)」均不满足 | `FocusPolicy.kt:41-45` |
| 4 | P1 | §41.2 / §41.5「GAIN 不自动恢复」「V0.1 直落 runtime.pause」「尚未 bind 真实 handle」与代码相反；`03-09 §8` 落地注、`09`、`16` 同步滞后 | `12 §41`、`03-09 §8:96-100` |
| 5 | P1 | 代码自相矛盾：类 KDoc 说未 bind、`:56` 说已 bind；`pauseFromExternal()` 成死代码；`AppBindings.kt:46` 仍写「待 T-L L2」 | `PlaybackService.kt:44-48, 56, 185-193` |
| 6 | P2 | `MediaSession.Callback` 仍直调 `runtime.*`，未通知 `onPlaying/onManualPause` → `03-07 §12 T03`「手动暂停 → GAIN 不恢复」失效；两方法只在内存实现、不在接口 | `PlaybackService.kt:109-120`、`MediaControlApi.kt:15-29` |
| 7 | P2 | GAIN 三变体一律压平为 `FocusChange.GAIN` | `PlaybackService.kt:76-78` |
| 8 | P2 | 零新增单测 / 零守护 / 零构建记录；`check_source_contracts.py:1164-1178` 只查 6 个字符串、`:1251` NOTE 过时 → **绿灯不代表接线正确** | 本轮实测 |
| 9 | P2 | `PlaybackComposition.kt:105` 仍写 `InMemoryMusicEngine`，与 §36 换绑 `EngineAdapter` 矛盾 | 源码注释 |

### 3.1 差异状态更新（T1 / T4 / T3 后，9 项全部处理）

- **#1 DI 依赖环 → 已解决**：`provideMediaControl()` 零参数；生成图 `SingletonC.java:905` 无参、`:860` 把同一 `@Singleton` 实例交给 `providePlayerController`、`:584` 注入 `PlaybackService`；静态守护防回归。
- **#2 重复 bind + 桥冗余 → 已解决**：删 `RuntimeMediaControlHandle.kt`，APK DEX 无该类；`HandleAdapter` 唯一句柄。
- **#3 duck 语义 → 静态链已通、真机待验**：`CAN_DUCK` → `HandleAdapter.duck(0.25f)` → `IMusicEngine.setVolume` → `EngineAdapter.setVolume` → `PlaybackEngine.setVolume`（native），**不是 no-op**；听感待 G01。
- **#4 / #5 → 已解决**：`PlaybackService` KDoc / 死代码、`AppBindings`、`PlaybackComposition.kt:105`、`03-09 §8`、`09 §1/§2/§7`、`16 §5.3`、`03-11 §1.1`、自检过时 NOTE 全部同步；`§41.2` 加「V0.1 旧语义作废」注。
- **#6 → 已解决（T4 / C-15）**：T03 生产链路成立；双注入守护覆盖隐式顺序依赖。
- **#7 GAIN 压平 → 已解决（T3）**：三个 `AUDIOFOCUS_GAIN*` 常量逐项映射三变体 + 2 单测 + 防压平守护。
- **#8 守护 → 已解决（T6）**：`check_source_contracts.py` 共 4 条长效守护（T1 / C-15 / T4 / T3）。
- **#9 → 已解决**：`PlaybackComposition.kt:105` 改为 `EngineAdapter` 口径。

## 4. 任务清单（T1~T7 全部收口）

| # | 优先级 | 任务 | 状态 / 验收 |
|---|---|---|---|
| T1 | P0 | 解环 + 去冗余桥 | **✅**：零参数绑定、删自建句柄；Hilt 图无环 |
| T2 | P0 | 真实构建验证 | **✅**：SDK 实为齐备（上轮误查），全量构建实证 |
| T3 | P1 | 焦点语义对齐 `03-07 §4/§12` | **✅**：GAIN 三变体逐项映射 + 2 单测 + duck 静态链核实 + 契约文档全同步 |
| T4 | P1 | 媒体会话改走门面 | **✅**：C-15 默认方法 + 状态回传 + 3 单测，T03 生产链路成立 |
| T5 | P1 | 清死代码与矛盾注释 | **✅**：最后一处 `PlaybackComposition.kt:105` 已改 |
| T6 | P1 | 补长效守护 | **✅**：4 条守护生效 |
| T7 | P1 | 回写正式交付批次 | **✅**：`12 §43/§44/§45`、`09 §1/§2/§7`、`16 §5.3`、`03-07 §3/§4`、`03-09 §8`、`03-11 §1.1` 全同步 |
| T8 | P2 | 原断点：⑦ C4 口径、L2 蓝牙 / `OutputRouter` 联动、L1 真机 G01/G02 与 T-V | 🟡 **⑦ C4 口径已由用户确认并在 `§47` 落地**（素材导出 + csv 61 行 + C4 新语义）；**L2 自研侧零权限部分已由 `§46` 完成**。剩余：① 61 项素材全量替换 + `:ui-legacy` 舱体 / R 引用批量改写（L3）；② 蓝牙 ACL 广播 + `BLUETOOTH_CONNECT` 运行时授权流程（L2）；③ 真机 G01/G02（唯一硬门槛） |

## 5. 验证与状态（§46 时点快照；§47 见交付报告）

| 检查 | 第三十三轮（核对） | T1 后 | T4 后 | T3 后 | **§46 后（当前）** |
|---|---|---|---|---|---|
| `./gradlew test :app:assembleDebug --offline` | NOT RUN（误判 SDK 不齐） | 1m33s | 42s | 32s | **BUILD SUCCESSFUL in 28s / 20s** |
| 测试 XML | — | 350 / 37 / 0 | 353 / 37 / 0 | 355 / 37 / 0 | **356 测试 / 37 套件 / 0 失败 / 0 错误 / 0 跳过**（+1） |
| APK DEX / so | — | 焦点链、20 so | +`onManualPause`/`onPlaying` | +`GAIN_TRANSIENT_MAY_DUCK` | +`BluetoothOutputPolicy`/`updateSettings`；仍不含 `RuntimeMediaControlHandle`；20 so |
| `check_source_contracts.py` | PASS 36/143/15（未覆盖新链） | 同 | +3 守护 | 4 条守护 | **PASS 36/143/15，共 5 条守护** |
| `pre_release_check.py` dev / release | PASS 6/6 | 6/6 / 仅 C1 | 6/6 / 仅 C1 | 6/6 / 仅 C1 | **PASS 6/6 / FAIL（仅 C1）** |
| guard / compileall | PASS | PASS / OK | PASS / OK | PASS / OK | PASS / OK，零个人路径 |
| Manifest / csv / docs 文件数 | 11 / 空表 / 30 | 同 | 同 | 同 | **11 权限（含既有 `BLUETOOTH_CONNECT`）/ 空表（C4 PASS）/ 30 个文件**，均未改 |

## 6. 回写记录

- 第三十三轮（核对）：`docs/12 §42`、`09 §1`、`16 §5.3`、当日日志、`MEMORY.md`（精简重写）。
- 第三十四轮（T1）：`12 §43`、`09 §1/§2`、`16 §5.3`、`03-09 §8`、任务清单、日志、`MEMORY.md`。
- 第三十五轮（T4）：`12 §44`、`09 §1/§2`、`16 §5.3`、`03-07 §3`、`check_source_contracts.py`、任务清单、日志、`MEMORY.md`。
- 第三十六轮（T3 收尾）：`12 §45`（含 §41.2 现行口径更正）、`09 §1/§2/§7`、`16 §5.3`、`03-07 §4`、`03-09 §8`、`03-11 §1.1`、`check_source_contracts.py`、任务清单、日志、`MEMORY.md`。
- 第三十七轮（§46）：`12 §46`、`09 §1/§2/§7`、`16 §5.3`、`03-09 §5/§8`、`03-11 §1.1`、`check_source_contracts.py`、`BluetoothOutputPolicy.kt`、`PlaybackService.kt`、`OutputRoutingContractTest.kt`、任务清单、日志、`MEMORY.md`。
- 第三十八轮（§47）：`12 §47`、`09 §1/§2/§7`、`16 §5.3`、`14` 顶部现行状态注、`06 §4`、新增 `tools/export_player_assets.py`、`tools/check-assets.py`、`tools/check_source_contracts.py`、`tools/check-no-netease-import.py`、`docs/assets-mapping.csv`（空表 → 61 行）、`app/src/main/assets/legacy-player/`（51 文件）、任务清单、日志、`MEMORY.md`。
- **本批按用户确认改了 `assets-mapping.csv` 与 C4 / dev / release 基线**；未改构建脚本 / Manifest / 模块依赖，零新增权限 / 依赖 / 模块。工作区仍未 commit / push。
- 第四十二轮（§51，2026-11-07）：`12 §51`（含文首结论）、`09 §1/§7`、`16 §5.3`、新建
  `app/src/main/kotlin/com/example/cloudmusic/MediaKeyAction.kt` 与
  `app/src/test/kotlin/com/example/cloudmusic/PlatformContractTest.kt`、
  `app/build.gradle.kts`（仅加 test 依赖）、`tools/check_source_contracts.py`
  （清单 143→145 + 6 组 §50 守护 + 3 组签名 + 2 条 NOTE）、任务清单、日志、`MEMORY.md`。
- **§51 验证基线**：`./gradlew test` **366 测试 / 39 套件 / 0 失败 / 0 错误 / 0 跳过**；
  自检 **36 / 145 / 15** PASS；dev **6/6**；release **FAIL(C1, C4)** 正确阻断；
  guard / py_compile OK；19 模块 / 20 so / 11 权限 / 61 行 csv / 51 素材全部未变。
- **§42.3 的 T8 仍未收口**：`createDataRequestHandler()` 取流句柄、事件码常量表、
  真机 G01~G09、61 项素材全量替换、蓝牙 ACL 广播授权、DLNA 输出切换触发点，
  均需真机实证或用户决策；§51.4 已给出取流链路的逐条真机判据。工作区仍未 commit / push。
- 第四十三轮（§52，2026-11-07）：`12 §52`（含文首结论）、`09 §1/§7`、`16 §5.3`、新建
  `app/src/main/kotlin/com/example/cloudmusic/AppActionContracts.kt` /
  `PermissionRequester.kt` / `LocalSourceAdder.kt`、
  `app/src/test/kotlin/com/example/cloudmusic/AppActionContractTest.kt`、
  `app/.../MainActivity.kt`、`app/.../ui/CloudMusicApp.kt`、`app/build.gradle.kts`
  （仅加 `testImplementation`）、`tools/check_source_contracts.py`
  （清单 145→149 + 5 组 §52 守护 + 4 组签名 + NOTE）、任务清单、日志、`MEMORY.md`。
- **§52 验证基线**：`./gradlew test` **380 测试 / 41 套件 / 0 失败 / 0 错误 / 0 跳过**、
  `:app:assembleDebug` BUILD SUCCESSFUL；自检 **36 / 149 / 15** PASS；dev **6/6**；
  release **FAIL(C1, C4)**；guard / py_compile OK；11 权限 / 19 模块 / 20 so /
  61 行 csv / 51 素材全部未变。
- **§50.7 #3 已收口（应用侧）**：真机「曲库 0 首 / Room 夹具」前提移除，
  复验判据见 `12 §52.4`。**§50.7 #1（取流句柄）与 #4（G01~G09）未动**；
  蓝牙 ACL 授权 / 61 项素材替换 / DLNA 切换触发点仍需决策或真机。
  第四十二、四十三批工作区仍未 commit / push。
- 第四十四轮（§53，2026-11-07，**只读实证轮**）：`12 §53`（含文首结论）、`09 §1/§7`、
  `16 §5.3`、新建 `analysis/data-request-handler-evidence-2026-11-07.md`、
  `engine-legacy/.../EngineAdapter.kt`（**仅注释**，行为仍 `createDataRequestHandler() = null`）、
  `tools/check_source_contracts.py`（4 组 §53 守护 + NOTE + docstring，清单仍 36/149/15）、
  任务清单、日志、`MEMORY.md`。反汇编产物在系统临时目录，未入库。
- **取流句柄结论**：`§50.6` 的「`IBaseDataSource` 或等价句柄」方向收窄为
  **`SourceDataRequest` 子类实例**；`Start(JJJ)J` / `Stop()` 契约、
  `libNMCommonCache.so` 的 5 个 JNI 符号、`NMCommonCache` 加载链全部核实。
  **不缺类不缺库**，`null` 正是播放期零 GET 的原因。
- **§53 验证**：`check_source_contracts.py` **PASS 36/149/15**（含 so 二进制符号扫描）；
  **零生产行为改动 / 零构建基线变化**：19 模块 / 11 权限 / 20 so / 61 行 csv /
  51 素材 / 380 测试均不变；Gradle 本轮 NOT RUN（§52 基线仍有效）。
- **仍未实证**：`Start` 三个 long 的语义、`OnDataRequest*` 参数含义、
  native 是否额外按名查找 `C(JII)V` / `H()Z`。实现规格见报告 §4，
  分阶段真机判据 A~D 见 §5；**不得凭猜测实现**，否则只是再伪装一次 `DEEP RUN`。
  第四十二~四十四批工作区仍未 commit / push。
- 第四十五轮（§54，2026-11-07，取流句柄阶段 A/B 落地）：`12 §54`（含文首结论）、
  `09 §1/§7`、`16 §5.3`、新建 `engine-legacy/.../RangeFetcher.kt` / `UrlDataRequest.kt` /
  `RangeFetcherTest.kt`（7 用例）、`engine-legacy/.../EngineAdapter.kt` 接线、
  `engine-legacy/build.gradle.kts`（仅 test 依赖）、
  `analysis/data-request-handler-evidence-2026-11-07.md`（§2.5 / §3 / §4 / §5 更新）、
  `tools/check_source_contracts.py`（§53 守护改口径 + 5 组 §54 守护 + 3 组签名 +
  NOTE，清单 **149 → 152**）、任务清单、日志、`MEMORY.md`。
- **§2.5 追加实证**：`Start(j1=请求标识 / j2=offset / j3=length)`，
  `end=j2+j3`，`j1` 原样作 `OnDataRequest*` 首参；`Start` 立即返回 0、异步取流。
  报告 §3 的未决项收窄为 `OnDataRequestData` 第 2/3 个 long 与 End 的 String。
- **§54 验证基线**：`./gradlew test` **394 测试 / 43 套件 / 0 失败 / 0 错误 / 0 跳过**
  （380 + `RangeFetcherTest` 7×2 变体）；自检 **36 / 152 / 15** PASS；dev **6/6**；
  release **FAIL(C1, C4)**；guard / py_compile OK；19 模块 / 11 权限 / 20 so /
  61 行 csv / 51 素材 / 指纹全部未变。
- **§50.7 #1 阶段 A/B 代码已具备，仍未真机验证**：判据见报告 §5 / `12 §54.5`
  （`InitPtr` / `SetDataRequest` 是否成功、播放期是否出现带 Range 的 GET、
  `active tracks ≥ 1`、是否 `OnWriteNetData SKIP`）。**编译通过 ≠ 出声**，
  A3 保持 `DEEP RUN`、舱不冻结。事件码常量表 / 61 项素材替换 / 蓝牙 ACL 授权 /
  DLNA 切换触发点 / G01~G09 仍待。第四十二~四十五批工作区仍未 commit / push。
- 第五十五轮（§55，2026-11-07，**真机验证轮，零源码改动**）：`12 §55`（含文首）、
  `09 §7`、`analysis/a3-verification-runbook-2026-11-07.md`（§0 已更正）、任务清单、日志、`MEMORY.md`。
  重新 `./gradlew :app:assembleDebug` BUILD SUCCESSFUL（APK 20:54 / 30 MB，含 §54 代码），
  `zipfile` 校验 20 dex / 20 arm64 so 中取流类全部在位。
- **首次判断错误已纠正（用户指出「手机还是同一个」）**：`adb devices` 只列出乐视电视
  `Letv Y43`（API 19 / armeabi-v7a / `characteristics=tv` / `com.letv.*`），
  手机无线端点断开未重连；`adb connect 192.168.1.11:39525` → **Redmi K60
  `23013RK75C / mondrian` / Android 13 / API 33 / arm64-v8a**，完全兼容。
  所有设备命令带 `-s`，电视未被触碰；未切网络、未 `pm grant` 伪造授权。
- **签名不匹配 → 授权重装**：`apksigner` 对比旧包 `5474f219…` / 新包 `59f9c4cc…`
  （两套 debug.keystore）→ `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；用户授权后
  `uninstall` + `install` Success（21:05:49）。恢复 §50 测试夹具（新库保留
  `room_master_table`，`tracks/queue_items/playback_state=1/1/1`，明确标注为夹具），
  `adb reverse tcp:8765` + PC `python -m http.server 8765`，设备 curl → 200 / 37824 B。
- **首次实跑结果**：**阶段 A PASS**（native 全链 `JNI_OnLoad → PlaybackEngine →
  setDataSource → PrepareAsync → BufferSource open → openSource 1`，crash 空、
  无 CheckJNI / `NoSuchMethodError`）；**阶段 B FAIL**（`Play status 9` 后 PC 音源
  **播放期零 GET**、`0 active tracks`、MediaSession `state=2`、logcat 无
  `SetDataRequest` / `Request Start`）→ §53 类型结论正确，但**调用链仍缺竞品 Java 侧
  `NMBufferSource.SetDataRequest` 接线**（或 `UrlResult` 字段 / `SourceConfig.type` 判定走本地分支）。
- **下一步**（`12 §55.4`）：① 加诊断 `Log.i` 到 handler/Start/fetch 后 `install -r`
  （签名已统一、夹具保留）确认 native 是否调用；② 反汇编 `DataSourcePlatformImpl` 找
  `SetDataRequest` 接线；③ 校准 `UrlResult` / `SourceConfig.type`；④ 出现 GET 后才进 C/D。
- **仍未出声**：A3 保持 `DEEP RUN`、舱不冻结、指纹不回填，C/D 与 G01~G09 仍 NOT RUN。
- 构建基线不变：394 测试 / 43 套件、自检 36/152/15、dev 6/6、release FAIL(C1,C4)、
  19 模块 / 11 权限 / 20 so / 61 行 csv。第四十二~四十五批工作区仍未 commit / push。
- 第四十六轮（§56，2026-11-07，复核 + 真机诊断）：`12 §56`（含文首结论）、`09 §7`、
  `16 §5.3`（补 §55/§56 条目）、`02 §7.4` 口径澄清、新建
  `analysis/review-and-diagnosis-2026-11-07.md`、
  `analysis/a3-verification-result-2026-11-07.md §7`、
  `analysis/data-request-handler-evidence-2026-11-07.md §4 修正 + §7`、
  `EngineAdapter.kt` / `UrlDataRequest.kt` / `SourceCallback.java`（`CM-DataReq` 诊断探针）、
  `ResourceInfo.java` / `PlayerDataSource.java`（带参重载 + extension 校准）、
  `tools/check_source_contracts.py`（§50 P0-5 守护改前缀匹配）、任务清单、日志。
- **行尾归一**：11 个 M 文件被整体 LF→CRLF，已按 HEAD 统一回 LF，
  `git diff --stat` 由 +7111/-6065 收敛为真实 **+1061/-15**；未新增 `.gitattributes` 文本规则。
- **文档↔代码 9 项复核**：`01 §9` 权限 / `01 §5.1` SAF / `02 §4.3` 门面一致；
  修正 `evidence §4` Range 公式（j1 误作 offset）、`02 §7.4` 字节流措辞、`16 §5.3` 补录；
  登记 `01 §6.1`「默认仅 WiFi 播放」未实现（挂账）。
- **真机两轮实证**：证伪 §55.4 方向 1 —— smali5 的 `SetDataRequest` 仅见于
  `NMBufferSource.smali`，且 native **确实调用** `createDataRequestHandler()` 并成功建句柄；
  但 `getUrl()` / `Start()` 全程未调用，prepare 阶段 `BufferSource open` 后回调
  **`SourceCallback.onError code=1001`**，随后 `msg=300 arg1=9` / `Play status 9`。
  `ResourceInfo.extension=mp3` 校准实验未改变结果（假设排除）。
  播放期零 GET / 0 active tracks / MediaSession state=2 三项判据仍为否。
- **§56 验证基线**：自检 36/152/15 PASS、`./gradlew test` **394 / 43 套件 / 0 失败**、
  `:app:assembleDebug` BUILD SUCCESSFUL（两版）、APK 20 dex / 20 so 含诊断字符串；
  dev 6/6 与 release FAIL(C1,C4) 本轮未复跑；19 模块 / 11 权限 / 20 so / 61 行 csv 零变化。
- **下一步（按证据顺序）**：① 查 `onError(1001)` 的 native 码表；② 核对
  `PlaybackEngine(0L)` 的 `cache=0x0` 与 `CreateBufferSource` 前置条件；③ 核对
  `IDataSourcePlatform` / `IPlaybackPlatform` 空壳是否必须注册；④ 校准
  `UrlResult.fileSize=0` 与 REMOTE 分支；⑤ 播放期出现带 Range 的 GET 后才进 C/D、G01~G09。
  **仍未出声**，A3 保持 `DEEP RUN`、舱不冻结、指纹不回填。
  第四十二~四十六批工作区仍未 commit / push.
- 第四十七轮（§57，2026-11-07，阶段 A/B 首次通过）：`12 §57`（含文首）、`09 §7`、
  `16 §5.3`、`analysis/a3-verification-result-2026-11-07.md §8`、
  `analysis/review-and-diagnosis-2026-11-07.md §7`、新建
  `analysis/a3-phase-ab-evidence-2026-11-07.md`、
  `EngineAdapter.kt`（VFM handle / `resolveLength` / 真实长度 / release 释放）、
  `NMCacheBase.java`（`getNativePtr()`）、`AppBindings.kt`（`@ApplicationContext`）、
  `AndroidManifest.xml`（`usesCleartextTraffic="true"`）、
  `tools/check_source_contracts.py`（两处 DI 守护改前缀）、任务清单、日志。
- **静态实证**：`capstone` 反汇编 + ELF 符号表把 `onError(1001)` 定位到
  `NMBufferSourceAdapter::Open()` 的多个失败分支（通用码）；jadx 真类证明
  `PlaybackEngine(long cacheManagerHandle)` 需要 VFM 的 C++ 句柄。
- **四轮真机实验**：E1 传真实 `cacheManagerHandle` → `onError(1001)` 消失、
  首次 `getUrl` / `CreateBufferSource`，但 `size:0` → Memory Mode / `openSource 2`；
  E2 HEAD 探测失败（Android HEAD 的 `contentLengthLong` 恒 -1）；
  E3 加 `usesCleartextTraffic="true"` → HEAD 首次到达音源（此前 App 内 http 被拦）；
  E4 后台线程读 `Content-Length` + `ResourceInfo`/`UrlResult` 同填 37824
  → **`Start id=1 offset=0 length=37824`、播放期带 Range 的 GET、
  `Decoder Open input file success`、`openSource 0`**。
- **四阶段**：A PASS、B PASS（首次）、C 未通过（`OnWriteNetData Failed Not Support` ×15）、
  D 未确认（active track 不稳定、MediaSession 恒 `state=2`）。**仍未出声**。
- **基线**：自检 36/152/15 PASS、394 测试 / 43 套件 / 0 失败、assembleDebug SUCCESSFUL、
  C3 PASS（11 权限）；19 模块 / 20 so / 61 行 csv / 51 素材零变化。
- **下一步**：① 反汇编 `OnWriteNetData Failed Not Support` 分支与 `BufferSourceStrategy`；
  ② 校准 `OnDataRequestData` 第 2/3 个 long（`SKIP offset/size/file_offset_` 判据）；
  ③ 核对 `IDataSourcePlatform` / `IPlaybackPlatform` 空壳；④ 按 `13 §7.1` 补事件码状态机
  （`msg=300 arg1=4`、`msg=200/7/8/101/6002`）；⑤ C/D 通过后才跑 G01~G09。
  A3 保持 `DEEP RUN`、舱不冻结、指纹不回填。第四十二~四十七批仍未 commit / push.
- 第四十八轮（§58，2026-11-07，阶段 C 反汇编定位）：`12 §58`（含文首）、`09 §7`、
  `16 §5.3`、`analysis/a3-phase-ab-evidence-2026-11-07.md §7`、新建
  `analysis/a3-stage-c-analysis-2026-11-07.md`、`SourceConfig.java`（实验回退注释）、
  `EngineAdapter.kt`（探测健壮性 + 注释）、任务清单、日志。
- **反汇编实证**：`NMBufferSourceImp::OnWriteNetData`(0xbcac/564) 的
  `Failed Not Support` 唯一条件 = `this+0xb8` 为 0；构造函数(0x9164/1012)
  `strh #0x101` 初值置 1，只在 `0x942c` 的 Memory Mode 分支清零；
  条件为 `BufferSourceStrategy`(x5) bit0=0 或 `NMVirtualFile`(+0xd8) 为空。
  `Java_..._OnNativeDataRequestData`(0x347f4) 用末参 long 作 buffer size。
- **E5 证伪并回退**：`cacheLocal=true` → `NMSliceFile Open Failed. Root Dir Not Init.` /
  `Open failed(-10), local cache unavailable` / `size:-1` / 零 Start / `openSource 2`；
  `SetConfig` 有 `Dir Empty` 校验，`01 §6.1` 明确「缓存不落盘」→ 已回退 `false`。
- **现场网络问题**：`SocketTimeoutException: failed to connect to /127.0.0.1:8765`，
  设备 curl 仍 200；重设 `adb reverse`、换 `ThreadingHTTPServer`、改 GET/Range/
  显式 `responseCode`/8s 均无效 → App 进程侧可达性问题，非协议回归。
  代码恢复 §57 E4 的 HEAD 形式，只保留 `responseCode` + 8s 健壮性增强。
- **基线**：自检 36/152/15 PASS、394 测试 / 43 套件 / 0 失败、assembleDebug SUCCESSFUL、
  dev 6/6 + C3 PASS；19 模块 / 11 权限 / 20 so / 61 行 csv 零变化；本轮无生产行为变化。
- **第四十九批（2026-11-08，`12 §59`）路线 1 证伪 + kind 槽校准 + E9**：
  ① 竞品 `getResourceInfo()` 仅 4 字段构造、`libncmplayerengine` 硬编码拼
  `{"id","md5","size","type":"cache"}`（type 无分支）、策略 bit0 = cache config 的
  `cache_local` → §58 路线 1 前提不成立；② `OnDataRequestData` 首 long 是
  `OnNetSourceData(int kind,…)` 的 kind 槽（==2 跳过 `NMMemoryCache::Write`），
  `OnWriteNetData`/`OnFlushNetData` 经 GOT+lambda 异步调用（解开 §58"零调用方"矛盾）；
  数据包 kind 恒传 1（2 组 §59 守护）。真机 E9：prepare 全通 / 解码器打开 MP3 /
  AudioTrack 创建 / 首段 24576B 解码；尾段部分读恰逢 EOF 触发 Memory Mode FIFO
  管道永久错位（×7 → -103 → `doDecoder -5`），native 级缺陷、Java 侧不可修复。
- **下一步（唯一可行路线须拍板）**：路线 2 = `NMVirtualFileManager.SetConfig(<App
  缓存根目录>)` + `cacheLocal=true`，与 `01 §6.1`「缓存不落盘」冲突，**须用户拍板**；
  口径内备选 = LOCAL 文件源（适配器 `+0x48==0` 分支），但需先实证 native 对
  `content://` URI / `openLocalFd` 的处理（现 slot 返回 -1），成本更高、暂列观察。
  判据：不再 `Start Use Memory Mode` / `Failed Not Support`，播放窗口稳定
  `active track ≥ 1` 且 MediaSession 非 `state=2`。
- **第五十批（2026-11-08，`12 §60`）口径内 LOCAL 文件源真机链路打通**：DataStore 种子
  + 夹具切换 + LocalDriver 纯路径回退零代码打通（免权限免缓存），引擎 `CreateLocalSource`
  → `XorLocalSource`（NoneDecryptor=ret 空透传）probe/codec 对 mp3/wav 双格式全过，
  但解码首包即 EOF（`0xDFB9B0BB` → EOS，格式/大小无关）；排除解密器/AVIO 回调/Size/
  EncryptedType 变体，根因收敛到 libncmaudioplayer 的 source 包装层（裸 read(fd) 无
  位置串行化，与 E9 的 FIFO 错位同族）。注意：真实 SAF/MediaStore 曲目 localPath 是
  `content://`，届时需实现 `openLocalFd`（现 slot 返回 -1，已登记）。
- **60 批续（AudioSourceAndroid 排除）**：该类无 DataProvider JNI 字符串（非本地文件
  通路）；`CNCMAudioLocalSource`（fopen/fread 版）本库无虚表 = 死代码；解码器 AVIO
  opaque 直连源对象。**LOCAL 路线判死（该 so 版本）**：fd 顺序位置被流层预读与解码
  并发竞争，`SetSourceMode` 为空桩，无 Java 侧杠杆。
- **第五十一批（2026-11-08，`12 §61`）用户拍板批准路线 2 并落地**：
  `cacheLocal=true` + `SetConfig`（键名 dir/max_size/slice_size/cache_clean_range
  反汇编钉死）；真机 E11/E11b 缓存管线物理全通（切片文件 37824 字节落盘、
  三历史症状全消、E9 尾段错位消失）；docs/01 §6.1 口径演进批注落档。
- **61 批续（虚表布局错位发现）**：解码器 AVIO 回调期望 INCMSourceIO 布局
  （Seek@+0x28/Size@+0x50），而 NMBufferSourceImp 是 INCMSourceI 布局
  （+0x28=Size、+0x50=SourceID）—— seek 回调命中 Size()、假成功真不 seek →
  首包 EOF（`0xDFB9B0BB`）。0x70 包装类已排除（handler 控制块）。
- **下一步**：定论适配器产物（[x21+0x28]）与 decoder+0x128 的赋值链——若裸 imp
  直连，则竞品必有 IO 适配环节（候选：VFM 产品本身/ player 层包装），照它补齐
  即可打通解码；路线 2 缓存层已全通（无需再动）。
  判据不变：不再 `Start Use Memory Mode` / `Failed Not Support`，播放窗口稳定
  `active track ≥ 1` 且 MediaSession 非 `state=2`。
  判据不变：不再 `Start Use Memory Mode` / `Failed Not Support`，播放窗口稳定
  `active track ≥ 1` 且 MediaSession 非 `state=2`。
  **仍未出声**；A3 `DEEP RUN`、舱不冻结、指纹不回填。第四十二~五十批仍未 commit / push。

- **61 批续（E12 系列 + 灭屏冻结 + 缓存命中缺陷）**：解码循环在 prepare 即消费完
  整小文件并 EOS（1008ms/4704ms 即中止，Play 后至为空操作）；连续 5 轮被灭屏冻结
  打断（Dozing + GreezeManager，屏幕无法远程唤醒）；发现磁盘缓存命中路径读 0 字节
  （.nmsf 37824B 在盘但 readed_len:0）。下轮：无 force-stop 周期验证缓存命中；
  .nmsfi 提交差异；若索引未提交则评估 Stop 提交路径。缓存命中可用则播放免网络。
- **61 批末（缓存命中缺陷确认 + 设备离线）**：E11b 日志已复现缓存命中读 0 字节
  （索引加载成功、数据读失败 result:-2）；设备无线 adb 掉线（灭屏深睡）阻塞 .nmsfi
  分析。**需用户物理唤醒/解锁设备**后继续：拉取 .nmsfi 分析切片表 → 定位索引/数据
  不一致根因 → 修通缓存命中（播放免网络，免疫灭屏冻结）。
- **61.8（Java 侧修复落地）**：EngineAdapter 收到 msg=100（native 错误通知）→
  ERROR + 异步 releaseAllInternal（imp Close → 索引提交 + slot 清理）；修复"错误后
  slot 残留导致下次 prepare invariant 崩溃"与"索引不提交导致缓存命中读 0 字节"。
  E14 待设备重连：验证错误后索引提交 → 二次播放缓存命中（免网络出声）。
- **E14（缓存命中物理全通）**：免网络二次播放 `ReadFromFileNoLock readed_len:32768
  + 5056` 完整从磁盘缓存提供；`.nmsfi` 为 JSON 格式；`openSource 0` prepare 成功；
  解码仍 `-541478725` EOS —— **与源/数据管线完全无关**（三模式同点复现），
  100% 位于 CNCMFFDecoder 层。下轮从解码器内部定位（mp3 demuxer seek 行为、
  packet buffer、或换格式测试）。
- **E15（WAV 隔离实验）被灭屏冻结打断**：需保持亮屏重试。WAV 简单 demuxer
  若可解码则隔离 mp3 特定行为；若同败则确认 AVIO 胶水层通用缺陷。
- **61.12（首包 EOF 根因定论）**：`0xDFB9B0BB` 是 CNCMFFDecoder 内部 EOF 标记；
  mp3 demuxer find_stream_info 消费到 EOF 后 seek-back 未正确回退 → 首包 EOF →
  1008ms PCM 即中止。静态分析已到边界，需运行时调试。缓存管线（路线 2）已完整
  验证无需再动。修复方向：① 大文件测试（>4.7s，PCM 缓冲在 EOF 前填满）；
  ② 换格式隔离；③ 反汇编 openWithSource post-find_stream_info 的显式 seek。
- **E16（37.6s 长文件）推翻"文件太短"假说**：302592B (37.6s) 仍仅 1008ms PCM
  即停——Read(2)+ 返回 0。根因确认为 CNCMFFDecoder 的 decode loop 固有行为，
  与源模式/文件大小/格式无关。需运行时调试（gdb/插桩）定位
  ffmpeg demuxer 在该 AVIO 配置下的 packet 解析失败点。
- **🎉 E16b/E17 判据 D 出声确认**：引擎真机完整播放 mp3 —— AudioTrack Write +
  doRenderFast + PCM 进度 0→4132ms + doPlayCompleted + msg=2。两次独立复现。
  `-541478725 = AVERROR_EOF`（正常 EOF，非错误）。A3 A/B/C/D 全部 PASS。
- **E19（MediaSession routing + AudioTrack 活跃渲染确认）**：亮屏状态
  `Media button session` 正确设置 + `1 are active` —— 全链正常。灭屏时 MIUI
  GreezeManager 冻结 —— 产品需 FGS 保活（已有 mediaPlayback FGS）。下轮：
  灭屏播放测试 + MediaSession PlaybackState 更新（PLAYING 状态同步）。
- **🎉 E20 判据 D 四重验证确认（§61.18）**：亮屏 Awake 状态 Media button session
  设置 ✓ / AudioTrack 活跃渲染（1 are active）✓ / PCM 写入 ✓ / 进度 0→4363ms ✓ /
  doPlayCompleted ✓ / PlaybackState 回退 ✓。四轮独立复现稳定。**判据 D 出声确认。**
  下轮：① MediaSession PlaybackState 更新（PLAYING 同步）；② 灭屏播放测试；
  ③ exported=false 恢复；④ G01~G09 验收。
- **🎉 E21 三重同时验证（§61.20）**：Media button session 设置 ✓ +
  PlaybackState state=3 (PLAYING，61.19 修复生效) ✓ + AudioTrack 活跃渲染 ✓ +
  播放进度 0→4357ms ✓ + doPlayCompleted ✓。**判据 D 三重维度确认，A/B/C/D 全部 PASS。**
  下轮：① 灭屏播放测试（FGS 保活）；② exported=false 恢复；③ G01~G09 验收。
- **E22（完整播放周期确认）**：PlaybackState state=3 (PLAYING) 持续 ✓ +
  播放位置 0→4357ms（93%）+ ~4.5s 自然完成 + active tracks 归零 ✓。
  61.19 修复生效（MediaSession 正确显示播放态）。完整周期：prepare→decode→render→complete ✓。
  灭屏测试需更长文件（4.7s 在灭屏前已播完）。
- **🎉🎉🎉 E23（37.6s 完整播放成功 — 判据 D 不可争议确认）**：8 倍拼接 mp3
  (302592B) normal mode 完整播放 37.6 秒——位置 0→37478ms（99.2%）、零中断、
  自然完成（bufferFill EOS → doPlayCompleted）。**推翻"1008ms 即停"假说**——
  4.7s 文件太短（< PCM 初始缓冲窗口），37.6s 文件正常持续到末尾。
  **判据 D 不可争议确认。A/B/C/D 全部 PASS。无需进一步验证。**
