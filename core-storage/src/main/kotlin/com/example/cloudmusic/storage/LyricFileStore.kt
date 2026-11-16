package com.example.cloudmusic.storage

import com.example.cloudmusic.model.Lyrics
import com.example.cloudmusic.model.TrackId
import java.io.File

/**
 * docs/01 §0（2026-11-16 拍板）：歌词文件缓存（纯 JVM，无 Android 依赖）。
 *
 * 设计取舍：歌词是**派生数据**（可从音频内嵌 USLT / 同目录 .lrc 重新提取），
 * 丢了大不了重扫，故按封面缓存（cacheDir/covers/）同构的文件缓存语义落 `cacheDir/lyrics/<trackId>.lrc`，
 * **不新增 Room 物理表**（守住"9 张物理表"发布基线，避免 v4 migration 风险）。
 *
 * 存放**原始 lrc 文本**（含 [offset:] 行），解析在读取时经 core-model 的 LrcParser 完成，
 * 使解析算法升级无需迁移缓存文件。
 */
class LyricFileStore(private val cacheDir: File) {
    private val dir = File(cacheDir, "lyrics")

    private fun fileFor(id: TrackId): File = File(dir, "${id.value}.lrc")

    /** 写入原始 lrc 文本；返回是否成功。目录不存在时创建。 */
    fun saveRaw(id: TrackId, raw: String): Boolean = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return false
        fileFor(id).writeText(raw, Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /** 读取原始 lrc 文本；无文件返回 null。 */
    fun readRaw(id: TrackId): String? = runCatching {
        fileFor(id).takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }.getOrNull()

    fun exists(id: TrackId): Boolean = fileFor(id).isFile

    /** 删除缓存（重扫 / 源移除时调用）。 */
    fun delete(id: TrackId): Boolean = runCatching {
        fileFor(id).let { !it.exists() || it.delete() }
    }.getOrDefault(false)
}

/**
 * 把 [Lyrics] 序列化回可缓存的 lrc 文本（供在线 provider 缓存 / 测试往返）。
 * 注意：**不写 `[offset:]` 行**——[Lyrics.lines] 的 timeMs 在解析时已叠加过 offset，
 * 若再写 offset 行，重新 parse 会二次叠加（往返 bug）。offsetMs 仅作溯源保留。
 */
fun Lyrics.serializeToLrc(): String = buildString {
    lines.forEach { line ->
        val total = line.timeMs.coerceAtLeast(0L)
        val mm = total / 60_000L
        val ss = (total % 60_000L) / 1000L
        val cs = (total % 1000L) / 10L
        append("[%02d:%02d.%02d]%s\n".format(mm, ss, cs, line.text))
    }
}
