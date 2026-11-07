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

// 03-05 §10：导出 schema 供后续 MigrationTestHelper（T13 / T-V）使用；
// 目录由 Gradle 在构建时解析，不含任何个人绝对路径。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    // T-L L1（12 §36）：EngineModule 换绑舱内 EngineAdapter（IMusicEngine 门面不变）。
    implementation(project(":engine-legacy"))
    implementation(project(":core-media"))
    // 第十六批（12 §25）：T-P2 / T-P3 端口的生产实现与唯一组合层
    implementation(project(":engine-bridge"))
    implementation(project(":output-localproxy"))
    implementation(project(":output-dlna"))
    implementation(project(":feature-player"))
    // 第三十一批（12 §40）：补 OutputRouter 的唯一 DI 绑定（03-09 §7「唯一裁决者」）。
    implementation(project(":output-router"))
    // 第十七批（12 §26，T-A1）：RoomScanSnapshotStore 实现 :scanner 的端口
    implementation(project(":scanner"))
    // 第十九批（12 §28，T-A3）：LocalDriver 的 SAF / MediaStore 生产端口
    implementation(project(":datasource-local"))
    // 第二十批（12 §29，T-A4）：JAudioTagger 标签解析（:scanner 依赖已在第十七批引入）
    implementation(libs.jaudiotagger)
    // 第二十二批（12 §31，T-A5 / C-13）：nanohttpd 本地流代理本体（BSD-3-Clause，06 §3 已登记）
    // 版本 catalog 早已钉死 2.3.1，本批才第一次真正声明依赖。
    implementation(libs.nanohttpd)

    // 03-05 §3 Room：9 张物理表 + FTS4 + Migration（schema JSON 由 KSP 导出）
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // QueueItemRow / AppSettingsCodec 的 JSON 序列化（不引入插件，只用 Json DSL）
    implementation(libs.kotlinx.serialization.json)
    // 第十八批（12 §27，T-A2）：自定义 Serializer<AppSettings> 的 DataStore
    implementation(libs.androidx.datastore)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // WebDavDriver 的 HttpStack 需要一个 OkHttpClient；datasource-webdav 用 implementation
    // 引入 okhttp，不向下传递，此处显式声明。
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    // Android library 的 JVM 单测只覆盖纯 JVM 适配器（不触碰 Android 框架类）。
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}

// 03-05 §10 / 12 §26 D-4x：gradle.properties 开了 org.gradle.parallel=true，
// debug 与 release 的 KSP 会并发写同一个 room.schemaLocation；Room 的
// SchemaBundle.deserialize 可能读到「刚创建但尚未写完」的空 2.json 而抛
// JsonDecodingException。串行化两个变体的 KSP，保留 schema 导出能力。
tasks.matching { it.name == "kspReleaseKotlin" }.configureEach {
    mustRunAfter("kspDebugKotlin")
}
