// docs/03-02 §V0.2 · FTP 驱动（12 §61.228）
// 纯 JVM：传输经 FtpClientPort 端口注入，commons-net 只在 :core-di 生产端口出现；
// 凭据只进 FtpConfig（内存），不落日志 / 缓存名 / TrackId。
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    api(project(":datasource-api"))

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}
