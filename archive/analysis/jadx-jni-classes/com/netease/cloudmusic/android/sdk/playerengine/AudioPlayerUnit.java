package com.netease.cloudmusic.android.sdk.playerengine;

import kotlin.Metadata;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;
import o2.c;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Metadata(d1 = {"\u0000Z\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\t\n\u0002\b\u0003\n\u0002\u0010\u0002\n\u0002\b\u0004\n\u0002\u0010\b\n\u0002\b\u0003\n\u0002\u0010\u0007\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0003\n\u0002\u0010\u0012\n\u0002\b\u000e\n\u0002\u0010\u000b\n\u0002\b\n\n\u0002\u0018\u0002\n\u0002\b\b\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u001e\u0018\u0000 Z2\u00020\u0001:\u0001ZB\u000f\u0012\b\b\u0002\u0010\u0002\u001a\u00020\u0003¢\u0006\u0002\u0010\u0004J\u0006\u0010\u0006\u001a\u00020\u0007J\u0006\u0010\b\u001a\u00020\u0007J\b\u0010\t\u001a\u00020\u0007H\u0004J\u0006\u0010\n\u001a\u00020\u0003J\u0006\u0010\u000b\u001a\u00020\fJ\u0006\u0010\r\u001a\u00020\u0003J\u0006\u0010\u000e\u001a\u00020\fJ\u0006\u0010\u000f\u001a\u00020\u0010J\u0016\u0010\u0011\u001a\u00020\u00072\u0006\u0010\u0012\u001a\u00020\u00132\u0006\u0010\u0014\u001a\u00020\fJ\u001e\u0010\u0015\u001a\u00020\f2\u0006\u0010\u0016\u001a\u00020\u00172\u0006\u0010\u0018\u001a\u00020\f2\u0006\u0010\u0014\u001a\u00020\fJ\u0011\u0010\u0019\u001a\u00020\u00072\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0011\u0010\u001b\u001a\u00020\u00032\u0006\u0010\u0002\u001a\u00020\u0003H\u0082 J\u0011\u0010\u001c\u001a\u00020\u00072\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0011\u0010\u001d\u001a\u00020\u00032\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0011\u0010\u001e\u001a\u00020\f2\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0011\u0010\u001f\u001a\u00020\u00032\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0011\u0010 \u001a\u00020\f2\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0011\u0010!\u001a\u00020\u00102\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J!\u0010\"\u001a\u00020\u00072\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010\u0012\u001a\u00020\u00132\u0006\u0010\u0014\u001a\u00020\fH\u0082 J)\u0010#\u001a\u00020\f2\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010\u0016\u001a\u00020\u00172\u0006\u0010\u0018\u001a\u00020\f2\u0006\u0010\u0014\u001a\u00020\fH\u0082 J\u0019\u0010$\u001a\u00020\u00072\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010%\u001a\u00020&H\u0082 J\u0019\u0010'\u001a\u00020\u00072\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010(\u001a\u00020&H\u0082 J\u0011\u0010)\u001a\u00020\f2\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0011\u0010*\u001a\u00020\f2\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0019\u0010+\u001a\u00020\u00032\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010,\u001a\u00020\u0003H\u0082 J\u0019\u0010-\u001a\u00020\u00072\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010.\u001a\u00020\u0003H\u0082 J\u001b\u0010/\u001a\u00020\u00072\u0006\u0010\u001a\u001a\u00020\u00032\b\u00100\u001a\u0004\u0018\u000101H\u0082 J\u0019\u00102\u001a\u00020\u00072\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u00103\u001a\u00020\fH\u0082 J\u0019\u00104\u001a\u00020\u00072\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u00105\u001a\u00020\u0003H\u0082 J\u0019\u00106\u001a\u00020\u00072\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u00107\u001a\u00020\fH\u0082 J\u0019\u00108\u001a\u00020\f2\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u00109\u001a\u00020:H\u0082 J\u001b\u0010;\u001a\u00020\u00072\u0006\u0010\u001a\u001a\u00020\u00032\b\u0010<\u001a\u0004\u0018\u00010=H\u0082 J\u0019\u0010>\u001a\u00020\u00072\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010?\u001a\u00020\fH\u0082 J)\u0010@\u001a\u00020\u00072\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010A\u001a\u00020\u00132\u0006\u0010B\u001a\u00020\u00132\u0006\u0010C\u001a\u00020\fH\u0082 J\u0019\u0010D\u001a\u00020\f2\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010E\u001a\u00020\u0010H\u0082 J!\u0010F\u001a\u00020\f2\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010\u0018\u001a\u00020\f2\u0006\u0010\u0014\u001a\u00020\fH\u0082 J\u0011\u0010G\u001a\u00020\u00072\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0010\u0010H\u001a\u00020\u00072\b\b\u0002\u0010%\u001a\u00020&J\u0010\u0010I\u001a\u00020\u00072\b\b\u0002\u0010(\u001a\u00020&J\u0006\u0010J\u001a\u00020\fJ\u0006\u0010K\u001a\u00020\fJ\u000e\u0010L\u001a\u00020\u00032\u0006\u0010M\u001a\u00020\u0003J\u000e\u0010N\u001a\u00020\u00072\u0006\u0010\u001a\u001a\u00020\u0003J\u0010\u0010O\u001a\u00020\u00072\b\u00100\u001a\u0004\u0018\u000101J\u000e\u0010P\u001a\u00020\u00072\u0006\u00103\u001a\u00020\fJ\u000e\u0010Q\u001a\u00020\u00072\u0006\u0010\u001a\u001a\u00020\u0003J\u000e\u0010R\u001a\u00020\u00072\u0006\u00107\u001a\u00020\fJ\u000e\u0010S\u001a\u00020\f2\u0006\u00109\u001a\u00020:J\u0010\u0010T\u001a\u00020\u00072\b\u0010<\u001a\u0004\u0018\u00010=J\u000e\u0010U\u001a\u00020\u00072\u0006\u0010?\u001a\u00020\fJ\u001e\u0010V\u001a\u00020\u00072\u0006\u0010A\u001a\u00020\u00132\u0006\u0010B\u001a\u00020\u00132\u0006\u0010C\u001a\u00020\fJ\u000e\u0010W\u001a\u00020\f2\u0006\u0010E\u001a\u00020\u0010J\u0016\u0010X\u001a\u00020\f2\u0006\u0010\u0018\u001a\u00020\f2\u0006\u0010\u0014\u001a\u00020\fJ\u0006\u0010Y\u001a\u00020\u0007R\u000e\u0010\u0005\u001a\u00020\u0003X\u0082\u000e¢\u0006\u0002\n\u0000¨\u0006["}, d2 = {"Lcom/netease/cloudmusic/android/sdk/playerengine/AudioPlayerUnit;", "", "cacheManagerHandle", "", "(J)V", "nativeHandle", "applyAudioEffect", "", "destroy", "finalize", "getDuration", "getMNNXPUType", "", "getPlayingTime", "getState", "getVolume", "", "loadAIFilter", "filterPath", "", "modeType", "loadAIModel", "data", "", "processType", "nativeApplyAudioEffect", "handle", "nativeCreate", "nativeDestroy", "nativeGetDuration", "nativeGetMNNXPUType", "nativeGetPlayingTime", "nativeGetState", "nativeGetVolume", "nativeLoadAIFilter", "nativeLoadAIModel", "nativePause", "fadeout", "", "nativePlay", "fadein", "nativePrepareAsync", "nativeReset", "nativeSeek", "time", "nativeSetAudioEffect", "effectHandle", "nativeSetAudioEffectCallback", "callback", "Lcom/netease/cloudmusic/android/sdk/playerengine/AudioEffectReadyCallback;", "nativeSetAudioIFEnable", "status", "nativeSetAudioIFProcess", "processHandle", "nativeSetAudioIFRatio", "ratio", "nativeSetDataSource", "dataSource", "Lcom/netease/cloudmusic/android/sdk/playerengine/PlayerDataSource;", "nativeSetListener", "listener", "Lcom/netease/cloudmusic/android/sdk/playerengine/PlayerUnitListener;", "nativeSetMNNXPUType", "xpuType", "nativeSetVividDecInfo", "modelPath", "channelConfigPath", "channelMode", "nativeSetVolume", "volume", "nativeStartAIModel", "nativeStop", "pause", "play", "prepareAsync", "reset", "seek", "timeMs", "setAudioEffect", "setAudioEffectReadyCallback", "setAudioIFEnable", "setAudioIFProcess", "setAudioIFRatio", "setDataSource", "setListener", "setMNNXPUType", "setVividDecInfo", "setVolume", "startAIModel", "stop", "Companion", "playerengine_release"}, k = 1, mv = {1, 6, 0}, xi = 48)
public final class AudioPlayerUnit {
    private long nativeHandle;

