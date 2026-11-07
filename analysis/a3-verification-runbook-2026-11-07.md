# 真机验证施工单 · 取流句柄阶段 A/B + 应用侧闭环（2026-11-07 第四十五批）

> **分工约定**：端到端验证由**你在设备上执行**，本仓侧只负责给出判据、命令与读结果。
> **不切换任何网络配置**（WiFi / 网卡 / 路由），不 `pm grant` / `install -g` 伪造运行时授权，
> 不改设备网络设置。`adb reverse` 是 adb 隧道端口转发，不属于网络切换。
>
> 依据：`docs/12 §50.1`（设备侧绕道手法）/ `§52.4`（应用侧判据）/
> `§54.5`（取流四阶段）/ `analysis/data-request-handler-evidence-2026-11-07.md §5`。

## 0. 结论已更正（2026-11-07 21:10）：首次判断错误，验证已实跑

> **20:51 的「设备不兼容、验证被硬阻断」是错的**。用户指出「手机还是同一个」后复核：
> `adb devices` 当时只列出 `192.168.1.21:5555`，那是一台**乐视电视**
> （`Letv Y43 / MetisFHD`、`ro.build.characteristics=tv`、`persimmon`、ARMv7、
> 1920×1080、`com.letv.* / com.stv.* / com.your.iptv`、API 19 / armeabi-v7a）；
> 手机的无线调试端点 `192.168.1.11:39525` 只是**断开未重连**。
> `adb connect 192.168.1.11:39525` → `connected`，确认
> **Xiaomi 23013RK75C / mondrian / Android 13 / API 33 / arm64-v8a**（Redmi K60），完全兼容。
>
> **★ 21:43 追加（`12 §56` 诊断轮）**：本施工单 §5 的阶段 A/B 判据已按真机结果修正 ——
> `CM-DataReq` 探针证明 native **确实调用** `createDataRequestHandler()` 并成功建句柄，
> **§55.4 方向 1（竞品 Java 侧缺 `SetDataRequest` 接线）证伪**；真正断点在 prepare 阶段
> （`SourceCallback.onError code=1001` → `Play status 9`），`getUrl()` / `Start()` 从未被调用。
> 复跑命令与现场状态见 `analysis/review-and-diagnosis-2026-11-07.md §6`；
> 手机已装含探针的 debug 包，夹具保留，下轮 `install -r` 即可继续。
> 后续所有命令必须 `-s 192.168.1.11:39525` 明确指定；电视未被触碰。
> **未切换任何网络配置**（WiFi / 网卡 / 路由），未 `pm grant` 伪造授权。

**实测准备**：旧包 `18:17:05` 与新包 debug.keystore 不同（`apksigner` 证书
`5474f219…` vs `59f9c4cc…`）→ `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；
经用户授权 `uninstall` + `install` 最新 APK（21:05:49 Success）；恢复 §50 测试夹具
（新库保留 `room_master_table`，`tracks/queue_items/playback_state = 1/1/1`，
`source_url=http://127.0.0.1:8765/cloudmusic_g01.mp3`）；`adb reverse tcp:8765 tcp:8765`
+ PC `python -m http.server 8765`，设备 `curl` → `HTTP 200 / 37824 B`。

**实测结果**：

| 阶段 | 结果 | 证据 |
|---|---|---|
| A · 类型确认 | ✅ **PASS** | `JNI_OnLoad Start` → `FindJavaClass IBaseDataSource / NCMNativeAudioPlayer Success` → `PlaybackEngine ctor` → `setDataSource notifyTrackActivated ret=0` → `PrepareAsync` → `BufferSource open: encryptedType=0` → `doPrepareAsync openSource 1`；`logcat -b crash` 空，无 CheckJNI / `UnsatisfiedLinkError` / `NoSuchMethodError`；FGS 通知 + MediaSession `active=true` + `mediaButtonReceiver` 正常 |
| B · 取流触发 | ❌ **FAIL** | 媒体键广播后 `CNCMAudioPlayer::Play status 9`；**PC 音源播放期零 GET**（唯一 GET 是 `21:09:14` 的连通性 curl）；`dumpsys media.audio_flinger` `1 Tracks of which 0 are active`；MediaSession `state=2` 不变；logcat **无** `SetDataRequest` / `Request Start` / `OnWriteNetData` / `SKIP` / `createDataRequestHandler` 痕迹 |
| C / D | ⛔ NOT RUN | 依赖 B 通过 |

