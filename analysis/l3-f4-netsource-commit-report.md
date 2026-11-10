# F4 · `libNMCommonCache.so` 落盘 / commit 链路反汇编结论（`12 §61.65`）

> **2026-11-10 第九十二批** · 承接 §61.64 变体 D 的有效负结果与「下一步」：
> 用 `capstone 5.0.7 + pyelftools` 反汇编 `libNMCommonCache.so`，回答
> **「Java 侧 1 MiB 回传数据为何 `commit` 只有 16384 ~ 180224 B」**。
> **离线、只读、不需要设备在场**；零 P0 源码改动、零设备操作。
> 机器证据：`analysis/l3-f4-netsource-commit-disasm.json`（**15 项 checks 全 PASS**，
> 21 个反汇编锚点 / 11 条 .rodata 字符串 / PLT 符号还原 / 真机日志交叉）。
> 可复跑：`python tools/l3_f4_netsource_commit_disasm.py`。

---

## 1. 结论先行

**瓶颈不在 Java 取流，也不在「落盘写不进去」，而在 `NMNetSource` 的
请求代次校验 + commit 记账链**：读侧在 moov 尾部读空后立刻重发新 Range 请求、
递增请求代次；旧代次仍在途的数据包在 `NMNetSource::OnData` 入口
`0x10798 b.ne 0x108E0` **被整包丢弃**，既不回调、不更新 `commit`、
也不唤醒读侧。于是 `commit` 永久停在首次唤醒前已通过代次校验的少量包
（变体 D `kind=2`：16384 = 2×8192；基线 `kind=1`：180224 = 22×8192），
而 Java 侧其实已经把整段 1 MiB 全部回传。

`kind=2` 更差的原因也有了二进制解释：`kind=1` 在 `OnNetSourceData` 里
**额外**写 `NMMemoryCache`（内存镜像），读侧可从内存拿到部分数据、重发更晚，
同代次内能 commit 更多包；`kind=2` 完全依赖易失效的 commit 唤醒链。

> **置信度分层**：代次校验早退、commit 写入点、kind 分支、SKIP/TRIM 均为
> **二进制坐实**；「读侧重发导致代次失效」是**与真机日志交叉后的推断**
> （`inference`），未做新的真机 A/B 直接触发该分支。

## 2. 方法

- `pyelftools` 读 `.dynsym`（256 项，发布 so 已 strip、无 `.symtab`）、
  `.rela.plt` / `.plt`（121 个 stub 还原为导入符号）、`.text`
  （`0x8ED0` 起、`0x3271C` 字节，`vaddr == file offset`）；
- `capstone` 全 `.text` 反汇编，按地址钉死 21 个指令锚点；
  ADRP+ADD 立即数还原 11 条 `.rodata` 日志字符串；
- 与 §61.64 已落盘真机 logcat
  `analysis/l3-f4-logs/vfile-commit-20261110-130021.txt` 交叉对账。

## 3. 二进制证据

### 3.1 `OnNetSourceData` @`0xBA44`（kind 槽的真实语义）

```
0xBAAC  cmp   w23, #2
0xBAB0  b.eq  0xBB34          ; kind==2 → 跳过内存缓存写
0xBAE8  bl    0xFE48          ; kind!=2 → NMMemoryCache::Write
0xBB34  ... 构造 0x28/0x60/0x40 字节任务对象 ...
0xBC10  bl    0x16468         ; 两种 kind 都投递到同一任务队列
```

→ **§59 注释「kind==2 跳过内存缓存写、只投递 OnWriteNetData」得到精确化**：
不是「只有 kind=2 才投递」，而是 **kind=1 = 内存缓存写 + 投递，
kind=2 = 只投递**。两者都走 vfile 写任务，但 kind=1 多一条内存镜像。

### 3.2 `OnWriteNetData` @`0xBCAC`（vfile 写的分支）

