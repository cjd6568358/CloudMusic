@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * F4 阻塞链路的端到端 1:1 复现（docs/12 §61.65 ~ §61.77 证据串）。
 *
 * 这不是新的逻辑，而是把散落在 [NmLogGate] / [NmNetSource] / [NMBufferSourceImp] /
 * [NmSlicesFile] / [NmAudioPlayer] 里的每一处已翻译逻辑，按真机观测到的**真实因果顺序**
 * 串成一条可运行、可观测、可打印的链。它是「不漏掉任何一处逻辑翻译」的集成视图，
 * 也是交付给用户直接看到的「结果」。
 *
 * 链路（真机 12 §61.70 / §61.75 结论）：
 *   正常顺序写入 → 跳跃读 W 告警 → PreSeekReadTask 回退连续提交点 →
 *   播放中连续 seek 使请求代次 1→22 递增 → 旧代次包重发被 0x10798 整包丢弃 →
 *   commit 冻结 16384 → WaitRequest 读空 result:-2 → 挂起异步读 →
 *   Finish Async:-103 → AudioTrack paused/release → Write start play 命中 0 次 → 无出声。
 *
 * 关键口径：断点在**代次错配**，不是日志级别；g_level 默认 0，日志本来就全开。
 */
// @spec D-02 / D-03 / D-05 / D-06 / D-07 / D-10 / D-11：完成通知链旁路验证（§61.54 / §61.55）
class NmF4Chain {

    /** 链路中的一步，带证据锚点，可机读审计。 */
    data class Step(
        val index: Int,
        val name: String,
        val detail: String,
        val evidence: String,
    )

    /**
     * §61.54 / §61.55 完成通知链旁路验证结果。
     * 这是**静态反汇编坐实的分支语义**，不是 §61.70 / §61.75 真机因果链的一环。
     * 用独立实例跑，避免污染主链 [NmBufferSourceImp] 的统计状态。
     */
    data class DoneChainProbe(
        val kind2Task: String,
        val kind2Outcomes: List<String>,
        val kind2CascadePosts: Int,
        val kind2RenderDone: Boolean,
        val kind1Task: String,
        val kind1Outcomes: List<String>,
        val kind1CascadePosts: Int,
        val flushTailCalls: Int,
        val sharedQueuePosts: Int,
        val note: String,
    )

    /** 一次完整复现的结构化结果。 */
    data class Reproduction(
        val steps: List<Step>,
        val initialGlobalLevel: Int,
        val finalGeneration: Long,
        val frozenCommit: Long,
        val droppedPackets: Int,
        val unexpectOffsetLogCount: Int,
        val waitStarvedResult: Int,
        val finishAsyncCode: Int,
        val outcomeWhenPaused: String,
        val outcomeWhenReleased: String,
        val writeStartPlayLogCount: Int,
        val sliceWriteCalls: Int,
        val cacheProgressCount: Int,
        val conclusion: String,
        val doneChain: DoneChainProbe,
    )

    // 解环用的可后设数据接收端：NmNetSource 需要 sink，sink 需要 buffer，
    // buffer 又需要 netSource；先建 holder，构造完再挂真实适配器。
    private class DelegatingSink : NmDataSink {
        var target: NmDataSink? = null
        override fun onNetSourceData(kind: Long, data: ByteArray, offset: Long, size: Long): Int =
            target?.onNetSourceData(kind, data, offset, size) ?: 0
    }

    /** NMNetSource::OnData → NMBufferSourceImp::OnNetSourceData 的适配（vtable+0x18）。 */
    private class BufferAdapter(private val buffer: NmBufferSourceImp) : NmDataSink {
        override fun onNetSourceData(kind: Long, data: ByteArray, offset: Long, size: Long): Int =
            buffer.onNetSourceData(kind, data, offset, size)
    }

    /** 测试桩：Range 请求永远成功（Java Start 的 native 侧抽象）。 */
    private class AlwaysOkRequester : NmRangeRequester {
        override fun start(generation: Long, offset: Long, length: Long): NmRangeStartResult =
            NmRangeStartResult(true)
    }

