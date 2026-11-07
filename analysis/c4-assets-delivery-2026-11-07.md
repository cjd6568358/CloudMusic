# 第三十八批交付与验收 · ⑦~⑨ 素材导出与 C4 口径落地（2026-11-07）

> 用户「确认」`docs/12 §40.5` D-145 两项：① 导入 61 项竞品素材；
> ② `check-assets.py` 未替换项改 dev WARN / release FAIL。
> **本批主动改变 C4 / dev / release 基线（已授权）**。完整断点见 `docs/12 §47`。

## 交付物

| 文件 | 改动 |
|---|---|
| `tools/export_player_assets.py` | 新增（纯 Python 零依赖、幂等）：读闭包 JSON → 按 `legacy_` 前缀导出 + 写映射表 / 统计 |
| `app/src/main/assets/legacy-player/**` | 新增 **51 文件 / 320,569 字节**；drawable/layout/font 复制，color/dimen/string 按条目抽取为 values XML（条目 name 加 `legacy_`） |
| `docs/assets-mapping.csv` | **空表 → 61 行**，全部 `replaced=false` |
| `analysis/player-assets-export.json` | 61 项 / 51 文件 / 320,569 字节 / 27 values 条目 / **0 缺失源** |
| `tools/check-assets.py` | `report.fail()` → `report.warn()`（dev WARN / release FAIL） |
| `tools/check_source_contracts.py` | 「必须空表」守护 → C4 新口径守护（字段合规 / replaced 值 / 待替换计数），同步过时 NOTE |
| `tools/check-no-netease-import.py` | C2 增加素材暂存区白名单（职责分离） |

## 落点决策：assets 暂存，不进 `:ui-legacy/res`

`06 §3 ⑦` 的落点是 `:ui-legacy/res/`，但该模块尚未建立（当前 19 模块、UI 为自研 Compose）。
实测 9 个播放页 layout 共引用 **146 项资源**，含大量 `@id/*` 定义、自定义 `app:` attr
与 `com.netease.*` 自定义 View 类；直接进 `res/` 会让 aapt2 因缺定义失败、破坏主工程编译。

因此素材保真导出到 **Android assets**（随 APK 打包、不参与 aapt2 编译），保持
**零新增模块 / 依赖 / 权限**，且 ⑨ 可验证「素材随 APK 落地」。
`:ui-legacy` 舱体、R 引用批量改写、layout inflate、照搬播放页仍属 **T-L L3**；
XML 内部引用本批保持原样，**不伪造已改写**。

## 体积口径实证修正（D-139）

闭包 JSON 中 61 项共 **73 条文件引用**；**73 条不去重累加恰为 5,227,338 字节**
（`values/strings.xml` 865 KB / `colors.xml` 118 KB / `dimens.xml` 93 KB 被多项重复引用），
与 D-139 的「73 文件 / 5.2 MiB」完全吻合 —— 那是「整份 values XML 按引用重复计」的估算口径。

去重后 **51 唯一文件 / 1,396,100 字节**；values 类只抽取闭包内 27 个条目
（不复制整份 XML）→ 实际写入 **51 文件 / 320,569 字节**，APK 内实测一致。
**清单完整无漏导**，差异来自去重与按需抽取。

## C4 基线与 C2 回归修正

| 模式 | 之前 | 现在 |
|---|---|---|
| 空表 | PASS | PASS（不变） |
| 有行 `replaced=false` | dev FAIL / release FAIL | **dev WARN + PASS / release FAIL** |
| dev 汇总 | 6/6 PASS | **6/6 PASS**（C4 一条 WARN） |
| release 汇总 | FAIL（仅 C1） | **FAIL（C1, C4）** ← 用户确认接受 |

导出后 C2 首次 dev FAIL：它把素材 layout 里的 `com.netease.*` 类名当成「自研源码外泄」。
这些类名正是 C4 追踪的待替换内容，职责应分开 → C2 增加白名单
`app/src/main/assets/legacy-player/`（跳过 23 个素材 XML、仍扫描 142 个自研源码），dev 恢复 6/6。

## 验证（⑨ + 全量回归）

| 检查 | 结果 |
|---|---|
| `./gradlew test :app:assembleDebug --offline` | **BUILD SUCCESSFUL**（增量 7s，240 tasks / 3 executed） |
| APK 实测 | `assets/legacy-player/` **51 文件 / 320,569 字节**；`lib/arm64-v8a/` **20 个 so** |
| 测试 XML | **356 测试 / 37 套件 / 0 失败 / 0 错误 / 0 跳过**（零回归） |
| `check_source_contracts.py` | PASS **36/143/15**，新守护 `[PASS] C4 新口径：映射表 61 行、61 项待替换…` |
| `check-assets.py` dev / release | **WARN + PASS（exit 0）** / **FAIL（exit 1）** |
| `pre_release_check.py` dev / release | **PASS 6/6** / **FAIL（C1, C4）** |
| Manifest / 模块 / 权限 / guard / compileall | 11 项 / 19 模块 / 零新增 / PASS / OK，零个人路径 |

## 诚实边界

- **⑦ 导出 + ⑧ 登记完成，⑨ 只验证到「素材随 APK 打包 + 主工程零回归」**：
  `06 §3 ⑨` 的「`:ui-legacy` 单独 assembleDebug、无 `Resources$NotFoundException`」未执行。
- **61 项全部 `replaced=false`**：release 已被 C4 阻断，发布前必须全量替换为自有素材。
- **XML 内部 `R.*` / `@type/name` 引用未改写**：assets 不参与编译；L3 转 res 时按映射表批量改写。
- `attr/xml` 1 项待人工确认；照搬播放页 / `PlayerStateBridge` 仍属 L3。
- 真机 G01/G02 仍是最小可听唯一硬门槛；**编译 / 静态验收通过 ≠ 真机出声**。

## 回写

`docs/12 §47`、`docs/09 §1/§2/§7`、`docs/16 §5.3`、`docs/14` 顶部现行状态注、
`docs/06 §4`、`analysis/task-backlog-2026-11-07.md`、当日工作日志、`MEMORY.md`。
`docs/` 仍 30 个文件；工作区仍未 commit / push。主节点仍 4 个 / 硬门槛仍 2 个。
