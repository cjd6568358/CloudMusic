# §61.105 ape-remote 加长等待单格裁决（2026-11-13）

- **status = NOT_RUN，device_run = false**：设备端点全部 offline（无线调试未上线）；不执行真机命令、不用旧矩阵 / 缓存 / 推断代替一手 logcat。§61.104 的 ape-remote NO_READY_IN_WINDOW 仍不判缺陷也不判通过。
- 判据 4/5 PASS（其中「设备在线」判据为 FAIL 是 NOT RUN 的直接原因）。

## 设备现场（只读）

```
List of devices attached
10.0.0.2:39853         offline transport_id:2
10.0.0.2:43355         offline transport_id:1
```

## 15:00 两份副本的处置

判据命中：True → 判定为 11-12 历史 22 格全矩阵快照副本，**不采信、不作任何判据、不覆盖、不删除**（历史零改写）。

| 文件 | serial | 格数 | counts | elapsed |
|---|---|---|---|---|
| b2-f3-ape-remote-longwait-2026-11-13.json | 192.168.1.11:38407 | 22 | {'PASS': 14, 'FAIL': 8} | 219.0 |
| b2-f3-retest2-2026-11-13.json | 192.168.1.11:38407 | 22 | {'PASS': 14, 'FAIL': 8} | 219.0 |

## 设备上线后的复跑剧本（本轮未执行）

```
python tools/l3_f3_matrix_run.py --serial <endpoint> --formats ape --paths remote --wait 30
```

设备上线后先 adb -s <endpoint> get-state=device；运行前备份 analysis/l3-f3-matrix.json 与 l3-f3-logs/（脚本会覆盖），跑完 git restore 恢复历史证据，新结果另存 -longwait 文件；真机命令全部带 -s <serial>；不擅自 pm grant / 不改设备设置。

## 判据明细

| # | 判据 | 结果 | 证据 |
|---|---|---|---|
| 1 | adb 存在可复跑的 device 端点 | FAIL | adb devices -l → 10.0.0.2:39853=offline, 10.0.0.2:43355=offline |
| 2 | 两份 15:00 副本经判据识别为 11-12 历史 22 格快照（不采信） | PASS | serial=192.168.1.11:38407 / 22 格 / 14 PASS 8 FAIL / elapsed 219.0s |
| 3 | 随附 *-retest2 日志为 11-12 旧 logcat（非 11-13 一手） | PASS | analysis\l3-f3-logs\ape-remote-retest2-2026-11-13.txt |
| 4 | §61.104 装机端点与副本端点不一致 | PASS | §61.104 真机 serial=10.0.0.2:43355；副本 serial=['192.168.1.11:38407', '192.168.1.11:38407'] |
| 5 | 已登记加长等待单格复跑命令（设备上线后可执行） | PASS | python tools/l3_f3_matrix_run.py --serial <endpoint> --formats ape --paths remote --wait 30 |

## 诚实边界

不宣称 8 格已修复 / 全格式覆盖；PASS 判据仅 READY(duration>0) + isPlaying，未逐格验证 seek / 完整时长 / ENDED；未做破坏性注入，未改 Manifest / CMake / 生产构建配置。

复跑：`python tools/b2_f3_longwait_audit.py --check`（只读比对冻结 JSON）。
