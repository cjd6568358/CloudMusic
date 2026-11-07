# 第四十一批 · 真机 A3 深入：native 引擎首次跑通（2026-11-07）

> 设备：Redmi K60 `23013RK75C/mondrian`，Android 13 / **arm64-v8a**，`adb 192.168.1.11:39525`。
> 完整断点：`docs/12 §50`。本轮不改 ⑦ / C4 / 权限决策口径。

## 结论速读

**A3 从 `PARTIAL RUN` 推进到 `DEEP RUN`**：6 个 so 加载链 → 真类 `FindClass` →
`PlaybackEngine` 构造 → `setDataSource` 字段/回调校验 → `prepareAsync` →
音频焦点 GAIN → `Play`，**全部在真机跑通且零崩溃**。

**但装手机仍放不出真实声音**：`audio_flinger` 0 个 active track、播放期本地音源服务零请求。
G01~G09 仍 NOT RUN、舱不冻结、`engine_compat_test` 不回填。硬门槛已精确收敛为
**`DataProvider` 取流回调链 + 真机出声**。

## 1. 设备侧限制与四条绕道

| 限制（MIUI 无线 adb） | 绕道 | 实证 |
|---|---|---|
| `pm grant` 报 `Neither user 2000 nor current process has GRANT_RUNTIME_PERMISSIONS` | `cmd appops set com.example.cloudmusic android:read_media_audio allow` | `ignore → allow`；**运行时权限 `granted` 仍 false，未伪造授权** |
| `install -r -g` 报 `INSTALL_GRANT_RUNTIME_PERMISSIONS` | 同上；不依赖动态授权 | 三项运行时权限保持 `granted=false` |
| `input keyevent` 报 `INJECT_EVENTS` 缺失 | `am broadcast -a android.intent.action.MEDIA_BUTTON -n com.example.cloudmusic/.MediaButtonEventReceiver` | 广播送达并触发播放、焦点请求 |
| PC `192.168.23.78` 与设备 `192.168.1.11` 不同网段 | `adb reverse tcp:8765 tcp:8765` + PC `python -m http.server 8765` | 设备 `curl http://127.0.0.1:8765/cloudmusic_g01.mp3` → `200 37824` |

另：DataStore `storages` 为空、曲库 0 首，用 `run-as` 拉出 Room 库直接灌
`tracks / queue_items / playback_state` 各 1 行，`source_url` 指向回环音源。
**这是测试夹具，不是应用自身扫描**；DB 回读 1/1/1 且冷启动未被 Room 重建清掉。
Git Bash 传设备路径一律 `MSYS_NO_PATHCONV=1`。

## 2. 真机暴露并修复的 5 个生产侧 P0（静态核对看不到）

| # | 缺口 | 修复 |
|---|---|---|
| 1 | `PlayerController.restore()` **全库只有单测调用方**，Room 队列永不被读 → `engine.prepare()` 不触发 | `PlaybackRuntime.start()` 注入 `QueueRepository`，`restore() + observe().first() → controller.restore()`；保持 PAUSED（PRD §6.1），异常落 `CloudMusicRuntime` 日志 |
| 2 | `MediaSession` 只 `setCallback + isActive`，`dumpsys` 显示 `state=null` | `setPlaybackState(STATE_PAUSED + PLAY/PAUSE/SKIP)`；真机回读 `state=2, actions=54` |
| 3 | `mediaButtonReceiver=null` / `Media button session is null` → `cmd media_session dispatch play` 被丢弃 | `setMediaButtonReceiver(PendingIntent)`；接收器 `exported=false → true` |
| 4 | `MediaButtonEventReceiver` **有意空实现**，系统按键到达后静默吞掉 | 改为转发 `PlaybackService.ACTION_PLAY / ACTION_PAUSE`；`onStartCommand` 处理 action，Callback 与 action 共用 `performPlay()/performPause()`（焦点 / C-15 / B04 同一份裁决，`requestAudioFocus()` 幂等） |
| 5 | `SourceConfig.type` 恒 `LOCAL`，URL 源无法走取流分支 | `SourceConfig(boolean remote)` / `PlayerDataSource(..., boolean remote)`，`EngineAdapter.prepare` 按 `source.url != null` 传值 —— **语义修正，真机尚未因此取流成功** |

## 3. 路线 A · 三道 CheckJNI abort 逐一攻破

| 关卡 | 真机精确报错 | 处理 |
|---|---|---|
| ① `JNI_OnLoad` | `JNI FindClass called with pending exception ClassNotFoundException: Didn't find class "com.netease.cloudmusic.media.audioplayer.NCMNativeAudioTrackBuilder"`；栈：`libncmaudioplayer.so AudioDeviceAudioTrack::Init → JNI_OnLoad → EngineNativeLoader.loadChain → EngineAdapter.prepare`；signal 6 | 从 so 提取 3 个类描述符，jadx 1.5.1 `--single-class` 从 `classes16/18.dex` 取真实源码，照搬 `NCMNativeAudioTrackBuilder` / `NCMNativeAudioPlayer` / `IBaseDataSource` 进 `:engine-legacy`（原包名不可改），补 `androidx.annotation.NonNull` 桩；`getPlayState()` 的 jadx 异常表作用域缺陷按原语义用 `try/finally` 重建 |
| ② `setDataSource` 字段 | `JNI GetFieldID called with pending exception NoSuchFieldError: no type "SourceConfig$Type" found and so no field "type" ...` | 从 `libncmplayerengine.so` 字符串表提取字段名，从 `classes14.dex` 取真实 Kotlin data class：补 `SourceConfig`（`type/encryptedType/cacheLocal/cacheOnly/engineCacheLoadConfig` + 内部枚举 `Type{LOCAL,REMOTE}`）、新增 `EncryptedType{None,EncryptNCM,EncryptXor163,EncryptXorKey}`、`ResourceInfo`（`extension/fileSize/id/md5`）、`EngineCacheLoadConfig` 同名字段。**去掉 `@Metadata`/Intrinsics** |
| ③ `setDataSource` 回调 | `JNI GetMethodID called with pending exception NoSuchMethodError: no non-static method "SourceCallback;.onUrlReady(ILjava/lang/String;)V"` | 竞品是 3 方法接口；空壳补 `onError(int)` / `onProgress(long,long)` / `onUrlReady(int,String)`，保留 class 形态（`PlayerDataSource` 直接 new），JNI 只按描述符查方法表 |

