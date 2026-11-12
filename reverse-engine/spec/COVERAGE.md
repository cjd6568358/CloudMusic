# COVERAGE —— 1:1 翻译覆盖总账

> **文首结论**：本目录覆盖 `docs/12 §61.54 ~ §61.88` 系列证据中**所有可静态证明的
> 播放内核控制逻辑**（204 条 `@spec`：168 条 A/B 类逻辑与数据点 + 6 条 V 组覆盖守护 + 14 条 W 组一手反汇编补译 + 8 条 X 组函数全集穷举 + 8 条 Z 组全量逐行翻译）
> 与**七份全量清单 / 元数据数据**（B 类）；
> 其余域按「非竞品逻辑 / 数据清单 / 合规边界 / 需真机」逐条给出**明确不翻译的理由**，
> 不留隐式空白。上一版遗留的两处「节选」缺口与 `OnNetSourceDataDone` 双分支简化错误本轮已闭环，
> 并新增第 8 节 `docs/12 §61.x` 全章节处置矩阵（**84 个有标题章节逐条给理由，截至本批 §61.88**）
> 与第 9 节 `analysis/` 178 份一手证据文件全集处置矩阵，剩余挂账如实登记。
> 本批 R 组把 §61.77 机读反汇编从「C 类不翻译」改列第 18 份 A 类锚点，并全量登记 33 项常量。
> 本批 U 组对 C 类 158 份做 L1 信号再清点：2 份 A3 反汇编补翻译（U-01~U-07）、
> 5 份机读 JSON 独有元数据全量登记（U-08）、24 份 highRisk 逐份裁定（U-09），口径同步为 178 = 27 + 151。
> 本批 V 组把同一 L1 判据应用到章节矩阵与低分证据：检出并补登记**此前漏登的 16 个章节**
> （§61.5~§61.18 共 14 章 + 并行工作流新增 §61.87 / §61.88），坐实 C 类 74 章里无遗漏的指令级反汇编；
> 134 份 `score<12` 证据里纯汇编片段候选为 **0**（双零漏网，V-01~V-06 守护规格）。
> 本批 W 组对仓库内真实 `libNMCommonCache.so` 做全 `.text` capstone 反汇编
> （8 窗口 / 238 指令 / 28 锚点全通过，so sha256 与 §61.77 一致），把 S-16 分片上界、
> B-20 / B-30 触发条件与日志门控本体从 L2 / L3 升级为 **L1 指令级坐实**，
> 并**修正分片循环整除时多写一片的真实行为差异**（W-01~W-14）；F4 因果链与 `F4 仍 BLOCKED` 不变。
> 本批 X 组把「是否漏过竞品逻辑」的判据改为**真实 so 二进制函数全集**：4 个竞品自研 so
> 共 1241 个函数（cache 127 / engine 966 / audio 139 / audio-legacy 9）逐个反汇编，
> 全量登记 2670 条 `.rodata` 字符串、9611 条条件分支、862 个返回立即数、1964 处日志引用，
> 处置为 translated 129 / registered 814 / mechanical 298，**unhandled=0**（X-01~X-08）。
> `libffmpeg.so` 2659 个函数是第三方 FFmpeg，由 F 组登记，不计入竞品自研逻辑。
> 本批 Z 组把「不漏过任何一个竞品逻辑」的判据从**符号表**换成**代码可达性**并完成全量逐行翻译：
> Y 组按 `.dynsym` 的 STT_FUNC 取 1241 个函数，实测只覆盖 4 个 so `.text` 指令的 **27%**
> （cache 15.7% / engine 58.0% / audio 4.2% / audio-legacy 1.4%），因为 so 已 strip（无 `.symtab`）。
> Z 组从 dynsym 函数 + `.data.rel.ro`/`.data`/`.got`/`.init_array` 代码指针出发递归下降，
> `bl` 目标自动成为新入口，剩余连续区段按 `adr/adrp` 引用与否分判字面量池与补充代码，得到
> **6326 个代码块 / 7486 个函数入口 / 388064 条指令**，一条指令一行 `i(...)`（159 个分块文件 17.4 MiB）；
> `.text` 字节账闭合：406580 可解码 = 388064 翻译 + 18512 字面量池 + 4 不可解码，
> 覆盖率 90.0% / 95.7% / 97.0% / 92.7%。`NmArm64.kt` 提供语义完备的解释器
> （NZCV 逐位推导、16 条件码、宽度截断、pre/post-index 写回、浮点 S/D/H、NEON 逐车道），
> **212 个语义 op** 全覆盖：`when(op)` 无实现分支 = 0、运行期 dropout = 0、
> 向量错桶 = 0（`add v0.16b` 曾与 `add x0` 共用标量桶，本批拆出 `ADD_VEC_I` 等 8 个向量 op 并实证归零）。
> **Y 组「PRESERVED 1358 条保真记录、无真实语义」的残留已清零。**
> 199 个 `.kt` 分 13 趟编译通过（约 8 分钟），712/712 断言全绿。边界：指令级 1:1 翻译，非 C++ 类型系统重构。
> 同时同步 §61.87 / §61.88 事实口径：B2 引擎 F3 22 格重跑 PASS 14 / FAIL 8、M2 门禁 PASS 解锁 M3、M3 接线完成但门禁未通过
> 属**另一条路线**，本目录仍不实现 M2、`F4 仍 BLOCKED` 不变。