**结论**：`createDataRequestHandler()` 已改为返回 `UrlDataRequest`，但 native **仍未发起取流**，
且无证据表明它调用了该方法 → `§53` 的类型结论正确，**调用链推断缺了一环**：
竞品 Java 层（`DataSourcePlatformImpl.buildDataSource`）很可能自行创建 `NMBufferSource`
并调 `SetDataRequest(handler)`；或 `UrlResult` 字段顺序 / `SourceConfig.type` 判定让 native
走了本地分支（`openLocalFd()` 恒 -1）。

**下一步诊断顺序**（详见 `docs/12 §55.4`）：① 在 `createDataRequestHandler()` /
`UrlDataRequest.Start()` / `RangeFetcher.fetch()` 加诊断 `Log.i`，重构建后
`adb install -r`（签名已统一、不用再卸载、夹具保留）确认 native 是否调用；
② 反汇编 `DataSourcePlatformImpl` 找 `NMBufferSource.SetDataRequest` 的 Java 侧接线；
③ 校准 `UrlResult` 字段与 `SourceConfig.type`；④ 播放期出现 GET 后才进 C/D。

**诚实边界**：仍未出声、A3 保持 `DEEP RUN`、舱不冻结、`ENGINE-FINGERPRINT` 不回填；
G01~G09 仍 `NOT RUN`。以下 §1~§7 的命令与判据仍有效，可作为后续复跑清单。

## 1. 前置条件（硬性，先跑这四条）

```bash
adb devices -l
adb shell "getprop ro.product.model; getprop ro.build.version.sdk; getprop ro.product.cpu.abi"
```

| 期望 | 不满足时 |
|---|---|
| `ro.build.version.sdk >= 26`（推荐 33+，与 §50 的 Android 13 基线一致） | 换设备；不要改 `minSdk` 去迁就 |
| `ro.product.cpu.abi` = `arm64-v8a`（可含 64 位列表） | 换设备；native 18 个 so 只有 arm64 |
| `device` 状态（非 `offline` / `unauthorized`） | 在设备上确认「允许 USB/无线调试」 |
| 无线调试已开、PC 可达 | **由你在设备上确认**，我不切换网络 |

## 2. 构建与安装（APK 已在本仓侧重建）

```bash
./gradlew :app:assembleDebug                       # 本机执行，产出含 §54 代码的 APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell "dumpsys package com.example.cloudmusic | grep -E 'versionName|lastUpdateTime'"
```

- `lastUpdateTime` 必须**晚于本轮构建时间**，否则装的是旧包，后面判据全部无意义。
- 不要 `adb install -g` / `pm grant`：§52 已实现「按动作申请」，运行时授权必须由 UI 弹窗产生，
  伪造 granted 会让权限判据失真。

## 3. 可回退夹具（沿用 §50.1，非应用自身行为）

```bash
# PC 侧：放一个测试 mp3，起本地音源（新终端、保持运行）
mkdir -p <你的临时目录>/cm-audio && cp <测试.mp3> <你的临时目录>/cm-audio/cloudmusic_g01.mp3
cd <你的临时目录>/cm-audio && python -m http.server 8765

# adb 隧道：让设备用 127.0.0.1:8765 访问 PC 服务（不切网络）
adb reverse tcp:8765 tcp:8765
adb shell "curl -s -o /dev/null -w '%{http_code} %{size_download}\n' http://127.0.0.1:8765/cloudmusic_g01.mp3"
# 期望：200 37824（大小随你的测试文件变化）

# 清空日志，准备抓播放期
adb logcat -c
```

Git Bash 传设备侧路径 / URI 一律 `MSYS_NO_PATHCONV=1`。

## 4. 应用侧判据（§52.4，先跑，确认「曲库 0 首」前提已移除）

| # | 操作（你在设备上） | 判据 |
|---|---|---|
| 4.1 | 冷启动 App | **不弹任何权限框**（`03-11 §8 #2` 启动零申请） |
| 4.2 | 数据源页 →「添加本地目录 (SAF)」→ 选含 mp3 的目录 | 出现 1 条 `local` Storage、Toast「已添加本地目录，开始全量重扫」；曲库页 `tracks > 0`，**不再 `run-as` 灌 Room 夹具** |
| 4.3 | 点播放 | 首次弹 `POST_NOTIFICATIONS`；**拒绝后仍能播放**（B03），仅无通知卡片 |
| 4.4 | 数据源页 →「从媒体库导入」 | API 33+ 弹 `READ_MEDIA_AUDIO`（≤32 弹 `READ_EXTERNAL_STORAGE`）；拒绝只给 SAF 引导、不循环弹窗 |
| 4.5 | `adb shell "dumpsys package com.example.cloudmusic \| grep -A20 'runtime permissions'"` | 回读 `granted=true/false` 与 UI 选择一致 |

