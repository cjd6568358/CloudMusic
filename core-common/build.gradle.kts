// docs/03-01 §2 · 全项目共用错误模型、ErrorCatalog、HashUtil
// docs/07-错误码总表.md · ErrorCatalog 的唯一真相源
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    // 纯 Kotlin + JDK（MessageDigest / ConcurrentHashMap），零第三方、零 Android 依赖
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
}
