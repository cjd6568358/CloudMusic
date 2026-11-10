# F4 · 变体 D 真机裁决：kind=2 落盘假说的有效负结果（`12 §61.64`）

> **2026-11-10 第九十一批** · 用户给出新端口 `41761` → 设备重新上线
> `10.0.0.2:41761`（`get-state=device`）。承接 §61.63 的下一步：
> 用 `--warmup 4.0` 复跑变体 D（`OnDataRequestData` kind `1L→2L`）取有效判据。
> **本轮链路完整展开、回归两格 PASS，主判据明确 NOT_MET** →
> 「把 kind 改成 2 能让 vfile 落盘」的修复预测**被真机证伪**；
> 同时证实 **kind 槽确实影响落盘量，但方向相反**（`1`→180224 / `2`→16384）。
> **F4 仍判 BLOCKED**，历史批次文字不改写。

---

## 1. 上线与实验条件

| 项 | 值 |
|---|---|
| 端点 | `10.0.0.2:41761`（旧 `39737` 已 `disconnect`）；`192.168.1.11:41761` / `127.0.0.1:41761` 均不可达 |
| 设备 | `get-state=device`（Redmi K60 / mondrian，同前批） |
| 命令 | `python tools/l3_f4_data_kind_ab.py --serial 10.0.0.2:41761 --apply --warmup 4.0` |
| 变体 | `OnDataRequestData(NATIVE_DATA_KIND_NORMAL, …)` → `OnDataRequestData(2L, …)` |
| 编译 / 安装 | `:app:assembleDebug --offline` **BUILD SUCCESSFUL in 8s**（rc=0）/ `install -r` **Success**（rc=0） |
| 主判据 | 自洽样本 `f3_m4a_complete.m4a` × REMOTE，60 s 全量 logcat（4,181 行） |
| 回归 | `f3_mp3.mp3` / `f3_wav.wav` × REMOTE，各 12 s |
| 恢复 | `finally` 逐字节恢复 P0 源码、基线重编译成功、`git diff` 空；`adb reverse` / `files/datastore/` 均为空 |

`clear_target_cache()` 本轮返回 `[]`（设备上目标缓存组已不存在），
但日志首行 `NMSliceFile Open Init :d6a57b…`（**无 `Use Old`**）与
`Start id=1 offset=0` 确认是**新建切片**，与 §61.63 清缓存后的起点一致，
因此两轮 commit 数值可直接对照。

## 2. 决定性对照（同起点：新建切片 / `Start id=1 offset=0`）

| | **基线 kind=1**（§61.63 清缓存后） | **变体 D kind=2**（本轮） |
|---|---|---|
| `commit` | **180224**（约 11 个 16 KiB 块） | **16384（仅 1 个块）** |
| 首次跳跃读 | `offset:327895 readed_len:0 result:-2` ×5 | `offset:327895 readed_len:0 result:-2` ×5 |
| 请求序列 | `id=1…5` → 回退 `id=6 offset=180224` | `id=1 offset=0` → `id=2 offset=0` → `id=3…6 offset=327895` → 回退 `id=7/8 offset=16384` |
| `Open codec` | ≈32.9 s `aac` | **全程无** |
| `Write start play` | 0 次 | **0 次** |
| `-103` | 4 次 | **1 次** |
| `Play status` | 3 | 3 |
| 回归 mp3 | — | **`mp3` / `Write start play nErr:0` / 无 `-103` PASS** |
| 回归 wav | — | **`pcm_s16le` / `Write start play nErr:0` / 无 `-103` PASS**（`Use Old` 切片、`commit=1048576`） |

日志证据：`analysis/l3-f4-logs/vfile-commit-20261110-130021.txt`（主判据）、
`-20261110-130127.txt`（mp3）、`-20261110-130142.txt`（wav）。

## 3. 裁决

1. **有效判据成立**：主判据 4,181 行、`createDataRequestHandler` /
   `NMSliceFile` / 8 次 `Start` / `WaitRequest commit` / `-103` 全在位，
   不是 §61.63 run1 那种「只有 `prepare`」的空跑；回归两格均**真实出声**，
   证明变体代码生效且未破坏基本取流链。因此本轮可以写结论。
2. **修复预测被证伪**：`kind=2` 并没有让 `OnWriteNetData` 把 1 MiB
   数据落进 vfile —— 反而 `commit` 从 180224 降到 **16384**，
   `Open codec` 从「≈33 s 延迟打开」变成「全程不打开」，主判据更差。
   → **不能把数据包 kind 改成 2 当作 F4 修复方向**。
