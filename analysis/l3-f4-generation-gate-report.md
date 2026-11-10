# F4 · 请求代次闸门与 vfile 落盘门控反汇编结论（`12 §61.67`）

> **2026-11-10 第九十四批** · 承接 `12 §61.65` 剩余断点①前半：
> 「继续反汇编 `MakeRangeRequestAsync` 代次递增点与 `NMSlicesFile::Write @0x12844`」。
> **离线、只读、不需要设备在场**；零 P0 源码改动、零设备操作、零新增依赖。
> 机器证据：`analysis/l3-f4-generation-gate-disasm.json`
> （**18 项 checks 全 PASS**，45 个反汇编锚点 / 11 条 `.rodata` 字符串 / PLT 符号还原 /
> §61.65 真机日志交叉）。可复跑：`python tools/l3_f4_generation_gate_disasm.py`。
> 审计：`python tools/l3_f4_generation_gate_audit.py` → `analysis/l3-f4-generation-gate-audit.json`。

---

## 1. 结论先行

§61.65 把「读侧读空 → 重发 Range 请求 → 旧代次在途包被 `0x10798` 整包丢弃 →
`commit` 恒 16384」标为 **`inference`**。本批把该推断的**三个缺环全部补成一手
二进制证据**，并新增两条此前未定位的实证：

1. **代次递增点坐实**：`MakeRangeRequestAsync @0x10458` 在
   `0x104B0 add x23,x8,#1` → `0x104B8 str x23,[x26,#0xA0]`（pre-index 后
   `x26 = this+0x20`，故 `+0xA0 == this+0xC0`）**每次重发都把请求代次 +1**，
   并在 `0x104D8 mov x1,x23` 把**新代次作为首参**经 `0x104E8 blr x8`
   （vtable+0x10）传给请求发起回调。
2. **丢弃链坐实**：`NMNetSource::OnData @0x10748` 的
   `0x10794 cmp x8,x24` / `0x10798 b.ne 0x108E0` 与 `[this+0xC0]` 当前代次比对；
   **`0x108E0` 位于数据回调 `0x1086C` 与 `commit` 写入 `0x10888` 之后**，
   跳转后只做 unlock + 栈保护检查 + `ret`。即代次不匹配的数据包
   **既不投递 `0x1086C blr x8`（vtable+0x18 → `OnNetSourceData` →
   `OnWriteNetData` 落盘入口），也不写 `commit`、不置 `+0xB8`、不唤醒读侧**。
3. **落盘门控坐实**：`NMSlicesFile::Write @0x127C0`（日志锚 `0x12844` →
   `0x3CF90`）要求 `offset + len <= [+0x70] = file_len` 且 `offset` 64B 对齐，
   否则返回 `-102` / `-1` / `-100` / `-98` **静默不写盘**。

**两条新实证**（§61.65 未定位）：

- 「Java 回传 1 MiB 一个字节都没进 vfile」（§61.63 `.nmsfi` 索引一手取证）
  的直接原因不只是 `commit` 不更新，而是**代次早退还跳过了数据投递回调**，
  `OnWriteNetData` / `NMSlicesFile::Write` 根本没收到这些包。
- 「跳跃式 probe 读 `offset=605007` 恒 `result:-2`」（§61.61 / §61.62）与
  `file_len` 门控一致：当前 `_0.nmsf` 仅 368640 B，
  `offset+len > file_len` 的写会被 `0x12824 b.le` 挡在分片写循环之外。

> **置信度分层**：代次递增 / 透传 / 比较 / 丢弃 / 落盘门控均为**二进制坐实**；
> 「读侧重发与代次失效的运行时时序」仍是**与真机日志交叉后的推断**
> （`inference`），本批未做新的真机 A/B 直接命中 `0x10798`，level-5 日志未开启。
> **证据等级提升 ≠ 修复 ≠ 验证。F4 仍 BLOCKED。**

## 2. 方法

- `pyelftools` 读 `.dynsym`（发布 so 已 strip、无 `.symtab`）、`.rela.plt` / `.plt`
  （PLT stub 还原为导入符号）、`.text`（`0x8ED0` 起、`0x3271C` 字节，
  `vaddr == file offset`）；
- `capstone 5.0.7` 全 `.text` 反汇编（51,655 条指令），按地址钉死 **45 个锚点**；
  ADRP+ADD 立即数还原 **11 条 `.rodata` 日志字符串**；
- 与 §61.65 已固化的真机证据 JSON 交叉（1028 条 `onData` / 9 次 `Start` /
  `commit=16384` / 代次 `(1-0)`→`(1-8)`），并与 §61.63 的 `.nmsfi` 索引、
  §61.61 / §61.62 的跳跃读日志对齐；
- 全部断言落在 `tools/l3_f4_generation_gate_disasm.py` 的 18 项 `checks`，
  不连 adb、不改 so、不改任何生产源码。

## 3. 二进制证据

