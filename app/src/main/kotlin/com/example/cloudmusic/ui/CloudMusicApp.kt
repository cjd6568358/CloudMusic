package com.example.cloudmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.cloudmusic.di.playback.RuntimeState
import com.example.cloudmusic.model.Storage
import com.example.cloudmusic.model.Track
import com.example.cloudmusic.output.router.ActiveOutput
import com.example.cloudmusic.output.router.OutputDeviceState
import com.example.cloudmusic.player.PlayerPhase
import com.example.cloudmusic.player.PlayerState
import com.example.cloudmusic.player.QueueItem
import com.example.cloudmusic.storage.Playlist
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * T-A6（`12 §33`）/ 第三十一批（`12 §40`）：V0.1 最小自研 UI。
 *
 * 页面（03-10 §5 的 V0.1 子集）：曲库 / 歌单 / 搜索 / 队列 / 设置 五页 + 数据源管理入口
 * + 输出设备弹窗。**播放页整页照搬属 T-L L3**，本文件只用 Compose 迷你播放条做控制占位，
 * 不重写唱片动画（禁止事项 1）。
 *
 * 边界（`03-10 §11`）：只经 Repository / StateFlow 取数，不碰 Driver / Room / OkHttp；
 * 蓝牙只作状态提示、**不列为与本机/DLNA 并列的可选输出**（禁止事项 8）。
 * V0.1 用状态切页，未引 navigation-compose / coil-compose。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudMusicApp(
    tracks: Flow<List<Track>>,
    storages: Flow<List<Storage>>,
    playlists: Flow<List<Playlist>>,
    queue: Flow<List<QueueItem>>,
    outputState: Flow<OutputDeviceState>,
    search: (String) -> Flow<List<Track>>,
    runtimeState: StateFlow<RuntimeState>,
    playerState: StateFlow<PlayerState>,
    onRescanAll: () -> Unit,
    onRetryUnavailable: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var showOutput by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    val trackList by tracks.collectAsState(initial = emptyList())
    val storageList by storages.collectAsState(initial = emptyList())
    val playlistList by playlists.collectAsState(initial = emptyList())
    val queueList by queue.collectAsState(initial = emptyList())
    val output by outputState.collectAsState(
        initial = OutputDeviceState(ActiveOutput.LOCAL, null, ""),
    )
    val runtime by runtimeState.collectAsState()
    val player by playerState.collectAsState()
    val resultsFlow = remember(query) { search(query) }
    val searchResults by resultsFlow.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            if (showSources) {
                TopAppBar(
                    title = { Text("数据源管理") },
                    navigationIcon = {
                        IconButton(onClick = { showSources = false }) { Text("‹") }
                    },
                )
            } else {
                TopAppBar(title = { Text("CloudMusic") })
            }
        },
        bottomBar = {
            Column {
                MiniPlayer(player, onPlay, onPause, onNext, onPrev)
                NavigationBar {
                    listOf(
                        "曲库" to "♫", "歌单" to "☰", "搜索" to "⌕", "队列" to "☷", "设置" to "⚙",
                    ).forEachIndexed { index, (label, glyph) ->
                        NavigationBarItem(
                            selected = !showSources && tab == index,
                            onClick = { tab = index; showSources = false },
                            icon = { Text(glyph) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScanBanner(runtime)
            when {
                showSources -> SourcesScreen(storageList, onRescanAll, onRetryUnavailable)
                tab == 0 -> LibraryScreen(trackList, onRescanAll)
                tab == 1 -> PlaylistsScreen(playlistList)
                tab == 2 -> SearchScreen(query, { query = it }, searchResults)
                tab == 3 -> QueueScreen(queueList)
                else -> SettingsScreen(runtime, onRescanAll, { showSources = true }, { showOutput = true })
            }
        }
    }

    if (showOutput) {
        OutputDialog(output) { showOutput = false }
    }
}

@Composable
private fun ScanBanner(state: RuntimeState) {
    val text = when {
        state.scanning -> "扫描中 ${state.scanned}/${state.total}"
        state.errors > 0 -> "扫描完成，${state.errors} 项失败"
        state.lastEffect.isNotBlank() -> "生效：${state.lastEffect}"
        else -> "曲库就绪"
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MiniPlayer(
    player: PlayerState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
) {
    val playing = player.phase == PlayerPhase.PLAYING
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = player.currentTrackId?.value ?: "未在播放",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = onPrev) { Text("⏮") }
            IconButton(onClick = { if (playing) onPause() else onPlay() }) {
                Text(if (playing) "⏸" else "▶")
            }
            IconButton(onClick = onNext) { Text("⏭") }
        }
    }
}

@Composable
private fun LibraryScreen(tracks: List<Track>, onRescan: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("全部歌曲 · ${tracks.size}", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onRescan) { Text("重新扫描") }
        }
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("曲库为空：添加数据源后执行扫描", textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(tracks, key = { it.id.value }) { track ->
                    TrackRow(track)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track) {
    ListItem(
        headlineContent = { Text(track.title, maxLines = 1) },
        supportingContent = { Text("${track.artist} · ${track.album}", maxLines = 1) },
        leadingContent = { Text("♪") },
    )
}

@Composable
private fun SourcesScreen(
    storages: List<Storage>,
    onRescan: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("数据源 · ${storages.size}", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRescan) { Text("全量重扫") }
            Button(onClick = onRetry) { Text("重试失败项") }
        }
        if (storages.isEmpty()) {
            Text("还没有数据源（V0.1 经 DataStore 配置 Storage）")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(storages, key = { it.id }) { storage ->
                    ListItem(
                        headlineContent = {
                            Text(storage.remark.ifBlank { storage.mountPath }, maxLines = 1)
                        },
                        supportingContent = {
                            val status = if (storage.isWork) "正常" else "不可用 ${storage.errorMsg}"
                            Text("${storage.driverType} · ${storage.mountPath} · $status", maxLines = 2)
                        },
                        leadingContent = { Text("☁") },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PlaylistsScreen(playlists: List<Playlist>) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("歌单 · ${playlists.size}", style = MaterialTheme.typography.titleMedium)
        if (playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有歌单（V0.1 通过 PlaylistRepository 写入）", textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(playlists, key = { it.id }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name, maxLines = 1) },
                        supportingContent = { Text("id=${playlist.id} · 更新于 ${playlist.updatedAt}") },
                        leadingContent = { Text("☰") },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    results: List<Track>,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("搜索标题 / 艺人 / 专辑") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            query.isBlank() -> Text("输入关键字开始搜索（TrackRepository.search）")
            results.isEmpty() -> Text("无匹配结果")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.id.value }) { track ->
                    TrackRow(track)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun QueueScreen(queue: List<QueueItem>) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("播放队列 · ${queue.size}", style = MaterialTheme.typography.titleMedium)
        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("队列为空（V0.1 由 QueueRepository.observe() 提供）", textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(queue, key = { "${it.queueId}-${it.position}" }) { item ->
                    ListItem(
                        headlineContent = { Text("#${item.position} · ${item.trackId.value}", maxLines = 1) },
                        supportingContent = { Text("来源：${item.sourceRef}") },
                        leadingContent = { Text("☷") },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    runtime: RuntimeState,
    onRescan: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenOutput: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("V0.1 运行态", style = MaterialTheme.typography.titleMedium)
        ListItem(
            headlineContent = { Text("播放内核") },
            supportingContent = { Text("EngineAdapter（舱内真实内核；真机 G01/G02 待验，12 §36）") },
        )
        ListItem(
            headlineContent = { Text("本地流代理") },
            supportingContent = { Text(if (runtime.proxyRunning) "运行中" else "按需启动") },
        )
        ListItem(
            headlineContent = { Text("最近生效副作用") },
            supportingContent = { Text(runtime.lastEffect.ifBlank { "无" }) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenSources) { Text("数据源管理") }
            Button(onClick = onOpenOutput) { Text("输出设备") }
        }
        Button(onClick = onRescan) { Text("触发全量重扫（refresh=true）") }
        Text(
            "设置写入由 SettingsStore 返回 effects，PlaybackRuntime 是唯一执行者（08 §7 / 12 §31）。" +
                "照搬播放页属 T-L L3，真机验收属 T-V，本页不伪造已接通。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 03-10 §11 #8：蓝牙只作状态提示，不列为与本机/DLNA 并列的可选输出。 */
@Composable
private fun OutputDialog(state: OutputDeviceState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输出设备") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "当前：${if (state.activeOutput == ActiveOutput.LOCAL) "本机" else "DLNA"}",
                    style = MaterialTheme.typography.titleSmall,
                )
                ListItem(
                    headlineContent = { Text("本机") },
                    supportingContent = { Text("手机扬声器 / 有线输出") },
                )
                ListItem(
                    headlineContent = { Text("DLNA") },
                    supportingContent = { Text("投屏到局域网设备（03-08）") },
                )
                if (state.routeHint.isNotBlank()) {
                    Text("状态提示：${state.routeHint}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "蓝牙仅显示连接状态、不作为并列输出（03-10 §11 #8）；" +
                        "切换与真机状态上报属 T-L L2，V0.1 只读展示。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
