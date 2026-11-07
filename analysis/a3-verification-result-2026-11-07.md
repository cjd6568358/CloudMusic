# A3 真机验证实测报告 · 阶段 A/B（2026-11-07 21:05~21:11）

> **首次判断已纠正**：20:51 曾判定「设备不兼容、验证被硬阻断」，用户指出「手机还是同一个」后复核，
> 确认当时 `adb devices` 只连到一台**乐视电视**，手机无线调试端点断开未重连。
> 详见 `docs/12 §55`。**未切换任何网络配置**（WiFi / 网卡 / 路由），未 `pm grant` 伪造授权。

## 1. 设备与环境

| 项 | 实测 |
|---|---|
| 误连端点 | `192.168.1.21:5555` — `Letv Y43 / MetisFHD`、`ro.build.characteristics=tv`、`persimmon`、ARMv7、1920×1080、API 19 / armeabi-v7a、`com.letv.* / com.stv.* / com.your.iptv` |
| 正确端点 | `adb connect 192.168.1.11:39525` → `Xiaomi 23013RK75C / mondrian`、**Android 13 / API 33 / arm64-v8a**（Redmi K60，§49/§50 同一台） |
| 设备命令 | 全部带 `-s 192.168.1.11:39525`；电视 `pm path com.example.cloudmusic` 始终为空 |
| 旧包 | `lastUpdateTime=2026-11-07 18:17:05`，签名 `5474f219…` |
| 新包 | `./gradlew :app:assembleDebug`（20:54，30 MB，含 §54 代码），签名 `59f9c4cc…` |
| 签名冲突 | `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（两套 debug.keystore）；用户授权后 `uninstall` + `install` → **Success**，`lastUpdateTime=21:05:49` |
| 测试夹具 | 新 Room 库保留 `room_master_table` / `user_version=2`，插入旧库 3 行：`tracks/queue_items/playback_state = 1/1/1`，`source_url=http://127.0.0.1:8765/cloudmusic_g01.mp3`（**夹具，不是应用扫描结果**） |
| 音源 | `adb pull /sdcard/Music/cloudmusic_g01.mp3`（37,824 B）→ PC 临时目录；`python -m http.server 8765`；`adb reverse tcp:8765 tcp:8765`；设备 `curl` → `HTTP 200 bytes=37824` |

## 2. 阶段 A · 类型确认 —— **PASS**

启动 App 后 FGS 通知、MediaSession `active=true` + `mediaButtonReceiver` 正常。
`logcat --pid=19306` 关键序列（零崩溃）：

```
I NMAudioPlayer: JNI_OnLoad Start / JNI_VERSION_1_6
I NMAudioPlayer: AudioDeviceAudioTrack::Init FindClass success
I NMAudioPlayer: FindJavaClass Name(...IBaseDataSource) Success
I NMAudioPlayer: FindJavaClass Name(...NCMNativeAudioPlayer) Success
I NEPlayer: PlaybackEngine ctor: this=... cache=0x0 listener=...
I NEPlayer: AudioPlayerUnit ctor: this=... (effect_engine deferred)
I NCMPlayerEngine: Data request error callback installed isPlayback=1
I NEPlayer: setDataSource notifyTrackActivated: ret=0
W NMAudioPlayer: CNCMAudioPlayer::PrepareAsync
I NEPlayer: BufferSource open: encryptedType=0
W NMAudioPlayer: CNCMAudioPlayer::doPrepareAsync openSource 1
I NEPlayer: PlayerNotifyEvent msg=300 arg1=2 / arg1=3 ; msg=100 arg1=1 arg2=1
```

- `logcat -b crash` **空**；无 CheckJNI abort / `UnsatisfiedLinkError` /
  `NoSuchMethodError` / `ClassNotFoundException`（§50 的三道 abort 未复现）。
- 说明：`SourceDataRequest` 真类、`NMCommonCache` 加载链、`UrlDataRequest` 的接入
  **没有引入新的 JNI 崩溃**，native 引擎可完整构造并 prepare。

## 3. 阶段 B · 取流触发 —— **FAIL**

以 `am broadcast -a android.intent.action.MEDIA_BUTTON -n .../.MediaButtonEventReceiver`
触发播放（§50.1 的免注入手法），日志出现：

```
W NMAudioPlayer: CNCMAudioPlayer::Play status 9
```

但四项判据全部为否：

