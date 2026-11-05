// docs/03-12 §1.1 模块总表 · :app 最小骨架（第十批）
// 仅用于打通 Android 构建链与 C3 的 Manifest 实测；业务代码 / Service / Activity
// 按 15 §7.5 后续批次补入。
// SDK 位置不在此声明：由 AGP 从 ANDROID_HOME 或本地 local.properties 解析。
plugins {
    alias(libs.plugins.cloudmusic.android.application)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.cloudmusic"

    defaultConfig {
        applicationId = "com.example.cloudmusic"
    }

    // 01 §2：仅 arm64-v8a。使用 addAll 而非 +=，避免 AGP 版本间集合类型差异。
    defaultConfig.ndk.abiFilters.addAll(listOf("arm64-v8a"))
}

dependencies {
    // :core-di 提供全项目唯一的 Hilt 注册点与门面 / Repository 绑定。
    implementation(project(":core-di"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
