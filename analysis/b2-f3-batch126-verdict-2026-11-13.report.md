# §61.106 真机复测逐格裁决（2026-11-13）

- **verdict = RECORDED，device_run = true**，端点 `10.0.0.2:41971`。
- 证据/纪律判据 **12/12 PASS**。
- 最终 8 格 counts = `{'PASS': 5, 'FAIL': 3}`；同批初轮（修复前）= `{'PASS': 5, 'FAIL': 3}`；§61.104 当时 = `{'FAIL': 5, 'PASS': 3}`。
- 阶段 B（ape×remote，wait=30.0s）counts = `{'FAIL': 1}`，单格结论 = `FAIL`（未见 STATE_READY(duration>0)）。

## 逐格对照（§61.104 → §61.106）

| 格式 | 路径 | §61.104 | 本批初轮 | 最终 | 较初轮变化 | duration_raw | 容器错误串消失 | Factory 25 | 原因 |
|---|---|---|---|---|---|---|---|---|---|
| aiff | local | FAIL | PASS | PASS | 否 | 11946 | 是 | 是 | STATE_READY(duration>0) 且 isPlaying（软/硬解渲染出声） |
| aiff | remote | FAIL | PASS | PASS | 否 | 11946 | 是 | 是 | STATE_READY(duration>0) 且 isPlaying（软/硬解渲染出声） |
| wma | local | FAIL | FAIL | FAIL | 否 | None | 是 | 是 | 存在明确错误回调 / 崩溃 / 打开失败 |
| wma | remote | FAIL | FAIL | FAIL | 否 | None | 是 | 是 | 存在明确错误回调 / 崩溃 / 打开失败 |
| ape | local | PASS | PASS | PASS | 否 | 2395742 | - | 是 | STATE_READY(duration>0) 且 isPlaying（软/硬解渲染出声） |
| ape | remote | FAIL | FAIL | FAIL | 否 | None | - | 是 | 未见 STATE_READY(duration>0) |
| wv | local | PASS | PASS | PASS | 否 | 12000 | - | 是 | STATE_READY(duration>0) 且 isPlaying（软/硬解渲染出声） |
| wv | remote | PASS | PASS | PASS | 否 | 12000 | - | 是 | STATE_READY(duration>0) 且 isPlaying（软/硬解渲染出声） |

## 根因结构变化（本批最重要的一手结论）

- **aiff ×2**：§61.104 的 `DECODER_INIT_FAILED` → 本批 PASS（`READY 11946ms` + `PLAYING` + `isMusicActive=true`），§61.105 的 PCM 家族预置真机闭环。
- **wma ×2**：§61.104/初轮的 `Loading finished before preparation is complete` **已消失**；日志出现 `diag publish endTracks+seekMap`（endTracks/SeekMap 真机生效）与逐包读取，但下游换成 `FfmpegDecoderException: Initialization failed` —— FFmpeg `wmadec.c:80` 要求 `block_align != 0`，而 B2 的 `createContext` 未为 wmav2 预置 `block_align` / `sample_rate` / `ch_layout`，`getExtraData` 对 `audio/x-wma` 亦返回 null。**容器层已通、解码器层待专项，wma 仍 FAIL**。
- **ape-remote**：8s→30s 加长等待仍 `NO_READY_IN_WINDOW`；30s 窗口内 `CM-Ape seek to pos=` 日志仅 3 行且停在第 2 帧/1435 帧，属逐帧 `RESULT_SEEK` + REMOTE 取流代价，**排除单纯时序不足**，不判缺陷也不判通过。

## 判据明细

| # | 判据 | 结果 | 证据 |
|---|---|---|---|
| 1 | 运行摘要存在且 device_run=true / 不卸载装机 / 历史已恢复 | PASS | b2-f3-batch126-run-2026-11-13.json |
| 2 | 阶段 A 8 格证据齐全 | PASS | counts={'PASS': 5, 'FAIL': 3} |
| 3 | 阶段 B ape×remote 加长等待单格证据齐全 | PASS | wait=30.0 / counts={'FAIL': 1} |
| 4 | 最终 8 格 logcat 一手文件均存在且非空 | PASS | analysis/l3-f3-logs/*-batch126b-2026-11-13.txt |
| 5 | 同批初轮（修复前）8 格证据并存，可作逐格对照 | PASS | b2-f3-batch126-2026-11-13.json |
| 6 | 加长等待单格 logcat 存在且非空 | PASS | analysis/l3-f3-logs/ape-remote-longwait-device-2026-11-13.txt |
| 7 | 历史 l3-f3-matrix.json 已恢复（14 PASS / 8 FAIL 快照） | PASS | counts={'PASS': 14, 'FAIL': 8} |
| 8 | §61.104 对照矩阵可读 | PASS | b2-f3-retest-2026-11-13.json |
| 9 | 真机命令带 -s <serial> 且端点记录在案 | PASS | serial=10.0.0.2:41971 |
| 10 | PASS 判据口径未提升（不逐格验证 seek / 时长 / ENDED） | PASS | 沿用 tools/l3_f3_matrix_run.py 的 STATE_READY(duration>0)+isPlaying |
| 11 | 裁决本身不写「8 格已修复 / 全格式覆盖」 | PASS | verdict=RECORDED；计数只作如实登记 |
| 12 | wma 容器层错误已消失、但解码器层出现新根因（如实登记，不判修复） | PASS | endTracks/seekMap 已下发；wmav2 的 avcodec_open2 仍失败（wmadec.c:80 block_align） |

## 诚实边界

PASS 判据仅 STATE_READY(duration>0) + isPlaying；未逐格验证 seek / 完整时长 / ENDED；不宣称 8 格已修复 / 全格式覆盖；未做破坏性注入；F4 双轨口径、A3 G09 PARTIAL、舱不冻结不变；不动解码器。APK 可构建 ≠ 已装机 ≠ 真机可播；本批已装机，但播放验收仍以逐格判据为准。

复跑：`python tools/b2_f3_batch126_verdict.py --check`（只读比对冻结 JSON）。
