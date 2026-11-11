# F4 · A1「绕过取流」时间盒真机裁决（12 §61.79，2026-11-11）

> **性质**：纯离线只读裁决，不连 adb、不重跑真机、不改历史证据、
> 零 P0 源码改动。判据未满足一律 FAIL / NOT RUN。
> **F4 仍 BLOCKED**；「绕行 ≠ 修复」。

## 1. 现场与取证链

- 端点 `10.0.0.2:37163`，app `package:/data/app/~~hgFKEcusZZYsx_cKQRZdSw==/com.example.cloudmusic-hGmQwq1aJHwIYcWmIFJYQQ==/base.apk`（**不含 §61.69 接线的基线 APK**：  `wiring_level_logs=[]`），样本 `f3_m4a_complete.m4a`（16573387 B）。
- 取证工具 `tools/l3_f4_a1_bypass_live_probe.py`（`probe_run=v2_fixed`）；
  首轮试跑发现 4 处取证缺陷，归档为 `analysis/l3-f4-a1-bypass-live-initial-v1.json`，**不作裁决依据**。
- v2 修正：先 `logcat -c` 再启动流式子进程、按本轮 app PID 分段、
  用 app uid（`10316`）关联 `dumpsys audio`、REMOTE 轮前清理私有样本、
  增加 t=2s 早快照与 AudioTrack 一手状态信号。
- 现场恢复 `restore_errors=[]`；临时 wifiOnly / adb reverse / 
  http.server / logcat 子进程 / 私有样本均在 finally 无条件恢复。

## 2. 两轮同窗对照（同一设备 / 同一样本 / 同一次运行）

| 维度 | LOCAL 主轮（绕过取流） | REMOTE 对照轮 |
|---|---|---|
| app PID / 分段行数 | 18560 / 517 | 18565 / 628 |
| `prepare remote=` | ['false', 'false'] | ['true'] |
| REMOTE 取流特征 | 0 | 30 |
| LOCAL 装配特征 | 8 | 0 |
| `Open codec` | ['aac', 'aac'] | [] |
| `Write start play nErr` | ['0', '0'] | [] |
| `AudioTrack Play` / ACTIVE | ['1', '1'] / 2 | [] / 0 |
| `frames delivered` | [1, 1] | [] |
| `msg=2` / Release | 2 / 2 | 0 / 0 |
| `Play status` | ['4', '4'] | ['3', '3'] |
| `-103` | 0 | 0 |
| MediaSession state / position | [2, 2, 2, 2, 2, 2, 2] / [0] | [3, 3, 3, 3] / [0] |
| 本 app AudioTrack `state:started` 快照 | False | False |

## 3. 判据逐条登记（§61.74/§61.75 多信号口径）

| # | 判据 | 实测 | 结论 |
|---|---|---|---|
| 1 | 绕过取流成立（LOCAL 无 REMOTE 取流特征） | remote_marker 合计=0（全 0）/ local_marker 合计=8 / prepare remote=['false', 'false'] | **PASS** |
| 2 | `Open codec` → `Write start play nErr:0`（点火） | codec=['aac', 'aac'] / write=['0', '0'] / AT_Play=['1', '1'] / ACTIVE=2 | **PASS** |
| 3 | 无 `-103` | Finish Async:-103=0 / 含 -103 行=0 | **PASS** |
| 4 | 持续播放 / frames delivered | frames=[1, 1] / msg2=2 / Release=2 / Paused=2 | **FAIL（每次仅交付 1 帧、紧随 msg=2 / Paused / Release，未持续出声）** |
| 5 | 本 app AudioTrack `state:started`（app uid 关联） | 7 次 dumpsys 快照 app_uid_line_present 全 False / track_started_any=False；logcat 有 ACTIVE 但仅 1 帧即释放 | **FAIL（未出现本 app 的持续 started AudioTrack）** |
| 6 | MediaSession 进入 PLAYING 且 position 递增 | states=[2, 2, 2, 2, 2, 2, 2] / distinct_positions=[0] | **FAIL（state 恒 2（PAUSED）、position 恒 0）** |
| 7 | 自动进曲 3 首（无 -103、3 次新解码） | codec 共 2 次 / dispatch next×3 rc=[0, 0, 0] / -103=0 | **FAIL（仅 2 次短命解码（非 3 首持续进曲））** |
| 8 | seek（fast-forward / rewind 后 position 变化） | distinct_positions=[0] / seek_markers=0 | **FAIL（position 未变化、无 seek 标记）** |
| 9 | ENDED（自然播完） | msg2=2（播放完成通知）/ natural_ended_markers=2 / frames=[1, 1]；msg=2 不等于自然播完 | **NOT RUN（受控窗口内未跑到曲末；`msg=2` 为过早完成通知，不伪造 ENDED）** |
| 10 | REMOTE 同窗对照（已知 FAIL） | remote=['true'] / codec=[] / write=[] / Play status=['3', '3'] / remote_marker 合计=30 / local_marker 合计=0 | **PASS** |

