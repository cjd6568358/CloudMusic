# A3 阶段 A/B 首次通过 · 证据包（2026-11-07 第四十七批 / `12 §57`）

> **一句话**：把 `cacheManagerHandle`、明文 HTTP、真实文件长度三处补齐后，
> native 首次调用 `Start()` 并发出带 `Range` 的 GET，解码器成功打开 MP3、
> `prepare` 返回 `openSource 0`。**阶段 A/B 通过；阶段 C 数据回传仍被
> `OnWriteNetData Failed Not Support` 挡住；阶段 D 未确认出声。**
>
> 承接 `analysis/review-and-diagnosis-2026-11-07.md §5` 的按证据顺序五步。
> **仍未出声**，A3 保持 `DEEP RUN`、舱不冻结、`ENGINE-FINGERPRINT` 不回填、
> G01~G09 `NOT RUN`。

---

## 1. 静态实证：`onError(1001)` 到底来自哪里

用 `capstone 5.0.7` 反汇编 `libncmplayerengine.so` + ELF 符号表：

- 搜索 `mov wN, #0x3e9`（1001）的机器码，共 10 处命中；
- 其中 4 处（0x424fc / 0x42558 / 0x425b0 / 0x42d9c）落在
  **`ncm_player_engine::NMBufferSourceAdapter::Open()`**（符号 0x42350，size 5572）；
- 典型片段：
  ```
  ldr  x0, [x21, #0x40]     ; ISourceCallback*
  ldr  x8, [x0]             ; vtable
  mov  w1, #0x3e9           ; 1001
  ldr  x8, [x8, #0x18]      ; OnError(int)
  blr  x8
  ```
- **结论**：1001 是 `Open()` 多个失败分支共用的通用错误码（某虚调用返回 0 即报），
  不是单一原因；§56 记录的 `SourceCallback.onError code=1001` 由此得到解释。

其余实证：

| 项 | 依据 |
|---|---|
| `PlaybackEngine(long cacheManagerHandle)` | jadx 真类 `PlaybackEngine.java`：`nativeHandle = nativeEngineCreate(j)`；`Playback.java` 的 `nativeCreate(platform, cacheManagerHandle, listener)` 同参数 |
| VFM 是真实 C++ 对象 | jadx `NMVirtualFileManager(Context)`：只做 `loadNative + InitPtr()`；`libNMCommonCache.so` 有 `InitPtr` / `CreateBufferSource` 等 23 个 `Java_*` 符号；竞品 dex 含 `getNativePtr` |
| `OnWriteNetData` 判据 | `libNMCommonCache.so` 字符串：`Failed Not Support` / `Failed Data Invalid` / `SKIP offset:%lld size:%lld file_offset_:%lld`；`NMBufferSourceImp` 构造带 `BufferSourceStrategy` |
| 明文流量 | Manifest 原本无 `usesCleartextTraffic`，targetSdk≥28 默认禁明文；PC 音源服务器在 E3 前**零 HEAD 记录** |

---

## 2. 四轮真机实验

设备 `192.168.1.11:39525`（Redmi K60 / Android 13 / API 33 / arm64-v8a），
所有命令带 `-s`，乐视电视未触碰；`install -r`（未卸载、夹具保留）、
`adb reverse tcp:8765` + PC `http.server 8765`；音源 37,824 B。

| 轮 | 最小改动 | `CM-DataReq` / native 结果 |
|---|---|---|
| E1 | `NMCacheBase.getNativePtr()`；DI 注入 `@ApplicationContext`；prepare 创建 `NMVirtualFileManager` 并把 `native_ptr_` 传给 `PlaybackEngine` | `NMVirtualFileManager: Alloc`；`PlaybackEngine ctor cache=0xb400…`（非 0）；**`onError(1001)` 消失**；首次 `getUrl` / `onUrlReady` / `CreateBufferSource`。但 `size:0` → `Start Use Memory Mode` → `av_probe_input_buffer read error` → `openSource 2` / `Play status 9` |
| E2 | `UrlResult.fileSize` 未知时 HEAD 探测 | `probeLength=-1`；`size:0` 不变（Android 对 HEAD 的 `contentLengthLong` 恒 -1） |
| E3 | Manifest 加 `usesCleartextTraffic="true"` | HEAD 首次到达音源（22:26:28 `HEAD 200`）；此前请求被 `NetworkSecurityPolicy` 拦截、服务器日志零记录 |
| E4 | 后台线程 + `CountDownLatch` 探测，改读 `Content-Length` 响应头；`ResourceInfo.fileSize` / `UrlResult.fileSize` 同填 37824 | **见下节** |

