package com.netease.cloudmusic.android.sdk.playerengine;

import com.netease.cloudmusic.biz.social.socialinput.SocialInputConfig;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import kotlin.collections.CollectionsKt;
import kotlin.jvm.JvmOverloads;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.InlineMarker;
import kotlin.jvm.internal.Intrinsics;
import o2.c;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class Playback {

    @Nullable
    private PlaybackListener listener;
    private volatile long nativeHandle;

    @NotNull
    private final ReentrantReadWriteLock nativeLifetimeLock;
    private final ReentrantReadWriteLock.ReadLock nativeReadLock;
    private final ReentrantReadWriteLock.WriteLock nativeWriteLock;

    @Nullable
    private volatile UnitEventListener unitEventListener;

    static {
        c.e("ffmpeg");
        c.e("NMCommonCache");
        c.e("ncmaudioplayer");
        c.e("neaudioeffects");
        c.e("ncmplayerengine");
    }

    public Playback(@Nullable PlaybackPlatform playbackPlatform, long j, @NotNull PlaybackListener playbackListener) {
        Intrinsics.checkNotNullParameter(playbackListener, "listener");
        ReentrantReadWriteLock reentrantReadWriteLock = new ReentrantReadWriteLock(true);
        this.nativeLifetimeLock = reentrantReadWriteLock;
        this.nativeReadLock = reentrantReadWriteLock.readLock();
        this.nativeWriteLock = reentrantReadWriteLock.writeLock();
        this.nativeHandle = nativeCreate(playbackPlatform, j, playbackListener);
    }

    public Playback(PlaybackPlatform playbackPlatform, long j, PlaybackListener playbackListener, int i, DefaultConstructorMarker defaultConstructorMarker) {
        this((i & 1) != 0 ? null : playbackPlatform, j, playbackListener);
    }

    private final native void nativeAttachAudioEffect(long handle, long effectHandle, String songTag);

    private final native void nativeBeginQualityContinuation(long handle);

    private final native void nativeCancelTransition(long handle);

    private final native void nativeChangeQuality(long handle, int targetBitrate);

    private final native void nativeCommitTransitionSwitch(long handle);

    private final native long nativeCreate(PlaybackPlatform platform, long cacheManagerHandle, PlaybackListener listener);

    private final native void nativeDiscardUSBDevice(long handle);

    private final native int nativeGetAudioDeviceType(long handle);

    private final native long nativeGetAudioLatency(long handle);

    private final native int nativeGetAudioTrackSessionId(long handle);

    private final native PlaybackCacheProgress nativeGetCacheProgress(long handle);

    private final native int nativeGetCurrentBitrate(long handle);

    private final native long nativeGetCurrentPosition(long handle);

    private final native String nativeGetCurrentSongId(long handle);

    private final native long nativeGetDuration(long handle);

    private final native int nativeGetPlaybackState(long handle);

    private final native long nativeGetPlayedTime(long handle, String songId);

    private final native long nativeGetPlayedTimeWithSpeed(long handle, String songId);

    private final native long nativeGetRoundPlayedTime(long handle, String songId);

    private final native long nativeGetRoundPlayedTimeWithSpeed(long handle, String songId);

    private final native int nativeGetTransitionState(long handle);

    private final native String nativeGetUnitPropertyBySongTag(long handle, String songTag, String property);

    private final native float nativeGetVolume(long handle);

    private final native boolean nativeIsBuffering(long handle);

    private final native boolean nativeIsMuted(long handle);

    private final native boolean nativeIsPlaying(long handle);

    private final native boolean nativeIsSeeking(long handle);

    private final native boolean nativeMarkPlaybackRoundStarted(long handle);

    private final native long nativeMusicInfoToDataSource(long handle, MusicInfo music, DataSourcePlatform dataSourcePlatform);

    private final native void nativeNotifyAudioEffectChanged(long handle);

    private final native void nativeNotifyNetworkQualityReduce(long handle);

    private final native void nativePause(long handle, boolean fadeout);

    private final native void nativePlay(long handle, boolean fadein);

    private final native int nativePrepareNext(long handle, long dataSourceHandle, MusicInfo musicInfo, String mixInfoJson, String mixType);

    private final native void nativeRelease(long handle);

    private final native void nativeResume(long handle);

    private final native void nativeSeekTo(long handle, long positionMs);

    private final native void nativeSetAudioDeviceType(long handle, int type);

    private final native void nativeSetAudioListener(long handle, long listenerHandle);

    private final native void nativeSetAudioSystemVolume(long handle, int maxVolume, int minVolume, int curVolume);

    private final native void nativeSetBackgroundMusicVolume(long handle, float volume);

    private final native int nativeSetDataSource(long handle, long dataSourceHandle, MusicInfo musicInfo);

    private final native void nativeSetHeadSetType(long handle, int type);

    private final native void nativeSetListener(long handle, PlaybackListener listener);

    private final native void nativeSetMusicMode(long handle, int mode);

    private final native void nativeSetMusicVoiceVolume(long handle, float volume);

    private final native void nativeSetMute(long handle, boolean muted);

    private final native void nativeSetNativeABTestFlags(long handle, String[] keys, int[] values);

    private final native void nativeSetNextDataSource(long handle, long dataSourceHandle, MusicInfo musicInfo);

    private final native void nativeSetPlaybackSpeed(long handle, float speed);

    private final native void nativeSetSepTrackEnabled(long handle, int mode);

    private final native void nativeSetSupportDolby(long handle, boolean support);

    private final native int nativeSetTransitionInfo(long handle, String mixInfoJson, String mixType);

    private final native void nativeSetUnitEventListener(long handle, UnitEventListener listener);

    private final native void nativeSetVolume(long handle, float volume);

    private final native void nativeStop(long handle);

    private final native void nativeUnattachAudioEffect(long handle, long effectHandle);

    private final native void nativeUpdateAudioEffectConfigBySongTag(long handle, String songTag, String config);

    private final native void nativeUseUSBDevice(long handle, int deviceId);

    private final native void nativeWriteCacheMeta(long handle, String resourceId, String metaJson);

    public static void pause$default(Playback playback, boolean z, int i, Object obj) {
        if ((i & 1) != 0) {
            z = false;
        }
        playback.pause(z);
    }

    public static void play$default(Playback playback, boolean z, int i, Object obj) {
        if ((i & 1) != 0) {
            z = false;
        }
        playback.play(z);
    }

    private final <T> T queryNative(Function0<? extends T> defaultValue, Function1<? super Long, ? extends T> query) {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? (T) query.invoke(Long.valueOf(j)) : (T) defaultValue.invoke();
        } finally {
            InlineMarker.finallyStart(1);
            this.nativeReadLock.unlock();
            InlineMarker.finallyEnd(1);
        }
    }

    public static void resume$default(Playback playback, boolean z, int i, Object obj) {
        if ((i & 1) != 0) {
            z = false;
        }
        playback.resume(z);
    }

    public final void attachAudioEffect(long effectHandle, @NotNull String songTag) {
        Intrinsics.checkNotNullParameter(songTag, "songTag");
        if (this.nativeHandle != 0) {
            nativeAttachAudioEffect(this.nativeHandle, effectHandle, songTag);
        }
    }

    public final void beginQualityContinuation() {
        if (this.nativeHandle != 0) {
            nativeBeginQualityContinuation(this.nativeHandle);
        }
    }

    public final void cancelTransition() {
        if (this.nativeHandle != 0) {
            nativeCancelTransition(this.nativeHandle);
        }
    }

    public final void changeQuality(int targetBitrate) {
        if (this.nativeHandle != 0) {
            nativeChangeQuality(this.nativeHandle, targetBitrate);
        }
    }

    public final void commitTransitionSwitch() {
        if (this.nativeHandle != 0) {
            nativeCommitTransitionSwitch(this.nativeHandle);
        }
    }

    public final void discardUSBDevice() {
        if (this.nativeHandle != 0) {
            nativeDiscardUSBDevice(this.nativeHandle);
        }
    }

    public final int getAudioDeviceType() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetAudioDeviceType(j) : 0;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final long getAudioLatency() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetAudioLatency(j) : 0L;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final int getAudioTrackSessionID() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetAudioTrackSessionId(j) : 0;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    @NotNull
    public final PlaybackCacheProgress getCacheProgress() {
        PlaybackCacheProgress playbackCacheProgress;
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            if (j != 0) {
                playbackCacheProgress = nativeGetCacheProgress(j);
                if (playbackCacheProgress == null) {
                    playbackCacheProgress = new PlaybackCacheProgress(0L, 0L, 3, null);
                }
            } else {
                playbackCacheProgress = new PlaybackCacheProgress(0L, 0L, 3, null);
            }
            return playbackCacheProgress;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final int getCurrentBitrate() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetCurrentBitrate(j) : 0;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final long getCurrentPosition() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetCurrentPosition(j) : 0L;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    @NotNull
    public final String getCurrentSongId() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetCurrentSongId(j) : "";
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final long getDuration() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetDuration(j) : 0L;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    @Nullable
    public final PlaybackListener getListener() {
        return this.listener;
    }

    public final int getPlaybackState() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetPlaybackState(j) : 1;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final long getPlayedTime(@NotNull String songId) {
        Intrinsics.checkNotNullParameter(songId, "songId");
        long r1 = 0L;
        if (songId.length() > 0) {
            this.nativeReadLock.lock();
            try {
                long j = this.nativeHandle;
                r1 = j != 0 ? nativeGetPlayedTime(j, songId) : 0L;
            } finally {
                this.nativeReadLock.unlock();
            }
        }
        return r1;
    }

    public final long getPlayedTimeWithSpeed(@NotNull String songId) {
        Intrinsics.checkNotNullParameter(songId, "songId");
        long r1 = 0L;
        if (songId.length() > 0) {
            this.nativeReadLock.lock();
            try {
                long j = this.nativeHandle;
                r1 = j != 0 ? nativeGetPlayedTimeWithSpeed(j, songId) : 0L;
            } finally {
                this.nativeReadLock.unlock();
            }
        }
        return r1;
    }

    public final long getRoundPlayedTime(@NotNull String songId) {
        Intrinsics.checkNotNullParameter(songId, "songId");
        long r1 = 0L;
        if (songId.length() > 0) {
            this.nativeReadLock.lock();
            try {
                long j = this.nativeHandle;
                r1 = j != 0 ? nativeGetRoundPlayedTime(j, songId) : -1L;
            } finally {
                this.nativeReadLock.unlock();
            }
        }
        return r1;
    }

    public final long getRoundPlayedTimeWithSpeed(@NotNull String songId) {
        Intrinsics.checkNotNullParameter(songId, "songId");
        long r1 = 0L;
        if (songId.length() > 0) {
            this.nativeReadLock.lock();
            try {
                long j = this.nativeHandle;
                r1 = j != 0 ? nativeGetRoundPlayedTimeWithSpeed(j, songId) : -1L;
            } finally {
                this.nativeReadLock.unlock();
            }
        }
        return r1;
    }

    public final int getTransitionState() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetTransitionState(j) : 0;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    @NotNull
    public final String getUnitPropertyBySongTag(@NotNull String songTag, @NotNull String property) {
        Intrinsics.checkNotNullParameter(songTag, "songTag");
        Intrinsics.checkNotNullParameter(property, "property");
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetUnitPropertyBySongTag(j, songTag, property) : "{\"code\":1,\"msg\":\"engine released\"}";
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final float getVolume() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetVolume(j) : 1.0f;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final boolean isBuffering() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeIsBuffering(j) : false;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final boolean isMuted() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeIsMuted(j) : false;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final boolean isPlaying() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeIsPlaying(j) : false;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final boolean isSeeking() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeIsSeeking(j) : false;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final boolean markPlaybackRoundStarted() {
        if (this.nativeHandle != 0) {
            return nativeMarkPlaybackRoundStarted(this.nativeHandle);
        }
        return false;
    }

    @Nullable
    public final synchronized PlaybackDataSource musicInfoToDataSource(@NotNull MusicInfo music, @NotNull DataSourcePlatform dataSourcePlatform) {
        Intrinsics.checkNotNullParameter(music, SocialInputConfig.FUNCTION_MUSIC);
        Intrinsics.checkNotNullParameter(dataSourcePlatform, "dataSourcePlatform");
        long j = this.nativeHandle;
        if (j == 0) {
            return null;
        }
        long nativeMusicInfoToDataSource = nativeMusicInfoToDataSource(j, music, dataSourcePlatform);
        return nativeMusicInfoToDataSource != 0 ? new PlaybackDataSource(nativeMusicInfoToDataSource, music) : null;
    }

    public final void notifyAudioEffectChanged() {
        if (this.nativeHandle != 0) {
            nativeNotifyAudioEffectChanged(this.nativeHandle);
        }
    }

    public final void notifyNetworkQualityReduce() {
        if (this.nativeHandle != 0) {
            nativeNotifyNetworkQualityReduce(this.nativeHandle);
        }
    }

    public final void pause(boolean fadeout) {
        if (this.nativeHandle != 0) {
            nativePause(this.nativeHandle, fadeout);
        }
    }

    public final void play(boolean fadein) {
        if (this.nativeHandle != 0) {
            nativePlay(this.nativeHandle, fadein);
        }
    }

    public final synchronized int prepareNext(@NotNull PlaybackDataSource dataSource, @NotNull String mixInfoJson, @NotNull String mixType) {
        Intrinsics.checkNotNullParameter(dataSource, "dataSource");
        Intrinsics.checkNotNullParameter(mixInfoJson, "mixInfoJson");
        Intrinsics.checkNotNullParameter(mixType, "mixType");
        long j = this.nativeHandle;
        if (j == 0) {
            dataSource.close();
            return PlaybackErrorCode.DATA_SOURCE;
        }
        long takeNativeHandle$playerengine_release = dataSource.takeNativeHandle$playerengine_release();
        if (takeNativeHandle$playerengine_release == 0) {
            return PlaybackErrorCode.DATA_SOURCE;
        }
        return nativePrepareNext(j, takeNativeHandle$playerengine_release, dataSource.getMusicInfo(), mixInfoJson, mixType);
    }

    public final synchronized void release() {
        this.nativeWriteLock.lock();
        try {
            long j = this.nativeHandle;
            this.nativeHandle = 0L;
            this.nativeWriteLock.unlock();
            if (j != 0) {
                nativeRelease(j);
            }
        } catch (Throwable th2) {
            this.nativeWriteLock.unlock();
            throw th2;
        }
    }

    @JvmOverloads
    public final void resume() {
        resume$default(this, false, 1, null);
    }

    @JvmOverloads
    public final void resume(boolean fadein) {
        play(fadein);
    }

    public final void seekTo(long positionMs) {
        if (this.nativeHandle != 0) {
            nativeSeekTo(this.nativeHandle, positionMs);
        }
    }

    public final void setAudioDeviceType(int type) {
        if (this.nativeHandle != 0) {
            nativeSetAudioDeviceType(this.nativeHandle, type);
        }
    }

    public final void setAudioListener(long listenerHandle) {
        if (this.nativeHandle != 0) {
            nativeSetAudioListener(this.nativeHandle, listenerHandle);
        }
    }

    public final void setAudioSystemVolume(int maxVolume, int minVolume, int curVolume) {
        if (this.nativeHandle != 0) {
            nativeSetAudioSystemVolume(this.nativeHandle, maxVolume, minVolume, curVolume);
        }
    }

    public final void setBackgroundMusicVolume(float volume) {
        if (this.nativeHandle != 0) {
            nativeSetBackgroundMusicVolume(this.nativeHandle, volume);
        }
    }

    public final synchronized int setDataSource(@NotNull PlaybackDataSource dataSource) {
        Intrinsics.checkNotNullParameter(dataSource, "dataSource");
        long j = this.nativeHandle;
        if (j == 0) {
            dataSource.close();
            return PlaybackErrorCode.DATA_SOURCE;
        }
        long takeNativeHandle$playerengine_release = dataSource.takeNativeHandle$playerengine_release();
        if (takeNativeHandle$playerengine_release == 0) {
            return PlaybackErrorCode.DATA_SOURCE;
        }
        return nativeSetDataSource(j, takeNativeHandle$playerengine_release, dataSource.getMusicInfo());
    }

    public final void setHeadSetType(int type) {
        if (this.nativeHandle != 0) {
            nativeSetHeadSetType(this.nativeHandle, type);
        }
    }

    public final synchronized void setListener(@Nullable PlaybackListener playbackListener) {
        this.listener = playbackListener;
        if (this.nativeHandle != 0) {
            nativeSetListener(this.nativeHandle, playbackListener);
        }
    }

    public final void setMusicMode(int mode) {
        if (this.nativeHandle != 0) {
            nativeSetMusicMode(this.nativeHandle, mode);
        }
    }

    public final void setMusicVoiceVolume(float volume) {
        if (this.nativeHandle != 0) {
            nativeSetMusicVoiceVolume(this.nativeHandle, volume);
        }
    }

    public final void setMute(boolean muted) {
        if (this.nativeHandle != 0) {
            nativeSetMute(this.nativeHandle, muted);
        }
    }

    public final void setNativeABTestFlags(@NotNull Map<String, Integer> flags) {
        Intrinsics.checkNotNullParameter(flags, "flags");
        if (this.nativeHandle == 0 || flags.isEmpty()) {
            return;
        }
        long j = this.nativeHandle;
        Object[] array = flags.keySet().toArray(new String[0]);
        if (array == null) {
            throw new NullPointerException("null cannot be cast to non-null type kotlin.Array<T of kotlin.collections.ArraysKt__ArraysJVMKt.toTypedArray>");
        }
        nativeSetNativeABTestFlags(j, (String[]) array, CollectionsKt.toIntArray(flags.values()));
    }

    public final synchronized void setNextDataSource(@NotNull PlaybackDataSource dataSource) {
        Intrinsics.checkNotNullParameter(dataSource, "dataSource");
        long j = this.nativeHandle;
        if (j == 0) {
            dataSource.close();
            return;
        }
        long takeNativeHandle$playerengine_release = dataSource.takeNativeHandle$playerengine_release();
        if (takeNativeHandle$playerengine_release == 0) {
            return;
        }
        nativeSetNextDataSource(j, takeNativeHandle$playerengine_release, dataSource.getMusicInfo());
    }

    public final void setPlaybackSpeed(float speed) {
        if (this.nativeHandle != 0) {
            nativeSetPlaybackSpeed(this.nativeHandle, speed);
        }
    }

    public final void setSepTrackEnabled(int mode) {
        if (this.nativeHandle != 0) {
            nativeSetSepTrackEnabled(this.nativeHandle, mode);
        }
    }

    public final void setSupportDolby(boolean support) {
        if (this.nativeHandle != 0) {
            nativeSetSupportDolby(this.nativeHandle, support);
        }
    }

    public final int setTransitionInfo(@NotNull String mixInfoJson, @NotNull String mixType) {
        Intrinsics.checkNotNullParameter(mixInfoJson, "mixInfoJson");
        Intrinsics.checkNotNullParameter(mixType, "mixType");
        if (this.nativeHandle != 0) {
            return nativeSetTransitionInfo(this.nativeHandle, mixInfoJson, mixType);
        }
        return -1;
    }

    public final synchronized void setUnitEventListener(@Nullable UnitEventListener listener) {
        this.unitEventListener = listener;
        if (this.nativeHandle != 0) {
            nativeSetUnitEventListener(this.nativeHandle, listener);
        }
    }

    public final void setVolume(float volume) {
        if (this.nativeHandle != 0) {
            nativeSetVolume(this.nativeHandle, volume);
        }
    }

    public final void stop() {
        if (this.nativeHandle != 0) {
            nativeStop(this.nativeHandle);
        }
    }

    public final void unattachAudioEffect(long effectHandle) {
        if (this.nativeHandle != 0) {
            nativeUnattachAudioEffect(this.nativeHandle, effectHandle);
        }
    }

    public final void updateAudioEffectConfigBySongTag(@NotNull String songTag, @NotNull String config) {
        Intrinsics.checkNotNullParameter(songTag, "songTag");
        Intrinsics.checkNotNullParameter(config, "config");
        if (this.nativeHandle != 0) {
            nativeUpdateAudioEffectConfigBySongTag(this.nativeHandle, songTag, config);
        }
    }

    public final void useUSBDevice(int deviceId) {
        if (this.nativeHandle != 0) {
            nativeUseUSBDevice(this.nativeHandle, deviceId);
        }
    }

    public final void writeCacheMeta(@NotNull String resourceId, @NotNull String metaJson) {
        Intrinsics.checkNotNullParameter(resourceId, "resourceId");
        Intrinsics.checkNotNullParameter(metaJson, "metaJson");
        if (this.nativeHandle != 0) {
            nativeWriteCacheMeta(this.nativeHandle, resourceId, metaJson);
        }
    }
}
