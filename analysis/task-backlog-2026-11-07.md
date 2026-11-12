# CloudMusic · 文档 ↔ 代码核对与任务清单（2026-11-07 第三十三轮）

> **★ 2026-11-12 第一百一十五批（`docs/12 §61.90`）：M4 自动进曲真机 PASS。**
> Room 双曲队列 SEQUENCE（DB run-as 推送，冷启动恢复）→ t1 自然 ENDED
> → **31ms 自动进曲**（G05 消费链 B2 同路径）→ t2 12.019s 精确播完 →
> SEQUENCE 末尾收束 + PAUSED 会话回退，零 crash。M4 主体判定完成：
> seek 三点（12/4/0ms）✓ / ENDED ✓ / 自动进曲 ✓；gapless/ReplayGain 挂账
> （C7 降级域）。方法教训：source_ref JSON 一律 json.dumps + json.loads
> 自验（{{ 转义二次踩坑）；DB 模板行硬编码（锚点行可能被前次 DELETE 清除）。
> M5 启动：20 分钟长稳（LIST_LOOP 双曲）+ F4 多信号验收（B2 口径）+ A3 收口。
> F4 仍 BLOCKED（历史档案）、A3 仍 G09 PARTIAL、舱不冻结。断点
> `§61.89 → §61.90`。
>
> **★ 2026-11-12 第一百一十四批（`docs/12 §61.89`，用户提供新端点 39565）：
> **M3 门禁 PASS + M4 seek/ENDED 实锤 —— M3 完结、M4 开工。**
> `tools/b2_m34_acceptance.py`（61.88b）真机执行全绿：① 65s URL 连续播放
> playing/ended/error=False + 缓存 527KB 落盘；② 移除 reverse 后复播出声 =
> **SimpleCache 命中实锤**（回落直连必失败）；③ M4 seek 三点 12ms/4ms/0ms
> 全 PASS（判据 <500ms）+ **ENDED 一手到达**（§61.49 起挂账清零）。
> M3 门禁 = PASS（连续 60s ✓ + 缓存命中 ✓）→ **M4 开工**。未竟：自动进曲双曲
> 队列注入、gapless/ReplayGain、aiff/wma/ape/wv Extractor（§61.87 维持）。
> F4 仍 BLOCKED（历史档案）、A3 仍 G09 PARTIAL、舱不冻结。断点
> `§61.88 → §61.89`。下一步：M4 收尾（自动进曲双曲验证 / gapless）→
> M5 全量回归（F4 验收 B2 口径 + A3 收口 + 20 分钟长稳）。
>
> **★ 2026-11-12 第一百一十三批（`docs/12 §61.88`，用户「继续」）：B2 M3
> 缓存/Range 接线落地（官方 CacheDataSource + SimpleCache）。**
> URL 态 = CacheDataSource 包 DefaultDataSource（HTTP 跨协议重定向），缓存优先
> + FLAG_IGNORE_CACHE_ON_ERROR 回落直连；SimpleCache LRU 256MiB @
> cacheDir/b2_media_cache（卸载即清）；LOCAL 态直读不缓存；DefaultMediaSourceFactory
> 注入。**全部官方语义，零 A 路线私有 vfile/commit/代次门控移植**。真机一手：
> 65s URL 源 READY(64999ms) + PLAYING + media cache ready（端点 38407）。
> 排障两枚：`am start-foreground-service` 触发 MIUI 前台限制 → 服务拆解 →
> playUrl 协程静默取消（外部注入一律 am startservice，F3 同）；dumpsys user
> 三层 restrictions 空确认门在 MIUI 层。观测中断：MIUI Doze 重置无线调试 →
> **M3 门禁未判**（60s 连续观测 + 缓存命中复播待设备回归补测，logcat 现场
> 留存设备侧）；自动发现轮询器守候。F4 仍 BLOCKED（历史档案）、A3 仍 G09
> PARTIAL、舱不冻结。断点 `§61.87 → §61.88`。下一步：设备回归 → M3 验收
> 补测 → M4 播放语义（seek/ENDED/gapless，seek 判据不再 NOT RUN）→ M5。
>
> **★ 2026-11-12 第一百一十二批（`docs/12 §61.87`，用户「继续」×3 + 新端点
> 38407）：M1 真机缺陷修复 + F3 22 格真机重跑 + **M2 门禁 PASS**。**
> ① **M1 骨架真机缺陷**：ExoPlayer 单线程违例（verifyApplicationThread 拒跨线程
> 调用，M1 在 Dispatchers.Default 构建/调用 → 命令静默丢弃 → 永不 READY，首轮
> 22 格全 FAIL）→ **主线程门面化**（onMain / onMainSync post+latch）修复。
> ② **F3 22 格真机重跑 PASS 14 / FAIL 8 ≥ 基线 11 → M2 门禁 PASS、解锁 M3**：
> mp3/flac/wav/m4a/aac/opus/ogg 全格式双路径出声（engines=['media3']）；
> ogg PASS + `Loaded FfmpegAudioRenderer` 实锤 libffmpegJNI.so 真机解码出力；
> FAIL 8 全为预登记的容器 Extractor 缺口（aiff/wma/ape/wv，decoder 在位），
> 零意外零崩溃。③ m4a 排障：自造 fMP4 105 处叶子 box largesize 被
> FragmentedMp4Extractor 拒收 → 夹具改 ffmpeg 标准容器，旧样本留作 F4 证据。
> ④ 解析器补丁（logcat pid 段）+ `tools/b2_device_pipeline.py`（4747cc9e）。
> ⑤ **F4 仍 BLOCKED（历史档案）**、A3 仍 G09 PARTIAL、舱不冻结、指纹不回填。
> 断点 `§61.86 → §61.87`。下一步：M3 `RangeCacheDataSource`（门禁已过）→
> M4 播放语义（seek/ENDED/gapless）→ M5 全量回归；aiff/wma/ape/wv
> extractor 单独立项；G09 复测。
>
> **★ 2026-11-12 第一百一十一批（`docs/12 §61.84~§61.85`，用户「拉取最新代码
> 了解最新进展 继续任务」→「无人值守按既定方案推进」）：M1 门禁补全 + B2 M2
> 落地 + F3 夹具扩展。**
> ① **M1 门禁 PASS（§61.84）**：联网拉取 media3 1.11.1 三制品后
> `:engine-opensource:compileDebugKotlin` 首次真实编译验证 SUCCESS（关闭 §61.81
> 遗留的联网编译验证缺口）；**施工单 §3 预判纠错** —— media3 1.11.1 AAR
> metadata 实测要求 compileSdk ≥ 36（7 issues，预判「要求 35」只查了 Maven
> 可达性未查 metadata）→ `libs.versions.toml compileSdk 35→36`（targetSdk
> 保持 35）+ android-36 平台入 SDK（platform-36_r02.zip）+ ndk 目录项对齐实装
> 27.1.12297006；全量回归 `test :app:assembleDebug` PASS（commit `228347af`）。
> ② **M2 落地（§61.85，commit `32ee7e6a`）**：JNI 三件 + decoder_ffmpeg Java
> 源入库（1.11.1 原样，Apache 2.0）；**Windows 构建四缺口逐一解决**（NDK 无
> 扩展名 shim ×47 / chocolatey make 4.4.1 / SDK 路径空格 → D:
dk271 junction /
> FFmpeg `VERSION` 与 libc++ `<version>` 大小写冲突改名）；FFmpeg 6.0
> `--disable-everything` + 16 解码器裁剪 4 ABI 交叉编译 EXIT:0；
> `:engine-opensource` externalNativeBuild → **libffmpegJNI.so 7.2MB 双 ABI**；
> 依赖演进（C4）：media3-decoder / androidx.annotation 1.9.1 / checker-qual
> 3.49.0；`Media3EngineAdapter` 接 `EXTENSION_RENDERER_MODE_ON`（硬解优先）。
> ③ **F3 夹具扩展（61.85b，commit `e905432e`）**：`l3_f3_fixture_gen.py` 新增
> ffmpeg 合成通道（生成后 `-f null` 全解码自检）→ **NO_SAMPLE 7→3**（aac/opus/
> wv 合成、m4a 登记 §61.61 已验证样本；tak/dsf/dff 无编码器——ffmpeg
> decode-only / 无 DSD encoder，结论材料 = OpenList 0 命中 + §61.83 符号实证）；
> `--skip-openlist` 幂等化。**M2 门禁第二条件（明确样本或换源结论）实质达成**；
> 第一条件（F3 重跑 PASS ≥ 11）仍 NOT RUN——需设备上线。
> ④ **守护**：新增 §61.85；**修复 §61.81~83 守护对 gitignored 本地记忆文件的
> 不可移植依赖**（`.workbuddy*/memory` 在其他会话/机器不存在或不含批次号 →
> 改为只对仓库内文件断言，防跨会话误报、不伪造记忆内容）。
> ⑤ **诚实边界**：libffmpegJNI.so 产物存在 ≠ decoder 可用 ≠ 真机可播；F3 14 格
> 重跑 NOT RUN、**M2 门禁未通过、不得进入 M3**；ape/wma（ASF）容器无 Media3
> Extractor（重跑时预期 FAIL/降级，如实登记）；**F4 仍 BLOCKED**、§61.75 终局
> 裁决不变；A3 仍 G09 PARTIAL、舱不冻结、指纹不回填。零 P0 native 改动、
> 18 私有 so 原样、未连 adb。正式断点 `§61.83 → §61.85`。
> **下一步（均需设备上线）**：① F3 22 格重跑（11 格式 × REMOTE/LOCAL，
> `tools/l3_f3_matrix_run.py`）→ 判 M2 门禁；② M3 `RangeCacheDataSource`；
> ③ G09 复测（USB / 亮屏）。tak/dsf/dff 维持换源结论。
>
> **★ 2026-11-11 第一百一十批（`docs/12 §61.83`，用户「继续」→ M2 前置补证 / 纯离线）**：
> 承接 §61.82 留下的「9 个格式 decoder 编入未确认」缺口，在用户选定的「保持离线」
> 框架内做纯离线补证：新增 `tools/l3_b2_m2_decoder_symbol_audit.py`（**纯 stdlib 零依赖**，
> 手写 ELF32/64 LE 解析器读 `.dynsym`，18 项 checks 全 PASS）→
> `analysis/l3-b2-m2-decoder-symbols.{json,report.md}`，并用隔离 venv
> `pyelftools 0.33` 做**独立实现互证** → `...-crosscheck.json`（`all_match=True`）。
> **证据等级从 §61.44 的「扫二进制可打印字符串」提升到「ELF 导出符号」**：
> arm64-v8a 与 armeabi-v7a 两 ABI 各自恰好 **100 个 `ff_*_decoder` + 75 个
> `ff_*_demuxer` 导出符号**，集合逐项完全一致、与 §61.44 的 100/75 吻合；
> 两份 so 的 `.symtab` 已 strip 但 AVCodec 全局变量保留在 `.dynsym`。
> **9 项存疑格式逐项收口**：`tak/tta/wavpack/dff/aiff/shorten/truehd/mlp` 8 项
> `NO_SYMBOL`、唯一 `dsf` = `DEMUXER_SYMBOL_ONLY`（有 `ff_dsf_demuxer`、无 decoder）；
> 15 项候选集 8 `DECODER_SYMBOL_PRESENT` / 2 `DEMUXER_SYMBOL_ONLY` / 5 `NO_SYMBOL`。
> 对 §61.82 第 3 节**只追加批注、不改历史正文**（原称「有 demuxer 或字符串痕迹」，
> 实证仅 dsf 真有 demuxer 导出符号；`aiff` 无 demuxer 与 F3 3 FAIL 相互印证）。
> **诚实边界**：**符号表补证 ≠ M2 完成**、**导出符号存在 ≠ decoder 可用 ≠ 真机可播**、
> `.symtab` strip 故不作「未编入」绝对断言；F3 14 格重跑仍 NOT RUN、M2 门禁未通过、
> **F4 仍 BLOCKED**、`§61.75` 终局裁决不变。零生产代码改动 / 零新增依赖 /
> so 只读未改 / 不动解码器 / 未联网 / 未连 adb / 未改系统环境变量；
> 未新增 `docs/` 文件（`09 §3` 仍 **31 个**）。正式断点 `§61.82 → §61.83`
> （推进的是补证证据等级，**不是 M2 阶段推进、不是 M2 开工**）。
> **★ 2026-11-11 第一百零九批（`docs/12 §61.82`，用户 21:01 选「保持离线，做 M2 前置」→ 纯离线取证）**：
> 承接 §61.81 遗留「是否联网完成真实编译验证」，用户选择**保持离线、不偏离 `--offline`**，
> 只做 M2 纯离线前置取证：产出 `analysis/l3-b2-m2-preflight-2026-11-11.md`（门禁 / 交叉编译
> 前置清单 / 格式覆盖候选集 / F3 14 格重跑施工单）、`tools/l3_b2_m2_preflight_audit.py`
> （50 项 checks）→ `analysis/l3-b2-m2-preflight.{json,report.md}`、
> `check_source_contracts.py §61.82` 守护、全量回写。**本机只读实测 4 项刚性缺口全缺**：
> Android NDK / CMake / FFmpeg 源码 / Media3 FFmpeg 扩展源码；Google Maven 无
> `media3-ffmpeg` 制品（§61.80 实证）→ **纯离线条件下 M2 交叉编译不可执行**，
> 本批未联网 / 未下载 / 未生成扩展产物。7 种 NO_SAMPLE 未补、F3 14 格重跑 **NOT RUN**、
> `§61.81` 的 Kotlin 编译与 test 回归尚未验证。**诚实边界**：**M2 前置取证 ≠ M2 完成**
> （`m2_gate_passed=False` / `stage=M2_PREFLIGHT`）、**F4 仍 BLOCKED**、
> `§61.75` 终局裁决不变；A3 仍 G09 PARTIAL、舱不冻结、F3 14 格未改写。
> 零生产代码改动 / 零新增依赖 / 未连 adb / 未重编译 / so 原样 / 不动解码器；
> 未新增 `docs/` 文件（`09 §3` 仍 **31 个**）。正式断点 `§61.81 → §61.82`。
> **★ 2026-11-11 第一百零八批（`docs/12 §61.81`，用户 18:19「授权开工 M1」→ B2 M1 骨架接入）**：
> 承接 §61.80 切换 B2 决策，按施工单 M1 定义落地：`gradle/libs.versions.toml` 加 `media3 = "1.11.1"`
> 与 exoplayer/datasource/common 三制品、`settings.gradle.kts` 启用第 20 个模块 `:engine-opensource`、
> 新建 `Media3EngineAdapter`（`IMusicEngine` 14 方法骨架）、`core-di/AppBindings.kt` 单点换绑且保留
> `:engine-legacy` 回退；6 项确认按用户授权的默认假设执行（音效降级留空 / 不引 `media3-session` /
> 18 个 so 暂不移除 / NO_SAMPLE·R7·「下载后播放」挂账）。新增 `tools/l3_b2_m1_audit.py`（39 项 checks）
> 与 `check_source_contracts.py §61.81` 守护，`§61.80` 守护演进为 M0/M1 双态。**Gradle 离线实跑**
> `:engine-opensource:compileDebugKotlin --offline` 得 `BUILD FAILED` / `EXIT 1`——配置阶段与
> core-common/core-model/engine-api 编译通过，但 3 个 media3 制品无离线缓存，卡在 `debugCompileClasspath`
> 依赖解析，**Kotlin 源码编译尚未验证、如实标注未编译验证**（原始日志
> `analysis/l3-b2-m1-gradle-offline-build.log`）；47 套件 / 430 测试零回归未验证，不伪造 `BUILD SUCCESSFUL`。
> **诚实边界**：**M1 骨架 ≠ B2 完成**（`b2_completed=False` / `M1_SKELETON`），Range / 音效 / FFmpeg
> 扩展 / gapless·seek·ENDED 均按 M2~M4 挂账、未伪造接线；**F4 仍 BLOCKED**、`§61.75` 终局裁决不变，
> A3 仍 G09 PARTIAL、舱不冻结、F3 14 格未改写。**不是 F4 修复、不是线路验收**。正式断点
> `§61.80 → §61.81`。未新增 `docs/` 文件（`09 §3` 仍 **31 个**）。各批均未提交、未 push。
> **★ 2026-11-11 第一百零七批（见 `docs/12 §61.80`，用户 17:44 拍板「切换 B2」→ 纯离线决策落地）**：
> **路线拍板已落地，§61.79 的「仍需用户拍板」阻塞解除**。A1 时间盒 `timebox_passed=False` → 按《播放内核两条路线评估》§6.1 **停止 A、不追加 A2 二进制补丁**；**B2 成为现行技术路线**（Media3/ExoPlayer `1.11.1` + 手动构建 FFmpeg 软解扩展 + 自研缓存/Range，落点 `docs/02 §0 A4` 的 `:engine-opensource` 逃生舱、`IMusicEngine` 门面不变）。B1 / B3 不做；**不是改解码器**（F4 与解码器正交）。
> **7 条架构决策冲突如实登记**：C1「不引入 Media3」范围限定为媒体会话层（不引 `media3-session`）、C2 `#26` 演进、C3 P0 绑定演进（单点换绑 + 门面签名零改动 + `:engine-legacy` 保留回退）、C4 零新增依赖演进（实施批次起、同步 §61.42/§61.43 守护）、C5 18 so 移除延后、C6 `settings.gradle.kts`「路线 C」旧编号演进、C7 音效降级列 P4 挂账。前置实证 `analysis/l3-b2-media3-probe.json`：Google Maven 5 制品全 HTTP 200 / `media3-exoplayer` 77 版本 / 稳定版 `1.11.1` / **无 `media3-ffmpeg` 制品**；AGP 8.9.2 / compileSdk 35 / minSdk 26 兼容。
> 交付：`analysis/l3-b2-implementation-plan-2026-11-11.md`（M0~M5 施工单 + 14 方法映射 + `RangeCacheDataSource` 设计 + 回归判据 + 回退 + 6 项待确认）、`tools/l3_b2_route_switch_audit.py` → `analysis/l3-b2-route-switch-audit.{json,report.md}`、`check_source_contracts.py §61.80` 守护、全量回写 12 / 09 / 16 / 17（§8.40）/ 本清单 / runbook ⑬ / 路线评估批注 / 当日日志 / 工作记忆。未新增 `docs/` 文件（`09 §3` 仍 **31 个**）。
> **诚实边界**：**B2 实施尚未开始**（`implementation_started=False` / `completed=False`）——本批零生产代码改动、零新增依赖、零模块启用、未换绑、未重编译 / 未重装、so 二进制原样、不动解码器、未连 adb 改系统设置、未 `pm grant`。**路线切换 ≠ 修复**，REMOTE 缺陷仍在、`§61.75` 终局裁决不变、**F4 仍 BLOCKED**；A3 仍 G09 PARTIAL、舱不冻结、F3 14 格未改写、7 种 NO_SAMPLE 待补。A 路线 `§61.50~§61.79` 证据转历史档案，一字不改。正式断点 `§61.79 → §61.80`。
>
> **下一步（§61.80 后）**：① **M1 实施批次**（引入 `androidx.media3 1.11.1`、新建 `:engine-opensource`、`Media3EngineAdapter` 骨架、`EngineModule` 单点换绑、同步演进 `§61.42/§61.43` 基线守护）；② 开工前需用户逐项确认 6 项：音效降级范围 / 7 种 NO_SAMPLE 样本 / R7 LGPL 重编（不作法律结论）/「下载后播放」产品语义 / 是否允许 `media3-session` / 18 个 so 移除时机；③ M2~M5 按施工单阶段门禁推进，每阶段回写断点与记忆；④ F4 验收 `17 §8.3` → F5 → P2/P3/P4；⑤ F3 NO_SAMPLE / T-V USB·DMR / A3 G09 PARTIAL。**不动解码器**。
>
> 〔**§61.80 追加批注，历史正文不改**〕上列第一百零六批「下一步（§61.79 后）」之 ① 已于 `12 §61.80`（2026-11-11 17:44）由用户拍板执行完毕；② 仍待确认；③④⑤ 按施工单推进。
>
> **★ 2026-11-11 第一百零六批（见 `docs/12 §61.79`，用户明确授权「跑 A1 时间盒，接线回滚一起做」→ 含真机实验）**：
> **A1 时间盒实验已执行（NOT_RUN → 已裁决）**。真机 `10.0.0.2:37163`（Redmi K60 / Android 13 / arm64-v8a，基线 APK 不含 `§61.69` 接线），样本 `f3_m4a_complete.m4a`（16,573,387 B）经 `§61.47` 零权限通道写入 app 私有 `files/music/`。首轮 `initial_v1__DEFECTIVE` 4 处取证缺陷（跨轮 logcat 污染 / `dumpsys audio` 归属假阴性 / 6s 首快照错过 350ms ACTIVE 窗口 / REMOTE 轮前未清私有样本）仅作留痕**不作裁决依据**；v2 修正（先 `logcat -c` 再启动流式子进程、按本轮 app PID 分段、app uid=`10316` 关联 `dumpsys audio`、REMOTE 轮前清理、t=2s 早快照 + AudioTrack 一手信号）重跑得干净同窗对照（`restore_errors=[]`）。
> **LOCAL 主轮（pid=18560 / 517 行）**：`remote=false` ×2、REMOTE 取流特征 **0**、LOCAL 装配 8 → 完整避开 `NMCommonCache` 代次门控 / commit / `result:-2` / -103 链；`Open codec for aac` ×2 / `AudioTrack Play(1)` ×2 / `STATE_ACTIVE` ×2 / `Write start play nErr:0` ×2 / `Play status 4` ×2 / **`-103` 0 次** → **解码链点火成立**（相对 `§61.75` 主判据无一 PASS 的实质新进展）。但 `frames delivered=[1,1]` 每次仅 1 帧、约 350ms 后 `msg=2` → `Paused` → `Stop` → `Release End`，MediaSession 恒 `state=2` / position 恒 0、7 次快照无本 app `state:started` 轨 → **无持续播放**；`msg=2` 是过早完成通知 ≠ 自然播完 ENDED，`frames=1` 不计持续出声。**REMOTE 对照轮（pid=18565 / 628 行）**：`remote=true` / `Start id=5` / `WaitRequest=5` / `result:-2=4` / `commit :=1` / codec·write·AudioTrack 全 0 / `Play status 3` / `state=3` 但 position 0，与 `§61.61/§61.75` 一致。
> **时间盒裁决 `timebox_passed=False`**（`Write start play` ✅ / 本 app AudioTrack `state:started` ❌ / 自动进曲 3 首 ❌）→ `A1_BYPASS_STREAM_LIVE_PARTIAL__IGNITION_ONLY__NO_SUSTAINED_PLAYBACK__TIMEBOX_NOT_PASSED`，建议 **`B2_PENDING_USER_DECISION`**（不追加 A2 二进制补丁、**路线切换仍需用户拍板**；本批不擅自启动 B2 / 不动解码器 / 不重编译重装）。**「绕行 ≠ 修复」**：REMOTE 缺陷仍在、`§61.75` 终局裁决不变、**F4 仍 BLOCKED**；暴露「点火后不能持续」这一播放链下游新的独立问题。
> 同批按 `§61.70` 勘误**回滚 `§61.69` level-5 诊断接线**（`EngineAdapter.kt` 只删接线；`SetConfig` / VFM / `source.url != null` 分叉 / `remote ? Type.REMOTE : Type.LOCAL` 功能代码零改动；历史 `l3-f4-level5-wiring-audit.json` 冻结不覆盖），`check_source_contracts.py` 8 处旧断言改 `rollback_ok` + 新增 `§61.79` 守护。`tools/l3_f4_a1_bypass_live_probe.py`（v2 真机取证）→ `analysis/l3-f4-a1-bypass-live.json` + `l3-f4-logs/a1-bypass-{local,remote}-20261111-164027.txt`；`tools/l3_f4_a1_bypass_verdict.py`（纯离线裁决）→ `l3-f4-a1-bypass-verdict.{json,report.md}`；`tools/l3_f4_wiring_rollback_audit.py` → `l3-f4-wiring-rollback-audit.{json,report.md}`。全量回写 12 / 09 / 16 / 17 / 本清单 / runbook / 当日日志 / 工作记忆；未新增 `docs/` 文件（`09 §3` 仍 **31 个**）、零 P0 native 改动、未重编译 / 未重装、so 二进制原样、未连 adb 改系统设置、未 `pm grant`、配对码不落盘。**F4 仍 BLOCKED**，A3 仍 G09 PARTIAL、舱不冻结。正式断点由 `§61.78` 推进到 `§61.79`。
>
> **下一步（§61.79 后）**：① **用户拍板路线切换 B2**（A1 时间盒已按规则判不通过 → 停止 A、不追加 A2 二进制补丁；B2 = Media3/ExoPlayer + 按需裁剪 FFmpeg 软解扩展 + 自研缓存/Range，**仍需用户拍板**）；② 可选：用户确认「下载后播放」产品语义（正式语义 or 仅验证手段、REMOTE 是否保留）；③ 若继续攻 native，仍是 `§61.77` 三处锚点 `0x10798` / `0xc728` / `0x12dc8`，但按路线评估不追加 A2 二进制补丁；④ F4 验收 `17 §8.3` → F5 → 回 P2→P3→P4；⑤ F3 补齐 7 种 NO_SAMPLE、T-V USB / DMR 项、A3 G09 PARTIAL。**不动解码器**（`§61.77` 根因与解码器正交）。
>
> **★ 2026-11-11 第一百零五批（见 `docs/12 §61.78`，用户「了解最新进展，继续开发任务」→ 纯离线）**：
> 承接 `§61.77` 三处 native 锚点待拍板 + 同日《播放内核两条路线评估》A1 时间盒建议，**不新增真机证据、零 P0 改动、不连 adb**（只读核查 `10.0.0.2:37163` = `device`、`pm path` 有 `base.apk`，不操作设备）。**决定性证据 = `§61.61` 四格矩阵里同一份自洽 fMP4** `f3_m4a_complete.m4a`（16,573,387 B）：LOCAL 格 `url=null` → `XorLocalSource::Open(fd)` → **`Open codec for aac` / `Play status 4` / `Write start play nErr:0` / `mDuration 155933` / 无 `WaitRequest` / 无跳跃读**；REMOTE 格 `Start id=1..4` → **`commit : 368640` 冻结** → `offset:605007 readed_len:0 result:-2` → `Play status 3` / 无 codec。F3 14 格 = LOCAL 6 PASS / REMOTE 5 PASS，7 份 LOCAL logcat 全无 `Start id=` / `WaitRequest` / `MakeRangeRequestAsync` / `ReadFromFileNoLock` / `NMSliceFile` / `commit :`（唯一 `commit` 是系统 `mstatcommitLog`）；**14 格 seek / ENDED 全 false → F4 主判据在 LOCAL 从未跑过**。源码装配 `EngineAdapter.kt` 的 `source.url != null` → `SourceConfig` 的 `remote ? Type.REMOTE : Type.LOCAL`，切 LOCAL **零 P0 / 零 native / 零新增权限 · Manifest action · Gradle 依赖**（`§61.47` 已有 `EXTRA_LOCAL_PATH` 零权限通道）。
> **裁定 `A1_BYPASS_STREAM_SUPPORTED_BY_EXISTING_DEVICE_EVIDENCE__BYPASS_NOT_FIX__INFERENCE`**：「绕过取流」= 把音源预落为 App 私有本地文件再喂 LOCAL，是 `0x10798`/`0xc728`/`0x12dc8` 的**绕行而非修复**；REMOTE 缺陷仍在、`§61.75` 终局裁决（主判据无一 PASS）不变。**边界**：7 种 NO_SAMPLE 未覆盖、自洽 fMP4 片段 LOCAL 可播 ≠ 完整真实 m4a 已支持、整文件预下载 / 首播延迟 / 存储 / seek 即时性 / gapless / 流式语义代价、产品主音源（OpenList / WebDAV / strm）走 REMOTE 需用户拍板「下载后播放」语义、`§61.69` 接线回滚仍待拍板。
> `tools/l3_f4_bypass_stream_audit.py`（纯 stdlib 零依赖、`device_run=false`、**61 项 checks 全 PASS**，实扫 4 类证据不硬编码）→ `analysis/l3-f4-bypass-stream-audit.{json,report.md}` + `check_source_contracts.py §61.78` 守护；全量回写 12 / 09 / 16 / 17 / 本清单 / runbook / 当日日志 / 工作记忆。**A1 时间盒实验 NOT_RUN、需用户拍板**（按纪律不把「继续」当路线授权 / 接线回滚授权）：push 样本 → `run-as` 写入 `files/music/` → `ACTION_PLAY + EXTRA_LOCAL_PATH` → 60s logcat + dumpsys 多信号判据 + 自动进曲 3 首 / seek / ENDED → 通过继续 A（F4 验收 `17 §8.3` → F5）、不通过立即转 B2 不追加 A2 二进制补丁。零 P0 源码改动、未连 adb、未重编译 / 未重装、so 二进制原样、`§61.67~§61.77` 历史正文与证据一字未改、未新增 `docs/` 文件（`09 §3` 仍 **31 个**）。**F4 仍 BLOCKED**，A3 仍 G09 PARTIAL、舱不冻结。正式断点由 `§61.77` 推进到 `§61.78`。
>
> **下一步**：① **用户拍板 F4 修复技术路线**（A1 绕过取流 / 改 native / 换源；本批已把 A1 的可行性用已有真机证据钉死）→ ② 拍板后按 §8.38 的 A1 时间盒实验执行一次回归验证 → ③ F4 验收 `17 §8.3` → F5 → 回 P2→P3→P4；④ `§61.69` 接线回滚拍板（须同步调整 §61.69/71/72/73/74/75 守护）。**不动解码器**（`§61.77` 根因与解码器正交）。
>
> 〔**§61.79 追加批注，历史正文不改**〕上列 ② / ④ 已于 `12 §61.79`（2026-11-11 16:45）执行完毕：用户授权「跑 A1 时间盒，接线回滚一起做」→ A1 时间盒真机实验已跑并裁决 `timebox_passed=False`、`§61.69` 接线已回滚且守护已同步。① 的路线拍板结论见本清单最上方第一百零六批「下一步（§61.79 后）」。
>
> **★ 2026-11-11 第一百零四批（见 `docs/12 §61.77`，用户「继续」→ 纯离线）**：
> 真机取证已于 §61.75 完结、**本批不需要设备**。`libNMCommonCache.so` `.rodata @0x3bee8` = `NMBufferSourceImp(...ReadFromFileNoLock ... result:%d`，**实扫唯一引用** `0xc454/0xc45c`；`0xc434 blr [vtable+0x28]` 返回值即 result。**`0xc6f8 bl 0x10198`（WaitRequest）返回 1 → `0xc728 mov w0,#-2` 就是 `result:-2` 的产生点**（返回 2 → -0x6a、其它 → -0x67、返回 0 成功回读）；另一处 -2 是 `0x12dc8`（§61.67 的 `NMSlicesFile::Write` 越界门控）。`0xa9bc mov w25,#-2` 与 `0xa9c4 mov w25,#-0x67` 是**两条硬编码分支**，共用 `0xa9e4 str w25,[+0x1a0]`，且仅在**挂起异步读 `[+0x198]` 非零**时写入（`0xa9d0/cbz`）；`[+0x198]` 登记/清零共 6 处（`0x9f44`、`0x10b20`、`0xa018`、`0x100c8`、`0x106c0`、`0x1095c`）。完整静态链：代次错配 `0x10798` → commit 冻结 `0x10888` → `result:-2` → 挂起异步读 → -2/-103 写 `+0x1a0` → §61.76 `Finish Async` 返回 -103 → §61.74 门控静默早退 → 无音频输出。**inference**，不断定本次命中哪条分支。三处修复锚点 `0x10798` / `0xc728` / `0x12dc8` 均在 native 内，与 §61.59「Java 侧修不了」一致，**真要修复需用户拍板技术路线**（改 native / 绕过取流 / 换源），本批不擅自改动。
> `tools/l3_f4_read_gap_chain_disasm.py`（**31 项 checks 全 PASS**）→
> `analysis/l3-f4-read-gap-chain.{json,report.md}` +
> `check_source_contracts.py §61.77` 守护；全量回写 12 / 09 / 16 / 17 /
> 本清单 / runbook / 当日日志 / 工作记忆。零 P0 源码改动、未连 adb、
> 未重编译 / 未重装、so 二进制原样、接线回滚仍待拍板、§61.67~§61.76
> 历史正文与证据一字未改。**F4 仍 BLOCKED**（静态证据等级提升 ≠
> 修复 ≠ 验证），A3 仍 G09 PARTIAL、舱不冻结。正式断点由 `§61.76`
> 推进到 `§61.77`。
>
> **下一步**：**需用户拍板 F4 修复技术路线**（改 native / 绕过取流 /
> 换源）；三处锚点均在 native 内、Java 侧修不了。拍板后 → 修复 →
> 修复后一次回归验证 → F4 验收 `17 §8.3` → F5 → 回 P2→P3→P4；
> 接线回滚拍板属源码决策，真机取证已完结、无需再为验证上线。
>
> **★ 2026-11-11 第一百零三批（见 `docs/12 §61.76`，用户「继续」→ 纯离线）**：
> 真机取证已于 §61.75 完结，**本批不需要设备**。补 §61.74 门控上游链：
> ① `libNMCommonCache.so` 中 `-103` 是**硬编码立即数** `mov w25, #-0x67`
> （`0xa85c`/`0xa9c4`），写 `[+0x1a0]`（`0xa8b4`/`0xa9e4`）；写入 B 仅在
> 挂起异步读 `[+0x198]` 非零时发生（`0xa9d0/cbz`）。`Finish Async` 回调经
> `0x9fc0 bl 0x16468` 写线程队列投递，`0xa008` 读 `+0x1a0`、清零
> `+0x188/190/198`、`0xa040` 打印后 **`0xa064 mov w0,w20` + `0xa07c ret`
> 把 -103 原样返回播放侧**。② `libncmaudioplayer.so` `[+0x20c]` =
> **paused 标志**（`.rodata @0xe190a` = `AudioTrack(%d): Paused`），
> `[+0x1e8]` = AudioTrack 对象指针（设置 `0x572d4`/`0x583bc`、释放清零
> `0x66810` 等 5 处）→ 对应 §61.74 门控 1/2/3。③ 勘误：初扫容差把
> `0x3b927`（Seek 串）误算成 `Finish Async` 第二引用，已排除。
> ④ **inference**：不断定本次命中哪道门控，**F4 修复点前移**到
> 「读侧为何留下挂起异步读并写入 -103」（`+0x198` 写入点 +
> `result:-2` 传递链），接 §61.65/§61.67 代次错配 → commit 冻结。
> ⑤ `tools/l3_f4_minus103_chain_disasm.py`（**25 项 checks 全 PASS**）→
> `analysis/l3-f4-minus103-chain.{json,report.md}` +
> `check_source_contracts.py §61.76` 守护；全量回写 12 / 09 / 16 / 17 /
> 本清单 / runbook / 当日日志 / 工作记忆。⑥ 零 P0 源码改动、未连 adb、
> 未重编译 / 未重装、so 二进制原样、接线回滚仍待拍板、§61.67~§61.75
> 历史正文与证据一字未改。**F4 仍 BLOCKED**（静态证据等级提升 ≠
> 修复 ≠ 验证），A3 仍 G09 PARTIAL、舱不冻结。正式断点由 `§61.75`
> 推进到 `§61.76`。
>
> **下一步（离线）**：定位 `[+0x198]` 挂起异步读的写入点与
> `result:-2` 传递链 → F4 修复 → 修复后一次回归验证 → F4 验收
> `17 §8.3` → F5 → 回 P2→P3→P4；接线回滚拍板属源码决策。
>
> **★ 2026-11-11 第一百零二批（见 `docs/12 §61.75`，用户「早就让你把
> 真机部分验证完 怎么又要上线？」）**：**用户指正成立、口径纠错** ——
> 设备自 11:00 起在线，真机验证已完成；此前把「F4 主判据未通过」与
> 「上线后复跑」并列写成下一步，造成「验证没做完」的错觉。
> **本清单及 `docs/17 §7` 第 10 条 / §8.34 中的「设备上线后复跑」
> 表述一律作废**。① `10.0.0.2:39257` 本轮开始时 offline、
> `reconnect offline` / `disconnect+connect` 均失败；用户给
> `10.0.0.2:37163` → `connect` 成功、`get-state=device`，app 仍是
> §61.73 装好的不含 §61.69 接线的基线 APK。② 新增
> `tools/l3_f4_playback_control_probe.py`：用 `cmd media_session
> dispatch`（MIUI 拒 `input` 的 `INJECT_EVENTS`，但此通道可用、
> **无需物理在场**）执行 `play` / `next`×3 / `fast-forward` /
> `rewind`，6 次 rc=0、3895 行 logcat + 4 次 `dumpsys` 快照，
> `finally` 恢复 adb reverse / `wifiOnly` / http.server / logcat。
> ③ **关键纠偏**：MediaSession 曾 `state=3(PLAYING)`，但
> `position` 恒 0、`metadata:null`、无本 app AudioTrack
> `state:started`、`Write start play` 0 次、`-103` 1 次 →
> **状态机上报 ≠ 音频输出**；控制轮证据里单一信号的
> `playing_verdict=PASS` 标注**不作准**（历史 JSON 原样保留，
> 勘误另行登记、不改写）。④ `tools/l3_f4_acceptance_final_audit.py`
> 按 `17 §8.3` 逐条定案：`Write start play` / 完整时长 /
> AudioTrack / 自动进曲 3 首 / seek / `-103` 均 **FAIL**，
> F3 矩阵与 MediaSession 为 PARTIAL，ENDED **NOT RUN**，
> **主判据无一 PASS**（12 项 checks 全 PASS、`no_criterion_pass=true`）、
> **F4 仍 BLOCKED**。⑤ **真机取证到此完结**：设备在场能做的观测 /
> 注入 / 播放控制 / UI 截图 / dumpsys 全部完成并落盘，
> **不再以「设备上线」作为验证前置**；后续是 F4 修复与回归验证，
> `§61.69` 接线回滚属**源码决策、非真机验证**。⑥
> `check_source_contracts.py §61.75` 守护 + 全量回写 12 / 09 /
> 16 / 17 / 本清单 / runbook / 当日日志 / 工作记忆，并**删除工作记忆
> §3 的「设备在线窗口」条目**。⑦ 零 P0 源码改动、未重编译 /
> 未重装、so 二进制原样、未改用户网络 / 未 `pm grant` /
> 配对码不落盘、§61.67~§61.74 历史正文与证据一字未改。
> A3 仍 G09 PARTIAL、舱不冻结、指纹不回填。正式技术断点由
> `§61.74` 推进到 `§61.75`（真机验收终局裁决 + 口径纠错）。
>
> **下一步（不再需要为验证上线）**：① `§61.69` 接线是否回滚
> （建议回滚，须同步调整 §61.69/§61.71/§61.72/§61.73/§61.74/
> §61.75 守护）→ ② 离线定位「代次错配 → `commit` 冻结 →
> `-103` → 播放链未起」的修复点 → ③ **修复后**做一次回归验证
> （届时若端点变化才需新配对端点 + 一次性配对码）→ ④ F4 验收
> `17 §8.3` → F5 → 回 P2→P3→P4。
>
> **★ 2026-11-11 第一百零一批（见 `docs/12 §61.74`，用户「继续」→ 离线主线）**：
> 设备已可下线，承接 §61.73 三轮真机最靠前问题「有 aac codec /
> `mDuration 2933` 却 `Write start play` 皆 0」。纯离线反汇编
> `libncmaudioplayer.so`（capstone 5.0.7 + pyelftools，sha `5bf085f4…`）：
> ① `.rodata @0xe180a` = `AudioTrack(%d): Write start play nErr:%d`、
> tag `NMAudioPlayer`；全 `.text`（205503 指令）`adrp+(add|ldr)`
> 扫描确认**唯一引用** `0x66eec/0x66ef4`、打印 `0x66f00 bl 0x5c18c`。
> ② **决定性发现：打印点前 4 道静默早退门控** —— `[this+0x1e8]`
> 两次判空（`0x66e74 cbz→0x66f50` / `0x66eac cbz→0x66f40`）、
> `[this+0x20c]` 非零（`0x66eb4 cbnz→0x66f5c`）、
> `bl 0x13d80` 返回 3（`0x66ec8 cmp / 0x66ecc b.eq→0x66f5c`）；
> `nErr` 来自 `bl 0x14500`，重试 >9 经 `[this+0xd8]` vtable+0x20
> 回调传 -3。③ **inference（判据口径修正）**：`Write start play`
> 0 次**不是「没有写 AudioTrack」的充分证据**，F4 验收不得单独依赖
> 它，应并用 `MediaSession state=3(PLAYING)` + 本 app AudioTrack
> `state:started` + 自动进曲 3 首无 `-103` + seek / ENDED；
> **绝不反向推断播放成功**（`MediaSession PAUSED` / `metadata:null` /
> 无 AudioTrack / `-103` 是独立一手证据，§61.73 结论仍成立）。
> 可验证锚点均需真机或动态插桩，本批未做、不臆断命中哪道门控。
> ④ `tools/l3_f4_play_gate_disasm.py`（**16 项 checks 全 PASS**）→
> `analysis/l3-f4-play-gate-{disasm.json,report.md}` +
> `check_source_contracts.py §61.74` 守护；§61.71 固化回放证据与
> §61.73 三轮审计证据只读交叉、零改写、不重跑覆盖。
> ⑤ 全量回写 12 / 09 / 16 / 17 / 本清单 / runbook / 当日日志 /
> 工作记忆；零 P0 源码改动、未连 adb、未重编译 / 未重装、
> so 二进制原样、未改用户网络、接线回滚仍待拍板。
> **F4 仍 BLOCKED**（静态证据等级提升 ≠ 修复 ≠ 验证），A3 仍
> G09 PARTIAL、舱不冻结、指纹不回填。正式技术断点由 `§61.73`
> 推进到 `§61.74`。**下一步**：① 接线回滚拍板（须同步调整
> §61.69 / §61.71 / §61.72 / §61.73 / §61.74 守护）→ ② 设备上线后
> 按**修正后的多信号判据**复跑 → ③ 让播放进入 PLAYING →
> ④ F4 验收 `17 §8.3` → F5 → 回 P2→P3→P4。
>
> **★ 2026-11-11 第一百批（见 `docs/12 §61.73`，用户给在线端点
> 「趁着设备在线赶紧做」）**：`40467` 超时 10060，`10.0.0.2:37509` 连上
> （Redmi K60 `23013RK75C` / `mondrian`）；`pm path` 空 → app 未装
> （§61.69 冷缓存现场）→ 装 §61.69 接线**之前**的基线 APK
> （`install -r` Success，**P0 源码未改、接线回滚仍待拍板**）。
> ① 按 §61.72 现行链完成**三轮 60s 真机纯观测**（冷 / 暖 / 用户拖动），
> 每轮 probe → 读 `log_file` → `--log` 真实格式串判读，`restore` 全 True。
> ② `SetLoggerCallback` 三轮 0 次 + 冷/暖轮 W 级 3/1 行 →
> **`g_level=0` 一手实证**；commit 恒定 253952 / 262144 / 262144、
> 代次 1→6 / 1→6 / 1→4、7 条真实 `.rodata` W 分支串**全 0 命中
> （有效负结果）**、`UnExpect New Offsetcommted` 未出现 → 代次错配仍
> 无直接分支日志。③ aac codec 仅冷/暖轮、`Write start play` 三轮皆 0、
> `-103` 冷/暖各 1 → **F4 主判据未通过**。④ 用户口述**已执行拖动**
> 且「进度条也没有时间」：`39257` 重新上线后 `screencap` 得播放页
> 截图（两端 `00:00`、播放按钮 ▶ 未播放、红色进度条约 1/4 处），
> 第三轮日志无 `mDuration` / 无 `Open codec` / 仅 `Play status 3`、
> `onSeek`/`seekTo`/`Slider` 全 0 → **播放未进入可 seek 态，拖动未
> 转化为有效 seek，seek 判据 NOT RUN**（原因不是用户未配合；用户口述 /
> UI 截图 / 日志分开登记、不互相冒充）。`input swipe` 实测被 MIUI
> `SecurityException: INJECT_EVENTS` 拒。⑤ 新增
> `tools/l3_f4_live_rounds_audit.py`（**19 项 checks 全 PASS**）+
> `analysis/l3-f4-live-rounds-{audit.json,report.md}` +
> `analysis/l3-f4-live-ui-20261111.png` + `check_source_contracts.py
> §61.73` 守护；§61.71 固化回放证据 sha256 `c350b06b…b12f` 零改写、
> 未重跑覆盖。⑥ 全量回写 12 / 09 / 16 / 17（P1★ 第 9 条 / F4 台账 /
> 末尾追加块 / §8.33）/ 本清单 / runbook / 当日日志 / 工作记忆。
> ⑦ **零 P0 源码改动**、未重编译 / 未重装含接线 APK、so 二进制原样、
> 未改用户网络 / 未 `pm grant` / 配对码不落盘、§61.67~§61.72 历史
> 正文与证据一字未改。**F4 仍 BLOCKED**，A3 仍 G09 PARTIAL /
> 舱不冻结、指纹不回填。正式技术断点由 `§61.72` 推进到 `§61.73`
> （真机一手证据链，不是 F4 修复 / 验收通过）。
>
> **下一步**：**更靠前的问题 = 先让播放进入可 seek 态**（总时长
> `00:00` / 无 `Write start play`），再谈触发 W 分支；之后
> ① 接线回滚拍板（须同步调整 §61.69 / §61.71 / §61.72 / §61.73 守护）
> → ② 设备在线窗口（当前 `10.0.0.2:39257`，端口会变）→ ③ F4 验收
> `17 §8.3` → F5 → 回 P2→P3→P4。
>
> **★ 2026-11-11 第九十九批（见 `docs/12 §61.72`，用户「继续」）**：只读
> `adb devices -l` 仍为空 → 设备完全离线；按纪律**不把「继续」当作接线
> 回滚授权**。盘点确认本轮可离线推进且不重复的缺口是**上线执行链本身**：
> ① runbook 顶部 §61.70 补充 / §61.71 ④ 已是「保持 `g_level=0` + 主动
> seek + probe 抓 logcat + 真实格式串扫 W 级」，但同文档 §1「一键执行
> （唯一命令）」仍指向 §61.59 已完成并裁决的 A/B `--apply`、§2 仍是旧
> 三分法，`l3_f4_preflight.py` 的 `one_liner` 亦停留在旧实验 → 设备上线
> 照 §1 跑会重跑已完成实验、临时改 P0、浪费冷缓存窗口；② 「主动 seek」
> 无任何可执行命令（探针无 seek 能力）；③ 探针落盘 logcat 路径未传给
> `--log`。做法：新增 `tools/l3_f4_online_readiness.py`（纯离线只读、
> 唯一外部动作 `adb devices`、`device_run=false`、`ready_to_run` 恒
> false、**14 项 checks 全 PASS**）→
> `analysis/l3-f4-online-readiness.{json,report.md}`；runbook 顶部加
> 「★ 现行上线执行链（§61.72）」两步一键命令 + `log_file` 路径传递 +
> 主动 seek 登记为用户**手动拖动（物理在场）**，§0/§1/§2 标为历史剧本
> 勿执行、§6 的 `HEAD 8c9d79b1` 加当时快照勘误。历史 A/B 正文、
> `§61.58` preflight 证据（`all_pass=true` / `ready_to_run=false` /
> 15 项）与 `§61.71` 固化回放证据（`mode=replay`）**零改写、不重跑**。
> `check_source_contracts.py §61.72` 守护 + 全量回写 12 / 09 / 16 /
> 17（P1★ §7 / F4 台账 / 末尾追加块 / §8.32）/ 本清单 / runbook /
> 当日日志 / 工作记忆；定稿核验见 `12 §61.72 ⑧`。**零 P0 源码改动**、
> 未重编译 / 未重装、未重跑 A/B、so 二进制原样、未切用户网络；
> 真机复跑 NOT RUN、接线未回滚（待用户拍板）。**F4 仍 BLOCKED**，A3 仍
> G09 PARTIAL、舱不冻结；`all_pass` 只表示离线执行链口径与资产自洽，
> 不是设备在线证明、不是 F4 验收通过。
>
> **下一步（均需用户）**：① §61.69 接线是否回滚（建议回滚，须同步调整
> §61.69 / §61.71 / §61.72 守护）；② 重开无线调试 → 新配对端点 +
> 一次性配对码（不落盘）+ 调试端点 → `adb pair` / `connect` /
> `get-state=device`；③ 确认 app 已装；④ 保持 `g_level=0` 复跑 +
> 播放中**手动** seek + 用真实格式串扫 W 级日志；⑤ F4 验收 `17 §8.3`
> → F5 → 回 P2→P3→P4。
>
> **★ 2026-11-11 第九十八批（见 `docs/12 §61.71`，用户「拉取代码 检查最新进展
> 继续完成任务」）**：`git pull --ff-only origin main` 得远程 `a619d34f`
> （§61.67~§61.70 四批），本地 = 远程 `0/0`、工作区干净。设备
> `adb devices -l` 仍为空、§61.69 接线回滚待用户拍板 → 不空等、不擅自
> 回滚 / 切网络，转做「上线后判据是否正确」的最后一环离线准备。
> ① **缺口**：runbook ④ 要求用真实 `.rodata` 格式串扫 W 级日志并统计 I 级
> 代次 / `commit` 序列，但被指定复跑的 `tools/l3_f4_vfile_commit_probe.py`
> 只有 commit 时序、无 W 分支判读、不统计代次序列（§61.68 用的是已被
> §61.70 证伪的猜测关键词表）。② 新增 `tools/l3_f4_wbranch_recheck.py`：
> 纯 stdlib 解析 ELF64 section header 读 7 条原生 `.rodata` 格式串
> （`0x3CC73`/`0x3CCC9`/`0x3CF90`/`0x3CFCE`/`0x3D062`/`0x3D017`/`0x3D0AE`）
> 驱动扫描，**零第三方依赖、不连 adb、不采集日志**；`--log` 单份 logcat
> 判读输出 4 种 `live_verdict`，`device_run` 恒 `false`。③ 回放 **34 份**
> 归档：6 条错误分支格式串 **0 命中**（§61.68 两轮 0 命中是分支未触发的
> 有效负结果）、`Write all finish` 仅 `ksab-flac-remote.txt` 1 次（默认
> `g_level=0` 下 W 级可输出）、§61.68 主日志代次 1→22 / `commit` 恒 16384 /
> `-103` 在场 / `Write start play` 0，`--log` 模式实跑通过；
> **13 项 checks 全 PASS**、so sha256 与 §61.70 一致。④ 交付
> `analysis/l3-f4-wbranch-recheck.json` + 报告 +
> `check_source_contracts.py §61.71` 守护；全量回写 12 / 09 / 16 / 17 /
> 本清单 / runbook / 工作记忆；未新增 `docs/` 文件。⑤ **同步本地工作记忆**：
> 此前停留在 §61.66（含已证伪的「开 level-5」路线），已按 §61.70 现行口径
> 刷新；工作记忆按 `.gitignore` 不入库。⑥ **零 P0 源码改动**、未重编译 /
> 未重装 APK、so 二进制原样、未切用户网络；真机复跑 NOT RUN、接线未回滚。
> **F4 仍 BLOCKED**、A3 仍 G09 PARTIAL、舱不冻结；`all_pass` 只表示离线
> 一致性核验通过，不是 F4 验收通过。
> ⑦ **正式批次定稿核验（见 `12 §61.71 ⑧`）**：`--log` 单日志判读默认写到
> 独立的 `analysis/l3-f4-wbranch-live-verdict.json`，不覆盖固化回放证据；
> 删去证据 JSON 中已被 §61.70 证伪的「真机 level-5 观测」口径；守护加
> `mode=replay` / `single_log_verdict=null` 断言。全量回写补齐
> `17`（P1★ §7 / F4 台账 / 末尾追加块）与当日日志；回放工具、契约自检、
> `git diff --check` 复跑全 PASS。
>
> **下一步（均需用户）**：① §61.69 接线是否回滚（建议回滚，须同步调整
> §61.69 / §61.71 守护）；② 重开无线调试 → 新配对端点 + 一次性配对码
> （不落盘）+ 调试端点 → `adb pair` / `connect` / `get-state=device`；
> ③ 确认 app 已装（未装则手机文件管理手动点
> `/sdcard/Download/CloudMusic-debug.apk`）；④ 保持 `g_level=0` 复跑 +
> 播放中主动 seek + 用本工具扫真实格式串；⑤ F4 验收 `17 §8.3` → F5 →
> 回 P2→P3→P4。
>
> **★ 2026-11-11 执行层细化（非新批次，见 `analysis/l3-f4-online-runbook.md`
> 的 `12 §61.70 补充执行层细化`）**：设备仍完全离线，纯离线反汇编把
> `§61.70` 留下的「构造触发 W 级分支的输入」落成可执行判据 ——
> ① F4 的 7 条 W 级格式串**全部是错误 / 边界分支告警**，正常顺序写入
> 不打印（`§61.68` 两轮 0 命中是正确行为，非日志门控）；
> ② `NMNetSource::OnData` 钉死因果链：代次与 `[this+0xc0]` 不等即
> `b.ne 0x108e0` 整包丢弃，**仅代次匹配才更新 `commit=offset+size`**，
> 收齐判据 `commit >= [this+0x38]+[this+0x40]` → 实证代次 `1→22`
> 递增 + 重发 ⇒ `commit` 冻结 16384 ⇒ `-2` / `-103` ⇒ 无 `Write start play`；
> ③ 真断点是**代次错配**不是日志级别；上线首要先手 = 保持 `g_level=0`
> + 播放中**主动 seek** + 用真实格式串扫 `UnExpect New Offsetcommted`
> （出现即把推断升级为一手实证）。
> 该细化**只改 runbook 执行层**，不改 `docs/12` 章节号 / 进度表 /
> 契约守护 / 断点号（正式断点仍 `§61.70`），零 P0 改动、零网络改动。
> **F4 仍 BLOCKED**。
>
> **★ 2026-11-11 第九十七批（见 `docs/12 §61.70`，纯离线门控语义纠错）**：
> 设备 `192.168.1.11:41703` 仍完全离线（`adb devices -l` 空列表），不空等、
> 不擅动用户网络，转做纯离线取证并**纠正前两批结论**：
> ① `libNMCommonCache.so` 门控是 `g_level > N` 抑制，`g_level` 默认 0
> （`.bss` 零初值 + 全库唯一 setter + `.init_array` 无写）→ **I/D/W/E
> 日志默认全部已输出**，不存在需额外开启的 level-5 开关；
> ② `§61.68` 两轮 W 级关键词全 0 是**分支未触发**的有效负结果，
> `setprop VERBOSE` 负结果成立但归因错误；
> ③ `§61.69` 的 `SetLoggerCallback(INMLogger(), 5)` 对 F4 目标 W 级
> **零增益**、会**抑制 I/D** 级证据；`INMLogger` 空类缺
> `BIInfo(ILjava/lang/String;)V` → `GetMethodID` 必然失败、可能留 JNI
> pending exception → **建议回滚，待用户拍板，本批未执行**；
> ④ **F4 仍 BLOCKED**，断点性质改为「构造能触发 W 级分支的输入
> （`offset+len > file_len` / 代次不匹配 / `commit != offset`）」，
> 设备上线后复跑须保持 `g_level=0`、用真实 `.rodata` 格式串扫 W 级日志。
> 新增 `tools/l3_f4_level5_gate_semantics_audit.py`（34/34 PASS）+ 报告 +
> `check_source_contracts.py §61.70` 守护。零 P0 源码改动、未重编译 /
> 未重装 APK、未改动用户网络。详见
> `analysis/l3-f4-level5-gate-semantics-report.md`。
>
> **下一步（须用户拍板 / 操作）**：① `§61.69` 接线是否回滚（建议回滚，
> 回滚需同步调整 `§61.69` 守护）；② 重新开启手机无线调试 → 给新配对端点
> + 一次性配对码（不落盘）→ `adb pair` + `adb connect`；③ 保持
> `g_level=0` 复跑并构造触发 W 级分支的输入；④ F4 验收 `17 §8.3` → F5。

> **★ 2026-11-11 第九十六批（见 `docs/12 §61.69`，用户「`jx、` →
> 选项「继续＝按 A 走（授权改 P0）」）**：承接第九十五批剩余断点①
> 「level-5 开启决策」。① 先用 `tools/l3_f4_loglevel_gate_probe.py`
> （19/19 PASS）+ 二进制取证定死三条硬约束：`INMLogger` 是空类 → 传
> `INMLogger()` 实例而非 null；getter `0x37e20` 是 `cmp w8,w0; cset w0,gt`
> **严格大于**语义 → `level=5` 不是必然解锁；`NMSlicesFile::Write` 里
> `g_level > 5` 会直接 return `-102/-1/-99` → **level 绝不能 ≥ 6**。
> ② 用户显式授权后落地**唯一生产源码改动** `EngineAdapter.kt` +20 行：
> `prepare()` 内 `SetConfig(json)` 之前接入
> `NMVirtualFileManager.SetLoggerCallback(INMLogger(), level)`
> （默认 5 / `filesDir/nmcache_log_level` 覆写 / `.coerceIn(0,5)`）；
> `:app:assembleDebug --offline` **BUILD SUCCESSFUL in 2m31s**、APK
> `31302687 B`。零播放逻辑改动、零新增依赖、so 二进制原样。
> ③ **真机 level-5 观测 NOT RUN**：`adb install -r` →
> `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（两套 debug.keystore）→ 卸载后全新
> 安装触发 MIUI `INSTALL_FAILED_USER_RESTRICTED`；`pm install` / session
> `install-create/write/commit` / `settings put global
> package_verifier_user_consent 0`（无 `WRITE_SECURE_SETTINGS`）/
> `am start` `file://` 与 MediaStore `content://`（报「解析软件包时出现问题
> (11)」：`com.miui.packageinstaller has no access to
> content://media/external/file/1000025218`）全部无效 → MIUI 系统级限制，
> **须用户在手机上确认一次**。APK 已推到
> `/sdcard/Download/CloudMusic-debug.apk`。随后设备 `192.168.1.11:41703`
> **完全离线**（`Test-NetConnection :41703 = False`、ICMP 均不通，本机
> WLAN 仍 `192.168.1.19`、**未改动用户网络**）。
> ④ 卸载前已 `run-as tar` 备份 app 私有数据到
> `analysis/l3-f4-backup/app-data-20261111.tar`（50969600 B，含
> `legacy_engine_cache` 切片）；卸载清空该缓存 → **后续真机起点是冷缓存**，
> 与 §61.68 暖缓存条件不同。`ACTION_PLAY` 直接 `playUrl`，不需恢复 Room 夹具。
> ⑤ 交付 `tools/l3_f4_level5_wiring_audit.py` →
> `analysis/l3-f4-level5-wiring-audit.json`（23 项 checks 全 PASS）+ 报告
> `analysis/l3-f4-level5-wiring-report.md` + `check_source_contracts.py
> §61.69` 守护。⑥ **F4 仍 BLOCKED**（诊断接线 + 编译产物就绪 ≠ 真机验证
> ≠ 修复）：`0x10798` 丢弃分支 / `NMSlicesFile::Write` 门控仍无直接分支日志、
> `-103` 未通过、自动进曲 3 首 / seek / ENDED NOT RUN、F3 14 格未改写、
> A3 仍 G09 PARTIAL → 舱不冻结、指纹不回填。零基线变化、配对码不落盘、
> 未改动用户网络。
> **正式技术断点由 `§61.68` 推进到 `§61.69`**（推进的是「诊断接线 +
> 编译产物就绪」，不是 F4 修复、不是真机验证）；本批改动未提交，
> `origin/main` 仍 `917bb7c7`。
> **下一步（须用户操作）**：重新开启手机无线调试 → 给新配对端点 +
> 一次性配对码（不落盘）→ `adb pair` + `adb connect` → 确认 app 是否已装
> （未装则在手机「文件管理」手动装 `/sdcard/Download/CloudMusic-debug.apk`）
> → 跑 `python tools/l3_f4_vfile_commit_probe.py --serial <新端点>
> --wait 60 --out analysis/l3-f4-level5-live.json` 对比 level-5 关键词，
> 必要时 `run-as` 写 `files/nmcache_log_level` 做 0/4/5 对照。
>
> **★ 2026-11-11 第九十五批（见 `docs/12 §61.68`，用户「41703 →
> 配对端点 + 一次性配对码 → 需要真机的任务赶紧做」）**：设备经
> `adb pair`（配对码不落盘）+ `adb connect 192.168.1.11:41703` 重新上线
> （Redmi K60 / Android 13 / API 33 / `arm64-v8a`；`41703` 单独 connect
> 被拒但 TCP 连通 → 判定调试端口、须先 pair 配对端口）后，立即执行
> **两轮真机纯观测**（60 s 基线 + 35 s `setprop log.tag.* VERBOSE` 试探，
> 复用 `tools/l3_f4_vfile_commit_probe.py`），零 P0 源码改动、未重编译 /
> 未重装 APK、未改动用户网络。① §61.67 的「代次递增 → 透传 → 重发」由
> `inference` **升级为运行时一手实证**：`MakeRangeRequestAsync(N)` 的 `N`
> 从 **1 连续递增到 22**、`NMNetSource(1-K)` 同步到 `1-21`、
> `force:true` 15 次 / `commit` 两轮恒 `16384`、`-103` 5 次、
> `result:-2` 26 条、Java `onData` 2706 条、aac codec 打开但
> `Write start play` / `doPlayCompleted` 均 0。② level-5 门控：
> `setprop VERBOSE` 为**有效负结果**（`D/NMCache` 80 条仍在、
> `NMSlicesFile` / `OnWriteNetData` / SKIP / TRIM 两轮全 0）→ 真正开关是
> native `NMVirtualFileManager.SetLoggerCallback(INMLogger, int)` 的
> level 参数，开启须改 Java 源码 + 重编译 + 重装 APK（动 P0 舱体），
> **本轮未做、待用户拍板**。③ 仍未升级：`0x10798` 整包丢弃分支与
> `NMSlicesFile::Write` 的 `file_len` 门控无直接分支日志；本轮跳跃读
> `offset=327895` / `25941`，未复现 §61.61/§61.62 的 `605007`。
> ④ 交付 `tools/l3_f4_generation_gate_live_audit.py` →
> `analysis/l3-f4-generation-gate-live-audit.json` + 报告
> `analysis/l3-f4-generation-gate-live-report.md` + 两轮 logcat / 两份
> 证据 JSON + `check_source_contracts.py §61.68` 守护。⑤ **F4 仍 BLOCKED**
> （证据等级提升 ≠ 修复 ≠ 验证）：`-103` 未通过、自动进曲 3 首 / seek /
> ENDED NOT RUN、F3 14 格未改写、A3 仍 G09 PARTIAL → 舱不冻结、
> 指纹不回填。零基线变化、so 二进制原样、配对码不落盘。
> **正式技术断点由 `§61.67` 推进到 `§61.68`**；本批改动未提交，
> `origin/main` 仍 `917bb7c7`。
>
> **★ 2026-11-10 第九十四批（见 `docs/12 §61.67`，用户「拉取最新代码 →
> 41417 → 先继续离线任务」）**：**纯离线、只读、不需要设备在场**。
> ① 拉取：`git pull --ff-only origin main` 由 `fe081778` 快进到 `917bb7c7`
> （7 提交 / 299 文件 / +129687 行），确认 `§61.66` 仅为仓库同步、
> 正式技术断点仍 `§61.65` / F4 BLOCKED。② 用户追加端点 `41417`：
> `10.0.0.2:41417` → `10060` 超时、`192.168.1.11:41417` → 拒绝连接，
> 重启 adb 会话、系统 adb `35.0.2` 与项目 `tools/bin/adb.exe` `37.0.1`
> 两个版本均试，`adb devices -l` 恒空 → 按用户选择登记为**待连端点**，
> 真机 A/B / level-5 日志实证 **NOT RUN**，未改动用户网络。
> ③ 离线把 §61.65 `inference` 的三个缺环补成一手二进制证据：
> `MakeRangeRequestAsync @0x104B0/0x104B8` 每次重发把 `[this+0xC0]`
> 代次 +1 并经 `0x104D8` 透传给 `0x104E8` 请求回调；
> `NMNetSource::OnData @0x10794/0x10798` 比较并整包丢弃。
> ④ 两条新实证：丢弃目标 `0x108E0` 位于 `0x1086C`（vtable+0x18 数据
> 回调 → `OnWriteNetData` 落盘入口）与 `0x10888`（commit 写）之后 →
> 被丢弃的包连数据投递都不执行（解释 §61.63 `.nmsfi` 只有 `[0,368640)`）；
> `NMSlicesFile::Write @0x127C0` 的 `0x12820/0x12824` 要求
> `offset+len <= [+0x70]=file_len`，越界 `-102` 静默不写
> （解释 §61.61/§61.62 跳跃读 `offset=605007` 恒 `result:-2`）。
> ⑤ 证据链：`tools/l3_f4_generation_gate_disasm.py`（45 锚点 / 11 字符串
> / 18 项 checks）+ `analysis/l3-f4-generation-gate-disasm.json` /
> `-report.md` + `tools/l3_f4_generation_gate_audit.py` →
> `analysis/l3-f4-generation-gate-audit.json` +
> `check_source_contracts.py §61.67` 守护。⑥ **F4 仍 BLOCKED**
> （证据等级提升 ≠ 修复 ≠ 验证）：`-103` 判据未通过、自动进曲 3 首 /
> seek / ENDED NOT RUN、F3 14 格未改写、A3 仍 G09 PARTIAL → 舱不冻结、
> 指纹不回填。零 P0 源码改动、`libNMCommonCache.so` 二进制原样、
> 零设备操作、零基线变化。⑦ **正式技术断点由 `docs/12 §61.65`
> 推进到 `§61.67`**（§61.66 仅仓库同步）；本批改动当时未提交，
> `origin/main` 仍 `917bb7c7`。
>
> **★ 2026-11-10 第九十三批（见 `docs/12 §61.66`，用户「提交推送」）**：
> **仓库同步批次**，零生产源码改动 / 零设备操作 / 零验收结论变化，
> **不推进技术断点**（仍 `§61.65` / F4 BLOCKED）。① 提交前实查
> `origin/main = fe081778 = merge-base`、ahead 5 / behind 0 →
> fast-forward 安全；`git status --short` 163 项（未跟踪目录折叠，
> 展开实为 264 新增 + 17 修改）。② `git add -A` → **281 文件 /
> +127728 行 / 67.7 MB**、零删除；索引级扫描无明文口令、无签名直链 /
> Bearer token、无 `.tmp` / `.pyc` / `*.jks`，`.workbuddy-ai/` 按
> `.gitignore` 排除；F3 音频样本 39 MB / 截图 17 MB / logcat 10 MB
> 按历史既有做法（仓库已追踪 5267 个二进制分析产物）一并入库。
> ③ 提交 `c26e7cb8`（`§61.38`~`§61.65` 二十八批）→
> `git push origin main` 得 `fe081778..c26e7cb8  main -> main`，
> exit `0`；`§61.31~§61.35` 五批历史本地 commit 一并同步；实查
> 远程 = 本地 `c26e7cb8`、`0/0`、工作区干净、契约自检 PASS。
> ④ **现行仓库状态**：二十八批已提交并 push；各历史批次块里的
> 「未提交、未 push / `HEAD` 仍 `8c9d79b1`」保留为**当时快照**，不改写。
> ⑤ **同步仓库 ≠ 通过验收**：F4 仍 BLOCKED、F3 14 格未改写、
> A3 仍 G09 PARTIAL、舱不冻结、指纹不回填；下一步仍是反汇编
> `MakeRangeRequestAsync` 与 `NMSlicesFile::Write @0x12844`、
> 设备在线时开 level-5 日志升级为实证。⑥ **守护口径迁移**：`§61.6x`
> 11 个钉死旧 `HEAD=8c9d79b1` 的历史审计工具（复验时确实把 1 份证据
> JSON 写成 `all_pass=false`，已 `git restore` 恢复、历史一字未改）按
> `§61.46` 纪律统一加「拒绝重跑、返回 2、不写文件」保护；实跑 11/11
> exit 2、49 个 `analysis/l3-f4-*.json` 哈希零变化、`py_compile` OK、
> 契约自检 PASS。此后长效守护以 `check_source_contracts.py` + 已固化
> JSON 为准，这 11 个工具升格为当时快照工具、不计入「全量审计 PASS」。
>
> **★ 2026-11-10 第九十二批（见 `docs/12 §61.65`，用户「环境已就绪 →
> 反汇编 libNMCommonCache.so 落盘链路」）**：**离线、只读、不需要设备在场**
> （端点 `10.0.0.2:41761` 已 offline，本批未连 adb）。① **回答的问题**：
> 「1 MiB 回传数据为何 `commit` 只有 16384~180224 B」——瓶颈不在 Java
> 取流、也不在 vfile 写不进去，而在 `NMNetSource` 的**请求代次校验 +
> commit 记账链**。② **一手反汇编**（`capstone + pyelftools`，`.dynsym`
> / 121 个 PLT stub / ADRP+ADD 字符串）：`OnNetSourceData @0xBA44`
> `kind=1` 额外写 `NMMemoryCache` 镜像、**两种 kind 都投递写线程任务**；
> `OnWriteNetData @0xBCAC` 以 `[+0xA8]=file_offset_` 产生 SKIP / TRIM、
> `0xBE80` 写失败永久清零 `+0xB8`、成功 `bl NotifyCacheProgress`；
> **`NMNetSource::OnData @0x10748` 的 `0x10798 b.ne 0x108E0` 是唯一硬
> 早退**（代次不匹配整包丢弃），`0x10888` 写 `commit=offset+length`，
> `0x108D0/0x108DC` 才唤醒读侧。③ **真机交叉**：§61.64 主判据日志
> **1028 条 `onData` / 9 次 `Start` 各 1 MiB / 每包 8192 B**，Java
> 完整回传；`commit` 恒 **16384**、`result:-2` 12 次、代次
> `(1-0)→(1-8)`、delayed 2s ×8 → 读侧读空重发使旧代次在途包被丢弃
> （**推断，未新做 A/B**）。`kind=1` 因内存镜像重发较晚故 180224，
> `kind=2` 只剩 16384。④ **精度修正**：`commit` 是连续确认偏移、
> **不是 vfile 落盘字节数**（§61.62/§61.64 曾当代理）；§61.64 正文
> 「8 次 Start」按原始日志登记为 9 次、历史不改写。⑤ 交付：
> `tools/l3_f4_netsource_commit_disasm.py` →
> `analysis/l3-f4-netsource-commit-disasm.json`（**15 项 checks 全 PASS**
> / 21 锚点 / 11 字符串 / PLT 还原 / 日志交叉）、报告
> `l3-f4-netsource-commit-report.md`、`check_source_contracts.py §61.65`
> 守护；全量回写 `docs/12 §61.65` / `09 §2·§7` / `16 §5.3` /
> `17 §8.3·§8.25` / 本清单 / 当日日志 / 工作记忆。**未新增 `docs/`
> 文件** → `09 §3` 计数仍 **31 个**。验证（实跑）：`py_compile` OK、
> 反汇编工具 exit 0、§61.64 裁决 30 项 / §61.50 13 项 / §61.49 17 项 /
> §61.48 10 项审计未回潮、`--offline` BUILD SUCCESSFUL /
> **47 套件 · 430 测试 · 0 失败**、dev **6/6**、release
> **FAIL(C1,C4)** 正确阻断、契约自检 PASS（含 §61.65）；零 P0 源码改动、
> so 二进制原样、无凭据、无设备改动。⑥ **诚实边界**：**F4 仍 BLOCKED**
> —— 定位 ≠ 修复 ≠ 验证；`-103` 未通过、自动进曲 3 首 / seek / ENDED
> 仍 NOT RUN、F3 14 格未改写、A3 仍 G09 PARTIAL / 舱不冻结，
> **不写「F4 已解决 / m4a 已支持」**。**下一步**：反汇编
> `MakeRangeRequestAsync` 代次递增点与 `NMSlicesFile::Write @0x12844`，
> 设备在线时开 level-5 日志升级为实证。`§61.38`~`§61.65`
> **二十八批**改动均未提交、未 push，`HEAD` 仍 `8c9d79b1`。
> **正式断点由 `docs/12 §61.64` 推进到 `§61.65`。**
>
> **★ 2026-11-10 第九十一批（见 `docs/12 §61.64`，用户提供新端口
> `41761` → `10.0.0.2:41761` 上线）**：按 §61.63 下一步用
> `--warmup 4.0` **复跑变体 D，首次取得有效判据**。① 变体编译
> `BUILD SUCCESSFUL in 8s` / `install -r` Success；主判据自洽
> 样本 REMOTE 60 s（4,181 行，链路完整展开）、回归 mp3/wav
> 各 12 s。② **有效判据成立**：`createDataRequestHandler` /
> `NMSliceFile` 新建 / 8 次 `Start` / `WaitRequest commit` /
> `-103` 全在位，回归两格均 `Write start play nErr:0` 且无
> `-103` PASS（证明变体生效、不是 §61.63 run1 的空跑）。
> ③ **同起点对照（新建切片 / `Start id=1 offset=0`）**：基线
> `kind=1` `commit=180224`、≈33 s 打开 `aac`；变体 `kind=2`
> **`commit=16384`（仅 1 个块）、全程无 `Open codec`、
> `Write start play` 0 次、`-103` ×1** → **「kind 改成 2 能修
> vfile 落盘」的修复预测被真机证伪**；kind 槽确实参与落盘但
> 方向相反。④ 根因方向转移到 **`OnWriteNetData` /
> `NMSliceFile::Write` / `commit` 记账的 native 落盘链路**
> （推论，待反汇编定位）；§61.63 的「kind 错配假说」按有效
> 负结果收口，不写「F4 已解决 / m4a 已支持」。⑤ **F4 仍
> BLOCKED**；自动进曲 3 首 / seek / ENDED NOT RUN、F3 14 格
> 未改写、A3 仍 G09 PARTIAL、舱不冻结、P0 零净改动
> （`git diff` 空）。⑥ 交付：`analysis/l3-f4-data-kind-ab-run3.json`
> + `-main/-mp3/-wav.json` + 3 份 logcat +
> `analysis/l3-f4-data-kind-verdict-report.md` +
> `tools/l3_f4_data_kind_verdict_audit.py` → **30 项 checks
> 全 PASS** + `check_source_contracts.py §61.64` 守护。
> ⑦ **顺带收口长期挂账的反汇编环境缺口**：`capstone 5.0.7` +
> `pyelftools` 装入隔离 venv（未污染系统 Python），
> `l3_f4_disasm_verify.py` / `l3_f4_jni_arg_verify.py` /
> `l3_f4_task_diff_verify.py` 复跑 **10/17/14 项全 PASS**；
> 具体 `OnWriteNetData` 定位仍待下批。⑧ 实跑：`py_compile` OK、
> §61.60~§61.64 审计与契约自检 PASS、`./gradlew test --offline`
> **47 套件 · 430 测试 · 0 失败 0 错误 0 跳过**、
> `git diff` 空、reverse / datastore 空、so 指纹一致、
> 零发布基线变化、全库无凭据泄露；设备仍在线、跑完可断开。
> ⑨ 全量回写 `docs/12 §61.64`、`09 §2·§7`、`16 §5.3`、
> `17 §8.24`、本清单、当日日志与 `MEMORY.md`；端点登记
> `10.0.0.2:41761`。`§61.38`~`§61.64` 二十七批未提交、
> 未 push，`HEAD` 仍 `8c9d79b1`。**正式断点由
> `docs/12 §61.63` 推进到 `§61.64`。**
>
> **★ 2026-11-10 第九十批（见 `docs/12 §61.63`，用户「继续」
> → 授权跑变体验证）**：① **设备侧 `.nmsfi` 明文索引一手取证**
> （9 个）：自洽 fMP4 只有 `[0,368640)` trunk / 切片实际 368640 B /
> mtime 前一晚 23:51；mp3 2 片全满、flac/wav 4 片各 1 MiB 全满、
> 另有不连续 trunk 可写 → **vfile 落盘机制本身能工作**，
> 坐实 §61.62 的 `commit=368640` 是「Java 回传的 1 MiB 数据一个
> 字节都没进 vfile」。② **清缓存对照（零代码改动）RULED_OUT**：
> 新建 `NMSliceFile` / `Start id=1 offset=0` 后 trunk 总字节
> 368640→**180224**、`commit=180224`、仍 `-103` ×4 / 无
> `Write start play` → 陈旧切片复用不是根因；初版判据把「JSON
> 变化」误当增长，已改为比较落盘总字节数并离线重判。
> ③ **新假说（未证实）**：路线 2 已切 normal mode，但数据包
> kind 仍按 Memory Mode 校准恒传 `1`（只写内存缓存），
> `kind==2` 才投递 `OnWriteNetData` 落盘任务。
> ④ **变体 D 两次均无有效判据**：run1 三格只有 `prepare`
> （`install -r` 后冷启动 `JNI_OnLoad` 晚近 2s + 首版判据缺陷，
> 已修为 `NO_VALID_CRITERION`，历史 JSON 另存不改写）；
> run2 变体编译 `BUILD SUCCESSFUL in 7s` 但 `install -r` 因
> **device offline** 失败、判据 NOT RUN → **不写假设证伪 /
> PASS / 崩溃**。`finally` 逐字节恢复 P0 源码、基线重编译成功、
> `git diff` 空。⑤ **F4 仍 BLOCKED**；自动进曲 3 首 / seek /
> ENDED NOT RUN、F3 14 格未改写、A3 仍 G09 PARTIAL、舱不冻结、
> 零发布基线变化；设备末尾掉线、可断开。⑥ 交付：
> `analysis/l3-f4-vfile-index-before/after.json` +
> `l3-f4-cache-reset-verdict.json` +
> `l3-f4-data-kind-ab-run1/run2.json` + 3 份 logcat +
> `tools/l3_f4_cache_reset_probe.py` / `l3_f4_data_kind_ab.py`；
> `l3_f4_vfile_commit_probe.py` 加 `--out` / `--warmup`；
> 报告 `analysis/l3-f4-vfile-diagnosis-report.md` +
> `tools/l3_f4_vfile_diagnosis_audit.py` → **35 项 checks 全 PASS**
> + `check_source_contracts.py §61.63` 守护。⑦ 实跑：
> `py_compile` OK、§61.60~§61.63 审计与契约自检 PASS、
> `./gradlew test --offline` **47 套件 · 430 测试 · 0 失败
> 0 错误 0 跳过**、so 指纹一致、全库无凭据泄露；全量回写
> `docs/12 §61.63`、`09 §2·§7`、`16 §5.3`、`17 §8.23`、
> 本清单、当日日志与 `MEMORY.md`。下一步：设备再次上线先
> `get-state=device`，用 `--warmup 4.0` 复跑变体 D，并做
> `OnNetSourceData` / `OnWriteNetData` native 级反汇编定位
> （需 capstone）。`§61.38`~`§61.63` 二十六批未提交、未 push，
> `HEAD` 仍 `8c9d79b1`。**正式断点由 `docs/12 §61.62`
> 推进到 `§61.63`。**
>
> **★ 2026-11-10 第八十九批（见 `docs/12 §61.62`，用户提供新端点
> `10.0.0.2:39737`）**：设备上线（首次 10060 超时，重启 adb 会话后
> `get-state=device`；preflight `all_pass=True / device_online=True`）
> → 趁在场做**零代码改动的 60 s 纯观测**，回答 §61.61 挂账的
> 「落盘速度不足 vs commit/Range 记账语义」。① P0 基线 APK（未改 /
> 未重编译 / 未重装）+ 自洽样本 `f3_m4a_complete.m4a` × REMOTE +
> 流式全量 logcat 4,560 行（`remote=true` 已核实，排除 LOCAL 残留）。
> ② **`commit` 60 s 恒定 `368640`**、5 次 `force:true` 重试仍
> `offset:605007 readed_len:0 result:-2` → 排除单纯速度不足、
> **强化 §61.61 的 vfile 归因**；10:27:58 `PreSeekReadTask` 回退 →
> **`Start id=6 offset=368640`** → **≈32.9 s `Open codec for aac` /
> `openSource 0`**，但随即 **`lasterr -103`**、AudioTrack Release、
> **`Write start play` 0 次、未出声**。③ **结论修正（历史不改写）**：
> §61.61 的「无 codec」是观察窗口不足（此前 12 s / 45 s 短于自愈
> 路径）；**F4 判据收窄到 `-103`**，真实阻塞是「vfile 跳跃读长时间
> 重试 + 数据不足」而非 codec 打不开。④ **F4 仍 BLOCKED**（观测 ≠
> 修复验证、`-103` 判据未通过）；自动进曲 3 首 / seek / ENDED
> NOT RUN、F3 14 格未改写、A3 仍 G09 PARTIAL、舱不冻结、
> P0 零净改动。⑤ 交付：`analysis/l3-f4-vfile-commit-probe.json`
> （初判 `UNEXPECTED_CODEC_OPENED` 如实保留）+ `-report.md` +
> 原始 logcat + `tools/l3_f4_vfile_commit_audit.py` → `-audit.json`
> （**30 项 checks 全 PASS**）+ `check_source_contracts.py §61.62`
> 守护。⑥ 实跑：`py_compile` OK、§61.60/§61.61/§61.62 审计全 PASS、
> 契约自检 PASS、`./gradlew test --offline` **47 套件 · 430 测试 ·
> 0 失败 0 错误 0 跳过**、`git diff` 空、reverse / datastore /
> http server 全恢复、so 指纹一致、零发布基线变化、全库无凭据泄露。
> ⑦ 全量回写 `docs/12 §61.62`、`09 §2·§7`、`16 §5.3`、
> `17 §8.22`、本清单、当日日志与 `MEMORY.md`。设备仍在场但**跑完
> 仍可断开**；端点登记 `10.0.0.2:39737`。下一步：以 `-103` 为主
> 判据做 native 级定位，再设计「补齐 `[368640, 605007)` 空洞」
> 的 Java/JNI 变体验证。`§61.38`~`§61.62` 二十五批均未提交、
> 未 push，`HEAD` 仍 `8c9d79b1`。**正式断点由 `docs/12 §61.61`
> 推进到 `§61.62`。**
>
> **★ 2026-11-10 第八十八批（见 `docs/12 §61.61`，用户「完整闭环了……
> 现在固化这个结论」）**：**真机部分已全部跑完、设备可断开**；本批纯离线
> 只读（不连 adb、不操作设备、零生产代码改动），只解析已落盘 logcat 与 JSON。
> ① 双嫌疑隔离：自洽样本 `f3_m4a_complete.m4a`（16,573,387 B / 158 顶层
> box / `moof` 52 = `mdat` 52 / 以配对 `mdat` 收尾 / 解析末尾 == 文件长度）
> × 变体 B（`OnDataRequestEnd` 首参恒传 `1L`）。② 决定性对照：P0 基线 ×
> 自洽样本 **LOCAL PASS**（`Open codec for aac` / `Play status 4` /
> `Write start play nErr:0` / `mDuration 155933`）；**P0 基线 REMOTE 与
> 变体 B REMOTE 均无 codec**，两格同一
> `ReadFromFileNoLock offset:605007 readed_len:0 result:-2` 与
> `WaitRequest commit:368640 < expect offset:605007`；§61.59 旧非自洽
> 样本同位置失败读但 `commit:147456`。③ **裁决**：样本自洽性排除、
> 错误完成通知排除为第二卡点；**真正卡点 = normal mode vfile 流式落盘
> 的连续 commit 跟不上 fMP4 跳跃式 probe 读，中间空洞未补齐 →
> `WaitRequest` 永久等待**。§61.59「环节 B 部分成立」与 §61.60 结构事实
> 不改写，只推翻 §61.60「样本为主」的主次结论。④ **F4 仍 BLOCKED**
> （根因锁定 ≠ 修复；未实施/未验证、native 具体机制未定位）；自动进曲
> 3 首 / seek / ENDED 如实 NOT RUN、F3 14 格未改写、A3 仍 G09 PARTIAL、
> 舱不冻结、P0 零净改动。⑤ 交付：`analysis/l3-f4-second-blocker-final.json`
> + `-final-report.md` + `tools/l3_f4_second_blocker_final_audit.py`
> → `analysis/l3-f4-second-blocker-final-audit.json`（**40 项 checks
> 全 PASS**）+ `check_source_contracts.py §61.61` 守护；并补齐 §61.60
> 当时缺失的 `l3-f4-second-blocker-audit.json`（15 项全 PASS，契约自检
> 由 FAIL 恢复 PASS）、补写 `docs/12 §61.60` 断点。⑥ 实跑：`py_compile`
> OK、契约自检 PASS、`./gradlew test --offline` **47 套件 · 430 测试 ·
> 0 失败 0 错误 0 跳过**、`UrlDataRequest.kt` `git diff` 空、so 指纹
> 一致、全库无凭据泄露、零发布基线变化。⑦ 全量回写 `docs/12
> §61.60/§61.61`、`09 §2·§7`、`16 §5.3`、`17 §8.20/§8.21`、本清单、
> 当日日志与 `MEMORY.md`。下一步：按 vfile 连续提交点 ↔ 跳跃式 probe 读
> 做 native 级定位，下次设备上线验证修复 → F5 → P2 → P3 → P4。
> `§61.38`~`§61.61` 二十四批均未提交、未 push，`HEAD` 仍 `8c9d79b1`。
> **正式断点由 `docs/12 §61.59` 推进到 `§61.61`（含 §61.60 补写）。**
>
> **★ 2026-11-09 第八十七批（见 `docs/12 §61.60`，断点 2026-11-10
> 补写）**：设备离线 → 纯离线 ISO-BMFF 一手解析 `f3_m4a_fast.m4a`
> （13 `moof` / 12 `mdat` / 孤立尾部 `moof` / 无索引 / 截断在分片中间），
> 变体 B 的 `onData` 与请求逐字节一致、两处未请求空洞；**当时**把第二
> 卡点归因改为「样本不自洽为主、vfile 落盘为次」并判
> `PENDING_REAL_SAMPLE`。12 项结构证据 + 15 项独立审计全 PASS +
> `check_source_contracts.py §61.60` 守护。**该主次已被 §61.61 真机对照
> 推翻**，按历史快照保留，不改写 §61.59 A/B 实测事实、F4 仍 BLOCKED。
>
> **★ 2026-11-09 第八十六批（见 `docs/12 §61.59`，用户提供新端点
> `192.168.1.11:40807`）**：设备上线，preflight `all_pass=True /\n> device_online=True / READY` → **kind 槽 A/B 真机执行**（`ab_executed=true`）。
> ① 变体 B（首参恒传 `1L`）跑 25 s 与 **45 s** 两次，另用恢复重编译后的
> P0 基线做同设备/同样本/同回环对照；两次变体 `p0_restored=True`、
> `baseline_rebuild_ok=True`、5 格回归全 PASS。② **决定性证据**：
> 基线 `Start id=3/id=4 offset=605007` 同区间连锁重复（`onEnd=[1,2,3]`），
> 变体只剩 `id=1/id=2`、**无 `id>=3`**（`onEnd=[1,2]`），两次 `starts`
> 逐字一致、45 s 排除等待不足；但**两者均无 `Open codec` / `Write start`**，
> `Play status 3`、无 `-103`。基线对照复现重复，排除环境波动。
> ③ **结论分层**：环节 A 已证实（§61.56）；**环节 B 部分成立**（错误完成
> 通知是重复请求的成因）但**非充分原因**（消除重试后仍无 codec，存在
> 第二卡点：fMP4 多 Range × normal mode vfile 落盘 / demuxer probe 数据不足）；
> 反直觉观察「基线重新请求可能反而承担补充 Range 作用」未实证。
> **F4 仍 BLOCKED**，不写「假设被证伪」（重复确实被消除）也不写「F4 已解决」。
> ④ 判据缺陷已修：原 `falsified` 硬编码 `offset=327895`，本次基线重复为
> `605007` → 改 `repeated_range()` 模式检测（`id>=3` 与 `id=2` 同 offset），
> 三轮日志离线重判结论不变。⑤ 恢复实测：`git diff` 空、`:96` 仍为
> `OnDataRequestEnd(requestId, …)`、`files/datastore/` 空、
> `adb reverse` 无 `tcp:8765`、so 指纹一致。⑥ 交付：报告
> `analysis/l3-f4-ab-result-report.md` + run1/run2/control 三份 JSON +
> `analysis/l3-f4-logs/ab-*.txt` + `tools/l3_f4_ab_result_audit.py`
> → **19 项全 PASS** + `check_source_contracts.py §61.59` 守护；
> §61.53/§61.57/§61.58 三处守护及审计工具的「A/B 尚未执行」断言统一
> 迁移为「A/B 已执行且结果如实登记」，并解除 preflight ↔ 契约自检的
> 循环依赖（改为静态检查守护在位）；全量回写 `docs/12 §61.59`、
> `09 §2/§7`、`16 §5.3`、`17 §8.19`、runbook、本清单、当日日志与 `MEMORY.md`。
> ⑦ 实跑：`py_compile` OK、12 批审计全 PASS、`./gradlew test --offline`
> **47 套件 · 430 测试 · 0 失败 0 错误 0 跳过**、dev **6/6**、
> release **FAIL(C1,C4)** 正确阻断、契约自检 PASS（含 §61.59）、
> 全库无凭据泄露。⑧ **诚实边界**：自动进曲 3 首 / seek / ENDED 如实
> NOT RUN，F3 矩阵 14 格未改写；两个 m4a 仍是人工截取/重排 fMP4 片段；
> A3 仍 G09 PARTIAL、舱不冻结。下一步需 native 级定位第二卡点或提供
> 完整非 fMP4 的真实 `.m4a`。`§61.38`~`§61.59` 二十二批改动均未提交、
> 未 push，`HEAD` 仍 `8c9d79b1`。**正式断点由 `docs/12 §61.58` 推进到
> `§61.59`。**
>
> **★ 2026-11-09 第八十五批（见 `docs/12 §61.58`，用户「继续」；设备仍离线，
> 纯离线 / 只读 / 零代码改动 / 零设备操作）**：不再新增静态反汇编，把
> §61.53~§61.57 的全部准备**收口成可执行闭环**。① `tools/l3_f4_preflight.py`
> （**15 项 checks 全 PASS**）汇总 A/B 所需全部离线前置条件：adb 二进制、
> 基线 APK（31,433,878 B）、5 个样本、`8765` 端口可绑定、
> `l3_f3_matrix_run.py` 9 个符号、A/B 脚本与 `gradlew.bat` 修复在位、
> A/B 结果文件不存在、P0 `git diff` 空且无变体常量、**10 批守护 JSON
> 全绿**、契约自检 PASS、§61.57 编译预检证据绿、runbook 在位、凭据未落盘。
> ② **结论分层**：`all_pass=True`（前置条件全齐）与 `ready_to_run=False`
> （**唯一阻塞 = 设备离线**）是两个不同结论，`device_online=false` 不算
> 前置条件失败。③ `analysis/l3-f4-online-runbook.md` 把 20 批文档压成一页：
> 上线前置、**一键命令** `python tools/l3_f4_kind_slot_ab.py --serial
> <ip:port> --apply`、判读三分支（环节 B 成立 / 假设证伪 / 无有效判据，
> 含「必须人工核对 JSON、不能只看退出码」）、跑完必做的恢复确认、
> 常见坑、证据落盘位置。④ 一处踩坑如实登记：首跑自检因按工具名臆测
> `l3-f3-matrix-audit.json` / `l3-f2-ext-fix-audit.json` 而误报，
> 实际为 `l3-f3-audit.json`（17 项）/ `l3-f2-ext-fix.json`（10 项），
> 已修正复跑全绿。⑤ 交付：`analysis/l3-f4-preflight.json` +
> `tools/l3_f4_preflight_audit.py` → **16 项全 PASS** +
> `check_source_contracts.py §61.58` 守护；全量回写 `docs/12 §61.58`、
> `09 §2/§7`、`16 §5.3`、`17 §8.18`、本清单、当日日志与 `MEMORY.md`。
> ⑥ 实跑：`py_compile` OK、自检 exit 0、`./gradlew test --offline`
> **47 套件 · 430 测试 · 0 失败 0 错误 0 跳过**、dev **6/6**、
> release **FAIL(C1,C4)** 正确阻断、契约自检 PASS（含 §61.58）、
> 全库无凭据泄露。⑦ **诚实边界**：`ab_executed=false`、**F4 仍 BLOCKED**；
> 主判据 / 5 格回归 / 自动进曲 3 首 / seek / ENDED 如实 NOT RUN；
> 两个 m4a 仍是人工截取/重排的 fMP4 片段，F3 矩阵 14 格未改写；
> A3 仍 G09 PARTIAL、舱不冻结。**离线可推进的准备到此收口** ——
> 后续除设备上线执行 A/B 外，F5 / T-V USB 项均需真机或物理在场。
> `§61.38`~`§61.58` 二十一批改动均未提交、未 push，`HEAD` 仍
> `8c9d79b1`。**正式断点由 `docs/12 §61.57` 推进到 `§61.58`。**
>
> **★ 2026-11-09 第八十四批（见 `docs/12 §61.57`，用户「继续」；设备仍离线，
> 不连 adb / 不 install / 不跑任何格 / 不连 OpenList）**：不再做第五轮静态
> 反汇编，改为验证「设备一上线就跑的 `--apply` 脚本」本身。① **编译期预检
> 抓出真实缺陷**：首跑 `tools/l3_f4_ab_compile_precheck.py` 抛
> `OSError [WinError 193]` —— `l3_f4_kind_slot_ab.py::gradle()` 在 Windows
> 直接执行无扩展名 `gradlew`（bash 脚本），而 `if os.name == "nt"` 分支与
> 上一行完全相同；**不修则设备上线后 `--apply` 会在编译步崩溃、连真机判据
> 都跑不到**。实测三种 Windows 调用方式均 `rc=0` / Gradle 8.11.1 →
> 改用 `gradlew.bat`。② 另修 `finally` 里 `restored` 仅在基线重编译成功时
> 才计算的误报缺陷（改为源码逐字节一致独立判断 + `baseline_rebuild_ok`
> 字段）；`cells` 为空仍可能返回 0、`install -r` 静默失败两点**如实登记为
> 已知限制、不改**。修改后 dry-run 4 锚点全 True / §61.53 审计 14 项全 PASS /
> 契约自检 PASS。③ **预检实跑**：变体 B（1 处替换 + 1 行
> `const val NATIVE_DONE_KIND_FLUSH = 1L`，`+144` B）
> `:app:assembleDebug --offline` **BUILD SUCCESSFUL in 41s**；`finally`
> 恢复基线并重编译，源码逐字节一致、基线重编译 rc=0、`git diff` 空、
> 临时备份已删，**9 项 checks 全 PASS**。④ 交付：报告
> `analysis/l3-f4-ab-compile-precheck-report.md` + 9 项证据 JSON +
> `tools/l3_f4_ab_compile_precheck_audit.py`（**20 项全 PASS**）+
> `check_source_contracts.py §61.57` 守护；修复 `tools/l3_f4_kind_slot_ab.py`；
> 全量回写 `docs/12 §61.57`、`09 §2/§7`、`16 §5.3`、`17 §8.17`、
> 本清单、当日日志与 `MEMORY.md`。⑤ 实跑：`py_compile` OK、预检 exit 0、
> `so` sha256 与 `ENGINE-FINGERPRINT` 一致、`UrlDataRequest.kt` `git diff` 空、
> `./gradlew test --offline` **47 套件 · 430 测试 · 0 失败 0 错误 0 跳过**、
> dev **6/6**、release **FAIL(C1,C4)** 正确阻断、契约自检 PASS（含 §61.57）、
> 全库无凭据泄露。⑥ **诚实边界**：**本批是唯一临时改过生产源码的批次，
> 最终零净改动**（不写「零代码改动」）；编译通过 ≠ A/B 执行 / 环节 B 证实；
> `ab_executed=false`，主判据 / 5 格回归 / 自动进曲 3 首 / seek / ENDED
> 如实 NOT RUN；**F4 仍 BLOCKED**；A3 仍 G09 PARTIAL、舱不冻结。
> 解除条件不变：设备上线给新端点 →
> `python tools/l3_f4_kind_slot_ab.py --serial <ip:port> --apply` →
> F3 14 格复跑 + 自动进曲 3 首（NOT RUN）→ F5 → 回 P2→P3→P4。
> `§61.38`~`§61.57` 二十批改动均未提交、未 push，`HEAD` 仍 `8c9d79b1`。
> **正式断点由 `docs/12 §61.56` 推进到 `§61.57`。**
>
> **★ 2026-11-09 第八十三批（见 `docs/12 §61.56`，用户「继续」；设备仍离线，
> 纯离线 / 只读 / 零代码改动 / 零设备操作）**：把 §61.52 kind 槽假设
> **拆成两个独立环节**并攻下前一个。① **环节 A（Java→native 参数槽）已由
> 二进制证据 + §59.3 真机校准双重确认**：ARM64 JNI `x3=long j2`（Java 侧 j1
> 请求标识），`SourceDataRequest.java:72-79` 原样透传；JNI 导出
> `OnNativeDataRequestEnd @ 0x3495c` 的 `mov x23,x2` / `mov x21,x3` →
> `0x34a54 mov x0,x23` / `0x34a58 mov x1,x21` → `bl 0x36c70`；trampoline
> `0x36c70` 再 `mov x21,x1` → `mov x1,x21` → `ldr x8,[x8,#0x20]` →
> `blr x8`，**`x1` 全程未被改写或重映射**；native `OnNetSourceDataDone`
> 正是 `cmp w1,#2`。② **强交叉验证**：数据包路径
> `OnNativeDataRequestData @ 0x347f4` 同样 `(x0,x1)=(native_ptr,j2)`、
> `bl 0x36f68`，而该路径的 `x1` 已被 `§59.3 E9` 真机校准为 **kind**
> （`1L` 正常送达、kind=2 尾段丢数据 → `Send Packet Invalid data error`）
> → **`requestId==2` 即 `kind==2`**。③ **环节 B（错误完成通知 → fMP4 REMOTE
> 卡点）仍待 A/B**：只有日志时序相关性、无裁决；§59.2 exp6 反例 / 竞品
> 同样传 requestId 却可用 / ape×remote 与 aiff 仍不解释；虚表槽类型由运行时
> 决定、静态证据不是 100% C++ 类型证明。④ 交付：报告
> `analysis/l3-f4-jni-arg-report.md` + `analysis/l3-f4-jni-arg.json`
> （**17 项全 PASS**）+ `tools/l3_f4_jni_arg_audit.py` → **19 项全 PASS**
> （防把「环节 A 已证实」偷换成「整条因果链已证实」、防提前改 P0 /
> 回填指纹 / 伪造 A/B / 凭据落盘）+ `check_source_contracts.py §61.56` 守护；
> 全量回写 `docs/12 §61.56`、`09 §2/§7`、`16 §5.3`、`17 §8.16`、
> 本清单、当日日志与 `MEMORY.md`。⑤ 实跑：`py_compile` OK、JNI 复核 exit 0、
> `so` sha256 与 `ENGINE-FINGERPRINT` 一致、`UrlDataRequest.kt` `git diff` 空、
> `./gradlew test --offline` **47 套件 · 430 测试 · 0 失败 0 错误 0 跳过**、
> dev **6/6**、release **FAIL(C1,C4)** 正确阻断、契约自检 PASS（含 §61.56）、
> 全库无凭据泄露。⑥ **诚实边界**：**F4 仍 BLOCKED、A/B 尚未执行**，本批
> 不替代 A/B；环节 A 成立只使 A/B 判据更清晰（恒传 `1L` 后 fMP4 REMOTE
> 出现 `Open codec for aac` 则 B 成立，仍 `Start id=3 offset=327895` 重复
> 则 B 证伪）。零代码改动、零设备操作；不写「根因已证实」「F4 已解决」
> 「m4a 已支持」「全格式已支持」；A3 仍 G09 PARTIAL、舱不冻结。
> 解除条件不变：设备上线给新端点 →
> `python tools/l3_f4_kind_slot_ab.py --serial <ip:port> --apply` →
> F3 14 格复跑 + 自动进曲 3 首（NOT RUN）→ F5 → 回 P2→P3→P4。
> `§61.38`~`§61.56` 十九批改动均未提交、未 push，`HEAD` 仍 `8c9d79b1`。
> **正式断点由 `docs/12 §61.55` 推进到 `§61.56`。**
>
> **★ 2026-11-09 第八十二批（见 `docs/12 §61.55`，用户「继续」；设备仍离线
> `10.0.0.2:39853` not found，PC 侧 OpenList 可达；纯离线 / 只读 /
> 零代码改动 / 零设备操作）**：回答两个此前只靠推理的问题。
> ① **两个 task 分别做什么**：一手反汇编确认 `kind==2` 的
> `PreloadDoneTaskOnWriteThread`（`0xb86c`/288 B）置 `bool=true`、经 GOT
> `0x4bf28` 取 `PreloadDoneTaskOnRenderThread`（`0xadbc`/420 B），
> `bl #0x16468` **级联向渲染线程宣告「预加载完成」**（重的完成通知链）；
> `kind!=2` 的 `OnFlushNetData`（`0xc068`/88 B）只有 22 条指令、检查
> `this+0xb8` 后 `vtable+0x40` 尾调用刷数据、不投递新任务。两者严重不对称，
> 与 `§59.3 E9`「kind=2 尾段 push 数据丢失 → `Send Packet Invalid data error`」
> 互相印证。② **A/B 风险显著降低**：`1L` 是 §59.3 已真机校准、正常播放每天
> 在走的数据包 kind，故 §61.53「恒传 `1L`」是对齐到已验证正常值、
> 不是引入未验证新路径。③ **F3 样本边界**：`tools/l3_f3_openlist_scan.py`
> （只读列目录、不下音频、凭据仅 `OLU/OLP` 环境变量、绝不写入仓库）递归扫
> **80 目录 / 0 错误**，音频分布 `mp3` 242 / `flac` 66 / `ape` 54 /
> `wma` 27 / `wav` 9 / `mp4` 3，**7 种 NO_SAMPLE 命中全 0** → 缺口是数据源
> 边界、不是脚本能力，需用户提供样本或换源后复用 §8.9 脚本；盘点受
> `max_depth=3 / max_dirs=80` 约束，不改变 F3 矩阵 14 格结论。
> ④ 交付：`analysis/l3-f4-task-diff-report.md` +
> `analysis/l3-f4-task-diff.json`（**14 项全 PASS**）+
> `analysis/l3-f3-openlist-scan.json` + `tools/l3_f4_task_diff_audit.py`
> （**19 项全 PASS**，防偷换结论 / 伪造样本 / 凭据落盘 / 提前改 P0 /
> 回填指纹 / 伪造 A/B）+ `check_source_contracts.py §61.55` 守护；
> 全量回写 `docs/12 §61.55`、`09 §2/§7`、`16 §5.3`、`17 §8.15`、
> 本清单、当日日志与 `MEMORY.md`。⑤ 实跑：`py_compile` OK、两工具 exit 0、
> `so` sha256 与 `ENGINE-FINGERPRINT` 一致、`UrlDataRequest.kt` `git diff` 空、
> `./gradlew test --offline` **47 套件 · 430 测试 · 0 失败 0 错误 0 跳过**、
> dev **6/6**、release **FAIL(C1,C4)** 正确阻断、契约自检 PASS（含 §61.55）、
> 全库无凭据泄露。⑥ **诚实边界**：**已证实的是 task 行为差异，不是
> 「传 `requestId` 导致 fMP4 卡点」的因果链**（exp6 反例 / 竞品同样传
> requestId 却可用 / 不解释 ape×remote 与 aiff）；**F4 仍 BLOCKED、
> A/B 尚未执行（`ab_executed=false`）**，本批不替代 A/B；解除条件不变 ——
> 设备上线给新端点 → `python tools/l3_f4_kind_slot_ab.py --serial <ip:port>
> --apply` → F3 14 格复跑 + 自动进曲 3 首（NOT RUN）→ F5 → 回 P2→P3→P4。
> A3 仍 G09 PARTIAL、舱不冻结。`§61.38`~`§61.55` 十八批改动均未提交、
> 未 push，`HEAD` 仍 `8c9d79b1`。**正式断点由 `docs/12 §61.54` 推进到
> `§61.55`。**
>
> **★ 2026-11-09 第八十一批（见 `docs/12 §61.54`，用户「继续」；设备仍离线，
> 纯离线 / 只读 / 零生产代码改动 / 零设备操作）**：把 §61.52 kind 槽假设的
> **native 侧前提**从 `§59.2` 文档转述升级为 **so 字节级一手证据**。
> ① `tools/l3_f4_disasm_verify.py`（capstone + pyelftools，本机既有、零新增依赖）
> 反汇编 `libNMCommonCache.so` 的 `OnNetSourceDataDone @ 0xbee8`：`cmp w1,#2` →
> `b.ne 0xbfa0`；`kind==2` 取 GOT `0x4bed0`（`PreloadDoneTaskOnWriteThread @
> 0xb86c`）、其余取 `0x4bee8`（`OnFlushNetData @ 0xc068`），两条 `.rela.dyn`
> 均 `GLOB_DAT`(1025) / addend 0，两分支都 `bl #0x16468` 投递同一队列、
> `blr x8` 执行 task。发布 so **已 strip 无 `.symtab`** → 脚本修为
> `.symtab or .dynsym` 兜底（首版抛 `AttributeError`）。② **结论分层**：
> 已证实的是分支语义；「传 `requestId` → `id==2` 误判 kind → fMP4 卡点」
> 的**因果链仍是高置信假设、未真机验证**（§59.2 exp6 反例 / 竞品同样传
> requestId 却可用 / 不解释 `ape×remote` 与 `aiff`）。③ 交付：报告
> `analysis/l3-f4-disasm-verify-report.md` + `analysis/l3-f4-disasm-verify.json`
> （10 项全 PASS）+ `tools/l3_f4_disasm_verify_audit.py` → 16 项全 PASS +
> `check_source_contracts.py §61.54` 守护；全量回写 `docs/12 §61.54`、
> `09 §2/§7`、`16 §5.3`、`17 §8.14`、本清单、当日日志与 `MEMORY.md`。
> ④ 实跑验证：`py_compile` OK、`so` sha256 与 `ENGINE-FINGERPRINT` 一致、
> `UrlDataRequest.kt` `git diff` 空、`./gradlew test --offline` **47 套件 ·
> 430 测试 · 0 失败 0 错误 0 跳过**、dev **6/6**、release **FAIL(C1,C4)**
> 正确阻断、契约自检 PASS（含 §61.54）、全库无凭据泄露。⑤ **诚实边界**：
> **F4 仍 BLOCKED、A/B 尚未执行（`ab_executed=false`）**；本批只加固「为什么
> 值得做 A/B」的理由、不替代 A/B；解除条件不变 —— 设备上线给新端点后
> `python tools/l3_f4_kind_slot_ab.py --serial <ip:port> --apply`，再 F3 14 格
> 复跑 + 自动进曲 3 首（NOT RUN）→ F5 → 回 P2→P3→P4。A3 仍 G09 PARTIAL、
> 舱不冻结。`§61.38`~`§61.54` 十七批改动均未提交、未 push，`HEAD` 仍
> `8c9d79b1`。**正式断点由 `docs/12 §61.53` 推进到 `§61.54`。**
>
> **★ 2026-11-09 第八十批（见 `docs/12 §61.53`，用户「继续」；设备仍离线，
> 本批纯离线、零生产代码改动、零设备操作）**：把「设备一上线就能验证 §61.52
> 假设」的准备做完。① 交付 `tools/l3_f4_kind_slot_ab.py`：**默认 dry-run**
> 只校验源码锚点唯一性 + 打印 8 步计划（本批已实跑，4 项锚点全 True，计划落
> `analysis/l3-f4-kind-slot-ab-plan.json`）；**`--apply` 才执行** —— 备份
> `UrlDataRequest.kt` 到系统临时目录 → 精确 1 处替换
> `OnDataRequestEnd(requestId, …)` → `OnDataRequestEnd(NATIVE_DONE_KIND_FLUSH, …)`
> 并加常量 `= 1L`（requestId 仅留日志）→ `./gradlew :app:assembleDebug --offline`
> → `adb install -r` → 主判据 `f3_m4a_fast.m4a × REMOTE`（25s）+ 回归 5 格
> （mp3 local/remote、wma/flac/wav remote）→ **`try/finally` 无条件恢复基线、
> 重编译、重装、校验源码一致**，并恢复 `wifiOnly` / `adb reverse`。
> ② 判据：`Open codec for aac` + `Write start play nErr:0`；证伪条件「仍
> `Start id=3 offset=327895` 重复且无 codec」；回归 5 格全 PASS；
> **自动进曲 3 首 / seek / ENDED 未实现，如实 `NOT RUN`**。零新增依赖 /
> 权限 / 模块，不连 OpenList、不写凭据、不改设备系统设置，真机命令全部带 `-s`。
> ③ **设备上线后一条命令**：
> `python tools/l3_f4_kind_slot_ab.py --serial <ip:port> --apply`。
> ④ 守护与证据：`tools/l3_f4_kind_slot_ab_audit.py` →
> `analysis/l3-f4-kind-slot-ab-audit.json`（**14 项全 PASS**：防提前改 P0、
> 防伪造 A/B 结果、防把未跑连播写成 PASS、防基线漂移）、
> `check_source_contracts.py §61.53`；全量回写。**未新增 `docs/` 文件**
> → `09 §3` 计数仍 **31 个**。验证（纯离线实跑）：`py_compile` OK、
> dry-run exit 0、六批审计 **14/15/14/13/17/10 全 PASS**、`--offline`
> **47 套件 · 430 测试 · 0 失败 0 错误 0 跳过**、dev **6/6**、release
> **FAIL(C1,C4)** 正确阻断、契约自检 PASS（含 §61.53）、`UrlDataRequest.kt`
> `git diff` 空、全库无凭据泄露。⑤ **诚实边界**：A/B **尚未执行**
> （`ab_executed=false`），§61.52 的 kind 槽结论仍是**高置信假设、未真机验证**；
> 本批零生产代码改动、零设备操作；不写「F4 已解决」「m4a 已支持」；
> F3 矩阵 14 格未改写；A3 仍 G09 PARTIAL、舱不冻结。**F4 仍 BLOCKED**。
> **下一步：设备上线执行 `--apply` → F5 → 回 P2→P3→P4。**
> `§61.38`~`§61.53` **十六批**改动均**未提交、未 push**，`HEAD` 仍
> `8c9d79b1`。**正式断点由 `docs/12 §61.52` 推进到 `§61.53`。**
>
> **★ 2026-11-09 第七十九批（见 `docs/12 §61.52`，用户「继续」；设备已离线，
> 本批纯离线）**：**把 F4 卡点根因方向定位到 `OnDataRequestEnd` 的 kind 槽**
> （**高置信假设，待真机 A/B；不是已证实根因，F4 仍 BLOCKED**）。
> ① `UrlDataRequest.sink.onEnd()` 把 `requestId` 传进 `OnDataRequestEnd` 首参，
> 而该首参在 native 是 `OnNetSourceDataDone(int kind, String)` 的 kind 槽；
> `docs/12 §59.2` 反汇编钉死 `kind==2 → PreloadDoneTaskOnWriteThread`、
> 其余 → `OnFlushNetData`；§59.3 已校准数据包 kind 为 1L，**End 从未校准**。
> fMP4 需多个 Range 才能 probe → `onEnd id=2` 必现 → 被当完成标记 → 读侧
> 数据不足 → 立即重发同区间，永远走不到 `Open codec`。
> ② 证据：基线 `onEnd id=2 → Start id=3 offset=327895` **1ms**；变体 A
> （去掉 2s）仍 **2ms**（与等待时长无关）；连接失败路径 `onEnd id=2→3→4→5`
> / `Start id=3→4→5→6` 每轮约 2ms 连锁；F3 全部 REMOTE 日志中
> **flac/wav 也有 `Start=[1,2]` 却 PASS**（`onEnd` 仅 `[1]`，第 2 请求被
> `Stop()` 取消），只有 fMP4 `onEnd` 含 `2` 且 FAIL → **多 Range 不是问题，
> `onEnd id≥2` 才是分界**。
> ③ **可证伪预测**：首参恒传 `NATIVE_DONE_KIND_FLUSH = 1L`（requestId 仅留日志）
> 后 `f3_m4a_fast.m4a × REMOTE` 应出现 `Open codec for aac`；否则假设证伪。
> 判据：codec → `Write start play nErr:0` → F3 14 格全量复跑零回归 →
> 自动进曲 3 首无 `-103`、无释放后回调；**2s 等待保留**。
> ④ **适用边界**：不解释 `ape×REMOTE`（`onEnd=[1]`，§61.49 截断归因仍有效）、
> 不解释 `aiff`（`openSource 2`）；§59.2 exp6 两条路径读侧均正常收尾是
> **已知反例**；竞品同样传 requestId 却可用，原因未实证。
> ⑤ **解除条件改变：不再需要用户提供样本，只需设备重新上线做一次 kind 槽
> A/B**；但假设未验证、代码未改 → **F4 仍 BLOCKED**。
> ⑥ 交付：`analysis/l3-f4-kind-slot-rootcause.md`、
> `tools/l3_f4_kind_slot_audit.py` → `analysis/l3-f4-kind-slot-audit.json`
> （**15 项全 PASS**）、`check_source_contracts.py §61.52` 守护；全量回写。
> **未新增 `docs/` 文件** → `09 §3` 计数仍 **31 个**。验证（纯离线实跑）：
> `py_compile` OK、五批审计 **15/14/13/17/10 全 PASS**、`--offline`
> **47 套件 · 430 测试 · 0 失败 0 错误 0 跳过**、dev **6/6**、release
> **FAIL(C1,C4)** 正确阻断、契约自检 PASS（含 §61.52）、`UrlDataRequest.kt`
> `git diff` 空、全库无凭据泄露。⑦ **诚实边界**：零代码改动、零设备操作；
> 不写「F4 已解决」「m4a 已支持」；未测 seek / ENDED / 自动进曲 3 首；
> F3 矩阵 14 格未改写；A3 仍 G09 PARTIAL、舱不冻结。
> **下一步：设备重新上线做 kind 槽 A/B → F5 → 回 P2→P3→P4。**
> `§61.38`~`§61.52` **十五批**改动均**未提交、未 push**，`HEAD` 仍
> `8c9d79b1`。**正式断点由 `docs/12 §61.51` 推进到 `§61.52`。**
>
> **★ 2026-11-09 第七十八批（见 `docs/12 §61.51`，用户「赶紧把需要手机验证的
> 部分做完 等下就要离线了」）**：**真机取证全部赶在设备离线前完成**，之后纯
> 离线收尾。① 补齐 §61.50 缺的复现载体：设备已有 fMP4 前 16MiB 经
> `adb exec-out dd` 取回，纯 Python 构造同内容 / 同字节数 3,918,787 B /
> 仅 `moov` 位置不同的 `f3_m4a_fast.m4a`（faststart）与 `f3_m4a.m4a`
> （moov-end），无编码器 / 无外网。② **真机**：`m4a_fast×LOCAL` =
> `extension=m4a` + `Open codec for aac` + `openSource 0` +
> `Write start play nErr:0` → **PASS**；`m4a_fast×REMOTE`（22s/25s）无 codec、
> `Start id=3 offset=327895` 同区间重复、`onEnd delayed 2s`×2、无 `-103`
> → **FAIL**（25s 仍不成立，非等待不足）。③ **A/B 证伪**：临时去掉
> `UrlDataRequest.sink.onEnd()` 的 `repeat(20) { sleep(100) }`（变体 A 立即
> 回传），编译 1m 5s → `install -r` → 同一样本 25s，**结果与基线完全相同**
> → §61.50 候选①「立即 onEnd / 条件化节流」被证伪，卡点不是 2s 造成，
> 另有机理（fMP4 多 Range × normal mode vfile）。**未合入变体**：基线副本
> 回退（`git diff` 空）、重编译 11s、重装 Success；回退后 5 格复验
> `mp3×local/remote`、`wma×remote` 全 PASS（零回归），并补跑 m4a 双路径最终取证。
> ④ 设备前置 `wifiOnly=false` / `adb reverse` 由脚本 `finally` 在离线前恢复，
> 设备随后按时离线。⑤ 交付：`analysis/l3-f4-ab-report.md`、
> `l3-f4-{faststart-probe,ab-a,baseline-recheck,m4a-final}.json`、
> `analysis/l3-f4-logs/` 原始 logcat、`tools/l3_f4_ab_audit.py` →
> `analysis/l3-f4-ab-audit.json`（**14 项全 PASS**）、
> `check_source_contracts.py §61.51` 守护；全量回写。**未新增 `docs/` 文件**
> → `09 §3` 计数仍 **31 个**。验证（实跑）：`py_compile` OK、四批审计
> 14/13/17/10 全 PASS、`--offline` **47 套件 · 430 测试 · 0 失败 0 错误
> 0 跳过**、dev **6/6**、release **FAIL(C1,C4)** 正确阻断、契约自检 PASS
> （含 §61.51）；A3 仍 G09 PARTIAL、舱不冻结。⑥ **诚实边界**：两个 m4a
> 是人工截取/重排的 fMP4 片段（含视频轨），不是完整真实 m4a；LOCAL 解出
> `aac` ≠「m4a 已支持」，REMOTE FAIL 不判播放器缺陷；**F3 矩阵 14 格未被
> 改写**；P0 最终零改动、未测 seek / ENDED / 自动进曲 3 首。**F4 仍
> BLOCKED**（载体已补、改 2s 被证伪）。**下一步：需 native 级定位或用户
> 提供非 fMP4 的 `faststart=false` 真实 `.m4a` → F5 → 回 P2→P3→P4。**
> `§61.38`~`§61.51` **十四批**改动均**未提交、未 push**，`HEAD` 仍
> `8c9d79b1`。**正式断点由 `docs/12 §61.50` 推进到 `§61.51`。**
>
> **★ 2026-11-09 第七十七批（见 `docs/12 §61.50`，用户「继续」→ F4）**：
> **F4 判 BLOCKED，P0 引擎舱代码零改动、不伪造完成**。① **根因钉死**：
> `UrlDataRequest.sink.onEnd()` 成功 EOF 固定等 2s（20×100ms + `cancelled`）；
> 立即 onEnd 会让 `push→ReadFromFileNoLock 0→重试` 以 ~5ms/轮烧完预算 →
> `-103`，E16 的 30s 延迟又造成自动进曲 17s 悬空回调。原因是 normal mode
> 下 `OnWriteNetData` GOT+lambda 异步任务落 vfile ~50ms，而 JNI 只有
> `Begin/Data/End/Start/Stop`、**无落盘完成回调**；P0 native so 原样复用
> 不可改 → **严格「零等待事件驱动根治」在当前基线下不可达**。② **两处
> 口径澄清**：`§61.26` G01 用 **faststart m4a（moov 前置）** 绕开竞态、
> G06 尾部 Range 是 200 投毒（已修）→ 现行 G01/G05/G07 PASS 不能证明
> 2s 可去掉；`§61.49` 称 F3 `ape×remote` 与 F4「同源」收窄为**只是同走
> `onEnd delayed 2s` 路径**，其根因是 4 MiB 截断片段缺 APE 尾部 seek table
> （无 `-103` / `Read 0` 重试 / codec），**不是复现载体**。③ **真机探针**：
> OpenList 全挂载只有 1 个 82MB mp4（无 m4a/aac、外部直连超时）；本机无
> 编码器、外网四源不通；设备 mp4 前 16MiB 为 **fMP4**，纯 Python 把顶层
> `moov` 挪到文件末尾构造 `f3_m4a.m4a`（3,918,787 B，未纳入 F3 清单）。
> `tools/l3_f4_eof_probe.py` 双路径两格均 `openSource=2 / codec=None /
> tailStarts=0 / delayed=False` → **尾部 seek 未触发、`f4_repro_carrier=false`**，
> 不能据此判播放器不支持 m4a。④ **候选方案（待 A/B 的假设）**：条件化
> 重试节流（推荐）、`OnDataRequestEnd` kind=2、自适应等待；三者须先有
> 稳定复现再 A/B，本批一个都不施工。⑤ **解除条件**：用户提供 1 个
> **faststart=false 的真实 `.m4a`/`.mp4` 样本**（几 MB）／恢复外网或给
> ffmpeg／明确接受「只做自适应改良不追求根治」。⑥ 交付：
> `tools/l3_f4_eof_probe.py` + `analysis/l3-f4-eof-probe.json` +
> `analysis/l3-f4-logs/`、施工单 `tl-l3-f4-eof-plan-2026-11-09.md`、
> `tools/l3_f4_eof_audit.py` → `analysis/l3-f4-eof-audit.json`（13 项全 PASS）
> + `check_source_contracts.py §61.50` 守护；全量回写。**未新增
> `docs/` 文件** → `09 §3` 计数仍 **31 个**。验证（实跑）：`py_compile` OK、
> F4 探针/审计 exit 0、F3 审计 17/17 与 §61.48 审计 10/10 未回潮、
> `--offline` **47 套件 · 430 测试 · 0 失败**（本批 Kotlin 零改动）、
> dev **6/6**、release **FAIL(C1,C4)** 正确阻断、契约自检 PASS（含
> §61.50）、探针前置与 `adb reverse` 均已恢复；A3 仍 G09 PARTIAL、
> 舱不冻结。**下一步：F4（BLOCKED，待样本）→ F5 → 回 P2→P3→P4；
> F3 的 7 种 NO_SAMPLE 格式同样待样本复跑。** `§61.38`~`§61.50`
> **十三批**改动均未提交、未 push，`HEAD` 仍 `8c9d79b1`。
> **正式断点由 `docs/12 §61.49` 推进到 `§61.50`。**
>
> **★ 2026-11-09 第七十六批（见 `docs/12 §61.49`，用户「继续」→ F3，纠偏
> 「为什么要编码器？从 openlist strm 驱动里拿」+ OpenList 凭据）**：
> ① **纠偏**：本机无音频编码器 / 外网四源超时曾判硬阻塞；用户指出样本本在
> OpenList —— `/strm_test_probe` 92 个 `.strm` → `/ecloud_self/我的音乐/`
> 真实直链（实测 `ape18/mp358/flac22/wma9/wav3`，Range 206），**不需要编码器**；
> 凭据只在进程内用、未落盘。② **样本 7 种**：mp3/wma 完整、flac/wav/ape
> 前 4 MiB 片段（WAV 修 RIFF/data 长度）、竞品 ogg、标准库合成 aiff；
> **NO_SAMPLE 由 9 收窄为 7**（m4a/aac/opus/wv/tak/dsf/dff）。③ **通道**：
> LOCAL 沿用 §61.47 并修正 `adb shell` stdin 直传只写 248 B 的坑；**新增
> REMOTE 调试入口** `EXTRA_SOURCE_URL` + `PlaybackRuntime.playUrl()`（`SourceRef.url`
> → `Link(url=…)` → `EngineSource(url=…)`），`http.server 8765` + `adb reverse`；
> 零新增 action / 权限 / 模块 / 依赖。设备默认网络 `MOBILE[NR]` + `wifiOnly=true`
> 会拒 URL 源 → 临时写 app 私有 `cloudmusic_settings.pb` 置 false，
> **跑完删除恢复默认**。④ **结果**：14 格 = **11 PASS / 3 FAIL**；
> mp3/wma/flac/wav/ogg 双路径全 PASS、ape LOCAL PASS；**14/14 格 extension
> 与真实格式一致**（F2-b 双路径复核、无 MIME 回潮）。FAIL 归因：`ape×remote`
> 截断片段缺尾部 seek table（同片段 LOCAL PASS、与 F4 EOF 2s 掩盖同源）、
> `aiff` 两格自造容器未被 demuxer 打开（需真实样本复核），均不判播放器缺陷。
> ⑤ 交付：`tools/l3_f3_fixture_gen.py` / `l3_f3_matrix_run.py` /
> `l3_f3_matrix_audit.py` → `analysis/l3-f3-audit.json`（17 项全 PASS）、
> `l3-f3-fixtures.json` / `l3-f3-matrix.json` / 14 份 logcat /
> `l3-f3-matrix-report.md` / 施工单 §0；`check_source_contracts.py §61.49`
> 守护；全量回写。**未新增 `docs/` 文件** → `09 §3` 计数仍 **31 个**。
> 验证（实跑）：`py_compile` OK、三工具 exit 0、dev **6/6**、release
> **FAIL(C1,C4)** 正确阻断、`--offline` **BUILD SUCCESSFUL in 1m 24s** /
> **47 套件 · 430 测试 · 0 失败 0 错误 0 跳过**、`install -r` Success、
> 设备前置已恢复、契约自检 PASS（含 §61.49 新守护）；A3 仍 G09 PARTIAL、
> 舱不冻结。⑥ **诚实边界**：F3 判 **PARTIAL** —— 7 种无样本、截断片段不验证
> 完整时长 / seek / ENDED、`mDuration_raw` 不换算、调试入口随 `12 §61.33`
> 收口，**不得写「全格式已支持」**。**下一步（当前主断点）：F4 根治 m4a
> EOF 2s → 补样本复跑 → F5 → 回 P2→P3→P4。** `§61.38`~`§61.49`
> **十二批**改动均**未提交、未 push**，`HEAD` 仍 `8c9d79b1`。
> **正式断点由 `docs/12 §61.48` 推进到 `§61.49`。**
>
> **★ 2026-11-09 第七十五批（见 `docs/12 §61.48`，用户「历史证据也支持修正…
> 开始施工」+ 新端口 `39853`）**：**F2-b 代码修正 + F2-c 真机复验均已完成**。
> ① **依据**：`review-and-diagnosis §4.2` 做过 `extension=mp3` 校准实验、
> `a3-phase-ab-evidence §E4` 留有 `resourceInfo extension=mp3 fileSize=37824`
> 成功真机日志 → 传真实扩展名不是新猜测；`:engine-legacy` 本就
> `implementation(project(":core-model"))` → 零新增依赖。② **改动**：
> `AudioFormats` 增 `EXT_BY_MIME`（由 `MIME_BY_EXT` 逆查派生）与
> `extOfSource(url, localPath, mimeHint)`（URL 后缀 → LOCAL 真实后缀 →
> MIME 逆查 → 空串，1..5 位字母数字、不回传 MIME、不编造）；
> `EngineAdapter.kt:405-416` 删旧 `?: source.mimeHint.orEmpty()` Elvis 链并
> 改调单一真相源；`AudioFormatsTest` 增 4 个 F2-b 用例（7 → 11）。
> P0 native 舱 / 指纹 / `openLocalFd=-1` 零改动，零新增 Gradle 依赖。
> ③ **F2-c 真机 PASS**：`37827` offline → `adb connect 10.0.0.2:39853`
> device；`am startservice` 被 `app is in background` 拒 → 按 §61.47 先拉
> `MainActivity` 再注入，成功。2370 行原始日志 / 12 行关键证据显示
> `resourceInfo extension=mp3 fileSize=-1`（修前 `audio/mpeg`）→
> `XorLocalSource::Open fd=152 size=37824` → `BufferSource open ret=0` →
> `Open codec for mp3` / `mDuration 4704` / `Play status 4` /
> `Write start play nErr:0`，无 onError / 崩溃 / ANR → **修正生效、mp3
> 零回归**。④ 交付：`tools/l3_f2_ext_fix_audit.py` →
> `analysis/l3-f2-ext-fix.json`（10 项 checks 全 PASS）、
> `analysis/l3-f2c-extension-verify-logcat.txt` + `-key-logcat.txt` +
> 通知栏截图、`check_source_contracts.py §61.48` 守护；
> `l3_f2_extension_chain_audit.py` 加 F2-b 后拒绝重跑保护（返回 2，不覆盖
> §61.46 历史快照），§61.46 / §61.47 守护保留为当时快照。**未新增
> `docs/` 文件** → `09 §3` 计数仍 **31 个**；19 模块 / 11 权限 / 20 so /
> 51 素材 / 61 行 csv 全 false / 零新增依赖 / `res/` 未建 / `:ui-legacy`
> 未 include 零变化。验证（实跑）：`py_compile` OK、新审计 exit 0、
> dev **6/6**、release **FAIL(C1,C4)** 正确阻断、`--offline`
> **BUILD SUCCESSFUL in 1m 2s** / **47 套件 · 430 测试 · 0 失败 0 错误
> 0 跳过**、`install -r` Success、契约自检 PASS（含 §61.48 新守护）；
> A3 仍 G09 PARTIAL、舱不冻结。⑤ **诚实边界**：F2-c 只复验 LOCAL，
> REMOTE 本批未重跑真机（4 个 JVM 用例 + A3 E4 历史日志覆盖）；截图是
> 下拉通知栏（MIUI 拒 `input` 无法收起），只作 FGS 旁证、不冒充播放页；
> `metadata: null` / `durationMs=0` 缺口未修；**mp3 可播 ≠ 全格式可播**。
> **下一步（当前主断点）：F3 逐格式 × REMOTE/LOCAL 双路径真机矩阵 →
> F4 → F5，完成后回 P2→P3→P4。** `§61.38`~`§61.48` **十一批**改动均
> **未提交、未 push**，`HEAD` 仍 `8c9d79b1`。**正式断点由 `docs/12
> §61.47` 推进到 `§61.48`。**
>
> **★ 2026-11-09 第七十四批（见 `docs/12 §61.47`，用户「自研APP没有音乐，
> 你自己解决」）**：**不再把「用户手动播一次 LOCAL 源」当阻塞**，由助手自行
> 解决曲库与播放链并完成 F2 真机取证。① **根因**：设备有
> `/sdcard/Music/cloudmusic_g01.mp3`（37,824 B）但 `READ_MEDIA_AUDIO
> granted=false` / appops `ignore`，MIUI 无线 adb 拒 `pm grant` /
> `input tap`，`DataStore.storages` 空、`DriverRegistry` 无 local driver →
> 曲库恒 0 首（历史 A3 靠 `run-as` 灌 Room 夹具 + URL 回环，app 从未有真实
> 本地曲库）。② **零权限注入通道（验收期调试入口）**：复用 `ACTION_PLAY` +
> 可选 `EXTRA_LOCAL_PATH`（`PlaybackService.kt`，无 extra 时 `runtime.play()`
> 零回归，Manifest 仍 1 个自定义 action / 11 权限）；`PlaybackRuntime.playLocal()`
> 用 `java.io.File` 的 `FileDirectorySource` 注册 `LocalDriver`，
> `TrackRepository.upsertBatch` + `QueueRepository.setAll` 让曲库 / 队列真实
> 有曲目，走 `setQueue/playAt` 生产链 → `EngineSource(url=null, localPath=…)`；
> 夹具经 `run-as` 写入 app 私有 `files/music/fixture.mp3`，无需任何权限。
> 零新增依赖 / 模块，P0 引擎舱零改动。③ **F2 真机取证（14:50:07，pid 15572）**：
> 44 行原始日志 `analysis/l3-f2-device-probe-logcat.txt`，关键
> `resourceInfo extension=audio/mpeg fileSize=-1` → **§61.46「待验证假设」
> 被证实**（LOCAL 把 MIME 当扩展名）；`prepare ... remote=false url=null` →
> `getLocalPath path=...fixture.mp3` →
> `CreateLocalSource: XorLocalSource::Open success fd=5 size=37824` →
> `BufferSource open ret=0` → **LOCAL 取流能力真实可用，`openLocalFd=-1`
> 不阻塞**；`Open codec for mp3` / `mDuration 4704` / `Play status 4` /
> `Write start play nErr:0` → **mp3 LOCAL 可播，错配未造成不可播（诚实挂账）**。
> `analysis/l3-reference/self-ui-with-local-track-2.png` 播放页显示 `fixture` /
> `本地音源` / `1 首` → **「自研 APP 没有音乐」已解决**；Room `cloudmusic.db`
> 151,552 B + `-wal` 412,032 B；`dumpsys media_session` `active=true` /
> `state=2`（夹具播完由 §61.25 回退）。④ 交付：
> `analysis/l3-f2-device-probe.md` 报告 + 日志 + 2 张截图、
> `tools/l3_f2_zero_permission_audit.py` → `analysis/l3-f2-zero-permission.json`
> （8 项 checks 全 PASS / 11 条 log 断言 / 19 模块 / 11 权限 / P0 未改）、
> `check_source_contracts.py §61.47` 守护；全量回写。**未新增 `docs/` 文件**
> → `09 §3` 计数仍 **31 个**；19 模块 / 11 权限 / 20 so / 51 素材 / 61 行
> csv 全 false / 零新增依赖 / `res/` 未建 / `:ui-legacy` 未 include 零变化。
> 验证（实跑）：`py_compile` OK、审计工具 exit 0、dev **6/6**、release
> **FAIL(C1,C4)** 正确阻断、`--offline` **BUILD SUCCESSFUL in 6s** /
> **47 套件 · 426 测试 · 0 失败 0 错误 0 跳过**、`adb install -r` Success、
> 契约自检 PASS（含 §61.47 新守护）；A3 仍 G09 PARTIAL、舱不冻结。
> ⑤ **诚实边界**：**F2-b 未做**（只取证未改 P0 代码；判据是「是否真的影响
> 非 mp3 格式」，避免给已可播的 mp3 引入回归）；`EXTRA_LOCAL_PATH` +
> `playLocal()` 是验收期调试入口、随 `12 §61.33` 收口；`durationMs=0` 进度
> `00:00/00:00`、MediaSession `metadata: null` 未设 → 通知栏不显示曲名，挂账。
> **下一步（当前主断点）：F2-b 修正 `EngineAdapter.ResourceInfo.extension`
> 推断链 → F2-c 复验 → F3 逐格式 × REMOTE/LOCAL 双路径矩阵（本批已提供可复用
> 零权限 LOCAL 注入通道）→ F4 → F5**，完成后回 P2→P3→P4。
> `§61.38`~`§61.47` **十批**改动均**未提交、未 push**，`HEAD` 仍 `8c9d79b1`。
> **正式断点由 `docs/12 §61.46` 推进到 `§61.47`。**
>
> **★ 2026-11-09 第七十三批（见 `docs/12 §61.46`，用户「继续」+ adb 端口
> 37827 + 方向锁定问答）**：① **端点更新**：`10.0.0.2:37827` 实跑
> `connected` / `device`，旧 `38061` offline；`23013RK75C` / Android 13；
> 竞品 `com.netease.cloudmusic` 与自研 `com.example.cloudmusic` 两包均在。
> **本批起真机操作改用 `37827`**。② **方向锁定诊断（只读，未改设备设置）**：
> `accelerometer_rotation=0` / `user_rotation=0` /
> `mUserRotationMode=USER_ROTATION_LOCKED` / `mCurrentRotation=ROTATION_0`
> → adb 协议本身不写方向设置，锁定此刻生效；「失效」来自前台 Activity 的
> `screenOrientation` 覆盖 + MIUI 快捷开关语义（`dumpsys` 实测 PORTRAIT /
> UNSPECIFIED / BEHIND 并存）、adb 会话伴随的 `am start` / 投屏工具写
> `accelerometer_rotation 1` / MIUI 无线调试重置；竞品 classes3|6|10|19 四个
> dex 含 `accelerometer_rotation` + `setRequestedOrientation: 高比例设备，
> 锁定竖屏` 中文日志 → 竞品主动管理方向。自查：adb 前后各读一次
> `settings get system accelerometer_rotation`（0=锁定 / 1=自动旋转），
> 用 `settings put ... 0` + `user_rotation 0` 恢复。③ **F2 前置实证（未改 P0
> 代码）**：`EngineAdapter.kt:405-417` 的 extension 链在 LOCAL 源落到
> `source.mimeHint`（MIME 当扩展名），REMOTE 无后缀 / 点后 >5 字符同样落
> mimeHint；`createDataRequestHandler()` 仅 URL、`getLocalPath()` 真路径但
> `openLocalFd()=-1`；历史 logcat **622 行命中 0** → 「待验证假设」既未证实
> 也未证伪，**不能据此改 P0 引擎舱**；MIUI 拒绝 `input tap` 无法自动切
> LOCAL。新增 `tools/l3_f2_extension_chain_audit.py` →
> `analysis/l3-f2-extension-chain.json`（4 分支 / LOCAL 落 mimeHint /
> `f2_code_unchanged=True` / 3 缺口 / 3 todo）、`check_source_contracts.py
> §61.46` 守护；全量回写。**未新增 `docs/` 文件** → `09 §3` 计数仍 **31 个**；
> 19 模块 / 11 权限 / 20 so / 51 素材 / 61 行 csv 全 false / 零新增依赖 /
> `res/` 未建 / `:ui-legacy` 未 include 零变化。验证（实跑）：`py_compile` OK、
> F2 工具 exit 0、dev **6/6**、release **FAIL(C1,C4)** 正确阻断、`--offline`
> **BUILD SUCCESSFUL in 6s** / **47 套件 · 426 测试 · 0 失败 0 错误 0 跳过**、
> 契约自检 PASS（含 §61.46 新守护）；A3 仍 G09 PARTIAL、舱不冻结。
> **下一步（当前主断点）：F2 真机取证 —— 需用户手动在自研 app 播放一次
> LOCAL 源，本助手 `adb logcat` 抓 `resourceInfo extension=...` 与
> `Open codec` 对照**，随后 F2-b/c 修正 → F3 → F4 → F5，完成后回 P2→P3→P4。
> `§61.38`~`§61.46` **九批**改动均**未提交、未 push**，`HEAD` 仍 `8c9d79b1`。
> **正式断点由 `docs/12 §61.45` 推进到 `§61.46`。**
>
> **★ 2026-11-09 第七十二批（见 `docs/12 §61.45`，用户「继续」→ 按第 ③ 条
> 「按清单 todo 顺序开发」开工 F1）**：**F1 单一格式真相源 `AudioFormats` 已完成**。
> 落点 `:core-model`（纯 Kotlin + JDK），6 处生产白名单（`ObjectType.AUDIO_EXT` /
> `ScanRules.audioExt` / `StrmPaths.defaultFilterTypes` / `MimeHint.MAP` /
> `PlayerController.mimeHintOf` / `DidlLiteBuilder.mimeOf`）全部接线，经既有
> `:datasource-api` 的 `api(project(":core-model"))` 传递依赖访问 → **零新增 Gradle
> 依赖 / 零新增模块 / 零改 DI 图**。新增 `AudioFormats.kt`（14 项 `AUDIO_EXT` +
> `alac` + 11 项 `MIME_BY_EXT` + 15 项 `STREAM_FILTER_EXT` 运行时派生）、
> `AudioFormatsTest.kt`（7 个纯 JVM 用例）、`tools/l3_f1_unified_audit.py` →
> `analysis/l3-f1-unified.json`（6 处全接线、残留硬编码 0）、
> `check_source_contracts.py §61.45` 守护；`l3_audio_format_audit.py` 加 **F1 后
> 拒绝重跑**保护（返回 `2`，§61.44 历史证据不覆盖）。行为变更 5 条如实登记
> （扫描 8→14 / strm 9→15 / MIME 统一到 `03-06 §7` / 6 种新格式不编造 MIME /
> P0 引擎舱零变化）。**诚实边界**：统一清单来源 ≠ native 已支持全格式，F1 未
> 跑真机矩阵（F3）、未触碰设备；`alac` 是否并入收录集留待 F3。**零发布基线变更**
> 且**未新增 `docs/` 文件** → `09 §3` 计数仍 **31 个**。验证（实跑）：
> `py_compile` OK、dev **6/6**、release **FAIL(C1,C4)** 正确阻断、`--offline`
> **BUILD SUCCESSFUL in 7s** / **47 套件 · 426 测试 · 0 失败 0 错误 0 跳过**
> （`AudioFormatsTest` 7 用例真实执行）、契约自检 PASS（含 §61.45 新守护）；
> A3 仍 G09 PARTIAL、舱不冻结。**下一步：按 todo 顺序开工 F2**（修
> `EngineAdapter.ResourceInfo.extension` 推断链），随后 F3 → F4 → F5，完成后回
> P2→P3→P4。`§61.38`~`§61.45` **八批**改动均**未提交、未 push**，
> `HEAD` 仍 `8c9d79b1`。**正式断点由 `docs/12 §61.44` 推进到 `§61.45`。**
>
> **★ 2026-11-09 第七十一批（见 `docs/12 §61.44`）**：用户三条指令 ——
> ① 「P0 现状是 native so 原样复用 + Java/JNI 层 jadx 重建源码 + slot，**没问题**」
> → **诚实边界拍板接受**，不追求整包 dex 复用，`docs/17 §1.2` 待拍板项移除、
> `MEMORY.md` 剩余断点 ⑥ 删除；② 「**目前首要目标是调通全格式播放内核**」→
> `docs/17 §4` 新增 **P1★ 首要**，**P2 播放页 1:1 顺延**（非取消），新增 `§8`；
> ③ 「**最后是按照清单 todo 顺序开发**」→ **F1→F5**（单一真相源 `AudioFormats`
> 统一 6 处白名单 → 修 `ResourceInfo.extension` 推断链 → 逐格式 × REMOTE/LOCAL
> 双路径真机矩阵 → 根治 m4a EOF 2s 掩盖 → A3 全绿后 ABI 回填/冻结舱）。
> 新增只读证据 `tools/l3_audio_format_audit.py` → `analysis/l3-audio-format-audit.json`
> + 施工单 `analysis/tl-l3-audio-format-plan-2026-11-09.md`：6 处白名单
> （14/8/9/9/7/7）不一致；`libffmpeg.so` 75 demuxer / 100 decoder（69 音频）/
> 3 专用解码库；A3 G01 仅 4 格式 mp3/flac/m4a/ogg 且全为 URL 回环夹具、
> **LOCAL 源未验证**；`EngineAdapter` LOCAL 源把 MIME 字符串当扩展名属语义错配。
> 新增 `check_source_contracts.py §61.44` 守护（首次 FAIL 由注释 `**` 标记致
> `adapter_self_declared_status` 误判，修工具双子串匹配后转 PASS）。**零发布基线
> 变更 / 零源码改动 / 零设备改动**，未新增 `docs/` 文件 → `09 §3` 计数仍 **31 个**。
> 验证：契约自检 PASS（含新守护）、`py_compile` OK、dev 6/6、release FAIL(C1,C4)、
> `--offline` **BUILD SUCCESSFUL in 8s** / **419 测试 / 46 套件 / 0 失败**；
> A3 仍 G09 PARTIAL、舱不冻结。**下一步：按 F1→F5 顺序开工全格式内核**（F1
> `AudioFormats`），完成后回到 P2→P3→P4；`§61.41` 三项授权保留暂缓。
> `§61.38`~`§61.44` **七批**改动均**未提交、未 push**，`HEAD` 仍 `8c9d79b1`。
> **正式断点由 `docs/12 §61.43` 推进到 `§61.44`。**
>
> **★ 2026-11-09 第七十批（见 `docs/12 §61.43`）**：用户叫停「零碎修补」，要求
> **有规划有目标的复刻**：① 核心播放二进制钉死 **P0 不可变基线**；② 先出竞品模块
> 清单；③ 更新开发文档 + 优先级；④ 复核代码 ↔ 文档差异。新增
> `docs/17-竞品模块清单与复刻规划.md`（`09 §3` 计数 **30 → 31**）+
> `tools/l3_module_inventory.py` → `analysis/l3-module-inventory.json`
> （799 activity / 182 alias / 93 service / 38 receiver / 23 provider / 84 权限 /
> 120 语义类名 → 17 功能域 / 50 中文模块名 / 63 包分组）。判定 **8 个保留模块**、在线
> 生态整块剔除；`docs/17 §3` 8 条冲突（C6 文档口径最重）/ `§4` P0~P5 优先级 /
> `§5` 13 条差异。**动态背景冻结到 P2**；用户已手动切竞品播放页取得
> `competitor-player-baseline.png`（1440×3200）、`l3-baseline-probe.json` 证实
> `#ff262626` 只是兜底 → **`§61.42`「等截图」硬阻塞解除**。新增
> `check_source_contracts.py §61.43` 守护。零发布基线变更（19 模块 / 11 权限 /
> 20 so / 51 素材 / 61 行 csv 全 false / 零新增依赖 / `res/` 未建 / `:ui-legacy`
> 未 include）。验证：契约自检 PASS（含新守护）、`py_compile` OK、dev 6/6、
> release FAIL(C1,C4)、`--offline` **BUILD SUCCESSFUL in 11s** / **419 测试 /
> 46 套件 / 0 失败**；A3 仍 G09 PARTIAL。**下一步：按 `docs/17 §4` P2 逐像素对齐
> 播放页（动态背景 / 圆形封面 / 唱臂 / 进度条 / 控制图标，`pixel_level_1to1` 达成前
> 仍 False）、P3 八个模块出子清单、`§5` 修订旧口径**；`§61.41` 三项授权暂缓。
> `§61.38`~`§61.43` **六批**改动均**未提交、未 push**，`HEAD` 仍 `8c9d79b1`。
> **正式断点由 `docs/12 §61.42` 推进到 `§61.43`。**

> **★ 2026-11-09 第六十九批（见 `docs/12 §61.42`）**：用户把口径升级为「**1:1 像素级
> 复刻**」，要求「代码自研 + 素材一律复用竞品 + 底部菜单栏不属于竞品」。本批零发布
> 基线落地：`LegacyAssets.kt` 竞品视觉规格单一真相源（色值 14 / 尺寸 20 / 位图 19 /
> 叠层 5）、`VinylDiscView` 换竞品真实位图并修 0 尺寸空白、`LegacyPlayerPage` /
> `CloudMusicApp` / `CloudMusicTheme` 深色主题 + 启动即全屏播放页 + 迷你条按
> `legacy_e0g.xml` 还原。真机 `analysis/l3-reference/self-ui-player-v3.png` 证实
> 唱片区可见、首屏无菜单栏、安装 Success / pid 10208 / 零崩溃。**1:1 仍未达成并
> 如实挂账**：竞品 `PlayerActivity` 在 Manifest `exported=false` → `am start -n`
> 被 `SecurityException ... not exported` 拒绝，`MiMiniPlayerActivity` 直达后前台
> 仍停在自研 app，`input tap` 被 `INJECT_EVENTS` 拒 → **需用户手动切竞品播放页后
> `adb screencap` 取真机基准截图（硬阻塞，非待办）**。工具
> `tools/l3_visual_replica_spec.py`（13 项 PASS，`pixel_level_1to1=False`）+
> `check_source_contracts.py §61.42` 守护 + 报告
> `analysis/tl-l3-visual-replica-2026-11-09.md`。验证：4 个守护全 PASS、dev 6/6、
> release FAIL(C1,C4)、`--offline` **BUILD SUCCESSFUL in 22s** / 419 测试 / 46 套件 /
> 0 失败；19 模块 / 11 权限 / 51 素材 / 61 行 csv 全 false / 零新增依赖 / `res/` 未建 /
> `:ui-legacy` 未 include 零变化，A3 仍 G09 PARTIAL。**下一步：等用户提供竞品播放页
> 真机截图 → 按截图逐像素对齐**；`§61.41` 三项授权保留暂缓。

> 第三十三轮为只读核对轮（零源码改动），完整断点见 `docs/12 §42`。
> **2026-11-07 更新（T1 / T2 已完成，见 `docs/12 §43`）**：上轮「临时 SDK 不齐、无法实跑 Gradle」是误查
> —— SDK 一直位于 `Temp/cloudmusic-ta1-sdk`（`platforms/android-35` + `build-tools/35.0.0`）。
> 本轮解环后已实跑 `./gradlew test :app:assembleDebug --offline` **BUILD SUCCESSFUL in 1m 33s**，
> Hilt 生成图 `provideMediaControl()` 零参数、**350 测试零回归**。下方差异 / 任务 / 验证状态已同步。
> **2026-11-07 再更新（T4 完成，见 `docs/12 §44`）**：`IMediaControl` 加带默认空实现的
> `onPlaying()` / `onManualPause()`（C-15），`PlaybackService` 回传用户操作 →
> **`03-07 §12 T03` 生产链路成立**；全量构建 **BUILD SUCCESSFUL in 42s**、**353 测试 / 37 套件 / 0 失败**。
> **2026-11-07 终版更新（T3 / T5 / T6 / T7 完成，见 `docs/12 §45`）**：GAIN 三变体逐项映射、
> duck 静态链核实、旧注释清理与文档全量同步完成；**355 测试 / 37 套件 / 0 失败**。
>
> **★ 2026-11-07 第四十一批（真机 A3 深入，见 `docs/12 §50`）**：`§42.3` 任务清单已不是当前主线。
> 真机实证并修复 5 个生产侧 P0（`restore()` 无调用方 / `MediaSession` 无播放态 /
> 媒体键接收器空实现 / 无 mediaButtonReceiver / `SourceConfig.type` 恒 LOCAL），
> 补齐 3 个照搬真类与 5 个类的 native 字段 / 回调，攻破 3 道 CheckJNI abort。
> **native 6 so → PlaybackEngine → setDataSource → prepareAsync → 焦点 GAIN → Play 全链真机跑通、零崩溃**；
> **仍 0 个 active track / 播放期无 HTTP 请求 → 未出声**。
> 剩余工作见 `12 §50.7`：`DataProvider.createDataRequestHandler()` 取流句柄、事件码常量表、
> 本轮平台层改动的 JVM 单测与守护、应用侧权限/数据源后重跑 G01~G09。
> 基线 356 测试 / 37 套件 / 0 失败不变，主节点 4 个 / 硬门槛 2 个。
> `§42.3` 八项任务 **T1~T7 全收口**，仅 T8（真机 / ⑦ C4 决策 / L2 蓝牙）待。
> **2026-11-07 再更新（第三十七批，见 `docs/12 §46`）**：`BECOMING_NOISY` 接入
> `BluetoothOutputPolicy` 生产裁决链（开关 / DLNA 豁免 / 去重 / 设置热更新）；
> 澄清 `BLUETOOTH_CONNECT` 本就在 11 项权限内、V0.1 未运行时申请 → B01 降级。
> **356 测试 / 37 套件 / 0 失败**、5 条守护、零新增权限。L2 仍未收口（蓝牙 ACL 广播需授权流程）。
> **2026-11-07 终版（第三十八批，见 `docs/12 §47`）**：用户确认 D-145 两项 → 61 项素材按
> `legacy_` 前缀导出到 `app/src/main/assets/legacy-player/`（51 文件 / 320,569 字节）、
> `assets-mapping.csv` 空表 → **61 行 `replaced=false`**；C4 改 dev WARN / release FAIL，
> **release 阻断由「仅 C1」变为「C1 + C4」**（用户确认接受）。**⑨ 只验证到素材随 APK
> 打包 + 主工程零回归**；素材全量替换 / `:ui-legacy` / R 引用改写仍属 L3。
> 下表 §5 为 §46 时点快照，§47 验证见 `analysis/c4-assets-delivery-2026-11-07.md`。
> **2026-11-07 终版（第三十九轮，见 `docs/12 §48`）**：核对出 DLNA 输出切换
> 全链路生产调用方为 0、`§46` 的 DLNA 豁免空转；新增联动守护（成对断言）；
> 工作区 82 项分组盘点。**自研侧零决策风险、零回归、真机无关的代码项已穷尽**，
> 剩余四项均需素材 / 权限决策 / 真机或用户指定方向。
> **2026-11-07 真机更新（第四十批，见 `docs/12 §49`）**：用户提供 arm64 真机，
> 推翻上一条静态结论 —— 发现并修复 `PlaybackService` 全代码无启动点的 P0 缺口；
> FGS / MediaSession / 扫描调度 / Room 真机生效。**A3 PARTIAL RUN**，
> G01~G09 仍 NOT RUN（曲库 0 首 / 播放键未送达 / native 引擎未加载 / MIUI 无线 adb 限制授权与注入）。
> 硬门槛收敛为应用侧播放链路 + 真机 G01/G02。
>
> **★ 2026-11-08 第五十四批（见 `docs/12 §61.27`）**：`§42.3` / 上表 §5 快照均已过期。
> 真机 A3 已推进到 **G01~G07 PASS / G08 定向 PASS（2h 长稳未跑满）/ G09 PARTIAL**。
> 本批补做 HEAD `211080c9` 缺失的设备验证（一次性 GET RST 注入 → 连接期重试救回
> 302592B 全量取流、37.8s 完整播放、零 -103），并修复「冷启动首次 ACTION_PLAY
> 被吞、MediaSession 假报 PLAYING 进度恒 0」（`pendingPlay` 兑现）。
> 基线 **399 测试 / 43 套件 / 0 失败**、契约自检 PASS、dev 6/6。完整证据与复现步骤
> 见 `analysis/g08-injection-evidence-2026-11-08.md`。剩余硬门槛收敛为：
> **① USB/亮屏复测 G09；② G08 2h 长稳；③ 收口后恢复 Manifest 生产形态**
> （含 61.23 把 LAUNCHER MainActivity 写 `exported=false` 的口径疑点）。
>
> **★ 2026-11-08 第五十五批（见 `docs/12 §61.28`）**：用户「按照文档计划完成任务」→
> 推进 `§61.27` 下一步③中判据最明确的挂账项「默认仅 WiFi 播放」（`§56` 登记未实现）。
> 四层落地：纯 JVM `NetworkPlaybackPolicy` → `PlayerController` 双入口闸门 →
> `AndroidNetworkPlaybackGate`（ConnectivityManager + 实时 `wifiOnly`，零新增权限）→
> 设置页开关 + `PlaybackService` 同步判定防假报 PLAYING。`09 §539` 的历史挂账收口。
> 基线 **408 测试 / 44 套件 / 0 失败**、dev 6/6、release FAIL(C1,C4)、11 权限不变；
> 真机 WiFi 正向回归 PASS（单次 PLAY 出声）。**负向路径需 USB 环境复测**（无线 adb
> 关 WiFi 会断调试连接），未当结论用。剩余硬门槛仍是 G09 / G08 2h / Manifest 恢复。
>
> **★ 2026-11-08 第五十六批（见 `docs/12 §61.29`）**：用户「继续」→ 推进 `16 §5.3` 的
> **T-L L2 主节点**。按 `03-11 §2` 既有 `SHOW_BLUETOOTH_NAME` 动作落地运行时授权
> （输出弹窗触发、启动零申请）、`permissionGranted` 改运行时真值、授权后才动态注册
> ACL 广播并用纯 JVM `BluetoothBroadcastAction` 翻译成 `BluetoothEvent`；未授权仍按
> `03-09 §8` B01 只保留 `BECOMING_NOISY`，设备地址不入日志（B12）。**零新增权限 /
> 零口径变更**。同步更新 3 条锁着旧口径的历史守护（§46 / §52）+ 新增 4 组 §61.29 守护，
> 并回写 `03-09 §4` / `03-11 §1.1` / `PermissionRequester` 过时注释。
> 基线 **416 测试 / 46 套件 / 0 失败**、契约自检 PASS、dev 6/6、C3 11 权限不变；
> 真机 13:50 降级验证 PASS（`granted=false` → `ACL receiver off`，单次 PLAY 出声零崩溃）。
> **已授权状态下的 ACL 链路未真机实测**（无线 adb 缺 GRANT_RUNTIME_PERMISSIONS、不伪造
> 授权），需 USB 环境复测。剩余：G09 / G08 2h / Manifest 恢复 / T-L L3 / T-V。
>
> **★ 2026-11-08 第五十七批（见 `docs/12 §61.30`）**：用户「继续」→ 先核
> 「一次性 ABI 验收 / 指纹回填」挂账，结论是 **A3 = G01~G09 全绿才冻结舱**
> （`03-06 §6`），当前 G08/G09 未收口，**不回填才是正确状态、不伪造验收结论**；
> A1/A2 与 C1/C6 本就 PASS。随后把设备上自 13:50:49 起持续运行的 LIST_LOOP 双曲
> 循环正式登记为 **G08 2h 长稳监控**（终点 15:50:49），新增可复跑采样器
> `analysis/g08-longrun-sample.py` + `g08-longrun-samples.jsonl`。
> 首次中期样本 `13:50:49→13:59:19`（8.5 分钟；后续已更新至 14:01，见 §61.30）：pid 16859 未变、App 在后台
> （焦点 `com.miui.home`）、灭屏 Dozing、16 次完成过渡、`-103`/`msg=100`/`FATAL`/
> `ETIMEDOUT` 全 0、最大 PSS 106.6 MiB（目标 ≤180）→ **ALIVE + CLEAN（非 2h 结论）**。
> 采样器迭代中按原始数据修正 4 处口径错误（非 UTF-8 解码 / `msg=2` 双日志重复计数 /
> 全量 logcat 混入 Google Play 的 ETIMEDOUT / 重叠窗口汇总重复累加）。
> **下一步**：15:50 后补采样收口 G08；同环境复测 G09 / 非 WiFi 负向 / 已授权 ACL；
> A3 全绿后才回填 ABI 验收与冻结舱；其后 Manifest 恢复、T-L L3、T-V。

> **★ 2026-11-08 第五十八批（见 `docs/12 §61.31`）**：用户「拉取代码…继续任务」→
> 断点由 `§61.30` 的「15:50 补采样收口 G08 2h」改为**用户新决策：20 分钟足够，
> 实际使用有问题再修改**（`01 §10 #5` / `03-06 §6.2` / `03-12 §9` 口径统一）。
> 真机实证：pid 16859 自 13:50:48 连续存活至 14:12:38（**21 分 49 秒**），
> 期间后台灭屏、22 次完成过渡、阻断级异常 0、PSS 峰值 110.3 MiB；终点
> `ApplicationExitInfo reason=10 USER REQUESTED / FORCE STOP / SwipeUpClean`
> = **用户上划清理**，非系统回收、非崩溃 → **G08 = PASS（20 分钟口径）**。
> 修复 `g08-longrun-sample.py` 硬编码 `adb`（本机 adb 不在 PATH，旧版直接
> `[WinError 2]`，文档声称「可复跑」实际跑不起来）：adb 自动探测 / `--adb` /
> `--connect` / `ApplicationExitInfo` 退出原因 / 进程消失不再退化全量 logcat /
> 判据 20 分钟。证据 `analysis/g08-20min-verdict-2026-11-08.md`。
> A3 = G01~G07 PASS / G08 PASS / G09 PARTIAL → **仍未全绿，舱不冻结、指纹不回填**。
> 剩余：G09 复测、非 WiFi 负向、已授权 ACL、Manifest 生产形态、T-L L3、
> T-V 阻断 12 条 / DMR T20/T21。
> 基线：在线 `./gradlew test` **BUILD SUCCESSFUL in 2m4s**；契约自检 PASS；
> dev **6/6**；release **FAIL(C1,C4)**；`--offline` 因本机缺 room/datastore
> 缓存依赖失败（环境差异，非代码缺陷）。本批工作区未 commit / push。

> **★ 2026-11-08 晚 第五十九批（见 `docs/12 §61.32`）**：用户「继续」→ 设备亮屏，
> 推进 `§61.31` 剩余①的 G09 复测（**零源码 / Manifest / 构建改动**）。
> 四组真机实验：① 桌面启动实测成功（`resolve-activity` 可解析、显式组件与
> `monkey -c LAUNCHER` 均拉起；`am start -a/-c/-p` 失败属参数组合，非产品缺陷）；
> ② 前台态 crash 约 100ms 恢复，但触发者是 **Activity 重建**而非 START_STICKY；
> ③ 后台态 crash **亮屏下 206s 仍无服务重启**，日志 `toggled into fg service restriction`
> + `RUN_ANY_IN_BACKGROUND: ignore` → **MIUI OEM 抑制**，推翻旧文档「需 USB/亮屏环境」
> 的归因（USB 不改变 OEM 策略）；④ 用户重新点开 App 约 **957ms** 自动
> `prepare g01-local`、保持 PAUSED，点播放即 `AudioTrack Write start play`。
> **G09 仍 PARTIAL，不判 PASS**；按用户「实际使用有问题再修改」不加自恢复兜底。
> A3 = G01~G08 PASS / G09 PARTIAL → 舱不冻结、指纹不回填。
> 同批修正 **P1 合规声明错误**：`NOTICE.md §3` 原写「二进制与素材尚未引入」
> （实际 18 so 已打包 / 12 JNI 真类已编译 / 61 项素材已导出），按实际状态重写；
> `03-07 §12 T16` 的「后台 2h」同步为 20 分钟；登记 app 无 `res/`、无
> `label`/`icon`（系统默认）的发布阻断 #2 现状。发布阻断 12 条盘点：
> **PASS 5 / 部分 5 / 未满足 2**，自动检查实际阻断 C1 + C4。
> 证据 `analysis/g09-recovery-evidence-2026-11-08.md`；验证结束 `force-stop`
> 恢复设备起点。剩余断点：非 WiFi 负向 / 已授权 ACL / A3 全绿后 ABI 回填 /
> Manifest 生产形态（LAUNCHER `exported` 口径需核实）/ T-L L3 / T-V 剩余项。
> 工作区未 commit / push。

> **★ 2026-11-08 晚 第六十批（见 `docs/12 §61.33`）**：用户「继续」→ 处理
> `§61.32` 剩余④的 LAUNCHER `exported` 口径疑点，**零行为改动**（仅 Manifest 注释 + 文档）。
> 按 Android 官方 `<activity>` 文档定论：带 MAIN/LAUNCHER 的 `MainActivity`
> **必须 `exported=true`**（Launcher 是外部应用，设 false 会抛
> `ActivityNotFoundException`），`§61.23` / `§61.24` 的「恢复 false / 随 Service
> 一并恢复」为错误口径，**作废**；`§61.27` 原标【推断】本批定论。
> 生产形态三组件：`MainActivity=true` / `PlaybackService=false` 且移除
> `ACTION_PLAY` intent-filter / `MediaButtonEventReceiver=true`，
> 施工单与验收步骤已写入 `12 §61.33`，**A3 全绿后才执行**（当前仍需 ACTION_PLAY
> 做真机触发）。同批修 `03-11 §1.1` 表格（Receiver 原写 `exported=false` +
> 「有意空实现」，与 `12 §50/§51` 的 `exported=true` + `MediaKeyAction` 转发矛盾），
> 并把全库现行 **7 处**旧 2 小时判据统一为 20 分钟（`03-07 B10`、`03-09 B11/T12`、
> `03-11 B07`、`10 §4`、`16 §5.3`、`09 §4 R8`；`12` 历史批次表述保留）。
> `tools/check_source_contracts.py` 新增 §61.33 守护（LAUNCHER exported + 2h 残留）。
> A3 状态不变：G01~G08 PASS / G09 PARTIAL → 舱不冻结、指纹不回填。
> 设备未触碰；工作区未 commit / push。

> **★ 2026-11-08 晚 第六十一批（见 `docs/12 §61.34` / C-16）**：用户「继续」→
> 核对发布阻断 #11 的 JVM 覆盖时发现 `DegradationPolicy.evaluate()` 的 **`offline`
> 是从未被读取的死参数**，`03-11 §3`「网络权限/飞行模式」一行只靠恒 false 的
> `showNetworkErrorDialog` 表达，测试里两态断言等价（**假覆盖**）。
> 本批做契约演进 C-16：`DegradationOutcome` 新增
> `networkSourceAvailable: Boolean = true`（默认值，现有调用零破坏），
> `evaluate` 映射 `!offline` 并追加离线信息提示；**硬规则不变**（本地曲库/播放恒可用、
> 不弹网络错误弹窗）。`PermissionGateTest` 新增 2 用例（两态差异 / 五类叠加），
> 把 `hardRuleNeverBlocksLocalPlayback` 的弱断言升级为 `assertEquals(!offline, …)`。
> `./gradlew test --offline` **BUILD SUCCESSFUL in 1m6s，418 测试 / 46 套件 / 0 失败**
> （基线 416 + 2，XML 确认两条 C-16 用例执行）；契约自检四条守护 PASS、
> dev 6/6、release FAIL(C1,C4)。
> 发布阻断 #11：**JVM 侧六行矩阵已全量覆盖**，真机 T05/T06/T14 仍待 T-V，仍为
> 「部分验证」不判全绿；`networkSourceAvailable` 当前无生产消费方，如实登记不伪造接线。
> `check_source_contracts.py` 新增 §61.34 守护防退回死参数。
> A3 状态不变：G01~G08 PASS / G09 PARTIAL → 舱不冻结、指纹不回填。
> 设备未触碰；工作区未 commit / push。

> **★ 2026-11-08 晚 第六十二批（见 `docs/12 §61.35` / C-17）**：用户「继续」→
> 想接上 §61.34 遗留的「`networkSourceAvailable` 无生产消费方」，核对后发现
> **离线事实早有生产实现**：`NetworkPlaybackPolicy` 的 `NetworkKind.NONE` 分支
> 经 `AndroidNetworkPlaybackGate`（ConnectivityManager）由 `PlayerController`
> 在播放前消费；C-16 的 `DegradationPolicy.offline` 是 UI 降级契约，两套口径有分叉风险。
> 本批新增 `isOffline(kind)` 作为**单一真相源** + `NetworkPlaybackPolicyTest`
> 遍历 4 种 `NetworkKind` 的交叉断言 + `check_source_contracts.py` §61.35 守护；
> **零产品行为改动，仍不伪造 UI 接线**（生产消费方依旧为空，如实登记）。
> 真机硬阻塞定性：仅无线 adb 时关 WiFi / 开飞行模式会失联且无法远程恢复
> （要关飞行模式又必须先有 adb），`pm grant` 缺 GRANT_RUNTIME_PERMISSIONS 且约定
> 不伪造授权 → 发布阻断 #10 与非 WiFi 负向 / ACL 属 **USB / 物理在场** 条件。
> `./gradlew test --offline` **419 测试 / 46 套件 / 0 失败**（416+C-16 的 2+C-17 的 1）、
> 契约自检五条守护 PASS、dev 6/6、release FAIL(C1,C4)。
> 本批把 §61.31~§61.35 五个批次按三组结构化**本地提交（未 push）**。
> **剩余收敛为需用户拍板**：G09 走自恢复兜底 / 独立进程隔离 / 接受 PARTIAL
> 为已知 OEM 限制（决定 A3 能否收口），其后 ABI 回填 + 冻结舱 + §61.33 施工单。
> A3 状态不变：G01~G08 PASS / G09 PARTIAL，舱不冻结、指纹不回填。

> **★ 2026-11-08 晚 第六十三批（见 `docs/12 §61.36`）**：用户对 G09 选
> **「3 保持现状不收口，先推进 T-L L3 / T-V 其它项」** → 转推素材替换线。
> `zipfile` 实证 **`app/src/main/assets/legacy-player/` 的 51 个竞品素材全部随
> debug APK 分发**（此前文档只说「不参与 aapt2」，不等于不进包），其中
> **8 个 layout XML 含 44 处 `com.netease.*` 类名 / 20 个去重类**
> （含 `NeteaseMusicSimpleDraweeView`）；`tools/check-no-netease-import.py`
> 跳过整个 `C4_STAGED_ASSETS` 目录后 C2 才 PASS（dev 报跳过 23 个文件），
> **该 PASS 不覆盖 APK 内容**。
> 修正发布阻断 #2（不能判 PASS）/ #5（豁免 + 分发侧残留）/ #7
> （`NOTICE.md §3` 补随包分发声明）；#1 仍为头号阻断（61 行 `replaced=false`）。
> 产出 `analysis/tl-l3-assets-compliance-audit-2026-11-08.md`（实证与类名清单）
> + `analysis/tl-l3-substitution-plan.md`（61 项按 type 的 A~E 五阶段施工单：
> 素材来源决策 → 重写/删除 8 个竞品 layout → csv 全 true / C4 转 PASS →
> 移出暂存区 + `:ui-legacy` / R 引用改写 → 验证回写）。
> `check_source_contracts.py` 加 §61.36 守护防伪造替换；`03-12 §8` 加口径注。
> **零源码 / 零产物 / 零设备改动**，未删改任何素材；契约自检六条守护 PASS、
> dev 6/6、release FAIL(C1,C4)。A3 按选项 3 保持 G09 PARTIAL，舱不冻结。
> 剩余前置：自有素材来源与许可证（用户决策）、`:ui-legacy` 舱体、R 引用改写工具。

> **★ 2026-11-08 21:49 第六十四批（见 `docs/12 §61.37` / D-146）**：用户定案
> 「素材直接用竞品的，这个 APP 只是我个人使用」→ 项目范围锁定
> **个人自用 / 侧载**（不公开发布仓库 / 不分发 APK / 不上架），
> **竞品素材直接沿用、不做替换**，`assets-mapping.csv` 61 行 `replaced=false` 保持。
> 口径变更：`03-12 §7` 场景表个人自用为唯一目标、另两行标注范围外；
> `§8` 发布阻断清单明确「**仅公开发布时适用**」；`NOTICE.md` 保留权利归属如实声明
> 并补范围决策；两份 T-L L3 报告加决策批注（替换施工单降级为未来备选）。
> **C4 dev WARN / release FAIL 机制不变**（D-145 确认，不擅自改构建基线），
> 但不再是当前待办、不影响本地 debug 构建。
> 当前 T-L L3 只剩**技术接入项**：8 个含 `com.netease.*` 类名的 layout 需重写
> 或删除（**aapt2 / 运行时找不到类，技术原因非权利原因**）、`:ui-legacy` 舱体、
> R 引用改写、照搬播放页。加 §61.37 守护（范围口径在位 + 不得伪造 `replaced=true`）。
> 零源码 / 零产物 / 零设备改动；契约自检七条守护 PASS、dev 6/6、
> release FAIL(C1,C4)。A3 仍按用户选项 3 保持 G09 PARTIAL，舱不冻结。

> **★ 2026-11-08 晚 第六十五批（见 `docs/12 §61.38`）**：用户「继续」→
> 承接上条 T-L L3 技术接入，**先实证、不擅自接 `res/`**。新增可复跑的
> `tools/legacy_layout_audit.py` 与 `analysis/legacy-layout-audit.json`，
> 钉死 9 个暂存 layout：9 个良构 XML / 114 元素、**44 处 `com.netease.*` /
> 20 去重类**、**33 个混淆 `app:*` attr**、**251 处资源引用**（187 处 `@id`
> 且 `@+id` 定义 0 / 64 处可导出 / 45 唯一 / 2 处 `@null`）。
> **口径澄清**：`12 §47` 的「146 项资源」= 101 唯一 id + 45 可导出资源，
> 45 项被 csv 61 行 / 闭包 163 项 / 暂存 51 文件三方完整覆盖、**缺口 0**
> （素材侧自洽完备）。**暂存素材在自研源码零生产引用方**、`app/src/main/res/`
> 不存在、`:ui-legacy` 未 include → 接 `res/` 属「照搬播放页」**可选增强**、
> 非运行必需（运行必需 0 项）。真正阻塞是三道 aapt2 缺口：无定义 id /
> 未声明 attr / 无实现 View（只换类名仍编译不过）。
> 产出 `analysis/tl-l3-technical-onboarding-2026-11-08.md` 分层施工单
> （阶段 A~E，仅在拍板后执行；三项需拍板：是否做照搬播放页 / UI 形态 /
> 是否建第 20 个模块）与 `check_source_contracts.py` §61.38 守护
> （防 layout 误搬进 `res`、防实证基线漂移，未来合法启用 `:ui-legacy` 自动降级 NOTE）。
> 验证：契约自检 PASS、dev **6/6**、release **FAIL(C1,C4)** 正确阻断、
> `compileall` OK、guard 零个人路径 PASS；`./gradlew test --offline`
> **419 测试 / 46 套件 / 0 失败**（BUILD SUCCESSFUL in 1m23s）、
> `:app:assembleDebug --offline` BUILD SUCCESSFUL in 13s、APK
> 31,256,825 字节 / 20 so / 51 个 legacy-player 条目。
> 19 模块 / 11 权限 / 20 so / 61 行 csv / 51 素材 / 指纹零变化；设备未触碰。
> **A3 仍 G01~G08 PASS / G09 PARTIAL → 舱不冻结、指纹不回填**。
> 剩余断点：① 是否做照搬播放页（三项需拍板）；② T-V 需 USB / 大数据集 /
> DMR 设备的项；③ G09 保持 PARTIAL；④ A3 全绿后 ABI 回填 + 冻结舱 +
> `§61.33` 施工单。工作区改动未提交（**未 push**）。

> **★ 2026-11-08 晚 第六十六批（见 `docs/12 §61.39`）**：用户「继续」→
> 承接 `§61.38` 施工单阶段 A/B 的「待猜项」，做**零决策风险的编译级实证**。
> `tools/legacy_layout_resolve.py` 从竞品 `attrs.xml` / `public.xml` 精确还原
> **33/33 attr 定义 + 101/101 id**（16 个带 enum/flag 子项；format 分布
> reference 13 / dimension 6 / (none) 4 / boolean 4 / string 2 / float 2 /
> integer 1 / reference+color 1），候选 `ids.xml` / `attrs.xml` 仅在
> `analysis/l3-probe/` → **阶段 A/B 为机械可搬，不需猜语义**。
> `tools/l3_aapt2_probe.py` 扩展为**四步实证**（仓库外临时最小工程）：
> ① baseline id 11 / attr 9 → ② +`ids.xml` id 0 → ③ +`attrs.xml` attr 0
> （剩 20 条 `drawable/color/font` 资源引用缺失）→ ④ 42 个暂存资源去
> `legacy_` 前缀接入后 **`compile_rc=0 / link_rc=0 / 错误 0 行`**；
> 8 个 `@color/*` 内部引用自洽、`.9.png` 正常编译、证据路径已脱敏。
> **主要结论**：aapt2 **不校验 Java 类** → `§61.37`「因 aapt2 找不到竞品类」
> 归因不完整，编译阻塞始终是资源定义缺失；44 处 / 20 类 `com.netease.*`
> View 属运行时 `LayoutInflater` 的 `ClassNotFoundException`，须阶段 C 重写；
> aapt2 全绿 ≠ 运行时可 inflate。报告
> `analysis/tl-l3-attr-id-resolution-2026-11-08.md`、§61.39 守护 +
> 第六十六批 NOTE。
> 验证：契约自检 PASS（含新守护）、dev **6/6**、release **FAIL(C1,C4)**、
> `compileall` OK、guard 零个人路径 PASS、两个工具复跑幂等；
> `./gradlew test :app:assembleDebug --offline` **BUILD SUCCESSFUL in 36s**、
> **419 测试 / 46 套件 / 0 失败 0 错误 0 跳过**、APK 31,256,825 字节 /
> 20 so / 51 个 legacy-player 条目 / 1 指纹。19 模块 / 11 权限 / 20 so /
> 61 行 csv（全 `false`）/ 51 素材零变化；`app/src/main/res/` 仍未建立、
> `:ui-legacy` 未 include、设备未触碰。
> **A3 仍 G01~G08 PASS / G09 PARTIAL → 舱不冻结、指纹不回填**。
> 剩余断点：① 是否做照搬播放页（三项需拍板）；② 阶段 C 重写 20 个竞品 View
> （唯一技术真阻塞）；③ T-V 需 USB / 大数据集 / DMR 设备的项；④ G09 保持
> PARTIAL；⑤ A3 全绿后 ABI 回填 + 冻结舱 + `§61.33` 施工单。
> 工作区改动未提交（**未 push**）。
>
> **★ 2026-11-08 晚 第六十七批（见 `docs/12 §61.40`）**：用户「继续 UI交互严格复刻竞品！！！！」
> → 拍板执行「照搬播放页」且 UI 交互严格复刻竞品。先完成 `§61.38` / `§61.39` 遗留的
> 只读核查，核心结论：**交互逻辑不在 XML 里**（`12 §39` 纯代码属性动画），
> 9 个已导出 layout 只是变体 / 子组件（竞品共 48 个），20 个竞品 View 无实现，
> 4 类 AndroidX 依赖离线未缓存 → 照搬 XML 既不能复刻交互、也不能运行，且要改发布基线。
> 故按 `03-10` 决策 ① 在 `:app` 内**零发布基线变更**落地可运行播放页：
> `VinylDiscView`（属性动画参数逐项照搬、不用 Compose Canvas / `animateFloatAsState`）、
> `PlayerStateBridge`（`03-10 §6` 唯一通道）、`LegacyPlayerPage`（跑马灯 / 手势 /
> 进度 / 控制 / 整页展开），共复刻 **7 项交互**；`§6` 中 **6 项**无生产后端的交互
> （模式 / 收藏 / 歌词 / 输出切换 / 队列弹窗 / 封面）只读占位、**不伪造完成**。
> 新增 `tools/l3_interaction_spec.py` + `analysis/l3-interaction-spec.json`
> （14 项全 PASS）+ §61.40 守护 + 第六十七批 NOTE + 报告
> `analysis/tl-l3-interaction-replica-2026-11-08.md`；改 `CloudMusicApp.kt` /
> `MainActivity.kt` 接线。
> 验证：契约自检 PASS（含新守护）、`py_compile` OK、guard 零个人路径 PASS、
> dev **6/6**、release **FAIL(C1,C4)**；
> `./gradlew test :app:assembleDebug --offline` **BUILD SUCCESSFUL in 44s**、
> **419 测试 / 46 套件 / 0 失败 0 错误 0 跳过**、APK 31,256,825 字节 /
> 20 so / 51 个 legacy-player 条目 / 1 指纹。19 模块 / 11 权限 / 20 so /
> 61 行 csv（全 `false`）/ 51 素材 / 零新增依赖 / 指纹零变化；
> `app/src/main/res/` 仍未建立、`:ui-legacy` 未 include、设备未触碰。
> **本轮未新增 `docs/` 文件 → `09 §3` 索引与计数仍 30 个不变。**
> **A3 仍 G01~G08 PASS / G09 PARTIAL → 舱不冻结、指纹不回填**。
> 剩余断点：① 三项拍板（像素级照搬是否值得 / UI 形态 / 是否建第 20 模块 `:ui-legacy`、
> 是否允许联网补 4 类依赖、是否补导 39 个 layout）；② 48 个播放页 layout 中其余
> 39 个与 20 个竞品 View 的像素级 XML 视觉、横屏黑胶 / 行车电台页专属交互；
> ③ `03-10 §6` 中 6 项无生产后端的交互；④ T-V 需 USB / 大数据集 / DMR 设备的项；
> ⑤ G09 保持 PARTIAL；⑥ A3 全绿后 ABI 回填 + 冻结舱 + `§61.33` 施工单。
> 工作区改动未提交（**未 push**）。
>
> **★ 2026-11-09 上午 第六十八批（见 `docs/12 §61.41`）**：用户对 A/B/C 明确回答
> **B** —— 三项全开（第 20 模块 `:ui-legacy` / 联网补 4 类依赖 / 补导其余 39 个
> layout），授权改变发布基线。按 `§61.38` 同纪律先实证：`tools/l3_full_replica_audit.py`
> 全量审计 48 个 `layout_semantic_tags`，**39 个未导出 layout 已全量复制到**
> `analysis/l3-full-replica/layouts/`（拍板项 ③ 文件层落地），实测 421 元素 /
> **53 个竞品 View 类 / 81 个混淆 `app:*` attr / 282 个 `@id` / 163 项资源引用**
> （约为 `§61.38` 的 `20/33/101/45` 口径 3 倍，`§61.39` 四步 aapt2 实证只覆盖 9 个、
> 不能外推）。**口径校正**：48 项是「资源闭包涉及的 layout」不是 48 个播放页，
> 11 个非播放页误命中、**无标准竖屏主播放页 tag**，`§61.40` 的「39 个含标准竖屏
> 主播放页」表述作废。**决定性结论**：`gb.xml` 里 ConstraintLayout 标准类名但
> `layout_constraint*` 被混淆成 `a5f`/`a5l`/`a65`/`a69`，竞品 `attrs.xml` 标准名
> **0 个** → 重声明同名 attr 只分配新 ID，ConstraintLayout 读不到值、约束语义不还原；
> 引依赖或 `§61.39` 的 `attrs.xml` 都只让 aapt2 编译通过；53 个 View 的绘制不可机械
> 还原。Google Maven 实测 `HTTP 200`。故三项授权**保留但暂缓执行**、本批零基线改动：
> 19 模块 / 零新增依赖 / `res/` 未建 / `:ui-legacy` 未 include / 51 素材 / 61 行 csv
> 全 `false` 零变化。新增 `tools/l3_full_replica_audit.py` +
> `analysis/l3-full-replica-audit.json`（4 项判据全 PASS）+ 施工单
> `analysis/tl-l3-full-replica-plan-2026-11-09.md` + §61.41 守护 + 第六十八批 NOTE。
> 修正后路径：B1 结构照搬 / B2 先补主页面与 81 个 attr 反查 / A 真实贴图优先
> （27 张真实图片）/ C 维持现状，**待重新拍板**；建议先 A 并行 B2 只读实证。
> 验证：契约自检 PASS（含新守护、§61.38/39/40 仍 PASS）、`py_compile` OK、
> guard 零个人路径 PASS、dev **6/6**、release **FAIL(C1,C4)**、
> `./gradlew test :app:assembleDebug --offline` **BUILD SUCCESSFUL in 49s**
> （254 tasks 全 up-to-date）、**419 测试 / 46 套件 / 0 失败 0 错误 0 跳过**基线不变。
> **本轮未新增 `docs/` 文件 → `09 §3` 索引与计数仍 30 个不变。**
> **A3 仍 G01~G08 PASS / G09 PARTIAL → 舱不冻结、指纹不回填**。
> 剩余断点：① 按修正后路径重新拍板；② 竖屏主播放页 layout 定位与 81 个混淆 attr
> 反查（B2 前置）；③ 53 个竞品 View 绘制重写；④ `03-10 §6` 中 6 项无生产后端交互；
> ⑤ T-V 需 USB / 大数据集 / DMR 设备的项；⑥ G09 保持 PARTIAL；⑦ A3 全绿后 ABI
> 回填 + 冻结舱 + `§61.33` 施工单。四批改动未提交（**未 push**）。

## 1. 当前进展（文档口径）

- 进度断点：`docs/12 §41`（第三十二批，音频焦点 + `ACTION_AUDIO_BECOMING_NOISY` 平台接线）；`09 §1/§2`、`16 §5.3` 同步到该批。
- 基线：19 模块 / 350 测试 · 37 套件 / 0 失败；自检 36/143/15；dev 6/6；release 仅 C1；C3 11 权限；C4 空表；APK 20 so。
- 剩余：主节点 4 个（T-L L1/L2/L3、T-V）、最小可听 2 个硬门槛（L1 真机 G01/G02 → T-V）。装手机能看能点、仍放不出真实声音。
- ⑦ 的 C4 口径（D-145）连续三轮未被选择，**发布基线保持不变**。

## 2. 关键发现：代码已领先文档一个批次

00:17 改动的 4 个文件已把焦点 / 断连从「直连 `PlaybackRuntime.pause()`」换到 `IMediaControl.onFocusChange(FocusChange.*)` 正式裁决链：

| 文件 | 改动 |
|---|---|
| `app/.../PlaybackService.kt` | 焦点常量映射 `FocusChange.*`；注入 `IMediaControl`；`onFocusChange` 派发 |
| `core-di/.../di/AppBindings.kt` | `provideMediaControl(runtime)` 改为 bind `RuntimeMediaControlHandle(runtime)` |
| `core-di/.../di/playback/RuntimeMediaControlHandle.kt` | 新增桥（`duck/unduck/seekRelative` 为 no-op） |
| `app/build.gradle.kts` | 新增 `implementation(project(":core-media"))` |

代码注释 **5 处**引用 `12 §42`，但 `docs/` 全库检索 `§42` /「第三十三批」**0 命中** → 悬空引用。

## 3. 差异清单（9 项）

| # | 级别 | 差异 | 证据 |
|---|---|---|---|
| 1 | **P0** | **DI 依赖环**：`provideMediaControl(runtime)` → `PlaybackRuntime` → `PlayerController` → `IMediaControl` → 回到起点。Dagger 编译期应报 `DependencyCycle`，该批代码**未证明可编译** | `AppBindings.kt:68-71`、`PlaybackComposition.kt:109-116, 216-232` |
| 2 | **P0** | **重复 bind + 桥冗余**：`PlayerController.init` 已 bind 内部 `HandleAdapter`，且它已实现 `duck/unduck`（`engine.setVolume`）与 `seekRelative`；新句柄三者 no-op，能力退化且互相覆盖 | `PlayerController.kt:49-51, 233-246`、`RuntimeMediaControlHandle.kt:35-40` |
| 3 | P1 | CAN_DUCK 经 `FocusPolicy` 只调 no-op `duck()` → **既不暂停也不降音量**；§41.2「三类 LOSS 一律暂停」与 `03-07 §4`「duck(0.25f)」均不满足 | `FocusPolicy.kt:41-45` |
| 4 | P1 | §41.2 / §41.5「GAIN 不自动恢复」「V0.1 直落 runtime.pause」「尚未 bind 真实 handle」与代码相反；`03-09 §8` 落地注、`09`、`16` 同步滞后 | `12 §41`、`03-09 §8:96-100` |
| 5 | P1 | 代码自相矛盾：类 KDoc 说未 bind、`:56` 说已 bind；`pauseFromExternal()` 成死代码；`AppBindings.kt:46` 仍写「待 T-L L2」 | `PlaybackService.kt:44-48, 56, 185-193` |
| 6 | P2 | `MediaSession.Callback` 仍直调 `runtime.*`，未通知 `onPlaying/onManualPause` → `03-07 §12 T03`「手动暂停 → GAIN 不恢复」失效；两方法只在内存实现、不在接口 | `PlaybackService.kt:109-120`、`MediaControlApi.kt:15-29` |
| 7 | P2 | GAIN 三变体一律压平为 `FocusChange.GAIN` | `PlaybackService.kt:76-78` |
| 8 | P2 | 零新增单测 / 零守护 / 零构建记录；`check_source_contracts.py:1164-1178` 只查 6 个字符串、`:1251` NOTE 过时 → **绿灯不代表接线正确** | 本轮实测 |
| 9 | P2 | `PlaybackComposition.kt:105` 仍写 `InMemoryMusicEngine`，与 §36 换绑 `EngineAdapter` 矛盾 | 源码注释 |

### 3.1 差异状态更新（T1 / T4 / T3 后，9 项全部处理）

- **#1 DI 依赖环 → 已解决**：`provideMediaControl()` 零参数；生成图 `SingletonC.java:905` 无参、`:860` 把同一 `@Singleton` 实例交给 `providePlayerController`、`:584` 注入 `PlaybackService`；静态守护防回归。
- **#2 重复 bind + 桥冗余 → 已解决**：删 `RuntimeMediaControlHandle.kt`，APK DEX 无该类；`HandleAdapter` 唯一句柄。
- **#3 duck 语义 → 静态链已通、真机待验**：`CAN_DUCK` → `HandleAdapter.duck(0.25f)` → `IMusicEngine.setVolume` → `EngineAdapter.setVolume` → `PlaybackEngine.setVolume`（native），**不是 no-op**；听感待 G01。
- **#4 / #5 → 已解决**：`PlaybackService` KDoc / 死代码、`AppBindings`、`PlaybackComposition.kt:105`、`03-09 §8`、`09 §1/§2/§7`、`16 §5.3`、`03-11 §1.1`、自检过时 NOTE 全部同步；`§41.2` 加「V0.1 旧语义作废」注。
- **#6 → 已解决（T4 / C-15）**：T03 生产链路成立；双注入守护覆盖隐式顺序依赖。
- **#7 GAIN 压平 → 已解决（T3）**：三个 `AUDIOFOCUS_GAIN*` 常量逐项映射三变体 + 2 单测 + 防压平守护。
- **#8 守护 → 已解决（T6）**：`check_source_contracts.py` 共 4 条长效守护（T1 / C-15 / T4 / T3）。
- **#9 → 已解决**：`PlaybackComposition.kt:105` 改为 `EngineAdapter` 口径。

## 4. 任务清单（T1~T7 全部收口）

| # | 优先级 | 任务 | 状态 / 验收 |
|---|---|---|---|
| T1 | P0 | 解环 + 去冗余桥 | **✅**：零参数绑定、删自建句柄；Hilt 图无环 |
| T2 | P0 | 真实构建验证 | **✅**：SDK 实为齐备（上轮误查），全量构建实证 |
| T3 | P1 | 焦点语义对齐 `03-07 §4/§12` | **✅**：GAIN 三变体逐项映射 + 2 单测 + duck 静态链核实 + 契约文档全同步 |
| T4 | P1 | 媒体会话改走门面 | **✅**：C-15 默认方法 + 状态回传 + 3 单测，T03 生产链路成立 |
| T5 | P1 | 清死代码与矛盾注释 | **✅**：最后一处 `PlaybackComposition.kt:105` 已改 |
| T6 | P1 | 补长效守护 | **✅**：4 条守护生效 |
| T7 | P1 | 回写正式交付批次 | **✅**：`12 §43/§44/§45`、`09 §1/§2/§7`、`16 §5.3`、`03-07 §3/§4`、`03-09 §8`、`03-11 §1.1` 全同步 |
| T8 | P2 | 原断点：⑦ C4 口径、L2 蓝牙 / `OutputRouter` 联动、L1 真机 G01/G02 与 T-V | 🟡 **⑦ C4 口径已由用户确认并在 `§47` 落地**（素材导出 + csv 61 行 + C4 新语义）；**L2 自研侧零权限部分已由 `§46` 完成**。剩余：① 61 项素材全量替换 + `:ui-legacy` 舱体 / R 引用批量改写（L3）；② 蓝牙 ACL 广播 + `BLUETOOTH_CONNECT` 运行时授权流程（L2）；③ 真机 G01/G02（唯一硬门槛） |

## 5. 验证与状态（§46 时点快照；§47 见交付报告）

| 检查 | 第三十三轮（核对） | T1 后 | T4 后 | T3 后 | **§46 后（当前）** |
|---|---|---|---|---|---|
| `./gradlew test :app:assembleDebug --offline` | NOT RUN（误判 SDK 不齐） | 1m33s | 42s | 32s | **BUILD SUCCESSFUL in 28s / 20s** |
| 测试 XML | — | 350 / 37 / 0 | 353 / 37 / 0 | 355 / 37 / 0 | **356 测试 / 37 套件 / 0 失败 / 0 错误 / 0 跳过**（+1） |
| APK DEX / so | — | 焦点链、20 so | +`onManualPause`/`onPlaying` | +`GAIN_TRANSIENT_MAY_DUCK` | +`BluetoothOutputPolicy`/`updateSettings`；仍不含 `RuntimeMediaControlHandle`；20 so |
| `check_source_contracts.py` | PASS 36/143/15（未覆盖新链） | 同 | +3 守护 | 4 条守护 | **PASS 36/143/15，共 5 条守护** |
| `pre_release_check.py` dev / release | PASS 6/6 | 6/6 / 仅 C1 | 6/6 / 仅 C1 | 6/6 / 仅 C1 | **PASS 6/6 / FAIL（仅 C1）** |
| guard / compileall | PASS | PASS / OK | PASS / OK | PASS / OK | PASS / OK，零个人路径 |
| Manifest / csv / docs 文件数 | 11 / 空表 / 30 | 同 | 同 | 同 | **11 权限（含既有 `BLUETOOTH_CONNECT`）/ 空表（C4 PASS）/ 30 个文件**，均未改 |

## 6. 回写记录

- 第三十三轮（核对）：`docs/12 §42`、`09 §1`、`16 §5.3`、当日日志、`MEMORY.md`（精简重写）。
- 第三十四轮（T1）：`12 §43`、`09 §1/§2`、`16 §5.3`、`03-09 §8`、任务清单、日志、`MEMORY.md`。
- 第三十五轮（T4）：`12 §44`、`09 §1/§2`、`16 §5.3`、`03-07 §3`、`check_source_contracts.py`、任务清单、日志、`MEMORY.md`。
- 第三十六轮（T3 收尾）：`12 §45`（含 §41.2 现行口径更正）、`09 §1/§2/§7`、`16 §5.3`、`03-07 §4`、`03-09 §8`、`03-11 §1.1`、`check_source_contracts.py`、任务清单、日志、`MEMORY.md`。
- 第三十七轮（§46）：`12 §46`、`09 §1/§2/§7`、`16 §5.3`、`03-09 §5/§8`、`03-11 §1.1`、`check_source_contracts.py`、`BluetoothOutputPolicy.kt`、`PlaybackService.kt`、`OutputRoutingContractTest.kt`、任务清单、日志、`MEMORY.md`。
- 第三十八轮（§47）：`12 §47`、`09 §1/§2/§7`、`16 §5.3`、`14` 顶部现行状态注、`06 §4`、新增 `tools/export_player_assets.py`、`tools/check-assets.py`、`tools/check_source_contracts.py`、`tools/check-no-netease-import.py`、`docs/assets-mapping.csv`（空表 → 61 行）、`app/src/main/assets/legacy-player/`（51 文件）、任务清单、日志、`MEMORY.md`。
- **本批按用户确认改了 `assets-mapping.csv` 与 C4 / dev / release 基线**；未改构建脚本 / Manifest / 模块依赖，零新增权限 / 依赖 / 模块。工作区仍未 commit / push。
- 第四十二轮（§51，2026-11-07）：`12 §51`（含文首结论）、`09 §1/§7`、`16 §5.3`、新建
  `app/src/main/kotlin/com/example/cloudmusic/MediaKeyAction.kt` 与
  `app/src/test/kotlin/com/example/cloudmusic/PlatformContractTest.kt`、
  `app/build.gradle.kts`（仅加 test 依赖）、`tools/check_source_contracts.py`
  （清单 143→145 + 6 组 §50 守护 + 3 组签名 + 2 条 NOTE）、任务清单、日志、`MEMORY.md`。
- **§51 验证基线**：`./gradlew test` **366 测试 / 39 套件 / 0 失败 / 0 错误 / 0 跳过**；
  自检 **36 / 145 / 15** PASS；dev **6/6**；release **FAIL(C1, C4)** 正确阻断；
  guard / py_compile OK；19 模块 / 20 so / 11 权限 / 61 行 csv / 51 素材全部未变。
- **§42.3 的 T8 仍未收口**：`createDataRequestHandler()` 取流句柄、事件码常量表、
  真机 G01~G09、61 项素材全量替换、蓝牙 ACL 广播授权、DLNA 输出切换触发点，
  均需真机实证或用户决策；§51.4 已给出取流链路的逐条真机判据。工作区仍未 commit / push。
- 第四十三轮（§52，2026-11-07）：`12 §52`（含文首结论）、`09 §1/§7`、`16 §5.3`、新建
  `app/src/main/kotlin/com/example/cloudmusic/AppActionContracts.kt` /
  `PermissionRequester.kt` / `LocalSourceAdder.kt`、
  `app/src/test/kotlin/com/example/cloudmusic/AppActionContractTest.kt`、
  `app/.../MainActivity.kt`、`app/.../ui/CloudMusicApp.kt`、`app/build.gradle.kts`
  （仅加 `testImplementation`）、`tools/check_source_contracts.py`
  （清单 145→149 + 5 组 §52 守护 + 4 组签名 + NOTE）、任务清单、日志、`MEMORY.md`。
- **§52 验证基线**：`./gradlew test` **380 测试 / 41 套件 / 0 失败 / 0 错误 / 0 跳过**、
  `:app:assembleDebug` BUILD SUCCESSFUL；自检 **36 / 149 / 15** PASS；dev **6/6**；
  release **FAIL(C1, C4)**；guard / py_compile OK；11 权限 / 19 模块 / 20 so /
  61 行 csv / 51 素材全部未变。
- **§50.7 #3 已收口（应用侧）**：真机「曲库 0 首 / Room 夹具」前提移除，
  复验判据见 `12 §52.4`。**§50.7 #1（取流句柄）与 #4（G01~G09）未动**；
  蓝牙 ACL 授权 / 61 项素材替换 / DLNA 切换触发点仍需决策或真机。
  第四十二、四十三批工作区仍未 commit / push。
- 第四十四轮（§53，2026-11-07，**只读实证轮**）：`12 §53`（含文首结论）、`09 §1/§7`、
  `16 §5.3`、新建 `analysis/data-request-handler-evidence-2026-11-07.md`、
  `engine-legacy/.../EngineAdapter.kt`（**仅注释**，行为仍 `createDataRequestHandler() = null`）、
  `tools/check_source_contracts.py`（4 组 §53 守护 + NOTE + docstring，清单仍 36/149/15）、
  任务清单、日志、`MEMORY.md`。反汇编产物在系统临时目录，未入库。
- **取流句柄结论**：`§50.6` 的「`IBaseDataSource` 或等价句柄」方向收窄为
  **`SourceDataRequest` 子类实例**；`Start(JJJ)J` / `Stop()` 契约、
  `libNMCommonCache.so` 的 5 个 JNI 符号、`NMCommonCache` 加载链全部核实。
  **不缺类不缺库**，`null` 正是播放期零 GET 的原因。
- **§53 验证**：`check_source_contracts.py` **PASS 36/149/15**（含 so 二进制符号扫描）；
  **零生产行为改动 / 零构建基线变化**：19 模块 / 11 权限 / 20 so / 61 行 csv /
  51 素材 / 380 测试均不变；Gradle 本轮 NOT RUN（§52 基线仍有效）。
- **仍未实证**：`Start` 三个 long 的语义、`OnDataRequest*` 参数含义、
  native 是否额外按名查找 `C(JII)V` / `H()Z`。实现规格见报告 §4，
  分阶段真机判据 A~D 见 §5；**不得凭猜测实现**，否则只是再伪装一次 `DEEP RUN`。
  第四十二~四十四批工作区仍未 commit / push。
- 第四十五轮（§54，2026-11-07，取流句柄阶段 A/B 落地）：`12 §54`（含文首结论）、
  `09 §1/§7`、`16 §5.3`、新建 `engine-legacy/.../RangeFetcher.kt` / `UrlDataRequest.kt` /
  `RangeFetcherTest.kt`（7 用例）、`engine-legacy/.../EngineAdapter.kt` 接线、
  `engine-legacy/build.gradle.kts`（仅 test 依赖）、
  `analysis/data-request-handler-evidence-2026-11-07.md`（§2.5 / §3 / §4 / §5 更新）、
  `tools/check_source_contracts.py`（§53 守护改口径 + 5 组 §54 守护 + 3 组签名 +
  NOTE，清单 **149 → 152**）、任务清单、日志、`MEMORY.md`。
- **§2.5 追加实证**：`Start(j1=请求标识 / j2=offset / j3=length)`，
  `end=j2+j3`，`j1` 原样作 `OnDataRequest*` 首参；`Start` 立即返回 0、异步取流。
  报告 §3 的未决项收窄为 `OnDataRequestData` 第 2/3 个 long 与 End 的 String。
- **§54 验证基线**：`./gradlew test` **394 测试 / 43 套件 / 0 失败 / 0 错误 / 0 跳过**
  （380 + `RangeFetcherTest` 7×2 变体）；自检 **36 / 152 / 15** PASS；dev **6/6**；
  release **FAIL(C1, C4)**；guard / py_compile OK；19 模块 / 11 权限 / 20 so /
  61 行 csv / 51 素材 / 指纹全部未变。
- **§50.7 #1 阶段 A/B 代码已具备，仍未真机验证**：判据见报告 §5 / `12 §54.5`
  （`InitPtr` / `SetDataRequest` 是否成功、播放期是否出现带 Range 的 GET、
  `active tracks ≥ 1`、是否 `OnWriteNetData SKIP`）。**编译通过 ≠ 出声**，
  A3 保持 `DEEP RUN`、舱不冻结。事件码常量表 / 61 项素材替换 / 蓝牙 ACL 授权 /
  DLNA 切换触发点 / G01~G09 仍待。第四十二~四十五批工作区仍未 commit / push。
- 第五十五轮（§55，2026-11-07，**真机验证轮，零源码改动**）：`12 §55`（含文首）、
  `09 §7`、`analysis/a3-verification-runbook-2026-11-07.md`（§0 已更正）、任务清单、日志、`MEMORY.md`。
  重新 `./gradlew :app:assembleDebug` BUILD SUCCESSFUL（APK 20:54 / 30 MB，含 §54 代码），
  `zipfile` 校验 20 dex / 20 arm64 so 中取流类全部在位。
- **首次判断错误已纠正（用户指出「手机还是同一个」）**：`adb devices` 只列出乐视电视
  `Letv Y43`（API 19 / armeabi-v7a / `characteristics=tv` / `com.letv.*`），
  手机无线端点断开未重连；`adb connect 192.168.1.11:39525` → **Redmi K60
  `23013RK75C / mondrian` / Android 13 / API 33 / arm64-v8a**，完全兼容。
  所有设备命令带 `-s`，电视未被触碰；未切网络、未 `pm grant` 伪造授权。
- **签名不匹配 → 授权重装**：`apksigner` 对比旧包 `5474f219…` / 新包 `59f9c4cc…`
  （两套 debug.keystore）→ `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；用户授权后
  `uninstall` + `install` Success（21:05:49）。恢复 §50 测试夹具（新库保留
  `room_master_table`，`tracks/queue_items/playback_state=1/1/1`，明确标注为夹具），
  `adb reverse tcp:8765` + PC `python -m http.server 8765`，设备 curl → 200 / 37824 B。
- **首次实跑结果**：**阶段 A PASS**（native 全链 `JNI_OnLoad → PlaybackEngine →
  setDataSource → PrepareAsync → BufferSource open → openSource 1`，crash 空、
  无 CheckJNI / `NoSuchMethodError`）；**阶段 B FAIL**（`Play status 9` 后 PC 音源
  **播放期零 GET**、`0 active tracks`、MediaSession `state=2`、logcat 无
  `SetDataRequest` / `Request Start`）→ §53 类型结论正确，但**调用链仍缺竞品 Java 侧
  `NMBufferSource.SetDataRequest` 接线**（或 `UrlResult` 字段 / `SourceConfig.type` 判定走本地分支）。
- **下一步**（`12 §55.4`）：① 加诊断 `Log.i` 到 handler/Start/fetch 后 `install -r`
  （签名已统一、夹具保留）确认 native 是否调用；② 反汇编 `DataSourcePlatformImpl` 找
  `SetDataRequest` 接线；③ 校准 `UrlResult` / `SourceConfig.type`；④ 出现 GET 后才进 C/D。
- **仍未出声**：A3 保持 `DEEP RUN`、舱不冻结、指纹不回填，C/D 与 G01~G09 仍 NOT RUN。
- 构建基线不变：394 测试 / 43 套件、自检 36/152/15、dev 6/6、release FAIL(C1,C4)、
  19 模块 / 11 权限 / 20 so / 61 行 csv。第四十二~四十五批工作区仍未 commit / push。
- 第四十六轮（§56，2026-11-07，复核 + 真机诊断）：`12 §56`（含文首结论）、`09 §7`、
  `16 §5.3`（补 §55/§56 条目）、`02 §7.4` 口径澄清、新建
  `analysis/review-and-diagnosis-2026-11-07.md`、
  `analysis/a3-verification-result-2026-11-07.md §7`、
  `analysis/data-request-handler-evidence-2026-11-07.md §4 修正 + §7`、
  `EngineAdapter.kt` / `UrlDataRequest.kt` / `SourceCallback.java`（`CM-DataReq` 诊断探针）、
  `ResourceInfo.java` / `PlayerDataSource.java`（带参重载 + extension 校准）、
  `tools/check_source_contracts.py`（§50 P0-5 守护改前缀匹配）、任务清单、日志。
- **行尾归一**：11 个 M 文件被整体 LF→CRLF，已按 HEAD 统一回 LF，
  `git diff --stat` 由 +7111/-6065 收敛为真实 **+1061/-15**；未新增 `.gitattributes` 文本规则。
- **文档↔代码 9 项复核**：`01 §9` 权限 / `01 §5.1` SAF / `02 §4.3` 门面一致；
  修正 `evidence §4` Range 公式（j1 误作 offset）、`02 §7.4` 字节流措辞、`16 §5.3` 补录；
  登记 `01 §6.1`「默认仅 WiFi 播放」未实现（挂账）。
- **真机两轮实证**：证伪 §55.4 方向 1 —— smali5 的 `SetDataRequest` 仅见于
  `NMBufferSource.smali`，且 native **确实调用** `createDataRequestHandler()` 并成功建句柄；
  但 `getUrl()` / `Start()` 全程未调用，prepare 阶段 `BufferSource open` 后回调
  **`SourceCallback.onError code=1001`**，随后 `msg=300 arg1=9` / `Play status 9`。
  `ResourceInfo.extension=mp3` 校准实验未改变结果（假设排除）。
  播放期零 GET / 0 active tracks / MediaSession state=2 三项判据仍为否。
- **§56 验证基线**：自检 36/152/15 PASS、`./gradlew test` **394 / 43 套件 / 0 失败**、
  `:app:assembleDebug` BUILD SUCCESSFUL（两版）、APK 20 dex / 20 so 含诊断字符串；
  dev 6/6 与 release FAIL(C1,C4) 本轮未复跑；19 模块 / 11 权限 / 20 so / 61 行 csv 零变化。
- **下一步（按证据顺序）**：① 查 `onError(1001)` 的 native 码表；② 核对
  `PlaybackEngine(0L)` 的 `cache=0x0` 与 `CreateBufferSource` 前置条件；③ 核对
  `IDataSourcePlatform` / `IPlaybackPlatform` 空壳是否必须注册；④ 校准
  `UrlResult.fileSize=0` 与 REMOTE 分支；⑤ 播放期出现带 Range 的 GET 后才进 C/D、G01~G09。
  **仍未出声**，A3 保持 `DEEP RUN`、舱不冻结、指纹不回填。
  第四十二~四十六批工作区仍未 commit / push.
- 第四十七轮（§57，2026-11-07，阶段 A/B 首次通过）：`12 §57`（含文首）、`09 §7`、
  `16 §5.3`、`analysis/a3-verification-result-2026-11-07.md §8`、
  `analysis/review-and-diagnosis-2026-11-07.md §7`、新建
  `analysis/a3-phase-ab-evidence-2026-11-07.md`、
  `EngineAdapter.kt`（VFM handle / `resolveLength` / 真实长度 / release 释放）、
  `NMCacheBase.java`（`getNativePtr()`）、`AppBindings.kt`（`@ApplicationContext`）、
  `AndroidManifest.xml`（`usesCleartextTraffic="true"`）、
  `tools/check_source_contracts.py`（两处 DI 守护改前缀）、任务清单、日志。
- **静态实证**：`capstone` 反汇编 + ELF 符号表把 `onError(1001)` 定位到
  `NMBufferSourceAdapter::Open()` 的多个失败分支（通用码）；jadx 真类证明
  `PlaybackEngine(long cacheManagerHandle)` 需要 VFM 的 C++ 句柄。
- **四轮真机实验**：E1 传真实 `cacheManagerHandle` → `onError(1001)` 消失、
  首次 `getUrl` / `CreateBufferSource`，但 `size:0` → Memory Mode / `openSource 2`；
  E2 HEAD 探测失败（Android HEAD 的 `contentLengthLong` 恒 -1）；
  E3 加 `usesCleartextTraffic="true"` → HEAD 首次到达音源（此前 App 内 http 被拦）；
  E4 后台线程读 `Content-Length` + `ResourceInfo`/`UrlResult` 同填 37824
  → **`Start id=1 offset=0 length=37824`、播放期带 Range 的 GET、
  `Decoder Open input file success`、`openSource 0`**。
- **四阶段**：A PASS、B PASS（首次）、C 未通过（`OnWriteNetData Failed Not Support` ×15）、
  D 未确认（active track 不稳定、MediaSession 恒 `state=2`）。**仍未出声**。
- **基线**：自检 36/152/15 PASS、394 测试 / 43 套件 / 0 失败、assembleDebug SUCCESSFUL、
  C3 PASS（11 权限）；19 模块 / 20 so / 61 行 csv / 51 素材零变化。
- **下一步**：① 反汇编 `OnWriteNetData Failed Not Support` 分支与 `BufferSourceStrategy`；
  ② 校准 `OnDataRequestData` 第 2/3 个 long（`SKIP offset/size/file_offset_` 判据）；
  ③ 核对 `IDataSourcePlatform` / `IPlaybackPlatform` 空壳；④ 按 `13 §7.1` 补事件码状态机
  （`msg=300 arg1=4`、`msg=200/7/8/101/6002`）；⑤ C/D 通过后才跑 G01~G09。
  A3 保持 `DEEP RUN`、舱不冻结、指纹不回填。第四十二~四十七批仍未 commit / push.
- 第四十八轮（§58，2026-11-07，阶段 C 反汇编定位）：`12 §58`（含文首）、`09 §7`、
  `16 §5.3`、`analysis/a3-phase-ab-evidence-2026-11-07.md §7`、新建
  `analysis/a3-stage-c-analysis-2026-11-07.md`、`SourceConfig.java`（实验回退注释）、
  `EngineAdapter.kt`（探测健壮性 + 注释）、任务清单、日志。
- **反汇编实证**：`NMBufferSourceImp::OnWriteNetData`(0xbcac/564) 的
  `Failed Not Support` 唯一条件 = `this+0xb8` 为 0；构造函数(0x9164/1012)
  `strh #0x101` 初值置 1，只在 `0x942c` 的 Memory Mode 分支清零；
  条件为 `BufferSourceStrategy`(x5) bit0=0 或 `NMVirtualFile`(+0xd8) 为空。
  `Java_..._OnNativeDataRequestData`(0x347f4) 用末参 long 作 buffer size。
- **E5 证伪并回退**：`cacheLocal=true` → `NMSliceFile Open Failed. Root Dir Not Init.` /
  `Open failed(-10), local cache unavailable` / `size:-1` / 零 Start / `openSource 2`；
  `SetConfig` 有 `Dir Empty` 校验，`01 §6.1` 明确「缓存不落盘」→ 已回退 `false`。
- **现场网络问题**：`SocketTimeoutException: failed to connect to /127.0.0.1:8765`，
  设备 curl 仍 200；重设 `adb reverse`、换 `ThreadingHTTPServer`、改 GET/Range/
  显式 `responseCode`/8s 均无效 → App 进程侧可达性问题，非协议回归。
  代码恢复 §57 E4 的 HEAD 形式，只保留 `responseCode` + 8s 健壮性增强。
- **基线**：自检 36/152/15 PASS、394 测试 / 43 套件 / 0 失败、assembleDebug SUCCESSFUL、
  dev 6/6 + C3 PASS；19 模块 / 11 权限 / 20 so / 61 行 csv 零变化；本轮无生产行为变化。
- **第四十九批（2026-11-08，`12 §59`）路线 1 证伪 + kind 槽校准 + E9**：
  ① 竞品 `getResourceInfo()` 仅 4 字段构造、`libncmplayerengine` 硬编码拼
  `{"id","md5","size","type":"cache"}`（type 无分支）、策略 bit0 = cache config 的
  `cache_local` → §58 路线 1 前提不成立；② `OnDataRequestData` 首 long 是
  `OnNetSourceData(int kind,…)` 的 kind 槽（==2 跳过 `NMMemoryCache::Write`），
  `OnWriteNetData`/`OnFlushNetData` 经 GOT+lambda 异步调用（解开 §58"零调用方"矛盾）；
  数据包 kind 恒传 1（2 组 §59 守护）。真机 E9：prepare 全通 / 解码器打开 MP3 /
  AudioTrack 创建 / 首段 24576B 解码；尾段部分读恰逢 EOF 触发 Memory Mode FIFO
  管道永久错位（×7 → -103 → `doDecoder -5`），native 级缺陷、Java 侧不可修复。
- **下一步（唯一可行路线须拍板）**：路线 2 = `NMVirtualFileManager.SetConfig(<App
  缓存根目录>)` + `cacheLocal=true`，与 `01 §6.1`「缓存不落盘」冲突，**须用户拍板**；
  口径内备选 = LOCAL 文件源（适配器 `+0x48==0` 分支），但需先实证 native 对
  `content://` URI / `openLocalFd` 的处理（现 slot 返回 -1），成本更高、暂列观察。
  判据：不再 `Start Use Memory Mode` / `Failed Not Support`，播放窗口稳定
  `active track ≥ 1` 且 MediaSession 非 `state=2`。
- **第五十批（2026-11-08，`12 §60`）口径内 LOCAL 文件源真机链路打通**：DataStore 种子
  + 夹具切换 + LocalDriver 纯路径回退零代码打通（免权限免缓存），引擎 `CreateLocalSource`
  → `XorLocalSource`（NoneDecryptor=ret 空透传）probe/codec 对 mp3/wav 双格式全过，
  但解码首包即 EOF（`0xDFB9B0BB` → EOS，格式/大小无关）；排除解密器/AVIO 回调/Size/
  EncryptedType 变体，根因收敛到 libncmaudioplayer 的 source 包装层（裸 read(fd) 无
  位置串行化，与 E9 的 FIFO 错位同族）。注意：真实 SAF/MediaStore 曲目 localPath 是
  `content://`，届时需实现 `openLocalFd`（现 slot 返回 -1，已登记）。
- **60 批续（AudioSourceAndroid 排除）**：该类无 DataProvider JNI 字符串（非本地文件
  通路）；`CNCMAudioLocalSource`（fopen/fread 版）本库无虚表 = 死代码；解码器 AVIO
  opaque 直连源对象。**LOCAL 路线判死（该 so 版本）**：fd 顺序位置被流层预读与解码
  并发竞争，`SetSourceMode` 为空桩，无 Java 侧杠杆。
- **第五十一批（2026-11-08，`12 §61`）用户拍板批准路线 2 并落地**：
  `cacheLocal=true` + `SetConfig`（键名 dir/max_size/slice_size/cache_clean_range
  反汇编钉死）；真机 E11/E11b 缓存管线物理全通（切片文件 37824 字节落盘、
  三历史症状全消、E9 尾段错位消失）；docs/01 §6.1 口径演进批注落档。
- **61 批续（虚表布局错位发现）**：解码器 AVIO 回调期望 INCMSourceIO 布局
  （Seek@+0x28/Size@+0x50），而 NMBufferSourceImp 是 INCMSourceI 布局
  （+0x28=Size、+0x50=SourceID）—— seek 回调命中 Size()、假成功真不 seek →
  首包 EOF（`0xDFB9B0BB`）。0x70 包装类已排除（handler 控制块）。
- **下一步**：定论适配器产物（[x21+0x28]）与 decoder+0x128 的赋值链——若裸 imp
  直连，则竞品必有 IO 适配环节（候选：VFM 产品本身/ player 层包装），照它补齐
  即可打通解码；路线 2 缓存层已全通（无需再动）。
  判据不变：不再 `Start Use Memory Mode` / `Failed Not Support`，播放窗口稳定
  `active track ≥ 1` 且 MediaSession 非 `state=2`。
  判据不变：不再 `Start Use Memory Mode` / `Failed Not Support`，播放窗口稳定
  `active track ≥ 1` 且 MediaSession 非 `state=2`。
  **仍未出声**；A3 `DEEP RUN`、舱不冻结、指纹不回填。第四十二~五十批仍未 commit / push。

- **61 批续（E12 系列 + 灭屏冻结 + 缓存命中缺陷）**：解码循环在 prepare 即消费完
  整小文件并 EOS（1008ms/4704ms 即中止，Play 后至为空操作）；连续 5 轮被灭屏冻结
  打断（Dozing + GreezeManager，屏幕无法远程唤醒）；发现磁盘缓存命中路径读 0 字节
  （.nmsf 37824B 在盘但 readed_len:0）。下轮：无 force-stop 周期验证缓存命中；
  .nmsfi 提交差异；若索引未提交则评估 Stop 提交路径。缓存命中可用则播放免网络。
- **61 批末（缓存命中缺陷确认 + 设备离线）**：E11b 日志已复现缓存命中读 0 字节
  （索引加载成功、数据读失败 result:-2）；设备无线 adb 掉线（灭屏深睡）阻塞 .nmsfi
  分析。**需用户物理唤醒/解锁设备**后继续：拉取 .nmsfi 分析切片表 → 定位索引/数据
  不一致根因 → 修通缓存命中（播放免网络，免疫灭屏冻结）。
- **61.8（Java 侧修复落地）**：EngineAdapter 收到 msg=100（native 错误通知）→
  ERROR + 异步 releaseAllInternal（imp Close → 索引提交 + slot 清理）；修复"错误后
  slot 残留导致下次 prepare invariant 崩溃"与"索引不提交导致缓存命中读 0 字节"。
  E14 待设备重连：验证错误后索引提交 → 二次播放缓存命中（免网络出声）。
- **E14（缓存命中物理全通）**：免网络二次播放 `ReadFromFileNoLock readed_len:32768
  + 5056` 完整从磁盘缓存提供；`.nmsfi` 为 JSON 格式；`openSource 0` prepare 成功；
  解码仍 `-541478725` EOS —— **与源/数据管线完全无关**（三模式同点复现），
  100% 位于 CNCMFFDecoder 层。下轮从解码器内部定位（mp3 demuxer seek 行为、
  packet buffer、或换格式测试）。
- **E15（WAV 隔离实验）被灭屏冻结打断**：需保持亮屏重试。WAV 简单 demuxer
  若可解码则隔离 mp3 特定行为；若同败则确认 AVIO 胶水层通用缺陷。
- **61.12（首包 EOF 根因定论）**：`0xDFB9B0BB` 是 CNCMFFDecoder 内部 EOF 标记；
  mp3 demuxer find_stream_info 消费到 EOF 后 seek-back 未正确回退 → 首包 EOF →
  1008ms PCM 即中止。静态分析已到边界，需运行时调试。缓存管线（路线 2）已完整
  验证无需再动。修复方向：① 大文件测试（>4.7s，PCM 缓冲在 EOF 前填满）；
  ② 换格式隔离；③ 反汇编 openWithSource post-find_stream_info 的显式 seek。
- **E16（37.6s 长文件）推翻"文件太短"假说**：302592B (37.6s) 仍仅 1008ms PCM
  即停——Read(2)+ 返回 0。根因确认为 CNCMFFDecoder 的 decode loop 固有行为，
  与源模式/文件大小/格式无关。需运行时调试（gdb/插桩）定位
  ffmpeg demuxer 在该 AVIO 配置下的 packet 解析失败点。
- **🎉 E16b/E17 判据 D 出声确认**：引擎真机完整播放 mp3 —— AudioTrack Write +
  doRenderFast + PCM 进度 0→4132ms + doPlayCompleted + msg=2。两次独立复现。
  `-541478725 = AVERROR_EOF`（正常 EOF，非错误）。A3 A/B/C/D 全部 PASS。
- **E19（MediaSession routing + AudioTrack 活跃渲染确认）**：亮屏状态
  `Media button session` 正确设置 + `1 are active` —— 全链正常。灭屏时 MIUI
  GreezeManager 冻结 —— 产品需 FGS 保活（已有 mediaPlayback FGS）。下轮：
  灭屏播放测试 + MediaSession PlaybackState 更新（PLAYING 状态同步）。
- **🎉 E20 判据 D 四重验证确认（§61.18）**：亮屏 Awake 状态 Media button session
  设置 ✓ / AudioTrack 活跃渲染（1 are active）✓ / PCM 写入 ✓ / 进度 0→4363ms ✓ /
  doPlayCompleted ✓ / PlaybackState 回退 ✓。四轮独立复现稳定。**判据 D 出声确认。**
  下轮：① MediaSession PlaybackState 更新（PLAYING 同步）；② 灭屏播放测试；
  ③ exported=false 恢复；④ G01~G09 验收。
- **🎉 E21 三重同时验证（§61.20）**：Media button session 设置 ✓ +
  PlaybackState state=3 (PLAYING，61.19 修复生效) ✓ + AudioTrack 活跃渲染 ✓ +
  播放进度 0→4357ms ✓ + doPlayCompleted ✓。**判据 D 三重维度确认，A/B/C/D 全部 PASS。**
  下轮：① 灭屏播放测试（FGS 保活）；② exported=false 恢复；③ G01~G09 验收。
- **E22（完整播放周期确认）**：PlaybackState state=3 (PLAYING) 持续 ✓ +
  播放位置 0→4357ms（93%）+ ~4.5s 自然完成 + active tracks 归零 ✓。
  61.19 修复生效（MediaSession 正确显示播放态）。完整周期：prepare→decode→render→complete ✓。
  灭屏测试需更长文件（4.7s 在灭屏前已播完）。
- **🎉🎉🎉 E23（37.6s 完整播放成功 — 判据 D 不可争议确认）**：8 倍拼接 mp3
  (302592B) normal mode 完整播放 37.6 秒——位置 0→37478ms（99.2%）、零中断、
  自然完成（bufferFill EOS → doPlayCompleted）。**推翻"1008ms 即停"假说**——
  4.7s 文件太短（< PCM 初始缓冲窗口），37.6s 文件正常持续到末尾。
  **判据 D 不可争议确认。A/B/C/D 全部 PASS。无需进一步验证。**