## 4. 关键判读：点火成立，但没有持续播放

- **绕过取流成立**：LOCAL 轮 `remote_marker_counts` 全 0、
  `local_marker_counts` 全 2、`prepare remote=false` ×2、
  `XorLocalSource::Open success`，**完整避开 `NMCommonCache`
  代次门控 / commit / `result:-2` / -103 链**。
- **解码链点火成立（相对 §61.75 的实质新进展）**：`Open codec for aac` ×2、
  `AudioTrack(0): Play(1)` ×2、`prior state:STATE_ACTIVE` ×2、
  `Write start play nErr:0` ×2、`Play status 4` ×2、`-103` 0 次。
  这是 §61.75 真机终局裁决（主判据无一 PASS）之后，第一次在真机上
  同时出现 codec / 写日志 / AudioTrack ACTIVE 且无 -103。
- **但没有持续播放**：`stop(...): called with 1 frames delivered`
  （每次仅 **1 帧**），约 350ms 后 `PlayerNotifyEvent msg=2` →
  `Paused` → `Stop` → `Release End` → `Free`；自动进曲只再开一条
  同样短命的 LOCAL 链（codec 共 2 次，不是 3 首）。
- `msg=2` 是**播放完成通知**，不是自然播完；`frames=1` 的短命播放
  不计「持续出声」。MediaSession 全程 `state=2`（PAUSED）、
  `position` 恒 0；7 次 dumpsys 快照（按 app uid 关联）均无本 app
  的 `state:started` AudioTrack。按 §61.74 多信号口径，
  **不能把 `Write start play` 单点当成播放成功**。
- REMOTE 对照轮依然 FAIL：`remote=true`、`Start id=5` /
  `WaitRequest=5` / `result:-2=4` / `commit :=1`，codec / write /
  AudioTrack 全 0、`Play status 3`、`state=3` 但 position 0 ——
  与 §61.61 / §61.75 一致（state=3 仅状态机上报）。

## 5. 时间盒裁决

- **A1_BYPASS_STREAM_LIVE_PARTIAL__IGNITION_ONLY__NO_SUSTAINED_PLAYBACK__TIMEBOX_NOT_PASSED：绕过取流在真机上成立且解码链点火（codec / Write start play nErr:0 / AudioTrack ACTIVE / 无 -103），但每次仅交付 1 帧即 msg=2 → Paused → Release，position 恒 0、MediaSession 恒 PAUSED、自动进曲 3 首 / seek / ENDED 全不通过；时间盒判据未通过。按既定规则建议转 B2（不追加 A2 二进制补丁），但路线切换仍需用户拍板。「绕行 ≠ 修复」，REMOTE 缺陷仍在，§61.75 终局裁决不变，F4 仍 BLOCKED。**
- 时间盒判据（《播放内核两条路线评估》§6.1）：`Write start play`
  出现 ✅ / 本 app AudioTrack `state:started` ❌ / 自动进曲 3 首 ❌
  → **timebox_passed = False**。
- 按既定规则：**不通过 ⇒ 停止 A，按路线评估立即转 B2，不追加 A2
  二进制补丁**。`B2_PENDING_USER_DECISION` —— **路线切换仍需用户拍板**，
  本批不擅自启动 B2、不重编译 / 不重装、不动解码器。
- **「绕行 ≠ 修复」**：REMOTE 取流缺陷仍在，§61.75 终局裁决不变，
  **F4 仍 BLOCKED**；§61.78 的裁定（A1 绕行成立但不是修复）
  在真机上得到部分验证，且暴露出「点火后不能持续」这一新的、
  位于播放链下游的独立问题。

## 6. 接线回滚（与 A1 正交，同一批交付）

- §61.69 诊断接线已按 §61.70 勘误从 `EngineAdapter.kt` 回滚；
  回滚证据 `analysis/l3-f4-wiring-rollback-audit.json`（`True`）。
- 只删诊断接线，`SetConfig` / VFM / `source.url != null` 分叉 /
  `remote ? Type.REMOTE : Type.LOCAL` 等功能代码零改动；
  **诊断清理 ≠ F4 修复**。

## 7. 诚实边界与实跑核验

- 首轮试跑（`initial_v1__DEFECTIVE`）4 处取证缺陷已如实登记并修正；
  两轮 logcat 按 PID 分段后 `local_marker_counts` 在 REMOTE 轮全 0，
  跨轮污染已消除。无线 adb 延迟使 REMOTE 轮时间轴较预定秒数漂移，
  但流式 logcat 全程抓取，不影响判据。
- checks：**34 项 / 全 PASS True**；失败项：无
- 零 P0 源码改动（除 §61.79 接线回滚）、未重编译 / 未重装、
  so 二进制原样、未改用户网络、未 `pm grant`、配对码不落盘。
- A3 仍 G09 PARTIAL、舱不冻结、F3 14 格未改写、指纹不回填。

> AI生成
