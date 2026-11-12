# LOGIC-SPEC —— 竞品播放内核 1:1 Kotlin 逻辑翻译规格映射

> **文首结论**：本目录把 `docs/12 §61.54 ~ §61.88` 系列反汇编证据逐条翻译成纯 JVM Kotlin
> （含 §61.77 R 组全量登记、A3 反汇编补遗 U 组与章节矩阵自证 V 组），
> 共 **206 条 `@spec` 逻辑点**（G15 / N30 / S23 / B30 / A12 / P12 / C8 / F8 / D12 / R8 / U10 / V6 / W14 / X8 / Z10），全部有对应 Kotlin 实现
> 与契约测试断言；`tools/audit.py` 长效守护「代码编号 ⊆ 规格编号」且零悬空、零禁项。
> 交付边界是**行为等价的逻辑翻译**，不含任何二进制、dex、so、资源或私有资产。
>
> **Z 组是判据口径的换代**：Y 组按 `.dynsym` 符号表取函数清单，实测只覆盖 4 个 so `.text`
> 指令的 27%（so 已 strip，无 `.symtab`）；Z 组改按**代码可达性**取全集，覆盖率 90.0% / 95.7% /
> 97.0% / 92.7%，`6324` 个代码块、`7486` 个函数入口、`388068` 条指令一条一行，
> `.text` 字节账三者相加闭合。1358 条「PRESERVED 保真记录、无真实语义」的残留已清零：
> `when(op)` 无实现分支 = 0、运行期 dropout = 0、向量错桶 = 0。

---

## 1. 口径与边界

### 1.1 「1:1」的定义

| 项 | 含义 |
|----|------|
| 翻译对象 | 反汇编**可证明的控制流 / 数据流 / 门控条件 / 错误码 / 日志语义** |
| 不做 | 复制或打包 `*.so` / dex / 资源 / 私有协议实现；不复制字符串常量表以外的私有素材 |
| 输出 | 纯 JVM Kotlin（`cloudmusic.reverse.kernel`），无 Android 框架依赖、无 `com.netease.*` import |
| 用途 | 用户可从 Kotlin 逻辑里抽取任意子集，合并进自己的项目（个人学习用途） |
| 精度 | 结构体偏移保留为 `NmFieldOffsets` 常量；错误码保留为 `NmError` 立即数常量 |

### 1.2 证据等级（每条规格必须标注）

| 等级 | 含义 |
|------|------|
| **L1** | 反汇编 JSON 里有具体地址 + 指令，逐指令坐实 |
| **L2** | 人类可读报告给出语义与地址锚点，指令级未逐条复核 |
| **L3** | 静态反汇编不足以 100% 证明（目标类型 / 触发条件 / 早退副作用），明确标注为推断或边界声明 |

> 项目纪律：**防静态推断被写成「已修复」、防 F4 提前 PASS**。凡 L3 项一律保留可注入语义，
> 不虚构不存在的指令，不把「报告级推断」升级为「逐指令坐实」。

### 1.3 覆盖边界（明确不翻译项）

- B2 生产实现（`IMusicEngine` 14 方法映射、Range 设计、M0~M5 施工单）：属**另一条路线**，
  本目录不改其阶段状态、不宣称 F4 已解决。
- `libffmpeg.so` decoder/demuxer 符号清单（`l3-b2-m2-decoder-symbols.json`）：属**格式覆盖前置数据**，
  不是播放内核控制逻辑；本轮已**全量数据层登记**到 `NmFormatCatalog.kt`（100 decoder / 75 demuxer / 查询矩阵 / 边界声明），见 F 组。
- R8 keep 规则、JNI 导出表全量、dex 类全量：数据清单，非逻辑；以 Inventory 常量登记。
- 真机 logcat 原始归档、`analysis/l3-f4-backup/`（已 gitignore）：证据来源，非翻译对象。

---

## 2. 一手证据源

| 证据文件 | 覆盖内容 | 对应 docs/12 章节 |
|----------|----------|-------------------|
| `analysis/l3-f4-netsource-commit-disasm.json` | OnData / OnWriteNetData / commit / 代次闸门 | §61.65 |
| `analysis/l3-f4-netsource-commit-report.md` | §61.65 人类可读报告 | §61.65 |
| `analysis/l3-f4-generation-gate-disasm.json` | MakeRangeRequestAsync 代次递增、NMSlicesFile 门控 | §61.67 |
| `analysis/l3-f4-generation-gate-report.md` | §61.67 四道静默门控表 | §61.67 |
| `analysis/l3-f4-level5-gate-semantics-report.md` | getter 语义纠错、g_level 默认 0、早退副作用 | §61.70 |
| `analysis/l3-f4-level5-wiring-report.md` | `.coerceIn(0,5)` 硬上限理由 | §61.70 §5.1 |
| `analysis/l3-f4-minus103-chain-report.md` | `-103` 硬编码产生链、播放侧状态机 | §61.76 |
| `analysis/l3-f4-play-gate-report.md` | `Write start play` 四道静默门控 | §61.74 |
| `analysis/l3-f4-playback-control-report.md` | 真机控制取证终局、F4 仍 BLOCKED | §61.75 |
| `analysis/l3-f4-read-gap-chain-report.md` | `result:-2` 与挂起异步读链 | §61.77 |
| `analysis/l3-f4-read-gap-chain.json` | §61.77 机读反汇编：31/31 checks、24 锚点、6 处 `[+0x198]`、7 处 -2、rodata 格式串 | §61.77 |
| `analysis/l3-f4-jni-arg-report.md` | Java→native 参数槽透传 | §61.56 |
| `analysis/l3-f4-vfile-commit-report.md` | 真机 60s vfile commit 观察 | §61.62 |
| `analysis/engine-jni-classes.json` | JNI 类/方法/符号全量清单（19 类 / 162 方法 / 202 符号） | — |
| `analysis/l3-b2-m2-decoder-symbols.json` | 100 decoder / 75 demuxer 导出符号 + 9/15/19 查询矩阵 | §61.83 |
| `analysis/l3-f4-disasm-verify.json` | §61.54 机读反汇编：`0xBEE8` 双分支 / GOT 槽 / 共享队列 | §61.54 |
| `analysis/l3-f4-disasm-verify-report.md` | §61.54 一手人类可读复核报告 | §61.54 |
| `analysis/l3-f4-task-diff.json` | §61.55 机读证据：函数 size / 指令数 / 14 checks / flush 22 指令 | §61.55 |
| `analysis/l3-f4-task-diff-report.md` | §61.55 两个 task 行为差异 + 级联 Render 链 | §61.55 |
| `docs/05-Native库实测分析.md` | native 依赖闭包、JNI 导出实测 | — |
| `docs/07-错误码总表.md` | 区分项目自研 `E_*` 与 native 立即数 `-2/-103/-102` | — |
| `analysis/l3-f4-online-runbook.md` | 上线复跑判据（`UnExpect New Offsetcommted`） | — |
| `analysis/a3-stage-c-analysis-2026-11-07.md` | A3 阶段 C 反汇编：构造期 `[+0xB8]` 初值 0x101 / Memory Mode 双门控（tbz / cbnz / strb wzr）/ `Failed Not Support` 唯一条件（U-01~U-05，L1） | §58 / U 组 |
| `analysis/a3-phase-ab-evidence-2026-11-07.md` | A3 A/B 实证：`NMBufferSourceAdapter::Open()` 的 `mov wN,#0x3e9`(1001) → `ISourceCallback::OnError` 虚调用链，10 处命中 / 4 处在 Open（U-06~U-07，L2） | §56 / U 组 |
| `analysis/l3-f4-minus103-chain.json` | §61.76 机读反汇编：16 锚点 / 25 checks / 5 处 -103 立即数（U-08 元数据） | §61.76 |
| `analysis/l3-f4-play-gate-disasm.json` | §61.74 机读反汇编：4 门控 / 16 checks / `Write start play` rodata 地址（U-08 元数据） | §61.74 |
| `analysis/l3-f4-level5-gate-semantics-audit.json` | §61.70 机读审计：27 findings / 34 checks / `g_level` `.bss` 语义（U-08 元数据） | §61.70 |
| `analysis/l3-f4-loglevel-gate-probe.json` | §61.68 机读探针：12 anchors / 19 checks / level-5 零侵入路径（U-08 元数据） | §61.68 |
| `analysis/l3-f4-jni-arg.json` | §61.56 机读复核：17 checks / 4 evidence / ARM64 JNI 参数槽（U-08 元数据） | §61.56 |

---

## 3. 规格映射表

### 3.1 G 组 —— `libNMCommonCache.so` 全局日志门控（15 条）

> 实现：`NmLogGate.kt`、`NmNativeTypes.kt`（`NmLogLevel` / `NmCollectingLogSink`）

