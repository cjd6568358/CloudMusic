package androidx.media3.extractor.asf

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput

/**
 * §61.97（B2 M4.2 · 容器缺口 ②）：WMA（ASF 容器）Extractor——单音频流
 * WMA v1/v2 CBR 范围（真机样本 f3_wma.wma 布局实测钉死，docs/12 §61.96）。
 *
 * 结构（MS-ASF / 实测）：
 * - Header Object（GUID 3026B275…）children：FileProps（packets @+56、
 *   play_duration @+64、min·max_packet_size @+92/96）+ … + StreamProps
 *   （114B child：WAVEFORMATEX @ +78：wftag/ch/rate/align/bits）；
 * - Data Object（GUID 75B22636…）：50B 头 + 定长包（max_packet_size）；
 *   包布局（实测钉死）：EC(1+2B) + info(1) + len(2) + send_time u32 @+6 +
 *   dur u16 @+10 + stream# u8 @+12 + MO# u32 @+13 + offset u16 @+17 +
 *   MO size u32 @+19（= block_align）+ delta u32 @+23
 *   → **WMA 帧 = 包尾 block_align 字节**（时间戳 = send + delta，MS-ASF 语义）。
 *
 * 解码器侧 wmav1/wmav2 已编入 FFmpeg 扩展（§61.83 符号实证）。多流 / 可变包 /
 * 压缩载荷不在本范围（真机样本为单流 CBR；遇即如实报错，不伪造解析）。
 */
@UnstableApi
class AsfExtractor : Extractor {

    private var output: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null

    private var packetSize = 0
    private var packetCount = 0L
    private var playDurationMs = 0L
    private var sampleRate = 0
    private var channels = 0
    private var blockAlign = 0
    private var wmaCodecTag = 0
    private var dataStart = 0L

    private var state = STATE_READ_HEADER
    private var currentPacket = 0L