---

## 1. 覆盖类别定义

| 类别 | 含义 | 交付形态 |
|------|------|----------|
| **A 逻辑翻译** | 有控制流 / 数据流 / 门控 / 错误码 / 日志语义，反汇编可证明 | 可运行 Kotlin 类 + 契约断言 |
| **B 数据层全量登记** | 清单 / 符号 / 查询矩阵，无控制流，但要求「不漏一项」 | 生成器产出的全量脱敏常量表 + 指纹 manifest |
| **C 明确不翻译** | 非竞品逻辑、纯数据清单、二进制实现、合规边界、需真机 | 本文件登记理由，不产出代码 |

> 判据：**凡是「逻辑」必进 A；凡是「清单」必进 B 且必须全量；凡是「不翻译」必须写明理由。**

---

## 2. A 类 —— 已逐条逻辑翻译（152 条 `@spec`）

| # | 证据域 | 来源 | Kotlin 实现 | `@spec` | 测试 |
|---|--------|------|-------------|---------|------|
| 1 | 全局日志门控 `g_level` / getter / setter / 注册链 | §61.70 | `NmLogGate.kt` | G-01~G-15 (15) | ✅ |
| 2 | `NMNetSource` 请求代次闸门 / commit / 唤醒 | §61.65 / §61.67 | `NmNetSource.kt` | N-01~N-30 (30) | ✅ |
| 3 | `NMSlicesFile::Write/Read` 四道静默门控 | §61.67 / §61.70 | `NmSlicesFile.kt` | S-01~S-23 (23) | ✅ |
| 4 | `NMBufferSourceImp` 写链 / `-103` 错误链 | §61.65 / §61.76 / §61.77 | `NmBufferSourceImp.kt` | B-01~B-30 (30) | ✅ |
| 5 | `AudioTrack::Write` 四道门控 / retry / callback | §61.74 / §61.76 | `NmAudioPlayer.kt` | A-01~A-12 (12) | ✅ |
| 6 | Java→native 参数槽透传 / JNI Inventory | §61.56 / docs/05 | `NmJniBridge.kt` | P-01~P-13 (12) | ✅ |
| 7 | 结构体偏移 / 错误码 / kind / 抽象接口 | §61.65~§61.77 | `NmNativeTypes.kt` | （被各编号引用） | ✅ |
| 8 | F4 阻塞链路端到端因果复现 | §61.65~§61.77 | `NmF4Chain.kt` | （集成视图） | ✅ |
| 9 | `OnNetSourceDataDone` 双分支 / 共享队列 / 级联完成链 / `OnFlushNetData` 四道门控 | §61.54 / §61.55 | `NmBufferSourceImp.kt` | D-01~D-12 (12) | ✅ |
| 10 | §61.77 读侧空白链：`ReadFromFileNoLock` 日志格式串 1:1 + 24 指令锚点 / 6 处 `[+0x198]` / 7 处 -2 / 31 checks 全量登记 | §61.77 | `NmBufferSourceImp.kt` / `NmReadGapCatalog.kt` | R-01~R-08 (8) | ✅ |
| 11 | A3 反汇编补遗：构造期 `[+0xB8]` 初值 0x101 / Memory Mode 双门控（L1）+ `Open()` 的 `onError(1001)` 虚调用链（L2）+ C 类再清点裁定 | §58 / §56 / U 组 | `NmA3Supplement.kt` / `NmDisasmMetaCatalog.kt` | U-01~U-10 (10) | ✅ |

> 每一条的证据锚点、实现位置、证据等级见 `spec/LOGIC-SPEC.md` 第 3.1~3.11 节。

---

## 3. B 类 —— 本轮补齐的全量清单（16 条 `@spec`）

| # | 证据域 | 来源 | Kotlin 实现 | 全量口径 | `@spec` |
|---|--------|------|-------------|----------|---------|
| 10 | Java SDK 入口层 JNI 清单 | `analysis/engine-jni-classes.json` | `NmJniCatalog.kt` | **19 类 / 162 native 方法 / 202 JNI 符号**逐项 | C-01~C-08 (8) |
| 11 | `libffmpeg.so` 格式覆盖 | `analysis/l3-b2-m2-decoder-symbols.json` | `NmFormatCatalog.kt` | **100 decoder / 75 demuxer** + 9/15/19 查询矩阵 + 8 边界声明 | F-01~F-08 (8) |

