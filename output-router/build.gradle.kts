// docs/03-09 §3 / §7 · 输出路由与蓝牙断连裁决的纯 JVM 契约层
// android.bluetooth 广播接收器、BLUETOOTH_CONNECT 权限申请属 Android 侧 :output-bluetooth；
// 本模块把状态模型、唯一裁决者与策略合并落地，避免循环依赖（见 12 §17.2）。
plugins {
    alias(libs.plugins.cloudmusic.jvm.library)
}

dependencies {
    api(project(":core-media"))                  // IMediaControl / FocusChange（唯一暂停出口）
    api(libs.kotlinx.coroutines.core)            // StateFlow<OutputDeviceState>

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}
