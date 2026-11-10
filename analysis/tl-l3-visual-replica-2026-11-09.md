# T-L L3 / `12 §61.42`：按竞品一手证据自研重画播放页 —— 交付、真机实证与 1:1 诚实挂账

> 2026-11-09 第六十九批。本报告是 `docs/12 §61.42` 的展开证据；口径冲突时以 `09 §2` 速查 →
> `docs/12 §61.42` → 本报告 → `analysis/l3-visual-replica-spec.json` 为序。

## 1. 用户口径演进（本批触发）

按时间线，用户三次钉死口径：

1. 「我说的照搬竞品，并不是一定要求连着代码一起搬。如果做不到，请严格参考竞品
   UI/风格和交互 自己实现一套。也就是说除了核心播放逻辑，其余全部自己实现」
   → **授权自研 UI**，核心播放逻辑保持不变，不再把「照搬 XML」当唯一路径。
2. 「总不能素材也不能搬过来吧？」＋「能复用的素材 一律复用竞品，避免产生色差」
   → **素材一律复用竞品**：图片 / 色值 / 尺寸值 / 字体全部直接取竞品导出产物，
   `docs/assets-mapping.csv` 61 行 `replaced=false` 保持，禁止自造颜色产生色差。
3. 「我要的是1:1 像素级复刻，还有底部的菜单栏是什么鬼？再次强调 1:1 像素级复刻！」
   → 目标升级为 **1:1 像素级复刻**；V0.1 五页 `NavigationBar`（曲库/歌单/搜索/
   队列/设置）不是竞品播放页组件，**不得出现在首屏**。

**执行口径（钉死）**：代码全部自研（不搬竞品自定义 View / 混淆 attr / Activity 粘合
代码）；素材一律复用竞品；严格参考竞品 UI / 风格 / 交互；核心播放逻辑
（`PlaybackRuntime` / native 引擎 / `PlayerStateBridge` 单通道）零改动；
未达成 1:1 时必须如实挂账，**不伪造完成**。

## 2. 竞品一手证据清单（单一真相源 `LegacyAssets.kt`）

| 类别 | 数量 | 取证来源 |
|---|---|---|
| 色值 | 14 项 + 5 项布局/真机色 | `values/legacy_colors.xml`、`layout/legacy_c5y.xml`(`#ff262626`)、`legacy_are.xml`(`#ff33333e` / 暖白文字)、`drawable/legacy_bn5.xml`(`#ffff3a3a` progress)、`legacy_bp1.xml`(`#ffff2c55` 红点)、真机启动屏六点采样 `#A22016` |
| 尺寸 | 20 项 | `values/legacy_dimens.xml`(`10.0dip`/`54.0dip`)、`legacy_are.xml`(30/36/40/28dip 控制)、`legacy_e0g.xml`(14sp / 38dip / 125dip / 37dip / 36dip / 22dip / 9dip)、`legacy_gr.xml`(44/40/54dip、18sp/12sp)、`legacy_bn5.xml`(3dip)、`legacy_h0o.xml`(8dip)、`legacy_bnx.xml`(18dip)、`legacy_coy.xml`(26dip) |
| 位图 | 19 项常量 | `drawable-xxhdpi/` 27 张光栅素材中语义已确认的唱片叠层 / 控制图标 / 默认封面 / 箭头 / 音符；证据 `analysis/l3-reference/legacy-icons-sheet.png`、`legacy-control-zoom.png`、`legacy-disc-layers.png` |
| 唱片叠层 | 5 层 z 序 | `layout/legacy_gc.xml`：`ga6` 底盘 → `g9f` 中心红盘(170dip) → `ga0` 黑胶环(margin 10dip)；`layout/legacy_bv5.xml`：`ga9` 外圈 → `ga8` 阴影框 |
| 字体 | 1 项 | `font/legacy_a.ttf`，name table 实证为 `"Dolphin Bold"` 文本字体（非图标字体） |
| 迷你条 | 完整规格 | `layout/legacy_e0g.xml` + `drawable/legacy_bnx.xml` + `layout/legacy_are.xml` |

