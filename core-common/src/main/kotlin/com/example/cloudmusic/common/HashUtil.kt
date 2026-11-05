package com.example.cloudmusic.common

import java.security.MessageDigest

/** 03-01 §6 约定：sha256() 定义在 :core-common，纯函数、无 Android 依赖。 */
object HashUtil {
    fun sha256(input: String): String = sha256(input.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