| 判据 | 期望 | 实测 |
|---|---|---|
| PC 音源播放期 GET | 带 `Range` 的 GET | **零**；唯一记录是 `21:09:14` 的连通性 `curl`（启动 App 前） |
| `dumpsys media.audio_flinger` | active tracks ≥ 1 | `1 Tracks of which 0 are active` |
| MediaSession | 进入播放态 | `state=2`（PAUSED）不变 |
| native 取流日志 | `SetDataRequest` / `Request Start` / `OnWriteNetData` | **全部没有**；`Play status 9` 后 App 进程无其它输出 |

## 4. 结论

把 `createDataRequestHandler()` 从 `null` 改为返回 `UrlDataRequest` 后，
**native 仍未发起取流**，且没有任何证据表明它调用过该方法。

因此 `§53` 的**类型结论正确**（`SourceDataRequest` 子类、不是 `IBaseDataSource`），
但**调用链推断缺了一环**，两个待查方向：

1. 竞品 Java 层（`DataSourcePlatformImpl.buildDataSource`）很可能**自己创建
   `NMBufferSource` 并调用 `SetDataRequest(handler)` / `Open()`**，native 不主动调
   `createDataRequestHandler()`；`libNMCommonCache.so` 的
   `Java_..._NMBufferSource_SetDataRequest` 是 Java→native 方向，支持这一推断。
2. `UrlResult` 字段顺序或 `SourceConfig.type` 判定让 native 走了本地文件分支
   （`openLocalFd()` 恒 `-1`），于是没有网络取流需求。

## 5. 下一步（按证据顺序）

1. **先确认调用与否**：在 `UrlDataProvider.createDataRequestHandler()` /
   `UrlDataRequest.Start()` / `RangeFetcher.fetch()` 加诊断 `Log.i`，重构建后
   `adb install -r`（**签名已统一，不必再卸载，夹具保留**），复跑 A/B 抓 logcat。
   - 有「handler 被创建」但无「Start 被调用」→ 方向 1；
   - 连「handler 被创建」都没有 → 同方向 1，去反汇编竞品 Java 侧接线。
2. 反汇编 `classes5.dex` 的 `DataSourcePlatformImpl`，定位 `NMBufferSource` 创建、
   `SetDataRequest`、`Open()` 的调用序列，补到 `:engine-legacy`。
3. 校准 `UrlResult` 字段与 `SourceConfig.type`（`§50.6` 挂账未清）。
4. **播放期出现 GET** 后才进入阶段 C（回传 / `active tracks`）与 D（出声），
   再逐条跑 G01~G09。

## 6. 诚实边界与现场状态

- **仍未出声**；A3 保持 `DEEP RUN`、舱不冻结、`ENGINE-FINGERPRINT` 不回填；
  阶段 C/D 与 G01~G09 仍 `NOT RUN`。
- 本轮**零源码改动 / 零构建基线变化**：394 测试 / 43 套件、自检 36 / 152 / 15、
  19 模块 / 11 权限 / 20 so / 61 行 csv 均不变。
- 现场保留：两台设备在线、`adb reverse tcp:8765` 隧道、PC `http.server 8765`、
  手机最新 APK（21:05:49）+ 1 条测试夹具 → 后续 `install -r` 覆盖即可复跑。

## 7. 第二轮（12 §56，21:35~21:43）· 诊断探针复跑与结论修正

> 完整复核与原始判据见 `analysis/review-and-diagnosis-2026-11-07.md`。
> **§5 的方向 1 已被证伪**，下面给出修正后的结论。

- **现场**：同一台 Redmi K60（`192.168.1.11:39525`）；PC `http.server 8765` 重启、
  `adb reverse` 仍在、设备 `curl` → 200 / 37824 B；Room 夹具 `1/1/1` 保留；
  `adb install -r` Success（签名已统一，**未卸载、未清数据**）；所有命令带 `-s`，电视未触碰。
- **诊断探针**：`:engine-legacy` 加 `CM-DataReq` 日志（`EngineDiag`、9 个 `DataProvider` 回调、
  `UrlDataRequest.Start/Stop/release` + `RangeSink`、`SourceCallback` 三个 native 回调）。
  **只加日志，不改取流分支**。
- **对照轮关键输出**：
  ```
  prepare trackId=g01-local remote=true url=http://127.0.0.1:8765/cloudmusic_g01.mp3 expectedLength=0
  setDataRequestErrorCallback callback=null enabled=false
  setDataRequestErrorCallback callback=...NativeDataRequestErrorCallback enabled=true
  createDataRequestHandler url=... cached=false
  createDataRequestHandler -> NEW UrlDataRequest
  ```
  → native **确实调用** `createDataRequestHandler()` 并成功创建句柄（`InitPtr()` 未崩溃）；
  但 `getUrl()` / `getLocalPath()` / `openLocalFd()` / `Start()` **一次都没有**。
