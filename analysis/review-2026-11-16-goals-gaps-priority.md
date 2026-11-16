# CloudMusic 目标 / 进展 / 设计-实现差距复核（2026-11-16）

> 结论先行：项目不是「什么都没做」，而是**后端链路（数据源、统一曲库、播放内核）基本打通，前端 1:1 复刻与歌词内容层几乎为零**；同时性能指标、稳定性判据和文档口径存在明显欠账。

## 0. 现场快照（只读核查）

| 项 | 实测 |
|---|---|
| HEAD | `e883f958`（2026-11-16 07:16，`docs: 61.162 T-V #9 全量含 WebDAV 冷扫实测 + 五项收口登记`） |
| 工作区 | 1 个未提交文件：`engine-opensource/.../Media3EngineAdapter.kt`（+32/-4，§61.163 `buildAudioSink` 注入） |
| 本地测试产物 | `64 套件 / 526 测试 / 0 失败`（由 `build/test-results/**/*.xml` 实际统计，与文档口径一致） |
| 文档断点 | `docs/12 §61.162`（第一百七十六批）→ `docs/17 §8.100` / `docs/09 §2` |
| 真机 | `adb devices -l` 为空；设备离线，所有真机项本次未复跑 |

## 1. 目标与背景（一页版）

- **产品目标**：Android 原生、离线优先的「统一虚拟曲库」播放器；本地 / WebDAV / strm / 播放列表在 UI 与队列里地位平等，用户不再关心歌曲来自哪里（`docs/01 §1`）。
- **硬边界**：仅 Android、仅 arm64-v8a、无账号 / 无云端 / 无后台服务、minSdk 26 / targetSdk 35、AGPL-3.0；范围锁定个人自用 / 侧载（D-146）。
- **原路线**：A 路线照搬竞品播放内核（`:engine-legacy` + 18 个私有 so）；2026-11-11 用户拍板改 **B2**：`:engine-opensource`（Media3/ExoPlayer + FFmpeg 软解扩展），`IMusicEngine` 14 方法门面不变（`docs/12 §61.80`）。
- **现行首要目标**：调通全格式播放内核 F1→F5（`docs/17 §8`），之后才回到 P2 播放页 1:1 → P3 八模块补齐 → P4 可选增强。

## 2. 当前进展（截至 §61.162）

**已闭环**
- 20 个 Gradle 模块；M1~M12 自研模块代码闭环；统一曲库 / 混合队列 / 跨源搜索与分组已打通。
- 本地 SAF/MediaStore、WebDAV（PROPFIND + Basic/Digest + Range 流式）、STRM（解析 + 去重 + 独立源 UI，§61.151）、m3u/pls 均为生产实现，非占位。
- B2 内核：F1 格式真相源、F2 扩展名推断、F3 格式矩阵（8/8 + enhanced 8/8）、F4-B2 验收、G08-B2 20min 长稳 5/5、APE 真机出声、WebDAV/STRM 真机全链闭环（92 STRM + 110 曲目落库，§61.158/§61.162）。
- gapless：SEQUENCE + 本地队列 ≥2 + 立即起播走 ExoPlayer 原生 playlist；真机 objective 实锤（同 AudioTrack session 4577 跨曲连续，§61.141）。

**未达标 / 阻塞**
- 冷扫：7,514 文件 216s、含 WebDAV 全量 467s，判据 ≤60s **FAIL 3.6~7.8×**；增量 1,051ms PASS。
- G09 crash→恢复 **PARTIAL**（前台/后台 `am crash` 均未自动重启，§61.162）。
- `pixel_level_1to1=False`；真机九页像素校准、真实封面实测、像素 diff 全部 NOT RUN。
- A3 仍 G09 PARTIAL、舱不冻结、指纹不回填；F5 待 A3 全绿后 ABI 回填。
- 无 `ContentObserver/FileObserver`，数据源变更依赖冷启动 / 手动 RESCAN。
- SFTP **0 行生产代码**；歌词 **0 行生产代码**；无竞品 / 网易云接口抓取逻辑。

## 3. 设计架构 vs 代码实现：关键差异

