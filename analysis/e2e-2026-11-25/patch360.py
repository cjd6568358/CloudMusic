# -*- coding: utf-8 -*-
"""§61.360: 启动页期间状态栏/导航栏刷品牌红。"""

p = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/SplashScreen.kt'
s = open(p, encoding='utf-8').read()

# 1. imports
old_imp = '''import androidx.compose.foundation.Canvas'''
new_imp = '''import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas'''
assert s.count(old_imp) == 1
s = s.replace(old_imp, new_imp)

old_imp2 = '''import androidx.compose.runtime.Composable'''
new_imp2 = '''import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect'''
assert s.count(old_imp2) == 1
s = s.replace(old_imp2, new_imp2)

old_imp3 = '''import androidx.compose.ui.graphics.Color'''
new_imp3 = '''import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView'''
assert s.count(old_imp3) == 1
s = s.replace(old_imp3, new_imp3)

old_imp4 = '''import androidx.compose.ui.unit.sp
import com.ccmsc.cloudmusic.ui.player.LegacyPalette'''
new_imp4 = '''import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.ccmsc.cloudmusic.ui.player.LegacyPalette'''
assert s.count(old_imp4) == 1
s = s.replace(old_imp4, new_imp4)

# 2. KDoc update
old_doc = ''' * 保留项：背景仍是 §61.165 从竞品启动屏一手采样的两色垂直渐变（上 #A22016 / 下 #AE2318），
 * 这是纯色渐变、无品牌识别成分，且 `tools/l3_visual_replica_spec.py` 仍钉死 `BrandRed`
 * 色值以守护播放页红色体系的一致性。'''
new_doc = ''' * §61.359（2026-11-25 用户拍板「统一」）：背景由竞品采样 `#A22016` / `#AE2318` 统一到
 * 品牌红 `#E54545`（与图标底、播放页强调色同值），`tools/l3_visual_replica_spec.py` 的
 * `BrandRed` 期望值同步；渐变两色同值 ⇒ 视觉上是纯色。
 *
 * §61.360（2026-11-25）：启动页期间把窗口**状态栏 / 导航栏**也刷成品牌红 + 白色图标。
 * 此前两栏由 `themes.xml` 写死黑色，红屏上下各压一条黑边（真机 `52-small.png` 实证）。
 * 这里刻意不做「保存-还原」配对——退出后 `CloudMusicApp` 的 `SystemBars.apply` 会按
 * 当前主题重算目标态（§61.291 的声明式口径），配对反而多一条可能漏掉的还原路径。'''
assert s.count(old_doc) == 1
s = s.replace(old_doc, new_doc)

# 3. body: set window bar colors while splash is visible
old_body = '''@Composable
fun SplashScreen(onFinish: () -> Unit) {
    Box('''
new_body = '''@Composable
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
    Box('''
assert s.count(old_body) == 1
s = s.replace(old_body, new_body)

# 4. helper at end
s = s.rstrip('\n') + '''

/**
 * §61.360：Compose 的 LocalView.context 在部分容器下是 ContextWrapper 而非 Activity，
 * 逐层解包拿真正的 Activity（拿不到就返回 null，调用方跳过栏色设置）。
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
'''
open(p, 'w', encoding='utf-8', newline='').write(s)
print('splash patched, CR=%d LF=%d' % (s.count('\r'), s.count('\n')))