> 由 `tools/gen_catalog.py` 从源 JSON 生成，**不做节选**；脱敏口径（零竞品包路径字面量）
> 与源 JSON `sha256` 指纹写入 `spec/catalog-manifest.json`，`tools/audit.py` 每次比对防回潮。
>
> 另：V 组 6 条 `@spec`（V-01~V-06）是**覆盖审计守护规格**，登记在 `LOGIC-SPEC.md` §3.12，
> 不属于 A 类逻辑翻译 / B 类数据清单，故不计入本节 152 + 16 = 168；全目录编号总数 **174**。

---

## 4. C 类 —— 明确不翻译（逐条理由）

| # | 域 | 不翻译理由 |
|---|-----|-----------|
| 12 | B2 生产实现：`IMusicEngine` 14 方法映射 / M0~M5 施工单 / Range 设计 | **另一条路线**，本目录不改其阶段状态、不宣称 F4 已解决；用户主线是「竞品内核逻辑翻译」而非「生产实现」 |
| 13 | 项目自有 `ErrorCatalog`（docs/07 的 `E_*`）/ 配置项（docs/08） | 是**用户项目自有**的错误码与配置，不是竞品内核逻辑；且项目纪律要求新增设置项先登记 docs/08 |
| 14 | `libffmpeg.so` 解码器实现 / FFmpeg 内部 | 二进制实现，非「逻辑」；且涉及 GPL v2+ / R7 边界，只登记导出符号存在性，不作能力或法律结论 |
| 15 | dex 类全量 / R8 keep 规则 / 167 个 so 清单 | 数据清单，无控制流；以 `NmJniBridge.Inventory` 统计常量登记即可 |
| 16 | 真机 logcat 原始归档 / `analysis/l3-f4-backup/` | 证据来源而非翻译对象；48.6 MiB 已 gitignore，不进仓库 |
| 17 | 任何 `.so` / dex / 资源 / 私有协议实现 | **合规边界**：只做行为等价的逻辑翻译，不复制/打包二进制与私有素材 |
| 18 | A3 G09 PARTIAL / 舱不冻结 / 指纹不回填 / T20/T21 DMR / 84 真类全集 / T-L L2 蓝牙 | 属**用户生产工程自身挂账**，不是竞品内核逻辑；改动会破坏 docs/12 计数与 C2 守护，故不纳入 |

---

## 5. 上一版缺口闭环记录

| 上一版遗留 | 本轮处理 | 状态 |
|-----------|----------|------|
| P-09/P-10 方法清单是「语义化节选」，清单级完整性未核对 | C 组把 162 个方法全量登记 + 源 JSON 指纹守护 | ✅ **已闭环** |
| `libffmpeg.so` decoder/demuxer 清单「仅以统计常量登记」 | F 组把 100 + 75 符号与 9/15/19 查询矩阵、8 边界声明全量登记 | ✅ **已闭环** |
| `NmBufferSourceImp.onNetSourceDataDone()` 把 `kind==2` / `kind!=2` 双分支都简化为 `onFlushNetData()` | D 组按 §61.54 / §61.55 一手反汇编纠正为「两分支不对称 + 共享队列 + 级联完成链」，并同步修正 B-16 / B-17 测试断言 | ✅ **已闭环** |
| S-16 分片上界 `end/slice_size` 未逐指令坐实 | W 组 0x12898 `msub` + 0x128A0 `cinc` 坐实 `upper = end/size + (end%size!=0)`，循环为半开区间 `[qStart, upper)`；旧实现整除时多写一片已修正 | ✅ **已闭环（W-04 / W-06）** |

---

## 6. 仍未覆盖 / 需真机（如实登记，不写成已解决）

