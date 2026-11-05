# 00 · 参考 APK 分析报告（NeteaseCloudMusic.apk）

> 用途：为自研安卓播放器提供**架构参考、能力清单与照搬取舍依据**。
> 分析方式：静态解包与字符串/清单分析，未运行、未动态调试、未修改原 APK。
> 分析工具与中间产物见 `tools/`、`analysis/`。

---

## 1. 样本指纹

| 项 | 值 |
|---|---|
| 文件 | `NeteaseCloudMusic.apk`（仓库根目录） |
| 体积 | 213 MB（23,084 个 ZIP 条目） |
| 包名 | `com.netease.cloudmusic` |
| 版本 | **9.6.20**（versionCode `9006020`） |
| minSdk / targetSdk | **23 (Android 6.0) / 33 (Android 13)** |
| Dex | **20 个**（classes.dex ~ classes20.dex），约 243,112 个类描述符、约 110 万条字符串 |
| Native | `arm64-v8a` 167 个 .so、`armeabi-v7a` 169 个 .so（**无 x86**） |
| Manifest 组件 | Activity **799**、Service **93**、Receiver **38**、Provider **23**、activity-alias **182** |
| 权限 | **84 项**（含推送、各厂商角标、OAID、位置、相机、通讯录等） |
| uses-feature | camera / camera.autofocus / **usb.host** |
| 资源规模 | layout 6,639、drawable-xxhdpi 6,005、drawable(XML) 4,865、anim 2,227、color 118、values 含 2,561 个 color |
| 签名/发布 | 正式签名包，含 HMS / agconnect / 个推 / 小米 / OPPO / vivo / 荣耀 推送 SDK |

**第一结论**：这是一个「超级 App」级别的工程，代码量约为本项目目标的 1~2 个数量级。它的绝大部分复杂度来自在线业务（账号、社交、推荐、直播、K 歌、商城、视频、广告、推送、多厂商适配），**与本地/网络挂载播放器无关**。参考时应只取「播放内核 + 曲库 + 播放 UI + 外设能力」这条主线。

---

## 2. 整体架构（分层）

```
┌──────────────────────────────────────────────────────────────┐
│ UI 层   Kotlin + Java + DataBinding + WMRouter(路由)            │
│         Activity/Fragment 799 个；RN(Hermes) 承担部分动态页       │
│         theme/ui、ui/component、module/player、module/lyric      │
├──────────────────────────────────────────────────────────────┤
│ 业务层  music/biz(14,253 类)、module/*、meta/virtual(762 类)     │
│         曲库 / 歌单 / 播放列表 / 本地音乐 / 音效 / 歌词 / 搜索       │
├──────────────────────────────────────────────────────────────┤
│ 服务层  多进程：主进程 / :play / :safemode / :leakcanary /        │
│         5 个 MpService 进程(elm~elq) + AIDL/IPC 跨进程            │
│         MainProcessPlayService、MusicCrossEngineIPCServicePlay   │
│         MediaSession + MediaButtonEventReceiver + MediaBrowser  │
├──────────────────────────────────────────────────────────────┤
│ 播放内核  com.netease.cloudmusic.android.sdk.playerengine.*      │
│         PlaybackEngine / AudioPlayerUnit / Playback /            │
│         PlaybackDataSource / SourceConfig / DataProvider /       │
│         PlaybackListener / PreloadCallback / MusicInfo ...       │
│         ↓ JNI                                                   │
│         libncmplayerengine.so(814K) / libncmaudioplayer.so(1.1M)│
│         libaudioplayer.so / libffmpeg.so(6.6M)                   │
│         libnecmMP3Dec / AACDec / FlacDec / MediaCodecJDec        │
│         libneaudioeffects.so + libneaudioeffect_core.so          │
│         libncmusbaudio.so(USB 独占) / libnmplaylist.so(播放队列)   │
├──────────────────────────────────────────────────────────────┤
│ 数据层  WCDB(加密 SQLite) + MMKV + Room/SQLite + OkHttp/Retrofit │
│         /Cronet + Fresco(图片) + 本地缓存策略模块                  │
└──────────────────────────────────────────────────────────────┘
```

关键工程特征：

