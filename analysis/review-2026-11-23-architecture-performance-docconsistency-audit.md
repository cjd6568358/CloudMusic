# 全项目复核改进报告 · 架构 / 性能 / 文档还原 / 实现差异（2026-11-23）

**触发**：用户「从架构设计 / 性能优化 / 设计文档还原 / 代码实现差异方面复核整个项目，出一份改进报告，以及后续如何推进开发」。
**复核基线**：`HEAD = 0824ae3d`（§61.317，第三百二十五批）；工作区干净；测试基线 **142 套件 / 1000 测试 / 0 失败**（docs/12 §61.317 勘误后口径）；guard **270 条 PASS**。
**方法**：① 状态盘点（docs/09 断点区 §61.308~§61.317 全读 + git log 近 25 提交）+ ② 三线并行源码复核（架构依赖图 / 性能配置实测 / 文档-代码一致性逐契约比对）+ ③ 关键 P0/P1 结论逐条一手抽查验证（file:line 复读）。本报告零源码改动。
**一句话结论**：**骨架资产高度健康**——分层、红线、契约还原度全部实测成立；真正的债务集中在两处：**「组合根/宿主」两个本应最薄的层发生引力塌缩**（PlaybackRuntime 1299 行 / CloudMusicApp.kt 2400 行），以及**两颗规模化性能雷**（CUE 分轨缓存 key 断裂 = 用户体感「大 CUE 仍卡」的结构性根因；整库队列切歌点查风暴）。两者都有清晰的修复路径，可在无设备窗口的修批中直接开工。

---

## 0. 现状快照（先对齐「我们站在哪」）

| 维度 | 状态 |
|---|---|
| 工程规模 | 21 模块（18 纯 JVM + app + core-di + engine-opensource）；≈270 个 Kotlin 源文件；100 个测试文件 |
| 播放内核 | B2 开源舱（Media3 1.11.1 + 自编译 FFmpeg 6.0 + 4 手写提取器），`engine-legacy` 已按 §61.307 c2 归档 `archive/`、出构建、活体引用清零 |
| Kernel 2.0 线 | §61.299 立项 → §61.300 四拍板（丙/①/c2/止血序）→ H0-1~5（§61.301/302）→ K1 六项（§61.303/304）→ K2 底座（§61.305）→ K3 保活（§61.306）→ K4 清退（§61.307/308）→ **K5 判读主体收口**（§61.309~316：K-01✓ K-02✓ K-05✓ K-09✓；K-06 Doze 属性、K-08 MIUI 限、K-10 无设备） |
| V0.4 线 | 01~08 代码侧全部落地 + 补批十轮（B01/B07 懒加载/重算/对比度/系统栏/状态保持/键盘/反馈）；自动可驱动判读 **18 项 ✅**；余用户在场项 U-11/15/17/20 |
| 最新止血 | §61.317 bufferMs 2000→15000 + cache warmer 全量预缓存（针对「大 CUE 播放仍卡」）——**本报告 P0-1 将证明该方案的缓存 key 设计没兑现其宣称效果** |

---

## 1. 架构设计复核

### 1.1 实测成立的部分（保持，不动）

| 项 | 结论 | 证据 |
|---|---|---|
| 分层与依赖方向 | 7 个数据源驱动全部退化为「契约 + 端口注入」纯 JVM 小模块，传输栈只在 core-di 组合根出现一次；`:app` 零直触实现模块依赖边 | 各 build.gradle.kts 全实读；app/build.gradle.kts:37-62 |
| 三条红线 | ① `IMusicEngine` 门面 **14+4 签名逐字零漂移**（EngineApi.kt:70-118 与 03-06 B2 §2 契约表一一对应，且经受 legacy→Media3 整舱换绑兑现）；② `@InstallIn` 全仓 8 处全在 :core-di；③ `com.netease.*` 活体引用仅存 archive/ | 手工逐方法核对 |
| DI 残绑 | InMemoryMusicEngine 生产残绑 **0 处**；EngineModule 唯一生产绑定 = Media3EngineAdapter（AppBindings.kt:71-73）；A 路线假回退开关已退役 | grep + 实读 |
| Link 四态分派 | `DataSourceAdapter.from(Link)` 穷尽 when 四分支、无 else 旁路；生产直接 new 实现仅 CastComposition.kt:57 一处且为端口正当实现；四态→EngineSource 映射与 02/03-06 双向一致 | DataSourceAdapter.kt:26-31；PlayerController.kt:910-927 |
| 行为对齐落点 | K1 全家落在独立小文件（KernelTuning 唯一参数出口 / ResolveLinkPolicy / PlayModeOrdering 表驱动），引擎舱未被行为逻辑污染 | feature-player 源码结构 |
| 纪律体系 | 每项决策带 § 批次锚点、guard A/B 反证、docs/12 登记——同类项目罕见的纪律水平 | 325 批连续可溯 |

