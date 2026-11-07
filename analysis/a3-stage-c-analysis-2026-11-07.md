# A3 阶段 C 反汇编分析（2026-11-07 第四十八批 / `12 §58`）

> **一句话**：`OnWriteNetData Failed Not Support` 的唯一条件是 `NMBufferSourceImp+0xb8`
> 处的 bool 为 0；该位在构造时**初值为 1**，只在进入 **Memory Mode** 分支时被清零。
> 进入 Memory Mode 由 `BufferSourceStrategy` 的 bit0 与 `NMVirtualFile` 是否为空共同决定。
> 本轮把机制钉死、证伪了 `cacheLocal=true` 假设并已回退；**阶段 C 仍未通过，仍未出声**。
>
> 承接 `analysis/a3-phase-ab-evidence-2026-11-07.md §5`（A/B 已通过）。
> A3 保持 `DEEP RUN`、舱不冻结、`ENGINE-FINGERPRINT` 不回填、G01~G09 `NOT RUN`。

---

## 1. 反汇编实证（`capstone 5.0.7` + ELF 符号表，`libNMCommonCache.so`）

### 1.1 `Failed Not Support` 的唯一条件

`NMBufferSourceImp::OnWriteNetData(shared_ptr<NMDataBuffer> const&, long, long)`
（符号 `0xbcac`, size 564）开头：

```asm
0xbcd8  ldrb  w8, [x0, #0xb8]     ; this+0xb8 (bool)
0xbcdc  cbz   w8, #0xbdd4         ; == 0 → 打印 "Failed Not Support" 并返回
```

该函数其余分支都有明确日志：`Failed Data Invalid`（buffer 为空）、
`SKIP offset:%lld size:%lld file_offset_:%lld`、`TRIM ... skip:%lld`、
`Write Trimmed Data Failed:%d`、`Write Data Failed:%d`。
`0xbe80 strb wzr,[x19,#0xb8]` 会在写失败后把该位清零。

### 1.2 `+0xb8` 初值为 1，只在 Memory Mode 清零

`NMBufferSourceImp` 构造函数（`0x9164`, size 1012）：

```asm
0x91b8  mov   w8, #0x101
0x91e0  strh  w8, [x0, #0xb8]     ; +0xb8 = 1, +0xb9 = 1
...
0x91fc  mov   x23, x5             ; x5 = BufferSourceStrategy（按值传）
0x92a0  strb  w23, [x19, #0x98]   ; 存策略
0x92c4  stp   x9, x8, [x19, #0xd8] ; +0xd8 = NMVirtualFile shared_ptr
...
0x941c  tbz   w23, #0, #0x9428    ; 策略 bit0 == 0 → Memory Mode
0x9420  ldr   x8, [x19, #0xd8]
0x9424  cbnz  x8, #0x9454         ; NMVirtualFile 非空 → 跳过 Memory Mode
0x9428  mov   w0, #6
0x942c  strb  wzr, [x19, #0xb8]   ; ← 清零：OnWriteNetData 从此 Not Support
0x9448  add   x2, x2, #0x649      ; "NMBufferSourceImp(%lld)::Start Use Memory Mode."
```

**结论**：只有 `BufferSourceStrategy & 1 == 1` **且** `NMVirtualFile != null` 才不进
Memory Mode；两者任一不满足，`+0xb8=0`，Java 侧 `OnDataRequestData` 的字节全部被拒。

### 1.3 JNI 回传链

`SourceDataRequest` 的 5 个 JNI 符号齐备（`InitPtr` / `DeletePtr` /
`OnNativeDataRequestBegin/Data/End`）。`Java_..._OnNativeDataRequestData`
（`0x347f4`, size 360）把最后一个 `long` 作为 buffer size 创建 `NMDataBuffer`，
再经虚表调用。因此 `OnDataRequestData(J[BJJ)` 的**第 4 个参数 = 本次长度**与
`§2.5` 实证一致；第 2/3 个 long 的语义仍未最终校准（`SKIP offset/size/file_offset_`
日志是下一轮判据）。

---

## 2. 真机实验 E5：`cacheLocal=true`（假设证伪，已回退）

依据 `1.2`，`cache config {"cache_local":false}` 会让 `NMVirtualFile` 为空。
把 `SourceConfig.cacheLocal` 临时置 `true` 后（同一台 K60、`install -r`、夹具保留）：

```
NMVirtualFileManager::CreateBufferSource resource config:{"id":"g01-local","md5":"",
  "size":-1,"type":"cache"}, cache config:{"range_size":1048576,"cache_local":true}
NMBufferSourceImp Create:1 / NMMemoryCache Create:1
W NMSliceFile Open Failed. Root Dir Not Init.
E NMBufferSourceImp(1)::Open failed(-10), local cache unavailable.
CNCMAudioPlayer::doPrepareAsync openSource 2
```