| 条件 | 分支 | 日志字符串（`.rodata`） |
|---|---|---|
| `[this+0xB8] == 0` | 直接返回不写 | `OnWriteNetData Failed Not Support.` @`0x3BB33` |
| buffer 无效 | 直接返回 | `Failed Data Invalid.` @`0x3BB69` |
| `offset+len <= [this+0xA8]` | **SKIP**（整包跳过） | `OnWriteNetData SKIP offset:%lld size:%lld file_offset_:%lld` @`0x3BBA0` |
| `offset < file_offset_ < end` | **TRIM**（只写尾部） | `OnWriteNetData TRIM ... skip:%lld` @`0x3BBF5` |
| 写失败 | `0xBE80 strb wzr,[x19,#0xB8]` **永久清零标志** | `Write Data Failed:%d.` @`0x3BC82` |
| 写成功 | `0xBED4 bl 0x8940` | PLT 还原为 `NMBufferSourceImp::NotifyCacheProgress(long)` |

`[this+0xA8]` 由唯一具名函数 `SetFileOffset` @`0xB798` 写入，配合 SKIP
日志的 `file_offset_:%lld` 可确认它就是 vfile 连续写水位。
`[+0xB8]` 还会被 `ReadFromFileNoLock` @`0xC4B8` 清零。

### 3.3 `NMNetSource::OnData` @`0x10748`（**commit 记账与硬早退**）

```
0x10790  ldr   x8, [x20, #0xC0]      ; 当前请求代次
0x10794  cmp   x8, x24               ; 与回调 arg1 比对
0x10798  b.ne  0x108E0               ; ★ 不匹配 → 直接返回（整包丢弃）
0x107F4  ldr   x8, [x20, #0x30]      ; 读 commit
0x107F8  cmp   x8, x22
0x107FC  b.eq  0x10840
0x10800  ... level-5 日志 "OnData() UnExpect New Offsetcommted:..." @0x3CCC9
0x10840  ldr   x0, [x20, #0x190]     ; 数据回调对象
0x1086C  blr   x8                    ; vtable+0x18：把数据交给上层
0x10884  adds  x8, x21, x22          ; offset + length
0x10888  str   x8, [x20, #0x30]      ; ★ 写 commit
0x10890  ... 与 [+0x40] 阈值比较 ...
0x108D0  str   w8, [x20, #0xB8]      ; 越过阈值 → 置唤醒标志
0x108DC  bl    0x8AB0                ; 条件变量 notify
```

`0x10798` 是该函数**唯一**的硬早退；`0x107F8` 的 offset 不连续只打
level-5 日志、仍继续更新 commit。日志
`NMNetSource(%lld-%lld)::WaitRequest ...` 的两个数字来自
`[+0x18]` 与 `[+0xC0]`（见 `0x101E0/0x101E4`），真机里从 `(1-0)`
递增到 `(1-8)`，坐实 `[+0xC0]` 就是请求代次。

`WaitRequest` @`0x10198` 在读侧读空时经 `MakeRangeRequestAsync`
（字符串 @`0x3CC1A`）发起新请求；`0x10320 ldr x8,[x19,#0x30]`
即日志 `commit : % lld` 的取值。

## 4. 真机日志交叉（§61.64 变体 D 主判据，一手统计）

| 项 | 实测 |
|---|---|
| `onData` 行数 | **1028**（绝大多数 8192 B，末包可拆成 215+7977） |
| `Start` 次数 / 长度 | **9 次，每次 `length=1048576`** |
| `commit :` | **恒 16384**（2 条 WaitRequest 行） |
| `ReadFromFileNoLock ... result:-2` | 12 次（含 `offset:327895` 的 ×5 跳跃读） |
| 请求代次 | `(1-0),(1-1),(1-2),(1-3),(1-4),(1-5),(1-7),(1-8)` |
| `onEnd ... delayed 2s` | 8 次 |

→ **Java 侧 1 MiB 完整回传，不是取流被提前取消**（`RangeFetcher.fetch`
没有在 `cancelled` 处提前 break）；`commit` 停在 2 个包完全是 native 侧行为。
§61.64 正文写「8 次 Start」为当时窗口口径，此处按原始日志如实登记为 9 次，
**历史文字不改写**。

