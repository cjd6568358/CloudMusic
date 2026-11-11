# B2 路线切换 · 实施施工单（2026-11-11 17:44 用户拍板「切换 B2」）

> **性质**：路线决策落地后的**实施规划 + 前置实证**，不是实现、不是验收报告。
> 权威定义来自 `analysis/l3-playback-route-evaluation-2026-11-11.md §1.1 / §4.2`；
> 本单不改写该报告原文，只补足「怎么开工」。
> **本批（`docs/12 §61.80`）零生产代码改动、零新增依赖、零模块启用、零 P0 native 改动。**
> **F4 仍 BLOCKED**：换路线 ≠ 修复；B2 实施尚未开始，不得据本单宣称 F4 已解决。

---

## 0. 决策与授权边界

| 项 | 内容 |
|---|---|
| 拍板 | 用户 2026-11-11 17:44「**切换 B2**」 |
| 前置判据 | `§61.79` A1 时间盒 `timebox_passed=False`（仅点火、无持续播放）→ 按 `路线评估 §6.1`「不通过立即转 B2」 |
| 授权范围 | 路线由 A 切到 B2，停止 A；产出规划、实证、守护、文档闭环 |
| **未授权 / 本批不做** | 引入 Media3 依赖、新建/启用 `:engine-opensource`、换绑 `EngineModule`、删除 18 个 so、重编译重装、改解码器、动 native 二进制、定案「下载后播放」产品语义、确认 LGPL 重编 |
| 立即生效 | ① A 路线停止（**不追加 A2 二进制补丁**、不再投入 `§61.77` 三锚点的 native 修复）；② `§61.75` F4 取证档案转为**历史证据**（不删除、不改写）；③ B2 成为现行技术路线 |

**「切换 B2」推进的是技术路线，不是 F4 修复、不是 B2 已实施完成、不是线路验收。**

---

## 1. B2 的权威定义（逐字对齐路线评估 §1.1 / §4.2）

> 放弃 `libncmplayerengine` / `libNMCommonCache` / `libncmaudioplayer`，
> 用 **Media3/ExoPlayer**（平台 `MediaCodec` 硬解优先）+ **手动构建的 FFmpeg 软解扩展**
> （按需裁剪 decoder）+ **自研缓存/Range 层**重建播放内核；
> 上层 **`IMusicEngine` 门面不变**。

- 目标模块是架构里预留的 **`:engine-opensource` 逃生舱**（`docs/02 §0 A4`）：
  「只要重写一个实现 `IMusicEngine` 的 `:engine-opensource`（FFmpeg + Media3/ExoPlayer），
  **上层零改动即可切换**」。B2 正是这个逃生舱，不是新发明的第三条路。
- **B1 不做**（保留黑盒 so，F4 一点没变）；**B3 不做**（D-146 个人自用，音效 DSP/AI 全栈重写投入产出比极低）。
- **不是「改 FFmpeg 解码」**：用户上轮已裁定 F4 卡在 `NMCommonCache` 取流/缓存层、与解码器正交；
  B2 的收益是**把缺陷所在的整层删掉**（路线评估 §6.4），不是换解码器。

---

## 2. 与现行架构决策 / P0 基线的冲突（**如实登记，不悄悄改**）

B2 会推翻或限定多条已登记的现行口径。`§61.80` 只做**登记与范围限定**，
**具体条文改写随各实施批次同步落地**，避免规划阶段先改契约造成守护自相矛盾。

