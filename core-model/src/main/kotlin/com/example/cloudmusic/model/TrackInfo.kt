package com.example.cloudmusic.model

import java.util.Locale

/**
 * V0.2 §61.218：歌曲信息面板的纯 JVM 展示口径。
 *
 * 只从 [Track] 已有的曲库字段派生，**不新增 Room 表 / 不读网络**：
 * - 格式：扩展名经 F1 真相源 [AudioFormats] 校验，未收录的后缀如实标「未知」；
 * - 采样率 / 位深：有真值才显示，0（未知）不伪造；
 * - 来源：[SourceType] 的人类可读名；
 * - 路径：只用 `sourceRef.path`（驱动内相对路径），**禁止**展示 `sourceRef.url`
 *   （STRM 直链可能带签名，07 §8 明令不得入日志 / UI 明文）。
 */
object TrackInfo {

    fun formatLabel(track: Track): String {
        val ext = track.sourceRef.path.substringAfterLast('.', "").lowercase()
        val codec = if (ext in AudioFormats.AUDIO_EXT || ext in AudioFormats.EXTRA_AUDIO_EXT) {
            ext.uppercase(Locale.ROOT)
        } else {
            "未知"
        }
        val rate = when {
            track.sampleRate <= 0 -> null
            track.sampleRate % 1000 == 0 -> "${track.sampleRate / 1000}kHz"
            else -> String.format(Locale.ROOT, "%.1fkHz", track.sampleRate / 1000.0)
        }
        val bits = if (track.bitDepth > 0) "${track.bitDepth}bit" else null
        return listOfNotNull(codec, rate, bits).joinToString(" · ")
    }

    fun sourceLabel(track: Track): String = when (track.sourceType) {
        SourceType.LOCAL -> "本地文件"
        SourceType.WEBDAV -> "WebDAV"
        SourceType.STRM -> "STRM 直链"
        SourceType.PLAYLIST -> "播放列表"
        SourceType.SFTP -> "SFTP"
        SourceType.FTP -> "FTP（预留）"
        SourceType.NFS -> "NFS（预留）"
    }

    /** 时长用 `mm:ss`，未知（<=0）显示占位而不是 00:00 冒充真值。 */
    fun durationLabel(track: Track): String {
        if (track.durationMs <= 0L) return "未知"
        val total = track.durationMs / 1000L
        return String.format(Locale.ROOT, "%02d:%02d", total / 60L, total % 60L)
    }
}
