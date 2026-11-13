/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <android/log.h>
#include <jni.h>
#include <stdlib.h>

// §61.107：WMA(WAVEFORMATEX) 适配需要本地缓冲与 LE 读取宏。
#include <algorithm>
#include <cstring>
#include <vector>

extern "C" {
#ifdef __cplusplus
#define __STDC_CONSTANT_MACROS
#ifdef _STDINT_H
#undef _STDINT_H
#endif
#include <stdint.h>
#endif
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/intreadwrite.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

#define LOG_TAG "ffmpeg_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGD(...) \
  ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))

#define ERROR_STRING_BUFFER_LENGTH 256

// Output format corresponding to AudioFormat.ENCODING_PCM_16BIT.
static const AVSampleFormat OUTPUT_FORMAT_PCM_16BIT = AV_SAMPLE_FMT_S16;
// Output format corresponding to AudioFormat.ENCODING_PCM_FLOAT.
static const AVSampleFormat OUTPUT_FORMAT_PCM_FLOAT = AV_SAMPLE_FMT_FLT;

// LINT.IfChange
static const int AUDIO_DECODER_ERROR_INVALID_DATA = -1;
static const int AUDIO_DECODER_ERROR_OTHER = -2;
// LINT.ThenChange(../java/androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder.java)

static jmethodID growOutputBufferMethod;

/**
 * Returns the AVCodec with the specified name, or NULL if it is not available.
 */
const AVCodec* getCodecByName(JNIEnv* env, jstring codecName);

/**
 * Allocates and opens a new AVCodecContext for the specified codec, passing the
 * provided extraData as initialization data for the decoder if it is non-NULL.
 * Returns the created context.
 */
AVCodecContext* createContext(JNIEnv* env, const AVCodec* codec,
                              jbyteArray extraData, jboolean outputFloat,
                              jint rawSampleRate, jint rawChannelCount);

struct GrowOutputBufferCallback {
  uint8_t* operator()(int requiredSize) const;

  JNIEnv* env;
  jobject thiz;
  jobject decoderOutputBuffer;
};

/**
 * Decodes the packet into the output buffer, returning the number of bytes
 * written, or a negative AUDIO_DECODER_ERROR constant value in the case of an
 * error.
 */
int decodePacket(AVCodecContext* context, AVPacket* packet,
                 uint8_t* outputBuffer, int outputSize,
                 GrowOutputBufferCallback growBuffer);

/**
 * Transforms ffmpeg AVERROR into a negative AUDIO_DECODER_ERROR constant value.
 */
int transformError(int errorNumber);

/**
 * Outputs a log message describing the avcodec error number.
 */
void logError(const char* functionName, int errorNumber);

/**
 * Releases the specified context.
 */
void releaseContext(AVCodecContext* context);

jstring ffmpegGetVersion(JNIEnv* env, jobject thiz) {
  return env->NewStringUTF(LIBAVCODEC_IDENT);
}

jint ffmpegGetInputBufferPaddingSize(JNIEnv* env, jobject thiz) {
  return (jint)AV_INPUT_BUFFER_PADDING_SIZE;
}

jboolean ffmpegHasDecoder(JNIEnv* env, jobject thiz, jstring codecName) {
  return getCodecByName(env, codecName) != NULL;
}

jlong ffmpegInitialize(JNIEnv* env, jobject thiz, jstring codecName,
                       jbyteArray extraData, jboolean outputFloat,
                       jint rawSampleRate, jint rawChannelCount) {
  const AVCodec* codec = getCodecByName(env, codecName);
  if (!codec) {
    LOGE("Codec not found.");
    return 0L;
  }
  return (jlong)createContext(env, codec, extraData, outputFloat, rawSampleRate,
                              rawChannelCount);
}

