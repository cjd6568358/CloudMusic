package com.ccmsc.cloudmusic.model

import com.ccmsc.cloudmusic.common.HashUtil
import com.ccmsc.cloudmusic.common.PathNormalizer

/**
 * §61.318（R1）· 远端取流的**物理文件锚**缓存 key 单一真相源。
 *
 * 背景（`analysis/review-2026-11-23-architecture-performance-docconsistency-audit.md` §2.2 P0-1）：
 * 旧 key `b2track:<trackId>` 对 CUE 分轨是**每轨一个条目**——TrackId 用
 * `<音频path>#<cue名>#<轨号>` 做锚（§61.221），同一物理 APE 的 N 个分轨
 * 各自成行、key 不同 ⇒ 切分轨必然 cache miss → 整文件重下 + warmer 全量重预热。
 * §61.317 的 15s 缓冲只治单轨内 rebuffer，治不了分轨间 miss。
 *
 * 修法：key 从「业务曲目 id」换轨为「**物理媒体文件**」——
 * - CUE 分轨：`sourceRef.path` 已是父音频路径（ScanModels.sourceRef() 取 obj.path，
 *   CUE 候选 obj = 分轨所属音频，`TrackFactory` 只覆写 id、不覆写 sourceRef）；
 * - 非 CUE：`sourceRef.path` 即自身路径；
 * - 同一 storage 下同一物理路径 ⇒ 同一 key，分轨切换秒级命中。
 *
 * 取 `sha256(storageId|normalizedPath)` 前 32 位而非明文路径：与 `TrackId.of` 同款
 * 稳定性口径（不含 host / 凭据 / 端口），缓存文件名不含用户目录与签名 URL。
 * 路径规范化复用 `PathNormalizer`（03-01 §8），非法越界路径按 `TrackId.of` 既有
 * 语义抛 `DsNotSupport`——扫描阶段已挡住，这里不静默吞。
 */
object MediaCacheKey {
    /** 新前缀：物理文件锚（与旧 `b2track:` 不相交，升级后旧条目由 LRU 自然淘汰）。 */
    const val FILE_PREFIX = "b2file:"
    /** 旧前缀：业务曲目 id 锚，仅用于兼容未在 EngineSource 填物理锚的调用方。 */
    const val TRACK_PREFIX = "b2track:"

    /** 从 Track 的物理来源派生 key；CUE 分轨同父音频 → 同 key。 */
    fun of(track: Track): String = ofSourceRef(track.sourceRef)

    fun ofSourceRef(ref: SourceRef): String = ofPath(ref.storageId, ref.path)

    /** 纯函数：storageId + 规范化物理路径 → 稳定 key。 */
    fun ofPath(storageId: String, physicalPath: String): String =
        FILE_PREFIX + HashUtil.sha256("$storageId|${PathNormalizer.normalize(physicalPath)}").take(32)

    /**
     * 兼容入口：调用方未携带物理锚时退回旧 key。
     * 生产路径必须经 [of] / [ofSourceRef] / [ofPath]；本函数只服务于
     * 测试夹具与历史直连调用（如 `playUrl` 注入的无曲目 EngineSource）。
     */
    fun ofFallback(trackId: String): String = TRACK_PREFIX + trackId

    /** 判定 key 是否为新物理锚形态（guard / 日志用）。 */
    fun isFileKey(key: String): Boolean = key.startsWith(FILE_PREFIX)
}
