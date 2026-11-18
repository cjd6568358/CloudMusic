import io

# docs/12 第二百五十批（判读批 + SurfaceNav 修复）
p = 'docs/12-编码阶段第一批交付.md'
s = io.open(p, encoding='utf-8').read()
batch = '''> AI生成

## 第二百五十批（§61.238）：真机判读窗口收口 —— 外置 lrc 端到端 PASS + SurfaceNav 三页直达修复（§61.223 挂账收口）

**触发**：设备 192.168.1.11:40489 重新上线（用户配对；亮屏在场）。判读积压窗口开。

### ② 判读结果（§61.232/246 积压清单逐项）

| 判据 | 结果 | 一手证据 |
|---|---|---|
| **外置 lrc 端到端**（§61.223 挂账） | **PASS** | `cache/lyrics/0339b783….lrc`（74 字节 = nas_song.lrc 全文）+ server 日志：nas_song.lrc 两次 readText（offset 0→74→32755）——**ref 五态修复后的驱动分块读链真机闭环**；判读服务器 = server_mt.py（多线程 + PrefixedHandle offset 读） |
| CUE 分轨扫描入库 | **PASS（复验）** | 快照 statHit skipped=4 + accepted=1（nas_song.mp3 mtime 变化重处理）；分轨锚点/N轨元数据既有（§61.223 已证） |
| **播放真值时长**（§61.181 链佐证） | **PASS** | 播放页 03:43 真值（非占位）+ 进度条比例正确（截图 61-238-player-trackinfo.png） |
| **SurfaceNav 三页直达** | **修复 + PASS** | OPEN_LIBRARY 从播放页被覆盖层吞掉（LIBRARY/SEARCH/SETTINGS 分支缺 showPlayer=false）→ 修复后曲库页正常 + 音乐库 tab 选中态（截图 61-238-library-cue2.png） |
| CUE 分轨点播从 cueStartMs 出声 | pending | 恢复队列=上一会话 STRM 曲目，UI 点播不可注入（INJECT_EVENTS）——已有证据链（锚点落库+LOCAL 链成熟+单测 5 例）支撑；直接判读维持 pending |
| TrackInfo 面板视觉 | pending | ⓘ 面板需 UI 点击（面板数据链 F1 单测覆盖） |
| 智能歌单点击播放 / fade/EQ 听感 / DLNA 音量听感 / 浅色视觉 | pending | 需 UI 注入或物理听感——维持统一窗口 |

### ③ 落地（判读中发现的修复）

| 件 | 内容 |
|---|---|
| `CloudMusicApp.kt` SurfaceNav 分支 | LIBRARY/SEARCH/SETTINGS 三分支补 `showPlayer = false`——覆盖层未关导致 intent 直达被播放页吞掉（§61.183 验收通道完整性缺口） |
| 工程债 | 临时补丁脚本八件出库（.gitignore）+ git config lfs.locksverify false（push TLS 抖动） |

### ④ 收尾

- 判读后设备 DataStore 已恢复单源（webdav-strm-probe）、reverse 移除、判读服务器停运。
- 全量 PASS：**101 套件 / 765 测试 / 0 失败**（SurfaceNav 修复为 UI 项零新用例）；
  `:app:assembleDebug` PASS；guard 全 PASS。

> AI生成'''
s = s.replace('> AI生成\n\n## 59. 第四十九批交付', batch + '\n\n## 59. 第四十九批交付', 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)

# docs/09 断点 + 文末条目
p = 'docs/09-文档闭环总览.md'
s = io.open(p, encoding='utf-8').read()
old_head = '> **★ §61.237 现行断点（2026-11-19 第二百四十九批，自然结束 fade-out —— armed 协调）**：'
new_head = '''> **★ §61.238 现行断点（2026-11-19 第二百五十批，真机判读窗口 —— 外置 lrc 端到端 PASS + SurfaceNav 修复）**：
> 设备 40489 窗口：**外置 lrc 端到端 PASS**（cache/lyrics 落盘 + server 日志分块读链闭环，
> §61.223 挂账收口）+ 播放真值时长佐证 + **SurfaceNav 三页直达修复**（覆盖层未关吞 intent
> → LIBRARY/SEARCH/SETTINGS 补 showPlayer=false）+ CUE 入库复验。CUE 点播/TrackInfo 面板/
> 听感类判读 pending（UI 注入硬阻塞）。**101/765/0**；设备已恢复单源。详见 `12 第二百五十批`。

> **★ §61.237 上一批断点（2026-11-19 第二百四十九批，自然结束 fade-out —— armed 协调）**：'''
assert old_head in s, 'head anchor'
s = s.replace(old_head, new_head, 1)
anchor = 'seek 干预优先）。正式断点 `§61.236 → §61.237`。'
assert anchor in s, 'tail anchor'
entries = '''
> **★ 2026-11-19 第二百五十批（`docs/12 §61.238`）**：本文 §2 已追加现行断点；
> 此前进度叙事按纪律不改写。外置 lrc 端到端收口（§61.223 挂账）+ SurfaceNav 修复；
> CUE 点播/TrackInfo 面板/听感类 pending。正式断点 `§61.237 → §61.238`。
'''
s = s.replace(anchor, anchor + entries, 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)

# docs/17 §8.163
p = 'docs/17-竞品模块清单与复刻规划.md'
s = io.open(p, encoding='utf-8').read()
s += '''
### 8.163 真机判读窗口（§61.238，2026-11-19 第二百五十批）

- **外置 lrc 端到端 PASS**（§61.223 挂账收口）：cache/lyrics 落盘 + server 日志确认
  ref 五态修复后的驱动分块读链真机闭环。
- **SurfaceNav 三页直达修复**：LIBRARY/SEARCH/SETTINGS 补 showPlayer=false（覆盖层吞
  intent 缺口）——真机曲库页 + tab 选中态 PASS。
- **播放真值时长佐证**（03:43 非占位）+ CUE 入库复验（statHit 口径）。
- **pending**：CUE 点播 cueStartMs 出声 / TrackInfo 面板视觉 / 智能歌单点击 / fade+EQ
  听感 / DLNA 音量听感 / 浅色视觉——UI 注入（INJECT_EVENTS）硬阻塞 + 听感需在场。
- **闸门**：**101 套件 / 765 测试 / 0 失败**；guard 全 PASS；`:app:assembleDebug` PASS。
'''
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print("docs ok")