| 编号 | 逻辑点 | 证据锚点 | 等级 |
|------|--------|----------|------|
| G-01 | getter `w0 = (g_level > N)`，大于才抑制 | `0x37E20` `adrp;ldr;cmp;cset gt` | L1 |
| G-02 | setter `str w0,[x8,#0x388]`，全 .text 唯一写者 | `0x37E34` / `0x37E38` | L1 |
| G-03 | `g_level` 位于 `.bss 0x4C388`，进程启动零填充 | `.bss` 段属性 | L1 |
| G-04 | `.init_array` 无写 + 唯一 setter ⇒ 默认恒为 0 | 全库线性扫描 | L1 |
| G-05 | `g_level <= N` 才输出日志（默认 I/D/W/E 全开） | 调用点 `tbnz/tbz` 汇总 | L1 |
| G-06 | getter 调用点 173 处 | 全 .text 扫描 | L2 |
| G-07 | logfn 调用点 156 处 | 全 .text 扫描 | L2 |
| G-08 | `(N,M)` 严格恒等 163/173 = 94.2%；`tbnz 147 / tbz 26` | 全 .text 扫描 + 直方图 | L2 |
| G-09 | logfn `0x3138C` → PLT `0x8A70` → `__android_log_print` | `0x3138C` / `0x8A70` | L1 |
| G-10 | `SetLoggerCallback @0x32D64` tail-call setter `0x37E34`，level 是唯一作用 | `0x32D64` / `0x32D88` | L1 |
| G-11 | 注册链 `GetObjectClass`→`GetMethodID("BIInfo","(ILjava/lang/String;)V")`→缓存 | `0x36198` / `0x4C318` / `0x4C310` / `0x4C320` | L1 |
| G-12 | 空类缺 `BIInfo` → `GetMethodID` 返回 NULL → `0x3624C cbz` 失败分支，标记不置位 | `0x3624C` | L1 |
| G-13 | tag 固定 `NMCache`（`.rodata 0x3B5F0`），格式 `"%s"`，N 同时作 priority | `.rodata 0x3B5F0` | L1 |
| G-14 | `OnData` W(5) 级 `offset/size/except offset/end` 日志 | `0x107BC` getter N=5 | L1 |
| G-15 | §61.70 §5.1 登记「`tbz` 早退可能改变部分返回码（-6/-99/-1）」 | 报告级，未逐指令坐实 | **L3** |

> G-15 落地为 `NmLogGate.suppressedSideEffectCode`，**默认 0**（不改变返回码），
> 仅在测试里显式配置时生效；不虚构指令。

### 3.2 N 组 —— `NMNetSource` 代次闸门 / commit / 唤醒（30 条）

> 实现：`NmNetSource.kt`

| 编号 | 逻辑点 | 证据锚点 | 等级 |
|------|--------|----------|------|
| N-01 | `MakeRangeRequestAsync @0x10458` 由 `WaitRequest @0x10304` 唯一调用 | `0x10458` / `0x10304` | L1 |
| N-02 | `[+0x20]` 写本次 Range 请求起始偏移 | `0x10498` `str x22,[x26,#0x20]!` | L1 |
| N-03 | 读当前代次 `[+0xC0]`（pre-index 后等价 `+0xA0`） | `0x104A0` 附近 | L1 |
| N-04 | 代次 `+1` | `add` 指令 | L1 |
| N-05 | 写回新代次 `[+0xC0]`，并作为首参透传 | `0x104B8` `str x23,[x26,#0xa0]` | L1 |
| N-06 | `vtable+0x10` 虚调用发起 Range 请求，args=(obj,gen,offset,length) | `0x10500` 附近 | L1 |
| N-07 | 请求参数装配（x2/x3 透传） | `0x104D0~0x10500` | L2 |
| N-08 | 虚调用返回后检查 start result | `0x10510` 附近 | L2 |
| N-09 | 日志格式串 `0x3CD64` 还原 | `0x10510` | L2 |
| N-10 | 请求失败 `0x1053C stp q0,q0,[x26]` 把 `[+0x20..0x40]` 置 -1，存错误码 | `0x1053C` / `0x10540` | L1 |
| N-11 | `NMNetSource::OnData @0x10748` 入口 | `0x10748` | L1 |
| N-12 | `0x10790 ldr x8,[x20,#0xC0]` 读当前代次 | `0x10790` | L1 |
| N-13 | `0x10794 cmp x8,x24` 与请求代次比较 | `0x10794` | L1 |
| N-14 | `0x10798 b.ne 0x108E0` 代次不匹配 → **整包丢弃**（唯一硬早退） | `0x10798` | L1 |
| N-15 | `0x107F4` 读 `[+0x30]` commit | `0x107F4` | L1 |
| N-16 | `commit != offset` 只打 W 级 `UnExpect New Offsetcommted`，**不丢弃** | `0x107F8 cmp` / `0x107FC b.eq` | L1 |
| N-17 | `0x10840` 取 `[+0x190]` 数据投递对象 | `0x10840` | L1 |
| N-18 | `vtable+0x18` 虚调用 | `0x1086C` | L1 |
| N-19 | 投递 → `NMBufferSourceImp::OnNetSourceData` | `0x1086C blr` | L1 |
| N-20 | `0x10884 adds x8,x21,x22` → `commit = offset + size` | `0x10884` | L1 |
| N-21 | `0x10888 str x8,[x20,#0x30]` 写回 commit | `0x10888` | L1 |
| N-22 | 唤醒阈值：`[+0x40] >= 1` 且 `commit >= [+0x38]+[+0x40]` | `0x108A0~0x108C0` | L2 |
| N-23 | `0x108D0` 置唤醒标志 `[+0xB8]` | `0x108D0` | L1 |
| N-24 | `0x108DC bl 0x8AB0` `condition_variable::notify_all` | `0x108DC` / `0x8AB0` | L1 |
| N-25 | `0x108E0` 丢弃路径终点：只 unlock + 栈保护 + ret | `0x108E0` | L1 |
| N-26 | `WaitRequest @0x10198/@0x10304` 日志两个数来自 `[+0x18]` 与 `[+0xC0]` | `0x10304` | L2 |
| N-27 | 返回值枚举 `0/1/2/其它` → `0/-2/-106/-103` | `0xC710/C718/C728/C730` | L1 |
| N-28 | `0x10320 ldr x8,[x19,#0x30]` 即日志 `commit : %lld` | `0x10320` | L1 |
| N-29 | `signal_size` 门控 + `commit < expectOffset` 饥饿判定 | `0x10304` 窗口 | L2 |
| N-30 | `PreSeekReadTask` 回退连续提交点并重新发请求 | §61.62 真机观察 | L2 |

> **精度修正（§61.65）**：`commit` 是「连续确认偏移」，**不是** vfile 磁盘落盘字节数。
> N-22 的 `requestSize + signalSize` 阈值是报告级语义（L2），测试以可注入参数验证，
> 未宣称逐指令窗口完全等价。

### 3.3 S 组 —— `NMSlicesFile::Write / Read` 四道静默门控（23 条）

> 实现：`NmSlicesFile.kt`

| 编号 | 逻辑点 | 证据锚点 | 等级 |
|------|--------|----------|------|
| S-01 | `NMSlicesFile::Write @0x127C0` 入口 | `0x127C0` | L1 |
| S-02 | `0x127C0 ldrb [+0xC8]` 读 disabled 标志 | `0x127C0` | L1 |
| S-03 | `0x127CC mov x0,#-0x64` → `-100` | `0x127CC` | L1 |
| S-04 | `0x12810 cbz w2,#0x12828` `len==0` → -102 | `0x12810` | L1 |
| S-05 | `0x12814 tbnz x21,#0x3f,#0x12828` offset 未 64B 对齐 → -102 | `0x12814` | L1 |
| S-06 | `0x12818 ldr x8,[x19,#0x70]` 读 file_len | `0x12818` | L1 |
| S-07 | `0x1281C add x28,x21,w23` `end = offset+len` | `0x1281C` | L1 |
| S-08 | `0x12820 cmp x28,x8` / `0x12824 b.le 0x12868` 越界 → -102 | `0x12820` / `0x12824` / `0x12834` | L1 |
| S-09 | 失败诊断日志门控汇合点 `0x12828 mov w0,#5` | `0x12828` | L1 |
| S-10 | `0x1282C bl 0x37E20` getter N=5 | `0x1282C` | L1 |
| S-11 | `0x12830 tbz w0,#0,#0x1283c` `g_level<=5` 才进日志 | `0x12830` | L1 |
| S-12 | `0x1283C` 打 `NMSlicesFile Write buffer len:...` W 级日志 | `0x1283C` / `0x3CF90` | L1 |
| S-13 | `g_level>5` 不打日志但 `0x12860 mov x0,#-0x66` 返回码相同 | `0x12834` / `0x12860` | L1 |
| S-14 | `0x12868 cmp x8,x21` `file_len == offset ?` | `0x12868` | L1 |
| S-15 | `0x1286C b.ne 0x12884` / `0x1287C mov x0,#-1` EOF → -1 | `0x1286C` / `0x1287C` / `0x3CFCE` | L1 |
| S-16 | `0x12890/0x12894 sdiv` 按 `[+0xD0] slice_size` 算起止片索引 | `0x12890` / `0x12894` | L2 |
| S-17 | `0x12980 bl 0x150E8` 实际分片写循环 | `0x12980` / `0x150E8` | L1 |
| S-18 | `0x12AF4 tbnz w0,#0x1f` 写失败 → `0x12B30 mov x0,#-0x62` = -98 | `0x12AF4` / `0x12B30` | L1 |
| S-19 | `0x12AD4/0x12ADC` 还原 `Write all finish` @`0x3D0AE` | `0x12AD4` / `0x3D0AE` | L1 |
| S-20 | `0x12AEC` 成功路径置完成标志 `[+0x104]` | `0x12AEC` | L1 |
| S-21 | `0x12A84 mov x0,xzr` 成功返回 0 | `0x12A84` | L1 |
| S-22 | `Read` EOF 为 I(4) 级 `tbz → 进日志`（§61.70 §3.1） | `0x12C5C` | L2 |
| S-23 | `Read` 尾 `0x12C48` 为 W(5) 级 `tbz → 进日志` | `0x12C48` | L2 |

> **分支顺序已核实**：`0x12824 b.le 0x12868` ⇒ EOF 判断（S-14/S-15）在越界门控**之后**，
> 与 Kotlin 实现顺序一致。EOF 分支在 `len>0 && end<=file_len` 的正常输入下数学上不可达
> （`len==0` 已被 S-04 拦），属原生二进制固有特性，**1:1 保留**，不改写为「更合理」的逻辑。
> S-16 分片上界按 `end / slice_size` 直译；原生是否为 `(end-1)/slice_size` 未逐指令坐实（L2），
> 列为待补证项，测试如实断言当前实现。

