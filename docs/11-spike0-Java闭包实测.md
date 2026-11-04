# 11 · spike #0 · 引擎舱 Java 层闭包实测

**日期**：2026-11-05
**触发**：`10-技术架构评估.md §2 R1` 把「jadx 反编译引擎舱 Java 层的实际闭包大小」列为唯一可能推翻 A1 的未知数，并要求作为编码第一件事，时间盒 1~2 天。
**方法**：本机确认无 jadx，但 `analysis/decoded/classes*.dex` 20 个 dex 齐全。jadx 的价值是还原源码后看 `import`；而**类引用闭包在 dex 层是精确可得的**——`class_def` 的 superclass / interfaces / fields / method protos，加上 `code_item` 里 `const-class` / `check-cast` / `new-instance` / `iget*` / `invoke*` 等指令引用的 type / field / method id，共同构成有向引用图。本轮用纯 Python 直接解析该图做 BFS，得到与 jadx 等价、且包含签名与字节码引用的闭包。
**脚本**：`tools/spike0_java_closure.py`（零第三方依赖，Windows/Linux 可跑）
**原始数据**：`analysis/spike0-java-closure.json`（6.9 MB，含全部闭包类清单、stub 边界清单、native 类清单）、`analysis/spike0-run.log`
**状态**：**实测完成，结论需用户拍板**（见 §6）；本文件同时作为本轮编码进度的存档点（§8）。

---

## 1. 结论先行

> **A1「照搬 `playerengine` Java SDK 子树」按原定义不成立。** 从 4 个 JNI 入口包族的 84 个类出发，**无裁剪的可达闭包为 135,954 个竞品自有类，占全 App 178,120 个类定义的 76.3%**，深度 21 层，并大量拖入在线业务、直播、IM、支付、启动框架等包族（业务耦合信号 16,091 个类）。这远超 `10 §2 R1` 建议的「≤ ~150 个类且不拖入 redux / 在线业务」判据。
>
> **但入口面很窄**：直接依赖（depth 1）只有 **168 个类**；舱内必须搬的 JNI 类只有 **84 个**，一层边界需 stub / 替换的类 **168 个**。闭包在 depth 3 之后爆炸（4,991 → 14,688 → 24,898），说明耦合是**间接传递**而非内核本身复杂。
>
> 因此判据应理解为：**「整舱照搬」被否决，但「薄门面 + 边界 stub」仍有可行空间**；是否值得做，取决于 `10 §2 R1` 给出的兜底比例，需用户拍板（§6）。这是本轮唯一可能改动既有架构决策的发现，**在拍板前不擅自改动 09 §2 的「jadx 重建源码，不打包 dex 片段」口径**。

---

## 2. 口径与可复现性

### 2.1 种子（JNI 入口，取自 `05` 实测的 4 个包族）

| 包族 | 类数 | 依据 |
|---|---|---|
| `com.netease.cloudmusic.android.sdk.playerengine.*` | 48 | `libncmplayerengine.so` 121 个 `Java_*` 导出 |
| `com.netease.cloudmusic.playlist.core.*` | 8 | `libnmplaylist.so` 31 个导出 |
| `com.netease.cloudmusic.sdk.NMCommonCache.*` | 26 | `libNMCommonCache.so` 58 个导出 |
| `com.netease.cloudmusic.media.NEAudioDevice*` | 2 | `libaudioplayer.so` 2 个导出 |
| **合计** | **84** | |

> 口径修正记录：`cloudmusic.media.` 整包有 457 个类 / 1,113 个 native 方法（音效、视频编辑、直播、录制等），**不属于播放内核**。种子已收窄到 `05 §2` 明确的 `NEAudioDevice` 精确类名，避免测量口径失真。

### 2.2 三种闭包定义（避免把「上界」误读成「必须搬的量」）

