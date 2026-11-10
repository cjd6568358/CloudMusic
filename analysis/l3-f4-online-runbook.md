# F4 · 设备上线一键执行清单（12 §61.58）

> **用途**：设备离线已持续数小时，F4 的离线准备（证据链 / A/B 脚本 /
> 编译期预检）全部就绪。本文档把「设备一上线要做什么、怎么判读、出问题怎么办」
> 压成一页，避免临场翻 20 批文档。
> **当前状态（§61.59 更新，2026-11-09 22:44）**：设备 `192.168.1.11:40807`
> 已上线，**A/B 已执行两次（25 s / 45 s）+ 基线对照**（`ab_executed=true`）。
> 主判据**未达成**：变体 B 消除了 `id>=3` 同区间重复请求，但 fMP4 REMOTE
> 仍无 codec → **环节 B 部分成立、非充分原因**，F4 仍 **BLOCKED**。
> 完整结论见 `analysis/l3-f4-ab-result-report.md`；本清单保留为
> 「再次上线复跑 / 后续实验」的操作手册。
>
> **端点状态（`12 §61.69` 更新，2026-11-11 凌晨）**：用户给出 `41703`
> 后单独 `adb connect 192.168.1.11:41703` 被拒，但当时只读
> `Test-NetConnection 192.168.1.11:41703 = True` → 判定 `41703` 是
> **调试端口**；用户补「配对端点 `192.168.1.11:38357` + 一次性配对码」
> 后 `adb pair` 成功、`adb connect 192.168.1.11:41703` 成功，设备上线为
> Redmi K60 `23013RK75C` / `mondrian`（Android 13 / API 33 / `arm64-v8a`）。
> **配对码不落盘**；`41417` / `41761` / `10.0.0.2:*` 历史端点仍不可达。
> 已按用户「需要真机的任务赶紧做」完成两轮真机纯观测（60 s 基线 /
> 35 s `setprop log.tag.* VERBOSE` 试探，零 P0 源码改动）：代次 `1→22`
> 连续递增坐实 §61.67 结论 A；level-5 分支两轮全 0，门控为 native
> `SetLoggerCallback(INMLogger, int)` 的 level 参数而非 liblog 优先级。
> **F4 仍 BLOCKED**、`-103` 未通过、无 `Write start play`；详见
> `analysis/l3-f4-generation-gate-live-report.md` 与 `12 §61.68`。
>
> **★ 当前状态（`12 §61.69`）：设备完全离线，真机任务整体阻塞**。用户授权
> 改 P0 后已落地 `EngineAdapter.kt` 的 level-5 诊断接线并重新编译
> （`:app:assembleDebug --offline` BUILD SUCCESSFUL / APK `31302687 B`），
> 但全新安装被 MIUI `INSTALL_FAILED_USER_RESTRICTED` 阻挡（adb 侧绕不过，
> 须在手机上手动确认），随后 `192.168.1.11:41703` 连 ICMP 都不通、
> `Test-NetConnection :41703 = False`；本机 WLAN 仍 `192.168.1.19`、
> **未改动用户网络**。结论：**真机 level-5 观测 NOT RUN**，F4 仍 BLOCKED。
> 当前 `origin/main` = `917bb7c7`。
>
> **★ 12 §61.70 更新（2026-11-11 凌晨，纯离线门控语义纠错）**：本批
> `adb devices -l` 返回**空列表** → 设备仍**完全离线**，真机任务整体阻塞，
> 未改动用户网络。用 capstone 反汇编 + 33 份归档 logcat 全扫，纠正
> `§61.68` / `§61.69` 的判据：**`libNMCommonCache.so` 默认 `g_level=0`
> 时 I/D/W/E 日志全部已输出**，F4 的 W 级分支 0 次命中是「分支未触发」
> 而非日志未开启；`SetLoggerCallback(..., 5)` 对 W 级零增益、会抑制 I/D
> 证据。**上线后判据相应改为**：① 保持 `g_level=0`（不调用
> `SetLoggerCallback`，或 `files/nmcache_log_level` 写 0）；② 用真实
> `.rodata` 格式串扫 W 级日志（`NMSlicesFile Write buffer len` /
> `::OnData() offset` / `OnWriteNetData SKIP|TRIM` / `file_len:`）；
> ③ 设计能触发这些分支的输入（越界写 / 代次不匹配 / `commit != offset`）；
> ④ `§61.69` 接线是否回滚**待用户拍板**（建议回滚，本批未执行）。
> 详见 `12 §61.70` 与 `analysis/l3-f4-level5-gate-semantics-report.md`。
> **F4 仍 BLOCKED**。
>
> **★ 12 §61.70 补充执行层细化（2026-11-11，纯离线反汇编，非新批次）**：
> 对 `.text` 两个窗口逐指令还原 + `.rodata` 全串表，**把「构造触发 W 级
> 分支的输入」从方向变成可执行判据**。本细化**只改本 runbook 的执行层**，
> 不改 `docs/12` 章节号 / `docs/09` 进度表 / 契约守护 / 断点号，
> **不算新批次交付，正式技术断点仍 `§61.70`**：
>
> | W 级格式串（`.rodata` 地址） | 触发条件（反汇编钉死） | 返回 |
> |---|---|---|
> | `::OnData() offset:%lld...`（`0x3cc73`） | 代次匹配且 `except_offset > offset` **或** `offset+size > end` | 仅日志 |
> | `::OnData() UnExpect New Offsetcommted`（`0x3ccc9`） | `commit != offset` | 仅日志 |
> | `NMSlicesFile Write buffer len... `（`0x3cf90`） | `len==0` **或** `offset` 非 64B 对齐 **或** `offset+len > file_len` | `-102` |
> | `... Finish EOF.`（`0x3cfce`） | `offset == file_len` | `-1` |
> | `... File Not Exsit`（`0x3d062`） | 切片文件对象缺失 | 继续 |
> | `... Fail. code:%d`（`0x3d017`） | 底层 slice write 返回负值 | `-98` |
> | `NMSlicesFile Write all finish`（`0x3d0ae`） | 全文件写满 + flush 完成（一次性） | `0` |
>
> **决定性结论**：
> ① 这些 W 级日志**全部是错误 / 边界分支的告警**，正常顺序写入**根本不
> 打印** → `§61.68` 两轮 0 命中是**正确行为**，不是日志被门控挡住；
> 再次印证 `§61.70`「默认 `g_level=0`、I/D/W/E 全开」。
> ② F4 真正该盯的是 **I 级（`prio=4`）** 的 `WaitRequest` /
> `MakeRangeRequestAsync` / `NMSlicesFile::Read ... Finish EOF`
> （`0x12c5c` 为 N=4，是 `§61.70` 所列 7 个 N=5 Write 侧 getter 之外的
> 补充观察，不改写 `§61.70` 结论）。
> ③ `NMNetSource::OnData @0x10790` 因果链钉死：首参 = 请求代次，
> 与 `[this+0xc0]` 不等即 `b.ne 0x108e0` **整包丢弃、无日志**；只有代次
> 匹配的包才在 `0x10884 adds x8,x21,x22` / `0x10888 str x8,[this+0x30]`
> 更新 `commit = offset+size`；收齐唤醒判据
> `commit >= [this+0x38]+[this+0x40]`。`§61.68` 实证代次 `1→22` 递增 +
> 重发 → 旧代次包全被 `0x10798` 丢弃 → **`commit` 冻结在 16384** →
> 永不收齐 → `result:-2` / `Finish Async:-103` → 无 `Write start play`。
> **F4 的真断点是代次错配，不是日志级别**。
>
> **上线复跑首要先手（替代「开 level-5」）**：保持 `g_level=0`，播放中
> **主动 seek**（改 `except offset` / 重置代次 / 让 `offset` 回跳），
> 用上表真实格式串扫 W 级日志；`UnExpect New Offsetcommted` 一旦出现，
> 即把「代次错配 → commit 卡住」由推断升级为运行时一手实证。另可播到
> 整曲末尾触发 `Write all finish`、seek 到缓存区外触发
> `OnWriteNetData SKIP|TRIM`。**F4 仍 BLOCKED**。
>
> **上线判据（须用户操作，助手不擅自切网络）**：① 手机开启「开发者选项 →
> 无线调试」，若提示重新配对，给出新的**配对端点 + 一次性配对码**（不落盘）
> 与**调试端点**；② `adb pair <配对端点> <配对码>` →
> `adb connect <调试端点>` → `get-state` 必须为 `device`；
> ③ `pm path com.example.cloudmusic` 确认 app 是否已装；未装则在手机
> 「文件管理」里点 `/sdcard/Download/CloudMusic-debug.apk` 手动安装，
> 或开启「USB 调试（安全设置）」后重试 `adb install -r`；
> ④ 跑 `python tools/l3_f4_vfile_commit_probe.py --serial <新端点>
> --wait 60 --out analysis/l3-f4-live-online.json`，按上方补充表用**真实
> `.rodata` 格式串**扫 W 级日志，并同时统计 I 级 `WaitRequest` /
> `MakeRangeRequestAsync` 的代次与 `commit` 序列；**不要**再用「level-5
> 关键词」或 `files/nmcache_log_level` 做 0/4/5 对照（`§61.70` 已证伪
> 该开关，`§61.69` 接线建议回滚、待用户拍板）。
> ⑤ 注意：卸载已清空 `legacy_engine_cache`，本次起点是**冷缓存**，
> 与 §61.68 暖缓存条件不同；`ACTION_PLAY` 直接 `playUrl`，不需恢复 Room 夹具。