### 3.4 B 组 —— `NMBufferSourceImp` 写链 / 错误链（30 条）

> 实现：`NmBufferSourceImp.kt`

| 编号 | 逻辑点 | 证据锚点 | 等级 |
|------|--------|----------|------|
| B-01 | `NMBufferSourceImp::Seek`（rodata `0x3B927`） | `0x3B927` | L2 |
| B-02 | `OnNetSourceData @0xBA44` 的 `kind==2` 分支 | `0xBAAC` / `0xBAB0` | L1 |
| B-03 | `0xBAAC cmp w23,#2` | `0xBAAC` | L1 |
| B-04 | `kind!=2` → `0xBAE8 bl 0xFE48` `NMMemoryCache::Write` 内存镜像 | `0xBAE8` / `0xFE48` | L1 |
| B-05 | `kind==2` 跳过内存缓存写，两种 kind 都汇合 `0xBB34` 构造任务 | `0xBB34` | L1 |
| B-06 | `0xBC10 bl 0x16468` 投递同一写任务队列 | `0xBC10` / `0x16468` | L1 |
| B-07 | `OnWriteNetData @0xBCAC` 入口 | `0xBCAC` | L1 |
| B-08 | `0xBCD8 ldrb [+0xB8]` 读 normal-mode 标志 | `0xBCD8` | L1 |
| B-09 | `0xBDD4 mov w0,#5` W 级 `Failed Not Support.` | `0xBDD4` | L2 |
| B-10 | buffer 无效 → `Failed Data Invalid.` 直接返回 | `0xBCD8` 窗口 | L2 |
| B-11 | `0xBD00 ldr x8,[x19,#0xA8]` 读 `file_offset_` 水位 | `0xBD00` | L1 |
| B-12 | `0xBD14 b.le 0xBE88` `end <= file_offset_` → SKIP 整包跳过 | `0xBD14` / `0xBE88` | L1 |
| B-13 | `0xBD18 subs x23,x8,x21` `offset < file_offset_ < end` → TRIM 只写尾部 | `0xBD18` | L1 |
| B-14 | `0xBE80 strb wzr,[x19,#0xB8]` 写失败后**永久**清零 normal-mode | `0xBE80` | L1 |
| B-15 | `0xBED4 bl 0x8940` → `NotifyCacheProgress(long)` | `0xBED4` / `0x8940` | L1 |
| B-16 | `OnNetSourceDataDone @0xBEE8`，`0xBF08 cmp w1,#2` → `PreloadDoneTask` | `0xBEE8` / `0xBF08` | L1 |
| B-17 | `OnFlushNetData @0xC068`（size 88） | `0xC068` | L2 |
| B-18 | `NotifyCacheProgress @0xC0C8`（size 324），PLT `0x8940` | `0xC0C8` / `0x8940` | L2 |
| B-19 | `SetFileOffset @0xB740`（size 104），唯一具名写入 `[+0xA8]` | `0xB740` | L2 |
| B-20 | `ReadFromFileNoLock @0xC4B8` 也会清零 `[+0xB8]`；触发条件未逐指令坐实 | §61.65 登记 | **L3** |
| B-21 | `0xC434 blr [vtable+0x28]` 返回值 w23 即日志里的 result | `0xC434` | L1 |
| B-22 | `0xC6F8 bl 0x10198` WaitRequest；`0→0 / 1→-2 / 2→-106 / 其它→-103` | `0xC6F8` / `0xC710~0xC730` | L1 |
| B-23 | `0xA9BC mov w25,#-2` 与 `0xA9C4 mov w25,#-0x67` 两条硬编码分支 | `0xA9BC` / `0xA9C4` | L1 |
| B-24 | 共用 `0xA9E4 str w25,[+0x1A0]` | `0xA9E4` | L1 |
| B-25 | `0xA9D0 ldr x8,[+0x198]` + `0xA9D4 cbz`：仅挂起异步读非零才写 `[+0x1A0]` | `0xA9D0` / `0xA9D4` | L1 |
| B-26 | `[+0x198]` 登记/清零共 6 处：`0x9F44 / 0x10B20 / 0xA018 / 0x100C8 / 0x106C0 / 0x1095C` | 六处地址 | L2 |
| B-27 | `Finish Async` `0xA008 ldr w20,[+0x1A0]` → `0xA010~0xA018` 清零 → `0xA07C ret` 原样返回 | `0xA008~0xA07C` | L1 |
| B-28 | `.rodata 0x3B8F3` = `NMBufferSourceImp::Read((%lld-%lld) Finish Async:%d` | `0x3B8F3` / `0xA040` | L1 |
| B-29 | `0xC478 cmn w23,#2; b.eq 0xC4C4` 打印后对 -2 的判定分支 | `0xC478` / `0xC4C4` | L1 |
| B-30 | 另一处 `mov x0,#-2`（`0x12DC8`）越界门控，与入口 -102 是独立路径 | `0x12DC8` | **L3** |

> B-20 / B-30 落地为**独立可调用语义**（`clearNormalModeOnReadPath()` / `secondaryRangeGate()`），
> 不并入主分支，不虚构触发条件。

### 3.5 A 组 —— `libncmaudioplayer.so` `AudioTrack::Write` 四道门控（12 条）

> 实现：`NmAudioPlayer.kt`

| 编号 | 逻辑点 | 证据锚点 | 等级 |
|------|--------|----------|------|
| A-01 | `.rodata 0xE180A` 全 .text **唯一**引用点 `0x66EEC/0x66EF4` | `0x66EEC` / `0xE180A` | L1 |
| A-02 | `0x66F00 bl 0x5C18C` 唯一打印点 `Write start play nErr:%d` | `0x66F00` / `0x5C18C` | L1 |
| A-03 | 门控 1：`[+0x1E8]` AudioTrack 为空 → `0x66F50` 返回 -1，不打印 | `0x66E70/0x66E74` | L1 |
| A-04 | 门控 2：二次判空 → `0x66F40` 收尾 -1 | `0x66EA8/0x66EAC` | L1 |
| A-05 | 门控 3：`[+0x20C]` paused 非零 → `0x66F5C` 跳过打印 | `0x66EB0/0x66EB4` | L1 |
| A-06 | 门控 4：`bl 0x13D80` 返回 3 → `0x66F5C` 跳过打印 | `0x66EC8/0x66ECC` | L1 |
| A-07 | `0x66EDC bl 0x14500` 返回值作为 nErr 打印 | `0x66EDC` / `0x14500` | L1 |
| A-08 | nErr==0 时 `0x66F58` 清零 `[+0x354]` 重试计数 | `0x66F58` | L1 |
| A-09 | nErr!=0 且计数 >9 → `0x66F28` 经 `[+0xD8] vtable+0x20` 回调传 -3 | `0x66F28` / `0x66F28` | L1 |
| A-10 | paused：`0x65198` 构造清零 / `0x657D4,0x67478` 置 1 → `vtable+0x150(w1=2)` / `0x67314` 清 0 → `w1=1` | 四处地址 | L1 |
| A-11 | AudioTrack 对象指针设置 `0x572D4/0x583BC`；释放清零 `0x4CEA8/0x4D630/0x55B5C/0x65CD0/0x66810` | 七处地址 | L1 |
| A-12 | `[+0x20C]` paused 字段（`AudioTrack(%d): Paused` @`0xE190A` 坐实） | `0xE190A` | L2 |

> 函数窗口 `0x66E44~0x66FA8`。4 道门控**全放行**才打印；真机 §61.75 观测 `Write start play` 命中 0 次。

### 3.6 P 组 —— Java→native 参数槽透传 / JNI Inventory（12 条）

> 实现：`NmJniBridge.kt`

| 编号 | 逻辑点 | 证据锚点 | 等级 |
|------|--------|----------|------|
| P-01 | ARM64 JNI：`x2=long j(native_ptr)`、`x3=long j2(kind/requestId)`，两条路径 `(x0,x1)=(native_ptr,j2)` 完全同构 | §61.56 | L1 |
| P-02 | `OnNativeDataRequestEnd @0x3495C`：`0x34988 cbz x2` / `0x349A4 mov x21,x3` | `0x3495C` 窗口 | L1 |
| P-03 | trampoline `0x36C70`：`0x36C90/0x36CA4` 原样放回 x1 → `0x36CAC ldr x8,[x8,#0x20]` → `0x36CB0 blr x8` | `0x36C70~0x36CB0` | L1 |
| P-04 | `OnNativeDataRequestData @0x347F4`：`0x34834 mov x21,x3` / `0x34914 bl 0x36F68` | `0x347F4~0x34914` | L1 |
| P-05 | `OnNetSourceDataDone @0xBEE8` 的 `0xBF08 cmp w1,#2` 正是用这个 w1 分支 | `0xBF08` | L1 |
| P-08 | JNI 统计：精确类 12 / native 方法 162 / JNI 符号 202；按 so 分布 118/2/24/58 | `engine-jni-classes.json` | L2 |
| P-09 | `AudioPlayerUnit` 27 个 native 方法（全量见 `NmJniCatalog`） | `engine-jni-classes.json` | L2 |
| P-10 | `Playback` 60 个 native 方法按语义分组（全量见 `NmJniCatalog`） | `engine-jni-classes.json` | L2 |
| P-11 | `PlaybackEngine` 24 个 native 方法 | `engine-jni-classes.json` | L2 |
| P-12 | `PlaybackDataSource` 7 个 native 方法 | `engine-jni-classes.json` | L2 |
| P-13 | `PlaylistJniBridge` 31 / `NEAudioDeviceListener` 2 个 native 方法 | `engine-jni-classes.json` | L2 |
| P-16 | AudioTrack 释放站点登记（与 A-11 同址，跨模块复用编号） | `0x66810` 等 | L2 |

