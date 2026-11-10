# T-L L3 · 技术接入前置实证与施工单（2026-11-08 晚，第六十五批）

> **一句话**：D-146 定案「个人自用 / 竞品素材沿用」后，T-L L3 只剩技术接入。本轮用
> 可复跑脚本把 9 个暂存 layout 的引用图钉死：**素材侧已自洽完备（45/45 覆盖、零缺口）**，
> 真正阻塞是 3 类编译期缺口（101 个无定义 `@id`、33 个未声明 `app:*` attr、20 个无实现
> 竞品自定义 View）；且这 51 个文件在自研源码里**零生产引用方**。因此「把 layout 接进
> `res/`」不是运行必需，而是「照搬播放页」这一**可选增强**的前置。本轮零源码 / 零产物 /
> 零设备改动，A3 与全部基线不变。
>
> 证据：`tools/legacy_layout_audit.py`（纯 Python 零依赖，可复跑）→
> `analysis/legacy-layout-audit.json`。依据 `12 §47` / `§61.36` / `§61.37`、
> `14 §4.1`、`docs/06`、`docs/assets-mapping.csv`、`analysis/player-resource-closure.json`。

## 0. 触发与边界

承接 `12 §61.37` 剩余断点①（T-L L3 技术接入）。该断点写的是「8 个含 `com.netease.*`
类名的 layout 需重写或删除（**技术原因**：aapt2 / LayoutInflater 找不到类）、`:ui-legacy`
舱体、R 引用改写、照搬播放页」。

在建舱 / 搬 `res/` 之前必须先精确知道：这些 layout 到底引用什么、导出的 51 个素材够不够、
以及当前 App 是否真的依赖它们。否则直接动代码等于猜测，与 `12 §51` 拒绝猜 native 取流
句柄、`12 §48` 不伪造投屏状态的纪律一致 —— **本轮先只做实证，不擅自接入**。

## 1. 实证数据（`legacy-layout-audit.json`）

| 项 | 实测 | 说明 |
|---|---|---|
| 暂存 layout | **9 个 / 114 个元素 / 9 个良构 XML** | `legacy_are/buu/bv5/c5y/c6a/e0g/gb/gc/gr` |
| 含竞品类名 | **8 个**（`legacy_c5y` 干净） | `44 处` 出现 / **20 个去重类**，与 `12 §61.36` 精确吻合 |
| `app:*` 混淆 attr | **33 个去重** | `a5f/a5l/a68/a69/aio/axm/r8/xs/...`，无一处有 `attrs.xml` 声明 |
| 资源引用总处数 | **251** | 其中 `@id/` 187、`@null` 2、可导出类型 64 |
| 可导出引用 | **64 处 / 45 唯一** | color 11 / dimen 2 / drawable 28 / font 1 / string 3 |
| `@id/` 引用 | **187 处 / 101 唯一**，`@+id` 定义 **0** | 全部是「引用已有 id」，无一处定义 |
| 映射表覆盖 | **45/45，缺口 0** | `assets-mapping.csv` 61 行含全部 45 项 |
| 闭包覆盖 | **45/45** | `player-resource-closure.json` 163 项亦全命中 |
| 暂存区实际定义 | **45/45，缺口 0** | 51 文件解析出 61 项定义（含 `.9.png` 九宫格口径） |
| 生产引用方 | **0 个** | 全库 `*.kt/*.java/*.xml/*.kts` 中无 `legacy-player` / `R.layout.legacy_` |
| `:ui-legacy` | **未 include**，仅 `settings.gradle.kts` 注释提及 | 仍 **19 模块** |

## 2. 三个关键结论

### 2.1 口径澄清：`§47` 的「146 项资源」= 101 个 id + 45 个可导出资源

`12 §47` 写「9 个 layout 引用 146 项资源 + 自定义 attr/View，故未直接接 res」。本轮实测：
**101 个唯一 `@id/` + 45 个唯一可导出引用 = 146**。即其中 **101 项是 layout 内部 id 引用
（不需要、也没有导出素材）**，真正需要素材的只有 45 项，且这 45 项**已被完整覆盖**（三方
交叉：csv / 闭包 / 暂存区定义均 45/45）。

