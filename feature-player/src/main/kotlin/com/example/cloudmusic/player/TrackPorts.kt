package com.example.cloudmusic.player

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId

/** 队列只存 `track_id` + `source_ref`，元数据从曲库按 id 取（§8.1）。 */
interface TrackLookup {
    suspend fun get(id: TrackId): Track?
}

/**
 * `Track.sourceRef` → `Link` 的重建端口（03-01 §5 / 03-06 §9 `resolve`）。
 * 生产由 `:datasource-*` 驱动 + 组合层 `proxy.publish` 提供（PROXIED 态给回环 URL）；
 * 纯 JVM 阶段注入 fake，不触网、不依赖 Android。
 */
fun interface TrackSourceResolver {
    suspend fun resolve(track: Track): Link
}

class UnavailableTrackSourceResolver : TrackSourceResolver {
    override suspend fun resolve(track: Track): Link =
        throw CloudMusicError.DsUnreachable("未配置 TrackSourceResolver（生产由驱动注入）")
}

/** 内存查找，供单测与 UI 预览使用。 */
class InMemoryTrackLookup(tracks: Collection<Track>) : TrackLookup {
    private val map = tracks.associateBy { it.id }
    override suspend fun get(id: TrackId): Track? = map[id]
}
