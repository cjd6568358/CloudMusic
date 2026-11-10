# F4 level-5 日志门控语义纠正报告（docs/12 §61.70 / 第九十七批）

> **2026-11-11 凌晨 · 纯离线只读取证** · 承接 `12 §61.68` / `§61.69` 剩余断点。
> 不连设备、不改网络、不改任何生产源码、未重编译 / 未重装 APK。
> 设备 `192.168.1.11:41703` 在本批开始时 `adb devices -l` 返回空列表，
> 真机任务整体仍阻塞；本批不空等，改做「设备上线后判据是否正确」的前置核验。

---

## 1. 结论先行（对前两批的勘误）

**`libNMCommonCache.so` 根本不存在「需要额外开启的 level-5 开关」。**

native 日志门控是标准的「全局阈值 > 消息级别则抑制」过滤：

```text
getter 0x37E20 :  w0 = (g_level > N)        ; N = 该条日志的消息级别
调用点分支      :  tbnz/tbz w0, ...          ; g_level > N  -> 跳过 / 提前返回，日志不输出
                                             ; g_level <= N -> 输出日志
```

`g_level` 存放在 `.bss` 的 `0x4c388`，**进程启动恒为 0**（零填充 + 全库唯一写者
是 `SetLoggerCallback` 的 level 参数 + `.init_array` 构造函数无写）。因此：

| 消息级别 N | logcat 前缀 | 默认 `g_level=0` | `level=5` | `level>=6` |
|---|---|---|---|---|
| 3 | `D/NMCache` | 输出 | **抑制** | 抑制 |
| 4 | `I/NMCache` | 输出 | **抑制** | 抑制 |
| 5 | `W/NMCache` | 输出 | 输出 | 抑制 + 改变部分 `tbz` 早退错误码 |
| 6 | `E/NMCache` | 输出 | 输出 | 输出（`6>6` 假） |

即：**日志在默认配置下本来就是全开的**；`SetLoggerCallback(..., level)` 的
`level` 是「抬高阈值、减少日志」，不是「解锁更多详细日志」。`§61.69` 把
`.coerceIn(0, 5)` 锁死在 5，实际效果是**关掉 I/D 级证据、对 W 级零增益**。

由此，`§61.68` 两轮 logcat 里 `NMSlicesFile` / `OnWriteNetData` / `SKIP` /
`TRIM` / `UnExpect New Offset` / `file_len:` 全 0，**不是「level-5 未开启」，
而是这些 W 级代码分支在真机运行中从未被触发**。`setprop log.tag.* VERBOSE`
的负结果依然成立、依然是有效证据，但归因错了。

---

## 2. 取证方法与环境

- 目标二进制：`engine-legacy/src/main/jniLibs/arm64-v8a/libNMCommonCache.so`
  （只读，sha256 见审计 JSON）。
- 反汇编：`capstone 5.0.7` + `pyelftools`，全 `.text`（51655 条指令）线性扫描；
  ADRP+ADD 还原 `.rodata` 字符串；`.plt` stub → GOT 槽 → `.rela.plt` 还原导入符号。
- Python 环境：`/c/Users/cjd65/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe`
  （`C:/Python313` 与 `~/.workbuddy/binaries/.../default` 均缺 `elftools`）。
- 一手运行证据：`analysis/l3-f4-logs/*.txt` 全部归档 logcat（33 份，含
  `§61.68` 两轮 6725 / 5404 行），按真实 `.rodata` 格式串前缀逐个计数。
- 只读审计工具：`tools/l3_f4_level5_gate_semantics_audit.py` →
  `analysis/l3-f4-level5-gate-semantics-audit.json`。

---

## 3. ELF 静态事实

| 项 | 地址 / 值 | 结论 |
|---|---|---|
| level 全局 | `0x4c388`，落在 `.bss` / `SHT_NOBITS` | 无文件初值，进程加载清零 |
| level 写者 | 全 `.text` 仅 `0x37E38 str w0,[x8,#0x388]` 1 处 | setter `0x37E34` |
| `.init_array` | 7 个构造函数访问 `0x2c0/0x2e8/0x2f0/0x2f8` 等偏移 | **无 `0x388` 写**，`g_level` 启动 = 0 |
| getter | `0x37E20 adrp; ldr w8,[x8,#0x388]; cmp w8,w0; cset w0,gt` | 严格「大于」语义 |
| JNI `SetLoggerCallback` | `0x32D64`，`0x32D88 b #0x37e34` tail-call setter | level 参数是**唯一**作用 |
| 日志后端 | PLT `0x8A70` → GOT `0x4bbd8` → `__android_log_print` | tag 固定 `0x3B5F0 = "NMCache"`，格式 `"%s"` |
| logger 注册 | `0x36198`：`GetObjectClass(logger)` → `GetMethodID` | 方法名 `0x3EAC2 = "BIInfo"`，签名 `0x3EAC9 = "(ILjava/lang/String;)V"` |

