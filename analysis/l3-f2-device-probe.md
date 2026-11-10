# F2 真机取证：零权限本地曲库注入 + `ResourceInfo.extension` 语义错配实证

> `12 §61.47` / 第七十四批 · 2026-11-09 14:50 · 端点 `10.0.0.2:37827`（Redmi K60 `23013RK75C` / Android 13 / arm64-v8a）
> 用户指令：「**自研APP没有音乐，你自己解决**」→ 不再把「请用户手动播放一次 LOCAL 源」当作阻塞，由本助手自行把曲目与播放链解决掉，并借此完成 F2 真机取证。
> 配套机器证据：`analysis/l3-f2-zero-permission.json`（由 `tools/l3_f2_zero_permission_audit.py` 生成）、原始日志 `analysis/l3-f2-device-probe-logcat.txt`、真机截图 `analysis/l3-reference/self-ui-with-local-track.png`（通知栏）与 `analysis/l3-reference/self-ui-with-local-track-2.png`（播放页）。

---

## 1. 根因：为什么「自研 APP 没有音乐」

只读诊断（`adb shell dumpsys package` / `appops` / `logcat`）确认设备上**有**音频文件，但 app 曲库 0 首：

| 环节 | 实测 | 结论 |
|---|---|---|
| 设备音频 | `/sdcard/Music/cloudmusic_g01.mp3`（37,824 B）、`/sdcard/Download/cm_rev_test2.mp3` | 夹具存在 |
| 运行时权限 | `READ_MEDIA_AUDIO: granted=false`，appops `ignore` | 读不到公共媒体目录 |
| MIUI 无线 adb | `pm grant` 被 `GRANT_RUNTIME_PERMISSIONS` 拒、`input tap` 被 `INJECT_EVENTS` 拒 | 无法脚本授权、无法脚本点「添加媒体库」 |
| `DataStore.storages` | 空 | `ScanCoordinator.run()` 无 Storage 可扫 → 曲库恒 0 首 |
| `DriverRegistry` | 无 local driver 实例 | `RegistryLinkProvider.link(track)` 无法产出 `Link.Kind.LOCAL` |

历史 A3 的 G01 是靠 `run-as` 灌 Room 临时夹具 + URL 回环跑通的，**app 自身从未有过真正的本地曲库**。

## 2. 解决：零权限本地曲库注入（验收期调试入口）

不新增 Manifest action / 权限 / 依赖 / 模块，不触碰 P0 引擎舱：

- 复用既有 `ACTION_PLAY`，新增可选 extra `EXTRA_LOCAL_PATH = "com.example.cloudmusic.extra.LOCAL_PATH"`（`PlaybackService.kt`）。无此 extra 时 `performPlay()` 保持 `runtime.play()` 原行为，零回归。
- `PlaybackRuntime.playLocal(path)`（`core-di/.../PlaybackComposition.kt`）：
  1. 以 App 私有目录为沙箱根，用平台 `java.io.File` 实现 `LocalDirectorySource`（`FileDirectorySource`），构造 `LocalDriver` 并 `DriverRegistry.put(...)` → 播放链可产出 `Link.Kind.LOCAL`；
  2. `TrackRepository.upsertBatch()` + `QueueRepository.setAll()` → 曲库 / 队列**真实有曲目**（Room 落盘，UI 可见，不是只读夹具）；
  3. 走 `PlayerController.setQueue()` + `playAt(0)` 完整生产链 → `EngineSource(url=null, localPath=…)`。
- 音频放进 `/data/data/com.example.cloudmusic/files/music/fixture.mp3`：shell 读公共目录 → `run-as` 写私有目录，**全程不需要 `READ_MEDIA_AUDIO`**。

实跑命令（关键点：Git Bash 传 `/data/...` 需 `MSYS_NO_PATHCONV=1`）：

```bash
adb -s 10.0.0.2:37827 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 10.0.0.2:37827 shell am start -n com.example.cloudmusic/.MainActivity
MSYS_NO_PATHCONV=1 adb -s 10.0.0.2:37827 shell "run-as com.example.cloudmusic sh -c 'mkdir -p files/music'"
MSYS_NO_PATHCONV=1 adb -s 10.0.0.2:37827 shell "cat /sdcard/Music/cloudmusic_g01.mp3 | run-as com.example.cloudmusic sh -c 'cat > files/music/fixture.mp3'"
adb -s 10.0.0.2:37827 logcat -c
MSYS_NO_PATHCONV=1 adb -s 10.0.0.2:37827 shell "am startservice -a com.example.cloudmusic.action.PLAY -c android.intent.category.DEFAULT --es com.example.cloudmusic.extra.LOCAL_PATH /data/data/com.example.cloudmusic/files/music/fixture.mp3"
adb -s 10.0.0.2:37827 logcat -d -v time | grep ... > analysis/l3-f2-device-probe-logcat.txt
```

夹具校验：`run-as ... ls -l files/music/` → `-rw-rw-rw- 1 u0_a327 u0_a327 37824 ... fixture.mp3`（与源夹具字节数一致）；Room：`databases/cloudmusic.db` 151,552 B + `-wal` 412,032 B（注入后 14:50 更新）。

## 3. 真机证据：F2 假设被证实，但 mp3 LOCAL 仍可播

