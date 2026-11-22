package com.example.cloudmusic.bridge

import com.example.cloudmusic.model.Header
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §61.302（H0-3）：HttpDataSource 顺序流续泵回归防线——
 * 「每 64KiB 一个全新 HTTP GET」结构税（起播吞吐钳在 64KiB/RTT）消除的判定。
 */
class HttpDataSourceStreamTest {

    private val data = ByteArray(300_000) { ((it * 31) and 0xff).toByte() }

    /** 计数栈：openStream 真流式（ByteArrayInputStream 视图）；fetch 仅计数（顺序路径不应触达）。 */
    private class CountingStack(private val data: ByteArray) : RangeHttpStack {
        var opens = 0
        var fetches = 0
        val openOffsets = mutableListOf<Long>()

        override fun fetch(
            url: String,
            headers: List<Header>,
            rangeStart: Long,
            redirect: Boolean,
            rangeEnd: Long?,
        ): HttpSlice {
            fetches++
            val end = (rangeEnd ?: (rangeStart + 64 * 1024 - 1)).coerceAtMost(data.size - 1L)
            val body = if (rangeStart >= data.size) ByteArray(0)
            else data.copyOfRange(rangeStart.toInt(), (end + 1).toInt())
            return HttpSlice(body, data.size.toLong())
        }

        override fun openStream(
            url: String,
            headers: List<Header>,
            rangeStart: Long,
            redirect: Boolean,
        ): HttpStreamSlice {
            opens++
            openOffsets += rangeStart
            return HttpStreamSlice(
                ByteArrayInputStream(data, rangeStart.toInt(), (data.size - rangeStart).toInt()),
                data.size.toLong(),
            )
        }
    }

    private fun ds(stack: RangeHttpStack) =
        HttpDataSource(Link(url = "http://x/a.mp3"), LinkArgs(), stack)

    @Test
    @DisplayName("§61.302 · 顺序读单次开流连续泵（原每 64KiB 一 GET）")
    fun sequentialReadsUseSingleOpen() {
        val stack = CountingStack(data)
        val d = ds(stack)
        val buf = ByteArray(64 * 1024)
        assertEquals(64 * 1024, d.readAt(0, buf, buf.size))
        assertTrue(buf.contentEquals(data.copyOfRange(0, buf.size)), "首读内容正确")
        assertEquals(64 * 1024, d.readAt((64 * 1024).toLong(), buf, buf.size))
        assertEquals(64 * 1024, d.readAt((128 * 1024).toLong(), buf, buf.size))
        assertEquals(1, stack.opens, "三次顺序读只开一次上游流")
        assertEquals(0, stack.fetches, "顺序路径不物化 fetch")
        assertEquals(listOf(0L), stack.openOffsets)
        d.close()
    }

    @Test
    @DisplayName("§61.302 · 非顺序读重开流（随机读语义不回归）")
    fun nonSequentialReadReopens() {
        val stack = CountingStack(data)
        val d = ds(stack)
        val buf = ByteArray(1_000)
        assertEquals(1_000, d.readAt(0, buf, buf.size))
        assertEquals(1_000, d.readAt(200_000L, buf, buf.size))
        assertEquals(2, stack.opens)
        assertEquals(listOf(0L, 200_000L), stack.openOffsets)
        assertEquals(data.copyOfRange(200_000, 201_000).toList(), buf.toList())
        d.close()
    }

    @Test
    @DisplayName("§61.302 · 顺序流自然 EOF 读 0（readAt 0 = EOF 口径，§15 T05）")
    fun sequentialEofReturnsZero() {
        val stack = CountingStack(ByteArray(1_000) { 7 })
        val d = ds(stack)
        val buf = ByteArray(2_000)
        assertEquals(1_000, d.readAt(0, buf, buf.size), "首读返回文件余量")
        assertEquals(0, d.readAt(1_000L, buf, buf.size), "EOF 读 0")
        assertEquals(1, stack.opens)
        d.close()
    }

    @Test
    @DisplayName("§61.302 · 陈旧流 IOE 自动重开（服务端空闲断开不报错）")
    fun staleStreamRecoversByReopen() {
        val stack = CountingStack(data)
        var poisoned = false
        val flaky = object : RangeHttpStack by stack {
            override fun openStream(
                url: String,
                headers: List<Header>,
                rangeStart: Long,
                redirect: Boolean,
            ): HttpStreamSlice =
                if (!poisoned && rangeStart > 0) {
                    poisoned = true
                    HttpStreamSlice(
                        object : InputStream() {
                            override fun read(): Int = throw IOException("stale stream")
                        },
                        data.size.toLong(),
                    )
                } else {
                    stack.openStream(url, headers, rangeStart, redirect)
                }
        }
        val d = ds(flaky)
        val buf = ByteArray(1_000)
        assertEquals(1_000, d.readAt(0, buf, buf.size))
        // 第二读命中陈旧流 → IOE → 关流重开成功（不是 -1 错误）
        assertEquals(1_000, d.readAt(1_000L, buf, buf.size))
        assertEquals(data.copyOfRange(1_000, 2_000).toList(), buf.toList())
        d.close()
    }

    @Test
    @DisplayName("§61.302 · close 释放上游流连接（幂等）")
    fun closeReleasesStream() {
        var closed = false
        val stack = object : RangeHttpStack {
            override fun fetch(
                url: String,
                headers: List<Header>,
                rangeStart: Long,
                redirect: Boolean,
                rangeEnd: Long?,
            ): HttpSlice = HttpSlice(ByteArray(0), -1L)

            override fun openStream(
                url: String,
                headers: List<Header>,
                rangeStart: Long,
                redirect: Boolean,
            ): HttpStreamSlice =
                HttpStreamSlice(
                    object : InputStream() {
                        override fun read(): Int = -1
                        override fun close() {
                            closed = true
                        }
                    },
                    -1L,
                )
        }
        val d = ds(stack)
        val buf = ByteArray(4)
        assertEquals(0, d.readAt(0, buf, buf.size), "开流即 EOF → 读 0")
        d.close()
        assertTrue(closed, "closeInternal 必须关闭上游流")
        d.close() // 幂等
    }
}
