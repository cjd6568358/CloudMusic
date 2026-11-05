# 05 · Native 库实测分析（ELF / JNI 实证）

**日期**：2026-11-04
**样本**：`D:\workspace\CloudMusic\NeteaseCloudMusic.apk` 解出的 `analysis/decoded/lib/arm64-v8a/`，共 **167 个 `.so`**
**方法**：Python 直接解析 ELF64 Program Header → `PT_DYNAMIC` → `DT_NEEDED` / `DT_STRTAB`，并扫描整个文件提取 `Java_*` JNI 符号字符串；不使用外部反编译器
**性质**：**实测数据**，用于校准架构与开发文档中的 native 假设

> 本文是 03-04 §6.2、03-06 §6、03-12 §2/§4 的实证依据。发现并纠正了初稿的一处**事实性错误**（§3）。

---

## 1. 总体数据

| 项 | 值 |
|---|---|
| so 总数 | 167（仅 `arm64-v8a`，与架构 A5 一致） |
| 最大 | `libffmpeg.so` 6,502 KB |
| 播放相关 | `libncmplayerengine.so` 814 KB / `libncmaudioplayer.so` / `libaudioplayer.so` 191 KB |
| 队列 | `libnmplaylist.so` 423 KB |
| 解析出的 JNI 符号 | 见 §2~§5 各表 |
| 常见系统依赖 | `liblog.so` / `libm.so` / `libdl.so` / `libc.so` / `libc++_shared.so` / `libandroid.so` / `libz.so` |

---

## 2. 播放内核依赖闭包（实测，校准 03-06 §6 / 03-12 §2）

### `libncmplayerengine.so`（814 KB）

**DT_NEEDED（13 项：6 个同包私有库 + libffmpeg + 6 个系统库；2026-11-04 复核修正计数，原写「12 项」）**：

```
liblog.so              libandroid.so
libncmaudioplayer.so   libncmusbaudio.so        ← 同包私有库
libNMCommonCache.so    libneaudioaiif.so         ← 同包私有库
libneaudioeffects.so   libneaudioeffect_core.so  ← 同包私有库
libffmpeg.so                                    ← 已单独打包
libm.so  libc++_shared.so  libdl.so  libc.so     ← 系统库
```

**JNI 导出符号 121 个**，全部形如
`Java_com_netease_cloudmusic_android_sdk_playerengine_AudioPlayerUnit_native*`
（样例：`nativeCreate` / `nativeDestroy` / `nativePlay` / `nativeGetDuration` / `nativeGetPlayingTime` / `nativeApplyAudioEffect`）。

> **结论**：架构 §4.1「JNI 符号硬绑定 Java 类名」得到实证；依赖闭包含 **6 个竞品私有库 + libffmpeg**（2026-11-04 复核修正：原写「5 个私有库」），C1 的 `readelf -d` 递归闭包检查必须覆盖它们，漏一个即 `dlopen` 失败。

### `libnmplaylist.so`（423 KB）

**DT_NEEDED**：`liblog.so` / `libsqlite3x.so` / `libm.so` / `libdl.so` / `libc.so`
**JNI 导出 31 个**，包名是 **`com.netease.cloudmusic.playlist.core.PlaylistJniBridge`**（不是 `android.sdk.playerengine.*`），样例：`nativeAppendMusicItems` / `nativeClearAll` / `nativeEnterDerivedMode` / `nativeDeleteRecentPlaylist`。

> ⚠️ **R8 keep 规则必须额外覆盖 `com.netease.cloudmusic.playlist.core.**`**，否则队列 JNI 会崩（03-12 §2 的示意规则已按本文补校准，见 §7）。

### `libNMCommonCache.so`（302 KB）

**JNI 导出 58 个**，包名 `com.netease.cloudmusic.sdk.NMCommonCache.*`（`NMBufferSource` / `NMCacheBase` / `NMVirtualFile`）。被 `libncmplayerengine.so` 直接依赖，同样需要 keep。

### `libaudioplayer.so`（191 KB）

**DT_NEEDED**：`libffmpeg.so` / `libc++_shared.so` / `liblog.so` / `libc.so` / `libm.so` / `libdl.so`
**JNI 导出 2 个**：`com.netease.cloudmusic.media.NEAudioDevice$NEAudioDeviceOnPlaybackPositionUpdateListener_*`。

---

## 3. ★ 实测纠错：`libscannative.so` 不是音乐扫描库

