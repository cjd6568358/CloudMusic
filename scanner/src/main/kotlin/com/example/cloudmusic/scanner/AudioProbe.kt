package com.example.cloudmusic.scanner

/**
 * docs/03-04 §6.1 / T03 · magic bytes 判定真实格式（扩展名只作初筛）。
 * 纯 JVM 实现，不依赖 MediaMetadataRetriever。
 */
enum class AudioFormat { MP3, FLAC, OGG, WAV, M4A, WMA, UNKNOWN }

object AudioProbe {
    fun probe(head: ByteArray): AudioFormat {
        if (head.size < 4) return AudioFormat.UNKNOWN
        if (head.startsWith("ID3")) return AudioFormat.MP3
        if (head.startsWith("fLaC")) return AudioFormat.FLAC
        if (head.startsWith("OggS")) return AudioFormat.OGG
        if (head.startsWith("RIFF") && head.size >= 12 && head.startsWith("WAVE", 8)) {
            return AudioFormat.WAV
        }
        if (head.size >= 8 && head.startsWith("ftyp", 4)) return AudioFormat.M4A
        // MPEG audio frame sync: 0xFF Ex / Fx
        if ((head[0].toInt() and 0xff) == 0xFF &&
            ((head[1].toInt() and 0xE0) == 0xE0)
        ) return AudioFormat.MP3
        // WMA ASF GUID 30 26 B2 75 8E 66 CF 11
        if (head.size >= 16 &&
            head[0] == 0x30.toByte() && head[1] == 0x26.toByte() &&
            head[2] == 0xB2.toByte() && head[3] == 0x75.toByte()
        ) return AudioFormat.WMA
        return AudioFormat.UNKNOWN
    }

    private fun ByteArray.startsWith(text: String, offset: Int = 0): Boolean {
        if (size < offset + text.length) return false
        for (i in text.indices) {
            if (this[offset + i] != text[i].code.toByte()) return false
        }
        return true
    }
}
