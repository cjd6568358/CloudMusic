# F4 · `OnNetSourceDataDone` kind 分支的一手二进制复核（12 §61.54）

> **2026-11-09 第八十一批** · 纯离线 / 只读 / 零生产代码改动 / 零设备操作。
> 承接 `analysis/l3-f4-kind-slot-rootcause.md`（`12 §61.52`）的高置信假设：
> Java 侧 `UrlDataRequest.sink.onEnd()` 把 `requestId` 传进 `OnDataRequestEnd`
> 首参，而该首参在 native 是 `OnNetSourceDataDone(int kind, ...)` 的 **kind 槽**。
> §61.52 当时只**引用** `docs/12 §59.2` 的文字记录；本批直接反汇编
> `libNMCommonCache.so` 并解析 `.rela.dyn` 的 GOT 重定位，把该前提升级为
> **一手二进制证据**。

---

## 1. 为什么要做这一步

设备已离线，kind 槽 A/B（`tools/l3_f4_kind_slot_ab.py --apply`）无法执行。
在等待设备的窗口里，能把置信度往前推的唯一方向是：**把假设的 native 侧前提
从「文档里的转述」变成「so 里的字节」**。

必须强调边界：本次复核的是 `OnNetSourceDataDone` **收到不同 kind 时做什么**，
不是 `UrlDataRequest` **传了什么**、更不是「传 requestId 导致 fMP4 卡点」的因果。
前者被证实了，后者**仍是假设**，判据不变（设备上线做一次 A/B）。

## 2. 方法与工具链

| 项 | 值 |
|---|---|
| 目标 so | `engine-legacy/src/main/jniLibs/arm64-v8a/libNMCommonCache.so` |
| 目标符号 | `_ZN17NMBufferSourceImp19OnNetSourceDataDoneEi` @ `0xbee8`，size `384` |
| 工具 | `tools/l3_f4_disasm_verify.py`（capstone + pyelftools，本机既有，零新增依赖） |
| 只读证据 | `analysis/l3-f4-disasm-verify.json`（10 项 checks 全 PASS） |
| 交叉参照 | `analysis/route1_disasm.py`（§59 当时的反汇编器） |

**注意**：发布 so 已被 strip，**没有 `.symtab`**，只有 256 个 `.dynsym` 动态符号；
`.text` 的 vaddr 与文件 offset 相同（均为 `0x8ed0`），故可直接按 vaddr 切片反汇编。
`tools/l3_f4_disasm_verify.py` 首版硬依赖 `.symtab` 会抛
`AttributeError: 'NoneType' object has no attribute 'iter_symbols'`，本批已修为
`.symtab or .dynsym` 兜底。

## 3. 一手指令证据（`0xbee8..0xc068`）

```
0xbf08  cmp        w1, #2              ; 首参 w1 = kind，与 2 比较
0xbf14  b.ne       #0xbfa0             ; kind!=2 → 另一分支
...
0xbf58  adrp       x9, #0x4b000
0xbf5c  ldr        x9, [x9, #0xed0]    ; kind==2：取 GOT 0x4bed0
0xbf6c  mov        v0.d[0], x9         ; 作为 task 函数指针写入任务对象
0xbf7c  bl         #0x16468            ; 投递队列（分支 1）
...
0xbfa0  cbz        x19, #0xc040        ; kind!=2 入口
0xbfe0  adrp       x9, #0x4b000
0xbfe4  ldr        x9, [x9, #0xee8]    ; 取 GOT 0x4bee8
0xbff4  mov        v0.d[0], x9
0xc004  bl         #0x16468            ; 投递同一队列（分支 2）
...
0xc034  blr        x8                  ; 队列取出后虚调用执行 task
```

两个分支构造的任务对象结构完全一致（同样 `0x60` 字节、同样 vtable 槽
`0x4ad18 / 0x4ad98 / 0x4ad50`），**唯一差别是 GOT 槽取出的函数指针**，且都投递
同一个 `0x16468` 队列 —— 这排除了「两个分支只是不同的日志/统计路径」的可能。

