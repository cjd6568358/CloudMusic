package com.example.cloudmusic.webdav

import java.security.MessageDigest
import java.util.Base64

/** docs/03-02 §4.3 · Go gowebdav 初始恒为 NoAuth，即使配了账号密码也要走 401 协商。 */
enum class AuthMode { NONE, BASIC, DIGEST }

data class DigestChallenge(
    val realm: String,
    val nonce: String,
    val qop: String,
    val opaque: String?,
    val algorithm: String,
) {
    companion object {
        /**
         * 按逗号切 k=v，取 `=` 前最后一个空白分隔 token 作为键
         * （第一片形如 `Digest realm="..."`，否则键会变成 "Digest realm"）。
         * 引号内的逗号不切分；剥掉值两侧引号与空白；qop 多值取 auth。
         */
        fun parse(header: String): DigestChallenge? {
            val params = mutableMapOf<String, String>()
            for (part in splitQuoted(header)) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq).trim().split(Regex("\\s+")).last().lowercase()
                val value = part.substring(eq + 1).trim().removeSurrounding("\"").trim()
                params[key] = value
            }
            val realm = params["realm"] ?: return null
            val nonce = params["nonce"] ?: return null
            val qop = (params["qop"] ?: "auth").split(',')
                .map { it.trim().lowercase() }
                .firstOrNull { it == "auth" } ?: "auth"
            return DigestChallenge(
                realm = realm,
                nonce = nonce,
                qop = qop,
                opaque = params["opaque"],
                algorithm = params["algorithm"] ?: "MD5",
            )
        }

        private fun splitQuoted(input: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var inQuote = false
            for (c in input) {
                when {
                    c == '"' -> { inQuote = !inQuote; sb.append(c) }
                    c == ',' && !inQuote -> { out += sb.toString(); sb.clear() }
                    else -> sb.append(c)
                }
            }
            if (sb.isNotEmpty()) out += sb.toString()
            return out
        }
    }
}

object DigestAuth {
    fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { b ->
                val v = b.toInt() and 0xff
                append(HEX[v ushr 4]).append(HEX[v and 0x0f])
            }
        }
    }

    /** RFC 2617 (qop=auth)：response = MD5(HA1:nonce:nc:cnonce:qop:HA2)。 */
    fun response(
        challenge: DigestChallenge,
        username: String,
        password: String,
        method: String,
        uri: String,
        nc: Int,
        cnonce: String,
    ): String {
        val ha1 = md5("$username:${challenge.realm}:$password")
        val ha2 = md5("$method:$uri")
        val ncHex = "%08x".format(nc)
        return md5("$ha1:${challenge.nonce}:$ncHex:$cnonce:${challenge.qop}:$ha2")
    }

    fun header(
        challenge: DigestChallenge,
        username: String,
        password: String,
        method: String,
        uri: String,
        nc: Int,
        cnonce: String,
    ): String = buildString {
        append("Digest username=\"").append(username).append('"')
        append(", realm=\"").append(challenge.realm).append('"')
        append(", nonce=\"").append(challenge.nonce).append('"')
        append(", uri=\"").append(uri).append('"')
        append(", response=\"").append(
            response(challenge, username, password, method, uri, nc, cnonce),
        ).append('"')
        append(", qop=").append(challenge.qop)
        append(", nc=").append("%08x".format(nc))
        append(", cnonce=\"").append(cnonce).append('"')
        append(", algorithm=").append(challenge.algorithm)
        challenge.opaque?.let { append(", opaque=\"").append(it).append('"') }
    }

    fun basic(username: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString(
            "$username:$password".toByteArray(Charsets.UTF_8),
        )

    private val HEX = "0123456789abcdef".toCharArray()
}
