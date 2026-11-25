package com.ccmsc.cloudmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.model.TrackInfo
import com.ccmsc.cloudmusic.model.recentlyAdded

/**
 * 飞牛音乐风格首页（docs/01 §0 拍板；基准 `161602_feiniu.png`，辨别清单
 * `analysis/l3-reference/feiniu-ui-adaptation-spec.md`）。
 *
 * 一手量化（真机 1440×3200）：背景 `#1E1E21`；横向大卡片流（漫游橙 `#FD7423` / 收藏 /
 * 最近）；空态插画「暂无最近添加数据」；底部悬浮胶囊三 tab（由外层 Scaffold 提供）。
 *
 * **辨别铁律**：飞牛是 NAS App 二级入口——顶部**返回箭头**是宿主壳层，**我们不画**
 * （首页是根级 tab，无上层可返回）；卡片流用本地曲库数据填充，漫游=随机播放、
 * 收藏=FavoriteRepo、最近=HistoryRepo，**不造** NAS 社交/云数据。
 */
@Composable
fun HomeScreen(
    tracks: List<Track>,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onOpenSources: () -> Unit,
    /**
     * §61.175：收藏 / 最近卡片详情入口（原实现错导向数据源管理，属死链）。
     *
     * §61.280（V0.4-07 ③）：**语义分流**——参数 `recent` 指出该卡该落到我的音乐的哪一段
     * （false = 收藏段 / true = 最近播放段）。此前两张卡都调 `tab = 5` 落到同屏顶部，
     * 「最近」卡点进去看到的是收藏段，语义不对。
     */
    onOpenMine: (recent: Boolean) -> Unit = {},
    favoriteCount: Int = 0,
    recentCount: Int = 0,
) {
    // §61.321（R3）：最近添加列表 + 序号索引都按曲库变化算一次。
    // 此前 `tracks.recentlyAdded()` 直接写在 LazyListScope 里（每次重组重排全表），
    // 行点击时再 `indexOfFirst` 全表扫一遍。
    val recent = remember(tracks) { tracks.recentlyAdded() }
    val indexById = remember(tracks) { tracks.withIndex().associate { (i, t) -> t.id to i } }
    LazyColumn(
        Modifier.fillMaxSize().background(FnComp.PageBg),
    ) {
        // ── 横向大卡片流（§61.261：底栏已有搜索 tab，首页不再重复放搜索钮）──
        item {
            // §61.365：三张 160dp 卡片 + 2×14dp 间距 = 548dp，屏宽只有 411dp ⇒ 第三张被
            // 右边缘硬切掉三分之一，用户读作「渲染 bug」。不动卡片设计，只在最外层叠一条
            // 占宽 15% 的右缘渐隐（固定，不随内容滚动）：被切的部分淡出成「还能右滑」。
            Box {
                Row(
                    Modifier.horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    RoamCard(count = tracks.size) {
                        if (tracks.isNotEmpty()) {
                            onPlayTracks(tracks.shuffled(), 0)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    CollectCard(count = favoriteCount, onClick = { onOpenMine(false) })
                    Spacer(Modifier.width(14.dp))
                    RecentCard(count = recentCount, onClick = { onOpenMine(true) })
                    Spacer(Modifier.width(20.dp))
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                0.85f to Color.Transparent,
                                1f to FnComp.PageBg,
                            ),
                        ),
                )
            }
        }

        // ── 最近添加（本地曲库；空则插画占位，不造数据） ──
        item {
            Text(
                "最近添加", color = FnComp.TextPrimary,
                fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
        if (tracks.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("♫", color = FnComp.TextHint, fontSize = 64.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "暂无最近添加数据", color = FnComp.TextHint, fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "从「音乐库 → 来源」添加音乐来源并扫描",
                        color = FnComp.TextHint, fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp).clickable { onOpenSources() },
                    )
                }
            }
        } else {
            items(recent, key = { it.id.value }) { track ->
                HomeTrackRow(track) {
                    onPlayTracks(tracks, indexById[track.id] ?: 0)
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

/**
 * 基准卡片底：160dp 圆角 16。三卡配色/艺术按 `161602_feiniu.png` 采样自绘
 * （漫游=米白底+红橙 blob+黑胶角；收藏=橙渐变+大心；最近=深绿+时钟），
 * 左下 = 白色线性图标 + 粗体标签 + 小字副题（spec §3.1 本地化：显示曲目/收藏/最近数）。
 */
@Composable
private fun FeatureCard(
    title: String,
    subtitle: String,
    background: androidx.compose.ui.graphics.Brush,
    icon: FnIcon,
    art: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width = 160.dp, height = 160.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable { onClick() },
    ) {
        art?.invoke(this)
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FnLineIcon(icon, Color.White)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 漫游卡：米白底 + 红橙艺术 blob + 右下黑胶盘角（基准 161602 漫游卡形态）。 */
@Composable
private fun RoamCard(count: Int, onClick: () -> Unit) {
    FeatureCard(
        title = "漫游",
        subtitle = "随机播放 · $count 首",
        background = androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(Color(0xFFD9D0C5), Color(0xFFC9BFB2)),
        ),
        icon = FnIcon.Broadcast,
        onClick = onClick,
        art = {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(120.dp, 110.dp),
            ) {
                // 红橙 blob（两圆叠置的柔和艺术块）
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .size(96.dp, 92.dp)
                        .clip(RoundedCornerShape(40.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFF4522B), Color(0xFFE23E6E)),
                            ),
                        ),
                )
                // 黑胶盘角（右下露边）
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF181818)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE23E3E)),
                    )
                }
            }
        },
    )
}