## 4. GOT 重定位（`.rela.dyn`，`R_AARCH64_GLOB_DAT` = type 1025）

| GOT 槽 | 重定位符号 | 符号地址 | 触发条件 |
|---|---|---|---|
| `0x4bed0` | `_ZN17NMBufferSourceImp28PreloadDoneTaskOnWriteThreadEv` | `0xb86c` | `kind == 2` |
| `0x4bee8` | `_ZN17NMBufferSourceImp14OnFlushNetDataEv` | `0xc068` | `kind != 2` |

两条都是 `GLOB_DAT`、addend 为 0、符号直接来自 `.dynsym`，**不是需要二次推算的
`RELATIVE` 重定位**，因此函数名映射是确定的、无歧义的。

## 5. 结论（分清「已证实」与「仍待验证」）

**已证实（本批一手二进制证据）**：
`docs/12 §59.2` 记录的分支语义成立 —— `OnNetSourceDataDone` 的首参 `int kind`
与 `#2` 比较，`kind==2` 投递 `PreloadDoneTaskOnWriteThread`（写线程的「预加载完成」
通知），其余投递 `OnFlushNetData`（刷数据）。这使 §61.52 假设中
「**`OnDataRequestEnd` 首参是 kind 槽，不是 requestId 槽**」这一 native 前提
从文档转述升级为二进制事实。

**仍待验证（假设，不是结论）**：
- Java 侧 `UrlDataRequest.kt:96` 至今传 `OnDataRequestEnd(requestId, ...)`，
  但「`requestId == 2` 被 native 误判为 `kind==2`、导致 fMP4 REMOTE 提前
  `PreloadDone` → 重发同区间 → 循环无 codec」这条**因果链没有真机证据**。
- `docs/12 §59.2` 的 **exp6 是已知反例**：两条路径读侧均正常收尾。
- **竞品同样传 requestId 却可用**，原因未实证（可能有上层保护/不同调用时序）。
- 该分支语义**不能解释** `ape×remote`（`onEnd=[1]`）与 `aiff`（`openSource 2`）。

因此：**F4 仍判 BLOCKED**。本批只是把「为什么值得做 A/B」的理由变硬了，
没有、也不能替代 A/B。

## 6. 解除条件（不变）

设备重新上线后给新端点，一条命令裁决：

```
python tools/l3_f4_kind_slot_ab.py --serial <ip:port> --apply
```

可证伪预测：首参恒传 `NATIVE_DONE_KIND_FLUSH = 1L` 后，
`f3_m4a_fast.m4a × REMOTE` 应出现 `Open codec for aac` +
`Write start play nErr:0`；若仍 `Start id=3 offset=327895` 重复且无 codec，
则 §61.52 假设证伪，需另找机理。脚本 `try/finally` 无条件恢复 P0 基线。
A/B 通过后仍需 F3 14 格全量复跑 + 自动进曲 3 首（无 `-103`、无释放后回调），
后者如实 `NOT RUN`。

## 7. 诚实边界

- 本批**零生产代码改动**：`UrlDataRequest.kt` 的 `git diff` 为空，
  `assets/ENGINE-FINGERPRINT` 不回填，so 打包集零变化，舱不冻结。
- **零设备操作**：不连 adb、不连 OpenList、不写凭据、不改设备系统设置。
- 不写「根因已证实」「F4 已解决」「m4a 已支持」；两个 m4a 样本仍是人工截取 /
  重排的 fMP4 片段（含视频轨），F3 矩阵 14 格未被改写。
- A3 仍 **G09 PARTIAL**；现行长稳窗口 20 分钟。`§61.38`~`§61.54` 十七批
  改动均未提交、未 push，`HEAD` 仍 `8c9d79b1`。

> AI生成
