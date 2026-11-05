// docs/03-06 §4 · IMusicEngine 稳定门面（纯 JVM 契约 + 内存参考实现）
// 舱内 EngineAdapter / :engine-bridge 的 DataSourceAdapter 属 Android 侧或后期 slot，
// 本模块只依赖 :core-model / :core-common / coroutines，保持本机可编译可单测。
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    api(project(":core-model"))                  // IDataSource
    api(project(":core-common"))                 // CloudMusicError
    api(libs.kotlinx.coroutines.core)            // state(): Flow<EngineState>

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}
