package com.example.cloudmusic.storage

import com.example.cloudmusic.model.Track

/**
 * §61.208（A4，§61.151 挂账）：同 URL 双源折叠（纯函数）。
 *
 * 92 条 STRM 曲目同时挂在两个委托源下（本地 strm 目录 + WebDAV strm），TrackId 按
 * `(storageId, path)` 口径生成 ⇒ 两行不同 Track。本函数是 [AppSettings.Scan.mergeDuplicates]
 * 开关（§61.151 起持久化但无消费方的死旋钮）的生产消费方：**UI 展示层折叠**，
 * 不改库不删行——曲库行是用户资产（§15 #3 禁删纪律；收藏/历史都挂在 trackId 上），
 * 扫描层折叠会让落选行在 refresh 与折叠间反复翻转，展示层折叠可逆、零副作用。
 *
 * 胜者口径：`sourceRef.url` 相同的行中取 `storageId` 字典序最小（并列取 path）——
 * **确定性**（与输入顺序、当前排序无关，重排不抖动）；`url` 为 null/blank 的条目
 * （本地文件、无直链）恒保留。
 */
object DuplicateFolder {

    fun fold(tracks: List<Track>, enabled: Boolean): List<Track> {
        if (!enabled) return tracks
        val winnerOf = HashMap<String, Track>()
        for (track in tracks) {
            val url = track.sourceRef.url?.takeIf { it.isNotBlank() } ?: continue
            val current = winnerOf[url]
            if (current == null || beats(track, current)) winnerOf[url] = track
        }
        if (winnerOf.isEmpty()) return tracks
        val winners = winnerOf.values.mapTo(HashSet()) { it.id.value }
        return tracks.filter { it.sourceRef.url.isNullOrBlank() || it.id.value in winners }
    }

    /** 候选是否胜过现任（storageId 字典序最小，并列取 path）。 */
    private fun beats(candidate: Track, incumbent: Track): Boolean {
        val byStorage = compareValues(candidate.sourceRef.storageId, incumbent.sourceRef.storageId)
        if (byStorage != 0) return byStorage < 0
        return compareValues(candidate.sourceRef.path, incumbent.sourceRef.path) < 0
    }
}