jint ffmpegDecode(JNIEnv* env, jobject thiz, jlong context, jobject inputData,
                  jint inputSize, jobject decoderOutputBuffer,
                  jobject outputData, jint outputSize) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  if (!inputData || !decoderOutputBuffer || !outputData) {
    LOGE("Input and output buffers must be non-NULL.");
    return -1;
  }
  if (inputSize < 0) {
    LOGE("Invalid input buffer size: %d.", inputSize);
    return -1;
  }
  if (outputSize < 0) {
    LOGE("Invalid output buffer length: %d", outputSize);
    return -1;
  }
  uint8_t* inputBuffer = (uint8_t*)env->GetDirectBufferAddress(inputData);
  uint8_t* outputBuffer = (uint8_t*)env->GetDirectBufferAddress(outputData);
  AVPacket* packet = av_packet_alloc();
  if (!packet) {
    LOGE("Failed to allocate packet.");
    return -1;
  }
  packet->data = inputBuffer;
  packet->size = inputSize;
  const int ret =
      decodePacket((AVCodecContext*)context, packet, outputBuffer, outputSize,
                   GrowOutputBufferCallback{env, thiz, decoderOutputBuffer});
  av_packet_free(&packet);
  return ret;
}

uint8_t* GrowOutputBufferCallback::operator()(int requiredSize) const {
  jobject newOutputData = env->CallObjectMethod(
      thiz, growOutputBufferMethod, decoderOutputBuffer, requiredSize);
  if (env->ExceptionCheck()) {
    LOGE("growOutputBuffer() failed");
    env->ExceptionDescribe();
    return nullptr;
  }
  return static_cast<uint8_t*>(env->GetDirectBufferAddress(newOutputData));
}

jint ffmpegGetChannelCount(JNIEnv* env, jobject thiz, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  return ((AVCodecContext*)context)->ch_layout.nb_channels;
}

jint ffmpegGetSampleRate(JNIEnv* env, jobject thiz, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  return ((AVCodecContext*)context)->sample_rate;
}

jlong ffmpegReset(JNIEnv* env, jobject thiz, jlong jContext,
                  jbyteArray extraData) {
  AVCodecContext* context = (AVCodecContext*)jContext;
  if (!context) {
    LOGE("Tried to reset without a context.");
    return 0L;
  }

  AVCodecID codecId = context->codec_id;
  if (codecId == AV_CODEC_ID_TRUEHD) {
    jboolean outputFloat =
        (jboolean)(context->request_sample_fmt == OUTPUT_FORMAT_PCM_FLOAT);
    // Release and recreate the context if the codec is TrueHD.
    // TODO: Figure out why flushing doesn't work for this codec.
    releaseContext(context);
    const AVCodec* codec = avcodec_find_decoder(codecId);
    if (!codec) {
      LOGE("Unexpected error finding codec %d.", codecId);
      return 0L;
    }
    return (jlong)createContext(env, codec, extraData, outputFloat,
                                /* rawSampleRate= */ -1,
                                /* rawChannelCount= */ -1);
  }

  avcodec_flush_buffers(context);
  return (jlong)context;
}

void ffmpegRelease(JNIEnv* env, jobject thiz, jlong context) {
  if (context) {
    releaseContext((AVCodecContext*)context);
  }
}

const AVCodec* getCodecByName(JNIEnv* env, jstring codecName) {
  if (!codecName) {
    return NULL;
  }
  const char* codecNameChars = env->GetStringUTFChars(codecName, NULL);
  const AVCodec* codec = avcodec_find_decoder_by_name(codecNameChars);
  env->ReleaseStringUTFChars(codecName, codecNameChars);
  return codec;
}

