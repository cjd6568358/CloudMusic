@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * Java SDK 入口层 JNI 清单 —— 由 tools/gen_catalog.py 全量生成，勿手改。
 *
 * 证据：analysis/engine-jni-classes.json（19 类 / 162 native 方法 / 202 JNI 符号）。
 * 脱敏口径（项目 C2 纪律）：本文件只登记**简单类名 / 简单类型名**，
 * 完整 descriptor 与包路径只保留在源 JSON 与 spec/catalog-manifest.json 里，
 * Kotlin 源码零竞品包路径字面量。重新生成后必须跑 build.py + audit.py。
 *
 * 本文件是**数据层全量登记**，不是控制逻辑翻译；证据等级见 spec/LOGIC-SPEC.md C 组。
 */

data class NmJniClassEntry(
    val simpleName: String,
    val preciseJniClass: Boolean,
    val dex: String,
    val nativeMethodCount: Int,
)

data class NmNativeMethodEntry(
    val ownerSimpleName: String,
    val name: String,
    val javaReturn: String,
    val javaParams: List<String>,
    val descriptorParams: List<String>,
    val declaration: String,
)

object NmJniCatalog {
    // @spec C-01：19 个真类，其中 12 个精确 JNI 类、7 个非精确类
    const val CLASS_COUNT: Int = 19
    const val PRECISE_CLASS_COUNT: Int = 12

    // @spec C-02：162 个 native 方法，按类全量登记
    const val METHOD_COUNT: Int = 162

    // @spec C-03：202 个 JNI 符号按 4 个 so 分布
    const val JNI_SYMBOL_COUNT: Int = 202
    val JNI_SYMBOLS_BY_LIB: Map<String, Int> = mapOf(
        "libncmplayerengine.so" to 118,
        "libaudioplayer.so" to 2,
        "libnmplaylist.so" to 24,
        "libNMCommonCache.so" to 58,
    )

    // @spec C-06：已知增量（2 处）
    val DELTA_KNOWN: List<String> = listOf(
        "Playback.nativeNotifyAudioEffectChanged (1)",
        "PlaylistJniBridge *Raw / 日志 / 更新类方法 (7)",
    )

    // @spec C-01 / C-05：19 个类（simpleName / precise / dex / 方法数）
    val CLASSES: List<NmJniClassEntry> = listOf(
        NmJniClassEntry("AudioPlayerUnit", true, "classes14.dex", 27),
        NmJniClassEntry("DataProvider", false, "classes14.dex", 0),
        NmJniClassEntry("MusicInfo", false, "classes14.dex", 0),
        NmJniClassEntry("NativeDataRequestErrorCallback", true, "classes14.dex", 1),
        NmJniClassEntry("Playback", true, "classes14.dex", 60),
        NmJniClassEntry("PlaybackDataSource", true, "classes14.dex", 7),
        NmJniClassEntry("PlaybackEngine", true, "classes14.dex", 24),
        NmJniClassEntry("PlaybackEngineListener", false, "classes14.dex", 0),
        NmJniClassEntry("PlaybackListener", false, "classes14.dex", 0),
        NmJniClassEntry("PlayerDataSource", false, "classes14.dex", 0),
        NmJniClassEntry("PlayerUnitListener", false, "classes14.dex", 0),
        NmJniClassEntry("UrlResult", false, "classes14.dex", 0),
        NmJniClassEntry("NEAudioDevice\$NEAudioDeviceOnPlaybackPositionUpdateListener", true, "classes16.dex", 2),
        NmJniClassEntry("PlaylistJniBridge", true, "classes5.dex", 31),
        NmJniClassEntry("NMBufferSource", true, "classes5.dex", 1),
        NmJniClassEntry("NMCacheBase", true, "classes5.dex", 1),
        NmJniClassEntry("NMVirtualFile", true, "classes5.dex", 1),
        NmJniClassEntry("NMVirtualFileManager", true, "classes5.dex", 5),
        NmJniClassEntry("SourceDataRequest", true, "classes5.dex", 2),
    )

