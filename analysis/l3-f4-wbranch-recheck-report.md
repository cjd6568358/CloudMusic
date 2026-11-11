# F4 上线复跑 W 分支判读工具（§61.71，2026-11-11 09:50）

> **性质**：纯离线、零第三方依赖、不连 adb、零 P0 源码改动、未改动用户网络。
> 承接 `analysis/l3-f4-online-runbook.md` 的「§61.70 补充执行层细化」，
> **不新增技术断点编号之外的验收结论，F4 仍 BLOCKED**。

## 1. 补的是什么缺口

runbook 上线步骤 ④ 明确要求：复跑
`tools/l3_f4_vfile_commit_probe.py` 后，「按补充表用**真实 `.rodata`
格式串**扫 W 级日志，并同时统计 I 级 `WaitRequest` /
`MakeRangeRequestAsync` 的代次与 `commit` 序列」。

实查该探针脚本（§61.62 产物）：它只解析 `WaitRequest commit` 时序、
`Start` / `onEnd` / `Open codec` / `Write start play`，**源码里没有任何一条
W 分支格式串**，也不统计代次序列。`§61.68` 的两轮观测靠的是另一个
审计脚本里的猜测关键词表（`NMSlicesFile` / `SKIP` / `TRIM` …），而
`§61.70` 已证伪这套「level-5 关键词」判据。也就是说：**设备一旦上线，
按 runbook 跑完探针后并没有可直接执行的 W 分支判读步骤** —— 这正是本轮补的环。

## 2. 做法：以 so 的 `.rodata` 为唯一真相源

`tools/l3_f4_wbranch_recheck.py` 用纯标准库解析 ELF64 section header
定位 `.rodata`，按 runbook 补充表的 7 个地址读出**原生格式串**，再用串中
稳定的文本锚点扫描 logcat；不硬编码猜测词，也不依赖 `capstone` /
`pyelftools`。读出的 7 条原文：

| 地址 | `.rodata` 格式串（原文） | 触发条件 | 返回 |
|---|---|---|---|
| `0x3CC73` | `NMNetSource(%lld-%lld)::OnData() offset:%lld, size:%lld, except offset:%lld, end:%lld` | 代次匹配且 `except_offset>offset` 或 `offset+size>end` | 仅日志 |
| `0x3CCC9` | `NMNetSource(%lld-%lld)::OnData() UnExpect New Offsetcommted:%lld, offset:%lld, size:%lld, cur_req_size:%lld` | `commit != offset` | 仅日志 |
| `0x3CF90` | `NMSlicesFile Write buffer len:%u, offset:%lld, file_len:%lld ` | `len==0` / `offset` 非 64B 对齐 / `offset+len>file_len` | `-102` |
| `0x3CFCE` | 同上前缀 + `Finish EOF.` | `offset == file_len` | `-1` |
| `0x3D062` | 同上前缀 + `File Not Exsit` | 切片文件对象缺失 | 继续 |
| `0x3D017` | 同上前缀 + `Fail. code:%d` | 底层 slice write 返回负值 | `-98` |
| `0x3D0AE` | `NMSlicesFile Write all finish` | 全文件写满 + flush 完成（一次性） | `0` |

**判读语义**：这 7 条全部是错误 / 边界分支告警，正常顺序写入根本不打印。
所以「0 命中」是**分支未触发的有效负结果**，不是日志被门控挡住
（`§61.70`：`g_level` 默认 0 → I/D/W/E 默认全部输出）。只有
`UnExpect New Offsetcommted` / `Write buffer len` 等真正出现，才具备把
「代次错配 → `commit` 冻结」从静态推断升级为一手实证的素材 —— 即便如此，
是否升级仍须人工复核，本工具不自动收口。

## 3. 离线回放实跑（可复跑、已验证）

`python tools/l3_f4_wbranch_recheck.py` 回放 `analysis/l3-f4-logs/` 全部
归档 logcat，结果写入 `analysis/l3-f4-wbranch-recheck.json`：

- **6 条错误分支格式串在全部归档中 0 命中** —— 与 `§61.70 ③` 一致，
  再次确认 `§61.68` 两轮 0 命中是正确行为。
- `NMSlicesFile Write all finish` 仅在 `ksab-flac-remote.txt` 命中 **1 次**，
  正是 `§61.70 ③` 引用的 `W/NMCache` 一手证据 → 默认配置下 W 级确实可输出。
- `§61.68` 主日志 `vfile-commit-20261111-004310.txt`：代次 `1→22` 严格递增、
  `commit` 恒定 `16384`、`Finish Async:-103` 在场、`Write start play` 为 0；
  第二轮 `004603` 同样 `commit=16384`。与执行层细化的因果链一致。
- 13 项 checks 全 PASS；`so` 的 sha256 一并固化防漂移
  （`7a1adc95…87e5fde4a6c`，与 `§61.70` 登记一致）。
- 归档实扫 **34 份**（`§61.70 ③` 记为 33 份，差异来自 `§61.68` 之后
  新增的 logcat；按目录实数如实登记，不改写历史数字）。

## 4. 设备上线后怎么用（本批未执行）

```bash
# ① 用户重开无线调试 → adb pair <配对端点> <配对码> → adb connect <调试端点>
#    get-state 必须为 device；确认 app 是否已装（未装则手机文件管理点
#    /sdcard/Download/CloudMusic-debug.apk）。起点是冷缓存。
python tools/l3_f4_vfile_commit_probe.py --serial <新端点> --wait 60 \
    --out analysis/l3-f4-live-online.json
python tools/l3_f4_wbranch_recheck.py --log analysis/l3-f4-logs/<该次 logcat>
```

保持 **`g_level=0`**、播放中**主动 seek**（改 `except offset` / 重置代次 /
让 `offset` 回跳），按上表真实格式串扫描；`UnExpect New Offsetcommted`
出现即具备升级判据的素材。**不要**再用「level-5 关键词」或
`files/nmcache_log_level` 做 0/4/5 对照（`§61.70` 已证伪该开关）。
`--log` 模式输出 `live_verdict` 四种分支之一，且 `device_run` 恒为
`false` —— 本工具只离线判读已有 logcat，**不采集、不连 adb、不伪造真机复跑**。
判读结果默认写入 `analysis/l3-f4-wbranch-live-verdict.json`，与固化的回放
证据 `analysis/l3-f4-wbranch-recheck.json` **路径分离**（`§61.71 ⑥` 守护
断言回放证据保持 `mode=replay` / `single_log_verdict=null`），避免上线复跑
时把历史回放证据覆盖掉。

## 5. 诚实边界

- 真机复跑 **NOT RUN**：`adb devices` 为空列表，设备 `192.168.1.11:41703`
  自 `§61.69` 后完全离线；**未擅自切网络、未伪造设备在线**。
- `§61.69` 的 `SetLoggerCallback(INMLogger(), 5)` 接线**仍在 P0 源码中**；
  `§61.70` 建议回滚但**须用户拍板，本批未执行**，且 `§61.69` 守护当前
  强制校验接线存在，回滚时须同步调整守护。
- **F4 仍 BLOCKED**：`-103` 判据未通过、自动进曲 3 首 / seek / ENDED 仍
  NOT RUN、F3 14 格未改写、A3 仍 G09 PARTIAL、舱不冻结、指纹不回填。
  `all_pass` 只表示离线一致性核验通过，**不是 F4 验收通过**。
- 禁止把 F4 判为已解决、把 m4a 或全格式判为已支持、或声称存在
  「需开启的 level-5 开关」；§61.67~§61.70 历史批次正文与证据 JSON
  一字未改。
