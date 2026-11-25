package com.ccmsc.cloudmusic.ui

import android.content.Context
import android.widget.Toast

/**
 * §61.294（V0.4 操作反馈）：Compose 层此前**没有任何轻量反馈通道**。
 *
 * 现状核对：宿主 `MainActivity` 已有 20 余处 `Toast`（添加来源、移除来源、权限引导、
 * 缓存清理…），而 Compose 里五类动作点完即静默——面板关掉、列表变了，
 * 但没有一句话告诉用户「刚才那下生效了」：
 *
 * - 曲库行 `＋` → 加入歌单（`AddToPlaylistSheet.onPick`）
 * - 加入歌单面板内「新建并加入」（`onCreateAndAdd`）
 * - 歌单页「⋮ → 重命名」「⋮ → 删除歌单」「曲目 ✕ 移除」（均已二次确认）
 *
 * 为什么是 Toast 而不是自建 Snackbar：仓库没有 `Scaffold` 的 `SnackbarHost`
 * （`§61.121` 已登记，当时因此选「确认」而非「撤销」）。新增浮层要连带定位、计时、
 * 手势吞并、与底栏/迷你条避让，与本批「补一句话反馈」不成比例；宿主侧同通道也已存在，
 * 不引入第二套视觉。若将来统一浮层，只替换 [showFeedback] 一处实现即可。
 *
 * 文案收在 [FeedbackText] 纯 Kotlin 里（不持 Context），由 `FeedbackTextTest` 钉住：
 * 名字必须截断（Toast 撑两行）、长度上限、六类互不相同。
 */
object FeedbackText {

    /** 名字过长会把 Toast 撑成两行——统一截断，保证一眼读完。 */
    const val NAME_LIMIT = 12

    /** 歌单名/曲名截断；空名回退成中性词，避免出现「已加入「」」。 */
    fun brief(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "该歌单"
        return if (trimmed.length <= NAME_LIMIT) trimmed else trimmed.take(NAME_LIMIT) + "…"
    }

    fun addedToPlaylist(playlistName: String): String = "已加入「${brief(playlistName)}」"

    fun createdWithTrack(name: String): String = "已新建「${brief(name)}」并加入"

    fun createdPlaylist(name: String): String = "已新建歌单「${brief(name)}」"

    fun renamedPlaylist(name: String): String = "已重命名为「${brief(name)}」"

    fun deletedPlaylist(name: String): String = "已删除歌单「${brief(name)}」"

    fun removedFromPlaylist(title: String): String = "已从歌单移除「${brief(title)}」"
}

/**
 * 单实例 Toast：连点「＋」时**替换**而不是排队，避免「已加入 A」「已加入 B」
 * 依次延迟弹出、与当前操作对不上号。
 */
private var feedbackToast: Toast? = null

private fun Context.show(message: String, duration: Int) {
    feedbackToast?.cancel()
    feedbackToast = Toast.makeText(this, message, duration).also { it.show() }
}

/**
 * 统一反馈出口（短提示）：动作已生效 / 不可用只给原因。
 *
 * **全仓生产代码只允许本文件创建 `Toast`**（`§61.294` / §61.334 守护）。此前四处 `Toast.makeText`
 * 各建各的，连点会排队；收口后共用同一实例，后一条直接顶掉前一条。
 */
fun Context.showFeedback(message: String) = show(message, Toast.LENGTH_SHORT)

/** 一次性引导（较长文案，如「点唱片封面可切换歌词」）：同一实例，只是显示更久。 */
fun Context.showFeedbackLong(message: String) = show(message, Toast.LENGTH_LONG)
