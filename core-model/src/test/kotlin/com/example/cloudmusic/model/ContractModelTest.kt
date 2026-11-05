package com.example.cloudmusic.model

import com.example.cloudmusic.common.HashUtil
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * docs/03-01 §12 契约测试 T01~T11（:core-model 侧）。
 * T12~T16 在 :datasource-api 的 DriverContractTest；T17~T20 依赖具体驱动实现，
 * 待 03-02 / 03-03 落地后补，不属于本轮范围。
 */
class ContractModelTest {

    @Nested
    @DisplayName("T01~T06 · Link 三态互斥与 scheme 约束")
    inner class LinkContract {

        @Test
        @DisplayName("T01 Link(url=http…) → kind == URL")
        fun t01() {
            val link = Link(url = "https://nas.example.com/music/a.flac?sign=xyz")
            assertEquals(Link.Kind.URL, link.kind)
        }

        @Test
        @DisplayName("T02 Link(localPath=…) → kind == LOCAL")
        fun t02() {
            val link = Link(localPath = "/storage/emulated/0/Music/a.flac", localSize = 4096)
            assertEquals(Link.Kind.LOCAL, link.kind)
        }

        @Test
        @DisplayName("T03 Link(proxy=true, text=…) → kind == TEXT 且 proxy=true")
        fun t03() {
            val link = Link(proxy = true, text = "https://nas.example.com/a.flac")
            assertEquals(Link.Kind.TEXT, link.kind)
            assertTrue(link.proxy)
        }

        @Test
        @DisplayName("T04 url 与 localPath 同时存在 → 抛 IllegalArgumentException")
        fun t04() {
            assertFailsWith<IllegalArgumentException> {
                Link(url = "https://example.com/a.flac", localPath = "/Music/a.flac")
            }
        }

        @Test
        @DisplayName("T05 text 未伴随 proxy=true → 抛异常")
        fun t05() {
            assertFailsWith<IllegalArgumentException> {
                Link(text = "https://example.com/a.flac", proxy = false)
            }
        }

        @Test
        @DisplayName("T06 非 http/https 直链 → 抛异常（PRD §5.2）")
        fun t06() {
            assertFailsWith<IllegalArgumentException> {
                Link(url = "ftp://nas.example.com/a.flac")
            }
        }

        @Test
        @DisplayName("补充：requireReference 只允许 PROXIED / LOCAL")
        fun requireReferenceGuard() {
            assertEquals(
                Link.Kind.PROXIED,
                Link(proxy = true, requireReference = true).kind,
            )
            assertFailsWith<IllegalArgumentException> {
                Link(url = "https://example.com/a.flac", requireReference = true)
            }
        }

        @Test
        @DisplayName("补充：空 Link 视为 PROXIED（url 为空），不产生歧义分派")
        fun emptyLink() {
            assertEquals(Link.Kind.PROXIED, Link().kind)
        }
    }

    @Nested
    @DisplayName("T07~T09 · ObjectType 判定")
    inner class ObjectTypeContract {

        @Test
        @DisplayName("T07 扩展名大小写不敏感")
        fun t07() {
            assertEquals(ObjectType.AUDIO, ObjectType.of("x.MP3", isDir = false))
            assertEquals(ObjectType.AUDIO, ObjectType.of("x.FLAC", isDir = false))
        }

        @Test
        @DisplayName("T08 .gitignore → UNKNOWN（extOf 取 gitignore，不在任何集合）")
        fun t08() {
            assertEquals("gitignore", ObjectType.extOf(".gitignore"))
            assertEquals(ObjectType.UNKNOWN, ObjectType.of(".gitignore", isDir = false))
        }

        @Test
        @DisplayName("T09 目录恒为 FOLDER")
        fun t09() {
            assertEquals(ObjectType.FOLDER, ObjectType.of("a/", isDir = true))
        }

        @Test
        @DisplayName("补充：m3u8 属 TEXT，wv/tak 属 AUDIO（10 §2 R14）")
        fun r14Corrections() {
            assertEquals(ObjectType.TEXT, ObjectType.of("list.m3u8", isDir = false))
            assertEquals(ObjectType.AUDIO, ObjectType.of("song.wv", isDir = false))
            assertEquals(ObjectType.AUDIO, ObjectType.of("song.tak", isDir = false))
            assertEquals(4, ObjectType.TEXT.value)
        }
    }

    @Nested
    @DisplayName("T10~T11 · TrackId 稳定性")
    inner class TrackIdContract {

        @Test
        @DisplayName("T10 规范化后 ID 相等")
        fun t10() {
            val a = TrackId.of("storage-1", "a/../b/c.flac")
            val b = TrackId.of("storage-1", "b/c.flac")
            assertEquals(a, b)
            assertEquals(32, a.value.length)
        }

        @Test
        @DisplayName("T11 换 host / 凭据但 Storage.id 不变 → ID 不变")
        fun t11() {
            val before = Storage(
                id = "stable-uuid", mountPath = "/nas", driverType = "webdav",
                addition = Addition("""{"host":"192.168.1.10","user":"u1"}"""),
            )
            val after = before.copy(
                addition = Addition("""{"host":"nas.example.com","user":"u2"}"""),
                mountPath = "/music",
            )
            assertEquals(before.id, after.id)
            assertEquals(
                TrackId.of(before.id, "Album/01.flac"),
                TrackId.of(after.id, "Album/01.flac"),
            )
        }

        @Test
        @DisplayName("补充：不同 namespace 必产生不同 ID")
        fun differentNamespace() {
            assertTrue(
                TrackId.of("s1", "a.flac") != TrackId.of("s2", "a.flac"),
            )
        }

        @Test
        @DisplayName("补充：HashUtil.sha256 为 64 位小写十六进制")
        fun hashFormat() {
            assertEquals(64, HashUtil.sha256("abc").length)
            assertEquals(
                HashUtil.sha256("abc"),
                HashUtil.sha256("abc".toByteArray(Charsets.UTF_8)),
            )
        }
    }

    @Test
    @DisplayName("补充：File.from(Obj) 完整搬运字段")
    fun fileFromObj() {
        val obj = Obj(name = "a.flac", size = 1024, modified = 1700000000L, sign = "token")
        val file = File.from(obj)
        assertEquals(obj.name, file.name)
        assertEquals(obj.size, file.size)
        assertEquals(obj.sign, file.sign)
        assertEquals(ObjectType.AUDIO, obj.type)
    }
}
