# §61.105 pcm_s16be 初始化根因静态审计（2026-11-13）

- 结论：**STATIC_ROOT_CAUSE_CONFIRMED**（静态源码级），真机回归 **NOT RUN**（设备离线）。
- 判据：8/8 PASS。

## 根因链

FFmpeg 6.0 avcodec_open2 对未声明 AV_CODEC_CAP_CHANNEL_CONF 的 audio decoder 要求 ch_layout.nb_channels != 0；pcm_s16be 属于 PCM_DECODER_* （只有 AV_CODEC_CAP_DR1）。上游 JNI 只对 mulaw/alaw 预置 sample_rate / ch_layout，故 pcm_s16be 的 createContext 返回 NULL → Java 抛 FfmpegDecoderException('Initialization failed')。

## 修法

ffmpeg_jni.cc createContext：按 av_get_bits_per_sample(codec->id) > 0 精确判定 PCM 家族，在 avcodec_open2 之前预置 sample_rate 与 av_channel_layout_default(ch_layout, rawChannelCount)；压缩解码器零影响。

## 判据明细

| # | 判据 | 结果 | 证据 |
|---|---|---|---|
| 1 | avcodec_open2 无 CHANNEL_CONF 的 audio decoder 强制要求 ch_layout | PASS | libavcodec/avcodec.c:251-257 `Decoder requires channel layout to be set` |
| 2 | PCM_DECODER_* 未声明 AV_CODEC_CAP_CHANNEL_CONF | PASS | libavcodec/pcm.c PCM_DECODER_1 .p.capabilities = AV_CODEC_CAP_DR1 |
| 3 | 未预置 sample_rate 时 time_base.den 取 0 | PASS | libavcodec/avcodec.c:293-297 |
| 4 | JNI 在 avcodec_open2 前仅对 PCM 家族预置 sample_rate/ch_layout | PASS | ffmpeg_jni.cc createContext / §61.105 |
| 5 | 压缩解码器不受影响（预置被 PCM 判定收窄） | PASS | 原「所有 codec 都预置」写法已收敛，aac/mp3/ape/wmav2/wavpack 保持上游行为 |
| 6 | audio/x-aiff → pcm_s16be 映射在位 | PASS | FfmpegLibrary.getCodecName |
| 7 | rawSampleRate/rawChannelCount 来自 Format 且有效 | PASS | FfmpegAudioDecoder.java:78（真机 Format=1ch 48000Hz → 两个值均 > 0） |
| 8 | 真机回归 NOT RUN（静态证据不提升为运行时结论） | PASS | §61.105：adb devices 两个历史端点均 offline；aiff 单格复测未执行 |

## 诚实边界

符号存在 ≠ 运行时可用 ≠ 真机可播；本机只完成静态证据 + native 编译，aiff 真机单格回归 NOT RUN，不写成「aiff 已修复 / 全格式已支持」。

复跑：`python tools/b2_pcm_init_audit.py --check`（只读比对冻结 JSON）。
