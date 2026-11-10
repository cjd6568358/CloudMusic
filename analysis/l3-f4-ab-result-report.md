# F4 · kind 槽 A/B 真机裁决结果（12 §61.59）

> **2026-11-09 第八十六批** · 用户提供新端点 `192.168.1.11:40807`，
> 设备上线 → 真机 A/B **已执行**（`ab_executed=true`）。
> 这是设备离线数小时后第一次裁决 §61.56 的**环节 B**。
> **结论：环节 B 部分成立，但不是 fMP4 REMOTE 无 codec 的充分原因；
> F4 仍判 BLOCKED。** P0 已无条件恢复、零净改动。

---

## 1. 执行摘要

| 运行 | 变体 | 端点 | 主判据窗口 | 主判据 | 5 格回归 | P0 恢复 |
|---|---|---|---|---|---|---|
| run1 | B（恒传 `1L`） | `192.168.1.11:40807` | 25 s | 未达成 | 全 PASS | ✅ |
| run2 | B（恒传 `1L`） | 同上 | **45 s** | 未达成 | 全 PASS | ✅ |
| 对照 | **P0 基线**（脚本恢复重编译后） | 同上 | 25 s | 未达成 | — | — |

`preflight` 上线前自检 `all_pass=True / device_online=True / READY`；
脚本 `--apply` 自动完成备份 → 变体 → `gradlew.bat :app:assembleDebug --offline`
→ `install -r` → 主判据 + 回归 → `finally` 恢复基线、重编译、重装。
两次均 `p0_restored=True`、`baseline_rebuild_ok=True`。

## 2. 决定性证据：重复请求被消除，但 codec 仍未打开

| | P0 基线（对照） | 变体 B（run1 / run2 完全一致） |
|---|---|---|
| `Start` 序列 | `id=1 offset=442368` → `id=2 offset=605007` → **`id=3 offset=605007`** → **`id=4 offset=605007`**（同区间连锁重复） | `id=1 offset=147456 length=180439` → `id=2 offset=605007 length=1048576`，**无 `id≥3`** |
| `onEnd` | `[1, 2, 3]` | `[1, 2]` |
| `Open codec` | 无 | 无 |
| `Write start play nErr:0` | 无 | 无 |
| `Play status` | 3（准备中） | 3（准备中） |
| `-103` | 无 | 无 |
| 5 格回归 | — | `mp3` local/remote、`wma`/`flac`/`wav` remote **全 PASS** |

两次变体运行的 `starts` / `on_end` 逐字一致，45 s 窗口排除「等待不足」；
基线对照在**同一设备、同一样本、同一 HTTP 回环**下复现 `id≥3` 同区间重复，
排除「环境波动导致现象不同」。因此差异由变体造成。

## 3. 结论分层（本批核心）

- **环节 A（`requestId → kind` 槽）已证实**（§61.56，二进制 + §59.3 校准）。
- **环节 B 部分成立**：`requestId==2 → kind==2 → PreloadDone` 确实是
  **「同区间重复请求」**的成因；恒传 `1L` 后连锁重试消失（4 个 Start → 2 个）。
  这与 §61.52 的预测方向一致。
- **环节 B 不是充分原因**：消除重试后 fMP4 REMOTE **仍无 codec**（25 s 与
  45 s 两次），说明存在**第二卡点**，位于「fMP4 多 Range × normal mode vfile
  落盘 / demuxer probe 数据不足」一侧，而非完成通知本身。
- 一个反直觉但必须如实写下的观察：基线里 `kind==2` 触发的「重新请求」在
  fMP4 场景下**可能反而承担了补充 Range 的作用**；变体 B 取消它之后，
  native 停在 `id=2` 不再请求，`demuxer` 拿不到足够数据。这解释了为什么
  「去掉错误完成通知」没有带来 codec，但**仍未实证**，需 native 级定位。
- **F4 仍判 BLOCKED**。不得写「假设被证伪」（重复请求确实被消除了）、
  也不得写「F4 已解决 / m4a 已支持 / 全格式已支持」。

## 4. 判据实现的一处缺陷（已修）

脚本原 `falsified` 硬编码 `offset=327895`。本次基线重复的实际是
`offset=605007`（首次请求位置随运行而变），导致 `falsified=False`。
判据应改为**「`id≥3` 的 Start 与 `id=2` 同 offset 重复」**这一模式，
而非固定数值。本批已修 `tools/l3_f4_kind_slot_ab.py`，并对既有
A/B 结果按新模式**离线重判**（见 `analysis/l3-f4-ab-result-audit.json`）：
基线 = 有重复、变体 = 无重复，结论不变。

## 5. 恢复确认（跑完已实测）

```
git diff -- UrlDataRequest.kt                    → 空，:96 仍为 OnDataRequestEnd(requestId, error ?: "")
app 私有 files/datastore/                       → 空（wifiOnly 前置已删）
adb reverse --list                              → 无 tcp:8765
p0_restored / baseline_rebuild_ok               → True / True（两次）
```

`so` sha256 与 `assets/ENGINE-FINGERPRINT` 一致、`engine_compat_test` 不回填、
舱不冻结。设备现已可离线，所有真机前置均已恢复。

## 6. 交付与守护

- 证据：`analysis/l3-f4-kind-slot-ab.json`（run2）、
  `analysis/l3-f4-ab-run1-25s.json` / `l3-f4-ab-run2-45s.json`、
  `analysis/l3-f4-ab-control-baseline.json`、
  `analysis/l3-f4-logs/ab-{run1,run2}-m4a-remote.txt` /
  `ab-control-baseline-remote.txt` / `ksab-*.txt`。
- `tools/l3_f4_ab_result_audit.py` → `analysis/l3-f4-ab-result-audit.json`
  （防把「部分成立」写成 PASS、防伪造恢复、防把 NOT RUN 写成已测）。
- **口径迁移**：§61.53 / §61.57 / §61.58 三处守护及其审计工具中
  「A/B 尚未执行」类断言统一改为「**A/B 已执行且结果如实登记**」
  （`primary_criterion_met=false`、`p0_restored=true`、
  `regression_all_pass=true`、`auto_advance_3_tracks` 含 NOT RUN）。
- `check_source_contracts.py §61.59` 守护。

## 7. 诚实边界与下一步

- **自动进曲 3 首（无 `-103`、无释放后回调）/ seek / ENDED 如实
  `NOT RUN`**；F3 14 格矩阵未全量复跑、未改写。
- 两个 m4a 仍是人工截取/重排的 fMP4 片段（含视频轨）；LOCAL 解出 `aac`
  ≠「m4a 已支持」，REMOTE FAIL 不判播放器缺陷。
- exp6 反例 / 竞品同样传 requestId 却可用 / `ape×remote` 与 `aiff`
  仍不解释。
- A3 仍 **G09 PARTIAL**、舱不冻结；`§61.38`~`§61.59` 二十二批改动
  均未提交、未 push，`HEAD` 仍 `8c9d79b1`。
- **下一步**：第二卡点需 native 级定位（fMP4 多 Range 的 vfile 落盘时序 /
  demuxer probe 所需数据范围），或用户提供**完整、非 fMP4 的真实 `.m4a`**
  重新建立可判定样本；设备下次上线时复用现有脚本即可。

> AI生成
