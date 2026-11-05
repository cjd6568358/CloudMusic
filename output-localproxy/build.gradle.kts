// docs/03-08 §8 · 本地 HTTP 流代理的纯 JVM 契约层
// nanohttpd 服务、局域网 IP 绑定、MulticastLock 与真机 Range 验收属 Android 侧 / 真机；
// 本模块只依赖 :core-model（Link / IDataSource），保持本机可编译可单测。
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    api(project(":core-model"))

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
}