**总评：分层架构 A 级、红线守护 A 级。**

### 1.2 架构债务（按严重度）

| # | 债务 | 位置 | 说明 | 建议 | 级 |
|---|---|---|---|---|---|
| A1 | **组合根引力塌缩：PlaybackRuntime 上帝类** | core-di/playback/PlaybackComposition.kt（1299 行） | 单类 ≥12 职责：扫描编排 + effects 循环 + 代理启停 + 歌单 CRUD 6 方法 + 收藏/历史 + ReplayGain 批分析 + 查重声纹 + 封面刮削 + DLNA 双循环 + DMR 音量进度 + 主题/淡入淡设置 + 缓存清理，共用一个 scope 和 _state，失效域互相放大 | 按域拆：ScanRuntime（ScanCoordinator 已可承接）/ LibraryFacade / DlnaSessionFacade，PlaybackRuntime 保留薄编排口；**需正式拍板**（与当年「不新建模块」决策同级） | P1 |
| A2 | **宿主镜像塌缩：CloudMusicApp.kt** | app/ui/CloudMusicApp.kt（2400 行） | 单文件承载应用导航状态机 + 8 整页 + 7 弹窗 + MiniPlayer；`CloudMusicApp` 单 composable **约 50 个参数**（:151-252） | 按 HomeScreen/FnLibraryScreen 已验证的拆分模式推进：页面拆文件、弹窗族下沉、回调聚合成 interface | P1 |
| A3 | cache warmer 裸线程 | Media3EngineAdapter.kt:443-470, 792 | 非 daemon、无受管中断（interrupt 只在 64KiB 块间隙生效、阻塞 socket read 期间不响应）、无法注入测试 | daemon + 命名受管执行器或 `scope.launch(IO)`；与 P0-1 同批处理（见 §3.1） | P1 |
| A4 | 驱动层复制粘贴临界 | SftpDriver.kt:141-184 vs FtpDriver.kt:135-178 | `readAll/cacheName/localLink/full/toObj/notReady/upstream` 七方法 **逐字级重复** + 同构落盘-校验-rename 流程；每新增 NFS 驱动约再复制 ~200 行 | datasource-api 提 `CachedRemoteDriver<P>` 抽象基类，两驱动只留端口差异——**NFS 开工前必须先做** | P1（NFS 前置则为 P0） |
| A5 | 服务定位器双轨 | DriverRegistry object（datasource-api）与 Hilt 并存；PlaybackRuntime 直插 put/remove（PlaybackComposition.kt:866-882） | 已登记的架构选择 | 冻结现状，禁止新增直引点 | P2 |
| A6 | Toast 收口不彻底 | MainActivity.kt 9 处 + LocalSourceAdder.kt:1 处仍在自建 Toast | §61.294 只收口了 ui/ 域 | showFeedback 升级为 app 级助手，11 处全部改走 | P2 |
| A7 | 默认值坑 | PlaybackComposition.kt:319-327 构造默认参数每次 new `OkHttpClient()` | 生产被注入覆盖，但测试/手动构造即踩 | 删默认值强制注入或提伴生字段 | P2 |
| A8 | 文案 when 双份 | FnLibraryScreen.kt:313-318 vs TrackInfo.kt:81-86（Availability→中文两套映射） | 枚举加值要改两处 | `availabilityLabel(short)` 单一真相源 | P2 |
| A9 | renderersFactory 重复构造 | Media3EngineAdapter.kt:332-356 vs 729-745 两处相同的匿名 DefaultRenderersFactory | 提私有工厂 | P2 |

