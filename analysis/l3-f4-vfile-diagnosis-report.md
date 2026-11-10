# F4 · vfile 落盘一手取证、清缓存对照与变体 D 受阻登记（`12 §61.63`）

> **2026-11-10 第九十批** · 用户「继续」（承接上一轮「要我继续跑变体验证，
> 还是先断开设备」→ 授权继续跑变体验证）。设备 `10.0.0.2:39737` 在场。
> 本批先做**零代码改动的设备侧一手取证**（`.nmsfi` 明文索引 + 清缓存对照），
> 再按新推出的根因假说跑变体 D；变体 D **两次均未获得有效判据**
> （首跑冷启动注入过早、次跑设备离线安装失败），按纪律**不写假设证伪、
> 不写 PASS**。
> **本批唯一有效结论**：F4 的 vfile 落盘失败**不是陈旧切片复用**，
> 且已把根因方向收窄到「路线 2 切换 normal mode 后，数据包 kind 仍按
> Memory Mode 校准恒传 `1`，可能未投递 `OnWriteNetData` 落盘任务」。
> **F4 仍判 BLOCKED**。历史批次（§61.61 / §61.62）文字不改写。

---

## 1. 设备侧一手取证：`.nmsfi` 索引（只读）

`run-as com.example.cloudmusic cat cache/legacy_engine_cache/*/*.nmsfi`
导出 9 个索引（明文 JSON），落盘 `analysis/l3-f4-vfile-index-before.json`：

| 资源 | `file_size` | slices / trunks | 判读 |
|---|---|---|---|
| **自洽 fMP4** `d6a57b…` | 16,573,387 | `slices[0].trunks=[{"offset":0,"size":368640}]`，其余 15 片全空 | **只落盘 368640 B**；`_0.nmsf` 实际大小正好 368640，**mtime 2026-11-09 23:51**（昨晚 §61.61 留下） |
| mp3 `5980aeaa…` | 1,129,442 | 2 片 **全满**（1048576 + 80866） | 落盘完整 |
| flac/wav `a852aff8…` | 4,194,304 | 4 片 **全满**（各 1048576） | 落盘完整 |
| wav `bf622e46…` / aiff `0fdbe943…` | 4,194,304 / 1,146,934 | 第 0 片全满、后续空 | 部分落盘 |
| fMP4 片段 `50bae5ca…` / `5ca0912a…` | 3,918,787 | 196608 / `[0,147456)+[327895,442368)` | **不连续 trunk 也能写入** |

**结论**：vfile 落盘机制本身能工作（多片全满、非连续 trunk 均可），
自洽 fMP4 的 368640 与 §61.62 的 `commit=368640` 完全对应 ——
**Java 侧回传的 1 MiB 数据一个字节都没进 vfile**，这才是
`ReadFromFileNoLock offset:605007 readed_len:0 result:-2` 的直接原因。

## 2. 清缓存对照实验（零代码改动，假说：陈旧切片被反复 `Use Old`）

`tools/l3_f4_cache_reset_probe.py --serial 10.0.0.2:39737 --wait 60`：
导出 before 索引 → 删除目标资源的 app 私有缓存组（可重建，样本仍在
`files/music/`）→ 复用 §61.62 观测脚本重跑同一条件 REMOTE → 导出 after 索引。

| 项 | before | after（清缓存后） |
|---|---|---|
| `NMSliceFile Open Init` | `Use Old:d6a57b…` | **新建**（无 `Use Old`） |
| 首个请求 | `Start id=1 offset=368640` | **`Start id=1 offset=0`** |
| trunk 总字节 | 368640 | **180224**（更少） |
| `commit` | 368640 | **180224** |
| 跳跃读 | `offset:605007 … -2` ×5 | `offset:327895 … -2` ×5 |
| 回退 → codec | `Start id=6 offset=368640` → ≈32.9 s `Open codec for aac` | `Start id=6 offset=180224` → ≈32.9 s `Open codec for aac` |
| 出声 | `Write start play` 0、`-103` | `Write start play` 0、**`-103` ×4** |

**判读修正（诚实）**：初版脚本把「trunk JSON 变了」当成「落盘改善」，
368640 → 180224 其实是**减少**；已改为比较落盘总字节数并离线重判，
结论为 **RULED_OUT**：清缓存后新建 vfile 仍只落盘 180224 B、
仍 `-103` 未出声 → **陈旧切片复用不是 F4 根因**。
证据 `analysis/l3-f4-cache-reset-verdict.json` +
`analysis/l3-f4-logs/vfile-commit-20261110-114804.txt`（2,894 行）。

## 3. 新推出的根因假说：路线 2 的 kind 错配（未证实）

§59 的 kind 校准是在 **Memory Mode**（`cache_local=false`）下做的：
那里 `OnWriteNetData` 恒被拒（`Failed Not Support`），所以数据包恒传
`kind=1` 走 `NMMemoryCache::Write` 才能出声。`UrlDataRequest.kt` 注释明确：
> `kind==2` → 跳过内存写，**投递 `OnWriteNetData` 异步任务（vfile 落盘）**；
> `kind!=2` → 只写内存缓存。

而路线 2（§61，用户拍板）已切到 **normal mode**（`cache_local=true`），
读侧改走 vfile（`ReadFromFileNoLock`）。→ **切换后遗留的错配可能是：
数据包仍恒传 `1`，只进内存缓存，vfile 永不落盘**。
这能解释：LOCAL 可播（不经 `UrlDataRequest`）、顺序格式 REMOTE 可播
（codec 在头部就打开、不依赖后续落盘）、fMP4 REMOTE 失败（probe 要跳读 vfile）。

