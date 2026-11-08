# T-L L3 · 素材暂存区分发合规审计（2026-11-08 晚）

> **★ 后续决策 D-146（2026-11-08 21:49）**：用户明确「素材直接用竞品的，
> 这个 APP 只是我个人使用」，项目范围锁定**个人自用 / 侧载**。
> 因此本报告的 §0 结论「公开发布前必须清除」与 §4 的风险定级，**仅在
> 未来改变范围、公开发布时适用**；当前版本按已知状态保留 51 个素材、
> 不做替换、不清除。实证数据（51 文件随包分发 / 8 个竞品 layout / C2 豁免）
> **仍然有效**，作为范围声明的事实依据保留。见 `12 §61.37`。
>
> 触发：用户选择「保持 A3 不收口，先推进 T-L L3 / T-V 其它项」。
> 本轮**零源码改动、零设备操作**；结论基于本机 `zipfile`、文件扫描与 C2 检查器源码。

## 0. 结论

- `app/src/main/assets/legacy-player/` 的 **51 个竞品素材全部随 debug APK 分发**。
  此前文档只写「assets 暂存、不参与 aapt2」，容易被误读为「不在包里」——
  **不参与编译 ≠ 不随包分发**。
- 其中 **8 个 layout XML 含 44 处 `com.netease.*` 类名**（20 个去重类，
  含带品牌名的 `NeteaseMusicSimpleDraweeView`）。
- **C2 的 PASS 是豁免下成立**：检查器跳过整个 `C4_STAGED_ASSETS` 目录
  （dev 报「扫描 153 文件、跳过 23 个 C4 追踪的未替换素材文件」），
  不等于「全库 / APK 无竞品内容」。
- 影响发布阻断 **#1 / #2 / #5 / #7**。个人自用 / 侧载按 `03-12 §7` 风险可控；
  **公开发布前必须清除**。处置方案见 `analysis/tl-l3-substitution-plan.md`。

## 1. 实证数据

| 项 | 值 | 方法 |
|---|---|---|
| 暂存素材 | **51 文件 / 320,569 字节** | `rglob` + 字节统计 |
| debug APK | `app/build/outputs/apk/debug/app-debug.apk` / 31,256,825 字节 | `os.stat` |
| APK 内 `assets/legacy-player/` 条目 | **51**（其中 9 个 layout） | `zipfile.ZipFile.namelist()` |
| 含 `com.netease.*` 的 layout | **8 文件 / 44 处引用** | 正则 `com\.netease\.[\w.]+` |
| 去重类名 | **20** | `Counter` |
| 中文品牌文案（网易 / 云音乐） | **0**（除类名外） | `grep -ri` |

**8 个文件**：`layout/legacy_are.xml`、`legacy_buu.xml`、`legacy_bv5.xml`、
`legacy_c6a.xml`、`legacy_e0g.xml`、`legacy_gb.xml`、`legacy_gc.xml`、`legacy_gr.xml`。

**20 个去重类**（摘录高频）：`RotationRelativeLayout`(8)、`MarqueeTextView`(5)、
`NeteaseMusicSimpleDraweeView`(4)、`PlayerDiscViewFlipper`(4)、
`AnimableDrawerImageView`(3)、`PlayerSeekBarNew`(2)、
`MarqueTextView`、`CircleProgressView`、`RotateFrameLayout`、
`RotationVinylView`、`CircleTouchView`、`TouchInterceptorRecyclerView`、
`PlayerLandScapePageMarqueeTextView`、`PlayerLandScapePageImageView`、
`MiLyricViewContainer`、`MiAnimViewRender` 等。

## 2. C2 豁免边界（`tools/check-no-netease-import.py:49-51`）

```python
if rel.startswith(C4_STAGED_ASSETS):
    staged_skipped += 1
    continue
```

`pre_release_check --mode dev` 输出：
`[PASS] 已扫描 153 个自研源码文件，无 com.netease.* 引用（另跳过 23 个 C4 追踪的未替换素材文件）`。

→ 该 PASS 的准确语义是「**自研源码**无引用」，**不包含暂存素材，也不覆盖 APK 内容**。
`03-12 §8` #5 若只引用 C2 结果会高估合规状态，本轮已在 §8 加注。

## 3. 发布阻断影响（`03-12 §8`）

| # | 条件 | 此前登记 | 本轮修正 |
|---|---|---|---|
| 1 | 素材全量替换 | ✗ 61 行 `replaced=false` | 不变，仍是头号阻断（C4 release FAIL） |
| 2 | 无第三方品牌特征 | ⚠「未含品牌字样，仅默认图标」 | **修正**：APK 内 20 个竞品类名为权利 / 品牌特征，**不能判 PASS** |
| 5 | 无 `com.netease.*` 外泄 | ✓ C2 PASS | **修正**：豁免下成立；分发侧仍有 44 处，需如实登记 |
| 7 | `NOTICE.md` / 声明 | ✓ 齐全 | **补充**：需声明这些文件**随 APK 分发**，而非仅「已导出暂存」 |

Manifest 现状复核：`app/src/main/AndroidManifest.xml` 无 `android:label` / `android:icon`，
`app/src/main/` 无 `res/`，应用名与图标为系统默认 —— **未含第三方品牌，但也未自定义**。

## 4. 风险定级与边界

**P1（分发合规），不是运行时缺陷**：assets 不参与 aapt2，不会因找不到
`com.netease.*` 类而崩溃；但随包分发竞品 layout / 类名，与 AGPL-3.0 开源及
`NOTICE.md §4` 的权利状态声明并不自洽。按 `03-12 §7`，个人自用 / 侧载风险可控；
公开发布仓库 / 分发 APK 前必须先完成 `tl-l3-substitution-plan.md` 的阶段 B~D。

本轮**未删除、未替换、未改动任何产物**，只登记事实与处置路径，避免把
「开发期占位」误当成「已合规」。

## 5. 复跑命令

```bash
python - <<'PY'
from pathlib import Path
import re, zipfile
root = Path('app/src/main/assets/legacy-player')
pat = re.compile(r'com\.netease\.[\w.]+')
files = [p for p in root.rglob('*') if p.is_file()]
hits = {p.relative_to(root).as_posix(): len(pat.findall(p.read_text(encoding='utf-8', errors='replace')))
        for p in files if pat.search(p.read_text(encoding='utf-8', errors='replace'))}
print('files', len(files), 'branded layouts', len(hits), 'refs', sum(hits.values()))
with zipfile.ZipFile('app/build/outputs/apk/debug/app-debug.apk') as z:
    print('apk staged entries', len([n for n in z.namelist() if 'legacy-player' in n]))
PY
python tools/pre_release_check.py --mode dev | grep -A2 '\[C2\]'
```

> AI生成
