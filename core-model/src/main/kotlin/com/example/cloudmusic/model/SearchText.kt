package com.example.cloudmusic.model

/**
 * `§61.296` 搜索输入归一化——**单一真相源**。
 *
 * 为什么需要它：中文输入法在中文态按空格键，常插入**全角空格 U+3000**；
 * 复制粘贴还会带进不换行空格 U+00A0 / 窄空格 U+2009 等。而 Java/Kotlin 正则的
 * `\s` **只认 ASCII 空白**——实测 `"周杰伦\u3000稻香".split("\\s+")` 得到 **1 段**，
 * 于是两个词被粘成一个 token，`LIKE '%周杰伦　稻香%'` 与 FTS MATCH 双双落空，
 * 用户看到的是「明明有这首歌，搜不到」。
 *
 * 也不能指望 `trim()` 兜底：Java 的 `String.trim()` 只剥 `<= U+0020`（实测
 * `"\u3000\u3000".trim().length == 2`），Kotlin 的 `trim()` 走 `Char.isWhitespace`
 * 才认全角空格——两套语义并存，不能作为契约。这里显式判定，不依赖实现细节。
 *
 * 归一化只做**分隔符折叠**，不碰任何可见字符（不做 NFKC / 大小写折叠）：
 * 大小写由下游 `contains(ignoreCase)` / FTS 负责，避免把用户输入改成别的字。
 */
object SearchText {

    /** 任意 Unicode 空白或空格分隔符：`isWhitespace` 覆盖 ASCII 与多数 CJK 空白，
     *  `Character.isSpaceChar` 补上不换行空格（U+00A0 / U+202F 的 `isWhitespace` 为 false）。 */
    fun isSeparator(c: Char): Boolean = c.isWhitespace() || Character.isSpaceChar(c)

    /** 分隔符折叠为单个半角空格，并去掉首尾分隔符；空输入返回空串。 */
    fun normalize(raw: String): String {
        val out = StringBuilder(raw.length)
        var pending = false
        raw.forEach { c ->
            if (isSeparator(c)) {
                if (out.isNotEmpty()) pending = true
            } else {
                if (pending) {
                    out.append(' ')
                    pending = false
                }
                out.append(c)
            }
        }
        return out.toString()
    }

    /** 归一化后的词元（已去空串）。`search` 的 AND 语义与 FTS MATCH 共用同一份切分。 */
    fun tokens(raw: String): List<String> {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return emptyList()
        return normalized.split(' ').filter { it.isNotEmpty() }
    }
}