| # | 现行口径 | 出处 | B2 下的演进 / 范围限定 | 本批动作 |
|---|---|---|---|---|
| C1 | 「**不引入 Media3**」，媒体会话/音频焦点主导方照搬竞品 | `docs/02 §6.2` / `§9` / `§13 决策 ③`；`docs/09 §2`；`docs/16 §13 #26` | **范围限定**：该决策针对「媒体会话 / 音频焦点主导方」，仍成立——B2 **只在播放内核层**引入 `media3-exoplayer` / `media3-datasource` / `media3-common`；**不引入 `media3-session` 作为主导**，MediaSession / AudioFocus / 媒体按键继续走 `:core-media` + `:media-legacy` + `IMediaControl` 门面（`androidx.media:media:1.7.0` 已在 catalog） | 登记，不改条文 |
| C2 | `§13 27 项决策 #26「不引入 Media3」全部仍有效` | `docs/16 §13` | 演进为「**媒体会话层不引入；播放内核层按 B2 引入**」；`docs/16 §5.3` 追加批注，原表保留历史快照 | 追加批注 |
| C3 | **P0：禁止改 `EngineModule` 绑定、禁止重写引擎** | `docs/09 §5` / 工作记忆「P0 不可变」 | 演进为「允许**单点换绑** `EngineModule.provideMusicEngine` 到 `:engine-opensource` 实现；`IMusicEngine` **门面签名零改动**、零参数换绑语义保留、`:engine-legacy` 与 `EngineAdapter` **保留不删作为回退**」 | 登记，不换绑 |
| C4 | 发布基线「**零新增依赖**」「19 模块 / 11 权限 / 20 so」 | `12 §61.42/§61.43` 守护、`check_source_contracts.py` | 演进为「B2 **实施批次起**允许新增 `androidx.media3.*` 依赖与第 20 个模块；每次新增必须同步演进 `§61.42/§61.43` 基线守护」。**`§61.80` 本批仍零新增依赖、19 模块不变** | 登记，不改基线 |
| C5 | 「18 个 arm64 so 二进制原样 / P0 native 不可变」 | `12 §10.2` / `ENGINE-FINGERPRINT` | B2 的**目标**是移除私有 so，但**移除属后续实施批次**；移除前须单独确认 D-146 权利状态与 `ENGINE-FINGERPRINT` 归档。本批 so 二进制原样 | 登记，不动 so |
| C6 | `settings.gradle.kts` 注释「`:engine-opensource`（逃生舱，**仅在路线 C 拍板后启用**）」 | `settings.gradle.kts` 模块总表注释 | 「路线 C」是旧编号；演进为「**B2 拍板后启用**」。注释改写随 M1 批次执行，本批不改构建文件 | 登记，不改文件 |
| C7 | 音效能力保持 5/5（103 个 `AudioEffects_*` JNI / 22 EQ / 10 混响 / 19 NCAE / MNN AI / 空间音频 / Hi-Res USB 独占） | `analysis/l3-audio-decoder-effect-research-2026-11-11.md`；路线评估 §4.2 | **明确降级**：B2 退到系统 `AudioEffect` 基础 EQ 或空实现；`IMusicEngine.attachAudioEffect` 保留签名但标为降级占位；音效能力列 **P4 长期挂账** | 登记 + 待用户确认 |

> C1 的范围限定是 B2 能不推翻「照搬竞品媒体焦点层」这条用户决策的关键：
> **ExoPlayer 只做解码与播放，不做会话/焦点主导**。若后续要使用 `media3-session`，
> 必须**重新拍板**，不得在实施中默认引入。

---

## 3. 前置实证（本批实跑，机器证据 `analysis/l3-b2-media3-probe.json`）

| 项 | 实测 | 结论 |
|---|---|---|
| Google Maven 连通性 | `dl.google.com/dl/android/maven2/androidx/media3/*/maven-metadata.xml` HTTP 200（5 个制品） | Media3 依赖**可达**，无网络阻断 |
| `media3-exoplayer` 版本 | 共 77 个版本；`<latest>`/`<release>` = `1.12.0-alpha02`；**最新稳定版 `1.11.1`** | **选 `1.11.1`**（非预发布），与 `§61.41` 已实测 Google Maven HTTP 200 一致 |
| 其余制品 | `media3-datasource` / `media3-common` / `media3-session` / `media3-decoder` 同版本线可达 | 核心制品齐备 |
| FFmpeg 软解扩展 | **Google Maven 无 `media3-ffmpeg` 制品** | 必须按 Android 官方《Media3 ExoPlayer 支持的格式》**手动构建 + 按需裁剪 decoder**；这是 M2 的独立风险，不是「下载依赖」 |
| 构建版本 | AGP 8.9.2 / Gradle 8.11.1 / Kotlin 2.1.0 / KSP 2.1.0-1.0.29 / JDK 17 / compileSdk=targetSdk 35 / minSdk 26 / NDK 27.0.12077973 | Media3 1.11.x 要求 compileSdk 35、minSdk ≥ 21，**与现有基线兼容**；无需升级 AGP/Kotlin |
| 逃生舱预留 | `settings.gradle.kts` 模块总表含 `:engine-opensource`；`docs/02 §0 A4` 明确「上层零改动即可切换」 | B2 有架构落点，不是临时起意 |
| 门面契约 | `engine-api/.../EngineApi.kt`：`IMusicEngine` **14 方法**、`EngineSource` 4 字段、`EngineConfig` 4 字段、`EngineState` 6 态 | 门面稳定，B2 只需新增实现，**门面零改动** |
| 唯一换绑点 | `core-di/.../AppBindings.kt` `EngineModule.provideMusicEngine` → `EngineAdapter()` | 换绑可逆、影响面单点 |
| 当前依赖状态 | `libs.versions.toml` / 所有 `build.gradle.kts` / `settings.gradle.kts` **零 `media3` 坐标**；19 模块 | 本批零新增依赖，实施从 M1 开始 |

