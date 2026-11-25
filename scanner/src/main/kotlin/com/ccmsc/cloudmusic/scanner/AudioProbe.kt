package com.ccmsc.cloudmusic.scanner

/**
 * docs/03-04 §6.1 / T03 · magic bytes 判定真实格式（扩展名只作初筛）。
 * 纯 JVM 实现，不依赖 MediaMetadataRetriever。
 */
enum class AudioFormat { MP3, FLAC, OGG, WAV, M4A, WMA, DSD, UNKNOWN }

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
        // §61.235（V0.2 格式解析 DSD）：DSF = "DSD " 魔数；DFF = "FRM8"（DSDIFF）。
        // 入库与 tag 读取链（JAudioTagger 3.0.x DsfFileReader/DffFileReader）已就绪，
        // 解码出声受引擎能力限制（能力边界如实登记，PlaybackRuntime autoSkipOnError 兜底）。
        if (head.startsWith("DSD ")) return AudioFormat.DSD
        if (head.startsWith("FRM8")) return AudioFormat.DSD
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