| 项 | 状态 | 依据 |
|----|------|------|
| F3 REMOTE/LOCAL 双路径真机矩阵（**B2 引擎**） | **已重跑：PASS 14 / FAIL 8 / NO_SAMPLE 3**（§61.87，serial `192.168.1.11:38407`，219s） | 整轮跑在 B2 Media3 内核上；FAIL 8 = wma / ape / wv / aiff 的 Extractor 缺口（decoder 已编入），零意外、零 A 路线回退。**这是另一条路线的真机结论**，不折算成本目录竞品 native 侧的覆盖 |
| F3 REMOTE/LOCAL 双路径真机矩阵（**竞品 native / A 路线**） | **NOT RUN** | §61.83 boundaries：导出符号存在 ≠ decoder 可用 ≠ 真机可播；本目录不把 B2 的 PASS 折算成 A 路线结论 |
| M2 阶段门禁（**B2 自研路线**） | **PASS，解锁 M3**（§61.87：PASS 14 ≥ A 路线基线 11；ogg Vorbis 软解实锤 `Loaded FfmpegAudioRenderer.`） | 属 B2 生产实现的里程碑；本目录**不实现 M2、不改 B2 阶段状态**。`NmFormatCatalog.VERDICT` / `BOUNDARIES` 里的 `M2_GATE_NOT_PASSED` 与 `NOT RUN` 是 **§61.83 源证据 verdict 的 1:1 登记**，源未变更故原样保留（历史口径 ≠ 当前状态） |
| M3（**B2 自研路线**） | **接线完成、门禁未通过**（§61.88） | 官方 `CacheDataSource` / `SimpleCache`（LRU 256MiB）接线 + `READY/PLAYING` 一手日志；60s 连续观测与缓存命中离线复播待补。该章明确「零 A 路线私有 vfile / commit / 代次门控移植」「F4 仍 BLOCKED（历史档案）」，属另一条路线，本目录不实现 M3 |
| F4 阻塞 | **仍 BLOCKED** | §61.70 / §61.75 真机终局；B2 M2 PASS 不涉及竞品 native 代次门控链，设备完全离线，需用户重开无线调试后按 runbook 复跑 |
| `§61.69` `EngineAdapter.kt` +20 行 `SetLoggerCallback(...,5)` | 建议回滚，**待用户拍板** | 对 W 级零增益、抑制 I/D、空类缺 `BIInfo` 有 JNI pending exception 风险 |
| `§50.7 #3` | 待真机复验 | 项目长期挂账 |
| S-16 分片上界 | ✅ 已由 W 组一手反汇编定案 | 见第 5 节 / LOGIC-SPEC §3.13 |

---

## 7. 防回潮机制

```
源 JSON / MD (只读：12 份一手源分布于 4 份 manifest；V 组 2 份 + W 组 1 份 + X 组 2 份档案受第 5/6/7 份 manifest 守护)
   ├─ tools/gen_catalog.py ────→ kt/src/NmJniCatalog.kt / NmFormatCatalog.kt (全量脱敏)
   │                        └─→ spec/catalog-manifest.json (sha256 + 条目数) [2 份源]
   ├─ tools/gen_done_chain_manifest.py
   │                        └─→ spec/done-chain-manifest.json (sha256 + 20 项常量) [2 份源]
   │                                     └→ 对应 kt/src/NmBufferSourceImp.kt 的 D 组 companion 常量
   ├─ tools/gen_read_gap_manifest.py
   │                        ├─→ kt/src/NmReadGapCatalog.kt (33 项常量 + 3 张清单，全量)
   │                        └─→ spec/read-gap-manifest.json (sha256 + 33 项常量) [1 份源]
   ├─ tools/gen_a3_meta_manifest.py
   │                        ├─→ kt/src/NmDisasmMetaCatalog.kt (7 份源 / 50 项 Int 常量)
   │                        └─→ spec/a3-meta-manifest.json (sha256 + 常量期望值) [7 份源]
   ├─ tools/scan_uncovered_evidence.py
   │                        ├─→ spec/uncovered-evidence-scan.json (158 份信号 / 24 份 highRisk)
   │                        └─→ spec/uncovered-evidence-adjudications.json (逐份裁定 + 名单指纹)
   ├─ W 组：tools/w_disasm_evidence.py (真实 so 全 .text 反汇编)
   │                        └─→ spec/w-disasm-evidence.json (8 窗口 / 238 指令 / 28 锚点)
   │        tools/gen_w_manifest.py
   │                        ├─→ kt/src/NmWDisasmCatalog.kt (21 项 Int + 2 项 String)
   │                        └─→ spec/w-disasm-manifest.json (sha256 + 常量期望值) [1 份证据]
   └─ V 组：tools/scan_chapter_matrix.py + tools/scan_lowscore_asm.py
                            ├─→ spec/chapter-matrix-scan.json (84 章矩阵 / unmapped=0)
                            ├─→ spec/lowscore-asm-scan.json (134 份低分证据 / 纯汇编候选 0)
                            └─→ tools/gen_chapter_matrix_manifest.py
                                  ├─→ kt/src/NmChapterMatrixCatalog.kt (39 项常量)
                                  └─→ spec/chapter-matrix-manifest.json (sha256 + 常量期望值) [2 份快照]
             │
             ├─ tools/build.py  → 分趟编译 + 契约测试(712) → build/test-report.json
             └─ tools/audit.py  → 26 项检查            → build/audit-report.json
                  ⑥ 清单源 JSON sha256 == catalog-manifest
                  ⑦ Kotlin *_COUNT == catalog-manifest
                  ⑧ D 组源 JSON sha256 == done-chain-manifest
                  ⑨ Kotlin D 组常量 == done-chain-manifest
                  ⑩ R 组源 JSON sha256 == read-gap-manifest
                  ⑪ Kotlin R 组常量 == read-gap-manifest
                  ⑫ highRisk 名单指纹 + 逐份裁定 == adjudications
                  ⑬ U 组 7 份源 sha256 == a3-meta-manifest
                  ⑭ Kotlin U 组常量 == a3-meta-manifest
                  ⑮ V 组 2 份扫描快照 sha256 == chapter-matrix-manifest
                  ⑯ Kotlin V 组 39 项常量 == chapter-matrix-manifest
                  ⑰ W 组反汇编证据 sha256 == w-disasm-manifest（含锚点 all_pass + so 指纹）
                  ⑱ Kotlin W 组 23 项常量 == w-disasm-manifest
                  ⑲ X 组函数全集档案 sha256 == x-manifest（含 unhandled=0）
                  ⑳ Kotlin X 组 8 项常量 == x-manifest
                  ㉑ Z 组 4 个 so sha256 == z-manifest
                  ㉒ 逐行守恒 i(...) 行数 == 388064；块函数 == 6326；包装 == 7486；分派 == 6326
                  ㉓ Kotlin Z 组常量 == z-manifest（含 .text 字节账闭合）
                  ㉔ 语义零残留：when(op) 无分支 = 0 且 z-audit dropout = 0 / vectorLeak = 0
```

