# NOTICE · 第三方组件与来源声明

**项目**：CloudMusic（暂定）
**许可证**：AGPL-3.0（见 `LICENSE`）
**最后更新**：2026-11-04

> 本文件随仓库发布，用于声明第三方开源依赖、参考样本与二进制来源。
> **当前状态**：项目处于文档阶段，仓库内**尚未包含**任何第三方代码、native 二进制或竞品素材；以下为按文档规划引入时的登记口径。

---

## 1. 第三方开源依赖（计划引入）

| 组件 | 许可证 | 用途 | 上游 |
|---|---|---|---|
| AndroidX / Room / Compose / Material3 | Apache-2.0 | UI、持久化 | https://developer.android.com/jetpack |
| Kotlin / kotlinx-coroutines | Apache-2.0 | 语言、异步 | https://github.com/JetBrains/kotlin |
| Hilt / Dagger | Apache-2.0 | 依赖注入 | https://github.com/google/dagger |
| OkHttp 4.x | Apache-2.0 | 网络（WebDAV / SOAP / 直链） | https://github.com/square/okhttp |
| Coil | Apache-2.0 | 封面加载与缓存 | https://github.com/coil-kt/coil |
| nanohttpd | **BSD-3-Clause**（Modified BSD License） | DLNA 本地流代理 | https://github.com/NanoHttpd/nanohttpd |
| JAudioTagger | **LGPL** | 音频标签解析 | http://www.jthink.net/jaudiotagger/ |
| MediaMetadataRetriever | Android 系统 API | 时长/音频参数兜底 | Android SDK |

**JAudioTagger（LGPL）使用条件**：以独立库（AAR/JAR）形式引入，不修改其源码并合入本项目自有类；保留其许可声明与源码获取途径（上方上游地址）；若未来使用修改版，须按 LGPL 开放对应修改。替换方案：`MediaMetadataRetriever` + 自研轻量标签解析器，`TagReader` 接口不变。详见 `docs/03-模块开发文档/03-12 构建发布与合规.md §6`。

上述许可证均与 AGPL-3.0 兼容；各依赖的完整许可文本随其分发包提供。

---

## 2. 参考样本声明（NeteaseCloudMusic.apk）

| 项 | 值 |
|---|---|
| 文件 | `NeteaseCloudMusic.apk`（仓库根目录） |
| 版本 | 9.6.20（versionCode 9006020） |
| 包名 | `com.netease.cloudmusic` |
| 权利 | **归原权利人所有**；本项目仅作个人学习与自用参考 |

本项目对该 APK 做过**静态解包与字符串/清单分析**（未运行、未动态调试、未修改原包）。分析产物见 `analysis/`，报告见 `docs/00-参考APK分析报告.md` 与 `docs/05-Native库实测分析.md`。

> 该 APK 的代码、native 库、图标、字体、文案、Logo、应用名均受著作权/商标保护。**本项目不声称对其拥有任何权利或授权。**

---

## 3. 二进制与素材来源（实际引入状态，2026-11-08 核对）

> 本节此前写「当前未引入」，与 `12 §32` 起的实现不符，属**合规声明错误**，现按仓库实际状态更正。
> 参考 APK 的来源、版本与权利声明见 §2；以下内容的权利一律归原权利人，本项目不声称原创或已获授权。

| 类别 | 来源 | 实际状态 | 登记位置 |
|---|---|---|---|
| 播放内核 native 库 | 上述参考 APK | **已引入 18 个 `arm64-v8a` so（18.5 MiB）**，随 debug APK 打包；提取侧 167 个是上限，不等于打包集 | `engine-legacy/src/main/jniLibs/arm64-v8a/`、`assets/ENGINE-FINGERPRINT` 与 `app/src/main/assets/ENGINE-FINGERPRINT`（18 行 SHA-256，C6 dev/release PASS） |
| 播放内核 Java SDK 层 | 上述参考 APK，jadx 反编译重建 | **已引入 12 个精确 JNI 真类 + slot**（`12 §35` 登记簿 51 行），编译进 `:engine-legacy` | `engine-legacy/src/main/java/com/netease/...`、`docs/12 §10.4`、`analysis/jadx-jni-classes/` |
| 媒体会话 / 音频焦点层 | 自研为主，竞品语义参照 | **未引入竞品二进制或 dex**；`:core-media` 为纯 JVM 自研焦点状态机，平台接线在 `:app/PlaybackService`；`:media-legacy` 舱体未建立 | `docs/03-07`、`docs/12 §41 / §45` |
| 播放页 layout / anim / drawable / 自定义 View | 上述参考 APK，资源 ID 反查导出 | **已导出 61 项 / 51 文件（320,569 字节）**，以 `legacy_` 前缀存于 assets 暂存区，**未接入 res、未参与 aapt2** | `docs/assets-mapping.csv`（61 行 `replaced=false`，C4 release FAIL）、`app/src/main/assets/legacy-player/`、`docs/06`、`docs/14` |

