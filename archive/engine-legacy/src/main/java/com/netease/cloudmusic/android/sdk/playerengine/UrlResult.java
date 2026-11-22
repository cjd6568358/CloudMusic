package com.netease.cloudmusic.android.sdk.playerengine;

/** 13 §3：6 字段 Kotlin data class 的最小 Java 承接（字段名待真机 G01 校准）。 */
public class UrlResult {
    public String url;
    public long fileSize;
    public String format;
    public long expiresAt;
    public int realBitrate;
    public String md5;

    public UrlResult(String url, long fileSize, String format,
                     long expiresAt, int realBitrate, String md5) {
        this.url = url;
        this.fileSize = fileSize;
        this.format = format;
        this.expiresAt = expiresAt;
        this.realBitrate = realBitrate;
        this.md5 = md5;
    }
}
