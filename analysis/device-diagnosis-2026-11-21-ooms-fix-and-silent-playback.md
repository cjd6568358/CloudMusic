# §61.266 真机复核 + 播放失败归因（2026-11-21 00:22~00:28，端点 192.168.1.10:37385）

## 一、§61.266 OOM 修复 —— 真机判读：PASS

装机 `1c2bb326`（§61.266）后，用户点播 `20？？ 长岛 黄昏`（CUE 分轨 / 259MB APE）：

- 进程 PID 2021 **持续存活 5 分钟以上**（多次 `pidof` 均为 2021），未再闪退；
- logcat 全 buffer 关键字 `OutOfMemory|SIGSEGV|FATAL EXCEPTION|tombstone` 命中 **0**；
- PSS 三次采样 181MB → 212MB → 204MB → 208MB，平稳无持续增长（largeHeap 后正常量级）；
- 结论：**Java 堆整段读入导致的 OOM/SIGSEGV 已消除**。§61.266 真机判据成立。

## 二、"仍无法播放（无声）" —— 归因：与 §61.266 无关的独立缺陷链

### 2.1 铁证（CM-Media3 / 进程 PID 2021 日志）

真机播放主链路是 **Media3EngineAdapter**（B2 / ExoPlayer），不是 A 路线 native engine，
也不是 §61.266 改的 OkHttp HttpDataSource。关键日志序列：

```
prepare trackId=c7507b1c… remote=true url=http://192.168.1.10:38449/t/<token> expectedLength=264813
seek toMs=995440 durMs=unset                    ← CUE 分轨起点 995s，此刻时长未知
error=Source error code=ERROR_CODE_PARSING_CONTAINER_MALFORMED
Caused by: ParserException: Loading finished before preparation is complete. {contentIsMalformed=true, dataType=1}
engine_error=E_DS_UPSTREAM_MEDIA3_PLAYBACK → state=IDLE
```

用户连续点播 5 次（00:23:25 / 26 / 33 / 50 / 58），每次都是 CUE 分轨 + 大 offset seek
（995440 / 1426693 ms）；两次明确 `ERROR_CODE_PARSING_CONTAINER_MALFORMED`，其余停在
`BUFFERING` 或 READY 后**无 `state=PLAYING`**（AudioTrack 只写静音帧、MediaSession 停在 PAUSED）。

### 2.2 本机代理侧 —— 排除"代理供数错误"

`curl -r 0-511 http://192.168.1.10:38449/t/98ca…`（READY 那次 token）实测：

```
HTTP/1.1 206 Partial Content
Content-Range: bytes 0-511/299845048        ← 真实 APE 299MB，Range 正常
前 16 字节 4d 41 43 20 "MAC "               ← APE 文件头正确
```

代理 `NanoHttpStreamProxy.RangeInputStream → source.readAt(start+pos, buf, want)` 按需读，
不整段入堆，**不是 §61.266 的 OOM 路径**。MIME 报 `application/x-www-form-urlencoded`
（错），但 Media3 代码特意不设 mimeType、按内容探测，故非主因。

### 2.3 已确证的字段语义错配（非直接崩溃点，但需登记）

`PlayerController.kt:707` 把 `track.durationMs`（毫秒时长）传给
`EngineSource.expectedLength`（注释语义为**字节长度**，`EngineAdapter.resolveLength()` 拿它
与 HEAD `Content-Length` 比较）；同时 `FakeEngine.duration = source.expectedLength` 又把该
字段当播放时长用。两套语义混用。`expectedLength=264813` 出现在日志里只是打印，
Media3 的 prepare 不使用它，故**不是本次崩溃的直接触发**，但属长期隐患。

## 三、结论与待办（不擅自改引擎时序）

1. **§61.266 已真机 PASS**，可回填 v0.3 账本 / docs 09 §2。
2. **无声问题**是 CUE 分轨 + Media3 大 offset seek 的独立缺陷：
   `PlayerController.prepareAt` 在 `engine.prepare()` 后立即 `engine.seek(cueStartMs)`，
   此刻 `duration=TIME_UNSET`，Media3 在 preparation 完成前收到大跨度 seek →
   容器解析失败 / 停在 BUFFERING。候选修复 = pending-seek（READY 后再落点）。
   但日志存在反例（unset 下 seek=1426693 仍有一次 READY 成功），真实失败模式未完全收敛，
   **按纪律不仓促改引擎时序**（需 A/B 反证 + 回归 + 多轮真机对照）。
3. 下一步建议：用户拍板是否立 §61.267 专项，按「pending-seek + A/B 反证 + 真机多轨对照」推进；
   或先取一个**整轨（非 CUE 分轨）**曲目点播作对照，确认整轨能出声即坐实分轨 seek 是唯一变量。
