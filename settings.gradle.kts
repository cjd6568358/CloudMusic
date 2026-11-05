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
// + 03-11 的权限清单 / 闸门 / 降级矩阵落在 :core-common，AppSettings 与变更生效规则落在
//   :core-storage（第九批补齐既有模块缺口，不新建模块，见 12 §18）
// + 第十三批 :engine-bridge（16 §5.3 T-P2，DataSourceAdapter 四态实现，12 §22）
// + 第十四批 :feature-player（16 §5.3 T-P1，业务队列 / PlayerController，12 §23）
// + 第十五批 :output-dlna（16 §5.3 T-P3，SSDP / SOAP / DIDL-Lite / CastUriResolver，12 §24）
// + 第十批 :app 与第十一批 :core-di 为 Android 模块（见下方 skipAndroid 块，12 §19 / §20），
//   当前共 17 个模块；前 15 个全部纯 JVM，本机可编译可单测。
// 任务拆分以 docs/16 §5 的四线任务表为唯一权威（取代 12 §7 滞后行 / 12 §20.5 / 15 §7.5 旧建议）：
//   T-P 纯 JVM 先行（:engine-bridge / :feature-player / :output-dlna，T-P4 C-05/C-06 已完成）
//   T-A Android 线（Room 9 张物理表 + DataStore / :datasource-local / JAudioTagger / Service / UI）
//   T-L 照搬舱线（按 12 §10 后期按需触发）  T-V 验收线（真机 G01~G09 / 发布阻断 12 条）
// 引擎舱路线 A 已拍板、slot 后期按需实现，不影响上述模块先行（09 §2）。
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
include(":engine-bridge")   // 第十三批：T-P2 IDataSource 四态实现（12 §22）
include(":feature-player")  // 第十四批：T-P1 业务编排层（12 §23）
include(":output-dlna")     // 第十五批：T-P3 投屏协议与决策（12 §24），T-P 线收口

// ---------------------------------------------------------------------------
// 多人协作：SDK 位置由 AGP 自行解析（ANDROID_HOME / 本地 local.properties），
// 本文件与任何版本控制文件都不含个人绝对路径，见 local.properties.example。
//
// 默认包含 Android 模块，保证 CI 与 release 一定校验 Manifest（C3）与 Hilt DI 图。
// 未配置 SDK 的成员可用 ./gradlew test -Pcloudmusic.noAndroid=true 临时跳过
// Android 模块，只跑 12 个纯 JVM 契约模块；CI / release 禁止使用该开关。
// ---------------------------------------------------------------------------
val skipAndroid = providers.gradleProperty("cloudmusic.noAndroid")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)
if (skipAndroid) {
    logger.lifecycle("[CloudMusic] -Pcloudmusic.noAndroid=true：本次跳过 Android 模块（仅限纯 JVM 契约开发）")
} else {
    include(":app")
    include(":core-di")   // 第十一批：Hilt 唯一注册点（12 §20）
}

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
