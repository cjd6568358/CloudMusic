// docs/03-12 §1.1 模块总表 · :app 最小骨架（第十批）
// 仅用于打通 Android 构建链与 C3 的 Manifest 实测；业务代码 / Service / Activity
// 按 15 §7.5 后续批次补入。
// SDK 位置不在此声明：由 AGP 从 ANDROID_HOME 或本地 local.properties 解析。
plugins {
    alias(libs.plugins.cloudmusic.android.application)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    // T-A6（12 §33）：Kotlin 2.1.0 的 Compose 编译器插件，版本随 catalog 的 kotlin 对齐
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.cloudmusic"

    // T-A6：Compose 运行时（除播放页外全部页面自研；播放页照搬属 T-L L3，03-10 §2）
    buildFeatures {
        compose = true
    }

    defaultConfig {
        applicationId = "com.example.cloudmusic"
    }

    // 01 §2：仅 arm64-v8a。使用 addAll 而非 +=，避免 AGP 版本间集合类型差异。
    defaultConfig.ndk.abiFilters.addAll(listOf("arm64-v8a"))
}

dependencies {
    // :core-di 提供全项目唯一的 Hilt 注册点与门面 / Repository 绑定。
    implementation(project(":core-di"))
    // T-L L1（12 §32）：让 :engine-legacy 的 18 个 arm64 so 经 manifest/资源合并进 APK。
    // 舱内当前只有 jniLibs + R8 keep 规则，无 Java 源码；JNI 真类按 12 §10.2 后期按需补。
    implementation(project(":engine-legacy"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // T-A5（12 §31）：Service 收集 PlaybackRuntime.state 更新通知；
    // 12 §61.81 / B2 M1：媒体会话 / 音频焦点主导方仍不引入 Media3（不引
    // media3-session），媒体会话与通知一律用平台 API（minSdk 26 已够）；
    // 播放内核层按 B2 在 :engine-opensource 引入 androidx.media3:media3-exoplayer（C1 范围限定）。
    implementation(libs.kotlinx.coroutines.core)

    // T-A6（12 §33 / 03-10 §2）：UI 只经 Repository 与 StateFlow 取数（§11 #4）。
    // 不引 navigation-compose（V0.1 用状态切页）、不引 coil-compose（封面占位待 T-L L3）。
    implementation(project(":core-model"))
    implementation(project(":core-storage"))
    implementation(project(":feature-player"))
    // 第三十一批（12 §40）：输出设备页读取 OutputDeviceState / ActiveOutput。
    implementation(project(":output-router"))
    // §61.110：DLNA 第一个生产调用点——MainActivity 持有 DlnaCastCoordinator 引用
    // （纯 JVM 编排器；网络细节仍隔离在 core-di 组合层，UI 只见类型与结果）。
    implementation(project(":output-dlna"))
    // 第三十三批（12 §42）：焦点事件映射到 FocusChange，交给 IMediaControl / FocusPolicy 裁决。
    implementation(project(":core-media"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    // 12 §51（第四十二批）：§50 平台层改动的 JVM 单测；约定插件已配 useJUnitPlatform()。
    // 只测纯判定逻辑（MediaKeyAction），不引入 Robolectric / Android 测试框架。
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    // 12 §52（第四十三批）：用 LocalAddition.parse 反向校验 Addition JSON 键名（仅测试期）。
    testImplementation(project(":datasource-local"))
}