> **边界声明（§61.56）**：静态反汇编只能证明**参数槽无变换**，不能 100% 证明虚表目标的 C++ 类型。
> P-09/P-10 的「节选」缺口已由本轮 C 组补齐：162 个方法在 `NmJniCatalog.kt` 全量登记，
> 并由 `spec/catalog-manifest.json` 的源 JSON 指纹守护防回潮，清单级完整性不再悬空。

### 3.7 C 组 —— Java SDK 入口层 JNI 清单全量登记（8 条）

> 实现：`NmJniCatalog.kt`（由 `tools/gen_catalog.py` 从 `analysis/engine-jni-classes.json` 生成，勿手改）。
> 这是**数据层全量登记**，不是控制逻辑翻译；脱敏口径见 C-04，防回潮见 C-08。

| 编号 | 逻辑点 | 证据锚点 | 等级 |
|------|--------|----------|------|
| C-01 | 19 个真类全量，其中 12 个 `preciseJniClass=true`、7 个非精确 | `classes[].preciseJniClass` | L1 |
| C-02 | 162 个 native 方法全量，按 19 类逐条登记（逐类求和与 `nativeMethodCount` 一致） | `nativeMethods[]` / `nativeMethodCount` | L1 |
| C-03 | 202 个 JNI 符号按 4 个 so 分布：118 / 2 / 24 / 58 | `jniSymbolsByLib` | L1 |
| C-04 | 脱敏口径：只登记简单类名 / 简单类型名，Kotlin 零竞品包路径字面量 | `gen_catalog.py` 硬断言 + `audit.py` 禁项扫描 | L1 |
| C-05 | dex 分布：`classes14.dex`(12 类) / `classes16.dex`(1) / `classes5.dex`(6) | `classes[].dex` | L1 |
| C-06 | `deltaKnown` 2 处已知增量原样登记 | `deltaKnown` | L2 |
| C-07 | 每个方法五字段全量：`name / javaReturn / javaParams / descriptorParams / declaration`；声明串由脱敏类型重组，不照抄含包路径的原串 | `nativeMethods[]` | L1 |
| C-08 | 源 JSON `sha256` + 条目数写入 `spec/catalog-manifest.json`，由 `audit.py` 比对防回潮 | `catalog-manifest.json` | L1 |

### 3.8 F 组 —— `libffmpeg.so` 格式覆盖清单全量登记（8 条）

> 实现：`NmFormatCatalog.kt`（由 `tools/gen_catalog.py` 从 `analysis/l3-b2-m2-decoder-symbols.json` 生成）。
> 证据等级：`ELF_exported_dynsym_symbol`；**不实现 M2、不改 B2 阶段状态、不宣称 F4 已解决**。

| 编号 | 逻辑点 | 证据锚点 | 等级 |
|------|--------|----------|------|
| F-01 | 100 个 `ff_*_decoder` 导出符号全量 | `full_decoder_symbols` | L1 |
| F-02 | 75 个 `ff_*_demuxer` 导出符号全量 | `full_demuxer_symbols` | L1 |
| F-03 | 双 ABI（arm64-v8a / armeabi-v7a）符号集合完全一致；18/18 checks 通过 | `cross_abi_identical` / `checks_total` / `checks_pass` | L1 |
| F-04 | `.symtab` 已被 strip，证据只覆盖 `.dynsym`；不作「未编入」绝对断言 | `symtab_stripped=true` / `boundaries` | L1 |
| F-05 | 9 个「decoder 编入未确认」格式查询逐项登记 | `unconfirmed_9` | L1 |
| F-06 | 15 个候选格式查询逐项登记 | `candidate_15` | L1 |
| F-07 | 19 个 codec 查询逐项登记 | `codec_queries` | L1 |
| F-08 | 8 条边界声明原样登记：导出符号存在 ≠ decoder 可用 ≠ 真机可播；M2 门禁未通过；F4 仍 BLOCKED | `boundaries` / `verdict` | L1 |

> F 组与 C 组一起把上一版「P-09/P-10 语义化节选」「decoder 清单仅统计常量」两处缺口升级为
> **全量逐项登记**；生成器 + manifest 保证「源 JSON 一变 → 指纹不匹配 → audit fail」，
> 防止清单被静态推断写成「已核对」。

### 3.9 D 组 —— `OnNetSourceDataDone` 双分支 / 级联完成链（12 条）

> 实现：`NmBufferSourceImp.kt`（`onNetSourceDataDone()` / `drainDoneQueue()` /
> `preloadDoneTaskOnWriteThread()` / `preloadDoneTaskOnRenderThread()` / `onFlushNetData()`）
> 与 `NmDoneTask` / `NmDoneTaskOutcome` 两个枚举。
> 证据：`analysis/l3-f4-disasm-verify.json` + `-report.md`（§61.54）、
> `analysis/l3-f4-task-diff.json` + `-report.md`（§61.55）。
> **纠错说明**：上一版把 `kind==2` 与 `kind!=2` 两个分支都简化为 `onFlushNetData()`，
> 与一手反汇编「两分支严重不对称 + 级联完成通知链」冲突；本组按指令级证据 1:1 纠正，
> 并同步修正 B-16 / B-17 的测试断言（`ContractTests.kt` section 11）。

| 编号 | 逻辑点 | 证据锚点 | 等级 |
|------|--------|----------|------|
| D-01 | `OnNetSourceDataDone @0xBEE8`（size 384）：`0xBF08 cmp w1,#2` / `0xBF14 b.ne 0xBFA0` 双分支入口，首参 `w1` 即 kind 槽 | `0xBEE8` / `0xBF08` / `0xBF14` | L1 |
| D-02 | `kind==2` → GOT `0x4BED0` → `PreloadDoneTaskOnWriteThreadEv @0xB86C`（288B / 72 指令），投递重任务 | `0xBF5C ldr x9,[x9,#0xed0]` / `0xB86C` | L1 |
| D-03 | `kind!=2` → GOT `0x4BEE8` → `OnFlushNetDataEv @0xC068`（88B / 22 指令），投递轻量任务 | `0xBFE4 ldr x9,[x9,#0xee8]` / `0xC068` | L1 |
| D-04 | 两分支任务对象结构完全一致（`0x60` 字节、同样 vtable 槽 `0x4AD18/0x4AD98/0x4AD50`），唯一差别是 GOT 槽取出的函数指针 | `l3-f4-disasm-verify-report.md` 任务对象对照 | L2 |
| D-05 | 两分支都 `bl 0x16468` 投递**同一队列**：Done 函数窗口内 2 处（`0xBF7C` / `0xC004`，源 JSON `queue_bl_0x16468_count=2`）＋ `PreloadDoneTaskOnWriteThread` 级联 1 处（`0xB924`），本批证据合计 3 处投递点；`QUEUE_POST_CALL_SITES=3` 与源 JSON 的 2 是**不同口径**，不矛盾 | `0xBF7C` / `0xC004` / `0xB924` / `0x16468` | L1 |
| D-06 | 队列取出点 `0xC034 blr x8` 虚调用执行任务；`PreloadDoneWrite` 级联出的 Render 任务在 FIFO 下排到队尾 | `0xC034` / `checks.queue_post_is_shared_address` | L2 |
| D-07 | `OnFlushNetData` 22 指令四道门控：`[+0xB8]` 非零 → `[+0xD8]` 回调对象非空 → 取 vtable → `vtable+0x40` → `0xC08C br x1` 尾调用；任一失败即静默 ret，且**不投递任何新任务** | `0xC070` / `0xC074` / `0xC078` / `0xC07C` / `0xC080` / `0xC084` / `0xC08C` | L1 |
| D-08 | `[+0xB8]==0` → `0xC090 mov w0,#5` → getter `0x37E20` → `0xC098 tbz` → logfn `0x3138C` 打 W(5) 级 `Not Normal Mode.`；`g_level>5` 只抑制打印，不改变分支 outcome | `0xC090~0xC0BC` / §61.70 | L1 |
| D-09 | `PreloadDoneTaskOnWriteThread @0xB86C`：`0xB8D8 mov w10,#1` / `0xB8F0 strb w10,[x0,#0x20]` 置 bool=true | `0xB8D8` / `0xB8F0` | L1 |
| D-10 | 同函数 `0xB8C0 ldr x9,[x9,#0xF28]` 取 GOT `0x4BF28` → `PreloadDoneTaskOnRenderThread @0xADBC`（420B / 105 指令）；`0xB924 bl 0x16468` **级联再投递**同一队列 | `0xB8C0` / `0xB924` / `0x4BF28` / `0xADBC` | L1 |
| D-11 | `PreloadDoneTaskOnRenderThread @0xADBC` 是重完成通知链（getter `0xAE14/0xAEB4`、logfn `0xAE3C/0xAEE4`、`0xAF10 blr x8`、`0xAF50`、`0xAF5C`）；`kind!=2` 分支**不级联**任何新任务 | `0xADBC~0xAF5C` / `l3-f4-task-diff-report.md` | L2 |
| D-12 | `l3-f4-task-diff.json` 的 `checks` 14/14 全通过（含 `done_posts_both_slots_to_same_queue`、`preload_write_posts_cascade_task`、`flush_is_lightweight`、`flush_checks_flag_at_0xb8`、`flush_tail_calls_vtable_0x40`、`queue_post_is_shared_address`），`all_pass=true` | `checks` / `functions` / `all_pass` | L1 |

