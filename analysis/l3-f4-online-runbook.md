# F4 · 设备上线一键执行清单（12 §61.58；现行执行链见 12 §61.72）

## ★ 现行上线执行链（`12 §61.72`，2026-11-11；**优先于下文 §0~§6**）

> **为什么有这一节**：本 runbook 的 §0~§6 是 `§61.58/§61.59` 写下的
> kind 槽 A/B 剧本，**该 A/B 早已执行完毕并裁决**（环节 B 部分成立、非充分
> 原因，`F4 仍 BLOCKED`）。但顶部 `§61.70 补充执行层细化` 与 `§61.71 ④`
> 已把上线判据改成「保持 `g_level=0` + 主动 seek + 探针抓 logcat + 用真实
> `.rodata` 格式串扫 W 级日志」。同一份文档里出现两条互相冲突的命令，
> 设备一上线照 §1 跑会重跑已完成实验、临时改 P0、浪费冷缓存窗口。
> **本节是现行唯一执行口径；下文 §0~§6 标注为历史剧本，勿执行。**

**① 用户侧前提（缺一不可，助手不擅自代办）**：`§61.69` 的
`SetLoggerCallback(INMLogger(), 5)` 接线**建议回滚、待用户拍板**（本批未
执行，回滚须同步调整 §61.69 / §61.71 守护）；重开无线调试给新的**配对端点
+ 一次性配对码（不落盘）** + 调试端点，`adb pair` → `adb connect` →
`adb -s <新端点> get-state` 必须为 `device`；确认 app 是否已装（未装则
手机「文件管理」点 `/sdcard/Download/CloudMusic-debug.apk`）。卸载已清空
`legacy_engine_cache` → 起点是**冷缓存**，与 §61.68 暖缓存条件不同。

**② 现行一键命令（两步判读，不是 A/B）**：

```bash
cd D:/workspace/CloudMusic
python tools/l3_f4_online_readiness.py          # 现行执行链自检，应 all_pass=True
python tools/l3_f4_vfile_commit_probe.py --serial <新端点> --wait 60 \
    --out analysis/l3-f4-live-online.json
# 探针把全量 logcat 落盘到 analysis/l3-f4-logs/vfile-commit-<stamp>.txt，
# 结果 JSON 的 log_file 字段就是下一步 --log 的输入，不要手工猜文件名：
python -c "import json;print(json.load(open('analysis/l3-f4-live-online.json',encoding='utf-8'))['log_file'])"
python tools/l3_f4_wbranch_recheck.py --log <上一步输出的 logcat 路径>
```

`l3_f4_wbranch_recheck.py --log` 的判读结果写到
`analysis/l3-f4-wbranch-live-verdict.json`，**不覆盖**固化的回放证据
`analysis/l3-f4-wbranch-recheck.json`；该工具纯离线、不连 adb、
只判读已有 logcat（`device_run=false`）。

**③ 「主动 seek」怎么落（本批补齐的执行缺口）**：现行探针
`l3_f4_vfile_commit_probe.py` **没有 seek 能力**（源码里没有 `input swipe`
或 `onSeek` 调用），而 `§61.70` 执行层细化要求「播放中主动 seek 改
`except offset` / 重置代次 / 让 `offset` 回跳」才能尝试触发
`UnExpect New Offsetcommted`。因此这一环**须用户在手机播放页手动拖动
进度条（物理在场）**，助手不得伪造该能力；MIUI 无线 adb 下
`input swipe` 可能被拒，属已知限制。seek 判据在真机执行前如实 **NOT RUN**。

**④ 判读（出现 W 分支日志 ≠ F4 修复）**：探针给出
`mechanism_verdict`（commit 是否随等待增长 / 是否追上 605007）；判读工具
给出 4 种 `live_verdict` 之一 —— `GENERATION_MISMATCH_OBSERVED` /
`FILE_LEN_GATE_OBSERVED` / `NORMAL_PATH_ONLY_W_CONFIRMED` /
`NO_W_BRANCH_TRIGGERED_EFFECTIVE_NEGATIVE`。6 条错误分支格式串在正常顺序
写入下 0 命中是**有效负结果**；只有 W 级分支日志真正出现，才具备把
「代次错配 → commit 冻结」由推断升级为一手实证的素材，**仍须人工复核，
不自动收口**。同时统计 I 级 `MakeRangeRequestAsync(N)` / `WaitRequest
commit` 代次序列与 `-103` / `Write start play`。

