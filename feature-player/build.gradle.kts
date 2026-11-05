// docs/03-06 §8 / §9 / 16 §5.3 T-P1 · 播放业务编排层（纯 JVM）
// 业务队列是唯一真相源，native 队列只读镜像；PlayerController 不直接依赖 Android 框架，
// 引擎 / 媒体会话 / Link 重建全部走端口，生产实现由 :core-di 注入。
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    api(project(":core-model"))      // Track / TrackId / SourceRef / Link
    api(project(":core-common"))     // CloudMusicError
    api(project(":engine-api"))      // IMusicEngine / EngineSource（C-05 消费方）
    api(project(":core-media"))      // PlayerControllerHandle / IMediaControl / EngineState
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}
