// 约定插件：纯 Kotlin / JVM library（:core-common / :core-model / :datasource-api）
// 依据 docs/03-模块开发文档/03-01 数据源抽象层.md §2：
//   ":core-model 零 Android 依赖（纯 Kotlin，便于 JVM 单元测试）"
// 因此本变体不应用 com.android.library，也不引入任何 Android 框架依赖。
plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