| 名称 | 规则 | 含义 |
|---|---|---|
| **全量可达闭包** | 所有竞品自有类一律展开 | jadx `import` 可达闭包的最坏上界 |
| **裁剪闭包** | 遇到明确在线业务包族（activity / fragment / module / discovery / ui / meta / play.* / 混淆短包等）即停止 | 假设激进裁剪后的规模 |
| **最小舱内闭包** | 只展开 4 个 JNI 包族内部，其余全部记为边界 | 舱内必须搬的源码量下界 + 一层需 stub 的接口面 |

### 2.3 分类规则

- 框架类（`java.*` / `android.*` / `androidx.*` / `kotlin.*` / `javax.*` 等）与第三方 / 厂商 SDK（`okhttp3.*` / `com.google.*` / `com.hihonor.*` / `com.tencent.*` / `nimlib` 相关厂商包等）**不展开**，只计数；展开它们会把闭包污染成「整个 Android 生态」。
- 竞品混淆短包（`y90.m0` / `e20` / `il0` 等）**视作竞品自有类展开**——它们正是 `10 §2 R1` 担心的 redux / 在线业务载体，不展开等于回避问题。

---

## 3. 实测数据

### 3.1 样本

| 项 | 值 |
|---|---|
| dex 数 / 总大小 | 20 个 / 168.8 MB |
| 类定义总数 | **178,120**（唯一类名 178,120） |
| 种子类 | 84 |
| 扫描的闭包类 | 135,954 |
| 框架引用次数 | 822,319 |
| 外部第三方类引用 | 2,619 |
| 被引用但无类定义 | 59（生成类 / SDK 桩） |

### 3.2 JNI 双向校验（so 侧 ↔ dex 侧）——R8 keep 包名校准

dex 侧共 **12 个类声明 210 个 native 方法**，与 `05` 的 so 侧 `Java_*` 导出 **212** 个高度吻合：

| 类 | dex 侧 native 方法 | 05 的 so 侧导出 |
|---|---|---|
| `playerengine.AudioPlayerUnit` | 27 |  |
| `playerengine.NativeDataRequestErrorCallback` | 1 |  |
| `playerengine.Playback` | 60 | **合计 119**（05 记 121） |
| `playerengine.PlaybackDataSource` | 7 |  |
| `playerengine.PlaybackEngine` | 24 |  |
| `playlist.core.PlaylistJniBridge` | **31** | **31** ✅ 完全一致 |
| `NMCommonCache.NMBufferSource` | 21 |  |
| `NMCommonCache.NMCacheBase` | 2 |  |
| `NMCommonCache.NMVirtualFile` | 7 | **合计 58** ✅ 完全一致 |
| `NMCommonCache.NMVirtualFileManager` | 23 |  |
| `NMCommonCache.SourceDataRequest` | 5 |  |
| `media.NEAudioDevice$NEAudioDeviceOnPlaybackPositionUpdateListener` | **2** | **2** ✅ 完全一致 |

- 差额 2 个（playerengine 121 vs 119、总计 212 vs 210）推测为 `RegisterNatives` 动态注册或符号重载所致，**不影响 keep 规则**：`05 §7` 的 keep 包名已由本轮给出精确类名清单，`03-12 §2` 的「示意包名」可以替换为下表 12 个精确类 + 4 个包族通配。
- **这是 spike #0 的确定性副产物**：即使 jadx 到手，R8 keep 校准也不再是待办项。

### 3.3 全量可达闭包与深度分层

| 闭包 | 类数 |
|---|---|
| 最小舱内闭包（4 个 JNI 包族内部） | **84** |
| 一层边界（需 stub / 接口替换） | **168** |
| 裁剪闭包（拦掉明确在线业务包族） | 15,906 + 10,401 个 stub 边界 |
| **全量可达闭包** | **135,954**（占全 App **76.3%**） |
| 其中业务耦合信号类 | 16,091 |

深度直方图（前 8 层）：`0:84 → 1:168 → 2:1,185 → 3:4,991 → 4:14,688 → 5:24,898 → 6:28,350 → 7:25,523`，峰值在第 6 层，最大深度 21。

**关键判读**：入口面（depth 0~1）只有 252 个类，第 3 层开始指数扩散。爆炸不是内核自身复杂，而是「内核 → 歌词/MNN 模型/配置 → 通用工具/启动框架 → 全 App」的传递链。

