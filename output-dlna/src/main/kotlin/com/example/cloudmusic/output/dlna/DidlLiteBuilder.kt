package com.example.cloudmusic.output.dlna

import com.example.cloudmusic.model.Track
import java.util.Locale

/** docs/03-08 §6.3 DIDL-Lite 元数据构造；所有文本字段必须 XML 转义（§13 T07）。 */
object DidlLiteBuilder {

    const val CONTENT_FEATURES =
        "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
    private const val UPNP_CLASS = "object.item.audioItem.musicTrack"

    fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** `hh:mm:ss.SSS`；负时长按 0 处理。 */
    fun formatDuration(durationMs: Long): String {
        val total = durationMs.coerceAtLeast(0)
        val h = total / 3_600_000
        val m = (total % 3_600_000) / 60_000
        val s = (total % 60_000) / 1_000
        val ms = total % 1_000
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", h, m, s, ms)
    }

    /** 12 §61.45 / F1：MIME 推断统一委托 AudioFormats，本处不再维护 when 分支。 */
    fun mimeOf(track: Track, hint: String? = null): String =
        hint?.takeIf { it.isNotBlank() }
            ?: com.example.cloudmusic.model.AudioFormats.mimeOfName(
                track.sourceRef.url ?: track.sourceRef.path
            )

    fun build(
        track: Track,
        uri: String,
        mime: String = mimeOf(track),
        durationMs: Long = track.durationMs,
        coverUrl: String? = null,
    ): String {
        val cover = coverUrl?.let { "\n    <upnp:albumArtURI>${escape(it)}</upnp:albumArtURI>" }.orEmpty()
        return buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">\n")
            append("  <item id=\"${escape(track.id.value)}\" parentID=\"-1\" restricted=\"1\">\n")
            append("    <dc:title>${escape(track.title)}</dc:title>\n")
            append("    <dc:creator>${escape(track.artist)}</dc:creator>\n")
            append("    <upnp:artist>${escape(track.artist)}</upnp:artist>\n")
            append("    <upnp:album>${escape(track.album)}</upnp:album>\n")
            append("    <upnp:class>$UPNP_CLASS</upnp:class>\n")
            append("    <res protocolInfo=\"http-get:*:$mime:$CONTENT_FEATURES\" ")
            append("duration=\"${formatDuration(durationMs)}\">${escape(uri)}</res>")
            append(cover).append("\n")
            append("  </item>\n</DIDL-Lite>")
        }
    }
}
