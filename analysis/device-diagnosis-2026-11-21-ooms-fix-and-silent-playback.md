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

## 三、曲库真相纠正（00:32，从设备 Room 489 轨实证）

直接取设备 `cloudmusic.db` + WAL 查证，**推翻上文 2.1 的「seek 竞态」归因**：

| 曲目 id | 标题 | duration_ms | cue_start_ms | cue_end_ms | 日志 seek |
|---|---|---|---|---|---|
| c7507b1c… | 24楼 | 264813 | **995440** | 1260253 | `toMs=995440` ✅ 正是 cue 起点 |
| b9ae0b20… | 你说的对 | 317027 | **1426693** | 1743720 | `toMs=1426693` ✅ 正是 cue 起点 |
| 8b2a9c74… | 20？？ 长岛 黄昏 | 50146 | 0 | 50146 | 无 seek（整轨）→ 仍 `PARSING_CONTAINER_MALFORMED` |

**结论修正**：seek 落点是**正确的** CUE 分轨起点，不是 bug。`duration_ms=264813`（4.4min）
只是分轨**显示时长**，与字节长度无关（印证 2.3 的字段语义错配，但不影响 seek）。
真正失败是 **ApeExtractor 对 WebDAV APE 的解析**：
- `8b2a9c74`（整轨、无 seek、来源 `听说.ape`）同样 `PARSING_CONTAINER_MALFORMED`，
  且该曲 `sample_rate=0 / bit_depth=0`（元数据从未解出），文件名含 GBK 问号（§61.264/265 已登记）。
- 另一来源 `一整夜.ape` 的 24楼分轨曾 READY 成功（98ca token，HEAD=299845048、`MAC ` 头正常）。
- 即失败与「具体 APE 源文件 / 编码变体」相关，而非 seek 时序。

### 曾尝试并已撤销的错误修复（纪律记录）

基于被推翻的竞态理论，一度写了 pending-seek（duration=TIME_UNSET 挂起 seek 至 READY）
+ `Media3SeekDeferTest` 单测。曲库真相证明方向错误后，**已全部撤销，未留任何代码**。
不把未收敛的猜测写进引擎。

## 四、结论与待办

1. **§61.266 已真机 PASS**，可回填 v0.3 账本 / docs 09 §2（已提交 `9409903a`）。
2. **无声问题根因 = WebDAV APE 源文件兼容性**：
   - 同一路径下 `一整夜.ape` 能 READY，`听说.ape`（含 GBK 问号、元数据全 0）解析失败。
   - 非代码回归（§61.266 改的 `HttpDataSource` 只在代理回源路径，已 curl 实证供数正常；
     Media3 直接取流走 `DefaultHttpDataSource`，与我改动无关）。
   - 属**源文件级缺陷**（服务器文件名/编码）或自定义 `ApeExtractor` 对该 APE 变体的兼容性问题。
3. 尝试外部注入本地 `f3_ape.ape` 做对照，被 MIUI 后台限制 / 音频焦点拦下（不注入 UI 是铁律），
   无法全自动完成对照。
4. 下一步（需用户在场）：点播**非「听说」专辑**的一首（如 `85760ba7 逝年 / 夏小虎`，整轨 OK），
   - 出声 → 坐实是「听说.ape」坏文件，与代码无关，走数据修复（替换/重下源文件）；
   - 不出声 → WebDAV APE 链路通病，立专项查自定义 `ApeExtractor` 对该编码的兼容性。


## 五、决定性对照（00:37~00:39）——推翻第四节 APE 归因

用 `cmd media_session dispatch play` 触发播放（不注入 UI，绕开 MIUI INJECT_EVENTS 限制），
当前曲目是 **「逝年 / 夏小虎」= MP3**（`.mp3?sign=...`），**非 APE、非 CUE 分轨**：

```
prepare trackId=f43878dd ... url=http://192.168.1.1:15244/d/ecloud_self/.../夏小虎 - 逝年.mp3?sign=...
state=BUFFERING
Caused by: java.io.EOFException: 
 not found: size=0 content=...
   → HttpDataSourceException: unexpected end of stream on com.android.okhttp.Address...
   → DefaultHttpDataSource.open → engine_error=E_DS_UPSTREAM_MEDIA3_PLAYBACK → state=IDLE
```

**根因最终定案**：本地播放 WebDAV 曲目时，Media3 走 `DefaultHttpDataSource`
**直连 WebDAV 带签名直链**（`?sign=...`），服务端连接不稳定/签名直链提前断流 →
`EOFException: unexpected end of stream` → preparation 失败 → 无声。
- 与 APE 编码无关（MP3 同样失败）；
- 与 CUE 分轨 seek 无关（逝年是整轨、无 seek）；
- 与 §61.266 改动无关（Media3 直连，不经我改的 HttpDataSource）；
- `forceWebdavProxy` 配置只作用于 DLNA/投屏直链决策（`DirectLinkCapability`），
  **管不到本地 Media3 播放**，不是现成开关。

修复方向（需拍板，影响播放器编排层）：让本地 WebDAV 播放走 app 已有的本机回环代理
（`NanoHttpStreamProxy` / `proxy.publish`，回源 OkHttp 稳定），而非 Media3 直连签名直链。
该改动跨 PlayerController / 组合层，须专项 + A/B 反证 + 真机多曲目对照，不在本批仓促改。

### 关于用户报「闪退」（00:38）

复查：进程 PID 26804 **全程存活**，logcat 无 FATAL/SIGSEGV/OOM/tombstone、无 am_proc_died；
前台由 app 切到浏览器属用户手动切走，**不是进程崩溃**。真实症状是「点播无声→自动回 IDLE」，
非崩溃。


## 六、§61.268 真机复验尝试（00:51~00:54）——环境阻塞，非代码失败

设备端点变为 `192.168.1.14:39211`（IP 从 .10 换到 .14；全网段扫 39211 定位）。
装机新 APK（030536ed）后注入 STRM 签名直链验证修复：

- **修复机制生效**：日志确认 `prepare ... url=http://192.168.1.14:43497/t/<token>`——
  签名直链已被 `ProductionTrackSourceResolver` 转成**本机回环代理 URL**，不再直连。
- **但代理回源仍失败**：`ERROR_CODE_IO_BAD_HTTP_STATUS` / Response 404 → IDLE。
- **根因是环境，非代码**：
  - 库里签名直链**未过期**（PC `curl -r 0-15` → 206 + `ID3` MP3 头，正常）；
  - 设备能 `ping` 通 192.168.1.1，TCP 能连上 15244，但**请求发出后服务端立刻断开连接**
    （设备 `curl` → `HTTP:000`，`Connected to ... port 15244` 后 `Closing connection`，无响应）；
  - 即当前网络环境（设备新 IP .14）下 WebDAV/OpenList 服务对设备断连——
    这正是 `unexpected end of stream` 的**真正来源**，是服务端/网络层问题。
- **诚实边界**：§61.268 的"签名直链转代理"在本环境下无法完成出声复验（上游断连）。
  修复机制本身（转代理）已被日志证实生效；但"转代理能否彻底解决 EOF"**未能证实**——
  因为上游连接问题在代理回源路径上依然存在。
- **不宣称修复成功**：真机出声判据（`state=PLAYING` + AudioTrack 真实帧）未达成。
  待网络环境恢复（设备能稳定访问 WebDAV）后复验；若届时代理回源仍失败，
  需回头查代理回源对 WebDAV 鉴权/Digest 的处理，另立专项。
