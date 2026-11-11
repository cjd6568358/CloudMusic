@file:Suppress("unused")
package cloudmusic.reverse.kernel

/**
 * Java → native 参数槽透传 1:1 翻译（12 §61.56）。
 *
 * ARM64 JNI 约定：x0=JNIEnv*、x1=jobject、x2=long j(native_ptr)、
 * **x3=long j2（Java 侧请求标识 / kind 槽）**、x4+ 其余参数。
 *
 * 证据：analysis/l3-f4-jni-arg-report.md、analysis/engine-jni-classes.json、
 *       docs/05-Native库实测分析.md §2。
 */
object NmJniBridge {

    /** 一次 native 虚调用的参数槽快照。 */
    data class NativeCall(
        val x0: Long,          // native_ptr
        val x1: Long,          // j2，原样透传，全程不改写
        val trampoline: Long,  // bl 目标
        val vtableSlot: Int,   // vtable 偏移
    )

    /**
     * @spec P-01 / P-04
     * `OnNativeDataRequestData @0x347F4`（size 360，数据包路径）：
     * 0x34834 `mov x21,x3` / 0x34838 `mov x22,x2` →
     * 0x34900 `mov x0,x22` / 0x34904 `mov x1,x21` / 0x34914 `bl 0x36F68`。
     * 两条路径的 `(x0,x1) = (native_ptr, j2)` 完全同构，x1 从未被改写或重映射。
     */
    fun onNativeDataRequestData(nativePtr: Long, j2: Long): NativeCall =
        NativeCall(nativePtr, j2, trampoline = 0x36F68, vtableSlot = 0x20)

    /**
     * @spec P-01 / P-02 / P-03
     * `OnNativeDataRequestEnd @0x3495C`（size 360，结束路径）：
     * 0x34988 `cbz x2` 判空 / 0x34990 `mov x23,x2` / 0x349A4 `mov x21,x3` →
     * 0x34A54 `mov x0,x23` / 0x34A58 `mov x1,x21` / 0x34A60 `bl 0x36C70`。
     * trampoline 0x36C70：0x36C90/0x36CA4 再原样放回 x1 → 0x36CAC `ldr x8,[x8,#0x20]`
     * → 0x36CB0 `blr x8`（vtable+0x20 虚调用）。
     */
    fun onNativeDataRequestEnd(nativePtr: Long, j2: Long): NativeCall =
        NativeCall(nativePtr, j2, trampoline = 0x36C70, vtableSlot = 0x20)

    /**
     * @spec P-03：trampoline 派发，x1 原样落到目标函数的 w1。
     * 虚表槽的具体目标类型由运行时决定（§61.56 边界声明：静态反汇编不是 100% C++ 类型证明）。
     */
    fun dispatch(call: NativeCall): Pair<Long, Long> = Pair(call.x0, call.x1)

    /**
     * @spec P-05
     * `OnNetSourceDataDone @0xBEE8` 的 `0xBF08 cmp w1,#2` 正是用这个 w1 做分支。
     */
    fun kindSlotBranch(w1: Long): Int = if (w1 == NmKind.NATIVE_DATA_KIND_PRELOAD) 2 else 1

    /**
     * @spec P-08
     * dex 侧 JNI 类/方法/符号统计（engine-jni-classes.json + docs/05 §2）。
     */
    object Inventory {
        const val PRECISE_JNI_CLASSES: Int = 12
        const val NATIVE_METHODS: Int = 162
        const val JNI_SYMBOLS: Int = 202
        const val LIB_NCMPLAYERENGINE_SYMBOLS: Int = 118
        const val LIB_AUDIOPLAYER_SYMBOLS: Int = 2
        const val LIB_NMPLAYLIST_SYMBOLS: Int = 24
        const val LIB_NMCOMMONCACHE_SYMBOLS: Int = 58
        const val ENGINE_JNI_EXPORTS: Int = 121   // docs/05 §2 实测
        const val PLAYLIST_JNI_EXPORTS: Int = 31
        const val CACHE_JNI_EXPORTS: Int = 58
        const val AUDIOPLAYER_JNI_EXPORTS: Int = 2
        const val SO_TOTAL_ARM64: Int = 167
        const val ENGINE_SO_COUNT_IN_CABIN: Int = 18

