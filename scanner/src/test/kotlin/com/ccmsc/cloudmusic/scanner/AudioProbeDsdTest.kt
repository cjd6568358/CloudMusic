package com.ccmsc.cloudmusic.scanner

import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals

/** §61.235：DSD 魔数探测（DSF "DSD " / DFF "FRM8"）——V0.2 格式解析 DSD 探测口径。 */
class AudioProbeDsdTest {

    private fun dsfHead(): ByteArray {
        // DSF 文件头：offset 0 = "DSD "，offset 4..8 = 版本，offset 8..16 = 总长
        val head = ByteArray(16)
        "DSD ".forEachIndexed { i, c -> head[i] = c.code.toByte() }
        head[4] = 1
        head[8] = 0x5C
        return head
    }

    private fun dffHead(): ByteArray {
        // DSDIFF 文件头：offset 0 = "FRM8"（FORM 容器 64bit 版）
        val head = ByteArray(16)
        "FRM8".forEachIndexed { i, c -> head[i] = c.code.toByte() }
        return head
    }

    @Test
    @DisplayName("§61.235 · DSF 魔数识别（DSD + 空格）")
    fun dsfMagic() {
        assertEquals(AudioFormat.DSD, AudioProbe.probe(dsfHead()))
    }

    @Test
    @DisplayName("§61.235 · DFF 魔数识别（FRM8 容器）")
    fun dffMagic() {
        assertEquals(AudioFormat.DSD, AudioProbe.probe(dffHead()))
    }

    @Test
    @DisplayName("§61.235 · 既有格式零回归（fLaC / ID3 / OggS 不受影响）")
    fun existingFormatsUnchanged() {
        assertEquals(AudioFormat.FLAC, AudioProbe.probe("fLaC".toByteArray() + ByteArray(12)))
        assertEquals(AudioFormat.MP3, AudioProbe.probe("ID3".toByteArray() + ByteArray(12)))
        assertEquals(AudioFormat.OGG, AudioProbe.probe("OggS".toByteArray() + ByteArray(12)))
        assertEquals(AudioFormat.UNKNOWN, AudioProbe.probe(ByteArray(16)))
    }
}
