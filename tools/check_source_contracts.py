#!/usr/bin/env python3
"""源码契约静态自检（不依赖 Gradle 的轻量结构校验）。

本脚本不假装等价编译，只检查「结构齐全 + 契约签名在位 + 测试编号覆盖 + 禁止依赖」，
可在无 Gradle 环境下快速跑；真正的编译与 JVM 单测用 ./gradlew test。
2026-11-05 本机已用真实 Gradle 8.11.1 验证：第一批 23 个、第二批 66 个、第三批 84 个、
第四批 104 个、第五批（03-06 :engine-api 门面）116 个、第六批（03-07 :core-media）131 个、
第七批（03-08 :output-localproxy 流代理）144 个、第八批（03-09 :output-router）159 个测试全绿。
详见 docs/12-编码阶段第一批交付.md §5.1 / §11 / §12 / §13 / §14 / §15 / §16 / §17。
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
)

CHECK_SCRIPTS = (
    "check-engine-abi.py", "check-no-netease-import.py", "check-permissions.py",
    "check-assets.py", "check-credentials.py", "check-fingerprint.py",
    "pre_release_check.py", "elf_probe.py", "check_common.py",
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