        /** @spec P-09：AudioPlayerUnit 27 个 native 方法（节选锚点）。 */
        val AUDIO_PLAYER_UNIT_METHODS: List<String> = listOf(
            "nativeCreate", "nativeDestroy", "nativeSetDataSource", "nativePrepareAsync",
            "nativePlay", "nativePause", "nativeStop", "nativeReset", "nativeSeek",
            "nativeGetDuration", "nativeGetPlayingTime", "nativeGetState", "nativeGetVolume",
            "nativeSetVolume", "nativeSetListener", "nativeApplyAudioEffect", "nativeSetAudioEffect",
            "nativeSetAudioEffectCallback", "nativeLoadAIFilter", "nativeLoadAIModel",
            "nativeStartAIModel", "nativeSetMNNXPUType", "nativeGetMNNXPUType",
            "nativeSetVividDecInfo", "nativeSetAudioIFEnable", "nativeSetAudioIFRatio",
            "nativeSetAudioIFProcess",
        )

        /** @spec P-10：Playback 60 个 native 方法（按语义分组节选）。 */
        val PLAYBACK_METHOD_GROUPS: Map<String, List<String>> = mapOf(
            "state" to listOf("nativeGetPlaybackState", "nativeIsPlaying", "nativeIsSeeking", "nativeIsBuffering", "nativeIsMuted", "nativeGetTransitionState"),
            "position" to listOf("nativeGetCurrentPosition", "nativeGetDuration", "nativeGetPlayedTime", "nativeGetPlayedTimeWithSpeed", "nativeGetRoundPlayedTime", "nativeGetRoundPlayedTimeWithSpeed"),
            "control" to listOf("nativePlay", "nativePause", "nativeResume", "nativeStop", "nativeSeekTo", "nativeRelease", "nativeCreate"),
            "source" to listOf("nativeSetDataSource", "nativeSetNextDataSource", "nativePrepareNext", "nativeMusicInfoToDataSource", "nativeWriteCacheMeta"),
            "quality" to listOf("nativeChangeQuality", "nativeBeginQualityContinuation", "nativeCommitTransitionSwitch", "nativeCancelTransition", "nativeSetTransitionInfo", "nativeNotifyNetworkQualityReduce"),
            "audio" to listOf("nativeSetVolume", "nativeGetVolume", "nativeSetMute", "nativeSetPlaybackSpeed", "nativeSetAudioSystemVolume", "nativeSetBackgroundMusicVolume", "nativeSetMusicVoiceVolume"),
            "device" to listOf("nativeGetAudioDeviceType", "nativeSetAudioDeviceType", "nativeGetAudioLatency", "nativeGetAudioTrackSessionId", "nativeUseUSBDevice", "nativeDiscardUSBDevice", "nativeSetHeadSetType", "nativeSetSupportDolby"),
            "effect" to listOf("nativeAttachAudioEffect", "nativeUnattachAudioEffect", "nativeSetAudioEffectCallback", "nativeUpdateAudioEffectConfigBySongTag", "nativeGetUnitPropertyBySongTag"),
            "cache" to listOf("nativeGetCacheProgress", "nativeGetCurrentBitrate", "nativeGetCurrentSongId", "nativeSetNativeABTestFlags", "nativeSetSepTrackEnabled", "nativeSetMusicMode"),
        )

        /** @spec P-11 / P-12 / P-13：其余精确 JNI 类的 native 方法数。 */
        const val PLAYBACK_ENGINE_METHODS: Int = 24
        const val PLAYBACK_DATA_SOURCE_METHODS: Int = 7
        const val PLAYLIST_JNI_BRIDGE_METHODS: Int = 31
        const val NE_AUDIO_DEVICE_LISTENER_METHODS: Int = 2
    }
}