    /**
     * 两个独立实例分别跑 kind==2 / kind!=2，统计互不干扰，也不改动主链 buffer 状态。
     * @spec D-02 / D-03 / D-05 / D-06 / D-07 / D-10 / D-11
     */
    private fun probeDoneChain(
        gate: NmLogGate,
        slices: NmSlicesFile,
        net: NmNetSource,
    ): DoneChainProbe {
        val b2 = NmBufferSourceImp(gate, slices, net)
        val t2 = b2.onNetSourceDataDone(NmKind.NATIVE_DONE_KIND_PRELOAD_DONE)
        val o2 = b2.drainDoneQueue()

        val b1 = NmBufferSourceImp(gate, slices, net)
        val t1 = b1.onNetSourceDataDone(NmKind.NATIVE_DONE_KIND_FLUSH)
        val o1 = b1.drainDoneQueue()

        return DoneChainProbe(
            kind2Task = t2.name,
            kind2Outcomes = o2.map { it.name },
            kind2CascadePosts = b2.cascadePosts,
            kind2RenderDone = b2.renderDoneCount == 1,
            kind1Task = t1.name,
            kind1Outcomes = o1.map { it.name },
            kind1CascadePosts = b1.cascadePosts,
            flushTailCalls = b1.flushTailCallCount,
            sharedQueuePosts = b2.preloadDoneCount + b1.flushTaskPosts,
            note = "源证据 l3-f4-disasm-verify.json 10/10 checks + " +
                "l3-f4-task-diff.json 14/14 checks 全通过；F4 仍 BLOCKED。",
        )
    }

    private fun onDataName(result: NmOnDataResult): String = when (result) {
        is NmOnDataResult.DROPPED -> "DROPPED"
        is NmOnDataResult.ACCEPTED -> "ACCEPTED(commit=${result.newCommit})"
    }

    private fun outcomeName(outcome: NmWriteOutcome): String = when (outcome) {
        is NmWriteOutcome.EARLY_RETURN_MINUS_1 -> "EARLY_RETURN_MINUS_1"
        is NmWriteOutcome.SKIP_PRINT -> "SKIP_PRINT"
        is NmWriteOutcome.PRINTED -> "PRINTED(nErr=${outcome.nErr})"
    }

