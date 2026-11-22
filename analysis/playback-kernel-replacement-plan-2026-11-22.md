# 播放内核替换执行方案（lx-music-mobile 对标 · Kernel 2.0 线，2026-11-22）

> **定位**：用户 2026-11-22 指令「鉴于当前项目采用的逆向竞品播放内核方式极其不稳定，现在打算将播放内核切换为 https://github.com/lyswhut/lx-music-mobile 的内核；界面/交互/架构设计还是我们的」的正式评估与可执行替换方案。
> **登记**：docs/12 第三百零七批（§61.299，docs-only）；四个拍板项待用户裁决后生效（§10）。注：第三百零六批（§61.298，APE seek off-by-one 修复）**已收口登记**（140/989/0、guard 260）——本方案顺延取号，互不冲突。
> **一句话结论**：lx-music-mobile **没有可直接移植的独立播放内核**——它的播放栈 = TypeScript 管理层 + fork 版 react-native-track-player（Android 底层为 androidx.media3-exoplayer **1.8.0** 硬编码），与本仓 B2 引擎舱（Media3 **1.11.1** 官方制品直连）**同底座且我们版本更新**。物理换装等于「换回一个版本更旧、格式更窄、维护更快归档的同类底座」。真正该做的「替换」是三件事：**①底座收敛保持领先（K2）②播放管理层对齐 lx 五年打磨的行为设计（K1/K3）③逆向残留彻底清退（K4）**——本方案称 **Kernel 2.0 线**。

---

## 0. 结论导航（给决策者的 90 秒版）

| 问题 | 结论 |
|---|---|
| 现在用的还是逆向竞品内核吗？ | **已经不是**。竞品 so 内核（A 路线）2026-11-11 拍板换绑开源舱 B2（§61.80/81），§61.131 已移出打包链，现行生产绑定 = `Media3EngineAdapter`（`core-di/AppBindings.kt` 第 73 行）。但「逆向血脉」残留四层（§2.2），且近期播放故障全发生在取流桩 / 状态管线 / OEM 保活层——恰好是 lx 管理层深耕的领域（§2.3） |
| lx 的内核是什么？ | fork 版 react-native-track-player（锁 commit `d76fe8f`，RNTP v2 老 API 架构）+ 自维护的 `compiler SDK` 内 `def media3Version = '1.8.0'`。它自己**没有一行音频原生代码**（Android 原生模块只有歌词/缓存/加密/QuickJS 宿主/工具） |
| 直接搬过来可行吗？ | **不可行也不值得**（§4.1 判死五条）：RN 库脱离 RN 不可用、media3 1.11.1→1.8.0 版本倒退、格式覆盖倒退（19 格 → Media3 内建子集）、gapless/EQ/ReplayGain/CUE/淡入淡出全丢、lx fork 升 1.9.0 曾回退且官方上游已归档 |
| 那怎么"换"？ | **Kernel 2.0 = 行为对齐 + 底座收敛 + 残留清退**（§4.3），五批施工 K1~K5（§6），`IMusicEngine` 门面（14 + 4 增量方法）签名零改动，界面/交互（V0.4 战果）零影响 |
| lx 是稳定性银弹吗？ | **不是**。lx 官方 FAQ 登记了「息屏自动暂停 / 无法自动播下一曲 / 后台划卡清理仍播放」等已知问题；Android 播放不稳定是行业共性，靠行为工程治理，不靠换底座 |

---

## 1. 用户诉求还原

- **痛点**：播放链路不稳定（近十余批止血的持续体感），来源被归因为「逆向竞品播放内核方式」。
- **目标**：播放内核切换为开源项目 lx-music-mobile 的内核；**界面 / 交互 / 架构设计保持自研不动**。
- **边界**（本方案的硬约束）：`IMusicEngine` 门面零改动；`PlayerController` 之外的 UI（V0.4-01~08 已落地成果）零影响；模块边界与分层不动；`:output-dlna` / `:core-media` 契约不动。

## 2. 现状事实基线（先纠偏，再谈替换）

### 2.1 内核舱现状：替换其实已经走完半程

| 时间线 | 事件 | 证据 |
|---|---|---|
| 11-04~11-08 | A 路线（竞品 18 so + jadx 重建 Java 层）真机实证期：Memory Mode FIFO 尾段错位（§59.2）、`XorLocalSource` 并发 fd 竞争致首包 EOF（§60.3/60.5）、MIUI GreezeManager 灭屏冻结网络（§61.16 批 E12~E13）——「逆向内核极其不稳定」的真实出处 | `12 §59~§61`、`13 §3/§7` |
| 11-11 | **用户 17:44 拍板换绑 B2 开源舱**：`IMusicEngine` 单点换绑 `Media3EngineAdapter`，门面 14 方法签名零改动 | `12 §61.65~66`、`AppBindings.kt` |
| §61.131 | `:engine-legacy` 移出打包链（拍板粒度 A，git 历史保留）；真机 19 格格式矩阵复验「legacy 移除无影响」 | `settings.gradle.kts` 第 91~93 行、`12` 13924 行 |
| 现行 | 生产绑定 `Media3EngineAdapter` = Media3 1.11.1 官方制品 + **自编译 FFmpeg 6.0**（media3 官方 `decoder_ffmpeg` 模块，`build_ffmpeg.sh` 裁剪，NDK 27.1，arm64+armv7）+ **四个手写提取器**（ape/wavpack/asf/aiff，按 libavformat 格式事实手写）+ 官方 CacheDataSource/SimpleCache（§61.88）+ ReplayGain/EQ/SpatialWiden/淡入淡出/CUE 分轨包络/gapless 原生 playlist（§61.131/139/224/230/231/237） | `engine-opensource/build.gradle.kts`、`libs.versions.toml`、`Media3EngineAdapter.kt` |
| 基线 | 测试 **140/989/0**，guard **260 条**（§61.298 收口后）；V0.4-01~08 已落地，仅剩 09 设备窗口（U-01~U-21） | `docs/09 §2`、`analysis/v04-ui-ux-regression-plan-2026-11-21.md` |

