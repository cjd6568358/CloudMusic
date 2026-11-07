# 第三十九轮核对报告 · DLNA 输出链路缺口、联动守护与工作区盘点（2026-11-07）

> 用户「继续」。`docs/12 §47.7` 剩余三项均需素材替换设计 / 权限流程决策 / 真机设备，
> 本轮先核对 §46 接入的断连裁决链能否真正触发 DLNA 豁免，并盘点长期滞留的工作区。
> 完整断点见 `docs/12 §48`。**核对轮：零生产源码改动、零构建基线变化**。

## 1. 实证：DLNA 输出切换缺触发点

全库检索（排除 `test/` / `build/`）：

| API | 生产调用方 |
|---|---|
| `OutputRouter.setActive(...)` | **0**（仅接口声明与 `InMemoryOutputRouter` 实现；测试里才调用） |
| `PlayerController.onOutputChanged(dlna)` | **0**（仅 `PlayerController.kt:113` 定义） |
| `OutputRouter.refreshBluetoothFrom(...)` | **0** |
| `PlayerState.remoteOutput` | 只被 `PlayerController` 自身写入、单测读取，UI 不消费 |
| `CloudMusicApp.OutputDialog` | 只读展示（`03-10 §11 #8`：切换与真机状态上报属 T-L L2，V0.1 只读） |

**结论**：`activeOutput` 恒 `LOCAL`、`remoteOutput` 恒 `false` →
§46 的 `BluetoothOutputPolicy` 里 `activeOutput == DLNA` 豁免**当前不会触发**。
`03-09 §7`「OutputRouter 是唯一裁决者」与 `03-08 §14 #9` 的**写入侧**尚未落地。
这不是代码错误，触发点依赖真实 DMR 控制链路（T-V T20/T21）与 UI 切换决策。

## 2. 为什么不擅自「接上」

把输出弹窗 DLNA 项点击直接接到 `setActive(DLNA)`，会在**没有真实 DMR 播放**时
把本机内核静音、暂停并标记「投屏中」、UI 显示 DLNA —— 属于**伪造状态**，
比留白更糟（与 §42 拒绝用 try/catch 假装主链路已通同一纪律）。

**可执行接线单（L2 / T-V 落地时，`12 §48.2`）**：

```
switchOutput(dlna: Boolean)                       // :core-di 组合层唯一入口
 → outputRouter.setActive(if (dlna) DLNA else LOCAL)          // 03-09 §7 唯一裁决者
 → playerController.onOutputChanged(dlna)                     // 03-06 §9：本机静音 / 暂停 / 遥控器态
      └ 内部 media?.setEnabled(!dlna)                          // 03-07 §12 T09：禁用本机媒体会话
 → 回落 LOCAL 时 outputRouter.refreshBluetoothFrom { ... }    // §7 重新读取 A2DP
```

`BluetoothOutputPolicy` 只读 `router.activeOutput`，无需改动。

## 3. 本轮唯一代码改动

`tools/check_source_contracts.py` 新增联动守护：扫描 `app/src/main` + `core-di/src/main`
的 `.kt`，断言 `.setActive(` 与 `.onOutputChanged(` **计数成对**；当前 0/0 → PASS 且明确打印
「生产调用方 0 处；DLNA 豁免未激活，缺口见 12 §48」。未来若有人只切一半状态即 FAIL。
另加 1 条 NOTE 记录缺口。首版误用未导入的 `source_files` 导致 NameError，已改为
`ROOT/...rglob("*.kt")` 并复跑通过。

## 4. 验证

| 检查 | 结果 |
|---|---|
| `check_source_contracts.py` | **PASS 36/143/15**，新增 `[PASS] 03-09 §7：输出切换联动成对（生产调用方 0 处）…` |
| `pre_release_check.py` dev / release | **PASS 6/6** / **FAIL（C1, C4）**，与 §47 基线一致、无回归 |
| guard / compileall / 零个人路径 | PASS / OK |
| 生产源码 / Manifest / csv / 模块数 | **零改动**（19 模块、11 权限、61 行 `replaced=false` 不变） |
| Gradle 构建 | NOT RUN（未改生产源码 / 构建脚本；§47 的 BUILD SUCCESSFUL 仍为基线） |

## 5. 工作区分组盘点（未提交）

`git status --short` **82 项**：**42 个已跟踪修改（`M`）+ 40 个未跟踪（`??`）**。

| 组 | 条目数 | 说明 |
|---|---|---|
| 源码 / 构建 | 37 | 19 模块 Kotlin / Gradle / Manifest / 依赖 catalog |
| 文档 | 18 | `docs/` 下 md + `assets-mapping.csv` |
| 分析产物 | 15 | `analysis/` 的 JSON / 反编译源码 / 任务清单 / 交付记录 |
| 工具脚本 | 9 | `tools/*.py`（含本轮守护改动） |
| 二进制 / 素材 | 3 | 18 个 so 所在目录、`ENGINE-FINGERPRINT`、`legacy-player/` 51 素材（git 折叠为目录条目） |

`15 §8` 四组提交只覆盖第一~八批；第九~三十九轮产物长期滞留。**未擅自 commit / push**；
建议按上表分组审阅后提交，注意 `*.apk` 走 LFS、`.workbuddy/` 与 `analysis/smali14/` 不入库。

## 6. 剩余断点与可执行性

| 项 | 需要什么 | 能否本机推进 |
|---|---|---|
| ① 61 项素材全量替换 + `:ui-legacy` 舱体 / R 引用改写（L3） | 自有素材设计 + 146 项 layout 依赖的 id/attr 定义 | ❌ 需素材资源与设计决策 |
| ② DLNA 输出切换触发点（本轮 §1/§2） | 真实 DMR 控制链路（T-V T20/T21）+ UI 切换决策 | ❌ 需真机 DMR / 产品决策 |
| ③ 蓝牙 ACL 广播 + `BLUETOOTH_CONNECT` 运行时授权（L2） | 权限弹窗与拒绝降级矩阵决策 | ❌ 需权限流程拍板 |
| ④ 真机 G01/G02（唯一硬门槛） | arm64 真机 + adb | ❌ 本机无设备 |

**自研侧零决策风险、零回归、真机无关的代码项已穷尽**；再推进必须用户提供素材 /
权限口径 / 真机，或指定新方向。主节点仍 **4 个**、硬门槛仍 **2 个**，
装手机能看能点、仍放不出真实声音。

## 7. 回写

`docs/12 §48`、`docs/09 §1`、`docs/16 §5.3`、`analysis/task-backlog-2026-11-07.md`、
当日工作日志、`MEMORY.md`。`docs/` 仍 30 个文件。
