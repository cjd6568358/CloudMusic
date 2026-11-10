# 全格式播放内核 · 现状盘点与分阶段施工单（2026-11-09 第七十一批 / `12 §61.44`）

> **触发（用户 2026-11-09 13:01 三条指令）**
> 1. 「诚实边界待你拍板：P0 现状是『native so 原样复用 + Java/JNI 层 jadx 重建源码 + slot』，**没问题**」→ 该口径**用户已确认接受**，不再追求整包 dex 复用，从「待拍板」移除。
> 2. 「**目前首要目标是调通全格式播放内核**」→ 开发优先级重排：播放页 1:1 对齐让位。
> 3. 「**最后是按照清单 todo 顺序开发**」→ 内核阶段按本报告 §4 的 **F1→F5** 顺序推进；内核完成后按 `docs/17 §4` 的 P2→P3→P4 顺序（每个模块先出子清单再自研）。
>
> **本轮性质**：只读实证 + 规划固化 + 文档重排，**零发布基线变更、零源码改动、零设备改动**。
> 机器证据：`tools/l3_audio_format_audit.py` → `analysis/l3-audio-format-audit.json`（纯标准库、可复跑）。

---

## 1. 结论先行

- 用户已把 **P0 诚实边界**拍板定案（接受 native so + jadx 重建 + slot），`docs/17 §1.2` 的「若要求连 Java 层也原样复用 dex 需重新拍板」不再是待办，仅保留为历史口径说明。
- **「全格式播放内核」不是「已支持但没测」，而是「native 能力远大于验证范围，且外围 6 份白名单互相打架」**：
  - `libffmpeg.so` 编入 **75 个 `ff_*_demuxer` / 100 个 `ff_*_decoder`**（其中 69 个音频相关），另有 3 个专用解码库；
  - A3 G01 真机**只验证过 4 种格式**（mp3 / flac / m4a(aac) / ogg(vorbis)），且**全是 URL 回环源的 12s 正弦音夹具**；
  - 工程里 **6 处格式白名单互不一致**（14 / 8 / 9 / 9 / 6类 / 6类），`scanner` 与 `strm` 会直接**过滤掉** ape / dsf / dff / aiff / wv / tak。
- **因此首要目标的定义不是「写新解码器」，而是「统一格式真相源 → 修正格式识别链 → 逐格式双路径真机验收 → 根治已知掩盖」**。解码器本身是竞品二进制，P0 冻结不动。
- 本轮**不动源码**：先把清单、证据、顺序钉死，下一轮起按 F1→F5 逐个施工，符合用户「按清单 todo 顺序开发」。

---

## 2. 现状实证（一手证据）

### 2.1 六处格式白名单（全部由 `tools/l3_audio_format_audit.py` 从源码正则解析）

以 `core-model/ObjectType.AUDIO_EXT`（**14 项**，最全）为基准：

| # | 位置 | 项数 | 相对基准缺失 |
|---|---|---|---|
| 1 | `core-model/.../ObjectType.AUDIO_EXT` | **14** | — |
| 2 | `scanner/.../ScanRules.audioExt` | **8** | `aiff, ape, dff, dsf, tak, wv` |
| 3 | `datasource-strm/.../StrmPaths.defaultFilterTypes` | **9** | `aiff, ape, dff, dsf, tak, wv`（多 `alac`） |
| 4 | `engine-bridge/.../MimeHint.MAP` | **9 音频 + 2 文本** | `aiff, ape, dff, dsf, tak, wv`（多 `alac`） |
| 5 | `feature-player/.../PlayerController.mimeHintOf` | **6 类 / 7 字面量** | `aiff, ape, dff, dsf, tak, wma, wv` |
| 6 | `output-dlna/.../DidlLiteBuilder.mimeOf` | **6 类 / 7 字面量** | `aiff, ape, dff, dsf, tak, wma, wv` |

**影响面**：#2 决定扫描器能否把文件收进曲库（缺项直接 `IGNORED`）；#3 决定 strm 过滤；#4/#5 决定 `EngineSource.mimeHint`；#6 决定 DLNA `protocolInfo`。未知格式一律落到 `application/octet-stream`。

### 2.2 native 解码能力（`libffmpeg.so`，6,658,592 字节）

