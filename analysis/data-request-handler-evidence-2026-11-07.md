# DataProvider.createDataRequestHandler() 静态取证报告（2026-11-07 第四十四批）

> **性质**：只读实证轮，**零生产行为改动**。`12 §50.6 / §50.7 #1` 把「native 期望的取流句柄 /
> `IBaseDataSource` 承接链」列为唯一出声硬门槛，但方向本身是推测。本轮用 baksmali 反汇编
> 竞品 dex + so 字符串/导出符号表，把返回对象的**类型、继承链、方法契约、JNI 符号、加载链**
> 逐项钉死，给出可执行实现规格与分阶段真机判据。**`createDataRequestHandler()` 仍返回 `null`**：
> 参数语义未实证前不凭猜测实现（§50.6「不猜」纪律）。结论见 `docs/12 §53`。
>
> **★ 2026-11-07 第四十五批更新（见 §2.5 / §4 / §5）**：继续反汇编 `nmcache/l.w` / `l.k`
> 与 `OnDataRequest*` 调用点，实证出 `Start(j1,j2,j3)` 的**参数语义**
> （`j1`=请求标识、`j2`=offset、`j3`=length）与回传首参，故已按 §4 落地
> `UrlDataRequest` + `RangeFetcher` 并接通 `createDataRequestHandler()`（阶段 A/B）。
> 上面的「仍返回 `null`」是**第四十四批当时的状态**，保留作时间线记录；
> 现状见 `docs/12 §54`。**仍未出声**，回传协议细节待真机阶段 C 校准。

## 1. 取证方法（全部本机可复现、零联网）

```bash
# ① so 的 ASCII 串 / ELF 导出符号（纯 Python，正则扫 [ -~]{4,}）
python - <<'PY'
import re; from pathlib import Path
d = Path('engine-legacy/src/main/jniLibs/arm64-v8a/libncmplayerengine.so').read_bytes()
for m in re.finditer(rb'[ -~]{4,}', d):
    s = m.group().decode('ascii', 'replace')
    if 'createDataRequestHandler' in s or 'SourceDataRequest' in s: print(m.start(), s)
PY

# ② 定位含该方法名的 dex（解析 dex header 的 string_ids，无需全量反汇编）
#    → analysis/decoded/classes5.dex、classes14.dex
# ③ baksmali 3.0.10 定向反汇编（输出到系统临时目录，不入库）
java -jar tools/baksmali-3.0.10.jar d analysis/decoded/classes5.dex  -o <tmp>/smali5    # 8947 个 smali
java -jar tools/baksmali-3.0.10.jar d analysis/decoded/classes18.dex -o <tmp>/smali18  # 8912 个 smali
```

Git Bash 传设备/盘符路径需 `MSYS_NO_PATHCONV=1` 或直接用 `C:/...` 绝对路径。

## 2. 证据链

### 2.1 native 侧确实按名查找 `createDataRequestHandler`

`libncmplayerengine.so`（arm64-v8a）的 JNI 方法表，偏移 `708603~708904`：

```
708657  getLocalPath
708670  onExpire
708679  (Ljava/lang/String;)Lcom/netease/cloudmusic/android/sdk/playerengine/UrlResult;
708759  createDataRequestHandler
708784  ()Ljava/lang/Object;          ← 返回 Object，无具体类约束
708805  cancelRequest
708823  close
708829  openLocalFd
708841  (Ljava/lang/String;)I
708863  closeLocalFd
708876  setDataRequestErrorCallback
708904  (Lcom/netease/cloudmusic/android/sdk/playerengine/DataRequestErrorCallback;Z)V
```

同一 so 的 C++ 导出符号给出取流架构：

```
ncm_player_engine::NMBufferSourceAdapter(...NMVirtualFileManager*, IPlayerDataSource*, ...)
ncm_player_engine::CreateLocalSource(int, string, EncryptedType, IDataProvider*)
NMVirtualFileManager::CreateBufferSource(string, string, shared_ptr<NMBufferSourceHandler>)
NMBufferSourceAdapter::SetDataRequestErrorObserver / LoadData / Read / Seek / Open / Close
```

