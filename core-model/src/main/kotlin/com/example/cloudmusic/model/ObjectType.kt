package com.example.cloudmusic.model

/**
 * 对齐 reference/openlist/zig-src/src/model.zig:13-20 的编号；
 * 前端按 type 分支选图标，编号不可改（docs/03-01 §3.1）。
 */
enum class ObjectType(val value: Int) {
    UNKNOWN(0), FOLDER(1), VIDEO(2), AUDIO(3), TEXT(4), IMAGE(5);

    companion object {
        // 集合加 _EXT 后缀，避免与同名枚举常量冲突。
        // 判定顺序 AUDIO → VIDEO → IMAGE → TEXT（对齐 OpenList-zig utils.GetObjType）。
        // 12 §61.45 / F1：音频格式清单统一取自 AudioFormats，本处不再维护字面量。
        private val AUDIO_EXT: Set<String> = AudioFormats.AUDIO_EXT
        // m3u8 已移出 VIDEO：本项目按文本播放列表处理（03-03 §3.5，10 §2 R14）。
        private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "rmvb", "webm", "flv")
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "avif", "ico")
        private val TEXT_EXT = setOf("lrc", "strm", "m3u", "m3u8", "pls", "txt", "nfo", "srt", "ass", "cue")

        fun of(name: String, isDir: Boolean): ObjectType {
            if (isDir) return FOLDER
            val ext = extOf(name)
            if (ext.isEmpty()) return UNKNOWN
            return when {
                ext in AUDIO_EXT -> AUDIO
                ext in VIDEO_EXT -> VIDEO
                ext in IMAGE_EXT -> IMAGE
                ext in TEXT_EXT -> TEXT
                else -> UNKNOWN
            }
        }

        /** 对齐 Go utils.Ext：取最后一个点之后的部分；".gitignore" → "gitignore"。 */
        fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()
    }
}