    static {
        c.e("ffmpeg");
        c.e("NMCommonCache");
        c.e("ncmaudioplayer");
        c.e("neaudioeffects");
        c.e("ncmplayerengine");
    }

    public AudioPlayerUnit() {
        this(0L, 1, null);
    }

    public AudioPlayerUnit(long j) {
        this.nativeHandle = nativeCreate(j);
    }

    public AudioPlayerUnit(long j, int i, DefaultConstructorMarker defaultConstructorMarker) {
        this((i & 1) != 0 ? 0L : j);
    }

    private final native void nativeApplyAudioEffect(long handle);

    private final native long nativeCreate(long cacheManagerHandle);

    private final native void nativeDestroy(long handle);

    private final native long nativeGetDuration(long handle);

    private final native int nativeGetMNNXPUType(long handle);

    private final native long nativeGetPlayingTime(long handle);

    private final native int nativeGetState(long handle);

    private final native float nativeGetVolume(long handle);

    private final native void nativeLoadAIFilter(long handle, String filterPath, int modeType);

    private final native int nativeLoadAIModel(long handle, byte[] data, int processType, int modeType);

    private final native void nativePause(long handle, boolean fadeout);

    private final native void nativePlay(long handle, boolean fadein);

    private final native int nativePrepareAsync(long handle);

