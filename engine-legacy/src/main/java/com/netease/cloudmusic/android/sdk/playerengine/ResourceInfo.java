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

    public String getExtension() { return extension; }
    public long getFileSize() { return fileSize; }
    public String getId() { return id; }
    public String getMd5() { return md5; }
}
