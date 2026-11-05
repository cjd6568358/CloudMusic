// docs/03-05 · 曲库与持久化（Repository 契约 + 纯 JVM 内存实现）
// Room / DataStore(Proto) / FTS4 属 Android 侧，按 03-04 的端口隔离惯例留到 :core-storage 的
// Android 适配层实现；本模块只依赖 :core-model 与 coroutines，保持本机可编译可单测。
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    api(project(":core-model"))
    api(libs.kotlinx.coroutines.core) // Repository 公开签名里的 Flow

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}
