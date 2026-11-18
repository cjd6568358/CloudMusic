import io

p = 'docs/09-文档闭环总览.md'
s = io.open(p, encoding='utf-8').read()

anchor = '> **★ §61.232 现行断点（2026-11-19 第二百四十四批，V0.2 收官 —— 验收回填 + 挂账清点，M12）**：'
assert anchor in s, '232 head anchor missing'

new_heads = '''> **★ §61.235 现行断点（2026-11-19 第二百四十七批，DSD 格式探测 + FTPS —— V0.2 格式域收尾）**：
> AudioProbe 补 DSD/DFF 魔数（DSF "DSD " / DFF "FRM8"；AUDIO_EXT 白名单已含 dsf/dff——
> 入库与 JAudioTagger tag 链就绪）；FTPS 显式 TLS（FtpConfig.ssl + FTPSClient(true) +
> FtpDialog 勾选行）。**101/765/0**（+1 套件/+4 用例）；守护 §61.235 A/B 反证 PASS。
> DSD 解码出声受引擎限制（autoSkipOnError 兜底）如实登记。详见 `12 第二百四十七批`。

> **★ §61.234 现行断点（2026-11-19 第二百四十五批，UI 主题化 —— V0.2 M11）**：
> ThemeMode 四值（死配置）→ UI 消费闭环：Comp/FnComp 基色 var 化 + applyTheme 切色
>（深色基准原值 / 浅色通用映射非像素级登记）+ 启动 applyThemeFromSettings + 设置页
> 四选项 + recreate 生效链。品牌色恒定。**98/751/0**；守护 §61.234 A/B 反证 PASS。
> 浅色视觉判读 pending-device。详见 `12 第二百四十五批`。

> **★ §61.233 现行断点（2026-11-19 第二百四十六批，封面在线刮削 —— V0.2 刮削收口）**：
> Deezer 端口（parseDeezerCover 三级兜底纯函数）+ CoverScrapeSession（缺封面可刮源批处理，
> 手动触发限数 50 不挂扫描期）+ updateCover 四件套（只写缺封面行）+ 设置页入口。
> **100/761/0**（+2 套件/+10 用例）；守护 §61.233 A/B 反证 PASS。真机判读 pending-device。
> **V0.2 扫描域（CUE/外置 lrc/刮削）全部收口**。详见 `12 第二百四十六批`。

> **★ §61.232 现行断点 → 上一批（2026-11-19 第二百四十四批，V0.2 收官 —— 验收回填 + 挂账清点，M12）**：'''
s = s.replace(anchor, new_heads, 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('head checkpoints inserted')
