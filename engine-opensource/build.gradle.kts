// docs/12 §61.81 / B2 M1 · :engine-opensource（Media3 播放内核逃生舱）
//
// docs/02 §0 A4 预留的逃生舱在用户 2026-11-11 17:44 拍板「切换 B2」后启用；
// 本模块只承载 Media3/ExoPlayer 播放内核与 IMusicEngine 门面适配骨架，
// **不接管媒体会话 / 音频焦点**（仍走 :core-media + :media-legacy + 平台 API，C1）。
//
// 多人协作：SDK 位置由 AGP 解析，本文件不含任何个人绝对路径。
plugins {
    alias(libs.plugins.cloudmusic.android.library)
}

android {
    namespace = "com.example.cloudmusic.engineopensource"
}

// 12 §61.81 / M1 骨架边界：
// - 只引 Media3 核心三制品（exoplayer / datasource / common），不引 media3-session；
// - FFmpeg 软解扩展 Maven 无制品，须手动构建并按需裁剪（M2，本批不引入）；
// - RangeCacheDataSource（自研缓存 / Range）属 M3，本批不伪造接线；
// - 音效降级（C7）：attachAudioEffect 留空实现，基础 EQ / 自研 DSP 属 M4 / P4。
dependencies {
    implementation(project(":engine-api"))
    implementation(project(":core-common"))
    implementation(project(":core-model"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.common)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
}