**正向确认（无违规）**：全仓生产代码 GlobalScope 0 处、Thread.sleep 0 处、runBlocking 仅 HTTP 工作线程 1 处（BridgeModule 封面，P2）、测试线程均命名+daemon；PlayerController 内 4 处 `System.err.println` 为日志规范残留（P2，换 LogPort）。

---

## 2. 性能优化复核

### 2.1 已优化项（全部在位，带证据，防「重新发明」）

- **取流历史税全部机制性修复**：302 重定向税（redirectCache TTL 240s + 403/404 失效重解析 + 跨 host 剥鉴权头，OkHttpAdapters.kt:44-78）、63/64KiB-一-GET 结构税（顺序 Range 续泵，HttpDataSource.kt:42-69）、缓存 key 与回环 token 解耦（b2track:<trackId>，Media3EngineAdapter.kt:379——但见 P0-1）、OOM 防线（流式 skip / ≤8MiB 随机读闸 / 封面 ≤5MB）。
- **K1 行为契约全落地**：手动切歌 500ms latest-wins 防抖 + switchMutex、失败 5s 跳曲 + 连续 5 次护栏、通知元数据 500ms 防抖、resolve 重试 1 次 + 429 退避 2~6s + 100s 总超时、prepareSeq 竞态指纹（PlayerController.kt + KernelTuning.kt 单点常量）。
- **扫描**：两阶段（阶段 A 纯本地不等网络、500 条/批 upsert；阶段 B 白名单 8 扩展名 + 512MB 阈值 + 头 64KB/尾 128B Range 读）、stat+快照双增量、STRM directUrl 快照缓存、目录级封面/歌词 memo、Semaphore(4)×(8)、熄屏 Pacer。
- **UI**：四大主力页 LazyColumn 逐行发懒收口、分组/收藏计算 remember 化、封面 64 张 LruCache + 滚动复用 + 两级降采样。
- **OkHttp**：全局单例共享连接池 + WebDAV 短请求 scoped client（15s/30s/90s）。

### 2.2 新发现热点（本报告核心增量）

#### ★ P0-1 CUE 分轨缓存 key 断裂 —— §61.317 止血方案的结构性缺陷，用户「大 CUE 仍卡」的真根因

**证据链（逐条一手验证）**：
1. 缓存 key = `"b2track:" + source.trackId`（Media3EngineAdapter.kt:379、warmer 同 key :449）；
2. `EngineSource.trackId = track.id.value`（PlayerController.kt:921）；
3. CUE 分轨的 Track id 锚点为 **`<音频path>#<cue名>#<轨号>`，同一音频的 N 个分轨各自成行、id 互不相同**（scanner/TrackFactory.kt:22、ScanCoordinator.kt:492 `idPathOverride = "${audio.path}#$cueStem#${t.no}"`）。

**推论**：同一物理 APE 的 N 个分轨 = N 个互不相通的缓存条目。**切换分轨 = 缓存必然 miss → 整文件重下 + warmer 全文件重预热**。§61.317 注释宣称「后续 seek/切曲/同一镜像其他分轨全部走缓存（秒开）」（Media3EngineAdapter.kt:439-440）**按现实现不成立**。250 首级 CUE APE 曲库一次听完的流量 = 同一文件被完整下载 N 次，且 259MB 级文件多次整拷挤兑 256MiB LRU（SimpleCache）。

**判定**：这与用户两轮反馈「播放大 CUE 文件仍卡」直接吻合——bufferMs 15s 只治了「单轨内 rebuffer」，分轨间 miss 它治不了。**P0，下一批首选。**

**修复方向（供施工单细化）**：
- cacheKey 从 trackId 换轨为**物理文件锚定**：Track 携带物理媒体锚（CUE 分轨 = 父音频 path；非 CUE = 自身 path），key 如 `b2file:<storageId>:<物理path>`。非 CUE 语义不变（一对一），CUE 分轨共享父条目 → 分轨切换秒级命中；
- warmer 增加「物理文件已完整在缓存则跳过」（查询 cached spans，避免重复全量回读）；
- warmer 线程治理（A3）同批收口；
- 升级后旧 key 失效一次（LRU 自然淘汰，可接受），须在批次登记；
- 判据：JVM 用例钉 key 派生纯函数 + guard A/B 反证 + 真机远程 CUE 专辑连播 3 分轨仅一次整文件回源（cache 目录条目数/logcat 比对）+ **用户听感复验（设备窗）**。