→ **`⑦` 的素材导出对这 9 个 layout 是自洽完备的**，「素材不够」从来不是 L3 的阻塞项。
`§47` 的粗略表述应以此口径为准（历史批次表述不动，按项目惯例只加定论注）。

### 2.2 三道 aapt2 阻塞（顺序即编译失败顺序）

`§61.37` 把阻塞归因于「找不到 `com.netease.*` 类」，实测该类只是**第三道**；前两道更早：

| 序 | 缺口 | 数量 | aapt2 / 运行时表现 |
|---|---|---|---|
| ① | **`@id/` 引用无 `@+id` 定义** | 187 处 / 101 唯一，定义 0 | `resource id/xxx not found`；这些 layout 是从竞品拆出的片段，id 定义在外部 `public.xml` / 其它 layout |
| ② | **`app:*` 混淆 attr 无声明** | 33 个去重 | `error: attribute app:a5l not found`；ConstraintLayout 与自定义 View 的 attr 被混淆成 `a5l/a69/...` |
| ③ | **`com.netease.*` 自定义 View 无实现** | 44 处 / 20 类 | aapt2 找不到类、运行时 `LayoutInflater` 抛 `ClassNotFoundException` |

→ 只把类名换掉**仍编译不过**。`§61.37` 的「重写或删除 8 个 layout」应理解为：要么用
自研 Compose / 自研 View 重写（同时补 id 定义与 attr 声明），要么维持「不接入」。

### 2.3 零生产引用方：接 `res/` 不是运行必需

51 个暂存素材在**全部自研源码中零引用**，`app/src/main/res/` 目录当前都不存在；
V0.1 的 5 页 Compose UI（`12 §40`）与迷你播放条不依赖任何 legacy layout。
`14 §4.1` 已实证唱片旋转是纯代码属性动画，不加载 `res/anim/`。

→ 当前 APK「能看能点、A3 = G01~G08 PASS / G09 PARTIAL」**与这 9 个 layout 无关**。
把 T-L L3 当成「发布阻断 / 运行阻断」是定性偏重；它在 D-146 后是**可选增强**。

## 3. D-146 后 T-L L3 的分层（对 `§61.37` 剩余断点的细化）

| 层 | 内容 | 状态 |
|---|---|---|
| **运行必需** | 无 | 当前个人自用 debug 版不依赖暂存区任何文件 |
| **可选增强** | 照搬播放页（竞品像素级 UI）+ `PlayerStateBridge` | 需 `:ui-legacy` 舱体 + 三道改写 + 20 个 View 重写，工作量大 |
| **需拍板** | ① 是否值得做照搬播放页（V0.1 已有 5 页自研 UI）；② 若做，UI 形态是保留 Compose 还是切 legacy layout；③ 是否允许为播放页单独建第 20 个模块 `:ui-legacy` | 三项均为产品 / 架构决策，本轮**不擅自定案** |
| **未来闸门** | C4 `replaced=false` / release FAIL(C1, C4) | D-146 已降级为「公开发布时才适用」，机制不变 |

**T-V 需 USB 在场 / 大数据集 / DMR 设备的项、G09（用户选项 3 保持 PARTIAL）、
A3 全绿后 ABI 回填与冻结舱，均不受本轮影响。**

## 4. 施工单（**仅在「要做照搬播放页」拍板后执行**，本轮不动工）

- **阶段 A · id 自洽**：为 101 个唯一 id 补 `@+id` 定义（或按 `public.xml` 还原语义名），
  把 187 处 `@id/` 改为可解析引用；这是 aapt2 第一道关。
- **阶段 B · attr 声明**：33 个混淆 `app:*` 逐一判定归属（ConstraintLayout 的
  `a5f/a5l/a65/a68/a69/...` 映射回 `layout_constraint*`，自定义 View 的补 `attrs.xml`，
  无法判定的随 View 重写一并裁剪）。
