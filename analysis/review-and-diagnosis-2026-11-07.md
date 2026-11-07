# 当前改动复核 + 取流链路真机诊断报告（2026-11-07，第四十六批 / `12 §56`）

> **触发**：用户「快速复核当前改动，检查产品/架构文档和代码差异。继续完任务」。
> **本轮性质**：复核 + 按 `12 §55.4` 顺序做真机实证（含一次字段校准实验）。
> **诚实边界**：**仍未出声**。A3 保持 `DEEP RUN`、舱不冻结、`ENGINE-FINGERPRINT` 不回填、
> 阶段 C/D 与 G01~G09 仍 `NOT RUN`。诊断日志与字段校准是取证手段，不是修复结论。

---

## 1. 工作区改动复核

### 1.1 行尾噪音（本轮已修复，最重要的一处）

`git status` 的 11 个已修改文件全部被整体从 **LF 改写成 CRLF**，造成
`git diff --stat` 显示 **+7111 / -6065**，其中绝大部分是伪改动，无法 review。

| 项 | 修复前 | 修复后 |
|---|---|---|
| `git diff --stat` | 7111 insertions / 6065 deletions | **1061 insertions / 15 deletions** |
| 典型文件 | `docs/12` 3122 行 LF → 3527 行 CRLF | 恢复 LF，只剩真实新增 407 行 |
| 范围 | 11 个 M 文件 + 部分新文件 | 23 个改动/未跟踪文本文件统一 LF（与 HEAD 一致） |

仓库 `.gitattributes` 只有 `*.apk` 的 LFS 规则，没有文本行尾约束；本轮按「与 HEAD 一致」
归一，**未新增 `.gitattributes` 规则**（属团队约定，需用户拍板后再加）。
上表「修复后」是归一当时的快照；本轮 §56 又新增诊断代码与文档回写，
收尾时最终 `git diff --stat` 为 **+1317 / -32**，且已二次复核 28 个改动文本文件
CRLF 残留为 **0**（编辑器写回时曾把 3 个 Java 文件改成 CRLF，已一并归一）。

### 1.2 归一后的实质改动（§51~§55，均为上一轮未提交成果）

| 文件 | 实质内容 |
|---|---|
| `app/build.gradle.kts` / `engine-legacy/build.gradle.kts` | 仅加 JVM `testImplementation`（JUnit5 / `:datasource-local`） |
| `MainActivity.kt` / `CloudMusicApp.kt` | §52：按动作申请权限、SAF / 媒体库两个数据源入口、rescan 闭环 |
| `MediaButtonEventReceiver.kt` / `MediaKeyAction.kt` | §51：键码判定下沉纯 JVM，Receiver 只解包转发 |
| `EngineAdapter.kt` | §54：`createDataRequestHandler()` 返回缓存的 `UrlDataRequest`，`cancelRequest/close` 成对释放 |
| `RangeFetcher.kt` / `UrlDataRequest.kt` / 单测 | §54：纯 JVM Range 取流 + `SourceDataRequest` 子类，7 个用例 |
| `tools/check_source_contracts.py` | +360 行：§50/§51/§52/§53/§54 共 20 组长效守护，清单 36 / **152** / 15 |
| `docs/09`、`12`、`16`、`analysis/task-backlog` | §51~§55 进度与结论回写 |

未跟踪的 3 份分析文档与 4 个 `:app` / 2 个 `:engine-legacy` 源文件、3 个单测文件均在上述批次登记范围内，
**无游离文件**。

### 1.3 本轮（§56）新增改动

| 文件 | 内容 | 是否改行为 |
|---|---|---|
| `EngineAdapter.kt` | `EngineDiag`（TAG `CM-DataReq`）+ 9 个 `DataProvider` 回调日志 + prepare 日志；构造带真实 `ResourceInfo` 的 `PlayerDataSource` | 仅日志 + 字段填值 |
| `UrlDataRequest.kt` | `tracedOpener` + `Start/Stop/release` + `RangeSink` 三通道日志 | 仅日志 |
| `SourceCallback.java` | `onError/onProgress/onUrlReady` 三个 native 回调加日志 | 仅日志 |
| `ResourceInfo.java` / `PlayerDataSource.java` | 新增带参构造 / 4 参构造，保留原无参与 3 参构造 | 仅新增重载 |
| `tools/check_source_contracts.py` | §50 P0-5 守护改前缀匹配（适配 4 参构造，语义不变） | 守护口径 |

---

## 2. 产品 / 架构文档 ↔ 代码一致性复核

