package com.example.cloudmusic.scanner

import com.example.cloudmusic.datasource.ByteRangeCapability
import com.example.cloudmusic.model.Addition
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.Storage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/03-04 §14 T01~T03、T05~T10、T15 的纯单元部分。 */
class ScannerUnitTest {

    @Test
    @DisplayName("T01 文件名 艺人 - 标题 拆分，专辑取父目录")
    fun t01() {
        val f = NameParser.parse("周杰伦 - 晴天.flac", "周杰伦 - 叶惠美")
        assertEquals("晴天", f.title)
        assertEquals("周杰伦", f.artist)
        assertEquals("叶惠美", f.album)
        assertEquals(0.2f, f.completeness)
    }

    @Test
    @DisplayName("T02 无分隔符 → title=文件名、artist=未知；播放列表 hint 优先")
    fun t02() {
        val f = NameParser.parse("晴天.flac", "未知目录")
        assertEquals("晴天", f.title)
        assertEquals("未知", f.artist)
        val hinted = NameParser.parse("01.flac", "专辑", TrackHint(title = "来自提示", artist = "提示艺人"))
        assertEquals("来自提示", hinted.title)
        assertEquals("提示艺人", hinted.artist)
    }

    @Test
    @DisplayName("T03 magic bytes 判定真实格式，不盲信扩展名")
    fun t03() {
        assertEquals(AudioFormat.FLAC, AudioProbe.probe("fLaC\u0000\u0000".toByteArray()))
        assertEquals(AudioFormat.MP3, AudioProbe.probe("ID3\u0004\u0000\u0000".toByteArray()))
        assertEquals(AudioFormat.OGG, AudioProbe.probe("OggS\u0000".toByteArray()))
        assertEquals(AudioFormat.WAV, AudioProbe.probe("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray()))
        assertEquals(AudioFormat.M4A, AudioProbe.probe("\u0000\u0000\u0000\u0018ftypM4A ".toByteArray()))
        assertEquals(AudioFormat.MP3, AudioProbe.probe(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0, 0)))
        assertEquals(AudioFormat.UNKNOWN, AudioProbe.probe(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    @DisplayName("T08 .nomedia / ignore_dirs 排除；T09/T10 最短时长只过滤已知短文件")
    fun excludeRules() {
        val rules = ScanRules()
        assertTrue(rules.isIgnoredDir("Android"))
        assertTrue(rules.isIgnoredDir(".thumbnails"))
        assertTrue(rules.isNoMedia(listOf("a.mp3", ".nomedia")))
        assertEquals(FileClass.AUDIO, rules.classify("a.MP3", false))
        assertEquals(FileClass.PLAYLIST, rules.classify("list.m3u8", false))
        assertEquals(FileClass.STRM, rules.classify("x.strm", false))
        assertEquals(FileClass.PASS_THROUGH, rules.classify("x.lrc", false))
        assertEquals(FileClass.IGNORED, rules.classify("x.exe", false))
        assertTrue(rules.tooShort(5_000L))
        assertEquals(false, rules.tooShort(null))
        assertEquals(false, rules.tooShort(0L))
        assertEquals(false, rules.tooShort(12_000L))
    }

    @Test
    @DisplayName("T05 ID3v2 文本帧解析；T06 损坏标签返回 EMPTY 仍能生成 Track；T15 不下载整文件")
    fun id3v2AndBrokenTag() = runTest {
        val head = id3v2(
            "TIT2" to "晴天", "TPE1" to "Jay", "TALB" to "叶惠美",
            "TRCK" to "3/12", "TYER" to "2001",
        )
        val range = FakeRange(head)
        val reader = HeadTagReader(rangeFor = { range })
        val candidate = candidate("周杰伦 - 晴天.mp3")
        val tags = reader.read(candidate)
        assertEquals("晴天", tags.title)
        assertEquals("Jay", tags.artist)
        assertEquals("叶惠美", tags.album)
        assertEquals(3, tags.trackNo)
        assertEquals(2001, tags.year)
        assertEquals(1.0f, tags.completeness)
        // T15：只读头部 64KB + 尾部 128B，绝不整文件下载
        assertEquals(2, range.calls.size)
        assertTrue(range.calls.all { it.second <= 64 * 1024 })
        assertTrue(range.calls.sumOf { it.second } < 100_000)

        val broken = HeadTagReader(rangeFor = { FakeRange(byteArrayOf(1, 2, 3, 4)) })
        assertEquals(RawTags.EMPTY, broken.read(candidate))
        val track = TrackFactory().build(candidate, broken.read(candidate))
        assertEquals("晴天", track.title)   // 文件名兜底
        assertEquals(0.2f, track.tagCompleteness)
    }

    @Test
    @DisplayName("§61.170 GBK 旧标签（enc=0x00 实为 GBK）在解析时修复为中文")
    fun gbkLegacyTag() = runTest {
        // ID3v2 enc=0x00 名义 Latin-1，但旧工具写入的是 GBK 字节
        val gbk = byteArrayOf(
            0xD6.toByte(), 0xDC.toByte(), // 周
            0xBD.toByte(), 0xDC.toByte(), // 杰
            0xC2.toByte(), 0xD7.toByte(), // 伦
        )
        val head = id3v2Raw("TIT2" to (byteArrayOf(0) + gbk))
        val tags = HeadTagReader(rangeFor = { FakeRange(head) }).read(candidate("c.mp3"))
        assertEquals("周杰伦", tags.title)
    }

    @Test
    @DisplayName("§61.170 GBK 文件名兜底（WebDAV/OpenList 文件名乱码）修复为中文")
    fun gbkFileNameFallback() {
        // "周杰伦 - 晴天.mp3" 的 GBK 字节被按 ISO-8859-1 解出
        val gbkName = mojibake(
            "周杰伦 - 晴天.mp3".toByteArray(charset("GB18030")),
        )
        val fallback = NameParser.parse(gbkName, "叶惠美")
        assertEquals("晴天", fallback.title)
        assertEquals("周杰伦", fallback.artist)
        assertEquals("叶惠美", fallback.album)
    }

    @Test
    @DisplayName("§61.170 百分号编码文件名（STRM 侧）解成中文标题")
    fun percentEncodedFileName() {
        val fallback = NameParser.parse(
            "S.H.E%20-%20%E8%8C%B1%E7%BD%97%E8%AE%B0.wma",
            "F3 双路径矩阵",
        )
        assertEquals("茱罗记", fallback.title)
        assertEquals("S.H.E", fallback.artist)
        // 不含 %XX 的名字原样保留（含字面 % 也不乱解）
        assertEquals("100% Pure", NameParser.normalize("100% Pure"))
    }

    @Test
    @DisplayName("LYRIC-1 内嵌 USLT 歌词解析（头部可读时）；无 USLT 帧时 lyrics=null")
    fun lyricUsLt() = runTest {
        val lyricText = "[00:01.00]第一行\n[00:02.50]第二行"
        val head = id3v2WithUsLt(lyricText)
        val tags = HeadTagReader(rangeFor = { FakeRange(head) }).read(candidate("a.mp3"))
        assertEquals(lyricText, tags.lyrics)

        val noLyric = HeadTagReader(rangeFor = { FakeRange(id3v2("TIT2" to "无歌词")) }).read(candidate("b.mp3"))
        assertEquals(null, noLyric.lyrics)
    }

    @Test
    @DisplayName("T07 TrackId 换 host / 凭据但 Storage.id 不变 → 不变")
    fun trackIdStable() {
        val factory = TrackFactory()
        val s1 = storage("""{"address":"https://nas-a/dav"}""")
        val s2 = storage("""{"address":"https://nas-b/dav","password":"changed"}""")
        val c = candidate("a.flac")
        assertEquals(
            factory.build(c.copy(storage = s1)).id,
            factory.build(c.copy(storage = s2)).id,
        )
    }

    @Test
    @DisplayName("封面优先级：内嵌 → 同目录 → 占位；>5MB 回落")
    fun coverPriority() {
        val candidate = candidate("a.flac")
        val resolver = PriorityCoverResolver(directoryCover = { "cover.jpg" }, placeholder = "placeholder")
        assertTrue(resolver.resolve(candidate, ByteArray(8))!!.startsWith("embedded:"))
        assertEquals("cover.jpg", resolver.resolve(candidate, null))
        assertEquals("placeholder", PriorityCoverResolver(placeholder = "placeholder").resolve(candidate, null))
        assertEquals("cover.jpg", resolver.resolve(candidate, ByteArray(6 * 1024 * 1024)))
        // 列表里没有 cover.jpg，按 §6.4 优先级应取 cover.png（实现正确）
        assertEquals("cover.png", PriorityCoverResolver.pickDirectoryCover(listOf("FOLDER.JPG", "cover.png")))
        assertEquals("COVER.JPG", PriorityCoverResolver.pickDirectoryCover(listOf("FOLDER.JPG", "COVER.JPG")))
        assertNull(PriorityCoverResolver.pickDirectoryCover(listOf("a.mp3")))
    }

    private fun storage(addition: String) = Storage(
        id = "s1", mountPath = "/nas", driverType = "webdav", addition = Addition(addition),
    )

    private fun candidate(name: String) = Candidate(
        storage = storage("{}"),
        obj = Obj(name = name, path = "/music/$name", size = 10_000_000L),
        kind = CandidateKind.AUDIO,
        parentDirName = "music",
    )

    private class FakeRange(private val data: ByteArray) : ByteRangeCapability {
        val calls = mutableListOf<Pair<Long, Int>>()
        override suspend fun readBytes(file: Obj, offset: Long, length: Int): ByteArray? {
            calls += offset to length
            if (offset >= data.size) return null
            val start = offset.toInt()
            return data.copyOfRange(start, minOf(data.size, start + length))
        }
    }

    private fun id3v2(vararg frames: Pair<String, String>): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { (id, text) ->
            // encoding=3 → UTF-8，兼容中文标签
            val payload = byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)
            body.write(id.toByteArray(Charsets.ISO_8859_1))
            body.write(synchSafe(payload.size))
            body.write(byteArrayOf(0, 0))
            body.write(payload)
        }
        val tagBody = body.toByteArray()
        return "ID3".toByteArray() + byteArrayOf(4, 0, 0) + synchSafe(tagBody.size) + tagBody
    }

    /** 把 GBK 字节串当 ISO-8859-1 解出来 —— 真机 WebDAV 文件名乱码形态。 */
    private fun mojibake(gbk: ByteArray): String = String(gbk, Charsets.ISO_8859_1)

    private fun charset(name: String): Charset = Charset.forName(name)

    /** 构造原始帧（payload 由调用方给出，用于 enc=0x00 的 GBK 旧标签等场景）。 */
    private fun id3v2Raw(vararg frames: Pair<String, ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { (id, payload) ->
            body.write(id.toByteArray(Charsets.ISO_8859_1))
            body.write(synchSafe(payload.size))
            body.write(byteArrayOf(0, 0))
            body.write(payload)
        }
        val tagBody = body.toByteArray()
        return "ID3".toByteArray() + byteArrayOf(4, 0, 0) + synchSafe(tagBody.size) + tagBody
    }

    /** 构造带 USLT 内嵌歌词的 ID3v2.4 帧：encoding=3(UTF-8) + lang='chi' + 空描述符 + 歌词。 */
    private fun id3v2WithUsLt(lyrics: String): ByteArray {
        val payload = byteArrayOf(3) + "chi".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0) + lyrics.toByteArray(Charsets.UTF_8)
        val body = ByteArrayOutputStream()
        body.write("USLT".toByteArray(Charsets.ISO_8859_1))
        body.write(synchSafe(payload.size))
        body.write(byteArrayOf(0, 0))
        body.write(payload)
        val tagBody = body.toByteArray()
        return "ID3".toByteArray() + byteArrayOf(4, 0, 0) + synchSafe(tagBody.size) + tagBody
    }

    private fun synchSafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7f).toByte(),
        ((value shr 14) and 0x7f).toByte(),
        ((value shr 7) and 0x7f).toByte(),
        (value and 0x7f).toByte(),
    )
}
