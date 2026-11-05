package com.example.cloudmusic.bridge

import com.example.cloudmusic.model.Header
import com.example.cloudmusic.model.IDataSource
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/03-06 §15 T01~T05 + §7.1 / §7.2 的纯 JVM 判定部分。 */
class DataSourceAdapterTest {

    private class FakeHttp(
        private val body: ByteArray,
        private val total: Long = body.size.toLong(),
    ) : RangeHttpStack {
        data class Call(val url: String, val headers: List<Header>, val rangeStart: Long, val redirect: Boolean)
        val calls = mutableListOf<Call>()

        override fun fetch(url: String, headers: List<Header>, rangeStart: Long, redirect: Boolean): HttpSlice {
            calls.add(Call(url, headers, rangeStart, redirect))
            val start = rangeStart.toInt().coerceIn(0, body.size)
            return HttpSlice(body.copyOfRange(start, body.size), total, "audio/flac")
        }
    }

    private class FailingHttp : RangeHttpStack {
        override fun fetch(url: String, headers: List<Header>, rangeStart: Long, redirect: Boolean): HttpSlice =
            throw IllegalStateException("模拟上游读取失败")
    }

    private class FakeFd(private val data: ByteArray) : SeekableSource {
        var closed = false
        val offsets = mutableListOf<Long>()
        override fun size(): Long = data.size.toLong()
        override fun readAt(offset: Long, buf: ByteArray, len: Int): Int {
            offsets.add(offset)
            if (offset >= size()) return 0
            val n = minOf(len.toLong(), size() - offset).toInt()
            data.copyInto(buf, 0, offset.toInt(), offset.toInt() + n)
            return n
        }

        override fun close() { closed = true }
    }

    private class FakeFdOpener(private val fd: FakeFd) : FdOpener {
        var openCount = 0
            private set
        override fun open(path: String): SeekableSource { openCount++; return fd }
    }

    private class FakeDriverStreams : DriverStreamFactory {
        var openCount = 0
            private set
        override fun open(link: Link): IDataSource {
            openCount++
            return MemoryDataSource(Link(text = "driver-bytes", proxy = true))
        }
    }

    // ------------------------------------------------------------------
    // T01~T03：只按 Link.kind 分派
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T01 · from(Link(url=…)) → HttpDataSource")
    fun t01() {
        val ds = DataSourceAdapter.from(Link(url = "https://example.com/a.flac"))
        assertIs<HttpDataSource>(ds)
        assertEquals("audio/flac", ds.mimeHint())
    }

    @Test
    @DisplayName("T02 · from(Link(localPath=…)) → FileDataSource")
    fun t02() {
        val ds = DataSourceAdapter.from(Link(localPath = "/music/a.flac", localSize = 100))
        assertIs<FileDataSource>(ds)
        assertEquals("audio/flac", ds.mimeHint())
    }

    @Test
    @DisplayName("T03 · from(Link(proxy=true)) → DriverDataSource，且不创建 nanohttpd / 不提前开流")
    fun t03() {
        val ds = DataSourceAdapter.from(Link(proxy = true))
        assertIs<DriverDataSource>(ds)

        val factory = FakeDriverStreams()
        val withFake = DataSourceAdapter.from(Link(proxy = true), driverStreams = factory)
        assertEquals(0, factory.openCount, "构造时不得启动服务或提前开流")
        assertEquals("driver-bytes".length.toLong(), withFake.open(0))
        assertEquals(1, factory.openCount)
        withFake.close()
    }

    // ------------------------------------------------------------------
    // T04 / T05：offset 读、错误与 EOF 区分
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T04 · MemoryDataSource 按 offset 读，内容与文本切片一致")
    fun t04() {
        val link = Link(text = "hello world", proxy = true)
        val ds = DataSourceAdapter.from(link)
        assertIs<MemoryDataSource>(ds)
        assertEquals(link.text!!.length.toLong(), ds.open(0))

        val buf = ByteArray(5)
        assertEquals(5, ds.readAt(6, buf, 5))
        assertEquals("world", String(buf))
        assertEquals(0, ds.readAt(100, buf, 5), "越界为 EOF")
        assertEquals(0, ds.readAt(0, buf, 0), "len=0 短路")
        ds.close()
    }

