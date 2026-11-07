/* 12 §50 真机 A3 补齐的照搬舱真类：jadx 1.5.1 定向反编译 classes16.dex。
 * libncmaudioplayer.so 的主播放器类（native 方法由该 so 提供）。
 * 原包名不可改：native 层用 FindClass 描述符硬绑定；仅移除 jadx 本地路径注释。
 */
package com.netease.cloudmusic.media.audioplayer;

import android.content.Context;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import zv.a;

public class NCMNativeAudioPlayer {
    private static final long LOCK_TIMEOUT_MS = 100;
    private static final String TAG = "NCMNativeAudioPlayer";
    private static volatile boolean sIsNativeLoaded = false;
    private volatile boolean isReleased;
    private final ReentrantLock lock;
    private OnAudioPlayerEventLisenter mAudioPlayerEventLisenter;
    public long mNativeContext;

        public interface OnAudioPlayerEventLisenter {
        void onAudioPlayerEvent(int i, int i2, int i3, Object obj);
    }

    public NCMNativeAudioPlayer(Context context) {
        this(context, -1L);
    }

    public static native void _discardUSBDevice();

    public static final native void _native_init();

    public static native void _setABTestStringKeyValueInfo(String str, int i);

    public static native void _setAudioDowmixInfo(int i, double d);

    public static native void _setAudioPost51Info(float[] fArr);

    public static native void _setAudioRenderRatioGuardParam(int i, int i2);

    public static native void _setLogOutputLevel(int i);

    public static native void _useUSBDevice(int i);

    public static void discardUSBDevice() {
        _discardUSBDevice();
    }

    public static void loadNative(Context context) {
        a.b(context, "ffmpeg", "com/netease/cloudmusic/media/audioplayer/NCMNativeAudioPlayer.class:loadNative:(Landroid/content/Context;)V line-32");
        a.b(context, "ncmusbaudio", "com/netease/cloudmusic/media/audioplayer/NCMNativeAudioPlayer.class:loadNative:(Landroid/content/Context;)V line-33");
        a.b(context, "ncmaudioplayer", "com/netease/cloudmusic/media/audioplayer/NCMNativeAudioPlayer.class:loadNative:(Landroid/content/Context;)V line-34");
        _native_init();
        sIsNativeLoaded = true;
    }

    private static void onAudioPlayerNativeEvent(Object obj, int i, int i2, int i3, Object obj2) {
        OnAudioPlayerEventLisenter onAudioPlayerEventLisenter;
        NCMNativeAudioPlayer nCMNativeAudioPlayer = (NCMNativeAudioPlayer) ((WeakReference) obj).get();
        if (nCMNativeAudioPlayer == null || (onAudioPlayerEventLisenter = nCMNativeAudioPlayer.mAudioPlayerEventLisenter) == null) {
            return;
        }
        onAudioPlayerEventLisenter.onAudioPlayerEvent(i, i2, i3, obj2);
    }

    public static void setABTestStringKeyValueInfo(String str, int i) {
        _setABTestStringKeyValueInfo(str, i);
    }

    public static void setAudioDowmixInfo(int i, double d) {
        _setAudioDowmixInfo(i, d);
    }

    public static void setAudioPost51Info(float[] fArr) {
        _setAudioPost51Info(fArr);
    }

    public static void setAudioRenderRatioGuardParam(int i, int i2) {
        _setAudioRenderRatioGuardParam(i, i2);
    }

    public static void setLogOutputLevel(int i) {
        _setLogOutputLevel(i);
    }

    public static void useUSBDevice(int i) {
        _useUSBDevice(i);
    }

    public void SetOnAudioPlayerEventLisenter(OnAudioPlayerEventLisenter onAudioPlayerEventLisenter) {
        this.mAudioPlayerEventLisenter = onAudioPlayerEventLisenter;
    }

    public native void _addCodecParams(int i, String str, long j);

    public native void _delCodecParams(int i, String str);

    public native void _enableFloatPCM(boolean z);

    public native int _getAudioDeviceType();

