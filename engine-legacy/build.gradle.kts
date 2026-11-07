// docs/03-12 §1.3 / §2 / §4，12 §10.2 步骤 1（第二十三批 / T-L L1）
// 照搬舱 1：只承载 18 个 arm64 so 打包集 + R8 keep 规则。
// Java 层 84 个 JNI 真类与一层边界 slot 属 §10.2 步骤 2~4，按「后期按需」逐个补，
// 本模块当前**无任何 Java / Kotlin 源码**（AGP 允许 library 只有 jniLibs）。
//
// 多人协作：SDK 位置由 AGP 解析，本文件不含任何个人绝对路径。
plugins {
    alias(libs.plugins.cloudmusic.android.library)
}

android {
    // namespace 与舱内竞品原包名（com.netease.cloudmusic.*）无关：
    // JNI 符号绑定的是 Java 类的包名，后续真类按源码目录组织即可。
    namespace = "com.example.cloudmusic.enginelegacy"

    defaultConfig {
        // 13 §8 的 12 个精确 JNI 类 + 4 个包族通配 + 202 个 Java_* 符号类覆盖规则，
        // 经 consumerProguardFiles 传递给 :app 的 release 混淆（C1 已校验规则文本）。
        consumerProguardFiles("proguard-rules.pro")
    }

    sourceSets {
        getByName("main") {
            // 默认路径即 src/main/jniLibs，显式声明便于 C1 / 静态自检定位。
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

// T-L L1（12 §36）：EngineAdapter 实现 :engine-api 的稳定门面；
// 这些自研模块只提供契约与模型，不把 Android / Room / OkHttp 带进照搬舱。
dependencies {
    implementation(project(":core-common"))
    implementation(project(":core-model"))
    implementation(project(":engine-api"))
    implementation(libs.kotlinx.coroutines.core)
}
