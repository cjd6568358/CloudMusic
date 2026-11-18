import io

p = 'docs/09-文档闭环总览.md'
s = io.open(p, encoding='utf-8').read()

old = '> **★ §61.232 现行断点（2026-11-19 第二百四十四批，V0.2 收官 —— 验收回填 + 挂账清点，M12）**：'
new = '''> **★ §61.234 现行断点（2026-11-19 第二百四十五批，UI 主题化 —— V0.2 M11）**：
> ThemeMode 四值（死配置）→ UI 消费闭环：Comp/FnComp 基色 var 化 + applyTheme 切色
>（深色基准原值 / 浅色通用映射非像素级登记）+ 启动 applyThemeFromSettings + 设置页
> 四选项 + recreate 生效链。品牌色恒定。**98/751/0**；守护 §61.234 A/B 反证 PASS。
> 浅色视觉判读 pending-device。详见 `12 第二百四十五批`。

> **★ §61.233 现行断点（2026-11-19 第二百四十六批，封面在线刮削 —— V0.2 刮削收口）**：
> Deezer 端口（parseDeezerCover 三级兜底纯函数）+ CoverScrapeSession（缺封面可刮源批处理，
> 手动触发限数 50 不挂扫描期）+ updateCover 四件套（只写缺封面行）+ 设置页入口。
> **100/761/0**（+2 套件/+10 用例）；守护 §61.233 A/B 反证 PASS。真机判读 pending-device。
> **V0.2 扫描域（CUE/外置 lrc/刮削）全部收口**。详见 `12 第二百四十六批`。

> **★ V0.2 终版口径（§61.233 后，docs/01 §10 回填更新）**：PRD §3 V0.2 列**九项全实现**
>（刮削 §61.233 ✓ / 主题化 §61.234 ✓ 转正；DSD 能力边界如实挂账）。真机判读积压统一
>`12 §61.232/246`。**100 套件 / 761 测试 / 0 失败**；guard 全 PASS。

> **★ §61.232 上一批断点（2026-11-19 第二百四十四批，V0.2 收官 —— 验收回填 + 挂账清点，M12）**：'''
assert old in s, '232 head'
s = s.replace(old, new, 1)

# 文末条目：找最后一个「正式断点」区（§61.230 → §61.231 的文末条目）
tail_anchor = None
for candidate in ('> 正式断点 `§61.230 → §61.231`。\n',
                  '> 正式断点 `§61.229 → §61.230`。\n'):
    if candidate in s:
        tail_anchor = candidate
        break
assert tail_anchor, 'no tail anchor'
entries = '''
> **★ 2026-11-19 第二百四十五批（`docs/12 §61.234`）**：本文 §2 已追加现行断点；
> 此前进度叙事按纪律不改写。UI 主题化收口（V0.2 M11；浅色映射非像素级登记）。
> 正式断点 `§61.231 → §61.234`。

> **★ 2026-11-19 第二百四十六批（`docs/12 §61.233`）**：本文 §2 已追加现行断点；
> 此前进度叙事按纪律不改写。封面刮削收口（V0.2 扫描域三件套全齐）。
> 正式断点 `§61.232 → §61.233`（编号倒挂如实登记：245 批=§61.234 先 push、246 批=§61.233 后 push）。
'''
s = s.replace(tail_anchor, tail_anchor + entries, 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('docs/09 backfilled cleanly')
