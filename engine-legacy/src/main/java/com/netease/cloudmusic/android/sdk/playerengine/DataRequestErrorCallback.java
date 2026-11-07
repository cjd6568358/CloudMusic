package com.netease.cloudmusic.android.sdk.playerengine;

public interface DataRequestErrorCallback {
    void onError(long requestId, int code, int subCode);
}