### E4 关键日志（阶段 A/B 判据）

```
cacheManagerHandle=-5476376656281165952 hasContext=true
probeLength=37824
resourceInfo extension=mp3 fileSize=37824
createDataRequestHandler -> NEW UrlDataRequest
getUrl url=http://127.0.0.1:8765/cloudmusic_g01.mp3 fileSize=37824
NMVirtualFileManager::CreateBufferSource resource config:{"id":"g01-local","md5":"","size":37824,"type":"cache"}
Start id=1 offset=0 length=37824
open url=... Range=bytes=0-37823
open result total=37824 contentLength=37824
onBegin id=1 total=37824 → onData ×5 → onEnd id=1 error=null
SourceCallback.onProgress 37824/37824
Decoder(0): Open input file success! / Open codec for mp3
Decoder(0): Codec ID - 86017 Audio: mp3, 48000 Hz, mono, s16p, 64 kb/s
CNCMAudioPlayer::doPrepareAsync openSource 0
```

PC 音源服务器（播放期，非连通性检查）：
```
22:26:28  HEAD /cloudmusic_g01.mp3 200
22:26:28  GET  /cloudmusic_g01.mp3 200   (×2)
22:27:23  HEAD /cloudmusic_g01.mp3 200
22:27:23~24 GET /cloudmusic_g01.mp3 200 (×4)
```
`CM-DataReq` 共记录 **5 次 `Start`**（offset 0 / 8192 / 16384 / 24576 / 32768），
`Range` 头与 `§2.5` 实证的 `j2=offset / j3=length` 完全一致。

---

## 3. 四阶段判据（`evidence §5`）

| 阶段 | 状态 | 判据 |
|---|---|---|
| A 类型确认 | ✅ **PASS** | `createDataRequestHandler()` 被调、`UrlDataRequest` 构造成功（`InitPtr()` 无崩溃） |
| B 取流触发 | ✅ **PASS（首次）** | 播放期出现带 `Range` 的 GET；`Start` 实参可核对；`prepare` 返回 `openSource 0`、解码器打开 MP3 |
| C 数据回传 | ❌ **未通过** | `NMBufferSourceImp::OnWriteNetData Failed Not Support` **15 次**、`OnFlushNetData Failed Not Support`、`Send Packet Invalid data error`、`doDecoder nErr -16/-541478725`；`Start Use Memory Mode` 仍在，native 从递增 offset 反复重取 |
| D 出声 | ⚠️ **未确认** | 一次播放窗口 `4 Tracks of which 1 are active`、另一次 0；MediaSession 恒 `state=2`；**不能归因本 App，不宣称出声** |

> **诚实边界**：阶段 A/B 通过 ≠ 出声。native 事件码表、MediaSession 状态回传、
> `OnDataRequestData` 的第 2/3 个 long 语义仍待校准。

---

## 4. 代码改动与基线

| 文件 | 改动 |
|---|---|
| `engine-legacy/.../EngineAdapter.kt` | `appContext` / `virtualFileManager`；`resolveLength()`（后台线程 HEAD + latch，读 `Content-Length`）；`ResourceInfo`/`UrlResult` 填真实长度；`PlaybackEngine(cacheHandle)`；`release()` 释放 VFM |
| `engine-legacy/.../NMCommonCache/NMCacheBase.java` | 只读 `getNativePtr()` |
| `core-di/.../AppBindings.kt` | `provideMusicEngine(@ApplicationContext)` + `EngineAdapter().also { … }`（零参数换绑语义不变） |
| `app/src/main/AndroidManifest.xml` | `usesCleartextTraffic="true"`（**11 权限不变**，C3 PASS；PRD `01 §6.1` HTTP 音源必需） |
| `tools/check_source_contracts.py` | §36 两处 DI 守护改前缀匹配 |