    public native int _getAudioLatence();

    public native int _getAudioSessionId();

    public native long _getAudioTrack();

    public native int _getCurrentPosition();

    public native int _getDuration();

    public native String _getMonitorInfo();

    public native int _getPlayState() throws IllegalStateException;

    public native int _getPlayedTime(String str);

    public native int _getPlayedTimeWithSpeed(String str);

    public native boolean _isBuffering();

    public native boolean _isPlaying() throws IllegalStateException;

    public final native void _native_finalize();

    public final native void _native_setup(Object obj);

    public native void _pause(boolean z) throws IllegalStateException;

    public native void _prepareAsync() throws IllegalStateException;

    public native void _release();

    public native void _reset();

    public native void _seekTo(int i) throws IllegalStateException;

    public native void _setAudioAIEnable(int i);

    public native void _setAudioAIProcessor(long j);

    public native void _setAudioAIRatio(int i);

    public native void _setAudioBufferLength(long j);

    public native void _setAudioCrossFadeTime(long j);

    public native void _setAudioDeviceType(int i);

    public native void _setAudioEffect(long j);

    public native void _setAudioListener(long j);

    public native void _setAudioPauseFadeTime(long j);

    public native void _setAudioStartFadeTime(long j);

    public native void _setAudioStreamType(int i) throws IllegalStateException;

    public native void _setAudioSystemVolume(int i, int i2, int i3);

    public native void _setAudioVividInfo(String str, String str2, int i);

    public native void _setBackMusicVolume(float f);

    public native void _setBlackCodecIds(int[] iArr);

    public native void _setContentWithSourceCustom(Object obj, int i) throws IOException, IllegalArgumentException, SecurityException, IllegalStateException;

    public native void _setContentWithUrl(String str, int i) throws IOException, IllegalArgumentException, SecurityException, IllegalStateException;

    public native void _setDataSource(Object obj) throws IOException, IllegalArgumentException, SecurityException, IllegalStateException;

    public native void _setDataSourceWithUrl(String str) throws IOException, IllegalArgumentException, SecurityException, IllegalStateException;

    public native void _setDeviceRestartMaxTime(long j);

    public native void _setGetPlayedTimeType(int i);

    public native void _setHeadSetType(int i);

    public native void _setMusicMode(int i);

    public native void _setMusicVoiceVolume(float f);

    public native void _setPlayBackMode(int i);

    public native boolean _setPlaySpeed(double d);

    public native void _setPlaybackCacheParams(double d, double d2, double d3, double d4);

    public native void _setPositionMinNotification(int i);

    public native void _setPositionNotification(int i);

    public native void _setVolume(float f);

    public native void _start(boolean z) throws IllegalStateException;

    public native void _stop() throws IllegalStateException;

    public void addCodecParams(int i, String str, long j) {
        if (this.isReleased) {
            return;
        }
        _addCodecParams(i, str, j);
    }

    public void delCodecParams(int i, String str) {
        if (this.isReleased) {
            return;
        }
        _delCodecParams(i, str);
    }

    public void enableFloatPCM(boolean z) {
        if (this.isReleased) {
            return;
        }
        _enableFloatPCM(z);
    }

    public int getAudioDeviceType() {
        if (this.isReleased) {
            return 0;
        }
        return _getAudioDeviceType();
    }

    public int getAudioLatence() {
        if (this.isReleased) {
            return 0;
        }
        return _getAudioLatence();
    }

    public int getAudioSessionId() {
        if (this.isReleased) {
            return 0;
        }
        return _getAudioSessionId();
    }

    public long getAudioTrack() {
        if (this.isReleased) {
            return 0L;
        }
        return _getAudioTrack();
    }

    public int getCurrentPosition() {
        if (this.isReleased) {
            return 0;
        }
        return _getCurrentPosition();
    }

    public int getDuration() {
        if (this.isReleased) {
            return 0;
        }
        return _getDuration();
    }

    public String getMonitorInfo() {
        return !this.isReleased ? _getMonitorInfo() : "";
    }

