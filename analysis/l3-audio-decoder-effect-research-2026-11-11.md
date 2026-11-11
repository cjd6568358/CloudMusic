# 播放内核解码器 / 音效实现核查与 FFmpeg 方案评估（2026-11-11）

> **性质**：调研核查报告，**非正式批次、不推进技术断点、零生产代码改动**。
> 依据 = 本仓库 18 个 so + 反编译产物 + `docs/00`/`05`/`09`/`10` 既有实证
> \+ Android 官方 Media3 文档（联网核查）。法律部分不作法律结论，按 D-146
> 「个人自用 / 侧载」范围与 `docs/09 §5` 已登记的 R7 处置口径表述。
> F4 仍 BLOCKED，本报告不改变任何验收判据。

---

## 1. 结论速览

| 问题 | 结论 |
|---|---|
| native 用什么解码器 | **混合后端**：`libffmpeg.so`（GPL 构建，100 个 `ff_*_decoder` / 75 demuxer，负责解复用 + 通用软解）+ 4 个按名加载的专用解码库（自研 MP3/AAC/FLAC 软解 + MediaCodec 硬解桥）。不是单一解码器。 |
| 音效/均衡器怎么实现 | **PCM 域自研 DSP 链**，不是 Android 系统 `AudioEffect` API。`libneaudioeffects.so` 暴露 **103 个 `AudioEffects_*` JNI**；22 个 10 段图示 EQ 预设 JSON、10 个混响预设 JSON、私有 NCAE 二进制音效包、效果链顺序 / 时间轴 / 平滑切换、ReplayGain、FIR 卷积、MNN 推理（AI 音效）。 |
| 改用 FFmpeg 解码有什么问题 | ① **FFmpeg 已经在链路里了**，换它修不了 F4（F4 卡在 `NMCommonCache` 代次门控 / 取流层，与解码器正交）；② 这份 so 是 **GPL 构建**（`--enable-gpl --enable-libx264`），公开发布有权利张力；③ 丢硬解 → 功耗/发热；④ 103 个音效 JNI 与效果链全部要重接；⑤ gapless / seek / 时长 / Hi-Res / DSD / USB 独占都要自己重做；⑥ 包体积与版本维护。 |
| 业界主流 | **平台解码器优先 + 少量软件解码扩展兜底**：Android 官方 Media3/ExoPlayer 默认用平台 `MediaCodec`，FFmpeg/FLAC/Opus 等软解扩展需手动构建、按需裁剪。大厂音乐 App 普遍是「硬解为主、特殊格式自研/FFmpeg 软解兜底、音效在 PCM 域自研 DSP、低延迟输出走 AAudio/OpenSL ES」。全量 FFmpeg 软解多见于桌面播放器与工具类 App。 |

---

## 2. 解码器构成（本地实证）

### 2.1 加载链

`analysis/smali14/com/netease/cloudmusic/android/sdk/playerengine/Playback.smali` 的
`<clinit>` 依次 `System.loadLibrary`：

```
ffmpeg → NMCommonCache → ncmaudioplayer → neaudioeffects → ncmplayerengine
```

`PlaybackEngine.smali` / `AudioPlayerUnit.smali` 同样含 `"ffmpeg"` 常量。
`docs/05 §2` 实测 `libncmplayerengine.so` 的 `DT_NEEDED` 共 13 项
（6 个同包私有库 + `libffmpeg` + 6 个系统库），递归闭包 17.1 MB（`docs/10 §2`）。

### 2.2 `libffmpeg.so`

| 项 | 实测 |
|---|---|
| 大小 | 6,658,592 B（6.5 MB），18 so 中最大 |
| 构建配置 | `--enable-gpl --enable-libx264 --enable-encoder='libx264,aac' --enable-muxer='h264,mp4,mov,aac' --enable-cross-compile --target-os=android --enable-asm ... -O3 -ffast-math` |
| 许可标记 | `GPL version 2 or later` × 6（**GPL 构建，非 LGPL**） |
| 解码器 | `ff_*_decoder` **100 个**（`docs/09 §61.44` 记 69 个音频相关），含 `ff_aac_decoder` / `ff_aac_latm_decoder` / `ff_ac4_mediacodec_decoder` / 大量 ADPCM 等 |
| demuxer | `ff_*_demuxer` **75 个**（Vorbis/FLAC/ID3v2/APIC 等标签线索 75 处命中，`docs/05 §3/§4`） |
| 关键 API | `avcodec_find_decoder` × 2；`libavcodec` 内部头 57 处 |
| JNI 导出 | **0**（纯 C/C++，由 `libncmplayerengine` / `libaudioplayer` 通过 `dlopen` 调用，`docs/05 §4`） |
| 依赖 | `libm/libz/libdl/libc`，**无竞品私有依赖**（`docs/05 §4` → 逃生舱可行依据） |