> D-07 / D-08 的 W 级日志语义与 G 组共用同一 `NmLogGate`：默认 `g_level=0` 时 W(5) 级全开；
> `SetLoggerCallback(...,5)` 抬高阈值后 W 级仍可打印（`5 > 5` 为假），`g_level>5` 才抑制打印。
> 测试对两种配置都断言 outcome 不变、只断言日志条数差异，避免把「日志级别」误写成「控制流分支」。
> D 组把 §61.54 / §61.55 从「报告级结论」提升为「逐指令坐实 + 可运行 Kotlin」，
> 是继 C / F 全量清单之后，对 `docs/12 §61.x` 一手证据的第三类补齐。
> D 组 20 个元数据常量由 `tools/gen_done_chain_manifest.py` 生成的
> `spec/done-chain-manifest.json` 以源 JSON `sha256` 守护，`audit.py` 第 8/9 项每次比对防回潮。

### 3.10 R 组 —— §61.77 读侧空白链一手证据全量登记（R-01~R-08）

> 证据：`analysis/l3-f4-read-gap-chain.json`（31/31 checks、all_pass=true、so_sha256 原样）、
> `analysis/l3-f4-read-gap-chain-report.md`（§61.77）。
> 由 `tools/gen_read_gap_manifest.py` 全量生成 `kt/src/NmReadGapCatalog.kt`（33 项常量 + 3 张清单），
> 受 `spec/read-gap-manifest.json` 的源 JSON `sha256` 守护，`audit.py` 第 10/11 项每次比对。
> **本组纠正两处口径**：① `ReadFromFileNoLock` 日志格式串此前缺 `expect len` 且 `readed_len`
> 硬编码 0，本组按 `.rodata @0x3BEE8` 四字段 1:1 还原；② 该机读 JSON 此前被 `COVERAGE.md`
> 第 9 节算进「C 类不翻译」，但它是 `-report.md` 的同源产物且现被 generator 直接引用，
> 本组改列为**第 18 份 A 类证据锚点**。核心控制流（-2 / -106 / -103 / 挂起异步读）在 B 组已翻译，
> 本组是「全量数据登记 + 精度补全」，不改写 F4 结论。

| 编号 | 逻辑点 | 证据锚点 | 等级 |
|------|--------|----------|------|
| R-01 | `.rodata @0x3BEE8` 格式串 `NMBufferSourceImp(%lld)::ReadFromFileNoLock  Local File Read expect len:%u, offset:%lld, readed_len:%u, result:%d`；唯一引用点 `0xC454 adrp` + `0xC45C add` → `0xC470 bl 0x3138C` 打印；Kotlin 日志四字段顺序 1:1，`readed_len` 按读结果取实参（成功=length / 失败=0），不再硬编码 0 | `0x3BEE8` / `0xC454` / `0xC45C` / `0xC470` | L1 |
| R-02 | `WaitRequest` 四返回码：`0xC6F8 bl 0x10198` → `0xC700 cmp w0,#1` / `0xC704 b.eq 0xC728`；`w0==0`→0、`==1`→`0xC728 -2`、`==2`→`0xC710 -0x6A(-106)`、其它→`0xC730 -0x67(-103)`，`0xC73C ret` | `0xC6F8` / `0xC700` / `0xC710` / `0xC728` / `0xC730` | L1 |
| R-03 | 两条硬编码分支 `0xA9BC mov w25,#-2` / `0xA9C4 mov w25,#-0x67` 共用 `0xA9E4 str w25,[x19,#0x1A0]` 写 Finish Async 错误码 | `0xA9BC` / `0xA9C4` / `0xA9E4` | L1 |
| R-04 | 挂起异步读门控 `0xA9D0 ldr x8,[x19,#0x198]` + `0xA9D4 cbz` → 仅非零才写 `[+0x1A0]`；源 JSON `pending_198_writes` **6 处**全量登记（`0x9F44` 注册 / `0xA018` Finish 清零 / `0x100C8` / `0x106C0` / `0x1095C` / `0x10B20`） | `0xA9D0` / `0xA9D4` / `pending_198_writes` | L1 |
| R-05 | `-2` 的 **7 处**硬编码立即数点全量登记：`0xA9BC / 0xC728 / 0xE720 / 0xE780 / 0xEBDC / 0x12DC8 / 0x1329C`；其中 `0x12DC8` 是 `NMSlicesFile::Write` 越界返回 -2，与入口 -102 是独立路径（B-30 已翻译） | `hardcoded_minus2_points` / `0x12DC8` | L1 |
| R-06 | 源 JSON `anchors` **24 个指令锚点**全量登记为 Kotlin 常量，含代次闸门交叉锚 `0x10794 cmp` / `0x10798 b.ne` / `0x10888 str commit` 与读链锚 `0xC434 blr` / `0xC478 cmn w23,#2` | `anchors` / `0x10794` / `0x10888` | L1 |
| R-07 | 源证据自洽性：`checks_total=31 / checks_pass=31 / all_pass=true`、`F4_STATUS=BLOCKED`、`fix_anchors` 3 条（`0x10798` 代次早退 / `0xC728` 读空 -2 / `0x12DC8` 越界 -2）、`device_run=false` | `checks` / `fix_anchors` / `truthfulness` | L1 |
| R-08 | 证据源登记口径：`l3-f4-read-gap-chain.json` 由「C 类不翻译」改列为**第 18 份 A 类锚点**（`-report.md` 同源机读产物、generator 直接输入），并纳入第 5 份受 `sha256` 守护的源 JSON；清单类数据全量登记但不推断触发条件 | `spec/read-gap-manifest.json` / COVERAGE 第 9 节 | L2 |

> R 组只登记反汇编能证明的地址 / 立即数 / 格式串，不把 `readed_len` 的寄存器来源、
> 7 处 -2 点各自的触发条件写成「已逐指令坐实」；`COVERAGE.md` 第 9 节统计当时同步为
> `178 = 20 已登记 + 158 明确不翻译`（U 组再清点后修正为 `178 = 27 + 151`），
> `F4 仍 BLOCKED` 口径不变。

### 3.11 U 组 —— A3 反汇编补遗与 C 类证据再清点（U-01~U-10）

> 由 `tools/scan_uncovered_evidence.py` 对 C 类 158 份逐份扫描「L1 指令级信号」后，
> 发现 24 份 highRisk；逐份人工判读结果见 `spec/uncovered-evidence-adjudications.json`。
> 其中 2 份 A3 证据是对竞品 so 的 capstone 反汇编、坐实了此前未翻译的控制流（U-01~U-07），
> 5 份机读 JSON 是同名 `-report.md` 的机读源、含独有全量数据（U-08），
> 其余 17 份为引用已登记证据 / 真机现象 / 项目工程账，明确不翻译（U-09）。

| 编号 | 证据锚点 | 实现位置 | 等级 |
|------|----------|----------|------|
| U-01 | `0x9164` 构造函数 `0x91B8 mov w8,#0x101` → `0x91E0 strh w8,[x0,#0xB8]`，一次置 `[+0xB8]` / `[+0xB9]` = 1 | `NmA3Supplement.kt` / `NmA3Error.INIT_BOTH_FLAGS` | L1 |
| U-02 | `0x91FC mov x23,x5` → `0x92A0 strb w23,[x19,#0x98]` 存策略；`0x92C4 stp ... [x19,#0xD8]` 存 NMVirtualFile | `NmMemoryModeGate` | L1 |
| U-03 | `0x941C tbz w23,#0,#0x9428`：策略 bit0 == 0 直接进 Memory Mode | `NmMemoryModeGate.apply()` | L1 |
| U-04 | `0x9420 ldr x8,[x19,#0xD8]` / `0x9424 cbnz x8,#0x9454` / `0x942C strb wzr,[x19,#0xB8]`：bit0==1 且 vfile 非空才跳过，否则清 `[+0xB8]` | `NmMemoryModeGate.apply()` | L1 |
| U-05 | `0x9428 mov w0,#6` + `0x9448 "NMBufferSourceImp(%lld)::Start Use Memory Mode."` | `NmA3Anchors.MEMORY_MODE_LOG_FMT` / `logArgOnEnter` | L1 |
| U-06 | `libncmplayerengine.so` `NMBufferSourceAdapter::Open @0x42350 size 5572`：全 `.text` 10 处 `mov wN,#0x3e9`，4 处（`0x424FC` / `0x42558` / `0x425B0` / `0x42D9C`）落在 Open，通用错误码 1001 | `NmA3Error.OPEN_GENERIC_ERROR` / `NmA3Anchors.OPEN_1001_SITES` | L2 |
| U-07 | `ldr x0,[x21,#0x40]` ISourceCallback* → `ldr x8,[x0]` vtable → `mov w1,#0x3e9` → `ldr x8,[x8,#0x18]` OnError(int) → `blr x8` | `NmBufferSourceAdapter.openWithProbe()` → `NmPlayerCallback.onError` | L2 |
| U-08 | 5 份机读反汇编 JSON 的独有元数据全量登记：25/16/34/19/17 checks、16/12 anchors、7 份源 `sha256` | `NmDisasmMetaCatalog.kt`（生成）/ `spec/a3-meta-manifest.json` | L1 |
| U-09 | C 类 158 份再清点：24 份 highRisk 逐份裁定（2 补翻译 / 5 全量登记 / 17 不翻译），名单指纹受 `audit.py` 第 12 项守护 | `tools/scan_uncovered_evidence.py` / `spec/uncovered-evidence-adjudications.json` | L2 |
| U-10 | 覆盖口径修正：`a3-*` A2/C4、`l3-f4-*` A18/B5/C84、`178 = 27 已登记 + 151 不翻译`、受守护源 5→12 份 | `spec/COVERAGE.md` 第 9 节 | L2 |

