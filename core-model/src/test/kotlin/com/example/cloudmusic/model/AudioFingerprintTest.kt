package com.example.cloudmusic.model

import kotlin.math.PI
import kotlin.math.sin
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §61.256：Tier3 自研声纹——合成 PCM 上的算法行为矩阵（纯 JVM）。 */
class AudioFingerprintTest {

    private val rate = 8_000

    /**
     * 合成「音乐」：多正弦叠加 + 缓变包络。**baseFactor 决定基频**——不同曲目必须
     * 频谱错开（峰值落不同 bin），否则指纹天然相同（§61.256 F3 教训）。
     */
    private fun music(seed: Double, seconds: Double = 30.0, baseFactor: Double = 1.0): FloatArray {
        val n = (rate * seconds).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val env = 0.6 + 0.4 * sin(t * 0.7 + seed)
            out[i] = (env * (
                sin(2 * PI * 220.0 * baseFactor * t + seed) * 0.5 +
                    sin(2 * PI * 440.0 * baseFactor * t + seed * 2) * 0.3 +
                    sin(2 * PI * 880.0 * baseFactor * t + seed * 3) * 0.2
                )).toFloat()
        }
        return out
    }

    /** 「转码劣化」：加噪 + 量化（模拟有损编码的频谱扰动）。 */
    private fun transcode(pcm: FloatArray): FloatArray {
        val out = FloatArray(pcm.size)
        for (i in pcm.indices) {
            val noise = (if (i % 3 == 0) 0.02f else -0.015f) * (if (i % 7 == 0) 2f else 1f)
            out[i] = ((pcm[i] + noise) * 64).toInt() / 64f   // 粗量化
        }
        return out
    }

    @Test
    @DisplayName("§61.256 F1 · 同源 PCM：指纹非空且相似度 = 1")
    fun sameSourceIdentical() {
        val pcm = music(1.0)
        val fp = AudioFingerprint.fingerprint(pcm, rate)
        assertTrue(fp.isNotEmpty(), "指纹非空")
        assertEquals(1.0, AudioFingerprint.similarity(fp, AudioFingerprint.fingerprint(pcm, rate)), 1e-9)
    }

    @Test
    @DisplayName("§61.256 F2 · 转码劣化（加噪+量化）→ 相似度 ≥ 阈值（疑似同源命中）")
    fun transcodedMatches() {
        val pcm = music(2.0)
        val sim = AudioFingerprint.similarity(
            AudioFingerprint.fingerprint(pcm, rate),
            AudioFingerprint.fingerprint(transcode(pcm), rate),
        )
        assertTrue(sim >= AudioFingerprint.MATCH_THRESHOLD, "转码相似度应命中阈值：$sim")
    }

    @Test
    @DisplayName("§61.256 F3 · 不同曲目（异频谱）→ 相似度低于阈值（不误报）")
    fun differentSongLowSimilarity() {
        val sim = AudioFingerprint.similarity(
            AudioFingerprint.fingerprint(music(1.0), rate),
            AudioFingerprint.fingerprint(music(9.0, seconds = 30.0, baseFactor = 1.5), rate),
        )
        assertTrue(sim < AudioFingerprint.MATCH_THRESHOLD, "异曲相似度应低：$sim")
    }

    @Test
    @DisplayName("§61.256 F4 · 边界：过短 PCM 空指纹 / 重采样率换算 / 相似度空集 0")
    fun boundaries() {
        assertTrue(AudioFingerprint.fingerprint(FloatArray(100), rate).isEmpty(), "过短 → 空指纹")
        val pcm = music(3.0)
        val up = AudioFingerprint.resample(pcm, 4_000)
        assertEquals(pcm.size, up.size, "低于目标率 → 原样（解码侧保证）")
        assertEquals(0.0, AudioFingerprint.similarity(emptySet(), setOf(1L)))
        assertEquals(0.0, AudioFingerprint.similarity(setOf(1L), emptySet()))
    }

    @Test
    @DisplayName("§61.256 F5 · likelySameSource 便捷判：同源 true / 异曲 false")
    fun likelySameSource() {
        val a = music(5.0)
        assertTrue(AudioFingerprint.likelySameSource(a, rate, transcode(a), rate))
        assertTrue(!AudioFingerprint.likelySameSource(a, rate, music(7.0, baseFactor = 1.5), rate))
    }
}