---

## 0. 上线前置（30 秒）

```bash
cd D:/workspace/CloudMusic
python tools/l3_f4_preflight.py          # 应输出 all_pass=True / 设备在线=True / READY
```

设备重新上线后端口会变，先拿新端点（用户给）：

```bash
tools/bin/adb.exe connect <ip:port>
tools/bin/adb.exe -s <ip:port> get-state   # 必须是 device
```

`preflight` 会汇总检查：adb 二进制、基线 APK、5 个样本、`8765` 端口、
runner 9 个符号、10 批守护 JSON、契约自检、P0 `git diff` 空、编译期预检证据。

## 1. 一键执行（唯一命令）

```bash
python tools/l3_f4_kind_slot_ab.py --serial <ip:port> --apply
```

脚本自动完成：备份 `UrlDataRequest.kt` → 应用变体 B（`OnDataRequestEnd` 首参
恒传 `NATIVE_DONE_KIND_FLUSH = 1L`）→ `gradlew.bat :app:assembleDebug --offline`
→ `adb install -r` → 主判据 `f3_m4a_fast.m4a × REMOTE`（25s）+
回归 5 格（`mp3` local/remote、`wma`/`flac`/`wav` remote）→
**`try/finally` 无条件恢复 P0 基线、重编译、重装**，并恢复
`wifiOnly` / `adb reverse`。

