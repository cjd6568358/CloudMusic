# F4 · `Write start play` 日志门控反汇编（§61.74，2026-11-11）

> **性质**：纯离线静态二进制证据（capstone 5.0.7 + pyelftools），
> 不连 adb、不改设备现场、零生产改动。结论是 **inference**，
> 不是 F4 修复、不是真机验证。**F4 仍 BLOCKED**。

## 1. 问题

`§61.73` 三轮真机：`Write start play` **0 次**，但冷/暖轮已有
`Open codec for aac` 与 `doPrepareAsync ... mDuration 2933`。
F4 一直把 `Write start play` 当「播放写 AudioTrack 开始」的判据，
需确认该日志 0 次能否单独推出「未写 AudioTrack」。

## 2. 静态证据

- so：`libncmaudioplayer.so` sha256 `5bf085f4020e…`（1171992 B）。
- `.rodata @0xe180a` = `AudioTrack(%d): Write start play nErr:%d`；tag `@0xdd1b3` = `NMAudioPlayer`。
- 全 `.text` 扫描 `adrp + (add|ldr)` 页内引用：该格式串**唯一**引用点
  `0x66eec/0x66ef4`，打印调用 `0x66f00 bl 0x5c18c`（函数窗口
  `0x66e44~0x66fa8`）。
- **到达打印点前有 4 道静默早退门控**：

| # | 指令 | 目标 | 语义 |
|---|---|---|---|
| 1 | `0x66e70 / 0x66e74` | `0x66f50` | [this+0x1e8] 为空 → 返回 -1，不打印 |
| 2 | `0x66ea8 / 0x66eac` | `0x66f40` | [this+0x1e8] 二次判空 → 收尾 -1，不打印 |
| 3 | `0x66eb0 / 0x66eb4` | `0x66f5c` | [this+0x20c] 非零 → 跳过打印，直接进入后续路径 |
| 4 | `0x66ec8 / 0x66ecc` | `0x66f5c` | bl 0x13d80 返回 3 → 跳过打印 |

只有 4 道全部放行，`0x66edc bl 0x14500` 的返回值才作为 `nErr`
在 `0x66f00` 打印；`nErr==0` 时 `0x66f58` 清零 `[this+0x354]`
重试计数，`nErr!=0` 且计数 >9 时 `0x66f28` 经 `[this+0xd8]`
vtable+0x20 回调传 **-3**。

## 3. 与 §61.73 真机证据交叉

- cold：`Write start play` 0 次、codec=aac、`-103` True、commit=[253952]
- warm：`Write start play` 0 次、codec=aac、`-103` True、commit=[262144]
- seek：`Write start play` 0 次、codec=无、`-103` False、commit=[262144]
- 下线前只读补证：`MediaSession state=2 (PAUSED)` / `position=0` /
  `metadata: null`；`dumpsys audio` 无本 app 的媒体 AudioTrack。

## 4. 结论（inference，须人工复核）

**`Write start play` 0 次不是「没有写 AudioTrack」的充分证据** ——
该日志点自身有 4 道静默早退，`[this+0x1e8]` / `[this+0x20c]` /
`0x13d80` 返回值任一命中都不打印。因此：
① 不能仅凭该日志 0 次断定播放链没走写线程；
② 更不能反过来推断「播放其实成功了」—— `MediaSession PAUSED`、
  `metadata:null`、无本 app AudioTrack、`-103` 在场是**独立的一手
  证据**，§61.73「播放未出声 / 未进入 PLAYING」结论仍然成立；
③ **F4 判据口径需修正**：后续验收不单独依赖 `Write start play`，
  应并用 `MediaSession state=3(PLAYING)` + 本 app AudioTrack
  `state:started` + 自动进曲 3 首无 `-103` + seek / ENDED。
- 下一步可验证锚点：`[this+0x1e8]`（AudioTrack 对象）、
  `[this+0x20c]`（状态字节）、`0x13d80` 返回值、`[this+0x354]`
  （重试计数）。**均需真机或动态插桩，本批未做**。

## 5. 实跑核验与边界

- checks：**16 项 / 全 PASS True**；失败项：无
- `§61.71` 固化回放证据、`§61.73` 三轮审计证据**零改写**；
  本工具只读它们做交叉，不重跑、不覆盖。
- **F4 仍 BLOCKED**：`-103` 判据未通过、播放未进入 PLAYING、
  自动进曲 3 首 / seek / ENDED 仍 NOT RUN、F3 14 格未改写；
  A3 仍 G09 PARTIAL、舱不冻结、指纹不回填。
- 零 P0 源码改动、未重编译 / 未重装、so 二进制原样、未改用户网络；
  §61.67~§61.73 历史正文与证据 JSON 一字未改。
- 静态证据等级提升 ≠ 修复 ≠ 验证；不写任何提前收口结论。

> AI生成
