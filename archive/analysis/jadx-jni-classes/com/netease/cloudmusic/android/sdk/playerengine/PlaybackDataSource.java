package com.netease.cloudmusic.android.sdk.playerengine;

import kotlin.Metadata;
import kotlin.jvm.internal.Intrinsics;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Metadata(d1 = {"\u0000<\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\t\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0005\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000b\n\u0000\n\u0002\u0010\u0002\n\u0002\b\u0013\n\u0002\u0010\b\n\u0002\b\u0006\n\u0002\u0018\u0002\n\u0002\b\u0005\u0018\u00002\u00020\u0001B\u0017\b\u0000\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0005¢\u0006\u0002\u0010\u0006J\u0006\u0010\f\u001a\u00020\rJ\b\u0010\u000e\u001a\u00020\u000fH\u0016J\b\u0010\u0010\u001a\u00020\u000fH\u0004J\u0011\u0010\u0011\u001a\u00020\r2\u0006\u0010\u0012\u001a\u00020\u0003H\u0082 J\u0019\u0010\u0013\u001a\u00020\r2\u0006\u0010\u0012\u001a\u00020\u00032\u0006\u0010\u0014\u001a\u00020\u0003H\u0082 J\u0011\u0010\u0015\u001a\u00020\u000f2\u0006\u0010\u0012\u001a\u00020\u0003H\u0082 J\u0011\u0010\u0016\u001a\u00020\u000f2\u0006\u0010\u0012\u001a\u00020\u0003H\u0082 J\u0011\u0010\u0017\u001a\u00020\u00032\u0006\u0010\u0018\u001a\u00020\u0003H\u0082 J9\u0010\u0019\u001a\u00020\r2\u0006\u0010\u0012\u001a\u00020\u00032\u0006\u0010\u001a\u001a\u00020\u00032\u0006\u0010\u001b\u001a\u00020\u00032\u0006\u0010\u001c\u001a\u00020\u00032\u0006\u0010\u001d\u001a\u00020\u00032\u0006\u0010\u001e\u001a\u00020\u0003H\u0082 J\u0019\u0010\u001f\u001a\u00020\r2\u0006\u0010\u0012\u001a\u00020\u00032\u0006\u0010 \u001a\u00020\rH\u0082 J(\u0010!\u001a\u00020\u000f2\u0006\u0010\"\u001a\u00020#2\u0006\u0010$\u001a\u00020\u00032\u0006\u0010%\u001a\u00020\u00032\u0006\u0010&\u001a\u00020#H\u0002J\u000e\u0010'\u001a\u00020\r2\u0006\u0010\u0014\u001a\u00020\u0003J\u000e\u0010(\u001a\u00020\r2\u0006\u0010)\u001a\u00020*J\u0010\u0010+\u001a\u00020\r2\b\u0010,\u001a\u0004\u0018\u00010\u000bJ\r\u0010-\u001a\u00020\u0003H\u0000¢\u0006\u0002\b.R\u000e\u0010\u0007\u001a\u00020\u0003X\u0082\u000e¢\u0006\u0002\n\u0000R\u0011\u0010\u0004\u001a\u00020\u0005¢\u0006\b\n\u0000\u001a\u0004\b\b\u0010\tR\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u000e¢\u0006\u0002\n\u0000R\u0010\u0010\n\u001a\u0004\u0018\u00010\u000bX\u0082\u000e¢\u0006\u0002\n\u0000¨\u0006/"}, d2 = {"Lcom/netease/cloudmusic/android/sdk/playerengine/PlaybackDataSource;", "Ljava/lang/AutoCloseable;", "nativeHandle", "", "musicInfo", "Lcom/netease/cloudmusic/android/sdk/playerengine/MusicInfo;", "(JLcom/netease/cloudmusic/android/sdk/playerengine/MusicInfo;)V", "engineCacheLoadConfigHandle", "getMusicInfo", "()Lcom/netease/cloudmusic/android/sdk/playerengine/MusicInfo;", "preloadCallback", "Lcom/netease/cloudmusic/android/sdk/playerengine/PreloadCallback;", "cancelPreload", "", "close", "", "finalize", "nativeCancelPreload", "handle", "nativePreload", "bytes", "nativeRelease", "nativeReleaseEngineCacheLoadConfigHandle", "nativeRetainEngineCacheLoadConfigHandle", "dataSourceHandle", "nativeSetEngineCacheLoadConfig", "rangeSize", "fragmentMax", "fragmentMin", "threshold", "roundSize", "nativeSetPreloadCallback", "enabled", "onNativePreloadResult", "status", "", "requestedBytes", "readyBytes", "errorCode", "preload", "setEngineCacheLoadConfig", "config", "Lcom/netease/cloudmusic/android/sdk/playerengine/EngineCacheLoadConfig;", "setPreloadCallback", "callback", "takeNativeHandle", "takeNativeHandle$playerengine_release", "playerengine_release"}, k = 1, mv = {1, 6, 0}, xi = 48)
public final class PlaybackDataSource implements AutoCloseable {
    private volatile long engineCacheLoadConfigHandle;

