package com.netease.cloudmusic.android.sdk.playerengine;

/**
 * 12 §50 真机 A3 · native 字段校准（jadx 1.5.1 / classes14.dex 实证）。
 * libncmplayerengine.so 在 setDataSource 时读取 extension 等字段；原空壳会导致
 * GetFieldID 失败。字段与 getter 按真实 Kotlin data class 补齐，默认值改为空串 / 0
 * （原实现为 null），避免 native 解引用空指针。
 */
public final class ResourceInfo {

    private String extension = "";
    private long fileSize = 0L;
    private String id = "";
    private String md5 = "";

    public ResourceInfo() {
    }

    /**
     * 12 §56（第四十六批 · 字段校准实验）：按竞品
     * `DataSourcePlatformImpl$buildDataSource$2.getResourceInfo()` 的构造填真实值。
     * so 的 JNI 字段表只从本类读 `extension`（`libncmplayerengine.so` 偏移 708213），
     * V0.1 空串可能让 demuxer 在 prepare 阶段直接失败；其余字段仅供缓存键。
     * **假设待真机验证**，不据此宣称已修复。
     */
    public ResourceInfo(String extension, long fileSize, String id, String md5) {
        this.extension = extension;
        this.fileSize = fileSize;
        this.id = id;
        this.md5 = md5;
    }

    public String getExtension() { return extension; }
    public long getFileSize() { return fileSize; }
    public String getId() { return id; }
    public String getMd5() { return md5; }
}