真机日志印证：`libncmaudioplayer.so` 与 `libaudioplayer.so` 各含
`Open codec for %s` 与 `avcodec_find_decoder`；§61.73 真机出现
`Decoder(0): Open codec for aac` / `Codec ID - 86018 ... 44100 Hz, stereo, fltp,
128 kb/s` / `doPrepareAsync ... mDuration 2933`。

### 2.3 专用解码库（Java 层按名加载，非 DT_NEEDED）

`docs/10 §2` 与 `analysis/spike1` 记录 dex 字符串实证：

| 库 | 大小 | 角色 |
|---|---|---|
| `libnecmMP3Dec.so` | 83,656 B | 自研 MP3 软解（纯 C++，JNI 0） |
| `libnecmAACDec.so` | 254,024 B | 自研 AAC 软解 |
| `libnecmFlacDec.so` | 18,056 B | 自研 FLAC 软解（`FrameSync` / `FillFrameBuffer`） |
| `libnecmMediaCodecJDec.so` | 137,112 B | **MediaCodec 硬解桥**（`MediaCodec` 串 36 处） |

解码器选择由 Java 层 `PlaybackEngine` / `AudioPlayerUnit` 按格式与设备能力决定，
FFmpeg 承担解复用与兜底软解，主流格式优先走自研软解或硬解桥 —— 这是
「**硬解优先 + 自研软解优化 + FFmpeg 兜底**」的典型大厂混合架构。

### 2.4 输出层

- `libncmaudioplayer.so`：`AudioTrack` 串 157 处、`AudioEffect` 7 处，
  `AudioTrack(%d): Write start play nErr:%d` 等（§61.74 反汇编）。
- `docs/00 §3.4`：AudioTrack / OpenSL ES / AAudio 三条输出路径 +
  `libncmusbaudio.so`（USB 独占 / Hi-Res）。
- 缓存/取流：`libNMCommonCache.so`（vfile / Range / 代次门控，F4 主断点所在）。

---

## 3. 音效 / 均衡器实现（本地实证）

### 3.1 库与 AI 链路

| 库 | 大小 | 关键串 |
|---|---|---|
| `libneaudioeffects.so` | 1,881,136 B | `AudioEffect` 230、`Parametric` 18、`Compressor` 25、`Limiter` 10、`Reverb` 22、`FIR` 4、`Biquad`、`Equalizer` |
| `libneaudioeffect_core.so` | 1,730,760 B | `AudioEffect` 4、`neaudio` 2 |
| `libneaudioaiif.so` | 432,200 B | `MNN` 53、`Biquad` 3、`AudioTrack` 32（AI 音效接口层） |
| `libMNN.so` + `libMNN_Express.so` | 2.8 MB | NN 推理引擎（`docs/10 §2` 明确为「AI 音效链路引入」） |

`docs/05 §5` 强调：**V0.1 不做音效也必须打包这些库**，因为它们在
`DT_NEEDED` 闭包内，缺一个即 `dlopen` 失败。

### 3.2 预设资源（`analysis/decoded/assets/audioeffect/`）

- `eq/`：**22 个 JSON**，均为 `{"on":true,"eqs":[10 个 dB 增益]}` —— 标准
  **10 段图示均衡（Graphic EQ）**。例：`bass.json` = `[6,4.5,6.2,2.09,0,0,0,0,0,0]`，
  `bass_treble.json` = `[6,5,6.2,1.5,0,0,1,3.2,4,0]`，`acg.json` = `[4,6.19,3.5,-0.5,-0.8,2,5.19,1,1.5,4]`。
  预设名：acg / ballad / bass / bass_treble / blues / chinese / classical /
  country / dance / downtempo / electronic / heavy_metal / hiphop / jazz /
  live / mid / pop / rock / soft / soft_bass / soft_treble / strong_bass。
- `reverb/`：**10 个 JSON**，参数化早期反射 + 混响 + 音调控制。例
  `bathroom.json` = `{"er":{"pattern":24,"rsize":50,"sdelay":40},"rvb":{"pdelay":0,"dtime":3.59,"hfdamping":2,"density":60,"rshape":40,"q":8,"diffusion":40,"swidth":110},"tc":{...}}`。
- `preinstall/`：**私有二进制 `NCAE` 格式**音效包 —— `cloudmusic` 16 个、
  `device` 1、`smartae` 1（智能音效）、`spatial` 1（空间音频）。文件名带 ID 与哈希，
  内容非明文 JSON，需专有解析器。

