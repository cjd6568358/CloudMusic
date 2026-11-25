package com.ccmsc.cloudmusic.storage

/**
 * §61.329（A-6）：CUE 分轨的封面继承——**纯逻辑，零 IO、零 Android 依赖**。
 *
 * 问题（评审 §六-8 一手）：分轨行走的是 `cueRaw` 元数据，阶段 B **跳过 tag 读取**
 * （§61.221：整轨 tag 是专辑级信息，读了会覆盖轨级标题/艺人），于是 `embeddedCover`
 * 恒为 null；同目录封面虽然由整轨候选解析，但落的是「候选 obj.path」这一个缓存键，
 * 分轨与整轨在同一块里并行处理，谁先谁后不定 ⇒ 分轨 `cover_ref` 大多为 null，
 * 播放页只能回退黑胶占位，背景取色链整条失效（`LegacyPlayerPage` 只读 `Track.coverRef`）。
 *
 * 判据：分轨的 `id` 是锚点路径 `<音频>#<cue>#<轨号>` 的 sha256（与整轨行**不同**），
 * 但两行的 `(storageId, sourcePath)` **完全相同**——`sourceRef.path` 一直是真实音频路径
 * （§61.221 定案，取流/驱动 `get` 语义不变）。所以「同一 (storage, path) 的**其它**行有
 * 封面」就等价于「父音频有封面」，无需新增列、无需重算哈希、也无需在扫描期赌先后。
 *
 * 边界（每条都有对应测试）：
 * - 只填**空封面**行；有封面的行一律不动（刮削/内嵌结果不会被覆盖）；
 * - 只认同 storage 内的兄弟行，同路径不同源不互相污染；
 * - 空串按缺失处理（历史行可能是 `''` 而不是 NULL）；
 * - 普通曲目天然不动：路径唯一 ⇒ 没有「其它行」⇒ 无 donor。
 */
object CoverInheritance {

    /** 判定所需的最小行投影；`coverRef` 为 null / 空白即「缺封面」。 */
    data class Row(
        val id: String,
        val storageId: String,
        val sourcePath: String,
        val coverRef: String?,
    )

    /**
     * 返回 `id → 应继承的 coverRef`；无 donor 的行不出现在结果里（调用方只写返回项）。
     *
     * 多个 donor 时取**输入序首个**：同路径的 donor 理应指向同一张封面（内容哈希去重后
     * 就是同一个文件），取首个只为让断言稳定，不承担正确性。
     */
    fun plan(rows: List<Row>): Map<String, String> {
        val donors = LinkedHashMap<String, String>()
        for (row in rows) {
            val cover = row.coverRef?.takeIf { it.isNotBlank() } ?: continue
            donors.putIfAbsent(key(row.storageId, row.sourcePath), cover)
        }
        if (donors.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (row in rows) {
            if (!row.coverRef.isNullOrBlank()) continue
            val cover = donors[key(row.storageId, row.sourcePath)] ?: continue
            out[row.id] = cover
        }
        return out
    }

    /** `\u0000` 分隔：storageId / path 都含 `/`，只有 NUL 不可能出现在两者中。 */
    private fun key(storageId: String, sourcePath: String) = "$storageId\u0000$sourcePath"
}
