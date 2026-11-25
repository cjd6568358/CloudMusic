# -*- coding: utf-8 -*-
"""§61.363：FnLineIcon 优先走 Material Symbols 矢量（32 枚），手写实现保留兜底。"""

P = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/FnIcons.kt'
s = open(P, encoding='utf-8').read()

# 1) imports
s = s.replace('''import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size''',
'''import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size''')

s = s.replace('''import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path''',
'''import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path''')

s = s.replace('''import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics''',
'''import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics''')

s = s.replace('''import androidx.compose.ui.unit.dp
''', '''import androidx.compose.ui.unit.dp
import com.ccmsc.cloudmusic.R
''', 1)

# 2) 映射表 + 说明，插在 iconSemantics 之前
anchor = '''/**
 * 纯图标控件的语义挂载（§61.290）。'''
table = '''/**
 * §61.363：FnIcon → Material Symbols Rounded 官方矢量（Apache 2.0）。
 *
 * 为什么整体替换：手写 [FnLineIcon] 每枚图标是多次 `drawLine` / 多条 `path` 拼出来的，
 * `StrokeCap.Round` 在每段端头画半径 = 半笔宽的实心圆，T 形/折角搭接时两圆相叠 ⇒
 * 48dp 下肉眼可见「拼装件 + 毛刺」（用户 2026-11-25 一手指出）。官方图标是**单条闭合
 * fill path**，一次成形、没有接头，且比例/光学对齐都由 Google 调好。
 *
 * 二进制体积：32 个 vector 合计约 20KB（纯文本路径），远小于引 `material-icons-extended`
 * （几十 MB）。所有 path 由 `analysis/e2e-2026-11-25/gen_fn_icons.py` 从官方站点直接拉取，
 * 未经手工改写。
 */
private val fnIconDrawable: Map<FnIcon, Int> = mapOf(
    FnIcon.Home to R.drawable.ic_fn_home,
    FnIcon.Library to R.drawable.ic_fn_library,
    FnIcon.More to R.drawable.ic_fn_more,
    FnIcon.Songs to R.drawable.ic_fn_songs,
    FnIcon.Artists to R.drawable.ic_fn_artists,
    FnIcon.Albums to R.drawable.ic_fn_albums,
    FnIcon.Sources to R.drawable.ic_fn_sources,
    FnIcon.Unavailable to R.drawable.ic_fn_unavailable,
    FnIcon.Playlist to R.drawable.ic_fn_playlist,
    FnIcon.Favorite to R.drawable.ic_fn_favorite,
    FnIcon.Recent to R.drawable.ic_fn_recent,
    FnIcon.Search to R.drawable.ic_fn_search,
    FnIcon.Refresh to R.drawable.ic_fn_refresh,
    FnIcon.Broadcast to R.drawable.ic_fn_broadcast,
    FnIcon.Network to R.drawable.ic_fn_network,
    FnIcon.Output to R.drawable.ic_fn_output,
    FnIcon.Trash to R.drawable.ic_fn_trash,
    FnIcon.Power to R.drawable.ic_fn_power,
    FnIcon.FolderOff to R.drawable.ic_fn_folder_off,
    FnIcon.Gauge to R.drawable.ic_fn_gauge,
    FnIcon.Level to R.drawable.ic_fn_level,
    FnIcon.Image to R.drawable.ic_fn_image,
    FnIcon.Duplicate to R.drawable.ic_fn_duplicate,
    FnIcon.Contrast to R.drawable.ic_fn_contrast,
    FnIcon.Wave to R.drawable.ic_fn_wave,
    FnIcon.Sliders to R.drawable.ic_fn_sliders,
    FnIcon.Lock to R.drawable.ic_fn_lock,
    FnIcon.Add to R.drawable.ic_fn_add,
    FnIcon.Settings to R.drawable.ic_fn_settings,
    FnIcon.Queue to R.drawable.ic_fn_queue,
    FnIcon.Play to R.drawable.ic_fn_play,
    FnIcon.Pause to R.drawable.ic_fn_pause,
)

''' + anchor
assert s.count(anchor) == 1
s = s.replace(anchor, table)

# 3) FnLineIcon：先走矢量
old_head = '''    Canvas(modifier.size(size).iconSemantics(contentDescription)) {'''
new_head = '''    // §61.363：有官方矢量就用矢量（单条 fill path，无拼接毛刺）；
    // 映射不到时保留原来的 Canvas 手写实现兜底（新加 FnIcon 值不会立刻炸）。
    val vector = fnIconDrawable[icon]
    if (vector != null) {
        Image(
            painter = painterResource(vector),
            contentDescription = contentDescription,
            modifier = modifier.size(size),
            colorFilter = ColorFilter.tint(tint),
        )
        return
    }
    Canvas(modifier.size(size).iconSemantics(contentDescription)) {'''
assert s.count(old_head) == 1
s = s.replace(old_head, new_head)

open(P, 'w', encoding='utf-8', newline='').write(s)
print('FnIcons wired to vector drawables, CR=%d LF=%d' % (s.count('\r'), s.count('\n')))
