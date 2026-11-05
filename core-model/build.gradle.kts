// docs/03-01 §3 / §5 · Obj / File / Link / Storage / Addition / Track / IDataSource
// 禁止出现 com.netease.*、OkHttp、Room、android.content.Context（03-01 §14 #1）
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    api(project(":core-common")) // TrackId.of 依赖 HashUtil.sha256
    // 刻意不引入任何第三方/Android 依赖，保证 JVM 单测可直接跑

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
}
