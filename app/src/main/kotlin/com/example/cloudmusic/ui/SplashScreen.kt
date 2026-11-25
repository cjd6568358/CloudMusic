package com.example.cloudmusic.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.example.cloudmusic.ui.player.LegacyPalette

/**
 * 启动页（自有品牌版）。
 *
 * 来历：§61.165 曾按「从启动页起 1:1 复刻竞品」的口径做过一版竞品复刻——红底 +
 * 从基准截图裁出的白色音符 logo + 竞品标语。**§61.172（用户 2026-11-16 拍板）起改为自有品牌**：
 * - logo：不再使用任何竞品裁切图（原 `assets/launch/logo.png` 已删除），改为 Canvas 自绘的
 *   **五条圆角声波**（`BrandWaveMark`），纯代码绘制、零外部资源；
 * - 标语：「听见好声音」（替换原竞品标语）。
 *
 * §61.359（2026-11-25 用户拍板「统一」）：背景由竞品采样 `#A22016` / `#AE2318` 统一到
 * 品牌红 `#E54545`（与图标底、播放页强调色同值），`tools/l3_visual_replica_spec.py` 的
 * `BrandRed` 期望值同步；渐变两色同值 ⇒ 视觉上是纯色。
 *
 * §61.360（2026-11-25）：启动页期间把窗口**状态栏 / 导航栏**也刷成品牌红 + 白色图标。
 * 此前两栏由 `themes.xml` 写死黑色，红屏上下各压一条黑边（真机 `52-small.png` 实证）。
 * 这里刻意不做「保存-还原」配对——退出后 `CloudMusicApp` 的 `SystemBars.apply` 会按
 * 当前主题重算目标态（§61.291 的声明式口径），配对反而多一条可能漏掉的还原路径。
 *
 * 布局沿用原基准：标中心 y 上移约 40dp（基准 logo 中心 y≈43.9%），标语贴底
 * 96dp、居中、白色 Bold。
 *
 * 放在 MainActivity 的 CloudMusicTheme 内作覆盖层，1.2s 或点击后由调用方关闭；
 * 显示状态用 `rememberSaveable`（§61.171：用 remember 时旋转会重放）。
 */
@Composable
@Suppress("DEPRECATION") // statusBarColor/navigationBarColor 在 API 35 标记弃用；本工程仍 opt-out 边到边，继续生效
fun SplashScreen(onFinish: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            val red = LegacyPalette.BrandRed
            window.statusBarColor = red
            window.navigationBarColor = red
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            // 红底 ⇒ 两栏图标用浅色（isAppearanceLight* 的语义是「栏底为浅色」）
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
        onDispose { }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        // §61.359：统一到品牌红 #E54545（原 #A22016/#AE2318 是竞品采样值）。
                        Color(LegacyPalette.BrandRed),
                        Color(LegacyPalette.SplashRedBottom),
                    ),
                ),
            )
            .clickable { onFinish() },
        contentAlignment = Alignment.Center,
    ) {
        BrandWaveMark(
            modifier = Modifier
                .width(64.dp)
                .height(44.dp)
                .offset(y = (-40).dp),
        )
        Text(
            text = "听见好声音",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp)
                .fillMaxWidth(),
        )
    }
}

/**
 * 自有品牌标：五条高度递变的白色圆角声波（中间最高、两侧收敛），
 * 表达「听」而不借用任何竞品图形。
 *
 * 几何：条宽 = 条间距 = width / (2n - 1)，各条垂直居中，圆角取半条宽呈胶囊形。
 */
@Composable
private fun BrandWaveMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val ratios = listOf(0.42f, 0.72f, 1f, 0.72f, 0.42f)
        val slot = size.width / (ratios.size * 2f - 1f)
        ratios.forEachIndexed { index, ratio ->
            val barHeight = size.height * ratio
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(index * slot * 2f, (size.height - barHeight) / 2f),
                size = Size(slot, barHeight),
                cornerRadius = CornerRadius(slot / 2f, slot / 2f),
            )
        }
    }
}

/**
 * §61.360：Compose 的 LocalView.context 在部分容器下是 ContextWrapper 而非 Activity，
 * 逐层解包拿真正的 Activity（拿不到就返回 null，调用方跳过栏色设置）。
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