### 3.3 DSP 能力面（`libneaudioeffects.so` 导出）

`Java_com_netease_cloudmusic_media_audiofx_effects_AudioEffects_*` 共 **103 个 JNI**，
按类归并：

| 类别 | 代表接口 |
|---|---|
| 均衡 | `setGraphEQGains` / `setGraphEQON` / `setParametricEQ*` / `setParametricEQ2*` / `setExParametricEQ*` / `setDynamicEQ*` / `setMSEQ*` / `setBassTreble*` / `setFIRImpulse` / `setHeadphoneImpulse` |
| 动态 | `setCompressor*` / `setMultiBandCompressor*` / `setPostCompressor*` / `setLimiter*` / `setLoudness*` / `setALC*` / `setMultiBandSaturation*` |
| 空间 | `setReverb*` / `setSpatialAudio*` / `setSpeakerSpatial*` / `setRotate3D*` / `setStereoEnhancer*` / `setMBStereoField*` / `setChannelDelay*` |
| 调制/趣味 | `setChorus*` / `setFlanger*` / `setTremolo*` / `setVibrato*` / `setPitchChanger*` / `setRobotization*` / `setDelay*` |
| 个性化 | `setEarFinger*`（耳纹）/ `setHearAudioGram` / `setHearAudioON` / `setHearDefaultLoss`（听力图）/ `setHeadphoneON` |
| 链控制 | `getEffectChainOrder` / `setEffectChainOrder` / `getCustomEffectChainOrder` / `setCustomEffectChainOrder` / `resetEffectChainOrder` / `setBypassMode` / `disableAllEffects` / `dumpParams` / `resetParams` |
| 时间轴/平滑 | `setEffectTimelineNative` / `clearEffectTimelineNative` / `getEffectTimelineStateNative` / `setEffectJsonSmoothNative` |

native 侧符号：`NCAudioEffectsProcessor::Create` /
`GetDefaultEffectChainOrder` / `SetChannelDelayParamsSmooth`、
`audiofx::AudioEffects::Create` / `SetEffectTimeline` / `ClearEffectTimeline`、
`ReplayGainProcessor::Init/PushSamples/ReadSamples/GetReplayGain`
（响度归一化是独立 processor）。

引擎侧接线（JNI 导出日志）：`nativeAttachAudioEffect` /
`nativeUnattachAudioEffect` / `nativeSetAudioEffect` /
`nativeApplyAudioEffect` / `nativeChangeQuality` /
`nativeNotifyAudioEffectChanged` / `nativeSetAudioEffectCallback` /
`nativeUpdateAudioEffectConfigBySongTag`（按曲目标签自动切音效）。

**判断**：这是完整的**自研 PCM 域效果链**（有链顺序、时间轴、平滑过渡、卷积、
多段动态、AI 推理），不是调用 Android `android.media.audiofx.Equalizer`。
系统 API 只有基础多段 EQ / BassBoost / Virtualizer，且各厂商实现不一致，
无法承载这种量级的音效产品。

---

## 4. 如果播放内核改用 FFmpeg 解码，会有什么问题

### 4.1 先纠正一个前提：FFmpeg 已经在用，且 F4 不在解码层

`Playback.<clinit>` 第一个加载的就是 `ffmpeg`；`libffmpeg.so` 里 100 个
decoder / 75 个 demuxer 正在为引擎提供解复用与兜底软解；真机日志
`Open codec for aac` 就是 `avcodec_find_decoder` 链路。
而 §61.77 已把 F4 根因钉在 **`NMCommonCache` 的请求代次门控 → `commit`
冻结 → `result:-2` → 挂起异步读 → -103 → 播放侧门控静默早退**，属于
**取流 / 缓存层**，与解码器正交。
→ **换解码器不会修 F4，反而把已经跑通的解码链推倒重来**。

### 4.2 具体问题清单