任何上游证据变更而未重新生成、或任何手改 catalog / D 组常量导致漂移，都会让 `audit.py` fail。
这正是项目「工具脚本 → JSON → 报告 → 审计脚本 → 长效守护」纪律的落地。

---

## 8. `docs/12 §61.x` 章节处置矩阵（全章节自证）

> 目的：对 `docs/12-编码阶段第一批交付.md` 的 `§61.x` 全章节逐条给出「已翻译 / 全量登记 /
> 明确不翻译 / 真机待复跑 / 另一条路线」的处置理由，不留隐式空白。
> 章节标题由 `^#{1,4} 61\.[0-9]+ ` 全量枚举核对：**截至本批（docs/12 最新 §61.88）
> 编号 1~88 中实际有标题 84 个**，另 4 个编号（`§61.19` / `§61.23` / `§61.24` / `§61.84`）
> 在文档中无对应标题，非遗漏。
> 处置统计（截至本批）：A 类逻辑翻译 9 章、B 类全量登记 1 章、C 类明确不翻译 74 章、无标题 4 个，合计 88。
> V 组用 `tools/scan_chapter_matrix.py` 把该矩阵做成机器可复现自证：动态切分全章节、交叉 L1 信号、
> 断言 `unmapped=0` 与「C 类零指令级反汇编」，**不写死总章节数** —— 并行工作流新增章节
> 若未先补登记，扫描器立即 fail。上一版表格只登记 72 个编号，漏了 `§61.5~§61.18`（14 章）
> 与新增的 `§61.87` / `§61.88`，本批共补登 16 章；C 类 74 章带地址前缀的指令行数为 0，
> 唯一含指令级反汇编的 `§61.54` / `§61.56` 已判 A 类并翻译（D 组 / P 组）。
> 判据说明：C 类里 §61.68 / §61.69 / §61.71 的 U 组 L1 分数偏高，是**引用已登记反汇编结论**的
> 真机观测 / 工具 / 口径文档，不是一手指令级证据，故「藏反汇编」判据改用
> 「带地址前缀的指令行数 / 连续助记词块 / asm fence」，不直接用 L1 分数。
> 明细见 `spec/chapter-matrix-scan.json`，由 `audit.py` 第 15 项守护。