**⑤ 保持的门控口径**：**保持 `g_level=0`**（默认 I/D/W/E 已全开），
**不要再用**「level-5 关键词」或 `files/nmcache_log_level` 做 0/4/5 对照
（`§61.70` 已证伪该开关）。真机复跑 **NOT RUN**、设备**完全离线**、
接线未回滚；`l3_f4_online_readiness.py` 的 `all_pass` 只表示离线执行链
口径与资产自洽，**不是设备在线证明、不是 F4 验收通过**。
**F4 仍 BLOCKED**；真机复跑后仍需 F4 验收 `17 §8.3` → F5 → P2→P3→P4。

**⑥ 已执行结果（`12 §61.73`，2026-11-11 11:00~11:20）**：按本节
①~④ 在 `10.0.0.2:37509` 完成**三轮 60s 真机纯观测**（冷缓存 /
暖缓存 / 用户拖动），每轮 probe → 读 `log_file` → `--log` 判读。
app 原本未装 → 装 §61.69 接线**之前**的基线 APK（`g_level=0`，
`SetLoggerCallback` 三轮 0 次，P0 源码未改）。结果：commit 恒定
253952 / 262144 / 262144、代次 1→6 / 1→6 / 1→4、W 级行 3 / 1 / 0，
**7 条真实 `.rodata` 格式串三轮全 0 命中（有效负结果）**，
`Open codec for aac` 仅冷 / 暖轮、`Write start play` 三轮皆 0、
`-103` 冷 / 暖各 1 次 → **F4 主判据未通过**。
用户口述已拖动且「进度条没有时间」：`10.0.0.2:39257` 重新上线后
`screencap` 抓到播放页 `analysis/l3-f4-live-ui-20261111.png` ——
进度条两端 `00:00`、播放按钮 ▶ 未播放；第三轮 logcat 无 `mDuration` /
无 `Open codec` / 仅 `Play status 3`，`onSeek` / `seekTo` / `Slider`
全 0 → **播放未进入可 seek 态，拖动未转化为有效 seek，seek 判据
仍 NOT RUN**（原因不是用户未配合）。`adb shell input swipe` 实测被
MIUI `SecurityException: INJECT_EVENTS` 拒绝。证据与 19 项审计见
`analysis/l3-f4-live-rounds-{audit.json,report.md}`。
**下一步更靠前的问题**：先让播放进入可 seek 态（总时长 00:00 /
无 `Write start play`），再谈触发 W 分支。

**⑦ 判据口径修正（`12 §61.74`，2026-11-11 纯离线反汇编）**：
`libncmaudioplayer.so` 的 `.rodata @0xe180a` =
`AudioTrack(%d): Write start play nErr:%d`，唯一打印点
`0x66f00` 前有 **4 道静默早退门控**（`[this+0x1e8]` 两次判空、
`[this+0x20c]` 非零跳过、`bl 0x13d80` 返回 3 跳过）→
**`Write start play` 0 次不是「没有写 AudioTrack」的充分证据**。
**绝不能反向推断播放成功**：`MediaSession PAUSED` /
`metadata:null` / 无本 app AudioTrack / `-103` 是独立一手证据，
§61.73「播放未出声」仍成立。→ **上线复跑后判读不得单看该日志点**，
应并用 `MediaSession state=3(PLAYING)` + 本 app AudioTrack
`state:started` + 自动进曲 3 首无 `-103` + seek / ENDED。
证据 `analysis/l3-f4-play-gate-{disasm.json,report.md}`（16 项 checks
全 PASS）+ `check_source_contracts.py §61.74` 守护。结论性质为
**inference**，未做动态插桩，不臆断本次命中哪道门控。

