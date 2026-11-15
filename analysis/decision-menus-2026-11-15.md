# 拍板施工单 ×2（2026-11-15，§61.130 补记）——跨曲 gapless 队列化 + :engine-legacy 去留

> 四项拍板的决策材料至此**全部就位**（另两项：G09 注入=一句确认即执行；T5-3 立项=
> `analysis/audio-effects-capstone-plan-2026-11-14.md` T5-3 段已备）。本文件补齐
> 前两项的**可执行施工单**——拍板后照单施工，不需要再出设计文档。

---

## 一、跨曲 gapless 门面队列化施工单

### 1.1 现状与根因（§61.105④ / §61.119 实证）

- 门面 `prepare(source, config)` **单 source 契约**（03-06 §4 single-handle）→
  B2 适配器每次 prepare 构建**单 MediaItem** 的 ExoPlayer → 曲目切换 =
  release 旧 handle + prepare 新 handle → **播完到下一曲之间有间隙**。
- `GaplessPolicy.assess()` 已钉死可达性判据：`cross_track=false, blocker=
  single-handle 契约`（§61.105④ 一手）。

### 1.2 方案（推荐：ExoPlayer 原生 playlist 模式 + 门面增量方法）

ExoPlayer **原生支持 gapless 拼接**：多 MediaItem 加入 player.playlist 后，
同格式相邻曲目的解码→渲染无缝衔接（Media3 内部处理），无需自定义拼接逻辑。

**门面演进（增量方法，14+1 方法不动）**：
```kotlin
// IMusicEngine 新增（additive，M1 守护加法兼容）：
fun prepareQueue(handle: EngineHandle, sources: List<EngineSource>,
                 startIndex: Int, config: EngineConfig)
```

**B2 适配器实现**：
- `prepareQueue` → 构造 `List<MediaItem>`（每个 EngineSource → MediaItem）→
  `player.setMediaItems(items, startIndex)` → `player.prepare()`。
- 队列自然切换：ExoPlayer 自动衔接下一 MediaItem（**真 gapless**）；
- `onMediaItemTransition` 回调 → 更新 `_state.currentTrackId`（队列游标同步）；
- `seekToNextMediaItem` / `seekToPreviousMediaItem` → 门面 next/prev 直接映射。

**A 路线 EngineAdapter 实现**：`prepareQueue` = 循环调 prepare（退化语义）+
`QueueSync.push` 下发 native 队列（A 路线本就有队列）——回退兼容。

**上层层改造（最小化）**：
- `PlayerController.prepareAt(index)` → 当队列 ≥ 2 时调 `prepareQueue`、
  = 1 时退 `prepare`（保持单曲路径零回归）；
- `PlaybackRuntime` / `PlayerStateBridge` / UI 零改动（PlayerState 接口不变）。

### 1.3 施工次序与验收

| 步骤 | 内容 | 验收 |
|---|---|---|
| 1 | 门面增量方法 + 三实现 | JVM 契约测试 |
| 2 | PlayerController 队列 ≥ 2 分支 | 既有 509 测试零回归 |
| 3 | 真机注入 2+ 曲目 → 播完→下一曲 | **无间隙听感** + logcat 无 ENDED→BUFFERING 间隙 |
| 4 | GaplessPolicy.assess 更新 | cross_track=true 可达 |

### 1.4 依赖与风险

- **依赖**：无新依赖（Media3 原生能力）。
- **风险**：低——单曲路径零回归（分支保护）；ExoPlayer playlist 是官方主线。
- **两首不同格式曲目的 gapless**：ExoPlayer 对不同解码器的相邻 MediaItem
  仍有渲染切换间隙（同为 mp3 → 真 gapless；mp3→flac → 极小间隙）——如实登记。

---

## 二、`:engine-legacy` 移出打包链施工单

### 2.1 现状

