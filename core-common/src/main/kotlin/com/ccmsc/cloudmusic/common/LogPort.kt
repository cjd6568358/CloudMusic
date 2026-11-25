package com.ccmsc.cloudmusic.common

/**
 * 轻量日志端口（§61.335 / B-7）：替代散落生产代码的 `System.err.println`。
 *
 * 纯 JVM 模块零 Android 依赖——`:core-di` 注入 `Log.d` 实现，`:feature-player` 等
 * 纯 JVM 测试默认拿 [NOOP]，不产生日志噪音、不依赖 logcat。
 */
fun interface LogPort {
    fun debug(tag: String, message: String)

    companion object {
        /** 默认无输出：JVM 单测与未接线的调用方零改动。 */
        val NOOP: LogPort = LogPort { _, _ -> }
    }
}