### 3.1 A · `MakeRangeRequestAsync @0x10458`（请求代次递增与透传）

`WaitRequest @0x10198` 的 `0x10304 bl #0x10458` 是唯一调用点，
入参 `(this, offset, length, signal_size, 0)`。

```
0x10498  str   x22, [x26, #0x20]!   ; 写 offset，x26 前移为 this+0x20
0x104A0  ldr   x8,  [x26, #0xA0]    ; == [this+0xC0]，读当前请求代次
0x104B0  add   x23, x8, #1          ; ★ 代次 +1
0x104B4  stp   x9, x22, [x26, #8]   ; [this+0x28]=end, [this+0x30]=offset
0x104B8  str   x23, [x26, #0xA0]    ; ★★ 写回新代次到 [this+0xC0]
0x104D8  mov   x1, x23              ; ★ 新代次作为回调首参
0x104DC  mov   x2, x22              ; offset
0x104E0  mov   x3, x21              ; length
0x104E4  ldr   x8, [x8, #0x10]      ; vtable+0x10
0x104E8  blr   x8                   ; ★ 发起 Range 请求（Java Start 的 native 侧）
0x10510  add   x2, x2, #0xD64       ; 结果日志 0x3CD64
         'NMNetSource::MakeRangeRequestAsync(%lld), start result:%d, offset:%lld, length:%lld'
```

失败分支 `0x1053C stp q0,q0,[x26]` 把 `[this+0x20..0x40]` 请求区间置 `-1`、
`0x10540 str w24,[x20,#0x54]` 存错误码、返回 `3`。

→ **§61.65 的「`MakeRangeRequestAsync` 递增请求代次」由推断升级为逐指令实证**，
且新代次原样传给 `Start` 回调，与 §54 实证的 `Start(j1=请求标识, …)` 对齐。

### 3.2 B · `NMNetSource::OnData @0x10748`（代次校验与整包丢弃）

```
0x10790  ldr   x8, [x20, #0xC0]      ; 当前请求代次
0x10794  cmp   x8, x24               ; x24 = 回调 arg1（发起请求时的代次）
0x10798  b.ne  0x108E0               ; ★★ 不匹配 → 整包丢弃
   ...  区间校验 / 日志 ...
0x10840  ldr   x0, [x20, #0x190]     ; ★ 数据投递对象
0x1086C  blr   x8                    ; ★★ vtable+0x18 数据回调（落盘入口）
0x10884  adds  x8, x21, x22          ; commit = offset + length
0x10888  str   x8, [x20, #0x30]      ; ★ 写 commit
0x108D0  str   w8, [x20, #0xB8]      ; 越过阈值 → 置唤醒标志
0x108DC  bl    0x8AB0                ; 唤醒等待读侧
0x108E0  mov   x0, x19               ; ★ 丢弃路径：unlock + 栈保护 + ret
```

`0x108E0` 的地址落在 `0x1086C`（数据回调）与 `0x10888`（commit 写）**之后**：
一旦 `0x10798` 命中，**整包既不落盘、不更新 commit、不唤醒读侧**。
唤醒还有第二道阈值（`0x10894` 要求 `[+0x40] >= 1` 且
`0x108AC` 要求 `commit >= [+0x38] + [+0x40]`），否则同样跳 `0x108E0`。

→ **新增实证①**：§61.63 观察到「自洽 fMP4 只有 `slices[0].trunks=[0,368640)`、
`_0.nmsf` 实际 368640 B，而 mp3/flac/wav 有多片全满 1 MiB trunk」，
根因不只是 `commit` 记账，而是**被丢弃的包连数据投递回调都没执行**。

### 3.3 C · `NMSlicesFile::Write @0x127C0`（落盘门控）

日志锚 `0x12844 ADRP` + `0x1284C ADD #0xF90` → `0x3CF90`：
`NMSlicesFile Write buffer len:%u, offset:%lld, file_len:%lld`。

| 条件 | 锚点 | 返回 | 含义 |
|---|---|---|---|
| `[+0xC8] != 0` | `0x127C0` / `0x127CC` | `-100` | 写功能被禁用，直接返回 |
| `len == 0` 或 `offset` 低 6 位非 0 | `0x12810` / `0x12814` | `-102` | 空包 / 未 64B 对齐 |
| `offset + len > [+0x70] = file_len` | `0x12820 cmp` / `0x12824 b.le` / `0x12834` | `-102` | **越界，不写盘** |
| `file_len == offset` | `0x12868 cmp` / `0x1286C` / `0x1287C` | `-1` | EOF，不写盘（日志 `0x3CFCE Finish EOF.`） |
| 分片写失败 | `0x12980 bl 0x150E8` → `0x12AF4` / `0x12B30` | `-98` | 日志 `0x3D017 Fail. code:%d` |
| 通过全部门控 | `0x12890/0x12894 sdiv` 按 `[+0xD0]` slice_size 算起止片 | `0` | `0x12AEC` 置 `[+0x104]`、`0x3D0AE Write all finish` |

