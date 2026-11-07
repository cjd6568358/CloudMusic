# 第三十七批交付与验收 · T-L L2 断连裁决链生产接线（2026-11-07）

> 用户「继续」。⑦ 的 C4 口径仍未被选择、真机 / adb 不可用 → 不碰发布基线，
> 挑 `docs/12 §45.5` 挂账的**零权限、零决策风险**自研侧项。完整断点见 `docs/12 §46`。

## 发现：策略早已完备，只缺生产接线

`output-router/.../BluetoothOutputPolicy.kt`（第八批落地，15 个单测 T01~T09 / T15 / B01~B10）
已实现 `03-09 §5` 的全部裁决：`autoPauseOnDisconnect` 开关、`activeOutput == DLNA` 豁免、
`pauseInFlight` 连续断开去重，唯一暂停出口是 `IMediaControl.onFocusChange(LOSS)`。
但它**只被单测使用**；`PlaybackService.noisyReceiver` 一直无条件暂停并弃焦点，
绕过了整条裁决链 —— 这是 `§45.5` 登记的 L2 缺口。

## 改了什么

| 文件 | 改动 |
|---|---|
| `output-router/.../BluetoothOutputPolicy.kt` | `private val settings` → `private var`；新增 `updateSettings(next)`（只替换配置、保留 `pauseInFlight` 去重状态） |
| `app/.../PlaybackService.kt` | 注入 `SettingsAccess` / `OutputRouter`；`onCreate` 建策略（`permissionGranted=false`）；`noisyReceiver` 每次读 `settings.current().playback` 刷新配置后 `onEvent(BECOMING_NOISY)`，**仅 `pauseDispatched` 时**弃焦点；`onPlay` 调 `notifyPlaybackResumed()` 复位去重 |
| `output-router/src/test/.../OutputRoutingContractTest.kt` | +1 单测：`updateSettings` 热更新（开→关→开）且去重状态不丢 |
| `tools/check_source_contracts.py` | +1 条 §46 守护（8 个 needle） |

**现行断连链路**：`ACTION_AUDIO_BECOMING_NOISY`（动态 receiver，无需权限）→
读当前设置并 `updateSettings` → `BluetoothOutputPolicy` 按「开关 && LOCAL && !in-flight」裁决 →
命中才 `IMediaControl.onFocusChange(LOSS)` + `abandonAudioFocus()`；否则只清蓝牙信息、不暂停不弃焦点。

## 权限口径澄清（重要）

`app/src/main/AndroidManifest.xml:18` **本来就声明了 `BLUETOOTH_CONNECT`**（`neverForLocation`，
既有 11 项权限之一），不是本批新增。V0.1 **未做运行时动态申请、未接蓝牙 ACL 广播**，因此按
`03-09 §8`「未授予时降级」把 `permissionGranted` 设为 `false`：`onEvent` 只放行
`BECOMING_NOISY`（B01 / T07），`DISCONNECTED` / `ADAPTER_OFF` / `CONNECTED` 一律 `NO_OP`。
**C3 仍 11 项、零新增权限 / 依赖 / 模块**。

## 验证（真实构建）

| 检查 | 结果 |
|---|---|
| `./gradlew test :app:assembleDebug --offline` | **BUILD SUCCESSFUL in 28s**（首次）/ **20s**（权限措辞修正后复跑）；240 tasks |
| 测试 XML | **356 测试 / 37 套件 / 0 失败 / 0 错误 / 0 跳过**（`test` 27/236 + debug 5/60 + release 5/60），较 §45 **+1** |
| APK | DEX 含 `BluetoothOutputPolicy` / `updateSettings` / `BECOMING_NOISY` / `GAIN_TRANSIENT_MAY_DUCK`；不含 `RuntimeMediaControlHandle`；20 个 so |
| Manifest | **11 项 uses-permission**（含既有 `BLUETOOTH_CONNECT`，零新增） |
| `check_source_contracts.py` | PASS **36/143/15**，累计 **5 条**长效守护 |
| dev / release | **PASS 6/6** / **FAIL（仅 C1）**，既有 3 个实证不变；C4 空表 PASS |
| guard / compileall | PASS / OK，零个人路径 |

## 诚实边界

- **只接 BECOMING_NOISY 降级路径，L2 整体仍未收口**：蓝牙 ACL `STATE_DISCONNECTED` /
  `ADAPTER_OFF` / `CONNECTED` 广播、设备名上报、`OutputRouter` 主动联动，仍需
  `BLUETOOTH_CONNECT` **运行时动态申请 + UI 授权流程**（权限弹窗与拒绝降级矩阵属需用户决策项）。
- `onSkipToNext/Previous` 仍直调 `runtime.*`；`03-08` 切 DLNA 的 `setActive(DLNA)` 生产调用方待核；
  真机 T10/B09 / duck 听感 / G01/G02 仍待。
- **零新增权限 / 依赖 / 模块**；⑦ 的 C4 口径（D-145）仍未被选择，发布基线未动。
- **编译 / DI / 静态验收通过 ≠ 真机出声**。

## 任务进度与断点

`§42.3` 的 T1~T7 已收口；本批完成 T8 中「L2 自研侧零权限部分」，T8 其余仍待：
① ⑦ 的 C4 口径决策；② 蓝牙运行时授权流程（L2）；③ 真机 G01/G02（唯一硬门槛）。

剩余主节点仍 **4 个**（T-L L1 / L2 / L3、T-V），最小可听仍 **2 个硬门槛**（L1 真机 G01/G02 → T-V）。
装手机能看能点、仍放不出真实声音。工作区仍未 commit / push，`docs/` 仍 30 个文件。

## 回写记录

`docs/12 §46`、`docs/09 §1/§2/§7`、`docs/16 §5.3`、`docs/03-09 §5/§8`、`docs/03-11 §1.1`、
`analysis/task-backlog-2026-11-07.md`、当日工作日志、`MEMORY.md`。
**未改** csv / 构建脚本 / Manifest。
