// docs/03-01 §4.1 / 03-12 §1.1 / 15 §7.5 #4 · Hilt 唯一注册点
//
// 第十一批：全项目唯一的 Driver 注册点 + 门面 / Repository 的 @Singleton 绑定。
// 首次真正启用第十批的 cloudmusic.android.library 约定插件。
// 多人协作：SDK 位置由 AGP 解析，本文件不含任何个人绝对路径。
plugins {
    alias(libs.plugins.cloudmusic.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.cloudmusic.di"
}

dependencies {
    // 纯 JVM 契约层与内存实现（Android 模块可依赖 JVM 模块）
    implementation(project(":core-common"))
    implementation(project(":core-model"))
    implementation(project(":datasource-api"))
    implementation(project(":datasource-webdav"))
    implementation(project(":datasource-strm"))
    implementation(project(":datasource-playlist"))
    implementation(project(":core-storage"))
    implementation(project(":engine-api"))
    implementation(project(":core-media"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // WebDavDriver 的 HttpStack 需要一个 OkHttpClient；datasource-webdav 用 implementation
    // 引入 okhttp，不向下传递，此处显式声明。
    implementation(libs.okhttp)
}