- **阶段 C · 20 个竞品 View**：按 `docs/14 §4.1` 语义名逐类重写为自研 View / Compose；
  `NeteaseMusicSimpleDraweeView` 等封面类可用 Coil + 自研圆角/裁剪替代；
  唱片旋转按 `12 §39` 用 `ValueAnimator` + `setRotation`（20s ÷ 倍速），不引 anim XML。
  纯技术重写，与 D-146「素材沿用」不冲突（沿用的是 drawable/color/font 等素材，不是类）。
- **阶段 D · R 引用改写**：45 项 `@type/name` → `@type/legacy_name`（`docs/06` 的
  `resource_id` 映射），工具化批量改写并守护；`legacy_c5y` 无竞品类名，可优先试接。
- **阶段 E · 舱体与验证**：按 `03-12 §1.1` 启用第 20 个模块 `:ui-legacy`（或落 `:app/res`），
  接入 `PlayerStateBridge`；判据 = `aapt2` 零 error、播放页渲染无 `ClassNotFoundException`、
  19→20 模块计数与 `09 §3` 索引同步、`assembleDebug` 成功、真机五页回归。
  完成后**重跑** `tools/legacy_layout_audit.py` 并更新本报告（§61.38 监护会自动提示基线漂移）。

## 5. 本轮明确不做（诚实边界）

- 不删除 / 不重写 / 不移动任何暂存素材，不把 layout 搬进 `res/`；
- 不新建 `:ui-legacy` 模块、不改 `settings.gradle.kts` 的 include、不改 Manifest；
- 不把 `assets-mapping.csv` 任何行改成 `replaced=true`（`§61.37` 守护禁止伪造替换）；
- 不触碰设备（无线 adb 现场保持 `§61.32` 结束时的 stopped 状态）；
- 不回填 `ENGINE-FINGERPRINT`、不冻结引擎舱、不把 G09 判 PASS。

## 6. 复跑与验证

```
python tools/legacy_layout_audit.py                    # 产出/刷新 JSON，打印 8 项摘要
python tools/check_source_contracts.py                # §61.38 守护 PASS
python tools/pre_release_check.py --mode dev          # 期望 6/6
python tools/pre_release_check.py --mode release      # 期望 FAIL(C1, C4) 正确阻断
python -m py_compile tools/legacy_layout_audit.py
./gradlew test --offline                             # 419 测试 / 46 套件 / 0 失败基线
```

**长效守护**（`tools/check_source_contracts.py` §61.38）：

1. 含 `com.netease` 的 layout 出现在 `app/src/main/res/` → **FAIL**（三道改写未完成不得搬）；
2. 审计工具 / JSON / 暂存目录缺失 → **FAIL**；
3. 实证基线漂移（44 处类名 / 45 项映射零缺口 / 45 项定义零缺口 / 零生产引用方任一变化）
   → **FAIL**，要求重跑脚本并回写施工单；
4. 未来 `:ui-legacy` 合法启用后自动降级为 NOTE，不堵死接入路径。

## 7. 交付清单

| 文件 | 性质 |
|---|---|
| `tools/legacy_layout_audit.py` | 新增，纯 Python 零依赖审计工具（只读） |
| `analysis/legacy-layout-audit.json` | 新增，可复跑证据 |
| `analysis/tl-l3-technical-onboarding-2026-11-08.md` | 新增，本报告（施工单） |
| `tools/check_source_contracts.py` | 新增 §61.38 守护 + NOTE |
| `docs/12 §61.38`、`09 §2/§7`、`16 §5.3`、`analysis/task-backlog-2026-11-07.md` | 回写断点 |
| `.workbuddy/memory/2026-11-08.md`、`MEMORY.md` | 当日日志与长期断点 |

**基线**：19 模块 / 11 权限 / 20 so / 61 行 csv / 51 素材 / `ENGINE-FINGERPRINT` 零变化；
A3 = G01~G08 PASS / G09 PARTIAL，舱不冻结、`engine_compat_test` 不回填。

> AI生成