> **诚实定性**：用户的「极其不稳定」体感是真实成立的，但成因分布在两个时代——11-11 前是竞品 so 本体的 native 结构性缺陷（已出链）；11-11 后的不稳定**没有一例归因于 Media3 内核本体**，全部在管理/取流/调度层（§2.3）。这正是本方案的价值主张：把「换内核」重新定义为「把管理层和残留治理到开源主线的成熟水准」。

### 2.2 逆向血脉残留清单（这才是「尚未切换完」的部分）

| # | 残留 | 文件/位置 | 危害 |
|---|---|---|---|
| R1 | 格式矩阵真相源仍以竞品 `libffmpeg.so` 为准 | `core-model/AudioFormats.kt` KDoc「客观边界：以 libffmpeg.so 实际编入的 demuxer + decoder 为准」；docs/17 §8.2 | 收录集与裁剪清单的权威来自逆向产物，而非自控 |
| R2 | 提取器论证口径反向引用竞品 | `WavpackExtractor.kt`「竞品 libffmpeg.so 的 .dynsym 里 wavpack 为 NO_SYMBOL」 | 设计叙事继续锚定竞品 |
| R3 | 03-06 权威内容仍以 A 路线为主轴（照搬舱/DataProvider 桥接/一次性 ABI 验收占了 §1~§6） | `docs/03-06` | 新成员/未来自查会按已废弃路线理解现状 |
| R4 | 构建闸门空转：C1 `check-engine-abi`、C6 `check-fingerprint`、R8 keep、ENGINE-FINGERPRINT 检查对象已出打包链 | `docs/03-12 §2/§4`、`tools/`、`assets/ENGINE-FINGERPRINT` | 门槛维护成本照付，检查语义失真 |
| R5 | 回退开关已断但文档/注释仍宣称可用：「C5 保留 :engine-legacy 回退」「恢复 include 即回 A 路线」 | `settings.gradle.kts`、`AppBindings.kt` 18~21 行 | 假逃生通道比没有更危险 |
| R6 | 仓内逆向资产占据工作区：`engine-legacy/` 全舱、`NeteaseCloudMusic.apk`（213MB）、`reverse-engine/`、`reference/`、`analysis/decoded*`/`analysis/jadx-*` | 仓库根 | 代码与素材边界模糊，发布合规闸门（C4/C6）语义与生产事实脱节 |
| R7 | P0 叙事「核心播放逻辑复用竞品二进制」仍是 17 号文档的显性章节（仅有更新指针） | `docs/17 §1` | 口径噪声 |
| R8 | `SandboxQueueSync`/`QueueSync` 的 native 消费者已不存在（A 路线 libnmplaylist） | `feature-player/QueueSync.kt` | 死代码 + 误导性契约 |

### 2.3 近期播放故障归因表（不稳定到底在哪层）

| 批次 | 症状 | 根因层 | 对应 lx 的经验域 |
|---|---|---|---|
| §61.177~179 | 播放链崩溃 + 曲库重建 | 应用层状态管线 / EngineThread | —— |
| §61.180~182 | 时长恒 00:00；假 PLAYING 无声；saveCursor 断链 | 真值管线 / 陈旧句柄防御 | 切歌串行化 + 状态回踩防御 |
| §61.252~255 | STRM/歌单无法播放（P0） | 链路解析 / 驱动层 / 孤儿源标记 | 取 URL 容错与竞态指纹 |
| §61.265~266 | OOM 闪退；HTTP 音源探测 OOM | 探测实现（有界 Range / 流式截断） | 点播时才取 URL、不做全量预读 |
| §61.268 | 签名直链无声（网络阻断） | 取流健壮性 → 回环代理 | 网络源失败退避与重试 |
| §61.269 | 每-Range 302 重定向税致卡顿 | 缓存/取流策略 | 缓存跟随重定向解析目标 |
| §61.211 / G09 | MIUI 自启动受限；灭屏进程冻结 | OEM 保活（行业共性） | 保活组合拳 + 电池优化引导 + FAQ 坦承同类问题 |

**结论**：管理层与取流层是「还在出血的层」。底层解码内核（Media3 + 自建 FFmpeg）在 19 格 × REMOTE/LOCAL 双路径矩阵上一线验证通过，**不是替换对象**。

