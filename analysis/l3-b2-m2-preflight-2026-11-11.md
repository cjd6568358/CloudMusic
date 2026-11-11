# B2 M2 前置取证报告 / 施工单（`12 §61.82`，2026-11-11 晚，纯离线）

> **授权**：用户 2026-11-11 21:01 在「联网编译验证 / 保持离线做 M2 前置 / 暂停」
> 中选择 **「保持离线，做 M2 前置」**。本批只做 M2 的**纯离线前置取证**，
> **不联网、不偏离 `--offline`、不改代码、不连真机、不重编译、不写 M2 完成**。
> 机器证据：`analysis/l3-b2-m2-preflight.json` / `analysis/l3-b2-m2-preflight-report.md`；
> 审计工具 `tools/l3_b2_m2_preflight_audit.py`。

---

## 0. 结论摘要

**`M2_PREFLIGHT_ONLY__M2_GATE_NOT_PASSED__F4_BLOCKED`**：M2 的门禁、格式覆盖候选集、
交叉编译前置清单、F3 14 格重跑施工单已按纯离线条件取证完毕，但 **M2 阶段本身未开工、
门禁未通过**：

- **4 项刚性缺口**（本机实测全缺）：Android **NDK** / **CMake** / **FFmpeg 源码** /
  **Media3 FFmpeg 扩展源码**。叠加 `analysis/l3-b2-media3-probe.json` 实证
  **Google Maven 无 `media3-ffmpeg` 制品** → 交叉编译必须联网获取上游源码与工具链，
  **纯离线不可执行**；本批不下载、不伪造构建产物。
- **7 种 NO_SAMPLE**（`m4a / aac / opus / wv / tak / dsf / dff`）自 `§61.55`
  OpenList 80 目录扫描 0 命中后仍未补，F3 14 格重跑**仍 NOT RUN**；
  主动 seek 判据还需 USB / 物理在场。
- **F4 仍 BLOCKED**，`§61.75` 终局裁决不变；A3 仍 G09 PARTIAL、舱不冻结、
  F3 14 格未改写。**M2 前置取证 ≠ M2 完成**。

---

## 1. M2 门禁（引用 `analysis/l3-b2-implementation-plan-2026-11-11.md §5`，原文不改）

| 项 | 施工单定义 |
|---|---|
| 目标 | 格式覆盖 |
| 交付物 | FFmpeg 软解扩展交叉编译（按需裁剪 decoder）、F3 14 格重跑、7 种 NO_SAMPLE 补样本 |
| 通过判据 | **F3 14 格 PASS 数 ≥ A 路线基线 11**；**7 种 NO_SAMPLE 有明确样本或换源结论** |
| 回退 | 平台硬解路径保留 |
| 阶段门禁 | 每阶段结束回写 `docs/12` 断点 + 当日工作记忆，同步 `check_source_contracts.py` 守护；**未通过判据不得进入下一阶段** |

施工单 `§7` 明确 **7 种 NO_SAMPLE 样本**「需用户提供样本或换源 → 阻塞 M2」；
`§6` 明确 F3 14 格基线「需重跑，不得以 A 路线结果代替」。

---

## 2. 交叉编译前置清单（本机只读实测，未改任何环境变量）

