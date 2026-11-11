# reverse-engine —— 竞品播放内核 1:1 Kotlin 逻辑翻译

> **结论先行**：本目录把 `docs/12 §61.54 ~ §61.86` 的反汇编证据逐条翻译成纯 JVM Kotlin，
> 共 **168 条 `@spec` 逻辑点**（G15 / N30 / S23 / B30 / A12 / P12 / C8 / F8 / D12 / R8 / U10）、
> **492 条契约断言全绿**（13 个 section）、**14 项审计全 OK**、**F4 阻塞链路可运行复现**；
> 两份一手清单（JNI 19 类 / 162 方法 / 202 符号、libffmpeg 100 decoder / 75 demuxer）
> 已**全量脱敏登记**，并由源 `sha256` 指纹守护防回潮。
> 上一版 `OnNetSourceDataDone` 双分支简化错误已按 §61.54 / §61.55 一手反汇编 1:1 纠正（D 组 12 条）；
> R 组把 §61.77 `read-gap-chain.json` 一手指令级证据全量登记（33 项常量 + 3 张清单，受 `sha256` 守护），
> 并把 `ReadFromFileNoLock` 日志按 `.rodata @0x3BEE8` 四字段 1:1 还原（R 组 8 条）；
> 本批 U 组对 C 类 158 份证据做 L1 信号再清点，补回 2 份 A3 反汇编里**此前未翻译的控制流**
> （构造期 Memory Mode 双门控 L1、`Open()` 的 `onError(1001)` 链 L2），
> 并把 5 份机读反汇编 JSON 的独有元数据全量登记（U 组 10 条）；
> `spec/COVERAGE.md` 第 8 节给出 `docs/12 §61.x` 全部 82 个有标题章节的逐条处置矩阵，
> 第 9 节给出 `analysis/` 178 份证据文件全集处置矩阵与 24 份 highRisk 逐份裁定，不留隐式空白。
> 交付物是**行为等价的逻辑代码**，不含任何二进制 / dex / so / 资源 / 私有资产；
> 目的是个人学习与后续按需抽取子集。

---

## 1. 这是什么 / 不是什么

| | |
|---|---|
| ✅ 是 | 反汇编可证明的控制流、数据流、门控条件、错误码、日志语义的 Kotlin 翻译 |
| ✅ 是 | 纯 JVM（`cloudmusic.reverse.kernel`），无 Android 框架依赖、无 `com.netease.*` import |
| ✅ 是 | 结构体偏移保留为常量、native 立即数错误码保留为常量，便于对照 `[this+0xXX]` |
| ✅ 是 | JNI / libffmpeg / §61.77 / A3 / 机读反汇编五类清单与常量表的**全量逐项登记**（不是节选、不是统计常量），生成器 + 指纹 manifest 防回潮 |
| ❌ 不是 | `.so` / dex / 资源 / 私有协议实现的复制或打包 |
| ❌ 不是 | B2 生产路线实现，也不宣称 F4 已解决（真机结论仍是 `F4 仍 BLOCKED`） |
| ❌ 不是 | 对现有 Gradle 模块的改动（不接 `settings.gradle.kts`、不改任何 build 文件） |

---

## 2. 目录结构