即 native 侧 `IDataProvider` → `NMBufferSourceAdapter` → `NMBufferSource`，Java 的
`DataProvider` 是它的 JNI 投影。方法名+签名字符串说明 native 用 `GetMethodID` 按名取
`createDataRequestHandler`，**返回 `null` 时该分支直接没有承接对象**（与 §50.6「播放期零 GET」一致）。

### 2.2 竞品实现类的定位

`classes5.dex` 的 `string_ids` 里有：

```
Lcom/netease/cloudmusic/android/sdk/playerengine/DataProvider;
Lcom/netease/cloudmusic/playback/DataSourcePlatformImpl$buildDataSource$2$getDataProvider$1;
Lcom/netease/cloudmusic/sdk/NMCommonCache/NMBufferSource;
Lcom/netease/cloudmusic/sdk/NMCommonCache/NMBufferSourceHandler;
Lcom/netease/cloudmusic/sdk/NMCommonCache/SourceDataRequest;
Lcom/netease/cloudmusic/module/player/datasource/IBaseDataSource;
```

`classes14.dex` 另有 `DataProvider$DefaultImpls`（Kotlin 接口默认实现）。

反汇编文件
`<tmp>/smali5/com/netease/cloudmusic/playback/DataSourcePlatformImpl$buildDataSource$2$getDataProvider$1.smali`：

```smali
.class public final Lcom/netease/cloudmusic/playback/DataSourcePlatformImpl$buildDataSource$2$getDataProvider$1;
.super Ljava/lang/Object;
.implements Lcom/netease/cloudmusic/android/sdk/playerengine/DataProvider;

.method public createDataRequestHandler()Ljava/lang/Object;
    .registers 2
    iget-object v0, p0, ...->$handler:Lcom/netease/cloudmusic/playback/DataSourcePlatformImpl$d;
    return-object v0
.end method
```

**结论 1**：返回值是一个字段 `$handler`，类型为 `DataSourcePlatformImpl$d`
—— **`Object` 的实际类型不是 `IBaseDataSource`**，§50.6 里「`IBaseDataSource` 或等价句柄」
的方向需要收窄。

### 2.3 继承链终点 = `SourceDataRequest`

```
DataSourcePlatformImpl$d                       (classes5.dex)
  .super Lcom/netease/cloudmusic/module/player/nmcache/l;
    .super Lcom/netease/cloudmusic/sdk/NMCommonCache/SourceDataRequest;   ← classes18.dex
```

- `$d` 覆写 `Start(JJJ)J`（先 `AtomicInteger.set(0x3f2)` 再 `invoke-super`）、`C(JII)V`、`H()Z`。
- `nmcache/l` 是真正的取流实现（13k+ 行 smali：`A/B/D/E/G/I/r/s/x` 等私有方法，
  涉及 `RequestContext`、`PriorityDomain`、`SocketFactory`、fastjson，明显是 HTTP Range 客户端）；
  它实现 `public Start(JJJ)J` / `public Stop()V` / `needFixBegin()Z` 等。
- `nmcache/l.Start(JJJ)J` 打日志 `"Request Start" + j1 + " " + j2 + "  " + j3`，
  构造 `nmcache/j(l, j2, j3, j1)` 提交到线程池，**返回 `0L`**；
  `j.run()` 再调 `l.b(l, j2, j3, j1)` 进入真正的请求组装（`l.G(RequestContext, JJJ)` 等）。

**结论 2**：native 期望的对象是 `com.netease.cloudmusic.sdk.NMCommonCache.SourceDataRequest`
的**子类实例**；契约入口是 `Start(long,long,long): long`（异步启动、返回 0）与 `Stop()`。

### 2.4 该类与 JNI 符号在舱内已经齐备

`engine-legacy/src/main/java/com/netease/cloudmusic/sdk/NMCommonCache/SourceDataRequest.java`
（`12 §50.3` 已照搬的真实源码）方法清单：

