package com.example.cloudmusic.strm

/** docs/03-03 §2.2 · `.strm` 消费型解析（与 OpenList 的「生成」方向相反）。 */
sealed interface StrmParseResult {
    data class Url(val url: String) : StrmParseResult
    data class Unsupported(val scheme: String) : StrmParseResult
    data object Empty : StrmParseResult
}

/**
 * 取第一行非空、非 `#` 注释，trim 后作为音源 URL；仅接受 http/https。
 * query / 签名参数原样保留，不解析、不执行；第二行起忽略（V0.1 无备用源）。
 *
 * 实现说明：先剥掉整个内容开头的 UTF-8 BOM 再逐行判断，否则 BOM 会让首行注释
 * 躲过 `startsWith("#")` 判定而被误当成 URL（03-03 B02 的正确语义）。
 */
fun parseStrm(content: String): StrmParseResult {
    val clean = content.removePrefix("\uFEFF")
    val line = clean.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?: return StrmParseResult.Empty
    val url = line.removePrefix("\uFEFF").trim()
    val scheme = url.substringBefore(':', "").lowercase()
    return when (scheme) {
        "http", "https" -> StrmParseResult.Url(url)
        else -> StrmParseResult.Unsupported(scheme)
    }
}

/** 「周杰伦 - 晴天.strm」→ (艺人, 标题)，供 03-04 元数据兜底。 */
fun strmTitleHint(fileName: String): Pair<String, String>? {
    val base = fileName.substringBeforeLast('.', fileName)
    val parts = base.split(" - ", limit = 2)
    return if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
}

/** 带签名的 URL 禁止入日志（07 §8）：只输出 scheme 与是否带 query。 */
fun safeStrmDescription(url: String): String =
    "[strm] scheme=${url.substringBefore(':', "")} hasQuery=${'?' in url}"