#### ★ P0-2 整库队列切歌点查风暴（hasCueTracks / buildQueueSources）

**证据**：`prepareAt` 每次（含每次自动连播 ENDED→next）先 `queue.any { lookup.get(item.trackId)... }`（PlayerController.kt:782-784）——`lookup.get` 是 Room 点查（PlaybackComposition.kt:159-161）；无 CUE 分轨的队列 `any` 必须扫**整条队列**才返回 false。gapless 优待路径 `buildQueueSources`（:788, 867-887）同样逐项 lookup+resolve。7838 首级队列 = 每次换歌最多 7838 次串行 DB 点查（协程+SQL 每项 0.1~0.5ms → 最坏 0.8~4s/次）。专辑级队列（10~20 首）无感。

**修复方向**：队列变更（setQueue）时**一次批量预取**（Room `in` 查询取 cue 标志或直接用 id 锚点字符串形态判定——分轨 id 含 `#` 段，可零 DB 判定，但需拍板口径）缓存为 `queueHasCue` 标志；buildQueueSources 批量化同理。判据：JVM 用例（大队列 setQueue 后 prepareAt 零点查）+ guard。

#### P1（可合并为 1~2 个性能批）

| # | 问题 | 证据 | 修法 |
|---|---|---|---|
| P1-1 | **positionMs 500ms tick 引发全 App 重组**（根因）：player StateFlow 顶层 collectAsState（CloudMusicApp.kt:312），整个 2400 行宿主每 500ms 重组 | PlayerController.kt:327-392 节拍器 | 位置状态下沉到真正消费它的叶子（播放页 seek 条），迷你条不消费进度则不订阅；回调 remember 化 |
| P1-2 | 歌单页智能歌单 6 预设 × 全库 filter+sort **每 tick 重算** | CloudMusicApp.kt:1326-1333 + SmartPlaylist.kt:65-82 | `remember(allTracks, rule)` |
| P1-3 | 队列页 `trackList.associate` 7838 项 HashMap 每 tick 重建 | CloudMusicApp.kt:545 | `remember(trackList)` |
| P1-4 | 首页 `recentlyAdded()`（groupBy+sort+distinctBy 全库）每次重组重算 | HomeScreen.kt:121 + Track.kt:47-54 | remember 化 |
| P1-5 | play_history observe **无 LIMIT 无淘汰**（§61.284 只修了 UI 侧） | RoomDaos.kt:182-186 | observe 加 LIMIT 或入库侧裁剪 |
| P1-6 | saveCursor 每 5s **全量读 7838 行** queue_items 找 index | RoomRepositories.kt:303-318 | 按 track_id 单查 |
| P1-7 | 阶段 B 并发 = 每源 Semaphore(8)，4 源并存 32 并发 tag 读+WebP 编码与解码抢核 | ScanCoordinator.kt:566 | 信号量提到 run() 级共享 |
| P1-8 | observeAll 全量重查 × ≥2 活跃订阅（扫描 16 批 upsert = 32 次 7838 行重查+排序） | RoomRepositories.kt:37-47；MainActivity.kt:305-310；PlayerStateBridge.kt:61-64 | 投影下沉/按源增量（可选，先测） |

#### P2（顺手批或记账）

搜索无防抖 + FTS 命中时 LIKE 三列全表扫恒求值（RoomRepositories.kt:52-61）；FTS **每次冷启动 onOpen 无条件 rebuild**（LibraryDatabase.kt:82-86）；`wifiAvailable` 全仓无写方恒 true →「仅 WiFi 补元数据」开关形同虚设（ScanModels.kt:27）；runBlocking 于 DLNA 封面 HTTP 线程（BridgeModule.kt:185-191）；System.err.println 热路径 4+ 处；RangeInputStream 每读分配新数组（NanoHttpStreamProxy.kt:225-235）；LoadControl/TagReader 注释漂移（见 §4）。

#### 性能 SLO 状态（需设备窗实测兜底）

