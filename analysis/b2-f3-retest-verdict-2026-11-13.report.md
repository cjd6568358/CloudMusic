# F3 8 格真机复测裁决（`docs/12 §61.104`）

- 工具：`tools/b2_f3_retest_verdict.py`（纯 stdlib / 只读）
- 判定：**PASS**（10/10 项）；`device_run=true`，serial=10.0.0.2:43355
- 计数：**{'FAIL': 5, 'PASS': 3}**（历史 8 FAIL → 本轮 3 PASS / 5 FAIL）

| 格式 | 路径 | 结论 | 根因 | READY | PLAYING |
|---|---|---|---|---|---|
| aiff | local | FAIL | DECODER_INIT_FAILED | N | N |
| aiff | remote | FAIL | DECODER_INIT_FAILED | N | N |
| wma | local | FAIL | EXTRACTOR_PREPARATION_INCOMPLETE | N | N |
| wma | remote | FAIL | EXTRACTOR_PREPARATION_INCOMPLETE | N | N |
| ape | local | PASS | READY_AND_PLAYING | Y | Y |
| ape | remote | FAIL | NO_READY_IN_WINDOW | N | N |
| wv | local | PASS | READY_AND_PLAYING | Y | Y |
| wv | remote | PASS | READY_AND_PLAYING | Y | Y |

## 判定项

| # | 检查项 | 结果 | 说明 |
|---|--------|------|------|
| 1 | 8 格全部有一手 logcat | PASS | *-retest-2026-11-13.txt |
| 2 | 复测计数 3 PASS / 5 FAIL | PASS | {'FAIL': 5, 'PASS': 3} |
| 3 | wv local+remote 均 PASS 且有 READY/PLAYING | PASS | WavpackExtractor 真机闭环 |
| 4 | ape-local PASS（§61.94c 修正有效） | PASS | ApeExtractor 逐帧 seek 日志在场 |
| 5 | aiff ×2 同一根因 DECODER_INIT_FAILED | PASS | pcm_s16be 初始化失败，非容器解析缺陷 |
| 6 | wma ×2 同一根因 EXTRACTOR_PREPARATION_INCOMPLETE | PASS | AsfExtractor 未下发 seekMap/endTracks 即 EOF |
| 7 | ape-remote 无错误但窗口内未 READY | PASS | 需更长等待或排查 CacheDataSource 时序 |
| 8 | 8 格自定义工厂均被调用 | PASS | CM-Factory createExtractors + extractors: 25 |
| 9 | 历史 F3 矩阵零改写（git restore 生效） | PASS | 14 PASS / 8 FAIL 当时快照不变 |
| 10 | 反伪证：不宣称 8 格已修复 | PASS | 5 格仍 FAIL，如实登记 |

## 诚实边界

- 历史 8 格 FAIL → 本轮 3 PASS / 5 FAIL；不得写成「容器缺口已修复」。
- PASS 判据为 STATE_READY(duration>0) + isPlaying，未逐格验证 seek / 完整时长 / ENDED。
- aiff 的 format_supported=YES 说明 MIME 映射生效，失败点在 FFmpeg decoder 初始化。
- ape-remote 无明确错误，不能判定为代码缺陷，也不能判定为通过。
- 历史 logcat 与 l3-f3-matrix.json 已 git restore 恢复；新结果另存独立文件。

> AI生成