---

## 4. 目标架构

```
上层（零改动）
  PlaybackService / PlayerController / PlaybackRuntime / :core-media
        │ 只依赖 :engine-api 的 IMusicEngine
        ▼
  EngineModule.provideMusicEngine  ← 唯一换绑点（M1 改，可回退）
        │
        ├── :engine-legacy / EngineAdapter   （保留不删，A 路线回退）
        └── :engine-opensource / Media3EngineAdapter  （B2 新实现）
                │
                ├── ExoPlayer（media3-exoplayer 1.11.1）
                │     RenderersFactory：平台 MediaCodec 硬解优先
                │     + 手动构建的 FFmpeg 软解扩展（M2，按需裁剪 decoder）
                │     DefaultLoadControl ← EngineConfig.bufferMs
                │     Player.Listener → EngineState Flow / ENDED / 错误
                ├── MediaItem 映射 ← EngineSource.url | localPath | mimeHint
                ├── RangeCacheDataSource.Factory（自研，OkHttp 4.12.0 已在 catalog）
                │     只用官方 DataSource 语义，**不做私有 vfile / commit 语义**
                │     ← 直接规避 F4 的 NMCommonCache 代次门控 / result:-2 / -103
                └── MediaSession / AudioFocus / 媒体按键：**不走这里**
                      继续 :core-media + :media-legacy + IMediaControl
```

### 4.1 `IMusicEngine` 14 方法 → Media3 映射（门面签名零改动）

| 门面方法 | B2 实现要点 | 备注 |
|---|---|---|
| `prepare(source, config)` | `MediaItem.Builder` 由 `url` / `localPath` / `mimeHint` 构造；`expectedLength` 传 Range 层；`setMediaItem` + `prepare()`；返回 `EngineHandle`（内部维护单 handle 不变量） | 保持 `03-06 §4` 单 handle 不变量 |
| `attachDataSource(handle, ds)` | 自研 `IDataSource` → `RangeCacheDataSource` 桥接；`localPath` 走 `FileDataSource` | 契约不变 |
| `play` / `pause` / `stop` | `player.play()` / `pause()` / `stop()` + `clearMediaItems()` | `stop` 复位 position |
| `seek(handle, positionMs)` | `player.seekTo(positionMs)`，钳制 `[0, duration]` | `§15 T09` |
| `setVolume(handle, volume)` | `player.volume = volume`（`0f..1f`） | 焦点 duck/unduck 走这里 |
| `positionMs` / `durationMs` | `player.currentPosition` / `player.duration`（未知返回 -1） | |
| `state()` | `Player.Listener` → `MutableStateFlow<EngineState>`；`STATE_ENDED` → `ENDED`，`onPlayerError` → `ERROR` | 6 态映射需单测 |
| `setQuality(handle, quality)` | **占位**：`InMemoryMusicEngine` 亦为空实现，B2 首版保持一致 | 登记为未实现，不伪造 |
| `attachAudioEffect(handle, effectId)` | **降级占位**：系统 `android.media.audiofx.Equalizer` 基础 EQ 或空实现 | 对应 C7 音效降级 |
| `release(handle)` | `player.release()` + 关 `IDataSource` + 复位 Flow | |
| `lastError()` | `CloudMusicError` 映射 `PlaybackException` | |

> `setQuality` / `attachAudioEffect` 两项在 A 路线下本就是空/弱实现，B2 **不新增能力**，
> 也不把它们写成「已支持」。

### 4.2 缓存 / Range 设计（F4 不再复现的关键）

