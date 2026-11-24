package com.example.cloudmusic.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * §61.138 封面缓存：`Track.coverRef`（扫描期落盘的本地封面文件路径）→ 降采样 Bitmap。
 * 命中缓存/确认缺失（misses）直接返回；LruCache 容量 64 张（列表行 48dip 用，
 * 256px 级降采样后单张 ≈ 256KB，峰值 ≈ 16MB 上限内）。解码失败计入 misses 不重试。
 * 与播放页（LegacyPlayerPage §61.109 ②）同源同策略，IO + runCatching。
 */
private val coverCache = LruCache<String, Bitmap>(64)
private val coverMisses = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

@Composable
fun rememberCompCover(path: String?, maxPx: Int = 256): Bitmap? {
    var bitmap by remember(path) { mutableStateOf(path?.let { coverCache.get(it) }) }
    LaunchedEffect(path) {
        if (path.isNullOrBlank() || bitmap != null || path in coverMisses) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxPx &&
                    bounds.outHeight / (sample * 2) >= maxPx
                ) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = false
                        inSampleSize = sample
                    },
                )
            }.getOrNull()
        }
        if (decoded != null) coverCache.put(path, decoded) else coverMisses.add(path)
        bitmap = decoded
    }
    return bitmap
}