| 章节 | 主题 | 处置 | 理由 / 对应组 |
|------|------|------|---------------|
| §61.1~§61.4、§61.20~§61.22、§61.25~§61.49 | 真机 E11~E23 播放验证、G01~G09 验收、T-L 蓝牙联动、F1~F3 格式真相源与真机矩阵 | **C** | 生产工程真机验证 / Java-Kotlin 侧实现 / 项目自有格式表，不是竞品 native 内核反汇编逻辑；改动会破坏 docs/12 计数与 C2 守护 |
| §61.5~§61.18 | AVIO 虚表布局假设 / E12~E23 真机实验 / Java 侧 nativeEvents 修复 / 首包 EOF 定论 | **C** | V 组补登的 14 章（上一版表格漏登）。其中 §61.5 含 `read_cb 0x55970` / `seek_cb 0x55a30` 反汇编与 AVIO 虚表槽 `+0x20/+0x28/+0x50` 布局错位假设，但**自标「未定论」**：§61.12 把根因改判为 `CNCMFFDecoder` / FFmpeg demuxer 内部行为并明确「静态分析已到边界，需运行时调试」，§61.13 又推翻「文件太短」假说 —— 按「只有反汇编能证明的控制流才进 A 类」判据不翻译；§61.8 的 `0x9b4c → 0x13070 → slot+0x40` Close 链是 vtable 槽位调用序列（无新门控），主体为项目自身 Java 侧修复，亦不翻译。其余 12 章是纯真机 E 系列实验记录，L1 汇编信号为 0。锚点如实登记在 `spec/chapter-matrix-scan.json` |
| §61.87 | ExoPlayer 单线程门面化 + F3 22 格 B2 引擎真机重跑 + M2 门禁 PASS | **C** | 并行工作流新增（V 组补登）。B2 **另一条路线**的生产实现：`onMain` / `onMainSync` 主线程门面化、判据正则补丁、F3 22 格 PASS 14 / FAIL 8、M2 PASS 解锁 M3；非竞品 native 反汇编逻辑，本目录不实现 M2。其 F3 / M2 事实口径已同步到第 6 节，`F4 仍 BLOCKED` 不变 |
| §61.88 | B2 M3 缓存/Range 接线（官方 `CacheDataSource` + `SimpleCache`） | **C** | 并行工作流新增（V 组补登）。B2 另一条路线的生产实现：官方 DataSource 语义、LRU 256MiB、`READY/PLAYING` 一手日志；该章明确「零 A 路线私有 vfile / commit / 代次门控移植」「M3 接线 ≠ M3 门禁通过」「F4 仍 BLOCKED（历史档案）」。非竞品 native 反汇编逻辑，本目录不实现 M3，口径同步到第 6 节 |
| §61.50~§61.53 | F4 根因实证、A/B 真机实验、kind 槽高置信假设、A/B 可复跑脚本 | **C** | 真机现象 / 假设 / 脚本，非静态可证明的控制流；`F4 仍 BLOCKED` 口径由 `F4-CHAIN-REPORT.md` 承接，不写成已解决 |
| §61.54、§61.55 | F4 native 分支语义一手二进制复核、kind 槽两个 task 行为差异 | **A → D 组** | `OnNetSourceDataDone` 双分支 + 共享 `0x16468` 队列 + 级联完成链 + `OnFlushNetData` 22 指令四道门控，逐指令坐实并 1:1 翻译（12 条 `@spec`） |
| §61.56 | JNI 参数槽透传复核、因果链拆为两环 | **A → P 组** | `(x0,x1)=(native_ptr,j2)` 参数槽无变换，P-01~P-05 已翻译；C++ 目标类型属 L3 边界声明，不虚构 |
| §61.57~§61.64 | A/B 编译期预检、上线自检、kind 槽真机裁决、第二卡点样本 / vfile commit 时序 / 变体 D | **C** | 脚本 / 真机观测 / 现象结论，非静态控制逻辑；§61.61「vfile 连续提交点 ↔ fMP4 跳跃读」结论被 F4 链路报告引用为因果背景 |
| §61.65 | F4 native 落盘 / commit 链路反汇编定位 | **A → N/S/B 组** | 代次闸门、`commit=offset+size` 记账、四道静默落盘门控、`-103` 错误链，逐指令坐实 |
| §61.66 | 仓库同步（提交 + 推送 `origin/main`） | **C** | 仓库操作，非内核逻辑 |
| §61.67 | F4 请求代次闸门与 vfile 落盘门控离线反汇编定位 | **A → N/S 组** | 代次递增 `0x104B8`、旧包整包丢弃 `0x10798`、slice 门控逐指令坐实 |
| §61.68、§61.71~§61.73 | 真机代次实证、W 分支判读工具、上线执行链口径迁移、三轮真机 `g_level=0` 实证 | **C** | 真机观测 / 工具 / 口径文档；`g_level` 默认 0、日志默认全开的语义已由 G 组翻译，不新增可翻译逻辑 |
| §61.69 | F4 level-5 诊断接线落地与 P0 编译产物 | **C**（部分 A → G 组） | `EngineAdapter.kt` +20 行 `SetLoggerCallback(...,5)` 是生产工程接线，建议回滚、**待用户拍板**；其门控语义由 G 组翻译，本目录不改生产代码 |
| §61.70 | F4 level-5 日志门控语义纠正 | **A → G 组** | getter `g_level > N` 抑制、`.bss` 零初值默认全开、早退副作用 L3 边界，逐指令坐实 |
| §61.74 | `Write start play` 日志门控反汇编与判据口径修正 | **A → A 组** | AudioTrack 四道静默门控 + retry / callback 逐指令坐实，真机 `Write start play` 命中 0 次 |
| §61.75 | 真机验收终局裁决与「设备上线」口径纠错 | **C** | 真机裁决（F4 仍 BLOCKED）；结论被 F4 链路报告承接，不新增可翻译逻辑 |
| §61.76 | `-103` 产生链与播放侧状态机定位 | **A → B 组** | `Finish Async` 原样返回错误码、挂起异步读 `[+0x198]` 门控、两条硬编码分支 |
| §61.77 | 读侧空白链补齐（`result:-2` 与挂起异步读） | **A → B 组** | `WaitRequest` 返回值 `0/1/2/其它 → 0/-2/-106/-103` 映射与日志链 |
| §61.78、§61.79 | A1「绕过取流」可行性论证 / 时间盒真机裁决 + §61.69 接线回滚 | **C** | 另一条路线 / 真机裁决；不改 B2 阶段状态，不作为竞品内核逻辑 |
| §61.80~§61.82 | B2 路线切换、M1 播放内核骨架接入、M2 前置取证 | **C** | **另一条路线**：B2 生产实现，本目录不实现 M2、不改阶段状态、不宣称 F4 已解决 |
| §61.83 | `libffmpeg.so` decoder/demuxer 导出符号表实证 | **B → F 组** | 100 decoder / 75 demuxer + 9 unconfirmed / 15 candidate / 19 codec queries / 8 边界声明全量登记；数据清单，非控制逻辑 |
| §61.85 | B2 M2 落地：FFmpeg 软解扩展交叉编译与接线 | **C** | 另一条路线 B2 生产实现，非竞品内核逻辑翻译 |
| §61.86 | F3 双引擎判据与 B2 诊断日志（设备重新上线） | **C** | 另一条路线 B2 的判据 / 诊断日志 / 真机脚本改动（`CM-Media3` 指纹、`l3_f3_matrix_run.py` 双判据），非竞品内核反汇编逻辑；该章记录「F3 22 格仍 NOT RUN」「F4 仍 BLOCKED」，其中 F3 状态已被 §61.87 的 B2 引擎重跑更新（见第 6 节），`F4 仍 BLOCKED` 不变 |
| §61.19 / §61.23 / §61.24 / §61.84 | 文档无对应章节标题 | **—** | 编号在 `docs/12` 中不存在（已用标题枚举核对），非遗漏 |

