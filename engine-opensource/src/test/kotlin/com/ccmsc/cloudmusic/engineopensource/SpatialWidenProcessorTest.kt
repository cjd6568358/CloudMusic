package com.ccmsc.cloudmusic.engineopensource

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** §61.160：声场展宽处理器纯 JVM 验证（恒等/展宽/透传降级）。 */
@UnstableApi
class SpatialWidenProcessorTest {

    private fun stereoBytes(samples: List<Pair<Int, Int>>): ByteArray {
        val out = ByteArray(samples.size * 4)
        samples.forEachIndexed { i, (l, r) ->
            val ls = l.toShort()
            val rs = r.toShort()
            out[i * 4] = (ls.toInt() and 0xFF).toByte()
            out[i * 4 + 1] = ((ls.toInt() shr 8) and 0xFF).toByte()
            out[i * 4 + 2] = (rs.toInt() and 0xFF).toByte()
            out[i * 4 + 3] = ((rs.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun toShorts(bytes: ByteArray): List<Short> {
        val out = mutableListOf<Short>()
        var i = 0
        while (i + 1 < bytes.size) {
            out += (((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort())
            i += 2
        }
        return out
    }

    private fun processed(bytes: ByteArray, widen: Float): ByteArray {
        val p = SpatialWidenProcessor()
        AudioEffectsBus.widenAmount = widen
        try {
            return rawProcess(p, bytes)
        } finally {
            AudioEffectsBus.widenAmount = 0f
        }
    }

    private fun rawProcess(p: SpatialWidenProcessor, bytes: ByteArray): ByteArray {
        val fmt = AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        p.configure(fmt)
        p.flush()
        p.queueInput(ByteBuffer.wrap(bytes))
        val out = p.output
        val arr = ByteArray(out.remaining())
        out.get(arr)
        return arr
    }

    @Test
    fun `amount 0 = 恒等直通`() {
        val input = stereoBytes(listOf(1000 to -2000, -3000 to 4000))
        val out = processed(input, 0f)
        assertTrue(input.contentEquals(out), "amount=0 应逐字节恒等")
    }

    @Test
    fun `amount 大于 0 展宽 side 放大`() {
        // L=4000, R=-4000：mid=0, side=4000 → k=1.5 → L'=6000, R'=-6000
        val input = stereoBytes(listOf(4000 to -4000))
        val out = processed(input, 0.5f)
        val shorts = toShorts(out)
        assertEquals(6000, shorts[0], "L' 应为 mid+side*1.5")
        assertEquals(-6000, shorts[1], "R' 应为 mid-side*1.5")
    }

    @Test
    fun `同相单声道信号 mid 不变 side=0 不展宽`() {
        // L=R=2000：mid=2000, side=0 → 展宽后仍 2000/2000（单声道内容不变形）
        val input = stereoBytes(listOf(2000 to 2000, 2000 to 2000))
        val out = processed(input, 0.8f)
        val shorts = toShorts(out)
        assertEquals(2000, shorts[0])
        assertEquals(2000, shorts[1])
    }

    @Test
    fun `非 16-bit 降级直通`() {
        val p = SpatialWidenProcessor()
        AudioEffectsBus.widenAmount = 0.8f
        val fmt = AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT)
        p.configure(fmt)
        p.flush()
        val input = byteArrayOf(1, 2, 3, 4)
        p.queueInput(ByteBuffer.wrap(input))
        val out = p.output
        val arr = ByteArray(out.remaining())
        out.get(arr)
        assertTrue(input.contentEquals(arr), "非 16-bit 立体声应原样透传")
    }

    /**
     * §61.177（真机一手回归）：Media3 `AudioProcessingPipeline` 会把本处理器上一包
     * `getOutput()` 返回的 ByteBuffer **原样**作为下一包 `queueInput()` 的输入，
     * 而 `BaseAudioProcessor` 的 `buffer` 字段是复用的（`getOutput()` 只把
     * `outputBuffer` 换成 `EMPTY_BUFFER`，不重建 `buffer`）⇒ 输出与输入是同一对象。
     * 旧实现 `out.put(inputBuffer)` 命中 `ByteBuffer` 的 `src == this` 分支抛
     * `IllegalArgumentException`，直通分支（amount=0，默认值）必崩。
     *
     * 本用例用同一对象走两包，正是真机上的那条路径；旧实现会在此抛异常。
     */
    @Test
    fun `管线复用输入缓冲 输入输出同一对象不崩`() {
        val p = SpatialWidenProcessor()
        AudioEffectsBus.widenAmount = 0f
        try {
            p.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            p.flush()

            val first = stereoBytes(listOf(1000 to -2000, -3000 to 4000))
            p.queueInput(ByteBuffer.wrap(first))
            val reused = p.output
            val arr1 = ByteArray(reused.remaining())
            reused.get(arr1)
            assertTrue(first.contentEquals(arr1), "第一包应直通")

            // 关键：把上一包 output 的同一对象当作下一包 input 传回（管线真实行为）
            val second = stereoBytes(listOf(500 to 600, -700 to 800))
            reused.clear()
            reused.put(second)
            reused.flip()
            p.queueInput(reused)

            val out2 = p.output
            val arr2 = ByteArray(out2.remaining())
            out2.get(arr2)
            assertTrue(second.contentEquals(arr2), "复用同一 buffer 的第二包应直通且不崩")
        } finally {
            AudioEffectsBus.widenAmount = 0f
        }
    }

    /** §61.177：复用同一对象时，展宽分支（amount>0）也必须正确。 */
    @Test
    fun `管线复用输入缓冲 展宽分支同样正确`() {
        val p = SpatialWidenProcessor()
        AudioEffectsBus.widenAmount = 0.5f
        try {
            p.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            p.flush()

            val first = stereoBytes(listOf(4000 to -4000))
            p.queueInput(ByteBuffer.wrap(first))
            val reused = p.output
            val arr1 = ByteArray(reused.remaining())
            reused.get(arr1)
            assertEquals(6000, toShorts(arr1)[0])

            val second = stereoBytes(listOf(4000 to -4000))
            reused.clear()
            reused.put(second)
            reused.flip()
            p.queueInput(reused)
            // getOutput() 是一次性的：首次调用即把 outputBuffer 换成 EMPTY_BUFFER，
            // 必须只取一次再读。
            val out2 = p.output
            val arr2 = ByteArray(out2.remaining())
            out2.get(arr2)
            val shorts = toShorts(arr2)
            assertEquals(6000, shorts[0], "复用同一 buffer 时 L' 仍应为 mid+side*1.5")
            assertEquals(-6000, shorts[1], "复用同一 buffer 时 R' 仍应为 mid-side*1.5")
        } finally {
            AudioEffectsBus.widenAmount = 0f
        }
    }
}