| 前置项 | 本机现状 | 判定 |
|---|---|---|
| Android NDK | 真实 SDK 仅 `platform-tools`；仓库外临时 SDK（构建链专用）只有 `build-tools` / `platforms` / `licenses` 与桩目录，**无 `ndk/`**；`ANDROID_NDK_HOME` 等环境变量未设置 | **缺失** |
| CMake / ninja | 真实 SDK 无 `cmake/`；项目 `app/`、`engine-legacy/`、约定插件中**无 `externalNativeBuild` / `cmake` 配置** | **缺失** |
| FFmpeg 源码 | 仓库内无 `ffmpeg/` 目录；常见本地源码路径均无；`analysis/`、`tools/` 无 FFmpeg 构建脚本 | **缺失** |
| Media3 FFmpeg 扩展源码 | 仓库内无 `media3*` 扩展目录；`libs.versions.toml` 只有 exoplayer / datasource / common 三制品 | **缺失** |
| Maven 制品 | `analysis/l3-b2-media3-probe.json` `note`：「media3 官方未发布 FFmpeg 软解扩展到 Maven；FFmpeg 扩展必须按官方《Media3 supported formats》手动构建并按需裁剪 decoder」；5 个探测制品全 HTTP 200 但**不含 `media3-ffmpeg`** | **必须手动构建** |
| 构建链版本 | AGP `8.9.2` / Gradle `8.11.1` / Kotlin `2.1.0` / KSP `2.1.0-1.0.29` / JDK 17 / `compileSdk=targetSdk 35` / `minSdk 26` / **仅 `arm64-v8a`**（`app/build.gradle.kts:26` `ndk.abiFilters.addAll`） | 已钉死，交叉编译产物须对齐 |
| 现有 native 基线 | `libffmpeg.so` 6,658,592 B，**GPL v2+ 构建**（`--enable-gpl --enable-libx264`，含 `GPL version 2 or later` × 6），**75 demuxer / 100 decoder（69 音频相关）** + 3 个专用软解库 + 1 个 MediaCodec 硬解桥；`docs/05 §4` 实证**无竞品私有依赖**（逃生舱可行依据） | 仅作候选集参考，**不可直接复用为 B2 扩展产物** |

**结论**：M2 的「FFmpeg 软解扩展交叉编译」在纯离线条件下**无法执行**，缺 4 项上游输入
（NDK / CMake / FFmpeg 源码 / 扩展源码），须联网获取或由用户提供离线副本。
本批**未联网、未下载、未生成任何 `.so`/扩展产物**，如实挂账。

---

## 3. 格式覆盖候选集（纯离线推导，**不是已支持清单**）

- **上层真相源**（`core-model/AudioFormats.kt`，F1 单一真相源）：
  `AUDIO_EXT` **14 项** = `mp3 / flac / ogg / m4a / wav / opus / wma / aac /
  ape / dsf / dff / aiff / wv / tak`；`EXTRA_AUDIO_EXT` = `alac`（可推断 MIME / strm
  过滤，**未并入 14 项收录集**）。`MIME_BY_EXT` 11 项、`STREAM_FILTER_EXT` 15 项。
- **native 能力**（`analysis/l3-audio-format-audit.json` / `12 §61.44`）：
  `libffmpeg.so` 75 `ff_*_demuxer` / 100 `ff_*_decoder`（69 音频相关）；
  `libnecmFlacDec.so` / `libnecmMP3Dec.so` / `libnecmAACDec.so` 三个专用软解库
  + `libnecmMediaCodecJDec.so` 硬解桥。
- **铁律（`12 §61.44`）**：`demuxer 存在 ≠ decoder 编入 ≠ 真机可播`。
  `tak / tta / wavpack / dsf / dff / aiff / shorten / truehd / mlp` 有 demuxer
  或字符串痕迹但**未确认对应 decoder 编入**；A3 G01 真机只验过
  `mp3 / flac / m4a(aac) / ogg(vorbis)` 4 种且全是 URL 回环 12s 正弦音夹具，
  **LOCAL 源全格式能力未验证**。
- **B2 路线选择**（施工单 `§1` / `§5`）：**Media3/ExoPlayer 平台 `MediaCodec` 硬解优先
  + 手动构建的 FFmpeg 软解扩展兜底**（官方 Media3 做法：默认平台解码器，FFmpeg 扩展
  只作兜底，按 `Android Developers《Media3 ExoPlayer 支持的格式》` 裁剪 decoder）。
  **不是「改 FFmpeg 解码修 F4」**——F4 卡在 `NMCommonCache` 取流/缓存层，
  与解码器正交（`§61.77` 三处修复锚点全在 native 缓存层）。
- **按需裁剪 decoder 候选**（待 M2 实测确认，**本批不定案**）：
  7 种 NO_SAMPLE 对应格式 + 上述 demuxer/decoder 未确认项 + `alac`；
  DSD（`dsf/dff`）还涉及 DoP / native DSD 通路与 USB 独占输出
  （`libncmusbaudio.so`），FFmpeg 软解通常只输出 PCM，须单独重做。
