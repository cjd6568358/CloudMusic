package com.netease.cloudmusic.android.sdk.playerengine;

import com.netease.cloudmusic.core.iapm.IAPMTracker;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;

public final class NativeDataRequestErrorCallback implements DataRequestErrorCallback {

    @NotNull
    private final Object lock = new Object();
    private long nativeHandle;

    public NativeDataRequestErrorCallback(long j) {
        this.nativeHandle = j;
    }

    private final native void nativeOnError(long nativeHandle, long requestId, int code, int subCode);

    public final void clearNativeHandle() {
        synchronized (this.lock) {
            this.nativeHandle = 0L;
            Unit unit = Unit.INSTANCE;
        }
    }

    @Override
    public void onError(long requestId, int code, int subCode) {
        synchronized (this.lock) {
            long j = this.nativeHandle;
            if (j != 0) {
                nativeOnError(j, requestId, code, subCode);
            }
            Unit unit = Unit.INSTANCE;
        }
    }
}