    override fun sniff(input: ExtractorInput): Boolean {
        val guid = ByteArray(16)
        val ok = input.peekFully(guid, 0, 16, true)
        val hit = ok && guid.contentEquals(ASF_HEADER_GUID)
        try {
            android.util.Log.i("CM-Asf", "sniff ok=%s hit=%s head=%s".format(
                ok, hit, guid.take(8).joinToString("") { "%02x".format(it) }))
        } catch (t: Throwable) {
            println("[CM-Asf] sniff ok=$ok hit=$hit")
        }
        return hit
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.seekMap(
            object : SeekMap {
                override fun isSeekable(): Boolean =
                    packetSize > 0 && packetCount > 0

                override fun getDurationUs(): Long =
                    if (playDurationMs > 0) playDurationMs * 1000 else C.TIME_UNSET

                override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                    val pkt = packetForTimeUs(timeUs)
                    return SeekMap.SeekPoints(
                        SeekPoint(pkt * packetDurationUs(), dataStart + pkt * packetSize),
                    )
                }
            },
        )
    }

    override fun seek(position: Long, timeUs: Long) {
        currentPacket = packetForTimeUs(timeUs)
        state = STATE_READ_PACKETS
    }

    private fun packetForTimeUs(timeUs: Long): Long {
        val dur = packetDurationUs()
        if (dur <= 0) return 0
        return (timeUs / dur).coerceIn(0, (packetCount - 1).coerceAtLeast(0))
    }

    private fun packetDurationUs(): Long =
        if (packetCount > 0 && playDurationMs > 0) {
            playDurationMs * 1000 / packetCount
        } else {
            0L
        }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        when (state) {
            STATE_READ_HEADER -> readHeader(input)
            STATE_READ_PACKETS -> readPackets(input)
            else -> Extractor.RESULT_END_OF_INPUT
        }

    /** Header Object children 遍历：FileProps / StreamProps 解析，其余跳过；随后进 Data Object。 */
    private fun readHeader(input: ExtractorInput): Int {
        val guid = ByteArray(16)
        if (!input.readFully(guid, 0, 16, true)) return Extractor.RESULT_END_OF_INPUT
        if (!guid.contentEquals(ASF_HEADER_GUID)) return Extractor.RESULT_END_OF_INPUT
        val hdrSize = readLeU64(input) ?: return Extractor.RESULT_END_OF_INPUT
        var pos = 30L
        var sawStreamProps = false

        // §61.94：Header Object 头部 = GUID16 + size8 + child_count4 + flags2 + pad2
        // = 30B；children 从 30 起。读侧在 hdrSize 后位于 24，需跳过 count/flags/pad。
        input.skipFully(6)
        val inputLen = input.length
        while (pos < hdrSize) {
            val childGuid = ByteArray(16)
            if (!input.readFully(childGuid, 0, 16, true)) return Extractor.RESULT_END_OF_INPUT
            val childSize = readLeU64(input) ?: return Extractor.RESULT_END_OF_INPUT
            if (childSize < 24 || (inputLen > 0 && childSize > inputLen)) {
                logW("非法 child size=$childSize @pos=$pos inputLen=$inputLen")
                return Extractor.RESULT_END_OF_INPUT
            }
            val bodyLen = (childSize - 24).toInt()
            val body = ByteArray(bodyLen.coerceAtLeast(0))
            if (bodyLen > 0 && !input.readFully(body, 0, bodyLen, true)) {
                return Extractor.RESULT_END_OF_INPUT
            }
            when {
                childGuid.contentEquals(FILE_PROPS_GUID) -> {
                    // body 布局（去掉 GUID16+Size8 后）：File ID(16)@0, File Size(8)@16,
                    // Creation(8)@24, packets(8)@32, play_dur(8)@40, send_dur(8)@48,
                    // preroll(8)@56, flags(4)@64, min_ps(4)@68, max_ps(4)@72（实测钉死）
                    if (bodyLen >= 76) {
                        packetCount = leU64(body, 32)
                        playDurationMs = leU64(body, 40) / 10_000
                        packetSize = readLeU32At(body, 72).toInt()
                    }
                }
                childGuid.contentEquals(STREAM_PROPS_GUID) -> {
                    sawStreamProps = parseStreamProps(body)
                    if (sawStreamProps) {
                        // §61.97：WAVEFORMATEX 就绪 → 下发轨道格式（audio/x-wma →
                        // FfmpegAudioRenderer 的 wmav1/wmav2 软解）。
                        trackOutput?.format(
                            Format.Builder()
                                .setSampleMimeType("audio/x-wma")
                                .setSampleRate(sampleRate)
                                .setChannelCount(channels)
                                .setCodecs("wmav2")
                                .build(),
                        )
                    }
                }
                else -> Unit   // HeaderExtension / CodecList / ContentDesc 等跳过
            }
            pos += childSize
        }
        if (!sawStreamProps || packetSize <= 0 || blockAlign <= 0) {
            logW("StreamProps/包尺寸缺失 saw=%s ps=%d".format(sawStreamProps, packetSize))
            return Extractor.RESULT_END_OF_INPUT
        }

        // Data Object：GUID 75B22636… + size(8) + file_id(16) + packets(8) + reserved(2) = 50B
        val g = ByteArray(16)
        if (!input.readFully(g, 0, 16, true)) return Extractor.RESULT_END_OF_INPUT
        if (!g.contentEquals(DATA_GUID)) {
            logW("Data Object GUID 不符：%s".format(g.contentToString()))
            return Extractor.RESULT_END_OF_INPUT
        }
        input.skipFully(34)   // size(8) + file_id(16) + total_packets(8) + reserved(2)
        dataStart = input.position
        logW("data object ready start=%d packets=%d ps=%d align=%d rate=%d"
            .format(dataStart, packetCount, packetSize, blockAlign, sampleRate))
        state = STATE_READ_PACKETS
        currentPacket = 0
        return Extractor.RESULT_CONTINUE
    }

    /**
     * StreamProps child（body 从 GUID+Size 后起）：音频流判定（Stream Type GUID
     * @ body 0）+ WAVEFORMATEX **模式搜索定位**（wftag ∈ WMA 系 + channels/rate
     * 合理性自校验——固定偏移跨样本脆弱，实测样本 f3_wma.wma 定位 @ body 40）。
     */
    private fun parseStreamProps(body: ByteArray): Boolean {
        if (body.size < 16) return false
        val isAudio = body[0] == 0x40.toByte() && body[1] == 0x9e.toByte()
        logW("StreamProps body=${body.size}B audio=$isAudio head=" +
            body.take(16).joinToString("") { "%02x".format(it) })
        if (!isAudio) return false
        val tagIdx = (0 until body.size - 14).firstOrNull { i ->
            val t = readLeU16At(body, i)
            val ok = t in 0x0160..0x0162 && readLeU16At(body, i + 2) in 1..8 &&
                readLeU32At(body, i + 4) in 8000..96000 &&
                readLeU16At(body, i + 12) > 0
            if (!ok && t in 0x0160..0x0162) {
                logW("wma tag @body $i 校验失败: ch=${readLeU16At(body, i + 2)} " +
                    "rate=${readLeU32At(body, i + 4)} align=${readLeU16At(body, i + 12)}")
            }
            ok
        } ?: run {
            logW("WAVEFORMATEX 模式搜索失败: body=${body.size}B audio=$isAudio")
            return false
        }
        wmaCodecTag = readLeU16At(body, tagIdx)
        channels = readLeU16At(body, tagIdx + 2).toInt()
        sampleRate = readLeU32At(body, tagIdx + 4).toInt()
        // WAVEFORMATEX: wftag(2) ch(2) rate(4) avg(4) → blockAlign(2) @ tagIdx+12
        blockAlign = readLeU16At(body, tagIdx + 12).toInt()
        return blockAlign > 0 && sampleRate > 0
    }

    /** 逐包：读包头 27B（send/delta），投递包尾 block_align 字节的 WMA 帧。 */
    private fun readPackets(input: ExtractorInput): Int {
        if (currentPacket >= packetCount) return Extractor.RESULT_END_OF_INPUT
        if (packetSize <= 0 || blockAlign <= 0) return Extractor.RESULT_END_OF_INPUT

        val head = ByteArray(27)
        if (!input.readFully(head, 0, 27, true)) return Extractor.RESULT_END_OF_INPUT
        val sendMs = (readLeU32At(head, 6) and 0xFFFFFFFFL)
        val deltaMs = readLeU32At(head, 23) and 0xFFFFFFFFL

        val fed = trackOutput?.sampleData(input, blockAlign, true) ?: 0
        if (fed <= 0) return Extractor.RESULT_END_OF_INPUT
        val timeUs = (sendMs + deltaMs) * 1000
        trackOutput?.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, fed, 0, null)
        currentPacket++
        return Extractor.RESULT_CONTINUE
    }

    /** §61.94：JVM 单测环境无 android.util.Log（未 mock）——双环境安全输出。 */
    private fun logW(msg: String) {
        try {
            Log.w(TAG, msg)
        } catch (t: Throwable) {
            println("[CM-Asf] $msg")
        }
    }

    override fun release() {
        output = null
        trackOutput = null
    }

    companion object {
        private const val TAG = "CM-Asf"
        private const val STATE_READ_HEADER = 0
        private const val STATE_READ_PACKETS = 1

        val ASF_HEADER_GUID = byteArrayOf(
            0x30.toByte(), 0x26.toByte(), 0xB2.toByte(), 0x75.toByte(), 0x8E.toByte(), 0x66.toByte(), 0xCF.toByte(), 0x11.toByte(),
            0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(), 0x00, 0x62, 0xCE.toByte(), 0x6C,
        )
        private val FILE_PROPS_GUID = byteArrayOf(
            0xA1.toByte(), 0xDC.toByte(), 0xAB.toByte(), 0x8C.toByte(), 0x47.toByte(), 0xA9.toByte(), 0xCF.toByte(), 0x11.toByte(),
            0x8E.toByte(), 0xE4.toByte(), 0x00, 0xC0.toByte(), 0x0C, 0x20, 0x53, 0x65,
        )
        private val STREAM_PROPS_GUID = byteArrayOf(
            0x91.toByte(), 0x07.toByte(), 0xDC.toByte(), 0xB7.toByte(), 0xB7.toByte(), 0xA9.toByte(), 0xCF.toByte(), 0x11.toByte(),
            0x8E.toByte(), 0xE6.toByte(), 0x00, 0xC0.toByte(), 0x0C, 0x20, 0x53, 0x65,
        )
        private val DATA_GUID = byteArrayOf(
            0x36.toByte(), 0x26.toByte(), 0xB2.toByte(), 0x75.toByte(), 0x8E.toByte(), 0x66.toByte(), 0xCF.toByte(), 0x11.toByte(),
            0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(), 0x00, 0x62, 0xCE.toByte(), 0x6C,
        )

        private fun readLeU16At(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun readLeU32At(b: ByteArray, off: Int): Long =
            (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

        private fun leU64(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }

        private fun readLeU64(input: ExtractorInput): Long? {
            val b = ByteArray(8)
            if (!input.readFully(b, 0, 8, true)) return null
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }
    }
}
