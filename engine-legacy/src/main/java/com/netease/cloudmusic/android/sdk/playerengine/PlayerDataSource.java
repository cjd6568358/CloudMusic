package com.netease.cloudmusic.android.sdk.playerengine;

import org.jetbrains.annotations.Nullable;

/**
 * 13 §7.3 实证：非 native 的 Kotlin 数据类，5 个 getter，是喂给
 * PlaybackEngine.nativeEngineSetDataSource(handle, playerDataSource) 的直接封装。
 *
 * V0.1 由 EngineAdapter 构造：dataProvider 持 EngineSource 的 url / localPath /
 * mimeHint / expectedLength，其余三项为空壳（不启用缓存 / 回调路径）。
 * 这些 slot 都属 §10.4 登记簿里 compile 触发补的类，本轮按 §35 继续登记。
 */
public class PlayerDataSource {

    private final DataProvider dataProvider;
    private final ResourceInfo resourceInfo;
    private final SourceConfig config;
    private final String songId;
    private final SourceCallback sourceCallback;

    public PlayerDataSource(DataProvider dataProvider, String songId) {
        this(dataProvider, songId, false);
    }

    /** 12 §50：按音源形态选择 SourceConfig.Type（URL 源必须 REMOTE）。 */
    public PlayerDataSource(DataProvider dataProvider, String songId, boolean remote) {
        this.dataProvider = dataProvider;
        this.resourceInfo = new ResourceInfo();
        this.config = new SourceConfig(remote);
        this.songId = songId;
        this.sourceCallback = new SourceCallback();
    }

    public DataProvider getDataProvider() { return dataProvider; }
    public ResourceInfo getResourceInfo() { return resourceInfo; }
    public SourceConfig getConfig() { return config; }
    public String getSongId() { return songId; }
    @Nullable
    public SourceCallback getSourceCallback() { return sourceCallback; }
}