- **R7 合规边界**：现有 `libffmpeg.so` 是 **GPL v2+**（非 LGPL）。按 D-146
  个人自用 / 侧载范围可控，但**公开发布前需法务评估或换 LGPL 重编**；
  编解码授权（AAC 等专利）同样需单独确认。**不作法律结论**。

> 本节是**候选集**，不是「全格式已支持」；任何格式未跑完 F3 REMOTE/LOCAL 双路径
> 真机判据前，不得记 PASS。

---

## 4. F3 14 格重跑施工单（**NOT RUN**）

- **基线（`12 §61.49` / `analysis/l3-f3-matrix-report.md`）**：
  7 格式 × REMOTE/LOCAL = **14 格 → 11 PASS / 3 FAIL / 7 NO_SAMPLE**；
  14/14 格 `extension` 与真实格式一致。
- **7 种 NO_SAMPLE**：`m4a / aac / opus / wv / tak / dsf / dff`
  （OpenList 80 目录扫描 0 命中，`§61.55` 已确认数据源确实没有）；
  矩阵里**一格未跑、不计 PASS 也不计 FAIL**。
- **3 FAIL 归因（均不判播放器缺陷）**：`ape×remote` 4 MiB 截断片段缺尾部
  seek table（同片段 LOCAL PASS）；`aiff` 为纯标准库合成极简 FORM/AIFF PCM、
  未被 demuxer 打开（自造容器）；剩余一格归因见矩阵报告。
- **重跑通道（复用既有脚本，不新造）**：
  `tools/l3_f3_fixture_gen.py` → `tools/l3_f3_matrix_run.py` →
  `tools/l3_f3_matrix_audit.py`；
  REMOTE = `EXTRA_SOURCE_URL` + `python -m http.server 8765` + `adb reverse`；
  LOCAL = `adb push` + `shell cat | run-as cat`；
  端点需设备在线、真机命令全部带 `-s <serial>`。
- **判据纪律（`§6`）**：单独 `MediaSession state=3` 或单独 `Write start play`
  **不等于播放成功**（`§61.75` 已实证）；须并用本 app AudioTrack `state:started`、
  position 递增、自动进曲 3 首无 `-103`、seek / ENDED。
- **阻塞（如实挂账）**：① 7 种 NO_SAMPLE 需用户提供样本或换源；
  ② 主动 seek 需用户**手动拖动**进度条（USB / 物理在场）；
  ③ 设备端点需在场；④ B2 `:engine-opensource` 的 Kotlin 编译与
  `./gradlew test` 回归尚未验证（`§61.81` 离线缺 media3 缓存）。
  **本批 NOT RUN、不伪造矩阵结果**。

---

## 5. 诚实边界

- **M2 前置取证 ≠ M2 完成**：`m2_started=False` / `m2_completed=False` /
  `m2_gate_passed=False`；M2 三项交付（扩展交叉编译 / F3 重跑 / 补样本）
  **一项未落地**，阶段门禁未通过，不得进入 M3。
- **F4 仍 BLOCKED**，`§61.75` 终局裁决不变；**路线切换 ≠ 修复**、
  **M1 骨架 ≠ B2 完成**、**前置取证 ≠ 修复**。
- A3 仍 G09 PARTIAL、舱不冻结、`engine_compat_test` 与指纹不回填、
  F3 14 格未改写、7 种 NO_SAMPLE 未补。
- **零生产代码改动 / 零新增依赖 / 零模块启用 / 未换绑 / 未重编译 / 未重装**；
  so 二进制原样、不动解码器、**未连 adb / 未连真机 / 未联网 / 未下载上游源码**、
  未改系统环境变量、未 `pm grant`。
- **不引 `media3-session`**（C1 范围限定）、**18 个 so 暂不移除**（回退 + C5）、
  R7 **不作法律结论**、音效降级与「下载后播放」仍按 `§61.81` 默认假设挂账。
- **`§61.50~§61.81` 的 A 路线证据与 M0/M1 机器证据冻结不改**，本批只追加
  `§61.82` 断点与 `analysis/`、`tools/` 产物；**未新增 `docs/` 文件**
  → `09 §3` 计数仍 **31 个**。

---

## 6. 下一步 / 需用户拍板项（本批不擅自执行）

