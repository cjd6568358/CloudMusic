// docs/03-04 · 扫描与元数据（编排层，纯 JVM）
// Room / JAudioTagger / MediaMetadataRetriever / 封面压缩由端口接口隔离，
// 由 :core-storage 与 Android 侧适配；本模块只依赖纯 Kotlin 契约与解析函数。
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    api(project(":datasource-api"))
    implementation(project(":datasource-strm"))
    implementation(project(":datasource-playlist"))

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}
