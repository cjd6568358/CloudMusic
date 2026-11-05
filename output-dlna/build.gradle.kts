// docs/03-08 §3~§6 / 16 §5.3 T-P3 · DLNA 投屏决策与协议（纯 JVM）
// SSDP 用 DatagramSocket 端口、描述 XML 用 JDK DOM、SOAP/DIDL-Lite 为纯字符串构造；
// MulticastLock / 局域网 IP 绑定 / nanohttpd 本体属 Android 侧，本模块不依赖 SDK、不触网。
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    api(project(":core-model"))          // Track / Link / Obj
    api(project(":core-common"))         // CloudMusicError / E_OUT_* 码
    api(project(":output-localproxy"))   // ProxySettings（08 §6 单一来源的运行时投影）
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}
