package com.example.cloudmusic.ui

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §61.294：动作反馈文案的口径真值表。
 *
 * 为什么值得钉死：这六句话是用户唯一能看到的「刚才那下生效了」。三种退化都很具体——
 * ① 名字不截断：42 字的歌单名把 Toast 撑成三行、盖住半个屏幕；
 * ② 空名不兜底：拼出「已加入「」」，看起来像 bug；
 * ③ 六类文案串味：新建和加入共用一句，用户分不清是不是多建了一个歌单。
 * 都只在真机上看得见，所以用纯 JVM 断言固定。
 */
class FeedbackTextTest {

    @Test
    @DisplayName("短名原样保留并去掉首尾空格（用户可能手滑打了空格）")
    fun shortNameKeptTrimmed() {
        assertEquals("周杰伦", FeedbackText.brief("  周杰伦 "))
        assertEquals("周杰伦", FeedbackText.brief("周杰伦"))
    }

    @Test
    @DisplayName("超长名截断到上限 + 省略号（Toast 只能一行）")
    fun longNameTruncated() {
        val long = "这是一个非常非常长的歌单名字用来测试截断行为"
        val brief = FeedbackText.brief(long)
        assertEquals(FeedbackText.NAME_LIMIT + 1, brief.length)
        assertTrue(brief.endsWith("…"))
        assertEquals(long.take(FeedbackText.NAME_LIMIT), brief.dropLast(1))
    }

    @Test
    @DisplayName("空名/纯空白回退中性词，禁出现「已加入「」」")
    fun blankNameFallsBack() {
        assertEquals("该歌单", FeedbackText.brief(""))
        assertEquals("该歌单", FeedbackText.brief("   "))
        assertEquals("已加入「该歌单」", FeedbackText.addedToPlaylist(""))
    }

    @Test
    @DisplayName("六类文案都带名字，且不超过 Toast 单行长度上限")
    fun messagesCarryNameAndStayShort() {
        val name = "我的收藏"
        val messages = listOf(
            FeedbackText.addedToPlaylist(name),
            FeedbackText.createdWithTrack(name),
            FeedbackText.createdPlaylist(name),
            FeedbackText.renamedPlaylist(name),
            FeedbackText.deletedPlaylist(name),
            FeedbackText.removedFromPlaylist(name),
        )
        messages.forEach {
            assertTrue(it.contains(name), "文案丢了名字：$it")
            assertTrue(it.length <= 24, "文案过长会撑两行：$it（${it.length} 字）")
            assertTrue('\n' !in it, "文案不得含换行：$it")
        }
    }

    @Test
    @DisplayName("六类文案互不相同——新建/加入不能共用一句")
    fun messagesAreDistinct() {
        val name = "通勤"
        val messages = listOf(
            FeedbackText.addedToPlaylist(name),
            FeedbackText.createdWithTrack(name),
            FeedbackText.createdPlaylist(name),
            FeedbackText.renamedPlaylist(name),
            FeedbackText.deletedPlaylist(name),
            FeedbackText.removedFromPlaylist(name),
        )
        assertEquals(messages.size, messages.toSet().size)
        assertNotEquals(
            FeedbackText.createdPlaylist(name),
            FeedbackText.createdWithTrack(name),
        )
    }
}