| # | 设计 / 文档原始口径 | 代码实况 | 性质 |
|---|---|---|---|
| 1 | `01 §7`/`02 §1`：播放页整页照搬竞品 `PlayerActivity` + 自定义 View + anim XML + Coil + Navigation + `:ui-legacy` | 无 `:ui-legacy`、无 Coil/navigation-compose；自研 `LegacyPlayerPage` + `VinylDiscView`；生产 `res/` 仅 2 个 values 文件，无 layout/drawable/anim；51 个素材放 `assets/legacy-player/` 直接读取 | **路线反转**，靠批注覆盖，正文未改 |
| 2 | `03-10 §2`：深色优先 + 跟随系统 | `CloudMusicTheme.kt` 固定 `darkColorScheme()`，`isSystemInDarkTheme` 未使用 | 明确差异 |
| 3 | `01 §7`：曲库首页为入口 | `CloudMusicApp.kt:146` `showPlayer=true`，启动即全屏播放页；无 Splash Activity | 首屏语义不一致 |
| 4 | `01 §3`：SFTP/FTP V0.2，歌词 V0.2 | 用户本轮要求提前到当前范围；代码侧两者均缺失 | **需求变更，需拍板** |
| 5 | `17 §8`：F4  BLOCKED（A 路线取流层） | `12 §61.92`：B2 口径 F4-B2 PASS；同一目标两份文档结论相反 | 口径分裂 |
| 6 | `NOTICE.md`：18 个 so 随 debug APK 打包 | `settings.gradle.kts` 已注释移出 `:engine-legacy` | 发布口径需核实 |
| 7 | PRD 冷扫 ≤60s | 467s FAIL | 验收未达标 |
| 8 | 「真机全链 PASS」 | 文档文字 + 静态守护为主，缺独立原始 logcat / SQL 计数证据 JSON | 证据链偏弱 |

## 4. 用户三点的逐条事实核查

### 4.1 UI / 交互 1:1 —— 判断成立

- 启动页：**无**。Manifest LAUNCHER = `MainActivity`，无 Splash；`analysis/l3-reference/competitor-launch.png` 只是参照图。
- 首页 / 播放页 / 个人中心 / 设置：均存在自研实现（`CloudMusicApp.kt` 1436 行、`CompeteUi.kt` 776 行、`LegacyPlayerPage.kt` 602 行、`VinylDiscView.kt` 303 行），但**不是 1:1**：图标多为字形占位，行与分组用通用 Comp 组件，播放页唱针为自绘近似，评论 / 音质标签 / 关注 / 在线人数等按「不造数」省略。
- 资源规模对照：竞品 65,974 条 `public.xml` / 6,683 layout / 2,232 anim / 1,754 语义 tag；本项目生产侧 0 layout / 0 anim / 51 个素材（约 0.06%）。
- 交互：无 Navigation / 返回栈，仅播放页横向切歌手势；固定深色；搜索结果与「我的音乐」行点击为空实现 `onClick = {}`。
- 硬阻塞：竞品标准竖屏主播放页 layout 未导出、20 个自定义 View 无 `onDraw` 源码、81 个混淆 attr 不可逆、MIUI 禁注入 ⇒ 无法自动取竞品播放页真机截图。守护文件 `analysis/l3-visual-replica-spec.json` 自己写着 `pixel_level_1to1: false`。

### 4.2 数据源 —— 部分不成立，需纠正

- **本地库**：已实现（SAF + MediaStore 双通道 + tag/封面扫描 + Room 9 表）。
- **WebDAV**：已实现（真实 OkHttp PROPFIND / Basic-Digest / Range 流；§61.158 三修复：挂载路径剥离、裸 `&` 转义、UTF-8→GBK 回落）。
- **STRM**：已实现（`StrmParser` 取第一行 URL、仅 http/https、`seenUrls` 去重、虚拟目录展开；§61.151 已补独立源 UI 入口）。
- **SFTP**：**确实零实现**——无 `datasource-sftp` 模块、无 factory、无 UI；`SourceType.SFTP` 只是枚举占位，`DataSourceModule` KDoc 写「V0.2 追加即可」。
- 统一曲库 / 混合队列：已打通（`Track.sourceType` + `storage_id/source_url` 列 + `DriverRegistry` 四源注册 + `QueueRepository` + FTS4 搜索）。
- 真正问题是**可用性 / 性能 / 证据**：全量冷扫 467s FAIL、无变更订阅、STRM 委托源对含音频目录会重复入库、WebDAV 删除源无 UI、`SourcesScreen` 移除源需 DataStore 手术（§61.151 自述）。

### 4.3 歌词 / 歌曲信息 —— 判断成立

- 全部 `src/main` 中 grep `歌词|Lyric|lrc|LyricView` **零命中**；`StrmPaths.defaultDownloadTypes` 中的 `lrc` 只是扩展名过滤。
- `LegacyPlayerPage` 无歌词容器；副控制行 `InfoGlyph()` 是只读几何占位；§61.109 明确「暂无歌词」占位行被移除。
- 歌曲信息来源只有本地 tag：`HeadTagReader`（ID3 头尾）、`AndroidMediaMetadataPort`、`JAudioTaggerFilePort`、`AndroidCoverWriter`。
- 全局 grep `music.163`、`/lyric`、`/song/detail`、`HttpURLConnection`、`openConnection` 在 `src/main` **零命中** ⇒ 「抓取竞品接口」不存在。原材料（疑似接口串）只散落在 `analysis/strings/classes*.strings.txt`、`analysis/apk/assets/default_link_config.json`，尚未转成任何生产代码。

## 5. 需要完成的功能点（按优先级重排）

### P0 — 先拍板，否则后面做多做少都无法验收

