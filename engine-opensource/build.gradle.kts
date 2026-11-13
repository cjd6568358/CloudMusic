// docs/12 §61.81 / B2 M1 · :engine-opensource（Media3 播放内核逃生舱）
//
// docs/02 §0 A4 预留的逃生舱在用户 2026-11-11 17:44 拍板「切换 B2」后启用；
// 本模块只承载 Media3/ExoPlayer 播放内核与 IMusicEngine 门面适配骨架，
// **不接管媒体会话 / 音频焦点**（仍走 :core-media + :media-legacy + 平台 API，C1）。
//
// §61.85 / B2 M2：FFmpeg 软解扩展落地（Maven 无 media3-ffmpeg 制品，按官方
// 《decoder_ffmpeg》模块手动构建）：
// - `src/main/jni/ffmpeg/` 为 FFmpeg 6.0 源码（**不入库**，由 build_ffmpeg.sh
//   交叉编译出 `jni/ffmpeg/android-libs/<abi>/lib{avcodec,avutil,swresample}.a`）；
// - `src/main/jni/{build_ffmpeg.sh,CMakeLists.txt,ffmpeg_jni.cc}` 原样取自
//   androidx/media 1.11.1 `libraries/decoder_ffmpeg`（Apache 2.0）；
// - `src/main/java/androidx/media3/decoder/ffmpeg/` 同源 Java 渲染器 / 解码器；
// - decoder 裁剪清单见 `build_ffmpeg.sh` 的 ENABLED_DECODERS（按 B2 F3 候选集）；
// - media3-decoder 为扩展 Java 侧依赖（SimpleDecoder / CryptoConfig 基类）。
//
// 多人协作：SDK 位置由 AGP 解析，本文件不含任何个人绝对路径。
plugins {
    alias(libs.plugins.cloudmusic.android.library)
}

android {
    namespace = "com.example.cloudmusic.engineopensource"

    // 本机实装 NDK（gradle/libs.versions.toml ndk 目录项同源）；M2 交叉编译依赖。
    ndkVersion = "27.1.12297006"

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        // 生产机 K60 = arm64；v7a 保持兼容覆盖。x86 系仅模拟器场景、按需再加。
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }
}

// 12 §61.81 M1 起 / 现行状态以 §61.92、§61.100 为准（§61.100 对齐勘误）：
// - 只引 Media3 核心制品（exoplayer / datasource / common / decoder），不引 media3-session；
// - 缓存/Range 已于 §61.88 / M3 落地为**官方** CacheDataSource + SimpleCache，
//   不再是「自研 RangeCacheDataSource 属 M3、不伪造接线」的 M1 挂账；
// - 音效（§61.100 对齐勘误）：C7 的「降级占位」口径已由用户 2026-11-13 拍板升级
//   为「必须支持」；§61.93 已在 Media3EngineAdapter.attachAudioEffect 挂系统
//   audiofx（Equalizer/BassBoost/Virtualizer）到 audioSessionId 并真机 PASS。
//   高级音效（竞品 native PCM 引擎）仍按 §61.98/§61.99 走 P2 专项，非本模块占位。
dependencies {
    implementation(project(":engine-api"))
    implementation(project(":core-common"))
    implementation(project(":core-model"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.decoder)
    implementation(libs.androidx.annotation)
    implementation(libs.checker.qual)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
}
