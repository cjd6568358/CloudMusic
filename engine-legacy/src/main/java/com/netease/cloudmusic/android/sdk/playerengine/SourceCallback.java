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

    /** 12 §56：三个 native 回调加诊断日志，确认 prepare 阶段 native 是否通知过 URL / 错误。 */
    private static void diag(String message) {
        try {
            android.util.Log.i("CM-DataReq", message);
        } catch (Throwable ignored) {
            // JVM 单测里 android.util.Log 是 stub；诊断日志不得影响纯逻辑测试。
        }
    }

    public void onError(int errorCode) { diag("SourceCallback.onError code=" + errorCode); }

    public void onProgress(long cachedBytes, long totalBytes) {
        diag("SourceCallback.onProgress " + cachedBytes + "/" + totalBytes);
    }

    public void onUrlReady(int realBitrate, String format) {
        diag("SourceCallback.onUrlReady bitrate=" + realBitrate + " format=" + format);
    }
}
