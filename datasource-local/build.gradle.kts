// docs/03-02 §3 · LocalDriver（SAF / MediaStore 双通道）
// 第十九批（12 §28，16 §5.3 T-A3）：纯 JVM 驱动 + 端口隔离；Android 框架访问
// 由 :core-di/local 的生产适配实现，本模块可脱离 SDK 编译与单测。
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    api(project(":datasource-api"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}