- 用 **官方 `DataSource` / `MediaSource` 语义**，不移植 `NMVirtualFileManager` / `NMSlicesFile` / `commit` 记账。
- `RangeCacheDataSource` 负责 HTTP Range、磁盘缓存、离线命中、失败回落；
  对齐 `03-08` 的 A9 投屏直链优先 / 代理回落策略与 `03-11` 的离线降级闸门。
- **禁止**把 A 路线的 vfile 分片 / 代次门控语义带进 B2（路线评估 §7「B2 自研缓存复现同类问题」风险）。
- 代理态仍经 nanohttpd 回环取流（现有 `:core-di/proxy`），不改变上层网络模型。

---

## 5. 分阶段实施计划（对齐路线评估 §4.2 工期拆解）

> 工期沿用「AI 辅助口径」估算区间，**不是承诺**；每阶段独立可验收、可回退。
> 本 `§61.80` 批只完成阶段 M0（决策落地 + 规划 + 实证 + 守护），**M1~M5 尚未开始**。

| 阶段 | 目标 | 交付物 | 通过判据 | 回退 |
|---|---|---|---|---|
| **M0（本批）** | 路线切换落地 | `§61.80` 断点 / 本施工单 / `l3-b2-media3-probe.json` / `l3_b2_route_switch_audit.py` / `check_source_contracts.py §61.80` 守护 / 全量文档回写 | 审计工具全 PASS、契约自检 PASS、`git diff --check` 干净；**零生产代码改动** | 不涉及 |
| **M1** | Media3 接入 + 门面适配骨架 | 新增 `:engine-opensource` 模块、`Media3EngineAdapter` 骨架、`libs.versions.toml` 加 `androidx.media3 1.11.1`、改 `settings.gradle.kts` 注释并 include、`EngineModule` 单点换绑、同步演进 `§61.42/§61.43` 基线守护 | `./gradlew test :app:assembleDebug --offline` 通过；现有 47 套件 / 430 测试零回归；`IMusicEngine` 门面签名零改动；本地文件可播（`Write start play` 类判据） | 换绑点切回 `EngineAdapter`，`:engine-legacy` 保留 |
| **M2** | 格式覆盖 | FFmpeg 软解扩展交叉编译（按需裁剪 decoder）、F3 14 格重跑、7 种 NO_SAMPLE 补样本 | F3 14 格 PASS 数 ≥ A 路线基线 11；7 种 NO_SAMPLE 有明确样本或换源结论 | 平台硬解路径保留 |
| **M3** | 缓存 / Range / 网络源 | `RangeCacheDataSource`、URL/LOCAL/PROXIED 三态接线、代理回落、离线闸门 | URL 源连续播放 ≥ 60s 无 `-103` 类冻结；本地回环代理取流通 | 回落直连 / 回落 LOCAL |
| **M4** | 播放语义 | gapless / seek / 精确时长 / ENDED / 自动进曲 3 首 / ReplayGain | `§61.74/§61.75` 多信号判据逐条登记；seek 与 ENDED 不再 NOT RUN | 单项开关降级 |
| **M5** | 全量回归 | F3 14 格 + F4 多信号 + A3 G01~G09 + 20 分钟长稳重跑；F4 验收 `17 §8.3` → F5 | F4 主判据全 PASS、A3 全绿、舱冻结、ABI 回填 | 保留 A 路线证据作对照 |

**阶段门禁**：每阶段结束必须按纪律回写 `docs/12` 进度断点 + 当日工作记忆，
同步 `check_source_contracts.py` 守护；未通过判据不得进入下一阶段。

---

## 6. 回归判据（B2 完成时必须全量重跑，沿用既有模板）

- **F3 14 格**（`tools/l3_f3_matrix_run.py`）：11 PASS / 3 FAIL / 7 NO_SAMPLE 基线需重跑，不得以 A 路线结果代替。
- **F4 多信号**（`§61.74/§61.75`）：本 app AudioTrack `state:started`、`Write start play nErr:0`、
  MediaSession `state=3` 且 position 递增、自动进曲 3 首无 `-103`、seek / ENDED。
- **A3 G01~G09** + **20 分钟长稳**（`§61.31` 口径）、`§61.33` 调试入口收口、ABI 回填 / 冻结舱。
- **判据纪律**：单独 `state=3` 或单独 `Write start play` **不等于**播放成功（§61.79 已实证）。