色值 / 尺寸 / 位图路径集中在 `app/src/main/kotlin/com/example/cloudmusic/ui/player/LegacyAssets.kt`
（`LegacyPalette` / `LegacyMetrics` / `LegacyAsset` + `loadLegacyBitmap` /
`loadLegacyTypeface` / `rememberLegacyImageBitmap`），UI 层只从这里取值，不自造颜色。

## 3. 交付清单（零发布基线变更）

| 文件 | 改动 |
|---|---|
| `ui/player/LegacyAssets.kt` | **新增**。竞品视觉规格单一真相源：14 色值 / 20 尺寸 / 19 素材常量 / 字体 / assets 加载器 |
| `ui/player/VinylDiscView.kt` | **重写视觉层**。自绘色块 → 竞品真实位图，按 `legacy_gc.xml`/`legacy_bv5.xml` z 序重排；**保留 `§61.40` 全部 9 个属性动画参数标记**；修复 Compose `AndroidView` 首帧 0 尺寸导致唱片区空白（`MATCH_PARENT` 兜底 + `onAttachedToWindow()` + 幂等 `applySizes()`） |
| `ui/player/LegacyPlayerPage.kt` | **重写**。`#ff262626` 背景、竞品返回箭头、平台 `TextView` 原生 MARQUEE、竞品控制位图、`legacy_bn5.xml` 进度条色值、删除破坏视觉的「只读占位」说明文字 |
| `ui/CloudMusicApp.kt` | `showPlayer` 默认 `true` → **启动即全屏播放页**，V0.1 五页 tab 不进首屏；迷你条按 `legacy_e0g.xml` 还原 37dp 圆环 / 36dp 旋转封面 / 9dp 红点 / 14sp 跑马灯 / 竞品控制位图；底先铺 `legacy_are.xml` `#ff33333e` 再叠 `legacy_bnx.xml` 渐变，解决白色图标低对比度 |
| `ui/CloudMusicTheme.kt` | **固定深色主题**，色值全部取自 `LegacyPalette`，不再 `isSystemInDarkTheme()` 跟随系统 |
| `tools/l3_visual_replica_spec.py` | **新增**。13 项实证守护 → `analysis/l3-visual-replica-spec.json`，`pixel_level_1to1` 恒 `False` |
| `tools/check_source_contracts.py` | 新增 `§61.42` 长效守护（校验证据 / 基线 / 不得伪造 1:1） |
| `tools/l3_interaction_spec.py` | 修正 `UNWIRED_INTERACTIONS` 封面占位叙述（「中心色块占位」→「竞品真实位图叠层作默认占位」），与 `§61.42` 现状一致 |

**未改动**：`MainActivity.kt` 的 `PlayerStateBridge` 现场构造、`app/src/main/assets/`、
Manifest / 11 权限、DI 图、`settings.gradle.kts`、`libs.versions.toml`、任何
`build.gradle.kts`、`docs/assets-mapping.csv`、20 so / 指纹。

## 4. 真机实证（一手，非推断）

设备 `10.0.0.2:38061`（Redmi K60 `23013RK75C` / `mondrian` / Android 13 / arm64-v8a）。

- `adb install -r app-debug.apk` → `Performing Streamed Install / Success`；
  `am force-stop` + `am start -n com.example.cloudmusic/.MainActivity` 成功；
  `pidof com.example.cloudmusic` = **10208 存活**，`logcat -d -t 300` 过滤
  `FATAL|AndroidRuntime|VinylDisc` **零命中**（无崩溃 / 无 `ClassNotFoundException`）。
- `analysis/l3-reference/self-ui-player-v3.png`（本批）：深色 `#ff262626` 播放页；
  黑胶叠层（`ga6`/`g9f`/`ga0`/`ga9`/`ga8`）与白色唱针**可见**；竞品返回箭头、
  标题「未在播放」、`0 首`；`· 暂无歌词 ·`；红色进度条；底部循环 / 上一首 /
  播放 / 下一首 / 收藏全部为竞品白色位图；**首屏无 V0.1 五页菜单栏**。
- 对比 `analysis/l3-reference/self-ui-player-v2.png`（上一轮，唱片区**空白**）：
  0 尺寸修复生效，`VinylDiscView` 子 View 不再停在 0×0。
