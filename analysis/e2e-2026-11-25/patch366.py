# -*- coding: utf-8 -*-
"""§61.366：播放页空态（无当前曲目）时，进度条/时间/收藏降透明度，不再像可操作。"""
P = 'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/player/LegacyPlayerPage.kt'
s = open(P, encoding='utf-8').read()

# 1) 收藏按钮：空态包一层 alpha
old_fav = '''                // §61.130（M5）：收藏切换——FavoriteRepository.toggle 的生产入口。
                val isFav = track?.id in favoriteIds
                LegacyIconButton(
                    assetPath = LegacyAsset.Favorite,
                    description = if (isFav) "取消收藏" else "收藏",
                    sizeDp = LegacyMetrics.InfoRowIconDp,
                    onClick = { track?.id?.let { bridge.onToggleFavorite(it) } },
                )'''
new_fav = '''                // §61.130（M5）：收藏切换——FavoriteRepository.toggle 的生产入口。
                // §61.366：无当前曲目时降到 35% 透明——此前空态下心形照常高亮，
                // 点了没反应，读作「坏掉的按钮」。
                val isFav = track?.id in favoriteIds
                Box(Modifier.alpha(if (track != null) 1f else 0.35f)) {
                    LegacyIconButton(
                        assetPath = LegacyAsset.Favorite,
                        description = if (isFav) "取消收藏" else "收藏",
                        sizeDp = LegacyMetrics.InfoRowIconDp,
                        onClick = { track?.id?.let { bridge.onToggleFavorite(it) } },
                    )
                }'''
assert s.count(old_fav) == 1, ('fav', s.count(old_fav))
s = s.replace(old_fav, new_fav)

# 2) 进度条传 enabled
old_pb = '''            BaselineProgressBar(
                positionMs = displayPositionMs,
                durationMs = durationMs,
                onSeek = { bridge.onSeek(it) },
            )'''
new_pb = '''            BaselineProgressBar(
                positionMs = displayPositionMs,
                durationMs = durationMs,
                onSeek = { bridge.onSeek(it) },
                // §61.366：无曲目时轨道画成 35% 透明（00:00/00:00 但轨道全亮=死控件）
                enabled = track != null,
            )'''
assert s.count(old_pb) == 1, ('progress call', s.count(old_pb))
s = s.replace(old_pb, new_pb)

# 3) 签名 + alpha
old_sig = '''private fun BaselineProgressBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {'''
new_sig = '''private fun BaselineProgressBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    /** §61.366：false = 当前无曲目，整条画成 35% 透明（仍保留布局高度）。 */
    enabled: Boolean = true,
) {'''
assert s.count(old_sig) == 1, ('progress sig', s.count(old_sig))
s = s.replace(old_sig, new_sig)

old_canvas = '''    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PlayerBaseline.ProgressSidePaddingDp.dp)
            .height(PlayerBaseline.ProgressTouchDp.dp)'''
new_canvas = '''    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PlayerBaseline.ProgressSidePaddingDp.dp)
            .height(PlayerBaseline.ProgressTouchDp.dp)
            .alpha(if (enabled) 1f else 0.35f)'''
assert s.count(old_canvas) == 1, ('progress canvas', s.count(old_canvas))
s = s.replace(old_canvas, new_canvas)

# 4) import alpha
if 'import androidx.compose.ui.draw.alpha' not in s:
    s = s.replace('import androidx.compose.ui.draw.clip',
                  'import androidx.compose.ui.draw.alpha\nimport androidx.compose.ui.draw.clip', 1)

open(P, 'w', encoding='utf-8', newline='').write(s)
print('player empty-state dimming applied')
