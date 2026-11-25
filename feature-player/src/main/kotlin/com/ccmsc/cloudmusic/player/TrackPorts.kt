package com.ccmsc.cloudmusic.player

import com.ccmsc.cloudmusic.common.CloudMusicError
import com.ccmsc.cloudmusic.model.Link
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.model.TrackId

/** 队列只存 `track_id` + `source_ref`，元数据从曲库按 id 取（§8.1）。 */
interface TrackLookup {
    suspend fun get(id: TrackId): Track?

    /**
     * §61.319（R2）：批量预取——生产 Room 实现覆写为单条 `IN` 查询，
     * 消除「每次 prepareAt 扫整队列做 N 次 Room 点查」的风暴。
     * 默认逐项委托 [get]：测试 fake / 内存实现零改动兼容（§61.319 guard 只钉生产覆写）。
     * @return 与 [ids] 等长、同序；缺项 null。
     */
    suspend fun getMany(ids: List<TrackId>): List<Track?> = ids.map { get(it) }
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
    override suspend fun getMany(ids: List<TrackId>): List<Track?> = ids.map { map[it] }
}

/**
 * §61.181（B 案）：引擎真值时长**回填曲库**的落库端口（[PlayerController] 在
 * 引擎解出真实时长且曲库缺值（durationMs<=0，STRM / 无标签）时调用一次）。
 * 生产由 `:core-di` 绑定 `TrackRepository.updateDuration`；纯 JVM 测试注入记录型 fake。
 */
fun interface TrackDurationWriter {
    suspend fun write(id: TrackId, durationMs: Long)
}

/**
 * §61.182（三层守护网 ② 配套）：播放游标**写侧**端口 —— 冷启动恢复（03-06 §8.2 /
 * PRD §6.1）的写半边。`QueueRepository.saveCursor` 此前全工程只有测试调用（T11 手调
 * 造数据）、生产链零写方 ⇒ playback_state 恒缺失、恢复链死路（真机一手
 * `cold-start restore: playback_state 缺失，跳过恢复`，`12 §61.181` 判据③现场）。
 * 写入节奏 = 切歌 / 暂停 + PLAYING 期每 5s（`QueueRepository.saveCursor` 接口注释
 * 既有口径）。生产由 `:core-di` 绑定 `QueueRepository.saveCursor`；null = 不写（单测）。
 */
fun interface CursorWriter {
    suspend fun write(trackId: String, positionMs: Long)
}

/**
 * §61.253：曲目可用性标记端口——源级失败（驱动缺失/孤儿源）时如实置 UNREACHABLE，
 * 曲库不再谎报 OK（PRD §4 离线优先：不可达条目标记为暂不可用）。
 */
fun interface TrackAvailabilityWriter {
    suspend fun mark(id: TrackId, available: Boolean)
}
