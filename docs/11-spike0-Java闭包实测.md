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
> **但入口面很窄**：直接依赖（depth 1）只有 **160 个真实类**（spike #0 原始计数 168 含 8 个 Dalvik 原始类型描述符伪影，见 §9.2）；舱内必须搬的 JNI 类只有 **84 个**，一层边界需 stub / 替换的类 **160 个**。闭包在 depth 3 之后爆炸（4,991 → 14,688 → 24,898），说明耦合是**间接传递**而非内核本身复杂。
>
> 因此判据应理解为：**「整舱照搬」被否决，但「薄门面 + 边界 slot」仍有可行空间**。**2026-11-05 用户拍板选 A，并定「slot 后期按需实现」**（§6 决议框；规程与登记簿见 `12 §10`）。A 不触发 dex 片段兜底分支，因此 `09 §2` 的「jadx 重建源码，不打包 dex 片段」口径**保持不变**。

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
| 被引用但无类定义 | **50**（另 9 个是 Dalvik 原始类型描述符伪影，spike #0 原记 59，见 §9.2） |

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
| 一层边界（需 stub / 接口替换） | **160**（spike #0 原记 168；8 个原始类型伪影已剔除，见 §9.2） |
| 裁剪闭包（拦掉明确在线业务包族） | 15,906 + 10,401 个 stub 边界 |
| **全量可达闭包** | **135,954**（占全 App **76.3%**） |
| 其中业务耦合信号类 | 16,091 |

深度直方图（前 8 层，depth 1 已按 §9.2 纠偏）：`0:84 → 1:160 → 2:1,185 → 3:4,991 → 4:14,688 → 5:24,898 → 6:28,350 → 7:25,523`，峰值在第 6 层，最大深度 21。

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
| 闭包 ≤ ~150 个类 | 全量 135,954；即使只算一层边界也 160 | ❌ 不通过 |
| 不拖入 redux / 在线业务 | 16,091 个业务信号类，路径已实证 | ❌ 不通过 |
| 「闭包大概率超出 playerengine 子树」 | 84 → 135,954 | ✅ 预判成立且远超预期 |

**结论**：按原判据应进入 `10 §2 R1` 的兜底分支——「用 dex 片段临时兜底并登记，兜底比例过高则启动 `:engine-opensource`（FFmpeg + Media3/ExoPlayer）评估」。但 `09 §2` 现行口径是「jadx 反编译重建源码，**不打包 dex 片段**」，两者冲突，**必须由用户拍板**（§6）。

---

## 5. 对既有文档 / 决策的影响面

| 对象 | 影响 | 处置 |
|---|---|---|
| `03-12 §2` R8 keep「示意包名」 | 可换成 12 个精确类 + 4 个包族通配 | **建议直接采纳**（零风险，本轮未擅自改） |
| `10 §2 R1` 连带依赖之 `PlaybackDataSource` 真实签名 | 已定位到 `playerengine.PlaybackDataSource`（7 个 native 方法）；签名仍需反编译看方法原型 | 待 jadx / smali 复核 |
| `10 §2 R3` / `03-12 §4` so 打包集 | 清单不因 Java 闭包改变（`DT_NEEDED` 是二进制侧事实）；spike #1 全局复核后由 17 修正为 **18 个**（补 `libc++_shared.so`），见 `13 §2` | ✅ 已收口 |
| `09 §2`「不打包 dex 片段」 | 兜底分支（B）会与之冲突；拍板 A 后不触发该分支 | ✅ **口径保持不变**（规程见 `12 §10`） |
| `02` 三段解耦 / `IMusicEngine` 门面 | **不受影响**。门面存在的意义正是让舱内实现可替换；本实测反而强化了门面的必要性 | 保持 |
| `:core-common` / `:core-model` / `:datasource-api` | **完全不受影响**，可继续先行 | 保持 |

---

## 6. 已拍板：引擎舱实现路线 A（2026-11-05）

