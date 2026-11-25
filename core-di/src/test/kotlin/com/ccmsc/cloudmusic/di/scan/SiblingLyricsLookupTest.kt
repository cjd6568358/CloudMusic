package com.ccmsc.cloudmusic.di.scan

import com.ccmsc.cloudmusic.common.CloudMusicError
import com.ccmsc.cloudmusic.datasource.Driver
import com.ccmsc.cloudmusic.datasource.DriverConfig
import com.ccmsc.cloudmusic.datasource.DriverCtx
import com.ccmsc.cloudmusic.datasource.TextReadCapability
import com.ccmsc.cloudmusic.model.Addition
import com.ccmsc.cloudmusic.model.Link
import com.ccmsc.cloudmusic.model.LinkArgs
import com.ccmsc.cloudmusic.model.Obj
import com.ccmsc.cloudmusic.model.Storage
import com.ccmsc.cloudmusic.scanner.Candidate
import com.ccmsc.cloudmusic.scanner.CandidateKind
import com.ccmsc.cloudmusic.scanner.TrackHint
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.Charset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §61.186：同目录 `.lrc` 歌词提取（用户点单「说好的歌词全都没有」的根因修复）——
 * 用户曲库主体 WebDAV/STRM 无内嵌 USLT，歌词缓存恒空；本类补同目录 `.lrc` 提取
 * （LOCAL File 直读 GB18030 回落 / 网络驱动 TextReadCapability）。
 */
class SiblingLyricsLookupTest {

    private val sinkCalls = mutableListOf<Pair<String, String>>()
    private val lookup = SiblingLyricsLookup(
        sink = { id, raw -> sinkCalls += id to raw },
        driverFor = { id -> drivers[id] },
    )
    private val drivers = mutableMapOf<String, Driver>()

    private fun storage(id: String) = Storage(id, "/$id", "webdav", Addition("{}"))

    private fun candidate(storageId: String, path: String) = Candidate(
        storage = storage(storageId),
        obj = Obj(name = path.substringAfterLast('/'), path = path, size = 4_096L),
        kind = CandidateKind.AUDIO,
        parentDirName = path.substringBeforeLast('/').substringAfterLast('/'),
        hint = TrackHint(),
    )

    private class FakeTextDriver(private val text: String?) : Driver, TextReadCapability {
        var reads = 0
        override val config = DriverConfig(name = "FakeText")
        override suspend fun init(ctx: DriverCtx) {}
        override fun drop() {}
        override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> = emptyList()
        override suspend fun get(path: String): Obj? = null
        override suspend fun link(file: Obj, args: LinkArgs): Link =
            Link(localPath = "/files/${file.name}")

        override suspend fun readText(file: Obj): String {
            reads++
            return text ?: throw CloudMusicError.DsUnreachable("no lrc: ${file.path}")
        }
    }

    @Test
    @DisplayName("网络源：同目录 .lrc 命中 → sink 落缓存（键稳定幂等）")
    fun remoteSiblingHit() = runTest {
        drivers["nas-1"] = FakeTextDriver("[00:01.00]hello")
        val c = candidate("nas-1", "/音乐/晴天.flac")
        lookup(c, "http://nas/dav/音乐/晴天.flac")
        assertEquals(1, sinkCalls.size)
        assertEquals("[00:01.00]hello", sinkCalls[0].second)
        // 同候选重复调用：StateFlow 键稳定（TrackId.of 同参同哈希），且不重复落盘
        lookup(c, "http://nas/dav/音乐/晴天.flac")
        assertEquals(2, sinkCalls.size)
        assertEquals(sinkCalls[0].first, sinkCalls[1].first)
    }

    @Test
    @DisplayName("无 .lrc：目录记忆生效，同目录第二个候选不再发 GET")
    fun remoteMissRememberedPerDir() = runTest {
        val driver = FakeTextDriver(null)
        drivers["nas-1"] = driver
        val c = candidate("nas-1", "/音乐/a.mp3")
        lookup(c, "http://nas/dav/音乐/a.mp3")
        assertEquals(0, sinkCalls.size)
        assertEquals(4, driver.reads, "无歌词目录：本轮 4 个候选各试一次")
        lookup(candidate("nas-1", "/音乐/b.mp3"), "http://nas/dav/音乐/b.mp3")
        assertEquals(4, driver.reads, "同目录记忆：第二个候选零请求")
        // 不同目录不受记忆影响
        lookup(candidate("nas-1", "/其它/c.mp3"), "http://nas/dav/其它/c.mp3")
        assertEquals(8, driver.reads)
    }

