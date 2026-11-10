# T-L L3 · 播放页「UI 交互严格复刻竞品」落地报告（2026-11-08 晚，第六十七批）

> **一句话**：用户拍板「继续 UI 交互严格复刻竞品」后，先做完了 `§61.38` / `§61.39`
> 遗留的只读核查，得出关键结论 —— **竞品的交互逻辑不在 layout XML 里**（`12 §39`
> 已证实是纯代码属性动画），因此「严格复刻交互」与「把 9 个残缺 XML 搬进生产
> `res/`」不是同一件事。本批按反编译实证参数，在 `:app` 内**零发布基线变更**落地
> 可运行的播放页：19 模块 / 11 权限 / 51 素材 / 61 行 csv / 0 新增依赖全部不变，
> `./gradlew :app:compileDebugKotlin --offline` 一次通过。
>
> 证据：`tools/l3_interaction_spec.py`（纯 Python 零依赖，可复跑）
> → `analysis/l3-interaction-spec.json`（14 项检查全 PASS）。
> 依据 `12 §39` / `§61.38` / `§61.39`、`docs/14 §4/§4.1`、`03-10 §3.3/§6/§11`。

## 0. 触发与边界

用户「**继续 UI交互严格复刻竞品！！！！**」——对 `§61.38` 施工单的三项需拍板，
这至少回答了 ①「是否做」（做）与 ②「UI 形态」（竞品形态）。但 ③「是否建第 20 个
模块 `:ui-legacy`」、以及「是否允许联网补 4 类 AndroidX 依赖」仍未拍板，且都**改变
发布基线**（项目长期纪律：不擅自改变发布基线 / 权限 / 素材口径）。

本批因此选择一条**不需要任何拍板**的路径：按项目契约 `03-10` 已定的形态
（「播放页整页照搬竞品 View 体系，其余页面纯 Compose，局部混用限制在播放页一屏」），
用**自研 View + 平台属性动画**实现交互等价播放页，落在既有 `:app` 内。
它不是「XML 像素级照搬」的完成，也不伪造完成；后者如实挂账（见 §5）。

## 1. 关键实证：为什么「严格复刻交互」不等于「照搬 9 个 XML」

| 证据 | 结论 |
|---|---|
| `12 §39` / `docs/14 §4.1` | 唱片旋转 / 唱针起落**全部是纯代码属性动画**，不加载任何 `res/anim/` XML；XML 静态骨架里没有交互 |
| `§61.38` | 9 个已导出 layout 的语义名是**迷你播放条、横屏黑胶 item、播放页背景、横屏黑胶、唱片视图、横屏播放页、行车电台页、音频播放页**等**变体 / 子组件**，**没有标准竖屏主播放页** |
| `analysis/player-resource-closure.json` | 竞品共 **48 个**播放页相关 layout，当前只导出 9 个 |
| `§61.39` | aapt2 四步实证：id / attr / 45 项资源引用三类编译错误可机械消除（step4 全绿），但 **aapt2 不校验 Java 类**；20 个 `com.netease.*` View 无实现 → 运行时 `LayoutInflater` 必抛 `ClassNotFoundException` |
| 离线 Gradle 缓存核查 | `androidx.constraintlayout` / `androidx.appcompat` / `com.google.android.material` / `androidx.recyclerview` **均未缓存**；照搬原 XML 需联网拉依赖，改变离线构建基线 |

→ 若把 XML 原样搬进生产 `res/`：既**复刻不了交互**（交互在代码里），也**运行不起来**
（20 个 View 无实现），还要新建模块、联网拉依赖、改发布基线。
→ 正确路径：**按实证参数复刻交互行为本身**，零基线变更、可离线验证。

## 2. 契约合规性核对（`03-10` 决策 ① / `§11 #1` / `§3.3` / `§6`）

| 契约 | 要求 | 本批做法 | 合规 |
|---|---|---|---|
| 决策 ① | 播放页整页照搬竞品（View 体系 + 自定义唱片 View），其余页面 Compose；双框架混用限制在播放页一屏 | 播放页用 `AndroidView` 承载自研 `VinylDiscView` + 平台 `TextView`，其余 5 页保持 Compose | ✅ |
| `§11 #1` | **禁止用 Compose Canvas 重写唱片动画** | 视觉用平台 `GradientDrawable`，旋转用 `ValueAnimator` + `View.setRotation`；源码零 `Canvas` / `drawBehind` | ✅ |
| `§3.3` | **禁止用 `animateFloatAsState` 去「近似」参数**，参数照搬 | 参数逐项取反编译实证值，源码零 `animateFloatAsState` | ✅ |
| `12 §39` | 旋转 / 唱针用代码属性动画，不用 anim XML | 见 §3 | ✅ |
| `§6` | `PlayerStateBridge` 是唯一通道，播放页不得持有 `PlayerController` / `IMusicEngine` | 桥只依赖 `PlaybackRuntime` + `TrackRepository` + `QueueRepository`；`bridge` 源码零相关 import | ✅ |
| `§11 #4` | UI 不碰 Driver / Room / OkHttp | 桥只经 Repository Flow 与 Runtime 方法 | ✅ |
| `§61.38` 守护 | 含 `com.netease` 类名的 layout 不得进 `app/src/main/res/` | 本批不搬任何 XML，`res/` 仍未建立 | ✅ |
| D-146 | 竞品素材沿用、`replaced=false` 保持 | CSV 61 行全 `false` 零改动 | ✅ |

