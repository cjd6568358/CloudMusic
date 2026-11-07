package com.netease.cloudmusic.sdk.NMCommonCache;

import java.nio.ByteBuffer;

public class NMBufferSource extends NMCacheBase {
    public NMBufferSource(long j) {
        InitFromPtr(j);
    }

    private native void DeletePtr(long j);

    public native void Abort();

    public native boolean CacheEnough(long j, long j2);

    public native void Close();

    public native long GetCurrCachePostion();

    public native long GetCurrCacheSize();

    public native int Open();

    public native void Preload();

    public native void PreloadRange(long j, long j2);

    public native int Read(byte[] bArr, long j);

    public native int ReadBuf(ByteBuffer byteBuffer, long j);

    public native long Seek(long j, long j2);

    public native void SetDataRequest(SourceDataRequest sourceDataRequest);

    public native void SetFileOffset(long j);

    public native void SetRequestMaxRangeSize(long j);

    public native void SetRequestMinRangeSize(long j);

    public native void SetRequestRoundSize(long j);

    public native void SetRespectCacheSize(long j);

    public native long Size();

    public native long SourceID();

    public native void Stop();

    @Override
    public void finalize() {
        long j = this.native_ptr_;
        this.native_ptr_ = 0L;
        DeletePtr(j);
    }
}
