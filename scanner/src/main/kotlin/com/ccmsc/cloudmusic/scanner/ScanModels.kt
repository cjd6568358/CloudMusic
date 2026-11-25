package com.ccmsc.cloudmusic.scanner

import com.ccmsc.cloudmusic.model.Obj
import com.ccmsc.cloudmusic.model.SourceRef
import com.ccmsc.cloudmusic.model.SourceType
import com.ccmsc.cloudmusic.model.Storage

/** docs/03-04 §4 / §5 · 扫描请求、进度与汇总。 */
enum class ScanPhase { FULL, INCREMENTAL, METADATA }

/** §61.221：CUE 分轨候选——obj 恒为分轨所属音频文件，cue 侧信息在 [Candidate] 专用字段。 */
enum class CandidateKind { AUDIO, STRM, PLAYLIST, CUE }

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
    /**
     * 设置页 `network.metadataWifiOnly` 的扫描侧语义：true 时仅在 [wifiAvailable] 为真
     * 才进 metadata 阶段。
     *
     * §61.323（B-2）接线：此前设置项从未传进请求，恒为默认 true；现由组合层在
     * `runScan()` 入口用 `AppSettings.network.metadataWifiOnly` 覆写。
     */
    val onlyWifiMetadata: Boolean = true,
    /**
     * 「当前在 WiFi」的事实位。
     *
     * §61.323（B-2）：此前**全仓零写方**、恒 true ⇒ 上一条判定永远放行，设置项形同虚设
     * （架构审计 §4 序 11）。现由组合层用 `AndroidNetworkPlaybackGate.currentKind()`
     * 覆写；纯 JVM 侧保持默认 true，不引入 Android 依赖。
     */
    val wifiAvailable: Boolean = true,
    val storageConcurrency: Int = 4,
    /** §61.161：2→4——metadata 阶段逐文件标签读取是冷扫瓶颈（§61.147/160 一手），
     *  4 并发在 mid-range 设备上实测安全（IO-bound，无 CPU 竞争恶化）。
     *  §61.204（A1-②）：4→8——远程 tag 已被 §61.203 跳过，剩余通道以本地 JAudio
     *  文件读（毫秒级）为主，8 并发把标签阶段压半；IO-bound，无 CPU 竞争恶化。
     *  §61.323（B-2）：语义 **每源 → 全源共享**——run() 级单实例信号量，4 源并存
     *  不再叠加成 32 并发抢核（架构审计 P1-7）。 */
    val metadataConcurrency: Int = 8,
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
    // ---- §61.221 CUE 分轨专用（kind=CUE 时非默认）----
    /**
     * TrackId / 快照键用的锚点路径：`<音频path>#<cue文件名去扩展名>#<轨号>`。
     * 同一音频的多分轨与既有 AUDIO/STRM 快照键语义（T11）全部依赖此键唯一性；
     * 既有候选恒为 null，`effectiveIdPath()` 与 obj.path 相同、零行为变化。
     */
    val idPathOverride: String? = null,
    /** cue 文件自身（快照 diff 用 stat 源）——cue 变化即使音频 stat 未变也触发重展开。 */
    val statObj: Obj? = null,
    /** cue 元数据（阶段 A 骨架即带全，阶段 B 据此跳过 tag 读取）。 */
    val cueRaw: RawTags? = null,
    /** 分轨起点毫秒；0 = 从头。 */
    val cueStartMs: Long = 0L,
    /** 分轨终点毫秒；0 = 无界（同 FILE 末轨 / 独立 FILE，播到自然结束）。 */
    val cueEndMs: Long = 0L,
) {
    /** 快照 / TrackId 的键：分轨用锚点，其余与 obj.path 相同。 */
    fun effectiveIdPath(): String = idPathOverride ?: obj.path

    /** 快照 diff 的 stat 源：分轨取 cue 文件 stat，其余即 obj 自身。 */
    fun statSize(): Long = statObj?.size ?: obj.size

    fun statModified(): Long = statObj?.modified ?: obj.modified

    fun sourceRef(): SourceRef = SourceRef(
        storageId = storage.id,
        path = obj.path,
        url = directUrl,
    )

    fun sourceType(): SourceType = when (kind) {
        CandidateKind.STRM -> SourceType.STRM
        CandidateKind.PLAYLIST -> SourceType.PLAYLIST
        // §61.221：分轨的来源类型跟随底层存储（与整轨一致），sourceRef.path 已是真实音频路径
        CandidateKind.AUDIO, CandidateKind.CUE -> when (storage.driverType.lowercase()) {
            "webdav" -> SourceType.WEBDAV
            "sftp" -> SourceType.SFTP
            "ftp" -> SourceType.FTP
            "nfs" -> SourceType.NFS
            else -> SourceType.LOCAL
        }
    }
}
