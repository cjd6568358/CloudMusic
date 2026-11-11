# F4 · 现行上线执行链一致性自检（§61.72，2026-11-11）

> **性质**：纯离线、只读、零 P0 源码改动、不重跑 A/B、不覆盖任何历史证据。
> 唯一外部动作是 `adb devices` 只读探测；`device_run=false`。
> **F4 仍 BLOCKED**，真机复跑 NOT RUN。

## 1. 补的是什么缺口

`analysis/l3-f4-online-runbook.md` 顶部的 §61.70 补充执行层细化与
§61.71 ④ 已把上线判据改为「保持 `g_level=0` + 主动 seek + 探针抓
logcat + 用真实 `.rodata` 格式串扫 W 级日志」，但同一份 runbook 的
§1「一键执行（唯一命令）」仍写着 `l3_f4_kind_slot_ab.py --apply`，
§2「判读三分支」仍是 A/B 变体 B 的旧三分法，`tools/l3_f4_preflight.py`
的 `one_liner` 也仍指向该已完成实验。设备上线后照 §1 执行会重跑
§61.59 已裁决的 A/B（环节 B 部分成立、非充分原因），并临时改 P0。
本批把执行链口径与资产一次性对齐，**不改 A/B 历史证据与正文**。

## 2. 实跑结果

- checks：**14 项 / 全 PASS True**；失败项：无
- 设备：只读 `adb devices` → `device_online=False`（探测行 0 条）；
  `ready_to_run=False`（**恒 false**，接线回滚待用户拍板）。
- 现行探针：`l3_f4_vfile_commit_probe.py` 流式 logcat 落盘到
  `analysis/l3-f4-logs/vfile-commit-<stamp>.txt`，结果 JSON 的
  `log_file` 字段即 `--log` 的输入；**探针不具备 seek 能力**，
  runbook 已把「播放中主动 seek」如实登记为用户手动操作（物理在场）。
- §61.71 固化回放证据：`mode=replay` / `single_log_verdict=null` /
  13 项 checks 全 PASS / 7 条真实 `.rodata` 串 / 34 份归档，零改写。
- §61.65~§61.70 六份历史证据 JSON 只读核验 `all_pass=True`。
- §61.58 `analysis/l3-f4-preflight.json` 历史证据零改写
  （`all_pass=True` / `ready_to_run=False` / 15 项 checks）。

## 3. 现行上线执行链（本批未执行）

```bash
# 1. python tools/l3_f4_online_readiness.py
# 2. python tools/l3_f4_vfile_commit_probe.py --serial <新端点> --wait 60 --out analysis/l3-f4-live-online.json
# 3. python -c "import json;print(json.load(open('analysis/l3-f4-live-online.json',encoding='utf-8'))['log_file'])"
# 4. python tools/l3_f4_wbranch_recheck.py --log <上一步输出的 logcat 路径>
```

前提（均需用户）：① §61.69 接线回滚拍板；② 重开无线调试给新的配对
端点 + 一次性配对码（不落盘）+ 调试端点，`get-state=device`；
③ 确认 app 已装（未装则手机文件管理点
`/sdcard/Download/CloudMusic-debug.apk`）；④ 播放中**手动拖动进度条**
完成主动 seek（MIUI 无线 adb 下 `input swipe` 可能被拒，不伪造该能力）。
卸载已清空 `legacy_engine_cache` → 起点是**冷缓存**。

判读：`l3_f4_wbranch_recheck.py --log` 输出 4 种 `live_verdict` 之一；
出现 W 分支日志只提供升级推断的素材，**不等于 F4 修复或验收通过**，
仍须人工复核。保持 `g_level=0`，不要再用「level-5 关键词」或
`files/nmcache_log_level` 做 0/4/5 对照。

## 4. 诚实边界

- **F4 仍 BLOCKED**：`-103` 判据未通过、自动进曲 3 首 / seek / ENDED
  仍 NOT RUN、F3 14 格未改写；A3 仍 G09 PARTIAL、舱不冻结、指纹不回填。
- 真机复跑 **NOT RUN**、设备仍离线、接线未回滚、未切用户网络、
  so 二进制原样、§61.67~§61.71 历史正文与证据 JSON 一字未改。
- `all_pass` 只表示离线执行链口径与资产自洽，**不是设备在线证明，
  也不是 F4 验收通过**。

> AI生成