> 矩阵口径：**只有「反汇编能证明的控制流 / 数据流 / 门控 / 错误码 / 日志语义」才进 A 类**；
> 真机现象、假设、脚本、生产工程实现、另一条路线一律进 C 类并写明理由；
> 清单类证据进 B 类且必须全量。这与 `LOGIC-SPEC.md` 第 1.3 节覆盖边界、`COVERAGE.md` 第 1 节判据一致。

---

## 9. `analysis/` 一手证据文件全集处置矩阵（178 份）

> 目的：对 `analysis/` 根目录全部 **178 份 `*.json` / `*.md`** 逐组给出处置理由，
> 与第 8 节的「章节矩阵」互补 —— 章节矩阵管 `docs/12` 的结论总账，本节管一手证据文件全集。
> 枚举口径：`analysis/*.json` + `analysis/*.md`（根目录）；不含 `analysis/apk/`、`analysis/decoded/`、
> `analysis/l3-f4-backup/`（已 gitignore）、`analysis/l3-f3-logs/` 等子目录里的解包资源、
> 真机日志归档与 app 私有数据备份 —— 它们是证据来源 / 二进制素材，不是逻辑翻译对象。

| 组（文件名前缀） | 份数 | 内容 | 处置 | 理由 |
|---|---|---|---|---|
| `a3-*` | 6 | A3 真机验收、native 突破、阶段 C 反汇编、A/B 实证、验证 runbook | **A（2 份）+ C（4 份）** | `a3-stage-c-analysis` / `a3-phase-ab-evidence` 是对竞品 so 的 capstone 指令级反汇编，坐实此前未翻译的控制流（U-01~U-07），本批由 C 类改列 A 类锚点；其余 4 份是项目自身 A3 阶段挂账 / runbook，非竞品内核逻辑 |
| `c4-*` | 1 | C4 资产交付 | **C** | 资产 / 合规清单，无控制流 |
| `g08-*` / `g09-*` | 3 | G08 20 分钟长稳、G09 恢复链路实证 | **C** | 项目自身长稳 / 恢复链路真机证据，非内核逻辑 |
| `l2-*` / `dlna-*` / `tl-*` | 12 | L2 断连、DLNA gap 计划、T-L L3 素材 / 布局 / 交互复刻 | **C** | 项目自身功能、素材与视觉交互复刻证据，属 `docs/12` 另一条路线；改动会破坏计数与 C2 守护 |
| `engine-*` / `jni-*` | 4 | `engine-api-audit.json`、`engine-jni-classes.json`、`jni-binding-report.json`、`jni-verify-report.json` | **B（1 份）+ C（3 份）** | `engine-jni-classes.json` 已由 C 组全量登记到 `NmJniCatalog.kt` 并受 `catalog-manifest.json` 的 `sha256` 守护；其余 3 份是 JNI 绑定审计元数据，无控制流，按 `NmJniBridge.Inventory` 统计常量口径登记 |
| `l3-audio-*` / `l3-f1-*` / `l3-f2-*` / `l3-f3-*` | 12 | F1 单一格式真相源、F2 零权限注入 / 真机复验、F3 双路径矩阵与 fixtures | **C** | F1~F3 是项目自研格式表 / 真机矩阵 / 样本，非竞品内核反汇编逻辑；F3 真机矩阵仍 **NOT RUN** |
| `l3-b2-*` | 12 | B2 路线切换、M1 骨架、M2 前置补证、`libffmpeg.so` decoder 符号表 | **B（1 份）+ C（11 份）** | `l3-b2-m2-decoder-symbols.json` 已由 F 组全量登记到 `NmFormatCatalog.kt` 并受指纹守护；其余是 B2 **另一条路线**的施工单 / 审计报告，不实现 M2、不改阶段状态 |
| `l3-baseline-*` / `l3-full-*` / `l3-interaction-*` / `l3-module-*` / `l3-playback-*` / `l3-visual-*` | 6 | 基线探针、全量复刻审计、交互 / 视觉规格、模块清单、播放路线评估 | **C** | 模块清单 / 视觉交互规格 / 布局复刻，属数据清单或项目自身规格，非内核控制逻辑；`l3-module-inventory.json` 的模块统计由 `NmJniBridge.Inventory` 常量口径覆盖 |
| `l3-f4-*` | 107 | F4 根因、A/B、真机观测、反汇编、level-5、vfile、W 分支、task-diff、read-gap 全链条 | **A（18 份）+ B（5 份）+ C（84 份）** | 18 份是 `LOGIC-SPEC.md` 第 2 节 A 类逻辑锚点；5 份机读反汇编 JSON（minus103 / play-gate-disasm / level5-semantics-audit / loglevel-probe / jni-arg）含 `-report.md` 未覆盖的独有全量数据，本批由 C 类改列 B 类数据源并受 `a3-meta-manifest.json` 守护；其余 84 份是真机 A/B 日志、自洽性探针、中间裁决与上线自检，属现象 / 过程记录 |
| `legacy-*` / `player-*` / `rotation-*` / `p3-*` / `spike*` / `review-*` | 10 | 布局解析、播放器动画 / 资源闭包、spike 探针、诊断复核 | **C** | UI / 资源 / 布局 / 项目自身诊断，非播放内核逻辑 |
| `t1-*` / `t3-*` / `t4-*` / `task-backlog-*` / `data-request-*` | 5 | T1/T3/T4 交付、任务 backlog、`DataRequestHandler` 证据 | **C** | 项目自身交付记录 / 任务账；`data-request-handler-evidence` 属 Java 侧数据请求证据，非 native 内核反汇编 |