```
reverse-engine/
├── README.md                     # 本文件
├── F4-CHAIN-REPORT.md            # 【结果】F4 链路复现报告（build.py 自动生成，勿手改）
├── spec/
│   ├── LOGIC-SPEC.md             # 168 条 @spec 的证据/实现/等级映射表（含 3.9 D / 3.10 R / 3.11 U 组）
│   ├── COVERAGE.md               # 覆盖总账 + 第 8 节章节矩阵 + 第 9 节 evidence 矩阵
│   ├── catalog-manifest.json     # 源 JSON sha256 + 条目数 + Kotlin 常量值（防回潮，勿手改）
│   ├── done-chain-manifest.json  # §61.54/55 源 JSON sha256 + 20 项 D 组常量期望值（防回潮）
│   ├── read-gap-manifest.json    # §61.77 源 JSON sha256 + 33 项 R 组常量期望值（防回潮）
│   ├── a3-meta-manifest.json     # U 组 7 份源 sha256 + 50 项常量期望值（防回潮）
│   ├── uncovered-evidence-scan.json          # C 类 158 份 L1 信号扫描结果（24 份 highRisk）
│   └── uncovered-evidence-adjudications.json # 24 份 highRisk 逐份裁定 + 名单指纹
├── kt/src/                       # 【核心交付】13 个纯 JVM Kotlin 源文件
│   ├── NmNativeTypes.kt          #   偏移常量 / 错误码 / kind / 日志级别 / 抽象接口
│   ├── NmLogGate.kt              #   libNMCommonCache.so 全局日志门控（g_level）
│   ├── NmNetSource.kt            #   请求代次闸门 / commit 记账 / 唤醒链
│   ├── NmSlicesFile.kt           #   四道静默不落盘门控 / 分片写
│   ├── NmBufferSourceImp.kt      #   OnNetSourceData / OnWriteNetData / -103 错误链 / Done 双分支 / 读侧日志 1:1
│   ├── NmAudioPlayer.kt          #   AudioTrack::Write 四道门控 / retry / callback
│   ├── NmJniBridge.kt            #   Java→native 参数槽透传 / JNI Inventory
│   ├── NmF4Chain.kt              #   端到端 F4 阻塞链路复现（集成视图）
│   ├── NmJniCatalog.kt           #   【全量】19 类 / 162 native 方法 / 202 JNI 符号（生成，勿手改）
│   ├── NmFormatCatalog.kt        #   【全量】100 decoder / 75 demuxer + 查询矩阵（生成，勿手改）
│   ├── NmReadGapCatalog.kt       #   【全量】§61.77 33 项常量 + 24 锚点 / 6+7+3 清单（生成，勿手改）
│   ├── NmA3Supplement.kt         #   U 组：构造期 Memory Mode 双门控（L1）+ Open() onError(1001) 链（L2）
│   └── NmDisasmMetaCatalog.kt    #   【全量】U 组 7 份源 / 50 项 Int 常量元数据（生成，勿手改）
├── test/
│   └── ContractTests.kt          # 492 条纯 JVM 契约断言（13 个 section，零外部依赖）
├── tools/
│   ├── gen_catalog.py            # 从源 JSON 全量生成脱敏 catalog + 指纹 manifest
│   ├── gen_done_chain_manifest.py # 从 §61.54/55 源 JSON 生成 D 组元数据指纹 manifest
│   ├── gen_read_gap_manifest.py  # 从 §61.77 源 JSON 全量生成 R 组 catalog + 指纹 manifest
│   ├── gen_a3_meta_manifest.py   # 从 7 份 A3 / 机读反汇编证据生成 U 组 catalog + 指纹 manifest
│   ├── scan_uncovered_evidence.py # 对 C 类证据做 L1 指令级信号再清点（输出 scan JSON）
│   ├── build.py                  # 编译 + 测试 + 生成 F4-CHAIN-REPORT.md
│   └── audit.py                  # 14 项长效守护（编号 / 禁项 / 产物 / 清单 / D 组 / R 组 / 再清点裁定 / U 组）
└── build/                        # 编译产物与报告（可整体清理）
    ├── classes/
    ├── test-report.json
    ├── contract-tests.log
    └── audit-report.json
```

---

## 3. 快速验证

环境无 `kotlinc`，`build.py` 自动从本机 Gradle 缓存发现
`kotlin-compiler-embeddable 2.0.20` + 运行时 jar，用 `java` 调 `K2JVMCompiler` 编译。