| 方法 | 作用 |
|---|---|
| `SourceDataRequest()` | 构造时调 `private native InitPtr()` 建 C++ 对象 |
| `OnDataRequestBegin(long,long)` | public 包装 → `OnNativeDataRequestBegin(native_ptr_,...)` |
| `OnDataRequestData(long,byte[],long,long)` | public 包装 → `OnNativeDataRequestData`，**数据回传通道** |
| `OnDataRequestEnd(long,String)` | public 包装 → `OnNativeDataRequestEnd` |
| `OnNativeDataRequestBegin/Data/End` | `public native`，由 so 提供 |
| `Start(long,long,long)` / `Stop()` | **`public abstract`，须由自研子类实现** |
| `deletePtr()` / `needFixBegin()` | 释放 / 兼容开关 |

`libNMCommonCache.so` 的导出符号逐项命中（5/5 + `NMBufferSource` 全套 22 个）：

```
Java_com_netease_cloudmusic_sdk_NMCommonCache_SourceDataRequest_InitPtr
Java_com_netease_cloudmusic_sdk_NMCommonCache_SourceDataRequest_DeletePtr
Java_com_netease_cloudmusic_sdk_NMCommonCache_SourceDataRequest_OnNativeDataRequestBegin
Java_com_netease_cloudmusic_sdk_NMCommonCache_SourceDataRequest_OnNativeDataRequestData
Java_com_netease_cloudmusic_sdk_NMCommonCache_SourceDataRequest_OnNativeDataRequestEnd
Java_com_netease_cloudmusic_sdk_NMCommonCache_NMBufferSource_SetDataRequest   ← 绑定入口
... NMBufferSource_Open/Read/ReadBuf/Seek/Size/Close/Stop/Abort/PreloadRange ...
```

且 `EngineNativeLoader.PLAYBACK_ENGINE_CHAIN` 的第 2 项就是 `NMCommonCache`：
`ffmpeg → NMCommonCache → ncmusbaudio → ncmaudioplayer → neaudioeffects → ncmplayerengine`。
**`InitPtr()` 在首次 prepare 时可解析，不存在缺库问题。**

另有日志字符串 `NMBufferSourceHanderAndroid Find method OnSourceCacheInfo Failed.` /
`Find method Preload Failed.` / `Find method Progress Failed.`，说明 native 对**另一个**回调对象
也按方法名 `GetMethodID` 查找；若真机出现同类 `NoSuchMethodError`，按精确方法名补类即可。

### 2.5 参数语义与回传协议（第四十五批追加实证）

调用链：`l.Start(JJJ)` → 构造 `nmcache/j(l, j2, j3, j1)` → `j.run()` →
`l.b(l, j2, j3, j1)` → `l.w(p1=j2, p3=j3, p5=j1)`。

`l.w` 里 `add-long v7, p1, p3` 得 `j2 + j3`，再以
`invoke-virtual/range {v1 .. v16} l.k(BizMusicMeta, String, String, JJ, I, PriorityDomain, I, Z, J, String, RequestContext)` 传入：

| `k` 参数 | 来源 | 语义 |
|---|---|---|
| `p4/p5`（第 1 个 `J`） | `move-wide/from16 v5, p1`（`w` 的 `p1` = `j2`） | **offset** |
| `p6/p7`（第 2 个 `J`） | `add-long v7, p1, p3` = `j2 + j3` | **end**（`k` 内 `sub-long v22, p6, 1` 即 `end-1`） |
| `p12/p13`（第 3 个 `J`） | `move-wide/from16 v13, p5`（`w` 的 `p5` = `j1`） | **请求标识** |

`l.k` 的 `OnDataRequest*` 调用点（`l.smali` 6685 / 8218 / 9551；另见
`nmcache/i.smali` 6605 / 7875 / 9349、`ak0/h.smali` 415 / 510）：

- `OnDataRequestBegin(JJ)`：首参 `move-wide/from16 v13, p12` = **`j1`**；
  次参 `v2/v3` 由 `lastIndexOf` + `substring` + `Long.parseLong` 从 `Content-Range` 解析，
  即**资源总长**。
