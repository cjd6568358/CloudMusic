package androidx.media3.decoder.ffmpeg

import androidx.media3.common.MimeTypes
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `12 §61.102` · 自定义容器 MIME → FFmpeg decoder 名映射回归。
 *
 * 上游 decoder_ffmpeg 只映射标准 MimeTypes，若不补这四条，
 * `FfmpegAudioRenderer.supportsFormatInternal` 会因 `getCodecName()==null`
 * 把 Ape/Asf/Aiff/Wavpack 的 Format 判为 FORMAT_UNSUPPORTED_SUBTYPE（无解码器）。
 *
 * 诚实边界：本测试只验证 Java 侧映射表，不加载 native 库、不断言
 * `ffmpegHasDecoder()` 为真；静态库是否启用对应 decoder 由 build_ffmpeg.sh 决定。
 */
class FfmpegLibraryMimeTest {

    @Test
    fun `四种自研 Extractor 的 MIME 均映射到 FFmpeg 解码器名`() {
        assertEquals("ape", FfmpegLibrary.getCodecName("audio/x-ape"))
        assertEquals("wmav2", FfmpegLibrary.getCodecName("audio/x-wma"))
        assertEquals("wavpack", FfmpegLibrary.getCodecName("audio/x-wavpack"))
        assertEquals("pcm_s16be", FfmpegLibrary.getCodecName("audio/x-aiff"))
    }

    @Test
    fun `上游标准 MIME 映射未被改动`() {
        assertEquals("mp3", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_MPEG))
        assertEquals("aac", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_AAC))
        assertEquals("flac", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_FLAC))
        assertEquals("opus", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_OPUS))
        assertEquals("pcm_alaw", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_ALAW))
    }

    @Test
    fun `未知 MIME 仍返回 null 不静默兜底`() {
        assertNull(FfmpegLibrary.getCodecName("audio/x-unknown"))
        assertNull(FfmpegLibrary.getCodecName(""))
    }
}