```bash
cd reverse-engine

# ① 从两份源 JSON 全量重新生成脱敏 catalog + 指纹 manifest
python tools/gen_catalog.py

# ①b 从 §61.54/§61.55 源 JSON 生成 D 组元数据指纹 manifest
python tools/gen_done_chain_manifest.py

# ①c 从 §61.77 源 JSON 全量生成 R 组 catalog + 指纹 manifest
python tools/gen_read_gap_manifest.py

# ①d 从 7 份 A3 / 机读反汇编证据生成 U 组 catalog + 指纹 manifest
python tools/gen_a3_meta_manifest.py

# ①e 对 C 类 158 份做 L1 指令级信号再清点（输出 scan JSON；裁定表由人工判读维护）
python tools/scan_uncovered_evidence.py

# ② 全量编译 14 个 .kt + 跑 492 条契约测试 + 生成 F4 链路报告
python tools/build.py

# ③ 规格/禁项/产物/清单/D 组/R 组/再清点裁定/U 组 14 项审计
python tools/audit.py
```

预期输出：

```
[GEN] NmJniCatalog.kt: 19 classes / 162 methods
[GEN] NmFormatCatalog.kt: 100 decoders / 75 demuxers
[GEN] catalog-manifest.json
[GEN] sanitize assertion: OK (zero com.netease / com/netease)
[GEN-DONE] done-chain-manifest.json
  checks: verify 10/10 | diff 14/14 | kotlinConstants 20 项
[GEN-READGAP] NmReadGapCatalog.kt
  rodata: 0x3BEE8 unique=True | anchors=24 pending198=6 minus2points=7
  checks 31/31 all_pass=True | F4=BLOCKED | kotlinConstants: 33 项
[GEN-READGAP] read-gap-manifest.json
[GEN-A3] sources=7 strConsts=7 intConsts=50
[GEN-A3] sanitize assertion: OK (zero com.netease / com/netease)
[SCAN] analysis root files=178 registered=27 (baseline=20, newlyRegisteredByUGroup=7) uncovered=158 currentUnregistered=151
[SCAN] highRisk(score>=12)=24 -> spec/uncovered-evidence-scan.json
[COMPILE] ok=True exit=0 src=14 jars=34
[TEST]    ok=True exit=0
CONTRACT TESTS: 492/492 passed, 0 failed
[CHAIN]   .../F4-CHAIN-REPORT.md
[RESULT]  overallOk=True

[AUDIT] pass=True
  - codeIdsCoveredBySpec: OK
  - noOrphanSpecIds: OK
  - allSpecRowsHaveEvidenceLevel: OK
  - noBannedPatterns: OK
  - testProductValid: OK
  - catalogFingerprintMatches: OK
  - catalogCountsMatchManifest: OK
  - doneChainFingerprintMatches: OK
  - doneChainConstantsMatch: OK
  - readGapFingerprintMatches: OK
  - readGapConstantsMatch: OK
  - uncoveredEvidenceAdjudicationsClean: OK
  - a3MetaFingerprintMatches: OK
  - a3MetaConstantsMatch: OK
  specIds=168 codeIds=168 groups={'A':12,'B':30,'C':8,'D':12,'F':8,'G':15,'N':30,'P':12,'R':8,'S':23,'U':10}
  adjudications clean=True highRisk=24
  a3Meta fingerprint=True constants=True
```

> 注意：①e 的扫描器只做**候选发现**，不代替人工判读。若证据集合变化导致
> `highRisk` 名单或分数漂移，`audit.py` 第 12 项会 fail，须重新判读并更新裁定表。

---

## 4. 核心结论（F4 链路复现）

`NmF4Chain.reproduce()` 把 6 个类里的逻辑按真机因果顺序串起来，实际运行得到：