**⑧ 真机取证完结（`12 §61.75`，2026-11-11 13:50）—— 口径纠错**：
**本 runbook 不再是「等设备上线再跑」的待办清单**。用户指正成立：
设备自 11:00 起在线，真机验证已完成，此前把「F4 主判据未通过」
与「上线后复跑」并列是错误口径。终局取证：新端点
`10.0.0.2:37163`，用 `cmd media_session dispatch`（绕过 MIUI 对
`input` 的 `INJECT_EVENTS` 限制）执行 `play` / `next`×3 /
`fast-forward` / `rewind`，6 次 rc=0、3895 行 logcat + 4 次快照。
**MediaSession 曾 `state=3(PLAYING)` 但 `position` 恒 0、
`metadata:null`、无本 app AudioTrack `state:started`、
`Write start play` 0 次、`-103` 1 次** → 状态机上报 ≠ 音频输出。
`tools/l3_f4_acceptance_final_audit.py` 按 `17 §8.3` 逐条定案：
**主判据无一 PASS**（FAIL×6 / PARTIAL×2 / NOT RUN×1），
F4 仍 BLOCKED。证据 `analysis/l3-f4-acceptance-final-
{json,report.md}` + `analysis/l3-f4-playback-control.{json,report.md}`
+ `check_source_contracts.py §61.75` 守护。
**真机取证到此完结，后续是 F4 修复与回归验证，不再以「设备上线」
作为验证前置**；`§61.69` 接线回滚属源码决策、非真机验证。

**⑨ 离线定位（`12 §61.76`，2026-11-11 14:20，纯反汇编、不需要设备）**：
把 `-103` 与门控状态位的上游链补齐。`libNMCommonCache.so` 中
`-103` 是**硬编码立即数** `mov w25, #-0x67`（`0xa85c`/`0xa9c4`），
写入 `[+0x1a0]`；写入 B 仅在**挂起异步读 `[+0x198]` 非零**时发生
（`0xa9d0/cbz`）。`Finish Async` 回调经 `0x9fc0 bl 0x16468`
写线程队列投递，`0xa008` 读出 `+0x1a0`、清零 `+0x188/190/198`、
`0xa040` 打印后 **`0xa064 mov w0,w20` 把 -103 原样返回播放侧**。
播放侧 `[+0x20c]` = **paused 标志**（`.rodata @0xe190a` =
`AudioTrack(%d): Paused`，置位 `0x657d4`/`0x67478`、清零
`0x65198`/`0x67314`）；`[+0x1e8]` = AudioTrack 对象指针
（设置 `0x572d4`/`0x583bc`、释放清零 `0x66810` 等 5 处）
→ 对应 §61.74 门控 1/2/3。**结论是 inference**，不断定本次命中
哪道门控；**F4 最靠前的修复点前移**到「读侧为什么留下挂起异步读
并写入 -103」（`+0x198` 写入点 + `result:-2` 传递链）。
证据 `analysis/l3-f4-minus103-chain.{json,report.md}`
（25 项 checks 全 PASS）+ `check_source_contracts.py §61.76` 守护。
F4 仍 BLOCKED。

**⑩ 读侧空白链（`12 §61.77`，2026-11-11 14:40，纯离线）**：承接
⑨ 前移的修复点，把「代次错配 → commit 冻结」与「-103 返回播放侧」
之间的空白补齐。`libNMCommonCache.so` `.rodata @0x3bee8` =
`NMBufferSourceImp(...ReadFromFileNoLock ... result:%d`，
**实扫唯一引用** `0xc454/0xc45c`；`0xc434 blr [vtable+0x28]`
返回值即 `result`。**`0xc6f8 bl 0x10198`（WaitRequest）返回 1 →
`0xc728 mov w0,#-2` 就是 `result:-2` 的产生点**（返回 2 → -0x6a、
其它 → -0x67；返回 0 走成功回读）。`0xa9bc mov w25,#-2` 与
`0xa9c4 mov w25,#-0x67` 是两条硬编码分支，共用
`0xa9e4 str w25,[+0x1a0]`，且仅在**挂起异步读 `[+0x198]` 非零**
时写入（`0xa9d0/cbz`）；`[+0x198]` 登记/清零共 6 处
（`0x9f44`、`0x10b20`、`0xa018`、`0x100c8`、`0x106c0`、`0x1095c`）。
完整链：代次错配 `0x10798` → commit 冻结 `0x10888` →
`result:-2` → 挂起异步读 → -2/-103 写 `+0x1a0` → §61.76
`Finish Async` 返回 -103 → §61.74 门控静默早退 → 无音频输出。
**inference**，不断定本次命中哪条分支。三处修复锚点
（`0x10798` / `0xc728` / `0x12dc8`）均在 native 内，
与 §61.59「Java 侧修不了」一致，**真要修复需用户拍板技术路线**
（改 native / 绕过取流 / 换源）。证据
`analysis/l3-f4-read-gap-chain.{json,report.md}`（31 项 checks
全 PASS）+ `check_source_contracts.py §61.77` 守护。
真机取证已完结、本批不需要设备；F4 仍 BLOCKED。

