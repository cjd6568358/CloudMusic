package com.netease.cloudmusic.sdk.NMCommonCache;

public abstract class NMCacheBase {
    protected long native_ptr_ = 0;

    public static native String GetVersion();

    /**
     * 12 §57（第四十七批）：`PlaybackEngine(long cacheManagerHandle)` 需要的是 VFM 的
     * C++ 对象指针（竞品 dex 中有 `getNativePtr` 方法名，jadx 真类把该字段设为 protected）。
     * 这里只暴露只读句柄，不改变 JNI 绑定与生命周期。
     */
    public long getNativePtr() {
        return native_ptr_;
    }

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
