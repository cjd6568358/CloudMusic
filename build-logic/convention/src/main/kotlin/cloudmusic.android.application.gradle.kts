// 约定插件：Android application（15 §7.5 #1，:app 使用）
//
// 与 cloudmusic.android.library 相同的「SDK 路径零硬编码」约束：
// 只从 version catalog 读取 API 级别，SDK 根目录交给 AGP 标准解析链。
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 同 cloudmusic.android.library：用官方 API 读取唯一 version catalog，不复制版本号。
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun catalogVersion(name: String): String = catalog.findVersion(name).get().requiredVersion

android {
    compileSdk = catalogVersion("compileSdk").toInt()

    defaultConfig {
        minSdk = catalogVersion("minSdk").toInt()
        targetSdk = catalogVersion("targetSdk").toInt()
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

    // applicationId / namespace / versionCode / versionName / abiFilters 由 :app 自设。
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
