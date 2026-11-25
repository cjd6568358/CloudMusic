package com.ccmsc.cloudmusic.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * 唱片中心标签的**自绘**版本（§61.278 / V0.4-05 顺带审查项）。
 *
 * ## 为什么换掉竞品位图
 *
 * 原实现用竞品 `legacy_g9f.webp`（588×588 红色唱片盘）作无封面时的占位。该图上有**两圈版权文字**：
 * 左弧「网易云音乐保留全部著作权权利」、右弧 "All rights are reserved by NetEase Cloud Music"。
 * 这是别人的权利声明，贴在我们的 App 里既**事实不成立**（内容不是他们的），也是明确的品牌外泄
 * （`03-12 §8` 发布阻断项里的高优先替换项，V0.4 顺带收口）。
 *
 * 本类按原图**几何与配色**重绘同一张标签，只去掉文字：黑色外缘 → 红色盘面 → 两道暗红压纹
 * → 中心轴孔（深底 + 亮圈）。尺寸/层序/z 序全部不变，所以播放页像素基准（§61.270）不受影响。
 */
internal object DiscLabelArt {

    /** 原图基准边长（`legacy_g9f.webp` 588×588）。 */
    const val BASE_PX = 588

    /** 盘面红：取自原图主体色。 */
    private const val LABEL_RED = 0xFFEE4B45.toInt()

    /** 外缘黑：原图 1.6% 边宽的黑色描边。 */
    private const val RIM_BLACK = 0xFF17171A.toInt()

    private var cached: Bitmap? = null

    /**
     * 取红色标签位图（进程内缓存一张，`VinylDiscView` 多处复用）。
     *
     * @param sizePx 目标边长像素；默认按原图 588px 出图，靠 `ImageView` 缩放。
     */
    fun redLabel(context: Context, sizePx: Int = BASE_PX): Bitmap {
        cached?.let { if (it.width == sizePx) return it }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = sizePx / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // 外缘（原图有一圈近黑描边，黑胶环压上来后露出的就是它）
        fill.color = RIM_BLACK
        c.drawCircle(r, r, r, fill)

        // 盘面
        val rim = sizePx * 0.016f
        fill.color = LABEL_RED
        c.drawCircle(r, r, r - rim, fill)

        // 两道压纹：原图有极淡的同心圆刻线（替换文字占位，保留「唱片」质感）
        val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.004f
            color = Color.argb(28, 0, 0, 0)
        }
        c.drawCircle(r, r, r * 0.78f, groove)
        c.drawCircle(r, r, r * 0.62f, groove)

        // §61.372：缺封面时这里是一整块**纯红大圆 + 一个小黑点**，用户读作「红碗」——
        // 没有任何图案说明「这里本该有封面」。补一个半透明八分音符（全 Path 绘制；
        // §61.278 守护禁止在该自绘里出现任何文字绘制）。alpha 72 ≈ 28%，压在红底上是「淡白」，
        // 够看出是图案，又不至于抢过有封面时的真实封面。
        // 尺寸按「图形占盘面 ~44%」定：首版 0.30 太小，缩略图里读作一个红色小逗号。
        // 中心轴孔会从符干中间穿过去——真唱片的中心孔本来就穿在标签图案上，保留这个真实感。
        val noteR = r * 0.44f
        val note = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(72, 255, 255, 255)
        }
        c.save()
        c.rotate(-16f, r, r)
        // 符头（实心椭圆，**横放**才是标准音符语形；竖放会读成「水滴」）
        c.drawOval(
            RectF(r - noteR * 0.62f, r - noteR * 0.02f, r - noteR * 0.02f, r + noteR * 0.34f),
            note,
        )
        // 符干（右缘竖直）
        c.drawRoundRect(
            RectF(r - noteR * 0.16f, r - noteR * 0.86f, r - noteR * 0.04f, r + noteR * 0.20f),
            noteR * 0.06f, noteR * 0.06f, note,
        )
        // 符尾（自杆顶向右下的一笔，粗细按旗面）
        note.style = Paint.Style.STROKE
        note.strokeWidth = noteR * 0.15f
        note.strokeCap = Paint.Cap.ROUND
        c.drawPath(
            Path().apply {
                moveTo(r - noteR * 0.10f, r - noteR * 0.86f)
                cubicTo(
                    r + noteR * 0.30f, r - noteR * 0.74f,
                    r + noteR * 0.36f, r - noteR * 0.34f,
                    r + noteR * 0.16f, r - noteR * 0.06f,
                )
            },
            note,
        )
        c.restore()

        // 中心轴孔：深底 + 亮圈（原图轴孔外侧有一道浅色高光）
        fill.color = Color.argb(38, 255, 255, 255)
        c.drawOval(RectF(r - r * 0.075f, r - r * 0.075f, r + r * 0.075f, r + r * 0.075f), fill)
        fill.color = 0xFF2B2B2E.toInt()
        c.drawCircle(r, r, r * 0.055f, fill)
        fill.color = 0xFF101012.toInt()
        c.drawCircle(r, r, r * 0.038f, fill)

        cached = bmp
        return bmp
    }
}
