package com.example.cloudmusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cloudmusic.model.Track

/**
 * 飞牛音乐风格「音乐库」页（基准 `161526_feiniu.png`；辨别清单
 * `analysis/l3-reference/feiniu-ui-adaptation-spec.md`）。
 *
 * §61.188（用户「中间的音乐库和飞牛的对吗」——对齐基准重排）：
 * - 基准形态 = **纵向行式分组卡**（图标左 24dp 红色线性图标 + 15sp 标签右 + 行间分隔线），
 *   不是横向图标瓦片——本批把横向字形瓦片（♫♬▣♯）重排为基准形态；
 * - 顶栏 = 居中加粗「音乐库」+ 右上搜索圆钮（飞牛口径），替换左对齐大标题 + ⌕ 字形；
 * - 图标 = Canvas 红色线性绘制（飞牛无导出素材，按基准截图形状自绘，不造位图）；
 * - **辨别铁律**：不画返回箭头（独立 App 无宿主壳层）；「已下载」无对应实现，剔除不造。
 */
@Composable
fun FnLibraryScreen(
    tracks: List<Track>,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSources: () -> Unit,
) {
    // 分组视角：全部歌曲 / 歌手 / 专辑 / 风格
    val view = remember { mutableIntStateOf(0) }
    val views = listOf("全部歌曲", "歌手", "专辑", "风格")

    LazyColumn(Modifier.fillMaxSize().background(FnComp.PageBg)) {
        // ── 顶栏（基准 161526）：居中「音乐库」+ 右上搜索圆钮 ──────────────────
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(40.dp))
                Text(
                    "音乐库", color = FnComp.TextPrimary,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(FnComp.CardBg)
                        .clickable { onOpenSearch() },
                    contentAlignment = Alignment.Center,
                ) {
                    FnLineIcon(FnIcon.Search, FnComp.TextPrimary)
                }
            }
        }

        // ── 分组卡 1：全部歌曲 / 歌手 / 专辑 / 风格（基准纵向行 + 分隔线） ─────
        item {
            FnMenuCard {
                FnMenuRow(FnIcon.Songs, "全部歌曲", selected = view.intValue == 0) {
                    view.intValue = 0
                }
                FnMenuDivider()
                FnMenuRow(FnIcon.Artists, "全部歌手", selected = view.intValue == 1) {
                    view.intValue = 1
                }
                FnMenuDivider()
                FnMenuRow(FnIcon.Albums, "全部专辑", selected = view.intValue == 2) {
                    view.intValue = 2
                }
                FnMenuDivider()
                FnMenuRow(FnIcon.Styles, "全部风格", selected = view.intValue == 3) {
                    view.intValue = 3
                }
            }
        }

        // ── 分组卡 2：歌单 / 收藏 / 最近播放（「已下载」无对应实现，剔除不造） ──
        item {
            Spacer(Modifier.height(16.dp))
            FnMenuCard {
                FnMenuRow(FnIcon.Playlist, "歌单") { onOpenPlaylists() }
                FnMenuDivider()
                FnMenuRow(FnIcon.Favorite, "收藏") { onOpenFavorites() }
                FnMenuDivider()
                FnMenuRow(FnIcon.Recent, "最近播放") { onOpenHistory() }
            }
        }

        // ── 数据源入口（映射飞牛「服务器设置」——spec §2 #6） ──────────────────
        item {
            Spacer(Modifier.height(16.dp))
            FnMenuCard {
                FnMenuRow(FnIcon.Sources, "数据源管理") { onOpenSources() }
            }
        }

        // ── 选中视角的曲目列表 ────────────────────────────────────────────────
        item {
            Text(
                views[view.intValue], color = FnComp.TextPrimary,
                fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        if (tracks.isEmpty()) {
            item {
                Text(
                    "曲库为空：从「数据源管理」添加并扫描",
                    color = FnComp.TextHint, fontSize = 13.sp,
                    modifier = Modifier.padding(20.dp),
                )
            }
        } else {
            val shown = when (view.intValue) {
                1 -> tracks.groupBy { it.artist.ifBlank { "未知艺人" } }.map { it.value.first() }
                2 -> tracks.groupBy { it.album.ifBlank { "未归类" } }.map { it.value.first() }
                3 -> tracks.groupBy { it.sourceType.name }.map { it.value.first() } // 风格→按音源类型
                else -> tracks
            }
            items(shown, key = { it.id.value }) { track ->
                FnTrackRow(track) {
                    val idx = tracks.indexOfFirst { it.id == track.id }
                    onPlayTracks(tracks, idx)
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

/** 基准分组卡：#2B2B2E 圆角容器，行间 1dp 分隔线（161526 逐段对照）。 */
@Composable
private fun FnMenuCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(FnComp.CardBg)
            .padding(vertical = 6.dp, horizontal = 14.dp),
    ) { content() }
}

/** 基准行：24dp 红色线性图标 + 15sp 标签；选中态标签转品牌红。 */
@Composable
private fun FnMenuRow(
    icon: FnIcon,
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FnLineIcon(icon, FnComp.IconRed)
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            color = if (selected) FnComp.IconRed else FnComp.TextPrimary,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun FnMenuDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 40.dp)
            .height(1.dp)
            .background(FnComp.PageBg),
    )
}

/** 基准线性图标种别（飞牛无导出素材，按 `161526/161602` 截图形状自绘）。 */
enum class FnIcon { Songs, Artists, Albums, Styles, Playlist, Favorite, Recent, Sources, Search, Broadcast }

/**
 * 红色线性图标（stroke 2dp，24dp 视框）。形状按基准截图自绘：
 * 歌曲音符 / 歌手人形 / 专辑同心圆 / 风格四芒 / 歌单包 / 心形 / 时钟 / 数据源文件夹。
 */
@Composable
fun FnLineIcon(icon: FnIcon, tint: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(24.dp)) {
        val s = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        val w = 2.dp.toPx()
        val c = tint
        val cx = size.width / 2f
        val cy = size.height / 2f
        fun path(block: (Path.() -> Unit)) = Path().apply(block)
        when (icon) {
            FnIcon.Songs -> {
                // 音符：右杆 + 左下圆头 + 外圈（基准歌曲图标 = 圆圈内八分音符）
                drawCircle(c, radius = size.minDimension / 2f - 2.dp.toPx(), center = Offset(cx, cy), style = s)
                drawPath(path {
                    moveTo(cx + 4.dp.toPx(), cy - 7.dp.toPx())
                    lineTo(cx + 4.dp.toPx(), cy + 4.dp.toPx())
                }, c, style = s)
                drawCircle(c, radius = 2.6f.dp.toPx(), center = Offset(cx + 1.4f.dp.toPx(), cy + 4.6f.dp.toPx()), style = s)
            }
            FnIcon.Artists -> {
                // 歌手：头 + 肩弧
                drawCircle(c, radius = 4.dp.toPx(), center = Offset(cx, cy - 4.dp.toPx()), style = s)
                drawPath(path {
                    moveTo(cx - 8.dp.toPx(), cy + 8.dp.toPx())
                    quadraticBezierTo(cx, cy + 1.dp.toPx(), cx + 8.dp.toPx(), cy + 8.dp.toPx())
                }, c, style = s)
            }
            FnIcon.Albums -> {
                // 专辑：同心圆 + 中孔
                drawCircle(c, radius = size.minDimension / 2f - 2.dp.toPx(), center = Offset(cx, cy), style = s)
                drawCircle(c, radius = 6.dp.toPx(), center = Offset(cx, cy), style = s)
                drawCircle(c, radius = 1.6f.dp.toPx(), center = Offset(cx, cy))
            }
            FnIcon.Styles -> {
                // 风格：四芒星（两线交叉 + 短斜叉）
                drawLine(c, Offset(cx, cy - 9.dp.toPx()), Offset(cx, cy + 9.dp.toPx()), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(c, Offset(cx - 9.dp.toPx(), cy), Offset(cx + 9.dp.toPx(), cy), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(c, Offset(cx - 5.dp.toPx(), cy - 5.dp.toPx()), Offset(cx + 5.dp.toPx(), cy + 5.dp.toPx()), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(c, Offset(cx + 5.dp.toPx(), cy - 5.dp.toPx()), Offset(cx - 5.dp.toPx(), cy + 5.dp.toPx()), strokeWidth = w, cap = StrokeCap.Round)
            }
            FnIcon.Playlist -> {
                // 歌单：圆角包形（基准歌单图标 = 提包）
                drawPath(path {
                    moveTo(cx - 7.dp.toPx(), cy - 3.dp.toPx())
                    lineTo(cx - 7.dp.toPx(), cy + 7.dp.toPx())
                    lineTo(cx + 7.dp.toPx(), cy + 7.dp.toPx())
                    lineTo(cx + 7.dp.toPx(), cy - 3.dp.toPx())
                    close()
                }, c, style = s)
                drawPath(path {
                    moveTo(cx - 4.dp.toPx(), cy - 3.dp.toPx())
                    lineTo(cx - 4.dp.toPx(), cy - 7.dp.toPx())
                    lineTo(cx + 4.dp.toPx(), cy - 7.dp.toPx())
                    lineTo(cx + 4.dp.toPx(), cy - 3.dp.toPx())
                }, c, style = s)
            }
            FnIcon.Favorite -> {
                // 心形（双 cubic）
                drawPath(path {
                    moveTo(cx, cy + 8.dp.toPx())
                    cubicTo(cx - 11.dp.toPx(), cy - 1.dp.toPx(), cx - 6.dp.toPx(), cy - 9.dp.toPx(), cx, cy - 3.dp.toPx())
                    cubicTo(cx + 6.dp.toPx(), cy - 9.dp.toPx(), cx + 11.dp.toPx(), cy - 1.dp.toPx(), cx, cy + 8.dp.toPx())
                }, c, style = s)
            }
            FnIcon.Recent -> {
                // 时钟：圆 + 指针
                drawCircle(c, radius = size.minDimension / 2f - 2.dp.toPx(), center = Offset(cx, cy), style = s)
                drawLine(c, Offset(cx, cy), Offset(cx, cy - 5.dp.toPx()), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(c, Offset(cx, cy), Offset(cx + 4.dp.toPx(), cy + 2.dp.toPx()), strokeWidth = w, cap = StrokeCap.Round)
            }
            FnIcon.Sources -> {
                // 数据源：文件夹
                drawPath(path {
                    moveTo(cx - 9.dp.toPx(), cy - 6.dp.toPx())
                    lineTo(cx - 3.dp.toPx(), cy - 6.dp.toPx())
                    lineTo(cx - 1.dp.toPx(), cy - 3.dp.toPx())
                    lineTo(cx + 9.dp.toPx(), cy - 3.dp.toPx())
                    lineTo(cx + 9.dp.toPx(), cy + 6.dp.toPx())
                    lineTo(cx - 9.dp.toPx(), cy + 6.dp.toPx())
                    close()
                }, c, style = s)
            }
            FnIcon.Search -> {
                // 搜索：圆 + 柄
                drawCircle(c, radius = 6.dp.toPx(), center = Offset(cx - 1.dp.toPx(), cy - 1.dp.toPx()), style = s)
                drawLine(c, Offset(cx + 3.5f.dp.toPx(), cy + 3.5f.dp.toPx()), Offset(cx + 8.dp.toPx(), cy + 8.dp.toPx()), strokeWidth = w, cap = StrokeCap.Round)
            }
            FnIcon.Broadcast -> {
                // 漫游：中点 + 两侧弧（基准「((•))」广播形态）
                drawCircle(c, radius = 2.4f.dp.toPx(), center = Offset(cx, cy))
                drawArc(
                    c, startAngle = -50f, sweepAngle = 100f,
                    useCenter = false, style = s,
                    topLeft = Offset(cx - 6.dp.toPx(), cy - 6.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(12.dp.toPx(), 12.dp.toPx()),
                )
                drawArc(
                    c, startAngle = -50f, sweepAngle = 100f,
                    useCenter = false, style = s,
                    topLeft = Offset(cx - 9.5f.dp.toPx(), cy - 9.5f.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(19.dp.toPx(), 19.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun FnTrackRow(track: Track, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cover = rememberCompCover(track.coverRef)
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(FnComp.CardBg),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                androidx.compose.foundation.Image(
                    bitmap = cover.asImageBitmap(), contentDescription = track.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                Text("♫", color = FnComp.TextHint, fontSize = 20.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = FnComp.TextPrimary, fontSize = 15.sp, maxLines = 1)
            Text(
                "${track.artist} · ${track.album}", color = FnComp.TextSecondary,
                fontSize = 11.sp, maxLines = 1,
            )
        }
    }
}