**素材替换承诺**：上述素材仅作为**开发期占位**。首次对外分发前必须全量替换为自有素材，且 `assets-mapping.csv` 每行 `replaced=true`；包名、应用名、图标、启动页、文案不得含「网易云音乐」及任何品牌特征。该项为发布阻断项（`03-12 §8` #1/#2/#7）。
当前 `app/src/main/` 无 `res/`、Manifest 无 `android:label` / `android:icon`，应用名与图标为系统默认：**未含第三方品牌特征，但也未自定义**（V0.1 现状，见 `12 §61.32`）。

> **★ `12 §61.36` 分发合规补充（2026-11-08 审计）**：`app/src/main/assets/legacy-player/`
> 的 **51 个文件随 debug APK 原样分发**（`assets/` 不参与 aapt2，但会进包），
> 其中 **8 个 layout XML 含 44 处 `com.netease.*` 类名 / 20 个去重类**
> （含带品牌名的 `NeteaseMusicSimpleDraweeView`）。C2 检查器跳过整个暂存目录后
> 才 PASS，**该 PASS 不等于 APK 内无竞品内容**。证据
> `analysis/tl-l3-assets-compliance-audit-2026-11-08.md`。
>
> **★ D-146（2026-11-08 21:49 用户决策）**：本项目**仅供个人自用 / 侧载**，
> 用户决定**竞品素材直接沿用、不做替换**，不公开发布仓库 / 不分发 APK / 不上架。
> 因此上述分发事实在个人自用范围内**按已知状态保留**，素材替换与暂存区清除
> 降级为「未来若公开发布才执行的备选路径」（见 `12 §61.37`）。
> **权利归属声明不变**：本文件不声称这些二进制 / 素材为本项目原创或已获授权。

---

## 4. 合规风险与处置（如实声明）

项目以 AGPL-3.0 开源，但第 3 节所列二进制与素材为**专有、权利状态不明**的第三方内容，二者在权利上并不自洽。

| 场景 | 处置 |
|---|---|
| 个人自用 / 侧载（当前目标） | 风险可控；本 NOTICE 如实声明来源与权利状况 |
| 公开发布仓库 / 分发 APK | ① 完成素材全量替换 ② 法务评估专有二进制的可分发性 ③ 若不可分发，启用架构预留的 `:engine-opensource`（FFmpeg + Media3/ExoPlayer，实测 `libffmpeg.so` 无竞品私有依赖，见 `05 §4`）替换播放内核，上层零改动 |
| 上架应用商店 | 额外处理隐私政策、权限说明与品牌审查 |

**不得**在公开仓库中声称这些二进制或素材为本项目原创、或已获原权利人授权。

---

## 5. 分析工具

| 工具 | 版本 | 来源 |
|---|---|---|
| apktool | 2.10.0 | 本机既有环境，已归拢至 `tools/` |
| smali / baksmali | 3.0.10 | 同上 |
| 7-Zip / adb / framework | — | 本机既有环境 |

`tools/` 目前**不含 jadx**；按用户既有环境从打包机补入，或以现有 baksmali 手工重建（`docs/06-资源索引与映射.md §3`）。

---

## 6. 参考源码只读快照

`reference/openlist/` 内 20 个源码文件 + `LICENSE`，是 **OpenList-zig**（OpenList，AList 社区分支）上游源码的**只读快照**（源码本体在各成员本机，位置因机器而异），仅用于开发期对照数据源驱动的接口语义，**不编译进本项目、不参与构建**。

| 项 | 值 |
|---|---|
| 上游 | OpenList-zig 上游源码（各成员本地位置不同；项目本体保持原位，未移动） |
| 许可证 | **AGPL-3.0**（与本项目一致，完整文本见 `reference/openlist/LICENSE`） |
| 权利 | 归原作者所有；本项目不声称原创或授权 |
| 内容 | `internal/driver/*`、`drivers/{local,webdav,strm,sftp,ftp}/*`、`zig-src/src/{driver,model}.zig`、`zig-src/src/drivers/{local,webdav,strm}.zig` |

> 若后续公开发布本仓库，此快照按 AGPL-3.0 保留版权声明与许可证即可；也可删除（文档仅将其作为对照材料，运行时不依赖）。