## 3. lx-music-mobile 播放栈解剖（全部一手源码证据，2026-11-22 取自 master / v1.9.2）

### 3.1 四层结构（谁负责什么）

```
UI 层（screens/components，RN 0.73.11 + Redux）
  ↓ playList / playNext / playPrev
JS 管理层 —— src/core/player/player.ts（21KB「大脑」：队列语义/播放模式/换源/重试/超时兜底）
  ├ 取 URL：src/core/music/{online,local,download}.ts（音源 API + 用户自定义源脚本）
  └ 队列数据：playInfo / playedList(已播历史栈) / tempPlayList(稍后播放) —— 纯 JS 数组
RNTP 封装层 —— src/plugins/player/{index,utils,playList,service,hook}.ts
原生层 —— fork react-native-track-player 锁 commit d76fe8f（RNTP v2 API + Media3 后端）
自研原生模块（无任何音频代码）：lyric / cache / crypto / userApi(QuickJS 跑自定义源脚本) / utils
```

关键佐证：lx 的 `android/app/src/main/java/cn/toside/music/mobile/` 全目录为歌词/缓存/加密/QuickJS 宿主/工具五类，唯一原生音频依赖是 fork RNTP（经 `applyNativeModulesAppBuildGradle` 自动链接）；`app` 模块无任何直接 ExoPlayer/Media3 依赖。

### 3.2 关键事实十一条（附出处）

1. **「内核」= fork 版 react-native-track-player，且是 RNTP v2 老 API 架构**：`package.json` 锁 `github:lyswhut/react-native-track-player#d76fe8f`；该 commit = fork `v2` 分支 HEAD（结构仍为 Java `com.guichaguri.trackplayer`，v2.1.2）。官方上游 doublesymmetry/react-native-track-player **已归档**。
2. **Android 底层 = androidx.media3-exoplayer 1.8.0，硬编码**：fork `android/build.gradle` 原文 `def media3Version = '1.8.0'`。fork 演进史为一条自维护迁移线（1.1.1→1.2.x→…→1.5.1→1.8.0；升 1.9.0 后又 Revert）——**它自己也在为底座维护性挣扎**。
3. **底层播放器队列里永远只有 2 个 track（当前曲 + 无声哨兵）**：`src/plugins/player/playList.ts` 的 `buildTracks` 生成 `id = {musicId}__//{random}__//{url|default}`；`PlaybackTrackChanged` 命中 `default` 哨兵即视为「自然播完」由 JS 接管。**所有队列语义（歌单/稍后播放/随机历史栈）都在 JS 层**，native RepeatMode 完全不用。
4. **取 URL 三级容错 + 竞态指纹 + 双超时**（`core/player/player.ts`）：常规失败重试 1 次 → 429 限流随机 2~6s 退避重试 → `onToggleSource` 换源提示；`createDelayNextTimeout(5000)` 失败 5s 后自动跳下一首；`createDelayNextTimeout(100000)` 取 URL 总超时 100s；`gettingUrlId`（musicId+音源Id）比对防旧响应覆盖新曲目。
5. **切歌串行化 + 双层防抖**：`actionId + promise 链`保证切歌按序执行，外层 800ms 防抖（快速连切只执行最后一次）；通知栏元数据 `debounceUpdateMetaInfoTools` 500ms 防抖 —— 源码注释明言「解决快速切歌导致的通知栏歌曲信息与当前播放歌曲对不上的问题」。
6. **播放模式纯上层计算**：手动切歌时 `list/singleLoop/none` 一律强制按 `listLoop` 语义前进；随机模式带 `playedList` 历史栈（可回溯上一曲、且已删曲目同步清理）+ `randomNextMusicInfo` 预生成。
7. **初始化选项**（`setupPlayer` 原文）：`maxBuffer: 1000`（秒）、`maxCacheSize` 用户可设、`waitForBuffer`、`handleAudioFocus` 用户可关、`autoUpdateMetadata: false`（通知栏元数据全 JS 控制）、`audioOffload` 可开关——audio offload 曾引发「某些设备上播放不正常」，fork 最终去掉 `setIsGaplessSupportRequired(true)` 保留 speed-change 项。
8. **保活组合拳**：`PARTIAL_WAKE_LOCK + WifiLock` 由 `MusicManager` 服务层统一持有（网络曲播放时持、暂停即释；ExoPlayer `WAKE_MODE_NONE`）；`ACTION_AUDIO_BECOMING_NOISY` 广播 → 自动暂停；首次播放前主动检查通知权限 + 引导关闭电池优化；后台计时统一走 `react-native-background-timer`（同为 lyswhut fork）。
9. **通知划掉（RemoteStop）= 退出整个 App**；TimeoutExit 定时退出直接 `handleExitApp`——停止语义极简（治「后台划卡清理后音乐仍在播放」的投诉）。
10. **蓝牙/车机滚动歌词**：开启后 MediaSession metadata 的 `title` 写当前行歌词、`artist` 写「歌名 - 歌手」（fork 也为此修过「更新蓝牙歌词导致封面丢失」）。
11. **lx 没有的东西**（诚实对照，避免幻觉滤镜）：无淡入淡出、无真正的下一首预加载（依赖 maxBuffer 1000s 大缓冲近似）、无 EQ/ReplayGain/音效管线、无 CUE 分轨、无 DLNA、无多源挂载（WebDAV/SFTP/FTP/strm 均无）——**产品面比本项目窄一个量级**；且官方 FAQ 登记了息屏自动暂停、后台清理仍播放等已知不稳定问题。

