package com.netease.cloudmusic.sdk.NMCommonCache;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

@SuppressLint({"IllegalNamingError"})
public class NMVirtualFileManager extends NMCacheBase {
    private static volatile boolean sIsNativeLoaded = false;

    public NMVirtualFileManager(Context context) {
        loadNative(context);
        InitPtr();
        Log.i("NMVirtualFileManager", "Alloc");
    }

    private native synchronized void DeletePtr(long j);

    private native void InitPtr();

    public static native void SetLoggerCallback(INMLogger iNMLogger, int i);

    public static native void SetNetworkClassInfo(String str);

    private static void loadNative(Context context) {
        if (sIsNativeLoaded) {
            return;
        }
        zv.a.b(context, "NMCommonCache", "com/netease/cloudmusic/sdk/NMCommonCache/NMVirtualFileManager.class:loadNative:(Landroid/content/Context;)V line-25");
        sIsNativeLoaded = true;
    }

    public static native void setABTestStringKeyValueInfo(String str, int i);

    public native synchronized String AllCacheInfo(boolean z);

    public native String CacheIndexInfo(String str);

    public native String CacheInfo(String str, int i);

    public native String CacheInfoDetail(String str, int i);

    public native long CacheSize();

    public native long CreateBufferSource(NMBufferSourceHandler nMBufferSourceHandler, String str, String str2);

    public native long CreateNMVirtualFile(String str);

    public native String Delete(String str, int i);

    public native int DeleteAll();

    public native boolean DeleteExtFile(String str);

    public native String GetRootDir();

    public native String ReadExtFile(String str);

    public native synchronized void SetCacheCleanRange(float f, float f2);

    public native void SetConfig(String str);

    public native long SetMaxSize(long j);

    public native void SetVirtualFileManagerHandler(NMVirtualFileManagerHandler nMVirtualFileManagerHandler);

    public native void TriggerCacheClean(String str, int i);

    public native boolean WriteExtFile(String str, String str2);

    @Override
    protected void finalize() {
        release();
    }

    public synchronized void release() {
        long j = this.native_ptr_;
        this.native_ptr_ = 0L;
        DeletePtr(j);
        Log.i("NMVirtualFileManager", "Delete");
    }
}
