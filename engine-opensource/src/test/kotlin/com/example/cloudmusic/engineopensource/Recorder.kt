package com.example.cloudmusic.engineopensource

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.common.DataReader

/**
 * §61.94/§61.97 测试基建：Extractor 输出的收集器（Format/SeekMap/样本）。
 */
@UnstableApi
internal class Recorder : ExtractorOutput, TrackOutput {

    val formats = mutableListOf<Format>()
    var seekMap: SeekMap? = null
    var samples = 0
    var sampleBytes = 0L
    val sampleTimesUs = mutableListOf<Long>()

    /** §61.105：endTracks 调用次数（Media3 的 sampleQueuesBuilt 依赖它）。 */
    var endTracksCalls = 0
    var seekMapCalls = 0

    /**
     * §61.322 黄金样本比对：按调用顺序保存样本字节。默认关闭——其他用例不读
     * 内容，开着会把整段音频留在内存里（截断样本也有 4MiB）。
     */
    var capturePayloads = false
    val payloads = mutableListOf<ByteArray>()

    override fun track(id: Int, type: Int): TrackOutput = this
    override fun seekMap(map: SeekMap) { seekMap = map; seekMapCalls++ }
    override fun endTracks() { endTracksCalls++ }

    override fun format(format: Format) { formats += format }

    override fun sampleData(
        input: DataReader, length: Int, allowEndOfInput: Boolean,
    ): Int {
        val buf = ByteArray(length)
        val n = input.read(buf, 0, length)
        sampleBytes += if (n > 0) n else 0
        if (capturePayloads && n > 0) payloads += buf.copyOf(n)
        return n
    }

    override fun sampleData(data: androidx.media3.common.util.ParsableByteArray, length: Int) {
        sampleBytes += length
        if (capturePayloads) {
            // §61.322：调用方给的是完整可读长度（ApeExtractor 传 fed），不能读 buffer 全量。
            val bytes = ByteArray(length)
            System.arraycopy(data.data, data.position, bytes, 0, length)
            payloads += bytes
        }
    }

    override fun sampleData(
        input: DataReader, length: Int, allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int = sampleData(input, length, allowEndOfInput)

    override fun sampleData(
        data: androidx.media3.common.util.ParsableByteArray, length: Int, sampleDataPart: Int,
    ) {
        sampleData(data, length)
    }

    override fun sampleMetadata(
        timeUs: Long, flags: Int, size: Int, offset: Int,
        cryptoData: androidx.media3.extractor.TrackOutput.CryptoData?,
    ) {
        samples++
        sampleTimesUs += timeUs
    }
}