| 项 | 值 |
|---|---|
| 模块 | `:engine-legacy`（Android library，19MB jniLibs/18 so + EngineAdapter + 84 JNI 真类） |
| 打包链 | `:app → :core-di → :engine-legacy → 18 so`（APK 含 18.5MiB legacy so） |
| 生产依赖 | **仅 DI 绑定回退语义**（AppBindings `EngineModule` 当前绑 `Media3EngineAdapter`）|
| B2 播放依赖 | **零**——Media3EngineAdapter 不引用 engine-legacy 的任何类/so |
| T5-2 分析依赖 | **零**——ReplayGainSpecMath（§61.123）已是分析引擎，LoudnessEvaluator 不再需要 |

### 2.2 移除方案（两种粒度供选）

**粒度 A（推荐）**：`implementation(project(":engine-legacy"))` 从 `:app` 与
`:core-di` 的 build.gradle.kts 移除 + `settings.gradle.kts` 注释掉模块声明
→ APK **-18.5MiB**；git 历史完整保留（`git log -- engine-legacy/`）。
- C2 扫描清单自动少一个目录（更干净）；
- `EngineModule` 的 fallback 注释同步更新（回退语义 = git 历史可恢复）。

**粒度 B（保守）**：仅从 `:app` 的依赖图移除（`settings.gradle.kts` 保留模块），
`:core-di` 保留（编译期检查但运行时不打包）——体积收益取决于 jniLibs 合并来源。

### 2.3 影响分析

| 项 | 影响 |
|---|---|
| B2 播放 | **零影响**（Media3EngineAdapter 不引用 legacy） |
| T5-2 分析 | **零影响**（ReplayGainSpecMath 替代 LoudnessEvaluator） |
| A 路线回退 | **丢失**（需要回退时从 git 历史恢复模块目录即可） |
| C1 check-engine-abi | 18 so → 0 so：脚本按打包集清单校验 → 需同步清单为 0 |
| C6 fingerprint | 同步（18 → 0） |
| APK 体积 | **-18.5MiB**（50.2 → ~31.7MB） |
| 12 条阻断 | #5 收口更干净 + #12 收敛 |

### 2.4 拍板后施工次序

1. `settings.gradle.kts` 注释 `include(":engine-legacy")` + `:app`/`:core-di` 移除依赖；
2. `check-engine-abi.py` / `check-fingerprint.py` 打包集清单同步为 0 so；
3. `./gradlew test :app:assembleDebug` 全绿 + APK 体积核对（~31.7MB）；
4. 真机装机回归（22 格矩阵关键格 ape/mp3/wma + G08 20min）；
5. docs/12 断点 + NOTICE.md 更新。

### 2.5 建议

**粒度 A**（彻底移出打包链）：B2 全格式真机闭环 + 长稳 PASS 后，legacy so
的播放职能已无必要；A 路线回退语义由 git 历史保留足够（本会话从未触发回退）。

---

---

## 三、施工单状态补记（§61.139，2026-11-15 第一百五十五批）

- **一.1 gapless 施工单**：步骤 1（门面增量 + 三实现，§61.131）已就绪；本批完成
  **步骤 2**（PlayerController 队列 ≥2 分支 + 索引同步 + next/prev 原生跳播）与
  **步骤 4**（B2 `prepareQueue` 传入真实 `GaplessPolicy.assess` 参量 → `cross_track=true`）；
  **步骤 3（真机 2+ 曲目无间隙听感）NOT RUN** —— 设备离线，不宣称已验收。
- 新增约束（施工单未列，正确性所需）：仅 `PlayMode.SEQUENCE` + 整队列本地音源 +
  `seekTo == 0` 的立即起播启用队列路径；随机/单曲循环/列表循环、带位置恢复、含
  网络或代理音源一律保持单曲 `prepare`，避免顺序语义错乱与「仅 WiFi」闸门被绕过。
- **二 `:engine-legacy` 去留**：`settings.gradle.kts` 已按粒度 A 注释 include
  （§61.131 拍板落地），本批无进一步改动；A 路线 `EngineAdapter` 同步补上两个
  增量方法的退化实现，保证将来从 git 历史恢复模块时仍可编译。
- 真机项（九页像素校准 / 封面实测 / gapless 听感 / 7,500 首冷扫）均待设备在场。

> AI生成
