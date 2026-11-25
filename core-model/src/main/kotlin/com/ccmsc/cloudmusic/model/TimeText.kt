package com.ccmsc.cloudmusic.model

import java.util.Locale

/**
 * §61.297：时间文本（播放位置 / 时长）的**单一真相源**。
 *
 * 此前全仓两份各写一遍 `%02d:%02d`：
 * - 列表侧 `TrackInfo.durationLabel`：未知（<=0）返回 [TrackInfo.UNKNOWN]；
 * - 播放页侧 `LegacyPlayerPage.formatMs`：未知返回 `00:00`，且**没带 Locale**。
 *
 * 两份实现的第一处真实差异是 **Locale 安全**：`String.format` 不带 Locale 时走
 * `Locale.getDefault()`，JDK17 实测 `ar_EG` / `fa_IR` 下 `%02d` 会输出本地数字
 * （时长变成 `٦٥:٣٠`，用户读不出、TalkBack 也按本地数字逐位念）。故数字格式化
 * 一律 `Locale.ROOT`——`%02x` 实测不受 locale 影响，哈希/排序键无需处理。
 *
 * 两处对「未知」的表述**故意不同**，不是遗漏：
 * - 列表里时长是**元数据字段**，缺失就该显示占位 [TrackInfo.UNKNOWN]（`—`），
 *   `00:00` 是冒充真值（`§61.218 F3` 已钉）；
 * - 播放页两端是**时间轴端点**，位置 0 本就该是 `00:00`；总时长未知时也显示
 *   `00:00`，与左侧等宽、不跳动（进度条另有 `safeDuration` 兜底，不会除零）。
 *
 * 分钟位**不折叠为小时**（`65:30` 而非 `1:05:30`）：与竞品播放页一致，且改动
 * 会影响时间行宽度（`§61.174` 基准绝对坐标）。`%02d` 允许 3 位，故 `100:00`
 * 也能正常显示。
 */
object TimeText {

    /** 播放位置 / 时间轴端点：`mm:ss`；负数按 0 处理（`00:00`）。 */
    fun clock(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.ROOT, "%02d:%02d", total / 60L, total % 60L)
    }

    /** 时长字段：`<= 0` 视为未知 → [TrackInfo.UNKNOWN]；否则同 [clock]。 */
    fun duration(ms: Long): String = if (ms <= 0L) TrackInfo.UNKNOWN else clock(ms)
}