| # | 问题 | 说明与本项目证据 |
|---|---|---|
| 1 | **许可 / 权利** | 本份 `libffmpeg.so` 是 `--enable-gpl --enable-libx264` 的 **GPL v2+ 构建**（含 `GPL version 2 or later` 串），不是 LGPL。按 D-146 个人自用 / 侧载范围可控（`docs/09 §5` R7 已登记：`NOTICE.md §4` / 架构 §0），但**公开发布前需法务评估**或改用 LGPL 重编 / `:engine-opensource`。这不是技术判断，不作法律结论。 |
| 2 | **丢硬解 → 功耗与发热** | 现有 `libnecmMediaCodecJDec.so` 是 MediaCodec 硬解桥。纯 FFmpeg 软解把解码压到 CPU，长时播放的耗电 / 温升会变；项目 A3 G08 有 20 分钟长稳与耗电判据（`12 §61.31`），`docs/10 §5` 还专门挂了 native PSS 预算未实测的账。业内共识是「能用硬解尽量用硬解，软解做降级」。 |
| 3 | **音效链断裂（最大工程量）** | 103 个 `AudioEffects_*` JNI、效果链顺序、时间轴、NCAE 私有预设、FIR 卷积、ReplayGain、MNN AI 音效全都挂在现有 PCM 缓冲上。换解码后端需在新链上重接全部效果处理器，否则 EQ / 混响 / 空间音效 / 智能音效全部失效。 |
| 4 | **gapless / seek / 精确时长** | FFmpeg 只管 demux/decode。无缝播放要自己处理 encoder delay 与 padding（LAME/Xing tag、iTunSMPB、Opus pre-skip、AAC priming），精确 seek 要自建索引，CBR 近似 seek 在官方 Media3 文档里默认都是关闭的。项目 F4 验收判据里 `seek` / `ENDED` / 完整时长本就 NOT RUN，换栈会让这些更远。 |
| 5 | **Hi-Res / DSD / USB 独占** | F3 的 7 种 NO_SAMPLE 含 dsf/dff；DSD 需要 DoP 或 native DSD 通路，FFmpeg 软解通常输出 PCM，位深 / 采样率 / USB 独占输出（`libncmusbaudio.so`）需要重做。 |
| 6 | **缓存层与解码层正交** | `NMCommonCache` 的 vfile / Range / 代次记账不因解码器更换而改变；§61.77 的三处修复锚点（`0x10798` / `0xc728` / `0x12dc8`）全在 native 缓存层，Java 侧改不了，换 FFmpeg 也不触及。 |
| 7 | **包体积与维护** | `libffmpeg.so` 已 6.5 MB；全量软解可能还要加 demuxer/protocol。FFmpeg 版本升级涉及安全补丁与 ABI，需自建 NDK 交叉编译链（官方 Media3 FFmpeg 扩展也要求手动构建）。 |
| 8 | **线程 / 实时性** | 软解耗时进播放线程，需要环形缓冲 + 低延迟输出（AAudio/OpenSL ES）才能避免 underrun；现有 AudioTrack 写链（§61.74 的 4 道门控）要重新验证。 |
| 9 | **编解码授权** | AAC 等格式的专利授权通常由设备厂商的平台解码器覆盖；自带解码器分发时的权利状态需单独确认（同样不作法律结论）。 |

### 4.3 什么时候「用 FFmpeg」是合理的

- 只作为**兜底软解扩展**（官方 Media3 的做法：默认平台解码器，FFmpeg 扩展
  按需构建、只含需要的 decoder），而不是替换整条播放内核 —— 收益是补齐
  APE / WV / TAK / DSD 等平台不支持的格式（正好对应 F3 的 7 种 NO_SAMPLE）。
- 或作为 `:engine-opensource` 逃生舱（`docs/02 §0` / `docs/05 §4` 已论证
  FFmpeg 无私有依赖、二进制层面可行）：但那是**整体替换播放内核**的大工程，
  不是「修 F4」的路径。

---

## 5. 业界主流方案（2026 核查）

### 5.1 Android 官方路线（权威依据）

Android Developers《支持的格式》（Media3 / ExoPlayer，页面标注 2026-07 更新）：

> 「默认情况下，ExoPlayer 使用 **Android 的平台解码器**。因此，支持的样本格式
> 取决于底层平台，而不是 ExoPlayer。」
>
> 「除了 Android 的平台解码器之外，ExoPlayer 还可以使用**软件解码器扩展程序**。
> 这些必须**手动构建**并包含在项目中。目前为 AV1、VP9、FLAC、Opus、**FFmpeg**、
> MIDI、IAMF 和 MPEG-H 提供软件解码器库。」
>
> FFmpeg 库「支持解码各种不同的音频样本格式，**可以选择在构建库时包含哪些
> 解码器**」，映射表：Vorbis→vorbis、Opus→opus、FLAC→flac、ALAC→alac、
  MP3→mp3、AAC→aac、AC-3/E-AC-3、DTS→dca、TrueHD→mlp 等。

同页还指出：MP3 / ADTS(AAC) / AMR 等只能做「恒定比特率近似 seek」，
`FLAG_ENABLE_CONSTANT_BITRATE_SEEKING` **默认未启用** —— 印证 4.2 第 4 条。

来源：https://developer.android.com/media/media3/exoplayer/supported-formats?hl=zh-cn