> U-06 / U-07 是 **L2**：报告给出语义与地址锚点，10 处立即数的逐条分支上下文未复核，
> 故 `openWithProbe` 用可注入 probe 表达触发条件，不硬编码「哪个具体分支」。
> 本节不把 A3 的 E1~E5 真机现象写成 F4 因果链；`F4 仍 BLOCKED` 口径不变。

---

### 3.12 V 组 —— 章节矩阵机器自证 / 低分证据零漏网 / §61.87~61.88 口径同步（V-01~V-06）

> V 组是**覆盖审计守护规格**（非竞品控制流翻译）：把 U 组的 L1 判据应用到
> `docs/12 §61.x` 章节矩阵，并把「低分证据是否漏掉纯汇编片段」做成机器可复现守护。
> 机读快照见 `spec/chapter-matrix-scan.json` / `spec/lowscore-asm-scan.json`，
> 常量表见 `kt/src/NmChapterMatrixCatalog.kt`（生成物），指纹见 `spec/chapter-matrix-manifest.json`。
> 本节 6 条不计入 A 类逻辑翻译 / B 类数据清单，全目录编号总数 168 + 6 = **174**。

| 编号 | 证据锚点 | 实现位置 | 等级 |
|------|----------|----------|------|
| V-01 | `docs/12` 全章节动态切分 + COVERAGE 第 8 节处置交叉；断言 `unmapped=0` / `missing=[19,23,24,84]` / A 类 `[54,55,56,65,67,70,74,76,77]` / B 类 `[83]` 固定，**不写死总章节数上界** | `tools/scan_chapter_matrix.py` / `spec/chapter-matrix-scan.json` | L1 |
| V-02 | 检出并补登此前漏登的 **16 章**（§61.5~§61.18 共 14 章 + 并行工作流新增 §61.87 / §61.88）；C 类含指令级反汇编候选 0；§61.5（AVIO 虚表布局假设，自标「未定论」，§61.12 改判需运行时调试）/ §61.8（Java 侧 vtable Close 调用链）含 native 锚点但逐份裁定不翻译 | `spec/COVERAGE.md` 第 8 节 / `spec/chapter-matrix-scan.json`（`anchorAdjudications`） | L2 |
| V-03 | U 组 `score<12` 的 134 份证据专项扫描：纯汇编片段候选 **0**、asm 类 fence **0**、带地址前缀指令行 / 连续助记词块 / 同行地址+助记词三类最大值全 0；29 份多 fence 文件逐份裁定为普通 markdown 块，名单受 `sha256` 指纹守护 | `tools/scan_lowscore_asm.py` / `spec/lowscore-asm-scan.json` | L1 |
| V-04 | 2 份扫描 JSON 的 `sha256` + 39 项 Kotlin 常量（32 Int / 5 String / 2 Boolean）写入 `chapter-matrix-manifest.json`，`audit.py` 第 15/16 项每次比对防回潮 | `tools/gen_chapter_matrix_manifest.py` / `spec/chapter-matrix-manifest.json` | L1 |
| V-05 | §61.87 / §61.88 事实口径同步：B2 引擎 F3 22 格 PASS 14 / FAIL 8 / NO_SAMPLE 3、M2 门禁 PASS 解锁 M3、M3 接线完成但门禁未通过；`NmFormatCatalog.VERDICT` / `BOUNDARIES` 里 `M2_GATE_NOT_PASSED` / `NOT RUN` 是 §61.83 源证据的 1:1 登记，源未变故保留；`F4 仍 BLOCKED` 不变 | `spec/COVERAGE.md` 第 6 节 / `NmChapterMatrixCatalog.kt`（B2 事实常量） | L2 |
| V-06 | 契约测试 section 14 断言章节矩阵**自洽关系**（A+B+C=总章数、总章数+NONE=编号总数、unmapped=0）而非写死总章节数，并覆盖低分零漏网、指纹长度、B2 事实与历史 verdict 保留 | `test/ContractTests.kt` | L1 |

> V-01 / V-02 的判据不用 U 组 L1 分数直接判「藏反汇编」：C 类里 §61.68 / §61.69 / §61.71
> 高分来自引用已登记结论的观测 / 工具文档；改用「带地址前缀指令行数 / 连续助记词块 / asm fence」。
> V 组不改变 `F4 仍 BLOCKED`、`M2_GATE_NOT_PASSED`（§61.83 历史 verdict）与本目录不实现 M2 / M3 的口径。

---

### 3.13 W 组 —— 竞品 so 一手反汇编逐指令补译（W-01~W-14）

> W 组用 `tools/w_disasm_evidence.py` 对仓库内真实 `libNMCommonCache.so`（arm64-v8a）
> 做全 `.text` capstone 反汇编（8 窗口 / 238 指令 / 28 锚点全通过；so `sha256` 与 §61.77
> 一手证据一致），把此前 L2 / L3 的挂账升级为 **L1 指令级坐实**，并修正了
> S-16 分片上界的**真实行为差异**。机读证据 `spec/w-disasm-evidence.json`、
> 常量表 `kt/src/NmWDisasmCatalog.kt`（生成物）、指纹 `spec/w-disasm-manifest.json`，
> 由 `audit.py` 第 17/18 项守护。

**S-16 定案**：终止片索引不是 `end/sliceSize` 直除。0x12898 `msub` 求余、
0x128A0 `cinc` 与 0x128A4 `cset` 使 `upper = end/size + (end%size != 0)`，
循环为**半开区间 `[qStart, upper)`**，末片 `last = upper-1 == (end-1)/size`。
旧实现 `qStart..end/size` 闭区间在 `end` 整除 `size` 时**多写一片**；
`NmSlicesFile.write()` 已按指令修正，F4 链路的 `sliceWriteCalls` 由 10 变为 5，
但「代次错配 → `commit` 冻结 16384 → `result:-2` → `Finish Async:-103` →
`Write start play` 0 次」的因果链与 `F4 仍 BLOCKED` 结论**不变**。

**B-20 定案**：`[+0xB8]!=0 && result<0 && result!=-2` 时清 `[+0xB8]` 并返回 -6；
`[+0xB8]==0` 直接 -6；`result>=0 || result==-2` 走边界判定且不清除标志。

**B-30 口径纠错**：`0x12DC8 mov x0,#-2` 的唯一引用是 `0x12CEC b.lt`，
触发条件为**读侧请求长度 <1**（窗口 0x12CC0~0x12CF8），不是 §61.77 报告级描述的
「`NMSlicesFile::Write` 越界门控」。历史 verdict 原样保留，此处以一手指令为准。

| 编号 | 证据锚点 | 实现位置 | 等级 |
|------|----------|----------|------|
| W-01 | 0x37E20~0x37E30 `adrp/ldr/cmp/cset gt/ret`：`g_level > level` 才抑制；0x37E34 为唯一写者 | `NmReadGate.isSuppressed()` / `NmLogGate` | L1 |
| W-02 | 0x1281C `add x28,x21,w23,uxtw` end=offset+len；0x12884 `ldr x10,[+0xD0]` slice_size | `NmSliceWindow.writeWindow()` | L1 |
| W-03 | 0x12890 `sdiv x9,x28,x10` qEnd；0x12894 `sdiv x27,x21,x10` qStart | `NmSliceWindow.compute()` | L1 |
| W-04 | 0x12898 `msub` + 0x1289C `cmp` + 0x128A0 `cinc` + 0x128A4 `cset` → upper=ceil(end/size) | `NmSliceWindow.Window.upper` / `NmSlicesFile.write()` | L1 |
| W-05 | 0x128A8 `cmp x27,x11` + 0x128AC `b.ge 0x12A54` → qStart>=upper 时零分片跳完成 | `NmSliceWindow.Window.empty` | L1 |
| W-06 | 0x128B0 `sub x8,x11,#1` last；0x128D8 `sub x8,x8,x27` count；半开区间 | `NmSliceWindow.Window.last/count/indices` | L1 |
| W-07 | 0x128C4 `mov w9,#0xe8` + 0x128DC `mul` + 0x128F8 `add` → 分片对象步长 232B | `NmSliceWindow.OBJECT_STRIDE` | L1 |
| W-08 | 0xC408 `ldrb [+0xB8]` + 0xC40C `cbz` → 标志为 0 直接返回 -6 | `NmReadGate.readFromFileNoLock()` FLAG_ZERO | L1 |
| W-09 | 0xC474 `tbz w23,#0x1f` + 0xC478 `cmn w23,#2` + 0xC47C `b.eq` → result>=0 或 ==-2 不清除标志 | `NmReadGate` BOUNDARY | L1 |
| W-10 | 0xC480 W(5) 门控 + 0xC4B8 `strb wzr,[+0xB8]` + 0xC4BC `mov w0,#-6` | `NmReadGate` CLEAR_FLAG | L1 |
| W-11 | 0xC4C8 `cbz` → -3；0xC4D4 `add` / 0xC4D8 `csetm w0,ge` → -1 或 0 | `NmReadGate` BOUNDARY | L1 |
| W-12 | 0x12CE0 `cinc` 读侧同样向上取整；0x12CE4 `cmp x12,#1` + 0x12CEC `b.lt 0x12DC8` → len<1 返回 -2 | `NmReadGate.zeroLengthGate()` / `NmSlicesFile.read()` | L1 |
| W-13 | 0x12DAC `tbnz w0,#0x1f` → 0x12DE0 `mov x0,#-0x62` -98；成功时 0x12DB4 累加 w26 并写回 | `NmReadGate.sliceReadLoop()` | L1 |
| W-14 | 0x12810 `cbz w2` + 0x12814 `tbnz x21,#0x3f` → len==0 或未 64B 对齐进入 -102 分支 | `NmSliceWindow.isUnaligned()` | L1 |

