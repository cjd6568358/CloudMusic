package com.netease.cloudmusic.playlist.core;

import com.netease.necm3dgift.BuildConfig;
import kotlin.jvm.JvmStatic;
import o2.c;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class PlaylistJniBridge {

    @NotNull
    public static final PlaylistJniBridge INSTANCE = new PlaylistJniBridge();

    static {
        c.e("nmplaylist");
    }

    private PlaylistJniBridge() {
    }

    @JvmStatic
    public static final native boolean nativeAppendMusicItems(@NotNull long[] musicIds, @Nullable byte[][] rawDataArray, @Nullable String[] bizSubtypeArray, int dataType);

    public static boolean nativeAppendMusicItems$default(long[] jArr, byte[][] bArr, String[] strArr, int i, int i2, Object obj) {
        if ((i2 & 8) != 0) {
            i = 0;
        }
        return nativeAppendMusicItems(jArr, bArr, strArr, i);
    }

    @JvmStatic
    public static final native boolean nativeClearAll();

    @JvmStatic
    @Nullable
    public static final SnapshotMeta nativeClearCurrentAndSwitchToRecent(int recentIndex) {
        Object nativeClearCurrentAndSwitchToRecentRaw = nativeClearCurrentAndSwitchToRecentRaw(recentIndex);
        if (nativeClearCurrentAndSwitchToRecentRaw instanceof SnapshotMeta) {
            return (SnapshotMeta) nativeClearCurrentAndSwitchToRecentRaw;
        }
        return null;
    }

    @JvmStatic
    private static final native Object nativeClearCurrentAndSwitchToRecentRaw(int recentIndex);

    @JvmStatic
    public static final native boolean nativeDeleteRecentPlaylist(int recentIndex);

    @JvmStatic
    public static final native void nativeDestroy();

    @JvmStatic
    public static final native boolean nativeEnterDerivedMode(@NotNull long[] musicIds, @NotNull byte[][] rawDataArray, int startIndex, int playMode, int customPlayMode, @Nullable String sourceId, @Nullable String sourceName, @Nullable String sourceUrl, @Nullable String playExtraJson, @Nullable String[] bizSubtypeArray, int dataType);

    @JvmStatic
    private static final native Object nativeExitDerivedModeRaw();

    @JvmStatic
    private static final native Object nativeGetOriginSnapshotMetaRaw();

    @JvmStatic
    @Nullable
    public static final PlaybackPosition nativeGetPlaybackPosition() {
        Object nativeGetPlaybackPositionRaw = nativeGetPlaybackPositionRaw();
        if (nativeGetPlaybackPositionRaw instanceof PlaybackPosition) {
            return (PlaybackPosition) nativeGetPlaybackPositionRaw;
        }
        return null;
    }

    @JvmStatic
    private static final native Object nativeGetPlaybackPositionRaw();

    @JvmStatic
    @Nullable
    public static final SnapshotMeta nativeGetSnapshotMeta() {
        Object nativeGetSnapshotMetaRaw = nativeGetSnapshotMetaRaw();
        if (nativeGetSnapshotMetaRaw instanceof SnapshotMeta) {
            return (SnapshotMeta) nativeGetSnapshotMetaRaw;
        }
        return null;
    }

    @JvmStatic
    private static final native Object nativeGetSnapshotMetaRaw();

    @JvmStatic
    @NotNull
    public static final native String nativeGetVersion();

    @JvmStatic
    public static final native boolean nativeImportRecentPlaylist(@NotNull long[] musicIds, @Nullable byte[][] rawDataArray, @Nullable String[] bizSubtypeArray, @Nullable String sourceId, @Nullable String sourceName, @Nullable String sourceUrl, @Nullable String playExtraJson, int playMode, int customPlayMode, int dataType);

    @JvmStatic
    public static final native int nativeInit(@NotNull String dbPath, int codecType);

    @JvmStatic
    public static final native boolean nativeInsertMusicItemsAt(@NotNull long[] musicIds, @Nullable byte[][] rawDataArray, @Nullable String[] bizSubtypeArray, int atIndex, int dataType);

    public static boolean nativeInsertMusicItemsAt$default(long[] jArr, byte[][] bArr, String[] strArr, int i, int i2, int i3, Object obj) {
        if ((i3 & 16) != 0) {
            i2 = 0;
        }
        return nativeInsertMusicItemsAt(jArr, bArr, strArr, i, i2);
    }

    @JvmStatic
    @Nullable
    public static final RecentPlaylistSummary[] nativeListRecentPlaylists(int limit) {
        Object[] nativeListRecentPlaylistsRaw = nativeListRecentPlaylistsRaw(limit);
        if (nativeListRecentPlaylistsRaw == null) {
            return null;
        }
        int length = nativeListRecentPlaylistsRaw.length;
        RecentPlaylistSummary[] recentPlaylistSummaryArr = new RecentPlaylistSummary[length];
        for (int i = 0; i < length; i++) {
            recentPlaylistSummaryArr[i] = (RecentPlaylistSummary) nativeListRecentPlaylistsRaw[i];
        }
        return recentPlaylistSummaryArr;
    }

    @JvmStatic
    private static final native Object[] nativeListRecentPlaylistsRaw(int limit);

    @JvmStatic
    public static final native boolean nativePersistList(@NotNull long[] musicIds, @Nullable byte[][] rawDataArray, @Nullable String[] bizSubtypeArray, @Nullable String sourceId, @Nullable String sourceName, @Nullable String sourceUrl, @Nullable String playExtraJson, int customPlayMode, int dataType);

    @JvmStatic
    @Nullable
    public static final SnapshotMeta nativePromoteRecentToCurrent(int recentIndex, int currentIndex) {
        Object nativePromoteRecentToCurrentRaw = nativePromoteRecentToCurrentRaw(recentIndex, currentIndex);
        if (nativePromoteRecentToCurrentRaw instanceof SnapshotMeta) {
            return (SnapshotMeta) nativePromoteRecentToCurrentRaw;
        }
        return null;
    }

    @JvmStatic
    private static final native Object nativePromoteRecentToCurrentRaw(int recentIndex, int currentIndex);

    @JvmStatic
    @Nullable
    public static final RawMusicItem[] nativeReadRecentPlaylist(int recentIndex, int startIndex, int count) {
        Object[] nativeReadRecentPlaylistRaw = nativeReadRecentPlaylistRaw(recentIndex, startIndex, count);
        if (nativeReadRecentPlaylistRaw == null) {
            return null;
        }
        int length = nativeReadRecentPlaylistRaw.length;
        RawMusicItem[] rawMusicItemArr = new RawMusicItem[length];
        for (int i = 0; i < length; i++) {
            rawMusicItemArr[i] = (RawMusicItem) nativeReadRecentPlaylistRaw[i];
        }
        return rawMusicItemArr;
    }

    @JvmStatic
    private static final native Object[] nativeReadRecentPlaylistRaw(int recentIndex, int startIndex, int count);

    @JvmStatic
    @Nullable
    public static final RawMusicItem[] nativeReadSnapshotRawRange(long snapshotId, int startIndex, int count) {
        Object[] nativeReadSnapshotRawRangeRaw = nativeReadSnapshotRawRangeRaw(snapshotId, startIndex, count);
        if (nativeReadSnapshotRawRangeRaw == null) {
            return null;
        }
        int length = nativeReadSnapshotRawRangeRaw.length;
        RawMusicItem[] rawMusicItemArr = new RawMusicItem[length];
        for (int i = 0; i < length; i++) {
            rawMusicItemArr[i] = (RawMusicItem) nativeReadSnapshotRawRangeRaw[i];
        }
        return rawMusicItemArr;
    }

    @JvmStatic
    private static final native Object[] nativeReadSnapshotRawRangeRaw(long snapshotId, int startIndex, int count);

    @JvmStatic
    public static final native boolean nativeRemoveMusicItems(@NotNull long[] musicIds);

    @JvmStatic
    public static final native boolean nativeReplaceCurrentSnapshotContent(@NotNull long[] musicIds, @Nullable byte[][] rawDataArray, @Nullable String[] bizSubtypeArray, int currentIndex, int dataType);

    public static boolean nativeReplaceCurrentSnapshotContent$default(long[] jArr, byte[][] bArr, String[] strArr, int i, int i2, int i3, Object obj) {
        if ((i3 & 16) != 0) {
            i2 = 0;
        }
        return nativeReplaceCurrentSnapshotContent(jArr, bArr, strArr, i, i2);
    }

    @JvmStatic
    @Nullable
    public static final native RestoreResult nativeRestoreFromDb();

    @JvmStatic
    public static final native int nativeSetDbAdapter(@Nullable PlaylistDbAdapterProxy adapter);

    @JvmStatic
    public static final native void nativeSetLogCallback(@Nullable LogCallback callback);

    @JvmStatic
    public static final native void nativeSetLogEnabled(boolean enabled);

    @JvmStatic
    public static final native void nativeSetLogLevel(int level);

    @JvmStatic
    public static final native boolean nativeUpdateCurrentSnapshotExtraInfo(@NotNull String playExtraJson);

    @JvmStatic
    public static final native boolean nativeUpdatePlayMode(int customPlayMode);

    @JvmStatic
    public static final native boolean nativeUpdatePlaybackPosition(int index, int seekMs, int playedTime);

    @JvmStatic
    public static final native boolean nativeUpdatePlaybackProgress(int seekMs, int playedTime);

    @JvmStatic
    public static final native boolean nativeUpdateShuffleOrder(@NotNull String modeStateJson);

    @Nullable
    public final SnapshotMeta nativeExitDerivedMode() {
        Object nativeExitDerivedModeRaw = nativeExitDerivedModeRaw();
        if (nativeExitDerivedModeRaw instanceof SnapshotMeta) {
            return (SnapshotMeta) nativeExitDerivedModeRaw;
        }
        return null;
    }

    @Nullable
    public final SnapshotMeta nativeGetOriginSnapshotMeta() {
        Object nativeGetOriginSnapshotMetaRaw = nativeGetOriginSnapshotMetaRaw();
        if (nativeGetOriginSnapshotMetaRaw instanceof SnapshotMeta) {
            return (SnapshotMeta) nativeGetOriginSnapshotMetaRaw;
        }
        return null;
    }
}