AVCodecContext* createContext(JNIEnv* env, const AVCodec* codec,
                              jbyteArray extraData, jboolean outputFloat,
                              jint rawSampleRate, jint rawChannelCount) {
  AVCodecContext* context = avcodec_alloc_context3(codec);
  if (!context) {
    LOGE("Failed to allocate context.");
    return NULL;
  }
  context->request_sample_fmt =
      outputFloat ? OUTPUT_FORMAT_PCM_FLOAT : OUTPUT_FORMAT_PCM_16BIT;
  // §61.107 / 12 §61.106 真机 wma ×2 的 FfmpegDecoderException 根因：
  // FFmpeg 6.0 libavcodec/wmadec.c 文件头明确要求 extradata 是
  // 「WAVEFORMATEX 结构末尾的 codec-specific 字节」（v1 4B / v2 6B），
  // wma_decode_init 还要求 avctx->block_align != 0；libavcodec/wma.c 的
  // ff_wma_init 再检查 sample_rate / ch_layout / bit_rate 均 > 0。
  // AsfExtractor 下发的是容器原生完整 WAVEFORMATEX（18B 头 + cbSize 尾部），
  // 本层把它适配成 FFmpeg 形态：解析字段预置 avctx，只把尾部字节作 extradata。
  // 其它 codec 走原路径（整块 extraData 就是 FFmpeg 期望的 extradata），零影响。
  jsize extraSize = extraData ? env->GetArrayLength(extraData) : 0;
  std::vector<uint8_t> extraBytes(extraSize > 0 ? extraSize : 0);
  if (extraSize > 0) {
    env->GetByteArrayRegion(extraData, 0, extraSize,
                            (jbyte*)extraBytes.data());
  }
  const uint8_t* extraPtr = extraBytes.data();
  int extraLen = (int)extraSize;
  const bool isWma = codec->id == AV_CODEC_ID_WMAV1 ||
                     codec->id == AV_CODEC_ID_WMAV2;
  if (isWma && extraLen >= 18) {
    int wfxChannels = AV_RL16(extraPtr + 2);
    int wfxRate = (int)AV_RL32(extraPtr + 4);
    int wfxAvgBytesPerSec = (int)AV_RL32(extraPtr + 8);
    int wfxBlockAlign = AV_RL16(extraPtr + 12);
    int wfxCbSize = AV_RL16(extraPtr + 16);
    if (wfxChannels > 0 && wfxRate > 0 && wfxBlockAlign > 0) {
      context->sample_rate = wfxRate;
      av_channel_layout_default(&context->ch_layout, wfxChannels);
      context->block_align = wfxBlockAlign;
      context->bit_rate = (int64_t)wfxAvgBytesPerSec * 8;
      int tail = std::min(wfxCbSize, extraLen - 18);
      if (tail > 0) {
        extraPtr += 18;
        extraLen = tail;
      }
      LOGD("wma wfx: rate=%d ch=%d align=%d bitrate=%lld extradata=%d",
           wfxRate, wfxChannels, wfxBlockAlign,
           (long long)context->bit_rate, extraLen);
    }
  }
  if (extraLen > 0) {
    context->extradata_size = extraLen;
    context->extradata =
        (uint8_t*)av_malloc(extraLen + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!context->extradata) {
      LOGE("Failed to allocate extradata.");
      releaseContext(context);
      return NULL;
    }
    memcpy(context->extradata, extraPtr, extraLen);
    memset(context->extradata + extraLen, 0, AV_INPUT_BUFFER_PADDING_SIZE);
  }
  // §61.105 / 12 §61.104 真机 aiff ×2 的 DECODER_INIT_FAILED 静态根因：
  // FFmpeg 6.0 libavcodec/avcodec.c 的 avcodec_open2 对「audio decoder 且
  // 未声明 AV_CODEC_CAP_CHANNEL_CONF」强制要求 ch_layout.nb_channels != 0，
  // 否则直接 AVERROR(EINVAL)（日志 "Decoder requires channel layout to be
  // set"）。libavcodec/pcm.c 的 PCM_DECODER_* 只声明 AV_CODEC_CAP_DR1，
  // 没有 CHANNEL_CONF → pcm_s16be 恰好命中；同时 time_base.den 取
  // sample_rate，未预置时同样为 0。上游只对 mulaw/alaw 预置这两个字段，
  // 因此 AIFF 的 pcm_s16be 在 createContext 返回 NULL → Java 侧抛
  // FfmpegDecoderException("Initialization failed.")。
  //
  // 修法：按官方 API av_get_bits_per_sample(codec->id) > 0 精确判定 PCM
  // 家族（声明于 libavcodec/codec_id.h，已由 avcodec.h 引入），仅对它们
  // 在 avcodec_open2 之前预置 sample_rate / ch_layout；压缩解码器
  // （aac/mp3/ape/wmav2/wavpack）零影响，保留上游从码流自解析的行为。
  // Format.NO_VALUE(-1) 用 > 0 守卫，避免构造非法 ch_layout。
  // 证据见 tools/b2_pcm_init_audit.py；真机回归 NOT RUN（设备离线，§61.105）。
  if (av_get_bits_per_sample(codec->id) > 0 && rawSampleRate > 0 &&
      rawChannelCount > 0) {
    context->sample_rate = rawSampleRate;
    av_channel_layout_default(&context->ch_layout, rawChannelCount);
  }
  context->err_recognition = AV_EF_IGNORE_ERR;
  int result = avcodec_open2(context, codec, NULL);
  if (result < 0) {
    logError("avcodec_open2", result);
    releaseContext(context);
    return NULL;
  }
  return context;
}

