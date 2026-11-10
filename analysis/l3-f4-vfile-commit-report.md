# F4 · vfile commit 时序真机观测与 §61.61 结论精度修正（`12 §61.62`）

> **2026-11-10 第八十九批** · 用户提供新端点 `10.0.0.2:39737` → 设备重新上线。
> 承接 `§61.61`（用户裁决：第二卡点 = vfile 流式落盘跟不上 fMP4 跳跃式
> probe 读）。本批**趁设备在场做一次零代码改动的纯观测**，回答 §61.61
> 明确挂账的问题：`commit` 究竟是「落盘速度/进度不足」还是
> 「commit / Range 记账语义不允许回填跳跃区间」。
> **结论：vfile 归因被进一步强化；但 §61.61 的「无 codec」是观察窗口
> 不足造成的精度问题 —— 60 s 窗口下 codec 最终打开，随即 `-103` 终止、
> 未出声。F4 判据由此收窄到 `-103`。**
> 历史批次结论**不改写**，本批只追加修正。

---

## 1. 上线与前置（只读取证）

| 项 | 结果 |
|---|---|
| 首次 `adb connect 10.0.0.2:39737` | `10060` 超时（临时抖动，非端点失效） |
| 重试 + `adb kill-server/start-server` 后 | **`connected to 10.0.0.2:39737`**、`get-state=device` |
| 设备 | `product:mondrian model:23013RK75C`（Redmi K60） |
| `tools/l3_f4_preflight.py` | `all_pass=True / device_online=True / 可执行=False`（A/B 已裁决，符合 §61.59 口径） |
| 设备侧 | 自研包已装、`files/music/` 10 个样本齐全、`adb reverse --list` 空、P0 基线 APK `31,433,878 B` 在位 |
| 网络层诊断 | 本机 `10.0.0.3`；首次失败时 `ping 10.0.0.2` 100% 丢包、历史端点 `37827/39853/40807` 与 `192.168.1.11:40807` 端口均不可达；**重启 adb 会话后连通**。推断为设备休眠 / 无线调试瞬时不可达，**具体原因未实证、不做定论**；端点可复用 |

## 2. 实验设计（纯观测，零代码改动）

`tools/l3_f4_vfile_commit_probe.py --serial 10.0.0.2:39737 --wait 60`：

- **P0 基线 APK，未改 `UrlDataRequest.kt`、未重编译、未重装**；
- 自洽样本 `f3_m4a_complete.m4a`（16,573,387 B）× **REMOTE**
  （`ACTION_PLAY` + `EXTRA_SOURCE_URL` → `http://127.0.0.1:8765/`
  + `adb reverse` + PC `http.server`）；
- `logcat -c` 后**流式抓 60 s 全量 logcat**（`analysis/l3-f4-logs/vfile-commit-20261110-102722.txt`，4,560 行），
  而不是像 §61.59 那样只看 25 s / 45 s 尾段；
- `finally` 无条件清理 `adb reverse`、删除临时 `wifiOnly` 前置、
  关闭 http server 与 logcat 子进程；设备系统设置不变。

日志首行确认是本格注入、不是上一首 LOCAL 残留：
`prepare trackId=… remote=true url=http://127.0.0.1:8765/f3_m4a_complete.m4a`、
`resourceInfo extension=m4a fileSize=16573387`。

## 3. 决定性证据（60 s 时间序列）

| 时间 | 事件 |
|---|---|
| 10:27:25.870 | `Start id=1 offset=368640 length=1048576` |
| 10:27:25.872 | `Start id=2 offset=605007`；`WaitRequest cur offset:368640 … expect offset:605007 … **commit : 368640**` |
| 10:27:36.894 | `onEnd id=2` → `WaitRequest … **force:true**` → `Start id=3 offset=605007` |
| 10:27:45.137 → 10:27:51.919 | 同样 `force:true` → `Start id=4 / id=5 offset=605007`（**共 5 次跳跃读失败**，`offset:605007 readed_len:0 result:-2`） |
| 10:27:58.726 | `onEnd id=5` |
| **10:27:58.745~748** | **`PreSeekReadTask` 回退**：`offset:35726 … out_len:332914` → **`Start id=6 offset=368640`**（回到连续提交点） |
| **10:27:58.763** | **`Open codec for aac`**（44100 Hz / stereo / 128 kb/s）、`doPrepareAsync openSource 0`、`mDuration` 有值、AudioTrack Alloc |
| 10:27:58.869~875 | **`doBufferFill status 4 nErr 1, lasterr -103`**、`PlayerNotifyEvent msg=100 arg1=1 arg2=-103`、AudioTrack **Pause / Stop / Release** |
| 全程 | `Play status 3` 仅 1 次；**`Write start play` 0 次**；无 `Play status 4` |

从注入到 `Open codec` **≈32.9 s**。§61.59 的 45 s 窗口、§61.61 的
约 12 s 窗口都短于这条自愈路径，因此当时如实记录的「仍无 codec」
是**观察窗口不足**，不是现象不存在。

## 4. 结论修正（三层，均不改写历史文字）

