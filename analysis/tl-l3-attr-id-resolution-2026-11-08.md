# T-L L3 · 阶段 A/B 精确还原与 aapt2 四步编译实证

> 批次：第六十六批 → `docs/12 §61.39`（2026-11-08 晚）
> 触发：用户「继续」，承接 `§61.38` 施工单阶段 A/B 的「待猜项」
> 性质：**零决策风险、零源码 / 零产物 / 零设备改动**的编译级实证
> 所有新增文件位于 `analysis/` + `tools/`，`app/src/main/res/` 仍未建立

## 1. 边界与不做的事

- 不改任何生产源码、资源、Manifest、构建配置；不建 `:ui-legacy`；不碰设备。
- 不把 9 个暂存 layout 搬进 `app/src/main/res/`；去前缀动作只发生在仓库外临时工程。
- 不替用户拍板「是否做照搬播放页 / UI 形态 / 第 20 个模块」三项。
- 不把 `aapt2` 通过等同于运行时可 inflate（aapt2 不校验 Java 类）。
- D-146 口径不变：竞品素材沿用、61 行 `replaced=false`、C4 机制不变。

## 2. 阶段 A/B：从「待猜」到「机械可搬」

`§61.38` 施工单把阶段 A 写成「补 101 个 id 定义」、阶段 B 写成「判定 33 个 attr
归属（ConstraintLayout 混淆名映射 / 自定义 View 补 `attrs.xml`）」—— 两项都带猜测
成分。本轮先查竞品反编译资源表里是否已有精确定义。

`tools/legacy_layout_resolve.py`（纯 Python 零依赖、只读）以
`analysis/legacy-layout-audit.json` 的 33 个 `app:*` attr 与 101 个 `@id/` 为靶，
解析 `analysis/decoded/res/values/attrs.xml`（3657 行 / 2361 个顶层 `<attr>`）与
`public.xml`（65976 行 / 22004 个 `type=id` / 2361 个 `type=attr`）。

| 靶项 | 定义命中 | public.xml 条目 | 缺口 |
|---|---|---|---|
| 33 个混淆 `app:*` attr | **33/33** | **33/33** | 0 |
| 101 个无定义 `@id/` | — | **101/101** | 0 |

- attr format 分布：reference 13 / dimension 6 / (none) 4 / boolean 4 / string 2 /
  float 2 / integer 1 / reference+color 复合 1；**16 个带 enum/flag 子项**（如
  `ae` / `ahz` 的 10 项 scaleType、`a5w` / `a6a` 的 3 项 spread 链）。
- 生成候选 `analysis/l3-probe/ids.xml`（101 行 `<item type="id">`）与
  `analysis/l3-probe/attrs.xml`（33 个顶层 `<attr>`，保留 format 与 enum/flag 子项）。
  **仅在 `analysis/`，未进生产 `res/`。**
- 结论：**阶段 A/B 不需要猜语义**，竞品资源表已给出完整定义，搬运是机械操作。
  证据 `analysis/legacy-layout-resolve.json`。

## 3. aapt2 四步编译实证

`tools/l3_aapt2_probe.py` 在仓库外临时最小 Android 工程（9 个 layout + 候选 values +
暂存素材）里分四步 `aapt2 compile --dir` / `link -I android.jar`，逐步消除错误。

| 步骤 | 带入 values | 接入暂存资源 | flat | link rc | 错误行 | id 缺失 | attr 缺失 | 其它 |
|---|---|---|---|---|---|---|---|---|
| 1 baseline | 无 | — | 9 | 1 | 20 | 11 | 9 | 0 |
| 2 +ids.xml | ids.xml | — | 10 | 1 | 20 | **0** | 18 | 2 |
| 3 +attrs.xml | ids.xml, attrs.xml | — | 11 | 1 | 20 | 0 | **0** | 20 |
| 4 +staged res | ids.xml, attrs.xml | **42 文件** | 53 | **0** | **0** | 0 | 0 | **0** |

- step1 基线错误即 `§61.38` 推断的前两道：`resource id/xxx not found` 与
  `attribute xxx not found`。
- step2 加 `ids.xml` 后 id 错误归零；step3 加 `attrs.xml` 后 attr 错误归零，
  剩余 20 条全是 `resource drawable/...` / `color/...` / `font/... not found`
  —— 即 **layout 引用的 45 项资源定义尚未接入临时工程**，不是 id/attr 定义错误。
- step4 把 `app/src/main/assets/legacy-player/` 下 42 个非 layout 文件按规则接入后
  **compile_rc=0 / link_rc=0 / 错误 0 行**，9 个 layout 在 `aapt2` 层面全部通过。

### 3.1 step4 的接入规则（只在临时工程内）

| 对象 | 规则 | 例 |
|---|---|---|
| 文件名 | 去 `legacy_` 前缀 | `legacy_bn5.xml` → `bn5.xml`；`legacy_csp.9.png` → `csp.9.png` |
| values 顶层定义名 | `name="legacy_xxx"` → `name="xxx"` | `legacy_b1w` → `b1w`（5 个 XML 重写） |
| values / drawable 内部引用 | **不改写**（本就是竞品原名） | `@color/b1d`、`@color/anm` 原样保留 |
| 配置目录 | 保持 | `drawable-xxhdpi` / `values-h640dp` / `font` |
| layout 文件名 | 保持 `legacy_` 前缀 | 审计确认 9 个 layout 之间无 `@layout/` 引用，不影响链接 |

- **内部引用闭合**：暂存 drawable / values XML 内共 8 个 `@color/*`
  （`ajd ani anm b1d b1h b1t du dz`），全部由 `legacy_colors.xml` 的对应定义覆盖，
  去前缀后自洽，无外部依赖。