- **多进程播放**：播放服务独立进程（`:play`），主进程通过 AIDL/Binder 与 `MainProcessPlayService` 通信；另有 5 个 `MpService` 进程用于插件/动态容器隔离。目的是播放稳定性与主进程崩溃隔离。
- **播放内核是自研 Native 引擎**，不是 ExoPlayer / Media3 / ijkplayer。已确认 JNI 符号：
  `Java_com_netease_cloudmusic_android_sdk_playerengine_AudioPlayerUnit_nativePlay/Pause/Seek/SetVolume/...`、
  `Playback_nativeAttachAudioEffect / nativeChangeQuality`、`PlaybackDataSource_native*`。
  内部走 FFmpeg + 自研 MP3/AAC/FLAC 软解 + MediaCodec 硬解 + AudioTrack / OpenSL ES / AAudio 输出。
  > **spike #1 实证补注（2026-11-05，`13 §3`）**：`PlaybackDataSource` 是预加载 / 引擎缓存配置类、**不是数据源接口**；真正的音源接口是 `DataProvider`（`getUrl()` / `getLocalPath()` / `openLocalFd()`），native 自行拉流、Java 层不喂字节流。本项目据此修正了本机取流链路（`16 §3`）。
- **播放队列在 Native 层**：`libnmplaylist.so` 暴露 `PlaylistJniBridge` 的 ~30 个 JNI 方法（快照、随机、插入、删除、最近播放、持久化），说明队列状态机是用 C++ 实现的。
- **UI 混合栈**：原生 + React Native(Hermes) + WebView 音频容器，另有大量 DataBinding 生成类。
- **资源强混淆**：类名大量为 `a/b/c`、资源名为 `a.webp`、`b21.xml`、`dq4.9.png`，`colors.xml` 里 2,561 个颜色名全是 `a/aa/b21/...`。**无法按文件名语义挑素材**，必须导出后人工预览映射。

---

## 3. 与本项目直接相关的能力清单

### 3.1 本地音乐 / 曲库扫描 ✅ 可重点参考

| 发现 | 证据 |
|---|---|
| 本地音乐独立业务模块 | `music/biz/localmusic/{activity,adapter,fragment,service,autofix}` |
| 扫描入口 | `ScanMusicActivity` |
| 后台任务 | `LocalMusicTaskService`、`LocalMusicMatchService`（曲目匹配/纠错） |
| 系统媒体库 | `MediaStore.Audio.{Media,Albums,Artists,Genres,Playlists}`、`MediaScannerConnection`、`MediaStoreUtils/MediaStoreHelper` |
| 自动修复 | `localmusic/autofix`（标签/文件名自动纠正） |
| Native 辅助 | `libnecmMediaInfo.so`（媒体信息）、`libpcmextract.so`、`libfpGenerate.so`（音频指纹） |
| 标签读写 | 字符串含 `_readTaglibTag` / `_writeTaglibTag`、ID3 相关类、`Lid3/*` |
| 音频格式常量 | `.mp3 .aac .m4a .wav .flac .ogg .opus .wma .ape .aiff .dsf .dff .cue .wavpack`；MIME `audio/flac`、`audio/x-flac`、`audio/x-ape`、`audio/mpeg`、`audio/mp4`、`audio/mp4a-latm`、`audio/ogg`、`audio/wav` |

可借鉴：**MediaStore + 目录递归双通道扫描、增量扫描、标签兜底为文件名、扫描白名单/时长过滤、自动修复、扫描进度与断点**。
不建议照搬：Native 指纹匹配、在线曲目云端匹配（我们无服务端）。

### 3.2 DLNA ✅ 已有完整实现（作为控制器）

| 项 | 内容 |
|---|---|
| 核心类 | `core/dlna/DlnaImpl`（含 ~25 个内部类）、`core/idlna/IDlnaInterface` |
| 业务层 | `module/player/utils/dlna/DeviceConfig`、`DlnaHelper`、`DlnaPlayback`、`DlnaTask`、`DlnaThread`、`DlnaSearchDialog` |
| 协议 | 原生 **SSDP + UPnP/1.0 SOAP**，基于 `java.net.Socket` 自己实现；字符串含 `UPnP/1.0`、`urn:schemas-upnp-org:device:`、`urn:schemas-upnp-org:service:`、`upnp:rootdevice`、`AVTransportURIMetaData`、`SetAVTransportURI`、`RenderingControl` |
| 依赖 | **未使用 Cling / jUPnP**（`cling` 命中均为 `Lifecycling` 等误匹配） |
| 角色 | **DMC（控制点 / 投屏到电视、音箱）**，`DlnaPlayback` 把歌曲 URL 推给 DMR 播放；未见 DMR 渲染器实现 |
| 状态机 | `DLNA_SELECT_DEVICE=180 / CHANGE_VOLUME=190 / DEVICE_QUERY=170 / GET_POSITION=230 / LOST_CONNECTION=220 / FASTSEARCH=250 / SWITCH=240` 等 Handler 消息码 |