**基线**：自检 `36 / 152 / 15 PASS`、`./gradlew test` **394 / 43 套件 / 0 失败**、
`:app:assembleDebug` 多次 BUILD SUCCESSFUL、`check-permissions.py` C3 PASS；
19 模块 / 11 权限 / 20 so / 61 行 csv / 51 素材零变化；
dev 6/6 与 release FAIL(C1,C4) 本轮未复跑（沿用 §54/§55 基线）。

---

## 5. 下一步（按证据顺序）

1. 反汇编 `NMBufferSourceImp::OnWriteNetData` 的 `Failed Not Support` 分支，
   确认 `BufferSourceStrategy` / Memory Mode 判定与 `resource config {"type":"cache"}` 的来源；
2. 校准 `OnDataRequestData(J[BJJ)` 第 2/3 个 long（`evidence §3` 未决项），
   以 `SKIP offset/size/file_offset_` 与 `Failed Data Invalid` 为判据；
3. 核对 `IDataSourcePlatform` / `IPlaybackPlatform` 空壳是否影响缓存策略；
4. 按 `13 §7.1` 事件码表把实测 `msg=300 arg1=4`、`msg=200/7/8/101/6002` 补进状态机
   （MediaSession 恒 `state=2` 的自研侧回传缺口）；
5. **阶段 C/D 通过**后才跑 G01~G09；此前不回填指纹、不冻结舱。

## 6. 现场

手机已装含 §57 全部改动的 debug 包（22:26 之后），Room 夹具 `1/1/1` 保留，
`adb reverse tcp:8765` 与 PC `http.server 8765` 在运行；logcat 快照在系统临时目录
`cm-a3-exp3/4/5/6.log`、`cm-a3-play.log`（未入库），下轮 `install -r` 即可复跑。
第四十二~四十七批工作区**仍未 commit / push**。

## 7. 后续（`12 §58`，2026-11-07 22:37~23:00）

用户「继续」后继续攻阶段 C，已完成 `Failed Not Support` 的机制定位：
`NMBufferSourceImp+0xb8` 初值为 1，只在 Memory Mode 分支被清零；
进入 Memory Mode 由 `BufferSourceStrategy` bit0 与 `NMVirtualFile` 是否为空决定。
`cacheLocal=true` 实验因缺缓存根目录失败并已回退；后续现场出现 App 到回环音源的
TCP 连接超时（非协议问题），代码恢复本证据包 E4 已验证的 HEAD 探测形式。
**阶段 A/B 的 E4 成功记录仍有效；阶段 C 未通过、D 未确认出声。**
完整反汇编、实验与两条后续路径见 **`analysis/a3-stage-c-analysis-2026-11-07.md`**。

> AI生成

## 8. E9（`12 §59`，2026-11-08 · kind 槽校准修复后复验）

修复：`UrlDataRequest.sink.onData` 的 `OnDataRequestData` 首 long 由 requestId 改为
常量 `NATIVE_DATA_KIND_NORMAL = 1L`。实证链（`analysis/route1_disasm.py` 可复跑）：

- JNI `OnNativeDataRequestData`（0x347f4）→ 分发 0x36f68 → `NMBufferSourceImp`
  次级基类虚表槽 +0x18（thunk 0xbee0）→ **`OnNetSourceData(int kind, buffer,
  offset, len)`**；`kind==2` 跳过 `NMMemoryCache::Write`（0xfe48）。
- `OnWriteNetData`（0xbcac）/ `OnFlushNetData`（0xc068）在 `.text` 中**无任何直接
  bl**，实经 **GOT（GLOB_DAT 0x4bfd8 / 0x4bee8）→ 0x60 字节 lambda 任务 → 0x16468
  队列**异步调用 —— 解开 §58"零调用方 vs 15 条日志"的矛盾（E4 两条日志都在）。