- `OnDataRequestData(J[BJJ)`：首参同为 `p12`（`j1`），`byte[]` 为数据，
  末参 `int-to-long v14, v9` = **本次读取长度**；第 2 个 `long`（`v76`）语义待真机校准。
- `OnDataRequestEnd(JLjava/lang/String;)`：首参同为 `p12`（`j1`），`String` 为错误信息。

**结论 —— `Start(j1, j2, j3)` 参数语义已实证**：

| 参数 | 语义 | 落地用法 |
|---|---|---|
| `j1` | 请求标识 | 原样作为三个 `OnDataRequest*` 的首参回传 |
| `j2` | 起始 offset | `Range: bytes=j2-(j2+j3-1)` 的起点、`OnDataRequestData` 的写入偏移基数 |
| `j3` | 请求长度 length | Range 终点、回传字节数上限 |

`Start` 必须**立即返回 0**（竞品 `l.Start` 末行 `const-wide/16 p1, 0x0; return-wide p1`），
取流在线程池异步执行。至此 §3 里「参数语义未实证」一项收窄为：
仅 `OnDataRequestData` 第 2/3 个 `long` 的精确含义与 `OnDataRequestEnd` 成功时 `String`
的取值待真机 logcat 校准（阶段 C），**不再阻塞阶段 A/B 的实现**。

## 3. 结论（对 §50.6 的修正）

| 项 | §50.6 旧口径 | 本轮实证 |
|---|---|---|
| 返回对象 | 「native 期望的取流句柄 / `IBaseDataSource` 或等价」 | **`SourceDataRequest` 子类实例**（`DataSourcePlatformImpl$d` → `nmcache/l` → `SourceDataRequest`） |
| 契约入口 | 未定义 | `Start(JJJ)J` 异步启动并返回 0、`Stop()` 取消 |
| 数据回传 | 未定义 | `OnDataRequestBegin/Data/End` → `OnNativeDataRequest*`，5 个 JNI 符号齐全 |
| 是否缺类 / 缺库 | 疑缺 84 真类全集 | 该类已在舱内，`NMCommonCache` 已在加载链，**不缺库** |
| `null` 的后果 | 推测 | native 拿到 `null` → `SetDataRequest` 无对象 → `Start` 不被调用 → **播放期零 HTTP 请求**（与实测吻合） |

**§2.5 追加实证后收窄的未决项**（第四十五批已据此落地阶段 A/B，剩余项待真机）：
1. ~~`Start` 三个 long 的语义~~ → **已实证**（`j1`=请求标识 / `j2`=offset / `j3`=length，见 §2.5）。
2. `OnDataRequestData(J[BJJ)` 第 2 个 long（`l.k` 的 `v76`）的精确含义；
   第 3 个已实证为本次读取长度，首参已实证为 `j1`。
3. `OnDataRequestEnd` 成功时 `String` 的取值（实现暂传空串，竞品传 `v0`）。
4. native 是否会额外按名查找 `C(JII)V` / `H()Z` / `needFixBegin()` 等方法。
5. `SourceConfig` 的 `REMOTE` 分支是否还要求 `openLocalFd()` 返回有效 fd（§50 已改 type，未改变结果）。

## 4. 可执行实现规格（第四十五批已按此落地）

> **落地状态（`12 §54`）**：已实现 1~4 与第 5 条守护。产物为
> `engine-legacy/.../RangeFetcher.kt`（`RangeFetcher` / `RangeOpener` / `RangeSink` /
> `HttpUrlRangeOpener`）、`UrlDataRequest.kt`（`SourceDataRequest` 子类，`Start/Stop/release`）、
> `RangeFetcherTest.kt`（7 个 JVM 用例），以及 `EngineAdapter.UrlDataProvider` 的
> `createDataRequestHandler()` 缓存返回 / `cancelRequest()` / `close()` 释放。
> 下列 1~5 即实现对照表；**阶段 A/B 已具备代码，仍待真机验证，不等于已出声**。