- **冷扫 ≤60s 未复测**：V0.4-08（2B 恢复远程 tag 补齐）以来无实测数据，且叠加 P1-7 并发结构；
- K-06（MIUI Doze 冻结音频管线）与 G09 同口径：非 App 缺陷，行业共性，按 §61.310 口径保持记录不设通过线；
- 500ms 重组链（P1-1~4）的真机 CPU/GC 占比待量化。

---

## 3. 设计文档还原 × 代码实现差异

### 3.1 还原度总评

| 文档 | 还原度 | 差异 |
|---|---|---|
| docs/02（架构）/03-01（Link 四态）/03-06 B2 段（门面/内核/取流） | ★★★★★ | 契约逐字一致，含兜底分支 |
| docs/07（错误码） | ★★★★☆ | 33 具名码零漂移；缺 4 码登记（下表） |
| docs/08（配置项） | ★★★☆☆ | 基础四组一致；滞后 5 项 |
| C-01~C-15 契约演进 | **15/15 全部在位且生产链活**，无空转桩 | 无 |

### 3.2 差异与真缺陷清单

| # | 类型 | 内容 | 证据 | 处置建议 | 级 |
|---|---|---|---|---|---|
| D1 | **代码真缺陷（数据丢失）** | `AudiofxSettings.fadeEnabled/fadeDurationMs`（AppSettings.kt:100/102）**不被持久化**：Codec encode/decode 只处理 enabled/effectLevel/bandsMillibel（AppSettingsCodec.kt:77-83/127-131）→ 用户开启淡入淡出后重启即失，而 UI 开关→生效链完整 | 一手验证 | Codec 补两字段 + round-trip 用例，修码批优先于修文档 | **P1** |
| D2 | 文档滞后 | docs/08 缺 audiofx 整组、缺 dlna.lastDmrUsn/lastDmrName、EngineConfig.bufferMs 仍写 2000（实际 15000）、§3.5 ftp 仍列「预留」（已是正式模块）、§3.4「私钥 passphrase 预留」与同表字段自相矛盾 | docs/08:152 等 | docs-only 批 | P2 |
| D3 | 错误码漂移 | E_SKIP_GUARD/E_LINK_TIMEOUT 借 `DsUpstream(...)` 壳，运行时实际码 = `E_DS_UPSTREAM_E_*`，与 03-06 宣称码名不符；E_SCAN_ORPHAN_SOURCE/E_SCAN_IMPORT 未入 07 且无 ErrorText 人话映射（落「扫描失败」兜底） | PlayerController.kt:618/737；ScanCoordinator.kt:135/298 | 拍板：正名（新增具名码）或承认借壳（改 03-06 措辞）+ 07 补登 + ErrorText 补映射 | P2 |
| D4 | 注释漂移 | Media3EngineAdapter.kt:481-485 KDoc 写「复播=2×bufferMs」，实码 :492-497 为等值；TagReader.kt:74 注释「生产 2」与实码（8 扩展名白名单）不符 | 一手核对 | 按文档纪律先修注释（docs-only 批） | P2 |
| D5 | 死代码 | **CompeteUi.kt：19 个 `Comp*` composable 仍全部零调用**（§61.287 口径现状成立；唯一活体 `rememberCompCover` 被 FeiniuUi/HomeScreen 生产引用） | 逐个点语法复检 | 抽走 rememberCompCover → 整文件删；与「Comp 令牌收敛为播放页专用」尚未施工的 V0.4-04 收尾合并批 | P2 |
| D6 | 文件内死分支 | ErrorText.kt:28 `"E_DS_IO"` 映射无生产写入方 | — | 留或删（删须同步测试） | P3 |
| D7 | 无害滞后 | settings.gradle.kts:101-111 尾注模块总表仍列已归档/未建模块 | — | 顺手批 | P3 |
| D8 | 注释级口径 | sandboxQueueSync（SandboxQueueSync 显式 no-op 记录器）为 §61.307-K4f 有意设计，非残桩 | PlaybackComposition.kt:169 | 留（已定案） | — |