    // @spec C-02 / C-07：162 个方法，五字段全量（已脱敏）
    val METHODS: List<NmNativeMethodEntry> = listOf(
        NmNativeMethodEntry("AudioPlayerUnit", "nativeApplyAudioEffect", "void", listOf("long"), listOf("J"), "void nativeApplyAudioEffect(long)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeCreate", "long", listOf("long"), listOf("J"), "long nativeCreate(long)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeDestroy", "void", listOf("long"), listOf("J"), "void nativeDestroy(long)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeGetDuration", "long", listOf("long"), listOf("J"), "long nativeGetDuration(long)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeGetMNNXPUType", "int", listOf("long"), listOf("J"), "int nativeGetMNNXPUType(long)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeGetPlayingTime", "long", listOf("long"), listOf("J"), "long nativeGetPlayingTime(long)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeGetState", "int", listOf("long"), listOf("J"), "int nativeGetState(long)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeGetVolume", "float", listOf("long"), listOf("J"), "float nativeGetVolume(long)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeLoadAIFilter", "void", listOf("long", "String", "int"), listOf("J", "String", "I"), "void nativeLoadAIFilter(long, String, int)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeLoadAIModel", "int", listOf("long", "byte[]", "int", "int"), listOf("J", "[B", "I", "I"), "int nativeLoadAIModel(long, byte[], int, int)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativePause", "void", listOf("long", "boolean"), listOf("J", "Z"), "void nativePause(long, boolean)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativePlay", "void", listOf("long", "boolean"), listOf("J", "Z"), "void nativePlay(long, boolean)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativePrepareAsync", "int", listOf("long"), listOf("J"), "int nativePrepareAsync(long)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeReset", "int", listOf("long"), listOf("J"), "int nativeReset(long)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeSeek", "long", listOf("long", "long"), listOf("J", "J"), "long nativeSeek(long, long)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeSetAudioEffect", "void", listOf("long", "long"), listOf("J", "J"), "void nativeSetAudioEffect(long, long)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeSetAudioEffectCallback", "void", listOf("long", "AudioEffectReadyCallback"), listOf("J", "AudioEffectReadyCallback"), "void nativeSetAudioEffectCallback(long, AudioEffectReadyCallback)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeSetAudioIFEnable", "void", listOf("long", "int"), listOf("J", "I"), "void nativeSetAudioIFEnable(long, int)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeSetAudioIFProcess", "void", listOf("long", "long"), listOf("J", "J"), "void nativeSetAudioIFProcess(long, long)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeSetAudioIFRatio", "void", listOf("long", "int"), listOf("J", "I"), "void nativeSetAudioIFRatio(long, int)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeSetDataSource", "int", listOf("long", "PlayerDataSource"), listOf("J", "PlayerDataSource"), "int nativeSetDataSource(long, PlayerDataSource)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeSetListener", "void", listOf("long", "PlayerUnitListener"), listOf("J", "PlayerUnitListener"), "void nativeSetListener(long, PlayerUnitListener)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeSetMNNXPUType", "void", listOf("long", "int"), listOf("J", "I"), "void nativeSetMNNXPUType(long, int)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeSetVividDecInfo", "void", listOf("long", "String", "String", "int"), listOf("J", "String", "String", "I"), "void nativeSetVividDecInfo(long, String, String, int)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeSetVolume", "int", listOf("long", "float"), listOf("J", "F"), "int nativeSetVolume(long, float)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeStartAIModel", "int", listOf("long", "int", "int"), listOf("J", "I", "I"), "int nativeStartAIModel(long, int, int)"),
        NmNativeMethodEntry("AudioPlayerUnit", "nativeStop", "void", listOf("long"), listOf("J"), "void nativeStop(long)"),
        NmNativeMethodEntry("NativeDataRequestErrorCallback", "nativeOnError", "void", listOf("long", "long", "int", "int"), listOf("J", "J", "I", "I"), "void nativeOnError(long, long, int, int)"),
        NmNativeMethodEntry("Playback", "nativeAttachAudioEffect", "void", listOf("long", "long", "String"), listOf("J", "J", "String"), "void nativeAttachAudioEffect(long, long, String)"),
        NmNativeMethodEntry("Playback", "nativeBeginQualityContinuation", "void", listOf("long"), listOf("J"), "void nativeBeginQualityContinuation(long)"),
        NmNativeMethodEntry("Playback", "nativeCancelTransition", "void", listOf("long"), listOf("J"), "void nativeCancelTransition(long)"),
        NmNativeMethodEntry("Playback", "nativeChangeQuality", "void", listOf("long", "int"), listOf("J", "I"), "void nativeChangeQuality(long, int)"),
        NmNativeMethodEntry("Playback", "nativeCommitTransitionSwitch", "void", listOf("long"), listOf("J"), "void nativeCommitTransitionSwitch(long)"),
        NmNativeMethodEntry("Playback", "nativeCreate", "long", listOf("PlaybackPlatform", "long", "PlaybackListener"), listOf("PlaybackPlatform", "J", "PlaybackListener"), "long nativeCreate(PlaybackPlatform, long, PlaybackListener)"),
        NmNativeMethodEntry("Playback", "nativeDiscardUSBDevice", "void", listOf("long"), listOf("J"), "void nativeDiscardUSBDevice(long)"),
        NmNativeMethodEntry("Playback", "nativeGetAudioDeviceType", "int", listOf("long"), listOf("J"), "int nativeGetAudioDeviceType(long)"),
        NmNativeMethodEntry("Playback", "nativeGetAudioLatency", "long", listOf("long"), listOf("J"), "long nativeGetAudioLatency(long)"),
        NmNativeMethodEntry("Playback", "nativeGetAudioTrackSessionId", "int", listOf("long"), listOf("J"), "int nativeGetAudioTrackSessionId(long)"),
        NmNativeMethodEntry("Playback", "nativeGetCacheProgress", "PlaybackCacheProgress", listOf("long"), listOf("J"), "PlaybackCacheProgress nativeGetCacheProgress(long)"),
        NmNativeMethodEntry("Playback", "nativeGetCurrentBitrate", "int", listOf("long"), listOf("J"), "int nativeGetCurrentBitrate(long)"),
        NmNativeMethodEntry("Playback", "nativeGetCurrentPosition", "long", listOf("long"), listOf("J"), "long nativeGetCurrentPosition(long)"),
        NmNativeMethodEntry("Playback", "nativeGetCurrentSongId", "String", listOf("long"), listOf("J"), "String nativeGetCurrentSongId(long)"),
        NmNativeMethodEntry("Playback", "nativeGetDuration", "long", listOf("long"), listOf("J"), "long nativeGetDuration(long)"),
        NmNativeMethodEntry("Playback", "nativeGetPlaybackState", "int", listOf("long"), listOf("J"), "int nativeGetPlaybackState(long)"),
        NmNativeMethodEntry("Playback", "nativeGetPlayedTime", "long", listOf("long", "String"), listOf("J", "String"), "long nativeGetPlayedTime(long, String)"),
        NmNativeMethodEntry("Playback", "nativeGetPlayedTimeWithSpeed", "long", listOf("long", "String"), listOf("J", "String"), "long nativeGetPlayedTimeWithSpeed(long, String)"),
        NmNativeMethodEntry("Playback", "nativeGetRoundPlayedTime", "long", listOf("long", "String"), listOf("J", "String"), "long nativeGetRoundPlayedTime(long, String)"),
        NmNativeMethodEntry("Playback", "nativeGetRoundPlayedTimeWithSpeed", "long", listOf("long", "String"), listOf("J", "String"), "long nativeGetRoundPlayedTimeWithSpeed(long, String)"),
        NmNativeMethodEntry("Playback", "nativeGetTransitionState", "int", listOf("long"), listOf("J"), "int nativeGetTransitionState(long)"),
        NmNativeMethodEntry("Playback", "nativeGetUnitPropertyBySongTag", "String", listOf("long", "String", "String"), listOf("J", "String", "String"), "String nativeGetUnitPropertyBySongTag(long, String, String)"),
        NmNativeMethodEntry("Playback", "nativeGetVolume", "float", listOf("long"), listOf("J"), "float nativeGetVolume(long)"),
        NmNativeMethodEntry("Playback", "nativeIsBuffering", "boolean", listOf("long"), listOf("J"), "boolean nativeIsBuffering(long)"),
        NmNativeMethodEntry("Playback", "nativeIsMuted", "boolean", listOf("long"), listOf("J"), "boolean nativeIsMuted(long)"),
        NmNativeMethodEntry("Playback", "nativeIsPlaying", "boolean", listOf("long"), listOf("J"), "boolean nativeIsPlaying(long)"),
        NmNativeMethodEntry("Playback", "nativeIsSeeking", "boolean", listOf("long"), listOf("J"), "boolean nativeIsSeeking(long)"),
        NmNativeMethodEntry("Playback", "nativeMarkPlaybackRoundStarted", "boolean", listOf("long"), listOf("J"), "boolean nativeMarkPlaybackRoundStarted(long)"),
        NmNativeMethodEntry("Playback", "nativeMusicInfoToDataSource", "long", listOf("long", "MusicInfo", "DataSourcePlatform"), listOf("J", "MusicInfo", "DataSourcePlatform"), "long nativeMusicInfoToDataSource(long, MusicInfo, DataSourcePlatform)"),
        NmNativeMethodEntry("Playback", "nativeNotifyAudioEffectChanged", "void", listOf("long"), listOf("J"), "void nativeNotifyAudioEffectChanged(long)"),
        NmNativeMethodEntry("Playback", "nativeNotifyNetworkQualityReduce", "void", listOf("long"), listOf("J"), "void nativeNotifyNetworkQualityReduce(long)"),
        NmNativeMethodEntry("Playback", "nativePause", "void", listOf("long", "boolean"), listOf("J", "Z"), "void nativePause(long, boolean)"),
        NmNativeMethodEntry("Playback", "nativePlay", "void", listOf("long", "boolean"), listOf("J", "Z"), "void nativePlay(long, boolean)"),
        NmNativeMethodEntry("Playback", "nativePrepareNext", "int", listOf("long", "long", "MusicInfo", "String", "String"), listOf("J", "J", "MusicInfo", "String", "String"), "int nativePrepareNext(long, long, MusicInfo, String, String)"),
        NmNativeMethodEntry("Playback", "nativeRelease", "void", listOf("long"), listOf("J"), "void nativeRelease(long)"),
        NmNativeMethodEntry("Playback", "nativeResume", "void", listOf("long"), listOf("J"), "void nativeResume(long)"),
        NmNativeMethodEntry("Playback", "nativeSeekTo", "void", listOf("long", "long"), listOf("J", "J"), "void nativeSeekTo(long, long)"),
        NmNativeMethodEntry("Playback", "nativeSetAudioDeviceType", "void", listOf("long", "int"), listOf("J", "I"), "void nativeSetAudioDeviceType(long, int)"),
        NmNativeMethodEntry("Playback", "nativeSetAudioListener", "void", listOf("long", "long"), listOf("J", "J"), "void nativeSetAudioListener(long, long)"),
        NmNativeMethodEntry("Playback", "nativeSetAudioSystemVolume", "void", listOf("long", "int", "int", "int"), listOf("J", "I", "I", "I"), "void nativeSetAudioSystemVolume(long, int, int, int)"),
        NmNativeMethodEntry("Playback", "nativeSetBackgroundMusicVolume", "void", listOf("long", "float"), listOf("J", "F"), "void nativeSetBackgroundMusicVolume(long, float)"),
        NmNativeMethodEntry("Playback", "nativeSetDataSource", "int", listOf("long", "long", "MusicInfo"), listOf("J", "J", "MusicInfo"), "int nativeSetDataSource(long, long, MusicInfo)"),
        NmNativeMethodEntry("Playback", "nativeSetHeadSetType", "void", listOf("long", "int"), listOf("J", "I"), "void nativeSetHeadSetType(long, int)"),
        NmNativeMethodEntry("Playback", "nativeSetListener", "void", listOf("long", "PlaybackListener"), listOf("J", "PlaybackListener"), "void nativeSetListener(long, PlaybackListener)"),
        NmNativeMethodEntry("Playback", "nativeSetMusicMode", "void", listOf("long", "int"), listOf("J", "I"), "void nativeSetMusicMode(long, int)"),
        NmNativeMethodEntry("Playback", "nativeSetMusicVoiceVolume", "void", listOf("long", "float"), listOf("J", "F"), "void nativeSetMusicVoiceVolume(long, float)"),
        NmNativeMethodEntry("Playback", "nativeSetMute", "void", listOf("long", "boolean"), listOf("J", "Z"), "void nativeSetMute(long, boolean)"),
        NmNativeMethodEntry("Playback", "nativeSetNativeABTestFlags", "void", listOf("long", "String[]", "int[]"), listOf("J", "[String", "[I"), "void nativeSetNativeABTestFlags(long, String[], int[])"),
        NmNativeMethodEntry("Playback", "nativeSetNextDataSource", "void", listOf("long", "long", "MusicInfo"), listOf("J", "J", "MusicInfo"), "void nativeSetNextDataSource(long, long, MusicInfo)"),
        NmNativeMethodEntry("Playback", "nativeSetPlaybackSpeed", "void", listOf("long", "float"), listOf("J", "F"), "void nativeSetPlaybackSpeed(long, float)"),
        NmNativeMethodEntry("Playback", "nativeSetSepTrackEnabled", "void", listOf("long", "int"), listOf("J", "I"), "void nativeSetSepTrackEnabled(long, int)"),
        NmNativeMethodEntry("Playback", "nativeSetSupportDolby", "void", listOf("long", "boolean"), listOf("J", "Z"), "void nativeSetSupportDolby(long, boolean)"),
        NmNativeMethodEntry("Playback", "nativeSetTransitionInfo", "int", listOf("long", "String", "String"), listOf("J", "String", "String"), "int nativeSetTransitionInfo(long, String, String)"),
        NmNativeMethodEntry("Playback", "nativeSetUnitEventListener", "void", listOf("long", "UnitEventListener"), listOf("J", "UnitEventListener"), "void nativeSetUnitEventListener(long, UnitEventListener)"),
        NmNativeMethodEntry("Playback", "nativeSetVolume", "void", listOf("long", "float"), listOf("J", "F"), "void nativeSetVolume(long, float)"),
        NmNativeMethodEntry("Playback", "nativeStop", "void", listOf("long"), listOf("J"), "void nativeStop(long)"),
        NmNativeMethodEntry("Playback", "nativeUnattachAudioEffect", "void", listOf("long", "long"), listOf("J", "J"), "void nativeUnattachAudioEffect(long, long)"),
        NmNativeMethodEntry("Playback", "nativeUpdateAudioEffectConfigBySongTag", "void", listOf("long", "String", "String"), listOf("J", "String", "String"), "void nativeUpdateAudioEffectConfigBySongTag(long, String, String)"),
        NmNativeMethodEntry("Playback", "nativeUseUSBDevice", "void", listOf("long", "int"), listOf("J", "I"), "void nativeUseUSBDevice(long, int)"),
        NmNativeMethodEntry("Playback", "nativeWriteCacheMeta", "void", listOf("long", "String", "String"), listOf("J", "String", "String"), "void nativeWriteCacheMeta(long, String, String)"),
        NmNativeMethodEntry("PlaybackDataSource", "nativeCancelPreload", "boolean", listOf("long"), listOf("J"), "boolean nativeCancelPreload(long)"),
        NmNativeMethodEntry("PlaybackDataSource", "nativePreload", "boolean", listOf("long", "long"), listOf("J", "J"), "boolean nativePreload(long, long)"),
        NmNativeMethodEntry("PlaybackDataSource", "nativeRelease", "void", listOf("long"), listOf("J"), "void nativeRelease(long)"),
        NmNativeMethodEntry("PlaybackDataSource", "nativeReleaseEngineCacheLoadConfigHandle", "void", listOf("long"), listOf("J"), "void nativeReleaseEngineCacheLoadConfigHandle(long)"),
        NmNativeMethodEntry("PlaybackDataSource", "nativeRetainEngineCacheLoadConfigHandle", "long", listOf("long"), listOf("J"), "long nativeRetainEngineCacheLoadConfigHandle(long)"),
        NmNativeMethodEntry("PlaybackDataSource", "nativeSetEngineCacheLoadConfig", "boolean", listOf("long", "long", "long", "long", "long", "long"), listOf("J", "J", "J", "J", "J", "J"), "boolean nativeSetEngineCacheLoadConfig(long, long, long, long, long, long)"),
        NmNativeMethodEntry("PlaybackDataSource", "nativeSetPreloadCallback", "boolean", listOf("long", "boolean"), listOf("J", "Z"), "boolean nativeSetPreloadCallback(long, boolean)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineAttachAudioEffect", "void", listOf("long", "long", "String"), listOf("J", "J", "String"), "void nativeEngineAttachAudioEffect(long, long, String)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineCancelTransition", "void", listOf("long"), listOf("J"), "void nativeEngineCancelTransition(long)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineCreate", "long", listOf("long"), listOf("J"), "long nativeEngineCreate(long)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineGetCurrentPosition", "long", listOf("long"), listOf("J"), "long nativeEngineGetCurrentPosition(long)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineGetDuration", "long", listOf("long"), listOf("J"), "long nativeEngineGetDuration(long)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineGetVolume", "float", listOf("long"), listOf("J"), "float nativeEngineGetVolume(long)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineIsPlaying", "boolean", listOf("long"), listOf("J"), "boolean nativeEngineIsPlaying(long)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineNotifyAudioEffectChanged", "void", listOf("long"), listOf("J"), "void nativeEngineNotifyAudioEffectChanged(long)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEnginePause", "void", listOf("long", "boolean"), listOf("J", "Z"), "void nativeEnginePause(long, boolean)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEnginePlay", "void", listOf("long", "boolean"), listOf("J", "Z"), "void nativeEnginePlay(long, boolean)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEnginePrepareAsync", "int", listOf("long"), listOf("J"), "int nativeEnginePrepareAsync(long)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEnginePrepareNext", "int", listOf("long", "PlayerDataSource", "String", "String"), listOf("J", "PlayerDataSource", "String", "String"), "int nativeEnginePrepareNext(long, PlayerDataSource, String, String)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineRelease", "void", listOf("long"), listOf("J"), "void nativeEngineRelease(long)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineReset", "int", listOf("long"), listOf("J"), "int nativeEngineReset(long)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineSeekTo", "void", listOf("long", "long"), listOf("J", "J"), "void nativeEngineSeekTo(long, long)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineSetAudioEffectCallback", "void", listOf("long", "AudioEffectReadyCallback"), listOf("J", "AudioEffectReadyCallback"), "void nativeEngineSetAudioEffectCallback(long, AudioEffectReadyCallback)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineSetAudioListener", "void", listOf("long", "long"), listOf("J", "J"), "void nativeEngineSetAudioListener(long, long)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineSetAudioVividInfo", "void", listOf("long", "String", "String", "int"), listOf("J", "String", "String", "I"), "void nativeEngineSetAudioVividInfo(long, String, String, int)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineSetDataSource", "int", listOf("long", "PlayerDataSource"), listOf("J", "PlayerDataSource"), "int nativeEngineSetDataSource(long, PlayerDataSource)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineSetListener", "void", listOf("long", "PlaybackEngineListener"), listOf("J", "PlaybackEngineListener"), "void nativeEngineSetListener(long, PlaybackEngineListener)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineSetPlaybackSpeed", "void", listOf("long", "float"), listOf("J", "F"), "void nativeEngineSetPlaybackSpeed(long, float)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineSetVolume", "void", listOf("long", "float"), listOf("J", "F"), "void nativeEngineSetVolume(long, float)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineStop", "void", listOf("long"), listOf("J"), "void nativeEngineStop(long)"),
        NmNativeMethodEntry("PlaybackEngine", "nativeEngineUnattachAudioEffect", "void", listOf("long", "long"), listOf("J", "J"), "void nativeEngineUnattachAudioEffect(long, long)"),
        NmNativeMethodEntry("NEAudioDevice\$NEAudioDeviceOnPlaybackPositionUpdateListener", "onMarkerReachedNative", "void", listOf("long"), listOf("J"), "void onMarkerReachedNative(long)"),
        NmNativeMethodEntry("NEAudioDevice\$NEAudioDeviceOnPlaybackPositionUpdateListener", "onPeriodicNotificationNative", "void", listOf("long"), listOf("J"), "void onPeriodicNotificationNative(long)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeAppendMusicItems", "boolean", listOf("long[]", "byte[][]", "String[]", "int"), listOf("[J", "[[B", "[String", "I"), "boolean nativeAppendMusicItems(long[], byte[][], String[], int)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeClearAll", "boolean", listOf(), listOf(), "boolean nativeClearAll()"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeClearCurrentAndSwitchToRecentRaw", "Object", listOf("int"), listOf("I"), "Object nativeClearCurrentAndSwitchToRecentRaw(int)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeDeleteRecentPlaylist", "boolean", listOf("int"), listOf("I"), "boolean nativeDeleteRecentPlaylist(int)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeDestroy", "void", listOf(), listOf(), "void nativeDestroy()"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeEnterDerivedMode", "boolean", listOf("long[]", "byte[][]", "int", "int", "int", "String", "String", "String", "String", "String[]", "int"), listOf("[J", "[[B", "I", "I", "I", "String", "String", "String", "String", "[String", "I"), "boolean nativeEnterDerivedMode(long[], byte[][], int, int, int, String, String, String, String, String[], int)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeExitDerivedModeRaw", "Object", listOf(), listOf(), "Object nativeExitDerivedModeRaw()"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeGetOriginSnapshotMetaRaw", "Object", listOf(), listOf(), "Object nativeGetOriginSnapshotMetaRaw()"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeGetPlaybackPositionRaw", "Object", listOf(), listOf(), "Object nativeGetPlaybackPositionRaw()"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeGetSnapshotMetaRaw", "Object", listOf(), listOf(), "Object nativeGetSnapshotMetaRaw()"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeGetVersion", "String", listOf(), listOf(), "String nativeGetVersion()"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeImportRecentPlaylist", "boolean", listOf("long[]", "byte[][]", "String[]", "String", "String", "String", "String", "int", "int", "int"), listOf("[J", "[[B", "[String", "String", "String", "String", "String", "I", "I", "I"), "boolean nativeImportRecentPlaylist(long[], byte[][], String[], String, String, String, String, int, int, int)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeInit", "int", listOf("String", "int"), listOf("String", "I"), "int nativeInit(String, int)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeInsertMusicItemsAt", "boolean", listOf("long[]", "byte[][]", "String[]", "int", "int"), listOf("[J", "[[B", "[String", "I", "I"), "boolean nativeInsertMusicItemsAt(long[], byte[][], String[], int, int)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeListRecentPlaylistsRaw", "Object[]", listOf("int"), listOf("I"), "Object[] nativeListRecentPlaylistsRaw(int)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativePersistList", "boolean", listOf("long[]", "byte[][]", "String[]", "String", "String", "String", "String", "int", "int"), listOf("[J", "[[B", "[String", "String", "String", "String", "String", "I", "I"), "boolean nativePersistList(long[], byte[][], String[], String, String, String, String, int, int)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativePromoteRecentToCurrentRaw", "Object", listOf("int", "int"), listOf("I", "I"), "Object nativePromoteRecentToCurrentRaw(int, int)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeReadRecentPlaylistRaw", "Object[]", listOf("int", "int", "int"), listOf("I", "I", "I"), "Object[] nativeReadRecentPlaylistRaw(int, int, int)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeReadSnapshotRawRangeRaw", "Object[]", listOf("long", "int", "int"), listOf("J", "I", "I"), "Object[] nativeReadSnapshotRawRangeRaw(long, int, int)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeRemoveMusicItems", "boolean", listOf("long[]"), listOf("[J"), "boolean nativeRemoveMusicItems(long[])"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeReplaceCurrentSnapshotContent", "boolean", listOf("long[]", "byte[][]", "String[]", "int", "int"), listOf("[J", "[[B", "[String", "I", "I"), "boolean nativeReplaceCurrentSnapshotContent(long[], byte[][], String[], int, int)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeRestoreFromDb", "RestoreResult", listOf(), listOf(), "RestoreResult nativeRestoreFromDb()"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeSetDbAdapter", "int", listOf("PlaylistDbAdapterProxy"), listOf("PlaylistDbAdapterProxy"), "int nativeSetDbAdapter(PlaylistDbAdapterProxy)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeSetLogCallback", "void", listOf("LogCallback"), listOf("LogCallback"), "void nativeSetLogCallback(LogCallback)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeSetLogEnabled", "void", listOf("boolean"), listOf("Z"), "void nativeSetLogEnabled(boolean)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeSetLogLevel", "void", listOf("int"), listOf("I"), "void nativeSetLogLevel(int)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeUpdateCurrentSnapshotExtraInfo", "boolean", listOf("String"), listOf("String"), "boolean nativeUpdateCurrentSnapshotExtraInfo(String)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeUpdatePlayMode", "boolean", listOf("int"), listOf("I"), "boolean nativeUpdatePlayMode(int)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeUpdatePlaybackPosition", "boolean", listOf("int", "int", "int"), listOf("I", "I", "I"), "boolean nativeUpdatePlaybackPosition(int, int, int)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeUpdatePlaybackProgress", "boolean", listOf("int", "int"), listOf("I", "I"), "boolean nativeUpdatePlaybackProgress(int, int)"),
        NmNativeMethodEntry("PlaylistJniBridge", "nativeUpdateShuffleOrder", "boolean", listOf("String"), listOf("String"), "boolean nativeUpdateShuffleOrder(String)"),
        NmNativeMethodEntry("NMBufferSource", "DeletePtr", "void", listOf("long"), listOf("J"), "void DeletePtr(long)"),
        NmNativeMethodEntry("NMCacheBase", "GetVersion", "String", listOf(), listOf(), "String GetVersion()"),
        NmNativeMethodEntry("NMVirtualFile", "DeletePtr", "void", listOf(), listOf(), "void DeletePtr()"),
        NmNativeMethodEntry("NMVirtualFileManager", "DeletePtr", "void", listOf("long"), listOf("J"), "void DeletePtr(long)"),
        NmNativeMethodEntry("NMVirtualFileManager", "InitPtr", "void", listOf(), listOf(), "void InitPtr()"),
        NmNativeMethodEntry("NMVirtualFileManager", "SetLoggerCallback", "void", listOf("INMLogger", "int"), listOf("INMLogger", "I"), "void SetLoggerCallback(INMLogger, int)"),
        NmNativeMethodEntry("NMVirtualFileManager", "SetNetworkClassInfo", "void", listOf("String"), listOf("String"), "void SetNetworkClassInfo(String)"),
        NmNativeMethodEntry("NMVirtualFileManager", "setABTestStringKeyValueInfo", "void", listOf("String", "int"), listOf("String", "I"), "void setABTestStringKeyValueInfo(String, int)"),
        NmNativeMethodEntry("SourceDataRequest", "DeletePtr", "void", listOf("long"), listOf("J"), "void DeletePtr(long)"),
        NmNativeMethodEntry("SourceDataRequest", "InitPtr", "void", listOf(), listOf(), "void InitPtr()"),
    )

    fun methodsOf(ownerSimpleName: String): List<NmNativeMethodEntry> =
        METHODS.filter { it.ownerSimpleName == ownerSimpleName }

    fun preciseClasses(): List<NmJniClassEntry> = CLASSES.filter { it.preciseJniClass }

    fun distinctMethodNames(): Set<String> = METHODS.map { it.name }.toSet()

    fun methodCountByClass(): Map<String, Int> =
        METHODS.groupingBy { it.ownerSimpleName }.eachCount()
}
