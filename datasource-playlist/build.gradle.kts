// docs/03-03 §3 · m3u / m3u8 / pls 解析与递归展开
// 纯 JVM：解析器为纯函数，驱动器通过 TextReadCapability 委托底层数据源。
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    api(project(":datasource-api"))
    api(project(":datasource-strm")) // 播放列表条目指向 .strm 时复用 parseStrm

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}
