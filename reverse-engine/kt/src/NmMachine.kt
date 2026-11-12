@file:Suppress("unused")
package cloudmusic.reverse.kernel

/** 跨块 / 跨函数调度器 @spec Z-05：按 pc 落在哪个代码块分派，bl/ret/尾调用/间接跳转都真实执行。
 *
 * 单独成文件是为了让编译分趟：块函数（NmCodeNN.kt）只依赖 NmArmState，
 * 不依赖本文件，因此可以先编译状态层、再分批编译块、最后编译调度层，
 * 避开单趟全量编译的 JVM 堆峰值。 */
class NmMachine(val st: NmArmState = NmArmState()) {
    /**
     * 从 [entry] 起执行，[budget] 是**指令数**上限（不是分派次数）：
     * 块内自循环不会消耗分派计数，只能靠 NmArmState.abortAt 兜住。
     */
    fun call(so: String, entry: Long, budget: Int = 4_000_000): Long {
        st.pc = entry
        st.x[30] = NmArmState.RET_SENTINEL
        st.abortAt = st.steps + budget
        var guard = 0
        while (guard++ < budget) {
            val pc = st.pc
            if (pc == NmArmState.RET_SENTINEL) {
                if (st.abortAt != Long.MAX_VALUE) st.abortAt = Long.MAX_VALUE
                return st.x[0]
            }
            val idx = NmTranslatedCatalog.blockIndexOf(so, pc)
            if (idx < 0) {
                st.ext.unresolvedTargets[pc] = (st.ext.unresolvedTargets[pc] ?: 0) + 1
                st.ext.externalCalls++
                st.abortAt = Long.MAX_VALUE
                return st.x[0]
            }
            NmBlocks.exec(idx, st)
        }
        st.ext.budgetExceeded++
        st.abortAt = Long.MAX_VALUE
        return st.x[0]
    }
}