**⑪ A1「绕过取流」路线（`12 §61.78`，2026-11-11 15:55，纯离线）**：
承接 `§61.77` 三处 native 锚点待拍板 + 同日《播放内核两条路线评估》
A1 时间盒建议。`§61.61` 四格矩阵里**同一份自洽 fMP4**
`f3_m4a_complete.m4a`：LOCAL 格 `url=null` → `XorLocalSource::Open(fd)`
→ `Open codec for aac` / `Write start play nErr:0` / 无 `WaitRequest`；
REMOTE 格 `commit : 368640` 冻结 / `result:-2` / 无 codec →
**缺陷隔离在 REMOTE 取流层**。F3 14 格 LOCAL 6 PASS / REMOTE 5 PASS，
7 份 LOCAL logcat 全无 `Start id=` / `WaitRequest` / `NMSliceFile` /
`commit :`；14 格 seek / ENDED 全 false → **主判据在 LOCAL 从未跑过**。
源码装配 `source.url != null` → `remote ? Type.REMOTE : Type.LOCAL`，
切 LOCAL 零 P0 / 零 native 改动。**裁定：A1 绕行成立、不是修复**，
REMOTE 缺陷仍在、`§61.75` 终局裁决不变，**F4 仍 BLOCKED**。
`tools/l3_f4_bypass_stream_audit.py`（**61 项 checks 全 PASS**）+
`analysis/l3-f4-bypass-stream-audit.{json,report.md}` +
`check_source_contracts.py §61.78` 守护。
**A1 时间盒实验 NOT RUN、需用户拍板**（不把「继续」当路线授权）：
前提 = 拍板 A1 与「下载后播放」语义 + 设备在线 + 接线回滚先定；
步骤 = push `f3_m4a_complete.m4a` → `run-as` 写入 app 私有
`files/music/` → `ACTION_PLAY + EXTRA_LOCAL_PATH` → 60s logcat +
`dumpsys media_session/audio` → 按 §61.74/§61.75 多信号判据
（`Write start play nErr:0` + 本 app AudioTrack `state:started` +
MediaSession `state=3` 且 position 递增 + 自动进曲 3 首无 `-103` +
seek / ENDED）→ 同一样本 REMOTE 轮对照 → `finally` 恢复现场。
通过继续 A（F4 验收 `17 §8.3` → F5）；**不通过立即转 B2、不追加
A2 二进制补丁**。**不动解码器**（根因在取流层，与解码器正交）。

**⑫ A1 时间盒已执行与裁决（`12 §61.79`，2026-11-11 16:45，用户授权「跑 A1 时间盒，接线回滚一起做」）**：
上列 ⑪ 的 **A1 时间盒实验 NOT_RUN 状态已解除**，本节为执行结果与后续状态，
⑪ 保留为 `§61.78` 当时的计划口径（历史正文不改）。

- **现场**：端点 `10.0.0.2:37163`（Redmi K60 / Android 13 / arm64-v8a），
  基线 APK 不含 `§61.69` 接线；样本 `f3_m4a_complete.m4a`（16,573,387 B）
  经 `§61.47` 零权限通道写入 app 私有 `files/music/`。首轮 v1 4 处取证缺陷
  仅作留痕（`analysis/l3-f4-a1-bypass-live-initial-v1.json`，**不作裁决依据**）；
  v2 修正（先 `logcat -c` / 按本轮 PID 分段 / app uid=`10316` 关联
  `dumpsys audio` / REMOTE 轮前清理私有样本 / t=2s 早快照 + AudioTrack 一手信号）
  重跑得干净同窗对照，`restore_errors=[]`。
- **LOCAL 主轮**：`remote=false` ×2、REMOTE 取流特征 0、LOCAL 装配 8 →
  完整避开 `NMCommonCache` 代次门控 / commit / `result:-2` / -103 链；
  `Open codec for aac` ×2 / `AudioTrack Play(1)` ×2 / `STATE_ACTIVE` ×2 /
  `Write start play nErr:0` ×2 / `Play status 4` ×2 / **`-103` 0 次**
  → **解码链点火成立**。但 `frames delivered=[1,1]` 每次仅 1 帧、约 350ms 后
  `msg=2` → `Paused` → `Stop` → `Release End`，MediaSession 恒 `state=2` /
  position 恒 0、7 次快照无本 app `state:started` 轨 → **无持续播放**。
  `msg=2` 是过早完成通知 ≠ 自然播完 ENDED；`frames=1` 不计持续出声。
