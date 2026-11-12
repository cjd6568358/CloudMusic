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

    override fun track(id: Int, type: Int): TrackOutput = this
    override fun seekMap(map: SeekMap) { seekMap = map }
    override fun endTracks() {}

    override fun format(format: Format) { formats += format }

    override fun sampleData(
        input: DataReader, length: Int, allowEndOfInput: Boolean,
    ): Int {
        val buf = ByteArray(length)
        val n = input.read(buf, 0, length)
        sampleBytes += if (n > 0) n else 0
        return n
    }

    override fun sampleData(data: androidx.media3.common.util.ParsableByteArray, length: Int) {
        sampleBytes += length
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
