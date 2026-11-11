# F4 · §61.69 level-5 诊断接线回滚审计（12 §61.79，2026-11-11）

> **性质**：纯离线只读审计，**零生产源码改动**（回滚动作本身不在此工具内执行）、
> 不连 adb、不构建、不重装。回滚的是诊断接线，**不是 F4 修复**；
> **绕行 ≠ 修复**，F4 仍 BLOCKED。

## 1. 回滚依据

- `12 §61.70` 勘误：`libNMCommonCache.so` 的 `g_level` 进程启动恒为 0，
  默认 I/D/W/E 日志**全开**；`level=5` 反而抑制 I/D（getter 为
  `cmp w8,w0; cset w0,gt` 严格大于比较）。
- `INMLogger` 是空类、无 `BIInfo`，`GetMethodID` 会失败，接线有风险。
- `§61.73`/`§61.75` 真机取证用的 APK 本就不含接线（基线 APK），
  源码保留接线与现场脱节，且零增益。

## 2. 核验结果

| # | 检查项 | 结论 |
|---|---|---|
| 1 | `inmlogger_import_removed` | PASS |
| 2 | `setlogger_instance_call_removed` | PASS |
| 3 | `setlogger_prefix_removed_from_adapter` | PASS |
| 4 | `nmcache_log_level_removed` | PASS |
| 5 | `default_5_removed` | PASS |
| 6 | `coerce_0_5_removed` | PASS |
| 7 | `diag_logger_line_removed` | PASS |
| 8 | `wiring_call_removed_native_decl_intact` | PASS |
| 9 | `vfm_still_constructed` | PASS |
| 10 | `setconfig_still_present` | PASS |
| 11 | `cache_dir_still_present` | PASS |
| 12 | `manager_setconfig_still_called` | PASS |
| 13 | `remote_local_fork_intact` | PASS |
| 14 | `prepare_async_still_called` | PASS |
| 15 | `native_load_chain_still_called` | PASS |
| 16 | `sourceconfig_fork_intact` | PASS |
| 17 | `historical_wiring_audit_json_intact` | PASS |
| 18 | `historical_wiring_audit_sha_recorded` | PASS |
| 19 | `so_binary_present` | PASS |
| 20 | `so_matches_historical_record` | PASS |
| 21 | `acceptance_final_still_blocked` | PASS |
| 22 | `bypass_audit_intact` | PASS |
| 23 | `docs12_has_61_79` | PASS |
| 24 | `docs12_still_f4_blocked` | PASS |
| 25 | `docs_09_16_17_mention_61_79` | PASS |
| 26 | `no_false_claim_in_tool_source` | PASS |
| 27 | `rollback_comment_cites_61_79` | PASS |
| 28 | `backlog_and_runbook_mention_61_79` | PASS |

## 3. 关键不变量

- 接线专有字面串自 `EngineAdapter.kt` / 源码树**全部消失**：{'import com.netease.cloudmusic.sdk.NMCommonCache.INMLogger': True, 'SetLoggerCallback(INMLogger(), level)': True, 'SetLoggerCallback(INMLogger()': True, 'nmcache_log_level': True, 'getOrDefault(5)': True, 'coerceIn(0, 5)': True, 'logger=INMLogger': True}
- 功能代码（`SetConfig` / VFM / 缓存目录 / `source.url != null` 分叉 /
  `remote ? Type.REMOTE : Type.LOCAL`）**保持不变**：{'NMVirtualFileManager(it)': True, 'SetConfig(json)': True, 'legacy_engine_cache': True, 'manager.SetConfig(json)': True, 'PlayerDataSource(provider, source.trackId, source.url != null, info)': True, 'engine.prepareAsync()': True, 'EngineNativeLoader.loadChain': True}
- `§61.69` 历史接线审计 JSON 作为**历史冻结件一字未改**
  （`all_pass=true` / `checks_total=23` / `setlogger_called_with_instance=true`），
  不重跑 `tools/l3_f4_level5_wiring_audit.py` 覆盖它。
- native so 二进制原样：`sha256=7a1adc95c255c241…`（与历史记录 一致）。
- 零 P0 native 改动、未重编译 / 未重装、未改设备系统设置。

## 4. 诚实边界

- 本批只回滚**诊断接线**，不修 REMOTE 取流缺陷；`§61.75` 终局裁决不变，
  **F4 仍 BLOCKED**，`§61.78` 的「绕行 ≠ 修复」口径不变。
- A1 时间盒真机实验另由 `tools/l3_f4_a1_bypass_live_probe.py` 取证、
  `tools/l3_f4_a1_bypass_verdict.py` 纯离线裁决，与本回滚正交。
- A3 仍 G09 PARTIAL、舱不冻结、F3 14 格未改写、指纹不回填。

## 5. 实跑核验

- checks：**28 项 / 全 PASS True**；失败项：无

> AI生成
