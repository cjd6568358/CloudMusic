package com.netease.cloudmusic.android.sdk.playerengine;

/**
 * 12 §50 真机 A3 · native 回调校准（jadx 1.5.1 / classes14.dex 实证）。
 *
 * 竞品是接口（onError / onProgress / onUrlReady），V0.1 空壳导致
 * libncmplayerengine.so 在 GetMethodID(SourceCallback, "onUrlReady", "(ILjava/lang/String;)V")
 * 处带 NoSuchMethodError 触发 CheckJNI abort。这里保留 V0.1 的 class 形态
 * （PlayerDataSource 直接 new），补齐三个同签名实例方法；JNI 只按描述符查方法表，
 * 不要求必须是接口。
 */
public class SourceCallback {

    public void onError(int errorCode) { }

    public void onProgress(long cachedBytes, long totalBytes) { }

    public void onUrlReady(int realBitrate, String format) { }
}