> **用户决议**：**A（薄门面 + 边界 slot）**，附加约束「**slot 后期按需实现**」。
> 即：本轮只确定路线，**不实现任何 slot、不提取 so、不建 `:engine-legacy` 源码**；后期开工时按编译 / 运行实际触及的边界逐个补 slot 并登记，规程与登记簿见 `12 §10`（`03-06 §2` 登记簿约定的实例化）。
> **A 不触发 dex 片段兜底分支**，因此 `09 §2`「jadx 重建源码，不打包 dex 片段」口径**保持不变**；B 不采用，C 作为 A 时间盒失败后的唯一转进方向。

| 选项 | 做法 | 代价 | 风险 | 决议 |
|---|---|---|---|---|
| **A. 薄门面 + 边界 slot** | 只搬 84 个 JNI 类，一层边界 160 个按需写成同名空壳 / 接口 | 1~2 周量级，需验证 JNI 回调闭包能否在 slot 下跑通 | 回调链深处若依赖真实业务对象会运行时崩，需 G01~G09 兜底 | ✅ **已选** |
| **B. dex 片段兜底** | 按 `03-06 §2` 临时保留 dex 片段 | 最快，但推翻 `09 §2` 口径，且把在线业务代码带进 AGPL 项目 | 合规与体积风险高 | ❌ 不采用 |
| **C. 启用 `:engine-opensource`** | FFmpeg + Media3/ExoPlayer 重写 `IMusicEngine` | 放弃照搬内核，工作量最大 | 架构最干净；`05 §4` 已实测 FFmpeg 无私有依赖 | 🔄 A 失败后转进 |

**执行判据（沿用 `10 §2 R1` 的原建议）**：2~3 天时间盒，84 个真类 + ≤200 个 slot 能编译出 `IMusicEngine` 实现，且真机 G01/G02（播放 / 暂停）通过 → A 成立；失败 → **直接转 C，不走 B**。

**术语对齐**：用户所称「slot」= 本文原称的 stub / 同名空壳 / 边界占位，三者指同一物；后续文档统一用 **slot**，需要时可括注 stub。

---

## 7. 未竟事项（明确挂账，不算遗漏）

> **2026-11-05 收口状态：#1 / #2 / #4 已由 spike #1 完成（`13`），#3 仍挂账。**

1. ~~`System.loadLibrary` 按名加载 so 的**全局**复核~~ **已完成（`13 §2`）**：实际用指令级 dex 解析（比字符串表匹配更精确）。结论：主 App 几乎不直接调 `System.loadLibrary`，统一走封装器 `zv.a.b(Context, libName, stack)`；18 个打包集裸名全部有加载证据；**并发现 `libc++_shared.so` 被误列为系统库，打包集 17 → 18**。
2. ~~`PlaybackDataSource` 的**方法签名**~~ **已完成（`13 §3`）**：baksmali 定向反汇编确认它是预加载 / 引擎缓存配置类（7 个 native 方法），**不是数据源接口**；真正接口为 `playerengine.DataProvider`，`03-06 §5` 适配层已按实际 dex 修正。**附带的 C1「JNI 202 vs dex 210 差 8」挂账也已收口（`13 §7.4`）**：差额 = `Playback.nativeNotifyAudioEffectChanged` 1 个 + `PlaylistJniBridge` 的 `*Raw` / 日志 / 更新类方法 7 个，不再是笼统的 RegisterNatives 推测。
3. ~~jadx 到手后：用反编译源码复核 §2 的分类规则与裁剪清单（84 + 168 个类）~~ **已收口（2026-11-05，见 §9）**：用纯 JVM 等价复核完成，清单为 **84 个种子 + 160 个一层边界**（168 含 8 个原始类型伪影），并给出 148 empty-stub / 12 keep-signature 的处置策略；**不必等 jadx**。更深层初始化链与一次性 ABI 验收仍待真机 / Android SDK。
4. ~~`libnmplaylist` 自带 `libsqlite3x` 的 native 持久化路径确认~~ **已完成（`13 §4`）**：5 张 SQLite 表 + `nativeInit(db_path)` + `nativeRestoreFromDb`，不是只读镜像；`03-06 §8` 已补隔离约束。
5. 上述 1/2 原计划用 `analysis/strings/*.strings.txt` + baksmali 3.0.10；实际采用 `tools/spike1_engine_audit.py`（指令级解析）+ baksmali 交叉验证，**均不依赖 jadx**。剩余挂账见 `13 §6`（JNI 202 vs 210 差 8、加固壳加密参数、其余映射行）。

