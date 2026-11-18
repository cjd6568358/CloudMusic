package com.example.cloudmusic.di.scan

/**
 * V0.2 §61.217：外置 lrc 的**候选路径顺序**（纯 JVM，不触网、不 list 目录）。
 *
 * §61.186 只试同目录 `<音频名>.lrc`，NAS 上常见的 `lyrics/` 子目录、
 * `艺人 - 标题.lrc`、大写 `.LRC` 全部漏掉。本类按确定性顺序给出候选相对文件名，
 * 由 [SiblingLyricsLookup] 逐个直击（不 list 目录，避免 §61.146 的 O(n²)）；
 * 最多 [MAX_CANDIDATES] 个，目录级失败记忆仍然生效（无歌词目录不逐文件重试）。
 */
object LyricsPathCandidates {
    /** 三类基名 × 四种形态；目录级记忆保证每个无歌词目录只跑满这一轮。 */
    const val MAX_CANDIDATES = 12

    /**
     * @param fileName 音频文件名（含扩展名）
     * @param title 扫描标签里的标题（可为空）
     * @param artist 扫描标签里的艺人（可为空）
     * @return 相对同一父目录的文件名，按优先级排序、去重、截断
     */
    fun forTrack(fileName: String, title: String?, artist: String?): List<String> {
        val base = fileName.substringBeforeLast('.', fileName).trim()
        if (base.isEmpty()) return emptyList()
        val names = LinkedHashSet<String>()
        addBase(names, base)
        val t = sanitize(title)
        if (t.isNotEmpty() && !t.equals(base, ignoreCase = true)) addBase(names, t)
        val a = sanitize(artist)
        if (a.isNotEmpty() && t.isNotEmpty()) addBase(names, "$a - $t")
        return names.take(MAX_CANDIDATES).toList()
    }

    /** 每种基名给四种形态：同目录/子目录 × 小写/大写扩展名。 */
    private fun addBase(out: MutableSet<String>, base: String) {
        out += "$base.lrc"
        out += "lyrics/$base.lrc"
        out += "$base.LRC"
        out += "lyrics/$base.LRC"
    }

    /** 标题/艺人来自标签，可能带路径分隔符或 Windows 非法字符；只作文件名，不能越界。 */
    private fun sanitize(raw: String?): String = raw.orEmpty().trim()
        .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_")
        .trim('.')
        .take(120)
}
