package com.example.cloudmusic.scanner

import com.example.cloudmusic.model.LegacyTextRepair

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
    /** docs/01 §0（2026-11-16 拍板）：内嵌歌词（ID3v2 USLT）。头部读取尽力而为，
     *  完整歌词由 core-di 的 JAudioTagger 通道提供（读取整文件）。 */
    val lyrics: String? = null,
    val completeness: Float = 0f,
) {
    companion object { val EMPTY = RawTags() }
}

interface TagReader {
    suspend fun read(candidate: Candidate): RawTags
    fun probe(head: ByteArray): AudioFormat = AudioProbe.probe(head)
}

/**
 * §61.203（A1-①，验收 #2）→ §61.281（V0.4-08，拍板 §61.273 #2B 恢复阶段 B）：远程源 tag 策略。
 *
 * **现行生产口径 = [PRODUCTION]（读模式：白名单 + 阈值）**，[SKIP] 降级为应急/对照开关。
 *
 * 467s 冷扫的主导成本 = WebDAV/STRM **逐文件** Range 头尾读（头 64KB + 尾 128B，
 * 各带鉴权握手）× 并发 4（§61.160 一手：网络存储占 467−87 ≈ 380s）。而头部解析只产出
 * title/artist/album 等文本帧——文件名兜底（strm `TrackHint` + [NameParser]）已有，
 * 逐文件读头部的信息增益性价比极低，故 §61.203 一度默认**全跳过**：远程候选直接得到
 * 文件名兜底级 [RawTags]（**非 EMPTY** 语义 ⇒ 仍走 `TrackFactory.build`，封面/歌词侧链
 * 照常消费），时长由 §61.181 播放期真值通道回填——代价是**专辑/年份/风格永久缺失**
 * （仅靠 03-10 §9 B15「已知字段才显示」把空缺藏起来，用户 2026-11-21 拍板要求恢复）。
 *
 * [readExts]（扩展名白名单）与 [maxBytes]（容量阈值）是 skip=false（读）模式下的
 * 约束：仅白名单容器（头字节可解析 ID3/MP4 系容器，与检索器兜底白名单 §61.147 同源
 * 理由）且未超阈值才值得发 Range 读；[maxBytes] 在两种模式下恒生效（巨型文件
 * 头解析价值趋零）。
 */
data class RemoteTagPolicy(
    val skipRemoteTags: Boolean = true,
    val readExts: Set<String> = DEFAULT_REMOTE_TAG_EXTS,
    val maxBytes: Long = 512L * 1024 * 1024,
) {
    fun shouldSkip(fileName: String, sizeBytes: Long): Boolean {
        if (sizeBytes > maxBytes) return true
        if (!skipRemoteTags) {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return ext !in readExts
        }
        return true
    }

    companion object {
        val DEFAULT_REMOTE_TAG_EXTS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus")

        /**
         * 生产默认（§61.281，V0.4-08，拍板 §61.273 #2B 恢复阶段 B）：**读模式**——
         * 只有白名单扩展名且未超 [maxBytes] 才发 Range 读，补回专辑/年份/风格。
         *
         * 成本边界（如实登记，不粉饰）：**新增/变更**的远程候选才会读——阶段 A 的
         * stat 命中跳过（§61.204）与快照 `metadataDone` 继承把「未变文件」全挡在门外，
         * 因此稳态增量扫描不产生 Range 读；只有**全量首扫 / 大目录新增**按文件数计费。
         * 并发受 `ScanRequest.metadataConcurrency`（生产 8，§61.323 B-2 全源共享）限流，阶段 A 骨架先入库
         * 并 emit `stageAFinished` ⇒ UI 不被阶段 B 阻塞（03-04 §10 两阶段语义不变）。
         */
        val PRODUCTION = RemoteTagPolicy(skipRemoteTags = false)

        /** 显式全跳过：应急开关 / 单测对照（§61.203 原始口径，保留不删）。 */
        val SKIP = RemoteTagPolicy(skipRemoteTags = true)
    }
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
        var lyrics: String? = null
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
                "USLT", "ULT" -> decodeUsLT(body)?.let { lyrics = it }
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
            lyrics = lyrics,
        )
    }

    /**
     * 解析 ID3v2 USLT（内嵌歌词）帧。帧体 = 1B 文本编码 + 3B ISO 语言码 +
     * 内容描述符（编码相关的 NUL 结尾）+ 歌词文本。描述符可能为空（单个 NUL）。
     * 头部读取只读 64KB，超长歌词可能被截断——完整歌词由 JAudioTagger 通道兜底。
     */
    private fun decodeUsLT(body: ByteArray): String? = runCatching {
        if (body.size < 5) return null
        val encoding = body[0].toInt() and 0xff
        val charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        val nulWidth = if (encoding == 1 || encoding == 2) 2 else 1
        // 跳过 3B 语言码，从描述符开始找结尾 NUL
        var d = 3
        while (d + nulWidth <= body.size) {
            val isNul = if (nulWidth == 1) body[d] == 0.toByte()
            else body[d] == 0.toByte() && body[d + 1] == 0.toByte()
            if (isNul) break
            d += nulWidth
        }
        val textStart = if (d + nulWidth <= body.size) d + nulWidth else body.size
        if (textStart >= body.size) return null
        val text = String(body, textStart, body.size - textStart, charset)
            .trimEnd('\u0000', ' ', '\t', '\r')
        LegacyTextRepair.fix(text)?.takeIf { it.isNotBlank() }
    }.getOrNull()

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
            lyrics = primary.lyrics?.takeIf { it.isNotBlank() } ?: fallback.lyrics,
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
        val text = String(data, charset).trimEnd('\u0000', ' ', '\t').trim()
        // §61.170：enc=0x00 但实为 GBK 的中文旧标签（本机检索器同病），统一走修复。
        return LegacyTextRepair.fix(text) ?: text
    }

    private fun isoText(bytes: ByteArray, offset: Int, length: Int): String? {
        if (offset + length > bytes.size) return null
        val text = String(bytes, offset, length, Charsets.ISO_8859_1)
            .trimEnd('\u0000', ' ').trim()
        if (text.isEmpty()) return null
        // §61.170：ID3v1 定长单字节字段同样是 GBK 高发区。
        return LegacyTextRepair.fix(text)
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
