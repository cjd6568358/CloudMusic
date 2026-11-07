/* 12 §50 真机 A3 补齐的照搬舱真类：jadx 1.5.1 定向反编译 classes18.dex。
 * libncmaudioplayer.so 引用的数据源接口。
 * 原包名不可改：native 层用 FindClass 描述符硬绑定；仅移除 jadx 本地路径注释。
 */
package com.netease.cloudmusic.module.player.datasource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;

public interface IBaseDataSource extends Cloneable {
    public static final int ERROR_NO_ERROR = -1;
    public static final int READ_ABORT = -5;
    public static final int READ_COMPLETE = -2;
    public static final int READ_FAIL = -1;
    public static final int READ_ING = -3;
    public static final int READ_NONE = -4;
    public static final int SEEK_FAIL = -1;

        public enum a {
        UNKNOWN(null),
        HTTP(null),
        MIGU(HTTP),
        AIDJ(null),
        COMPLEX(null),
        ENGINE(null),
        LOCAL(null),
        MS_FILE(LOCAL),
        MS(null),
        EMPTY(null);


        @Nullable
        private final a a;

        a(@Nullable a aVar) {
            this.a = aVar;
        }

        public boolean a(@NonNull a aVar) {
            for (a aVar2 = this; aVar2 != null; aVar2 = aVar2.a) {
                if (aVar2 == aVar) {
                    return true;
                }
            }
            return false;
        }
    }

    void abortRead();

    boolean canFastSeek();

    IBaseDataSource clone();

    void close();

    long getBufferedPosition();

    @NonNull
    a getDataSourceKind();

    int getError();

    Object getId();

    long getMusicInfoId();

    int getReadDataType();

    long getSize();

    String getSourceId();

    String getUri();

    long read(byte[] bArr, long j);

    long readAt(long j, byte[] bArr, long j2) throws IOException;

    long readBufAt(long j, @Nullable ByteBuffer byteBuffer, long j2) throws IOException;

    long seek(long j, int i);

    void setDataSourceMode(int i);

    void setUri(String str);
}
