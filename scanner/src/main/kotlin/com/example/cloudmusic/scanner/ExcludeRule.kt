package com.example.cloudmusic.scanner

/** docs/03-04 §5 / §9 · 白名单与排除规则。 */
enum class FileClass { DIRECTORY, AUDIO, STRM, PLAYLIST, PASS_THROUGH, IGNORED }

data class ScanRules(
    /** 12 §61.45 / F1：统一取自 AudioFormats（14 项），不再只认 8 项。 */
    val audioExt: Set<String> = com.example.cloudmusic.model.AudioFormats.AUDIO_EXT,
    val strmExt: Set<String> = setOf("strm"),
    val playlistExt: Set<String> = setOf("m3u", "m3u8", "pls"),
    /** 透传但不入曲库（对齐 03-03 download_file_types） */
    val textPassThrough: Set<String> = setOf("lrc", "srt", "ass", "vtt"),
    val ignoreDirs: Set<String> = setOf("android", ".thumbnails", ".git"),
    /** < 10s 碎片过滤；时长未知时不过滤（B03） */
    val minDurationMs: Long = 10_000L,
) {
    fun classify(name: String, isDir: Boolean): FileClass {
        if (isDir) return FileClass.DIRECTORY
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in audioExt -> FileClass.AUDIO
            in strmExt -> FileClass.STRM
            in playlistExt -> FileClass.PLAYLIST
            in textPassThrough -> FileClass.PASS_THROUGH
            else -> FileClass.IGNORED
        }
    }

    fun isIgnoredDir(name: String): Boolean =
        name.lowercase() in ignoreDirs || name.startsWith(".")

    fun isNoMedia(items: Collection<String>): Boolean = ".nomedia" in items

    /** 只有「已知且确实太短」才过滤，未知时长放行。 */
    fun tooShort(durationMs: Long?): Boolean =
        durationMs != null && durationMs > 0 && durationMs < minDurationMs
}
