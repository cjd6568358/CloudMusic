#!/usr/bin/env python3
"""源码契约静态自检（不依赖 Gradle 的轻量结构校验）。

本脚本不假装等价编译，只检查「结构齐全 + 契约签名在位 + 测试编号覆盖 + 禁止依赖」，
可在无 Gradle 环境下快速跑；真正的编译与 JVM 单测用 ./gradlew test。
2026-11-05 本机已用真实 Gradle 8.11.1 验证：第一批 23 个、第二批 66 个、第三批 84 个、
第四批 104 个、第五批（03-06 :engine-api 门面）116 个、第六批（03-07 :core-media）131 个、
第七批（03-08 :output-localproxy 流代理）144 个、第八批（03-09 :output-router）159 个测试全绿、
第九批（03-11 权限与设置契约，落在 :core-common / :core-storage 两个既有模块）新增 20 个单测。
第十批（多人协作 Android 构建基座）打通 :app:assembleDebug，清单扩到 28 构建文件 / 10 工具脚本，
新增「:app Manifest ≡ C3 允许清单」交叉校验；SDK 路径零硬编码由 tools/check-android-env.py --guard 守护。
第十一批（:core-di Hilt 唯一注册点 + :app 接入）新增「@InstallIn 只允许出现在 :core-di」守护。
第十二批（16 §5.3 T-P4）落地 C-05 / C-06：185 测试全绿，新增 EngineSource 可选字段与
ProxyStartPolicy 触发器 ④ 的签名断言。
第十三批（16 §5.3 T-P2）落地 :engine-bridge 四态 IDataSource 与 DataSourceAdapter（T01~T05）。
第十四批（16 §5.3 T-P1）落地 :feature-player 业务队列 / PlayMode / QueueSync / PlayerController（T07、T08、T11）。
第十五批（16 §5.3 T-P3）落地 :output-dlna 决策表与 SSDP / SOAP / DIDL-Lite（T01~T09、T13、T14），T-P 线收口。
详见 docs/12-编码阶段第一批交付.md §5.1 / §11 / §12 / §13 / §14 / §15 / §16 / §17 / §18 / §19 / §20 / §21。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

GRADLE_FILES = (
    "settings.gradle.kts", "build.gradle.kts", "gradle.properties",
    "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties",
    "gradle/wrapper/gradle-wrapper.jar", "gradlew", "gradlew.bat",
    "build-logic/settings.gradle.kts", "build-logic/convention/build.gradle.kts",
    "build-logic/convention/src/main/kotlin/cloudmusic.jvm.library.gradle.kts",
    "build-logic/convention/src/main/kotlin/cloudmusic.android.library.gradle.kts",
    "build-logic/convention/src/main/kotlin/cloudmusic.android.application.gradle.kts",
    "local.properties.example", "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml", "core-di/build.gradle.kts",
    "engine-bridge/build.gradle.kts", "feature-player/build.gradle.kts",
    "output-dlna/build.gradle.kts",
    "core-common/build.gradle.kts", "core-model/build.gradle.kts",
    "datasource-api/build.gradle.kts",
    "datasource-strm/build.gradle.kts", "datasource-playlist/build.gradle.kts",
    "datasource-webdav/build.gradle.kts", "scanner/build.gradle.kts",
    "core-storage/build.gradle.kts", "engine-api/build.gradle.kts",
    "core-media/build.gradle.kts", "output-localproxy/build.gradle.kts",
    "output-router/build.gradle.kts",
)

# docs/03-01 §2 包结构 + 03-02 / 03-03 驱动 + 03-04 扫描器 + 03-05 曲库 + 03-06 门面
# + 03-07 媒体会话 + 03-08 本地流代理 + 03-09 输出路由
CONTRACT_FILES = (
    "core-common/src/main/kotlin/com/example/cloudmusic/common/CloudMusicError.kt",
    "core-common/src/main/kotlin/com/example/cloudmusic/common/ErrorCatalog.kt",
    "core-common/src/main/kotlin/com/example/cloudmusic/common/HashUtil.kt",
    "core-common/src/main/kotlin/com/example/cloudmusic/common/PathNormalizer.kt",
    # 03-11 §1 / §2 / §3：权限清单、按动作申请闸门、拒绝降级矩阵
    "core-common/src/main/kotlin/com/example/cloudmusic/common/PermissionCatalog.kt",
    "core-common/src/main/kotlin/com/example/cloudmusic/common/PermissionGate.kt",
    "core-model/src/main/kotlin/com/example/cloudmusic/model/ObjectType.kt",
    "core-model/src/main/kotlin/com/example/cloudmusic/model/Obj.kt",
    "core-model/src/main/kotlin/com/example/cloudmusic/model/Link.kt",
    "core-model/src/main/kotlin/com/example/cloudmusic/model/Storage.kt",
    "core-model/src/main/kotlin/com/example/cloudmusic/model/Track.kt",
    "core-model/src/main/kotlin/com/example/cloudmusic/model/IDataSource.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/Driver.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/DriverRegistry.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/PathRule.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/LinkRule.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/DriverLifecycle.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/DirectLinkCapability.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/ByteRangeCapability.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/AbstractDriver.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/TextReadCapability.kt",
    "datasource-strm/src/main/kotlin/com/example/cloudmusic/strm/StrmParser.kt",
    "datasource-strm/src/main/kotlin/com/example/cloudmusic/strm/StrmPaths.kt",
    "datasource-strm/src/main/kotlin/com/example/cloudmusic/strm/StrmDriver.kt",
    "datasource-playlist/src/main/kotlin/com/example/cloudmusic/playlist/PlaylistParser.kt",
    "datasource-playlist/src/main/kotlin/com/example/cloudmusic/playlist/PlaylistResolver.kt",
    "datasource-playlist/src/main/kotlin/com/example/cloudmusic/playlist/PlaylistDriver.kt",
    "datasource-webdav/src/main/kotlin/com/example/cloudmusic/webdav/DigestAuth.kt",
    "datasource-webdav/src/main/kotlin/com/example/cloudmusic/webdav/PropfindParser.kt",
    "datasource-webdav/src/main/kotlin/com/example/cloudmusic/webdav/WebDavHttp.kt",
    "datasource-webdav/src/main/kotlin/com/example/cloudmusic/webdav/WebDavDriver.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/ScanModels.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/ScanPorts.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/NameParser.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/AudioProbe.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/ExcludeRule.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/TagReader.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/CoverResolver.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/TrackFactory.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/ScanCoordinator.kt",
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/LibraryModels.kt",
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/Repositories.kt",
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/LibraryQuery.kt",
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/InMemoryLibrary.kt",
    # 08 §1~§8 / 03-05 §4 / 03-11 §4~§7：AppSettings 全量设置项与变更生效规则
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/AppSettings.kt",
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/SettingsChange.kt",
    "core-model/src/test/kotlin/com/example/cloudmusic/model/ContractModelTest.kt",
    "datasource-api/src/test/kotlin/com/example/cloudmusic/datasource/DriverContractTest.kt",
    "datasource-strm/src/test/kotlin/com/example/cloudmusic/strm/StrmParserTest.kt",
    "datasource-strm/src/test/kotlin/com/example/cloudmusic/strm/StrmPathsTest.kt",
    "datasource-strm/src/test/kotlin/com/example/cloudmusic/strm/StrmDriverTest.kt",
    "datasource-playlist/src/test/kotlin/com/example/cloudmusic/playlist/PlaylistParserTest.kt",
    "datasource-playlist/src/test/kotlin/com/example/cloudmusic/playlist/PlaylistResolverTest.kt",
    "datasource-webdav/src/test/kotlin/com/example/cloudmusic/webdav/DigestAuthTest.kt",
    "datasource-webdav/src/test/kotlin/com/example/cloudmusic/webdav/PropfindParserTest.kt",
    "datasource-webdav/src/test/kotlin/com/example/cloudmusic/webdav/WebDavDriverTest.kt",
    "scanner/src/test/kotlin/com/example/cloudmusic/scanner/ScannerUnitTest.kt",
    "scanner/src/test/kotlin/com/example/cloudmusic/scanner/ScanCoordinatorTest.kt",
    "core-storage/src/test/kotlin/com/example/cloudmusic/storage/LibraryContractTest.kt",
    "core-storage/src/test/kotlin/com/example/cloudmusic/storage/InMemoryRepositoryTest.kt",
    "engine-api/src/main/kotlin/com/example/cloudmusic/engine/api/EngineApi.kt",
    "engine-api/src/test/kotlin/com/example/cloudmusic/engine/api/EngineApiContractTest.kt",
    "core-media/src/main/kotlin/com/example/cloudmusic/media/MediaControlApi.kt",
    "core-media/src/main/kotlin/com/example/cloudmusic/media/FocusPolicy.kt",
    "core-media/src/main/kotlin/com/example/cloudmusic/media/MediaKeyDispatcher.kt",
    "core-media/src/main/kotlin/com/example/cloudmusic/media/InMemoryMediaControl.kt",
    "core-media/src/test/kotlin/com/example/cloudmusic/media/MediaControlContractTest.kt",
    "output-localproxy/src/main/kotlin/com/example/cloudmusic/output/localproxy/ProxyModels.kt",
    "output-localproxy/src/main/kotlin/com/example/cloudmusic/output/localproxy/RangeResponder.kt",
    "output-localproxy/src/main/kotlin/com/example/cloudmusic/output/localproxy/TokenRegistry.kt",
    "output-localproxy/src/main/kotlin/com/example/cloudmusic/output/localproxy/LocalHttpStreamProxy.kt",
    "output-localproxy/src/test/kotlin/com/example/cloudmusic/output/localproxy/LocalProxyContractTest.kt",
    "output-router/src/main/kotlin/com/example/cloudmusic/output/router/OutputModels.kt",
    "output-router/src/main/kotlin/com/example/cloudmusic/output/router/OutputRouter.kt",
    "output-router/src/main/kotlin/com/example/cloudmusic/output/router/BluetoothOutputPolicy.kt",
    "output-router/src/test/kotlin/com/example/cloudmusic/output/router/OutputRoutingContractTest.kt",
    "core-common/src/test/kotlin/com/example/cloudmusic/common/PermissionGateTest.kt",
    "core-storage/src/test/kotlin/com/example/cloudmusic/storage/AppSettingsTest.kt",
    # 03-01 §4.1 / 15 §7.5 #4：Hilt 唯一注册点（第十一批）
    "core-di/src/main/kotlin/com/example/cloudmusic/di/DataSourceModule.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/AppBindings.kt",
    "app/src/main/kotlin/com/example/cloudmusic/CloudMusicApplication.kt",
    # 03-06 §7 / 16 §5.3 T-P2：IDataSource 四态实现（第十三批）
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/DataSourcePorts.kt",
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/BaseDataSource.kt",
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/MemoryDataSource.kt",
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/FileDataSource.kt",
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/HttpDataSource.kt",
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/DriverDataSource.kt",
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/DataSourceAdapter.kt",
    "engine-bridge/src/test/kotlin/com/example/cloudmusic/bridge/DataSourceAdapterTest.kt",
    # 03-06 §8 / §9 / 16 §5.3 T-P1：播放业务编排层（第十四批）
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/PlaybackModels.kt",
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/QueueRepository.kt",
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/PlayModeOrdering.kt",
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/QueueSync.kt",
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/TrackPorts.kt",
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/PlayerController.kt",
    "feature-player/src/test/kotlin/com/example/cloudmusic/player/PlayerControllerTest.kt",
    # 03-08 §3~§6 / 16 §5.3 T-P3：DLNA 投屏协议与决策（第十五批）
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/DlnaModels.kt",
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/CastUriResolver.kt",
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/DescriptionParser.kt",
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/DidlLiteBuilder.kt",
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/SoapController.kt",
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/SsdpDiscovery.kt",
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/ManualDmrProbe.kt",
    "output-dlna/src/test/kotlin/com/example/cloudmusic/output/dlna/DlnaContractTest.kt",
)

CHECK_SCRIPTS = (
    "check-engine-abi.py", "check-no-netease-import.py", "check-permissions.py",
    "check-assets.py", "check-credentials.py", "check-fingerprint.py",
    "pre_release_check.py", "elf_probe.py", "check_common.py",
    "check-android-env.py",
)

# 03-01 / 03-02 / 03-03 / 03-04 契约中不可漂移的签名片段：{文件: [片段]}
SIGNATURES = {
    "core-model/src/main/kotlin/com/example/cloudmusic/model/Link.kt": [
        "enum class Kind { URL, LOCAL, PROXIED, TEXT }",
        "val kind: Kind get()",
        "url 与 localPath 互斥",
    ],
    "core-model/src/main/kotlin/com/example/cloudmusic/model/ObjectType.kt": [
        "UNKNOWN(0), FOLDER(1), VIDEO(2), AUDIO(3), TEXT(4), IMAGE(5)",
        "fun extOf(name: String): String",
    ],
    "core-model/src/main/kotlin/com/example/cloudmusic/model/IDataSource.kt": [
        "fun open(offset: Long): Long", "fun readAt(", "fun mimeHint(): String",
    ],
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/Driver.kt": [
        "interface Driver", "suspend fun init(ctx: DriverCtx)", "fun drop()",
        "suspend fun list(dir: Obj, refresh: Boolean = false): List<Obj>",
        "suspend fun get(path: String): Obj?",
        "suspend fun link(file: Obj, args: LinkArgs = LinkArgs()): Link",
        "fun mustProxy(): Boolean = onlyProxy || noLinkUrl",
        "typealias DriverFactory = (Storage, Addition) -> Driver",
    ],
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/DriverRegistry.kt": [
        "fun register(type: String, factory: DriverFactory)",
        "fun types(): Set<String>", "object DriverRegistry",
    ],
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/TextReadCapability.kt": [
        "interface TextReadCapability", "suspend fun readText(file: Obj): String",
    ],
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/AbstractDriver.kt": [
        "abstract class AbstractDriver(", "fun JsonObject.firstStr(",
        "final override suspend fun init(ctx: DriverCtx)",
    ],
    "datasource-strm/src/main/kotlin/com/example/cloudmusic/strm/StrmParser.kt": [
        "fun parseStrm(content: String): StrmParseResult",
    ],
    "datasource-playlist/src/main/kotlin/com/example/cloudmusic/playlist/PlaylistParser.kt": [
        "sealed interface PlaylistEntry", "fun parsePlaylist(fileName: String, text: String)",
        "const val MAX_DEPTH = 5",
    ],
    "datasource-webdav/src/main/kotlin/com/example/cloudmusic/webdav/WebDavDriver.kt": [
        "class WebDavDriver(", "DirectLinkCapability",
        "override suspend fun directUrl(file: Obj): String?",
    ],
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/ScanCoordinator.kt": [
        "class ScanCoordinator(", "fun scan(request: ScanRequest): Flow<ScanProgress>",
        "suspend fun run(",
    ],
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/TrackFactory.kt": [
        "fun build(candidate: Candidate, raw: RawTags = RawTags.EMPTY): Track",
        "TrackId.of(candidate.storage.id, candidate.obj.path)",
    ],
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/ScanPorts.kt": [
        "interface TrackSink", "interface ScanSnapshotStore",
    ],
    "core-common/src/main/kotlin/com/example/cloudmusic/common/CloudMusicError.kt": [
        "open class CloudMusicError(", "typealias CmResult<T> = Result<T>",
        "fun mapDriverError(",
    ],
    # 03-05 §5 Repository 契约签名（逐字对齐）
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/Repositories.kt": [
        "interface TrackRepository", "fun observeAll(): Flow<List<Track>>",
        "suspend fun upsertBatch(tracks: List<Track>)",
        "suspend fun markOrphan(storageId: String)",
        "interface PlaylistRepository", "interface FavoriteRepository",
        "interface HistoryRepository", "interface StorageRepository",
        "const val UPSERT_BATCH_SIZE = 500",
    ],
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/LibraryModels.kt": [
        "data class Playlist(", "data class TrackEntity(",
        "fun toTrack(): Track", "fun from(",
    ],
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/InMemoryLibrary.kt": [
        "class InMemoryTrackRepository(", "class ScanReconciler(",
        "suspend fun upsertFromScan(batch: List<Track>)",
        "class InMemoryPlaylistRepository(", "class InMemoryStorageRepository(",
    ],
    # 03-06 §4 稳定门面（逐字对齐，不得漂移）
    "engine-api/src/main/kotlin/com/example/cloudmusic/engine/api/EngineApi.kt": [
        "value class EngineHandle(val raw: Long)",
        "enum class EngineState { IDLE, PREPARING, PLAYING, PAUSED, ENDED, ERROR }",
        "interface IMusicEngine",
        "fun prepare(source: EngineSource, config: EngineConfig): EngineHandle",
        "fun attachDataSource(handle: EngineHandle, ds: IDataSource)",
        "fun state(): Flow<EngineState>",
        "fun lastError(): CloudMusicError?",
        "class InMemoryMusicEngine : IMusicEngine",
        # C-05（16 §4）：门面可携带 URL / 本机路径，默认 null、14 方法签名不动
        "val url: String? = null",
        "val localPath: String? = null",
        "url 与 localPath 互斥（16 §3.2 本机取流决策表）",
    ],
    # C-06（16 §4 / 03-08 §8.4）：本机播放 proxy 态触发器 ④
    "output-localproxy/src/main/kotlin/com/example/cloudmusic/output/localproxy/LocalHttpStreamProxy.kt": [
        "localPlaybackKind: Link.Kind? = null",
        "localPlaybackKind == Link.Kind.PROXIED || localPlaybackKind == Link.Kind.TEXT",
        "interface LocalHttpStreamProxy {",
    ],
    # 03-07 §3 媒体会话门面（逐字对齐，不得漂移）
    "core-media/src/main/kotlin/com/example/cloudmusic/media/MediaControlApi.kt": [
        "interface IMediaControl",
        "fun onFocusChange(change: FocusChange)",
        "fun onMediaButton(key: MediaKey, action: MediaAction)",
        "fun bind(controller: PlayerControllerHandle)",
        "fun publishMetadata(track: Track, coverPath: String?)",
        "fun setPlaybackState(state: EngineState, positionMs: Long, durationMs: Long)",
        "fun setEnabled(enabled: Boolean)",
        "fun release()",
        "interface PlayerControllerHandle",
        "enum class MediaAction { DOWN, UP, LONG_PRESS, DOUBLE_CLICK }",
    ],
    # 03-08 §8 本地流代理契约层
    "output-localproxy/src/main/kotlin/com/example/cloudmusic/output/localproxy/LocalHttpStreamProxy.kt": [
        "interface LocalHttpStreamProxy",
        "fun urlFor(trackId: String): String",
        "fun serve(request: ProxyRequest): ProxyResponse",
        "object ProxyStartPolicy",
        "class InMemoryLocalHttpStreamProxy(",
    ],
    "output-localproxy/src/main/kotlin/com/example/cloudmusic/output/localproxy/RangeResponder.kt": [
        "object RangeResponder",
        "fun parse(header: String?): HttpRange?",
        "fun respond(",
    ],
    "output-localproxy/src/main/kotlin/com/example/cloudmusic/output/localproxy/TokenRegistry.kt": [
        "class TokenRegistry(",
        "fun register(trackId: String, link: Link): String",
        "fun resolve(token: String): ProxyEntry?",
        "fun invalidateAll()",
    ],
    # 03-09 §3 / §7 输出路由与蓝牙断连裁决
    "output-router/src/main/kotlin/com/example/cloudmusic/output/router/OutputModels.kt": [
        "enum class ActiveOutput { LOCAL, DLNA }",
        "data class BluetoothInfo(",
        "data class OutputDeviceState(",
        "data class BluetoothSettings(",
    ],
    "output-router/src/main/kotlin/com/example/cloudmusic/output/router/OutputRouter.kt": [
        "interface OutputRouter",
        "fun setActive(output: ActiveOutput)",
        "fun updateBluetooth(info: BluetoothInfo?)",
        "class InMemoryOutputRouter : OutputRouter",
    ],
    "output-router/src/main/kotlin/com/example/cloudmusic/output/router/BluetoothOutputPolicy.kt": [
        "class BluetoothOutputPolicy(",
        "fun onEvent(event: BluetoothEvent, info: BluetoothInfo? = null): BluetoothEventResult",
        "fun notifyPlaybackResumed()",
        "mediaControl.onFocusChange(FocusChange.LOSS)",
    ],
    # 03-11 §1 / §2 / §3 + 08 §1 / §6 / §7
    "core-common/src/main/kotlin/com/example/cloudmusic/common/PermissionCatalog.kt": [
        "object PermissionCatalog {",
        "val DECLARED: List<DeclaredPermission> = listOf(",
        "const val MAX_DECLARED_COUNT = 12",
        "const val ALLOW_BACKUP = false",
        "fun effectiveAt(sdkInt: Int): List<DeclaredPermission>",
    ],
    "core-common/src/main/kotlin/com/example/cloudmusic/common/PermissionGate.kt": [
        "fun requiredFor(action: UserAction): List<String>",
        "fun requiredFor(action: UserAction, sdkInt: Int): List<String>",
        "fun requestsForStartup(): List<String> = emptyList()",
        "val isCoreCapabilityBlocked: Boolean",
    ],
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/AppSettings.kt": [
        "data class AppSettings(",
        "data class NetworkSettings(",
        "data class ScanSettings(",
        "object SettingsValidator {",
    ],
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/SettingsChange.kt": [
        "enum class SettingsChange {",
        "fun diff(before: AppSettings, after: AppSettings): Set<SettingsChange>",
        "fun effectsFor(before: AppSettings, after: AppSettings): Set<Effect>",
        "fun requiresFullRescan(before: AppSettings, after: AppSettings): Boolean",
    ],
    # 03-01 §4.1 / 12 §20：Hilt 唯一注册点与 Application 入口
    "core-di/src/main/kotlin/com/example/cloudmusic/di/DataSourceModule.kt": [
        "object DataSourceModule {",
        "@InstallIn(SingletonComponent::class)",
        "@StringKey(KEY_WEBDAV)",
        "class DriverRegistrar @Inject constructor(",
        "fun registerAll(): Set<String>",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/AppBindings.kt": [
        "object EngineModule {",
        "object MediaModule {",
        "object LibraryModule {",
        "fun provideMusicEngine(): IMusicEngine",
        "fun provideLibraryStore(): InMemoryLibraryStore",
    ],
    "app/src/main/kotlin/com/example/cloudmusic/CloudMusicApplication.kt": [
        "@HiltAndroidApp",
        "class CloudMusicApplication : Application()",
        "driverRegistrar.registerAll()",
    ],
    # 03-06 §7 / §15 T01~T05（T-P2）
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/DataSourcePorts.kt": [
        "fun interface RangeHttpStack {",
        "interface FdOpener {",
        "fun interface DriverStreamFactory {",
        "class JvmFileFdOpener : FdOpener {",
    ],
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/BaseDataSource.kt": [
        "abstract class BaseDataSource(protected val link: Link) : IDataSource",
        "final override fun readAt(offset: Long, buf: ByteArray, len: Int): Int",
        "lastError = e.toCloudMusicError()",
        "final override fun close()",
    ],
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/DataSourceAdapter.kt": [
        "object DataSourceAdapter {",
        "Link.Kind.URL -> HttpDataSource(link, args, http)",
        "Link.Kind.LOCAL -> FileDataSource(link, fdOpener)",
        "Link.Kind.PROXIED -> DriverDataSource(link, driverStreams)",
        "Link.Kind.TEXT -> MemoryDataSource(link)",
    ],
    # 03-06 §8 / §9 / §15 T07、T08、T11（T-P1）
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/QueueRepository.kt": [
        "interface QueueRepository {",
        "suspend fun move(from: Int, to: Int)",
        "suspend fun saveCursor(trackId: String, positionMs: Long)",
        "class InMemoryQueueRepository(",
    ],
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/PlayModeOrdering.kt": [
        "object ShuffleOrder {",
        "fun generate(seed: Long, size: Int): List<Int>",
        "fun nextIndex(",
    ],
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/QueueSync.kt": [
        "interface QueueSync {",
        "suspend fun push(items: List<QueueItem>, mode: PlayMode, startAt: Int)",
        "object NativeQueueSandbox {",
    ],
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/PlayerController.kt": [
        "class PlayerController(",
        "val state: StateFlow<PlayerState>",
        "suspend fun restore(snapshot: PlaybackSnapshot, items: List<QueueItem>)",
        "if (autoPlay) engine.play(h) else engine.pause(h)",
    ],
    # 03-08 §3.1 / §5 / §6 / §13 T01~T09、T13、T14（T-P3）
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/CastUriResolver.kt": [
        "class CastUriResolver(",
        "suspend fun resolve(track: Track): CastUri",
        "if (settings.forceWebdavProxy || driverPreferProxy)",
        "interface CastProxyPort {",
    ],
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/DescriptionParser.kt": [
        "object DescriptionParser {",
        "fun resolveUrl(base: String, control: String): String",
        "DlnaDescParse",
    ],
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/DidlLiteBuilder.kt": [
        "const val CONTENT_FEATURES",
        "fun escape(value: String): String",
        "fun formatDuration(durationMs: Long): String",
    ],
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/SoapController.kt": [
        "fun soapAction(serviceType: String, action: String): String",
        "fun setAvTransportUri(",
        "fun errorCode(body: String): Int?",
    ],
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/SsdpDiscovery.kt": [
        "fun buildMSearch(st: String, mx: Int = 3): String",
        "fun parseResponses(raw: Iterable<String>): List<SsdpResponse>",
        "interface MulticastLockPort {",
    ],
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/ManualDmrProbe.kt": [
        "fun candidateUrls(spec: ManualDmrSpec): List<String>",
        "suspend fun probe(",
    ],
}

# :core-model 零 Android 依赖（03-01 §14 #1）
FORBIDDEN_IMPORTS = re.compile(
    r"^\s*import\s+(android\.|androidx\.|okhttp3\.|com\.netease\.|retrofit2\.)",
    re.MULTILINE,
)


def main() -> int:
    failures: list[str] = []
    warnings: list[str] = []

    for rel in GRADLE_FILES + CONTRACT_FILES:
        if not (ROOT / rel).is_file():
            failures.append(f"缺文件 {rel}")
    for script in CHECK_SCRIPTS:
        if not (ROOT / "tools" / script).is_file():
            failures.append(f"缺检查脚本 tools/{script}")

    catalog = ROOT / "gradle" / "libs.versions.toml"
    if catalog.is_file():
        text = catalog.read_text(encoding="utf-8")
        if re.search(r'=\s*"(2\.x|8\.x|[\d]+\.x)"', text):
            failures.append("libs.versions.toml 仍含 x 占位版本")
        else:
            print("[PASS] version catalog 已钉死精确版本（无 2.x / 8.x 占位）")

    # 03-11 §1：权限清单在 Kotlin 真相源与 C3 静态检查之间不得漂移。
    # 两处都逐字写出权限字面量，任一侧增删都会被此比对拦截。
    perm_re = re.compile(r'"(android\.permission\.[A-Z_]+)"')
    kt_catalog = (
        ROOT / "core-common" / "src" / "main" / "kotlin" / "com" / "example"
        / "cloudmusic" / "common" / "PermissionCatalog.kt"
    )
    py_c3 = ROOT / "tools" / "check-permissions.py"
    if kt_catalog.is_file() and py_c3.is_file():
        kt_perms = set(perm_re.findall(kt_catalog.read_text(encoding="utf-8")))
        py_perms = set(perm_re.findall(py_c3.read_text(encoding="utf-8")))
        if kt_perms and kt_perms == py_perms:
            print(f"[PASS] 03-11 §1 权限清单：Kotlin 真相源 ↔ C3 静态检查一致（{len(kt_perms)} 条）")
        else:
            failures.append(
                f"权限清单漂移：仅 Kotlin={sorted(kt_perms - py_perms)}，"
                f"仅 C3={sorted(py_perms - kt_perms)}"
            )

    # 03-11 §1.1：:app Manifest 的权限集合必须与 C3 允许清单逐项一致。
    app_manifest = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
    if app_manifest.is_file() and py_c3.is_file():
        declared = set(re.findall(
            r'<uses-permission\b[^>]*android:name\s*=\s*"([^"]+)"',
            app_manifest.read_text(encoding="utf-8"),
        ))
        match = re.search(r"^ALLOWED\s*=\s*\{(.*?)^\}", py_c3.read_text(encoding="utf-8"), re.M | re.S)
        allowed = set(perm_re.findall(match.group(1))) if match else set()
        if allowed and declared == allowed:
            print(f"[PASS] 03-11 §1.1 Manifest 权限 ≡ C3 允许清单（{len(declared)} 项）")
        else:
            failures.append(
                f"Manifest 权限漂移：仅 Manifest={sorted(declared - allowed)}，"
                f"仅 C3={sorted(allowed - declared)}"
            )

    # 03-01 §4.1：全项目唯一的 Driver 注册点，@InstallIn(SingletonComponent) 只能在 :core-di。
    install_in = [
        p.relative_to(ROOT).as_posix()
        for p in sorted(ROOT.glob("*/src/main/kotlin/**/*.kt"))
        if "@InstallIn(SingletonComponent::class)" in p.read_text(encoding="utf-8")
    ]
    outside = [f for f in install_in if not f.startswith("core-di/")]
    if outside:
        failures.append(f"Hilt 注册点外泄（03-01 §4.1 要求唯一，只允许 :core-di）：{outside}")
    elif install_in:
        print(f"[PASS] 03-01 §4.1 Hilt 唯一注册点：@InstallIn 全部位于 :core-di（{len(install_in)} 个文件）")

    for rel, needles in SIGNATURES.items():
        path = ROOT / rel
        if not path.is_file():
            continue
        body = path.read_text(encoding="utf-8")
        for needle in needles:
            if needle not in body:
                failures.append(f"契约签名漂移 {rel}: 缺 {needle!r}")

    model_dir = ROOT / "core-model" / "src" / "main"
    for path in model_dir.rglob("*.kt"):
        hit = FORBIDDEN_IMPORTS.search(path.read_text(encoding="utf-8"))
        if hit:
            failures.append(f":core-model 出现禁止依赖 {path.name}: {hit.group(0).strip()}")

    # 03-01 §12 的 T01~T20：后四条分布在 strm / webdav 驱动测试中
    tests = "".join(
        p.read_text(encoding="utf-8")
        for p in sorted(ROOT.glob("*/src/test/kotlin/**/*.kt"))
    )
    missing_tests = [f"T{i:02d}" for i in range(1, 21) if f"T{i:02d}" not in tests]
    if missing_tests:
        failures.append(f"契约测试编号缺覆盖：{missing_tests}")
    else:
        print("[PASS] 03-01 §12 的 T01~T20 编号全部有对应测试")

    if (ROOT / "core-common" / "src" / "main" / "kotlin" / "com" / "example" /
            "cloudmusic" / "common" / "PathNormalizer.kt").is_file():
        warnings.append("实现期修正 C-01：路径规范化下沉至 :core-common（见 PathNormalizer KDoc）")
    if (ROOT / "datasource-api" / "src" / "main" / "kotlin" / "com" / "example" /
            "cloudmusic" / "datasource" / "TextReadCapability.kt").is_file():
        warnings.append("实现期契约回补 C-02：TextReadCapability（03-01 §5.1 可选能力模式）")
    if (ROOT / "core-storage" / "src" / "main" / "kotlin" / "com" / "example" /
            "cloudmusic" / "storage" / "InMemoryLibrary.kt").is_file():
        warnings.append("实现期补充 C-03（Playlist/TrackEntity 就近定义）/ 澄清 C-04（重扫恢复走 ScanReconciler 显式通道），见 12 §13")
        warnings.append("契约演进 C-05（EngineSource 可选 url/localPath）/ C-06（ProxyStartPolicy 触发器 ④）已落地，见 16 §4 / 12 §21")

    print(f"[INFO] 检查 {len(GRADLE_FILES)} 个构建文件 / {len(CONTRACT_FILES)} 个契约源码与测试 / "
          f"{len(CHECK_SCRIPTS)} 个工具脚本")
    for w in warnings:
        print(f"[NOTE] {w}")
    if failures:
        for f in failures:
            print(f"[FAIL] {f}")
        print(f"\n结果：FAIL（{len(failures)} 项）。注意：静态自检不替代 ./gradlew test")
        return 1
    print("\n结果：PASS。结构齐全、契约签名在位、禁止依赖为零；"
          "编译与 JVM 单测请用 ./gradlew test（本机已验证通过）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