### 3.3 与本项目 B2 舱的底座/能力对照

| 维度 | lx-music-mobile | CloudMusic B2（现行） | 判定 |
|---|---|---|---|
| 底座 | media3-exoplayer **1.8.0**（RNTP v2 fork，自维护迁移） | media3 **1.11.1** 官方 AAR 直连 | **我们更新且更标准** |
| 解码扩展 | 无 ffmpeg 扩展（默认平台 MediaCodec + 内建 Extractors） | 自编译 FFmpeg 6.0（官方 decoder_ffmpeg 模块）| 我们宽 |
| 容器覆盖 | Media3 内建子集（无 APE/WavPack/ASF/AIFF/DSF/DFF/TAK/DSD） | **19 格 × REMOTE/LOCAL 双路径真机矩阵**（§61.226 系列） | 我们宽得多 |
| 缓冲/缓存 | LoadControl 可配 + RNTP 磁盘缓存 API（SimpleCache 包装） | 官方 CacheDataSource + SimpleCache（§61.88） | 等价 |
| 队列 | 底层双曲+哨兵，语义全上层 | ExoPlayer 原生 playlist gapless（§61.131/139）+ Room 业务队列唯一真相源 | 各有立场（见 §6-K0 决策记录） |
| 音效 | 无 | EQ / ReplayGain / SpatialWiden / 淡入淡出 / CUE 包络 | 我们独有 |
| 歌词 | lrc-file-parser + 桌面歌词 + 蓝牙滚动歌词 | 自研 LRC + 逐字高亮（§61.242）+ AVRCP 封面（§61.249） | 大体等价，滚动歌词为 lx 独有小件 |
| 媒体会话 | RNTP service 自管 + autoUpdateMetadata=false | 平台 API（C1 限定，不引 media3-session）+ 03-07 契约 | 等价 |
| 管理层成熟度（切歌竞态/重试/防抖/保活/超时兜底） | **五年打磨，正是其资产** | 已修同类病灶（§61.182 等）但未形成整套行为契约 | **K1/K3 的移植对象** |

### 3.4 许可证与合规口径

- lx-music-mobile：**Apache-2.0 + 九条补充条款**（数据来源免责 / 版权数据 24h 清除 / 非商业 / 接受协议等）。补充条款约束的是**数据与使用行为**，不改变代码许可。
- 本项目为内网个人自用（D-146 口径）：引用其**行为设计**（不复制代码）无任何新增义务；本方案 K1/K3 全部为「按行为语义用 Kotlin 重写 + 契约测试钉住」，**不引入任何 lx 源码**。
- 若未来公开发布（项目 AGPL-3.0）：Apache-2.0 → AGPL 单向兼容无障碍；届时需重核 **FFmpeg LGPL 构建**与 **NOTICE 归属**（列 K4d 审计项，现状以仓内 LICENSE/NOTICE.md 为准）。

## 4. 替换路线评估（甲/乙/丙）

### 4.1 路线甲：物理移植 lx 内核（fork RNTP 的 Android 侧作为库引入）——**判死**

1. **技术上不可行**：RNTP 是为 React Native JS 桥造的库（`MusicManager` + `MusicService` + ReactContext/Promise 事件通路），脱离 RN 运行时无法实例化；剥离 RN 层 = 用 Kotlin 重写整个事件桥与控制面 —— 工作量 ≥ 直接用原生 Media3 重写（**而这件事 11-11 已经做完了**）。
2. **版本倒退**：1.11.1 → 1.8.0，放弃 androidx 官方主线一年以上的修复；lx fork 升 1.9.0 曾 Revert，接手等于维护一个死分支。
3. **格式倒退**：RNTP 不带 ffmpeg 扩展与补位提取器，19 格矩阵直接塌缩为 Media3 内建子集（APE/WavPack/ASF/AIFF/DSD/TAK 全没了）。
4. **能力丢失**：gapless 原生队列、EQ、ReplayGain、SpatialWiden、CUE 分轨包络、淡入淡出——lx 全部没有；DLNA/多源挂载/AVRCP 面完全重叠丢失。
5. **得不偿失**：真正值钱的管理层逻辑是 TypeScript，物理移植带不过来，能带过来的（ExoPlayer 实例管理）我们已有且更新。

### 4.2 路线乙：维持 B2 现状继续零散止血——**不采**

§61.163~§61.270 六十余批中二十余批为播放故障修复，每批只治当层出血点；残留清单（§2.2）不治理，逆向口径继续污染文档与闸门；防抖/竞态/退避等行为无契约钉住，回归风险随每批累积。

### 4.3 路线丙（推荐）：Kernel 2.0 —— 同源收敛 + 行为对齐 + 残留清退

**定义**：以 lx-music-mobile 为**行为蓝本**（不是代码蓝本），在保持自有门面/架构/UI 的前提下完成三件事——