→ **新增实证②**：`offset+len <= file_len` 是硬门控。当前 vfile 只到 368640 B，
而 fMP4 的 probe 需要 `offset=605007`，写入无法越过 `file_len` 扩展，
读侧自然恒 `ReadFromFileNoLock ... result:-2`（§61.61 / §61.62）。
`0x3D062 File Not Exsit` 与 `0x3CFCE Finish EOF.` 两条日志可由 level-5 日志在真机确认。

## 4. 与既有真机证据交叉

| 既有观察 | 出处 | 本批二进制解释 |
|---|---|---|
| Java `onData` 1028 条 / 9 次 `Start` 每次 `length=1048576` / 每包 8192 B | §61.65 | Java 取流完整，瓶颈不在 Java |
| `commit` 恒 `16384`（kind=2）/ `180224`（kind=1） | §61.65 | 只有代次匹配的包走 `0x10888`；重发使旧代次包全被 `0x10798` 丢弃 |
| 请求代次 `(1-0)`→`(1-8)` | §61.65 | `0x104B0/0x104B8` 每次重发 +1，`0x104D8` 透传给回调 |
| `.nmsfi` 只有 `[0,368640)` 一片、无全满 trunk | §61.63 | 丢弃路径跳过 `0x1086C` 数据回调，包根本没到 `OnWriteNetData` |
| `offset=605007 readed_len:0 result:-2` × 多次 | §61.61 / §61.62 | `NMSlicesFile::Write` 的 `offset+len <= file_len` 门控（当前 368640） |
| 变体 D（kind=2）`commit` 反而更小 | §61.64 | kind=1 额外写 `NMMemoryCache` 让读侧较晚读空、较少重发（§61.65 已解释） |

## 5. 设备端点 `41417` 排查（用户 23:58 追加，如实登记）

用户给出 `41417`，按项目历史的 adb 无线调试端点格式 `10.0.0.2:<5 位端口>`
（上一轮在线端点 `10.0.0.2:41761`）处理：

| 尝试 | 结果 |
|---|---|
| `adb connect 10.0.0.2:41417` | `10060` 连接超时（主机不可达） |
| `adb connect 192.168.1.11:41417` | 拒绝连接 |
| 重启 adb 会话后重试两个地址 | 结果不变 |
| 系统 adb `35.0.2` / 项目 `tools/bin/adb.exe` `37.0.1` 两个版本 | 结果不变 |
| `adb devices -l` | 空 |

按用户选择「**先继续离线任务**」：把 `41417` 登记为**待连端点**，
本批真机 A/B 与「开 level-5 日志把 `inference` 升级为实证」的后半段 **NOT RUN**。
**未擅自改动用户网络**（WiFi / 网卡 / 路由），待用户补完整 `IP:port`
或确认手机「开发者选项 → 无线调试」已开启后再跑。

## 6. 诚实边界

- 本轮为**纯静态反汇编 + 已固化真机日志的交叉**，`inference` 性质未改变：
  未做新的真机 A/B 直接命中 `0x10798`，读侧重发与代次失效的运行时时序未逐包坐实。
- level-5 日志（`NMSlicesFile Write` 的 SKIP / EOF / Fail / `Write all finish`、
  `OnWriteNetData` 的 SKIP / TRIM / `Failed Not Support`）在现有 logcat 中未开启，
  C 段落盘门控分支**只由二进制坐实**、未由运行时日志确认。
- **F4 仍 BLOCKED**：根因方向更清楚但仍**未修复、未验证**；`-103` 判据未通过；
  自动进曲 3 首（无 `-103`、无释放后回调）/ seek / ENDED 仍 **NOT RUN**；
  F3 14 格矩阵未改写；A3 仍 **G09 PARTIAL** → **舱不冻结**、
  `engine_compat_test` 不回填、指纹不回填。
  **不写「F4 已解决 / m4a 已支持 / 全格式已支持」**。
- 零 P0 源码改动、`libNMCommonCache.so` 二进制原样、零新增依赖、零设备操作；
  §61.65 / §61.64 / §61.50 等历史批次块的当时快照口径按约定保留，不改写。

## 7. 剩余断点与交接（按 `12 §65` 顺序）

1. **F4（BLOCKED）**：设备在线后按 `analysis/l3-f4-online-runbook.md` 与
   `12 §61.58` 前置清单，开 level-5 日志做一次「代次早退 / 落盘门控」真机实证，
   把 `inference` 升级为实证；随后才谈修复与 `docs/17 §8.3` 验收。
   端点 `41417` 待用户确认。
2. **F5**：A3 全绿后 ABI 回填 / 冻结舱。
3. **F3**：补齐 7 种 `NO_SAMPLE`（§61.55 已确认 OpenList 80 目录零样本）。
4. **G09 PARTIAL** / 舱不冻结；**T-V** USB / DMR 项。
5. 完成后回 P2 → P3 → P4。

> AI生成
