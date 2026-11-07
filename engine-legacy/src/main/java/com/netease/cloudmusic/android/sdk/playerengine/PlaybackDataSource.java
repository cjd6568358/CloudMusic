package com.netease.cloudmusic.android.sdk.playerengine;

import kotlin.jvm.internal.Intrinsics;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
        boolean z = false;
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
        boolean z = false;
        long j = this.nativeHandle;
        if (j != 0 && bytes > 0) {
            z = nativePreload(j, bytes);
        }
        return z;
    }

    public final synchronized boolean setEngineCacheLoadConfig(@NotNull EngineCacheLoadConfig config) {
        boolean z = false;
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