- **底座收敛（K2）**：Media3 锁定上游最新稳定版、四提取器与 decoder_ffmpeg 同版对齐、格式矩阵真相源从竞品 so 换轨为自控双源；
- **行为对齐（K1/K3）**：把 §3.2 中 lx 验证多年的管理层行为（切歌串行化/三级容错/双超时/竞态指纹/元数据防抖/保活组合拳/停止语义）移植为 `PlayerController` / 取流层的行为契约，每条配 JVM 用例 + guard A/B 反证；
- **残留清退（K4）**：§2.2 R1~R8 逐条退役（处置方式见拍板 C），A 路线文档转历史档案，闸门改语义。

**红线**：① `IMusicEngine` 门面 14 + 4 增量方法签名零改动（2026-11-05 造的门面在 11-11 换核时已一次兑现「零改动换内核」的价值，本次继续兑现）；② V0.4 UI/交互成果零影响；③ 模块边界不动，不新建模块；④ 媒体会话仍由平台 API 主导（C1 限定，不引 media3-session）；⑤ 不引入 RN/JS 运行时。

## 5. 目标架构与模块落点

| 模块 | 本线动作 | 明确不动 |
|---|---|---|
| `:engine-api` | —— | 门面签名、`InMemoryMusicEngine`、`EngineSource`/`EngineConfig` 语义 |
| `:engine-opensource` | K2：media3 版本、四提取器 API 对齐、`build_ffmpeg.sh` 裁剪清单重审；K3：缓冲/LoadControl 参数对照 lx 校准 | 适配器行为骨架、缓存方案（CacheDataSource/SimpleCache）、RG/EQ/SpatialWiden 管线、gapless 原生队列 |
| `:feature-player` | **K1/K3 主战场**：切歌串行化+防抖、link() 三级容错+竞态指纹、播放模式语义核对、连续失败护栏 | `QueueRepository`/Room 真相源、`PlayerController` 状态机骨架、03-06 §9 契约 |
| `:engine-bridge` | —— | `DataSourceAdapter` 四态、Range/EOF 语义（回环代理与扫描的消费者不变） |
| `:core-media` / `:output-*` | K3 对照核对 + 设备窗判读项 | 焦点状态机、输出路由、DLNA 会话引擎（§61.193~202 战果） |
| `:scanner` / 数据源模块 | —— | 扫描编排、驱动契约（K2b 只改 `AudioFormats` 的**真相源口径与注释**，收录集零变更） |
| `:engine-legacy` | K4 处置（按拍板 C） | —— |
| `:core-di` | K4 回退注释放弃（R5） | 绑定结构 |
| docs/ | K4 重写 03-06 / 03-12 闸门语义 / 17 历史化 | 01/03-10 V0.4 权威口径不动 |

## 6. 批次施工计划（K1~K5）

> **纪律总则**（V0.4 线同口径，每批零例外）：`./gradlew test` 全量计数（当前基线 **140/989/0**，只增不减）+ `check_source_contracts.py` 新增 guard 判据并逐条 A/B 反证（改伪实现确认 FAIL 后字节恢复）+ docs/12 批次登记 + docs/09 断点推进。每批独立可 revert（回滚半径 = 单批）。

### K0（本批，已执行）：方案定稿 + 登记注册（docs-only）

计划文件（本文件）+ docs/12 第三百零七批 + docs/09 §2 断点 + docs/03-06 文首演进注（降级声明 + 指针）。**不开工施工，四项拍板（§10）落定前不动任何源码**。

### K1（代码批，无设备窗即可施工）：取流与切歌行为对齐

| # | 施工项 | 设计（lx 出处 → 本项目落点） | 判据要点 |
|---|---|---|---|
| 1a | 切歌串行化 + 防抖 | lx `actionId`+promise 链 + 800ms 防抖（§3.2-5）→ `PlayerController` 手动切歌代次护栏（复用 §61.285 扫描代次先例）+ **500ms 防抖**（比 lx 更灵敏，可否决参数 §10-E）；**自动连播（ENDED→next）不受防抖抑制** | 连点 3 次仅末次生效且序贯无交错 prepare；防抖不吞自动切歌 |
| 1b | `link()` 三级容错 + 竞态指纹 | lx 取 URL 容错（§3.2-4）→ `ResolveLink` 链：常规失败重试 1 次 → 429/限流随机 2~6s 退避 → 直链失效重签（`E_LINK_EXPIRED` 既有链 + §61.268 回环代理）→ 总超时 100s；`linkId`（trackId+源代次）竞态指纹防旧响应覆盖新曲 | 每级容错有独立 JVM 用例；指纹不匹配的迟到响应整链丢弃并入 failure_log |
| 1c | 自动跳曲节奏 | lx `delayNextTimeout(5000)`（§3.2-4）→ `autoSkipOnError` 路径加 5s 延迟（期间可人工干预），failure_log 照记 | 延迟期间收到人工 next 立即让位（不双切） |
| 1d | 通知元数据防抖 | lx `debounceUpdateMetaInfoTools` 500ms（§3.2-5）→ `publishMetadata` 发布口加防抖 + 竞态只保最新（治「快速切歌通知栏错乱」） | 防抖窗内多次发布只落最新一次；duration 同源（§61.185/186 战果）不回退 |
| 1e | 播放模式语义 | lx「手动切歌强制 listLoop 前进 + 随机历史栈可回溯」（§3.2-6）→ 核对 `PlayMode` 四态在手动/自动路径下的前进语义并补断言；`ShuffleOrder` 现状同 seed 可复现（T07）保持 | 手动 prev 优先历史栈回溯；已删曲目从历史栈剔除 |
| 1f | 连续失败护栏 | （lx 无，自设安全网）同一会话连续 ≥5 首 `autoSkip` 失败 → 暂停跳曲并置 UI 提示（可关） | 第 5 次失败停在半途状态，提示文案人话化（V0.4-02 口径） |

