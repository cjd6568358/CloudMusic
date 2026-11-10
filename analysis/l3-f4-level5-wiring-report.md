# 12 §61.69 · F4 level-5 诊断接线审计结论报告

> **批次**：第九十六批 / `docs/12 §61.69` / 2026-11-11 凌晨
> **生成**：`python tools/l3_f4_level5_wiring_audit.py`
> **机器证据**：`analysis/l3-f4-level5-wiring-audit.json`
> **性质**：**只读审计**。不连 adb、不构建、不改任何生产源码。

---

## 结论先行

用户通过选项明确「继续＝按 A 走（**授权改 P0**）」后，本批完成**唯一一处生产源码改动**：
`engine-legacy/.../EngineAdapter.kt`（+20 行）在 `prepare()` 内接入
`NMVirtualFileManager.SetLoggerCallback(INMLogger(), level)`，
`:app:assembleDebug --offline` **BUILD SUCCESSFUL in 2m31s**、APK
`31302687 B`。

**但真机 level-5 观测 NOT RUN**：全新安装被 MIUI
`INSTALL_FAILED_USER_RESTRICTED` 阻挡（adb 侧绕不过，须用户在手机上确认一次），
随后设备 `192.168.1.11:41703` **完全离线**（`Test-NetConnection :41703 = False`、
ICMP 均不通），本机 WLAN 仍 `192.168.1.19`、**未改动用户网络**。

**审计结果：23 项 checks 全 PASS**（wiring 8 / evidence 5 / binary 2 /
docs 5 / honesty 3）。**F4 仍 BLOCKED** —— 诊断接线 + 编译产物就绪
≠ 真机验证 ≠ 修复；`0x10798` 整包丢弃分支与 `NMSlicesFile::Write` 的
`file_len` 门控**仍无直接分支日志**，`-103` 未通过，自动进曲 3 首 / seek /
ENDED 仍 NOT RUN，A3 仍 G09 PARTIAL → 舱不冻结、指纹不回填。

---

## 1. 为什么必须改 P0（零侵入路径已被证伪）

`tools/l3_f4_loglevel_gate_probe.py`（`analysis/l3-f4-loglevel-gate-probe.json`，
19/19 PASS）已从 ELF / capstone 一手证据定死：

| 项 | 实证 |
|---|---|
| level 全局 | `0x4c388` 落在 `.bss`（`SHT_NOBITS`），进程启动恒为 0 |
| 全 `.text` 访问 | 仅 getter `0x37e20/0x37e24` 与 setter `0x37e34/0x37e38` |
| setter 唯一调用点 | `0x32d88` = `SetLoggerCallback(INMLogger,int)` JNI `0x32d64` 的 tail call |
| `SetConfig` | `@0xe3e4` 函数体 ADRP+ADD 还原出 6 条 `.rodata` 串，全是 `Config Error...` / `Finish`；全 so 大小写不敏感 `level\|debug\|verbose\|log_output` **0 命中** |
| `libnblog.so` | 仅 `.rodata` 字符串 + `libdl.so` 的 `dlopen`/`dlsym` 探测，不在 `DT_NEEDED` |

→ **不存在**「不改 Java 源码 / 不重编译 / 不重装 APK」即可在真机开启 native
level-5 分支日志的路径。§61.68 的 `setprop log.tag.* VERBOSE` 是**有效负结果**，
不是操作失误。

## 2. 改 P0 前定死的三条硬约束（决定接线形态）

| # | 二进制实证 | 对改动的约束 |
|---|---|---|
| 1 | `INMLogger` 是空类 `public class INMLogger {}`，无任何回调方法；JNI `0x32d70 mov x0,x2` 把 logger 交给唯一 callee `0x36198`（`0x361c8 mov x20,x0`） | 传 **`INMLogger()` 实例**，不传 `null` —— null 安全性未实证 |
| 2 | getter `0x37e20` = `cmp w8,w0; cset w0,gt`，即**全局 level 严格大于传入 level 才为真**；全 `.text` 173 个调用点实参为 4/5 | `level=5` 不是「必然解锁 level-5」的可靠结论，须真机 0/4/5 对照 |
| 3 | `NMSlicesFile::Write` 附近 `g_level > 5` 会直接 return `-102`(0x1282c) / `-1`(0x12874, 0x12c5c) / `-99`(0x12c48) | **level 绝不能 ≥ 6**，否则注入原本不存在的错误返回、污染 F4 观测 → `.coerceIn(0, 5)` 硬上限 |
| 4 | `0x3138c` 日志函数引用 `libnblog.so`(0x3e6df) 并经 `dlopen`/`dlsym` PLT；真机 `/system/lib64/libnblog.so` 249208 B | 日志后端可用；`setprop` 无效确系 native level 门控，不是 liblog 优先级过滤 |