- 截图顶部 `com.example.cloudmusic.MainActivity` 一行是系统「显示当前 Activity」
  调试叠加层（MIUI 开发者选项），**非竞品组件、非 app 绘制**，不改 app 无法消除，
  如实记录、不伪装成 UI 缺陷或已修复。

## 5. 诚实口径：**1:1 像素级复刻未达成**

`tools/l3_visual_replica_spec.py` 把 `verdict.pixel_level_1to1` 固定为 `False`，
并由 `check_source_contracts.py §61.42` 守护「不得伪造成已达成」。本批达成的是
**「色值 / 尺寸 / 位图 / 控件顺序全部取自竞品一手证据的自研实现」**，
不含竞品自定义 View 的绘制代码与 XML 骨架。

**结构性缺口（5 项，与 `PIXEL_GAPS` 一致）**：
1. 竞品**无标准竖屏主播放页 layout tag**（`§61.41`：48 个闭包 layout 中 11 个非
   播放页误命中，主播放页由 Activity / Presenter 代码动态组合），无可逐像素对照的
   XML 骨架；
2. 20 个竞品自定义 View 的 `onDraw` / 状态机无源码（`§61.39`：aapt2 不校验 Java 类，
   XML 进 `res/` 必 `LayoutInflater ClassNotFoundException`）；
3. 81 个混淆 `app:*` attr 语义不可逆（`layout_constraint*` → `a5f`/`a5l`/`a65`/`a69`，
   重声明同名 attr 只分配新资源 ID，ConstraintLayout 读不到值）；
4. 无线 adb 受 MIUI `INJECT_EVENTS` 限制，`input tap` / `keyevent` 被拒，
   **无法自动切到竞品播放页取真机截图做逐像素比对**（属 USB / 手工在场硬阻塞，
   不是待办）；
5. 离线 Gradle 缓存缺 `constraintlayout` / `appcompat` / `material` / `recyclerview`，
   且 `app/src/main/res/` 未建立、`:ui-legacy` 未 include（`§61.40` / `§61.41` 基线）。

**真机截图上可见的剩余差距（本批新增，如实挂账）**：
- 中心红色唱片盘 `g9f` 当前按 `discSize*0.60` 呈现，视觉是一张**大红色实心盘**，
  与竞品「黑胶盘 + 中心较小专辑封面」的比例有差；`g9f` 是横屏黑胶变体
  (`legacy_gc.xml`) 的中心位图，不是竖屏主播放页封面，比例属自研近似；
- 唱针为白色圆角杆 + 圆形 pivot 的**自绘形态**（竞品唱针位图不在导出闭包内），
  形态与金属质感属近似；
- 「未在播放」「0 首」「· 暂无歌词 ·」是**自研占位文案**，非竞品文案；
  竞品歌词区 / 顶栏右侧图标（分享、评论、更多）未复刻；
- 控制行用 `Arrangement.SpaceEvenly` 排布、播放键 36dip 居中，与竞品竖屏页的
  精确间距 / 权重未逐像素对齐。

## 6. 达成 1:1 的唯一可执行路径

**必须由用户提供竞品播放页真机截图**：请在本机手动打开网易云音乐并进入任一
歌曲的播放页，然后告知本助手；本助手用 `adb screencap -p` 取 `1440x3200`
真机截图作为**逐像素比对基准**，再按截图逐项对齐自研页：

- 唱片区占比、中心封面直径与黑胶环比例、唱针角度 / 长度 / 位置；
- 顶栏图标集合与顺序、标题 / 艺人字号与颜色、歌词区文案与行距；
- 进度条高度 / 圆角 / 已播与缓冲色、时间文字位置；
- 控制行图标数量 / 间距 / 尺寸、底部留白；
- 背景渐变 / 模糊封面（若有）与状态栏沉浸式处理。

> 本批已额外尝试通过竞品 `AppwidgetPlayerRedirectActivity` 直达播放页，前台仍
> 回到 `com.example.cloudmusic/.MainActivity`，未取得基准截图；`input tap` 亦被
> `INJECT_EVENTS` 拒绝。故「自动取竞品截图」不可行，**不是待办而是硬阻塞**。
> 备选（代价高、仍需截图）：按 `§61.41` 三项授权补导其余 39 个 layout + 4 类依赖
> + `:ui-legacy` 舱体，但 20 个竞品 View 的绘制代码仍需按截图自研重绘，
> 且改发布基线不可逆 —— 三项授权**保留、暂缓**，不擅自动工。