用例：每项配套纯 JVM 用例（`PlayerControllerTest` / 新 `ResolveLinkPolicyTest`）。guard 编号 `§61.299-K1a~f`（施工时按批顺延编号）。

### K2（构建批）：底座与格式矩阵收敛

- **2a 版本时间盒**（1~2 批）：media3 `1.11.1` → **当时最新稳定版**（施工时以 `maven.google.com/` 实测钉死，写入 `libs.versions.toml`；连同 `decoder_ffmpeg` 三件 `build_ffmpeg.sh / CMakeLists.txt / ffmpeg_jni.cc` 从**对应版本**的上游源树重新取齐——现取自 1.11.1，注释已注明）；四个手写提取器按新版 Extractor API 移植适配；AGP/compileSdk 联动评估（1.11.1 曾要求 compileSdk≥36，见 `libs.versions.toml` 头注）；**格式矩阵 19 格 × REMOTE/LOCAL 双路径全量重跑** + G02~G07 锚点复验。
- **2b 真相源换轨**（纯文档+注释批，**收录集零变更**）：`AudioFormats` KDoc 边界口径从「以 libffmpeg.so 实际编入为准（docs/17 §8.2）」改写为**自控双源**：`build_ffmpeg.sh` `ENABLED_DECODERS` ∪ Media3 内建 Extractors；F-01~F-08 登记（`reverse-engine/ContractTests` F 组）重定向到新真相源；`MIME_BY_EXT` 按 F3 真机实测回填 ape/dsf/dff/aiff/wv/tak 六格真实 MIME（现落 `OCTET_STREAM` 占位——「不编造」口径到期赎回）。
- **2c ffmpeg 裁剪清单重审**：`ENABLED_DECODERS` 从「F3 候选集（竞品对齐色彩）」按**真实曲库格式分布 + APK 体积预算**定稿，结果登记入册（现状参考：竞品 libffmpeg 6.5MB/ABI；自编译产物体积在 build.gradle 实测）。

guard：`版本串一致`（三处：toml / adapter 报文串 / decoder_ffmpeg 源注释）、`AudioFormats 注释不含 libffmpeg`、`四提取器在册`、`MIME 表无占位`（回填后）。

### K3（代码+文档批）：中断 / 保活 / 通知行为对齐

| # | lx 行为 | 本项目现状对照 | K3 动作 |
|---|---|---|---|
| 3a | RemoteStop=退出整个 App（§3.2-9） | 现状待核对 | 建议**不照抄**：改为「停播 + service 主动 stopSelf」，退出 App 交给用户；列拍板小项（§10-E） |
| 3b | WakeLock/WifiLock 服务层统一持有、网络曲暂停即释（§3.2-8） | FGS + WAKE_LOCK（B15）已有；WifiLock 持有策略待核对 | 核对补缺：网络曲`PAUSED` 时释放 WifiLock |
| 3c | BECOMING_NOISY → 暂停 | 已有（§41/§46，autoPauseOnDisconnect 开关 + DLNA 豁免） | 仅回归判读（K-09） |
| 3d | 首播前通知权限 + 电池优化引导 | 已有（§52 + §61.211 MIUI 引导） | 仅回归判读 |
| 3e | `autoUpdateMetadata=false` 全 JS 控制元数据 | 等价已达成（§61.185/186 同源链 + 1d 防抖） | 收口 |
| 3f | audio offload（曾致设备不正常，最终裁剪参数） | 未启用 | **不开**（音效管线 EQ/RG 必然失效，得不偿失）；登记反向决策 |
| 3g | 蓝牙/车机 metadata 滚动歌词（§3.2-10） | 有 AVRCP 封面（§61.249）+ 逐字歌词面板 | **暂不做**（§10-E 可否决项） |
| 3h | 灭屏/后台稳定性 FAQ 治理 | G09 PARTIAL 定性（MIUI 受限） | 判读项 K-07 只记录 | 

**K0 反向决策记录（防后人翻案不明白缘由）**：**不采用 lx 的「双曲+哨兵」队列**。lx 这么做是为了把队列语义全部留在 JS 层（换源简单、通知可控、回避向底层同步大队列）；本项目该职责已由「Room 业务队列唯一真相源 + 单向下发 + PlayerController」承担（03-06 §8），且 lx 哨兵方案**做不到原生跨曲 gapless**（§61.131），只能靠 maxBuffer 1000 秒的巨缓冲近似——我们保留 ExoPlayer 原生 playlist 路线。

### K4（仓务+docs 批）：逆向残留清退（§2.2 R1~R8 逐条）