- **REMOTE 对照轮**：`remote=true` / `Start id=5` / `WaitRequest=5` /
  `result:-2=4` / `commit :=1` / codec·write·AudioTrack 全 0 /
  `Play status 3` / `state=3` 但 position 0 → 与 `§61.61/§61.75` 一致。
- **裁决**：`timebox_passed=False`（`Write start play` ✅ / 本 app AudioTrack
  `state:started` ❌ / 自动进曲 3 首 ❌）→
  `A1_BYPASS_STREAM_LIVE_PARTIAL__IGNITION_ONLY__NO_SUSTAINED_PLAYBACK__TIMEBOX_NOT_PASSED`，
  建议 **`B2_PENDING_USER_DECISION`**（不追加 A2 二进制补丁、
  **路线切换仍需用户拍板**）。**「绕行 ≠ 修复」**，REMOTE 缺陷仍在、
  `§61.75` 终局裁决不变、**F4 仍 BLOCKED**；暴露「点火后不能持续」这一
  播放链下游新的独立问题。
- **同批接线回滚**：按 `§61.70` 勘误从 `EngineAdapter.kt` 删除 `§61.69`
  level-5 诊断接线（只删接线、功能代码零改动、历史接线审计 JSON 冻结不覆盖），
  `check_source_contracts.py` 8 处旧断言改 `rollback_ok` + 新增 `§61.79` 守护。
- **取证 / 裁决链**：`tools/l3_f4_a1_bypass_live_probe.py`（v2 真机）→
  `analysis/l3-f4-a1-bypass-live.json` + `l3-f4-logs/a1-bypass-{local,remote}-20261111-164027.txt`；
  `tools/l3_f4_a1_bypass_verdict.py`（纯离线）→ `l3-f4-a1-bypass-verdict.{json,report.md}`；
  `tools/l3_f4_wiring_rollback_audit.py` → `l3-f4-wiring-rollback-audit.{json,report.md}`。
- **下一步（需用户拍板）**：① 路线切换 B2（A1 时间盒已判不通过 → 停止 A、
  不追加 A2 二进制补丁）；② 可选确认「下载后播放」产品语义。
  本批不擅自启动 B2、不动解码器、不重编译 / 不重装、未 `pm grant`。


**⑬ B2 路线切换已落地（`12 §61.80`，2026-11-11 17:44 用户拍板「切换 B2」）**：
上列 ⑫ 的「下一步（需用户拍板）① 路线切换 B2」**已执行**，本节登记决策结果与后续状态，
⑫ 保留为 `§61.79` 当时的裁决口径（历史正文不改）。
- **A 路线停止**：不再投入 `§61.77` 三处 native 锚点，不追加 A2 二进制补丁；
  `§61.75` 真机取证档案转为**历史证据**，一字不改。
- **B2 成为现行技术路线**：Media3/ExoPlayer `1.11.1` + 手动构建 FFmpeg 软解扩展 +
  自研缓存/Range，落点 `:engine-opensource` 逃生舱、`IMusicEngine` 门面不变。
  `analysis/l3-b2-media3-probe.json` 实证 5 制品全 HTTP 200 / 无 `media3-ffmpeg` 制品。
- **本批零生产代码改动、B2 实施尚未开始**：零新增依赖、零模块启用、未换绑、
  未重编译 / 未重装、so 原样、不动解码器。**路线切换 ≠ 修复**，**F4 仍 BLOCKED**。
- 施工单 `analysis/l3-b2-implementation-plan-2026-11-11.md`（M0~M5）、审计工具
  `tools/l3_b2_route_switch_audit.py`、`check_source_contracts.py §61.80` 守护。
- **下一步（需用户确认 6 项后开 M1）**：音效降级范围 / 7 种 NO_SAMPLE / R7 LGPL /
  「下载后播放」语义 / 是否允许 `media3-session` / 18 个 so 移除时机。
  本 runbook 的 §1「一键执行」A/B 剧本属 `§61.58/§61.59` 历史口径，**不要再跑**。

---

