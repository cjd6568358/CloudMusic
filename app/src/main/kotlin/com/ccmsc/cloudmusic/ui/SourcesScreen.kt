package com.ccmsc.cloudmusic.ui

import android.graphics.Bitmap
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Slider
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ccmsc.cloudmusic.ui.player.loadLegacyTypeface
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccmsc.cloudmusic.SurfaceNav
import com.ccmsc.cloudmusic.di.playback.RuntimeState
import com.ccmsc.cloudmusic.model.SmartPlaylist
import com.ccmsc.cloudmusic.model.Storage
import com.ccmsc.cloudmusic.model.Availability
import com.ccmsc.cloudmusic.model.ErrorText
import com.ccmsc.cloudmusic.model.Track
import com.ccmsc.cloudmusic.model.TrackId
import com.ccmsc.cloudmusic.model.TrackInfo
import com.ccmsc.cloudmusic.output.dlna.DmrDevice
import com.ccmsc.cloudmusic.output.dlna.DeviceSource
import com.ccmsc.cloudmusic.output.dlna.ManualDmrSpec
import com.ccmsc.cloudmusic.output.router.ActiveOutput
import com.ccmsc.cloudmusic.output.router.OutputDeviceState
import com.ccmsc.cloudmusic.ui.player.LegacyPlayerPage
import com.ccmsc.cloudmusic.ui.player.PlayerStateBridge
import com.ccmsc.cloudmusic.player.PlayerPhase
import com.ccmsc.cloudmusic.player.PlayerState
import com.ccmsc.cloudmusic.player.QueueItem
import com.ccmsc.cloudmusic.storage.AppSettings
import com.ccmsc.cloudmusic.storage.Playlist
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/** §61.137 数据源管理（`main_account` 行样式）：添加来源 / 维护 / 已添加 三组。 */
@Composable
internal fun SourcesScreen(
    storages: List<Storage>,
    /**
     * §61.373：每个来源当前**入库曲目数**（`sourceRef.storageId` → count）。
     *
     * 此前来源行只写「driverType · 正常/不可用」——添加完一个源，用户没有任何途径
     * 知道它到底扫到几首：扫到 0 首时既可能是空目录、也可能是地址/凭据填错，
     * 而行内「正常」还会让人以为配置成功。曲目数直接给出答案。
     */
    trackCountByStorage: Map<String, Int> = emptyMap(),
    onRescan: () -> Unit,
    onRetry: () -> Unit,
    onRefreshStorage: (String) -> Unit,
    mergeDuplicates: Boolean,
    onMergeDuplicatesChanged: (Boolean) -> Unit,
    onAddSaf: () -> Unit,
    onAddMediaStore: () -> Unit,
    onAddWebDav: (String, String, String, String) -> Unit,
    onAddStrm: (String, String) -> Unit,
    onAddSftp: (String, Int, String, String, String, String, String) -> Unit,
    onAddFtp: (String, Int, String, String, String, Boolean) -> Unit,
    onRemoveStorage: (String) -> Unit,
    onBack: () -> Unit,
) {
    var showWebDav by remember { mutableStateOf(false) }
    var showStrm by remember { mutableStateOf(false) }
    var showSftp by remember { mutableStateOf(false) }
    var showFtp by remember { mutableStateOf(false) }
    // §61.207：行点击 → 源操作面板（刷新此源 / 移除）——§61.152 的移除确认扩为双动作，
    // 「刷新此源」复用定向增量扫（§61.206/§61.204 同链）。
    var actionTarget by remember { mutableStateOf<Storage?>(null) }
    Column(Modifier.fillMaxSize().background(FnComp.PageBg)) {
        FnPageHeader("音乐来源", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize()) {
            item { FnSectionTitle("添加音乐来源") }
            item {
                FnGroupCard {
                    FnRow(FnIcon.Folder, "本地目录（SAF）", onAddSaf, value = "无需读取权限")
                    FnRow(FnIcon.Image, "从媒体库导入", onAddMediaStore, value = "按动作申请权限", dividerAbove = true)
                    FnRow(FnIcon.Cloud, "添加 WebDAV 源", { showWebDav = true }, value = "地址 + 认证", dividerAbove = true)
                    // §61.151：STRM 独立源（复用已添加的来源挂载 .strm 挂载点）。
                    FnRow(FnIcon.Link, "添加 STRM 源", { showStrm = true }, value = "复用已有来源 + .strm", dividerAbove = true)
                    // V0.2 §61.216：SFTP 数据源（凭据只在内存 Addition，不写日志）。
                    FnRow(FnIcon.Terminal, "添加 SFTP 源", { showSftp = true }, value = "SSH 文件传输", dividerAbove = true)
                    FnRow(FnIcon.Lan, "添加 FTP 源", { showFtp = true }, value = "文件传输协议", dividerAbove = true)
                }
            }
            item { FnSectionTitle("维护") }
            item {
                FnGroupCard {
                    FnRow(FnIcon.Refresh, "全量重扫", onRescan, value = "找出新增与恢复的文件")
                    FnRow(FnIcon.Refresh, "重试失败项", onRetry, value = "只处理上次失败的", dividerAbove = true)
                    // §61.208（A4）：同 URL 双源折叠开关（scan.mergeDuplicates 消费方）。
                    FnRow(
                        FnIcon.Duplicate, "合并重复项",
                        { onMergeDuplicatesChanged(!mergeDuplicates) },
                        value = if (mergeDuplicates) "同 URL 只显示一行" else "关闭",
                        showChevron = false,
                        dividerAbove = true,
                        trailing = {
                            FnSwitch(checked = mergeDuplicates, onChange = onMergeDuplicatesChanged)
                        },
                    )
                }
            }
            item { FnSectionTitle("已添加 · ${storages.size}") }
            if (storages.isEmpty()) {
                item {
                    Text(
                        "还没有音乐来源，点上方按钮添加本地目录或导入媒体库",
                        color = FnComp.TextHint,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(storages, key = { it.id }) { storage ->
                    // §61.275：错误码不进用户文案（07 码表 → 人话映射）
                    val status = if (storage.isWork) {
                        "正常"
                    } else {
                        "不可用 · " + ErrorText.human(storage.errorMsg)
                    }
                    // §61.373：曲目数紧跟状态——「正常 · 0 首」一眼就是可疑配置。
                    val hits = trackCountByStorage[storage.id] ?: 0
                    FnGroupCard(Modifier.padding(bottom = 10.dp)) {
                        FnRow(
                            // §61.373：行图标按来源类型分（与六个添加入口同一套语形）。
                            sourceIcon(storage.driverType),
                            storage.remark.ifBlank { storage.mountPath },
                            // §61.207：点行 → 源操作面板（刷新此源 / 移除）。
                            { actionTarget = storage },
                            value = "$hits 首 · $status",
                            // §61.275（B06）：失败源在列表里也必须一眼可辨（灰显 + 角标）
                            trailing = if (storage.isWork) null else {
                                { FnUnavailableBadge("不可用") }
                            },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
    if (showWebDav) {
        WebDavDialog(
            onDismiss = { showWebDav = false },
            onAdd = { url, user, pass, path ->
                onAddWebDav(url, user, pass, path)
                showWebDav = false
            },
        )
    }
    // §61.151：STRM 独立源对话框——委托源候选 = 已添加且非 strm 的存储
    // （strm 委托 strm 不禁止但无意义，不列）。
    if (showStrm) {
        StrmDialog(
            delegates = storages.filter { it.driverType != "strm" },
            onDismiss = { showStrm = false },
            onAdd = { delegateId, paths ->
                onAddStrm(delegateId, paths)
                showStrm = false
            },
        )
    }
    // V0.2 §61.216：SFTP 源输入面板（端口非法时本地拦截，不发起连接）。
    if (showSftp) {
        SftpDialog(
            onDismiss = { showSftp = false },
            onAdd = { host, port, user, pass, root, key, passphrase ->
                onAddSftp(host, port, user, pass, root, key, passphrase)
                showSftp = false
            },
        )
    }
    if (showFtp) {
        FtpDialog(
            onDismiss = { showFtp = false },
            onAdd = { host, port, user, pass, root, ssl ->
                onAddFtp(host, port, user, pass, root, ssl)
                showFtp = false
            },
        )
    }
    // §61.207：源操作面板（§61.152 移除确认扩为双动作）——「刷新此源」走定向增量扫。
    actionTarget?.let { target ->
        FnSheetDialog(
            onDismiss = { actionTarget = null },
            title = target.remark.ifBlank { target.mountPath },
            content = {
                Text(
                    "${target.driverType} · " + (
                        if (target.isWork) "正常" else "不可用 · " + ErrorText.human(target.errorMsg)
                        ) + "\n" +
                        "刷新只重扫该源（有变更的文件才重新处理）；移除标记曲目不可用，文件不删。",
                    color = FnComp.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            },
            actions = listOf(
                "刷新此源" to {
                    onRefreshStorage(target.id)
                    actionTarget = null
                },
                "移除" to {
                    onRemoveStorage(target.id)
                    actionTarget = null
                },
            ),
            destructiveIndexOf = 1,
        )
    }
}

/** §61.132/§61.137：WebDAV 源添加（b5y 面板 + 表单；字段与 WebDavDriver strCompat 对齐）。 */
@Composable
internal fun WebDavDialog(
    onDismiss: () -> Unit,
    onAdd: (url: String, username: String, password: String, rootPath: String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rootPath by remember { mutableStateOf("/music") }
    FnSheetDialog(
        onDismiss = onDismiss,
        title = "添加 WebDAV 源",
        // §61.295：4 格 + 动作行，键盘弹出后放不下 → 内容可滚，聚焦格自动滚进视野。
        scrollable = true,
        content = {
            Column {
                // §61.293：首格自动聚焦，回车逐格下移，末格（远程路径）回车收键盘。
                FnDialogInput(url, { url = it }, hint = "服务器地址（含端口），如 https://nas:5006", autoFocus = true, imeAction = ImeAction.Next)
                FnDialogInput(username, { username = it }, hint = "用户名（可空=匿名）", imeAction = ImeAction.Next)
                FnDialogInput(password, { password = it }, hint = "密码（可空）", password = true, imeAction = ImeAction.Next)
                FnDialogInput(rootPath, { rootPath = it }, hint = "远程路径（默认 /music）")
            }
        },
        actions = listOf("添加" to {
            if (url.isNotBlank()) {
                onAdd(url.trim().trimEnd('/'), username.trim(), password, rootPath.trim())
            }
        }),
    )
}

/** V0.2 §61.216：SFTP 源输入面板（字段与 SftpConfig / 08 配置项对齐；凭据只进 Addition.raw）。 */
@Composable
internal fun SftpDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Int, String, String, String, String, String) -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var root by remember { mutableStateOf("/") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    FnSheetDialog(
        onDismiss = onDismiss,
        title = "添加 SFTP 源",
        // §61.295：7 格是本 App 最长的表单，不开滚动时键盘一起就够不到末尾两格。
        scrollable = true,
        content = {
            Column {
                // §61.293：首格自动聚焦，回车逐格下移，末格（私钥口令）回车收键盘。
                FnDialogInput(host, { host = it }, hint = "主机 IP / 域名，如 nas.local", autoFocus = true, imeAction = ImeAction.Next)
                FnDialogInput(port, { port = it }, hint = "SSH 端口（默认 22）", imeAction = ImeAction.Next)
                FnDialogInput(user, { user = it }, hint = "用户名", imeAction = ImeAction.Next)
                FnDialogInput(pass, { pass = it }, hint = "密码 / 口令", password = true, imeAction = ImeAction.Next)
                FnDialogInput(root, { root = it }, hint = "远程根目录（默认 /）", imeAction = ImeAction.Next)
                // §61.239：Ed25519 边界一手文案（§61.223 判读登记收口）——Android 无
                // Ed25519 JCE provider，JSch 本地 Auth fail，推荐 RSA/EC 私钥口径。
                FnDialogInput(privateKey, { privateKey = it }, hint = "PEM 私钥（可与密码二选一，换行用 \\n；推荐 RSA/EC）", password = true, imeAction = ImeAction.Next)
                FnDialogInput(passphrase, { passphrase = it }, hint = "私钥口令（可空）", password = true)
                if (error != null) {
                    Text(
                        error!!,
                        color = FnComp.IconRed,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
        actions = listOf(
            "添加" to {
                val parsedPort = port.trim().toIntOrNull()
                when {
                    host.isBlank() -> error = "请填写主机地址"
                    user.isBlank() -> error = "请填写用户名"
                    parsedPort == null || parsedPort !in 1..65535 -> error = "端口必须是 1~65535"
                    pass.isBlank() && privateKey.isBlank() -> error = "请填写密码或 PEM 私钥之一"
                    else -> {
                        error = null
                        onAdd(host.trim(), parsedPort, user.trim(), pass, root.trim(), privateKey, passphrase)
                    }
                }
            },
            "关闭" to onDismiss,
        ),
    )
}

/** §61.228：FTP 源添加（§61.216 SftpDialog 同型；username 空 = anonymous，驱动侧默认）。 */
@Composable
internal fun FtpDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Int, String, String, String, Boolean) -> Unit,
) {
    var ssl by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("21") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var root by remember { mutableStateOf("/") }
    var error by remember { mutableStateOf<String?>(null) }
    FnSheetDialog(
        onDismiss = onDismiss,
        title = "添加 FTP 源",
        // §61.295：5 格 + FTPS 开关，同 SFTP 处理。
        scrollable = true,
        content = {
            Column {
                // §61.293：首格自动聚焦，回车逐格下移，末格（远程根目录）回车收键盘。
                FnDialogInput(host, { host = it }, hint = "主机 IP / 域名，如 nas.local", autoFocus = true, imeAction = ImeAction.Next)
                FnDialogInput(port, { port = it }, hint = "FTP 端口（默认 21）", imeAction = ImeAction.Next)
                FnDialogInput(user, { user = it }, hint = "用户名（空 = 匿名）", imeAction = ImeAction.Next)
                FnDialogInput(pass, { pass = it }, hint = "密码（匿名可空）", password = true, imeAction = ImeAction.Next)
                FnDialogInput(root, { root = it }, hint = "远程根目录（默认 /）")
                FnRow(
                    icon = FnIcon.Lock,
                    title = "FTPS（显式 TLS）",
                    onClick = { ssl = !ssl },
                    showChevron = false,
                    trailing = { FnSwitch(checked = ssl, onChange = { ssl = it }) },
                )
                if (error != null) {
                    Text(
                        error!!,
                        color = FnComp.IconRed,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
        actions = listOf(
            "添加" to {
                val parsedPort = port.trim().toIntOrNull()
                when {
                    host.isBlank() -> error = "请填写主机地址"
                    parsedPort == null || parsedPort !in 1..65535 -> error = "端口必须是 1~65535"
                    else -> {
                        error = null
                        onAdd(host.trim(), parsedPort, user.trim(), pass, root.trim(), ssl)
                    }
                }
            },
            "关闭" to onDismiss,
        ),
    )
}

@Composable
internal fun StrmDialog(
    delegates: List<Storage>,
    onDismiss: () -> Unit,
    onAdd: (delegateStorageId: String, paths: String) -> Unit,
) {
    var delegateId by remember { mutableStateOf("") }
    var paths by remember { mutableStateOf("") }
    val canAdd = delegateId.isNotBlank() && delegates.any { it.id == delegateId }
    FnSheetDialog(
        onDismiss = onDismiss,
        title = "添加 STRM 源",
        // §61.295：委托来源列表随存储数变长，再加 3 行挂载路径编辑框——键盘一弹必然超屏。
        scrollable = true,
        content = {
            Column {
                if (delegates.isEmpty()) {
                    Text(
                        "还没有可复用的来源——先添加本地目录 / WebDAV，再挂载其 .strm 挂载点",
                        color = FnComp.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    Text(
                        "复用来源",
                        color = FnComp.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    delegates.forEach { storage ->
                        val selected = storage.id == delegateId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { delegateId = storage.id }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (selected) "●" else "○",
                                color = if (selected) FnComp.IconRed else FnComp.TextSecondary,
                                fontSize = 14.sp,
                            )
                            Text(
                                " ${storage.remark.ifBlank { storage.mountPath }}（${storage.driverType}）",
                                color = if (selected) FnComp.TextPrimary else FnComp.TextSecondary,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
                // §61.161：多行路径编辑（parsePaths 每行 `目录` 或 `名:目录`）。
                androidx.compose.foundation.text.BasicTextField(
                    value = paths,
                    onValueChange = { paths = it },
                    minLines = 3,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = FnComp.TextPrimary,
                        fontSize = 14.sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(FnComp.InputBg)
                        .padding(12.dp),
                    decorationBox = { inner ->
                        if (paths.isBlank()) {
                            Text(
                                "挂载路径（可空=委托根目录；每行 目录 或 名:目录）",
                                color = FnComp.TextHint,
                                fontSize = 14.sp,
                            )
                        }
                        inner()
                    },
                )
            }
        },
        actions = listOf("添加" to {
            if (canAdd) onAdd(delegateId, paths.trim())
        }),
    )
}


/**
 * §61.373：来源类型 → 图标。与来源页六个添加入口同一套语形，
 * 让「列表里这行是什么源」和「我要添加什么源」对得上。未知类型回退 Sources。
 */
private fun sourceIcon(driverType: String): FnIcon = when (driverType.lowercase()) {
    "local", "saf" -> FnIcon.Folder
    "mediastore", "media_store" -> FnIcon.Image
    "webdav" -> FnIcon.Cloud
    "strm" -> FnIcon.Link
    "sftp" -> FnIcon.Terminal
    "ftp" -> FnIcon.Lan
    else -> FnIcon.Sources
}