    @Test
    @DisplayName("T05 · readAt 返回 -1 是错误分支，不得当 EOF；lastError 可判定")
    fun t05() {
        val ds = DataSourceAdapter.from(
            Link(url = "https://example.com/a.mp3"),
            http = FailingHttp(),
        ) as HttpDataSource

        val buf = ByteArray(8)
        assertEquals(-1, ds.readAt(0, buf, 8))
        assertNotNull(ds.lastError, "错误分支必须留下可判定的 CloudMusicError")
        assertTrue(ds.lastError!!.code.startsWith("E_DS_UPSTREAM_"))
        assertEquals(0, ds.readAt(0, buf, 0), "len=0 不进入错误分支")
        ds.close()
    }

    @Test
    @DisplayName("§7.2 · EOF 返回 0 且不置 lastError；与 -1 明确区分")
    fun eofIsNotError() {
        val ds = DataSourceAdapter.from(
            Link(url = "https://example.com/a.mp3"),
            http = FakeHttp(ByteArray(0), total = 0),
        ) as HttpDataSource
        assertEquals(0, ds.open(0))
        assertEquals(0, ds.readAt(0, ByteArray(4), 4))
        assertNull(ds.lastError)
    }

    // ------------------------------------------------------------------
    // 端口透传、线程安全骨架、close 幂等
    // ------------------------------------------------------------------

    @Test
    @DisplayName("§7 · HttpDataSource 透传 Link + LinkArgs 头并带 Range 起点")
    fun httpRangeAndHeaders() {
        val http = FakeHttp("0123456789".encodeToByteArray())
        val ds = DataSourceAdapter.from(
            Link(url = "https://example.com/a.flac", headers = listOf(Header("Authorization", "Basic x"))),
            args = LinkArgs(headers = listOf(Header("X-Trace", "1")), redirect = true),
            http = http,
        )
        assertEquals(10L, ds.open(0))
        val buf = ByteArray(4)
        assertEquals(4, ds.readAt(3, buf, 4))
        assertEquals("3456", String(buf))

        val call = http.calls.last()
        assertEquals(3L, call.rangeStart)
        assertTrue(call.redirect)
        assertEquals(listOf("Authorization", "X-Trace"), call.headers.map { it.name })
        assertEquals("audio/flac", ds.mimeHint())
        ds.close()
    }

    @Test
    @DisplayName("§7.1 · FileDataSource 经 FdOpener 按 offset 读，close 幂等且释放句柄")
    fun fileViaFdOpener() {
        val fd = FakeFd("abcdef".encodeToByteArray())
        val opener = FakeFdOpener(fd)
        val ds = DataSourceAdapter.from(Link(localPath = "/music/a.flac"), fdOpener = opener)
        assertEquals(6L, ds.open(0))
        val buf = ByteArray(3)
        assertEquals(3, ds.readAt(2, buf, 3))
        assertEquals("cde", String(buf))
        assertEquals(listOf(2L), fd.offsets)
        ds.close()
        ds.close()
        assertTrue(fd.closed)
        assertEquals(1, opener.openCount, "自动懒打开只发生一次")
        assertFailsWith<IllegalStateException> { ds.readAt(0, buf, 3) }
    }

    @Test
    @DisplayName("§7.1 · BaseDataSource 参数校验：offset / len 与关闭态")
    fun baseDataSourceGuards() {
        val ds = DataSourceAdapter.from(Link(text = "abc", proxy = true))
        val buf = ByteArray(4)
        assertFailsWith<IllegalArgumentException> { ds.readAt(-1, buf, 2) }
        assertFailsWith<IllegalArgumentException> { ds.readAt(0, buf, 5) }
        ds.close()
        assertFailsWith<IllegalStateException> { ds.readAt(0, buf, 2) }
    }

    @Test
    @DisplayName("§7.2 · readAt 可被并发调用（@Synchronized 骨架）")
    fun concurrentReads() {
        val ds = DataSourceAdapter.from(Link(text = "0123456789", proxy = true)) as MemoryDataSource
        ds.open(0)
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..4).map {
            Thread {
                repeat(50) {
                    val buf = ByteArray(4)
                    if (ds.readAt(0, buf, 4) != 4 || String(buf) != "0123") failures.incrementAndGet()
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        assertEquals(0, failures.get(), "并发读不得出现数据竞争")
        assertNull(ds.lastError)
        ds.close()
    }

    @Test
    @DisplayName("MimeHint · 扩展名映射与未知后缀兜底")
    fun mimeHints() {
        assertEquals("audio/mpeg", MimeHint.of(Link(url = "https://x/a.MP3?token=1")))
        assertEquals("audio/flac", MimeHint.of(Link(localPath = "/a.flac")))
        assertEquals("application/octet-stream", MimeHint.of(Link(url = "https://x/a.bin")))
        assertEquals("text/plain", MimeHint.of(Link(url = "https://x/a"), "text/plain"))
    }
}
