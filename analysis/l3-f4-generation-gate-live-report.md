# F4 · 请求代次闸门真机实证结论（`12 §61.68`）

> **2026-11-11 凌晨 第九十五批** · 承接 `12 §61.67` 剩余断点①：
> 「设备在线后开 level-5 日志做一次代次早退 / 落盘门控真机实证，把 `inference` 升级为实证」。
> **纯观测、零 P0 源码改动、未重编译 / 未重装 APK、未改动用户网络**；
> 设备经 `adb pair`（配对码不落盘）+ `adb connect 192.168.1.11:41703` 上线。
> 机器证据：`analysis/l3-f4-generation-gate-live-audit.json`；复跑：
> `python tools/l3_f4_generation_gate_live_audit.py`。

---

## 1. 结论先行

**已升级为运行时一手实证**：§61.67 反汇编定位的「`MakeRangeRequestAsync`
每次重发把 `[this+0xC0]` 代次 +1（`0x104B0`/`0x104B8`）并经 `0x104D8`
透传给请求回调」，在本轮真机 logcat 中被**逐次直接坐实** ——
`NMNetSource::MakeRangeRequestAsync(N)` 的 `N` 从 **1 连续递增到 22**，
`NMNetSource(1-K)` 实例序号同步递增，每次 `WaitRequest ... force:true`
重发都对应一个新代次。

**仍未升级**：`0x10798 b.ne 0x108E0` 整包丢弃分支与 `NMSlicesFile::Write`
的 `offset+len <= file_len` 门控，**没有直接的分支日志**。
本轮用 `setprop log.tag.* VERBOSE` 零侵入试探，证实 level-5 分支日志
**不是** liblog 优先级过滤（`D/NMCache` 80 条 / `I/NMCache` 72 条仍在，
但 `NMSlicesFile` / `OnWriteNetData` / SKIP / TRIM / `UnExpect New Offset`
两轮全 0）；真正开关是 native 的
`NMVirtualFileManager.SetLoggerCallback(INMLogger, int)` 的 **level 参数**，
开启须改 Java 源码 + 重编译 + 重装 APK（会动 P0 舱体），**本轮未做、待拍板**。

> **证据等级提升 ≠ 修复 ≠ 验证。F4 仍 BLOCKED。**

## 2. 设备与端点（用户 00:36~00:42 提供，如实登记）

| 项 | 值 |
|---|---|
| 调试端点 | `192.168.1.11:41703`（`adb connect` 成功） |
| 配对端点 | `192.168.1.11:38357`（`adb pair` 成功；**配对码不落盘**） |
| 设备 | Redmi K60 `23013RK75C` / `mondrian` |
| 系统 | Android 13 / API 33 / `arm64-v8a` |
| 本机网段 | WLAN `192.168.1.19/24`，网关 `192.168.1.1` |
| 已装 APK | `com.example.cloudmusic`，`lastUpdateTime 2026-11-10 13:03` |

**排查订正**：首个端口 `41703` 单独 `adb connect` 被拒，但 TCP 端口连通
（`Test-NetConnection = True`）→ 判定 `41703` 是**调试端口**、用户随后给的
`11:38357` 是**配对端口**；Android 11+ 无线调试须先 `adb pair` 再 `adb connect`。
`41417` / `41761` / `10.0.0.2:*` 均不可达（10060 / 拒绝连接），
两版本 adb（系统 `35.0.2` / 项目 `37.0.1`）+ 重启会话复核一致。
**未擅自切换用户网络**（WiFi / 网卡 / 路由）。

## 3. 方法（两轮纯观测）

复用 `tools/l3_f4_vfile_commit_probe.py`（`12 §61.62` 既有框架，零代码改动）：
PC `http.server` 8765 + `adb reverse tcp:8765` + `ACTION_PLAY` 注入
`f3_m4a_complete.m4a`（16,573,387 B），`logcat -v time` 流式抓全量，
`finally` 无条件恢复 `adb reverse` / 临时 `wifiOnly` 设置 / HTTP 服务 /
logcat 子进程；设备系统设置不变。

