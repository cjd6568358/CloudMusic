package com.example.cloudmusic.model

import com.example.cloudmusic.common.HashUtil
import com.example.cloudmusic.common.PathNormalizer

/** docs/03-01 §3.4：业务层统一曲库模型。 */

enum class SourceType { LOCAL, WEBDAV, STRM, PLAYLIST, SFTP, FTP, NFS }

/** 恢复后自动置回 OK；灰显但绝不自动移除（PRD §5.3）。 */
enum class Availability { OK, UNREACHABLE, LINK_EXPIRED, UNSUPPORTED_SCHEME }

data class Track(
    val id: TrackId,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val trackNo: Int = 0,
    val year: Int = 0,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    /** 可重建 Link 的最小定位符。 */
    val sourceRef: SourceRef,
    val sourceType: SourceType,
    val coverRef: String? = null,
    /** §61.120（T5-2 前置）：曲库落库的曲目增益（dB，负=衰减）；null = 未分析。 */
    val replayGainDb: Float? = null,
    /** 0~1，标签缺失时靠文件名兜底。 */
    val tagCompleteness: Float = 0f,
    val availability: Availability = Availability.OK,
    /** §61.262：入库时间（Room added_at），默认 0 保证既有构造零回归。 */
    val addedAt: Long = 0L,
    // ---- §61.221 CUE 分轨（cue sheet 展开；整轨/普通曲目恒 0）----
    /** 分轨起点毫秒；0 = 从头。播放链据此定位与截断（V0.2 M5-③，另批接线）。 */
    val cueStartMs: Long = 0L,
    /** 分轨终点毫秒；0 = 无界（同 FILE 末轨 / 独立 FILE，播到自然结束）。 */
    val cueEndMs: Long = 0L,
) {
    val isCueTrack: Boolean get() = cueStartMs > 0 || cueEndMs > 0
}

/**
 * §61.262：首页「最近添加」= 按 addedAt 倒序、按 id 去重、取前 N。
 * 旧实现 `tracks.take(20)` 只是曲库前 20 条（CUE 同曲分轨会看起来重复），语义错误。
 */
fun List<Track>.recentlyAdded(limit: Int = 20): List<Track> =
    groupBy { it.id }.values
        .map { group -> group.maxBy { it.addedAt } }
        .sortedByDescending { it.addedAt }
        .take(limit.coerceAtLeast(0))

data class SourceRef(
    /** → Storage.id */
    val storageId: String,
    /** 驱动内规范化相对路径。 */
    val path: String,
    /** strm / 播放列表 URL 条目的原始 URL。含签名，禁止写入日志（07 §8）。 */
    val url: String? = null,
)

data class TrackId(val value: String) {
    companion object {
        /**
         * 稳定性规则（03-01 §7）：来源命名空间 + 规范化相对路径；
         * 不含 host / 端口 / 账号，地址变更不丢收藏与播放记录。
         *
         * 实现期修正 C-01：规范化调用下沉到 :core-common 的 PathNormalizer，
         * 避免 :core-model 反向依赖 :datasource-api（详见 PathNormalizer KDoc）。
         */
        fun of(namespace: String, relativePath: String): TrackId =
            TrackId(HashUtil.sha256("$namespace|${PathNormalizer.normalize(relativePath)}").take(32))
    }
}
