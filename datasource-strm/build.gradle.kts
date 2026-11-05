// docs/03-03 §2 · strm 消费型驱动与共享解析函数（扫描器可直接内联调用，03-04 §5）
// 纯 JVM：不触网、不做文件 IO，一律委托底层 Driver 的 TextReadCapability。
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    api(project(":datasource-api"))

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}