> W 组不改变 `F4 仍 BLOCKED`、`M2_GATE_NOT_PASSED`（§61.83 历史 verdict），
> 本目录仍不实现 B2 / M2 / M3。0x12C44 / 0x12C58 的 EOF 日志分支返回码语义
> 仍按报告级保留，不推断。

---

### 3.14 X 组 —— 竞品函数全集 1:1 逻辑目录（X-01~X-08）

> X 组把「是否漏过竞品逻辑」的判据从 docs 章节 / 证据文件改为**真实 so 二进制**：
> `tools/x_function_inventory.py` 枚举 4 个竞品自研 so 的 `.dynsym` STT_FUNC 共 **1241** 个函数；
> `tools/x_semantic_anchors.py` 全量提取 **2670** 条 `.rodata` 字符串、**9611** 条条件分支、
> **862** 个返回立即数、**1964** 处日志引用；`tools/gen_x_catalog.py` 把每个函数生成
> `XFunction` 条目（32 个分块，避免 JVM 64KB 方法上限）。
> `NmXLogic.kt` 的 `NmXControlFlow.evaluate()` 按函数**真实出现过的分支助记词**做门控求值。
> `libffmpeg.so` 是第三方 FFmpeg（2659 函数），单独由 F 组登记，不计入竞品自研逻辑。

| 编号 | 证据锚点 | 实现位置 | 等级 |
|------|----------|----------|------|
| X-01 | 4 个自研 so 的 .dynsym STT_FUNC 全集 1241 个（cache 127 / engine 966 / audio 139 / audio-legacy 9），按地址可查 | `NmXCatalog.FUNCTIONS` / `NmXControlFlow.lookup()` | L1 |
| X-02 | 每个函数必须有且仅有 1 个处置：translated 129 / registered 814 / mechanical 298，**unhandled=0** | `XFunction.disposition` / `NmXControlFlow.unhandled()` | L1 |
| X-03 | 9611 条条件分支逐条登记（addr/mnemonic/op/target）；助记词分布 cbz 3582 / tbnz 1268 / tbz 1129 / b.eq 970 / b.ne 893 / cbnz 795 等，Kotlin 侧按位掩码 1:1 求值 | `x-semantic-anchors.json` / `XBranchKind` / `XFunction.branchMask` | L1 |
| X-04 | `evaluate(addr, XCondition)` 按判零(cbz/cbnz) → 位测试(tbz/tbnz) → 相等 → 大小比较的真实优先级判定，返回路径返回码与判读依据 | `NmXControlFlow.evaluate()` | L1 |
| X-05 | 862 个 `mov w0/x0,#N` 返回立即数全量登记，去重 60 值（含 -1002 / -1001 / -106 / -103 / -6 / -2 / -1） | `NmXCatalog.ERROR_CODES` | L1 |
| X-06 | 按 so / 函数族汇总；814 个 registered 函数（JNI 导出 169、Playback 族等）全部可 lookup 与 evaluate | `NmXControlFlow.bySo()` | L1 |
| X-07 | 2670 条 .rodata 字符串全量登记（不节选）+ 1964 处函数→字符串引用，Kotlin 侧由 sha256 指纹引用 | `x-semantic-anchors.json` / `NmXCatalog.RODATA_STRING_COUNT` | L1 |
| X-08 | 2 份源 JSON sha256/bytes 与 6 项 Int / 2 项 String 常量由 `audit.py` 第 19/20 项守护；符号名脱敏零竞品包名 | `x-manifest.json` / `audit.py` | L1 |

> 边界如实登记：X 组是**函数全集的控制流骨架 1:1 机器翻译 + 可执行门控求值**，
> 不宣称把每个函数的完整 C++ 语义（STL 容器 / 内存管理 / 第三方模板）手写重写；
> 纯机械函数（thunk / trivial / runtime / data_or_linear 共 298 个）逐条给出机器判据的处置理由。
> 不改变 `F4 仍 BLOCKED`，本目录不实现 B2 / M2 / M3。

---

### 3.15 Z 组 —— 代码口径全量逐行翻译 + 语义差分实证（Z-01~Z-10）

> 与 Y 组的判据差别：Y 组以 `.dynsym` 的 STT_FUNC 为函数清单（1241 个 / 90525 条指令），
> 实测只覆盖 4 个 so `.text` 指令的 27%——so 已 strip（无 `.symtab`）。Z 组改用**代码可达性**：
> 从 dynsym 函数 + `.data.rel.ro`/`.data`/`.got`/`.init_array` 的代码指针出发递归下降，
> `bl` 目标自动成为新入口，剩余连续区段按 `adr/adrp` 引用与否分为字面量池与补充代码。
> 最终 `.text` 字节账闭合：**406580 条可解码 = 388068 翻译 + 18512 字面量池 + 0 不可解码**
> （此前 4 条 `decode()==None` 的地址在解码器补全后可正常翻译，`undecodableInReach` 4 → 0；
> 这 4 条原先充当块分隔，补齐后相邻块合并，块数 6326 → 6324）。

| 编号 | 逻辑点 | 实现 / 证据 | 等级 |
|------|--------|-------------|------|
| Z-01 | 代码口径函数全集：6324 个代码块 / 7486 个入口 / 388068 条指令，一条指令一行 `i(...)`；`.text` 三者相加等于全节 | `kt/src/NmCode00..158.kt`（159 文件）/ `spec/z-manifest.json` | L1 |
| Z-02 | 216 个语义 op 编码（`NmOp`）替代「助记词 + 类别」两段式；标量与向量整数运算分别分桶（`ADD_REG` vs `ADD_VEC_I` 等） | `NmOp.kt`（由 `tools/z_isa.py` 生成）/ `tools/z_isa.py` | L1 |
| Z-03 | 块索引：每块 (lo, span) 以 base64 字符串常量携带，运行期 `pack()` 成 `LongArray`；`blockIndexOf` 二分定位，lo/hi 双向闭合 | `NmTranslatedCatalog.kt` / `audit.py` 第 23 项 | L1 |
| Z-04 | 7486 个函数入口地址表（每域升序、落在块内），入口可直接经 `NmMachine.call` 调用；每个入口另有 `nf_*` 包装 | `NmTranslatedCatalog.entryAddrs` / `NmFuncs00..14.kt` | L1 |
| Z-05 | 跨块/跨函数调度：`bl` 写 x30 跳目标、`ret` 跳 x30、尾调用与间接跳转真实执行；块内自循环由 `abortAt` 指令预算兜住 | `NmMachine.kt` / `NmBlocks.kt` | L1 |
| Z-06 | 指令语义完备：NZCV 逐位推导、16 条件码、宽度截断、pre/post-index 写回、寄存器偏移扩展、浮点 S/D/H、NEON 逐车道；`when(op)` 无实现分支 = 0，dropout = 0，向量错桶 = 0 | `NmArm64.kt` / `spec/z-audit.json` / `audit.py` 第 26 项 | L1 |
| Z-07 | 外部依赖如实登记：MRS 计数器用确定性桩并计数、SVC 计数、PLT 界外目标记 `unresolvedTargets`、BRK/UDF 记 trap 并停机 | `NmExternal` / 契约测试 Z-08 | L1 |
| Z-08 | JVM 上限处置：索引不用 `longArrayOf` 字面量（13000 个 Long 常量会让 `<clinit>` 超 64KB，已用最小样例实证 `Method too large`）；块分派用每函数 600 分支 tableswitch；7486 包装按每文件 500 拆分；编译按「状态层 → 块批次 → 调度层 → 测试」13 趟分批，避开单趟 OOM | `tools/build.py` / `NmBlocks.kt` / `spec/z-manifest.json` 的 `jvmLimits` | L1 |
| Z-09 | 语义差分实证（回答「翻译得对不对」，而非「有没有翻译」）：以 Unicorn ARM64 为参照真值，同一初始状态（`x0..x29=0`、`v0..v31=0`、`fpcr/fpsr=0`、内存按 so 的 PT_LOAD 段灌入、`x30=RET_SENTINEL` 落在未映射页）逐拍比对 `x/v/sp/nzcv` 与写过的内存；停机口径三条——离开 `.text`、执行 `mrs/msr/svc/hvc/smc` **之前**（我方是确定性桩值）、`ret` 取指 fault；Kotlin 停在未翻译目标（`kotlinUnresolved > 0`）而 Oracle 继续跑桩时，把 Oracle 预算收到 Kotlin 实际执行条数再比一次，**比到共同可执行区间末尾**全等才改判 `externalStop` | `tools/z_diff.py` / `spec/z-diff.json` / `test/NmDiffDump.kt` / `audit.py` 第 28 项 | L1 |
| Z-10 | 差分暴露的语义缺陷逐条修正（每条都有「首个分歧拍 + 触发指令」证据）：① 块内分支跳回**块入口**时兜底 `pc = p + 4` 会吞掉入口那条指令 → 加 `steps == s0` 判据；② `ldr/str` 真实宽度由数据寄存器决定（助记符不带宽度后缀），独占写取 `regs[1]`；③ capstone 的 `shift.type` / `ext` 是**整数枚举**（LSL=1…ROR=5、UXTB=1…SXTX=8），此前除 LSL 外全退化为 LSL、扩展全丢；④ `movi dN, #imm` 标量形式（无排列码）；⑤ `ext vN.8b` 按排列码定左右拼接宽度；⑥ `scvtf/ucvtf/fcvtzs` 带排列码时逐车道；⑦ `fmov` 通用↔浮点是**位拷贝**不是数值转换；⑧ `mov dN, vM.d[k]` 是 lane 提取并清高位；⑨ `fadd/fmul/fabd/fmaxnm/...` 带排列码必须走向量桶 | `tools/z_isa.py` / `tools/z_transpile.py` / `kt/src/NmArm64.kt` / `spec/z-diff.json` | L1 |