**死代码扫描结论**：生产代码 TODO/FIXME/stub/NotImplementedError **零真残留**（「placeholder」命中均属封面占位语义）；五个 T-P 模块（engine-bridge/feature-player/output-dlna/output-localproxy/output-router）生产调方 4/10/10/6/7 处——**无僵尸模块**；InMemory* 家族均为有意保留的契约参考实现（KDoc 自证）。唯一 InMemory 生产绑定（InMemoryMediaControl/InMemoryOutputRouter）有明确 KDoc 拍板口径。

### 3.3 归档彻底性（K4 验收）

`archive/` 不被 include ✅；非 archive 活体代码零 `com.netease.*`/`playerengine.*` import ✅（assets/legacy-player/ 的 9 个 layout 为 D-146 证据留档，运行时不 inflate）；C1/C6 闸门已按 §61.307 换 B2 引擎自检 ✅。**Kernel 2.0 DoD（D1~D8）全部达成**。

---

## 4. 改进清单总表（汇总，按施工优先级）

| 序 | 级 | 类型 | 事项 | 建议批次 |
|---|---|---|---|---|
| 1 | **P0** | 性能/正确性 | CUE 分轨缓存 key 换轨为物理文件锚 + warmer 跳过已缓存 + warmer 线程治理（A3 合并） | R1（第 1 批） |
| 2 | **P0** | 性能 | hasCueTracks / buildQueueSources 队列批量预取 | R2 |
| 3 | **P1** | 数据丢失 | fade 设置持久化补齐 | R2（同批小项） |
| 4 | P1 | 性能 | 500ms tick 重组链（位置状态下沉 + P1-2/3/4 remember） | R3 |
| 5 | P1 | 性能 | play_history LIMIT + saveCursor 单查 + 搜索防抖/LIKE 收敛 | R4（可并入 R3） |
| 6 | P1 | 架构 | PlaybackRuntime 拆域（A1）——需拍板 | R6（V0.5 首批） |
| 7 | P1 | 架构 | CloudMusicApp.kt 拆页（A2） | R6+ |
| 8 | P1 | 复用 | CachedRemoteDriver 抽象（A4）——**NFS 开工前置** | R7 |
| 9 | P2 | 文档 | docs-only 对齐批：08 五项 + 07 四码 + 注释漂移两处 + settings 尾注 | R5（可随时插排） |
| 10 | P2 | 代码卫生 | Toast 全域收口（A6）+ System.err 换 LogPort + CompeteUi 清退 + B21 歌单展开态 saver | R8 |
| 11 | P2 | 性能 | wifiAvailable 接线（metadataWifiOnly 真生效）+ 阶段 B 全局信号量 | R4（同批决策项） |
| 12 | P3 | 观察项 | FTS onStart rebuild、RangeInputStream 分配、observeAll 投影——先测后动 | 记账 |

**明确不建议做的（防偏航）**：
- 不换底座、不动 IMusicEngine 门面、不新建模块（Kernel 2.0 红线延续）；
- 不做无实测依据的预防性重构（P3 项先量化）；
- 观察器双轨（A5）不强行收口——已登记架构选择，收益低扰动大；
- DSD/NFS/USB 独占等 V1.0 项维持版本归属，不提前热身（NFS 仅解除「先抽 A4」的依赖关系）。

---

## 5. 后续开发推进路线

> 编号接续：下一批 = **第三百二十六批（§61.318）**。每批零例外沿用项目纪律：全量测试计数（142/1000/0 起，只增不减）+ guard 新判据 A/B 反证 + docs/12 登记 + docs/09 断点推进。

### W1 · 修复批（无设备窗依赖，拍板后即可开工）

| 批 | 内容 | 判据要点 |
|---|---|---|
| **R1 CUE 缓存断链修复** | key 换轨 `b2file:<物理锚>`（scanner 给 Track 补物理锚 or 锚点派生纯函数，两者施工时拍板）+ warmer cached-spans 查询跳过 + 线程 daemon/受管化 | JVM 用例钉 key 派生；guard；旧 key 失效登记；真机验证并入设备窗（远程 CUE 连播 3 分轨仅 1 次整文件回源 + **用户听感复验**） |
| **R2 队列批量化 + fade 持久化** | hasCueTracks/buildQueueSources setQueue 时一次批量预取（或 id 锚点形态零 DB 判定）+ AppSettingsCodec 补 fade 两字段 | 大队列 prepareAt 零点查用例；fade round-trip 用例；guard ×2 |
| **R3 重组链治理** | positionMs 下沉叶子消费 + SmartPlaylist/titles/recentlyAdded remember + 迷你条 firstOrNull 收敛 | 重组次数前后对照（Compose compiler metrics 或真机 GPU profile 判读归设备窗） |
| **R4 持久交互杂项** | play_history LIMIT + saveCursor 单查 + 搜索防抖 + 阶段 B 全局信号量（每源 4×8→全局 8 拍板）+ wifiAvailable 接线 | 全量测试不回归；冷扫 SLO 复测**并入设备窗** |