## 7. 长效守护

- `tools/l3_visual_replica_spec.py`：**13 项全 PASS**（色值 14 / 尺寸 20 / 素材常量
  19 / 唱片叠层 5 层 / 主题 / 首屏无 tab bar / 禁止 API / 动画参数 / 单通道 /
  零基线）。证据 `analysis/l3-visual-replica-spec.json`。
- `tools/check_source_contracts.py §61.42`：校验工具 / 证据 / 4 个播放页源码在位、
  `checks` 全 True、`pixel_level_1to1 is False`、`pixel_level_gaps >= 4`、
  19 模块 / 11 权限 / 51 素材 / 61 行 csv 全 false / 零新增依赖 / `res` 未建 /
  `:ui-legacy` 未 include 任一漂移即 **FAIL**。
- `§61.38` / `§61.39` / `§61.40` / `§61.41` 守护仍全部 PASS。

## 8. 验证基线

| 项 | 结果 |
|---|---|
| `tools/l3_interaction_spec.py` | 14 项 PASS |
| `tools/l3_full_replica_audit.py` | 4 项 PASS |
| `tools/l3_visual_replica_spec.py` | 13 项 PASS |
| `tools/check_source_contracts.py` | **PASS（含 §61.42 新守护）** |
| `python -m py_compile`（2 个工具） | OK |
| `pre_release_check.py --mode dev` | **PASS（6/6）** |
| `pre_release_check.py --mode release` | **FAIL（C1, C4）正确阻断** |
| `./gradlew test :app:assembleDebug --offline` | **BUILD SUCCESSFUL in 22s**（254 tasks / 21 executed） |
| JVM 测试 | **419 测试 / 46 套件 / 0 失败 0 错误 0 跳过** |
| 真机 | 安装 Success / pid 10208 存活 / logcat 零崩溃 / `self-ui-player-v3.png` 唱片区可见 |

**零发布基线证据**：19 模块 / 11 权限 / 20 so / `assets/legacy-player/` 51 文件 /
`docs/assets-mapping.csv` 61 行全 `false` / 零新增依赖 / `app/src/main/res/` 未建立 /
`:ui-legacy` 未 include / assets·Manifest·权限·DI 图·指纹零改动。

**A3 状态不变**：G01~G08 PASS / G09 PARTIAL → 舱**不冻结**、
`engine_compat_test` **不回填**、Manifest 保持验收期调试形态。

## 9. 剩余断点与下一步（`docs/12 §61.42` 收口）

1. **需用户提供竞品播放页真机截图**（1:1 逐像素基准；无线 adb 硬阻塞，非待办）；
2. 拿到基准后按 `§6` 清单逐项对齐：中心封面 / 唱针 / 顶栏 / 文案 / 进度条 / 控制行；
3. `§61.41` 三项授权（第 20 模块 `:ui-legacy` / 联网补 4 类依赖 / 补导 39 个 layout）
   保留但暂缓；B2 主页面定位与 81 个混淆 attr 反查；20 个竞品 View 绘制重写；
4. `03-10 §6` 的 6 项交互仍**无生产后端**（模式 / 收藏 / 歌词 / 输出切换 / 队列弹窗 /
   封面），当前只读占位并显式标注，不伪造完成；
5. T-V 中需 USB / 大数据集 / DMR 设备的项（真机飞行模式、权限降级矩阵、冷扫增量、
   DMR T20/T21）；
6. G09 保持 PARTIAL（用户选项 3）；
7. A3 全绿后 ABI 验收回填 + 冻结舱 + `§61.33` Manifest 生产形态施工单。

## 10. 仓库状态

`§61.38` ~ `§61.42` 五批改动均**未提交、未 push**，`HEAD` 仍
`8c9d79b1 docs: D-146 项目范围定案为个人自用...`。本批新增
`analysis/tl-l3-visual-replica-2026-11-09.md` 属 `analysis/`，
**未新增 `docs/` 文件 → `09 §3` 索引与计数仍 30 个**（28 md + 1 csv + 根 `NOTICE.md`）。