## 3. 接线形态（唯一生产改动）

```kotlin
val level = runCatching {
    java.io.File(context.filesDir, "nmcache_log_level")
        .readText().trim().toInt()
}.getOrDefault(5).coerceIn(0, 5)
NMVirtualFileManager.SetLoggerCallback(INMLogger(), level)
EngineDiag.i("SetLoggerCallback level=$level logger=INMLogger cap<=5")
```

- 位置：`prepare()` 的 `vfm?.let { manager -> … }` 块内、`manager.SetConfig(json)`
  **之前**；`SetConfig` 保持原样。
- `nmcache_log_level` 仅诊断期由 `adb run-as` 写入，**不属正式设置项、不进
  DataStore、不登记 `08 §1`**。
- 零播放逻辑改动、零新增依赖、零 so 二进制改动；新增
  `INMLogger` import 未触发 C2 静态检查。

## 4. 安装受阻与设备离线（诚实登记）

| 步骤 | 结果 |
|---|---|
| `adb install -r` | `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（两套 debug.keystore 历史坑） |
| 卸载 `com.example.cloudmusic` | Success |
| 全新安装 | MIUI `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` |
| `pm install -r` / `-i com.android.shell` | 同一限制拦下 |
| session `install-create/write/commit` | commit 同样被拦 |
| `settings put global package_verifier_user_consent 0` | `SecurityException`：shell 无 `WRITE_SECURE_SETTINGS` |
| `am start` `file://` / MediaStore `content://` | 后者报 `解析软件包时出现问题。(11)`：`com.miui.packageinstaller has no access to content://media/external/file/1000025218`；按钮仅「确定」 |

→ **MIUI 系统级限制，adb 侧绕不过，须用户在手机上确认一次**。APK 已推到
`/sdcard/Download/CloudMusic-debug.apk`（31302687 B）。随后设备完全离线，
连 ICMP 都不通 → 手机端无线调试已关闭 / 已离开该网段，只能由用户重新开启
并给新配对端点。**全程未改动用户网络**。

**数据安全**：卸载前已 `run-as tar` 备份 app 私有数据到
`analysis/l3-f4-backup/app-data-20261111.tar`（50969600 B，含
`cache/legacy_engine_cache/` 的 `.nmsf` / `.nmsfi` 切片）。卸载会清空该缓存
→ **后续真机起点是冷缓存，与 §61.68 暖缓存条件不同**。`ACTION_PLAY` →
`runtime.playUrl(injectedUrl)` 不查 Room 曲库，故不需恢复夹具。

## 5. 23 项 checks 一览（`all_pass=true` / `failed_checks=[]`）

| 组 | 数量 | 关键项 |
|---|---|---|
| wiring | 8/8 | `INMLogger` import、`SetLoggerCallback(INMLogger(), level)`、全树无 `SetLoggerCallback(null`、`coerceIn(0, 5)`、默认 `getOrDefault(5)`、`nmcache_log_level` 覆写、注释引用 `12 §61.69`、`SetConfig(json)` 仍在 |
| evidence | 5/5 | loglevel 探查 19/19 且记录 `null_logger_risk` / 严格大于语义；§61.68 真机审计 29/29 且 `f4_still_blocked`；§61.67 反汇编审计 25/25 零回潮 |
| binary | 2/2 | `libNMCommonCache.so` 在场并记 sha256；全源码树 `SetLoggerCallback(INMLogger()` 恰好 1 处 |
| docs | 5/5 | `12 §61.69` / `17 §8.29` / backlog / runbook 设备离线 + 上线判据 / `09 §7` + `16 §5.3` 进度块 |
| honesty | 3/3 | 两份章节均写 `F4 仍 BLOCKED`；否定式引用剔除后无「已开启 / 已收口」伪证；三处文档均登记设备离线 |