| 观测项 | 值 | 证据 |
|--------|-----|------|
| 初始 `g_level` | `0`（日志默认全开） | §61.70 语义纠错 |
| 请求代次 | `1 → 22` 递增 | §61.67 真机观测 |
| `commit` | **冻结 16384** | 代次错配，非日志级别 |
| 旧代次包丢弃 | `8` 次 | `0x10798 b.ne 0x108E0` |
| `UnExpect New Offsetcommted` | 命中 `1` 次（跳跃读，不丢包） | §61.68 上线复跑判据 |
| `WaitRequest` | `result:-2` | §61.77 |
| `Finish Async` | `-103` 原样返回播放侧 | §61.76 |
| `Write start play` | 命中 `0` 次 | §61.74 / §61.75 |

> **断点在代次门控，不是日志级别**；`F4 仍 BLOCKED`。
> 完整 11 步链路表见 `F4-CHAIN-REPORT.md`（由代码真实运行生成，非手写叙述）；
> 报告另含「完成通知链旁路验证」（§61.54/§61.55 静态分支语义），明确标注**非 F4 因果环节**。
> U 组补的 A3 控制流（Memory Mode 门控 / `onError(1001)`）是**另一条阶段证据链**，
> 不并入这 11 步，也不改写 F4 结论。

---

## 5. 如何抽取到自己的项目

1. **按逻辑点抽**：每条逻辑在源码里都有 `// @spec X-NN` 注释，
   在 `spec/LOGIC-SPEC.md` 里可查到它的证据锚点与证据等级。
2. **按类抽**：13 个 `.kt` 之间只有构造依赖，无 Android / 第三方依赖；
   抽单个类时带上 `NmNativeTypes.kt`（偏移/错误码/接口）即可。
   `NmJniCatalog.kt` / `NmFormatCatalog.kt` / `NmReadGapCatalog.kt` /
   `NmDisasmMetaCatalog.kt` 是纯数据常量表，可整表抽走，也可按需舍弃。
3. **替换抽象**：`NmDataSink` / `NmRangeRequester` / `NmPlayerCallback` 是边界接口，
   换成自己项目的真实实现即可；`NmLogGate` 的 `NmLogSink` 换成自己的日志门面。
4. **保留偏移常量**：`NmFieldOffsets` 里每个常量都对应反汇编的 `[this+0xXX]`，
   便于后续和 native 侧对齐，不建议改成「语义化命名后丢掉数值」。
5. **注意证据等级**：标 **L3** 的项（G-15 / B-20 / B-30）是「报告级推断 / 触发条件未坐实」，
   已做成可注入或独立可调用语义，合并时不要当成已坐实的硬逻辑。
6. **清单层**：C / F / R / U 四组是**数据全量登记**而非控制逻辑，抽取时按「常量表」对待；
   不要把它误当成可执行的解码或 JNI 实现。
7. **注意 D 组**：`onNetSourceDataDone()` 的 `kind==2`（重任务 + 级联 Render 通知）与
   `kind!=2`（轻量刷数据尾调用）两个分支**不对称**，抽取时不要合并成同一个函数；
   两分支共用 `0x16468` 队列投递，任务对象同为 `0x60` 字节。
8. **注意 R 组**：`NmReadGapCatalog.kt` 由 `gen_read_gap_manifest.py` 生成、勿手改；
   `readFromFileNoLock` 的日志格式串是 `.rodata @0x3BEE8` 四字段 1:1
   （`expect len` / `offset` / `readed_len` / `result`），
   不要把 `expect len` 漏掉、也不要把 `readed_len` 再硬编码成 0。
9. **注意 U 组**：`NmA3Supplement.kt` 里 U-01~U-05 是 L1 指令级门控（可硬用）；
   U-06 / U-07 是 **L2**，`NmBufferSourceAdapter.openWithProbe()` 用可注入 probe 表达
   「某虚调用返回 0」的触发条件，**不要**把它当成「已坐实具体哪个失败分支」。
   `NmDisasmMetaCatalog.kt` 由 `gen_a3_meta_manifest.py` 生成、勿手改。

---

## 6. 边界与合规声明

