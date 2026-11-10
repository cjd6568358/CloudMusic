# F4 · level-5 日志门控「零侵入开启路径」探查结论

> **机器证据**：`analysis/l3-f4-loglevel-gate-probe.json`（19 项 checks 全 PASS）
> **复跑**：`/c/Users/cjd65/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe tools/l3_f4_loglevel_gate_probe.py`
> **性质**：承接 `12 §61.68` 剩余断点①的纯离线二进制取证。ELF 符号 + capstone 全 `.text` 反汇编，
> 未连 adb、未改任何生产源码、`libNMCommonCache.so` 二进制原样。
> **定位**：`§61.68` 剩余断点的探查证据，**不构成新交付批次**；待用户拍板后并入下一批统一固化文档 / 守护。

---

## 1. 结论先行

**不存在**「不改 Java 源码 / 不重编译 / 不重装 APK」即可在真机开启 native level-5 分支日志的路径。

`§61.68` 第二轮真机观测里 `setprop log.tag.* VERBOSE` 不能解锁 level-5，本探查给出了二进制层原因：
level 是 `libNMCommonCache.so` 内一个**进程内 `.bss` 全局**，进程启动恒为 0，全 `.text` 只有一个写入点，
且该写入点只由 `SetLoggerCallback(INMLogger,int)` 这个 JNI 调用触达；native 侧**根本不存在**
「日志级别配置」这个概念（无 JSON key、无 prop、无配置文件、无跨进程入口）。

唯一开启方式是在 **app 进程内**调用 `NMVirtualFileManager.SetLoggerCallback(logger, level)`；
生产 app 全库无该调用点 → 必须改 Java 源码 + 重编译 + 重装 APK（动 P0 舱体），**需用户拍板**。

**F4 仍 `BLOCKED`**。本探查只收窄断点，不修复、不验证。

---

## 2. 一手证据（19 项 checks 全 PASS）

| # | 锚点 / 检查 | 实证结果 |
|---|---|---|
| 1 | `level` 全局 | `0x4c388` 落在 `.bss`（`SHT_NOBITS`，`addr=0x4c130 size=0x4a0`）→ 进程启动恒为 0 |
| 2 | 全局访问点 | 全 `.text` **仅 2 条**：getter `0x37e24 ldr w8,[x8,#0x388]` / setter `0x37e38 str w0,[x8,#0x388]` |
| 3 | setter 唯一调用点 | `0x37e34` 全 `.text` 只有 `0x32d88` 一处 `b`，即 `SetLoggerCallback` JNI 的 tail call |
| 4 | JNI 实现 | `Java_..._SetLoggerCallback @0x32d64`，**size=40 B**：`mov x0,x2` → `mov w19,w3` → `bl 0x36198` → `mov w0,w19` → `b 0x37e34` |
| 5 | `w3` 即 level | `0x32d74 mov w19,w3`，`w3` 是 JNI 第 4 参 `int i` |
| 6 | logger 去向 | `0x32d70 mov x0,x2`（`x2` = `INMLogger`）；callee `0x36198` 全 `.text` 唯一调用点 `0x32d78`，`0x361c8 mov x20,x0` |
| 7 | getter 语义 | `0x37e28 cmp w8,w0` + `0x37e2c cset w0,gt` → **全局 level 严格大于传入 level 才为真** |
| 8 | 导入表 | `DT_NEEDED = liblog.so / libm.so / libc++_shared.so / libdl.so / libc.so`，**无 `libnblog.so`**；`__android_log_print` 为 `SHN_UNDEF` 导入 |
| 9 | `libnblog.so` 线索 | 仅 `.rodata` 两个字符串 `libnblog.so` / `nblog_write`，靠 `libdl.so` 动态探测，未链接 → 不构成可配置开关 |
| 10 | `SetConfig` 直证 | `_ZN20NMVirtualFileManager9SetConfigE... @0xe3e4`，函数体 286 条指令，ADRP+ADD 引用的 `.rodata` 串**只有 6 条**，全是 `Config Error...` / `Finish` 日志格式串，**无 level 类** |
| 11 | 全 so 关键字 | 大小写不敏感扫描 `level` / `log_level` / `debug` / `verbose` / `log_output` → **0 命中** |