- **校准轮**（`ResourceInfo.extension=mp3`，依据 so JNI 字段表 + 竞品 smali 非空校验）：
  新增 `SourceCallback.onError code=1001`，其余日志与对照轮**逐行一致** → 假设排除。
- **native 序列（两轮相同）**：`BufferSource open: encryptedType=0` →
  `onError(1001)` → `doPrepareAsync openSource 1` →
  `msg=100 arg1=1 arg2=1` / `msg=300 arg1=9` → `Play status 9`。
- **四项判据仍全为否**：播放期零 GET（PC 音源只有 21:35:08 的连通性 curl）、
  `1 Tracks of which 0 are active`、MediaSession `state=2`、
  logcat 无 `SetDataRequest` / `Request Start` / `OnWriteNetData`。
- **结论**：§53 类型结论与 §54 句柄代码成立，**真正断点在 prepare 阶段**
  （native 报错退出，未进入取流），不在「竞品 Java 侧接线」也不在句柄类型。
  下一步查 `onError(1001)` 码表、`PlaybackEngine(0L)` 的 `cache=0x0`、
  `IDataSourcePlatform` / `IPlaybackPlatform` 空壳注册、`UrlResult.fileSize=0`。
- **基线**：自检 36/152/15 PASS、`./gradlew test` 394 / 43 套件 / 0 失败、
  `:app:assembleDebug` BUILD SUCCESSFUL；19 模块 / 11 权限 / 20 so / 61 行 csv 零变化。
  **仍未出声**，A3 保持 `DEEP RUN`、舱不冻结、指纹不回填、G01~G09 NOT RUN。

## 8. 第三轮（`12 §57`，22:16~22:29）· 阶段 A/B 首次通过

> 完整证据包 `analysis/a3-phase-ab-evidence-2026-11-07.md`；本节只记判据结论。

- **静态**：`capstone` 反汇编把 `onError(1001)` 定位到
  `ncm_player_engine::NMBufferSourceAdapter::Open()` 的多个失败分支（通用码），
  不是单一原因；jadx 真类证明 `PlaybackEngine(long)` 需要 VFM 的 C++ 句柄。
- **四轮实验**（同一设备、`install -r`、夹具保留）：
  E1 传真实 `cacheManagerHandle` → `onError(1001)` 消失、首次 `getUrl` / `CreateBufferSource`，
  但 `size:0` → Memory Mode → `av_probe_input_buffer read error` / `openSource 2`；
  E2 HEAD 探测失败（Android 对 HEAD 的 `contentLengthLong` 恒 -1）；
  E3 加 `usesCleartextTraffic="true"` 后 HEAD 首次到达音源（此前 App 内 `http://` 被拦）；
  E4 后台线程读 `Content-Length` 响应头 + `ResourceInfo`/`UrlResult` 同填 37824。
- **E4 判据**：`probeLength=37824` / `CreateBufferSource size:37824` /
  **`Start id=1 offset=0 length=37824`** / `Range: bytes=0-37823` / `onBegin`+`onData`×5+`onEnd` /
  `Decoder(0): Open input file success` / `Open codec for mp3 48000Hz` /
  **`doPrepareAsync openSource 0`**；PC 音源播放期 HEAD + 多条带 Range 的 GET；
  共 5 次 `Start`（offset 0/8192/16384/24576/32768）。
- **四阶段**：A **PASS**、B **PASS（首次）**、C **未通过**
  （`OnWriteNetData Failed Not Support` ×15、`Send Packet Invalid data error`、
  `doDecoder nErr -16/-541478725`、native 反复重取）、D **未确认**
  （一次播放窗口 `1 active track`、另一次 0；MediaSession 恒 `state=2`）。
- **代码**：`EngineAdapter.kt`（VFM handle / `resolveLength()` / 真实长度 / release 释放）、
  `NMCacheBase.java`（`getNativePtr()`）、`AppBindings.kt`（`@ApplicationContext`）、
  Manifest 明文放行、`check_source_contracts.py` 两处守护改前缀。
- **基线**：自检 36/152/15 PASS、`./gradlew test` 394 / 43 套件 / 0 失败、
  assembleDebug SUCCESSFUL、C3 PASS（11 权限）；19 模块 / 20 so / 61 行 csv 零变化。
- **仍未出声**，A3 保持 `DEEP RUN`、舱不冻结、指纹不回填、G01~G09 NOT RUN。
  下一轮：反汇编 `OnWriteNetData Failed Not Support` 分支、校准
  `OnDataRequestData` 第 2/3 个 long、补事件码状态机。