## 5. 对「1 MiB 只 commit 16384~180224 B」的完整解释

1. Java 每个 8192 B 包经 `OnDataRequestData` → `OnNetSourceData`，
   `kind` 决定是否写内存镜像，随后投递写线程任务；
2. 读侧 `ReadFromFileNoLock` 要读 `offset:0 / 32768`，但 fMP4 的 demuxer
   先行跳到 `offset:327895` 找 moov，`result:-2` 读空；
3. `WaitRequest` 以 `signal_size=8192` 等待，少量包通过后唤醒读侧，
   读空即 `MakeRangeRequestAsync` 递增请求代次 `(1-N)`；
4. 旧代次仍在途的 `OnData` 包在 `0x10798 b.ne` **整包丢弃**，
   `commit` 不再推进（`0x10888` 不执行）、读侧不再被唤醒；
5. 反复重发 9 次、每次 `onEnd` 延迟 2s，最终烧完重试预算 → `-103`；
6. `kind=1` 因内存镜像让读侧较晚读空 / 重发较少，同代次内多 commit
   到 180224；`kind=2` 无镜像，只剩 16384。

**精度修正（重要）**：`commit` 是 `NMNetSource` 的**连续确认偏移**，
**不是 vfile 的磁盘写入字节数**；§61.62 / §61.64 曾把 `commit` 当作
落盘量代理，本批据 `0x10888` 与 `WaitRequest` 日志给出该修正。
vfile 实际落盘由 `OnWriteNetData` → `NMSlicesFile::Write` 独立执行
（字符串 `NMSlicesFile Write buffer len:%u, offset:%lld, file_len:%lld`
@`0x3CF90`、`Write all finish` @`0x3D0AE`）。

## 6. 对 F4 的意义与诚实边界

- **根因方向已从「Java kind 值错配」推进到 native 请求代次 / commit 唤醒链**
  （`0x10798` + `0x10888` + `WaitRequest` 重发），并给出了可证伪的具体位置。
- **F4 仍判 BLOCKED**：根因方向定位 ≠ 修复 ≠ 验证。`-103` 判据未通过；
  自动进曲 3 首（无 `-103`、无释放后回调）/ seek / ENDED 仍 **NOT RUN**；
  F3 14 格矩阵未改写；A3 仍 **G09 PARTIAL**、舱不冻结、
  `engine_compat_test` 不回填。**不写「F4 已解决 / m4a 已支持 / 全格式已支持」**。
- **未验证部分**：本轮是静态反汇编 + 已有日志的交叉推断，
  没有新的真机 A/B 直接命中 `0x10798`；level-5 日志
  （SKIP / TRIM / Failed Not Support）在现有 logcat 中未开启，
  这些分支只由二进制坐实。修复方向仍需真机验证。
- 零 P0 源码改动、零设备操作、零新增 Gradle 依赖；
  `libNMCommonCache.so` 二进制原样（未重打包、未改指纹）。

## 7. 下一步（仍不需要设备在场）

1. 反汇编 `MakeRangeRequestAsync` 的代次递增点与 `NMNetSource::OnData`
   调用方（JNI 回调链），确认 `arg1` 是否就是 `Start` 的 `requestId`；
2. 反汇编 `NMSlicesFile::Write` @`0x12844` 附近，回答
   「已 commit 的数据有多少真正落进 vfile」；
3. 若设备重新在线，做一次 `level-5` 日志开启的短跑，直接观测
   `OnData() UnExpect New Offset` / `SKIP` / `Failed Not Support` 分布，
   把 §5 的 `inference` 升级为实证；
4. 之后才谈修复（Java 侧无法改 native；可行方向是让 Java 在
   `Start` 重发时不再为旧代次回传数据，或调整 `signal_size` / 首包策略），
   并按 `docs/17 §8.3` 判据验收。

> AI生成
