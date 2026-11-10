# T-L L3 · B 路线（完整像素级照搬）拍板后前置实证与分阶段施工单（2026-11-09，第六十八批）

> **一句话**：用户拍板 B（三项全开）。按 `§61.38`「拍板后先实证、不猜就动手」的同纪律
> 先跑了 48 个闭包 layout 的全量审计，结论是：三项在**文件与网络层面可行**（39 个 layout
> 已全量导出、Google Maven 实测 HTTP 200），但「像素级一致」被两道**原理性缺口**阻断，
> 且付出的代价是**把离线构建基线永久改掉**。因此本批**先实证 + 回写，不改基线**，
> 把改基线（模块 / 依赖 / 生产 `res/`）与 View 重写留到用户据此重新拍板后的下一批。
> **不伪造完成**。
>
> 证据：`tools/l3_full_replica_audit.py`（纯 Python 零依赖、可复跑）→
> `analysis/l3-full-replica-audit.json`；39 个 layout 工作区副本在
> `analysis/l3-full-replica/layouts/`。依据 `analysis/player-resource-closure.json`、
> `analysis/legacy-layout-audit.json`、`analysis/decoded/res/layout/`、`docs/14 §4`。

## 0. 触发与三项拍板状态

用户 2026-11-09 对上一轮给出的 A/B/C 三选一明确回答 **B**，即授权：

| 拍板项 | 授权状态 | 本批是否执行 | 理由 |
|---|---|---|---|
| ① 启用第 20 个模块 `:ui-legacy`（19→20，同步 `09 §3`） | **已生效** | 暂缓 | 见 §2：改基线不可逆，且单独建舱不改善视觉 |
| ② 允许联网补 4 类依赖（`constraintlayout` / `appcompat` / `material` / `recyclerview`） | **已生效** | 暂缓（仅做可达性实测） | 实测 Google Maven `HTTP 200`；但引依赖不解决 attr 语义（见 §2.2） |
| ③ 补导 48 个播放页 layout 中其余 39 个 | **已生效** | **已完成文件层导出** | 39 个 XML 已从 `analysis/decoded/res/layout/` 复制到工作区，未接生产 `res/` |

**授权保留**：三项拍板不撤回、不降级；本批只是按实证优先纪律把「改基线」这一步推迟到
用户看到 §2 的结论之后，避免用确定代价换不确定收益。

## 1. 全量实证数据（`l3-full-replica-audit.json`）

| 项 | 已导出 9 个（`§61.38` 口径） | 其余 39 个（本批实测） | 合计 / 增量 |
|---|---|---|---|
| layout 数 | 9 | **39** | 48 |
| XML 元素 | 114 | **421** | 535 |
| 竞品自定义 View 类 | 20 | **53**（含新增 33 类） | 53 |
| 混淆 `app:*` attr | 33 | **81**（含新增 48 个） | 81 |
| `@id/` 唯一 | 101 | **282**（新增 **259**） | 282 |
| 可导出资源引用唯一 | 45 | **163**（新增 118） | 163 |
| `@+id` 定义 | 0 | **0** | 0 |

即：补导 39 个 layout 不是「复制 39 个文件」，而是要把 id / attr / 资源 / View 的
改写规模从 `101/33/45/20` 扩到 `282/81/163/53`，约 **3 倍**，`§61.39` 的四步 aapt2
实证（step4 全绿）**只覆盖已导出的 9 个**，不能外推到 39 个。

### 1.1 口径校正（重要）

`docs/12 §61.40` 与上一轮答复中写的「竞品共 **48 个播放页相关 layout**，其余 39 个
含标准竖屏主播放页」**不准确**，按本批实证校正为：

- `layout_semantic_tags` 的 48 项是 `player-resource-closure.json` 的**资源闭包涉及的
  layout**，不是 48 个播放页；
- 其中 **11 个是非播放页误命中**（`activity_discovery_music`、6 个 `discovery_item_*`、
  `fragment_circle_discussion`、`layout_comment_discuss_item`、podcast / mlog 相关等）；
- **不存在标准竖屏主播放页 tag**（`activity_player` / `activity_new_player` /
  `fragment_player` 均未在集合中出现）；
- 集合里是横屏播放页 `activity_new_player_land`、行车电台 `activity_new_player_vehicle`、
  音频播放页 `activity_player_audio`、迷你条 `fragment_mi_miniplayer`、背景
  `layout_player_backgroud`、横屏黑胶 `layout_land_vinyl`、唱片视图 `view_vinyl_records`
  等**变体与子组件**。