### 3.4 业务耦合的直接证据

最短耦合路径（种子 → 在线业务，实测）：

```
playerengine.AudioPlayerUnit
  → core.lyric.model.KaraokeWord
  → y90.m0                          （混淆类）
  → module.player.controller.hintview.FastPlayDynamicColorBubbleHintView
  → module.player.ExtractColorResult
```

包族分布 top（全量闭包）：`music.biz` 12,289、`module.listentogether` 2,249、`discovery.view` 2,120、`play.party.livepage` 1,828、`utils` 1,819、`play.listen.v2` 1,739、`eb3` 1,660（混淆）、`ui` 1,655、`activity` 1,273、`fragment` 1,266、`module.player` 1,213、`meta` 919、`module.ad` 774、`nmvideocreator.publish.fragment` 547、`epay.sdk.base` 471……

一层边界（minimal）top：`ui.BottomSheetDialog` 27、`core.patch` 13、`meta` 10、`core.pay` 9、`core.logsalvage.upload` 7、`core.startup.model` 6、`core.lyric.model` 6、`core.mp` 6、`e7`/`e20` 各 6。

> 与 `10 §2 R1` 的预判一致：`NMVirtualFileManager`、`automix`、redux 风格混淆类确实与 playerengine 语境交织。本轮把它从「推测」变成了可复现的数字与路径。

---

## 4. 对照 `10 §2 R1` 判据

| 判据 | 实测 | 判定 |
|---|---|---|
| 闭包 ≤ ~150 个类 | 全量 135,954；即使只算一层边界也 168 | ❌ 不通过 |
| 不拖入 redux / 在线业务 | 16,091 个业务信号类，路径已实证 | ❌ 不通过 |
| 「闭包大概率超出 playerengine 子树」 | 84 → 135,954 | ✅ 预判成立且远超预期 |

**结论**：按原判据应进入 `10 §2 R1` 的兜底分支——「用 dex 片段临时兜底并登记，兜底比例过高则启动 `:engine-opensource`（FFmpeg + Media3/ExoPlayer）评估」。但 `09 §2` 现行口径是「jadx 反编译重建源码，**不打包 dex 片段**」，两者冲突，**必须由用户拍板**（§6）。

---

## 5. 对既有文档 / 决策的影响面

| 对象 | 影响 | 处置 |
|---|---|---|
| `03-12 §2` R8 keep「示意包名」 | 可换成 12 个精确类 + 4 个包族通配 | **建议直接采纳**（零风险，本轮未擅自改） |
| `10 §2 R1` 连带依赖之 `PlaybackDataSource` 真实签名 | 已定位到 `playerengine.PlaybackDataSource`（7 个 native 方法）；签名仍需反编译看方法原型 | 待 jadx / smali 复核 |
| `10 §2 R3` / `03-12 §4` so 打包集 | 17 个清单不因 Java 闭包改变（`DT_NEEDED` 是二进制侧事实）；但 `System.loadLibrary` 按名加载清单尚未全局复核 | 待补（§7） |
| `09 §2`「不打包 dex 片段」 | 与兜底分支直接冲突 | **待用户拍板** |
| `02` 三段解耦 / `IMusicEngine` 门面 | **不受影响**。门面存在的意义正是让舱内实现可替换；本实测反而强化了门面的必要性 | 保持 |
| `:core-common` / `:core-model` / `:datasource-api` | **完全不受影响**，可继续先行 | 保持 |

---

## 6. 待用户拍板：引擎舱实现路线（三选一）

