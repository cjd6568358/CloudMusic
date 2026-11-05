# CloudMusic · :engine-legacy R8 / ProGuard 规则
#
# 依据：03-12 §2（规则骨架）、11 §3.2（12 个精确 JNI 类）、13 §8（媒体会话照搬层类名实证）、
#       14 §4（播放页自定义 View）。2026-11-05 由 C1 静态校验，见 tools/check-engine-abi.py。
#
# ⚠️ 本文件只是**静态规则文本**：不创建 :engine-legacy 源码、不提取 so、不实现任何 slot，
#    路线 A「slot 后期按需实现」的边界保持不变（12 §10）。发布前需随舱源码一起启用该文件。
# ⚠️ 竞品 so 的 JNI 符号硬绑定 Java 全限定类名，漏 keep 一个类即运行时 UnsatisfiedLinkError。

# ---------------------------------------------------------------------------
# 1. 12 个精确 JNI 类（11 §3.2 / so 侧 202 个 Java_* 导出）
#    C1 会逐条校验这些类名出现在规则文本里，缺一即阻断构建。
# ---------------------------------------------------------------------------
-keep class com.netease.cloudmusic.android.sdk.playerengine.AudioPlayerUnit { *; }
-keep class com.netease.cloudmusic.android.sdk.playerengine.NativeDataRequestErrorCallback { *; }
-keep class com.netease.cloudmusic.android.sdk.playerengine.Playback { *; }
-keep class com.netease.cloudmusic.android.sdk.playerengine.PlaybackDataSource { *; }
-keep class com.netease.cloudmusic.android.sdk.playerengine.PlaybackEngine { *; }
-keep class com.netease.cloudmusic.playlist.core.PlaylistJniBridge { *; }
-keep class com.netease.cloudmusic.sdk.NMCommonCache.NMBufferSource { *; }
-keep class com.netease.cloudmusic.sdk.NMCommonCache.NMCacheBase { *; }
-keep class com.netease.cloudmusic.sdk.NMCommonCache.NMVirtualFile { *; }
-keep class com.netease.cloudmusic.sdk.NMCommonCache.NMVirtualFileManager { *; }
-keep class com.netease.cloudmusic.sdk.NMCommonCache.SourceDataRequest { *; }
-keep class com.netease.cloudmusic.media.NEAudioDevice$NEAudioDeviceOnPlaybackPositionUpdateListener { *; }

# ---------------------------------------------------------------------------
# 2. JNI 包族通配（覆盖内部类、JNI 回调接口与后期按需补的 slot）
#    13 §7.5：PlayerDataSource / PlaybackListener / PlaybackEngineListener /
#    PlayerUnitListener / UnitEventListener 无 JNI 导出但会被 native 回调引用。
# ---------------------------------------------------------------------------
-keep class com.netease.cloudmusic.android.sdk.playerengine.** { *; }
-keep class com.netease.cloudmusic.playlist.core.** { *; }
-keep class com.netease.cloudmusic.sdk.NMCommonCache.** { *; }
-keep class com.netease.cloudmusic.media.NEAudioDevice** { *; }

# 所有 native 方法及声明类（兜底）
-keepclasseswithmembernames class * { native <methods>; }

# ---------------------------------------------------------------------------
# 3. 媒体会话 / 音频焦点照搬层（13 §8 实证精确类，替换 03-12 §2 的示意包名）
#    注意：03-07 §2.1 的 `AudioFocusKeeper` / `AudioFocusHolderWrapper` 只是
#    **字符串常量**（日志 / 字段名），dex 里没有对应类定义；焦点状态机的真实类名
#    待 jadx 定位，届时按 12 §10.2 步骤 3「编译期按需补」并同步追加本文件。
# ---------------------------------------------------------------------------
-keep class com.netease.cloudmusic.receiver.MediaButtonEventReceiver { *; }
-keep class com.netease.cloudmusic.receiver.MediaButtonEventReceiver$* { *; }
-keep class com.netease.cloudmusic.receiver.mediabutton.** { *; }
-keep class com.netease.cloudmusic.module.player.monitor.** { *; }
-keep class com.netease.cloudmusic.module.player.utils.AudioFocusChangeRecord** { *; }
-keep class com.netease.cloudmusic.module.player.utils.AudioPlayConfigHelper$AudioFocusDialogConfig** { *; }
-keep class com.netease.cloudmusic.ui.AudioFocusDialogHelper** { *; }
-keep class com.netease.cloudmusic.aidl.MediaSessionCallbackParam** { *; }

# ---------------------------------------------------------------------------
# 4. 播放页照搬自定义 View（XML 反射构造；14 §4 的 20 个精确类由通配覆盖）
# ---------------------------------------------------------------------------
-keep class com.netease.cloudmusic.** extends android.view.View { *; }
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# ---------------------------------------------------------------------------
# 5. 门面实现 / 枚举名 / 日志中不得落凭据
#    Room / DataStore 的枚举按字符串名存取（03-05 §10 / §14 #7），不能混淆枚举成员名。
# ---------------------------------------------------------------------------
-keep class * implements com.example.cloudmusic.engine.api.IMusicEngine { *; }
-keep class * implements com.example.cloudmusic.media.IMediaControl { *; }
-keepclassmembers enum * { *; }
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# ---------------------------------------------------------------------------
# 6. 明确剔除、不进舱、无需 keep（03-07 §2.3 / PRD §8）
#    WebMediaButtonReceiver、GameAudioFocusListener、MediaButtonABManager、
#    PlayerLogTrace、AndroidHonorReleaseMediaSession、HiCarMediaSessionDelegate、
#    CarMediaSessionHandlerDelegate、redux/* 与 AIDL 跨进程胶水。
#    若编译期发现这些类被意外引用，说明裁剪不干净，应回查 12 §10.2 步骤 3。
# ---------------------------------------------------------------------------
