package com.example.cloudmusic

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §61.249：会话封面位图——coverArtFile 纯函数（ref 解析/锚定/存在性）边界矩阵。 */
class PlaybackServiceCoverArtTest {

    @TempDir
    lateinit var cacheDir: File

    @Test
    @DisplayName("§61.249 C1 · 绝对路径存在 → 原样；不存在 → null")
    fun absolutePath() {
        val f = File(cacheDir, "covers/x.webp").apply {
            parentFile.mkdirs(); writeBytes(byteArrayOf(1))
        }
        assertEquals(f.absolutePath, PlaybackService.coverArtFile(f.absolutePath, cacheDir)!!.absolutePath)
        assertNull(PlaybackService.coverArtFile("/non/exists/x.webp", cacheDir), "不存在不伪造")
    }

    @Test
    @DisplayName("§61.249 C2 · 相对 ref 锚 cacheDir（§61.223 cacheDirPrefix 教训同源）")
    fun relativeAnchorsToCacheDir() {
        val f = File(cacheDir, "covers/y.webp").apply {
            parentFile.mkdirs(); writeBytes(byteArrayOf(1))
        }
        val got = PlaybackService.coverArtFile("covers/y.webp", cacheDir)
        assertTrue(got?.isFile == true, "相对 ref 锚 cacheDir 解析")
        assertEquals(f.absolutePath, got!!.absolutePath)
    }

    @Test
    @DisplayName("§61.249 C3 · content:// / 空 / null → null（走 ContentResolver 或无封面态）")
    fun nonFileRefs() {
        assertNull(PlaybackService.coverArtFile("content://media/external/audio/1", cacheDir))
        assertNull(PlaybackService.coverArtFile("", cacheDir))
        assertNull(PlaybackService.coverArtFile("   ", cacheDir))
        assertNull(PlaybackService.coverArtFile(null, cacheDir))
    }
}