| 选项 | 做法 | 代价 | 风险 |
|---|---|---|---|
| **A. 薄门面 + 边界 stub（推荐先试）** | 只搬 84 个 JNI 类，其余 168 个一层边界写成同名空壳 / 接口；逐个编译报错再补，配 `03-06 §2` 的登记簿 | 1~2 周量级，需验证 JNI 回调闭包是否真能在 stub 下跑通 | 若 JNI 回调链深处依赖真实业务对象，stub 会运行时崩；需 G01~G09 兜底 |
| **B. dex 片段兜底** | 按 `03-06 §2` 临时保留 dex 片段 | 最快，但推翻 `09 §2` 口径，且把在线业务代码带进 AGPL 项目 | 合规与体积风险高 |
| **C. 启用 `:engine-opensource`** | FFmpeg + Media3/ExoPlayer 重写 `IMusicEngine` | 放弃照搬内核，工作量最大 | 架构上最干净；`05 §4` 已实测 FFmpeg 无私有依赖，路径可行 |

> 建议：先按 **A** 做 2~3 天时间盒的可编译性验证（判据：84 + ≤200 个 stub 能编译出 `IMusicEngine` 实现，且 G01/G02 通过）；失败则直接转 **C**，不走 B。
> **本轮不擅自改动架构决策**，`09 §2` 保持原口径，等拍板后再同步。

---

## 7. 未竟事项（明确挂账，不算遗漏）

1. `System.loadLibrary` 按名加载 so 的**全局**复核：目前 4 个软解器来自 `10 §2 R3` 的 dex 字符串实证，尚未用字符串表做全量匹配；用于钉死 17 个 so 的最终名单。
2. `PlaybackDataSource` 的**方法签名**（`03-06 §5` 适配层当前仍是假设）：需要 jadx 或 baksmali 反编译这一个类即可，不必等全量反编译。
3. jadx 到手后：用反编译源码复核 §2 的分类规则与裁剪清单（预期只需校验 84 + 168 个类，而非 13 万个）。
4. `libnmplaylist` 自带 `libsqlite3x` 的 native 持久化路径确认（`10 §4 #5`）。
5. 上述 1/2 可用现有 `analysis/strings/*.strings.txt` + baksmali 3.0.10 完成，**不依赖 jadx**。

---

## 8. 本轮编码进度存档（2026-11-05 00:31 断点）

### 已完成

| # | 内容 | 落点 |
|---|---|---|
| 1 | 复核与架构评估的全部结论 | 上一轮已写入 15 份原有文档 + `10-技术架构评估.md` |
| 2 | **R3 拍板**（so 闭包打包） | `10` 文首决议框 / §2 R3 / §4 / §6；`03-12 §4` 已钉死 17 个 so 清单与大小 |
| 3 | **R4 拍板**（`allowBackup=false`） | `10` / `03-11 §1.1` / `03-12 §3` C3 增加断言 |
| 4 | **spike #0 判据定为编码第一件事** | `10` §4 / §6；本文件为实测结果 |
| 5 | spike #0 工具与原始数据 | `tools/spike0_java_closure.py`、`analysis/spike0-java-closure.json`、`analysis/spike0-run.log` |
| 6 | `03-12 §4` 打包集实测复核 | 复跑 `.temp/elf_closure.py`：167 个 / 125.6 MB，闭包 13 个 / 17.1 MB，冗余 108.5 MB（86%）；4 个软解器实测 481.3 KB |

### 已启动但未落地

- `10 §4` 提到「`libs.versions.toml` 钉精确版本」「纯自研模块源码」——**均尚未开始**，属计划项，待续。

### 尚未开始（原任务清单 #3~#6）

- Gradle 多模块骨架（settings / version catalog / convention plugins / wrapper / 各 module build 文件）
- `:core-common` / `:core-model` / `:datasource-api` 的 Kotlin 实现
- 契约单测 T01~T16
- C1~C6 静态检查脚本与 `preReleaseCheck`

### 环境事实（影响验收方式）

- 本机有 **JDK 17**、**Python 3.13**、Android SDK 仅 `platform-tools`；**无 Gradle CLI、无 kotlinc、无 SDK platforms/build-tools、无 jadx、无 Gradle 缓存**。
- 因此 Kotlin/Gradle 产物**无法在本机编译验证**，只能做静态自检；后续若需实编译，须补齐 Gradle wrapper 发行版 + AGP + SDK 或换到打包机。

> AI生成