- 适用范围：**个人项目 / 学习目的 / 侧载自用**（沿用项目 D-146 定案），不公开发布、不分发 APK。
- 证据全部来自仓库内已有的反汇编 JSON / Markdown 报告 / 文档，未引入新的二进制素材。
- 不引用任何竞品包名（`com.netease.*`）：三个 generator 硬断言 + `audit.py` 禁项扫描双重守护。
- 不写死任何 JDK / SDK / 项目绝对路径；`java` 由 `PATH` / `JAVA_HOME` 推断，
  Kotlin jar 由 `GRADLE_USER_HOME`（默认本机缓存）推断。
- JNI 方法清单、decoder/demuxer 符号清单、§61.77 读侧空白链常量与 A3 / 机读反汇编元数据
  已**全量登记**（数据层，不是控制逻辑翻译），由四个 generator + 四份 manifest 的 `sha256` 指纹守护；
  B2 施工单 / R8 keep 规则等仍属**另一条路线**，仅以统计常量登记，不改变其阶段状态。
- 共 **12 份源（10 份 JSON + 2 份 A3 Markdown）受 `sha256` 指纹守护**，分布于
  `catalog-manifest.json`（2）/ `done-chain-manifest.json`（2）/
  `read-gap-manifest.json`（1）/ `a3-meta-manifest.json`（7）；
  手改 Kotlin 常量或上游证据变更未重生成都会让 `audit.py` fail。
- `scan_uncovered_evidence.py` 对 C 类证据做再清点，`uncovered-evidence-adjudications.json`
  记录 24 份 highRisk 的逐份裁定与名单指纹，`audit.py` 第 12 项守护；证据集合变化须重裁定。
- **不实现 M2、不改 B2 阶段状态、不宣称 F4 已解决**；`F4 仍 BLOCKED`、`M2_GATE_NOT_PASSED` 原样保留。

---

## 7. 验证基线（本次交付）

| 项 | 值 |
|----|-----|
| Kotlin 源文件 | 13（`kt/src`）+ 1 测试文件 = 14 个 `.kt` 全量编译 |
| 契约断言 | 492 / 492 通过，0 失败（13 个 section；section 8 补 F4 旁路验证 15 条、section 12 补 R 组 54 条、section 13 补 U 组 79 条） |
| `@spec` 编号 | 168（G15 / N30 / S23 / B30 / A12 / P12 / C8 / F8 / D12 / R8 / U10），代码与规格双向闭环 |
| 审计检查 | **14 项全 OK**（含 12 份源指纹、Kotlin 常量一致性、24 份 highRisk 裁定名单指纹） |
| 禁项命中 | 0（零 `com.netease` / 零 `android.*` / 零绝对路径） |
| 全量清单 | JNI 19 类 / 162 native 方法 / 202 JNI 符号；libffmpeg 100 decoder / 75 demuxer + 9/15/19 查询矩阵 + 8 边界声明；§61.77 24 锚点 / 6 处 `[+0x198]` / 7 处 -2 / 31 checks；U 组 7 份源 / 50 项 Int 常量 |
| 章节 / 证据覆盖 | `COVERAGE.md` 第 8 节 §61.x 82 章 + 第 9 节 `analysis/` 178 份证据全集（A 20 / B 7 / C 151）+ 24 份 highRisk 逐份裁定 |
| 指纹守护 | 12 份源 `sha256` 分布于 4 份 manifest（catalog 2 / done-chain 2 / read-gap 1 / a3-meta 7）+ D/R/U 组 Kotlin 常量一致性 |
| 编译链 | Kotlin embeddable 2.0.20 + 34 jar 兜底 classpath，JDK 17，约 15s |
| F4 链路 | 11 步复现（因果链不变）+ 完成通知链旁路验证，`F4-CHAIN-REPORT.md` 自动生成 |
| 交付文件 | 31 个（根 2 + spec 8 + kt/src 13 + test 1 + tools 7），全部 LF（`CRLF_bad=[]`）；生成链 `write_text` 已统一 `newline="\n"` |