| 轮次 | 窗口 | 日志 | 证据 JSON | 变量 |
|---|---|---|---|---|
| ① 基线 | 60 s | `analysis/l3-f4-logs/vfile-commit-20261111-004310.txt`（6725 行） | `analysis/l3-f4-generation-gate-live.json` | 默认 |
| ② level-5 试探 | 35 s | `analysis/l3-f4-logs/vfile-commit-20261111-004603.txt`（5404 行） | `analysis/l3-f4-generation-gate-live-lvl5.json` | 先 `setprop log.tag.{NMCache,NMCommonCache,NMNetSource,NMSlicesFile,NCMCache,NMCacheLog} VERBOSE`，跑完清空恢复 |

两轮均 `verdict=OBSERVED`、`restore.device_settings_unchanged=true`。

## 4. 一手运行时证据（轮①）

### 4.1 代次递增与透传（§61.67 结论 A 坐实）

```
11-11 00:43:15.584 I/NMCache: NMNetSource::MakeRangeRequestAsync(1), start result:0, offset:16384,  length:1048576
11-11 00:43:15.586 I/NMCache: NMNetSource(1-1)::WaitRequest offset:327895 ... force:false
11-11 00:43:15.586 I/NMCache: NMNetSource::MakeRangeRequestAsync(2), start result:0, offset:327895, length:1048576
11-11 00:43:17.951 I/NMCache: NMNetSource(1-2)::OnEnd() result:
11-11 00:43:17.951 I/NMCache: NMNetSource(1-2)::WaitRequest offset:327895 ... force:true
11-11 00:43:17.951 I/NMCache: NMNetSource::MakeRangeRequestAsync(3), start result:0, offset:327895, length:1048576
  … 依次到 MakeRangeRequestAsync(22) …
```

- **`MakeRangeRequestAsync(N)` 序列 = `1 2 3 … 22`，严格连续递增 22 次**；
- `NMNetSource(1-K)` 实例序号 `1-1 → 1-9+` 同步递增；
- 每次 `WaitRequest ... force:true` 重发都紧跟一个新代次，与 `0x104B0`
  递增点 / `0x104D8` 透传 / `0x104E8` 发起请求逐条对齐；
- `start result:0` 恒为成功（异步发起，与 §54 `Start(JJJ)J` 返回 0 一致）。

### 4.2 读侧阻塞链（与 §61.65 / §61.67 交叉）

| 观察 | 数值 | 解释 |
|---|---|---|
| `WaitRequest ... commit : 16384` | 2 条明细，恒 `16384` | 只有代次匹配的包走 `0x10888`；旧代次包被丢弃 |
| `WaitRequest force:true` / `force:false` | 15 / 6 | 读侧反复重发 Range |
| `Start id=` / `onEnd id=` | 22 / 21 | Java 取流完整，瓶颈不在 Java |
| `CM-DataReq: onData id=1 offset=… count=8192` | ≥3 | Java 侧数据确实回传 |
| `ReadFromFileNoLock … readed_len:0, result:-2` | 26 条 | vfile 读不到目标区间 |
| `NMBufferSourceImp::Read((1-K) Finish Async:-103` | **5 次**，K=2,4,5,6,7 | 多个代次的异步读以 `-103` 结束 |
| `doBufferFill status 4 nErr 1, lasterr -103` | 1 次 | native 缓冲填充失败 |
| `Open codec for aac` | 1 次（轮①） | codec 打开但**未出声** |
| `Write start play` / `doPlayCompleted` | **0 / 0** | 无 PCM 写入、无完成事件 |

轮① `has_103=true`、aac codec 打开但无 `Write start play` / `doPlayCompleted`
→ 与 `§61.62`「≈33 s 回退后打开 codec 但 `-103` 未出声」同类现象。

### 4.3 level-5 门控的实证结论（轮②）

| 关键词 | 轮① | 轮②（`setprop VERBOSE` 后） |
|---|---|---|
| `NMSlicesFile` / `OnWriteNetData` / `Write all finish` | 0 | 0 |
| `UnExpect New Offset` / SKIP / TRIM / `Failed Not Support` | 0 | 0 |
| `File Not Exsit` / `Finish EOF` | 0 | 0 |
| `D/NMCache` / `I/NMCache` / `W/NMCache` | 有 | **80 / 72 / 3** |
| `MakeRangeRequestAsync` / `NMNetSource` | 42 / 85 | 30 / 61 |

