package com.netease.cloudmusic.sdk.NMCommonCache;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 12 §54 落地的取流句柄基类；**12 §59（第四十九批）按真机 exp6 原始日志 + capstone
 * 实证校准三个回调的长参语义**（此前 §54 只实证了 `Start(JJJ)J` 的 j1/j2/j3）：
 *
 * - `OnDataRequestBegin(j1, j2)` → native `OnNetSourceDataStart(int, long)`：
 *   j1（int 槽）被忽略，j2 = 资源总长（normal mode 才写 `+0x80` 水位）。
 * - `OnDataRequestData(j1, bytes, j2, j3)` → native `OnNetSourceData(int, buffer, long, long)`：
 *   **j1 = 数据包 kind，不是 requestId**：`kind==2`（末包/完成标记）跳过
 *   `NMMemoryCache::Write`，只投 `OnWriteNetData` 异步任务；Memory Mode 下该任务被
 *   `+0xb8` 判定拒绝（`Failed Not Support`），数据丢失。j2/j3 = 文件绝对 offset / 长度。
 * - `OnDataRequestEnd(j1, error)` → native `OnNetSourceDataDone(int, String)`：
 *   j1==2 → `PreloadDoneTaskOnWriteThread`，否则 → `OnFlushNetData` 任务。
 *
 * 竞品 `nmcache/l.Start(JJJ)` 把 Start 的 j1（请求标识）原样带回三个回调首参 ——
 * 在 normal mode（`cache_local=true`）下被拒的只是内存快路径，数据经 OnWriteNetData
 * 落 vfile；Memory Mode 下没有该兜底，调用方（[com.example.cloudmusic.enginelegacy.UrlDataRequest]）
 * 必须在数据包上传非 2 的 kind（实证取 1）。
 */
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
