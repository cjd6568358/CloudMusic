# F4 · 播放控制判据真机终局取证（§61.75，2026-11-11）

> **性质**：真机主动控制取证（非被动观测）。`cmd media_session dispatch`
> 绕过 MIUI 对 `input` 的 INJECT_EVENTS 限制，**无需用户物理在场**。
> 判据未满足一律 FAIL / NOT RUN；**不把「已执行控制动作」当作验收通过**。

## 1. 现场

- serial `10.0.0.2:37163`，app `package:/data/app/~~hgFKEcusZZYsx_cKQRZdSw==/com.example.cloudmusic-hGmQwq1aJHwIYcWmIFJYQQ==/base.apk`（不含 §61.69 接线的基线 APK），样本 `f3_m4a_complete.m4a`。
- 端点：用户给 `10.0.0.2:37163` 后 `get-state=device`；此前 `39257`
  曾 offline、重连失败。**配对码不落盘**、未改用户网络、未 `pm grant`。
- 零 P0 源码改动；临时 wifiOnly / adb reverse / http.server / logcat
  子进程均在 finally 恢复。

## 2. 执行的控制动作

| t(s) | 动作 | 结果 |
|---|---|---|
| 4.6 | 注入 ACTION_PLAY http://127.0.0.1:8765/f3_m4a_complete.m4a | rc=0 |
| 10.0 | snapshot_A 注入后、dispatch 前 | 快照完成 |
| 20.2 | cmd media_session dispatch play | rc=0  |
| 22.6 | snapshot_B play 后 | 快照完成 |
| 28.7 | cmd media_session dispatch next（第 1 次切歌） | rc=0  |
| 32.0 | cmd media_session dispatch next（第 2 次切歌） | rc=0  |
| 42.0 | cmd media_session dispatch next（第 3 次切歌） | rc=0  |
| 50.0 | cmd media_session dispatch fast-forward（seek 前向） | rc=0  |
| 56.0 | cmd media_session dispatch rewind（seek 回退） | rc=0  |
| 64.0 | snapshot_C 全部控制动作后 | 快照完成 |
| 70.8 | snapshot_C_final | 收尾快照完成 |

## 3. 判据逐条登记（真实结果，不粉饰）

| 判据 | 实测 | 结论 |
|---|---|---|
| 播放进入 PLAYING（MediaSession state=3） | 快照 state=[3, 3, 2, 2] | **PASS** |
| 本 app AudioTrack `state:started` | app AudioTrack started=False | **FAIL（未出现本 app 的 started AudioTrack）** |
| `Write start play nErr` 打印 | 命中 0 次 [] | **FAIL（0 次；按 §61.74 口径非充分证据，需与其它信号并用）** |
| 自动进曲 / 切歌 3 首（无 `-103`、无释放后回调） | dispatch next ×3 均 rc=0；codec=['aac'] / prepare=4 | **FAIL（未出现 3 次新解码/进曲，或 -103 仍在）** |
| seek（fast-forward / rewind 后 position 变化） | position 序列=[0, 0, 0, 0] | **FAIL（position 未变化）** |
| ENDED（自然播完） | 本窗口未观测到自然播完（无 ENDED 日志） | **NOT RUN（受控窗口内未跑到曲末，不伪造）** |
| `-103` | 1 次 | **FAIL（-103 仍在场）** |

## 4. 日志统计

- logcat `analysis/l3-f4-logs/playback-control-20261111-135736.txt`（3895 行）：codec=['aac']、`Write start play`=[]、
  `Play status`=['3']、`mDuration`=['2933']、`doPrepareAsync`=4、
  `-103`=1、Java `onData`=645。

## 5. 结论

- **播放控制动作全部执行成功，但主判据未通过（见第 3 节逐条）；F4 仍 BLOCKED，真机取证到此收口，不再把「设备上线」留作下一步。**
- `dispatch` 动作全部成功执行（见第 2 节），但**动作成功 ≠ 播放器响应**；
  是否进入 PLAYING 以 media_session / audio / logcat 实测为准。
- §61.74 的判据口径修正仍适用：`Write start play` 0 次不是
  「没有写 AudioTrack」的充分证据，故本轮并用多信号判据。
- **F4 仍 BLOCKED** 除非所有主判据实测 PASS；本批不写任何提前收口结论。
- A3 仍 G09 PARTIAL、舱不冻结、`engine_compat_test` 与指纹不回填；
  F3 14 格未改写。零 P0 源码改动、未重编译 / 未重装、so 二进制原样。

> AI生成
