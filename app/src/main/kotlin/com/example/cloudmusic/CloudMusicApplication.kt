package com.example.cloudmusic

import android.app.Application
import com.example.cloudmusic.di.DriverRegistrar
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * docs/03-01 §4.1 / 12 §20：Hilt 应用入口。
 *
 * `DriverRegistrar` 持有 Hilt multibindings 的 `Map<String, DriverFactory>`，
 * 在启动时一次性灌进 [com.example.cloudmusic.datasource.DriverRegistry]。
 * 这是全项目唯一触发注册的地方；各驱动模块不自行注册，避免重复 key（T16）。
 *
 * V0.1 此处不做任何网络请求 / 凭据读取 / 日志输出（C5）。
 */
@HiltAndroidApp
class CloudMusicApplication : Application() {

    @Inject
    lateinit var driverRegistrar: DriverRegistrar

    override fun onCreate() {
        super.onCreate()
        driverRegistrar.registerAll()
    }
}
