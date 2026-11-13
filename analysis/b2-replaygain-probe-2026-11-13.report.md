# §61.107 ReplayGain 真机两格对照（2026-11-13）

- device_run = true，端点 `10.0.0.2:41971`，每格等待 8.0s，判据 **3/5 PASS**。
- 期望 TRACK 音量 = `10^(-6.54/20)` ≈ `0.4710`。

| 格 | 样本 | READY | PLAYING | replaygain 日志 | gain | volume |
|---|---|---|---|---|---|---|
| tagged | f3_mp3_rg.mp3 | True | True | False | None | None |
| plain | f3_mp3.mp3 | True | True | False | None | None |

## 一手日志

- **tagged**：`<无>`
- **plain**：`<无>`

## 判据明细

| # | 判据 | 结果 | 证据 |
|---|---|---|---|
| 1 | 两格均 READY(duration>0) + PLAYING | PASS | tagged=True/True plain=True/True |
| 2 | 带标签格出现 replaygain 日志且增益非 none | FAIL | [] |
| 3 | 带标签格音量 ≈ 0.4710（10^(-6.54/20)） | FAIL | volume=None |
| 4 | 无标签对照格保持 volume=1.000（不误施增益） | PASS | gain=None volume=None |
| 5 | 两格均无播放错误回调 | PASS | tagged=0 plain=0 |

## 诚实边界

只验证含 ID3 TXXX REPLAYGAIN_* 标签的 MP3 LOCAL 通道；不验证其它容器 / REMOTE / 扫描式 ReplayGain，不宣称全格式生效；未改 Manifest / CMake / 生产构建配置，未做破坏性注入。