1. **联网获取上游**：是否授权联网拉取 Android NDK / CMake / FFmpeg 源码 /
   Media3 FFmpeg 扩展源码（偏离现行 `--offline` 约定）；或由用户提供离线副本。
2. **7 种 NO_SAMPLE 样本**：提供样本或确认换源（当前数据源 0 命中）。
3. **R7 / LGPL 重编**：不阻塞自用，阻塞公开发布；不作法律结论。
4. **M1 真实编译验证**（`§61.81` 遗留）：是否授权联网拉取 3 个 media3 制品到
   临时 Gradle 缓存，完成 `:engine-opensource:compileDebugKotlin` + `./gradlew test`。
5. 上述任一未决项解决前，M2 保持 `PREFLIGHT` 状态，按门禁回写断点与记忆，
   **不动解码器**。

---

## 7. 机器证据

| 文件 | 用途 |
|---|---|
| `tools/l3_b2_m2_preflight_audit.py` | 纯离线只读审计（纯 stdlib / `device_run=false`） |
| `analysis/l3-b2-m2-preflight.json` | 机器证据（checks / verdict / boundaries / toolchain_gaps） |
| `analysis/l3-b2-m2-preflight-report.md` | 人类可读报告 |
| `analysis/l3-f3-matrix-report.md` / `analysis/l3-f3-matrix.json` | F3 14 格基线（历史证据，冻结不改） |
| `analysis/l3-audio-format-audit.json` | 75 demuxer / 100 decoder / 6 处白名单实证（冻结不改） |
| `analysis/l3-b2-media3-probe.json` | Google Maven 制品可达性 + 无 `media3-ffmpeg` 实证（冻结不改） |
| `analysis/l3-audio-decoder-effect-research-2026-11-11.md` | GPL 构建配置 / 硬解 vs 软解 / 官方扩展手动构建依据（冻结不改） |
## 9. 补证批注（`12 §61.83`，2026-11-11，第 3 节历史正文不改）

> 本报告 `§0~§8` 是 M2 前置取证的原始记录，**原文保留**；本节只追加补证结论。

- **第 3 节「9 个格式 decoder 编入未确认」已由 `12 §61.83` 逐项收口**：
  `tools/l3_b2_m2_decoder_symbol_audit.py`（纯 stdlib 手写 ELF32/64 解析）读
  `libffmpeg.so` 的 `.dynsym` 导出符号表，隔离 venv `pyelftools 0.33` 独立互证；
  证据等级由 §61.44 的「二进制可打印字符串扫描」提升为「ELF 导出符号」。
- **两 ABI 各 100 `ff_*_decoder` / 75 `ff_*_demuxer`，集合逐项一致、与 §61.44
  100/75 吻合**；`.symtab` 已 strip，本证据只覆盖 `.dynsym` 导出符号。
- **9 项判定**：`tak/tta/wavpack/dff/aiff/shorten/truehd/mlp` 8 项 `NO_SYMBOL`；
  唯一 `dsf` = `DEMUXER_SYMBOL_ONLY`（有 `ff_dsf_demuxer`、无 `ff_dsf_decoder`）。
  **口径细化**：第 3 节原称「有 demuxer 或字符串痕迹」，实证**仅 `dsf` 真有
  demuxer 导出符号**，其余 8 个连 demuxer 导出符号都没有；`aiff` 无
  `ff_aiff_demuxer` 与 `§4` 的 F3 3 FAIL「`aiff` 自造容器未被 demuxer 打开」相互印证。
  `dsd_lsbf/msbf*` 四个 DSD-PCM decoder 符号编入，印证 §3 关于 DSD 需单独重做
  DoP / native DSD 通路的判断，但不等价于 `dsf/dff` 可播。
- **铁律与边界不变**：**导出符号存在 ≠ decoder 可用 ≠ 真机可播**；
  `.symtab` strip 故不作「未编入」绝对断言；第 3 节「候选集、不是已支持清单」
  结论不变，F3 14 格重跑仍 **NOT RUN**、7 种 NO_SAMPLE 未补。
  **符号表补证 ≠ M2 完成**、`m2_gate_passed=False`、**F4 仍 BLOCKED**、
  `§61.75` 终局裁决不变。详见 `analysis/l3-b2-m2-decoder-symbols-report.md`。


> AI生成
