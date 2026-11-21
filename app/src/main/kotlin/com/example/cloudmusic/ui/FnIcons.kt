package com.example.cloudmusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 飞牛线性图标系统（**唯一图标真相源**，docs/03-10 §8 映射表的代码侧）。
 *
 * ## 为什么有这一层（§61.278 / V0.4-05）
 *
 * V0.4-04 之前图标是「一件一行手绘 Canvas + 设置页一排 Unicode 字形」混着用的：
 * 设置页 `♫ ⇅ ⌾ ✕ ⚑ ↻ ☁ ◎ ⚖ ▤ ⧉ ◐ ∿ ≡ 🔒` 十几种字符，同一个 `◎` 还同时代表
 * 「忽略目录」和「扫描速度限制」。三个真实问题：
 *
 * 1. **表意错**：`✕` 表示「清理缓存」读起来是「关闭/报错」；`☁` 表示「音乐来源」暗示
 *    只有云盘（本地目录也是来源）；`⚑` 表示「后台播放不中断」毫无对应关系；
 * 2. **观感散**：Unicode 字形在不同厂商字体里字重/基线/大小完全不同（MIUI 上 `⚑`
 *    还是彩色 emoji 形态），一排图标横向看过去粗细、高低都对不齐；
 * 3. **会冲突**：`专辑` 画成「同心圆 + 中孔」= **靶心**，与「搜索（圆 + 柄）」「最近
 *    （圆 + 指针）」在 16.6dp 行内小尺寸下几乎糊成一团；`数据源（文件夹）` 与
 *    `歌单（提包）` 都是「带盖方盒」，同屏并列分不出。
 *
 * 本文件把这些收成一套**同视框（24 单位）、同笔画（2 单位）、同端点（圆头）**的矢量
 * 图标，`FnRow` / `FnStatCard` / 曲库视图 chip / 快捷入口全部只从这里取图。
 *
 * ## 绘制口径
 *
 * - 坐标系：24×24 单位视框，绘制时按 `size / 24` 等比缩放（`u = box / 24`），
 *   所以同一个图标在 17dp 行内槽位与 24dp 顶栏圆钮里形状一致，只有笔画随之变细；
 * - 笔画：`2 * u`，圆头 + 圆角连接；不引任何位图素材（`assets-mapping.csv` 口径
 *   `replaced=false`，图标一律自绘，不复制竞品资源）。
 */
enum class FnIcon {
    // ── 曲库视图 / 导航 ────────────────────────────────────────────────────
    /** 全部歌曲：圆圈内八分音符（基准 `161526` 曲库图标）。 */
    Songs,

    /** 歌手：头 + 肩弧。 */
    Artists,

    /** 专辑：**唱片套（圆角方）+ 内圈 + 中孔**——不再是「同心圆靶心」。 */
    Albums,

    /** 音源 / 音乐来源：**双层机架（存储盒 + 指示灯）**——与歌单提包不同形。 */
    Sources,

    /** 不可用：圆 + 斜杠（禁止符号）。 */
    Unavailable,

    /** 歌单：提包（基准 `161526` 歌单图标）。 */
    Playlist,

    /** 收藏：心形。 */
    Favorite,

    /** 最近播放：时钟。 */
    Recent,

    /** 搜索：放大镜。 */
    Search,

    /** 重扫：开口圆环 + 箭头。 */
    Refresh,

    /** 投屏 / 漫游：中点 + 双弧。 */
    Broadcast,

    // ── 设置行 / 行操作 ───────────────────────────────────────────────────
    /** 移动网络播放：上下箭头（数据进出）。 */
    Network,

    /** 播放到（输出设备）：显示器 + 底座。 */
    Output,

    /** 清理播放缓存：垃圾桶。 */
    Trash,

    /** 后台播放不中断 / 自启动：电源符号。 */
    Power,

    /** 忽略目录：文件夹 + 减号（排除语义）。 */
    FolderOff,

    /** 扫描速度限制：表盘 + 指针。 */
    Gauge,

    /** 音量均衡分析：三根不等高音柱。 */
    Level,

    /** 刮削缺失封面：相框 + 山 + 太阳。 */
    Image,

    /** 检测重复曲目 / 合并重复项：两个错位方框。 */
    Duplicate,

    /** 主题：对比度半圆。 */
    Contrast,

    /** 淡入淡出：渐强渐弱包络。 */
    Wave,

    /** 均衡器：三根竖滑杆 + 滑块。 */
    Sliders,

    /** 加密 / 凭据：锁。 */
    Lock,

    /** 添加来源：圆角方框 + 加号。 */
    Add,

    /** 设置：齿轮（双环 + 齿）。 */
    Settings,

    /** 队列：三行清单（末行短）。 */
    Queue,
}

/**
 * 画一个飞牛线性图标。
 *
 * @param size 视框边长（默认 24dp）；内部按 `size / 24` 等比缩放，笔画随之变细。
 */
