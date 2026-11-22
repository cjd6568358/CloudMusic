package com.netease.cloudmusic.android.sdk.playerengine;

import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;
import o2.c;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
