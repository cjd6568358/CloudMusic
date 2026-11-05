package com.example.cloudmusic.scanner

import com.example.cloudmusic.datasource.ByteRangeCapability

/** docs/03-04 §6.1 · 原始标签。失败一律返回 EMPTY，不中断整轮扫描（B16）。 */
data class RawTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackNo: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val durationMs: Long? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val embeddedCover: ByteArray? = null,
    val completeness: Float = 0f,
) {
    companion object { val EMPTY = RawTags() }
}

interface TagReader {
    suspend fun read(candidate: Candidate): RawTags
    fun probe(head: ByteArray): AudioFormat = AudioProbe.probe(head)
}

object EmptyTagReader : TagReader {
    override suspend fun read(candidate: Candidate): RawTags = RawTags.EMPTY
}

/**
 * 轻量纯 JVM 标签读取：只经 [ByteRangeCapability] 读头部 64KB + 尾部 128B，
 * **绝不下载整文件**（03-04 §15 #1）。解析 ID3v2 文本帧 / APIC 与 ID3v1；
 * 生产环境可由 JAudioTagger + MediaMetadataRetriever 的适配器替换，接口不变。
 * 驱动未实现 ByteRangeCapability 或标签损坏时返回 EMPTY，走文件名兜底。
 */
class HeadTagReader(
    private val rangeFor: (Candidate) -> ByteRangeCapability? = { null },
    private val headSize: Int = 64 * 1024,
    private val tailSize: Int = 128,
) : TagReader {

    override suspend fun read(candidate: Candidate): RawTags = runCatching {
        val range = rangeFor(candidate) ?: return RawTags.EMPTY
        val head = range.readBytes(candidate.obj, 0, headSize) ?: return RawTags.EMPTY
        val tail = if (candidate.obj.size > tailSize) {
            range.readBytes(candidate.obj, candidate.obj.size - tailSize, tailSize)
        } else null
        merge(parseId3v2(head), tail?.let(::parseId3v1) ?: RawTags.EMPTY)
    }.getOrElse { RawTags.EMPTY }

    private fun parseId3v2(head: ByteArray): RawTags {
        if (head.size < 10 || !head.matches("ID3", 0)) return RawTags.EMPTY
        val major = head[3].toInt() and 0xff
        if (major !in 2..4) return RawTags.EMPTY
        val limit = minOf(head.size, 10 + synchSafe(head, 6))
        val fields = mutableMapOf<String, String>()
        var cover: ByteArray? = null
        var pos = 10
        while (pos + 10 <= limit) {
            val headerSize = if (major == 2) 6 else 10
            if (pos + headerSize > limit) break
            val idLength = if (major == 2) 3 else 4
            val id = String(head, pos, idLength, Charsets.ISO_8859_1)
            val frameSize = if (major == 2) be24(head, pos + 3)
            else if (major == 4) synchSafe(head, pos + 4) else be32(head, pos + 4)
            if (frameSize <= 0 || pos + headerSize + frameSize > limit) break
            val body = head.copyOfRange(pos + headerSize, pos + headerSize + frameSize)
            when (id) {
                "TIT2", "TT2" -> fields["title"] = decodeText(body)
                "TPE1", "TP1" -> fields["artist"] = decodeText(body)
                "TALB", "TAL" -> fields["album"] = decodeText(body)
                "TRCK", "TRK" -> fields["track"] = decodeText(body)
                "TYER", "TYE", "TDRC" -> fields["year"] = decodeText(body)
                "TCON", "TCO" -> fields["genre"] = decodeText(body)
                "APIC", "PIC" -> cover = body
            }
            pos += headerSize + frameSize
        }
        return RawTags(
            title = fields["title"]?.takeIf { it.isNotBlank() },
            artist = fields["artist"]?.takeIf { it.isNotBlank() },
            album = fields["album"]?.takeIf { it.isNotBlank() },
            trackNo = fields["track"]?.substringBefore('/')?.toIntOrNull(),
            year = fields["year"]?.take(4)?.toIntOrNull(),
            genre = fields["genre"],
            embeddedCover = cover,
        )
    }

    private fun parseId3v1(tail: ByteArray): RawTags {
        if (tail.size < 128) return RawTags.EMPTY
        val start = tail.size - 128
        if (!tail.matches("TAG", start)) return RawTags.EMPTY
        return RawTags(
            title = isoText(tail, start + 3, 30),
            artist = isoText(tail, start + 33, 30),
            album = isoText(tail, start + 63, 30),
            year = isoText(tail, start + 93, 4)?.toIntOrNull(),
        )
    }

    private fun merge(primary: RawTags, fallback: RawTags): RawTags {
        val result = RawTags(
            title = primary.title ?: fallback.title,
            artist = primary.artist ?: fallback.artist,
            album = primary.album ?: fallback.album,
            trackNo = primary.trackNo ?: fallback.trackNo,
            year = primary.year ?: fallback.year,
            genre = primary.genre ?: fallback.genre,
            durationMs = primary.durationMs ?: fallback.durationMs,
            sampleRate = primary.sampleRate ?: fallback.sampleRate,
            bitDepth = primary.bitDepth ?: fallback.bitDepth,
            embeddedCover = primary.embeddedCover ?: fallback.embeddedCover,
        )
        val filled = listOf(result.title, result.artist, result.album)
            .count { !it.isNullOrBlank() }
        return result.copy(
            completeness = when {
                filled == 3 -> 1.0f
                filled > 0 -> 0.5f
                else -> 0f
            },
        )
    }

    private fun decodeText(body: ByteArray): String {
        if (body.isEmpty()) return ""
        val encoding = body[0].toInt() and 0xff
        val data = body.copyOfRange(1, body.size)
        val charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(data, charset).trimEnd('\u0000', ' ', '\t').trim()
    }

    private fun isoText(bytes: ByteArray, offset: Int, length: Int): String? {
        if (offset + length > bytes.size) return null
        return String(bytes, offset, length, Charsets.ISO_8859_1)
            .trimEnd('\u0000', ' ').trim().takeIf { it.isNotEmpty() }
    }

    private fun ByteArray.matches(text: String, offset: Int): Boolean {
        if (size < offset + text.length) return false
        return text.indices.all { this[offset + it] == text[it].code.toByte() }
    }

    private fun synchSafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7f) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7f) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7f) shl 7) or
            (bytes[offset + 3].toInt() and 0x7f)

    private fun be32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun be24(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 16) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            (bytes[offset + 2].toInt() and 0xff)
}