- `NMMemoryCache`（对象 +0xc8，构造无条件创建）是**绝对寻址 FIFO 管道**：写须
  `offset == 队头+未读量`、读须 `offset == 队头`，错位静默忽略、无随机访问。

E9 实测（`cm-a3-exp9.log`，未入库；`install -r` / 夹具保留 / 音源 200 37824B）：

```
Start id=1 (0,37824) → onData ×5（kind=1 全部落缓存）
Read((1-1) Finish Async:24576
Decoder(0): Open input file success! → Open codec for mp3
  Codec ID - 86017 Audio: mp3, 48000 Hz, mono, s16p, 64 kb/s
doPrepareAsync openSource 0 / mDuration 4704
AudioTrack(0): Alloc. → Create AudioTrack: 30752, 48000Hz
Read(2)@24576 → 部分读 13248B 恰逢 EOF（offset+read ≥ size → -1）
→ 读侧重试 (24576,13248) ×7（重写被 FIFO 静默拒绝，head 不回退）
→ Retry Failed → Read((1-7) Finish Async:-103 → doDecoder nErr -5
```

**记账**：A/B 再确认 PASS；阶段 C 卡点 = Memory Mode 缺随机访问能力的 native 级
缺陷（FIFO 管道 + 尾部 EOF 部分读），Java 侧不可修复；路线 1 证伪
（`getResourceInfo` 仅 4 字段构造、builder 硬编码 4 键 type 恒 "cache"、
策略 bit0 = cache config 的 `cache_local`）。**URL 源出声只剩路线 2
（`SetConfig` 根目录 + `cacheLocal=true`），须用户对 01 §6.1 拍板。**
仍未出声，A3 `DEEP RUN`、舱不冻结、指纹不回填。

> AI生成

## 9. E10 系列（`12 §60`，2026-11-08 · LOCAL 文件源真机链路）

数据链（零 Java 改动，真机验证通过）：DataStore 种子 `local` 存储（root=App 内部
files 目录）→ 驱动注册 → MediaStore 通道无权限查询空 → `Obj` 兜底 →
`Link(localPath=纯路径)` → `SourceConfig(LOCAL)`。引擎走 `NMBufferSourceAdapter`
`+0x48==0` 分支 → `CreateLocalSource` → `XorLocalSource`（`NoneDecryptor::Decode=ret`
空透传）。

| 实验 | 文件 | 结果 |
|---|---|---|
| E10 | g01.mp3 37824B | probe/codec mp3 ✓ `openSource 0` ✓ AudioTrack ✓ → 首包 `nErr -541478725` → EOS（3ms） |
| E10b | mp3+64KB 填充 | `Send Packet Invalid data error, Ignored` → 同错 |
| E10c | 自造 WAV 96044B | `Open codec for pcm_s16le` ✓ → 同错（格式无关） |
| E10d | WAV+1.2MB 填充 | 同错（大小无关） |

排除（均有反汇编实证）：解密器（NoneDecryptor::Decode=ret）、AVIO read/seek/
AVSEEK_SIZE 回调、Size=-1（Open 用 lseek(SEEK_END) 正确设置）、EncryptedType 变体
（NCM 需魔头；Xor163/XorKey 损坏裸文件）。根因收敛：`XorLocalSource::Read` 裸
`read(fd)` 无位置串行化，流层 bufferFill 线程与解码线程并发读同一 fd → 解码器首读
命中 EOF 偏移（错误映射表 0xdf05a0：Read=-1 → `0xDFB9B0BB`）→ EOS。与 E9 的
NMMemoryCache FIFO 错位同族，均属 native 顺序位置管理缺陷，Java 侧不可修复。

竞品本地播放入口候选：libncmaudioplayer 的 **AudioSourceAndroid**（JNI 源，虚表在位）
—— 下轮反汇编其 Read/JNI 通路定论；若成立，则按它重构本地源接线（实现
`openLocalFd`：ContentResolver.openFileDescriptor + detachFd）。路线 2 仍待用户对
`01 §6.1` 拍板。设备已回滚基线（DB/md5/DataStore/WAL 均复原）。仍未出声，
A3 `DEEP RUN`、舱不冻结、指纹不回填。