> **下文 §0~§6 的性质（`12 §61.72` 标注）**：`§61.58/§61.59` 的 kind 槽
> A/B 剧本，**已执行完毕、已裁决**，仅作历史与恢复流程参考。
> **不要按 §1 再跑 `l3_f4_kind_slot_ab.py --apply`**；`§61.58` 的
> `analysis/l3-f4-preflight.json` 是历史固化证据，本批零改写、不重跑。
> **§6 结尾的「`HEAD` 仍 `8c9d79b1` / 二十一批未提交」是当时快照**，
> 现行 `HEAD` 以 `git log -1` 为准（`a619d34f`）。

---

> **用途（历史，§61.58）**：设备离线已持续数小时，F4 的离线准备（证据链 / A/B 脚本 /
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
> **④ 的执行工具（`12 §61.71`，2026-11-11 补）**：探针脚本本身只解析
> commit 时序、没有 W 分支判读；跑完探针后必须再跑
> `python tools/l3_f4_wbranch_recheck.py --log <该次 logcat>` 完成真实
> `.rodata` 格式串扫描 + I 级代次 / `commit` 序列统计。该工具纯离线、
> 不连 adb、只判读已有 logcat（`device_run=false`），输出 `live_verdict`
> 到 `analysis/l3-f4-wbranch-live-verdict.json`；**默认不覆盖**固化的回放
> 证据 `analysis/l3-f4-wbranch-recheck.json`（`§61.71 ⑥` 守护断言
> `mode=replay` / `single_log_verdict=null`）。离线回放 34 份归档已实跑
> 验证（6 条错误分支格式串 0 命中 / `Write all finish` 1 次 /
> 13 项 checks 全 PASS）。报告 `analysis/l3-f4-wbranch-recheck-report.md`。
> ⑤ 注意：卸载已清空 `legacy_engine_cache`，本次起点是**冷缓存**，
> 与 §61.68 暖缓存条件不同；`ACTION_PLAY` 直接 `playUrl`，不需恢复 Room 夹具。

---

## 0. 上线前置（30 秒）（历史剧本，勿执行 —— §61.58/§61.59）

> **§61.72 勘误**：下面是 §61.58 的 A/B 自检，其 `ready_to_run` 在 §61.59
> 后**恒为 false**，`one_liner` 仍指向已完成的 A/B；**不会**输出 READY，
> 也不应据此再跑 `--apply`。本轮上线自检请用
> `python tools/l3_f4_online_readiness.py`（见本文件顶部现行执行链）。

```bash
cd D:/workspace/CloudMusic
python tools/l3_f4_preflight.py          # 历史 A/B 自检：all_pass=True / ready_to_run=False
```

设备重新上线后端口会变，先拿新端点（用户给）：

```bash
tools/bin/adb.exe connect <ip:port>
tools/bin/adb.exe -s <ip:port> get-state   # 必须是 device
```

`preflight` 会汇总检查：adb 二进制、基线 APK、5 个样本、`8765` 端口、
runner 9 个符号、10 批守护 JSON、契约自检、P0 `git diff` 空、编译期预检证据。

## 1. 一键执行（唯一命令）（历史剧本，勿执行 —— §61.58/§61.59）

> **§61.72 警示**：本节命令**不要执行**。它是 §61.59 已跑完（25 s / 45 s
> 两次 + 基线对照）并已裁决的 kind 槽 A/B；现行唯一命令见文件顶部
> 「★ 现行上线执行链（`12 §61.72`）」②。保留本节只为历史与恢复流程可追溯。

```bash
python tools/l3_f4_kind_slot_ab.py --serial <ip:port> --apply
```

脚本自动完成：备份 `UrlDataRequest.kt` → 应用变体 B（`OnDataRequestEnd` 首参
恒传 `NATIVE_DONE_KIND_FLUSH = 1L`）→ `gradlew.bat :app:assembleDebug --offline`
→ `adb install -r` → 主判据 `f3_m4a_fast.m4a × REMOTE`（25s）+
回归 5 格（`mp3` local/remote、`wma`/`flac`/`wav` remote）→
**`try/finally` 无条件恢复 P0 基线、重编译、重装**，并恢复
`wifiOnly` / `adb reverse`。

## 2. 判读三分支（历史 A/B 判读，勿用于本轮）

> **§61.72 警示**：下表是 A/B 变体 B 的旧三分法，本轮判读请用文件顶部
> §61.72 ④ 的 `live_verdict` + `mechanism_verdict` 组合。

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
  `§61.38`~`§61.58` 二十一批改动均未提交、未 push，`HEAD` 仍 `8c9d79b1`
  （**当时快照**；`12 §61.72` 勘误：现行 `HEAD` 以 `git log -1` 为准，
  现为 `a619d34f`，§61.31~§61.65 已随 `c26e7cb8` 提交并 push）。
