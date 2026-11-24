package com.example.cloudmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cloudmusic.model.Availability
import com.example.cloudmusic.model.SourceType
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.model.TrackId
import com.example.cloudmusic.model.TrackInfo

/**
 * 飞牛音乐风格「音乐库」页（基准 `161526_feiniu.png`；辨别清单
 * `analysis/l3-reference/feiniu-ui-adaptation-spec.md`）。
 *
 * §61.188（用户「中间的音乐库和飞牛的对吗」——对齐基准重排）：
 * - 基准形态 = 纵向行式分组卡（图标左 + 标签右 + 行间分隔线）；顶栏居中标题 + 右上圆钮；
 * - 图标 = Canvas 红色线性绘制（飞牛无导出素材，按基准截图形状自绘，不造位图）；
 *   图标本体自 §61.278 起统一收在 `FnIcons.kt`（唯一图标真相源，见 03-10 §8 映射表）；
 * - **辨别铁律**：不画返回箭头（独立 App 无宿主壳层）；「已下载」无对应实现，剔除不造。
 *
 * **§61.274（V0.4-01 入口合并，拍板 §61.272 #4）**：本页吸收原 `tab7` 曲库管理
 * （`LibraryScreen`）的全部功能——「＋」入歌单、不可用视图 + 重试、重扫入口——
 * 全 App 只保留本页一个曲库入口（「更多」面板的「曲库」行与 tab7 分支已删除）。
 *
 * 视图组 = 全部歌曲 / 歌手 / 专辑 / **音源** / 不可用：
 * - 原「风格」视图按 `sourceType` 伪分组（曲库无 genre 字段，扫描侧读出也未入库），
 *   名不副实；本批改名为**音源**，标签与真实数据一致（新增 genre 列需 DB 迁移，另立批）；
 * - 歌手/专辑/音源视图原实现只显示每组**首条**（用户看不见组内其余曲目 → 视为丢歌），
 *   改为「计数行 + 展开/收起」，展开后行内仍带「＋」。
 */