## 2. 判读三分支

打开 `analysis/l3-f4-kind-slot-ab.json`：

| 情况 | 判据 | 结论与下一步 |
|---|---|---|
| **环节 B 成立** | `primary.codec == "aac"` 且 `primary.write == true`，`regression_all_pass == true` | §61.56 因果链闭环；仍需 **F3 14 格全量复跑 + 自动进曲 3 首**（无 `-103`、无释放后回调）→ F5 |
| **假设证伪** | `primary.codec == null` 且 `primary.starts` 含 `offset=327895` 重复 | 错误完成通知不是卡点主因，需 native 级另找机理（fMP4 多 Range × normal mode vfile）；保留 2s 等待不动 |
| **无有效判据** | `cells` 为空、`primary` 为 `{}`、或 `p0_restored == false` / `baseline_rebuild_ok == false` | **不得写结论**；查 `analysis/l3-f4-logs/ksab-*.txt` 与脚本输出，先解决注入/编译/设备问题再重跑 |

> **已知限制（§61.57 登记、未改）**：`cells` 为空时脚本仍可能返回 0，
> `finally` 里 `install -r` 失败被静默吞掉 —— 所以**必须人工核对 JSON**，
> 不能只看退出码。
>
> **§61.59 实际执行结果**：两次变体均落入「无有效判据之外的第四种状态」——
> `primary_criterion_met=false`（无 codec）且 `falsified=false`
> （也没有 `id>=3` 重复），即**现象改变但未通过**。脚本原 `falsified` 硬编码
> `offset=327895`，而本次基线重复的是 `offset=605007`；§61.59 已把判据改为
> 「`id>=3` 与 `id=2` 同 offset 重复」的模式检测。两次变体的
> `starts` / `on_end` 逐字一致，45 s 窗口排除等待不足；基线对照在同设备/
> 同样本/同回环下复现 `id>=3` 重复，排除环境波动。
> **结论：环节 B 部分成立（错误完成通知是重复请求的成因），但消除它后
> 仍无 codec，存在第二卡点（fMP4 多 Range × normal mode vfile 落盘 /
> demuxer probe 数据不足），需 native 级定位。**

