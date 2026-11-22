package com.netease.cloudmusic.android.sdk.playerengine;

import kotlin.jvm.internal.Intrinsics;
import o2.c;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class PlaybackEngine {

    @Nullable
    private PlaybackEngineListener listener;
    private long nativeHandle;

    static {
        c.e("ffmpeg");
        c.e("NMCommonCache");
        c.e("ncmusbaudio");
        c.e("ncmaudioplayer");
        c.e("neaudioeffects");
        c.e("ncmplayerengine");
    }

    public PlaybackEngine(long j) {
        this.nativeHandle = nativeEngineCreate(j);
    }

    private final native void nativeEngineAttachAudioEffect(long handle, long effectHandle, String songTag);

    private final native void nativeEngineCancelTransition(long handle);

    private final native long nativeEngineCreate(long cacheManagerHandle);

    private final native long nativeEngineGetCurrentPosition(long handle);

    private final native long nativeEngineGetDuration(long handle);

    private final native float nativeEngineGetVolume(long handle);

    private final native boolean nativeEngineIsPlaying(long handle);

    private final native void nativeEngineNotifyAudioEffectChanged(long handle);

    private final native void nativeEnginePause(long handle, boolean fadeout);

    private final native void nativeEnginePlay(long handle, boolean fadein);

    private final native int nativeEnginePrepareAsync(long handle);

    private final native int nativeEnginePrepareNext(long handle, PlayerDataSource dataSource, String mixInfoJson, String mixType);

    private final native void nativeEngineRelease(long handle);

    private final native int nativeEngineReset(long handle);

    private final native void nativeEngineSeekTo(long handle, long positionMs);

    private final native void nativeEngineSetAudioEffectCallback(long handle, AudioEffectReadyCallback callback);

    private final native void nativeEngineSetAudioListener(long handle, long listenerHandle);

    private final native void nativeEngineSetAudioVividInfo(long handle, String modelPath, String channelConfigPath, int channelMode);

    private final native int nativeEngineSetDataSource(long handle, PlayerDataSource dataSource);

    private final native void nativeEngineSetListener(long handle, PlaybackEngineListener listener);

    private final native void nativeEngineSetPlaybackSpeed(long handle, float speed);

    private final native void nativeEngineSetVolume(long handle, float volume);

    private final native void nativeEngineStop(long handle);

    private final native void nativeEngineUnattachAudioEffect(long handle, long effectHandle);

    public static void pause$default(PlaybackEngine playbackEngine, boolean z, int i, Object obj) {
        if ((i & 1) != 0) {
            z = false;
        }
        playbackEngine.pause(z);
    }

    public static void play$default(PlaybackEngine playbackEngine, boolean z, int i, Object obj) {
        if ((i & 1) != 0) {
            z = false;
        }
        playbackEngine.play(z);
    }

    public static int prepareNext$default(PlaybackEngine playbackEngine, PlayerDataSource playerDataSource, String str, String str2, int i, Object obj) {
        if ((i & 2) != 0) {
            str = "";
        }
        if ((i & 4) != 0) {
            str2 = "";
        }
        return playbackEngine.prepareNext(playerDataSource, str, str2);
    }

    public final void attachAudioEffect(long effectHandle, @NotNull String songTag) {
        Intrinsics.checkNotNullParameter(songTag, "songTag");
        nativeEngineAttachAudioEffect(this.nativeHandle, effectHandle, songTag);
    }

    public final void cancelTransition() {
        nativeEngineCancelTransition(this.nativeHandle);
    }

    protected final void finalize() {
        release();
    }

    public final long getCurrentPosition() {
        return nativeEngineGetCurrentPosition(this.nativeHandle);
    }

    public final long getDuration() {
        return nativeEngineGetDuration(this.nativeHandle);
    }

    @Nullable
    public final PlaybackEngineListener getListener() {
        return this.listener;
    }

    public final float getVolume() {
        return nativeEngineGetVolume(this.nativeHandle);
    }

    public final boolean isPlaying() {
        return nativeEngineIsPlaying(this.nativeHandle);
    }

    public final void notifyAudioEffectChanged() {
        nativeEngineNotifyAudioEffectChanged(this.nativeHandle);
    }

    public final void onEngineEvent(int msg, @NotNull String songId, int arg1, int arg2) {
        Intrinsics.checkNotNullParameter(songId, "songId");
        PlaybackEngineListener playbackEngineListener = this.listener;
        if (playbackEngineListener != null) {
            playbackEngineListener.onEngineEvent(msg, songId, arg1, arg2);
        }
    }

    public final void pause(boolean fadeout) {
        nativeEnginePause(this.nativeHandle, fadeout);
    }

    public final void play(boolean fadein) {
        nativeEnginePlay(this.nativeHandle, fadein);
    }

    public final int prepareAsync() {
        return nativeEnginePrepareAsync(this.nativeHandle);
    }

    public final int prepareNext(@NotNull PlayerDataSource source, @NotNull String mixInfoJson, @NotNull String mixType) {
        Intrinsics.checkNotNullParameter(source, "source");
        Intrinsics.checkNotNullParameter(mixInfoJson, "mixInfoJson");
        Intrinsics.checkNotNullParameter(mixType, "mixType");
        return nativeEnginePrepareNext(this.nativeHandle, source, mixInfoJson, mixType);
    }

    public final void release() {
        long j = this.nativeHandle;
        if (j != 0) {
            nativeEngineRelease(j);
            this.nativeHandle = 0L;
        }
    }

    public final int reset() {
        return nativeEngineReset(this.nativeHandle);
    }

    public final void seekTo(long positionMs) {
        nativeEngineSeekTo(this.nativeHandle, positionMs);
    }

    public final void setAudioEffectCallback(@Nullable AudioEffectReadyCallback callback) {
        nativeEngineSetAudioEffectCallback(this.nativeHandle, callback);
    }

    public final void setAudioListener(long listenerHandle) {
        nativeEngineSetAudioListener(this.nativeHandle, listenerHandle);
    }

    public final void setAudioVividInfo(@NotNull AudioVividInfo info) {
        Intrinsics.checkNotNullParameter(info, "info");
        nativeEngineSetAudioVividInfo(this.nativeHandle, info.getModelPath(), info.getChannelConfigPath(), info.getChannelMode());
    }

    public final int setDataSource(@NotNull PlayerDataSource source) {
        Intrinsics.checkNotNullParameter(source, "source");
        return nativeEngineSetDataSource(this.nativeHandle, source);
    }

    public final void setListener(@Nullable PlaybackEngineListener playbackEngineListener) {
        this.listener = playbackEngineListener;
    }

    public final void setPlaybackSpeed(float speed) {
        nativeEngineSetPlaybackSpeed(this.nativeHandle, speed);
    }

    public final void setVolume(float volume) {
        nativeEngineSetVolume(this.nativeHandle, volume);
    }

    public final void stop() {
        nativeEngineStop(this.nativeHandle);
    }

    public final void unattachAudioEffect(long effectHandle) {
        nativeEngineUnattachAudioEffect(this.nativeHandle, effectHandle);
    }
}