`SetConfig` 函数体实际引用的 6 条 `.rodata` 串：

```text
0x3c5a5  NMVirtualFileManager::SetConfig Config Error:%s
0x3c5d5  NMVirtualFileManager::SetConfig Config Error. Except Object:%s
0x3c614  NMVirtualFileManager::SetConfig Config Error. Dir Empty:%s
0x3c64f  NMVirtualFileManager::SetConfig Config Error. Create Dir Failed:%s
0x3c692  NMVirtualFileManager::SetConfig Config Error. Cache Clean Range Invalid:%s
0x3c6dd  NMVirtualFileManager::SetConfig Finish:%s
```

---

## 3. 与 `§61.68` 两轮真机观测的对应

轮②在 `setprop log.tag.* VERBOSE` 后，`D/NMCache` 80 条、`I/NMCache` 72 条、`W/NMCache` 3 条仍在，
但 `NMSlicesFile` / `OnWriteNetData` / `Write all finish` / `UnExpect New Offset` / `SKIP` / `TRIM`
全部 0 条。

→ 不是 liblog 优先级过滤（否则 `D/NMCache` 不会照常输出），而是这些分支受 native `level` 全局门控；
该全局在 `.bss` 恒为 0，只有 `SetLoggerCallback` 能写。**`setprop` 与本全局无任何数据通路**。

---

## 4. 逐条排除的候选路径

| 候选路径 | 结论 | 依据 |
|---|---|---|
| `setprop log.tag.NMCache VERBOSE` | 无效 | `§61.68` 轮②实证；level 是 `.bss` 进程内变量，与 liblog 优先级无关 |
| `SetConfig` JSON 加 `level` / `debug` / `verbose` 字段 | 无效 | 函数体引用串无对应 key；全 so 大小写不敏感 0 命中 |
| adb shell 写 prop / 配置文件 | 无效 | 全 `.text` 只有 getter/setter 访问 `0x4c388`，无外部写入口，无文件读取路径 |
| `libnblog.so` / `nblog_write` | 无效 | 仅 `.rodata` 字符串，`DT_NEEDED` 未链接，靠 `libdl.so` `dlopen` 探测 |
| `NCMNativeAudioPlayer.setLogOutputLevel(int)` | 无效 | 属 `libncmaudioplayer.so` 静态接口，不是 `0x4c388` |
| `PlaylistJniBridge.nativeSetLogLevel(int)` | 无效 | 属 playlist so 侧全局日志级别，不是 `0x4c388` |
| 跨进程注入 / 运行时 hook（frida 等） | 本轮未采用 | 超出「零侵入纯观测」边界，且属改运行时行为，非项目既定验证手段 |
| 改 `wifiOnly` / `adb reverse` / 音源 | 无关 | 探针 `set_network_gate` 只影响取流可达性，与 native level 无数据通路 |

---

## 5. 探查过程中的一次证据链修正（诚实记录）

上一版工具里 `setconfig_json_keys_have_no_level` 这条 check 是**误报 FAIL**，根因有两层：

1. 它用 `[ -~]{4,}` 正则从字符串表里凑 6 个 config key，而 `dir` 只有 3 字符，被正则漏掉，
   导致 `len(config_keys) >= 6` 不成立（`level_like_config_keys` 本来就是空数组）；