#### 3.15.1 差分实证结果（`spec/z-diff.json`，可复跑）

| 项 | 值 |
|----|----|
| 参照真值 | Unicorn 2.1.4（QEMU TCG），ARM64 LE |
| 抽样 | 4 个域各 60 个入口（共 240 例），`maxInsn = 200` |
| 比对字段 | `x0..x29` / `SP` / `NZCV` / `v0..v31`（含高 64 位）/ 栈区 128 KiB 的 FNV 摘要 |
| 结果 | **240/240 全等**，逐拍比对的指令合计 **5231** 条，`mismatches = 0` |
| 停机分布 | `count`(跑满预算) 8 / `left-text`(尾调用进 PLT) 89 / `fault`(ret 取指哨兵) 91 / `boundary`(mrs/svc 之前) 52 |
| 口径对称化 | `external-stop reconciled = 1`（Oracle 预算收到 Kotlin 实际条数后逐字段全等才改判，且单独记账） |

差分在**收敛过程中揪出 9 类语义缺陷**（不是「补了测试让它变绿」，而是每条都定位到
「首个分歧拍 + 触发指令」后修实现）：块自循环被兜底跳过、`ldr/str` 宽度取错、
移位/扩展类型退化成 LSL、`movi dN` 标量形式、`ext .8b` 拼接错位、`scvtf/fcvtzs`
只算 lane0、`fmov` 当成数值转换、`mov dN,vM.d[k]` 整寄存器拷贝、`fadd/fmul` 走标量桶；
本轮又补两处：`fnmsub` 的符号零（`-Sa+(Sn*Sm)` ≠ `-(Sa-Sn*Sm)`）与 `fcvtz*` 的
**位宽饱和**（原实现溢出回绕）。全部固化进 Z-10 与契约断言。

> **诚实边界**：5231 / 388068 ≈ 1.35%，是**抽样**差分，不是「全量语义等价」的证明；
> 52 例 `mrs/svc` 与 89 例 PLT 尾调用停在外部边界，边界之外不可比。它证明的是
> 「已抽取样本在共同可执行区间内逐条等价」，配合 Z-06 的静态完备性（`when(op)`
> 零缺分支 / dropout 0 / 向量错桶 0）构成当前最高强度的语义证据。

---

## 4. 审计守护

`tools/audit.py` 每次运行检查：

1. **编号闭环**：`kt/src/*.kt` 与 `test/*.kt` 里出现的每个 `@spec` 编号，必须在本文第 3 节出现；
2. **零孤儿编号**：代码里的编号不得落在规格集合之外；
3. **等级完整**：每个规格编号必须带 `L1/L2/L3` 之一；
4. **禁项扫描**：全部 Kotlin 源码不得出现 `com.netease.*` import、`android.*` import、Windows/Unix 绝对路径；
5. **产物完整性**：`build/test-report.json` 的 `overallOk` 必须为 true、测试失败数为 0；
6. **清单指纹**：`spec/catalog-manifest.json` 记录的源 JSON `sha256` 必须与当前源文件一致（防证据回潮）；
7. **清单计数**：Kotlin catalog 里的 `*_COUNT` 常量必须与 manifest 记录一致；
8. **D 组指纹**：`spec/done-chain-manifest.json` 记录的两份 §61.54/§61.55 源 JSON `sha256` 必须与当前源文件一致；
9. **D 组常量**：`NmBufferSourceImp.kt` 的 20 个 D 组元数据常量必须与 manifest 的 `kotlinConstants` 一致（解析支持十进制与 `0x` 十六进制字面量）；
10. **R 组指纹**：`spec/read-gap-manifest.json` 记录的 §61.77 源 JSON `sha256` 必须与当前源文件一致；
11. **R 组常量**：`NmReadGapCatalog.kt` 的 33 项常量必须与 manifest 的 `kotlinConstants` 一致；
12. **再清点裁定**：`uncovered-evidence-scan.json` 的 highRisk 名单指纹与裁定表一致，且每份候选都有合法裁定与理由；
13. **U 组指纹**：`a3-meta-manifest.json` 记录的 7 份源 `sha256` 必须与当前磁盘一致；
14. **U 组常量**：`NmDisasmMetaCatalog.kt` 的 Int / String 常量必须与 manifest 一致；
15. **V 组指纹**：`chapter-matrix-manifest.json` 记录的 2 份扫描快照 `sha256` / 字节数必须与磁盘一致；
16. **V 组常量**：`NmChapterMatrixCatalog.kt` 的 39 项 Int / String / Boolean 常量必须与 manifest 一致；
17. **W 组指纹**：`w-disasm-manifest.json` 记录的一手反汇编证据 `sha256` / bytes 与磁盘一致，且证据锚点全通过、so 指纹一致；
18. **W 组常量**：`NmWDisasmCatalog.kt` 的 21 项 Int / 2 项 String 常量必须与 manifest 一致；
19. **X 组指纹**：`x-function-inventory.json` / `x-semantic-anchors.json` 的 `sha256` 与 `x-manifest.json` 一致，且函数全集**零未处置**；
20. **X 组常量**：`NmXCatalog.kt` 的 6 项 Int / 2 项 String 常量必须与 manifest 一致；
21. **Z 组指纹**：4 个竞品自研 so 的 `sha256` / 字节数 与 `z-manifest.json` 一致；
22. **Z 组逐行守恒**：`NmCode*.kt` 里 `i(...)` 行数 == 388068、块函数 == 6324、入口包装 == 7486、分派分支 == 6324；
23. **Z 组常量**：`NmTranslatedCatalog.kt` 的 `BLOCK_COUNT` / `ENTRY_COUNT` / `INSTRUCTION_COUNT` + 8 项分域常量与 manifest 一致，且 `.text` 字节账三者相加闭合；
24. **Z 组语义零残留**：`NmArm64.kt` 的 `when(op)` 覆盖全部 op（`UNKNOWN` / `COUNT` 除外）**零缺分支**，且 `z-audit.json` 的 `dropoutTotal == 0`、`vectorLeakTotal == 0`；
25. **Z 组块兜底判据**：`NmCode*.kt` 的每个块函数必须有且只有一处 `val s0 = steps` 与 `if (steps == s0 && pc == p) pc = p + 4`，锁死「块内分支跳回块入口被兜底当成无人匹配、`+4` 跳过入口指令」这类静默丢指令的回归（§Z-10 ①）；
26. **Z 组差分证据自洽**：`spec/z-diff.json` 的 `cases == ok`、`instructionsCompared > 0`、`perDomain` 每域 `ok == total > 0`，且 `externalStop` 改判条数单独记账（可审计，不允许静默吸收差异）。

输出：`build/audit-report.json`（含 `pass`、逐项明细、编号总数、禁项命中数、清单 / D / R / U / V / W / X / Z 组指纹结果）。

> 文中按主题合并编号（26 条），实际断言项 **27 个**：如第 22 条同时校验 `i(...)` 行数 / 块函数 / 入口包装 / 分派分支四项守恒，
> 第 1、2 条编号闭环与零孤儿各自独立成项。

---

## 5. 如何验证

```bash
# 从两份源 JSON 全量重新生成脱敏 catalog + 指纹 manifest
python tools/gen_catalog.py

# 从 §61.54/§61.55 源 JSON 生成 D 组元数据指纹 manifest
python tools/gen_done_chain_manifest.py

# 从 §61.77 源 JSON 生成 R 组全量登记 catalog + 指纹 manifest
python tools/gen_read_gap_manifest.py

# 从 7 份 A3 / 机读反汇编证据生成 U 组 catalog + 指纹 manifest
python tools/gen_a3_meta_manifest.py

# 对 C 类 158 份做 L1 指令级信号再清点（输出 scan JSON，裁定表由人工判读维护）
python tools/scan_uncovered_evidence.py

# V 组：章节矩阵机器自证 / 低分证据零漏网 / 生成 catalog + 指纹 manifest
python tools/scan_chapter_matrix.py
python tools/scan_lowscore_asm.py
python tools/gen_chapter_matrix_manifest.py

# W 组：从真实 so 逐指令反汇编取证 / 生成 catalog + 指纹 manifest
python tools/w_disasm_evidence.py
python tools/gen_w_manifest.py

# X 组：函数全集穷举 / 语义锚点全量取证 / 生成 Kotlin 逻辑目录
python tools/x_function_inventory.py
python tools/x_semantic_anchors.py
python tools/gen_x_catalog.py

# Z 组：代码可达性发现全集 -> 6326 块 / 7486 入口 / 388064 指令逐行转译为 Kotlin
python tools/z_transpile.py        # 需 capstone（用装有 capstone 的解释器执行）

# Z 组语义残留审计：decode 失败（dropout）与「向量操作数进了标量桶」（vector-leak）
python tools/z_audit.py

# 全量编译 + 契约测试 + 生成 F4 链路报告（含完成通知链旁路验证）
python tools/build.py

# 规格/禁项/产物/清单/D 组/R 组/再清点裁定/U/V/W/X/Z 组 27 项审计
python tools/audit.py
```

预期：契约测试全绿（`724/724`）、`overallOk=true`、`audit pass=true`（27 项检查全 OK）。
`F4-CHAIN-REPORT.md` 由 `NmF4Chain.reproduce()` 真实运行生成，不是手写叙述；
其中「完成通知链旁路验证」是独立实例跑出的静态分支语义，明确标注非 F4 因果环节。
