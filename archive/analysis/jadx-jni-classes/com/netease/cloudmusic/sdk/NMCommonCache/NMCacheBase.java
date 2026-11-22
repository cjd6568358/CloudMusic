package com.netease.cloudmusic.sdk.NMCommonCache;

public abstract class NMCacheBase {
    protected long native_ptr_ = 0;

    public static native String GetVersion();

    public void CheckNativePtr() throws JNIInvalidException {
        if (this.native_ptr_ != 0) {
            return;
        }
        StackTraceElement stackTraceElement = Thread.currentThread().getStackTrace()[3];
        throw new JNIInvalidException("Invalid Native Handle " + stackTraceElement.getClassName() + stackTraceElement.getMethodName());
    }

    protected native void InitFromPtr(long j);

    protected abstract void finalize();
}