---

## 7. 需用户后续确认（不阻塞 M0，阻塞对应阶段）

1. **音效降级范围**（C7）：接受退到基础 EQ 并列 P4 挂账，还是保留部分能力另立方案 → 阻塞 M4 体验验收。
2. **7 种 NO_SAMPLE 样本**：需用户提供样本或换源（OpenList 80 目录扫描 0 命中）→ 阻塞 M2。
3. **R7 / LGPL 重编**：移除私有 so 与 GPL FFmpeg 后权利状态改善，但 LGPL 重编与编解码授权需单独确认，**不作法律结论** → 阻塞公开发布，不阻塞自用。
4. **「下载后播放」产品语义**：正式语义还是仅验证手段、REMOTE 是否保留 → 阻塞 M3 产品接线。
5. **是否允许 `media3-session`**：C1 默认**不引入**；如需 Media3 主导会话，须重新拍板。
6. **so 移除时机**（C5）：18 个私有 so 与 `ENGINE-FINGERPRINT` 的归档/移除需单独确认。

---

## 8. 诚实边界

- **本批是路线切换，不是 F4 修复**：REMOTE 取流缺陷仍在，`§61.75` 终局裁决不变，
  **F4 仍 BLOCKED**；A3 仍 G09 PARTIAL、舱不冻结、F3 14 格未改写、7 种 NO_SAMPLE 未补。
- **B2 实施尚未开始**：零生产代码改动、零新增依赖、零模块启用、未换绑、未重编译 / 未重装、
  so 二进制原样、不动解码器、未改用户网络、未 `pm grant`、未连 adb。
- **A 路线证据不删除**：`§61.50~§61.79` 的 41 工具 / 72 证据 / 38 logcat 转为**历史档案**，
  一字不改；`analysis/l3-f4-level5-wiring-audit.json` 等冻结件保持原样。
- **路线评估不改写**：本报告是决策支持文档，本单只补施工细节，原文保留。
- 工期为估算区间 + 置信度（中），不是承诺；法律部分不作结论。

---

## 9. 证据索引

- 路线定义：`analysis/l3-playback-route-evaluation-2026-11-11.md §1.1 / §4.2 / §6.1 / §6.2 / §7`
- 前置实证：`analysis/l3-b2-media3-probe.json`（Google Maven metadata，本批联网抓取）
- 逃生舱：`docs/02-产品架构文档.md §0 A4`、`settings.gradle.kts` 模块总表
- 门面：`engine-api/src/main/kotlin/.../EngineApi.kt`、`core-di/src/main/kotlin/.../AppBindings.kt`
- 构建基线：`gradle/libs.versions.toml`、`settings.gradle.kts`、`engine-legacy/build.gradle.kts`
- F4 现状：`analysis/l3-f4-acceptance-final.json`、`analysis/l3-f4-a1-bypass-verdict.json`、`docs/12 §61.75/§61.79`
- 合规：`docs/09 §5 R7`、`NOTICE.md`、D-146（`12 §61.37`）
- 外部：Android Developers《Media3 ExoPlayer 支持的格式》
  https://developer.android.com/media/media3/exoplayer/supported-formats?hl=zh-cn
## 8. M1 实施批注（`12 §61.81`，2026-11-11 18:19，历史正文不改）

> 本报告 `§1~§7` 是 M0~M5 施工单与决策支持文档，原文保留；本节只补记 M1 落地状态。

- **状态**：M1 已开工（`implementation_stage=M1_SKELETON`），**M1 骨架 ≠ B2 完成**；
  正式断点 `12 §61.80 → §61.81`。`§7` 六项确认按用户授权的默认假设执行并如实挂账。
- **已落地**：`media3 1.11.1` 三制品 / 第 20 模块 `:engine-opensource` /
  `Media3EngineAdapter` 14 方法骨架 / `EngineModule` 单点换绑 + 回退保留 / 门面零改动 /
  `§61.81` 守护 + M0/M1 双态演进。
- **未落地（阶段门禁，未伪造接线）**：M2 格式覆盖 + FFmpeg 软解扩展手动构建、
  M3 `RangeCacheDataSource`、M4 gapless / seek / ENDED / 自动进曲、M5 全量回归与 F4 验收。