1. **4a 引擎舱资产处置**（按拍板 C 执行）：`:engine-legacy/` 目录、`assets/ENGINE-FINGERPRINT`（双份）、A 路线工具脚本（`check-engine-abi.py` / `extract-engine-libs.py` / `check-fingerprint.py` / `engine_api_audit.py` / `engine_jni_*.py` / `spike1_engine_audit.py` / `check_jni_bindings.py` / `e14-cache-hit.sh` 等）。
2. **4b 假回退开关退役**：`settings.gradle.kts` C5 注释、`AppBindings.kt` 18~21 行回退说明改写为「A 路线已于 §61.299-K4 彻底退役，回退 = git 历史」。
3. **4c 文档权威化**：`03-06` 重写（B2 主轴：`Media3EngineAdapter` 映射表、现行线程模型、guard 索引；A 路线 §3/§5/§6 压缩为「历史档案」附录）；`03-12` §2/§4 闸门改语义——C1/C6 退役，替换为 **B2 引擎自检**（media3 版本一致性 / libffmpegJNI 存在性 / 四提取器在册 / NDK 锁定 / `check-no-netease-import` 例外清单清空）；`docs/17` §1 P0 章节加终局批注（§8.195 先例）。
4. **4d 开源归属审计**：`NOTICE.md` / `LICENSE` 核对（AGPL-3.0 + Media3 Apache-2.0 + FFmpeg LGPL 构建 + JAudioTagger LGPL 先例 + `decoder_ffmpeg` 三件来源声明），补齐缺失项。
5. **4e 仓内逆向资料处置**（并拍板 C）：`NeteaseCloudMusic.apk`（213MB）、`reverse-engine/`、`reference/`、`analysis/decoded*` / `analysis/jadx-*`。
6. **4f 死代码评估**：`SandboxQueueSync`/`QueueSync`（R8，B2 无 native 消费者）按 03-06 §8 现状注执行裁撤或降级为测试替身。

### K5（真机判读批）：全量回归并入 V0.4-09 设备窗（一窗全收）

执行清单沿用 `analysis/e2e-unified-device-window-checklist-2026-11-20.md` 增补 **K-01~K-10**（§61.282 先例）：

```
K-01  K2a 升级后 19 格 × REMOTE/LOCAL 双路径矩阵回放（强制项）
K-02  快速连点切歌 20 次：通知栏/播放页元数据不错乱、无双重 prepare、无状态漂移（K1a/1d 的判据）
K-03  直链 403/签名过期：重签成功或 5s 跳曲提示（K1b/1c）
K-04  限流/429 源（mock）：2~6s 退避重试仅 1 次（K1b）
K-05  G02~G07 锚点复跑（seek 单调 / 时长 / ENDED 交棒 / Range 回源）
K-06  G08 后台 20 分钟复跑（升级后）
K-07  灭屏 20 分钟 + G09 对照（MIUI GreezeManager 行为记录，不设通过线——行业共性，如实登记）
K-08  通知栏划掉行为（K3a 拍板后语义）
K-09  冷启动恢复：队列/进度/保持 PAUSED（saveCursor 三口链）
K-10  AVRCP/蓝牙实车或耳机实测（封面/按键/断连暂停）
```

全绿或如实登记 pending；判读产物归 `analysis/e2e-*` 证据链。

## 7. 验收判据总表（Kernel 2.0 DoD）

| # | 判据 | 验证方式 |
|---|---|---|
| D1 | 生产绑定唯一 = `Media3EngineAdapter`，仓内无 `:engine-legacy` 活体（按拍板 C） | `settings.gradle.kts` + `check-no-netease-import`（例外清单清空） |
| D2 | 全部权威文档不再存在「现行 = 复用竞品二进制」口径；A 路线内容仅存于历史档案节 | 全文检索 + 文档评审 |
| D3 | 格式矩阵真相源 = 自控双源；19 格双路径矩阵 K2 后全绿复跑 | F3 矩阵证据 + K-01 |
| D4 | K1 行为齐备且每条有 guard + A/B 反证 + JVM 用例 | `check_source_contracts.py` + 全量测试 |
| D5 | 测试基线只增不减（140/989/0 起）；guard 只增不减（260 条起） | `./gradlew test` + guard 计数 |
| D6 | 门面 14+4 方法签名零改动（diff 为空） | `tools/check_source_contracts.py` 签名守护 |
| D7 | V0.4 UI/交互成果零影响（U-01~U-21 判读不含 K 线回归项） | V0.4-09 与 K5 合并判读 |
| D8 | 每批独立可 revert；回滚演练一次（任选 K 批做 revert+重放） | git 操作记录 |

## 8. 风险登记

