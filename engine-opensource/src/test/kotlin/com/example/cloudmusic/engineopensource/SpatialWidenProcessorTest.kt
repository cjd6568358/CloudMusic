package com.example.cloudmusic.engineopensource

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
}