---

## 8. 本轮编码进度存档（2026-11-05 00:31 断点）

### 已完成

| # | 内容 | 落点 |
|---|---|---|
| 1 | 复核与架构评估的全部结论 | 上一轮已写入 15 份原有文档 + `10-技术架构评估.md` |
| 2 | **R3 拍板**（so 闭包打包） | `10` 文首决议框 / §2 R3 / §4 / §6；`03-12 §4` 已钉死 so 清单与大小（拍板时 17 个，spike #1 后修正为 18 个，见 `13 §2`） |
| 3 | **R4 拍板**（`allowBackup=false`） | `10` / `03-11 §1.1` / `03-12 §3` C3 增加断言 |
| 4 | **spike #0 判据定为编码第一件事** | `10` §4 / §6；本文件为实测结果 |
| 5 | spike #0 工具与原始数据 | `tools/spike0_java_closure.py`、`analysis/spike0-java-closure.json`、`analysis/spike0-run.log` |
| 6 | `03-12 §4` 打包集实测复核 | 复跑 `.temp/elf_closure.py`：167 个 / 125.6 MB，闭包 13 个 / 17.1 MB，冗余 108.5 MB（86%）；4 个软解器实测 481.3 KB |

### 已启动但未落地

- `10 §4` 提到「`libs.versions.toml` 钉精确版本」「纯自研模块源码」——**当时尚未开始**（历史存档，现已完成，见 §8.1）。

### 当时的「尚未开始」清单（原任务清单 #3~#6，已由 §8.1 更新为完成）

- Gradle 多模块骨架（settings / version catalog / convention plugins / wrapper / 各 module build 文件）
- `:core-common` / `:core-model` / `:datasource-api` 的 Kotlin 实现
- 契约单测 T01~T16
- C1~C6 静态检查脚本与 `preReleaseCheck`

### 环境事实（影响验收方式）

- 本机有 **JDK 17**（Zulu 17.0.21）、**Python 3.13**、Android SDK 仅 `platform-tools`；**无 kotlinc、无 SDK platforms/build-tools、无 jadx**。Gradle CLI 与缓存起初也没有，但网络可达 Maven Central / Gradle Plugin Portal / Google Maven / 华为云镜像。
- **2026-11-05 复核修正**：纯 JVM 模块**不需要 Android SDK platforms/build-tools**。已把 Gradle 8.11.1 发行版部署到隔离临时目录、生成项目 wrapper，`./gradlew test` 与 `./gradlew preReleaseCheck` 均 **BUILD SUCCESSFUL**（23 个测试 0 失败，详见 `12 §5.1`）。仅 Android 侧模块（`:app` / `:ui-legacy` / `assembleRelease`）仍需补 SDK platforms 35 与 build-tools。上文「无法在本机编译验证」是补齐工具链前的旧结论。

### 8.1 续（2026-11-05，编码第一批）