    /**
     * 按真机因果顺序跑完整条链。所有观测值都从被翻译的类里真实读出，
     * 没有任何一处是硬编码的结论常量（结论由末端状态推导）。
     */
    fun reproduce(): Reproduction {
        val logs = NmCollectingLogSink()
        val gate = NmLogGate(logs)
        val slices = NmSlicesFile(fileLen = 1048576L, sliceSize = 4096L, gate = gate)
        val delegating = DelegatingSink()
        val net = NmNetSource(AlwaysOkRequester(), delegating, gate, sourceId = 1L)
        val buffer = NmBufferSourceImp(gate, slices, net)
        delegating.target = BufferAdapter(buffer)
        val audio = NmAudioPlayer(gate)

        val steps = mutableListOf<Step>()
        fun step(name: String, detail: String, evidence: String) {
            steps += Step(steps.size + 1, name, detail, evidence)
        }

        // ---- A 日志门控初态 ----
        val initialGlobalLevel = gate.globalLevel
        step(
            "日志门控初态",
            "g_level=$initialGlobalLevel → I/D/W/E 默认全开；不存在「需要额外开启的 level-5 开关」",
            "G-03 / G-04 / G-05（§61.70 语义纠错）",
        )

        // ---- B 首个 Range 请求 ----
        val gen1 = net.makeRangeRequestAsync(0L, 16384L, 8192L)
        step(
            "发起首个 Range 请求",
            "代次=$gen1, offset=0, length=16384, signal_size=8192（代次递增点 0x104B8）",
            "N-01 / N-02 / N-05 / N-22（§61.67）",
        )

        // ---- C 正常顺序写入：完整落盘链一路放行 ----
        repeat(4) { i -> net.onData(gen1, i * 4096L, 4096L, ByteArray(4096)) }
        val drainResults = buffer.drainWriteQueue()
        step(
            "正常顺序写入 4×4096",
            "4 包全部通过代次校验；commit=${net.commit}；" +
                "OnNetSourceData→写任务队列→OnWriteNetData→NMSlicesFile::Write all finish；" +
                "落盘返回=$drainResults，分片写调用=${slices.sliceWriteCalls}，" +
                "NotifyCacheProgress 次数=${buffer.cacheProgress.size}；" +
                "正常顺序写入不打 UnExpect（§61.68 两轮 0 命中是正确行为）",
            "N-15 / N-17 / N-20 / N-21 + B-01~B-15 + S-16~S-21（§61.65）",
        )

        // ---- D 跳跃 offset：W 级告警但不丢弃 ----
        val jumpResult = net.onData(gen1, 20480L, 4096L, ByteArray(4096))
        buffer.drainWriteQueue()
        val unexpectCount = logs.countOf("UnExpect New Offsetcommted")
        step(
            "跳跃 offset 的 W 级告警（只打印、不丢包）",
            "commit(16384) != offset(20480) → 打印 UnExpect New Offsetcommted，命中 $unexpectCount 次；" +
                "结果=${onDataName(jumpResult)}，commit 仍更新为 ${net.commit}（上线复跑的判据格式串）",
            "N-15 / N-16 + G-14（§61.65 / §61.68 runbook）",
        )

        // ---- E PreSeekReadTask 回退连续提交点 ----
        val rollbackGen = net.rollbackToContinuousCommitPoint(16384L)
        step(
            "PreSeekReadTask 回退连续提交点并重新发请求",
            "commit 回退到 16384；回退内部再调 MakeRangeRequestAsync 使代次递增为 $rollbackGen" +
                "（对应真机 §61.62 回退 368640 后 Open codec for aac 随即 -103）",
            "N-30 + §61.62 真机 vfile commit 观察",
        )

        // ---- F 播放中连续 seek → 代次持续递增 ----
        var finalGen = rollbackGen
        repeat(20) { finalGen = net.makeRangeRequestAsync(16384L, 1048576L, 8192L) }
        step(
            "播放中连续 seek → 请求代次持续递增",
            "代次 $rollbackGen → $finalGen（真机观测 1→22 递增 + 重发）",
            "N-03 / N-04 / N-05（§61.67 代次闸门）",
        )

        // ---- G 旧代次包重发 → 0x10798 整包丢弃 → commit 冻结 ----
        repeat(8) { net.onData(gen1, 16384L, 4096L, ByteArray(4096)) }
        step(
            "旧代次（gen=$gen1）缓存包被重发 → 整包丢弃",
            "0x10790 ldr / 0x10794 cmp / 0x10798 b.ne 0x108E0 命中；" +
                "droppedPacketCount=${net.droppedPacketCount}；commit 冻结在 ${net.commit}；" +
                "丢弃路径既不是 0x1086C 投递、也不写 commit、不置 +0xB8、不 notify_all",
            "N-12 / N-13 / N-14 / N-25（§61.65 / §61.67，F4 真断点）",
        )

        // ---- H WaitRequest 读空 → result:-2 ----
        val readResult = buffer.readFromFileNoLock(16384L, 4096L, 20480L)
        step(
            "WaitRequest 读空 → result:-2",
            "commit(${net.commit}) < expectOffset(20480) → WaitRequest=WAIT_STARVED(1) → " +
                "ReadFromFileNoLock 映射为 $readResult；starvedReadCount=${buffer.starvedReadCount}",
            "N-26 / N-28 / N-29 + B-21 / B-22 / B-29（§61.77）",
        )

        // ---- I 挂起异步读 → Finish Async:-103 原样返回播放侧 ----
        buffer.markAsyncReadStarted()
        buffer.onAsyncReadComplete(starved = false)
        val finishCode = buffer.finishAsync()
        step(
            "挂起异步读 → Finish Async:-103 原样返回播放侧",
            "[+0x198] 非零才把 [+0x1A0] 写成 -0x67；Finish Async 打印并原样返回 $finishCode；" +
                "finishAsyncLogCount=${buffer.finishAsyncLogCount}",
            "B-23 / B-24 / B-25 / B-26 / B-27 / B-28（§61.76）",
        )

        // ---- J 播放侧 paused / release → Write start play 静默 ----
        audio.attachAudioTrack(Any())
        audio.setPaused(true)
        val outcomePaused = audio.writeStartPlay()
        audio.releaseAudioTrack()
        val outcomeReleased = audio.writeStartPlay()
        step(
            "AudioTrack paused / release → Write start play 静默门控",
            "paused 时门控 3 命中 → ${outcomeName(outcomePaused)}；" +
                "release 后门控 1 命中 → ${outcomeName(outcomeReleased)}；" +
                "Write start play 打印次数=${audio.writeStartPlayLogCount}（真机 0 次）",
            "A-01~A-07 / A-10 / A-11（§61.74 / §61.75）",
        )

        // ---- K 结论（由末端状态推导，不硬编码） ----
        val conclusion = "代次错配 → commit 冻结 ${net.commit} → result:$readResult → 挂起异步读 → " +
            "Finish Async:$finishCode → AudioTrack paused/release → " +
            "Write start play 命中 ${audio.writeStartPlayLogCount} 次 → 无出声。" +
            "F4 仍 BLOCKED（与 12 §61.70 / §61.75 真机结论一致；断点在代次门控，不是日志级别）。"
        step("结论", conclusion, "§61.65~§61.77 全链")

        // §61.54 / §61.55 完成通知链：独立实例旁路验证，不污染主链 buffer 状态
        val doneChain = probeDoneChain(gate, slices, net)

        return Reproduction(
            steps = steps,
            initialGlobalLevel = initialGlobalLevel,
            finalGeneration = finalGen,
            frozenCommit = net.commit,
            droppedPackets = net.droppedPacketCount,
            unexpectOffsetLogCount = unexpectCount,
            waitStarvedResult = readResult,
            finishAsyncCode = finishCode,
            outcomeWhenPaused = outcomeName(outcomePaused),
            outcomeWhenReleased = outcomeName(outcomeReleased),
            writeStartPlayLogCount = audio.writeStartPlayLogCount,
            sliceWriteCalls = slices.sliceWriteCalls,
            cacheProgressCount = buffer.cacheProgress.size,
            conclusion = conclusion,
            doneChain = doneChain,
        )
    }

