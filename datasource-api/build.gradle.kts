// docs/03-01 §4 / §5.1 / §8 · Driver 契约、注册表、PathRule、DriverLifecycle、可选能力
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    api(project(":core-model"))
    api(project(":core-common"))
    api(libs.kotlinx.coroutines.core) // DriverCtx.coroutineContext / Dispatchers.IO 出现在公开签名
    implementation(libs.kotlinx.serialization.json) // AbstractDriver 解析 Addition.raw

    // 03-01 §4.1 的 DataSourceModule（Hilt @Module）属于 :core-di，且需要具体 Driver 实现；
    // 本模块只提供 DriverRegistry / DriverFactory，不引用 Hilt 注解。
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}