> 统计：**178 = 已登记证据源 27 + 明确不翻译 151**。
> 27 份 = A 类逻辑锚点 20 份 + B 类全量清单 / 元数据源 7 份，明细见 `LOGIC-SPEC.md` 第 2 节；
> 其中 **12 份一手源受 `sha256` 指纹守护**（10 份 JSON + 2 份 A3 Markdown），分布于 4 份 manifest；另有 V 组 2 份 + W 组 1 份 + X 组 2 份档案受第 5/6/7 份 manifest 守护。明细：
> `catalog-manifest.json`（2）、`done-chain-manifest.json`（2）、`read-gap-manifest.json`（1）
> 与 `a3-meta-manifest.json`（7，含 2 份 A3 md 与 5 份机读 JSON）。

> 口径声明：判据与第 1 节一致 —— 只有「反汇编能证明的控制流 / 数据流 / 门控 / 错误码 / 日志语义」
> 进 A 类；清单类进 B 类且必须全量；真机 A/B 日志、自洽性探针、中间裁决、上线自检、
> 项目自身工程证据、视觉 / 交互 / 资产复刻一律进 C 类并写明理由。
> 特别说明：同名 `-report.md` 已登记时，其机读 `.json` / `-audit.json` 视为**同源产物按同一处置**，
> 不重复登记、也不宣称逐条翻译过它们（防「静态推断被写成已核对」）。
> **例外**：本批 `l3-f4-read-gap-chain.json` 因被 `tools/gen_read_gap_manifest.py` 直接引用为 generator 输入，
> 单独列为第 18 份 A 类锚点并受 `sha256` 守护，不再与 `-report.md` 合并计数（R-08）。
> **例外 2（U 组）**：`tools/scan_uncovered_evidence.py` 再清点后，5 份机读反汇编 JSON
> 因含 `-report.md` 未覆盖的独有全量数据，改列 B 类数据源并由 `gen_a3_meta_manifest.py` 守护；
> 2 份 A3 md 因坐实未翻译控制流改列 A 类锚点；不再一律按「同源产物不重复登记」处理（U-08 / U-10）。
> 本节不改变 `F4 仍 BLOCKED` 的口径。`M2_GATE_NOT_PASSED` / `F3 真机矩阵 NOT RUN` 是
> `NmFormatCatalog.VERDICT` / `BOUNDARIES`（§61.83 源证据）**历史 verdict 的 1:1 登记**，
> 源未变更故原样保留；B2 路线当前状态以第 6 节为准（§61.87：F3 22 格已重跑、M2 门禁 PASS；
> §61.88：M3 接线完成、门禁未通过）。
> 两者分属不同路线与不同时间点，不互相覆盖、不把静态推断写成已修复。