- **构建诚实状态**：离线实跑 `:engine-opensource:compileDebugKotlin --offline` →
  `BUILD FAILED` / `EXIT 1`，卡在 3 个 media3 制品无离线缓存的依赖解析阶段，
  **Kotlin 源码编译尚未验证**（日志 `analysis/l3-b2-m1-gradle-offline-build.log`）；
  47 套件 / 430 测试零回归未验证。若需真实编译验证，须用户拍板联网拉取依赖
  （偏离现行 `--offline` 约定，本批不擅自执行）。
- **F4 仍 BLOCKED**、`§61.75` 终局裁决不变；不动解码器。

## 9. M2 前置取证批注（`12 §61.82`，2026-11-11 21:01，历史正文不改）

> 本报告 `§1~§8` 是 M0~M5 施工单与决策支持文档，原文保留；本节只补记 M2 前置状态。

- **状态**：M2 保持 `PREFLIGHT`（`m2_started=False` / `m2_completed=False` /
  `m2_gate_passed=False`），`§5` M2 门禁原文不改、门禁未通过、**不得进入 M3**。
  正式断点 `12 §61.81 → §61.82`；**M2 前置取证 ≠ M2 完成**。
- **纯离线取证**：4 项刚性缺口（NDK / CMake / FFmpeg 源码 / Media3 FFmpeg 扩展源码）
  本机全缺，Google Maven 无 `media3-ffmpeg` 制品 → 交叉编译纯离线不可执行；
  格式覆盖候选集（`AUDIO_EXT` 14 + `alac` / 75 demuxer / 100 decoder）已登记，
  **不是已支持清单**。
- **F3 14 格**：基线 11/3/7 与 7 种 NO_SAMPLE 已复核，重跑施工单已写，
  **NOT RUN**（样本 / USB 物理在场 / 端点 / `§61.81` 编译未验证阻塞）。
- **未决项**：联网获取上游工具链与源码、7 种 NO_SAMPLE 样本、R7 / LGPL 重编、
  `§61.81` 遗留的联网编译验证；本批不擅自执行。
- **F4 仍 BLOCKED**、`§61.75` 终局裁决不变；不动解码器。详见
  `analysis/l3-b2-m2-preflight-2026-11-11.md`。

## 10. M2 前置补证批注（`12 §61.83`，2026-11-11，历史正文不改）

> 本报告 `§1~§9` 是 M0~M5 施工单与 M2 前置状态，原文保留；本节只补记符号表补证。

- **状态**：M2 仍保持 `PREFLIGHT`（`m2_started=False` / `m2_completed=False` /
  `m2_gate_passed=False`），`§5` M2 门禁原文不改、门禁未通过、**不得进入 M3**。
  正式断点 `12 §61.82 → §61.83`，推进的是**补证证据等级**、不是 M2 阶段推进。
  **符号表补证 ≠ M2 完成**。
- **格式覆盖证据升级**：`§61.44` 字符串法 → **ELF `.dynsym` 导出符号表**
  （纯 stdlib 手写 ELF32/64 解析 + pyelftools 独立互证）。两 ABI 各自 100
  `ff_*_decoder` / 75 `ff_*_demuxer`，集合一致、与 `§61.44` 100/75 吻合。
  `.symtab` 已 strip，只覆盖导出符号，不作「未编入」绝对断言。
- **9 项存疑逐项收口**：8 项 `NO_SYMBOL`、唯一 `dsf` = `DEMUXER_SYMBOL_ONLY`；
  15 项候选集 8 / 2 / 5；`dsd_lsbf/msbf*` 四个 DSD-PCM decoder 符号编入，
  印证 DSD 需单独重做 DoP / native DSD 通路，但不等价于 `dsf/dff` 可播。
- **铁律不变**：导出符号存在 ≠ decoder 可用 ≠ 真机可播；F3 14 格重跑仍
  **NOT RUN**（样本 / USB 物理在场 / 端点 / `§61.81` 编译未验证阻塞）。
- **未决项**：`§9` 的 4 项联网 / 样本 / R7 / 编译验证原样保留，本批不擅自执行。
- **F4 仍 BLOCKED**、`§61.75` 终局裁决不变；不动解码器。详见
  `analysis/l3-b2-m2-decoder-symbols-report.md`。


> AI生成
