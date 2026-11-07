package com.netease.cloudmusic.media;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;
import androidx.annotation.Nullable;

public class NEAudioDevice {
    private static final String TAG = "NEAudioDevice";
    private int mAudioFormat;
    private int mBufferSizeInBytes;
    private int mChannelConfig;
    private AudioTrack mInstance;
    private int mMode;
    private long mNativeListener;
    private int mPeriodInFrames;
    private int mSampleRateInHz;
    private int mSessionId;
    private int mStreamType;

    private class NEAudioDeviceOnPlaybackPositionUpdateListener implements AudioTrack.OnPlaybackPositionUpdateListener {
        private long mNativeInstance;

        public NEAudioDeviceOnPlaybackPositionUpdateListener(long j) {
            this.mNativeInstance = j;
        }

        private native void onMarkerReachedNative(long j);

        private native void onPeriodicNotificationNative(long j);

        @Override
        public void onMarkerReached(@Nullable AudioTrack audioTrack) {
            long j = this.mNativeInstance;
            if (j != 0) {
                onMarkerReachedNative(j);
            }
        }

        @Override
        public void onPeriodicNotification(@Nullable AudioTrack audioTrack) {
            long j = this.mNativeInstance;
            if (j != 0) {
                onPeriodicNotificationNative(j);
            }
        }
    }

    public NEAudioDevice(int i, int i2, int i3, int i4, int i5, int i6, int i7, long j) throws IllegalArgumentException {
        this.mInstance = null;
        this.mPeriodInFrames = 0;
        createAudioTrack(i, i2, i3, i4, i5, i6, i7, j);
        this.mStreamType = i;
        this.mSampleRateInHz = i2;
        this.mChannelConfig = i3;
        this.mAudioFormat = i4;
        this.mBufferSizeInBytes = i5;
        this.mMode = i6;
        this.mSessionId = i7;
        this.mNativeListener = j;
    }

    public NEAudioDevice(int i, int i2, int i3, int i4, int i5, int i6, long j) throws IllegalArgumentException {
        this(i, i2, i3, i4, i5, i6, 0, j);
    }

    private void createAudioTrack(int i, int i2, int i3, int i4, int i5, int i6, int i7, long j) throws IllegalArgumentException {
        release();
        AudioTrack audioTrack = new AudioTrack(new AudioAttributes.Builder().setLegacyStreamType(i).build(), new AudioFormat.Builder().setChannelMask(i3).setEncoding(i4).setSampleRate(i2).build(), i5, i6, i7);
        this.mInstance = audioTrack;
        audioTrack.setPlaybackPositionUpdateListener(new NEAudioDeviceOnPlaybackPositionUpdateListener(j));
    }

    public static float getMaxVolume() {
        return AudioTrack.getMaxVolume();
    }

    public static int getMinBufferSize(int i, int i2, int i3) {
        return AudioTrack.getMinBufferSize(i, i2, i3);
    }

    public static float getMinVolume() {
        return AudioTrack.getMinVolume();
    }

    public static int getNativeOutputSampleRate(int i) {
        return AudioTrack.getNativeOutputSampleRate(i);
    }

    private void recreateAudioTrack() throws IllegalArgumentException {
        createAudioTrack(this.mStreamType, this.mSampleRateInHz, this.mChannelConfig, this.mAudioFormat, this.mBufferSizeInBytes, this.mMode, this.mSessionId, this.mNativeListener);
        setPositionNotificationPeriod(this.mPeriodInFrames);
    }

    public void flush() {
        AudioTrack audioTrack = this.mInstance;
        if (audioTrack != null) {
            audioTrack.flush();
        }
    }

    public int getAudioSessionId() {
        AudioTrack audioTrack = this.mInstance;
        if (audioTrack != null) {
            return audioTrack.getAudioSessionId();
        }
        return 0;
    }

    public int getPlayState() {
        AudioTrack audioTrack = this.mInstance;
        if (audioTrack != null) {
            return audioTrack.getPlayState();
        }
        return 1;
    }

    public long getPlaybackHeadPosition() {
        if (this.mInstance != null) {
            return this.mInstance.getPlaybackHeadPosition() & 4294967295L;
        }
        return 0L;
    }

    public void pause() throws IllegalStateException {
        AudioTrack audioTrack = this.mInstance;
        if (audioTrack == null) {
            throw new IllegalStateException("pause() called on empty AudioTrack.");
        }
        audioTrack.pause();
    }

    public void play() throws IllegalStateException {
        AudioTrack audioTrack = this.mInstance;
        if (audioTrack == null) {
            throw new IllegalStateException("play() called on empty AudioTrack.");
        }
        audioTrack.play();
    }

    public void release() {
        AudioTrack audioTrack = this.mInstance;
        if (audioTrack != null) {
            audioTrack.release();
            this.mInstance = null;
        }
    }

    public int setPositionNotificationPeriod(int i) {
        AudioTrack audioTrack = this.mInstance;
        if (audioTrack == null) {
            return -3;
        }
        int positionNotificationPeriod = audioTrack.setPositionNotificationPeriod(i);
        if (positionNotificationPeriod == 0) {
            this.mPeriodInFrames = i;
        }
        return positionNotificationPeriod;
    }

    public int setVolume(float f) {
        AudioTrack audioTrack = this.mInstance;
        if (audioTrack != null) {
            return audioTrack.setVolume(f);
        }
        return -3;
    }

    public void stop() throws IllegalStateException {
        AudioTrack audioTrack = this.mInstance;
        if (audioTrack == null) {
            throw new IllegalStateException("stop() called on empty AudioTrack.");
        }
        audioTrack.stop();
    }

    public int write(@Nullable byte[] bArr, int i, int i2) {
        AudioTrack audioTrack = this.mInstance;
        if (audioTrack == null || bArr == null) {
            return -3;
        }
        int write = audioTrack.write(bArr, i, i2);
        if (write != -6) {
            return write;
        }
        Log.e(TAG, "Dead AudioTrack Object, re-create AudioTrack");
        try {
            recreateAudioTrack();
            return 0;
        } catch (IllegalStateException e) {
            Log.e(TAG, "Re-create AudioTrack failed" + e.getMessage());
            return write;
        }
    }

    public int write(@Nullable float[] fArr, int i, int i2, int i3) {
        AudioTrack audioTrack = this.mInstance;
        if (audioTrack == null || fArr == null) {
            return -3;
        }
        int write = audioTrack.write(fArr, i, i2, i3);
        if (write != -6) {
            return write;
        }
        Log.e(TAG, "Dead AudioTrack Object, re-create AudioTrack");
        try {
            recreateAudioTrack();
            return 0;
        } catch (IllegalStateException e) {
            Log.e(TAG, "Re-create AudioTrack failed" + e.getMessage());
            return write;
        }
    }
}