- **75 个 demuxer**：`aac, amr, ape, asf, av3a, avi, dsf, dts, flac, flv, h263, h264, hevc, kux, live_flv, m4v, matroska, mov, mp3, mpegps, mpegts, mpegtsraw, mpegvideo, ogg, pcm_alaw/f32be/f32le/f64be/f64le/mulaw/s16be/s16le/s24be/s24le/s32be/s32le/s8/u16be/u16le/u24be/u24le/u32be/u32le/u8/vidc, sln, wav, webm_dash_manifest` + 30 个 `image*_pipe`。
- **100 个 decoder**，其中 69 个音频相关；确认存在的关键词：`mp3, aac, flac, ape, alac, vorbis, opus, wma, pcm_*, amr, adpcm_*, celt, dts`。
- **3 个专用解码库**：`libnecmFlacDec.so` / `libnecmMP3Dec.so` / `libnecmAACDec.so`。
- **诚实边界**：`tak / tta / wavpack / dsf / dff / aiff / shorten / truehd / mlp` 有 demuxer 或字符串痕迹，但**未确认对应 `ff_*_decoder` 编入**；demuxer 存在 ≠ decoder 编入 ≠ 真机可播，结论只能由 F3 真机矩阵给出。`libncmplayerengine.so` 只有少量格式字符串，**不是**解码器所在库。

### 2.3 A3 真机已验证范围（`analysis/a3-phase-ab-evidence-2026-11-07.md §26`）

```
G01 四格式（ffmpeg/imageio 生成 12s 正弦音，URL 源，自动连播链）：
12:16:11 prepare g04-flac → Open codec for flac   → Write start play → 12.1s 完成
12:16:29 prepare g05-m4a  → Open codec for aac    → Write start play → 12.1s 完成
12:16:41 prepare g06-ogg  → Open codec for vorbis → Write start play → 12.2s 完成
mp3 前证（E17~E24 七轮）。进程全程存活。
```

→ **4 种**：mp3 / flac / m4a / ogg；**仅 REMOTE 路径**（`adb reverse tcp:8765` + PC `python -m http.server`）；**LOCAL 源（SAF / MediaStore 直读）全格式能力未验证**。

### 2.4 格式识别链与两条取流路径（关键风险）

`engine-legacy/.../EngineAdapter.kt:405-416`：

```java
// 12 §56：so 的 JNI 字段表只从 ResourceInfo 读 extension
val extension = source.url
    ?.substringAfterLast('.', "")?.substringBefore('?')
    ?.takeIf { it.isNotEmpty() && it.length <= 5 }
    ?: source.mimeHint.orEmpty()            // ← LOCAL 源走这里
val info = ResourceInfo(extension, length, source.trackId, "")
```

- **REMOTE**：`source.url` 后缀 → 扩展名；无后缀 / 签名直链 / WebDAV 临时 URL 会取不到。
- **LOCAL**：`source.url == null` → fallback 到 `source.mimeHint`，而它是 `audio/mpeg` 这类 **MIME 字符串，不是扩展名** → 语义错配。代码注释自述「**这是待真机验证的假设，不是已证实的修复**」。
- `createDataRequestHandler()`（`EngineAdapter.kt:244-253`）**仅 URL 源返回 `UrlDataRequest`**，LOCAL 源返回 `null`；native 走 `getLocalPath()` / `openLocalFd()` 直读（`SourceConfig.Type.LOCAL`，见 `SourceConfig.java:27-32`）。两条路径互不相同，G01 只覆盖其中一条。

### 2.5 已知格式专属问题（掩盖，非根治）

`UrlDataRequest.kt:80-86`：m4a（`moov-at-end`）触发 `Start→push→onEnd→Read 0→重试` 死循环（`-103`），当前用**成功 EOF 延迟 2s 回传 + `cancelled` 检查**掩盖（vfile 落盘需 ~50ms）。`a3-phase-ab-evidence-2026-11-07.md:544` 有对应记录。2s sleep 在自动进曲下有释放后回调风险，F4 需根治。

---

## 3. 「全格式」的客观定义与验收判据

**定义**：以 `libffmpeg.so` 实际编入的 `ff_*_demuxer` + `ff_*_decoder` 交集为候选集，逐格式在 **REMOTE（URL/回环）与 LOCAL（SAF/MediaStore）双路径**下真机通过。

