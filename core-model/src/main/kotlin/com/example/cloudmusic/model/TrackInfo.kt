package com.example.cloudmusic.model

import java.util.Locale

/**
 * V0.2 §61.218：歌曲信息面板的纯 JVM 展示口径。
 *
 * 只从 [Track] 已有的曲库字段派生，**不新增 Room 表 / 不读网络**：
 * - 格式：扩展名经 F1 真相源 [AudioFormats] 校验，未收录的后缀不伪造（显示占位 `—`）；
 * - 采样率 / 位深：有真值才显示，0（未知）不伪造；
 * - 来源：[SourceType] 的人类可读名；
 * - 路径：只用 `sourceRef.path`（驱动内相对路径），**禁止**展示 `sourceRef.url`
 *   （STRM 直链可能带签名，07 §8 明令不得入日志 / UI 明文）。
 *
 * **§61.275（V0.4-02 显示契约统一）**：新增 [listSubtitle] / [isKnown]，把「已知字段才
 * 显示」收敛为**单一真相源**——列表副题不再出现空段（`"艺人 · "`）与「未知」刷屏。
 * 缺失值统一用 [UNKNOWN]（`—`）而不是「未知」字样：后者只在筛选 / 空态文案里出现。
 */
object TrackInfo {

    /** 字段缺失的显示占位（**不是**「未知」字样，避免列表噪声）。 */
    const val UNKNOWN = "—"

    /**
     * 标签缺失时的兜底值：扫描器（[com.example.cloudmusic.scanner.NameParser]）在文件名
     * 解析失败时会写入字面量「未知」，ID3 侧还有 `unknown` / `<unknown>` 等写法——
     * 这些都不是真实标签，展示层一律当作缺失。
     */
    private val PLACEHOLDERS = setOf(
        "未知", "未知艺人", "未知专辑", "未知歌手", "unknown", "<unknown>", "n/a", "-", "--",
    )

    /** 字段是真值（非空白、非兜底占位）。 */
    fun isKnown(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        return v.isNotEmpty() && v.lowercase(Locale.ROOT) !in PLACEHOLDERS
    }

    /**
     * 列表副题：`艺人 · 专辑`，**已知字段才显示**（队列页范本的最小化推广）。
     *
     * 缺艺人只显示专辑、缺专辑只显示艺人、两者都缺返回空串（调用方据此不渲染副行）。
     */
    fun listSubtitle(track: Track): String = listOfNotNull(
        track.artist.takeIf { isKnown(it) },
        track.album.takeIf { isKnown(it) },
    ).joinToString(" · ")

    fun formatLabel(track: Track): String {
        val ext = track.sourceRef.path.substringAfterLast('.', "").lowercase()
        val codec = if (ext in AudioFormats.AUDIO_EXT || ext in AudioFormats.EXTRA_AUDIO_EXT) {
            ext.uppercase(Locale.ROOT)
        } else {
            null
        }
        val rate = when {
            track.sampleRate <= 0 -> null
            track.sampleRate % 1000 == 0 -> "${track.sampleRate / 1000}kHz"
            else -> String.format(Locale.ROOT, "%.1fkHz", track.sampleRate / 1000.0)
        }
        val bits = if (track.bitDepth > 0) "${track.bitDepth}bit" else null
        return listOfNotNull(codec, rate, bits).joinToString(" · ").ifBlank { UNKNOWN }
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

    /**
     * 不可用原因（人话，`V0.4-03` 灰显契约）：`OK` 返回空串 = 可用、不显示角标。
     *
     * 枚举名（`UNREACHABLE` 等）永不进 UI；点击不可用曲目时用它做提示文案，
     * 不弹网络错误（PRD §7.5 #1）。
     */
    fun availabilityLabel(track: Track): String = when (track.availability) {
        Availability.OK -> ""
        Availability.UNREACHABLE -> "找不到文件：来源当前不可达"
        Availability.LINK_EXPIRED -> "链接已失效：重新扫描或刷新来源后可恢复"
        Availability.UNSUPPORTED_SCHEME -> "链接格式不支持，无法播放"
    }

    /** 时长用 `mm:ss`，未知（<=0）显示占位 [UNKNOWN] 而不是 00:00 冒充真值。 */
    fun durationLabel(track: Track): String {
        if (track.durationMs <= 0L) return UNKNOWN
        val total = track.durationMs / 1000L
        return String.format(Locale.ROOT, "%02d:%02d", total / 60L, total % 60L)
    }
}