int decodePacket(AVCodecContext* context, AVPacket* packet,
                 uint8_t* outputBuffer, int outputSize,
                 GrowOutputBufferCallback growBuffer) {
  int result = 0;
  // Queue input data.
  result = avcodec_send_packet(context, packet);
  if (result) {
    logError("avcodec_send_packet", result);
    return transformError(result);
  }

  // Dequeue output data until it runs out.
  int outSize = 0;
  while (true) {
    AVFrame* frame = av_frame_alloc();
    if (!frame) {
      LOGE("Failed to allocate output frame.");
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    result = avcodec_receive_frame(context, frame);
    if (result) {
      av_frame_free(&frame);
      if (result == AVERROR(EAGAIN)) {
        break;
      }
      logError("avcodec_receive_frame", result);
      return transformError(result);
    }

    // Resample output.
    AVSampleFormat sampleFormat = context->sample_fmt;
    int channelCount = context->ch_layout.nb_channels;
    int sampleRate = context->sample_rate;
    int sampleCount = frame->nb_samples;
    int dataSize = av_samples_get_buffer_size(NULL, channelCount, sampleCount,
                                              sampleFormat, 1);
    SwrContext* resampleContext = static_cast<SwrContext*>(context->opaque);
    if (!resampleContext) {
      result =
          swr_alloc_set_opts2(&resampleContext,             // ps
                              &context->ch_layout,          // out_ch_layout
                              context->request_sample_fmt,  // out_sample_fmt
                              sampleRate,                   // out_sample_rate
                              &context->ch_layout,          // in_ch_layout
                              sampleFormat,                 // in_sample_fmt
                              sampleRate,                   // in_sample_rate
                              0,                            // log_offset
                              NULL                          // log_ctx
          );
      if (result < 0) {
        logError("swr_alloc_set_opts2", result);
        av_frame_free(&frame);
        return transformError(result);
      }
      result = swr_init(resampleContext);
      if (result < 0) {
        logError("swr_init", result);
        av_frame_free(&frame);
        return transformError(result);
      }
      context->opaque = resampleContext;
    }

    int outSampleSize = av_get_bytes_per_sample(context->request_sample_fmt);
    int outSamples = swr_get_out_samples(resampleContext, sampleCount);
    int bufferOutSize = outSampleSize * channelCount * outSamples;
    if (outSize + bufferOutSize > outputSize) {
      LOGD(
          "Output buffer size (%d) too small for output data (%d), "
          "reallocating buffer.",
          outputSize, outSize + bufferOutSize);
      outputSize = outSize + bufferOutSize;
      outputBuffer = growBuffer(outputSize);
      if (!outputBuffer) {
        LOGE("Failed to reallocate output buffer.");
        av_frame_free(&frame);
        return AUDIO_DECODER_ERROR_OTHER;
      }
    }
    result = swr_convert(resampleContext, &outputBuffer, bufferOutSize,
                         (const uint8_t**)frame->data, frame->nb_samples);
    av_frame_free(&frame);
    if (result < 0) {
      logError("swr_convert", result);
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    int available = swr_get_out_samples(resampleContext, 0);
    if (available != 0) {
      LOGE("Expected no samples remaining after resampling, but found %d.",
           available);
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    outputBuffer += bufferOutSize;
    outSize += bufferOutSize;
  }
  return outSize;
}

int transformError(int errorNumber) {
  return errorNumber == AVERROR_INVALIDDATA ? AUDIO_DECODER_ERROR_INVALID_DATA
                                            : AUDIO_DECODER_ERROR_OTHER;
}

void logError(const char* functionName, int errorNumber) {
  char* buffer = (char*)malloc(ERROR_STRING_BUFFER_LENGTH * sizeof(char));
  av_strerror(errorNumber, buffer, ERROR_STRING_BUFFER_LENGTH);
  LOGE("Error in %s: %s", functionName, buffer);
  free(buffer);
}

void releaseContext(AVCodecContext* context) {
  if (!context) {
    return;
  }
  SwrContext* swrContext;
  if ((swrContext = (SwrContext*)context->opaque)) {
    swr_free(&swrContext);
    context->opaque = NULL;
  }
  avcodec_free_context(&context);
}

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    LOGE("JNI_OnLoad: GetEnv failed");
    return -1;
  }
  jclass clazz =
      env->FindClass("androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder");
  if (!clazz) {
    LOGE("JNI_OnLoad: FindClass failed");
    return -1;
  }
  growOutputBufferMethod =
      env->GetMethodID(clazz, "growOutputBuffer",
                       "(Landroidx/media3/decoder/"
                       "SimpleDecoderOutputBuffer;I)Ljava/nio/ByteBuffer;");
  if (!growOutputBufferMethod) {
    LOGE("JNI_OnLoad: GetMethodID failed");
    return -1;
  }
  static const JNINativeMethod kFfmpegAudioDecoderMethods[] = {
      {"ffmpegInitialize", "(Ljava/lang/String;[BZII)J",
       reinterpret_cast<void*>(ffmpegInitialize)},
      {"ffmpegDecode",
       "(JLjava/nio/ByteBuffer;ILandroidx/media3/decoder/"
       "SimpleDecoderOutputBuffer;Ljava/nio/ByteBuffer;I)I",
       reinterpret_cast<void*>(ffmpegDecode)},
      {"ffmpegGetChannelCount", "(J)I",
       reinterpret_cast<void*>(ffmpegGetChannelCount)},
      {"ffmpegGetSampleRate", "(J)I",
       reinterpret_cast<void*>(ffmpegGetSampleRate)},
      {"ffmpegReset", "(J[B)J", reinterpret_cast<void*>(ffmpegReset)},
      {"ffmpegRelease", "(J)V", reinterpret_cast<void*>(ffmpegRelease)},
  };
  if (env->RegisterNatives(clazz, kFfmpegAudioDecoderMethods,
                           sizeof(kFfmpegAudioDecoderMethods) /
                               sizeof(kFfmpegAudioDecoderMethods[0])) < 0) {
    LOGE("JNI_OnLoad: RegisterNatives failed for FfmpegAudioDecoder");
    return -1;
  }

  jclass libraryClazz =
      env->FindClass("androidx/media3/decoder/ffmpeg/FfmpegLibrary");
  if (!libraryClazz) {
    LOGE("JNI_OnLoad: FindClass failed for FfmpegLibrary");
    return -1;
  }
  static const JNINativeMethod kFfmpegLibraryMethods[] = {
      {"ffmpegGetVersion", "()Ljava/lang/String;",
       reinterpret_cast<void*>(ffmpegGetVersion)},
      {"ffmpegGetInputBufferPaddingSize", "()I",
       reinterpret_cast<void*>(ffmpegGetInputBufferPaddingSize)},
      {"ffmpegHasDecoder", "(Ljava/lang/String;)Z",
       reinterpret_cast<void*>(ffmpegHasDecoder)},
  };
  if (env->RegisterNatives(libraryClazz, kFfmpegLibraryMethods,
                           sizeof(kFfmpegLibraryMethods) /
                               sizeof(kFfmpegLibraryMethods[0])) < 0) {
    LOGE("JNI_OnLoad: RegisterNatives failed for FfmpegLibrary");
    return -1;
  }

  return JNI_VERSION_1_6;
}