1. **§61.61 用户裁决的方向被强化**：
   `commit` 在整个 60 s 内**恒定 `368640`**（只出现 1 个值），
   5 次 `force:true` 重试仍拿不到 `605007` 的字节。
   → **排除「单纯落盘速度/进度不足」**（给足时间也无增长），
   指向 **commit / Range 记账语义不允许回填跳跃区间**；
   且只有 native **回退到连续提交点 `368640` 重新请求**后才补齐数据、
   打开 codec —— 正好印证「`commit` 与跳跃点之间的空洞未被补齐」。
   §61.61 的「样本自洽性 / 错误完成通知均排除」不改写。
2. **观察精度修正**：长窗口下 codec **会**打开（≈32.9 s），
   但**随即 `-103` 终止、`Write start play` 0 次、未出声**。
   所以「fMP4 REMOTE 无 codec」应精确为
   **「跳跃读长时间重试 → 延迟打开 codec → 立即 `-103` 中止」**。
   §61.59 / §61.61 的实测日志与判据本身不改写，只补充窗口条件。
3. **F4 判据收窄到 `-103`**：这与 §61.50 / §61.51 最初定的
   「自动进曲连续 3 首无 `-103`、无释放后回调」完全一致 ——
   本批实测到 `-103` 与 AudioTrack Release，说明
   **F4 的真实阻塞不是「codec 打不开」，而是「vfile 跳跃读导致的
   长时间重试 + 数据不足 → `-103`」**。

> 工程口径补充：`commit` 恒定的具体 native 字段 / 函数
> （`NMNetSource::WaitRequest`、`PreSeekReadTask` 回退触发条件）
> 仍**未反汇编定位**；本批未实施、未验证任何修复。

## 5. 诚实边界

- **F4 仍判 BLOCKED**：本批是观测，不是修复验证；
  `-103` 判据**未通过**（反而首次在自洽样本 REMOTE 上实测到）。
- 不写「F4 已解决 / m4a 已支持 / 全格式已支持 / 已出声」；
  `Open codec for aac` **不等于**出声（无 `Write start play`、
  无 `Play status 4`、有 `-103` 与 Release）。
- **自动进曲 3 首 / seek / ENDED 仍 NOT RUN**；F3 14 格矩阵未改写；
  `f3_m4a_complete.m4a` 是「自洽 fMP4」而非完整真实非 fMP4 的 `.m4a`。
- P0 引擎舱零净改动（`UrlDataRequest.kt` `git diff` 空）、零新增依赖、
  发布基线零变化；A3 仍 **G09 PARTIAL**、舱不冻结、
  `engine_compat_test` 不回填。
- `tools/l3_f4_vfile_commit_probe.py` 的初判字段
  `mechanism_verdict=UNEXPECTED_CODEC_OPENED` **如实保留**（当时未复核
  `-103`），修正由本批离线审计 `tools/l3_f4_vfile_commit_audit.py` 给出，
  不回改原始证据。
- 设备仍在场（`10.0.0.2:39737`），本批跑完后**设备仍可断开**；
  下次上线复跑可直接重用 `--serial <新端点>`。

## 6. 交付与守护

- `analysis/l3-f4-logs/vfile-commit-20261110-102722.txt`（4,560 行原始 logcat）
- `analysis/l3-f4-vfile-commit-probe.json`（观测原始证据，含 commit 序列）
- `tools/l3_f4_vfile_commit_probe.py`（可复跑，纯 Python 零依赖、零个人路径）
- `tools/l3_f4_vfile_commit_audit.py` → `analysis/l3-f4-vfile-commit-audit.json`
  （离线复核，**30 项 checks 全 PASS**，防把「codec 打开」写成「已出声 / F4 已解决」、
  防把 commit 恒定写成「速度不足」、防回改 §61.61 历史结论）
- `check_source_contracts.py §61.62` 守护；全量回写 `docs/12 §61.62`、
  `09 §2·§7`、`16 §5.3`、`17 §8.22`、任务清单、当日日志与长期记忆。

## 7. 剩余断点与下一步

1. **F4（BLOCKED）**：以 `-103` 为主判据，做 native 级定位
   （`NMNetSource::WaitRequest` commit 记账 / `PreSeekReadTask` 回退条件 /
   Range 调度），再设计 Java/JNI 层「补齐 `[368640, 605007)` 空洞」
   的变体验证；下次设备上线复跑，判据 = 自动进曲 3 首无 `-103`、
   无释放后回调、seek / ENDED 正常、出现 `Write start play nErr:0`。
2. **F5**：A3 全绿后 ABI 回填 + 冻结舱 + `§61.33` 收口。
3. **P2** 播放页 1:1 → **P3** 八模块；F3 补齐 7 种 NO_SAMPLE；
   G09 PARTIAL / 舱不冻结；T-V USB / DMR 项。
4. 端点登记：本批 `10.0.0.2:39737`（历史 `10.0.0.2:37827` /
   `10.0.0.2:39853` / `192.168.1.11:40807`）。
   `§61.38`~`§61.62` 多批改动均未提交、未 push，`HEAD` 仍 `8c9d79b1`。

> AI生成
