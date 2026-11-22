package com.netease.cloudmusic.sdk.NMCommonCache;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public abstract class SourceDataRequest extends NMCacheBase {
    protected Lock lock = new ReentrantLock();
    protected String song_id_;

    public SourceDataRequest() {
        InitPtr();
    }

    private native void DeletePtr(long j);

    private native void InitPtr();

    public void OnDataRequestBegin(long j, long j2) {
        if (!needFixBegin()) {
            OnNativeDataRequestBegin(this.native_ptr_, j, j2);
            return;
        }
        try {
            try {
                this.lock.lock();
                long j3 = this.native_ptr_;
                if (j3 != 0) {
                    OnNativeDataRequestBegin(j3, j, j2);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } finally {
            this.lock.unlock();
        }
    }

    public void OnDataRequestData(long j, byte[] bArr, long j2, long j3) {
        try {
            try {
                this.lock.lock();
                long j4 = this.native_ptr_;
                if (j4 != 0) {
                    OnNativeDataRequestData(j4, j, bArr, j2, j3);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } finally {
            this.lock.unlock();
        }
    }

    public void OnDataRequestEnd(long j, String str) {
        try {
            try {
                this.lock.lock();
                long j2 = this.native_ptr_;
                if (j2 != 0) {
                    OnNativeDataRequestEnd(j2, j, str);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } finally {
            this.lock.unlock();
        }
    }

    public native void OnNativeDataRequestBegin(long j, long j2, long j3);

    public native void OnNativeDataRequestData(long j, long j2, byte[] bArr, long j3, long j4);

    public native void OnNativeDataRequestEnd(long j, long j2, String str);

    public abstract long Start(long j, long j2, long j3);

    public abstract void Stop();

    public void deletePtr() {
        try {
            try {
                this.lock.lock();
                long j = this.native_ptr_;
                if (j != 0) {
                    this.native_ptr_ = 0L;
                    DeletePtr(j);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    protected void finalize() {
        deletePtr();
    }

    public boolean needFixBegin() {
        return false;
    }
}