> 关键推论：投屏时 DMR 无法访问手机的 `content://`、WebDAV 鉴权或 strm 私链，所以**手机端必须内置一个本地 HTTP 流代理**（把任意数据源转成 DMR 可访问的 URL）。这是架构文档必须落的一块，参考 APK 里对应 `fileDataSource uri` 相关字符串。

### 3.3 蓝牙 / 音频输出 ✅ 以系统能力为主

| 项 | 内容 |
|---|---|
| 模块 | `module/bluetooth/*`：`BluetoothStateImpl`、`IBluetoothStateService`、`OnBluetoothStateChangeListener`、`channel/ble/*`、`utils/BluetoothDeviceA2DPAudioOutputCallback` |
| 私有协议 | 有 BLE 自定义传输（`transport/model/{Header,Packet,Payload,TransConfig}`），用于特定 IoT 耳机；`module/iot/sonyearphone/*` 为索尼耳机适配 |
| 车机 | `module/hicar`（华为 HiCar）、`module/ucar`（`MediaBrowserService` / Android Auto）、`CarPlay` 相关 UI |
| 编解码识别 | 字符串含 `LDAC`、`aptX / aptX HD`、`HFP`、`A2DP connected`、`isBluetoothA2dpOn`、`hiRes`、`HIRES` |
| 独占输出 | `libncmusbaudio.so` + `USB_PERMISSION` + `uses-feature: android.hardware.usb.host`，有「USB 独占」「淡入淡出开关」逻辑 |
| 线控 | `MediaButtonEventReceiver`、`MediaSession`、桌面小组件、`AutoplayBluetoothA2DPReceiver` |

对我们的结论：**90% 的蓝牙体验来自 Android 系统 A2DP + MediaSession + AVRCP**，私有 BLE / 厂商耳机 SDK / HiCar 全部可以不做，属于典型的「大厂适配负担」。

### 3.4 音乐格式解析与音效

- 解码：FFmpeg(6.6MB) + 自研软解 `libnecmMP3Dec/AACDec/FlacDec` + `MediaCodecJDec` + `libnecmMediaInfo`。
- 音效：`module/player/audioeffect/core/*`、`media/audiofx/effects/AudioEffects*`、`libneaudioeffects.so`、`libneaudioeffect_core.so`，JSON 描述的 EQ/Reverb/Compress/Limiter/Flanger/Chorus/Peq 等；`libneaudiovisualizers.so` 提供频谱。
- 歌词：`module/lyric/*`、`LRC`、`.lrc`、`klyric`、`lyric1/lyric2`，`libKaraokeGradeGenerator.so`。
- 高解析：`hiRes/HIRES` 标记与 USB 独占输出。

### 3.5 ❌ 参考 APK **不具备** 的能力（本项目需从零实现）

| 需求 | 证据 |
|---|---|
| WebDAV 挂载 | 全量 110 万条字符串中 `webdav` **仅 1 次命中**，无任何 WebDAV 客户端类 |
| SFTP / NFS / FTP 挂载 | 无 `jsch`/`sshj`/`nfs`/`smb` 客户端；仅通用字符串 `ftp://` 1 处 |
| `.strm` 音乐文件 | `strm` 仅 2 次命中且均为无关字符串（`StrMessageConvert`、`strStrMap`），**无 strm 解析逻辑** |
| `.m3u/.m3u8/.pls` 播放列表 | `m3u8` 1 次（视频用），无音频播放列表解析 |

> 这四项是本项目的**核心差异点**，也是产品文档里必须单独立项、在架构文档里统一抽象的部分。

---

## 4. 视觉风格与可复用素材

