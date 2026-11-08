# G09 恢复链路复测 · 真机证据与发布阻断盘点（2026-11-08 晚）

> 承接 `docs/12 §61.31` 剩余断点①「USB/亮屏复测 G09（START_STICKY 恢复）」。
> 本轮设备**亮屏**、无线端点在线，是此前缺的条件；结论**纠正了旧归因**，但**不判 G09 PASS**。

## 0. 现场与方法

| 项 | 值 |
|---|---|
| 设备 | Redmi K60 / `23013RK75C` / Android 13 (API 33) / `arm64-v8a` / `192.168.1.11:37763` |
| 电源 | `mWakefulness=Awake`（**亮屏**全程），非灭屏 / Dozing |
| 起点 | `dumpsys package` = `stopped=true`、无进程、无服务；`dumpsys activity services` = nothing |
| 音源 | `adb reverse tcp:8765 tcp:8765` 保留，设备侧 `curl` 得 **200**（`cloudmusic_g01.mp3`，302592 B） |
| Room | `cloudmusic.db` + wal/shm 在位（13:50 夹具）；设备无 `sqlite3` 可执行权限 |
| 命令 | 全部带 `-s`；未切网络、未 `pm grant`、未重装、未改 Manifest / 源码 |

注入手段：`am crash <pid>`（Android `CrashedByAdbException`，`logcat -b crash` 有 FATAL 行）。
**边界**：这是 Java 层 shell 注入，不是 native SIGSEGV；进程退出等价，但引擎级 crash 标记 /
tombstone 路径未覆盖（`03-06 §12.6`）。

## 1. 桌面启动实测（发布前实际使用路径）

| 命令 | 结果 |
|---|---|
| `am start -a MAIN -c LAUNCHER -p com.example.cloudmusic` | **失败**：`unable to resolve Intent` |
| `cmd package resolve-activity --brief -a MAIN -c LAUNCHER -p …` | **成功解析** `com.example.cloudmusic/.MainActivity` |
| `am start -n com.example.cloudmusic/.MainActivity` | **成功**，pid 20595、`topResumedActivity` = MainActivity |
| `monkey -p com.example.cloudmusic -c LAUNCHER 1` | **成功**，`Events injected: 1` |

→ 第一条失败是 `am` 该参数组合在 stopped 包上的解析行为，**不是 LAUNCHER 声明缺陷**：
`resolve-activity` 能解析、显式组件与桌面点击都能启动。Manifest 的
`MainActivity exported=true + MAIN/LAUNCHER` 形态可用（`§61.24` 验收期调试形态）。

## 2. 崩溃前基线（19:54~19:55）

`MainActivity.onStart()` 拉起 FGS，`dumpsys activity services` 显示：

```
ServiceRecord … com.example.cloudmusic/.PlaybackService
  isForeground=true foregroundId=1001
  startRequested=true delayedStop=false stopIfKilled=false callStart=true lastStartId=2
  allowStartForeground=PROC_STATE_TOP   createdFromFg=true
```

`ACTION_PLAY` 后 `dumpsys media_session`：`state=PlaybackState {state=3 (PLAYING), position=0, …}`；
logcat 有 `createDataRequestHandler url=…cloudmusic_g01.mp3` / `Play status 4`。
`callStart=true / stopIfKilled=false` 与源码 `PlaybackService.kt:301 return START_STICKY` 一致，
**代码侧返回值正确**。

## 3. 实验 A：前台态崩溃 → 100ms 自动恢复（19:55:07~19:55:09）

```
19:55:08.136 E/AndroidRuntime(20595): FATAL EXCEPTION: main / CrashedByAdbException
19:55:08.214 I/ActivityManager: Process com.example.cloudmusic (pid 20595) has died: fg TOP
19:55:08.236 I/ActivityManager: Start proc 20596 … for top-activity {…/.MainActivity} caller=com.android.shell
```

2 秒内新 pid 20596、ServiceRecord 重建、`isForeground=true`、MediaSession `state=2 (PAUSED)`。
**但不能算 START_STICKY 成功**：`Start proc … for top-activity` 说明是**系统重建前台 Activity**
带起的进程，服务随 `MainActivity.onStart()` 重新创建，不是系统因 service restart 调度。

