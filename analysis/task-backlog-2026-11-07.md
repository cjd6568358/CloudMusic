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
