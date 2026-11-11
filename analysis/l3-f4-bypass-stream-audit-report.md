# F4 · A1「绕过取流」离线可行性论证报告（2026-11-11 / `12 §61.78`）

> **性质**：纯离线证据审计，**不新增真机证据、不推进 F4 修复、零 P0 源码改动**。
> 工具 `tools/l3_f4_bypass_stream_audit.py` 实扫已落盘的一手 logcat / JSON / 源码，
> **61 项 checks 全 PASS**（`device_run=false`）。
> 触发：用户「快速查看项目最近变动，了解最新进展，继续开发任务」；承接
> `§61.77` 三处 native 锚点待拍板 + 同日《播放内核两条路线评估》A1 时间盒建议。

---

## 1. 结论速览

**裁定**：`A1_BYPASS_STREAM_SUPPORTED_BY_EXISTING_DEVICE_EVIDENCE__BYPASS_NOT_FIX__INFERENCE`。

「绕过取流」在**已有真机一手证据**上成立，且是 `§61.77` 三处 native 锚点
（`0x10798` 代次比较 / `0xc728` WaitRequest 读空 -2 / `0x12dc8` 越界 -2）的
**绕行方案**：把音源预落为 App 私有本地文件、令 `EngineSource(url=null,
localPath=…)`，native 即走 `SourceConfig.Type.LOCAL` → `XorLocalSource` 直读 fd，
**不进入** `NMNetSource` / `NMSlicesFile::Write` / 代次门控 / `commit` 记账链。

**但这是绕行，不是修复**：REMOTE 取流缺陷仍在，`§61.75` 终局裁决
（F4 主判据无一 PASS）不变，**F4 仍 BLOCKED**。

## 2. 决定性证据：同一样本的 LOCAL / REMOTE 四格对照（`§61.61`）

样本 `f3_m4a_complete.m4a` / 16,573,387 B / sha256 `8821815b4fd374a1…`。

| 维度 | LOCAL（`selfconsist-local.txt`） | REMOTE（`selfconsist-remote.txt`） |
|---|---|---|
| 取流 | `url=null` → `getLocalPath` → `XorLocalSource::Open` fd | `Start id=1..4` → `WaitRequest` |
| codec | **`Open codec for aac`** | 无 |
| 出声 | **`Write start play nErr:0`** | 无 |
| Play status | 4 | 3 |
| 跳跃读 | 无 | `offset:605007 readed_len:0 result:-2`（6 次） |
| commit | 无记账 | **冻结在 368640** |

同一份自洽 fMP4，LOCAL 出声、REMOTE 无 codec —— **缺陷被隔离在 REMOTE
取流层**，与 `§61.77` 的静态链（代次错配 → commit 冻结 → result:-2 → -103）
完全对齐。`§61.51/§61.52` 报告原文亦登记「LOCAL 同内容可播 aac」/
「LOCAL 解出 `aac` ≠「m4a 已支持」」。

## 3. F3 双路径矩阵与 LOCAL 日志特征

- 14 格 = LOCAL 7 + REMOTE 7；**LOCAL 6 PASS / REMOTE
  5 PASS / 合计 11 PASS · 3 FAIL**。
- 每个 LOCAL PASS 格判据均为 `Write start play nErr:0`，且
  `path_evidence`（getLocalPath / CreateLocalSource / XorOpen）三项全真。
- 7 份 LOCAL logcat **无** `Start id=` / `WaitRequest` /
  `MakeRangeRequestAsync` / `ReadFromFileNoLock` / `NMSliceFile` / `commit :`；
  唯一含 `commit` 的行是系统 `mstatcommitLog`，与 native 记账无关。
- 14 格 `seek_observed` / `ended_observed` **全 false** → F4 主判据
  （自动进曲 3 首 / seek / ENDED）在 LOCAL 路径上**从未跑过**。
- 7 种 NO_SAMPLE 未覆盖：m4a, aac, opus, wv, tak, dsf, dff。

## 4. 源码装配点（切 LOCAL 无需改 P0 / native）

| 位置 | 实证 |
|---|---|
| `EngineAdapter.kt` | `PlayerDataSource(provider, source.trackId, source.url != null, info)` |
| `SourceConfig.java` | `this.type = remote ? Type.REMOTE : Type.LOCAL;` |
| `PlayerDataSource.java` | `new SourceConfig(remote)` |
| `§61.47` 入口 | `ACTION_PLAY` + `EXTRA_LOCAL_PATH` → app 私有 `files/music/`（零权限） |

即：只需让 `EngineSource.url == null` 且 `localPath` 指向已完整落地的本地文件，
native 自动走 LOCAL。**零新增权限 / 零新增 Manifest action / 零 Gradle 依赖 /
零 P0 源码改动 / 零 native 改动**。

