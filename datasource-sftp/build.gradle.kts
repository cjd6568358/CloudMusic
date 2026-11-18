// V0.2 · SFTP 数据源（纯 JVM 驱动；JSch 传输端口由 :core-di 注入）
// 与 :datasource-webdav 同构：驱动只依赖 datasource-api，传输经可注入端口，
// JVM 单测用 fake，不触碰真实 SSH 服务。
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    api(project(":datasource-api"))

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}
