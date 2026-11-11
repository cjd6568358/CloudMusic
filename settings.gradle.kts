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
// + 第十批 :app 与第十一批 :core-di 为 Android 模块（见下方 skipAndroid 块，12 §19 / §20）。
// + 第十七批（12 §26，T-A1）：Room 9 张物理表 + FTS4 + Migration(1,2) 落在 :core-di/db；
// + 第十八批（12 §27，T-A2）：DataStore 的 AppSettings / StorageRepository 落在 :core-di/settings；
//   两批均**未新建模块**，:core-storage 保持纯 JVM 契约层（D-40）。
// + 第十九批（12 §28，T-A3）：新增第 18 个模块 :datasource-local（纯 JVM LocalDriver），
// + 第二十批（12 §29，T-A4）：标签 / 封面 / 扫描器生产组合层落在 :core-di/scan，仍未新建模块。
// + 第二十二批（12 §31，T-A5）：nanohttpd 本体 / effects 执行者 / PlaybackRuntime 落在
//   :core-di/proxy|scan|playback，PlaybackService 与 Manifest 组件声明落在 :app，仍未新建模块。
// + 第二十三批（12 §32，T-L L1 步骤 1）：启用第 19 个模块 :engine-legacy（Android library），
//   18 个 arm64 so 落在 src/main/jniLibs/arm64-v8a/ + ENGINE-FINGERPRINT；仍无 JNI 真类 / slot。
//   SAF / MediaStore 生产适配落在 :core-di/local。**M0 时共 19 个模块；前 16 个纯 JVM +
//   :app + :core-di + :engine-legacy**，本机可编译可单测，noAndroid 逃生开关不变。
// + 12 §61.81（第一百零八批 / B2 M1）：启用第 20 个模块 :engine-opensource
//   （Media3/ExoPlayer 播放内核骨架，媒体会话仍走平台 API）；18 个私有 so 本批不移除。
// 任务拆分以 docs/16 §5 的四线任务表为唯一权威（取代 12 §7 滞后行 / 12 §20.5 / 15 §7.5 旧建议）：
//   T-P 纯 JVM 先行（:engine-bridge / :feature-player / :output-dlna，T-P4 C-05/C-06 已完成）
//   T-A Android 线（T-A1 Room / T-A2 DataStore / T-A3 本地音源 / T-A4 标签封面与扫描接线 /
//      T-A5 PlaybackService + nanohttpd 本体 + effects 执行者 /
//      T-A6 Compose UI 骨架：12 §33 全部收口，剩余 4 个主节点 = T-L L1/L2/L3 + T-V）
//   T-L 照搬舱线（L1 已按 12 §10.2 完成步骤 1~3：so + 指纹 + 舱内真类编译通过 +
//      EngineAdapter 门面换绑；登记簿 51 行。余真机 G01/G02 与 native 回调校准，见 12 §36）
//   T-V 验收线（真机 G01~G09 / 发布阻断 12 条）
// 引擎舱路线 A 已拍板、slot 后期按需实现，不影响上述模块先行（09 §2）。
// ---------------------------------------------------------------------------
include(":core-common")
include(":core-model")
include(":datasource-api")
include(":datasource-strm")
include(":datasource-playlist")
include(":datasource-webdav")
include(":datasource-local")  // 第十九批：T-A3 本地音源（纯 JVM 驱动 + 端口，12 §28）
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
// Android 模块，只跑 16 个纯 JVM 契约模块；CI / release 禁止使用该开关。
// ---------------------------------------------------------------------------
val skipAndroid = providers.gradleProperty("cloudmusic.noAndroid")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)
if (skipAndroid) {
    logger.lifecycle("[CloudMusic] -Pcloudmusic.noAndroid=true：本次跳过 Android 模块（仅限纯 JVM 契约开发）")
} else {
    include(":app")
    include(":core-di")         // 第十一批：Hilt 唯一注册点（12 §20）
    include(":engine-legacy")   // 第二十三批：T-L L1 舱体（18 个 arm64 so，12 §10.2 步骤 1）
    include(":engine-opensource")  // 12 §61.81 / B2 M1：Media3 播放内核骨架（C5 保留 :engine-legacy 回退）
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
//                   :engine-opensource (B2 逃生舱，12 §61.81 / M1 拍板后已启用)
// 媒体会话        : :media-legacy
// 输出            : :output-router, :output-dlna, :output-localproxy,
//                   :output-bluetooth
// ---------------------------------------------------------------------------
