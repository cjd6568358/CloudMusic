package com.example.cloudmusic.scanner

import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.SourceRef
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Storage

/** docs/03-04 §4 / §5 · 扫描请求、进度与汇总。 */
enum class ScanPhase { FULL, INCREMENTAL, METADATA }

enum class CandidateKind { AUDIO, STRM, PLAYLIST }

data class ScanRequest(
    /** 空集合 = 扫描全部活跃 Storage */
    val storageIds: Set<String> = emptySet(),
    /**
     * C-14（`12 §31`）：**进度标签**，用于 `ScanProgress` / UI 展示，
     * 不控制扫描分支；实际语义由 [refresh] / [storageIds] / [onlyUnavailable] 决定。
     * 保留该字段是为了不改既有调用方与单测（03-04 §4）。
     */
    val phase: ScanPhase = ScanPhase.FULL,
    val refresh: Boolean = false,
    /** 阶段 B 开关；阶段 A 从不等待网络元数据 */
    val includeMetadata: Boolean = true,
    val onlyWifiMetadata: Boolean = true,
    val wifiAvailable: Boolean = true,
    val storageConcurrency: Int = 4,
    val metadataConcurrency: Int = 2,
    /** 每批 upsert 条数（03-04 §10：禁止逐条写库） */
    val batchSize: Int = 500,
    /**
     * C-14：03-04 §4「网络恢复 / 手动重试失败项」的**定向增量**。
     * true 时只处理当前 `Availability != OK` 的条目；由 [TrackSink.unavailablePaths] 提供集合。
     */
    val onlyUnavailable: Boolean = false,
)

data class ScanError(val path: String, val code: String, val detail: String)

data class ScanProgress(
    val storageId: String,
    val phase: ScanPhase,
    val scanned: Int,
    val total: Int,
    val currentPath: String,
    val errors: List<ScanError> = emptyList(),
    val cancelled: Boolean = false,
    val stageAFinished: Boolean = false,
)

data class ScanReport(
    val scanned: Int,
    val upserted: Int,
    val skipped: Int,
    val unavailable: Int,
    val errors: List<ScanError>,
    val cancelled: Boolean,
    val stageAFinished: Boolean,
)

/** 播放列表 #EXTINF / pls Title 提供的元数据提示（03-03 §4）。 */
data class TrackHint(
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
)

/** 扫描候选：一个可入库条目。 */
data class Candidate(
    val storage: Storage,
    val obj: Obj,
    val kind: CandidateKind,
    val parentDirName: String,
    val hint: TrackHint? = null,
    /** strm / 播放列表 URL 条目的原始 URL（含签名，禁止入日志） */
    val directUrl: String? = null,
) {
    fun sourceRef(): SourceRef = SourceRef(
        storageId = storage.id,
        path = obj.path,
        url = directUrl,
    )

    fun sourceType(): SourceType = when (kind) {
        CandidateKind.STRM -> SourceType.STRM
        CandidateKind.PLAYLIST -> SourceType.PLAYLIST
        CandidateKind.AUDIO -> when (storage.driverType.lowercase()) {
            "webdav" -> SourceType.WEBDAV
            "sftp" -> SourceType.SFTP
            "ftp" -> SourceType.FTP
            "nfs" -> SourceType.NFS
            else -> SourceType.LOCAL
        }
    }
}
