package com.example.cloudmusic.scanner

/** docs/03-04 §6.5 · 标签缺失时的文件名 / 目录名兜底。 */
object NameParser {

    data class Fallback(
        val title: String,
        val artist: String,
        val album: String,
        val completeness: Float,
    )

    fun parse(fileName: String, parentDirName: String, hint: TrackHint? = null): Fallback {
        val base = fileName.substringBeforeLast('.', fileName).trim()
        val parts = base.split(" - ", limit = 2)
        val hasSplit = parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()

        val title = hint?.title?.takeIf { it.isNotBlank() }
            ?: if (hasSplit) parts[1].trim() else base.ifBlank { fileName }
        val artist = hint?.artist?.takeIf { it.isNotBlank() }
            ?: if (hasSplit) parts[0].trim() else artistFromParent(parentDirName)
        val album = albumFromParent(parentDirName)

        // 仅文件名级信息 → 0.2（03-04 §6.5）
        return Fallback(title, artist, album, 0.2f)
    }

    private fun artistFromParent(parent: String): String {
        val parts = parent.split(" - ", limit = 2)
        return if (parts.size == 2) parts[0].trim().ifBlank { "未知" } else "未知"
    }

    private fun albumFromParent(parent: String): String {
        val parts = parent.split(" - ", limit = 2)
        return (if (parts.size == 2) parts[1] else parent).trim().ifBlank { "未知" }
    }
}