### W2 · 设备窗收口（一窗全收，用户在场，约 30~60 分钟）

优先在 R1/R2/R4 之后开窗：

1. **大 CUE 听感复验**（R1 的最终判据——上两轮止血未达的用户体感闭环）；
2. V0.4-09 / K5 余项：U-11/15/17（走查）、U-20（Toast）、K-08（通知划掉手划）、K-10（蓝牙实车/耳机）；
3. K-03/04 设备 mock（SourceType 枚举注入设计）；
4. 冷扫 ≤60s SLO 复测（2B 恢复后首次）+ 20 分钟后台长稳（B2 内核口径）；
5. 500ms 重组链真机 CPU/GC 判读（R3 佐证）。

### W3 · V0.5 主线（W1~W2 战果落袋后启动，开工前拍板项少）

**结构治理四连**（顺序执行，每批独立可 revert）：
- R6 拆 PlaybackRuntime（ScanRuntime / LibraryFacade / DlnaSessionFacade，编排口保留）；
- R6+ 拆 CloudMusicApp.kt（页面逐文件化 + 50 参数聚合成 UiActions 接口 + 弹窗族下沉）；
- R7 CachedRemoteDriver 抽象（Sftp/Ftp 去重 ~180 行，为 NFS 铺路）；
- R8 卫生批（Toast 全域 + LogPort + CompeteUi 清退 + B21 展开态 saver）。

**功能选型**（基于 docs/01 §3 范围表剩余项，建议按此优先级拍板）：
| 候选 | 版本归属 | 依据 |
|---|---|---|
| 桌面小组件 / 通知栏自定义样式 | V0.2 余项 | PRD §13 #10 已立项未施工；R6+ 拆页后接入成本低 |
| NFS 挂载 | V1.0 | A4/R7 完成后即为「端口差异实现」小批 |
| DLNA 多设备记忆 / 指纹去重 / DSD / USB 独占 | V1.0 | 按需后置 |
| 素材替换（C4 61 行 replaced=false） | 发布门槛 | 个人自用不急；公开发布前一次性收口（AGPL/NOTICE 审计同批） |

### 验收口径（全线）

- 全量测试只增不减（当前 142/1000/0）；guard 只增不减（当前 270 条）；
- 门面 14+4 签名 diff 恒空；V0.4 判读战果零回归（U-01~U-21 已 ✓ 项不回退）；
- 每批 docs/12 登记 + docs/09 断点推进 + A/B 反证。

---

## 6. 结论

1. **这个项目的骨架价值已被三轮换核（A→B2→Kernel 2.0）反复验证**：门面 14+4 签名零漂移、C-01~C-15 全落地、四态分派无旁路、文档契约还原度 03-01/03-06 五星——「文档驱动」纪律是真实兑现的，不是姿态。
2. **当前不需要架构级手术，需要的是「拆两塌、修两雷、补一漏」**：拆 PlaybackRuntime/CloudMusicApp 两处引力塌缩（P1，V0.5 主线）；修 CUE 缓存 key 与队列点查两颗规模化雷（P0，可立即开工）；补 fade 持久化数据丢失（P1，小批）。全部路径清晰、风险可控、与既有红线无冲突。
3. **性能历史优化全部在位且可溯源**；新增热点全部集中在「规模化路径」（大曲库、大 CUE、长队列）——这恰好是真实使用（7838 首 / 252 分轨）与开发期小样本（单专辑）的差异面，建议以本报告 W1 批次 + W2 设备窗收口。
4. 文档侧仅 07/08 存在滞后型漂移，一个 docs-only 批即可归零；错误码借壳需拍板「正名 vs 承认」。

> AI生成