## 5. 取流四阶段判据（§54.5 / 报告 §5，本批重点）

**抓日志**（播放期保持运行，另开终端）：

```bash
adb logcat -v time | grep -iE "cloudmusic|NMBufferSource|SourceDataRequest|DataRequest|CheckJNI|UnsatisfiedLink|FindClass|GetMethodID"
```

触发播放（无 `INJECT_EVENTS` 时用媒体键广播，§50.1 已验证可达）：

```bash
adb shell am broadcast -a android.intent.action.MEDIA_BUTTON -n com.example.cloudmusic/.MediaButtonEventReceiver
# 或：adb shell cmd media_session dispatch play
```

| 阶段 | 期望输出 | 失败分支与处置 |
|---|---|---|
| **A · 类型确认** | logcat 出现 `SetDataRequest` / `Request Start`；`SourceDataRequest` 的 `InitPtr()` 不报 `UnsatisfiedLinkError` | 若 `NoSuchMethodError: <方法名>` / `NoSuchFieldError` / `ClassNotFoundException`，**把精确报错贴回来**，按名字补类或方法；不要猜 |
| **B · 取流触发** | PC 的 `python -m http.server 8765` 在**播放期**出现 `GET /... HTTP/1.1` 且请求头带 `Range: bytes=j2-(j2+j3-1)`（§50 时为零请求） | 无请求 → 贴 logcat 中 `BufferSource open` 之后的 30 行；检查 `UrlResult.url` 是否非空、`SourceConfig(remote)` 是否生效 |
| **C · 数据回传** | `NMBufferSourceImp` 出现 `OnWriteNetData`（不是 `SKIP offset=...`）；`adb shell "dumpsys media.audio_flinger \| grep -iE 'Tracks\|active'"` 的 active tracks **≥ 1**（§50 为 `1 Tracks of which 0 are active`） | 若 `SKIP offset=...`，说明 `OnDataRequestData` 第 2 个 long 语义不对 —— 贴 `SKIP` 行的 offset 与 j2/j3 实参，据此校准 |
| **D · 出声** | `adb shell "dumpsys audio \| grep -i cloudmusic"` 出现本 App 的 AudioTrack；设备实际出声 | 未出声前**不冻结舱、不回填 `ENGINE-FINGERPRINT`**；此后才逐条跑 G01~G09 |

辅助回读：

```bash
adb shell "dumpsys media_session | grep -A12 com.example.cloudmusic"   # state=2 / actions=54 基线
adb shell "dumpsys media.audio_flinger | grep -iE 'Tracks|active'"
adb logcat -b crash -d                                                  # 空 = 无崩溃
```

## 6. 结果回传表（请把输出贴回来，我读结果并定位）

| # | 需要的内容 | 你的输出 |
|---|---|---|
| 1 | §1 的 `getprop` 三行 + `adb devices -l` | |
| 2 | §2 的 `lastUpdateTime` | |
| 3 | §4.1~4.5 的逐项观察（尤其是否弹框、`tracks` 数量、拒绝后是否仍播放） | |
| 4 | 播放期 logcat 关键片段（A 阶段 30 行左右） | |
| 5 | PC `http.server` 的访问日志（是否出现带 Range 的 GET） | |
| 6 | `dumpsys media.audio_flinger` 的 Tracks 行、是否 `SKIP` | |
| 7 | `adb logcat -b crash -d` 是否为空 | |

## 7. 禁止事项与诚实边界

- ❌ 不切换 PC / 设备的 WiFi、网卡、路由；不为了「同网段」改任何网络设置。
- ❌ 不 `pm grant` / `appops` 伪造运行时授权；不 `run-as` 直接灌 Room 曲目当「应用扫描结果」。
- ❌ 不因「native 链跑通」就宣称出声；`A3` 在 D 阶段前保持 `DEEP RUN`、舱不冻结。
- 阶段 A~C 任一出现 CheckJNI abort / `UnsatisfiedLinkError`，按精确报错补类，
  **不要在无证据时批量改协议**（§50 三道 abort 的处置方式）。
- 本单所有命令均可重复执行、可回退（`adb reverse --remove-all` 可清隧道）。
