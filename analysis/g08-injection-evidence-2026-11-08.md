# G08 定向故障注入 + 冷启动启动竞态 · 真机证据（2026-11-08）

> 承接 `docs/12 §61.26`（G01~G07 PASS / G08 运行中 / G09 PARTIAL）与 HEAD 提交
> `211080c9`（`61.26b` 取流韧性修复，提交说明「设备验证随 G08 完成后安装」——
> 即**修复代码已入库但尚未装到真机验证**）。本轮补上这段验证，并修掉验证过程中
> 暴露的冷启动播放指令丢失缺陷。

## 0. 证据分级与现场

| 级别 | 内容 |
|---|---|
| 【真机实测】 | Redmi K60 / `192.168.1.11:37763` / Android 13，13:11~13:19 的 `adb logcat -d -v time`、`dumpsys media_session`、`run-as` 目录核对；全部命令带 `-s` |
| 【本机实测】 | 注入代理 stdout、`./gradlew test :app:assembleDebug --offline`、`tools/check_source_contracts.py`、测试 XML 统计 |
| 【本目录一手材料】 | `RangeFetcher.kt` / `UrlDataRequest.kt` / `PlayerController.kt` / `AndroidManifest.xml` 当前源码与 `git show 09037327` 历史版本 |
| 【推断 / 存疑】 | G08 的「连续后台 2h」长稳、G09 的 START_STICKY 恢复、MainActivity `exported` 口径 —— 见 §5，**均未当结论用** |

现场保留：`adb reverse tcp:8765 tcp:8765` 已恢复、PC `python -m http.server 8765` 在运行、
Room 夹具（g01-local / g02-remote）保留、乐视电视未触碰、未切网络、未伪造授权。

## 1. 复现方法（可重跑）

1. `python analysis/g08-fail-once-proxy.py`（PC 监听 8766 → 转发 8765；
   **HEAD 全放行**，`resolveLength()` 长度探测必须先成功；**第一个 GET 不发响应、
   以 SO_LINGER(1,0) 发 RST** 模拟连接期瞬断，之后全放行）。
2. `adb reverse --remove tcp:8765 && adb reverse tcp:8765 tcp:8766`。
3. `am force-stop com.example.cloudmusic`；
   `run-as com.example.cloudmusic rm -rf cache/legacy_engine_cache`（清 App 内部引擎
   缓存，**强制回源**，否则 `Use Old` 磁盘命中不发网络请求，测不到取流韧性）。
4. `am start -n com.example.cloudmusic/.MainActivity`（解除 stopped state）+
   **一次** `am start-foreground-service -a com.example.cloudmusic.action.PLAY`。
5. 抓 50s logcat → `/tmp/cm-g08-inject.log`；代理 stdout 作为请求侧对账。
6. 收尾：`adb reverse --remove tcp:8765 && adb reverse tcp:8765 tcp:8765`，停代理。

## 2. 【真机实测】61.26b：连接期重试一次救回全量取流

PC 代理请求侧（时间戳与 logcat 同一时钟）：

```
13:17:18.965 HEAD get#0 -> PASS        （curl -I 预检，HEAD 不消耗 fail 配额）
13:17:29.433 HEAD get#0 -> PASS        （设备侧 curl 预检）
13:17:38.445 GET  Range: bytes=0-302591  get#1 -> FAIL_ONCE (RST)
13:17:38.467 GET  Range: bytes=0-302591  get#2 -> PASS      （22ms 后的重试）
13:18:16.661 GET  Range: bytes=0-95999   get#3 -> PASS      （g02 自动进曲回源）
```

设备侧 logcat（`CM-DataReq`，pid 16856）：

```
13:17:38.599 prepare trackId=g01-local url=.../cloudmusic_g01.mp3 expectedLength=302592
13:17:38.600 createDataRequestHandler cached=false -> NEW UrlDataRequest
13:17:38.613 Start id=1 offset=0 length=302592
13:17:38.614 open url=... Range=bytes=0-302591      ← 第 1 次（被 RST）
13:17:38.643 open url=... Range=bytes=0-302591      ← 第 2 次（§61.26b 连接期重试）
13:17:38.665 open result total=302592 → onBegin id=1 total=302592
13:17:38.702~38.710 onData ×37  → offset=294912 count=7680（尾 294912+7680=302592 ✓）
13:17:40.716 onEnd id=1 error=null（2s 可取消延迟）
13:17:38.740 CNCMAudioPlayer::Play status 4
13:17:38.787 AudioTrack(0): Write start play nErr:0
13:18:16.775 doPlayCompleted kPlayerPlaybackCompeted（37.8s 完整播放）
13:18:16.918 自动进曲 g02 → Play status 4 + AudioTrack(1): Write start play
```

**判据**：同一 `Start id=1` 对应**两条 `open url`**（修复前只有 1 条，失败即
`onEnd(error=...)` → native `-103` 整曲跳过）；第二次成功，302592B 全量送达、
`onEnd error=null`、37.8s 自然播完并自动进曲。全程**零 `-103`、零 `msg=100`、
零 `ETIMEDOUT`**。

→ **§61.26b 取流韧性定向验证 PASS**（这是提交 `211080c9` 缺失的设备验证补做）。