1. 在 `:engine-legacy` 新建 `UrlDataRequest : SourceDataRequest()`（**原包名不可改**，JNI 硬绑定），
   构造持有 `url` 与可注入的 `RangeOpener`；`createDataRequestHandler()` 缓存单例返回，
   仅 `source.url != null` 时创建，LOCAL 源保持 `null`。
2. `Start(j1,j2,j3)` 立即返回 `0`，在后台线程：
   `HttpURLConnection` 带 `Range: bytes=j2-(j2+j3-1)` GET → `OnDataRequestBegin(...)`
   （**12 §56 修正**：旧稿误写成 `<j1>-<j1+j2-1>`；§2.5 实证为 `j1`=请求标识、
   `j2`=offset、`j3`=length，代码 `RangeFetcher.rangeHeader(offset, length)` 与之一致）
   → 分片 `OnDataRequestData(...)` → `OnDataRequestEnd(...)`；异常走 `OnDataRequestEnd(err)`。
3. `Stop()` 取消并 `deletePtr()`；`UrlDataProvider.close()` 里一并释放，避免 native 句柄泄漏。
4. 取流纯逻辑抽成不继承 native 类的 `RangeFetcher` + `RangeOpener` 端口，
   **放纯 JVM 单测**（Range 头、分片、错误、取消），避免在 JVM 里实例化 `InitPtr()`。
5. 守护：`check_source_contracts.py` 断言返回对象类型、`Start/Stop` 覆写、
   `NMCommonCache` 在加载链、5 个 JNI 符号清单。

## 5. 分阶段真机判据（由用户在 Redmi K60 上执行，本机只读结果）

| 阶段 | 代码状态 | 判据 |
|---|---|---|
| A · 类型确认 | **已落地（§54）**：`createDataRequestHandler()` 返回 `UrlDataRequest` 单例 | logcat 出现 `SetDataRequest` / `Request Start`；若 CheckJNI 报 `NoSuchMethodError: <方法名>` / `NoSuchFieldError` / `UnsatisfiedLinkError`，**按精确名字补方法/字段/库**，不要猜；同时确认 `InitPtr()` 在 `setDataSource` 阶段能否成功 |
| B · 取流触发 | **已落地（§54）**：`Start(j1,j2,j3)` 后台发 `Range: bytes=j2-(j2+j3-1)` | PC 侧 `python -m http.server 8765` 在**播放期**出现带 `Range` 的 GET（§50 时为零）；记录三个 `long` 的实参值核对 `offset/length` 映射，并检查响应 `Content-Range` 是否给出 total |
| C · 数据回传 | **已落地（§54，协议细节待校准）**：`OnDataRequestBegin/Data/End` 已接通 | `dumpsys media.audio_flinger` 的 `active tracks ≥ 1`（§50 为 0）、`NMBufferSourceImp` 出现 `OnWriteNetData` 而非 `SKIP`；若 `SKIP offset=...`，据日志校准 `OnDataRequestData` 第 2 个 long 的语义 |
| D · 出声 | — | 音频输出有本 App 的 AudioTrack；此后才逐条跑 G01~G09 |

任何阶段出现 `UnsatisfiedLinkError` / CheckJNI abort / 进程崩溃，取**精确报错**再补类。
取流成功前 `assets/ENGINE-FINGERPRINT` 不回填、舱不冻结（`§50.6` 口径不变）。

## 6. 复现产物与基线

- 反汇编产物在系统临时目录（`cm-smali5` / `cm-smali18`），**未入库**；
  仓库内证据为 dex 原文 `analysis/decoded/classes5.dex` / `classes18.dex`、
  `analysis/decoded/classes14.dex` 与 18 个 arm64 so。
- 第四十四批当时**零生产行为改动**（`createDataRequestHandler()` 为 `null`）；
  第四十五批已按 §4 接通 `UrlDataRequest`（详见 `docs/12 §54`），但 11 权限 /
  19 模块 / 20 so / 61 行 `replaced=false` / `ENGINE-FINGERPRINT` 全部不变，
  **仍未出声**、A3 保持 `DEEP RUN`。