2. 进一步字节级勘查发现，`max_size` / `file_slice_size` / `auto_cache_clean` 其实来自
   `.rodata` 的 `0x3d286` 附近**另一张 NUL 分隔的 JSON key 表**（`md5` / `file_id` /
   `file_size` / `file_slice_size` / `trunks` / `slices` / `ext` / `type` / `cache_local` /
   `range_size` / `max_size` / ...），**并非 `SetConfig` 函数引用**；而 `dir` 在 so 里只出现在
   `closedir` / `opendir` / `readdir` / `rmdir` 等 libc 导入符号名中，不存在独立 `dir` 字符串。

→ 原证据链本身不严谨。**本轮没有放宽门槛凑绿**，而是替换为两条更强的直接证据：

- `setconfig_cpp_refs_no_level_key`：`SetConfig` 函数体 ADRP+ADD 反汇编还原出的 6 条引用串，无 level 类；
- `so_has_no_level_keyword_at_all`：全 so 二进制大小写不敏感关键字扫描 0 命中。

**结论未变，证据强度提升**。这一修正已写入机器证据的 `honest_notes`。

---

## 6. 唯一路径与「改 P0 前必须先确认」的两个风险

**唯一路径**：在 `engine-legacy` 生产调用链里，进程初始化时调用
`NMVirtualFileManager.SetLoggerCallback(logger, level)` → 改 Java/Kotlin 源码 → 重编译 → 重装 debug APK。

本轮二进制实证额外暴露两个此前被简写成「`SetLoggerCallback(null, 5)`」所掩盖的实操风险，
**授权后不能照抄该简写**：

1. **不要传 `null` logger**。JNI `0x32d70 mov x0,x2` 把 `INMLogger` 对象传给唯一 callee
   `0x36198`（`0x361c8 mov x20,x0`，后续有 `0x361ec cbz x0,#0x36268` 等分支）。
   传 null 的安全性未经实证，可能在该 callee 或后续 `blr` 回调解引用时崩溃 → 应传一个
   `INMLogger` 实现实例。
2. **`level=5` 未必够**。getter `0x37e20` 实为 `cmp w8,w0; cset w0,gt`，判定是
   `global_level > 传入 level` 才为真（**严格大于**）。因此「填 5 就开 level-5」不成立，
   需结合 level-5 分支调用处传给 getter 的实参确定取值（可能要 6 或更大）。

两者本轮**均未做取舍、未改任何代码**，仅登记为改 P0 方案的前置判据。

---

## 7. 诚实边界

- **F4 仍 `BLOCKED`**：证据等级提升 ≠ 修复 ≠ 验证；`-103` 判据未通过；
  `Write start play` / `doPlayCompleted` 未出现；自动进曲 3 首 / seek / ENDED 仍 `NOT RUN`；
  F3 台账不改写；A3 仍 **G09 PARTIAL** → 舱不冻结、`engine_compat_test` 不回填、指纹不回填。
- `0x10798` 整包丢弃分支与 `NMSlicesFile::Write` 的 `file_len` 门控仍**无直接分支日志**，level-5 未开启。
- 本轮**零 P0 源码改动**、未重编译、未重装 APK、未连 adb、未改 so、未改动用户网络；
  临时 `wifiOnly` / `adb reverse` / HTTP server / `log.tag.*` prop 均已在 `§61.68` 探针 `finally` 中恢复。
- 本文件为探查证据，**不构成新交付批次**；待用户拍板后并入下一批统一固化文档 / 守护。

---

## 8. 本轮验证

| 项 | 结果 |
|---|---|
| `py_compile tools/l3_f4_loglevel_gate_probe.py` | OK |
| `tools/l3_f4_loglevel_gate_probe.py` | **19 项 checks 全 PASS**，exit 0 |
| `tools/check_source_contracts.py` | **PASS**（结构齐全、契约签名在位、禁止依赖为零；清单 36 / 152 / 15 零变化） |
| 历史 `analysis/l3-f4-*audit.json` 全量复核 | 17 份全部 `all_pass=True` / `failed_checks=[]`，零回潮 |
| `./gradlew test` / 重编译 / 重装 APK | **未执行**（本轮零生产源码改动，不构成交付批次） |
