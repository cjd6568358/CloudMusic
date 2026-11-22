# F4 阻塞链路 1:1 复现（Kotlin 逻辑翻译）

> 证据：docs/12 §61.65 ~ §61.77；真机结论 §61.70 / §61.75。
> 本复现由 `reverse-engine/kt/src/*.kt` 的真实逻辑运行得出，非手工叙述。

| # | 步骤 | 观测 | 证据锚点 |
|---|------|------|----------|
| 1 | 日志门控初态 | g_level=0 → I/D/W/E 默认全开；不存在「需要额外开启的 level-5 开关」 | G-03 / G-04 / G-05（§61.70 语义纠错） |
| 2 | 发起首个 Range 请求 | 代次=1, offset=0, length=16384, signal_size=8192（代次递增点 0x104B8） | N-01 / N-02 / N-05 / N-22（§61.67） |
| 3 | 正常顺序写入 4×4096 | 4 包全部通过代次校验；commit=16384；OnNetSourceData→写任务队列→OnWriteNetData→NMSlicesFile::Write all finish；落盘返回=[0, 0, 0, 0]，分片写调用=4，NotifyCacheProgress 次数=4；正常顺序写入不打 UnExpect（§61.68 两轮 0 命中是正确行为） | N-15 / N-17 / N-20 / N-21 + B-01~B-15 + S-16~S-21（§61.65） |
| 4 | 跳跃 offset 的 W 级告警（只打印、不丢包） | commit(16384) != offset(20480) → 打印 UnExpect New Offsetcommted，命中 1 次；结果=ACCEPTED(commit=24576)，commit 仍更新为 24576（上线复跑的判据格式串） | N-15 / N-16 + G-14（§61.65 / §61.68 runbook） |
| 5 | PreSeekReadTask 回退连续提交点并重新发请求 | commit 回退到 16384；回退内部再调 MakeRangeRequestAsync 使代次递增为 2（对应真机 §61.62 回退 368640 后 Open codec for aac 随即 -103） | N-30 + §61.62 真机 vfile commit 观察 |
| 6 | 播放中连续 seek → 请求代次持续递增 | 代次 2 → 22（真机观测 1→22 递增 + 重发） | N-03 / N-04 / N-05（§61.67 代次闸门） |
| 7 | 旧代次（gen=1）缓存包被重发 → 整包丢弃 | 0x10790 ldr / 0x10794 cmp / 0x10798 b.ne 0x108E0 命中；droppedPacketCount=8；commit 冻结在 16384；丢弃路径既不是 0x1086C 投递、也不写 commit、不置 +0xB8、不 notify_all | N-12 / N-13 / N-14 / N-25（§61.65 / §61.67，F4 真断点） |
| 8 | WaitRequest 读空 → result:-2 | commit(16384) < expectOffset(20480) → WaitRequest=WAIT_STARVED(1) → ReadFromFileNoLock 映射为 -2；starvedReadCount=1 | N-26 / N-28 / N-29 + B-21 / B-22 / B-29（§61.77） |
| 9 | 挂起异步读 → Finish Async:-103 原样返回播放侧 | [+0x198] 非零才把 [+0x1A0] 写成 -0x67；Finish Async 打印并原样返回 -103；finishAsyncLogCount=1 | B-23 / B-24 / B-25 / B-26 / B-27 / B-28（§61.76） |
| 10 | AudioTrack paused / release → Write start play 静默门控 | paused 时门控 3 命中 → SKIP_PRINT；release 后门控 1 命中 → EARLY_RETURN_MINUS_1；Write start play 打印次数=0（真机 0 次） | A-01~A-07 / A-10 / A-11（§61.74 / §61.75） |
| 11 | 结论 | 代次错配 → commit 冻结 16384 → result:-2 → 挂起异步读 → Finish Async:-103 → AudioTrack paused/release → Write start play 命中 0 次 → 无出声。F4 仍 BLOCKED（与 12 §61.70 / §61.75 真机结论一致；断点在代次门控，不是日志级别）。 | §61.65~§61.77 全链 |

## 终态快照

```text
initialGlobalLevel      = 0
finalGeneration         = 22
frozenCommit            = 16384
droppedPackets          = 8
unexpectOffsetLogCount  = 1
waitStarvedResult       = -2
finishAsyncCode         = -103
outcomeWhenPaused       = SKIP_PRINT
outcomeWhenReleased     = EARLY_RETURN_MINUS_1
writeStartPlayLogCount  = 0
sliceWriteCalls         = 5
cacheProgressCount      = 5
```

## 完成通知链旁路验证（§61.54 / §61.55，静态分支语义）

> **非 F4 因果环节**：以上 11 步是 §61.70 / §61.75 真机因果链；本节是
> §61.54 / §61.55 纯离线反汇编坐实的 `OnNetSourceDataDone` 分支语义，
> 用已翻译的 Kotlin 逻辑独立跑一遍，不宣称它在 F4 真机里按此时序发生，
> 也不把它当作 F4 的根因（防静态推断被写成「已修复」）。

| kind | 投递任务 | drain 结果 | 级联投递 | 尾调用 |
|------|----------|------------|----------|--------|
| 2（预加载完成） | PreloadDoneWrite | PRELOAD_WRITE_CASCADE -> PRELOAD_RENDER_DONE | 1 | - |
| 1（刷数据） | Flush | FLUSH_TAIL_CALL | 0 | 1 |

- 两分支共投递同一 `0x16468` 队列 2 次；`kind==2` 的 Render 完成通知 = true。
- `kind==2` 是 288B / 72 指令的重任务并级联 420B / 105 指令的 Render 任务；`kind!=2` 是 88B / 22 指令的轻量尾调用，二者**不对称**。
- 源证据 l3-f4-disasm-verify.json 10/10 checks + l3-f4-task-diff.json 14/14 checks 全通过；F4 仍 BLOCKED。

## 结论

代次错配 → commit 冻结 16384 → result:-2 → 挂起异步读 → Finish Async:-103 → AudioTrack paused/release → Write start play 命中 0 次 → 无出声。F4 仍 BLOCKED（与 12 §61.70 / §61.75 真机结论一致；断点在代次门控，不是日志级别）。
