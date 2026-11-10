# F4 · kind 槽两个 task 的行为差异 + F3 样本边界盘点（12 §61.55）

> **2026-11-09 第八十二批** · 设备仍离线 → **纯离线 / 只读**、
> 零生产代码改动、零设备操作、零新增依赖。
> 承接 `12 §61.54`（`OnNetSourceDataDone` 分支语义的一手反汇编）。
> 本批回答两个此前只靠推理的问题：
> ① **这两个 task 分别做什么**，误传 `kind=2` 到底有多严重；
> ② **F3 的 7 种 NO_SAMPLE 格式在数据源里到底有没有**。

---

## 1. 两个 task 严重不对称（一手反汇编）

| | `kind == 2` | `kind != 2` |
|---|---|---|
| GOT 槽 | `0x4bed0` | `0x4bee8` |
| 符号 | `PreloadDoneTaskOnWriteThreadEv` | `OnFlushNetDataEv` |
| 地址 / size | `0xb86c` / **288 B** | `0xc068` / **88 B（22 条指令）** |
| 行为 | 构造 2 个对象、置 `bool=true`、**级联再投递一个 task** | 检查 `this+0xb8` 标志 → `vtable+0x40` 尾调用刷数据 |
| 是否投递新任务 | **是**（`bl #0x16468`） | **否** |

`PreloadDoneTaskOnWriteThread` 的关键指令：

```
0xb8bc  adrp  x9, #0x4b000
0xb8c0  ldr   x9, [x9, #0xf28]   ; GOT 0x4bf28
0xb8d8  mov   w10, #1
0xb8f0  strb  w10, [x0, #0x20]   ; bool 参数 = true
0xb900  bl    #0xd23c
0xb914  blr   x8
0xb924  bl    #0x16468            ; 把新 task 投递到同一队列
```

`.rela.dyn` 解析 `0x4bf28` → `_ZN17NMBufferSourceImp29PreloadDoneTaskOnRenderThreadEb`
@ `0xadbc`（size 420，`GLOB_DAT`）。即 **`kind==2` 会级联向渲染线程宣告
「预加载完成」**，是一条重的完成通知链；而 `OnFlushNetData` 只有 22 条指令、
不投递任何新任务，只是轻量刷数据 + 尾调用回调。

`0x16468` 本身是共享的任务队列投递函数（带引用计数与 `this+0x169/0x16a`
状态检查），三个调用点（`OnNetSourceDataDone` 两处 + `PreloadDoneTaskOnWriteThread`
一处）地址完全一致。

## 2. 与 §59.3 E9 历史真机实验互相印证

`docs/12 §59.3` 记录：数据包 kind 校准为 `NATIVE_DATA_KIND_NORMAL = 1L` 后，
`kind=1` 的 push 全部落内存缓存、32768 B 送达解码器；
**`kind=2` 的尾段 push 数据丢失 → `Send Packet Invalid data error`**。
这与本批静态发现吻合：kind=2 走的是「完成通知」而非「数据写入」。

**对 §61.53 A/B 的风险评估（本批最有实用价值的结论）**：
`1L` 不是一条凭空猜测的新值，而是 §59.3 已**真机校准、正常播放每天都在走**的
数据包 kind。因此把 `OnDataRequestEnd` 首参恒传 `1L` 是**对齐到已验证的正常值**，
而不是引入未验证路径 —— A/B 变体的安全性显著提高。
（`OnFlushNetData` 在 `this+0xb8` 为 0 时会走日志/错误分支，但既然 1L 数据包
路径长期可用，该分支已被覆盖。）

## 3. F3 的 7 种 NO_SAMPLE：数据源确实没有

`tools/l3_f3_openlist_scan.py`（只读列目录、**不下载音频**、凭据只从 `OLU/OLP`
环境变量读、绝不写入仓库）递归扫描 OpenList：

| 项 | 结果 |
|---|---|
| 扫描目录 | **80**（深度 ≤3，`per_page=500`） |
| 列目录错误 | **0** |
| 音频扩展名分布 | `mp3` 242 / `flac` 66 / `ape` 54 / `wma` 27 / `wav` 9 / `mp4` 3 |
| 7 种 NO_SAMPLE 命中 | **0**（`m4a/aac/opus/wv/tak/dsf/dff` 全为 0） |

结论：F3 的 NO_SAMPLE 缺口**不是脚本能力问题，也不是没去找**，而是这个数据源
确实没有这些格式；只能由用户提供样本或更换数据源，之后复用 §8.9 脚本复跑。
本盘点**不改变 F3 矩阵 14 格的既有结论**，也不判任何格式为已支持。
注：扫描受 `max_depth=3 / max_dirs=80` 上限约束，结论只对已扫描到的目录成立。

## 4. 结论分层（不偷换）

- **已证实（本批 + §61.54 一手二进制证据）**：`kind==2` 触发的是级联的
  「预加载完成」通知链，`kind!=2` 只是轻量刷数据；两者严重不对称；
  `1L` 是 §59.3 已校准的正常值。
- **仍待验证（高置信假设、不是结论）**：`UrlDataRequest.kt:96` 传 `requestId` →
  `id==2` 被误判 kind → fMP4 REMOTE 数据不足却被宣告完成 → 重发同区间 → 无 codec。
  这条**因果链仍无真机证据**。§59.2 exp6 反例、竞品同样传 requestId 却可用、
  `ape×remote` 与 `aiff` 仍不解释。
- **F4 仍判 BLOCKED**，A/B 尚未执行（`ab_executed=false`）。本批只是把
  「为什么值得做 A/B」以及「为什么 1L 是合理修复值」两条理由变硬，**不替代 A/B**。

## 5. 解除条件（不变）

设备重新上线后给新端点：

```
python tools/l3_f4_kind_slot_ab.py --serial <ip:port> --apply
```

预测 fMP4 REMOTE 出现 `Open codec for aac` + `Write start play nErr:0`；
若仍 `Start id=3 offset=327895` 重复且无 codec，则假设证伪。
脚本 `finally` 无条件恢复 P0 基线并重编译重装。之后仍需 F3 14 格全量复跑 +
自动进曲 3 首（无 `-103`、无释放后回调），后者如实 **NOT RUN**。

## 6. 交付与诚实边界

- `tools/l3_f4_task_diff_verify.py` → `analysis/l3-f4-task-diff.json`
  （**14 项 checks 全 PASS**）；`tools/l3_f3_openlist_scan.py` →
  `analysis/l3-f3-openlist-scan.json`；
  `tools/l3_f4_task_diff_audit.py` → `analysis/l3-f4-task-diff-audit.json`；
  `check_source_contracts.py §61.55` 守护。
- **零生产代码改动**：`UrlDataRequest.kt` `git diff` 为空、`so` sha256 与
  `assets/ENGINE-FINGERPRINT` 一致、`engine_compat_test` 不回填、舱不冻结。
- **零设备操作**：不连 adb、不改设备设置；OpenList 只列目录、不下音频。
- 不写「根因已证实」「F4 已解决」「m4a 已支持」「全格式已支持」；
  两个 m4a 仍是人工截取/重排的 fMP4 片段；F3 矩阵 14 格未改写；
  A3 仍 **G09 PARTIAL**。`§61.38`~`§61.55` 十八批改动均未提交、未 push，
  `HEAD` 仍 `8c9d79b1`。

> AI生成
