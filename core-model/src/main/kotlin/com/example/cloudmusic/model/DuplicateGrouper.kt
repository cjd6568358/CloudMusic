package com.example.cloudmusic.model

/**
 * §61.247（V0.3 M5 / A1 分层去重）：Tier2 疑似重复分组——**纯 JVM 零 IO**。
 *
 * 口径（如实登记）：
 * - Tier1（内容哈希精确确认趟）挂账另批——需 TrackContentHasher 内容读端口接入驱动链，
 *   本对象预留 [groupByHash] 消费位；当前生产面只有 Tier2。
 * - Tier2 键 = (标题归一化, 艺人归一化)，簇内时长差 ≤ [DURATION_TOLERANCE_MS]（±1s，
 *   单链式聚合：排序后相邻差都 ≤1s 则连成一簇——转码/重封装的时长漂移覆盖）。
 * - 输出为**疑似**重复（live 版/同名单曲同名同长误报风险存在），处置完全由用户手动
 *   （PRD 原则 6 读优先：不自动删除/不自动合并）。
 * - 归一化 = trim + 小写 + 空白折叠；艺人空串参与分组（「未知艺人」同曲互为疑似）。
 */
object DuplicateGrouper {

    /** 时长容差：转码/重封装造成的毫秒级漂移覆盖（±1s）。 */
    const val DURATION_TOLERANCE_MS: Long = 1_000L

    /** 归一化：trim + 小写 + 空白折叠（中英混排友好）。
     *  `§61.296`：空白折叠走 `SearchText`——全角空格 U+3000 / 不换行空格同样是分隔符，
     *  否则「Album　Name」与「Album Name」会算成两组，疑似重复检测漏报。
     *  不做字形折叠（NFKC）：保守，避免把不同字形的标题并成一组。 */
    fun normalize(s: String): String = SearchText.normalize(s).lowercase()

    /** Tier2 疑似重复分组。确定性输出（组按标题、组内按路径排序）。 */
    fun suspectedDuplicates(tracks: List<Track>): List<DuplicateGroup> {
        val byMeta = tracks
            .filter { it.title.isNotBlank() }
            .groupBy { Pair(normalize(it.title), normalize(it.artist)) }
        val out = mutableListOf<DuplicateGroup>()
        for ((_, members) in byMeta) {
            if (members.size < 2) continue
            val sorted = members.sortedBy { it.durationMs }
            var cluster = mutableListOf<Track>()
            val clusters = mutableListOf<List<Track>>()
            for (t in sorted) {
                if (cluster.isEmpty() || t.durationMs - cluster.last().durationMs <= DURATION_TOLERANCE_MS) {
                    cluster += t
                } else {
                    if (cluster.size >= 2) clusters += cluster
                    cluster = mutableListOf(t)
                }
            }
            if (cluster.size >= 2) clusters += cluster
            for (c in clusters) {
                out += DuplicateGroup(
                    title = c.first().title,
                    artist = c.first().artist,
                    items = c.sortedBy { it.sourceRef.path },
                )
            }
        }
        return out.sortedBy { it.title.lowercase() }
    }

    /**
     * Tier1 精确去重（消费位）：[hashOf] 给出内容指纹（生产面为 TrackContentHasher，
     * 挂账）——同指纹分组；hash 为 null（未算出/不可读）的曲目不参与。现在即可被
     * JVM 测试与未来哈希趟直接复用。
     */
    fun groupByHash(tracks: List<Track>, hashOf: (Track) -> String?): List<DuplicateGroup> {
        val byHash = tracks
            .mapNotNull { t -> hashOf(t)?.let { it to t } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= 2 }
        return byHash.values.map { members ->
            DuplicateGroup(
                title = members.first().title,
                artist = members.first().artist,
                items = members.sortedBy { it.sourceRef.path },
            )
        }.sortedBy { it.title.lowercase() }
    }
}

/** §61.247：一组疑似重复——同键同簇的曲目（≥2 条；按路径排序保证确定性）。 */
data class DuplicateGroup(
    val title: String,
    val artist: String,
    val items: List<Track>,
)

/**
 * §61.247：一次检测的报告（[groups] 按标题排序；空库/无重复 = 空表）。
 * §61.248 扩展（Tier1 确认趟，默认值向后兼容）：[confirmed] = 内容哈希一致的精确重复；
 * [candidates] = 疑似组唯一候选文件数；[hashedCount]/[hashFailed] = 已算/不可读。
 */
data class DuplicateReport(
    val scanned: Int,
    val groups: List<DuplicateGroup>,
    val confirmed: List<DuplicateGroup> = emptyList(),
    val candidates: Int = 0,
    /** §61.248：本轮**成功**算出的候选数（≠累计——累计看 memoSize）。 */
    val hashedCount: Int = 0,
    val hashFailed: Int = 0,
    /** §61.250：命中跨次记忆直接复用的候选数（零 IO）。 */
    val memoHits: Int = 0,
    /** §61.250：跨次指纹记忆累计条数（重跑推进进度，UI 展示用）。 */
    val memoSize: Int = 0,
    /** §61.256：Tier3 声纹疑似同源组（转码/异格式拷贝；阈值保守，处置用户手动）。 */
    val fingerprintConfirmed: List<DuplicateGroup> = emptyList(),
    /** §61.256：本轮成功声纹的候选数。 */
    val fingerprintedCount: Int = 0,
    /** §61.256：不可解码候选数（远端取流失败/编解码器不支持，如实计数）。 */
    val fingerprintFailed: Int = 0,
)