`getter` 调用点 **173** 个、`logfn 0x3138C` 调用点 **156** 个。把每个 getter
调用点的实参 `N` 与同块 logfn 首参 `M` 配对：

| (N, M) | 数量 |
|---|---|
| (5, 5) | 98 |
| (4, 4) | 30 |
| (3, 3) | 27 |
| (6, 6) | 8 |
| 其余（配对窗口边界 / 相邻调用共用 logfn） | 10 |

**163/173 = 94.2% 严格恒等**：getter 的 `N` 就是该条日志的消息级别，且同时
作为 `__android_log_print` 的 priority 传入。分支类型 `tbnz` 147 / `tbz` 26，
方向统一为 `g_level > N` 抑制。

### 3.1 F4 关键区域逐点钉死

| 区域 | getter 地址 | N | M | 分支 | 真实日志格式串 |
|---|---|---|---|---|---|
| `NMNetSource::OnData` | `0x107BC` | 5 | 5 | `tbnz` → 跳过 | `NMNetSource(%lld-%lld)::OnData() offset:%lld, size:%lld, except offset:%lld, end:%lld` |
| `NMNetSource::OnData` | `0x10804` | 5 | 5 | `tbnz` → 跳过 | `...UnExpect New Offsetcommted:%lld, offset:%lld, size:%lld, cur_req_size:%lld` |
| `NMSlicesFile::Write` 门控 | `0x1282C` | 5 | 5 | `tbz` → 进日志 | `NMSlicesFile Write buffer len:%u, offset:%lld, file_len:%lld ` |
| `NMSlicesFile::Write` EOF | `0x12874` | 5 | — | `tbz` → 进日志 | （`Finish EOF.` 分支） |
| `Write all finish` | `0x12AC8` | 5 | 5 | `tbnz` → 跳过 | `NMSlicesFile Write all finish` |
| `Write ... Fail.code` | `0x12AFC` | 5 | 5 | `tbnz` → 跳过 | `NMSlicesFile Write buffer len:%u, offset:%lld, file_len:%lld Fail. code:%d` |
| `NMSlicesFile::Read` 尾 | `0x12C48` | 5 | 5 | `tbz` → 进日志 | `NMSlicesFile::Read buffer len:%u, offset:%lld, file_len:%lld ` |
| `NMSlicesFile::Read` EOF | `0x12C5C` | 4 | 4 | `tbz` → 进日志 | `...Finish EOF.` |

对照低级别：`WaitRequest` / `MakeRangeRequestAsync` 的 4 个 logfn 调用点
（`0x10224 / 0x10274 / 0x102EC / 0x10528`）首参全部 **4** → `I/NMCache`；
`NMBufferSourceImp::Read` 系列为 **3** → `D/NMCache`。

### 3.2 `NMSlicesFile::Write` 的 `file_len` 门控究竟在哪

```text
0x12810  cbz   w2, #0x12828            ; len == 0
0x12814  tbnz  x21, #0x3f, #0x12828   ; offset 未 64B 对齐
0x12818  ldr   x8, [x19, #0x70]        ; file_len
0x1281C  add   x28, x21, w23           ; offset + len
0x12820  cmp   x28, x8
0x12824  b.le  #0x12868               ; offset+len <= file_len -> 正常写，跳过日志
0x12828  mov   w0, #5
0x1282C  bl    #0x37e20
0x12830  tbz   w0, #0, #0x1283c       ; g_level <= 5 -> 打日志
0x12834  mov   x0, #-0x66             ; g_level > 5 -> 不打日志，直接返回 -102
0x1283C  ... log 'NMSlicesFile Write buffer len:%u, offset:%lld, file_len:%lld '
0x12860  mov   x0, #-0x66             ; 返回 -102
```

