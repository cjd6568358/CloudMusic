# F4 · 真机验收终局裁决（§61.75，2026-11-11）

> **性质**：纯离线只读汇总，**真机取证已完结**。不连 adb、不重跑实验、
> 不改历史证据、零 P0 源码改动。判据未满足一律 FAIL / NOT RUN。
> **F4 仍 BLOCKED**。

## 1. 现场（设备在场能取的，已全部取完）

- `§61.73`：端点 `10.0.0.2:37509` → app 未装 → 装不含 §61.69 接线的
  基线 APK，**三轮 60s 纯观测**（冷缓存 / 暖缓存 / 用户拖动），
  共 10573 行 logcat + 3 份 probe JSON + 3 份 live verdict + UI 截图。
- `§61.75`：端点 `10.0.0.2:37163`，`cmd media_session dispatch`
  绕过 MIUI 的 INJECT_EVENTS 限制，**主动执行** `play` / `next`×3 /
  `fast-forward` / `rewind`（6 次全部 rc=0），3895 行 logcat +
  4 次 media_session/audio 快照。**无需用户物理在场**。
- 下线前 `dumpsys` 补证 + 播放页 `screencap`。

## 2. F4 验收判据逐条定案（`17 §8.3`）

| # | 判据 | 实测 | 结论 |
|---|---|---|---|
| 1 | 双路径格式矩阵（`17 §8.3` F3 前置） | F3 14 格 = 11 PASS / 3 FAIL / 7 种 NO_SAMPLE，未改写 | **PARTIAL（非本轮真机范围，需样本）** |
| 2 | `Open codec` → `Write start play` | 三轮 0/0/0 次 + 控制轮 0 次；codec 仅冷/暖轮 aac | **FAIL（全程 0 次；按 §61.74 为非充分证据，需并用其它信号）** |
| 3 | 完整时长 / UI 进度条 | UI 截图两端 00:00；metadata:null；mDuration 2933 未映射到 UI | **FAIL（总时长不可用）** |
| 4 | MediaSession 进入 PLAYING | 控制轮快照 state=[3, 3, 2, 2]（曾出现 3），position=[0, 0, 0, 0] | **PARTIAL（state=3 仅状态机上报，无音频输出佐证）** |
| 5 | 本 app AudioTrack `state:started` | 快照 app_audio_track_started=False；dumpsys 无本 app 媒体轨 | **FAIL（未出现）** |
| 6 | 自动进曲 3 首（无 -103、无释放后回调） | dispatch next×3 rc=[0, 0, 0]；新 codec=['aac']；-103 三轮 [True, True, False] + 控制轮 1 | **FAIL（无 3 次新解码，-103 仍在场）** |
| 7 | seek / pause→play 位置连续 | fast-forward+rewind rc=0，但 position 序列=[0, 0, 0, 0] | **FAIL（position 恒 0）** |
| 8 | ENDED（自然播完） | 受控窗口内未跑到曲末，无 ENDED 日志 | **NOT RUN（不伪造）** |
| 9 | `-103` 判据 | 三轮 [True, True, False] + 控制轮 1 次 | **FAIL（-103 仍在场）** |

## 3. 关键纠偏：`state=3` 不是播放成功

- 控制轮 `snapshot_A/B` 的 MediaSession 上报 **state=3 (PLAYING)**，
  但 `position=0` 恒不变、`metadata: null`、**无本 app AudioTrack
  `state:started`**、`Write start play` 0 次、`-103` 1 次。
- 按 `§61.74` 的多信号口径：状态机上报 PLAYING **不构成播放成功**；
  音频输出须由 AudioTrack / 写日志 / position 推进共同佐证。
- `dispatch next`×3 后 state 回落到 2(PAUSED)，position 仍 0，
  codec 仅 1 次 → 切歌动作被接收但没有新曲目真正开始解码。
- `§61.74` 反汇编已证明 `Write start play` 打印点前有 4 道静默早退
  门控，故其 0 次**不是「没写 AudioTrack」的充分证据**；但无
  AudioTrack started + position 恒 0 是独立证据，结论不依赖它。

## 4. 终局结论

- **F4 验收未通过：所有主判据逐条登记为 FAIL / PARTIAL / NOT RUN，无一项 PASS；真机取证到此完结，后续是 F4 修复与回归验证，不再以「设备上线」作为验证前置。**
- **真机取证到此完结**：设备在场能执行的观测、注入、播放控制、
  UI 截图、dumpsys 全部完成；**不需要再为「验证」上线**。
  后续只有 F4 修复后的**回归验证**才需要设备。
- F3 14 格未改写；`-103` / 自动进曲 3 首 / seek / ENDED 判据未通过；
  A3 仍 G09 PARTIAL、舱不冻结、`engine_compat_test` 与指纹不回填。
- 零 P0 源码改动、未重编译 / 未重装含接线 APK、so 二进制原样、
  未改用户网络 / 未 `pm grant` / 配对码不落盘；
  §61.67~§61.74 历史正文与证据 JSON 一字未改。
- 接线回滚仍待用户拍板（不属真机验证，属源码决策）。

## 5. 实跑核验

- checks：**12 项 / 全 PASS True**；失败项：无
- 证据来源：`l3-f4-live-rounds-audit.json`、`l3-f4-playback-control.json`、`l3-f4-play-gate-disasm.json` 均只读、零改写。

> AI生成