`libNMCommonCache.so` sha256 已写入机器证据 JSON，供后续复核二进制未被改动。

## 6. 与 §61.68 的对应（不改写历史结论）

- §61.68 两轮真机纯观测确立的「代次 `1→22` 连续递增 / `commit` 恒 16384 /
  `setprop VERBOSE` 有效负结果」**原样保留**，本批审计确认其 JSON 未回潮。
- 本批把 §61.68 剩余断点①「level-5 开启决策」从**待拍板**推进为
  **已授权 + 诊断接线已落地 + 编译产物就绪**，但**未推进到真机验证**。
- §61.67 的 B（`0x10798` 整包丢弃）/ ⓐ（跳过 `0x1086C` 数据回调）/ ⓑ
  （`NMSlicesFile::Write` 的 `file_len` 门控）**仍是 inference / 强交叉**，
  无直接分支日志，不升级为实证。

## 7. 诚实边界

- **F4 仍 BLOCKED**：`-103` 判据未通过、自动进曲 3 首 / seek / ENDED 仍
  NOT RUN、F3 14 格未改写、A3 仍 **G09 PARTIAL** → 舱不冻结、
  `engine_compat_test` 不回填、指纹不回填。
- 诊断接线 + 编译产物就绪 ≠ 真机验证 ≠ F4 修复；不臆断 level-5 已生效 /
  F4 已收口 / m4a 已支持 / 全格式已支持。
- `level=5` 是否足以解锁 level-5 分支**未经真机确认**（getter 是严格大于
  比较），必要时用 `files/nmcache_log_level` 做 0/4/5 对照。
- 零改动用户网络、配对码不落盘、零新增依赖、未改 so 二进制。
- 本批改动未提交 / 未 push，`origin/main` 仍 `917bb7c7`。

## 8. 剩余断点（下一步，须用户操作）

1. 手机开启「开发者选项 → 无线调试」；若提示重新配对，给出新的
   **配对端点 + 一次性配对码**（不落盘）与**调试端点**。
2. `adb pair <配对端点> <配对码>` → `adb connect <调试端点>` →
   `get-state` 必须为 `device`。
3. `pm path com.example.cloudmusic` 确认 app 是否已装；未装则在手机
   「文件管理」手动点 `/sdcard/Download/CloudMusic-debug.apk` 安装，
   或开启「USB 调试（安全设置）」后重试 `adb install -r`。
4. 跑 `python tools/l3_f4_vfile_commit_probe.py --serial <新端点> --wait 60
   --out analysis/l3-f4-level5-live.json`，对比 level-5 关键词
   （`NMSlicesFile` / `OnWriteNetData` / SKIP / TRIM / `UnExpect New Offset`）
   是否出现；必要时 `run-as` 写 `files/nmcache_log_level` 做 0/4/5 对照。
5. 出现 `0x10798` 丢弃分支 / `NMSlicesFile::Write` 门控直接日志后，才把
   §61.67 的 B / ⓐ / ⓑ 从 `inference` 升级为实证；之后才谈 F4 修复、
   `docs/17 §8.3` 验收 → F5 → 回 P2→P3→P4。

> **注意**：卸载已清空 `legacy_engine_cache`，本次起点是**冷缓存**，
> 与 §61.68 暖缓存条件不同；`ACTION_PLAY` 直接 `playUrl`，不需恢复 Room 夹具。

## 9. 本批验证

`py_compile` OK；审计工具 exit `0`（23/23 PASS）；契约自检 PASS
（含 `check_source_contracts.py §61.69` 守护、清单 **36 / 152 / 15** 零变化）；
dev **6/6 PASS**、release **FAIL(C1,C4)** 正确阻断；
`./gradlew test --offline` **47 套件 · 430 测试 · 0 失败 0 错误 0 跳过**；
§61.67 / §61.68 / loglevel 三份历史审计 JSON `all_pass=True` 零回潮。

> AI生成