    private final native int nativeReset(long handle);

    private final native long nativeSeek(long handle, long time);

    private final native void nativeSetAudioEffect(long handle, long effectHandle);

    private final native void nativeSetAudioEffectCallback(long handle, AudioEffectReadyCallback callback);

    private final native void nativeSetAudioIFEnable(long handle, int status);

    private final native void nativeSetAudioIFProcess(long handle, long processHandle);

    private final native void nativeSetAudioIFRatio(long handle, int ratio);

    private final native int nativeSetDataSource(long handle, PlayerDataSource dataSource);

    private final native void nativeSetListener(long handle, PlayerUnitListener listener);

    private final native void nativeSetMNNXPUType(long handle, int xpuType);

    private final native void nativeSetVividDecInfo(long handle, String modelPath, String channelConfigPath, int channelMode);

    private final native int nativeSetVolume(long handle, float volume);

    private final native int nativeStartAIModel(long handle, int processType, int modeType);

    private final native void nativeStop(long handle);

    public static void pause$default(AudioPlayerUnit audioPlayerUnit, boolean z, int i, Object obj) {
        if ((i & 1) != 0) {
            z = false;
        }
        audioPlayerUnit.pause(z);
    }

    public static void play$default(AudioPlayerUnit audioPlayerUnit, boolean z, int i, Object obj) {
        if ((i & 1) != 0) {
            z = false;
        }
        audioPlayerUnit.play(z);
    }

    public final void applyAudioEffect() {
        nativeApplyAudioEffect(this.nativeHandle);
    }

    public final void destroy() {
        long j = this.nativeHandle;
        if (j != 0) {
            nativeDestroy(j);
            this.nativeHandle = 0L;
        }
    }

    protected final void finalize() {
        destroy();
    }

    public final long getDuration() {
        return nativeGetDuration(this.nativeHandle);
    }

    public final int getMNNXPUType() {
        return nativeGetMNNXPUType(this.nativeHandle);
    }

    public final long getPlayingTime() {
        return nativeGetPlayingTime(this.nativeHandle);
    }

    public final int getState() {
        return nativeGetState(this.nativeHandle);
    }

    public final float getVolume() {
        return nativeGetVolume(this.nativeHandle);
    }

    public final void loadAIFilter(@NotNull String filterPath, int modeType) {
        Intrinsics.checkNotNullParameter(filterPath, "filterPath");
        nativeLoadAIFilter(this.nativeHandle, filterPath, modeType);
    }

    public final int loadAIModel(@NotNull byte[] data, int processType, int modeType) {
        Intrinsics.checkNotNullParameter(data, "data");
        return nativeLoadAIModel(this.nativeHandle, data, processType, modeType);
    }

    public final void pause(boolean fadeout) {
        nativePause(this.nativeHandle, fadeout);
    }

    public final void play(boolean fadein) {
        nativePlay(this.nativeHandle, fadein);
    }

    public final int prepareAsync() {
        return nativePrepareAsync(this.nativeHandle);
    }

    public final int reset() {
        return nativeReset(this.nativeHandle);
    }

    public final long seek(long timeMs) {
        return nativeSeek(this.nativeHandle, timeMs);
    }

    public final void setAudioEffect(long handle) {
        nativeSetAudioEffect(this.nativeHandle, handle);
    }

    public final void setAudioEffectReadyCallback(@Nullable AudioEffectReadyCallback callback) {
        nativeSetAudioEffectCallback(this.nativeHandle, callback);
    }

    public final void setAudioIFEnable(int status) {
        nativeSetAudioIFEnable(this.nativeHandle, status);
    }

    public final void setAudioIFProcess(long handle) {
        nativeSetAudioIFProcess(this.nativeHandle, handle);
    }

    public final void setAudioIFRatio(int ratio) {
        nativeSetAudioIFRatio(this.nativeHandle, ratio);
    }

    public final int setDataSource(@NotNull PlayerDataSource dataSource) {
        Intrinsics.checkNotNullParameter(dataSource, "dataSource");
        return nativeSetDataSource(this.nativeHandle, dataSource);
    }

    public final void setListener(@Nullable PlayerUnitListener listener) {
        nativeSetListener(this.nativeHandle, listener);
    }

    public final void setMNNXPUType(int xpuType) {
        nativeSetMNNXPUType(this.nativeHandle, xpuType);
    }

    public final void setVividDecInfo(@NotNull String modelPath, @NotNull String channelConfigPath, int channelMode) {
        Intrinsics.checkNotNullParameter(modelPath, "modelPath");
        Intrinsics.checkNotNullParameter(channelConfigPath, "channelConfigPath");
        nativeSetVividDecInfo(this.nativeHandle, modelPath, channelConfigPath, channelMode);
    }

    public final int setVolume(float volume) {
        return nativeSetVolume(this.nativeHandle, volume);
    }

    public final int startAIModel(int processType, int modeType) {
        return nativeStartAIModel(this.nativeHandle, processType, modeType);
    }

    public final void stop() {
        nativeStop(this.nativeHandle);
    }
}