| # | 文档口径 | 代码现状 | 结论 / 处置 |
|---|---|---|---|
| 1 | `01 §9` 权限最小化（11 项，SAF 优先） | Manifest 仍 11 项；§52 首次播放申请 `POST_NOTIFICATIONS`、导入申请 `READ_MEDIA_AUDIO`，SAF 无需权限 | ✅ 一致，零新增权限 |
| 2 | `01 §5.1` 数据源类型 / `03-02 §3` 双通道 | §52 数据源页新增 SAF 目录 + 媒体库导入，写 `Storage` 后 `rescanAll()` | ✅ 一致 |
| 3 | `01 §6.1` 「默认仅 WiFi 播放、移动网络开关」 | `:core-common` 有设置项，`HttpUrlRangeOpener` 无 WiFi 门禁 | ⚠️ **未实现**（V0.1 挂账，非本轮回归），已登记 |
| 4 | `02 §7.4` 「引擎舱无 Java 字节流入口」 | §53/§54 实证 native 有 `OnDataRequestData(long,byte[],long,long)` 回传入口 | ⚠️ **口径需澄清**：并非「Java 推流」，而是 **native 拉模式**（native 回调 `Start` → Java HTTP Range → 字节回传）；已回写 `02 §7.4` 注 |
| 5 | `02 §4.3` 稳定门面 `IMusicEngine` 14 方法 | `EngineAdapter` 逐方法映射 `PlaybackEngine` | ✅ 一致 |
| 6 | `09 §7` / `12 §55` 真机记录 | 与 logcat 原始序列一致 | ✅ 一致；但 `16 §5.3` **缺 §55 条目**，本轮补齐 |
| 7 | `evidence §4` 第 2 条 `Range: bytes=<j1>-<j1+j2-1>` | §2.5 实证实为 `j2=offset / j3=length`，代码 `rangeHeader(offset,length)` | ❌ **文档写错**，本轮修正为 `bytes=j2-(j2+j3-1)` |
| 8 | `UrlResult` 字段校准（`§50.6` 挂账） | so 的 JNI 字段表只读 `url / fileSize / expiresAt`（偏移 707672~707753）；`bitrate / md5` 属 `CacheEntry` 区 | ✅ 舱内 `url / fileSize / expiresAt` 均匹配；`realBitrate` 命名差异 native 不读，无影响 |
| 9 | `SourceConfig.type` | 舱内 `LOCAL/REMOTE` 枚举名与 so 的 `LOCAL` 字符串、竞品 `SourceConfig$Type` 一致 | ✅ 一致 |

---

## 3. 验证基线

