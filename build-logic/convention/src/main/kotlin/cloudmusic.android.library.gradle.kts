// 约定插件：Android library（15 §7.5 #1）
//
// 多人协作约束：**不出现任何 SDK 绝对路径**。SDK 位置由 AGP 按
// local.properties(sdk.dir) → ANDROID_HOME → ANDROID_SDK_ROOT 自行解析，
// 本插件只声明「需要哪个 API 级别」，版本号统一取自 gradle/libs.versions.toml。
// 新人接入见仓库根 local.properties.example，自检用 tools/check-android-env.py。
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// precompiled script plugin 不生成 `libs` 类型安全访问器，直接用官方 API 取同一个
// version catalog，避免把版本号复制成第二份真相源。
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun catalogVersion(name: String): String = catalog.findVersion(name).get().requiredVersion

android {
    compileSdk = catalogVersion("compileSdk").toInt()

    defaultConfig {
        minSdk = catalogVersion("minSdk").toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        val java = JavaVersion.toVersion(catalogVersion("java"))
        sourceCompatibility = java
        targetCompatibility = java
    }

    kotlinOptions {
        jvmTarget = catalogVersion("java")
    }

    // namespace 与具体依赖由各模块自行声明，约定插件不代填，避免跨模块串味。
}

// Android 模块的 JVM 单测同样走 JUnit Platform（与纯 JVM 模块保持一致）。
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