→ 即使把 39 个全搬进生产并逐个改写，**也拼不出用户日常看到的那个竖屏主播放页**。
「像素级一致」缺的不是文件数量，而是**主页面本身**。

## 2. 两道原理性阻断（为什么 B 不等于「看起来一样」）

### 2.1 主播放页不在闭包里

见 §1.1。这是素材侧缺口，需回到参考 APK 重新定位主播放页 layout（可能用了不同
`android:tag`、或由代码动态构建 / Fragment 组合），属新的反编译实证批次，**不是建模块
或加依赖能解决的**。

### 2.2 混淆 `app:*` attr 的布局语义不可逆（决定性）

`analysis/decoded/res/layout/gb.xml`（横屏播放页）实测：

```xml
<androidx.constraintlayout.widget.ConstraintLayout
    android:id="@id/container"
    app:a5f="parent" app:a5l="parent" app:a65="parent" app:a69="parent" />
```

- 第三方库类名 `androidx.constraintlayout.widget.ConstraintLayout` **未被 R8 改名**；
- 但它的 `layout_constraint*` 属性名被资源混淆成 `a5f` / `a5l` / `a65` / `a69`；
- 竞品 `analysis/decoded/res/values/attrs.xml` 中标准 `layout_constraint*` 名
  **实测 0 个**（`attr_semantics_probe`）；
- 在自研工程里按 `§61.39` 重新声明 `<attr name="a5f" .../>`，aapt2 会分配**新的
  资源 ID**；而 ConstraintLayout 编译期读的是 `R.attr.layout_constraint*` 的固定 ID，
  **读不到 `app:a5f` 的值** → 所有约束失效，子 View 全部堆在左上角 / 尺寸为 0。

→ `§61.39` 的 `attrs.xml` 只让 **aapt2 编译通过**（step4 `link_rc=0`），
**不还原布局语义**；引入 `androidx.constraintlayout` 依赖同样不解决。
要还原，必须先把 81 个混淆 attr **逐个反查回标准名**（资源 ID ↔ AndroidX AAR attr ID
对齐 + 语义判定），这是一轮独立的反编译实证，工作量与 `§61.39` 相当且更易错。

### 2.3 20→53 个竞品自定义 View 的绘制不可机械还原

`docs/14 §4` 已把这批类分成 5 组（唱片 / 横屏黑胶 / 迷你条 / 跑马灯 / 进度图片），
但它们的 `onDraw`、状态机、触摸派发、图片加载都在 dex 里。jadx 能取逻辑，**取不到
像素级视觉**；自研重写只能得到「近似」。B 承诺的「像素级」在这里同样不成立。

## 3. 本批实际交付（不是空报告）

1. `tools/l3_full_replica_audit.py`（新增，纯 Python 零依赖、可复跑）；
2. `analysis/l3-full-replica-audit.json`（新增，48 个 tag + 39 个逐 layout 引用图 +
   聚合缺口 + attr 语义探针 + 4 项判据）；
3. `analysis/l3-full-replica/layouts/` **39 个 XML 工作区副本**（拍板项 ③ 的文件层落地）；
4. Google Maven 连通性实测 `HTTP 200`（拍板项 ② 的可达性证据，未改 catalog）；
5. `tools/check_source_contracts.py` 新增 `§61.41` 守护 + 第六十八批 NOTE；
6. 回写 `docs/12 §61.41`、`09 §2/§7`、`16 §5.3`、
   `analysis/task-backlog-2026-11-07.md` 第六十八批、当日日志与 `MEMORY.md`。
   **本批未新增 `docs/` 文件 → `09 §3` 索引与计数仍 30 个不变。**

## 4. 本批明确不做（诚实边界）

- **不改 `settings.gradle.kts`**：`:ui-legacy` 仍未 include，模块数仍 **19**；
- **不改 `gradle/libs.versions.toml` / 任何 `build.gradle.kts`**：零新增依赖，
  离线构建基线 `./gradlew ... --offline` 保持有效；
- **不建 `app/src/main/res/`、不建 `ui-legacy/`**：生产 `res/` 仍不存在，
  `§61.38` / `§61.40` 守护的「res 未建 / 零生产引用方」口径未被破坏；
- **不动 `docs/assets-mapping.csv`（61 行全 `false`）、51 个暂存素材、Manifest、
  11 权限、DI 图、引擎指纹、20 so**；