    /**
     * 12 §50：jadx 1.5.1 对本方法的异常表重建有缺陷（引用了作用域外的局部变量 th，
     * 且缺少末尾 return），无法编译。这里按反编译源码的原语义重建：
     * released 返回 0 → tryLock(100ms) → 失败 / 再次 released 返回 0 →
     * 正常取 native 状态 → finally 解锁；InterruptedException 恢复中断标志并返回 0。
     * native 方法签名与类描述符完全不变，不影响 JNI 绑定。
     */
    public int getPlayState() throws IllegalStateException {
        if (this.isReleased) {
            return 0;
        }
        boolean locked = false;
        try {
            locked = this.lock.tryLock(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!locked || this.isReleased) {
                return 0;
            }
            return _getPlayState();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } finally {
            if (locked) {
                this.lock.unlock();
            }
        }
    }

    public int getPlayedTime(String str) {
        if (this.isReleased) {
            return 0;
        }
        return _getPlayedTime(str);
    }

    public int getPlayedTimeWithSpeed(String str) {
        if (this.isReleased) {
            return 0;
        }
        return _getPlayedTimeWithSpeed(str);
    }

    public boolean isBuffering() {
        if (this.isReleased) {
            return false;
        }
        return _isBuffering();
    }

    public boolean isPlaying() throws IllegalStateException {
        if (this.isReleased) {
            return false;
        }
        return _isPlaying();
    }

    public void native_finalize() {
        this.lock.lock();
        try {
            if (!this.isReleased) {
                this.isReleased = true;
                _native_finalize();
            }
        } finally {
            this.lock.unlock();
        }
    }

    public void pause(boolean z) throws IllegalStateException {
        if (this.isReleased) {
            return;
        }
        _pause(z);
    }

    public void prepareAsync() throws IllegalStateException {
        if (this.isReleased) {
            return;
        }
        _prepareAsync();
    }

    public void release() {
        this.lock.lock();
        try {
            if (!this.isReleased) {
                this.isReleased = true;
                _release();
            }
        } finally {
            this.lock.unlock();
        }
    }

    public void reset() {
        this.lock.lock();
        try {
            if (!this.isReleased) {
                _reset();
            }
        } finally {
            this.lock.unlock();
        }
    }

    public void seekTo(int i) throws IllegalStateException {
        if (this.isReleased) {
            return;
        }
        _seekTo(i);
    }

    public void setAudioAIEnable(int i) {
        if (this.isReleased) {
            return;
        }
        _setAudioAIEnable(i);
    }

    public void setAudioAIProcessor(long j) {
        if (this.isReleased) {
            return;
        }
        _setAudioAIProcessor(j);
    }

    public void setAudioAIRatio(int i) {
        if (this.isReleased) {
            return;
        }
        _setAudioAIRatio(i);
    }

    public void setAudioBufferLength(long j) {
        if (this.isReleased) {
            return;
        }
        _setAudioBufferLength(j);
    }

    public void setAudioCrossFadeTime(long j) {
        if (this.isReleased) {
            return;
        }
        _setAudioCrossFadeTime(j);
    }

    public void setAudioDeviceType(int i) {
        if (this.isReleased) {
            return;
        }
        _setAudioDeviceType(i);
    }

    public void setAudioEffect(long j) {
        if (this.isReleased) {
            return;
        }
        _setAudioEffect(j);
    }

    public void setAudioListener(long j) {
        if (this.isReleased) {
            return;
        }
        _setAudioListener(j);
    }

    public void setAudioPauseFadeTime(long j) {
        if (this.isReleased) {
            return;
        }
        _setAudioPauseFadeTime(j);
    }

    public void setAudioStartFadeTime(long j) {
        if (this.isReleased) {
            return;
        }
        _setAudioStartFadeTime(j);
    }

    public void setAudioStreamType(int i) throws IllegalStateException {
        if (this.isReleased) {
            return;
        }
        _setAudioStreamType(i);
    }