## 3. 【真机实测】新发现并修复：冷启动首次 ACTION_PLAY 被吞

装 `211080c9` 后的第一次冷启动（13:11:14，pid 28078），**只发一次** ACTION_PLAY：

```
13:11:14.120 prepare trackId=g01-local（缓存命中）
13:11:14.331 msg=200 arg1=702（prepare 真完成）
13:11:14.335 NMBufferSourceImp::Read(2) Finish Sync:0
13:11:14.342 NMBufferSourceImp::Read(3) Finish Sync:0
dumpsys media_session: state=PlaybackState {state=3, position=0, ...}
→ 之后 18s 无 msg=2000 / 无 Write start play / 无 Play status 4
```

再发**第二次** ACTION_PLAY（13:12:35）才 `Play status 4` + `Write start play` +
`msg=2000 0→5900ms`。即：**首次播放指令被丢弃，MediaSession 假报 PLAYING、进度恒 0**。

根因（【本目录一手材料】`PlayerController.kt` / `PlaybackService.kt`）：
`PlaybackService.onCreate` 异步 `restore()` 重建 Room 队列；`ACTION_PLAY` 到达时
`queue` 仍为空 → `resume()` 无 handle、`restartCurrentIfAny()` 的 index 不在
`queue.indices` → 指令静默丢弃；restore 随后按 PRD §6.1 **只 prepare 不播**。

修复（`§61.27`）：`pendingPlay: AtomicBoolean` 记住「用户明确发出的播放意图」
（只在 `queue.isEmpty()` 时置位），`restore()` 完成后 `compareAndSet(true,false)`
兑现一次 `resume()`。PRD §6.1「不自动开播」不受影响——无用户指令的 restore
仍保持 PAUSED（既有 T11 用例守住）。

**修复后单次 ACTION_PLAY 实测**（§2 同一轮，13:17:38）：`Play status 4` /
`Write start play` / `msg=2000 0→4324→4825` / 37.8s 完成 / 自动进曲，**零补发指令**。
新增单测 `pendingPlayBeforeRestore` PASSED。

## 4. 验证基线（【本机实测】）

| 检查 | 结果 |
|---|---|
| `./gradlew test :app:assembleDebug --offline` | BUILD SUCCESSFUL in 41s |
| 测试 XML | 399 测试 / 43 套件 / 0 失败 / 0 错误 / 0 跳过（新增 1 用例） |
| `check_source_contracts.py` | PASS，新增 4 组 §61.27 守护全部绿灯 |
| `pre_release_check.py --mode dev` | PASS 6/6 |
| `adb install -r` | Success（13:17 前，夹具保留） |
| 行尾 | 4 个改动 / 新增代码文件 CRLF=0；3 个源码 / 脚本文件真实 diff `+83`（文档回写另计），无行尾虚胖 |

## 5. 未收口项（诚实边界，不升级为结论）

- **G08「连续后台 2h」仍未跑满**：本轮是**定向故障注入**（单次 RST + 50s
  双曲循环），证明的是「连接期瞬断不再导致 -103 跳曲」这一根因修复，
  **不等价于 2h 长稳 PASS**。原 `§61.26` 记录的「4 次 -103 / 30+ 过渡」现象在
  本轮 2 次过渡中零复现。2h 监控需用户允许长时间占用真机后补跑。
- **G09 仍 PARTIAL**：MIUI + 无线 adb 下 `START_STICKY` 2.5min 未自动重启；
  需 USB / 亮屏环境复测。Room 恢复已由多轮冷启动验证。
- **Manifest 验收期调试形态本轮保留**：`§61.25` 写「验收后恢复 61.23 生产形态」
  （`PlaybackService exported=false` 且移除 ACTION_PLAY intent-filter）。因 G09
  未收口、复测仍需外部触发播放，**本轮不恢复**，避免堵死后续验证入口。
- **口径疑点登记（待用户拍板）**：`git show 09037327:app/.../AndroidManifest.xml`
  的「61.23 生产形态」把带 `MAIN/LAUNCHER` intent-filter 的 `MainActivity` 写成
  `android:exported="false"`。Android 12+ 下该组合会导致桌面无法解析启动入口
  （【推断】，依据 AOSP exported 规则；本机未对 61.23 版做过桌面启动实测）。
  若后续按字面恢复，需先真机确认：
  `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER com.example.cloudmusic`。
- 其它既有挂账未变：T-L L3 61 项素材全量替换 / `:ui-legacy`、L2 蓝牙 ACL 广播
  与 `BLUETOOTH_CONNECT` 运行时授权、`01 §6.1` 默认仅 WiFi 播放、性能基线、
  发布阻断 12 条、真实 DMR 矩阵 T20/T21、`ENGINE-FINGERPRINT` 回填。

## 6. 下一步（按依赖顺序）

1. USB / 亮屏环境复测 G09（START_STICKY），并补跑 G08 2h 长稳。
2. G09 收口后统一恢复 Manifest 生产形态（含 MainActivity exported 口径裁定），
   再做一次 `:app:assembleDebug` + 桌面启动实测。
3. 其后按 `16 §5.3` 推进 T-L L3 / L2 与 T-V 发布阻断项。

> AI生成
