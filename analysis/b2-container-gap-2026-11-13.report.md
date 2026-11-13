# 容器缺口闭环审计（`docs/12 §61.102`）

- 工具：`tools/b2_container_gap_audit.py`（纯 stdlib / 只读）
- 判定：**PASS**（14/14 项）；`device_run=false`

| # | 检查项 | 结果 | 说明 |
|---|--------|------|------|
| 1 | MIME 映射 audio/x-ape → ape | PASS | FfmpegLibrary.getCodecName 实扫 |
| 2 | MIME 映射 audio/x-wma → wmav2 | PASS | FfmpegLibrary.getCodecName 实扫 |
| 3 | MIME 映射 audio/x-wavpack → wavpack | PASS | FfmpegLibrary.getCodecName 实扫 |
| 4 | MIME 映射 audio/x-aiff → pcm_s16be | PASS | FfmpegLibrary.getCodecName 实扫 |
| 5 | 上游标准 MIME 映射未回退 | PASS | mp3/aac/flac/opus 原样 |
| 6 | 未知 MIME 仍返回 null | PASS | 不静默兜底 |
| 7 | AiffExtractor 已实现 | PASS | FORM/COMM/SSND + 80-bit 采样率 |
| 8 | WavpackExtractor 已实现 | PASS | wvpk 变长块 |
| 9 | 四个 Extractor 全挂进 factoryWithApe | PASS | 自定义工厂清单实扫 |
| 10 | 新增三个 JVM 测试文件在位 | PASS | Aiff/Wavpack/MIME 映射 |
| 11 | AIFF 采样率勘误登记（48000 非 44100） | PASS | §61.101 曾误记 44100，本批只追加勘误、不改历史正文 |
| 12 | 历史 F3 矩阵零改写 | PASS | 14 PASS / 8 FAIL 当时快照 |
| 13 | 真机复测 NOT RUN 边界在位 | PASS | 静态库 decoder 未编入，不推断可播 |
| 14 | 反伪证：不宣称已可播 | PASS | KDoc 明示映射存在 ≠ 解码器编入 ≠ 真机可播 |

## 诚实边界

- 本工具只校验 Java/Kotlin 侧接线与 JVM 单测，不加载 native 库。
- ffmpegHasDecoder(codecName) 是否为真取决于 build_ffmpeg.sh 的 ENABLED_DECODERS（需 ape / wmav2 / wavpack / pcm_s16be），本批未交叉编译。
- F3 8 FAIL 真机复测 NOT RUN：本机仍无法产出含 FFmpeg 静态库的 APK。
- 历史正文与证据 JSON 零改写；零设备操作、零联网、不动解码器。

> AI生成