## 5. 边界（不得越界解读）

1. 绕行 ≠ 修复：REMOTE 取流缺陷仍在，§61.75 终局裁决（主判据无一 PASS）不变。
2. F3 / §61.61 的 LOCAL 格只跑到单曲 prepare→出声；自动进曲 3 首 / seek / ENDED 全部 NOT RUN。
3. 7 种格式 NO_SAMPLE 未覆盖；自洽 fMP4 片段 LOCAL 可播 ≠ 完整真实 m4a 已支持。
4. 生产代价：整文件预下载、首播延迟、存储占用、seek 即时性、gapless、流式语义丢失。
5. 产品主音源（OpenList / WebDAV / strm 签名直链）原本走 REMOTE；改为下载后播放需用户拍板产品语义。
6. §61.69 接线回滚在 §61.78 时仍待拍板、已于 §61.79 执行；本批（§61.78）零 P0 源码改动、未连 adb、未重编译 / 未重装。

## 6. A1 时间盒实验设计（**本批 NOT RUN，需用户拍板**）

> 设备只读核查：`10.0.0.2:37163` = `device`，`pm path` 返回 `base.apk`；
> 但按项目纪律（不把「继续」当作路线授权 / 接线回滚授权），**本批不执行
> 修复性真机注入**，`§61.75` 的「真机取证已完结」口径不变。

**前提**：用户拍板接受 A1「绕过取流」路线与「下载后播放」语义；设备在线（本批只读核查 10.0.0.2:37163 = device，app base.apk 已装）；§61.69 接线回滚与否先定（§61.78 当时口径；§61.79 已回滚并同步守护）。

- 1. adb push analysis/l3-f3-fixtures/f3_m4a_complete.m4a 到 /sdcard/Download/
- 2. 按 §61.47 零权限通道 shell cat | run-as ... sh -c 'cat > files/music/f3_m4a_complete.m4a' 写入 app 私有目录（Git Bash 传 /data/... 需 MSYS_NO_PATHCONV=1）
- 3. 注入 ACTION_PLAY + EXTRA_LOCAL_PATH（§61.47 已有入口，零新增 Manifest action / 权限 / 依赖）
- 4. 流式抓 60s 全量 logcat + dumpsys media_session / audio 快照（§61.74/§61.75 多信号判据）
- 5. 判据：Write start play nErr:0 + 本 app AudioTrack state:started + MediaSession state=3 且 position 递增 + 自动进曲 3 首无 -103 + seek / ENDED
- 6. 对照：同一样本 REMOTE 轮（已知 FAIL，§61.61）
- 7. finally 恢复：清理注入的本地文件 / 停止服务 / 恢复队列，不落盘无关状态

**裁决**：通过 → 继续 A（F4 验收 17 §8.3 → F5 → P2/P3/P4）；不通过 → 按路线评估立即转 B2，不追加 A2 二进制补丁。

> **§61.79 演进批注（历史正文不改）**：A1 时间盒已于 `12 §61.79` 经用户授权
> 执行并裁决 `timebox_passed=False`（绕行成立但仅点火、无持续播放），
> `§61.69` 接线已回滚；本节保留 `§61.78` 当时的 NOT RUN 设计快照。

## 7. 与其他路线的关系

- **不要换解码器**：`§61.77` 根因在 `NMCommonCache` 取流层，与解码器正交；
  `analysis/l3-audio-decoder-effect-research-2026-11-11.md` 已登记
  「换解码器修不了 F4，只会把跑通的链推倒」。
- **不要转 A2 二进制补丁**：so 已 strip、无 `.symtab`、无源码、无回归基线
  （`§61.56`）。A1 不成立按路线评估直接转 **B2**（Media3 + 按需 FFmpeg 扩展）。
- **补格式**才考虑 LGPL 重编的增量 FFmpeg 软解扩展；**公开发布**才触发 `09 §5 R7`。

## 8. 验证与诚实边界

`python tools/l3_f4_bypass_stream_audit.py` → 61 项全 PASS；
`python tools/check_source_contracts.py` → PASS（含 `§61.78` 守护）；
`py_compile` OK；`git diff --check` 干净。零 P0 源码改动、未连 adb、
未重编译 / 未重装、so 二进制原样、`§61.67~§61.77` 历史正文与证据 JSON 一字未改。
**F4 仍 BLOCKED** —— 静态/离线证据等级提升 ≠ 修复 ≠ 验证；
A3 仍 G09 PARTIAL、舱不冻结、指纹不回填、F3 14 格未改写。

> AI生成