| 项 | 结果 |
|---|---|
| `python tools/check_source_contracts.py` | **PASS**，清单 36 / 152 / 15 |
| `./gradlew test` | **394 测试 / 43 套件 / 0 失败 / 0 错误 / 0 跳过**（§54 基线，本轮改动后复跑） |
| `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL**（两次，含诊断版与校准版） |
| APK 内容校验 | 20 dex / 20 arm64 so；`classes5.dex` 含 `CM-DataReq` 等 4 个诊断字符串 |
| 构建基线 | 19 模块 / 11 权限 / 20 so / 61 行 csv / 51 素材 **零变化**（未动 Manifest / 模块 / 依赖 / so） |
| dev 6/6、release FAIL(C1,C4) | 本轮**未复跑**，沿用 §54/§55 基线（诚实标注） |

---

## 4. `12 §55.4` 执行结果：真机实证（Redmi K60，21:35~21:43）

**现场保留**：`192.168.1.11:39525`（Android 13 / API 33 / arm64-v8a），所有命令带 `-s`，
乐视电视未被触碰；`adb reverse tcp:8765` 在、PC `http.server 8765` 重启、设备 `curl` →
`HTTP 200 / 37824 B`；Room 夹具 `1/1/1` 保留（明确标注为夹具）；
`adb install -r` Success（签名已统一，**未卸载、未清数据**）。

### 4.1 第一轮：仅诊断日志

`TAG=CM-DataReq` 完整输出（启动 + 媒体键触发播放）：

```
prepare trackId=g01-local remote=true url=http://127.0.0.1:8765/cloudmusic_g01.mp3 expectedLength=0
setDataRequestErrorCallback callback=null enabled=false
setDataRequestErrorCallback callback=...NativeDataRequestErrorCallback enabled=true
createDataRequestHandler url=http://127.0.0.1:8765/cloudmusic_g01.mp3 cached=false
createDataRequestHandler -> NEW UrlDataRequest
```

**native 关键序列**：

```
BufferSource open: encryptedType=0
doPrepareAsync openSource 1
PlayerNotifyEvent msg=300 arg1=2 / arg1=3 ; msg=100 arg1=1 arg2=1 ; msg=300 arg1=9
CNCMAudioPlayer::Play status 9
```

**判据**：PC 音源播放期 **零 GET**（只有 21:35:08 连通性 curl）、
`1 Tracks of which 0 are active`、MediaSession `state=2`、
logcat 无 `SetDataRequest` / `Request Start` / `OnWriteNetData`。

### 4.2 第二轮：`ResourceInfo.extension` 校准实验

依据：so 的 JNI 字段表只从 `ResourceInfo` 读 `extension`（偏移 708213），
而 V0.1 空壳给 `""`，竞品 smali 构造时该参数经 `checkNotNullExpressionValue` 校验非空。
本轮按 URL 后缀填 `mp3`、`fileSize=expectedLength`、`id=trackId`：

```
resourceInfo extension=mp3 fileSize=0
SourceCallback.onError code=1001        ← 新增诊断暴露的 native 错误回调
```

其余日志与第一轮**逐行一致**：`msg=300 arg1=9` / `Play status 9` / 零 GET / 0 active tracks。
→ **假设排除**：`extension` 空不是本次 prepare 失败的原因。

### 4.3 结论（对 `§55` 的修正）

| `§55.4` 原假设 | 本轮实证 | 判定 |
|---|---|---|
| ① 竞品 Java 侧自己调 `NMBufferSource.SetDataRequest`，native 不主动调 `createDataRequestHandler()` | smali5 中 `SetDataRequest` **仅**出现在 `NMBufferSource.smali`，`DataSourcePlatformImpl` 无任何引用；真机日志显示 native **确实调用** `createDataRequestHandler()` 并成功 `new UrlDataRequest`（`InitPtr()` 未崩溃） | ❌ **证伪** |
| ② `UrlResult` 字段 / `SourceConfig.type` 让 native 走本地分支 | native 全程**未调用** `getUrl()` / `getLocalPath()` / `openLocalFd()`，说明还没走到取 URL 那一步 | ⚠️ 不是首要原因，断点更靠前 |
| 新发现 | prepare 阶段 `BufferSource open` 后 native 回调 **`SourceCallback.onError(1001)`**，随后进入状态 9；`getUrl` / `Start` 从未被调用 | ✅ **真正的断点在 prepare，不在取流句柄** |

**§53 的类型结论仍然正确**（`SourceDataRequest` 子类），§54 的代码也确实被 native 拿到；
问题是 native 在 prepare 阶段就报错退出，根本没进入读取 / 取流阶段。

---

## 5. 剩余断点与下一步（按证据顺序）

1. **查 `onError(1001)` 的 C++ 定义**：竞品 `onError` 只 `Log.w("onError songId=... code=...")`，
   码表在 native；需反汇编 `libncmplayerengine.so` 或查 `JniSourceCallback` 的错误枚举，
   确定 prepare 失败的直接原因。**不猜码值**。
2. **核对 `PlaybackEngine(0L)` 的 `cache=0x0`**：日志 `PlaybackEngine ctor: ... cache=0x0`
   说明 `NMVirtualFileManager` 为空，而 `CreateBufferSource` 依赖它；
   竞品经 `Playback` 层构造并注入，V0.1 直接 `new PlaybackEngine(0L)` 可能绕过了这一步。
3. **核对平台层是否必须注册**：so 有 `JniDataSourcePlatform::canPlayOnline/getUrl` 与
   `JniPlaybackPlatform::*` 全套符号，而舱内 `DataSourcePlatform.java` / `PlaybackPlatform.java`
   **仍是空类且 EngineAdapter 未注册**；这可能是 native 拿不到 URL 的原因。
4. **校准 `UrlResult.fileSize`（当前 0）与 REMOTE 分支条件**：夹具 `expectedLength=0`，
   需确认 native 是否因长度 0 判定音源无效。
5. 以上任一改动后，**播放期出现带 Range 的 GET** 才进阶段 C（回传 / active tracks）与 D（出声），
   再逐条跑 G01~G09。

---

## 6. 现场与复现

- 诊断日志 TAG `CM-DataReq`；完整 logcat 快照在系统临时目录
  `cm-a3-full.log`（诊断轮）/ `cm-a3-exp2.log`（校准轮），**未入库**。
- PC 音源 `/c/Users/cjd65/AppData/Local/Temp/cm-audio/cloudmusic_g01.mp3`（37,824 B），
  `python -m http.server 8765 --bind 127.0.0.1`，`adb reverse tcp:8765 tcp:8765`。
- 手机已装含 §56 诊断与字段校准的 debug 包（21:42），夹具保留，下轮 `install -r` 即可复跑。
- 本轮所有 `git` 改动**仍未 commit / push**（含第四十二~四十六批）。

## 7. 后续（`12 §57`，2026-11-07 22:00~22:30）

用户「继续」后按本报告 §5 的顺序推进，已完成 ①②③④ 中的三项根因修复：

- `onError(1001)` 经 `capstone` 反汇编定位为 `NMBufferSourceAdapter::Open()` 的通用失败码；
- 补齐 `cacheManagerHandle`（VFM 真实句柄）、明文 HTTP 放行、真实文件长度 37824 后，
  **阶段 A/B 首次通过**：native 调用 `Start`、播放期发出带 `Range` 的 GET、
  解码器打开 MP3、`prepare` 返回 `openSource 0`。
- **阶段 C 仍未通过**（`OnWriteNetData Failed Not Support` ×15）、**D 未确认出声**；
  A3 仍 `DEEP RUN`、指纹不回填、G01~G09 NOT RUN。
- 完整四轮实验、判据表、代码改动与下一步见
  **`analysis/a3-phase-ab-evidence-2026-11-07.md`** 与 `docs/12 §57`。
- 本报告 §5 的第 1~4 项已收口，剩余工作以证据包 §5 为准；
  第四十二~四十七批工作区仍未 commit / push。

> AI生成
