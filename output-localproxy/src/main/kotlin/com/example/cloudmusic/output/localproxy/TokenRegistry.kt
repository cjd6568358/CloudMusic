package com.example.cloudmusic.output.localproxy

import com.example.cloudmusic.model.Link
import java.security.SecureRandom

/**
 * docs/03-08 §8.3「token 与回源」的纯 JVM 实现。
 *
 * - 随机 **16 字节 hex**（32 字符）、短时效（默认 10 分钟）；
 * - 只映射当前队列内曲目，**不暴露真实路径 / 凭据**（T12：token 不存在 / 过期只返回 null）；
 * - 退出投屏 [invalidateAll] 让全部 token 立即失效（§8.4 生命周期）。
 */
class TokenRegistry(
    private val ttlMs: Long = 10 * 60 * 1000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: (Int) -> ByteArray = { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } },
) {
    private val entries = LinkedHashMap<String, ProxyEntry>()

    fun register(trackId: String, link: Link): String {
        val token = random(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        entries[token] = ProxyEntry(trackId, link, clock() + ttlMs)
        return token
    }

    /** 过期 token 会被顺手清除；调用方据此返回 404，且不得把路径写进响应。 */
    fun resolve(token: String): ProxyEntry? {
        val entry = entries[token] ?: return null
        if (entry.expiresAt <= clock()) {
            entries.remove(token)
            return null
        }
        return entry
    }

    fun invalidateAll() {
        entries.clear()
    }

    fun purgeExpired() {
        val now = clock()
        entries.entries.removeIf { it.value.expiresAt <= now }
    }

    fun size(): Int = entries.size
}