- **不切 UI 入口**：当前可运行播放页仍是 `§61.40` 的自研 `LegacyPlayerPage`；
- **不伪造**：不把「39 个文件已导出」写成「像素级照搬已完成」，不把 `aapt2` 编译通过
  写成「布局语义已还原」；
- **不触碰设备**；A3 仍 G01~G08 PASS / G09 PARTIAL，舱不冻结。

## 5. 修正后的执行路径（请重新拍板）

| 路径 | 内容 | 代价 | 视觉收益 |
|---|---|---|---|
| **B1 · 结构照搬**（原 B 的可执行部分） | 建 `:ui-legacy` + 补 4 类依赖 + 把 9 个已实证 layout / ids / attrs / 42 项资源接进生产 `res/` + 20 个类名替换为自研 View + 同步守护 | **永久改变离线构建基线**；53 类 / 81 attr / 282 id 的改写需分多批 | 有限：约束语义仍失效、无主播放页、View 是空壳 → **可能比当前自研页更不像** |
| **B2 · 先补主页面与 attr 反查** | 新批次：从参考 APK 重新定位竖屏主播放页 layout + 81 个混淆 attr 反查回标准名 + 53 个 View 的绘制逐个重写 | 反编译实证工作量大，但**不改基线** | 高：这是真正通向像素级的前置 |
| **A · 真实贴图优先**（上一轮路线 A） | 把 51 个已导出素材里 **27 张真实图片**（14 png + 13 webp）按语义贴进当前自研 `VinylDiscView` / 图标 | 零基线风险、见效快 | 中高：图标 / 唱片 / 唱针 / 背景换成竞品真实素材，布局层级仍不同 |
| **C · 维持现状** | 只保留 7 项交互复刻，视觉差异挂账，转去推进 A3 / T-V | 零 | 无 |

**建议组合**：先做 **A**（零风险、立刻让 UI 明显接近竞品），并行做 **B2** 的只读实证；
等主播放页 layout 与 attr 映射都拿到后，再决定是否值得付 **B1** 的基线代价。

## 6. 复跑与验证

```
python tools/l3_full_replica_audit.py            # 48 个 tag / 39 个副本 / 4 项判据
python tools/check_source_contracts.py          # §61.41 守护 PASS（含 §61.38/39/40 仍 PASS）
python -m py_compile tools/l3_full_replica_audit.py tools/check_source_contracts.py
python tools/check-android-env.py --guard       # 零个人路径
python tools/pre_release_check.py --mode dev    # 期望 6/6（基线未动）
python tools/pre_release_check.py --mode release# 期望 FAIL(C1, C4) 正确阻断
GRADLE_USER_HOME=<isolated> ANDROID_HOME=<isolated> ./gradlew test :app:assembleDebug --offline
```

**长效守护**（`check_source_contracts.py §61.41`）：审计工具 / JSON / 报告 / 39 个副本在位，
且 `closure_tag_count=48` / `remaining_layout_count=39` / `has_standard_portrait_main_player_tag=false`
/ `standard_layout_constraint_attr_names=0` / `netease_class_unique_count=53` /
`app_attr_unique_count=81` 任一漂移即 FAIL，要求重跑工具并回写施工单；
同时守护「本批未改基线」（19 模块 / 零新依赖 / `res/` 未建 / `:ui-legacy` 未 include）。

## 7. 交付清单

| 文件 | 性质 |
|---|---|
| `tools/l3_full_replica_audit.py` | 新增，纯 Python 零依赖全量审计工具 |
| `analysis/l3-full-replica-audit.json` | 新增，可复跑证据 |
| `analysis/l3-full-replica/layouts/*.xml` | 新增，39 个未导出 layout 工作区副本 |
| `analysis/tl-l3-full-replica-plan-2026-11-09.md` | 新增，本施工单 |
| `tools/check_source_contracts.py` | 新增 §61.41 守护 + 第六十八批 NOTE |
| `docs/12 §61.41`、`09 §2/§7`、`16 §5.3`、`task-backlog`、当日日志与 `MEMORY.md` | 回写断点 |

**基线**：19 模块 / 11 权限 / 20 so / 61 行 csv（全 `false`）/ 51 素材 / 零新增依赖 /
`app/src/main/res/` 未建立 / `:ui-legacy` 未 include / 指纹零变化 / 设备未触碰。
A3 = G01~G08 PASS / G09 PARTIAL，舱不冻结、`engine_compat_test` 不回填。

> AI生成