初稿（00 报告之外的 02 §2、03-04 §6.2、03-12 §4、04 报告 R5）曾把 `libscannative.so` 列为「本地音乐扫描 / 标签解析」的照搬候选。**实测证明这是错的**：

| 项 | 实测值 |
|---|---|
| 大小 | 2,630 KB |
| DT_NEEDED | `libz.so` / `liblog.so` / `libm.so` / `libdl.so` / `libc.so`（纯系统库 + zlib） |
| JNI 导出 | **7 个，全部属于 `com.huawei.hms.scankit.util.OpencvJNI`** |
| 符号样例 | `QRCornerDetect` / `QuadFitting` / `adaptiveBinary` / `imageResize` / `imageRotate` / `multiBarcodeDetect` |

即：**华为 HMS ScanKit 的扫码/二维码 OpenCV 封装**。名字里的 `scan` 指「扫码」，不是「扫描音乐文件」。

### 与元数据真正相关的库（同样不可用）

| 库 | 大小 | JNI 导出 | 线索 | 判定 |
|---|---|---|---|---|
| `liblive_disk_scanner.so` | 565 KB | **0** | `DiskScannerDelegate` / `File system scan failed` / `initScanner` | 纯 C++ 内部库，无 Java 入口 |
| `libnecmMediaInfo.so` | 358 KB | **0** | DT_NEEDED 含 `libnecmMediaCommon.so` | 纯 C++ 内部库 |
| `libnecmMediaCommon.so` | 2,076 KB | **0** | `PARSE_TAGGING` / `GetMPEGAudioFrameSize` | 标签解析确实在此，但无 JNI 入口、闭包大 |
| `libfpGenerate.so` | — | 0 | ID3v2.3 帧解析字符串 | 音频指纹（PRD §8 明确不做） |
| `libffmpeg.so` | 6,502 KB | 0 | Vorbis/FLAC demuxer（75 处命中） | 已被播放内核舱依赖，不重复引入 |

**全库标签线索扫描结果**：含 `ID3v2 / APIC / Vorbis / METADATA_BLOCK / .mp3 / .flac` 等 ≥3 处命中的 so 只有 `libffmpeg.so`(75)、`libfpGenerate.so`(55)、`libnertc_sdk.so`(33)、`libavbase.so`(3) 等，全部是解码/音视频 SDK，**没有一个提供可直接从 Java 调用的标签解析 API**。

### 结论与定案

> **扫描与标签解析不照搬任何竞品 native 库**，一律采用 `JAudioTagger`（LGPL）+ Android `MediaMetadataRetriever`，扫描逻辑自研。

理由：① 相关库 JNI 导出为 0，照搬后无法从 Java 层调用，需自写 JNI 桥并拖入庞大闭包；② 用户 A1「优先复制 so」针对的是**有明确 JNI 入口的播放内核**，扫描层无匹配场景，照搬原则不适用（架构 §0 A1）；③ `TagReader` 接口不变，未来可替换实现。

**已据此修正**：02 §2（`:scanner` 来源）、03-04 §6.2/§13 B16/§14 T21/§17、03-12 §4（取消 scannative 单独 ABI 验收）、04 报告 R5。

---

## 4. `libffmpeg.so`（6,502 KB）

**DT_NEEDED**：`libm.so` / `libz.so` / `libdl.so` / `libc.so`（**无竞品私有依赖**，可独立加载）
**JNI 导出 0**：纯 C/C++ 解码库，由 `libncmplayerengine.so` / `libaudioplayer.so` 通过 dlopen 调用。

> 这也是「逃生舱 `:engine-opensource`（FFmpeg + Media3/ExoPlayer）」在二进制层面可行的依据：FFmpeg 本身不依赖竞品私有库。

---

## 5. 其他播放相关库速览

| 库 | 大小 | JNI | 备注 |
|---|---|---|---|
| `libnecmMP3Dec.so` | 81 KB | 0 | 自研 MP3 软解，纯 C++ |
| `libnecmFlacDec.so` | 17 KB | 0 | FLAC 软解（`FrameSync` / `FillFrameBuffer`） |
| `libnecmMediaCodecJDec.so` | — | — | MediaCodec 硬解桥 |
| `libncmusbaudio.so` | — | — | USB 独占输出（V0.1 不做，但被引擎依赖，**必须打包**） |
| `libneaudioeffects.so` / `libneaudioeffect_core.so` | — | — | 音效（V0.1 不做，但被引擎依赖，**必须打包**） |