- 原任务清单 #3~#6 已全部落地：Gradle 多模块骨架与精确版本目录、`:core-common` / `:core-model` / `:datasource-api` 契约源码（17 个 Kotlin 文件）、T01~T16、C1~C6 与 `preReleaseCheck`。本机静态自检 dev 6/6 PASS、release 正确阻断。
- §8「已启动但未落地」的两项（`libs.versions.toml` 钉版本、纯自研模块源码）**均已完成**；`10 §4` 的 #2 / #4 / #6 已同步。
- **实证发现**：`analysis/decoded/lib/arm64-v8a/` 只剩 1 个 `libnesec-x86.so`（实为 x86_64），169 个 ELF32 库在 `armeabi-v7a/`；APK 包内 `lib/arm64-v8a/` 才是完整的 167 个 / 125.6 MiB AARCH64 so，与 `05` / `10 §2 R3` 一致。C1 / C6 / `extract-engine-libs.py` 已改为直接读 APK 本体。详见 `12 §4`。
- **§7 #2 挂账补充**：4 个入口 so 的 `.dynstr` 实测 `Java_*` 导出 202 个（118+2+24+58），与 §3.2 的 dex 侧 210 个 native 方法差 8，推测为 `RegisterNatives` 动态注册；不影响 12 个精确类的 R8 keep 校准结论。
- **进度断点已迁至 `docs/12-编码阶段第一批交付.md` §7**。§6 的引擎舱路线已于 2026-11-05 拍板为 **A（薄门面 + 边界 slot，slot 后期按需实现）**；A 与 `09 §2` 口径兼容，未改动该口径；slot 规程与登记簿见 `12 §10`。
- **编译验证补记（2026-11-05 13:29~13:55）**：JDK 17 + 网络可用 + 隔离部署 Gradle 8.11.1，生成项目 wrapper 后 `./gradlew test` **BUILD SUCCESSFUL**（23 个测试 / 0 失败 / 0 错误 / 0 跳过），`./gradlew preReleaseCheck` **BUILD SUCCESSFUL**（C1~C6 dev 6/6 PASS）。上文「环境事实」的「无法在本机编译」已被修正：仅 Android 侧仍需 SDK platforms/build-tools。详见 `12 §5.1`。
- **第二批补记（2026-11-05 14:08~14:35）**：03-02 / 03-03 的三个纯 JVM 驱动（strm / playlist / webdav）落地，03-01 §12 的 **T17~T20 全部补齐**；`./gradlew test` **BUILD SUCCESSFUL / 66 个测试 0 失败**，C1~C6 dev 6/6 PASS。详见 `12 §11`。
- **第三批补记（2026-11-05 14:59~15:20）**：03-04 `:scanner` 纯 JVM 落地（两阶段扫描 / 增量 / 内联展开 / 取消 / 端口隔离），`./gradlew test` **BUILD SUCCESSFUL / 84 个测试 0 失败**。详见 `12 §12`。同时确立强制规范「每次任务结束前必须同步进度到文档」。

---

## 9. P3 裁剪清单复核（2026-11-05，收口 §7 #3）

**触发**：`12 §7 #4` 的 P3「三个照搬舱 Java 依赖闭包裁剪」；§7 #3 原挂账为「jadx 到手后复核 §2 分类规则与裁剪清单（只需校验 84 + 168 个类）」，`13 §6 #1` 与 `05 §7 #2` 同样引用。
**方法**：不依赖 jadx，复用 `analysis/spike0-java-closure.json` 的 BFS 产物，再用 20 个 dex 的 `class_def`（178,120 个）做精确前缀枚举与存在性校验；工具 `tools/p3_trim_audit.py`，产物 `analysis/p3-trim-audit.json` / `.csv`。

### 9.1 84 个 JNI 种子类：完全吻合

| 包族（精确前缀） | 实测 | §2.1 文档 | spike0 种子命中 |
|---|---|---|---|
| `…sdk.playerengine.` | 48 | 48 | 48 |
| `…playlist.core.` | 8 | 8 | 8 |
| `…sdk.NMCommonCache.` | 26 | 26 | 26 |
| `…media.NEAudioDevice` | 2 | 2 | 2 |
| **合计** | **84** | **84** | **84** |

`§2.1` 把 `cloudmusic.media.` 整包（457 类 / 1,113 native 方法）收窄到 `NEAudioDevice` 精确类的判断**正确**；§2 的分类规则无需修改。

### 9.2 数字纠偏：168 → 160，ghost 59 → 50

`spike0` 的 `dotted()` 对非 `L...;` 描述符直接返回原字符串，导致 **Dalvik 原始类型描述符被当成类名**：

