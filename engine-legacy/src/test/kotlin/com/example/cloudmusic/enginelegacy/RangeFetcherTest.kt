package com.example.cloudmusic.enginelegacy

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 12 §54（第四十五批）· native 取流协议的**纯 JVM** 回归用例。
 *
 * 覆盖 `12 §53` 实证出的 `Start(requestId, offset, length)` 语义与
 * `OnDataRequestBegin/Data/End` 回传序列；不实例化 `SourceDataRequest`
 * （其构造会调 native `InitPtr()`），只测 [RangeFetcher] 这一层可判定逻辑。
 */
class RangeFetcherTest {

    private class FakeOpener(
        private val bytes: ByteArray,
        private val total: Long = bytes.size.toLong(),
        private val fail: String? = null,
    ) : RangeOpener {
        var seenUrl: String? = null
        var seenOffset: Long = -1
        var seenLength: Long = -1

        override fun open(url: String, offset: Long, length: Long): RangeResponse {
            seenUrl = url
            seenOffset = offset
            seenLength = length
            if (fail != null) error(fail)
            return RangeResponse(ByteArrayInputStream(bytes), total, bytes.size.toLong())
        }
    }

    private class Recorder : RangeSink {
        var beginTotal: Long? = null
        val beginIds = mutableListOf<Long>()
        val dataIds = mutableListOf<Long>()
        val offsets = mutableListOf<Long>()
        val counts = mutableListOf<Long>()
        var endError: String? = "UNSET"
        var endCalled = false
        var endId: Long? = null

        override fun onBegin(requestId: Long, totalLength: Long) {
            beginIds += requestId
            beginTotal = totalLength
        }

        override fun onData(requestId: Long, bytes: ByteArray, offset: Long, count: Long) {
            dataIds += requestId
            offsets += offset
            counts += count
            assertEquals(count.toInt(), bytes.size, "回传 byte[] 必须与 count 等长")
        }

        override fun onEnd(requestId: Long, error: String?) {
            endCalled = true
            endId = requestId
            endError = error
        }
    }

    @Test
    @DisplayName("12 §54 · Range 头：闭区间与开放式区间")
    fun rangeHeader() {
        assertEquals("bytes=0-999", RangeFetcher.rangeHeader(0, 1000))
        assertEquals("bytes=1024-2047", RangeFetcher.rangeHeader(1024, 1024))
        assertEquals("bytes=100-", RangeFetcher.rangeHeader(100, 0))
    }

    @Test
    @DisplayName("12 §54 · Content-Range 解析：标准 / 无起点 / 缺失 / 非法")
    fun parseTotal() {
        assertEquals(37824L, RangeFetcher.parseTotal("bytes 0-999/37824"))
        assertEquals(37824L, RangeFetcher.parseTotal("bytes */37824"))
        assertEquals(-1L, RangeFetcher.parseTotal(null))
        assertEquals(-1L, RangeFetcher.parseTotal("bytes 0-999"))
        assertEquals(-1L, RangeFetcher.parseTotal("bytes 0-999/abc"))
    }

    @Test
    @DisplayName("12 §54 · 正常取流：Begin(total) → Data 序列 → End(null)，requestId 原样带回")
    fun fetchHappyPath() {
        val payload = ByteArray(40) { it.toByte() }
        val opener = FakeOpener(payload, total = 37824L)
        val sink = Recorder()
        val read = RangeFetcher.fetch("http://127.0.0.1/a.mp3", 77L, 100L, 40L, opener, sink)

        assertEquals(40L, read)
        assertEquals("http://127.0.0.1/a.mp3", opener.seenUrl)
        assertEquals(100L, opener.seenOffset, "j2 = offset")
        assertEquals(40L, opener.seenLength, "j3 = length")
        assertEquals(37824L, sink.beginTotal)
        assertEquals(listOf(77L), sink.beginIds)
        assertTrue(sink.dataIds.all { it == 77L }, "每个 OnDataRequestData 首参都是 requestId")
        assertEquals(listOf(100L), sink.offsets, "首个数据块偏移 = offset")
        assertEquals(listOf(40L), sink.counts)
        assertTrue(sink.endCalled)
        assertEquals(77L, sink.endId)
        assertNull(sink.endError, "成功时 End 的 error 为 null")
    }

    @Test
    @DisplayName("12 §54 · 大分片：16 KiB 分块、offset 递增、requestId 恒定")
    fun fetchChunking() {
        val payload = ByteArray(RangeFetcher.CHUNK * 2 + 123) { (it % 251).toByte() }
        val opener = FakeOpener(payload)
        val sink = Recorder()
        val read = RangeFetcher.fetch("http://h/a", 9L, 5000L, payload.size.toLong(), opener, sink)

        assertEquals(payload.size.toLong(), read)
        assertEquals(3, sink.counts.size, "16K + 16K + 123 三片")
        assertEquals(listOf(5000L, 5000L + RangeFetcher.CHUNK, 5000L + 2L * RangeFetcher.CHUNK), sink.offsets)
        assertEquals(payload.size.toLong(), sink.counts.sum())
        assertTrue(sink.dataIds.all { it == 9L })
        assertNull(sink.endError)
    }

    @Test
    @DisplayName("12 §54 · length 限制：不把超出请求长度的字节回传给 native")
    fun fetchHonoursLength() {
        val payload = ByteArray(200) { it.toByte() }
        val sink = Recorder()
        val read = RangeFetcher.fetch("http://h/a", 1L, 0L, 50L, FakeOpener(payload), sink)
        assertEquals(50L, read)
        // 单次 read 可能读满 buffer；实现按 length 截断回传，保证不超过请求区间。
        assertTrue(sink.counts.sum() <= 50L)
    }

    @Test
    @DisplayName("12 §54 · opener 失败：异常不冒泡，转成 End(error)")
    fun fetchFailure() {
        val sink = Recorder()
        val read = RangeFetcher.fetch("http://h/a", 5L, 0L, 10L, FakeOpener(ByteArray(0), fail = "HTTP 404"), sink)
        assertEquals(0L, read)
        assertTrue(sink.endCalled)
        assertEquals("HTTP 404", sink.endError)
        assertTrue(sink.dataIds.isEmpty(), "失败不应回传数据")
    }

    @Test
    @DisplayName("12 §54 · Stop 取消：不回传数据，仍走 End 收尾")
    fun fetchCancelled() {
        val sink = Recorder()
        val read = RangeFetcher.fetch(
            "http://h/a", 3L, 0L, 100L,
            FakeOpener(ByteArray(100)), sink,
            cancelled = { true },
        )
        assertEquals(0L, read)
        assertTrue(sink.dataIds.isEmpty())
        assertTrue(sink.endCalled, "取消也要让 native 收到 End，避免等待悬挂")
        assertNull(sink.endError)
    }
}