> AI生成

## 10. AudioSourceAndroid 排除与 LOCAL 路线判死（`12 §60.6`，60 批续）

- `AudioSourceAndroid`：无 DataProvider JNI 字符串 → 非本地文件通路。
- `CNCMAudioLocalSource`（fopen/fread，EOF=4）：无虚表 = 死代码。
- 解码器 AVIO opaque = `[decoder+0x128]` = 源对象直连（无缓存包装）。
- **LOCAL 路线判死（该 so 版本）**：fd 顺序位置被流层预读与解码器竞争，
  与 E9 同族；`SetSourceMode` 为空桩，无 Java 侧杠杆。
- **唯一残余路径 = 路线 2**（`SetConfig` 根目录 + `cacheLocal=true`），
  待用户对 `01 §6.1`「缓存不落盘」拍板。

> AI生成

## 11. E11/E11b（`12 §61`，2026-11-08 · 路线 2 落地，normal mode 缓存管线全通）

用户拍板批准路线 2（`01 §6.1` 口径演进：App 内部缓存子目录、512MiB 上限）。
实现：`SourceConfig.cacheLocal=true` + `EngineAdapter.SetConfig`（键名反汇编钉死：
dir / max_size / slice_size / cache_clean_range_min|max —— E11 未传清理区间键触发
`Cache Clean Range Invalid` 警告，E11b 显式传入 0.8/1.0 后消失）。

E11b 实测（install -r、夹具保留）：

```
SetConfig Finish:{"dir":"…/cache/legacy_engine_cache","max_size":536870912,…}
CreateBufferSource … cache config:{"range_size":1048576,"cache_local":true}
NMSliceFile Open Init Use Old:g01-local（根目录/切片文件就绪）
Start id=1 (0,37824) → 5 push → Read(1) Finish Async:32768
NMSlicesFile Write all finish        ← 全文件写入切片缓存
g01-local_0.nmsf = 37824 字节（真机落盘验证）
Read(2) Finish Async:5056            ← E9 尾部错位消失
Start Use Memory Mode / Failed Not Support / Send Packet Invalid：全部不再出现
Open codec for mp3 ✓ → 首包 nErr -541478725 → EOS ✗（唯一残余阻塞）
```

- `-541478725 = 0xDFB9B0BB` = libncmaudioplayer 的「源 Read 返回 -1（EOF）」映射；
  exp6/exp9/E10/E11 同点位复现 → 与源模式无关，位于 CNCMFFDecoder 胶水层
  （`avformat_find_stream_info` 消费到 EOF 后，首包读取未回退到首帧位置）。
- 下轮：反汇编 openWithSource 的 post-open 序列 + getAudioPCM/ReadAudioFrame 的
  错误条件 + 0x70 包装类虚表布局（slot+0x28 是 Seek 还是 Size）。
- 设备状态：缓存目录落盘正常（卸载即清）；A3 `DEEP RUN`、舱不冻结、指纹不回填。

> AI生成

## 12. AVIO 虚表布局错位发现（`12 §61.5`，61 批续）

解码器 AVIO 三回调调用源对象槽 +0x20/+0x28/+0x50（INCMSourceIO 布局，以
XorLocalSource 虚表核对全吻合）。`NMBufferSourceImp` 虚表为另一布局
（entries[7]=Size、entries[12]=SourceID）：若 opaque 直连 imp，则 **seek 回调命中
Size()、AVSEEK_SIZE 命中 SourceID()**——回调把请求偏移原样返回（sp[0] 未被 Size
触碰）→ ffmpeg 以为 seek 成功 → 实际读位置仍在 EOF → 首包 Read=-1 →
`0xDFB9B0BB(=-541478725)` → EOS。与 E11 日志吻合（无任何 Seek 日志、写缓存正常）。
0x70 包装类 = shared_ptr_emplace<NMBufferSourceHandlerImpl>（handler 控制块，
非源包装）已排除。未定论：适配器产物（[x21+0x28]）是裸 imp 还是 IO 适配对象。

> AI生成

## 13. E12 系列与灭屏冻结、缓存命中缺陷（`12 §61.6`，61 批续）