    public void setAudioSystemVolume(int i, int i2, int i3) {
        if (this.isReleased) {
            return;
        }
        _setAudioSystemVolume(i, i2, i3);
    }

    public void setAudioVividInfo(String str, String str2, int i) {
        if (this.isReleased) {
            return;
        }
        _setAudioVividInfo(str, str2, i);
    }

    public void setBackMusicVolume(float f) {
        if (this.isReleased) {
            return;
        }
        _setBackMusicVolume(f);
    }

    public void setBlackCodecIds(int[] iArr) {
        if (this.isReleased) {
            return;
        }
        _setBlackCodecIds(iArr);
    }

    public void setContentWithSourceCustom(Object obj, int i) throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
        if (this.isReleased) {
            return;
        }
        _setContentWithSourceCustom(obj, i);
    }

    public void setContentWithUrl(String str, int i) throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
        if (this.isReleased) {
            return;
        }
        _setContentWithUrl(str, i);
    }

    public void setDataSource(Object obj) throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
        if (this.isReleased) {
            return;
        }
        _setDataSource(obj);
    }

    public void setDataSourceWithUrl(String str) throws IOException, IllegalArgumentException, SecurityException, IllegalStateException {
        if (this.isReleased) {
            return;
        }
        _setDataSourceWithUrl(str);
    }

    public void setDeviceRestartMaxTime(long j) {
        if (this.isReleased) {
            return;
        }
        _setDeviceRestartMaxTime(j);
    }

    public void setGetPlayedTimeType(int i) {
        if (this.isReleased) {
            return;
        }
        _setGetPlayedTimeType(i);
    }

    public void setHeadSetType(int i) {
        if (this.isReleased) {
            return;
        }
        _setHeadSetType(i);
    }

    public void setMusicMode(int i) {
        if (this.isReleased) {
            return;
        }
        _setMusicMode(i);
    }

    public void setMusicVoiceVolume(float f) {
        if (this.isReleased) {
            return;
        }
        _setMusicVoiceVolume(f);
    }

    public void setPlayBackMode(int i) {
        if (this.isReleased) {
            return;
        }
        _setPlayBackMode(i);
    }

    public boolean setPlaySpeed(double d) {
        if (this.isReleased) {
            return false;
        }
        return _setPlaySpeed(d);
    }

    public void setPlaybackCacheParams(double d, double d2, double d3, double d4) {
        if (this.isReleased) {
            return;
        }
        _setPlaybackCacheParams(d, d2, d3, d4);
    }

    public void setPositionMinNotification(int i) {
        if (this.isReleased) {
            return;
        }
        _setPositionMinNotification(i);
    }

    public void setPositionNotification(int i) {
        if (this.isReleased) {
            return;
        }
        _setPositionNotification(i);
    }

    public void setVolume(float f) {
        if (this.isReleased) {
            return;
        }
        boolean z = false;
        try {
            try {
                z = this.lock.tryLock(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (z) {
                    if (this.isReleased) {
                        if (z) {
                            this.lock.unlock();
                            return;
                        }
                        return;
                    }
                    _setVolume(f);
                }
                if (!z) {
                    return;
                }
            } catch (InterruptedException unused) {
                Thread.currentThread().interrupt();
                if (!z) {
                    return;
                }
            }
            this.lock.unlock();
        } catch (Throwable th) {
            if (z) {
                this.lock.unlock();
            }
            throw th;
        }
    }

    public void start(boolean z) throws IllegalStateException {
        if (this.isReleased) {
            return;
        }
        _start(z);
    }

    public void stop() throws IllegalStateException {
        if (this.isReleased) {
            return;
        }
        _stop();
    }

    public NCMNativeAudioPlayer(Context context, long j) {
        this.lock = new ReentrantLock();
        this.mNativeContext = 0L;
        this.isReleased = false;
        this.mAudioPlayerEventLisenter = null;
        if (!sIsNativeLoaded) {
            loadNative(context);
        }
        _native_setup(new WeakReference(this));
    }
}