    @Test
    @DisplayName("LOCAL：sibling .lrc GB18030 编码回落解码，非 UTF-8 天书")
    fun localGbkFallback(@TempDir dir: File) = runTest {
        val audio = File(dir, "song.mp3").apply { writeBytes(byteArrayOf(1)) }
        val lrcBytes = "[00:01.50]大约在冬季".toByteArray(Charset.forName("GB18030"))
        File(dir, "song.lrc").writeBytes(lrcBytes)
        lookup(candidate("local", audio.absolutePath), audio.absolutePath)
        assertEquals(1, sinkCalls.size)
        assertEquals("[00:01.50]大约在冬季", sinkCalls[0].second)
        assertTrue('\uFFFD' !in sinkCalls[0].second)
    }

    @Test
    @DisplayName("§61.223：无 scheme 相对路径 ref（SFTP walk 形态）→ 驱动 TextReadCapability 命中")
    fun sftpRelativeRefHit() = runTest {
        drivers["sftp-1"] = FakeTextDriver("[00:01.00]sftp lrc")
        val c = candidate("sftp-1", "nas_song.mp3")
        lookup(c, "nas_song.mp3")
        assertEquals(1, sinkCalls.size, "相对路径 ref 走驱动读候选，旧三态判定误判 LOCAL File")
        assertEquals("[00:01.00]sftp lrc", sinkCalls[0].second)
    }

    @Test
    @DisplayName("§61.223：SFTP link() 缓存路径 ref（cacheDir 前缀）→ 驱动 TextReadCapability 命中")
    fun sftpCacheRefHit() = runTest {
        drivers["sftp-2"] = FakeTextDriver("[00:01.00]cache ref lrc")
        val lookup2 = SiblingLyricsLookup(
            sink = { id, raw -> sinkCalls += id to raw },
            driverFor = { id -> drivers[id] },
            cacheDirPrefix = "/data/user/0/com.ccmsc.cloudmusic/cache",
        )
        // RegistryLinkLocator 对 SFTP 返回 link() 本地缓存路径（§61.216 全量缓存语义）
        lookup2(candidate("sftp-2", "nas_song.mp3"), "/data/user/0/com.ccmsc.cloudmusic/cache/sftp/x.mp3")
        assertEquals(1, sinkCalls.size, "缓存路径 ref 应回落驱动读同目录 lrc")
        assertEquals("[00:01.00]cache ref lrc", sinkCalls[0].second)
    }

    private class PathAwareTextDriver(private val answer: (String) -> String?) : Driver, TextReadCapability {
        val tried = ArrayList<String>()
        override val config = DriverConfig(name = "PathAwareText")
        override suspend fun init(ctx: DriverCtx) {}
        override fun drop() {}
        override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> = emptyList()
        override suspend fun get(path: String): Obj? = null
        override suspend fun link(file: Obj, args: LinkArgs): Link = Link(localPath = "/files/${file.name}")
        override suspend fun readText(file: Obj): String {
            tried += file.path
            return answer(file.path) ?: throw CloudMusicError.DsUnreachable("no lrc: ${file.path}")
        }
    }

    @Test
    @DisplayName("§61.217：lyrics/ 子目录命中即止，不发后续候选请求")
    fun remoteLyricsSubdirHit() = runTest {
        val driver = PathAwareTextDriver { path ->
            "[00:02.00]子目录歌词".takeIf { path == "/音乐/lyrics/晴天.lrc" }
        }
        drivers["nas-2"] = driver
        val c = candidate("nas-2", "/音乐/晴天.flac").copy(
            hint = TrackHint(title = "晴天", artist = "周杰伦"),
        )
        lookup(c, "http://nas/dav/音乐/晴天.flac")
        assertEquals(1, sinkCalls.size)
        assertEquals("[00:02.00]子目录歌词", sinkCalls[0].second)
        assertEquals(listOf("/音乐/晴天.lrc", "/音乐/lyrics/晴天.lrc"), driver.tried)
    }

    @Test
    @DisplayName("§61.217：LOCAL 大写 .LRC + GB18030 回落命中")
    fun localUppercaseLrcGbk(@TempDir dir: File) = runTest {
        val audio = File(dir, "song.mp3").apply { writeBytes(byteArrayOf(1)) }
        File(dir, "song.LRC").writeBytes("[00:03.00]大写歌词".toByteArray(Charset.forName("GB18030")))
        lookup(candidate("local", audio.absolutePath), audio.absolutePath)
        assertEquals(1, sinkCalls.size)
        assertEquals("[00:03.00]大写歌词", sinkCalls[0].second)
    }

    @Test
    @DisplayName("LOCAL 无 sibling → 不落缓存；根目录候选（无父目录）直接 no-op")
    fun localMissAndRootNoop(@TempDir dir: File) = runTest {
        val audio = File(dir, "song.flac").apply { writeBytes(byteArrayOf(1)) }
        lookup(candidate("local", audio.absolutePath), audio.absolutePath)
        assertEquals(0, sinkCalls.size)
        lookup(candidate("local", "root.mp3"), "root.mp3")
        assertEquals(0, sinkCalls.size)
    }
}
