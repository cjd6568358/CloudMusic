@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * W 组 —— NMSlicesFile::Write / Read 分片索引窗口的 1:1 指令级翻译。
 *
 * 一手证据：spec/w-disasm-evidence.json（libNMCommonCache.so arm64-v8a 全 .text
 * capstone 反汇编，8 窗口 / 238 指令 / 28 锚点全通过；so sha256 与 §61.77 一致）。
 *
 * **S-16 定案**（此前 L2 挂账，本轮逐指令坐实）：终止片索引不是 `end/sliceSize` 直除。
 * ```
 * 0x1281C add  x28, x21, w23, uxtw     end    = offset + len
 * 0x12884 ldr  x10, [x19, #0xd0]       size   = [+0xD0] slice_size
 * 0x12890 sdiv x9,  x28, x10           qEnd   = end / size
 * 0x12894 sdiv x27, x21, x10           qStart = offset / size
 * 0x12898 msub x10, x9, x10, x28       rem    = end - qEnd*size
 * 0x128A0 cinc x11, x9, ne             upper  = qEnd + (rem != 0)
 * 0x128A4 cset w10, ne                 remFlag
 * 0x128A8 cmp  x27, x11 / b.ge 0x12A54 qStart >= upper → 零分片，跳到完成
 * 0x128B0 sub  x8, x11, #1             last   = upper - 1
 * 0x128D8 sub  x8, x8, x27             count  = upper - qStart
 * ```
 * 即循环是**半开区间 `[qStart, upper)`**，`upper = ceil(end/size)`，
 * 末片 `last = upper-1 == (end-1)/size`（end>0）。旧实现 `qStart..end/size`
 * 闭区间在 end 整除 size 时会**多写一片**，本轮按指令修正。
 *
 * @spec W-02 / W-03 / W-04 / W-05 / W-06 / W-07 / W-12 / W-14
 */
object NmSliceWindow {

    /** @spec W-07 0x128C4 `mov w9,#0xe8` + 0x128DC `mul` + 0x128F8 `add x23,x23,#0xe8`。 */
    const val OBJECT_STRIDE: Long = 232L

    /** @spec W-14 0x12814 `tbnz x21,#0x3f`：offset 低 6 位必须为 0。 */
    const val ALIGN_MASK: Long = 0x3FL

    data class Window(
        /** end = offset + len */
        val end: Long,
        /** @spec W-03 `sdiv x9,x28,x10` */
        val qEnd: Long,
        /** @spec W-03 `sdiv x27,x21,x10` */
        val qStart: Long,
        /** @spec W-04 `msub x10,x9,x10,x28` */
        val remainder: Long,
        /** @spec W-04 `cset w10,ne` */
        val hasRemainder: Boolean,
        /** @spec W-04 `cinc x11,x9,ne` —— 半开区间上界 */
        val upper: Long,
        /** @spec W-06 `sub x8,x11,#1` */
        val last: Long,
        /** @spec W-06 `sub x8,x8,x27` —— 实际分片写调用次数 */
        val count: Long,
        /** @spec W-07 `mul x23,x27,x9` —— 首片对象在数组里的字节偏移 */
        val firstObjectOffset: Long,
    ) {
        /** @spec W-05 0x128AC `b.ge 0x12A54`：起片 >= 上界时整个循环被跳过。 */
        val empty: Boolean get() = qStart >= upper

        /** 半开区间 [qStart, upper)；为零分片时返回空区间。 */
        val indices: LongRange
            get() = if (empty) 0L until 0L else qStart until upper
    }

    /**
     * 1:1 复算分片窗口。`end` 由调用方按 `offset + len` 传入（对应 0x1281C 的 add）。
     *
     * @spec W-02 / W-03 / W-04 / W-05 / W-06 / W-07 / W-12
     */
    fun compute(end: Long, offset: Long, sliceSize: Long): Window {
        require(sliceSize > 0) { "slice_size [+0xD0] must be positive" }
        val qEnd = end / sliceSize                 // 0x12890 sdiv
        val qStart = offset / sliceSize            // 0x12894 sdiv
        val remainder = end - qEnd * sliceSize     // 0x12898 msub
        val hasRemainder = remainder != 0L         // 0x1289C cmp + 0x128A4 cset
        val upper = qEnd + if (hasRemainder) 1L else 0L   // 0x128A0 cinc
        val count = if (qStart >= upper) 0L else upper - qStart
        return Window(
            end = end,
            qEnd = qEnd,
            qStart = qStart,
            remainder = remainder,
            hasRemainder = hasRemainder,
            upper = upper,
            last = upper - 1,
            count = count,
            firstObjectOffset = qStart * OBJECT_STRIDE,
        )
    }

    /** 写侧窗口：0x1281C 先算 end，再走 compute。 @spec W-02 / W-04 */
    fun writeWindow(offset: Long, len: Long, sliceSize: Long): Window =
        compute(offset + len, offset, sliceSize)

    /**
     * 读侧窗口：0x12CCC `add x25,x12,x19` 同样先算 end，0x12CD0~0x12CE0 用
     * 同一组 sdiv / msub / cset / cinc 得到 upper。
     *
     * @spec W-12
     */
    fun readWindow(offset: Long, len: Long, sliceSize: Long): Window =
        compute(offset + len, offset, sliceSize)

    /** 0x12814 `tbnz x21,#0x3f`：未 64B 对齐即进入 -102 分支。 @spec W-14 */
    fun isUnaligned(offset: Long): Boolean = (offset and ALIGN_MASK) != 0L
}