> ⚠️ 重要：**「V0.1 不做某功能」不等于「可以不打包对应 so」**。只要出现在 `DT_NEEDED` 闭包里就必须随舱打包，否则 `dlopen` 失败。C1 检查以**实测闭包**为准，不以功能范围为准。

---

## 6. 对 ABI 验收与 R8 的影响

### 6.1 C1 检查的输入（03-12 §3 / 03-06 §6）

```
① JNI 符号对照：以 §2 实测的 121(engine) + 31(playlist) + 58(NMCommonCache) + 2(audioplayer) 为基线
② DT_NEEDED 闭包：以 §2 的 12 项 + §4/§5 各项为基线，递归到系统库为止
③ 167 个 so 齐全；特别注意 USB / 音效 / sqlite3x 这类「功能不用但要打包」的库
```

### 6.2 R8 keep 规则校准（替换 03-12 §2 的示意包名）

```proguard
-keep class com.netease.cloudmusic.android.sdk.playerengine.** { *; }   # engine 121 符号
-keep class com.netease.cloudmusic.playlist.core.** { *; }               # playlist 31 符号 ★实测新增
-keep class com.netease.cloudmusic.sdk.NMCommonCache.** { *; }            # cache 58 符号 ★实测新增
-keep class com.netease.cloudmusic.media.** { *; }                        # audioplayer 2 符号 ★实测新增
-keepclasseswithmembernames class * { native <methods>; }
# 以下包名需 jadx 反编译后按实际结果校准（见 §7）：
-keep class com.netease.cloudmusic.module.player.** { *; }
-keep class com.netease.cloudmusic.receiver.mediabutton.** { *; }
-keep class com.netease.cloudmusic.activity.PlayerActivity { *; }
```

---

## 7. 仍需 jadx 的部分（本文无法覆盖）

ELF 解析只能看到**二进制侧**。以下内容仍须 jadx 反编译 `classes*.dex` 后确定，属于 03-12 §3 的 R2 范畴：

1. ~~`:media-legacy` 各焦点类的**准确包名**~~ **部分收口（2026-11-05，`13 §8`）**：按键 / 会话 / 焦点数据类已实证精确类名并写入 `engine-legacy/proguard-rules.pro`；但 `AudioFocusKeeper` / `AudioFocusHolderWrapper` 只是字符串常量、无类定义，**焦点状态机的真实混淆类名仍待 jadx**（`13 §6 #8`）。03-12 §2 的示意包名已收窄为精确条目。
2. ~~Java SDK 层对每个 JNI 类的**初始化链与依赖闭包**~~ **部分收口（2026-11-05，`11 §9`）**：一层边界已用纯 JVM 等价复核到类级清单（84 个 JNI 种子 + **160 个真实边界类**，148 empty-stub / 12 keep-signature），不必等 jadx；**更深层初始化链与一次性 ABI 验收仍需 jadx / 真机 G01**。
3. `PlayerActivity` 所在的 dex 与其引用的**资源 ID**（已知 `public.xml` 全量 65,974 条、anim 2,232 条，ID 段 `0x7f010000`~`0x7f0108b7`；反查规程见 `03-10 §3` 与 `06-资源索引与映射.md`）。
4. 播放页自定义唱片 View 的类名与构造签名（R8 keep 的 `View` 子类规则需据此校准）。

> 本文已把「二进制可确定的部分」全部定案；剩余项是**机械反查工作**，不再是文档中的不确定假设。

---

## 8. 结论

| 原假设 | 实测 | 处置 |
|---|---|---|
| `libscannative.so` 是音乐扫描库 | ❌ HMS ScanKit 扫码库 | 排除；03-04 §6.2 定案 JAudioTagger |
| 标签解析可照搬 native so | ❌ 相关库 JNI 导出为 0 | 取消 03-12 §4 的单独 ABI 验收 |
| 引擎只依赖少数几个 so | ❌ 13 个直接依赖、6 个私有库 + libffmpeg（2026-11-04 复核修正计数） | C1 按实测闭包检查 |
| 队列 JNI 与引擎同包 | ❌ 在 `playlist.core` 包 | R8 keep 补 3 个包名 |
| FFmpeg 可独立用于逃生舱 | ✅ 无私有依赖 | 保留 `:engine-opensource` 路径 |

> AI生成
