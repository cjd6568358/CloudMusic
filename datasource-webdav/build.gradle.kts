// docs/03-02 §4 · WebDAV 驱动（AuthMode 三态 / PROPFIND / 直链）
// 纯 JVM：OkHttp 为 JVM 库；XML 用 JDK 自带 DOM 解析，不依赖 Android XmlPullParser。
// HTTP 经可注入 HttpStack，单测用 fake，不需要真实网络。
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    api(project(":datasource-api"))
    implementation(libs.okhttp)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}