## 3. 已复刻的 7 项交互（参数逐项照搬，非近似）

### 3.1 唱片旋转（`VinylDiscView`）

竞品 `RotationRelativeLayout.init()` / `AnimationHolder.onAnimationUpdate()` 实证参数：

| 项 | 竞品 | 本批 |
|---|---|---|
| 值域 | `ValueAnimator.ofFloat(0.0f, 1.0f)` | 同 |
| 插值器 | `new LinearInterpolator()` | 同 |
| 重复 | `setRepeatCount(-1)` / `setRepeatMode(1)` | `ValueAnimator.INFINITE` / `RESTART` |
| 周期 | `yh0.m.b() = (long)(20000.0f / getPlaySpeed(...))` | `(20_000L / playSpeed)`，基准 **20000ms** |
| 角度 | `(getAnimatedFraction() * 360.0f + offset) % 360.0f` | `animatedFraction * 360f` |
| 目标 | `View.setRotation(...)` | `discContainer.rotation = ...` |
| 暂停 / 恢复 | 属性动画 pause / resume | `pauseRotation()` / `startRotation()` 续转不重置角度 |
| anim XML | **不加载** | 不加载 |

`mRotationOffset` 恒 0 场景等价；生产侧无倍速设置源，`setPlaySpeed()` 接口保留、
固定 1.0（`12 §61.40` 挂账）。

### 3.2 唱针起落

竞品 `PlayerVinylViewHolder` 的 `discHeadUpRotateAnimation` / `discHeadDownRotateAnimation`
两个 `ObjectAnimator`；本批 `animateNeedle(down)` 用 `ObjectAnimator.ofFloat(needle,
View.ROTATION, ...)`，抬起 `-28°` / 落下 `0°`，pivot 固定顶部，260ms。

### 3.3 其余 5 项

| 竞品 View | 本批实现 |
|---|---|
| `MarqueeTextView` / `MarqueTextView` / `PlayerLandScapePageMarqueeTextView` | 平台 `TextView` 原生 `MARQUEE` + `marqueeRepeatLimit=-1` + `isSelected=true` |
| `GestureImageSwitcher` / `CircleTouchView` / `PlayerDiscViewFlipper` | `detectHorizontalDragGestures` 透明覆盖层，72dp 阈值 → `onPrev` / `onNext` |
| `PlayerSeekBarNew` / `CircleProgressView` | Material3 `Slider` → `PlayerStateBridge.onSeek()` → `PlaybackRuntime.seekTo()` |
| 播放 / 暂停 / 上下曲 | `PlayerStateBridge` → `PlaybackRuntime.play/pause/next/prev` |
| `fragment_mi_miniplayer` → `PlayerActivity` | 迷你播放条 `clickable` → `showPlayer` 全屏页；整页 `onClose` 返回 |

`PlayerStateBridge` 按 `03-10 §6` 单向绑定：`state`（透传 `runtime.playerState()`）、
`track`（`combine(state, TrackRepository.observeAll())`）、`durationMs`（`Track.durationMs`）、
`playMode`、`queueCount`。

## 4. 6 项只读占位 —— 不伪造完成

`03-10 §6` 契约列出、但当前生产侧**没有真实后端**的交互，一律不接假动作：

| 契约项 | 生产侧缺口 | UI 形态 |
|---|---|---|
| `onModeToggle()` | `PlayerController` 只有 `setQueue(items, mode, startAt)`，切换会 `sync.push` 全量下发 native 队列，不擅自改变真机运行时行为 | 模式图标只读 |
| `onFavorite()` | `Track` 无收藏字段、无收藏 Repository | `♡` 只读 |
| 歌词 `MiLyricViewContainer` | 无歌词数据源 | 「· 暂无歌词 ·」占位 |
| `onOpenOutput()` | `OutputRouter.setActive` 生产调用方 0（`12 §48`） | 不显示切换入口 |
| `onOpenQueue()` | `03-10 §5` 队列弹窗属 V0.2 完善项 | 队列数量只读 |
| 封面 DraweeView 系列 | 未引 `coil-compose`（`12 §33` V0.1 口径） | 主色 `#FF3A3A` 中心色块 |

页面底部也显式标注「模式 / 收藏 / 歌词 / 输出切换为只读占位：生产侧无后端」，
避免把占位误读为已接通。

## 5. 范围挂账（未做，需另行拍板）

1. **48 个播放页相关 layout 中的其余 39 个**未补导（标准竖屏主播放页、横屏黑胶页、
   行车电台页、歌词页、播放列表 sheet 等）；当前 9 个只是变体 / 子组件。
