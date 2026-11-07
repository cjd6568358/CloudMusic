package com.netease.cloudmusic.sdk.NMCommonCache;

public class NMVirtualFile extends NMCacheBase {
    public NMVirtualFile(long j) {
        InitFromPtr(j);
    }

    private native void DeletePtr();

    public native long AvailableSize(long j);

    public native long Close();

    public native long Open();

    public native long Read(byte[] bArr, long j, long j2);

    public native long Size();

    public native long Write(byte[] bArr, long j, long j2);

    @Override
    public void finalize() {
        DeletePtr();
        this.native_ptr_ = 0L;
    }
}
