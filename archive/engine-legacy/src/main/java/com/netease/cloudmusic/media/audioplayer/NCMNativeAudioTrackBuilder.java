/* 12 §50 真机 A3 补齐的照搬舱真类：jadx 1.5.1 定向反编译 classes16.dex。
 * libncmaudioplayer.so JNI_OnLoad FindClass 目标（AudioTrack 工厂）。
 * 原包名不可改：native 层用 FindClass 描述符硬绑定；仅移除 jadx 本地路径注释。
 */
package com.netease.cloudmusic.media.audioplayer;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

public class NCMNativeAudioTrackBuilder {
    public static AudioTrack create(AudioAttributes audioAttributes, int i, int i2, int i3, int i4, int i5, int i6, int i7) {
        try {
            AudioFormat build = new AudioFormat.Builder().setEncoding(i4).setSampleRate(i2).setChannelMask(i3).build();
            if (audioAttributes == null) {
                audioAttributes = i != 0 ? new AudioAttributes.Builder().setUsage(1).setContentType(2).setFlags(i).build() : new AudioAttributes.Builder().setUsage(1).setContentType(2).build();
            }
            AudioTrack build2 = i7 > 0 ? new AudioTrack.Builder().setAudioAttributes(audioAttributes).setAudioFormat(build).setTransferMode(i6).setBufferSizeInBytes(i5).setSessionId(i7).build() : new AudioTrack.Builder().setAudioAttributes(audioAttributes).setAudioFormat(build).setTransferMode(i6).setBufferSizeInBytes(i5).build();
            if (build2 != null) {
                if (build2.getState() == 1) {
                    return build2;
                }
            }
            return null;
        } catch (Exception e) {
            Log.e("AudioTrack", e.getLocalizedMessage());
            return null;
        }
    }
}
