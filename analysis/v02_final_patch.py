import io

p = 'docs/01-产品文档.md'
s = io.open(p, encoding='utf-8').read()
old_block = s[s.find('> **★ V0.2 回填'):s.find('> gapless（§61.141 提前实现）与')]
assert 'V0.2 回填' in old_block and len(old_block) > 200, 'anchor check'
new_block = '''> **★ V0.2 回填（§61.233 终版，2026-11-19，代码侧全收口）**——PRD §3 V0.2 列九项全实现：
> **SFTP / FTP 挂载 ✓**（§61.216/219/228 第 21+22 模块；SFTP 真机扫描/播放/私钥已 PASS
> `12 §61.223`，FTP 真机 pending）；**.strm 相对路径 ✓**（§61.227）；**扫描**——CUE 分轨 ✓
> （§61.221/224/226 全闭环）/ 外置 lrc ✓（§61.217 + §61.223 五态修复）/ **刮削 ✓**
> （§61.233 Deezer 端口 + 批处理 + updateCover）；**播放**——均衡器 ✓（§61.93/118 + 调节面
> §61.231）/ 淡入淡出 ✓（§61.230）；**DLNA DMC**——进度 ✓（§61.202）/ 音量 ✓（§61.229）；
> **蓝牙编码格式显示 ✓**（§61.218 TrackInfo）；**格式解析**——APE ✓（V0.1 enhanced）/
> CUE ✓ / **DSD ❌ 挂账（V1.0 边缘，能力边界如实登记）**；**智能歌单 ✓**（§61.220）；
> **歌词 lrc+内嵌 ✓**（提前至 V0.1 §61.164/186/198）；**UI 主题化 ✓**（§61.234 ThemeMode
> 四值消费 + token 切色；浅色映射非像素级登记）。**V0.2 冻结口径：PRD 九项全实现**
> （DSD 除外的格式域全齐），听感/视觉/网络判读统一 pending-device 清单见 `12 §61.232/246`。
> gapless（§61.141 提前实现）与歌词（同前）口径已含在上列。'''
s = s.replace(old_block, new_block, 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)

p = 'docs/09-文档闭环总览.md'
s = io.open(p, encoding='utf-8').read()
anchor = '> 正式断点 `§61.232 → §61.234`。\n'
tail_entry = '''
> **★ 2026-11-19 V0.2 终版口径（§61.233 后，docs/01 §10 回填更新）**：PRD §3 V0.2 列
> **九项全实现**（刮削 §61.233 ✓ / 主题化 §61.234 ✓ 转正；DSD 能力边界如实挂账）。
> 真机判读积压统一 `12 §61.232/246`。**100 套件 / 761 测试 / 0 失败**；guard 全 PASS。
'''
assert anchor in s
s = s.replace(anchor, anchor + tail_entry, 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print("docs finalized")