@Composable
fun FnLibraryScreen(
    tracks: List<Track>,
    onPlayTracks: (List<Track>, Int) -> Unit,
    /** §61.119（M3）：行尾「＋」→ 加入歌单面板（`AddToPlaylistSheet` 接线不变）。 */
    onAddToPlaylist: (Track) -> Unit,
    /** 全库重扫（原 tab7 顶栏「↻」）。 */
    onRescan: () -> Unit,
    /**
     * §61.285（U-13 拍板 ②）：扫描进行中 → 重扫钮显示进度环并禁用点击。
     * 此前点重扫**零可见反馈**（§61.188 移除扫描横幅后），大库远程扫描几十秒
     * 用户不知道是否生效，只能连点（而 `runScan` 是 last-wins：连点会取消重启）。
     */
    scanning: Boolean = false,
    /** 不可用视图的「重试」动作（原 tab7 UNAVAILABLE 视图按钮）。 */
    onRetryUnavailable: () -> Unit,
    /** §61.327（A-7）：当前播放曲目 id——曲目行据此点亮「正在播放」。 */
    nowPlayingId: TrackId? = null,
) {
    var viewIndex by rememberSaveable { mutableIntStateOf(0) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    val views = LibraryView.entries
    val current = views[viewIndex.coerceIn(0, views.lastIndex)]

    // §61.289：分组与不可用筛选移出 `LazyListScope` 并记忆化。
    // 它们此前写在 content lambda 里 ⇒ 每次列表重建（切视图 / 展开分组 / 曲库更新）
    // 都全量扫一遍 7,500 首；现在只在 tracks 或视图变化时重算。
    val groups = remember(tracks, current) { libraryGroups(tracks, current) }
    val badTracks = remember(tracks) { unavailableTracks(tracks) }

    LazyColumn(Modifier.fillMaxSize().background(FnComp.PageBg)) {
        // ── 顶栏（基准 161526）：居中「音乐库」+ 右上重扫 / 搜索圆钮 ────────────
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    // §61.287：40dp 是下限不是硬高——此容器装 20sp 标题，
                    // 系统字号放大后行随之长高，不裁标题。
                    .heightIn(min = FnBaseline.HeaderButtonDp.dp),
            ) {
                Text(
                    "音乐库", color = FnComp.TextPrimary,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center),
                )
                Row(
                    Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FnHeaderButton(FnIcon.Refresh, onRescan, busy = scanning)
                }
            }
        }

        // ── 视图切换（全部歌曲 / 歌手 / 专辑 / 音源 / 不可用） ──────────────────
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                views.forEachIndexed { i, v ->
                    FnViewChip(v.label, v.icon, i == viewIndex) { viewIndex = i }
                }
            }
        }

        // ── 当前视图 ──────────────────────────────────────────────────────────
        if (tracks.isEmpty()) {
            item {
                Text(
                    "曲库为空：从「来源」添加音乐来源并扫描",
                    color = FnComp.TextHint, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
        } else {
            when (current) {
                LibraryView.ALL -> {
                    item { FnCountLine("共 ${tracks.size} 首") }
                    items(tracks, key = { it.id.value }) { track ->
                        FnTrackLine(
                            track = track,
                            onPlay = {
                                val idx = tracks.indexOfFirst { it.id == track.id }
                                onPlayTracks(tracks, idx)
                            },
                            onAdd = { onAddToPlaylist(track) },
                            nowPlayingId = nowPlayingId,
                        )
                    }
                }

                LibraryView.ARTIST, LibraryView.ALBUM, LibraryView.SOURCE -> {
                    // §61.289：groups 已在外层 remember（见 composable 顶部）。
                    item {
                        FnCountLine("${groups.size} 个分组 · ${tracks.size} 首")
                    }
                    groups.forEach { (name, groupTracks) ->
                        item(key = "group-" + name) {
                            val open = name in expanded
                            FnGroupHeader(
                                name = name,
                                count = groupTracks.size,
                                open = open,
                            ) {
                                expanded = if (open) expanded - name else expanded + name
                            }
                        }
                        if (name in expanded) {
                            items(groupTracks, key = { "t-" + name + "-" + it.id.value }) { track ->
                                FnTrackLine(
                                    track = track,
                                    onPlay = {
                                        val idx = groupTracks.indexOfFirst { it.id == track.id }
                                        onPlayTracks(groupTracks, idx)
                                    },
                                    onAdd = { onAddToPlaylist(track) },
                                    nowPlayingId = nowPlayingId,
                                )
                            }
                        }
                    }
                }

                LibraryView.UNAVAILABLE -> {
                    // §61.289：badTracks 已在外层 remember（见 composable 顶部）。
                    item { FnCountLine("${badTracks.size} 首不可用（仍保留在曲库，恢复后自动转可用）") }
                    if (badTracks.isEmpty()) {
                        item {
                            Text(
                                "没有不可用曲目",
                                color = FnComp.TextHint, fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                            )
                        }
                    } else {
                        item {
                            FnActionButton(
                                text = "重试不可用曲目",
                                onClick = onRetryUnavailable,
                                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp),
                            )
                        }
                        items(badTracks, key = { it.id.value }) { track ->
                            FnTrackLine(
                                track = track,
                                onPlay = {}, // §61.276：点击由行内拦截，只提示原因
                                subtitle = unavailableSubtitle(track),
                                nowPlayingId = nowPlayingId,
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * 视图组（原 `CloudMusicApp.LibraryView` 迁入；tab7 已废弃）。
 *
 * §61.289：`private` → `internal`——分组/不可用视图的取数逻辑抽成纯函数后要能被 JVM 测试断言。
 */
internal enum class LibraryView(val label: String, val icon: FnIcon) {
    ALL("全部歌曲", FnIcon.Songs),
    ARTIST("歌手", FnIcon.Artists),
    ALBUM("专辑", FnIcon.Albums),
    SOURCE("音源", FnIcon.Sources),
    UNAVAILABLE("不可用", FnIcon.Unavailable),
}

/** 按标签分组并按名称排序（空标签已由调用方兜底，不产生空分组）。 */
private fun groupByLabel(
    tracks: List<Track>,
    key: (Track) -> String,
): List<Pair<String, List<Track>>> =
    tracks.groupBy(key).toList().sortedBy { it.first }

/**
 * §61.289：某视图下的分组结果（纯函数，便于单测；调用方必须 `remember` 起来）。
 *
 * 此前这段 `when` 直接写在 `LazyListScope` 的 content lambda 里——它是 LazyColumn 的
 * item 构建器，**每次列表重建都会重跑**（切视图、展开/收起分组、曲库更新）。
 * 7,500 首时一次 `groupBy + sortedBy` 是几十毫秒级，展开分组会明显掉帧。
 *
 * 排序语义保持原样：先按组名升序；「音源」视图再按组内曲目数降序（稳定排序，
 * 数量相同仍按名称序）。
 */
internal fun libraryGroups(
    tracks: List<Track>,
    view: LibraryView,
): List<Pair<String, List<Track>>> = when (view) {
    // §61.275：「未知」字样不进筛选/分组标题（扫描器兜底值也算缺失）
    LibraryView.ARTIST -> groupByLabel(tracks) {
        it.artist.takeIf { v -> TrackInfo.isKnown(v) } ?: "未标注艺人"
    }
    LibraryView.ALBUM -> groupByLabel(tracks) {
        it.album.takeIf { v -> TrackInfo.isKnown(v) } ?: "未归类专辑"
    }
    LibraryView.SOURCE -> groupByLabel(tracks) { it.sourceType.shortLabel() }
        .sortedByDescending { it.second.size }
    LibraryView.ALL, LibraryView.UNAVAILABLE -> emptyList()
}

/** §61.289：不可用视图的取数（纯函数；同样要被 `remember` 起来，别在 LazyListScope 里跑）。 */
internal fun unavailableTracks(tracks: List<Track>): List<Track> =
    tracks.filter { it.availability != Availability.OK }

/** 音源短标签：列表分组用（`TrackInfo.sourceLabel` 是详情口径，带「直链 / 预留」后缀）。 */
private fun SourceType.shortLabel(): String = when (this) {
    SourceType.LOCAL -> "本机"
    SourceType.WEBDAV -> "WebDAV"
    SourceType.STRM -> "STRM"
    SourceType.PLAYLIST -> "播放列表"
    SourceType.SFTP -> "SFTP"
    SourceType.FTP -> "FTP"
    SourceType.NFS -> "NFS"
}

/**
 * 不可用原因（人话）：枚举名不进 UI。
 *
 * `OK` 返回 null —— 调用方只在 `availability != OK` 时取用，防御性返回空串避免 null 泄漏。
 */
private fun unavailableReason(a: Availability): String = when (a) {
    Availability.OK -> ""
    Availability.UNREACHABLE -> "源暂不可达"
    Availability.LINK_EXPIRED -> "链接已失效"
    Availability.UNSUPPORTED_SCHEME -> "链接格式不支持"
}

private fun unavailableSubtitle(track: Track): String =
    listOfNotNull(
        unavailableReason(track.availability).ifBlank { null },
        track.sourceRef.path.ifBlank { null },
    ).joinToString(" · ")

/**
 * 顶栏圆钮（40dp，与飞牛页头同量值）。
 *
 * §61.285（U-13 拍板 ②）：[busy] 时圆钮内换成品牌橙进度环并**禁用点击**——
 * 禁用不只是防连点，也是 last-wins 语义的 UI 表达（重入会取消并重启当前扫描，
 * 用户看到的"重新开始"其实会丢掉已扫进度）。
 */
@Composable
private fun FnHeaderButton(icon: FnIcon, onClick: () -> Unit, busy: Boolean = false) {
    Box(
        Modifier
            .size(FnBaseline.HeaderButtonDp.dp)
            .clip(CircleShape)
            .background(FnComp.CardBg)
            // §61.290：纯图标圆钮 + 扫描中换成进度环——两个状态都必须说得出口：
            // 描述给「这个钮是什么」，stateDescription 给「现在在做什么」。
            .semantics {
                contentDescription = icon.label
                if (busy) {
                    stateDescription = "正在扫描"
                    disabled()
                }
            }
            .clickable(enabled = !busy) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = FnComp.IconRed,
                strokeWidth = 2.dp,
            )
        } else {
            FnLineIcon(icon, FnComp.TextPrimary)
        }
    }
}

/** 视图胶囊（选中 = 品牌红字 + 红 16% 底，未选中 = 卡片底灰白字）。 */
@Composable
private fun FnViewChip(
    label: String,
    icon: FnIcon,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) FnComp.IconRed else FnComp.TextSecondary
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) FnComp.IconRed.copy(alpha = 0.16f) else FnComp.CardBg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // §61.278：视图 chip 带图 —— 五个视图（歌/人/碟/源/禁用）一眼可分，
        // 也避免「全部歌曲」与「专辑」在纯文字下要靠读字才分得清。
        FnLineIcon(icon, tint, size = 14.dp)
        Text(label, color = tint, fontSize = 13.sp, maxLines = 1)
    }
}

/** 列表上方计数行（原 tab7 的分组副标题口径，改成真实计数）。 */
@Composable
private fun FnCountLine(text: String) {
    Text(
        text,
        color = FnComp.TextHint,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

/** 分组头：名称 + 首数 + 展开箭头，整行可点（修「只显示每组首条」的丢歌语义）。 */
@Composable
private fun FnGroupHeader(name: String, count: Int, open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            color = FnComp.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("$count 首", color = FnComp.TextHint, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text(if (open) "▾" else "▸", color = FnComp.TextSecondary, fontSize = 12.sp)
    }
}

/** 曲目行：共享 [FnTrackRow] + 行尾「＋」（入歌单）。 */
@Composable
private fun FnTrackLine(
    track: Track,
    onPlay: () -> Unit,
    onAdd: (() -> Unit)? = null,
    // §61.275：副题走 TrackInfo 单一真相源（已知字段才显示，占位值不渲染）
    subtitle: String = TrackInfo.listSubtitle(track),
    /** §61.327（A-7）：当前播放曲目 id；命中即点亮（判断只在 [FnTrackRow] 里做一次）。 */
    nowPlayingId: TrackId? = null,
) {
    val context = LocalContext.current
    val unavailable = track.availability != Availability.OK
    FnTrackRow(
        title = track.title,
        subtitle = subtitle,
        // §61.276（V0.4-03 B09）：不可用曲目仍可见/可搜索/可保留，点击只给原因，
        // 不发起播放、不弹网络错误。
        onClick = {
            if (unavailable) {
                context.showFeedback(TrackInfo.availabilityLabel(track))
            } else {
                onPlay()
            }
        },
        coverRef = track.coverRef,
        dimmed = unavailable,
        badge = if (unavailable) "暂不可用" else null,
        playing = track.id == nowPlayingId,
        trailing = {
            if (onAdd != null) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onAdd() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("＋", color = FnComp.TextSecondary, fontSize = 18.sp)
                }
            }
        },
    )
}