原始日志 44 行见 `analysis/l3-f2-device-probe-logcat.txt`，关键行（pid 15572 / uid 10327，14:50:07）：

```
I/CM-DataReq: prepare trackId=aac639b4bbea89897e628250f3536b4f remote=false url=null expectedLength=0
I/CM-DataReq: resourceInfo extension=audio/mpeg fileSize=-1
I/CM-DataReq: getLocalPath path=/data/data/com.example.cloudmusic/files/music/fixture.mp3
I/NEPlayer : CreateLocalSource: route XorLocalSource type=0 path=/data/data/com.example.cloudmusic/files/music/fixture.mp3
I/NEPlayer : XorLocalSource::Open success path=... fd=5 size=37824 providerOwned=0
I/NEPlayer : BufferSource open: encryptedType=0, response=..., ret=0
W/NMAudioPlayer: Decoder(0): Open codec for mp3
W/NMAudioPlayer: CNCMAudioPlayer::doPrepareAsync mAudioStream start 0, mDuration 4704, audioOffset 349
W/NMAudioPlayer: CNCMAudioPlayer::Play status 4
W/NMAudioPlayer: AudioTrack(0): Write start play nErr:0
```

逐条结论：

1. **§61.46 的「待验证假设」已被真机证实**：`source.url == null` 时 `EngineAdapter.kt:405-417` 的 Elvis 链落到 `source.mimeHint.orEmpty()`，把 MIME 字符串 `audio/mpeg` 当作扩展名传进 `ResourceInfo`（`fileSize=-1`）。F2 的语义错配**真实存在**，不再是「静态推测」。
2. **LOCAL 取流能力真实存在，且不经 `createDataRequestHandler()` / `openLocalFd()`**：native 直接走 `CreateLocalSource → XorLocalSource::Open`（`fd=5 size=37824`）→ `BufferSource open ret=0` 读取本地文件。`openLocalFd()=-1` 未阻断 LOCAL 源，§61.46 挂账的「LOCAL 真实取流能力待真机确认」**已证实可用**。
3. **mp3 LOCAL 源能进解码 / 播放链**：`Open codec for mp3` → `mDuration 4704` → `Play status 4` → `Write start play nErr:0`，零崩溃。**即 `extension` 错配并未让 mp3 不可播**（native 按内容探测成功）。这也是必须如实写下的边界：不能把「extension 错」直接等同于「全格式不可播」。
4. **曲库 / UI 已真实有音乐**：`analysis/l3-reference/self-ui-with-local-track-2.png` 播放页显示曲名 `fixture`、艺术家 `本地音源`、右侧 `1 首`，黑胶叠层 / 唱针 / 控制条在位。用户「APP 没有音乐」的诉求**已被解决**（验收期调试形态）。
5. `dumpsys media_session`：`active=true` / `media button session is com.example.cloudmusic/CloudMusic` / `state=2`（PAUSED，12s 夹具播完由 §61.25 回退逻辑置回）；通知栏截图 `self-ui-with-local-track.png` 显示 FGS「播放服务运行中」与媒体控制键。

## 4. 诚实边界（不伪造完成）

- **P0 引擎舱零改动**：`EngineAdapter.kt` 的 `extension` 链、`openLocalFd()=-1`、§56 注释「待真机验证的假设」全部保持原样；`20 so` / `ENGINE-FINGERPRINT` / `EngineModule` 换绑 / 84 JNI 真类与 slot 均未触碰。
- **F2-b 未做**：本批只完成「取证」，未修正 `extension` 推断链。是否需要修正、以及修正后是否影响其它格式，须按 todo 顺序在 F2-b 决策（**mp3 已可播不代表其它格式可播**；`extension` 可能参与 demuxer 选择，需 F3 逐格式矩阵证实）。
- **调试入口非生产形态**：`EXTRA_LOCAL_PATH` 与 `playLocal()` 是验收期注入通道，生产形态随 `12 §61.33` 的 `ACTION_PLAY` 收口；`durationMs=0` 注入导致进度显示 `00:00 / 00:00`，MediaSession `metadata: null` 未设置 → 通知栏不显示曲名。这些是已知缺口，挂账不掩饰。
- **零发布基线变化**：19 模块 / 11 权限 / 1 个 `ACTION_PLAY` / 20 so / 51 素材 / 61 行 csv 全 `replaced=false` / 零新增依赖 / `app/src/main/res/` 未建 / `:ui-legacy` 未 include / `docs/` 计数仍 31。
- **本批未新增 `docs/` 文件**，证据全在 `analysis/` + `tools/`，故 `09 §3` 的 31 个文件计数不变。

## 5. 下一步（按 `docs/17 §8` todo 顺序）

1. **F2-b**：依据本批真机结论修正 `EngineAdapter.ResourceInfo.extension`——LOCAL 源取 `source.localPath` 真实扩展名（而非 MIME），REMOTE 无后缀 / 点后 >5 字符时按 `mimeHint` 反查或内容探测；改前先判「是否真的影响非 mp3 格式」，避免为已可播的 mp3 引入回归。
2. **F2-c**：同一注入通道复验 `resourceInfo extension=mp3`，并抓 `Open codec` 对照。
3. **F3**：逐格式 × REMOTE/LOCAL 双路径真机矩阵（本批已提供可复用的零权限 LOCAL 注入通道）。
4. **F4** → **F5**，完成后回 P2 播放页 1:1。