@Composable
fun FnLineIcon(
    icon: FnIcon,
    tint: Color,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val sw = 2f * u
        val s = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun pt(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
            color = tint,
            start = pt(x1, y1),
            end = pt(x2, y2),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 2f) = drawRoundRect(
            color = tint,
            topLeft = pt(x, y),
            size = Size(w * u, h * u),
            cornerRadius = CornerRadius(r * u),
            style = s,
        )
        fun ring(cx: Float, cy: Float, r: Float) =
            drawCircle(color = tint, radius = r * u, center = pt(cx, cy), style = s)
        fun dot(cx: Float, cy: Float, r: Float) =
            drawCircle(color = tint, radius = r * u, center = pt(cx, cy), style = Fill)
        fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) = drawArc(
            color = tint,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = pt(cx - r, cy - r),
            size = Size(2 * r * u, 2 * r * u),
            style = s,
        )
        fun build(block: Path.() -> Unit) = Path().apply(block)
        fun path(block: Path.() -> Unit) = drawPath(build(block), tint, style = s)

        when (icon) {
            FnIcon.Songs -> {
                ring(12f, 12f, 10f)
                line(16f, 5f, 16f, 16f)
                ring(13.4f, 16.6f, 2.6f)
            }

            FnIcon.Artists -> {
                ring(12f, 8f, 4f)
                path {
                    moveTo(4f * u, 20f * u)
                    quadraticBezierTo(12f * u, 13f * u, 20f * u, 20f * u)
                }
            }

            // 唱片套：圆角方 + 内圈 + 中孔。方形轮廓与「搜索/最近/全部歌曲」的圆形成对比，
            // 16.6dp 行内也不再是四个同心圆挤在一起。
            FnIcon.Albums -> {
                rect(3f, 3f, 18f, 18f, r = 3f)
                ring(12f, 12f, 4.6f)
                dot(12f, 12f, 1.3f)
            }

            // 机架式存储：两格 + 指示灯。和歌单提包（单腔 + 提手）轮廓差异明显。
            FnIcon.Sources -> {
                rect(3f, 5.5f, 18f, 5f, r = 1.8f)
                rect(3f, 14.5f, 18f, 5f, r = 1.8f)
                dot(17.2f, 8f, 0.85f)
                dot(17.2f, 17f, 0.85f)
            }

            FnIcon.Unavailable -> {
                ring(12f, 12f, 9f)
                line(5.6f, 18.4f, 18.4f, 5.6f)
            }

            // 提包：腔体 + 提手（基准歌单图标）。
            FnIcon.Playlist -> {
                rect(4.5f, 8.5f, 15f, 12f, r = 2.5f)
                path {
                    moveTo(9f * u, 8.5f * u)
                    lineTo(9f * u, 4.8f * u)
                    lineTo(15f * u, 4.8f * u)
                    lineTo(15f * u, 8.5f * u)
                }
            }

            FnIcon.Favorite -> path {
                moveTo(12f * u, 20f * u)
                cubicTo(1f * u, 11f * u, 6f * u, 3f * u, 12f * u, 9f * u)
                cubicTo(18f * u, 3f * u, 23f * u, 11f * u, 12f * u, 20f * u)
            }

            FnIcon.Recent -> {
                ring(12f, 12f, 10f)
                line(12f, 12f, 12f, 7f)
                line(12f, 12f, 16f, 14f)
            }

            FnIcon.Search -> {
                ring(11f, 11f, 6f)
                line(15.2f, 15.2f, 20f, 20f)
            }

            FnIcon.Refresh -> {
                arc(12f, 12f, 8f, -50f, 285f)
                path {
                    moveTo(12.5f * u, 3.4f * u)
                    lineTo(17.2f * u, 4.6f * u)
                    lineTo(15.2f * u, 9f * u)
                }
            }

            FnIcon.Broadcast -> {
                dot(12f, 12f, 2.4f)
                arc(12f, 12f, 6f, -50f, 100f)
                arc(12f, 12f, 9.5f, -50f, 100f)
            }

            FnIcon.Network -> {
                line(8f, 4.5f, 8f, 19.5f)
                path {
                    moveTo(5.5f * u, 16.5f * u)
                    lineTo(8f * u, 19.5f * u)
                    lineTo(10.5f * u, 16.5f * u)
                }
                line(16f, 19.5f, 16f, 4.5f)
                path {
                    moveTo(13.5f * u, 7.5f * u)
                    lineTo(16f * u, 4.5f * u)
                    lineTo(18.5f * u, 7.5f * u)
                }
            }

            FnIcon.Output -> {
                rect(3f, 4.5f, 18f, 11.5f, r = 2f)
                line(12f, 16f, 12f, 19.5f)
                line(8f, 19.5f, 16f, 19.5f)
            }

            FnIcon.Trash -> {
                line(4.5f, 7f, 19.5f, 7f)
                line(9.5f, 4.5f, 14.5f, 4.5f)
                path {
                    moveTo(6.5f * u, 7f * u)
                    lineTo(7.4f * u, 19.5f * u)
                    lineTo(16.6f * u, 19.5f * u)
                    lineTo(17.5f * u, 7f * u)
                }
            }

            FnIcon.Power -> {
                arc(12f, 12f, 8f, -62f, 304f)
                line(12f, 3.5f, 12f, 11.5f)
            }

            FnIcon.FolderOff -> {
                path {
                    moveTo(3f * u, 8.5f * u)
                    lineTo(9f * u, 8.5f * u)
                    lineTo(11f * u, 11f * u)
                    lineTo(21f * u, 11f * u)
                    lineTo(21f * u, 19f * u)
                    lineTo(3f * u, 19f * u)
                    close()
                }
                line(10.5f, 15f, 16.5f, 15f)
            }

            FnIcon.Gauge -> {
                arc(12f, 14.5f, 8.5f, 156f, 228f)
                line(12f, 14.5f, 15.6f, 9.9f)
                dot(12f, 14.5f, 1.3f)
            }

            FnIcon.Level -> {
                line(7f, 19f, 7f, 11f)
                line(12f, 19f, 12f, 5f)
                line(17f, 19f, 17f, 14f)
            }

            FnIcon.Image -> {
                rect(3f, 4.5f, 18f, 15f, r = 2f)
                dot(16.5f, 9f, 1.4f)
                path {
                    moveTo(5.2f * u, 17.2f * u)
                    lineTo(9.5f * u, 11.5f * u)
                    lineTo(12.8f * u, 15.2f * u)
                    lineTo(15.3f * u, 13.2f * u)
                    lineTo(18.8f * u, 17.2f * u)
                }
            }

            FnIcon.Duplicate -> {
                rect(3.5f, 3.5f, 12f, 13f, r = 2.2f)
                rect(8.5f, 7.5f, 12f, 13f, r = 2.2f)
            }

            FnIcon.Contrast -> {
                ring(12f, 12f, 9f)
                drawArc(
                    color = tint,
                    startAngle = -90f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = pt(3f, 3f),
                    size = Size(18f * u, 18f * u),
                    style = Fill,
                )
            }

            FnIcon.Wave -> path {
                moveTo(3f * u, 18f * u)
                cubicTo(9f * u, 18f * u, 9f * u, 6f * u, 12f * u, 6f * u)
                cubicTo(15f * u, 6f * u, 15f * u, 18f * u, 21f * u, 18f * u)
            }

            FnIcon.Sliders -> {
                line(7f, 4f, 7f, 20f)
                line(12f, 4f, 12f, 20f)
                line(17f, 4f, 17f, 20f)
                dot(7f, 14f, 2.2f)
                dot(12f, 8.5f, 2.2f)
                dot(17f, 12f, 2.2f)
            }

            FnIcon.Lock -> {
                rect(5f, 11f, 14f, 9.5f, r = 2f)
                arc(12f, 11f, 4.2f, 180f, 180f)
                dot(12f, 15.5f, 1.5f)
            }

            FnIcon.Add -> {
                rect(4f, 4f, 16f, 16f, r = 4f)
                line(12f, 8.5f, 12f, 15.5f)
                line(8.5f, 12f, 15.5f, 12f)
            }

            // 齿轮：8 个梯形齿的**外轮廓一笔画出** + 中心圆。
            // 试过「圆环 + 放射短齿」，8 根等长辐条在放大稿上读成**船舵/太阳**；
            // 轮廓路径的齿是块状梯形，缩到 17dp 仍能看出齿形，且与其余线性图标同笔画。
            FnIcon.Settings -> {
                val teeth = 8
                val outerR = 9.6f
                val baseR = 6.6f
                val halfBase = 13f
                val halfTip = 8f
                val pts = ArrayList<Offset>(teeth * 4)
                for (i in 0 until teeth) {
                    val a0 = i * 360f / teeth
                    listOf(
                        (a0 - halfBase) to baseR, (a0 - halfTip) to outerR,
                        (a0 + halfTip) to outerR, (a0 + halfBase) to baseR,
                    ).forEach { (deg, rad) ->
                        val r = Math.toRadians(deg.toDouble())
                        pts += Offset(
                            12f + kotlin.math.cos(r).toFloat() * rad,
                            12f + kotlin.math.sin(r).toFloat() * rad,
                        )
                    }
                }
                drawPath(
                    path = build {
                        moveTo(pts[0].x * u, pts[0].y * u)
                        for (k in 1 until pts.size) lineTo(pts[k].x * u, pts[k].y * u)
                        close()
                    },
                    color = tint,
                    style = s,
                )
                ring(12f, 12f, 3.2f)
            }

            FnIcon.Queue -> {
                line(4.5f, 7f, 19.5f, 7f)
                line(4.5f, 12f, 19.5f, 12f)
                line(4.5f, 17f, 14f, 17f)
            }
        }
    }
}
