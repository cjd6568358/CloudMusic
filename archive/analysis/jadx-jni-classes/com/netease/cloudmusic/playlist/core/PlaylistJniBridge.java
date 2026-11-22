package com.netease.cloudmusic.playlist.core;

import com.netease.necm3dgift.BuildConfig;
import kotlin.Metadata;
import kotlin.jvm.JvmStatic;
import o2.c;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Metadata(d1 = {"\u0000x\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\u000b\n\u0000\n\u0002\u0010\u0016\n\u0000\n\u0002\u0010\u0011\n\u0002\u0010\u0012\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0010\b\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0010\u0002\n\u0002\b\u000e\n\u0002\u0018\u0002\n\u0002\b\r\n\u0002\u0018\u0002\n\u0002\b\n\n\u0002\u0018\u0002\n\u0002\b\u0006\n\u0002\u0010\t\n\u0002\b\u0006\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u000e\bÆ\u0002\u0018\u00002\u00020\u0001B\u0007\b\u0002¢\u0006\u0002\u0010\u0002JB\u0010\u0003\u001a\u00020\u00042\u0006\u0010\u0005\u001a\u00020\u00062\u000e\u0010\u0007\u001a\n\u0012\u0004\u0012\u00020\t\u0018\u00010\b2\u0010\u0010\n\u001a\f\u0012\u0006\u0012\u0004\u0018\u00010\u000b\u0018\u00010\b2\b\b\u0002\u0010\f\u001a\u00020\rH\u0087 ¢\u0006\u0002\u0010\u000eJ\t\u0010\u000f\u001a\u00020\u0004H\u0087 J\u0012\u0010\u0010\u001a\u0004\u0018\u00010\u00112\u0006\u0010\u0012\u001a\u00020\rH\u0007J\u0013\u0010\u0013\u001a\u0004\u0018\u00010\u00012\u0006\u0010\u0012\u001a\u00020\rH\u0083 J\u0011\u0010\u0014\u001a\u00020\u00042\u0006\u0010\u0012\u001a\u00020\rH\u0087 J\t\u0010\u0015\u001a\u00020\u0016H\u0087 J\u0080\u0001\u0010\u0017\u001a\u00020\u00042\u0006\u0010\u0005\u001a\u00020\u00062\f\u0010\u0007\u001a\b\u0012\u0004\u0012\u00020\t0\b2\u0006\u0010\u0018\u001a\u00020\r2\u0006\u0010\u0019\u001a\u00020\r2\u0006\u0010\u001a\u001a\u00020\r2\b\u0010\u001b\u001a\u0004\u0018\u00010\u000b2\b\u0010\u001c\u001a\u0004\u0018\u00010\u000b2\b\u0010\u001d\u001a\u0004\u0018\u00010\u000b2\b\u0010\u001e\u001a\u0004\u0018\u00010\u000b2\u0012\b\u0002\u0010\n\u001a\f\u0012\u0006\u0012\u0004\u0018\u00010\u000b\u0018\u00010\b2\u0006\u0010\f\u001a\u00020\rH\u0087 ¢\u0006\u0002\u0010\u001fJ\b\u0010 \u001a\u0004\u0018\u00010\u0011J\u000b\u0010!\u001a\u0004\u0018\u00010\u0001H\u0083 J\b\u0010\"\u001a\u0004\u0018\u00010\u0011J\u000b\u0010#\u001a\u0004\u0018\u00010\u0001H\u0083 J\n\u0010$\u001a\u0004\u0018\u00010%H\u0007J\u000b\u0010&\u001a\u0004\u0018\u00010\u0001H\u0083 J\n\u0010'\u001a\u0004\u0018\u00010\u0011H\u0007J\u000b\u0010(\u001a\u0004\u0018\u00010\u0001H\u0083 J\t\u0010)\u001a\u00020\u000bH\u0087 J~\u0010*\u001a\u00020\u00042\u0006\u0010\u0005\u001a\u00020\u00062\u000e\u0010\u0007\u001a\n\u0012\u0004\u0012\u00020\t\u0018\u00010\b2\u0010\u0010\n\u001a\f\u0012\u0006\u0012\u0004\u0018\u00010\u000b\u0018\u00010\b2\b\u0010\u001b\u001a\u0004\u0018\u00010\u000b2\b\u0010\u001c\u001a\u0004\u0018\u00010\u000b2\b\u0010\u001d\u001a\u0004\u0018\u00010\u000b2\b\u0010\u001e\u001a\u0004\u0018\u00010\u000b2\b\b\u0002\u0010\u0019\u001a\u00020\r2\b\b\u0002\u0010\u001a\u001a\u00020\r2\b\b\u0002\u0010\f\u001a\u00020\rH\u0087 ¢\u0006\u0002\u0010+J\u0019\u0010,\u001a\u00020\r2\u0006\u0010-\u001a\u00020\u000b2\u0006\u0010.\u001a\u00020\rH\u0087 JJ\u0010/\u001a\u00020\u00042\u0006\u0010\u0005\u001a\u00020\u00062\u000e\u0010\u0007\u001a\n\u0012\u0004\u0012\u00020\t\u0018\u00010\b2\u0010\u0010\n\u001a\f\u0012\u0006\u0012\u0004\u0018\u00010\u000b\u0018\u00010\b2\u0006\u00100\u001a\u00020\r2\b\b\u0002\u0010\f\u001a\u00020\rH\u0087 ¢\u0006\u0002\u00101J\u001d\u00102\u001a\n\u0012\u0004\u0012\u000203\u0018\u00010\b2\u0006\u00104\u001a\u00020\rH\u0007¢\u0006\u0002\u00105J\u001e\u00106\u001a\n\u0012\u0004\u0012\u00020\u0001\u0018\u00010\b2\u0006\u00104\u001a\u00020\rH\u0083 ¢\u0006\u0002\u00107Jt\u00108\u001a\u00020\u00042\u0006\u0010\u0005\u001a\u00020\u00062\u000e\u0010\u0007\u001a\n\u0012\u0004\u0012\u00020\t\u0018\u00010\b2\u0010\u0010\n\u001a\f\u0012\u0006\u0012\u0004\u0018\u00010\u000b\u0018\u00010\b2\b\u0010\u001b\u001a\u0004\u0018\u00010\u000b2\b\u0010\u001c\u001a\u0004\u0018\u00010\u000b2\b\u0010\u001d\u001a\u0004\u0018\u00010\u000b2\b\u0010\u001e\u001a\u0004\u0018\u00010\u000b2\b\b\u0002\u0010\u001a\u001a\u00020\r2\b\b\u0002\u0010\f\u001a\u00020\rH\u0087 ¢\u0006\u0002\u00109J\u001a\u0010:\u001a\u0004\u0018\u00010\u00112\u0006\u0010\u0012\u001a\u00020\r2\u0006\u0010;\u001a\u00020\rH\u0007J\u001b\u0010<\u001a\u0004\u0018\u00010\u00012\u0006\u0010\u0012\u001a\u00020\r2\u0006\u0010;\u001a\u00020\rH\u0083 J-\u0010=\u001a\n\u0012\u0004\u0012\u00020>\u0018\u00010\b2\u0006\u0010\u0012\u001a\u00020\r2\u0006\u0010\u0018\u001a\u00020\r2\u0006\u0010?\u001a\u00020\rH\u0007¢\u0006\u0002\u0010@J.\u0010A\u001a\n\u0012\u0004\u0012\u00020\u0001\u0018\u00010\b2\u0006\u0010\u0012\u001a\u00020\r2\u0006\u0010\u0018\u001a\u00020\r2\u0006\u0010?\u001a\u00020\rH\u0083 ¢\u0006\u0002\u0010BJ-\u0010C\u001a\n\u0012\u0004\u0012\u00020>\u0018\u00010\b2\u0006\u0010D\u001a\u00020E2\u0006\u0010\u0018\u001a\u00020\r2\u0006\u0010?\u001a\u00020\rH\u0007¢\u0006\u0002\u0010FJ.\u0010G\u001a\n\u0012\u0004\u0012\u00020\u0001\u0018\u00010\b2\u0006\u0010D\u001a\u00020E2\u0006\u0010\u0018\u001a\u00020\r2\u0006\u0010?\u001a\u00020\rH\u0083 ¢\u0006\u0002\u0010HJ\u0011\u0010I\u001a\u00020\u00042\u0006\u0010\u0005\u001a\u00020\u0006H\u0087 JJ\u0010J\u001a\u00020\u00042\u0006\u0010\u0005\u001a\u00020\u00062\u000e\u0010\u0007\u001a\n\u0012\u0004\u0012\u00020\t\u0018\u00010\b2\u0010\u0010\n\u001a\f\u0012\u0006\u0012\u0004\u0018\u00010\u000b\u0018\u00010\b2\u0006\u0010;\u001a\u00020\r2\b\b\u0002\u0010\f\u001a\u00020\rH\u0087 ¢\u0006\u0002\u00101J\u000b\u0010K\u001a\u0004\u0018\u00010LH\u0087 J\u0013\u0010M\u001a\u00020\r2\b\u0010N\u001a\u0004\u0018\u00010OH\u0087 J\u0013\u0010P\u001a\u00020\u00162\b\u0010Q\u001a\u0004\u0018\u00010RH\u0087 J\u0011\u0010S\u001a\u00020\u00162\u0006\u0010T\u001a\u00020\u0004H\u0087 J\u0011\u0010U\u001a\u00020\u00162\u0006\u0010V\u001a\u00020\rH\u0087 J\u0011\u0010W\u001a\u00020\u00042\u0006\u0010\u001e\u001a\u00020\u000bH\u0087 J\u0011\u0010X\u001a\u00020\u00042\u0006\u0010\u001a\u001a\u00020\rH\u0087 J!\u0010Y\u001a\u00020\u00042\u0006\u0010Z\u001a\u00020\r2\u0006\u0010[\u001a\u00020\r2\u0006\u0010\\\u001a\u00020\rH\u0087 J\u0019\u0010]\u001a\u00020\u00042\u0006\u0010[\u001a\u00020\r2\u0006\u0010\\\u001a\u00020\rH\u0087 J\u0011\u0010^\u001a\u00020\u00042\u0006\u0010_\u001a\u00020\u000bH\u0087 ¨\u0006`"}, d2 = {"Lcom/netease/cloudmusic/playlist/core/PlaylistJniBridge;", BuildConfig.FLAVOR, "()V", "nativeAppendMusicItems", BuildConfig.FLAVOR, "musicIds", BuildConfig.FLAVOR, "rawDataArray", BuildConfig.FLAVOR, BuildConfig.FLAVOR, "bizSubtypeArray", BuildConfig.FLAVOR, "dataType", BuildConfig.FLAVOR, "([J[[B[Ljava/lang/String;I)Z", "nativeClearAll", "nativeClearCurrentAndSwitchToRecent", "Lcom/netease/cloudmusic/playlist/core/SnapshotMeta;", "recentIndex", "nativeClearCurrentAndSwitchToRecentRaw", "nativeDeleteRecentPlaylist", "nativeDestroy", BuildConfig.FLAVOR, "nativeEnterDerivedMode", "startIndex", "playMode", "customPlayMode", "sourceId", "sourceName", "sourceUrl", "playExtraJson", "([J[[BIIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;I)Z", "nativeExitDerivedMode", "nativeExitDerivedModeRaw", "nativeGetOriginSnapshotMeta", "nativeGetOriginSnapshotMetaRaw", "nativeGetPlaybackPosition", "Lcom/netease/cloudmusic/playlist/core/PlaybackPosition;", "nativeGetPlaybackPositionRaw", "nativeGetSnapshotMeta", "nativeGetSnapshotMetaRaw", "nativeGetVersion", "nativeImportRecentPlaylist", "([J[[B[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;III)Z", "nativeInit", "dbPath", "codecType", "nativeInsertMusicItemsAt", "atIndex", "([J[[B[Ljava/lang/String;II)Z", "nativeListRecentPlaylists", "Lcom/netease/cloudmusic/playlist/core/RecentPlaylistSummary;", "limit", "(I)[Lcom/netease/cloudmusic/playlist/core/RecentPlaylistSummary;", "nativeListRecentPlaylistsRaw", "(I)[Ljava/lang/Object;", "nativePersistList", "([J[[B[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)Z", "nativePromoteRecentToCurrent", "currentIndex", "nativePromoteRecentToCurrentRaw", "nativeReadRecentPlaylist", "Lcom/netease/cloudmusic/playlist/core/RawMusicItem;", "count", "(III)[Lcom/netease/cloudmusic/playlist/core/RawMusicItem;", "nativeReadRecentPlaylistRaw", "(III)[Ljava/lang/Object;", "nativeReadSnapshotRawRange", "snapshotId", BuildConfig.FLAVOR, "(JII)[Lcom/netease/cloudmusic/playlist/core/RawMusicItem;", "nativeReadSnapshotRawRangeRaw", "(JII)[Ljava/lang/Object;", "nativeRemoveMusicItems", "nativeReplaceCurrentSnapshotContent", "nativeRestoreFromDb", "Lcom/netease/cloudmusic/playlist/core/RestoreResult;", "nativeSetDbAdapter", "adapter", "Lcom/netease/cloudmusic/playlist/core/PlaylistDbAdapterProxy;", "nativeSetLogCallback", "callback", "Lcom/netease/cloudmusic/playlist/core/LogCallback;", "nativeSetLogEnabled", "enabled", "nativeSetLogLevel", "level", "nativeUpdateCurrentSnapshotExtraInfo", "nativeUpdatePlayMode", "nativeUpdatePlaybackPosition", "index", "seekMs", "playedTime", "nativeUpdatePlaybackProgress", "nativeUpdateShuffleOrder", "modeStateJson", "library_release"}, k = 1, mv = {1, 6, 0}, xi = 48)
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