2. **20 个竞品 View 的像素级视觉**（混淆 layout XML 未搬进生产 `res/`）。
3. **横屏黑胶页 / 行车电台页 / 音频播放页变体**的专属交互。
4. `§61.39` 的 aapt2 四步实证仍只在仓库外临时工程，未接生产 `res/`。

对应 `§61.38` 施工单遗留的三项拍板与升级条件：

| 拍板项 | 升级条件 / 代价 |
|---|---|
| 是否启用第 20 个模块 `:ui-legacy` | 改 `settings.gradle.kts`，19 → 20 模块，同步 `09 §3` 索引 |
| 是否允许联网补 4 类依赖 | `constraintlayout` / `appcompat` / `material components` / `recyclerview` 离线缓存均无，需预置 AAR 或联网，改变离线构建基线 |
| 是否补导 39 个 layout | 需扩展资源闭包审计 + aapt2 实证到更多 layout，避免用残缺素材冒充「严格复刻」 |

**本批明确不做**：不建模块、不改 `settings.gradle.kts` 的 include、不改
`app/build.gradle.kts` 的依赖、不搬任何 layout 进 `res/`、不改 Manifest / 权限 /
`assets-mapping.csv` / 51 素材 / 引擎指纹、不触碰设备。

## 6. 零发布基线变更证据（`l3-interaction-spec.json`）

| 项 | 值 | 变化 |
|---|---|---|
| 模块数 | 19 | 零 |
| 权限数 | 11 | 零 |
| 暂存素材 | 51 文件 | 零 |
| `assets-mapping.csv` | 61 行，全 `replaced=false` | 零 |
| 新增依赖 | 0 | 零 |
| `app/src/main/res/` | 不存在 | 零 |
| `:ui-legacy` | 未 include | 零 |
| 引擎指纹 / 20 so / APK 内素材 | 不变 | 零 |
| `EngineAdapter` / DI 图 | 未改（桥在 `MainActivity` 现场构造） | 零 |

`tools/l3_interaction_spec.py` 的 14 项检查：播放页 3 文件在位、旋转 9 个参数标记
精确、零 `Canvas` / `animateFloatAsState` / `drawBehind`、桥零引擎 import、入口已接线、
20 个竞品类名、基线 19/11/51/61、零新增依赖、`res/` 未建、`:ui-legacy` 未 include ——
**全部 PASS**。

## 7. 交付清单

| 文件 | 性质 |
|---|---|
| `app/src/main/kotlin/com/example/cloudmusic/ui/player/VinylDiscView.kt` | 新增，自研 View：唱片旋转 + 唱针属性动画 |
| `app/src/main/kotlin/com/example/cloudmusic/ui/player/PlayerStateBridge.kt` | 新增，`03-10 §6` 状态桥（只接真实后端） |
| `app/src/main/kotlin/com/example/cloudmusic/ui/player/LegacyPlayerPage.kt` | 新增，整页播放页（跑马灯 / 手势 / 进度 / 控制） |
| `app/src/main/kotlin/com/example/cloudmusic/ui/CloudMusicApp.kt` | 改：迷你条点击展开整页 + `bridge` 参数 |
| `app/src/main/kotlin/com/example/cloudmusic/MainActivity.kt` | 改：现场构造 `PlayerStateBridge` 并传入 |
| `tools/l3_interaction_spec.py` | 新增，纯 Python 零依赖实证工具 |
| `analysis/l3-interaction-spec.json` | 新增，14 项检查证据 |
| `analysis/tl-l3-interaction-replica-2026-11-08.md` | 新增，本报告 |
| `tools/check_source_contracts.py` | 新增 `§61.40` 守护 + 第六十七批 NOTE |
| `docs/12 §61.40`、`09 §2/§7`、`16 §5.3`、`analysis/task-backlog-2026-11-07.md` | 回写断点 |
| `.workbuddy/memory/2026-11-08.md`、`MEMORY.md` | 当日日志与长期断点 |

## 8. 复跑与验证

```
python tools/l3_interaction_spec.py              # 14 项全 PASS，刷新证据 JSON
python tools/check_source_contracts.py          # §61.40 守护 PASS
python -m py_compile tools/l3_interaction_spec.py tools/check_source_contracts.py
python tools/check-android-env.py --guard       # 零个人路径
python tools/pre_release_check.py --mode dev    # 期望 6/6
python tools/pre_release_check.py --mode release# 期望 FAIL(C1, C4) 正确阻断
GRADLE_USER_HOME=<isolated> ANDROID_HOME=<isolated> ./gradlew test :app:assembleDebug --offline
```

**A3 状态不变**：G01~G08 PASS / G09 PARTIAL → 舱**不冻结**、
`engine_compat_test` **不回填**、Manifest 保持验收期调试形态。
T-V 需 USB / 大数据集 / DMR 设备的项仍挂账。

> AI生成