- 结果比基线更差：零 `Start`、`openSource 2`、无解码器打开。
- `SetConfig(String)` 的反汇编显示它有 `Config Error. Dir Empty` /
  `Create Dir Failed` 校验 → 启用本地缓存必须先初始化缓存根目录；
  而我们的 `zv.a.b(Context,...)` slot 只做 `System.loadLibrary`，**丢弃了 Context**。
- `01 §6.1` 明确「缓存不落盘」；启用本地缓存属于产品口径变更，不能擅自做。
- **已回退 `cacheLocal=false`**，`SourceConfig` 只保留实验结论注释，行为与 §57 一致。

---

## 3. 现场网络问题（本轮新增，需下一轮排查）

后续复跑出现 `probeLength FAILED SocketTimeoutException: failed to connect to
/127.0.0.1 (port 8765) ... after 5000ms`，而同一时刻设备 `curl` 仍 `HTTP 200 / 37824 B`。

已逐一排除并如实记录：

| 尝试 | 结果 |
|---|---|
| 重设 `adb reverse --remove tcp:8765` + `reverse tcp:8765 tcp:8765` | 设备 curl 正常，App 仍超时 |
| PC 音源换 `ThreadingHTTPServer`（原单线程 `http.server`） | 同上 |
| 探测改 GET / `Range: bytes=0-0` / 显式 `responseCode` / 8s 超时 | 同上；确认与请求方法无关 |
| 检查 Manifest `usesCleartextTraffic="true"` | 仍在（C3 PASS） |

→ 属 **App 进程侧的网络可达性/系统省电策略问题**（MIUI 日志见
`MiuiNetworkPolicy` / `GreezeManager frozen fail` / `SmartPower ... inactive service`），
不是取流协议问题。`§57 E4` 的 A/B 成功记录仍然是有效实证。

代码已恢复到 `§57 E4` 已验证成功的 **HEAD 探测**形式，只保留
`responseCode` 显式触发连接与 8s 等待两处健壮性增强（不改语义）。

---

## 4. 本轮代码改动（**无生产行为变化**）

| 文件 | 改动 |
|---|---|
| `engine-legacy/.../EngineAdapter.kt` | `resolveLength()` 保留 HEAD 形式，增加 `responseCode` 显式连接、超时 3s→8s；注释记录 §58 实验与现场问题 |
| `engine-legacy/.../playerengine/SourceConfig.java` | `cacheLocal` 实验后回退为 `false`，只留结论注释 |

`check_source_contracts.py`、Manifest、DI、测试均未改。

---

## 5. 阶段 C 的两条后续路径（需用户拍板 / 更多实证）

1. **补齐完整 `NMSourceInfo`**（推荐，不改变产品口径）：反编译竞品
   `DataSourcePlatformImpl.getResourceInfo()`，还原 `resource config` JSON 的
   完整字段（`file_id` / `trunks` / `slices` / `ext` / `type` 等键名已在 so
   的字符串表中），让 `CreateBufferSource` 解析出网络策略（`bit0=1`），
   从而不进 Memory Mode。当前最小 slot 只产出
   `{"id","md5","size","type":"cache"}`。
2. **启用本地缓存目录**：调用 `NMVirtualFileManager.SetConfig(...)` 初始化
   Root Dir 并保持 `cacheLocal=true`。但这与 `01 §6.1`「缓存不落盘」冲突，
   **必须先由用户拍板**是否改为允许 App 内部缓存目录。

无论哪条，判据都是：logcat 不再出现 `Start Use Memory Mode` /
`OnWriteNetData Failed Not Support`，且 `Decoder` 不再报
`Send Packet Invalid data error` / `doDecoder nErr`，播放窗口内稳定出现
本 App 的 `active track ≥ 1` 与 MediaSession 非 `state=2`。

---

## 6. 验证基线

- 静态自检 **36 / 152 / 15 PASS**；`./gradlew test` **394 / 43 套件 / 0 失败**；
  `:app:assembleDebug` BUILD SUCCESSFUL；dev 守护 6/6 PASS、C3 PASS（11 权限）。
- 19 模块 / 11 权限 / 20 so / 61 行 csv / 51 素材零变化。
- 所有设备命令带 `-s`，电视未触碰；未切网络、未伪造授权、未卸载、夹具保留。
- **仍未出声**：A/B PASS（§57 E4 实证）、C 未通过、D 未确认；
  A3 保持 `DEEP RUN`、舱不冻结、指纹不回填、G01~G09 NOT RUN。
- 第四十二~四十八批工作区**仍未 commit / push**。

现场：手机装的是 §58 最终代码（HEAD 探测 + `cacheLocal=false`），
`adb reverse tcp:8765` 已重设、PC 多线程音源服务运行中、夹具 `1/1/1` 保留。
logcat 快照在系统临时目录 `cm-a3-exp7.log` / `cm-a3-restore*.log`（未入库）。

> AI生成
