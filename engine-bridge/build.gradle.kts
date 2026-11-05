// docs/03-06 §7 / 16 §5.3 T-P2 · IDataSource 四态实现（纯 JVM）
// 端口隔离：HTTP 用 RangeHttpStack、文件用 FdOpener、PROXIED 用 DriverStreamFactory；
// 生产实现（OkHttp / ContentResolver.openFileDescriptor）属 Android 侧，本模块不触网、不依赖 SDK。
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    api(project(":core-model"))     // Link / LinkArgs / Header / IDataSource
    api(project(":core-common"))    // CloudMusicError

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}
