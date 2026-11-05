// docs/03-07 §3 · IMediaControl 门面 + 焦点/按键的纯 JVM 参考实现
// :media-legacy 的 MediaLegacyAdapter 属 Android 侧照搬舱（jadx 重建 + slot 后期按需），
// 本模块只依赖 :core-model / :engine-api / coroutines，保持本机可编译可单测。
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    api(project(":core-model"))                  // Track
    api(project(":engine-api"))                  // EngineState
    api(libs.kotlinx.coroutines.core)            // StateFlow

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}