`setprop log.tag.* VERBOSE` **没有解锁任何 level-5 分支**，但 DEBUG 级日志
本来就在 → **排除 liblog 优先级过滤假说**。`libNMCommonCache.so` 只导入
`__android_log_print`、导出
`Java_..._NMVirtualFileManager_SetLoggerCallback`，说明门控是 native 内部
全局 log level，由 `SetLoggerCallback(INMLogger, int level)` 的第二个参数设置。
生产 app 全库无该方法的调用点 → 要开 level-5 必须改 Java 源码并重编译、
重装 APK（会动 P0 舱体与签名基线），**本轮不做、留待用户拍板**。

## 5. 与 `§61.67` 反汇编的对应（诚实分层）

| §61.67 二进制结论 | 本轮真机状态 |
|---|---|
| A. `0x104B0/0x104B8` 代次递增、`0x104D8` 透传 | ✅ **升级为一手实证**（`MakeRangeRequestAsync(N)` 1→22 连续） |
| B. `0x10794/0x10798` 比较并整包丢弃 | ⚠️ **强交叉、未直接命中**：`commit` 恒 16384 + 22 次重发 + `-103` 随代次出现 + 零数据投递日志，与丢弃链一致，但无 `OnData() UnExpect New Offset` 分支日志 |
| ⓐ `0x108E0` 跳过 `0x1086C` 数据回调 | ⚠️ 同上：`OnWriteNetData` / SKIP / TRIM 全 0，但 level-5 未开，**不能据此判定分支未执行** |
| ⓑ `NMSlicesFile::Write` 的 `offset+len <= file_len` 门控 | ❌ **本轮未验证**：样本跳跃读为 `offset=327895` / `25941`，未复现 `§61.61/§61.62` 的 `offset=605007`；26 条 `result:-2` 只证明读失败，不证明门控 |
| `commit` 精度修正（连续确认偏移 ≠ 落盘字节数） | ✅ 沿用，`commit` 恒 `16384` 与 §61.65 一致 |

## 6. 诚实边界

- 本轮**只把「代次递增 → 透传 → 重发」升级为运行时一手实证**；
  `inference` 的其余部分（旧代次包在 `0x10798` 被丢弃的逐包时序、
  `file_len` 门控的返回路径）**仍未由分支日志直接坐实**。
- level-5 日志未开启，`setprop` 试探为**有效负结果**；开启需改 Java 源码 +
  重编译 + 重装 APK（会动 P0 舱体），本轮未做、未伪造。
- **F4 仍 BLOCKED**：`-103` 判据未通过、自动进曲 3 首（无 `-103`、
  无释放后回调）/ seek / ENDED 仍 **NOT RUN**、F3 14 格矩阵未改写、
  A3 仍 **G09 PARTIAL** → **舱不冻结**、`engine_compat_test` 不回填、
  指纹不回填。**不写「F4 已解决 / m4a 已支持 / 全格式已支持」**。
- 样本是「自洽 fMP4」而非完整真实 m4a；LOCAL PASS ≠ m4a 已支持。
- 零 P0 源码改动、未重编译 / 未重装 APK、`libNMCommonCache.so` 二进制原样、
  零新增依赖；临时 `wifiOnly` / `adb reverse` / PC `http.server` /
  `log.tag.*` prop 均已恢复；**配对码不落盘、未改动用户网络**。
- `§61.65` / `§61.67` 等历史批次块的当时快照口径按约定保留，不改写。

## 7. 剩余断点与交接

1. **level-5 开启决策（需用户拍板）**：是否允许在 `engine-legacy` 加一行
   `NMVirtualFileManager.SetLoggerCallback(null, 5)` 并重编译 / 重装 APK，
   以直接观测 `0x10798` 丢弃分支与 `NMSlicesFile::Write` 门控返回值。
   代价：动 P0 舱体 + 可能的 debug.keystore 签名不匹配（§61.55 有先例）。
2. **F4（BLOCKED）**：level-5 日志开启后复跑，把 B / ⓐ / ⓑ 升级为实证；
   随后才谈修复与 `docs/17 §8.3` 验收（自动进曲 3 首无 `-103`、seek、ENDED）。
3. **F5**：A3 全绿后 ABI 回填 / 冻结舱。
4. **F3**：补齐 7 种 `NO_SAMPLE`；**G09 PARTIAL** / 舱不冻结；**T-V** USB / DMR。
5. 完成后回 P2 → P3 → P4。端点 `192.168.1.11:41703` 已在线，
   配对端点 `38357` 仅本次有效，下次上线须重新 `adb pair`。

> AI生成
