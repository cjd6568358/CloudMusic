package com.netease.cloudmusic.android.sdk.playerengine;

import com.netease.cloudmusic.core.iapm.IAPMTracker;
import kotlin.Metadata;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;

@Metadata(d1 = {"\u0000(\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\t\n\u0002\b\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\u0002\n\u0002\b\u0003\n\u0002\u0010\b\n\u0002\b\u0003\b\u0000\u0018\u00002\u00020\u0001B\r\u0012\u0006\u0010\u0002\u001a\u00020\u0003¢\u0006\u0002\u0010\u0004J\u0006\u0010\u0007\u001a\u00020\bJ)\u0010\t\u001a\u00020\b2\u0006\u0010\u0002\u001a\u00020\u00032\u0006\u0010\n\u001a\u00020\u00032\u0006\u0010\u000b\u001a\u00020\f2\u0006\u0010\r\u001a\u00020\fH\u0082 J \u0010\u000e\u001a\u00020\b2\u0006\u0010\n\u001a\u00020\u00032\u0006\u0010\u000b\u001a\u00020\f2\u0006\u0010\r\u001a\u00020\fH\u0016R\u000e\u0010\u0005\u001a\u00020\u0006X\u0082\u0004¢\u0006\u0002\n\u0000R\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u000e¢\u0006\u0002\n\u0000¨\u0006\u000f"}, d2 = {"Lcom/netease/cloudmusic/android/sdk/playerengine/NativeDataRequestErrorCallback;", "Lcom/netease/cloudmusic/android/sdk/playerengine/DataRequestErrorCallback;", "nativeHandle", "", "(J)V", "lock", "", "clearNativeHandle", "", "nativeOnError", "requestId", IAPMTracker.KEY_CODE, "", "subCode", "onError", "playerengine_release"}, k = 1, mv = {1, 6, 0}, xi = 48)
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