> **这只是假说，未证实**；`OnNetSourceData` 的具体分支语义与
> `commit` 记账仍待 native 级反汇编定位。

## 4. 变体 D 两次尝试：均无有效判据（如实挂账）

`tools/l3_f4_data_kind_ab.py`（默认 dry-run、`--apply` 才改代码、
`finally` 无条件恢复 P0 基线并重编译重装）：把
`OnDataRequestData(NATIVE_DATA_KIND_NORMAL, …)` 临时改为
`OnDataRequestData(2L, …)`，主判据 = 自洽样本 REMOTE 60 s，
回归 = mp3 / wav REMOTE 各 12 s，判据以 **`-103` 消除 +
`Write start play nErr:0`** 为准（§61.62：`Open codec` ≠ 出声）。

| | 结果 | 原因 / 处置 |
|---|---|---|
| **run1** | 三格**只有 `prepare trackId=…`**，无 `createDataRequestHandler` / `Start` / codec / `-103`；日志显示 `install -r` 后首次冷启动 `JNI_OnLoad` 比注入晚近 2 s，主线程 `Skipped 38 frames` | **NO_VALID_CRITERION**。首版判据误写成 `NOT_MET`，已修正为 `chain_started` + `NO_VALID_CRITERION`；历史 JSON 另存 `analysis/l3-f4-data-kind-ab-run1.json` 不改写 |
| **run2** | 变体编译 `BUILD SUCCESSFUL in 7s`（rc=0），**`install -r` rc=1 → `adb.exe: device offline`**；主判据/回归 NOT RUN | 设备掉线（`get-state=offline`，`adb connect` / 重启 adb server 均失败），**不是编译或签名问题**。`finally` 已逐字节恢复源码、基线重编译成功；设备上 APK 自 run1 的 `finally` 起即为 P0 基线。证据 `analysis/l3-f4-data-kind-ab-run2.json` |

**不写**：「kind=2 假说被证伪」「变体 D 导致崩溃」「F4 已解决」。
两次均未产生可判定真机数据。

## 5. 结论与诚实边界

- **有效结论**：① vfile 索引一手证据把 §61.62 的 `commit=368640`
  坐实为「切片文件实际只写到该偏移、Java 回传数据未落盘」；
  ② 清缓存对照 **RULED_OUT**（陈旧切片复用不是根因）；
  ③ 根因方向收窄到「normal mode 下数据包 kind / `OnWriteNetData`
  落盘链路错配」（假说，未证实）。
- **F4 仍判 BLOCKED**：根因未定位、未修复、未验证；`-103` 判据未通过；
  自动进曲 3 首 / seek / ENDED 如实 **NOT RUN**；F3 14 格矩阵未改写；
  A3 仍 **G09 PARTIAL**、舱不冻结、`engine_compat_test` 不回填。
- P0 引擎舱**零净改动**（`UrlDataRequest.kt git diff` 空）、
  native so 二进制原样、零新增依赖、发布基线零变化；
  删除的仅为 app 私有 vfile 缓存（可重建，非用户数据），
  删除前已导出索引证据；`adb reverse` / `wifiOnly` 前置 / http server
  均由观测脚本 `finally` 恢复。
- 设备 **已掉线、可断开**；下次上线复跑变体 D 前须先确认
  `get-state=device`，并保留 `warmup=4.0`。

## 6. 交付与守护

- 新证据：`analysis/l3-f4-vfile-index-before.json` /
  `-after.json`、`analysis/l3-f4-cache-reset-probe.json` /
  `-verdict.json`、`analysis/l3-f4-data-kind-ab-run1.json` /
  `-run2.json` / `-main.json` / `-mp3.json` / `-wav.json`、
  对应 `analysis/l3-f4-logs/vfile-commit-20261110-11*.txt`。
- 新工具：`tools/l3_f4_cache_reset_probe.py`、
  `tools/l3_f4_data_kind_ab.py`；`tools/l3_f4_vfile_commit_probe.py`
  增加 `--out`（避免覆盖 §61.62 证据）与 `--warmup`（默认 1.5 不变，
  仅新实验显式加长）。
- `tools/l3_f4_vfile_diagnosis_audit.py` →
  `analysis/l3-f4-vfile-diagnosis-audit.json`（**35 项 checks 全 PASS**）
  + `check_source_contracts.py §61.63` 守护：防把「无有效判据」写成
  证伪 / PASS、防把清缓存 RULED_OUT 写成根因已定位、防伪造 P0 恢复。
- 全量回写 `docs/12 §61.63`、`09 §2·§7`、`16 §5.3`、
  `17 §8.23`、任务清单、当日日志与长期记忆。

## 7. 剩余断点与下一步

1. **F4（BLOCKED）**：设备再次上线后，先 `get-state=device`，
   用 `--warmup 4.0` 复跑变体 D 取有效判据；同时做
   `OnNetSourceData` / `OnWriteNetData` 的 native 级反汇编定位
   （`libNMCommonCache.so`，需 capstone）；主判据仍是
   自动进曲 3 首无 `-103`、无释放后回调、seek/ENDED 正常、
   `Write start play nErr:0`。
2. **F5**：A3 全绿后 ABI 回填 + 冻结舱 + `§61.33` 收口。
3. **P2** 播放页 1:1 → **P3** 八模块；F3 补齐 7 种 NO_SAMPLE；
   G09 PARTIAL / 舱不冻结；T-V USB / DMR 项。
4. 端点历史：`10.0.0.2:39737`（本批末尾掉线）/
   `10.0.0.2:37827` / `10.0.0.2:39853` / `192.168.1.11:40807`。
   `§61.38`~`§61.63` 二十六批改动均未提交、未 push，
   `HEAD` 仍 `8c9d79b1`。

> AI生成
