package com.netease.cloudmusic.android.sdk.playerengine;

/**
 * 13 §3 实证的 9 方法音源接口（jadx 定向反编译结论）。
 * 本轮按编译报错补的 interface slot；V0.1 由 EngineAdapter.UrlDataProvider 实现，
 * 只提供 URL / localPath，不消费 Java 字节流（16 §3.2）。
 * native 侧实际字段名 / JNI 回调仍待真机 G01 校准。
 */
public interface DataProvider {
    UrlResult getUrl();
    String getLocalPath();
    int openLocalFd(String path);
    void closeLocalFd(int fd);
    UrlResult onExpire(String url);
    void setDataRequestErrorCallback(DataRequestErrorCallback callback, boolean enabled);
    Object createDataRequestHandler();
    void cancelRequest();
    void close();
}