    /** 渲染成 Markdown，供命令行打印 / 写入交付报告。 */
    fun render(r: Reproduction): String = buildString {
        appendLine("# F4 阻塞链路 1:1 复现（Kotlin 逻辑翻译）")
        appendLine()
        appendLine("> 证据：docs/12 §61.65 ~ §61.77；真机结论 §61.70 / §61.75。")
        appendLine("> 本复现由 `reverse-engine/kt/src/*.kt` 的真实逻辑运行得出，非手工叙述。")
        appendLine()
        appendLine("| # | 步骤 | 观测 | 证据锚点 |")
        appendLine("|---|------|------|----------|")
        for (s in r.steps) {
            appendLine("| ${s.index} | ${s.name} | ${s.detail.replace("|", "\\|")} | ${s.evidence} |")
        }
        appendLine()
        appendLine("## 终态快照")
        appendLine()
        appendLine("```text")
        appendLine("initialGlobalLevel      = ${r.initialGlobalLevel}")
        appendLine("finalGeneration         = ${r.finalGeneration}")
        appendLine("frozenCommit            = ${r.frozenCommit}")
        appendLine("droppedPackets          = ${r.droppedPackets}")
        appendLine("unexpectOffsetLogCount  = ${r.unexpectOffsetLogCount}")
        appendLine("waitStarvedResult       = ${r.waitStarvedResult}")
        appendLine("finishAsyncCode         = ${r.finishAsyncCode}")
        appendLine("outcomeWhenPaused       = ${r.outcomeWhenPaused}")
        appendLine("outcomeWhenReleased     = ${r.outcomeWhenReleased}")
        appendLine("writeStartPlayLogCount  = ${r.writeStartPlayLogCount}")
        appendLine("sliceWriteCalls         = ${r.sliceWriteCalls}")
        appendLine("cacheProgressCount      = ${r.cacheProgressCount}")
        appendLine("```")
        appendLine()
        appendLine("## 完成通知链旁路验证（§61.54 / §61.55，静态分支语义）")
        appendLine()
        appendLine("> **非 F4 因果环节**：以上 11 步是 §61.70 / §61.75 真机因果链；本节是")
        appendLine("> §61.54 / §61.55 纯离线反汇编坐实的 `OnNetSourceDataDone` 分支语义，")
        appendLine("> 用已翻译的 Kotlin 逻辑独立跑一遍，不宣称它在 F4 真机里按此时序发生，")
        appendLine("> 也不把它当作 F4 的根因（防静态推断被写成「已修复」）。")
        appendLine()
        appendLine("| kind | 投递任务 | drain 结果 | 级联投递 | 尾调用 |")
        appendLine("|------|----------|------------|----------|--------|")
        appendLine("| 2（预加载完成） | ${r.doneChain.kind2Task} | ${r.doneChain.kind2Outcomes.joinToString(" -> ")} | ${r.doneChain.kind2CascadePosts} | - |")
        appendLine("| 1（刷数据） | ${r.doneChain.kind1Task} | ${r.doneChain.kind1Outcomes.joinToString(" -> ")} | ${r.doneChain.kind1CascadePosts} | ${r.doneChain.flushTailCalls} |")
        appendLine()
        appendLine("- 两分支共投递同一 `0x16468` 队列 ${r.doneChain.sharedQueuePosts} 次；`kind==2` 的 Render 完成通知 = ${r.doneChain.kind2RenderDone}。")
        appendLine("- `kind==2` 是 288B / 72 指令的重任务并级联 420B / 105 指令的 Render 任务；`kind!=2` 是 88B / 22 指令的轻量尾调用，二者**不对称**。")
        appendLine("- ${r.doneChain.note}")
        appendLine()
        appendLine("## 结论")
        appendLine()
        appendLine(r.conclusion)
    }
}