照搬文件统一清除 jadx 本地路径注释，`check-android-env.py --guard` 零个人路径继续 PASS。

## 4. 真机实证链（`logcat --pid` + `dumpsys`）

```
JNI_OnLoad success
NMAudioPlayer: JNI_OnLoad Start / JNI_VERSION_1_6 / Init AudioTrack Method ...
FindJavaClass Name(...NCMNativeAudioPlayer) Success / NewGlobalRef ... Success
FindJavaClass Name(...IBaseDataSource) Success / AndroidAudioPlayer::Init begin!
NEPlayer: PlaybackEngine ctor / AudioPlayerUnit ctor
NCMPlayerEngine: Data request error callback installed isPlayback=1
NEPlayer: setDataSource notifyTrackActivated ret=0
NMAudioPlayer: CNCMAudioPlayer::PrepareAsync → doPrepareAsync openSource 1
NEPlayer: BufferSource open: encryptedType=0
PlayerNotifyEvent msg=300 arg1=2/3/9, msg=100 arg1=1 arg2=1
NMAudioPlayer: CNCMAudioPlayer::Play status 9
dumpsys audio        : pack=com.example.cloudmusic GAIN USAGE_MEDIA CONTENT_TYPE_MUSIC
dumpsys media_session: state=PlaybackState {state=2, actions=54}
logcat -b crash      : 空；进程存活
```

## 5. 验证与基线

| 检查 | 结果 |
|---|---|
| `./gradlew test :app:assembleDebug --offline` | 多轮 **BUILD SUCCESSFUL**（最后 23s / 240 tasks） |
| 测试 XML | **356 测试 / 37 套件 / 0 失败 / 0 错误 / 0 跳过**（本轮未新增 JVM 单测） |
| 自检 | `check_source_contracts.py` PASS **36/143/15**；dev **6/6 PASS**；release **FAIL（C1, C4）**；guard / compileall / 零个人路径 OK |
| APK | DEX 含 3 新真类 + `EncryptedType`；**20 so**；`assets/legacy-player/` 51 项 / 320,569 字节 |
| 基线 | 19 模块 / **11 权限**（`exported` 改真不增权限）/ csv **61 行 `replaced=false`** / `docs/` 29 + 根 `NOTICE.md` = 30，全部未变 |

## 6. 诚实边界

- `dumpsys media.audio_flinger`：**`1 Tracks of which 0 are active`**；`dumpsys audio` players 只有系统 SoundPool，**没有本 App 的 AudioTrack**。
- PC 音源服务只收到 17:40~17:41 的连通性测试请求，**播放期没有任何 GET** → native 未发起取流。
- `UrlDataProvider.getUrl()` 已返回回环 URL 的 `UrlResult`，但 `createDataRequestHandler()` 返回 `null`、`openLocalFd()` 返回 -1；native 期望的取流句柄 / `IBaseDataSource` 承接链尚未实现。把 `SourceConfig.type` 改 `REMOTE` 也未改变结果。
- 事件码 `msg=300 arg1=2/3/9`、`msg=100 arg1=1 arg2=1` 仍未解读；native 日志 `songId=` 为空。
- **G01~G09 仍 NOT RUN**：解码 / 时长 / seek / ENDED / Range / 解码失败 / 2h 后台 / native crash 恢复全部未验证。
- 测试曲目是直接写 Room 的夹具；`READ_MEDIA_AUDIO` 运行时权限仍 `granted=false`。
- 本轮平台层改动（Service / Receiver / Runtime）**没有新增 JVM 单测**，只做了真机实证。

## 7. 下一步施工单（`12 §50.7`）

1. **T-L L1 收口**：实现 `DataProvider.createDataRequestHandler()` / `openLocalFd` 的 native 期望对象，让 `UrlResult.url` 真正被请求；校准 `UrlResult` 字段与事件码常量表。
2. 补齐本轮平台层改动的 JVM 单测与 `check_source_contracts.py` 长效守护（restore 接线 / PlaybackState / 媒体键转发 / 真类在位）。
3. 应用侧补齐：按动作申请权限、配置数据源全量重扫、去掉 Room 夹具后重跑同一条链路。
4. 取流成功后逐条跑 G01~G09 与 T-V。主节点仍 **4 个**、最小可听仍 **2 个硬门槛**。

## 8. 回写记录

`docs/12 §50`（新增）、`09 §1/§7`、`16 §5.3`、`03-06 §6`、`03-12 §3`、
`analysis/task-backlog-2026-11-07.md`、`.workbuddy/memory/2026-11-07.md`、`MEMORY.md`。
设备现场保留：App 已安装、`/sdcard/Music/cloudmusic_g01.mp3` 在位、`adb reverse` 与本地音源服务仍在运行。
工作区改动仍未 commit / push。