| 编号 | 事项 | 交付判据 |
|---|---|---|
| P0-D1 | 定义「1:1」的验收含义：A) 严格像素级（需竞品真机截图/录屏 + 未导出的 View 绘制代码，当前硬阻塞）；B) 视觉高保真自研（用 23 张已有基准图 + 色值/尺寸表做人工比对） | 用户选定 A/B 并写入 `docs/01`、`docs/17`，同步 `pixel_level_1to1` 判据 |
| P0-D2 | 歌词数据来源：本地 lrc/内嵌优先，还是允许在线抓取（离线优先 + AGPL + 个人自用范围下的合规风险） | 明确 provider、失败降级、缓存与法律口径；未定前不做网络抓取 |
| P0-D3 | SFTP 是否提前进 V0.1（原 PRD 为 V0.2） | 决定是单独立项还是维持预留 |

### P0 — 立即开工（不依赖设备在线）

| 编号 | 事项 | 交付判据 |
|---|---|---|
| P0-1 | **启动页 + 主框架对齐**：补 Splash（品牌色 + logo + 过渡），修正首屏为曲库首页或按 P0-D1 保持播放页首屏；建立 Navigation / 返回栈 | 冷启动截屏与基准图一致；返回键逐页可退出；九页 tab 可达 |
| P0-2 | **歌词与歌曲信息最小闭环**：core-model 歌词模型 + LrcParser（同目录 lrc / ID3 USLT）+ scanner 落库 + `LegacyPlayerPage` 歌词 View（与 `PlayerStateBridge.positionMs` 同步）+ `InfoGlyph` 改真实信息面板 | 至少一首带 lrc 的本地曲目逐句高亮；信息面板显示 title/artist/album/封面/时长/来源；无歌词时优雅降级 |
| P0-3 | **UI 基准证据补齐**：由用户用外部设备手动采竞品标准竖屏播放页 / 首页 / 设置截图（我不切换网络、不操作用户手机）；我把截图归档为 diff 基准 | `analysis/ui-replica/` 新增基准图与逐页对照表；`pixel_level_1to1` 有可核对依据 |

### P1 — 核心功能补齐

| 编号 | 事项 | 交付判据 |
|---|---|---|
| P1-1 | **SFTP 数据源**：新增 `datasource-sftp`（SSH/SFTP 客户端 + list/read/Range link + 私钥凭据不落日志）+ `DataSourceModule` factory + `SourcesScreen` 添加入口 + 契约/真机测试 | 添加 SFTP 源 → 扫描入统一曲库 → 跨源队列播放出声；凭据不出现在日志 |
| P1-2 | **冷扫性能优化**：WebDAV 逐文件 tag 是主导成本；按扩展/容量跳过网络 tag、批量元数据、索引缓存、后台限频 | 7,514 本地 + WebDAV 全量 ≤60s；增量 ≤10s 保持 PASS |
| P1-3 | **G09 crash 恢复**：查 START_STICKY / MIUI 自启动 / fresh-install 差异，补前台服务恢复与状态重建 | 前台 + 后台 `am crash` 后 15s 内自动恢复并恢复播放态 |
| P1-4 | **播放页剩余元素**：评论入口（如范围允许）、音质标签、cast、关注/心形交互从只读占位改真实动作；横向切歌之外的手势与转场 | 逐项在 §4.1 差距表上打勾，且不做假数据 |

### P2 — 稳定性与体验收口

- 数据源变更订阅：SAF/MediaStore `ContentObserver` + WebDAV/STRM 手动刷新入口，替代冷启动 / 手动 RESCAN。
- 数据源 UX：WebDAV 源删除、STRM 委托源重复入库去重（TrackId 策略或扫描过滤）。
- 真机听感与封面：gapless 主观听感、九页像素校准、真实封面实测、歌单首曲封面回归。
- A3 / F5：G09 转 PASS 后回填 ABI 指纹、冻结引擎舱、完成 `§61.33` Manifest 生产形态施工单。

### P3 — 工程债 / 文档口径

- 统一 P1★ 叙事：B2 下 F4 应写「A 路线历史 BLOCKED / B2 已 PASS」，消除 `docs/17` 与 `docs/12` 矛盾。
- 把 B2、20 模块、`:engine-legacy` 移出打包链写回 `docs/01`/`docs/02`/`NOTICE.md` 正文，不再只靠批注。
- 为 WebDAV/STRM 真机 PASS 补独立证据 JSON（原始 logcat 片段、flinger Active 行、落库 SQL 计数、设备与时间），把「文档声称」升级为可复核证据。
- 清理测试数字漂移（62/509、62/512、64/526 等），统一对外基线为 64/526/0 并标注历史快照不改写。
- 提交当前未提交的 `Media3EngineAdapter.kt` 改动（+32/-4），避免 §61.163 与工作区脱钩。

## 6. 我现在不能替你做的事

- 不切换 WiFi / 网络；真机采集和配对码由你操作，我只给判据并读结果。
- 当前 `adb devices` 为空，所有真机复跑（像素、冷扫、G09、SFTP）需设备在线窗口。
- 「抓取竞品接口」涉及在线服务与合规口径，须先由 P0-D2 拍板，不能默认开工。