- E12c（duration_ms=37824 绕过 probe）：prepare 即时完成（openSource 0），解码循环
  80ms 内消费完整源 → 源 EOF（-1 → `0xDFB9B0BB`）→ EOS，PCM 仅缓冲 1008ms/4704ms
  → Play 在 EOS 后下发为空操作（`0 active tracks`）。
- E12d/E12e/E13/E13b：被灭屏网络冻结打断（`mWakefulness=Dozing` + GreezeManager）；
  设备 curl 200 而应用超时（§58.3 根因确认）；`input keyevent` 无 INJECT_EVENTS
  （无线 adb 限制），屏幕无法远程唤醒。
- **缓存命中缺陷**：E11 落盘的完整缓存（.nmsf 37824B + .nmsfi 160B）在命中路径
  `ReadFromFileNoLock readed_len:0, result:-2` —— 引擎有完整缓存却读出 0 字节。
  下轮：无 force-stop 的完整周期验证缓存命中；.nmsfi 提交差异分析；若为索引未
  提交，评估引擎 Stop 提交路径。缓存命中若可用，播放免网络、免疫灭屏冻结。

> AI生成

## 14. 缓存命中缺陷确认 + 设备离线（`12 §61.7`，61 批末）

- **缓存命中缺陷在 E11b 日志中已复现**（E11 落盘 37824B → E11b `Use Old` 索引加载
  成功 → `ReadFromFileNoLock readed_len:0, result:-2`）—— 有缓存读 0 字节，非新问题
  而是可稳定复现的独立缺陷。
- 设备无线 adb 掉线（灭屏深睡，WiFi/adbd 挂起；`connect` 重试 100s 失败）——
  .nmsfi 索引拉取与缓存命中专项阻塞，需物理唤醒/解锁设备。
- 静态旁证：imp 的 Open 调 vfile slot+0x10 成功（无 "Open failed" 日志）；失败在
  NMSlicesFile 按索引切片表读 .nmsf 数据文件的环节。

> AI生成

## 15. E14：缓存命中物理全通（`12 §61.10`，61 批续）

**Phase 1**（首次播放）：duration_ms=37824 绕过 probe → 5 push →
`NMSlicesFile Write all finish` → `.nmsfi`(JSON)/`.nmsf`(37824B) 落盘。

**Phase 2**（断开 reverse 免网络）：
```
NMSliceFile Open Init Use Old:g01-local           ← 索引命中
ReadFromFileNoLock readed_len:32768, offset:0     ← 缓存命中 ✓
openSource 0                                       ← prepare ✓
ReadFromFileNoLock readed_len:5056, offset:32768  ← 尾段 ✓
doDecoder nErr -541478725 → EOS                   ← 解码层阻塞（唯一残余）
```

**里程碑**：缓存写入/命中/数据管线三环节全部物理验证通过。`.nmsfi` 为 JSON
格式。残余 `-541478725` 与源管线无关（三种模式同点复现），100% 在 CNCMFFDecoder
层—— mp3 demuxer → packet → codec 链路中的某一环节。下轮从解码器内部定位。

> AI生成

## 16. 🎉 E17：判据 D 出声确认（`12 §61.14`，61 批收官）

**根因**：play 命令从未到达 native 播放器（`Media button session is null`），
解码管线自始至终工作正常。通过 PlaybackService `ACTION_PLAY` intent 直接触发：

```
AudioTrack(0): Write start play nErr:0     ← PCM 写入 AudioTrack
AudioTrack::doRenderFast                    ← 渲染循环
onEngineEvent msg=2000 arg1=357→4257       ← 播放进度 357→4257ms
bufferFill EOS                              ← 解码自然 EOF
No more data to push                        ← PCM 全部渲染
AudioTrack::doRenderFast kAudioPlayerEOS    ← 渲染完成
CNCMAudioPlayer::doPlayCompleted            ← 播放完成 ✓
msg=2                                       ← "播放完成"通知 ✓
```

**A3 状态：A/B/C/D 全部 PASS。** 照搬舱引擎真机出声确认。

> AI生成
