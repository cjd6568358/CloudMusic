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