| 项 | spike #0 原始 | 伪影 | 复核后 | 说明 |
|---|---|---|---|---|
| 一层边界（depth 1） | 168 | 8 个 `B/C/D/F/I/J/V/Z` | **160** | 160 个全部在 dex 中有类定义（ghost 0） |
| 被引用但无类定义（ghost） | 59 | 9 个（多一个 `S`=short） | **50** | 其余为生成类 / SDK 桩，如 `common.TraceMonitor` |
| 最小舱内闭包 | 84 | 0 | **84** | 不变 |
| 全量可达闭包 | 135,954 | 0 | **135,954** | 不变（该计数只含有类定义的自有类） |

深度直方图首层相应为 `0:84 → 1:160 → 2:1,185 → …`。**这是计数纠偏，不是结论翻转**：闭环在第 3 层后依旧指数爆炸（4,991 → 14,688 → 24,898），`10 §2 R1` 两项判据仍不通过，路线 A 与 `09 §2` 口径均不变。

### 9.3 160 个边界类的分类与 stub 策略

| 类别 | 数量 | 处置 |
|---|---|---|
| `obfuscated-short`（`aa2`、`e7`、`e20` 等短包） | 67 | 同名空壳 / 接口桩 |
| `telemetry-startup`（logsalvage / apm / patch / startup） | 31 | 空实现，不进舱 |
| `ui-widget`（`BottomSheetDialog` 系列、主题 View） | 26 | 空壳 |
| `mp-framework`（`core.mp.*`） | 11 | 空壳 |
| `lyric-model`（`core.lyric.model.*`、`ringtones.view.*`） | 9 | **保留方法签名**（JNI 回调链会传歌词模型） |
| `resource-r-class`（`R$attr` / `R$styleable` / `R$xml`） | 5 | 空壳或常量 |
| `mnn-model` / `im-online` | 各 4 | 空壳 |
| `callback-interface` | 3 | **保留方法签名** |
| **合计** | **160** | **148 个 empty-stub + 12 个 keep-signature** |

**12 个需保留签名的类**（`analysis/p3-trim-audit.csv` 里 `stub_strategy=keep-signature`）：
`commoninterface.OnDeleteMusicListener`；`core.lyric.model.{AbsLyric, AbsLyricLine, CommonLyricLine, KaraokeLine, KaraokeWord, SentenceInfo}`；`core.lyric.ringtones.view.{BaseRangeBar, BaseRangeBar$a, LyricViewWithRangeBar}`；`ui.BottomSheetDialog.ActionMenuItem$OnActionMenuItemClickListener`、`…PlayQualityActionMenuItem$OnPlayQualityChangeListener`。

> 与 `§3.4` 的最短耦合路径一致（`AudioPlayerUnit → KaraokeWord → y90.m0 → module.player…`）：歌词模型是内核与在线业务之间的**直接**桥，不能整体裁掉，必须留签名。

### 9.4 挂账收口与剩余

| 挂账 | 状态 |
|---|---|
| `§7 #3` / `13 §6 #1`：复核 §2 分类规则与 84 + 168 裁剪清单 | ✅ **已用纯 JVM 等价复核收口**（84 + **160**，含伪影纠偏），不必等 jadx |
| `05 §7 #2`：Java SDK 层初始化链与依赖闭包 | ✅ **部分收口**：一层边界清单已精确到类 + stub 策略；**更深层初始化链仍需 jadx / 真机 G01** |
| `12 §7 #4`：P3 一次性 ABI 验收（`05 §7`、`03-06 §6`、`03-12 §2`） | ⏸ **仍待**：需 Android SDK 落地 18 个 so + `assembleDebug` + 真机 G01/G02。本轮只完成「Java 依赖闭包裁剪」的静态部分 |
| `12 §10.2` 步骤 2/3：84 个真类重建 + 按编译报错补 slot | 清单已就绪（84 种子 + 160 边界 CSV，其中 12 个留签名）；**slot 仍按路线 A 后期按需触发**，本轮未实现任何 slot、未提取 so |

> **命令**：`python tools/p3_trim_audit.py`，产物 `analysis/p3-trim-audit.json` / `.csv`。复核只改了统计精度，**不改动路线 A 的拍板与 `09 §2`「jadx 重建源码、不打包 dex 片段」口径**。

> AI生成