## 4. 实验 B：后台态崩溃 → 150s+ 不重启（19:57:15~20:00:41）

先 `am start -a MAIN -c HOME` 把 App 退到后台（top = `com.miui.home`），保持亮屏，
确认服务仍 `isForeground=true / startRequested=true / callStart=true`，随后注入 crash。

```
19:57:15.987 I/ActivityManager: Process com.example.cloudmusic (pid 20596) has died: prcp FGS
19:57:15.987 I/SmartPower: com.example.cloudmusic state=hibernation adj=200 → died
19:57:51.150 I/AppStateTracker: Package com.example.cloudmusic/10318 toggled into fg service restriction
```

观察点 `+2s / +3s / +5s / +8s / +13s / +23s / +43s / +49s / +89s / +206s`
全部为 `pidof` 空、`dumpsys activity services` = nothing；日志中**无**
`Scheduling restart of crashed service`、无 `Start proc … for service`。
`dumpsys activity processes`：`last crashed +1m31s ago; crashes since then: 1`。
`cmd appops get`：`RUN_ANY_IN_BACKGROUND: ignore`、`START_FOREGROUND: allow`。

→ **MIUI 在进程崩溃后抑制了服务重启，并把包置入 FGS restriction**。
**归因纠正**：`§61.26` / `§61.31` 记的「需 USB / 亮屏环境复测」不成立——本轮就是亮屏，
USB 不会改变 OEM 的后台策略；这是 **OEM 侧限制，非本项目代码缺陷，也非无线 adb 所致**。

## 5. 实验 C：用户交互恢复（20:00:44~20:01:08）

`monkey -p … -c LAUNCHER 1`（等价桌面再点开）后：

```
20:00:44.014 Start proc 20597 … for next-top-activity {…/.MainActivity}
20:00:44.831 CM-DataReq: SetConfig ok … cacheManagerHandle …
20:00:44.832 CM-DataReq: prepare trackId=g01-local … url=…cloudmusic_g01.mp3
20:00:44.870 NMAudioPlayer: doPrepareAsync openSource 0，mDuration 37800
20:00:44.971 NEPlayer: prepared notifyTrackInfoReady
```

- pid 20597、FGS 前台、`startRequested=true / callStart=true`
- MediaSession `state=2 (PAUSED)`、`position=0`
- **约 957ms** 自动 `prepare` 回上一曲目并让引擎就绪（Room 游标恢复的消费侧证据）
- 20:01:07 再发 `ACTION_PLAY` → `state=3`、`Play status 4`、
  `AudioTrack(0): Write start play nErr:0` → **回到可播放态且实际出声**

## 6. G09 判据拆解与结论

| 判据（`03-06 §6.2` / `§12.6`） | 结果 | 证据 |
|---|---|---|
| 注入 crash → 进程退出 | ✓（等价） | `FATAL EXCEPTION / CrashedByAdbException`、`has died`；**非 native SIGSEGV** |
| 系统重启服务（START_STICKY） | ✗ | 后台态 206s 无重启、无 `Scheduling restart`；MIUI `fg service restriction` |
| 服务 / FGS 恢复 | ✓（仅用户交互后） | pid 20597、`isForeground=true` |
| 队列 / 进度从 Room 游标恢复 | ⚠ 部分 | 自动 `prepare trackId=g01-local` 且有 `queueRepository.restore()`（`PlaybackComposition.kt:135`）；但 MediaSession `metadata=null / size=0`、崩溃前后 `position` 均为 0，**进度数值一致性无区分度** |
| 保持 PAUSED | ✓ | `state=2` |
| ≤5s 回到可播放态 | ✓（用户交互后） | 约 957ms 引擎 prepared，点播放即出声 |

**结论：G09 仍为 PARTIAL，不判 PASS、不伪造自动重启。**
- 已确证的部分：代码侧 START_STICKY 返回值正确；前台态崩溃 100ms 自动恢复；
  后台态崩溃后用户重新点开 App，约 1s 恢复到 PAUSED 且可立即播放。