- **`.9.png` 通过**：反编译产物保留 nine-patch chunk，`aapt2` 正常编译，
  compile 日志为空。
- 证据产物已把仓库外 `workdir` 脱敏为 `<workdir>`，不含个人绝对路径
  （`check-android-env.py --guard` PASS）。

> step1~step3 的「错误行 20」是 **aapt2 单轮报错上限**，不代表错误总量；
> 判据看的是「id / attr / 其它」三类计数是否按预期归零，而非绝对行数。

## 4. 对 `§61.38` 三道阻塞的精确化（本轮主要结论）

`§61.38` 写「三道 aapt2 阻塞：① 无定义 id ② 未声明 attr ③ 无实现 View」，
`§61.37` 写「8 个含 `com.netease.*` 类名的 layout 因 aapt2 找不到类需重写 / 删除」。
四步实证给出更精确的口径：

| # | 阻塞 | 是否 aapt2 错误 | 本轮结果 |
|---|---|---|---|
| ① | 101 个 `@id/` 无 `@+id` 定义 | **是** | `ids.xml` 机械消除 |
| ② | 33 个 `app:*` attr 无声明 | **是** | 竞品 `attrs.xml` 搬运机械消除 |
| ③ | 45 项 layout 资源引用未接入 `res/` | **是** | 42 个暂存文件去前缀接入后归零（§61.38 已证素材齐备） |
| ④ | 44 处 / 20 类 `com.netease.*` 自定义 View 无实现 | **否** | aapt2 **不校验 Java 类**，step4 全绿；属运行时 `LayoutInflater` 的 `ClassNotFoundException`，须阶段 C 重写 |

**要点**：aapt2 从来没有因为「找不到竞品类」而失败（它不解析 Java 类），
`§61.37` 的归因不完整；真正让编译失败的始终是资源定义缺失。
把类名换掉而不补 id / attr / 资源定义，仍然编译不过 —— 这一点 `§61.38` 已定性，
本轮用编译级证据钉死。反之，aapt2 全绿也**不等于**运行时可 inflate。

## 5. 施工单阶段状态更新

| 阶段 | `§61.38` 表述 | `§61.39` 现行 |
|---|---|---|
| A 补 101 个 id 定义 | 待猜 | **已实证机械可搬**，候选 `ids.xml` 在 `analysis/l3-probe/` |
| B 判定 33 个 attr 归属 | 待猜 | **已实证 33/33 定义 + public id 精确命中**，无需猜语义 |
| C 按 `14 §4.1` 重写 20 个 View | 未动 | **未动，唯一真阻塞（运行时）** |
| D 45 项 `@type/name` → `@type/legacy_name` | 未动 | 编译级实证「去前缀可链接」；生产 `res/` 未建，不接线 |
| E 建 `:ui-legacy` / `PlayerStateBridge` / 真机回归 | 需拍板 | **仍需拍板**，本轮不构成接线 |

## 6. 不变项（验收基线）

- `app/src/main/res/` 不存在、`:ui-legacy` 未 include、**19 模块**；
  51 个暂存素材零生产引用方。
- 11 权限 / 20 so / 61 行 csv（全 `replaced=false`）/ 51 素材 / APK 体积 /
  `ENGINE-FINGERPRINT` 零变化；C1~C6 与 C4 dev WARN / release FAIL 机制不变。
- **A3 = G01~G08 PASS / G09 PARTIAL**，舱不冻结、`engine_compat_test` 不回填、
  Manifest 保持验收期调试形态；设备未触碰。

## 7. 复跑方式（占位符，不含个人路径）

```
python tools/legacy_layout_resolve.py
ANDROID_HOME=<SDK> python tools/l3_aapt2_probe.py \
    --aapt2 <SDK>/build-tools/<ver>/aapt2.exe \
    --android-jar <SDK>/platforms/<api>/android.jar
```

产物：`analysis/legacy-layout-resolve.json`、`analysis/l3-probe/{ids,attrs}.xml`、
`analysis/l3-probe/aapt2-probe-result.json`、`analysis/l3-probe/aapt2-probe.md`。
`--workdir` 可指定仓库外临时目录；不指定则自动建系统临时目录。

## 8. 剩余断点 / 下一步

1. **需拍板**（不变）：是否做「照搬播放页」、UI 形态保留 Compose 还是切 legacy
   layout、是否允许建第 20 个模块 `:ui-legacy`。阶段 C / D / E 仅在拍板后执行。
2. **唯一技术真阻塞**：阶段 C 重写 20 个 `com.netease.*` View（或改用自研控件），
   否则即便 aapt2 全绿，运行时 inflate 仍抛 `ClassNotFoundException`。
3. T-V 需 USB / 大数据集 / DMR 设备的项；G09 保持 PARTIAL（用户选项 3）；
   A3 全绿后 ABI 验收回填 + 冻结舱 + `§61.33` Manifest 生产形态施工单。

## 附：本批文件清单

| 文件 | 类型 |
|---|---|
| `tools/legacy_layout_resolve.py` | 阶段 A/B 还原工具（本批纳入守护） |
| `analysis/legacy-layout-resolve.json` | attr/id 还原证据 |
| `analysis/l3-probe/ids.xml` / `attrs.xml` | 候选资源（仅 analysis） |
| `tools/l3_aapt2_probe.py` | 四步 aapt2 实证工具（本批扩展 + 路径脱敏） |
| `analysis/l3-probe/aapt2-probe-result.json` / `.md` | 四步实证证据 |
| `analysis/tl-l3-attr-id-resolution-2026-11-08.md` | 本报告 |
| `tools/check_source_contracts.py` | §61.39 守护 + 第六十六批 NOTE |

> AI生成
