pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CloudMusic"

// ---------------------------------------------------------------------------
// 已落地模块（2026-11-05）：03-01 契约层 + 03-02/03-03 的三个纯 JVM 数据源驱动
// + 03-04 的 :scanner 扫描编排层 + 03-05 的 :core-storage 曲库契约与内存实现
// + 03-06 的 :engine-api 稳定门面契约与内存参考实现
// + 03-07 的 :core-media 媒体会话门面 / 焦点状态机 / 按键分发
// + 03-08 的 :output-localproxy 流代理纯 JVM 契约层（Range / token / 启动策略）
// + 03-09 的 :output-router 输出路由 / 蓝牙断连裁决纯 JVM 契约层
// （全部纯 JVM，本机可编译可单测；Room / DataStore 适配与 :engine-legacy 舱仍待 Android 侧，见 12 §13 / §10）。
// 依据 docs/09 §7 / 11 §5 / 12 §10：引擎舱路线 A 已拍板、slot 后期按需实现，
// 不影响这些模块先行。:datasource-local / :core-di 依赖 Android SDK，见 12 §11.4。
// ---------------------------------------------------------------------------
include(":core-common")
include(":core-model")
include(":datasource-api")
include(":datasource-strm")
include(":datasource-playlist")
include(":datasource-webdav")
include(":scanner")
include(":core-storage")
include(":engine-api")
include(":core-media")
include(":output-localproxy")
include(":output-router")

// ---------------------------------------------------------------------------
// docs/03-模块开发文档/03-12 构建发布与合规.md §1.1 模块总表
// 后续迭代在对应目录/源码建立后再逐行启用，避免产生无 build 文件的空项目。
//
// App / UI        : :app, :ui-legacy
// 业务            : :feature-library, :feature-player
// 核心契约        : :core-di, :core-storage, :core-media
// 数据源          : :datasource-local, :datasource-webdav, :datasource-strm,
//                   :datasource-playlist  (sftp/ftp/nfs 为 V0.2+ 预留)
// 扫描            : :scanner
// 引擎            : :engine-api, :engine-legacy, :engine-bridge,
//                   :engine-opensource (逃生舱，仅在路线 C 拍板后启用)
// 媒体会话        : :media-legacy
// 输出            : :output-router, :output-dlna, :output-localproxy,
//                   :output-bluetooth
// ---------------------------------------------------------------------------