- **本轮补充（`§61.72`）**：现行探针的「主动 seek」需用户**手动拖动**
  进度条（**物理在场**），脚本无此能力，seek 判据真机执行前仍 NOT RUN；
  `--log` 的输入来自探针结果 JSON 的 `log_file` 字段，不猜文件名。
**⑭ B2 M1 骨架接入（`12 §61.81`，2026-11-11 18:19 用户「授权开工 M1」）**：
本批**纯离线工程改动、未连 adb、未重编译重装**，不产生新真机证据，`§61.75` 真机取证终局结论不变。
交付：`androidx.media3 1.11.1` 三制品、第 20 模块 `:engine-opensource`、
`Media3EngineAdapter` 14 方法骨架、`EngineModule` 单点换绑（保留 `:engine-legacy` 回退）、
`tools/l3_b2_m1_audit.py`（39 项 checks）+ `check_source_contracts.py §61.81` 守护。
**Gradle 离线实跑** `:engine-opensource:compileDebugKotlin --offline` → `BUILD FAILED` / `EXIT 1`，
3 个 media3 制品无离线缓存卡在 `debugCompileClasspath` 依赖解析，Kotlin 源码编译尚未验证、
如实标注未编译验证（日志 `analysis/l3-b2-m1-gradle-offline-build.log`）；
47 套件 / 430 测试零回归未验证，不伪造 `BUILD SUCCESSFUL`。
**M1 骨架 ≠ B2 完成**、**F4 仍 BLOCKED**、A3 仍 G09 PARTIAL、舱不冻结；
Range / 音效 / FFmpeg 扩展 / gapless·seek·ENDED 均按 M2~M4 挂账。
正式断点 `12 §61.80 → §61.81`。

**⑮ B2 M2 前置取证（`12 §61.82`，2026-11-11 21:01 用户选「保持离线，做 M2 前置」）**：
本批**纯离线、未连 adb / 未连真机、未联网、未重编译重装**，不产生新真机证据，
`§61.75` 真机取证终局结论不变。交付：`analysis/l3-b2-m2-preflight-2026-11-11.md`、
`tools/l3_b2_m2_preflight_audit.py`（50 项 checks）+ 证据、`check_source_contracts.py §61.82` 守护。
**本机 4 项刚性缺口全缺**（NDK / CMake / FFmpeg 源码 / Media3 FFmpeg 扩展源码），
Google Maven 无 `media3-ffmpeg` 制品 → 纯离线条件下交叉编译不可执行；
7 种 NO_SAMPLE 未补、F3 14 格重跑 **NOT RUN**、主动 seek 仍 USB / 物理在场阻塞，
`§61.81` 的 Kotlin 编译与 test 回归尚未验证。**M2 前置取证 ≠ M2 完成**、
**F4 仍 BLOCKED**、`§61.75` 终局裁决不变、A3 仍 G09 PARTIAL、舱不冻结。
正式断点 `12 §61.81 → §61.82`。

**⑯ B2 M2 前置补证（`12 §61.83`，2026-11-11 用户「继续」→ 保持离线）**：
本批**纯离线、未连 adb / 未连真机、未联网、未重编译重装**，不产生新真机证据，
`§61.75` 真机取证终局结论不变。承接 §61.82 的「9 个格式 decoder 编入未确认」缺口，
用纯 stdlib 手写 ELF 解析器读 `libffmpeg.so` 的 `.dynsym`（18 项 checks 全 PASS），
隔离 venv `pyelftools 0.33` 独立互证 `all_match=True`；证据等级由「字符串痕迹」
提升到「ELF 导出符号」。两 ABI 各 **100 decoder / 75 demuxer** 集合一致；
9 项存疑格式收口为 8 `NO_SYMBOL` + 1（`dsf`）`DEMUXER_SYMBOL_ONLY`。
**符号表补证 ≠ M2 完成**、**导出符号存在 ≠ decoder 可用 ≠ 真机可播**；
F3 14 格重跑仍 NOT RUN、M2 门禁未通过、**F4 仍 BLOCKED**、`§61.75` 终局裁决不变、
A3 仍 G09 PARTIAL、舱不冻结。正式断点 `12 §61.82 → §61.83`（非 M2 阶段推进）。


> AI生成