### 5.2 产业实践归纳

| 层 | 主流做法 | 为什么 |
|---|---|---|
| 解码 | **平台硬解（MediaCodec / CoreAudio）为主 + 少量自研或 FFmpeg 软解兜底** | 省电、低发热、有厂商专利覆盖；只有 APE / WV / TAK / DSD / 无损特殊容器才需要软解兜底 |
| 解复用 | 平台 Extractor 或 FFmpeg/libavformat（尤其非标准容器、网络流） | FFmpeg 的 demuxer 覆盖最广，这是它最不可替代的部分 |
| 音效 | **PCM 域自研 DSP 效果链**（参量 EQ / 图示 EQ / 多段压缩 / 混响 / 卷积 / 空间音频 / 响度归一化），高端产品叠加 NN 推理 | 系统 `AudioEffect` API 能力有限、厂商实现不一致、无法做时间轴与平滑切换；商业音效需要可分发预设与私有格式 |
| 输出 | AAudio（低延迟）/ OpenSL ES / AudioTrack；Hi-Res 走 USB 独占 | 位深 / 采样率 / 时钟与蓝牙编解码链路由输出层决定 |
| 会话 / 系统 | MediaSession + A2DP/AVRCP | `docs/00 §3.3` 已实证 90% 蓝牙体验来自系统协议 |
| 桌面/开源播放器 | VLC / foobar2000 等用 libavcodec 全量软解 | 无电池约束、格式覆盖优先 |

**趋势判断**：移动音乐播放器的解法不是「全量 FFmpeg」，而是
「平台解码为主、软件解码按需扩展、音效独立 DSP 链、输出走低延迟通路」。
本项目参考 APK 的架构（混合解码后端 + 独立音效库 + MNN AI 音效 + 多输出路径）
正是这一类的重投入版本；自研 MP3/AAC/FLAC 软解与 MediaCodec 硬解桥的存在，
说明它在功耗与音质上做了专门优化，而不是简单地「包一个 FFmpeg」。

### 5.3 与本项目路线的关系

- `docs/01 §6.1`、`docs/02 §0 A4`：用户已定案「**优先复制竞品 so 而非从头重写
  解码器**」，`IMusicEngine` 门面保留 `:engine-opensource` 逃生舱。
- D-146：个人自用 / 侧载，不公开发布 → 当前 GPL FFmpeg 与竞品私有 so 的权利
  状态按已登记口径处理；**公开发布阻断 #7** 才触发替换评估。
- 当前最靠前的断点仍是 **F4 取流/缓存层**（§61.77），与解码器选型无关。

---

## 6. 建议（按目的分）

| 目的 | 建议 |
|---|---|
| 修 F4 / 让播放出声 | **不要动解码器**。攻 §61.77 的三处 native 缓存锚点，或按上一轮的建议走「绕过取流」路线（不改二进制、可逆）。 |
| 补齐 F3 的 7 种 NO_SAMPLE | 可考虑按官方 Media3 方式**增量**引入 LGPL 重编的 FFmpeg 软解扩展，只编入需要的 decoder，作为兜底而非替换。 |
| 未来公开发布 | 先解决 R7：GPL FFmpeg 需替换/重编，竞品私有 so 需法务评估或启用 `:engine-opensource`；音效链（103 JNI + NCAE 预设）是另一大块替换成本，别低估。 |
| 保持现状 | 按 D-146 个人自用范围，不改；本报告只作为技术尽调留档。 |

---

## 7. 证据索引

- 本地：`engine-legacy/src/main/jniLibs/arm64-v8a/*.so`（18 个）、
  `analysis/decoded/assets/audioeffect/{eq,reverb,preinstall}/`、
  `analysis/smali14/.../playerengine/Playback.smali`、
  `analysis/engine-jni-exports.log`、`analysis/jni-diff-playerengine.log`。
- 文档：`docs/00 §3.4`、`docs/05 §2/§3/§4/§5`、`docs/09 §5 R7`、
  `docs/10 §2/§5`、`docs/01 §6.1`、`docs/02 §0 A4`。
- 近期批次：`docs/12 §61.73`（真机 codec 日志）、`§61.74`（AudioTrack 写链门控）、
  `§61.76`/`§61.77`（-103 与 `result:-2` 链）。
- 外部：Android Developers《Media3 ExoPlayer 支持的格式》
  https://developer.android.com/media/media3/exoplayer/supported-formats?hl=zh-cn
  （2026-07-22 页面标注更新，2026-11-11 访问）；Media3 ExoPlayer 概览
  https://developer.android.google.cn/media/media3/exoplayer 。

> AI生成
