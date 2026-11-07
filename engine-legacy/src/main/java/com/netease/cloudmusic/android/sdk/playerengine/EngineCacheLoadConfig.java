package com.netease.cloudmusic.android.sdk.playerengine;

/**
 * 12 §50 真机 A3 · native 字段校准。libncmplayerengine.so 在 SourceConfig 之后
 * 会读取 rangeSize / fragmentMax / fragmentMin / threshold / roundSize；原实现
 * 只有 getter、没有同名字段，GetFieldID 会失败。这里补 long 字段并让 getter 读字段。
 */
public class EngineCacheLoadConfig {
    private long rangeSize = 0L;
    private long fragmentMax = 0L;
    private long fragmentMin = 0L;
    private long threshold = 0L;
    private long roundSize = 0L;

    public long getRangeSize() { return rangeSize; }
    public long getFragmentMax() { return fragmentMax; }
    public long getFragmentMin() { return fragmentMin; }
    public long getThreshold() { return threshold; }
    public long getRoundSize() { return roundSize; }
}
