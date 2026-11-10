# F4 · A/B 脚本编译期预检与静态审查（12 §61.57）

> **2026-11-09 第八十四批** · 设备仍离线 → **不连 adb、不 install、不跑任何格**。
> 本批是截至目前**唯一临时改过生产源码**的批次（用于编译验证），但
> `try/finally` 已无条件恢复，**零净改动**（`git diff` 空）。
> `ab_executed` 仍为 `false`，本批**不是 A/B**。

---

## 1. 动机

连续四批（§61.54~§61.56）把 F4 的静态证据链做厚了，但设备一上线就要执行的
`tools/l3_f4_kind_slot_ab.py --apply` 里，**「变体 B 能不能编译过」这一环从未实证**。
如果常量缩进、Long 类型或 Gradle 启动器有问题，设备上线后会白跑一趟。
本批把这一环提前验证掉。

## 2. 预检抓到的真实缺陷（本批最大价值）

首跑 `tools/l3_f4_ab_compile_precheck.py` 直接抛：

```
OSError: [WinError 193] %1 不是有效的 Win32 应用程序。
```

根因：`l3_f4_kind_slot_ab.py::gradle()` 在 Windows 上直接
`subprocess.run(["./gradlew", ...])` —— `gradlew` 是**无扩展名的 bash 脚本**，
Windows `CreateProcess` 不能执行；原函数里那个
`if os.name == "nt": cmd = [str(GRADLE), ...]` 分支与上一行**完全相同**，是无效复制。
也就是说：**若不修，设备上线后 `--apply` 会在编译这一步直接崩溃，连真机判据都跑不到。**

实测三种 Windows 调用方式（`gradlew.bat` 直调 / `cmd.exe /c` 绝对 / 相对路径）
均为 `rc=0`、`Gradle 8.11.1`，据此修复：

```python
launcher = GRADLE.with_name("gradlew.bat") if os.name == "nt" else GRADLE
cmd = [str(launcher), *tasks, "--offline"]
```

## 3. 静态审查发现的另外两点

| # | 发现 | 处理 |
|---|---|---|
| 1 | `finally` 里 `restored` **只在基线重编译成功时**才计算：gradle 临时故障会误报「P0 未恢复」，而源码其实已恢复 | **已修**：`restored` 独立判断源码逐字节一致；新增 `baseline_rebuild_ok` 字段；返回值要求两者同时为真 |
| 2 | 若 `cells` 为空（异常导致主判据没跑），结果仍可能 `return 0`；`finally` 里 `install -r` 失败被静默吞掉 | **如实登记为已知限制，不改**：避免在设备上线前扩大改动面，判读由人工按 JSON 的 `cells` / `primary_criterion_met` 完成 |

修改后重跑 `dry-run`（4 项锚点仍全 True）、
`tools/l3_f4_kind_slot_ab_audit.py`（**14 项全 PASS**）、
契约自检 **PASS**，§61.53 的守护未被破坏。

## 4. 预检结果（实跑）

`tools/l3_f4_ab_compile_precheck.py` 复用 A/B 脚本的锚点与替换逻辑，
临时写入变体 B（`1` 处调用替换 + `1` 行 `const val NATIVE_DONE_KIND_FLUSH = 1L`，
字节增量 `+144`）→ `./gradlew :app:assembleDebug --offline`：

```
[PRECHECK] 变体编译 rc=0 BUILD SUCCESSFUL   （41s，124 tasks / 12 executed）
[RESTORE] 源码一致=True 基线重编译 rc=0 git_diff空=True
[PASS] §61.57 编译期预检：9 项 checks 全 PASS
```

`finally` 已恢复基线并重编译；`UrlDataRequest.kt:96` 仍是
`OnDataRequestEnd(requestId, error ?: "")`，无 `NATIVE_DONE_KIND_FLUSH` 残留，
`git diff` 为空，系统临时备份已删除。

## 5. 结论与边界

**已实证**：设备上线后 `--apply` 的**编译环节**可过（离线 Gradle、变体语法/类型/依赖
均无问题），且回退链能把 P0 恢复成逐字节一致。这排除了「白跑一趟」的一类风险。

**未实证 / 不变**：
- 编译通过**不证明**变体在真机上的行为，更不证明 §61.56 的**环节 B** 因果链；
- 本批不连设备、不安装 APK、不跑主判据与 5 格回归，`ab_executed=false`；
- **F4 仍判 BLOCKED**，自动进曲 3 首 / seek / ENDED 如实 **NOT RUN**；
- §59.2 exp6 反例、竞品同样传 requestId 却可用、`ape×remote` 与 `aiff` 仍不解释；
- 不写「F4 已解决」「m4a 已支持」「全格式已支持」；A3 仍 **G09 PARTIAL**、舱不冻结。

**诚实声明**：本批**临时改过**生产源码用于编译验证，最终**零净改动**；
不写「零代码改动」。`so` sha256 与 `assets/ENGINE-FINGERPRINT` 一致、
`engine_compat_test` 不回填。

## 6. 解除条件（不变）

设备重新上线后给新端点：

```
python tools/l3_f4_kind_slot_ab.py --serial <ip:port> --apply
```

判据：`f3_m4a_fast.m4a × REMOTE` 出现 `Open codec for aac` +
`Write start play nErr:0` → 环节 B 成立；仍 `Start id=3 offset=327895`
重复且无 codec → 证伪。脚本 `finally` 无条件恢复 P0 基线并重编译重装。
之后仍需 F3 14 格全量复跑 + 自动进曲 3 首（无 `-103`、无释放后回调）。

## 7. 交付

- `tools/l3_f4_ab_compile_precheck.py` → `analysis/l3-f4-ab-compile-precheck.json`
  （**9 项 checks 全 PASS**）；`tools/l3_f4_ab_compile_precheck_audit.py` →
  `analysis/l3-f4-ab-compile-precheck-audit.json`；
  `check_source_contracts.py §61.57` 守护。
- 修复 `tools/l3_f4_kind_slot_ab.py`（Windows 启动器 + `restored` 判定 +
  `baseline_rebuild_ok` 字段），dry-run / §61.53 审计 / 契约自检全部复验通过。
- 全量回写 `docs/12 §61.57`、`09 §2/§7`、`16 §5.3`、`17 §8.17`、
  任务清单、当日日志与长期记忆；未新增 `docs/` 文件。
- `§61.38`~`§61.57` 二十批改动均未提交、未 push，`HEAD` 仍 `8c9d79b1`。

> AI生成
