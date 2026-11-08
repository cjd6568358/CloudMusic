# T-L L3 · 61 项素材替换施工单（2026-11-08 晚）

> **★ D-146 决策更新（2026-11-08 21:49）**：用户决定**竞品素材直接沿用、
> 不做替换**（仅限个人自用 / 侧载）。因此本施工单**降级为「未来若公开发布
> 才执行的备选路径」**：阶段 A 的素材来源决策已不再需要，阶段 C 的
> `replaced=true` / C4 转 PASS、阶段 D 的「移出暂存区」**均不再是当前待办**。
> 当前 T-L L3 剩下的只有**技术接入项**：8 个含 `com.netease.*` 类名的 layout
> 需重写或删除（aapt2 / 运行时找不到类，**技术原因，不是权利原因**）、
> `:ui-legacy` 舱体、R 引用改写、照搬播放页。详见 `12 §61.37`。
>
> 依据 `03-12 §8` #1/#2/#7、`docs/06`、`docs/14`、`12 §47`、`NOTICE.md §3`、
> 证据 `analysis/tl-l3-assets-compliance-audit-2026-11-08.md`。
> **前置决策未满足**：自有素材来源（自研重绘 / 明确开源授权素材 / 用户提供）
> 需用户拍板，本施工单不代替该决策，也不擅自改变 C4 发布基线。

## 0. 现状（本轮实测）

| 项 | 值 |
|---|---|
| `docs/assets-mapping.csv` | **61 行，全部 `replaced=false`** |
| 暂存素材 | `app/src/main/assets/legacy-player/` **51 文件 / 320,569 字节** |
| 类型分布 | drawable 28 / color 18 / layout 9 / string 3 / dimen 2 / font 1 |
| 引用来源 | 61 行全部 `used_by = player_page_closure(14 §4.1)` |
| 随 APK 分发 | **是**（debug APK 内 51 条目，其中 9 个 layout） |
| 含 `com.netease.*` 的 layout | **8 文件 / 44 处 / 20 个去重类** |
| C4 | dev WARN / **release FAIL**（与 C1 并列阻断） |
| `:ui-legacy` 舱体 | **未建立**（`12 §47` 起只是 assets 暂存） |

## 1. 阶段 A · 自有素材准备（**需用户拍板**）

三条路径，选定后才可开工：

1. **自研重绘**：按 `docs/06` 的语义名与尺寸规格重新绘制 28 个 drawable、
   定义 18 个 color / 2 个 dimen / 3 个 string、自选 1 个开源字体；
   无第三方权利负担，工作量最大。
2. **明确授权的开源素材**：逐项登记来源与许可证到 `NOTICE.md`，
   `assets-mapping.csv` 的 `notes` 列记录出处；需人工核对许可证兼容性。
3. **V0.1 保持占位但不分发**：仅个人自用 / 侧载，公开发布前再执行本施工单
   （现状，符合 `03-12 §7` 的个人自用路径）。

## 2. 阶段 B · 8 个含竞品类名的 layout **必须重写或删除**

`layout/legacy_are.xml`、`legacy_buu.xml`、`legacy_bv5.xml`、`legacy_c6a.xml`、
`legacy_e0g.xml`、`legacy_gb.xml`、`legacy_gc.xml`、`legacy_gr.xml`
共 44 处 `com.netease.*` 类名。**不能原样搬进 `res/`**：

- 编译侧：aapt2 / LayoutInflater 找不到这些自定义 View 类会直接失败或运行时崩溃；
- 权利侧：这些类名（含 `NeteaseMusicSimpleDraweeView`）本身是竞品标识，
  随包分发与 `03-12 §7` / `NOTICE.md §4` 的处置路径冲突。

处置二选一：① 用自研 Compose / 自研 View 重写这 8 个布局；
② 若 V0.1 播放页仍走最小自研 UI（`12 §33`），直接删除不接入。
`docs/14 §4.1` 已实证唱片旋转是**纯代码属性动画**（`ValueAnimator` + `setRotation`），
**不加载任何 `res/anim/` XML**，因此动画资源不是重写阻塞项。

## 3. 阶段 C · 映射表与工具

1. 每项自研素材落盘后，把 `docs/assets-mapping.csv` 对应行 `replaced` 改 `true`，
   `notes` 填自研素材相对路径与（若适用）许可证来源；**61 行全 true** 才算完成。
2. `tools/check-assets.py` / `pre_release_check.py` 的 C4 语义：
   dev WARN → PASS、release `FAIL(C4)` → **PASS**，
   release 阻断从 `FAIL(C1, C4)` 收敛为**仅 C1**。
3. 同步 `NOTICE.md §3`：把「已导出 / 占位」改为「已全量替换为自有素材」，
   并删除「随 APK 分发竞品 layout」的风险描述。

## 4. 阶段 D · 从 APK 移除暂存区 + 接 `res/` / `:ui-legacy`

1. 删除（或迁出构建输入）`app/src/main/assets/legacy-player/` 全部 51 文件，
   确保 `zipfile` 检查 APK 内 `assets/legacy-player` 条目 **= 0**。
2. 需要的素材按 Android 资源约定落入 `app/src/main/res/` 或新建
   `:ui-legacy` 舱体（`12 §47` 预留），`legacy_*` 前缀统一改为自研命名。
3. **R 引用批量改写**：`docs/06` / `analysis/player-assets-export.json` 的
   `resource_id` → 新 R 字段；注意 9 个 layout 引用了 146 项资源与自定义 attr / View，
   `12 §47` 因此未直接接 res，本阶段需连同自定义 View / attr 一起处理或裁剪。
4. 更新 `docs/06 §4`、`docs/14`、C2 的 `C4_STAGED_ASSETS` 豁免（目录消失后
   `staged_skipped` 应归零，C2 变成**无豁免 PASS**）。

## 5. 阶段 E · 验证与回写

```
python tools/check_source_contracts.py
python tools/pre_release_check.py --mode dev        # 期望 PASS 6/6（C4 不再 WARN）
python tools/pre_release_check.py --mode release    # 期望仅 C1 阻断
./gradlew test :app:assembleDebug
python - <<'PY'
import zipfile
with zipfile.ZipFile('app/build/outputs/apk/debug/app-debug.apk') as z:
    assert not [n for n in z.namelist() if 'legacy-player' in n]
PY
```
真机：桌面启动 → 播放页 / 设置页渲染无崩溃；媒体键与通知控件正常。
回写：`12` 新批次、`03-12 §8` #1/#2/#5/#7 状态、`09 §2/§7`、
`analysis/task-backlog-2026-11-07.md`、`NOTICE.md`、任务清单与当日日志。

## 6. 长效守护（本批已加）

`tools/check_source_contracts.py` §61.36 守护：只要暂存区仍存在含
`com.netease.*` 的 layout，就要求 (a) `NOTICE.md` 明确写出「随 APK 分发」，
(b) `assets-mapping.csv` 全部 `replaced=false`，**防止在未真正替换时把 C4 标成已替换**。

## 7. 未完成前置（本轮不擅自推进）

- 自有素材来源与许可证（用户决策，阶段 A）；
- `:ui-legacy` 舱体结构与 R 引用批量改写工具（阶段 D）；
- 61 项逐项视觉规格确认（依赖 `docs/06` 与人工设计）。

> AI生成