## 3. 恢复确认（跑完必做）

```bash
git diff -- engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt
# 必须为空；且 :96 仍是 OnDataRequestEnd(requestId, error ?: "")
tools/bin/adb.exe -s <ip:port> shell "run-as com.example.cloudmusic sh -c 'ls files/datastore/ 2>/dev/null || echo empty'"
tools/bin/adb.exe -s <ip:port> reverse --list     # 应无 tcp:8765
python tools/l3_f4_kind_slot_ab_audit.py          # 14 项仍应全 PASS
python tools/check_source_contracts.py            # 结果：PASS
```

## 4. 常见坑（按历史真机实证）

| 现象 | 原因 / 处理 |
|---|---|
| `OSError [WinError 193]` | **§61.57 已修**（Windows 必须用 `gradlew.bat`），不应再出现 |
| `am startservice` 被 `app is in background` 拒 | 脚本已先 `am start -n <PKG>/.MainActivity` 再注入（§61.47 流程） |
| REMOTE 无 `prepare` | 设备默认网络常为 `MOBILE`，`wifiOnly=true` 会拒 URL 源；脚本 `set_network_gate(True)` 临时写 app 私有 `cloudmusic_settings.pb`，`finally` 恢复 |
| LOCAL 样本只写了 248 B | `adb shell` stdin 直传被截断；脚本用 `adb push /data/local/tmp` + `shell cat \| run-as cat`（§61.49 已修） |
| `openSource=2` / 无 codec | 人工重排的 moov-end fMP4 native 打不开；**主判据用 `f3_m4a_fast.m4a`（faststart）**，不要用 `f3_m4a.m4a` |
| 设备掉线 / 端口变 | 所有真机命令带 `-s <serial>`；掉线先 `adb connect`，脚本 `finally` 仍会恢复源码 |

## 5. 证据落盘位置

| 内容 | 路径 |
|---|---|
| A/B 结果 | `analysis/l3-f4-kind-slot-ab.json` |
| 逐格 logcat | `analysis/l3-f4-logs/ksab-*.txt` |
| 前置条件自检 | `analysis/l3-f4-preflight.json` |
| 编译期预检 | `analysis/l3-f4-ab-compile-precheck.json` |
| 因果链环节 A（JNI 透传） | `analysis/l3-f4-jni-arg-report.md` |
| native 分支 / task 差异 | `analysis/l3-f4-disasm-verify-report.md`、`analysis/l3-f4-task-diff-report.md` |
| F3 矩阵基线 | `analysis/l3-f3-matrix-report.md` |
| W 级分支触发条件反汇编（`§61.70` 补充执行层细化） | `analysis/l3-f4-wbranch-trigger-disasm.txt` |

## 6. 诚实边界

- 两个 m4a 都是**人工截取/重排的 fMP4 片段（含视频轨）**，不是完整真实 m4a；
  LOCAL 解出 `aac` ≠「m4a 已支持」，REMOTE FAIL 也不判播放器缺陷。
- **F3 矩阵 14 格未被改写**；7 种 NO_SAMPLE 已确认 OpenList 无样本（§61.55），
  需用户提供或换源后复用 §8.9 脚本。
- 自动进曲 3 首 / seek / ENDED 判据脚本未实现，如实 **NOT RUN**；
  A/B 通过后不能据此收口 F4。
- A3 仍 **G09 PARTIAL**、舱不冻结、`engine_compat_test` 不回填；
  `§61.38`~`§61.58` 二十一批改动均未提交、未 push，`HEAD` 仍 `8c9d79b1`。

> AI生成