| # | 风险 | 对策 |
|---|---|---|
| X1 | K2a 升级引入回归（提取器 API 变化 / compileSdk 联动 / ffmpeg_jni 接口变化） | 时间盒批；升级单独成批可整体 revert；19 格矩阵强制重跑（K-01）；`decoder_ffmpeg` 三件与 media3 同版本对齐取齐 |
| X2 | lx 行为参数（防抖 500ms / 退避 2~6s / 总超时 100s / 跳曲延迟 5s）手感不适 | 全部为常量集中在单一 `KernelTuning` 对象，§10-E 可否决表，一次拍板可全局改 |
| X3 | K4 删档误伤 | git 历史保全 + 拍板 C 明确处置粒度 + 删除前先移入 `.temp` 清点校验 |
| X4 | 哨兵 vs 原生队列的双轨论争议 | §6-K0 反向决策记录定案（gapless 不可弃） |
| X5 | lx 补充条款被误读为代码引用障碍 | §3.4 口径：行为设计不受版权保护，零代码引用，无义务 |
| X6 | 设备窗口不可得 | K1~K4 全部无设备可施工；K5 随 V0.4-09 候窗，不阻塞 |

## 9. 与 V0.4 主线的编排

- V0.4 现状：01~08 代码侧全部落地 + 11 个补批，只剩 **09 = 设备窗口**（U-01~U-21）。
- **建议（拍板 D-②）**：K 线在拍板后立即开工（K1→K2→K3→K4 均无设备依赖），**K5 与 V0.4-09 合并为同一设备窗**，一次在场会话同时收 U-01~U-21 + K-01~K-10；拍板后在本计划 + v04 计划文件各补一行编排注（v04 文件仅加指针，不改其清单）。
- 备选（拍板 D-①）：先开设备窗收 V0.4-09（战果落袋），K 线从窗口后开始、K5 独立候第二次窗。缺点：两次设备窗。

## 10. 待拍板项

| # | 事项 | 选项 | 建议 |
|---|---|---|---|
| **A** | 替换路线 | 甲：物理移植 lx 内核 / 乙：维持现状散修 / 丙：Kernel 2.0 | **丙**（§4 论证） |
| **B** | K2a 底座升级 | ① 升至当时最新稳定版（时间盒+矩阵重跑）② 锁死 1.11.1 只做行为对齐 | **①**（lx 的教训正是「锁死老版本自维护」不可持续；但 ① 若升级回归面过大可降级为 ②，时间盒内自主裁决并登记） |
| **C** | 逆向资产物理处置 | c1：`engine-legacy/` + `NeteaseCloudMusic.apk` + `reverse-engine/` + `reference/` + `analysis/decoded*`/`jadx-*` **全部移出工作区**（git 历史保全）/ c2：仓内归档子目录降权 / c3：维持现状仅文档退役 | **c1**（工作区与逆向产物彻底切割，符合「内网自用 + 未来可能开源」的长期路径；APK 213MB 也减负） |
| **D** | 执行序 | ① 先 V0.4-09 收口再 K 线 ② K 线先行、K5 与 V0.4-09 合并一窗 | **②** |
| **E** | 默认参数与反向决策（可否决表，不否决即按此执行） | 防抖 500ms / 退避 2~6s / link 总超时 100s / 跳曲延迟 5s / 连续失败 ≥5 停 / 哨兵不采用 / RemoteStop=停播停服不退 App / audio offload 不开 / metadata 滚动歌词暂不做 | 按左列默认 |

## 11. 引用与证据清单

**lx-music-mobile（master @ v1.9.2，2026-11-22 检索）**
- 仓库主页 / README（技术栈、平台、协议）：https://github.com/lyswhut/lx-music-mobile
- `package.json`（RN 0.73.11；`react-native-track-player` 锁 `lyswhut/react-native-track-player#d76fe8f`；Apache-2.0）
- `android/app/src/main/java/cn/toside/music/mobile/`（原生模块清单：lyric/cache/crypto/userApi/utils，无音频代码）
- `src/plugins/player/{index,utils,playList,service}.ts`、`src/core/player/player.ts`（哨兵队列 / 三级容错 / 双超时 / 串行化 + 800ms 防抖 / 通知元数据 500ms 防抖 / setupPlayer 选项）
- fork `react-native-track-player`（v2 分支 = d76fe8f）：`android/build.gradle` `def media3Version = '1.8.0'`；commits 史（media3 迁移线 + 1.9.0 Revert + 蓝牙歌词 / offload 修复 / 缓存管理）
- 官方 FAQ（息屏自动暂停 / 后台清理等已知问题）：https://lyswhut.github.io/lx-music-doc/mobile/faq

**本项目（D:\workspace\CloudMusic@第三百零五批）**
- `settings.gradle.kts` §61.131 注释 / `core-di/AppBindings.kt` / `engine-api/EngineApi.kt` / `engine-opensource/build.gradle.kts` / `gradle/libs.versions.toml`（media3=1.11.1）/ `core-model/AudioFormats.kt`
- `docs/03-06 播放内核与队列.md`（A 路线全量契约与演进批注）/ `docs/03-12 构建发布与合规.md`（C1~C6 闸门）/ `docs/13-引擎舱实证复核.md`（A 路线实证史）/ `docs/17-竞品模块清单与复刻规划.md`（P0 基线与 §8.44 更新指针）
- `docs/12` §59~§61（A 路线 native 缺陷实证）、§61.65~66（换绑 B2）、§61.80/81、§61.131、§61.163~§61.297（近 60 批故障与止血史、现行基线 140/987/0、guard 259）
- `analysis/v04-ui-ux-regression-plan-2026-11-21.md`（现行 V0.4 主线及 09 待办）

> AI生成
