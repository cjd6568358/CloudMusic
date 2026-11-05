package com.example.cloudmusic.webdav

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/03-02 §9 T02 / T03 与 §4.3 RFC 2617。 */
class DigestAuthTest {

    @Test
    @DisplayName("T02 DigestChallenge.parse 键不含 Digest 前缀，qop 多值取 auth")
    fun t02() {
        val challenge = DigestChallenge.parse(
            """Digest realm="testrealm@host.com", nonce="dcd98b7102dd2f0e8b11d0f600bfb0c093", qop="auth,auth-int", opaque="5ccc069c403ebaf9f0171e9517f40e41", algorithm=MD5""",
        )!!
        assertEquals("testrealm@host.com", challenge.realm)
        assertEquals("dcd98b7102dd2f0e8b11d0f600bfb0c093", challenge.nonce)
        assertEquals("auth", challenge.qop)
        assertEquals("5ccc069c403ebaf9f0171e9517f40e41", challenge.opaque)
        assertEquals("MD5", challenge.algorithm)
    }

    @Test
    @DisplayName("T03 Digest response 与 RFC 2617 经典示例一致")
    fun t03() {
        val challenge = DigestChallenge(
            realm = "testrealm@host.com",
            nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093",
            qop = "auth",
            opaque = null,
            algorithm = "MD5",
        )
        assertEquals(
            "6629fae49393a05397450978507c4ef1",
            DigestAuth.response(
                challenge, "Mufasa", "Circle Of Life", "GET", "/dir/index.html", 1, "0a4f113b",
            ),
        )
    }

    @Test
    @DisplayName("B13 Basic base64 正确；凭据含特殊字符不进日志")
    fun basicHeader() {
        assertEquals("Basic dTpw", DigestAuth.basic("u", "p"))
        assertEquals(
            "Basic " + java.util.Base64.getEncoder().encodeToString("u:p@中".toByteArray()),
            DigestAuth.basic("u", "p@中"),
        )
        assertNull(DigestChallenge.parse("Basic realm=\"x\""))
        assertTrue(DigestAuth.header(
            DigestChallenge("r", "n", "auth", null, "MD5"), "u", "p", "GET", "/", 1, "cn",
        ).contains("nc=00000001"))
    }
}