3. **但 kind 槽确实参与落盘**：同样新建切片、同样 `Start id=1 offset=0`，
   仅 kind 不同就得到 180224 vs 16384 的落盘量差异。结合 §59 注释
   （`kind==2` 跳过 `NMMemoryCache::Write`、只投递 `OnWriteNetData`），
   更合理的解释是：**`kind=1` 的数据经内存缓存后被 flush 到 vfile，
   而 `kind=2` 的 `OnWriteNetData` 路径在 normal mode 下只处理了极少数据**。
   → 根因方向从「Java 侧 kind 值错配」转移到 **`OnWriteNetData` /
   `NMSliceFile::Write` / `commit` 记账的 native 落盘链路本身**。
   （此为基于真机对照的推论，具体函数仍待反汇编定位。）
4. **§61.63 的「kind 错配假说」按有效负结果收口**：不是「未证实」，
   而是「按该方向改 kind=2 已被证伪」；不写「F4 已解决 /
   m4a 已支持 / 全格式已支持」。

## 4. 诚实边界

- **F4 仍判 BLOCKED**：根因未定位、未修复、未验证；`-103` 判据未通过。
- 自动进曲 3 首（无 `-103`、无释放后回调）/ seek / ENDED 如实
  **NOT RUN**；F3 14 格矩阵未改写，回归只跑 2 格不是全量矩阵。
- `Open codec` ≠ 出声（§61.62）；回归 PASS 也不等于 fMP4 可播。
- P0 引擎舱零净改动（`UrlDataRequest.kt git diff` 空）、
  native so 二进制原样、零新增依赖、发布基线零变化；
  A3 仍 **G09 PARTIAL**、舱不冻结、`engine_compat_test` 不回填。
- 变体只临时存在，`finally` 无条件恢复 P0 基线并重编译重装；
  删除/清理的仅为 app 私有 vfile 缓存（可重建、非用户数据）。
- 设备仍在线（`10.0.0.2:41761`），本批跑完**仍可断开**。

## 5. 交付与守护

- `analysis/l3-f4-data-kind-ab-run3.json`（本轮结果快照，
  run1/run2 历史 JSON 不改写）+ `-main.json` / `-mp3.json` / `-wav.json`
  + 3 份真机 logcat；报告 `analysis/l3-f4-data-kind-verdict-report.md`。
- `tools/l3_f4_data_kind_verdict_audit.py` →
  `analysis/l3-f4-data-kind-verdict-audit.json`（**30 项 checks 全 PASS**，
  防把有效负结果写成「无有效判据 / 假设未测」、防把回归 PASS 写成
  主判据 PASS、防伪造 P0 恢复、防写 F4 已解决）；
  `check_source_contracts.py §61.64` 守护。
- 全量回写 `docs/12 §61.64`、`09 §2·§7`、`16 §5.3`、
  `17 §8.24`、任务清单、当日日志与长期记忆；端点登记
  `10.0.0.2:41761`。

## 6. 剩余断点与下一步

1. **F4（BLOCKED）**：做 `OnNetSourceData` → `OnWriteNetData` →
   `NMSliceFile::Write` / `commit` 记账的 **native 级反汇编定位**
   （`libNMCommonCache.so`）。**本批已收口反汇编环境缺口**：
   `capstone 5.0.7` + `pyelftools` 装入隔离 venv（未污染系统
   Python），三个历史反汇编工具复跑 **10 / 17 / 14 项全 PASS**；
   具体 `OnWriteNetData` / `commit` 记账定位仍待下批。
   重点解释「为什么在 normal mode 下 1 MiB 回传数据只落盘
   16384~180224 B」。
   修复验证判据不变：自动进曲 3 首无 `-103`、无释放后回调、
   seek / ENDED 正常、REMOTE 出现 `Write start play nErr:0`。
2. **F5**：A3 全绿后 ABI 回填 + 冻结舱 + `§61.33` 收口。
3. **P2** 播放页 1:1 → **P3** 八模块；F3 补齐 7 种 NO_SAMPLE；
   G09 PARTIAL / 舱不冻结；T-V USB / DMR 项。
4. `§61.38`~`§61.64` 二十七批改动均未提交、未 push，
   `HEAD` 仍 `8c9d79b1`。

> AI生成