/** 收藏卡：橙渐变 + 大心（基准 161602 收藏卡）。 */
@Composable
private fun CollectCard(count: Int, onClick: () -> Unit) {
    FeatureCard(
        title = "收藏",
        subtitle = "$count 首",
        background = androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(Color(0xFFF7A73B), Color(0xFFFD7423)),
        ),
        icon = FnIcon.Favorite,
        onClick = onClick,
        art = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FnLineIcon(FnIcon.Favorite, Color(0xFFFFD2BF).copy(alpha = 0.9f))
            }
        },
    )
}

/** 最近卡：深绿 + 时钟（基准 161602 最近卡）。 */
@Composable
private fun RecentCard(count: Int, onClick: () -> Unit) {
    FeatureCard(
        title = "最近",
        subtitle = "$count 首",
        background = androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(Color(0xFF245B49), Color(0xFF1B4034)),
        ),
        icon = FnIcon.Recent,
        onClick = onClick,
        art = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FnLineIcon(FnIcon.Recent, Color(0xFFDDE7E2).copy(alpha = 0.55f))
            }
        },
    )
}

@Composable
private fun HomeTrackRow(track: Track, onClick: () -> Unit) {
    // §61.276（V0.4-03 B09）：不可用曲目灰显但仍可见，点击只提示原因。
    val context = LocalContext.current
    val unavailable = track.availability != com.ccmsc.cloudmusic.model.Availability.OK
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (unavailable) 0.45f else 1f)
            .clickable {
                if (unavailable) {
                    context.showFeedback(TrackInfo.availabilityLabel(track))
                } else {
                    onClick()
                }
            }
            .padding(horizontal = 20.dp, vertical = 10.dp),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    track.title, color = FnComp.TextPrimary, fontSize = 15.sp,
                    maxLines = 1, modifier = Modifier.weight(1f, fill = false),
                )
                if (unavailable) {
                    Spacer(Modifier.width(6.dp))
                    FnUnavailableBadge()
                }
            }
            // §61.275（显示契约）：已知字段才显示——缺专辑不再出现「艺人 · 」空段 /
            // 「未知」噪声（远程曲目常态）。
            val subtitle = TrackInfo.listSubtitle(track)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle, color = FnComp.TextSecondary,
                    fontSize = 11.sp, maxLines = 1,
                )
            }
        }
    }
}
