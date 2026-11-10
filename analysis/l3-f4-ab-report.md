# F4 A/B 真机报告：faststart fMP4 对照样本 + 2s 等待证伪（2026-11-09 第七十八批 / `12 §61.51`）

> 设备 `10.0.0.2:39853`（Redmi K60 / Android 13），所有命令带 `-s`；
> **真机部分在设备离线前全部完成**，前置 `wifiOnly=false` / `adb reverse`
> 已由脚本 `finally` 恢复，P0 代码已回退基线并重装。
> 本报告为纯离线收尾，不再连接设备。

---

## 1. 为什么能做 A/B（承接 §61.50 的 BLOCKED）

§61.50 判 F4 BLOCKED 的唯一缺口是「没有能稳定触发 REMOTE 卡点的样本」。
本批从设备上已有的 fMP4（`/sdcard/Download/SaveTwitter…(1280p).mp4` 前
16MiB，`adb exec-out dd` 取回）构造了**同内容、同字节数、仅 `moov` 位置不同**
的一对样本（纯 Python，无编码器）：

| 样本 | 结构 | 字节数 |
|---|---|---|
| `f3_m4a_fast.m4a` | `ftyp(32) + moov(1302) + [styp/moof/mdat]×38`（**原 faststart 顺序**） | 3,918,787 |
| `f3_m4a.m4a` | `ftyp + [片段]×38 + moov`（§61.50 探针用，**moov 挪到末尾**） | 3,918,787 |

fMP4 片段靠 `moof/trun` 相对偏移定位，不依赖 `stco` 绝对地址，故重排合法。

## 2. 真机结果（设备在线期间全部完成）

| 格 | `extension` | codec | openSource | Write start | 判定 |
|---|---|---|---|---|---|
| `m4a_fast × LOCAL`（最终取证） | `m4a` | **`aac`** | **0** | **nErr:0** | **PASS** |
| `m4a_fast × REMOTE`（22s） | `m4a` | — | — | — | **FAIL**（无 codec） |
| `m4a_fast × REMOTE`（25s，基线） | `m4a` | — | — | — | **FAIL**（`Start id=3 offset=327895` 重复） |
| `m4a_fast × REMOTE`（变体 A，25s） | `m4a` | — | — | — | **FAIL**（`Start id=3 offset=327895` 重复） |
| `m4a moov-at-end × LOCAL/REMOTE`（§61.50） | `m4a` | — | `openSource 2` | — | FAIL（demuxer 未打开） |

REMOTE 失败现场（25s 仍不成立，非等待不足）：
`Start id=1 offset=147456 length=1048576` →
`Start id=2 offset=327895 length=1048576` →
`Start id=3 offset=327895 length=1048576`（**同区间重试**）→
`onEnd … delayed 2s` ×2 → `Play status 3`，无 `-103`、无 `Open codec`。

**基线恢复后另跑 5 格复验**（`analysis/l3-f4-baseline-recheck.json`）：
`mp3×local` / `mp3×remote` / `wma×remote` 全 **PASS**，
确认 A/B 变体回退后生产行为与 §61.49/§61.50 一致、零回归。
（其中一次 `m4a_local` 因 `adb push` 通道瞬时报错得到无效 FAIL，已用
独立临时文件名重推补跑，最终 `push_ok=true` 且 `Open codec for aac` PASS。）

## 3. A/B 实验与结论

**变体 A**：`UrlDataRequest.sink.onEnd()` 成功 EOF **去掉 `repeat(20) { sleep(100) }`**，
改为立即回传（日志标记 `AB-A immediate`），保留 `cancelled` 检查。
编译 `./gradlew :app:assembleDebug --offline`（1m 5s）→ `install -r` Success →
跑同一 `f3_m4a_fast.m4a × REMOTE` 25s。

| | 基线（2s） | 变体 A（立即） |
|---|---|---|
| `Open codec` | 无 | 无 |
| 重复 `Start id=3 offset=327895` | 有 | **有（完全相同）** |
| `-103` | 无 | 无 |
| `onEnd` | `delayed 2s` ×2 | `AB-A immediate` ×2 |

→ **去掉 2s 等待并未改变结果**，`12 §61.50` 施工单 §4 的候选①
「条件化重试节流 / 立即 onEnd」**在该载体上被 A/B 证伪**；
该 REMOTE 卡点不是固定 2s 延迟造成的，另有机理（fMP4 多 Range 分片与
normal mode vfile 写入的交互，尚需 native 级实证）。
因此**未合入任何 P0 改动**，代码已用基线副本回退、重新编译安装并复验。

## 4. 对 F4 状态的影响（仍 BLOCKED，但证据升级）

- §61.50 的「缺复现载体」阻塞**部分解除**：现在有稳定的 REMOTE 失败载体
  （faststart fMP4，`extension=m4a` 正确、LOCAL 同内容可播 aac）。
- 但**新的 A/B 结论是负面的**：它证明「改 2s」不是该卡点的解，
  且 P0 二进制不可改、Java 侧无落盘完成回调 → **F4 仍判 BLOCKED**。
- 下一步需要的是 **native 级反汇编定位 fMP4 多 Range 卡点**，或用户提供
  一个非 fMP4、moov-at-end 的真实 `.m4a`（`faststart=false`）来复现
  §61.50 描述的经典 `-103` 竞态。**不伪造 F4 完成。**

## 5. 证据清单

| 文件 | 内容 |
|---|---|
| `analysis/l3-f4-faststart-probe.json` | faststart 样本 9s 双路径初测（LOCAL PASS / REMOTE 仍准备中） |
| `analysis/l3-f4-logs/fast-{local,remote,remote-25s}.txt` | 原始 logcat |
| `analysis/l3-f4-ab-a.json` + `analysis/l3-f4-logs/ab-a-remote.txt` | 变体 A 真机结果 |
| `analysis/l3-f4-baseline-recheck.json` + `recheck-*.txt` | 回退基线后 5 格复验 |
| `analysis/l3-f4-m4a-final.json` + `final-m4a-{local,remote}.txt` | m4a 双路径最终取证 |
| `analysis/l3-f3-fixtures/f3_m4a_fast.m4a` | faststart 对照样本 |
| `analysis/l3-f3-fixtures/f3_m4a.m4a` | moov-at-end 对照样本（§61.50） |
| `analysis/l3-f4-ab-audit.json` | 13 项 checks 审计（本批守护输入） |

## 6. 诚实边界

- 两个 m4a 样本都是**人工截取/重排的 fMP4 片段（前 ~3.9MB，含视频轨）**，
  不是完整真实 m4a 音频；LOCAL 能解出 `aac` 只能说明 demuxer 识别容器，
  **不能据此写「m4a 已支持」**，也不把 REMOTE FAIL 判为播放器缺陷。
- 本批**未改任何生产代码**（`UrlDataRequest.kt` 已 `git diff` 为空），
  零新增依赖 / 权限 / 模块；A/B 变体只临时存在、已回退。
- `mDuration` / seek / ENDED / 自动进曲 3 首连播**本批未测**；
  A3 仍 G09 PARTIAL → 舱不冻结、`engine_compat_test` 不回填。
- F3 矩阵 JSON（§61.49 的 14 格）**未被本批改写**；m4a 两格作为 F4 独立
  证据登记，不并入 F3 矩阵，避免破坏既有守护与历史快照。

> AI生成
