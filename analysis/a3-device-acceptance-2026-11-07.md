# 第四十批交付与验收 · 真机 A3 首次执行 / Service 启动缺口修复（2026-11-07）

> 用户「4，adb 192.168.1.11:39525」——选择 `12 §48.6` 第 ④ 项真机 G01/G02 并提供无线 adb 地址。
> 完整断点见 `docs/12 §49`。**A3 由 NOT RUN 升为 PARTIAL RUN；G01~G09 仍 NOT RUN，不判 PASS。**

## 1. 真机环境（实证）

| 项 | 实测 |
|---|---|
| adb | `C:/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe`（**不在 PATH**，临时 SDK 内无 `platform-tools/`）；`1.0.41 / 37.0.1` |
| 连接 | `adb connect 192.168.1.11:39525` → `connected`，`devices -l` 单设备在线 |
| 设备 | `23013RK75C` / `mondrian`（Redmi K60）、**Android 13**、**`arm64-v8a`** ✅ 符合唯一 ABI |
| APK | `app-debug.apk`（31,130,091 字节）安装 `Success` |

## 2. 真机基础设施层（PASS）

1. App 冷启动 `MainActivity +824ms`，进程存活，**`logcat -b crash` 为空**、无 `FATAL` / `UnsatisfiedLinkError`。
2. Hilt / Compose / DataStore / Room 真机初始化正常，20 个 so 随包加载无异常。
3. **修复后** `PlaybackService` 前台运行：`isForeground=true foregroundId=1001`、
   `Notification(channel=cloudmusic.playback ...)`、`allowStartForeground=PROC_STATE_TOP`。
4. **MediaSession 被系统识别**：`MiuiMediaControlPanel: bindPlayer: ... CloudMusic, 曲库就绪 /
   正在扫描曲库 0/0`；`dumpsys media_session` 显示 `CloudMusic ... active=true`、`USAGE_MEDIA`。
5. `PlaybackRuntime.start()` 冷启动扫描调度真实执行。
6. Room 库真实创建：`run-as` 拉出 `cloudmusic.db`，核对 **9 张业务表 + tracks_fts 系列**（与 `03-05 §2` 一致）。
7. 测试音源 `/sdcard/Music/cloudmusic_g01.mp3`（参考 APK assets，37,824 字节）已推送，
   MediaStore 索引 `_id=1000025147`。

## 3. P0 缺口（真机独有，静态核对看不到）

首次安装后 `dumpsys activity services com.example.cloudmusic` 返回 `(nothing)`：
`PlaybackService` 虽在 Manifest 声明 `exported=false`，但**全代码没有任何启动点**；
`adb shell am start-foreground-service` 也因 `Requires permission not exported` 无法从外部拉起。
即 §31~§48 的所有 Service 侧代码**从未在真机运行过**。

**修复（本轮唯一生产源码改动）**：`MainActivity.onStart()` 中
`startForegroundService(Intent(this, PlaybackService::class.java))`。
重建 `BUILD SUCCESSFUL in 22s`、重装后上述 FGS / 通知 / MediaSession / 扫描全部实证生效。
这也推翻了 `§48`「自研侧零风险代码项已穷尽」的判断 —— 静态核对无法发现「组件从未启动」。

## 4. G01~G09 未执行（诚实边界）

| 阻塞 | 实证 |
|---|---|
| 曲库为空 | Room `tracks=0 / queue_items=0 / playback_state=0 / scan_snapshot=0`；系统面板「扫描曲库 0/0」。根因：V0.1 未配置数据源 + `READ_MEDIA_AUDIO` 未授予 |
| 播放链路未触发 | `cmd media_session dispatch play/pause` 无崩溃，但 `dumpsys audio` 焦点栈仍只有 `com.ss.android.ugc.aweme.lite`（抖音）→ **`onPlay()` 未送达**；session 仍 `state=null / metadata=null / queue size=0` |
| native 引擎未加载 | 无曲目 → 无 handle → `EngineAdapter.prepare()` 从未调用 → **`EngineNativeLoader` 与 18 个 so 的运行时加载、15 个 dex-only 方法的 `UnsatisfiedLinkError` 均未观察** |
| 设备侧限制 | MIUI 无线 adb：`pm grant` 缺 `GRANT_RUNTIME_PERMISSIONS`、`install -r -g` 缺 `INSTALL_GRANT_RUNTIME_PERMISSIONS`、`input keyevent` 缺 `INJECT_EVENTS`；三项运行时权限 `granted=false` |

**结论**：G01（mp3/flac/m4a/ogg 各播 10s）、G02（play→pause→play 位置连续）及 G03~G09
**全部 NOT RUN**；A3 不判 PASS，引擎舱不冻结，`assets/ENGINE-FINGERPRINT` 不回填
`engine_compat_test=PASS`。装手机仍「能看能点、放不出真实声音」，但根因从「无设备」
精确到「应用侧数据源 / 权限 / 队列链路未接通」。

## 5. 真机验收施工单（需用户在设备上配合）

1. 开启 MIUI「USB 调试（安全设置）」或改用 USB 连接，解除三项 adb 限制。
2. 按 `03-11 §1.1` 落地「按动作申请」权限闸门（`POST_NOTIFICATIONS` 首次播放前、
   `READ_MEDIA_AUDIO` 选 MediaStore 通道时），或走 **SAF 添加本地目录**（无需读取权限）。
3. 配置数据源并触发全量重扫，确认 Room `tracks > 0`、队列有曲目。
4. 点击播放 / 让 `media_session dispatch` 聚焦本会话，抓 logcat 观察
   `EngineNativeLoader` 加载链、`FindClass` 回调与 `UrlResult` 字段名，再逐条跑 G01~G09。

设备当前状态保留：App 已安装并前台运行、测试 mp3 在 `/sdcard/Music/`、三项运行时权限仍未授予。

## 6. 环境与验证备忘

- Git Bash 传 `/sdcard/...` 必须 `export MSYS_NO_PATHCONV=1`，否则被转换成本地 PortableGit 路径。
- 绕开 `INJECT_EVENTS` 的途径：`cmd media_session dispatch play|pause`（本轮已验证可执行）。
- 绕开权限读 Room：`adb exec-out run-as com.example.cloudmusic cat databases/cloudmusic.db`
  （连同 `-wal` / `-shm` 一起拉出，用 Python `sqlite3` 打开）。

| 检查 | 结果 |
|---|---|
| `./gradlew test :app:assembleDebug --offline` | **BUILD SUCCESSFUL in 22s**（240 tasks / 19 executed） |
| 测试 XML | **356 测试 / 37 套件 / 0 失败 / 0 错误 / 0 跳过** |
| 自检 | `check_source_contracts.py` PASS **36/143/15**；dev **6/6**；release **FAIL（C1, C4）**；guard / compileall / 零个人路径 OK |
| 基线 | 19 模块 / 11 权限 / 61 行 `replaced=false` / 20 so / 51 素材均不变 |
| A3 | **NOT RUN → PARTIAL RUN** |
| 节点 | 主节点仍 4 个、最小可听仍 2 个硬门槛；「无 adb / 无 arm64 设备」已解除，收敛为应用侧播放链路 + 真机 G01/G02 |

## 7. 回写

`docs/12 §49`、`docs/09 §1/§2/§7`、`docs/16 §5.3`、`docs/03-06 §6`、`docs/03-12 §3`、
`analysis/task-backlog-2026-11-07.md`、当日工作日志、`MEMORY.md`。
`docs/` 仍 30 个文件；工作区 **83 项**改动，仍未 commit / push。