**每种格式的判据**（沿用 A3 G01 口径）：`Open codec for <codec>` → `Write start play` → 完整时长（±0.5s）→ `ENDED` → 自动进曲；附加 `seek` / `pause→play` 位置连续 / 进程零崩溃。

**不得写入「已支持」的情形**：仅 demuxer 字符串存在、仅 JVM 单测通过、仅 URL 夹具通过、仅 LOCAL 未测、仅静态白名单补齐。

---

## 4. 施工 todo（用户第 3 条「按清单顺序开发」的执行序列）

| 阶段 | 目标 | 具体交付 | 前置 | 验收 |
|---|---|---|---|---|
| **F1** | 建立单一格式真相源 `AudioFormats` | 以 `libffmpeg.so` 符号为边界，统一 6 处白名单；新增纯 JVM 契约 + 单测；消除 14/8/9/9/6/6 漂移 | — | 6 处清单同一来源；契约自检 + JVM 测试全绿 |
| **F2** | 修正 `EngineAdapter` 的 `ResourceInfo.extension` 推断链 | LOCAL 源改用真实扩展名/内容嗅探，不拿 MIME 当扩展名；URL 无后缀兜底；**先真机证伪/证实代码注释的「待验证假设」** | F1 | LOCAL 源 `resourceInfo extension=` 日志为真实扩展名；真机 format 探针通过 |
| **F3** | 逐格式 × 双路径真机验收矩阵 | 每种候选格式分别跑 REMOTE + LOCAL，记录 codec / 播放 / 时长 / seek / ENDED；产出可复跑脚本 + JSON 证据 | F2 | 矩阵逐格 PASS/PARTIAL/FAIL 如实登记，不伪造 |
| **F4** | 根治 m4a EOF 2s 延迟掩盖 | 定位 `moov-at-end` 死循环根因（vfile 落盘窗口 vs 读侧重试），替换 2s sleep | F3 | 自动进曲连续 3 首无 `-103`、无释放后回调 |
| **F5** | A3 全绿后 ABI 回填 + 冻结舱 | G01~G09 全 PASS 后回填 `engine_compat_test`、冻结引擎舱、落 `§61.33` Manifest 生产形态施工单 | F4 | ABI 验收回填、舱冻结、指纹不变 |

**F1→F5 完成后**，才回到 `docs/17 §4` 的 **P2 播放页 1:1 对齐 → P3 八个保留模块补齐 → P4 可选增强**，每个模块仍按「二级入口 × 子功能 × 三元落点」先出子清单再自研。

---

## 5. 本轮边界（零发布基线变更）

- **不改** `settings.gradle.kts`（仍 19 模块）、`libs.versions.toml` / 任何 `build.gradle.kts`（零新增依赖）、`app/src/main/res/`（未建）、`:ui-legacy`（未 include）、51 素材 / 61 行 csv（`replaced=false`）/ Manifest / 11 权限 / DI 图 / 指纹 / 20 so。
- **不改** 6 处白名单源码、`EngineAdapter` / `UrlDataRequest` 生产逻辑：这些是 F1/F2/F4 的施工对象，须按 todo 顺序在后续批次动工，本轮只固化证据与顺序。
- **不碰设备**：真机矩阵（F3）需 USB 在场 / 多格式样本，属后续批次。
- **不新增 `docs/` 文件**：本报告放 `analysis/`，`09 §3` 计数仍 **31**，`check_source_contracts.py §61.43` 的 `index_ok` 不受影响。

---

## 6. 长效守护

- `tools/l3_audio_format_audit.py` → `analysis/l3-audio-format-audit.json`：6 处白名单 / 差集 / `libffmpeg.so` demuxer·decoder / A3 G01 4 格式 / extension 推断链 / F1~F5，纯标准库只读、可复跑。
- `check_source_contracts.py §61.44` 守护：用户拍板口径在位、`docs/17 §4` 首要目标行在位、格式审计证据计数（`14/8/9`、demuxer `75`、真机 `4`）不漂移、`docs/` 计数仍 31；任一漂移即 FAIL。
- 既有守护不变：`§61.38`~`§61.43`、`l3_interaction_spec.py`、`l3_visual_replica_spec.py`（`pixel_level_1to1=False`）、`pre_release_check.py`（dev 6/6 / release FAIL(C1,C4)）。

> AI生成