| 项 | 内容 |
|---|---|
| 主色 | `#FF3A3A`（59 处引用）、`#FF2C55`（57 处）、`#FE2C55`、`#EF424C`；中性 `#FF313131` / `#FFF5F5F5` / `#FF999999` |
| 主题 | `AppTheme`（parent `@style/b4`，开启 `windowDrawsSystemBarBackgrounds`）；`values-night` 等深色变体；`theme/core`、`theme/ui` 模块 |
| 字体 | `assets/bamboo.ttf`、`assets/roboto_en_large.ttf`、`assets/street-regular.otf`、`res/font/a.ttf`、`res/font/b.ttf` |
| 图标 | `res/drawable-xxhdpi-v4` 6,005 个（多为 `.webp`），`res/drawable` 4,865 个（多为 XML vector/selector），`res/mipmap-xxhdpi-v4` 251 个启动图标 |
| 播放控件命名 | `dolphin_surface_play_1/2.xml`、`dolphin_surface_pause.xml`、`dolphin_surface_next.xml`、`dolphin_surface_lyric.xml`、`dolphin_outline_play_1.xml`、`dolphin_mix_playlist.xml`、`t_miniplaybar_*`、`note_btn_{play,pause,next}*.webp`、`stat_notify_music.webp`、`shortcut_local_music.webp` |
| 动画/动效 | `res/anim` 2,227 个、`assets/music_play_animation.json`（Lottie 唱片播放动画）、`assets/wave.json`、`assets/disk.png`、`assets/lomo.png` |

**注意**：除 `dolphin_*` / `t_*` / `note_*` 等少数前缀外，绝大多数资源名已被混淆（`a.webp`、`ab.png`…），需导出后建立 `<原混淆名> → <语义名>` 映射表再使用。

---

## 5. 「照搬」可行性评估（重要）

用户原话：「原生支持 DLNA、蓝牙、音乐格式解析，实现细节照搬 NeteaseCloudMusic」。按落地可行性分三档：

| 档位 | 内容 | 可行性 |
|---|---|---|
| **A. 直接照搬（推荐）** | 能力清单、交互流程、状态机语义、屏幕结构、视觉度量（配色/圆角/间距/唱片动画）、协议行为（SSDP/SOAP/AVRCP 本身是公开标准）、扫描策略、错误处理文案 | ✅ 完全可行，本项目按此执行 |
| **B. 用开源实现重写（推荐）** | 播放内核 → Media3/ExoPlayer（+ FFmpeg 扩展）；DLNA → 自写 SSDP/SOAP 或 jUPnP；蓝牙 → 系统 A2DP + MediaSession；标签 → JAudioTagger / MediaMetadataRetriever；WebDAV → Sardine / OkHttp 自定义 | ✅ 可行，功能等价、代码自主 |
| **C. 直接复制代码 / .so（不推荐）** | 反编译 smali 或复用 `libncmplayerengine.so` 等 167 个 native 库 | ❌ 不可行：① 著作权与 EULA 风险；② 类名/资源名强混淆，smali 几乎不可读；③ native 库强依赖其内部 Java 类、初始化链与私有协议，且仅 arm 架构；④ 会引入完全无法维护的黑盒 |

**建议口径**：把「照搬实现细节」理解为 **A + B** —— 照搬行为与体验，重写实现。此项需用户确认，已列入产品文档「待确认清单」第 1 项。

---

## 6. 合规与风险提示

1. 本 APK 为第三方正式签名商业应用，**代码、native 库、图标、字体、文案、Logo、应用名均受著作权/商标保护**。
2. 素材仅建议作为**开发期占位**；正式发布前必须替换为自有素材，且**包名不得使用 `com.netease.cloudmusic`**，不得使用「网易云音乐」及其 Logo/图标/品牌文案。
3. 本报告由静态分析得出，用于自研功能设计参考；不包含任何可直接编译进项目的第三方代码。
4. 参考 APK 的 84 项权限中，本项目只需约 10~12 项（详见产品文档 §9），不要照抄 Manifest。

---

## 7. 分析产物索引

| 路径 | 内容 |
|---|---|
| `tools/` | 集中后的 apktool 2.10.0 / smali 3.0.10 / baksmali / 7z / adb / framework / 封装脚本 |
| `analysis/decoded/` | apktool 解出的可读 `AndroidManifest.xml`、`res/values/*`、`assets/`、`lib/`、20 个 dex |
| `analysis/strings/*.strings.txt` | 20 个 dex 的全部字符串表（约 110 万条） |
| `analysis/manifest-and-strings-report.txt` | 组件、权限、包名前缀、关键词命中、URL 的完整报告（1,641 行） |
| `analysis/apk-filelist.txt` | APK 全文件清单 |
| `analysis/dex_strings.py` / `analysis/summarize.py` | 可复用的分析脚本（已同步一份到 `tools/dex-strings.py`） |
| `reference/openlist/` | OpenList-zig 驱动源码只读快照（20 个文件 + `LICENSE`，AGPL-3.0），供本项目开发期对照接口语义，不参与构建 |

> AI生成
