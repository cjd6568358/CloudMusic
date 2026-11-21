package com.example.cloudmusic.ui

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §61.290：图标枚举的 `label` 是 **TalkBack 播报名**，不是注释。
 *
 * 为什么值得一组断言：`FnLineIcon` 画在 `Canvas` 上，默认不产生任何语义节点。纯图标
 * 控件（底栏 tab / 页头圆钮 / 歌单行三键 / 迷你条 / 播放键）在无障碍服务里本来只剩
 * 「按钮」两个字——名字全靠这里。所以这组断言的口径是「**漏写要被构建挡住**」：
 *
 * 1. **条数钉死**：新增图标必须同时决定播报名，否则此处先红；
 * 2. **非空 / 唯一**：两个不同图标叫同一个名字，用户听到的是「重扫」却触发了「搜索」；
 * 3. **无 ASCII**：`"Refresh"` / `"OK"` 这类英文名在中文 TalkBack 下读成字母串；
 * 4. **长度上限**：播报是线性朗读，超长名字会把列表焦点移动拖成念稿。
 *
 * 真机 TalkBack 实际播报（语速、合并语义、焦点顺序）归 V0.4-09（U-17 判读）。
 */
class FnIconLabelTest {

    /** 系统语言为中文时的播报友好上限（4 字为主，留 1 字余量）。 */
    private val maxLabelLength = 5

    @Test
    @DisplayName("图标枚举条数钉死：新增图标必须一并决定播报名")
    fun iconCountIsPinned() {
        assertEquals(
            expected = 29,
            actual = FnIcon.entries.size,
            message = "新增/删除 FnIcon 时必须同步本断言与 label，避免哑图标流入界面",
        )
    }

    @Test
    @DisplayName("每个图标的播报名非空且无多余空白")
    fun everyLabelIsNonBlank() {
        val bad = FnIcon.entries.filter { it.label.isBlank() || it.label != it.label.trim() }
        assertTrue(bad.isEmpty(), "空白或带首尾空白的播报名：${bad.map { it.name }}")
    }

    @Test
    @DisplayName("同枚举内播报名唯一：不同图标不能叫同一个名字")
    fun labelsAreUnique() {
        val dup = FnIcon.entries.groupBy { it.label }.filterValues { it.size > 1 }
        assertTrue(dup.isEmpty(), "重名播报：${dup.map { "${it.key}=${it.value.map { e -> e.name }}" }}")
    }

    @Test
    @DisplayName("播报名不含 ASCII：避免中文 TalkBack 逐个念字母")
    fun labelsHaveNoAscii() {
        val bad = FnIcon.entries.filter { e -> e.label.any { it.code < 128 } }
        assertTrue(bad.isEmpty(), "含 ASCII 字符的播报名：${bad.map { "${it.name}=${it.label}" }}")
    }

    @Test
    @DisplayName("播报名长度不超过 5 字：焦点移动不该变成念稿")
    fun labelsAreShort() {
        val bad = FnIcon.entries.filter { it.label.length > maxLabelLength }
        assertTrue(
            bad.isEmpty(),
            "超长播报名（>${maxLabelLength}）：${bad.map { "${it.name}=${it.label}(${it.label.length})" }}",
        )
    }

    @Test
    @DisplayName("底栏四 tab 全部有播报名且唯一：这是整屏唯一导航入口")
    fun capsuleTabsAreLabelled() {
        val tabs = FnCapsuleIcon.entries
        assertEquals(4, tabs.size, "底栏 tab 数量变化时需同步判读项 U-17")
        assertTrue(tabs.all { it.label.isNotBlank() }, "底栏存在无名字 tab")
        assertEquals(tabs.size, tabs.map { it.label }.distinct().size, "底栏 tab 播报名重名")
        assertTrue(tabs.all { t -> t.label.none { it.code < 128 } }, "底栏 tab 播报名含 ASCII")
    }
}
