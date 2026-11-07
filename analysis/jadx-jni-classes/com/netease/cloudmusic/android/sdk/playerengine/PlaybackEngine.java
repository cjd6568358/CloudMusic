package com.netease.cloudmusic.android.sdk.playerengine;

import kotlin.Metadata;
import kotlin.jvm.internal.Intrinsics;
import o2.c;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Metadata(d1 = {"\u0000X\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\t\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0006\n\u0002\u0010\u0002\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0005\n\u0002\u0010\u0007\n\u0000\n\u0002\u0010\u000b\n\u0002\b\u000e\n\u0002\u0010\b\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\b\n\u0002\u0018\u0002\n\u0002\b \n\u0002\u0018\u0002\n\u0002\b\u0007\u0018\u0000 Z2\u00020\u0001:\u0001ZB\r\u0012\u0006\u0010\u0002\u001a\u00020\u0003¢\u0006\u0002\u0010\u0004J\u0016\u0010\f\u001a\u00020\r2\u0006\u0010\u000e\u001a\u00020\u00032\u0006\u0010\u000f\u001a\u00020\u0010J\u0006\u0010\u0011\u001a\u00020\rJ\b\u0010\u0012\u001a\u00020\rH\u0004J\u0006\u0010\u0013\u001a\u00020\u0003J\u0006\u0010\u0014\u001a\u00020\u0003J\u0006\u0010\u0015\u001a\u00020\u0016J\u0006\u0010\u0017\u001a\u00020\u0018J!\u0010\u0019\u001a\u00020\r2\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010\u000e\u001a\u00020\u00032\u0006\u0010\u000f\u001a\u00020\u0010H\u0082 J\u0011\u0010\u001b\u001a\u00020\r2\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0011\u0010\u001c\u001a\u00020\u00032\u0006\u0010\u0002\u001a\u00020\u0003H\u0082 J\u0011\u0010\u001d\u001a\u00020\u00032\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0011\u0010\u001e\u001a\u00020\u00032\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0011\u0010\u001f\u001a\u00020\u00162\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0011\u0010 \u001a\u00020\u00182\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0011\u0010!\u001a\u00020\r2\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0019\u0010\"\u001a\u00020\r2\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010#\u001a\u00020\u0018H\u0082 J\u0019\u0010$\u001a\u00020\r2\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010%\u001a\u00020\u0018H\u0082 J\u0011\u0010&\u001a\u00020'2\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J)\u0010(\u001a\u00020'2\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010)\u001a\u00020*2\u0006\u0010+\u001a\u00020\u00102\u0006\u0010,\u001a\u00020\u0010H\u0082 J\u0011\u0010-\u001a\u00020\r2\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0011\u0010.\u001a\u00020'2\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0019\u0010/\u001a\u00020\r2\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u00100\u001a\u00020\u0003H\u0082 J\u001b\u00101\u001a\u00020\r2\u0006\u0010\u001a\u001a\u00020\u00032\b\u00102\u001a\u0004\u0018\u000103H\u0082 J\u0019\u00104\u001a\u00020\r2\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u00105\u001a\u00020\u0003H\u0082 J)\u00106\u001a\u00020\r2\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u00107\u001a\u00020\u00102\u0006\u00108\u001a\u00020\u00102\u0006\u00109\u001a\u00020'H\u0082 J\u0019\u0010:\u001a\u00020'2\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010)\u001a\u00020*H\u0082 J\u001b\u0010;\u001a\u00020\r2\u0006\u0010\u001a\u001a\u00020\u00032\b\u0010\u0005\u001a\u0004\u0018\u00010\u0006H\u0082 J\u0019\u0010<\u001a\u00020\r2\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010=\u001a\u00020\u0016H\u0082 J\u0019\u0010>\u001a\u00020\r2\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010?\u001a\u00020\u0016H\u0082 J\u0011\u0010@\u001a\u00020\r2\u0006\u0010\u001a\u001a\u00020\u0003H\u0082 J\u0019\u0010A\u001a\u00020\r2\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010\u000e\u001a\u00020\u0003H\u0082 J\u0006\u0010B\u001a\u00020\rJ&\u0010C\u001a\u00020\r2\u0006\u0010D\u001a\u00020'2\u0006\u0010E\u001a\u00020\u00102\u0006\u0010F\u001a\u00020'2\u0006\u0010G\u001a\u00020'J\u0010\u0010H\u001a\u00020\r2\b\b\u0002\u0010#\u001a\u00020\u0018J\u0010\u0010I\u001a\u00020\r2\b\b\u0002\u0010%\u001a\u00020\u0018J\u0006\u0010J\u001a\u00020'J\"\u0010K\u001a\u00020'2\u0006\u0010L\u001a\u00020*2\b\b\u0002\u0010+\u001a\u00020\u00102\b\b\u0002\u0010,\u001a\u00020\u0010J\u0006\u0010M\u001a\u00020\rJ\u0006\u0010N\u001a\u00020'J\u000e\u0010O\u001a\u00020\r2\u0006\u00100\u001a\u00020\u0003J\u0010\u0010P\u001a\u00020\r2\b\u00102\u001a\u0004\u0018\u000103J\u000e\u0010Q\u001a\u00020\r2\u0006\u00105\u001a\u00020\u0003J\u000e\u0010R\u001a\u00020\r2\u0006\u0010S\u001a\u00020TJ\u000e\u0010U\u001a\u00020'2\u0006\u0010L\u001a\u00020*J\u000e\u0010V\u001a\u00020\r2\u0006\u0010=\u001a\u00020\u0016J\u000e\u0010W\u001a\u00020\r2\u0006\u0010?\u001a\u00020\u0016J\u0006\u0010X\u001a\u00020\rJ\u000e\u0010Y\u001a\u00020\r2\u0006\u0010\u000e\u001a\u00020\u0003R\u001c\u0010\u0005\u001a\u0004\u0018\u00010\u0006X\u0086\u000e¢\u0006\u000e\n\u0000\u001a\u0004\b\u0007\u0010\b\"\u0004\b\t\u0010\nR\u000e\u0010\u000b\u001a\u00020\u0003X\u0082\u000e¢\u0006\u0002\n\u0000¨\u0006["}, d2 = {"Lcom/netease/cloudmusic/android/sdk/playerengine/PlaybackEngine;", "", "cacheManagerHandle", "", "(J)V", "listener", "Lcom/netease/cloudmusic/android/sdk/playerengine/PlaybackEngineListener;", "getListener", "()Lcom/netease/cloudmusic/android/sdk/playerengine/PlaybackEngineListener;", "setListener", "(Lcom/netease/cloudmusic/android/sdk/playerengine/PlaybackEngineListener;)V", "nativeHandle", "attachAudioEffect", "", "effectHandle", "songTag", "", "cancelTransition", "finalize", "getCurrentPosition", "getDuration", "getVolume", "", "isPlaying", "", "nativeEngineAttachAudioEffect", "handle", "nativeEngineCancelTransition", "nativeEngineCreate", "nativeEngineGetCurrentPosition", "nativeEngineGetDuration", "nativeEngineGetVolume", "nativeEngineIsPlaying", "nativeEngineNotifyAudioEffectChanged", "nativeEnginePause", "fadeout", "nativeEnginePlay", "fadein", "nativeEnginePrepareAsync", "", "nativeEnginePrepareNext", "dataSource", "Lcom/netease/cloudmusic/android/sdk/playerengine/PlayerDataSource;", "mixInfoJson", "mixType", "nativeEngineRelease", "nativeEngineReset", "nativeEngineSeekTo", "positionMs", "nativeEngineSetAudioEffectCallback", "callback", "Lcom/netease/cloudmusic/android/sdk/playerengine/AudioEffectReadyCallback;", "nativeEngineSetAudioListener", "listenerHandle", "nativeEngineSetAudioVividInfo", "modelPath", "channelConfigPath", "channelMode", "nativeEngineSetDataSource", "nativeEngineSetListener", "nativeEngineSetPlaybackSpeed", "speed", "nativeEngineSetVolume", "volume", "nativeEngineStop", "nativeEngineUnattachAudioEffect", "notifyAudioEffectChanged", "onEngineEvent", "msg", "songId", "arg1", "arg2", "pause", "play", "prepareAsync", "prepareNext", "source", "release", "reset", "seekTo", "setAudioEffectCallback", "setAudioListener", "setAudioVividInfo", "info", "Lcom/netease/cloudmusic/android/sdk/playerengine/AudioVividInfo;", "setDataSource", "setPlaybackSpeed", "setVolume", "stop", "unattachAudioEffect", "Companion", "playerengine_release"}, k = 1, mv = {1, 6, 0}, xi = 48)
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