**这条 W 级日志是「越界 / 未对齐 / 零长度」的失败诊断**，不是常规写入日志。
它在归档 logcat 中 0 次，配合反汇编可给出比 `§61.68` 更强的表述：
本轮运行中 `NMSlicesFile::Write` 的入参始终满足 `offset+len <= file_len`
（或该路径整体未被走到），**`file_len` 门控从未触发**。

---

## 4. 归档 logcat 的一手实证（33 份全扫）

| 真实格式串关键词 | 全部归档命中 |
|---|---|
| `NMSlicesFile Write buffer len` / `NMSlicesFile::Read buffer len` / `file_len:` | **0 / 0 / 0** |
| `OnWriteNetData SKIP` / `OnWriteNetData TRIM` | **0 / 0** |
| `::OnData() offset` / `UnExpect New Offsetcommted` | **0 / 0** |
| `NMSlicesFile Write all finish` | **1** |
| `MakeRangeRequestAsync` / `WaitRequest` / `Finish Async:-103` | 169 / 129 / 12 |

**决定性一条**出现在 `analysis/l3-f4-logs/ksab-flac-remote.txt`：

```text
11-09 23:59:42.917 W/NMCache ( 1992): NMSlicesFile Write all finish
```

这是 `w0=5` 的 **W 级**日志，在**未调用 `SetLoggerCallback`** 的默认配置下
确实输出了 —— 直接证明 N=5 分支不受抑制、日志后端可用。全部归档中
`I` / `D` / `W` 三种优先级均大量存在，与 `g_level=0` 完全一致。

因此 `§61.68` 两轮 0 命中应重新定性为**有效负结果（分支未触发）**，而不是
「日志被门控挡住」。这也解释了为什么 `setprop VERBOSE` 两轮都是 0：
liblog 优先级本来就不是瓶颈。

---

## 5. 对 `§61.68` / `§61.69` 的逐条勘误

| 前批结论 | 本批纠正 |
|---|---|
| 「level-5 分支日志未开启，真开关是 native `SetLoggerCallback` 的 level 参数」 | 门控 `g_level > N` 默认 `g_level=0` → 全开；`SetLoggerCallback` 只用于**抬高阈值关日志** |
| 「开启 level-5 需改 Java 源码 + 重编译 + 重装 APK」 | 不需要。W 级日志默认已输出 |
| 「`§61.69` 接线 `SetLoggerCallback(INMLogger(), 5)` 是 F4 诊断前置」 | 对 F4 目标 W 级**零增益**；会抑制 I/D 级 `MakeRangeRequestAsync` / `WaitRequest` / `ReadFromFileNoLock ... result:-2` 等 `§61.68` 赖以计数的证据 |
| 「`0x10798` / `file_len` 门控无直接分支日志，等设备上线开日志后复跑」 | 日志已在，缺的是**能触发这些分支的输入**；断点性质改变 |
| 「`INMLogger` 是空类，故传 `INMLogger()` 实例」 | 空类仍不满足 native `GetMethodID("BIInfo", "(ILjava/lang/String;)V")` |

### 5.1 `§61.69` P0 接线的三项新风险

1. **无效**：`level=5` 与默认 `g_level=0` 对 N=5 分支效果相同（`5>5` 与
   `0>5` 皆为假），F4 真正需要的 W 级日志一条也不会多。
2. **有害**：对 N=3/4 的调用点，`5>3` / `5>4` 变真 → `tbnz` 跳过日志、
   `tbz` 不进入日志路径并可能提前返回 `-0x63` / `-1` / `-6` 等错误码。
   即会**抹掉** `§61.68` 的一手证据并轻微改变部分错误返回路径。
3. **JNI 隐患**：`0x36198` 对传入的 logger 对象执行
   `GetObjectClass` + `GetMethodID(jclass, "BIInfo", "(ILjava/lang/String;)V")`，
   把 `jmethodID` 缓存到 `0x4c318`、global ref 缓存到 `0x4c310`。
   仓库内 `INMLogger.java` 是 **空类**（`public class INMLogger {}`），
   该 `GetMethodID` 必然返回 NULL → `0x3624C cbz` 走失败分支、初始化标记
   `0x4c320` 不置位；且 JNI 规范下 `GetMethodID` 找不到方法会抛
   `NoSuchMethodError`，**可能留下 pending exception**，而同一 `prepare()`
   链紧跟着还要调 `SetConfig(json)`。此后果需真机确认，本批不夸大。

