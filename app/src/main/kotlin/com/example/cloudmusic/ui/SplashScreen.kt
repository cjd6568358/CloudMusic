package com.example.cloudmusic.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cloudmusic.ui.player.LegacyPalette

/**
 * 启动页（自有品牌版）。
 *
 * 来历：§61.165 曾按「从启动页起 1:1 复刻竞品」的口径做过一版竞品复刻——红底 +
 * 从基准截图裁出的白色音符 logo + 竞品标语。**§61.172（用户 2026-11-16 拍板）起改为自有品牌**：
 * - logo：不再使用任何竞品裁切图（原 `assets/launch/logo.png` 已删除），改为 Canvas 自绘的
 *   **五条圆角声波**（`BrandWaveMark`），纯代码绘制、零外部资源；
 * - 标语：「倾听好声音」（替换原竞品标语）。
 *
 * 保留项：背景仍是 §61.165 从竞品启动屏一手采样的两色垂直渐变（上 #A22016 / 下 #AE2318），
 * 这是纯色渐变、无品牌识别成分，且 `tools/l3_visual_replica_spec.py` 仍钉死 `BrandRed`
 * 色值以守护播放页红色体系的一致性。
 *
 * 布局沿用原基准：标中心 y 上移约 40dp（基准 logo 中心 y≈43.9%），标语贴底
 * 96dp、居中、白色 Bold。
 *
 * 放在 MainActivity 的 CloudMusicTheme 内作覆盖层，1.2s 或点击后由调用方关闭；
 * 显示状态用 `rememberSaveable`（§61.171：用 remember 时旋转会重放）。
 */
@Composable
fun SplashScreen(onFinish: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(LegacyPalette.BrandRed),          // #A22016 顶
                        Color(LegacyPalette.SplashRedBottom),   // #AE2318 底
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
            text = "倾听好声音",
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
