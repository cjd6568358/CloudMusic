package com.ccmsc.cloudmusic.scanner

import com.ccmsc.cloudmusic.model.TrackId

/**
 * §61.254：扫描期歌单导入端口——m3u/m3u8/pls 展开条目注册为**歌单页命名实体**
 * （用户拍板：任意源内 m3u 展开轨在歌单页可见可播）。id = 稳定派生（重扫幂等）；
 * 产物 = 仓库侧 upsertImported（歌单行 + 全量曲目替换，顺序 = m3u 条目序）。
 */
fun interface PlaylistImportSink {
    suspend fun import(id: String, name: String, trackIds: List<TrackId>)
}

/** §61.254：一次 m3u 展开的导入引用（idPath 供 accepted 交集过滤；TrackId 组合层派生）。 */
data class PlaylistImport(val id: String, val name: String, val idPaths: List<String>)