- 未通过的部分：后台态 crash 后系统不自动重启服务（MIUI OEM 抑制，亮屏亦无效）。
- **A3 仍非全绿**：G01~G08 PASS / G09 PARTIAL → 按 `03-06 §6` 舱**不冻结**、
  `assets/ENGINE-FINGERPRINT` 的 `engine_compat_test` **不回填**。
- 按用户「实际使用有问题再修改」的口径，本轮**不加** WorkManager / Alarm 兜底重启；
  若真实使用中崩溃后恢复不可接受，再评估自恢复兜底或 `IMusicEngine` 独立进程隔离（§12.6 预留）。

## 7. 发布阻断 12 条状态（`03-12 §8`，本轮静态核对）

| # | 条件 | 状态 | 依据 |
|---|---|---|---|
| 1 | 素材全量替换 | ✗ | `docs/assets-mapping.csv` 61 行 `replaced=true` **0 行**；C4 release FAIL |
| 2 | 无第三方品牌特征 | ⚠ | 未见品牌字样；但 `app/src/main/` 无 `res/`、Manifest 无 `label`/`icon`，应用名与图标为系统默认、未自定义，需发布前人工确认 |
| 3 | 权限 ≤12 无禁止项 | ✓ | C3 PASS，11 项 |
| 4 | 一次性 ABI 验收 + 指纹一致 | ⚠ | C6 PASS（18 so SHA-256）；C1 release 3 项既有实证 FAIL；A3 未全绿 → 不回填 |
| 5 | 无 `com.netease.*` 外泄自研模块 | ✓ | C2 PASS，扫描 153 文件 0 处 |
| 6 | 无硬编码凭据 / 日志不落凭据 | ✓ | C5 PASS |
| 7 | `NOTICE.md` / `LICENSE` 齐全并声明 | ✓ | 两文件在位；**本批修正 `NOTICE.md §3` 过时声明**（原写「尚未提取进项目」） |
| 8 | 真机用例全绿 | ✗ | G09 PARTIAL；`03-07 T10~T14/T16`、`03-08 T20~T22`、`03-09 T13/T14` 未全跑；T16 口径已同步 20 分钟 |
| 9 | 性能基线 | ⚠ | 20 分钟后台 PASS（`§61.31`）；7,500 首冷扫 ≤60s / 增量 ≤10s 未测 |
| 10 | 飞行模式本地播放 | ⚠ | 未系统验证；E24 缓存命中离线播放为部分证据 |
| 11 | 权限拒绝降级矩阵 | ⚠ | B01 / B03 等已验证，全矩阵未跑 |
| 12 | 竞品 so 使用场景决策 | ✓ | `03-12 §7` 三场景 + `NOTICE.md §4` 已如实记录 |

**PASS 5 条（3/5/6/7/12）、部分 5 条（2/4/9/10/11）、未满足 2 条（1/8）**；
自动检查实际阻断为 **C1（3 项既有实证）+ C4（61 项占位素材）**，与 `pre_release_check --mode release` 一致。
按用户决策，20 分钟之外的长稳与实际使用观察不再作为发布前硬门槛重复跑。

## 8. 复跑命令

```
adb connect 192.168.1.11:37763
adb -s <S> shell monkey -p com.example.cloudmusic -c android.intent.category.LAUNCHER 1
adb -s <S> shell am start-foreground-service -a com.example.cloudmusic.action.PLAY
adb -s <S> shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s <S> shell am crash $(adb -s <S> shell pidof com.example.cloudmusic)
adb -s <S> shell dumpsys activity services com.example.cloudmusic
adb -s <S> shell "logcat -d -v time | grep -E 'Scheduling restart|Start proc.*cloudmusic|fg service restriction'"
```

## 9. 现场恢复

验证结束后 `am force-stop com.example.cloudmusic`，回到本轮起点的
「无进程 / stopped=true / 无 FGS 通知」状态；`adb reverse` 与 PC 音源服务保持原样。

> AI生成