> **建议（未执行，待用户拍板）**：回滚 `EngineAdapter.kt` 的
> `§61.69` 接线，恢复零 `SetLoggerCallback` 调用；或至少把默认 level 改为 0。
> 回滚属于再次改动 P0 舱体 + 重编译，用户此前授权的是「按 A 走（接线）」，
> **本批不把「继续」理解为回滚授权**，保持源码原样。

---

## 6. F4 断点性质的改变（F4 仍 BLOCKED）

**F4 仍 BLOCKED**：`-103` 未通过、自动进曲 3 首 / seek / ENDED 仍 NOT RUN、
A3 仍 G09 PARTIAL、舱不冻结、指纹不回填。本批是**证据模型纠错**，
不是修复、不是验证。

断点从「等设备上线上开 level-5 日志」改为：

1. **默认日志已全开**，W 级 `0x10798` / `file_len` / `OnWriteNetData` 分支在
   既有 33 份归档 logcat 中 0 次命中，是有效负结果；
2. 要升级为实证，需**构造能触发这些分支的输入**（例如让
   `offset+len > file_len`、让回调代次与 `[this+0xC0]` 不匹配、让
   `commit != offset`），而不是调日志级别；
3. 或接受负结果，把 `§61.67` 的 `inference` 中「旧代次包在 `0x10798`
   被丢弃」与「`file_len` 门控返回路径」明确降级为**静态推断 + 运行时
   一致性证据**，不再计划靠「开日志」坐实。

设备上线后若仍要复跑，判据应改为：**保持 `g_level=0`（不调用
`SetLoggerCallback` 或 level=0）**，用真实格式串前缀扫 W 级日志，
并针对上述触发条件设计输入。

---

## 7. 诚实边界

- 本批 **零 P0 源码改动**、未重编译 / 未重装 APK、so 二进制原样、
  未改动用户网络、未伪造任何授权或设备在线状态。
- 临时状态：无（纯只读，无需恢复）。设备 `192.168.1.11:41703` 在本批
  `adb devices -l` 为**空列表**，真机 level-5 观测 **NOT RUN**。
- `§61.68` / `§61.69` 的历史批次正文**不改写**，仅在 `docs/12` 对应章节
  追加 `§61.70 勘误` 标注；历史证据 JSON 保持 `all_pass=true` 原样。
- 本批不作任何「F4 已收口、格式支持范围扩大、或额外日志开关已生效」的断言；
  上述三类说法均为本项目明令禁止的越界表述。
- `BIInfo` 缺失导致的 JNI pending exception 后果**未真机验证**，仅登记为风险。

---

## 8. 审计结果（机器证据）

工具：`tools/l3_f4_level5_gate_semantics_audit.py`
产物：`analysis/l3-f4-level5-gate-semantics-audit.json`

| 分组 | PASS |
|---|---|
| binary（ELF 静态事实 10 项） | 10 / 10 |
| semantics（门控语义 6 项） | 6 / 6 |
| logcat（归档实证 5 项） | 5 / 5 |
| docs（文档口径 7 项） | 7 / 7 |
| honesty（诚实边界 6 项） | 6 / 6 |
| **合计** | **34 / 34** |

长效守护：`tools/check_source_contracts.py` 新增 `12 §61.70 守护`，
校验审计 JSON `all_pass`、34 项检查、勘误登记、回滚待拍板、F4 仍 BLOCKED、
接线仍在位、历史证据不回潮。

---

## 9. 剩余断点与交接（下一步须用户拍板 / 操作）

1. **`§61.69` 接线是否回滚**（用户拍板）：建议回滚，理由见 §5.1。
   回滚需改 `EngineAdapter.kt` + 重编译 + 重装 APK，且需同步调整
   `check_source_contracts.py §61.69` 守护（当前强制校验接线存在）。
2. **设备重新上线**（用户操作）：重开无线调试 → 给新配对端点 +
   一次性配对码（不落盘）→ `adb pair` + `adb connect`；助手不擅自切网络。
3. **真机复跑口径**：保持 `g_level=0`，用真实 `.rodata` 格式串扫 W 级日志，
   并设计触发 `offset+len > file_len` / 代次不匹配 / `commit != offset` 的输入。
4. **F4（BLOCKED）** → 之后才谈 `docs/17 §8.3` 验收（自动进曲 3 首无 `-103`、
   seek、ENDED）。
5. **F5**：A3 全绿后 ABI 回填 / 冻结舱。
