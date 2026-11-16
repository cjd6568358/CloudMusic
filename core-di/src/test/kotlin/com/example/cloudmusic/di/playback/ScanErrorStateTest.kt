package com.example.cloudmusic.di.playback

import com.example.cloudmusic.scanner.ScanError
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §61.184：Storage 级扫描错误 → 数据源错误态（`storageErrorStates` 纯判定）。
 * `StorageRepository.setError` 此前零生产写方（审计揪出的断链）——设置页
 * `StorageDto.errorMsg` 有读无写，扫描失败用户永远看不到。
 */
class ScanErrorStateTest {

    private val storageIds = setOf("nas-1", "webdav-strm-probe")

    @Test
    @DisplayName("Storage 级失败（path == storageId）→ code: detail 错误态")
    fun storageFailureBecomesErrorState() {
        val states = storageErrorStates(
            listOf(ScanError("nas-1", "E_DS_UNREACHABLE", "connect timeout")),
            storageIds,
            seenStorages = setOf("nas-1"),
        )
        assertEquals("E_DS_UNREACHABLE: connect timeout", states["nas-1"])
    }

    @Test
    @DisplayName("文件级错误（path 含 /）不误伤数据源错误态；storage 可达 → 允许清陈旧")
    fun fileErrorsDoNotTouchStorageState() {
        val states = storageErrorStates(
            listOf(
                ScanError("/nas-1/音乐/坏文件.flac", "E_TAG", "header corrupt"),
                ScanError("webdav-strm-probe/子目录/xx.strm", "E_READ", "404"),
            ),
            storageIds,
            seenStorages = setOf("nas-1", "webdav-strm-probe"),
        )
        assertTrue(
            states.all { (_, msg) -> msg.isBlank() },
            "文件路径与 storageId（无 /）天然不撞：不得把文件级失败写成数据源错误态",
        )
        assertEquals(setOf("nas-1", "webdav-strm-probe"), states.keys,
            "storage 本轮可达（清单成功）→ 幂等清陈旧错误态")
    }

    @Test
    @DisplayName("本轮跑过且未失败 → 清陈旧 errorMsg（幂等空串）；没跑过 → 不动")
    fun cleanStoragesClearStaleError() {
        val states = storageErrorStates(
            listOf(ScanError("nas-1", "E_DS_UNREACHABLE", "down")),
            storageIds,
            seenStorages = setOf("nas-1", "webdav-strm-probe", "ghost-storage"),
        )
        assertEquals("E_DS_UNREACHABLE: down", states["nas-1"])
        assertEquals("", states["webdav-strm-probe"], "本轮跑过且干净 → 幂等清空")
        assertTrue("ghost-storage" !in states, "本轮没跑过 → 保持原样，不伪造正常")
    }

    @Test
    @DisplayName("detail 截 120 字防天书刷屏")
    fun detailTruncated() {
        val long = "x".repeat(500)
        val states = storageErrorStates(
            listOf(ScanError("nas-1", "E_SCAN", long)),
            storageIds,
            seenStorages = emptySet(),
        )
        assertEquals("E_SCAN: " + "x".repeat(120), states["nas-1"])
    }
}