- 相关既有证据：`analysis/engine-api-audit.json`（`createDataRequestHandler` 返回 `Ljava/lang/Object;`）、
  `analysis/jni-binding-report.json` / `jni-verify-report.json`（`libNMCommonCache.so` 58 个 JNI 符号）、
  `docs/12 §50` / `§51.4` / `§52.4`。

## 7. 12 §56 真机诊断对 §5 判据的修正（2026-11-07 21:35~21:43）

**结论先行**：§2 的类型 / 契约实证全部成立，§4 的实现也确实被 native 拿到；
但 **阶段 A/B 的失败原因不是取流句柄，而是 prepare 阶段报错**。
完整日志与复核表见 `analysis/review-and-diagnosis-2026-11-07.md`。

### 7.1 对 `§55.4` 两方向的判定

- **方向 1（竞品 Java 侧自己调 `NMBufferSource.SetDataRequest`）证伪**：
  `cm-smali5` 全量检索显示 `SetDataRequest` **仅**出现在
  `com/netease/cloudmusic/sdk/NMCommonCache/NMBufferSource.smali`；
  `DataSourcePlatformImpl` 及其内部类没有任何引用，`getDataProvider$1` 只返回 `$handler` 字段。
  真机 `CM-DataReq` 日志进一步证明 native **确实调用** `createDataRequestHandler()`
  并成功 `new UrlDataRequest`（`InitPtr()` 未崩溃、logcat 零 CheckJNI abort）。
- **方向 2（`UrlResult` / `SourceConfig.type` 走本地分支）不是首要原因**：
  `getUrl()` / `getLocalPath()` / `openLocalFd()` 三个回调**一次都没被调用**，
  说明 native 还没有走到取 URL 的那一步。

### 7.2 新证据：prepare 阶段即失败

```
BufferSource open: encryptedType=0
SourceCallback.onError code=1001          ← 新增诊断日志暴露
doPrepareAsync openSource 1
PlayerNotifyEvent msg=100 arg1=1 arg2=1 ; msg=300 arg1=9
CNCMAudioPlayer::Play status 9
```

`getUrl` / `Start` / `openLocalFd` 全程缺失；播放期 PC 音源零 GET、
`1 Tracks of which 0 are active`、MediaSession `state=2`。

### 7.3 字段校准实验（假设排除）

so 的 JNI 字段表只从 `ResourceInfo` 读 `extension`（偏移 708213），
而 V0.1 空壳给 `""`、竞品 smali 构造时该参数经 `checkNotNullExpressionValue` 校验非空。
按 URL 后缀填 `extension=mp3` 后真机复跑，除新增的 `onError code=1001` 日志外
（来自同时加的 `SourceCallback` 探针），**其余日志与对照轮逐行一致** → 假设排除。
另核实 `UrlResult` 的 JNI 字段为 `url / fileSize / expiresAt`（偏移 707672~707753），
`bitrate / md5` 属 `CacheEntry` 区，舱内 `realBitrate` 命名差异 native 不读、无影响。

### 7.4 下一步（替代 §5 的阶段 A/B 判据顺序）

1. 查 `onError(1001)` 的 native 码表（竞品 `onError` 只 `Log.w`，码值在 so）；
2. 核对 `PlaybackEngine(0L)` 的 `cache=0x0`（`NMVirtualFileManager` 为空）与
   `CreateBufferSource` 的前置条件；
3. 核对 `IDataSourcePlatform` / `IPlaybackPlatform` 是否必须注册
   （so 有全套 `Jni*Platform` 符号，舱内两个类仍是空壳）；
4. 校准 `UrlResult.fileSize=0` 与 REMOTE 分支条件；
5. **播放期出现带 Range 的 GET** 后才回到 §5 的阶段 C/D。

**仍未出声**，A3 保持 `DEEP RUN`、舱不冻结、指纹不回填。