    @NotNull
    private final MusicInfo musicInfo;
    private volatile long nativeHandle;

    @Nullable
    private volatile PreloadCallback preloadCallback;

    public PlaybackDataSource(long j, @NotNull MusicInfo musicInfo) {
        Intrinsics.checkNotNullParameter(musicInfo, "musicInfo");
        this.nativeHandle = j;
        this.musicInfo = musicInfo;
        this.engineCacheLoadConfigHandle = nativeRetainEngineCacheLoadConfigHandle(this.nativeHandle);
    }

    private final native boolean nativeCancelPreload(long handle);

    private final native boolean nativePreload(long handle, long bytes);

    private final native void nativeRelease(long handle);

    private final native void nativeReleaseEngineCacheLoadConfigHandle(long handle);

    private final native long nativeRetainEngineCacheLoadConfigHandle(long dataSourceHandle);

    private final native boolean nativeSetEngineCacheLoadConfig(long handle, long rangeSize, long fragmentMax, long fragmentMin, long threshold, long roundSize);

    private final native boolean nativeSetPreloadCallback(long handle, boolean enabled);

    private final void onNativePreloadResult(int status, long requestedBytes, long readyBytes, int errorCode) {
        PreloadStatus preloadStatus = status != 0 ? status != 2 ? PreloadStatus.FAILED : PreloadStatus.CANCELLED : PreloadStatus.COMPLETED;
        PreloadCallback preloadCallback = this.preloadCallback;
        if (preloadCallback != null) {
            preloadCallback.onResult(new PreloadResult(preloadStatus, requestedBytes, readyBytes, errorCode));
        }
    }

    public final synchronized boolean cancelPreload() {
        boolean z;
        long j = this.nativeHandle;
        if (j != 0) {
            z = nativeCancelPreload(j);
        }
        return z;
    }

    @Override
    public synchronized void close() {
        long j = this.nativeHandle;
        if (j != 0) {
            this.nativeHandle = 0L;
            nativeRelease(j);
        }
        long j2 = this.engineCacheLoadConfigHandle;
        if (j2 != 0) {
            this.engineCacheLoadConfigHandle = 0L;
            nativeReleaseEngineCacheLoadConfigHandle(j2);
        }
        this.preloadCallback = null;
    }

    protected final void finalize() {
        close();
    }

    @NotNull
    public final MusicInfo getMusicInfo() {
        return this.musicInfo;
    }

    public final synchronized boolean preload(long bytes) {
        boolean z;
        long j = this.nativeHandle;
        if (j != 0 && bytes > 0) {
            z = nativePreload(j, bytes);
        }
        return z;
    }

    public final synchronized boolean setEngineCacheLoadConfig(@NotNull EngineCacheLoadConfig config) {
        boolean z;
        Intrinsics.checkNotNullParameter(config, "config");
        long j = this.engineCacheLoadConfigHandle;
        if (j != 0) {
            z = nativeSetEngineCacheLoadConfig(j, config.getRangeSize(), config.getFragmentMax(), config.getFragmentMin(), config.getThreshold(), config.getRoundSize());
        }
        return z;
    }

    public final synchronized boolean setPreloadCallback(@Nullable PreloadCallback callback) {
        long j = this.nativeHandle;
        if (j == 0) {
            return false;
        }
        if (!nativeSetPreloadCallback(j, callback != null)) {
            return false;
        }
        this.preloadCallback = callback;
        return true;
    }

    public final synchronized long takeNativeHandle$playerengine_release() {
        long j;
        j = this.nativeHandle;
        this.nativeHandle = 0L;
        return j;
    }
}
