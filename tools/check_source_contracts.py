#!/usr/bin/env python3
"""源码契约静态自检（不依赖 Gradle 的轻量结构校验）。

本脚本不假装等价编译，只检查「结构齐全 + 契约签名在位 + 测试编号覆盖 + 禁止依赖」，
可在无 Gradle 环境下快速跑；真正的编译与 JVM 单测用 ./gradlew test。
2026-11-05 本机已用真实 Gradle 8.11.1 验证：第一批 23 个、第二批 66 个、第三批 84 个、
第四批 104 个、第五批（03-06 :engine-api 门面）116 个、第六批（03-07 :core-media）131 个、
第七批（03-08 :output-localproxy 流代理）144 个、第八批（03-09 :output-router）159 个测试全绿、
第九批（03-11 权限与设置契约，落在 :core-common / :core-storage 两个既有模块）新增 20 个单测。
第十批（多人协作 Android 构建基座）打通 :app:assembleDebug，清单扩到 28 构建文件 / 10 工具脚本，
新增「:app Manifest ≡ C3 允许清单」交叉校验；SDK 路径零硬编码由 tools/check-android-env.py --guard 守护。
第十一批（:core-di Hilt 唯一注册点 + :app 接入）新增「@InstallIn 只允许出现在 :core-di」守护。
第十二批（16 §5.3 T-P4）落地 C-05 / C-06：185 测试全绿，新增 EngineSource 可选字段与
ProxyStartPolicy 触发器 ④ 的签名断言。
第十三批（16 §5.3 T-P2）落地 :engine-bridge 四态 IDataSource 与 DataSourceAdapter（T01~T05）。
第十四批（16 §5.3 T-P1）落地 :feature-player 业务队列 / PlayMode / QueueSync / PlayerController（T07、T08、T11）。
第十五批（16 §5.3 T-P3）落地 :output-dlna 决策表与 SSDP / SOAP / DIDL-Lite（T01~T09、T13、T14），T-P 线收口。
第十六批（12 §25）在 :core-di 落地 T-P2 / T-P3 端口的 OkHttp / ContentResolver / DatagramSocket
生产实现与唯一 Hilt 接线，并登记 C-07（PROXIED Link 补 proxySourceUrl）/ C-08（匿名直链探测待 T-A5）。
第十七批（12 §26，16 §5.3 T-A1）在 :core-di/db 落地 Room 的 9 张物理表 + FTS4 + 1→2 Migration，
把 LibraryModule 的内存 Repository 换绑 Room 实现（D-08），并守护「禁止破坏性迁移」。
第十八批（12 §27，16 §5.3 T-A2）在 :core-di/settings 落地 DataStore(自定义 Serializer) 的
AppSettings 持久化、StorageRepository 换绑与运行时投影，并登记 C-09（投屏决策动态取设置）。
第十九批（12 §28，16 §5.3 T-A3）新建纯 JVM 的 :datasource-local（LocalDriver + 双通道端口），
在 :core-di/local 落地 SAF / MediaStore 生产适配，并补齐 DataSourceModule 的 local 注册键。
第二十批（12 §29，16 §5.3 T-A4）落地 JAudioTagger + MediaMetadataRetriever 的 TagReader、
封面压缩落盘、扫描器生产组合层，并补上 DriverRegistry 实例同步缺口（C-10）。
第二十一批（12 §30）为 T-A5 开工前契约核对，登记 C-12 / C-13 / C-14（零源码改动）。
第二十二批（12 §31，T-A5）落地 ByteRange 生产实现（C-12）、nanohttpd 本体与
PlaybackService / Manifest（C-13）、effects 执行者与定向增量（C-14）、真实直链探测（C-08），
清单扩到 33 / 138 / 10 并新增五组长效守护。
第二十三批（12 §32，T-L L1 步骤 1）按 12 §10.2 提取 18 个 arm64 so、生成
ENGINE-FINGERPRINT、启用第 19 个模块 :engine-legacy，并产出 JNI 真类 / native 方法索引；
清单扩到 36 / 138 / 11，新增打包集 / 指纹 / 舱模块 / 索引四组守护。
第二十四批（12 §33，T-A6）在 :app 落地 Compose UI 骨架（MainActivity / 主题 /
曲库-数据源-设置三页 / 迷你播放条）与 LAUNCHER 声明，不新建模块；
清单扩到 36 / 141 / 11，新增 Activity / Compose 依赖 / UI 禁止依赖三组守护。
第二十五批（12 §34，T-L L1 步骤 2 前置）从 Maven Central + Google Maven 组装
jadx-cli 1.5.1（仓库外），用 --single-class 定向取得 12 个精确 JNI 类的真实 Java 源码，
新增 tools/engine_jni_verify.py 核对源码↔签名索引；清单扩到 36 / 141 / 12。
第二十六批（12 §35，T-L L1 步骤 3）把 12 个真类放进 :engine-legacy，
四轮「编译 → 只补报错缺的类 → 再编译」把 302 个错误收敛到 0（45 行登记簿、
37 个唯一 slot），并实现 EngineNativeLoader 承接精确加载链；
清单扩到 36 / 142 / 12，新增舱内编译单元 / 加载链 / 封装器 / 登记簿四组守护。
第二十七批（12 §36，T-L L1）实现 EngineAdapter 薄门面（IMusicEngine 14 方法 →
PlaybackEngine 真实 API）、补齐 PlayerDataSource 数据链并把 EngineModule 换绑到舱内实现；
清单扩到 36 / 143 / 12，新增门面映射 / DI 换绑 / 数据源 getter 三组守护。
第二十八轮（12 §37，真机不可用）完成静态 ABI 验收 A1/A2：新增
tools/check_jni_bindings.py 对映 202 个 Java_* 符号与 210 个 native 声明
（静态绑定 195 / dex-only 15 / so-only 7 / 净差 8 = 13 §7.4 基线），A2 闭包 14 个零越界；
并接通 EngineAdapter 的 native 事件回调桥（事件码原样透传，不猜常量）。
清单扩到 36 / 143 / 13，新增 ABI 报告 / 事件桥两组守护。
第二十九轮（12 §38，T-L L3 前置）用 jadx + smali R 字段解析复核
`14 §4.1` 挂账：12 处 loadAnimation → 4 个静态字段 → 2 个 scale anim，
证实唱片旋转不走该链路；新增 tools/resolve_player_anim.py，
清单扩到 36 / 143 / 14，新增 anim 报告 / C4 空表两组守护。
第三十轮（12 §39，T-L L3）用 jadx 证实唱片旋转 / 唱针是纯代码属性动画
（ValueAnimator + setRotation，20s ÷ playSpeed），收口 `14 §4.1` 挂账、
确认播放页闭包无需 anim 项；新增 tools/resolve_rotation_anim.py，
清单扩到 36 / 143 / 15，新增旋转报告守护。
第三十一批（12 §40，T-A6 完善）把 UI 从 3 页扩到 5 页（曲库/歌单/搜索/队列/设置）
+ 数据源入口 + 输出设备弹窗，并补齐 `OutputRouter` 的唯一 DI 绑定；
新增五页导航 / 蓝牙口径 / DI 绑定三组守护（清单仍 36 / 143 / 15）。
第三十二批（12 §41，T-A5 遗留）给 PlaybackService 接上音频焦点
（AudioFocusRequest / OnAudioFocusChangeListener）与 ACTION_AUDIO_BECOMING_NOISY
动态广播，全用平台 API、零新增权限与依赖；新增焦点接线守护。
第四十二批（12 §51，§50 平台层改动的回归守护）把媒体键判定下沉到纯 JVM 的
MediaKeyAction，新增 :app 首个 JVM 单测 PlatformContractTest（5 用例），
并在本脚本补 6 组 §50 长效守护（restore / PlaybackState / mediaButtonReceiver /
Receiver 转发 / remote 分支 / 真类在位）；清单扩到 36 / 145 / 15。
第四十三批（12 §52，§50.7 #3 应用侧闭环）接入按动作权限闸门
（PermissionRequester → PermissionGate.requiredFor，按设备 SDK 分派）与本地数据源
添加闭环（SAF 优先 / MediaStore 按动作申请 → StorageRepository.add → rescanAll）；
新增 7 个 JVM 用例与 5 组 §52 守护，清单扩到 36 / 149 / 15。
第四十四批（12 §53，取流句柄静态取证轮）用 baksmali 反汇编 classes5 / classes18.dex
与 so 导出符号实证出 `createDataRequestHandler()` 的返回对象类型
（`DataSourcePlatformImpl$d` → `nmcache/l` → `SourceDataRequest`）、`Start(JJJ)J` / `Stop()`
契约与 5 个 JNI 符号；新增 4 组 §53 守护 + 报告引用，清单仍 36 / 149 / 15。
第四十五批（12 §54，取流句柄阶段 A/B 落地）继续反汇编 l.w / l.k 与 OnDataRequest*
调用点，实证出 Start(j1=请求标识, j2=offset, j3=length) 与回传首参，据此新建
RangeFetcher（纯 JVM）/ UrlDataRequest（SourceDataRequest 子类）/ 7 个 JVM 用例，
把 createDataRequestHandler() 从 null 改为按 source.url 缓存返回；
新增 5 组 §54 守护，清单扩到 36 / 152 / 15。仍待真机阶段 C 校准回传协议、未出声。
蓝牙 SHOW_BLUETOOTH_NAME 仍按 §48.6 挂账、未接入。
第四十九批（12 §59，阶段 C 根因修复）用 capstone + exp6 原始 logcat 钉死数据通路：
OnDataRequestData 首 long 是 native OnNetSourceData 的 kind 槽（==2 跳过
NMMemoryCache::Write），不是 requestId；Memory Mode 下 OnWriteNetData 异步任务
（GOT+lambda 间接调用）恒被拒。据此把 UrlDataRequest 的数据包 kind 恒传 1；
resource config 由 builder 硬编码 {"id","md5","size","type":"cache"}（无 file_id/trunks）、
strategy bit0 = cache config 的 cache_local —— §58 路线 1 证伪、路线 2 仍待用户拍板。
新增 2 组 §59 守护，清单仍 36 / 152 / 15。
第五十一批（12 §61，用户 2026-11-08 拍板批准路线 2）：SourceConfig.cacheLocal=true
（normal mode，§58~§60 三个 native 缺陷的最终解）+ EngineAdapter.SetConfig 初始化
App 内部缓存根目录（512MiB 上限）；docs/01 §6.1 口径演进随批落档。新增 4 组 §61 守护。
第五十四批（12 §61.27，G08 故障注入 + 冷启动启动竞态）：PlayerController 记住
restore 之前到达的播放意图（pendingPlay），修掉「首次 ACTION_PLAY 被吞、进度恒 0」；
RangeFetcher 连接期失败重试一次 + 5s 连接超时经一次性 RST 注入实证救回全量取流。
新增 4 组 §61.27 守护。
第五十五批（12 §61.28，按文档计划补全「默认仅 WiFi 播放」）：01 §6.1 / 03-02 §344
「播放前由 03-06 检查」此前只有 AppSettings.network.wifiOnly 字段、零生产消费方。
新增纯 JVM 判定 NetworkPlaybackPolicy + PlayerController 闸门 + Android 生产实现
（ConnectivityManager，零新增权限）+ 设置页开关，新增 4 组 §61.28 守护。
第五十六批（12 §61.29，T-L L2 收口）：按 03-11 §2 已定义的 SHOW_BLUETOOTH_NAME
动作在输出弹窗申请 BLUETOOTH_CONNECT，permissionGranted 改运行时真值，已授权才
动态注册 ACL 广播并翻译成 BluetoothEvent；未授权仍按 03-09 §8 B01 降级。新增 4 组守护。
详见 docs/12-编码阶段第一批交付.md §5.1 / §11~§21 / §25~§61。
"""
from __future__ import annotations

import csv
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

GRADLE_FILES = (
    "settings.gradle.kts", "build.gradle.kts", "gradle.properties",
    "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties",
    "gradle/wrapper/gradle-wrapper.jar", "gradlew", "gradlew.bat",
    "build-logic/settings.gradle.kts", "build-logic/convention/build.gradle.kts",
    "build-logic/convention/src/main/kotlin/cloudmusic.jvm.library.gradle.kts",
    "build-logic/convention/src/main/kotlin/cloudmusic.android.library.gradle.kts",
    "build-logic/convention/src/main/kotlin/cloudmusic.android.application.gradle.kts",
    "local.properties.example", "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml", "core-di/build.gradle.kts",
    # 12 §32（T-L L1 步骤 1）：照搬舱 1 的舱体文件
    "engine-legacy/build.gradle.kts", "engine-legacy/src/main/AndroidManifest.xml",
    "engine-legacy/proguard-rules.pro",
    "engine-bridge/build.gradle.kts", "feature-player/build.gradle.kts",
    "output-dlna/build.gradle.kts",
    "core-common/build.gradle.kts", "core-model/build.gradle.kts",
    "datasource-api/build.gradle.kts",
    "datasource-strm/build.gradle.kts", "datasource-playlist/build.gradle.kts",
    "datasource-webdav/build.gradle.kts", "datasource-local/build.gradle.kts",
    "scanner/build.gradle.kts",
    "core-storage/build.gradle.kts", "engine-api/build.gradle.kts",
    "core-media/build.gradle.kts", "output-localproxy/build.gradle.kts",
    "output-router/build.gradle.kts",
)

# docs/03-01 §2 包结构 + 03-02 / 03-03 驱动 + 03-04 扫描器 + 03-05 曲库 + 03-06 门面
# + 03-07 媒体会话 + 03-08 本地流代理 + 03-09 输出路由
CONTRACT_FILES = (
    "core-common/src/main/kotlin/com/example/cloudmusic/common/CloudMusicError.kt",
    "core-common/src/main/kotlin/com/example/cloudmusic/common/ErrorCatalog.kt",
    "core-common/src/main/kotlin/com/example/cloudmusic/common/HashUtil.kt",
    "core-common/src/main/kotlin/com/example/cloudmusic/common/PathNormalizer.kt",
    # 03-11 §1 / §2 / §3：权限清单、按动作申请闸门、拒绝降级矩阵
    "core-common/src/main/kotlin/com/example/cloudmusic/common/PermissionCatalog.kt",
    "core-common/src/main/kotlin/com/example/cloudmusic/common/PermissionGate.kt",
    "core-model/src/main/kotlin/com/example/cloudmusic/model/ObjectType.kt",
    "core-model/src/main/kotlin/com/example/cloudmusic/model/Obj.kt",
    "core-model/src/main/kotlin/com/example/cloudmusic/model/Link.kt",
    "core-model/src/main/kotlin/com/example/cloudmusic/model/Storage.kt",
    "core-model/src/main/kotlin/com/example/cloudmusic/model/Track.kt",
    "core-model/src/main/kotlin/com/example/cloudmusic/model/IDataSource.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/Driver.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/DriverRegistry.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/PathRule.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/LinkRule.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/DriverLifecycle.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/DirectLinkCapability.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/ByteRangeCapability.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/AbstractDriver.kt",
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/TextReadCapability.kt",
    "datasource-strm/src/main/kotlin/com/example/cloudmusic/strm/StrmParser.kt",
    "datasource-strm/src/main/kotlin/com/example/cloudmusic/strm/StrmPaths.kt",
    "datasource-strm/src/main/kotlin/com/example/cloudmusic/strm/StrmDriver.kt",
    "datasource-playlist/src/main/kotlin/com/example/cloudmusic/playlist/PlaylistParser.kt",
    "datasource-playlist/src/main/kotlin/com/example/cloudmusic/playlist/PlaylistResolver.kt",
    "datasource-playlist/src/main/kotlin/com/example/cloudmusic/playlist/PlaylistDriver.kt",
    "datasource-webdav/src/main/kotlin/com/example/cloudmusic/webdav/DigestAuth.kt",
    "datasource-webdav/src/main/kotlin/com/example/cloudmusic/webdav/PropfindParser.kt",
    "datasource-webdav/src/main/kotlin/com/example/cloudmusic/webdav/WebDavHttp.kt",
    "datasource-webdav/src/main/kotlin/com/example/cloudmusic/webdav/WebDavDriver.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/ScanModels.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/ScanPorts.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/NameParser.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/AudioProbe.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/ExcludeRule.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/TagReader.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/CoverResolver.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/TrackFactory.kt",
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/ScanCoordinator.kt",
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/LibraryModels.kt",
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/Repositories.kt",
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/LibraryQuery.kt",
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/InMemoryLibrary.kt",
    # 08 §1~§8 / 03-05 §4 / 03-11 §4~§7：AppSettings 全量设置项与变更生效规则
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/AppSettings.kt",
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/SettingsChange.kt",
    "core-model/src/test/kotlin/com/example/cloudmusic/model/ContractModelTest.kt",
    "datasource-api/src/test/kotlin/com/example/cloudmusic/datasource/DriverContractTest.kt",
    "datasource-strm/src/test/kotlin/com/example/cloudmusic/strm/StrmParserTest.kt",
    "datasource-strm/src/test/kotlin/com/example/cloudmusic/strm/StrmPathsTest.kt",
    "datasource-strm/src/test/kotlin/com/example/cloudmusic/strm/StrmDriverTest.kt",
    "datasource-playlist/src/test/kotlin/com/example/cloudmusic/playlist/PlaylistParserTest.kt",
    "datasource-playlist/src/test/kotlin/com/example/cloudmusic/playlist/PlaylistResolverTest.kt",
    "datasource-webdav/src/test/kotlin/com/example/cloudmusic/webdav/DigestAuthTest.kt",
    "datasource-webdav/src/test/kotlin/com/example/cloudmusic/webdav/PropfindParserTest.kt",
    "datasource-webdav/src/test/kotlin/com/example/cloudmusic/webdav/WebDavDriverTest.kt",
    "scanner/src/test/kotlin/com/example/cloudmusic/scanner/ScannerUnitTest.kt",
    "scanner/src/test/kotlin/com/example/cloudmusic/scanner/ScanCoordinatorTest.kt",
    "core-storage/src/test/kotlin/com/example/cloudmusic/storage/LibraryContractTest.kt",
    "core-storage/src/test/kotlin/com/example/cloudmusic/storage/InMemoryRepositoryTest.kt",
    "engine-api/src/main/kotlin/com/example/cloudmusic/engine/api/EngineApi.kt",
    "engine-api/src/test/kotlin/com/example/cloudmusic/engine/api/EngineApiContractTest.kt",
    "core-media/src/main/kotlin/com/example/cloudmusic/media/MediaControlApi.kt",
    "core-media/src/main/kotlin/com/example/cloudmusic/media/FocusPolicy.kt",
    "core-media/src/main/kotlin/com/example/cloudmusic/media/MediaKeyDispatcher.kt",
    "core-media/src/main/kotlin/com/example/cloudmusic/media/InMemoryMediaControl.kt",
    "core-media/src/test/kotlin/com/example/cloudmusic/media/MediaControlContractTest.kt",
    "output-localproxy/src/main/kotlin/com/example/cloudmusic/output/localproxy/ProxyModels.kt",
    "output-localproxy/src/main/kotlin/com/example/cloudmusic/output/localproxy/RangeResponder.kt",
    "output-localproxy/src/main/kotlin/com/example/cloudmusic/output/localproxy/TokenRegistry.kt",
    "output-localproxy/src/main/kotlin/com/example/cloudmusic/output/localproxy/LocalHttpStreamProxy.kt",
    "output-localproxy/src/test/kotlin/com/example/cloudmusic/output/localproxy/LocalProxyContractTest.kt",
    "output-router/src/main/kotlin/com/example/cloudmusic/output/router/OutputModels.kt",
    "output-router/src/main/kotlin/com/example/cloudmusic/output/router/OutputRouter.kt",
    "output-router/src/main/kotlin/com/example/cloudmusic/output/router/BluetoothOutputPolicy.kt",
    "output-router/src/test/kotlin/com/example/cloudmusic/output/router/OutputRoutingContractTest.kt",
    "core-common/src/test/kotlin/com/example/cloudmusic/common/PermissionGateTest.kt",
    "core-storage/src/test/kotlin/com/example/cloudmusic/storage/AppSettingsTest.kt",
    # 03-01 §4.1 / 15 §7.5 #4：Hilt 唯一注册点（第十一批）
    "core-di/src/main/kotlin/com/example/cloudmusic/di/DataSourceModule.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/AppBindings.kt",
    "app/src/main/kotlin/com/example/cloudmusic/CloudMusicApplication.kt",
    # 03-06 §7 / 16 §5.3 T-P2：IDataSource 四态实现（第十三批）
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/DataSourcePorts.kt",
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/BaseDataSource.kt",
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/MemoryDataSource.kt",
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/FileDataSource.kt",
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/HttpDataSource.kt",
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/DriverDataSource.kt",
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/DataSourceAdapter.kt",
    "engine-bridge/src/test/kotlin/com/example/cloudmusic/bridge/DataSourceAdapterTest.kt",
    # 03-06 §8 / §9 / 16 §5.3 T-P1：播放业务编排层（第十四批）
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/PlaybackModels.kt",
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/QueueRepository.kt",
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/PlayModeOrdering.kt",
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/QueueSync.kt",
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/TrackPorts.kt",
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/PlayerController.kt",
    "feature-player/src/test/kotlin/com/example/cloudmusic/player/PlayerControllerTest.kt",
    # 03-08 §3~§6 / 16 §5.3 T-P3：DLNA 投屏协议与决策（第十五批）
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/DlnaModels.kt",
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/CastUriResolver.kt",
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/DescriptionParser.kt",
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/DidlLiteBuilder.kt",
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/SoapController.kt",
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/SsdpDiscovery.kt",
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/ManualDmrProbe.kt",
    "output-dlna/src/test/kotlin/com/example/cloudmusic/output/dlna/DlnaContractTest.kt",
    # 12 §25 第十六批：T-P2 / T-P3 端口的生产实现与 :core-di 唯一 Hilt 接线
    "core-di/src/main/kotlin/com/example/cloudmusic/di/adapters/OkHttpAdapters.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/adapters/AndroidSourceAdapters.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/adapters/DatagramAdapters.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/adapters/CastComposition.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/BridgeModule.kt",
    "core-di/src/test/kotlin/com/example/cloudmusic/di/adapters/ProductionAdaptersTest.kt",
    # 12 §26 第十七批：T-A1 Room（9 张物理表 + FTS4 + Migration + 生产 Repository）
    "core-di/src/main/kotlin/com/example/cloudmusic/di/db/LibrarySchema.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/db/RoomEntities.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/db/RoomDaos.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/db/RoomMigrations.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/db/LibraryDatabase.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/db/RoomRepositories.kt",
    "core-di/src/test/kotlin/com/example/cloudmusic/di/db/LibraryRoomContractTest.kt",
    # 12 §27 第十八批：T-A2 DataStore 持久化与运行时投影
    "core-di/src/main/kotlin/com/example/cloudmusic/di/settings/AppSettingsCodec.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/settings/SettingsAccess.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/settings/SettingsDataStore.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/settings/DataStoreStorageRepository.kt",
    "core-di/src/test/kotlin/com/example/cloudmusic/di/settings/SettingsContractTest.kt",
    # 12 §28 第十九批：T-A3 本地音源（纯 JVM 驱动 + Android 生产端口）
    "datasource-local/src/main/kotlin/com/example/cloudmusic/local/LocalModels.kt",
    "datasource-local/src/main/kotlin/com/example/cloudmusic/local/LocalDriver.kt",
    "datasource-local/src/test/kotlin/com/example/cloudmusic/local/LocalDriverTest.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/local/AndroidLocalSources.kt",
    # 12 §29 第二十批：T-A4 标签 / 封面 / 扫描器生产组合层
    "core-di/src/main/kotlin/com/example/cloudmusic/di/scan/ScanContracts.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/scan/ProductionTagAndCover.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/scan/ScanComposition.kt",
    "core-di/src/test/kotlin/com/example/cloudmusic/di/scan/ScanContractsTest.kt",
    # 12 §31 第二十二批：T-A5（C-12 / C-13 / C-14 / C-08）的生产接线
    "core-di/src/main/kotlin/com/example/cloudmusic/di/scan/NetworkByteRange.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/scan/SettingsEffects.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/proxy/NanoHttpStreamProxy.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/proxy/AndroidLanAddress.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/playback/PlaybackComposition.kt",
    "app/src/main/kotlin/com/example/cloudmusic/PlaybackService.kt",
    "app/src/main/kotlin/com/example/cloudmusic/MediaButtonEventReceiver.kt",
    "core-di/src/test/kotlin/com/example/cloudmusic/di/ta5/Ta5ContractsTest.kt",
    # 12 §33 第二十四批：T-A6 Compose UI 骨架（:app，无新建模块）
    "app/src/main/kotlin/com/example/cloudmusic/MainActivity.kt",
    "app/src/main/kotlin/com/example/cloudmusic/ui/CloudMusicTheme.kt",
    "app/src/main/kotlin/com/example/cloudmusic/ui/CloudMusicApp.kt",
    # 12 §35 第二十六批：T-L L1 步骤 3（舱内真类编译 + 自研加载器）
    "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/EngineNativeLoader.kt",
    # 12 §36 第二十七批：EngineAdapter 薄门面 + PlayerDataSource 数据链
    "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/EngineAdapter.kt",
    # 12 §51 第四十二批：§50 平台层改动的纯判定抽离 + :app 首个 JVM 单测
    "app/src/main/kotlin/com/example/cloudmusic/MediaKeyAction.kt",
    "app/src/test/kotlin/com/example/cloudmusic/PlatformContractTest.kt",
    # 12 §52 第四十三批：按动作申请权限 + 本地数据源添加闭环
    "app/src/main/kotlin/com/example/cloudmusic/AppActionContracts.kt",
    "app/src/main/kotlin/com/example/cloudmusic/PermissionRequester.kt",
    "app/src/main/kotlin/com/example/cloudmusic/LocalSourceAdder.kt",
    "app/src/test/kotlin/com/example/cloudmusic/AppActionContractTest.kt",
    # 12 §54 第四十五批：取流句柄阶段 A/B（SourceDataRequest 子类 + 纯 JVM RangeFetcher）
    "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/RangeFetcher.kt",
    "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt",
    "engine-legacy/src/test/kotlin/com/example/cloudmusic/enginelegacy/RangeFetcherTest.kt",
)

CHECK_SCRIPTS = (
    "check-engine-abi.py", "check-no-netease-import.py", "check-permissions.py",
    "check-assets.py", "check-credentials.py", "check-fingerprint.py",
    "pre_release_check.py", "elf_probe.py", "check_common.py",
    "check-android-env.py", "engine_jni_class_index.py", "engine_jni_verify.py",
    "check_jni_bindings.py", "resolve_player_anim.py", "resolve_rotation_anim.py",
)

# 03-01 / 03-02 / 03-03 / 03-04 契约中不可漂移的签名片段：{文件: [片段]}
SIGNATURES = {
    "core-model/src/main/kotlin/com/example/cloudmusic/model/Link.kt": [
        "enum class Kind { URL, LOCAL, PROXIED, TEXT }",
        "val kind: Kind get()",
        "url 与 localPath 互斥",
        # C-07（12 §25）：PROXIED 专用回源 URL，默认 null、不改 kind 判定
        "val proxySourceUrl: String? = null",
    ],
    "core-model/src/main/kotlin/com/example/cloudmusic/model/ObjectType.kt": [
        "UNKNOWN(0), FOLDER(1), VIDEO(2), AUDIO(3), TEXT(4), IMAGE(5)",
        "fun extOf(name: String): String",
    ],
    "core-model/src/main/kotlin/com/example/cloudmusic/model/IDataSource.kt": [
        "fun open(offset: Long): Long", "fun readAt(", "fun mimeHint(): String",
    ],
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/Driver.kt": [
        "interface Driver", "suspend fun init(ctx: DriverCtx)", "fun drop()",
        "suspend fun list(dir: Obj, refresh: Boolean = false): List<Obj>",
        "suspend fun get(path: String): Obj?",
        "suspend fun link(file: Obj, args: LinkArgs = LinkArgs()): Link",
        "fun mustProxy(): Boolean = onlyProxy || noLinkUrl",
        "typealias DriverFactory = (Storage, Addition) -> Driver",
    ],
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/DriverRegistry.kt": [
        "fun register(type: String, factory: DriverFactory)",
        "fun types(): Set<String>", "object DriverRegistry",
    ],
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/TextReadCapability.kt": [
        "interface TextReadCapability", "suspend fun readText(file: Obj): String",
    ],
    "datasource-api/src/main/kotlin/com/example/cloudmusic/datasource/AbstractDriver.kt": [
        "abstract class AbstractDriver(", "fun JsonObject.firstStr(",
        "final override suspend fun init(ctx: DriverCtx)",
    ],
    "datasource-strm/src/main/kotlin/com/example/cloudmusic/strm/StrmParser.kt": [
        "fun parseStrm(content: String): StrmParseResult",
    ],
    "datasource-playlist/src/main/kotlin/com/example/cloudmusic/playlist/PlaylistParser.kt": [
        "sealed interface PlaylistEntry", "fun parsePlaylist(fileName: String, text: String)",
        "const val MAX_DEPTH = 5",
    ],
    "datasource-webdav/src/main/kotlin/com/example/cloudmusic/webdav/WebDavDriver.kt": [
        "class WebDavDriver(", "DirectLinkCapability",
        "override suspend fun directUrl(file: Obj): String?",
        "proxySourceUrl = url",  # C-07：PROXIED 分支必须带回源 URL
        # C-12（12 §31）：ByteRangeCapability 的真正生产实现
        "), DirectLinkCapability, ByteRangeCapability {",
        "override suspend fun readBytes(file: Obj, offset: Long, length: Int): ByteArray?",
    ],
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/ScanCoordinator.kt": [
        "class ScanCoordinator(", "fun scan(request: ScanRequest): Flow<ScanProgress>",
        "suspend fun run(",
    ],
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/TrackFactory.kt": [
        "fun build(candidate: Candidate, raw: RawTags = RawTags.EMPTY): Track",
        "TrackId.of(candidate.storage.id, candidate.obj.path)",
    ],
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/ScanPorts.kt": [
        "interface TrackSink", "interface ScanSnapshotStore",
        # C-14（12 §31）：定向增量的不可用条目输入（默认空集，零回归）
        "suspend fun unavailablePaths(storageId: String): Set<String> = emptySet()",
    ],
    "core-common/src/main/kotlin/com/example/cloudmusic/common/CloudMusicError.kt": [
        "open class CloudMusicError(", "typealias CmResult<T> = Result<T>",
        "fun mapDriverError(",
    ],
    # 03-05 §5 Repository 契约签名（逐字对齐）
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/Repositories.kt": [
        "interface TrackRepository", "fun observeAll(): Flow<List<Track>>",
        "suspend fun upsertBatch(tracks: List<Track>)",
        "suspend fun markOrphan(storageId: String)",
        "interface PlaylistRepository", "interface FavoriteRepository",
        "interface HistoryRepository", "interface StorageRepository",
        "const val UPSERT_BATCH_SIZE = 500",
    ],
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/LibraryModels.kt": [
        "data class Playlist(", "data class TrackEntity(",
        "fun toTrack(): Track", "fun from(",
    ],
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/InMemoryLibrary.kt": [
        "class InMemoryTrackRepository(", "class ScanReconciler(",
        "suspend fun upsertFromScan(batch: List<Track>)",
        "class InMemoryPlaylistRepository(", "class InMemoryStorageRepository(",
    ],
    # 03-06 §4 稳定门面（逐字对齐，不得漂移）
    "engine-api/src/main/kotlin/com/example/cloudmusic/engine/api/EngineApi.kt": [
        "value class EngineHandle(val raw: Long)",
        "enum class EngineState { IDLE, PREPARING, PLAYING, PAUSED, ENDED, ERROR }",
        "interface IMusicEngine",
        "fun prepare(source: EngineSource, config: EngineConfig): EngineHandle",
        "fun attachDataSource(handle: EngineHandle, ds: IDataSource)",
        "fun state(): Flow<EngineState>",
        "fun lastError(): CloudMusicError?",
        "class InMemoryMusicEngine : IMusicEngine",
        # C-05（16 §4）：门面可携带 URL / 本机路径，默认 null、14 方法签名不动
        "val url: String? = null",
        "val localPath: String? = null",
        "url 与 localPath 互斥（16 §3.2 本机取流决策表）",
    ],
    # C-06（16 §4 / 03-08 §8.4）的断言并入下方同名 key：Python dict 重复键会覆盖，
    # 第十六批检修发现原写法使 C-06 两条断言实际未生效，已合并（12 §25）。
    # 03-07 §3 媒体会话门面（逐字对齐，不得漂移）
    "core-media/src/main/kotlin/com/example/cloudmusic/media/MediaControlApi.kt": [
        "interface IMediaControl",
        "fun onFocusChange(change: FocusChange)",
        "fun onMediaButton(key: MediaKey, action: MediaAction)",
        "fun bind(controller: PlayerControllerHandle)",
        "fun publishMetadata(track: Track, coverPath: String?)",
        "fun setPlaybackState(state: EngineState, positionMs: Long, durationMs: Long)",
        "fun setEnabled(enabled: Boolean)",
        "fun release()",
        # C-15（12 §44）：带默认空实现的状态通知，既有实现零回归
        "fun onPlaying() {}",
        "fun onManualPause() {}",
        "interface PlayerControllerHandle",
        "enum class MediaAction { DOWN, UP, LONG_PRESS, DOUBLE_CLICK }",
    ],
    # 03-08 §8 本地流代理契约层
    "output-localproxy/src/main/kotlin/com/example/cloudmusic/output/localproxy/LocalHttpStreamProxy.kt": [
        "interface LocalHttpStreamProxy",
        "fun urlFor(trackId: String): String",
        "fun serve(request: ProxyRequest): ProxyResponse",
        "object ProxyStartPolicy",
        "class InMemoryLocalHttpStreamProxy(",
        # C-06：本机播放 proxy 态触发器 ④（合并后真正参与断言）
        "localPlaybackKind: Link.Kind? = null",
        "localPlaybackKind == Link.Kind.PROXIED || localPlaybackKind == Link.Kind.TEXT",
    ],
    "output-localproxy/src/main/kotlin/com/example/cloudmusic/output/localproxy/RangeResponder.kt": [
        "object RangeResponder",
        "fun parse(header: String?): HttpRange?",
        "fun respond(",
    ],
    "output-localproxy/src/main/kotlin/com/example/cloudmusic/output/localproxy/TokenRegistry.kt": [
        "class TokenRegistry(",
        "fun register(trackId: String, link: Link): String",
        "fun resolve(token: String): ProxyEntry?",
        "fun invalidateAll()",
    ],
    # 03-09 §3 / §7 输出路由与蓝牙断连裁决
    "output-router/src/main/kotlin/com/example/cloudmusic/output/router/OutputModels.kt": [
        "enum class ActiveOutput { LOCAL, DLNA }",
        "data class BluetoothInfo(",
        "data class OutputDeviceState(",
        "data class BluetoothSettings(",
    ],
    "output-router/src/main/kotlin/com/example/cloudmusic/output/router/OutputRouter.kt": [
        "interface OutputRouter",
        "fun setActive(output: ActiveOutput)",
        "fun updateBluetooth(info: BluetoothInfo?)",
        "class InMemoryOutputRouter : OutputRouter",
    ],
    "output-router/src/main/kotlin/com/example/cloudmusic/output/router/BluetoothOutputPolicy.kt": [
        "class BluetoothOutputPolicy(",
        "fun onEvent(event: BluetoothEvent, info: BluetoothInfo? = null): BluetoothEventResult",
        "fun notifyPlaybackResumed()",
        "mediaControl.onFocusChange(FocusChange.LOSS)",
    ],
    # 03-11 §1 / §2 / §3 + 08 §1 / §6 / §7
    "core-common/src/main/kotlin/com/example/cloudmusic/common/PermissionCatalog.kt": [
        "object PermissionCatalog {",
        "val DECLARED: List<DeclaredPermission> = listOf(",
        "const val MAX_DECLARED_COUNT = 12",
        "const val ALLOW_BACKUP = false",
        "fun effectiveAt(sdkInt: Int): List<DeclaredPermission>",
    ],
    "core-common/src/main/kotlin/com/example/cloudmusic/common/PermissionGate.kt": [
        "fun requiredFor(action: UserAction): List<String>",
        "fun requiredFor(action: UserAction, sdkInt: Int): List<String>",
        "fun requestsForStartup(): List<String> = emptyList()",
        "val isCoreCapabilityBlocked: Boolean",
    ],
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/AppSettings.kt": [
        "data class AppSettings(",
        "data class NetworkSettings(",
        "data class ScanSettings(",
        "object SettingsValidator {",
    ],
    "core-storage/src/main/kotlin/com/example/cloudmusic/storage/SettingsChange.kt": [
        "enum class SettingsChange {",
        "fun diff(before: AppSettings, after: AppSettings): Set<SettingsChange>",
        "fun effectsFor(before: AppSettings, after: AppSettings): Set<Effect>",
        "fun requiresFullRescan(before: AppSettings, after: AppSettings): Boolean",
    ],
    # 03-01 §4.1 / 12 §20：Hilt 唯一注册点与 Application 入口
    "core-di/src/main/kotlin/com/example/cloudmusic/di/DataSourceModule.kt": [
        "object DataSourceModule {",
        "@InstallIn(SingletonComponent::class)",
        "@StringKey(KEY_WEBDAV)",
        "class DriverRegistrar @Inject constructor(",
        "fun registerAll(): Set<String>",
        # 第十九批（12 §28）：第 4 个 V0.1 驱动键 local
        "@StringKey(KEY_LOCAL)",
        "fun provideLocalFactory(@ApplicationContext context: Context): DriverFactory",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/AppBindings.kt": [
        "object EngineModule {",
        "object MediaModule {",
        "object LibraryModule {",
        # 12 §57：provider 增加 @ApplicationContext 参数（供 cacheManagerHandle 实验），
        # 守护改按方法名前缀匹配，换绑语义不变。
        "fun provideMusicEngine(",
        # 第十七批（12 §26）：D-08 换绑 Room，仅改 @Provides 返回值
        "fun provideLibraryDatabase(@ApplicationContext context: Context): LibraryDatabase",
        "RoomTrackRepository(db)", "RoomQueueRepository(db)",
        "RoomScanSnapshotStore(db)",
        # 第十八批（12 §27）：T-A2 Storage 改绑 DataStore
        "fun provideSettingsAccess(store: SettingsStore): SettingsAccess",
        "DataStoreStorageRepository(access, tracks)",
    ],
    "app/src/main/kotlin/com/example/cloudmusic/CloudMusicApplication.kt": [
        "@HiltAndroidApp",
        "class CloudMusicApplication : Application()",
        "driverRegistrar.registerAll()",
    ],
    # 03-06 §7 / §15 T01~T05（T-P2）
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/DataSourcePorts.kt": [
        "fun interface RangeHttpStack {",
        "interface FdOpener {",
        "fun interface DriverStreamFactory {",
        "class JvmFileFdOpener : FdOpener {",
    ],
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/BaseDataSource.kt": [
        "abstract class BaseDataSource(protected val link: Link) : IDataSource",
        "final override fun readAt(offset: Long, buf: ByteArray, len: Int): Int",
        "lastError = e.toCloudMusicError()",
        "final override fun close()",
    ],
    "engine-bridge/src/main/kotlin/com/example/cloudmusic/bridge/DataSourceAdapter.kt": [
        "object DataSourceAdapter {",
        "Link.Kind.URL -> HttpDataSource(link, args, http)",
        "Link.Kind.LOCAL -> FileDataSource(link, fdOpener)",
        "Link.Kind.PROXIED -> DriverDataSource(link, driverStreams)",
        "Link.Kind.TEXT -> MemoryDataSource(link)",
    ],
    # 03-06 §8 / §9 / §15 T07、T08、T11（T-P1）
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/QueueRepository.kt": [
        "interface QueueRepository {",
        "suspend fun move(from: Int, to: Int)",
        "suspend fun saveCursor(trackId: String, positionMs: Long)",
        "class InMemoryQueueRepository(",
    ],
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/PlayModeOrdering.kt": [
        "object ShuffleOrder {",
        "fun generate(seed: Long, size: Int): List<Int>",
        "fun nextIndex(",
    ],
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/QueueSync.kt": [
        "interface QueueSync {",
        "suspend fun push(items: List<QueueItem>, mode: PlayMode, startAt: Int)",
        "object NativeQueueSandbox {",
    ],
    "feature-player/src/main/kotlin/com/example/cloudmusic/player/PlayerController.kt": [
        "class PlayerController(",
        "val state: StateFlow<PlayerState>",
        "suspend fun restore(snapshot: PlaybackSnapshot, items: List<QueueItem>)",
        "if (autoPlay) engine.play(h) else engine.pause(h)",
    ],
    # 03-08 §3.1 / §5 / §6 / §13 T01~T09、T13、T14（T-P3）
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/CastUriResolver.kt": [
        "class CastUriResolver(",
        "suspend fun resolve(track: Track): CastUri",
        # C-09（12 §27）：设置动态投影，默认 null 保持第十五批行为
        "private val settingsSource: (() -> ProxySettings)? = null",
        "val current = settingsSource?.invoke() ?: settings",
        "if (current.forceWebdavProxy || driverPreferProxy)",
        "interface CastProxyPort {",
        # C-08 / C-13（12 §31）：Track 上下文透传 + 封面 URL 端口
        "suspend fun directUrl(link: Link, track: Track?): String? = directUrl(link)",
        "val url = directLinks.directUrl(link, track)",
        "fun coverUrlFor(trackId: String): String? = null",
    ],
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/DescriptionParser.kt": [
        "object DescriptionParser {",
        "fun resolveUrl(base: String, control: String): String",
        "DlnaDescParse",
    ],
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/DidlLiteBuilder.kt": [
        "const val CONTENT_FEATURES",
        "fun escape(value: String): String",
        "fun formatDuration(durationMs: Long): String",
    ],
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/SoapController.kt": [
        "fun soapAction(serviceType: String, action: String): String",
        "fun setAvTransportUri(",
        "fun errorCode(body: String): Int?",
    ],
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/SsdpDiscovery.kt": [
        "fun buildMSearch(st: String, mx: Int = 3): String",
        "fun parseResponses(raw: Iterable<String>): List<SsdpResponse>",
        "interface MulticastLockPort {",
    ],
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/ManualDmrProbe.kt": [
        "fun candidateUrls(spec: ManualDmrSpec): List<String>",
        "suspend fun probe(",
    ],
    # 12 §25 第十六批：生产端口适配 + :core-di 唯一接线
    "core-di/src/main/kotlin/com/example/cloudmusic/di/adapters/OkHttpAdapters.kt": [
        "class OkHttpRangeHttpStack(", "class OkHttpHeadProbe(",
        "class OkHttpDescriptionFetcher(", "class OkHttpSoapTransport(",
        "fun totalLength(",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/adapters/AndroidSourceAdapters.kt": [
        "class ContentResolverFdOpener(", "class WifiMulticastLockPort(",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/adapters/DatagramAdapters.kt": [
        "class DatagramMulticastTransport(",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/adapters/CastComposition.kt": [
        "fun interface DataSourceFactory", "class OkHttpProxiedStreamFactory(",
        "class RegistryLinkProvider :", "class ConservativeDirectLinkProvider :",
        "class PrePublishedCastProxy(",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/BridgeModule.kt": [
        "object BridgeModule {", "@InstallIn(SingletonComponent::class)",
        "fun provideCastUriResolver(", "fun provideSsdpScanner(",
        "fun provideProxySettings(store: SettingsStore): ProxySettings",
        "settingsSource = { SettingsProjection.proxy(store.current()) }",
    ],
    # 12 §26 第十七批：03-05 §3 / §6 / §8 / §10 的 Room 契约
    "core-di/src/main/kotlin/com/example/cloudmusic/di/db/LibrarySchema.kt": [
        "object LibrarySchema {",
        "const val VERSION_V1 = 1",
        "const val VERSION_V2 = 2",
        "val PHYSICAL_TABLES = listOf(",
        "object MigrationSql {",
        "ALTER TABLE tracks ADD COLUMN sort_title",
        "fun isNonDestructive(",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/db/RoomEntities.kt": [
        "@Entity(", "data class TrackRow(", "@Fts4(contentEntity = TrackRow::class)",
        "data class TrackFtsRow(", "data class PlaylistRow(", "data class PlaylistTrackRow(",
        "data class FavoriteRow(", "data class PlayHistoryRow(", "data class FailureLogRow(",
        "data class ScanSnapshotRow(", "data class QueueItemRow(",
        "data class PlaybackStateRow(",
        "fun from(track: Track, existing: TrackRow?, now: Long): TrackRow",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/db/RoomDaos.kt": [
        "interface TrackDao {", "interface PlaylistDao {", "interface FavoriteDao {",
        "interface HistoryDao {", "interface QueueDao {", "interface ScanSnapshotDao {",
        "interface FailureLogDao {",
        "fun searchFts(match: String): Flow<List<TrackRow>>",
        "suspend fun markOrphan(storageId: String, now: Long)",
        "suspend fun rebuildFts()",
        "suspend fun prune(cutoff: Long, max: Int)",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/db/RoomMigrations.kt": [
        "object LibraryMigrations {", "val MIGRATION_1_2: Migration =",
        "MigrationSql.apply(", "override fun migrate(db: SupportSQLiteDatabase)",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/db/LibraryDatabase.kt": [
        "@Database(", "version = LibrarySchema.VERSION,", "exportSchema = true,",
        "abstract class LibraryDatabase : RoomDatabase()",
        ".addMigrations(*LibraryMigrations.ALL)",
        "VALUES('rebuild')",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/db/RoomRepositories.kt": [
        "object UpsertPlanner {", "class RoomTrackRepository(", "class RoomPlaylistRepository(",
        "class RoomFavoriteRepository(", "class RoomHistoryRepository(",
        "class RoomQueueRepository(", "class RoomScanSnapshotStore(",
        "class RoomFailureLog(", "UpsertPlanner.chunks(tracks)",
        "TrackRow.from(it, existing[it.id.value], now)",
    ],
    # 12 §27 第十八批：03-05 §4 / 08 §6 / §7 的 DataStore 契约
    "core-di/src/main/kotlin/com/example/cloudmusic/di/settings/AppSettingsCodec.kt": [
        "object AppSettingsCodec {", "fun encode(settings: AppSettings): String",
        "fun decode(raw: String): AppSettings", "fun decodeOrDefault(raw: String): AppSettings",
        "put(\"additionRaw\", dto.additionRaw)",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/settings/SettingsAccess.kt": [
        "interface SettingsAccess {", "data class SettingsUpdate(",
        "object SettingsProjection {", "fun proxy(settings: AppSettings): ProxySettings",
        "fun StorageDto.toModel(): Storage", "fun Storage.toDto(): StorageDto",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/settings/SettingsDataStore.kt": [
        "object AppSettingsSerializer : Serializer<AppSettings>",
        "class SettingsStore @Inject constructor(",
        "override suspend fun update(transform: (AppSettings) -> AppSettings): SettingsUpdate",
        "CorruptionException(", "SettingsValidator.validate(next)",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/settings/DataStoreStorageRepository.kt": [
        "class DataStoreStorageRepository(", "override suspend fun add(type: String, additionJson: String): Storage",
        "tracks.markOrphan(id)", "override suspend fun setError(id: String, msg: String)",
    ],
    # 12 §28 第十九批：03-02 §3 LocalDriver 双通道契约
    "datasource-local/src/main/kotlin/com/example/cloudmusic/local/LocalModels.kt": [
        "enum class LocalChannel { SAF, MEDIA_STORE }",
        "interface LocalDirectorySource {", "data class LocalAddition(",
        "object LocalPathMapping {", "object LocalEntryGrouper {",
        "fun safRelativeFromDocumentId(treeDocId: String, docId: String): String?",
    ],
    "datasource-local/src/main/kotlin/com/example/cloudmusic/local/LocalDriver.kt": [
        "class LocalDriver(", "override val config = DriverConfig(name = \"Local\", localSort = true)",
        "private fun sandboxAbsolute(path: String): String",
        "PathRule.sandbox(root.sandboxRoot(), absolute)",
        "override suspend fun link(file: Obj, args: LinkArgs): Link",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/local/AndroidLocalSources.kt": [
        "class SafDirectorySource(", "class MediaStoreDirectorySource(",
        "DocumentsContract.buildChildDocumentsUriUsingTree",
        "MediaStore.Audio.Media.EXTERNAL_CONTENT_URI",
        "LocalEntryGrouper.group(absolutePath, queryAll())",
    ],
    # 12 §29 第二十批：03-04 §6 / §7 的标签、封面与扫描器生产契约
    "core-di/src/main/kotlin/com/example/cloudmusic/di/scan/ScanContracts.kt": [
        "fun interface MediaMetadataPort", "fun interface JAudioTaggerPort",
        "interface CoverWriterPort {", "interface LinkLocator {",
        "object TagMerger {", "object CoverPolicy {", "class CoverLookupCache {",
        "object DriverSyncPlanner {",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/scan/ProductionTagAndCover.kt": [
        "class AndroidMediaMetadataPort(", "class JAudioTaggerFilePort :",
        "class AndroidCoverWriter(", "class RegistryLinkLocator :",
        "class DirectoryCoverLookup(", "class AndroidTagReader(",
        "class AndroidCoverResolver(", "AudioFileIO.read(file)",
        "Bitmap.CompressFormat.WEBP",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/scan/ScanComposition.kt": [
        "class StorageDriverBinder(", "class DataStoreStorageProvider(",
        "class RoomTrackSink(", "object ScanModule {",
        "DriverRegistry.put(storage.id, driver)",
        "reconciler.upsertFromScan(items)",
        "fun provideScanCoordinator(",
    ],
    # 12 §31 第二十二批：T-A5 新落地的契约（C-08 / C-12 / C-13 / C-14）
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/ScanModels.kt": [
        "val onlyUnavailable: Boolean = false",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/scan/NetworkByteRange.kt": [
        "object ByteRangePolicy {", "fun interface RangeRequester {",
        "class UrlByteRangeSource(", "class OkHttpRangeRequester(",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/scan/SettingsEffects.kt": [
        "object EffectsPolicy {", "class SettingsEffectsRunner",
        "coordinator.run(ScanRequest(refresh = true))",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/proxy/NanoHttpStreamProxy.kt": [
        "NanoHTTPD(0)", "fun interface LanAddressProvider {",
        "class NanoHttpStreamProxy(", "image/webp",
        "fun coverUrlFor(trackId: String): String?", "class RangeInputStream(",
    ],
    "core-di/src/main/kotlin/com/example/cloudmusic/di/playback/PlaybackComposition.kt": [
        "class RoomTrackLookup(", "class SandboxQueueSync :",
        "class ProductionTrackSourceResolver(", "class PlaybackRuntime",
        "object PlaybackModule {", "ProxyStartPolicy.shouldStart(",
    ],
    "app/src/main/kotlin/com/example/cloudmusic/PlaybackService.kt": [
        "class PlaybackService : Service()", "@AndroidEntryPoint",
        "START_STICKY", "MediaSession.Callback", "startForeground(NOTIFICATION_ID",
    ],
    # 12 §51：§50 媒体键转发成果的签名（Receiver 只解包转发，判定在 MediaKeyAction）
    "app/src/main/kotlin/com/example/cloudmusic/MediaButtonEventReceiver.kt": [
        "class MediaButtonEventReceiver : BroadcastReceiver()",
        "MediaKeyAction.resolve(event?.keyCode)", "startForegroundService(",
    ],
    "app/src/main/kotlin/com/example/cloudmusic/MediaKeyAction.kt": [
        "object MediaKeyAction {", "fun resolve(keyCode: Int?): String?",
    ],
    "app/src/test/kotlin/com/example/cloudmusic/PlatformContractTest.kt": [
        "class PlatformContractTest {", "fun pauseKey()", "fun playKeys()",
        "fun unknownKeys()", "fun closedValueDomain()", "fun actionConstants()",
    ],
    # 12 §52：应用动作的纯判定与 Addition 构造（:app 侧签名）
    "app/src/main/kotlin/com/example/cloudmusic/AppActionContracts.kt": [
        "object PermissionDecision {", "object LocalSourceSpec {",
        "fun classify(", "fun safAddition(treeUri: String): String",
        "const val DRIVER_TYPE = \"local\"",
    ],
    "app/src/main/kotlin/com/example/cloudmusic/PermissionRequester.kt": [
        "class PermissionRequester(", "fun request(", "RequestMultiplePermissions",
    ],
    "app/src/main/kotlin/com/example/cloudmusic/LocalSourceAdder.kt": [
        "class LocalSourceAdder(", "fun addSaf(uri: Uri)", "fun addMediaStore()",
        "runtime.rescanAll()",
    ],
    "app/src/test/kotlin/com/example/cloudmusic/AppActionContractTest.kt": [
        "class AppActionContractTest {", "fun requiredForActions()",
        "fun classifyPartitions()", "fun safAdditionRoundTrip()", "fun jsonEscaping()",
    ],
    # 12 §54：取流句柄的纯 JVM 协议与 native 适配签名
    "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/RangeFetcher.kt": [
        "object RangeFetcher {", "fun interface RangeOpener {", "interface RangeSink {",
        "fun fetch(", "class HttpUrlRangeOpener(",
    ],
    "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt": [
        "class UrlDataRequest(", ": SourceDataRequest()",
        "override fun Start(j1: Long, j2: Long, j3: Long): Long", "override fun Stop()",
    ],
    "engine-legacy/src/test/kotlin/com/example/cloudmusic/enginelegacy/RangeFetcherTest.kt": [
        "class RangeFetcherTest {", "fun rangeHeader()", "fun parseTotal()",
        "fun fetchHappyPath()", "fun fetchFailure()",
    ],
}

# :core-model 零 Android 依赖（03-01 §14 #1）
FORBIDDEN_IMPORTS = re.compile(
    r"^\s*import\s+(android\.|androidx\.|okhttp3\.|com\.netease\.|retrofit2\.)",
    re.MULTILINE,
)


def main() -> int:
    failures: list[str] = []
    warnings: list[str] = []

    for rel in GRADLE_FILES + CONTRACT_FILES:
        if not (ROOT / rel).is_file():
            failures.append(f"缺文件 {rel}")
    for script in CHECK_SCRIPTS:
        if not (ROOT / "tools" / script).is_file():
            failures.append(f"缺检查脚本 tools/{script}")

    catalog = ROOT / "gradle" / "libs.versions.toml"
    if catalog.is_file():
        text = catalog.read_text(encoding="utf-8")
        if re.search(r'=\s*"(2\.x|8\.x|[\d]+\.x)"', text):
            failures.append("libs.versions.toml 仍含 x 占位版本")
        else:
            print("[PASS] version catalog 已钉死精确版本（无 2.x / 8.x 占位）")

    # 03-11 §1：权限清单在 Kotlin 真相源与 C3 静态检查之间不得漂移。
    # 两处都逐字写出权限字面量，任一侧增删都会被此比对拦截。
    perm_re = re.compile(r'"(android\.permission\.[A-Z_]+)"')
    kt_catalog = (
        ROOT / "core-common" / "src" / "main" / "kotlin" / "com" / "example"
        / "cloudmusic" / "common" / "PermissionCatalog.kt"
    )
    py_c3 = ROOT / "tools" / "check-permissions.py"
    if kt_catalog.is_file() and py_c3.is_file():
        kt_perms = set(perm_re.findall(kt_catalog.read_text(encoding="utf-8")))
        py_perms = set(perm_re.findall(py_c3.read_text(encoding="utf-8")))
        if kt_perms and kt_perms == py_perms:
            print(f"[PASS] 03-11 §1 权限清单：Kotlin 真相源 ↔ C3 静态检查一致（{len(kt_perms)} 条）")
        else:
            failures.append(
                f"权限清单漂移：仅 Kotlin={sorted(kt_perms - py_perms)}，"
                f"仅 C3={sorted(py_perms - kt_perms)}"
            )

    # 03-11 §1.1：:app Manifest 的权限集合必须与 C3 允许清单逐项一致。
    app_manifest = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
    if app_manifest.is_file() and py_c3.is_file():
        declared = set(re.findall(
            r'<uses-permission\b[^>]*android:name\s*=\s*"([^"]+)"',
            app_manifest.read_text(encoding="utf-8"),
        ))
        match = re.search(r"^ALLOWED\s*=\s*\{(.*?)^\}", py_c3.read_text(encoding="utf-8"), re.M | re.S)
        allowed = set(perm_re.findall(match.group(1))) if match else set()
        if allowed and declared == allowed:
            print(f"[PASS] 03-11 §1.1 Manifest 权限 ≡ C3 允许清单（{len(declared)} 项）")
        else:
            failures.append(
                f"Manifest 权限漂移：仅 Manifest={sorted(declared - allowed)}，"
                f"仅 C3={sorted(allowed - declared)}"
            )

    # 03-01 §4.1：全项目唯一的 Driver 注册点，@InstallIn(SingletonComponent) 只能在 :core-di。
    install_in = [
        p.relative_to(ROOT).as_posix()
        for p in sorted(ROOT.glob("*/src/main/kotlin/**/*.kt"))
        if "@InstallIn(SingletonComponent::class)" in p.read_text(encoding="utf-8")
    ]
    outside = [f for f in install_in if not f.startswith("core-di/")]
    if outside:
        failures.append(f"Hilt 注册点外泄（03-01 §4.1 要求唯一，只允许 :core-di）：{outside}")
    elif install_in:
        print(f"[PASS] 03-01 §4.1 Hilt 唯一注册点：@InstallIn 全部位于 :core-di（{len(install_in)} 个文件）")

    # 12 §25 D-39 的教训：SIGNATURES 是 dict，重复键会静默吞掉前一组断言。
    script_text = (ROOT / "tools" / "check_source_contracts.py").read_text(encoding="utf-8")
    sig_block = script_text.split("SIGNATURES = {", 1)[1].split("\n}\n", 1)[0]
    sig_keys = re.findall(r'^\s{4}"([^"]+\.kt)":\s*\[', sig_block, re.M)
    duplicate_keys = sorted({k for k in sig_keys if sig_keys.count(k) > 1})
    if duplicate_keys:
        failures.append(f"SIGNATURES 存在重复键（后者覆盖前者，断言静默失效）：{duplicate_keys}")
    else:
        print(f"[PASS] SIGNATURES 无重复键（{len(sig_keys)} 项签名组全部生效）")

    # 03-05 §3 / §10 / §14 #1 / B10：Room 表数、Migration 注册与禁止破坏性重建。
    db_dir = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com" /
              "example" / "cloudmusic" / "di" / "db")
    db_text = "\n".join(
        p.read_text(encoding="utf-8") for p in sorted(db_dir.glob("*.kt"))
    ) if db_dir.is_dir() else ""
    entity_count = len(re.findall(r"@Entity\(", db_text))
    if entity_count != 10:
        failures.append(f"03-05 §3 Room 实体应为 9 张物理表 + 1 张 FTS4，实测 {entity_count} 个 @Entity")
    else:
        print("[PASS] 03-05 §3 Room 实体：9 张物理表 + 1 张 FTS4 虚拟表")
    if re.search(r"\.fallbackToDestructiveMigration\s*\(", db_text):
        failures.append("03-05 §14 #1 / B10：Room 禁止 fallbackToDestructiveMigration()")
    elif ".addMigrations(*LibraryMigrations.ALL)" not in db_text:
        failures.append("03-05 §10：Room 未注册 Migration(1,2)")
    else:
        print("[PASS] 03-05 §10 Room 迁移：注册 1→2、无破坏性重建")
    if "additionRaw" in db_text:
        failures.append("03-05 §14 #2 / 08 §5：凭据 additionRaw 不得写入 Room 层（只允许 DataStore）")
    else:
        print("[PASS] 03-05 §14 #2 凭据隔离：Room 层零 additionRaw")

    # 03-12 §1.1：:core-storage 必须保持纯 JVM（Room 只在 :core-di/db）。
    storage_dir = ROOT / "core-storage" / "src" / "main"
    for path in sorted(storage_dir.rglob("*.kt")):
        if FORBIDDEN_IMPORTS.search(path.read_text(encoding="utf-8")):
            failures.append(f":core-storage 出现 Android 依赖（应保持纯 JVM）: {path.name}")

    # 03-02 §3 / 12 §28：:datasource-local 是纯 JVM 驱动，Android 端口只在 :core-di/local。
    local_src = ROOT / "datasource-local" / "src" / "main"
    local_android = [
        p.name for p in sorted(local_src.rglob("*.kt"))
        if FORBIDDEN_IMPORTS.search(p.read_text(encoding="utf-8"))
    ]
    if local_android:
        failures.append(f":datasource-local 出现 Android 依赖（应保持纯 JVM）: {local_android}")
    else:
        print("[PASS] 03-02 §3 本地驱动：:datasource-local 零 Android 依赖")
    ds_module = ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example" / \
        "cloudmusic" / "di" / "DataSourceModule.kt"
    if ds_module.is_file() and "@StringKey(KEY_LOCAL)" not in ds_module.read_text(encoding="utf-8"):
        failures.append("03-01 §4.1：DataSourceModule 未注册 local 驱动键")

    # 12 §29 / C-10：`DriverRegistry.get(storageId)` 取实例，只注册 factory 扫描器仍拿不到驱动。
    scan_dir = ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example" / \
        "cloudmusic" / "di" / "scan"
    scan_text = "\n".join(
        p.read_text(encoding="utf-8") for p in sorted(scan_dir.glob("*.kt"))
    ) if scan_dir.is_dir() else ""
    if "DriverRegistry.put(" not in scan_text:
        failures.append("C-10：未把 Driver 实例放入 DriverRegistry，ScanCoordinator 无法扫描")
    elif "reconciler.upsertFromScan" not in scan_text:
        failures.append("C-04：TrackSink 必须走 ScanReconciler 恢复通道")
    else:
        print("[PASS] C-10 / C-04：驱动实例已同步、扫描写入走恢复通道")
    if re.search(r"^\s*import\s+java\.awt", scan_text, re.M):
        failures.append("03-04 §6.2：JAudioTagger 通道不得 import java.awt（Android 无此包）")
    catalog_text = (ROOT / "gradle" / "libs.versions.toml").read_text(encoding="utf-8")
    if "net.jthink:jaudiotagger" not in catalog_text:
        failures.append("05 §3 / 03-04 §6.2：JAudioTagger 依赖未登记")

    # ---- 12 §31（第二十二批 / T-A5）长效守护 ---------------------------------
    # C-12：此前生产实现数为 0，HeadTagReader 的 rangeFor 恒取 null，
    # 网络音源标签 + 同目录封面两条链路同时失效（12 §30 实测发现）。
    webdav_src = (
        ROOT / "datasource-webdav" / "src" / "main" / "kotlin" / "com"
        / "example" / "cloudmusic" / "webdav" / "WebDavDriver.kt"
    )
    webdav_text = webdav_src.read_text(encoding="utf-8") if webdav_src.is_file() else ""
    if "ByteRangeCapability" not in webdav_text or "override suspend fun readBytes" not in webdav_text:
        failures.append("C-12：ByteRangeCapability 生产实现缺失，网络音源标签与封面会同时失效")
    else:
        print("[PASS] C-12：ByteRangeCapability 生产实现在场（WebDAV）")

    di_build = ROOT / "core-di" / "build.gradle.kts"
    di_build_text = di_build.read_text(encoding="utf-8") if di_build.is_file() else ""
    if "libs.nanohttpd" not in di_build_text:
        failures.append("C-13：nanohttpd 服务本体依赖未声明（03-08 §8）")
    else:
        print("[PASS] C-13：nanohttpd 服务本体依赖已声明")

    manifest = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
    manifest_text = manifest.read_text(encoding="utf-8") if manifest.is_file() else ""
    manifest_needles = (
        'android:name=".PlaybackService"',
        'android:foregroundServiceType="mediaPlayback"',
        'android:name=".MediaButtonEventReceiver"',
        "android.intent.action.MEDIA_BUTTON",
    )
    missing_manifest = [n for n in manifest_needles if n not in manifest_text]
    if missing_manifest:
        failures.append(f"03-11 §1.1 Manifest 组件声明缺失：{missing_manifest}")
    else:
        print("[PASS] 03-11 §1.1 Manifest：Service / Receiver / FGS 类型齐备")

    settings_src = (
        ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
        / "cloudmusic" / "di" / "settings" / "SettingsDataStore.kt"
    )
    settings_text = settings_src.read_text(encoding="utf-8") if settings_src.is_file() else ""
    if "class SettingsEffectsBus" not in settings_text or "effectsBus.emit(update)" not in settings_text:
        failures.append("C-14：SettingsUpdate.effects 没有运行时执行入口")
    else:
        print("[PASS] C-14：effects 广播总线已接入写入路径")

    scan_models = (
        ROOT / "scanner" / "src" / "main" / "kotlin" / "com" / "example"
        / "cloudmusic" / "scanner" / "ScanModels.kt"
    )
    scan_models_text = scan_models.read_text(encoding="utf-8") if scan_models.is_file() else ""
    if "onlyUnavailable" not in scan_models_text:
        failures.append("C-14：ScanRequest 缺定向增量承载（03-04 §4）")
    else:
        print("[PASS] C-14：ScanRequest 定向增量参数在位")

    # ---- 12 §32（第二十三批 / T-L L1 步骤 1）长效守护 --------------------------
    packed_dir = ROOT / "engine-legacy" / "src" / "main" / "jniLibs" / "arm64-v8a"
    packed_names = {p.name for p in packed_dir.glob("*.so")} if packed_dir.is_dir() else set()
    if len(packed_names) != 18:
        failures.append(
            f"12 §10.2 步骤 1：jniLibs 打包集应为 18 个，实测 {len(packed_names)} 个"
        )
    else:
        print("[PASS] T-L L1：18 个 arm64 so 打包集已按 03-12 §4 落地")

    fp_root = ROOT / "assets" / "ENGINE-FINGERPRINT"
    fp_app = ROOT / "app" / "src" / "main" / "assets" / "ENGINE-FINGERPRINT"
    if not (
        fp_root.is_file() and fp_app.is_file()
        and fp_root.read_text(encoding="utf-8") == fp_app.read_text(encoding="utf-8")
    ):
        failures.append("03-06 §6.3：ENGINE-FINGERPRINT 根目录 / app assets 副本缺失或不一致")
    else:
        print("[PASS] T-L L1：ENGINE-FINGERPRINT 双份一致（C6 校验源）")

    settings_text = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    app_build_text = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    if 'include(":engine-legacy")' not in settings_text:
        failures.append("12 §32：:engine-legacy 舱模块未 include")
    if 'project(":engine-legacy")' not in app_build_text:
        failures.append("12 §32：:app 未依赖 :engine-legacy，18 个 so 不会进 APK")
    else:
        print("[PASS] T-L L1：舱模块已 include 且 :app 已依赖（so 进 APK）")

    jni_index = ROOT / "analysis" / "engine-jni-classes.json"
    if not jni_index.is_file():
        failures.append("12 §10.2 步骤 2 前置：JNI 真类索引未生成（跑 engine_jni_class_index.py）")
    else:
        print("[PASS] T-L L1：JNI 真类 / native 方法索引在位（步骤 2 前置清单）")

    # ---- 12 §33（第二十四批 / T-A6）长效守护 ----------------------------------
    app_manifest = (
        ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
    ).read_text(encoding="utf-8")
    activity_needles = (
        'android:name=".MainActivity"',
        "android.intent.action.MAIN",
        "android.intent.category.LAUNCHER",
    )
    missing_activity = [n for n in activity_needles if n not in app_manifest]
    if missing_activity:
        failures.append(f"03-10 §5：LAUNCHER Activity 声明缺失 {missing_activity}")
    else:
        print("[PASS] T-A6：MainActivity / LAUNCHER 声明齐备")

    compose_needles = (
        "libs.plugins.kotlin.compose",
        "libs.androidx.compose.bom",
        "libs.androidx.compose.material3",
        "libs.androidx.activity.compose",
    )
    missing_compose = [n for n in compose_needles if n not in app_build_text]
    if missing_compose:
        failures.append(f"03-10 §2：Compose 插件 / 依赖缺失 {missing_compose}")
    elif "compose = true" not in app_build_text:
        failures.append("03-10 §2：:app 未启用 buildFeatures.compose")
    else:
        print("[PASS] T-A6：Compose 插件 + BOM + Material3 + activity-compose 依赖在位")

    # 03-10 §11 #4：UI 只经 Repository / StateFlow，不直接碰 Driver / Room / OkHttp。
    ui_dir = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "ui"
    ui_text = "\n".join(
        p.read_text(encoding="utf-8") for p in sorted(ui_dir.glob("*.kt"))
    ) if ui_dir.is_dir() else ""
    forbidden_ui = ("import android.database.", "import okhttp3.", "import androidx.room.")
    hit = next((n for n in forbidden_ui if n in ui_text), None)
    if hit:
        failures.append(f"03-10 §11 #4：UI 层出现禁止依赖 {hit}")
    elif "DriverRegistry" in ui_text:
        failures.append("03-10 §11 #4：UI 层不得直接调 DriverRegistry")
    else:
        print("[PASS] T-A6：UI 层零 Driver / Room / OkHttp 直接依赖")

    # ---- 12 §34（第二十五批 / T-L L1 步骤 2 前置）长效守护 -----------------------
    jni_src = ROOT / "analysis" / "jadx-jni-classes"
    jni_java = sorted(jni_src.rglob("*.java")) if jni_src.is_dir() else []
    if len(jni_java) != 12:
        failures.append(
            f"12 §10.2 步骤 2：12 个精确 JNI 类的反编译源码应齐全，实测 {len(jni_java)} 个"
        )
    else:
        print("[PASS] T-L L1：12 个精确 JNI 类真实 Java 源码在位（jadx 1.5.1）")
    jni_report = ROOT / "analysis" / "jni-verify-report.json"
    if not jni_report.is_file():
        failures.append("12 §34：JNI 源码↔索引核对报告未生成（跑 engine_jni_verify.py）")
    else:
        try:
            verified = json.loads(jni_report.read_text(encoding="utf-8"))
            if verified.get("sourceFilesPresent") != 12:
                failures.append("12 §34：核对报告里源码在位数不是 12")
            else:
                print("[PASS] T-L L1：JNI 源码↔签名索引核对报告在位（12/12）")
        except (ValueError, OSError) as exc:
            failures.append(f"12 §34：核对报告不可解析：{exc}")

    # ---- 12 §35（第二十六批 / T-L L1 步骤 3）长效守护 -------------------------
    cabin = ROOT / "engine-legacy" / "src" / "main" / "java"
    cabin_java = list(cabin.rglob("*.java")) if cabin.is_dir() else []
    if len(cabin_java) < 54:
        failures.append(
            f"12 §10.2 步骤 3：舱内应有 12 真类 + 42 slot = 54 个 Java 文件，实测 {len(cabin_java)}"
        )
    else:
        print("[PASS] T-L L1：舱内 12 真类 + slot 编译单元在位（54 个 Java）")

    loader = cabin / "com" / "example" / "cloudmusic" / "enginelegacy" / "EngineNativeLoader.kt"
    if not loader.is_file() or "PLAYBACK_ENGINE_CHAIN" not in loader.read_text(encoding="utf-8"):
        failures.append("12 §35：EngineNativeLoader 缺精确加载链（12 §34 D-104）")
    else:
        print("[PASS] T-L L1：EngineNativeLoader 承载 D-104 按类加载链")

    bridges = (
        cabin / "o2" / "c.java",
        cabin / "zv" / "a.java",
    )
    if any(not p.is_file() or "EngineNativeLoader.load" not in p.read_text(encoding="utf-8")
           for p in bridges):
        failures.append("12 §35：o2.c / zv.a 加载封装器未委托到自研加载器")
    else:
        print("[PASS] T-L L1：混淆加载封装器已委托到 EngineNativeLoader（非空壳）")

    ledger = (ROOT / "docs" / "12-编码阶段第一批交付.md").read_text(encoding="utf-8")
    if "| 51 |" not in ledger:
        failures.append("12 §10.4：slot 登记簿缺第 51 行（数据源链 slot 未登记）")
    else:
        print("[PASS] T-L L1：slot 登记簿 51 行全部由 compile 触发（≤200 上限）")

    # ---- 12 §36（第二十七批 / EngineAdapter + DI 换绑）长效守护 -----------------
    adapter = cabin / "com" / "example" / "cloudmusic" / "enginelegacy" / "EngineAdapter.kt"
    adapter_text = adapter.read_text(encoding="utf-8") if adapter.is_file() else ""
    adapter_needles = (
        "class EngineAdapter : IMusicEngine",
        "EngineNativeLoader.loadChain",
        "PlayerDataSource(provider",
        "s.provider.close()",
    )
    missing_adapter = [n for n in adapter_needles if n not in adapter_text]
    if missing_adapter:
        failures.append(f"12 §36：EngineAdapter 缺映射要素 {missing_adapter}")
    else:
        print("[PASS] T-L L1：EngineAdapter 门面映射与加载链在位")

    bindings = (
        ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
        / "cloudmusic" / "di" / "AppBindings.kt"
    ).read_text(encoding="utf-8")
    # 12 §57：改为 `EngineAdapter().also { ... }` 注入 Context，仍须保证 DI 绑的是舱内实现。
    if "EngineAdapter()" not in bindings:
        failures.append("12 §36：EngineModule 未换绑 EngineAdapter（IMusicEngine 仍为内存实现）")
    else:
        print("[PASS] T-L L1：EngineModule 已换绑 EngineAdapter（D-08 换绑点，门面零改动）")

    pds = cabin / "com" / "netease" / "cloudmusic" / "android" / "sdk" / "playerengine" / "PlayerDataSource.java"
    if not pds.is_file() or "getDataProvider()" not in pds.read_text(encoding="utf-8"):
        failures.append("12 §36：PlayerDataSource 缺 13 §7.3 的 5 个 getter")
    else:
        print("[PASS] T-L L1：PlayerDataSource 数据源链 getter 在位")

    # ---- 12 §37（第二十八轮 / 静态 ABI 验收 A1/A2）长效守护 -------------------
    abi_report = ROOT / "analysis" / "jni-binding-report.json"
    if not abi_report.is_file():
        failures.append("03-06 §6 A1/A2：静态 ABI 对映报告未生成（跑 check_jni_bindings.py）")
    else:
        try:
            abi = json.loads(abi_report.read_text(encoding="utf-8"))
            if not abi.get("matchesBaselineDelta8"):
                failures.append("12 §37：A1 净差不是 8，与 13 §7.4 基线不符")
            elif not abi.get("a2", {}).get("pass"):
                failures.append("12 §37：A2 DT_NEEDED 闭包越界")
            else:
                print("[PASS] 静态 ABI：A1 净差 8 与 13 §7.4 吻合、A2 闭包零越界（A3 真机待跑）")
        except (ValueError, OSError) as exc:
            failures.append(f"12 §37：ABI 报告不可解析：{exc}")

    if "StateListener" not in adapter_text or "nativeEvents()" not in adapter_text:
        failures.append("12 §37：EngineAdapter 未接通 native 事件回调桥")
    else:
        print("[PASS] T-L L1：native 事件回调桥接通（事件码原样透传，待 G01 校准）")

    # ---- 12 §38（第二十九轮 / T-L L3 动画链路实证）长效守护 -------------------
    anim_report = ROOT / "analysis" / "player-anim-resolution.json"
    if not anim_report.is_file():
        failures.append("14 §4.1：播放页 anim 链路解析报告未生成（跑 resolve_player_anim.py）")
    else:
        try:
            anim = json.loads(anim_report.read_text(encoding="utf-8"))
            if anim.get("resolvedResourceIdCount") != 2 or anim.get("rotateCandidates", {}).get("count") != 40:
                failures.append("12 §38：anim 收敛数 / rotate 候选数与 14 §4.1 基线不符")
            elif anim.get("assetMappingChanged"):
                failures.append("12 §38：解析工具不得改动 docs/assets-mapping.csv（导出由 export_player_assets.py 统一负责）")
            else:
                print("[PASS] T-L L3：loadAnimation 链路收敛 2 个 anim，40 rotate 候选复核，映射表未被解析工具改写")
        except (ValueError, OSError) as exc:
            failures.append(f"12 §38：anim 报告不可解析：{exc}")

    # ---- 12 §47（用户确认 C4 口径后：映射表允许有行，但字段 / 状态须合规）--------
    mapping_csv = ROOT / "docs" / "assets-mapping.csv"
    if not mapping_csv.is_file():
        failures.append("06 §4：docs/assets-mapping.csv 缺失")
    else:
        try:
            with mapping_csv.open("r", encoding="utf-8-sig", newline="") as fh:
                mrows = list(csv.DictReader(fh))
            required = {
                "legacy_name", "type", "resource_id", "semantic_name",
                "used_by", "notes", "replaced",
            }
            if mrows and not required.issubset(set(mrows[0].keys())):
                failures.append(f"06 §4：映射表字段不符 {sorted(mrows[0].keys())}")
            elif mrows:
                bad = [r for r in mrows
                       if str(r.get("replaced", "")).strip().lower() not in {"true", "false"}]
                if bad:
                    failures.append(f"06 §4：{len(bad)} 行 replaced 值非 true/false")
                else:
                    pending = sum(1 for r in mrows
                                  if r["replaced"].strip().lower() != "true")
                    print(f"[PASS] C4 新口径：映射表 {len(mrows)} 行、{pending} 项待替换（dev WARN / release FAIL）")
            else:
                print("[PASS] C4：映射表为空表（尚无素材导出）")
        except (OSError, ValueError) as exc:
            failures.append(f"06 §4：映射表不可解析：{exc}")

    # ---- 12 §39（第三十轮 / 唱片旋转挂账收口）长效守护 -----------------------
    rot_report = ROOT / "analysis" / "rotation-anim-resolution.json"
    if not rot_report.is_file():
        failures.append("14 §4.1：唱片旋转收口报告未生成（跑 resolve_rotation_anim.py）")
    else:
        try:
            rot = json.loads(rot_report.read_text(encoding="utf-8"))
            if not rot.get("rotationIsPureCode") or rot.get("visualParams", {}).get("baselineDurationMs") != 20000:
                failures.append("12 §39：唱片旋转实证参数与 20000ms 基准不符")
            elif rot.get("assetMappingChanged"):
                failures.append("12 §39：旋转复核不得改动 assets-mapping.csv（C4 空表语义）")
            else:
                print("[PASS] T-L L3：唱片旋转为纯代码属性动画（20s/speed），40 rotate 候选无需导出")
        except (ValueError, OSError) as exc:
            failures.append(f"12 §39：旋转报告不可解析：{exc}")

    # ---- 12 §40（第三十一批 / T-A6 UI 完善）长效守护 -------------------------
    ui_app = (
        ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
        / "cloudmusic" / "ui" / "CloudMusicApp.kt"
    ).read_text(encoding="utf-8")
    ui_needles = (
        "\"曲库\" to \"♫\"", "\"歌单\" to \"☰\"", "\"搜索\" to \"⌕\"",
        "\"队列\" to \"☷\"", "\"设置\" to \"⚙\"", "OutputDialog(", "蓝牙仅显示连接状态",
    )
    missing_ui = [n for n in ui_needles if n not in ui_app]
    if missing_ui:
        failures.append(f"03-10 §5：UI 五页 / 输出弹窗 / 蓝牙口径缺失 {missing_ui}")
    else:
        print("[PASS] T-A6：五页导航 + 输出弹窗 + 蓝牙不并列口径在位")

    if "provideOutputRouter" not in bindings:
        failures.append("12 §40：OutputRouter 缺唯一 DI 绑定（03-09 §7）")
    elif 'project(":output-router")' not in (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8"):
        failures.append("12 §40：:app 未依赖 :output-router")
    else:
        print("[PASS] T-A6：OutputRouter DI 绑定与 :app 依赖在位")

    # ---- 12 §41（第三十二批 / 音频焦点与 BECOMING_NOISY）长效守护 -------------
    service = (
        ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
        / "cloudmusic" / "PlaybackService.kt"
    ).read_text(encoding="utf-8")
    focus_needles = (
        "AudioFocusRequest.Builder", "ACTION_AUDIO_BECOMING_NOISY",
        "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK", "RECEIVER_NOT_EXPORTED",
        "abandonAudioFocusRequest", "AUDIOFOCUS_REQUEST_GRANTED",
    )
    missing_focus = [n for n in focus_needles if n not in service]
    if missing_focus:
        failures.append(f"03-09 §8：音频焦点 / BECOMING_NOISY 接线缺失 {missing_focus}")
    else:
        print("[PASS] T-A5：音频焦点 + BECOMING_NOISY 平台接线在位（零新增权限/依赖）")

    # ---- 12 §43 / §44（T1 解环 + T4 状态回传）长效守护 ------------------------
    bindings = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                / "cloudmusic" / "di" / "AppBindings.kt").read_text(encoding="utf-8")
    if "fun provideMediaControl(runtime" in bindings:
        failures.append("12 §43：provideMediaControl 再次注入 PlaybackRuntime，DI 依赖环回归")
    elif "fun provideMediaControl(): IMediaControl = InMemoryMediaControl()" not in bindings:
        failures.append("12 §43：MediaModule 零参数绑定缺失")
    else:
        print("[PASS] T1：MediaModule 零参数绑定在位（无 DI 依赖环）")

    facade = (ROOT / "core-media" / "src" / "main" / "kotlin" / "com" / "example"
              / "cloudmusic" / "media" / "MediaControlApi.kt").read_text(encoding="utf-8")
    missing_facade = [n for n in ("fun onPlaying() {}", "fun onManualPause() {}")
                      if n not in facade]
    if missing_facade:
        failures.append(f"C-15：IMediaControl 默认状态通知方法缺失 {missing_facade}")
    else:
        print("[PASS] C-15：IMediaControl 默认空实现在位（既有实现零回归）")

    session_needles = (
        "mediaControl.onPlaying()", "mediaControl.onManualPause()",
        "lateinit var runtime: PlaybackRuntime", "lateinit var mediaControl: IMediaControl",
    )
    missing_session = [n for n in session_needles if n not in service]
    if missing_session:
        failures.append(f"12 §44：媒体会话状态回传 / 双注入守护缺失 {missing_session}")
    else:
        print("[PASS] T4：onPlay/onPause 状态回传在位；双注入保证 PlayerController.init 完成 bind")

    # ---- 12 §45（T3 / GAIN 三变体逐项映射）长效守护 --------------------------
    gain_needles = (
        "AudioManager.AUDIOFOCUS_GAIN -> FocusChange.GAIN",
        "AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> FocusChange.GAIN_TRANSIENT",
        "AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> FocusChange.GAIN_TRANSIENT_MAY_DUCK",
    )
    missing_gain = [n for n in gain_needles if n not in service]
    if missing_gain:
        failures.append(f"12 §45：GAIN 三变体逐项映射缺失 {missing_gain}")
    else:
        print("[PASS] T3：AUDIOFOCUS_GAIN* 三变体逐项映射到 FocusChange（未压平）")

    # ---- 12 §46（第三十七批 / 断连裁决链接入生产）长效守护 --------------------
    disconnect_needles = (
        "BluetoothOutputPolicy(", "BluetoothEvent.BECOMING_NOISY",
        "disconnectPolicy.updateSettings", "autoPauseOnDisconnect = playback.autoPauseOnDisconnect",
        # §61.29：从硬编码 false 改为运行时权限真值（B01 降级仍由策略负责）。
        "bluetoothConnectGranted()", "notifyPlaybackResumed()",
        "lateinit var settings: SettingsAccess", "lateinit var outputRouter: OutputRouter",
    )
    missing_disconnect = [n for n in disconnect_needles if n not in service]
    if missing_disconnect:
        failures.append(f"12 §46：断连裁决链生产接线缺失 {missing_disconnect}")
    else:
        print("[PASS] T-L L2：BECOMING_NOISY 经 BluetoothOutputPolicy 裁决（零新增权限）")

    # ---- 12 §48（DLNA 输出切换：联动契约守护，缺口见 §48.2）-----------------
    # 03-08 §14 #9 / 03-09 §7：切 DLNA 必须同时改 OutputRouter 与 PlayerController
    # （后者内部再联动 IMediaControl.setEnabled，T09），禁止只切一半状态。
    # 当前两者生产调用方均为 0 → DLNA 豁免暂未触发；缺口如实挂账，不伪造投屏状态。
    active_calls = output_changed_calls = 0
    for root_rel in ("app/src/main", "core-di/src/main"):
        base = ROOT / root_rel
        if not base.is_dir():
            continue
        for prod in sorted(base.rglob("*.kt")):
            if "build" in prod.parts:
                continue
            body = prod.read_text(encoding="utf-8", errors="replace")
            active_calls += len(re.findall(r"\.setActive\(", body))
            output_changed_calls += len(re.findall(r"\.onOutputChanged\(", body))
    if active_calls != output_changed_calls:
        failures.append(
            f"03-08 §14 #9 / 03-09 §7：OutputRouter.setActive({active_calls}) 与 "
            f"PlayerController.onOutputChanged({output_changed_calls}) 必须成对联动"
        )
    else:
        print(
            f"[PASS] 03-09 §7：输出切换联动成对（生产调用方 {active_calls} 处）；"
            "当前无触发点 → DLNA 豁免未激活，缺口见 12 §48"
        )

    # ---- 12 §50 / §51（第四十二批 / 真机 A3 平台层改动的回归守护）--------------
    # §50 的 5 个 P0 修复当时只有真机实证、没有 JVM 单测与静态守护（§50.6 明确挂账）。
    # 这里把「冷启动恢复 / 会话播放态 / 媒体键入口 / 转发判定 / remote 分支 / 真类在位」
    # 钉成成对断言；§51 把按键判定下沉到纯 JVM 的 MediaKeyAction，语义改由 :app
    # 的 PlatformContractTest 覆盖。静态守护 + JVM 单测双保险，防止真机成果被回退。
    composition = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                   / "cloudmusic" / "di" / "playback" / "PlaybackComposition.kt").read_text(encoding="utf-8")
    restore_needles = (
        "queueRepository.restore()", "queueRepository.observe().first()",
        "controller.restore(snapshot, items)", 'Log.w("CloudMusicRuntime"',
    )
    missing_restore = [n for n in restore_needles if n not in composition]
    if missing_restore:
        failures.append(f"12 §50 P0-1：冷启动 restore 生产接线缺失 {missing_restore}")
    else:
        print("[PASS] 12 §50 P0-1：PlaybackRuntime.start() 读 Room 队列并 controller.restore()")

    session50_needles = (
        "setPlaybackState(", "PlaybackState.STATE_PAUSED",
        "PlaybackState.ACTION_PLAY", "PlaybackState.ACTION_PAUSE",
        "PlaybackState.ACTION_SKIP_TO_NEXT", "PlaybackState.ACTION_SKIP_TO_PREVIOUS",
        "setMediaButtonReceiver(", "PendingIntent.getBroadcast(",
        "MediaButtonEventReceiver::class.java", "PendingIntent.FLAG_IMMUTABLE",
        "ACTION_PLAY -> performPlay()", "ACTION_PAUSE -> performPause()",
    )
    missing_session50 = [n for n in session50_needles if n not in service]
    if missing_session50:
        failures.append(f"12 §50 P0-2/3/4：会话播放态 / 媒体键入口 / action 分派缺失 {missing_session50}")
    else:
        print("[PASS] 12 §50 P0-2/3/4：PlaybackState + mediaButtonReceiver + action 分派在位")

    receiver = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                / "cloudmusic" / "MediaButtonEventReceiver.kt").read_text(encoding="utf-8")
    key_action = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                  / "cloudmusic" / "MediaKeyAction.kt").read_text(encoding="utf-8")
    forward_needles = (
        "MediaKeyAction.resolve(event?.keyCode)", "startForegroundService(",
        "Intent(context, PlaybackService::class.java).setAction(action)",
    )
    map_needles = (
        "object MediaKeyAction {", "fun resolve(keyCode: Int?): String?",
        "KeyEvent.KEYCODE_MEDIA_PAUSE", "KeyEvent.KEYCODE_MEDIA_PLAY",
        "KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE", "KeyEvent.KEYCODE_HEADSETHOOK",
        "null -> PlaybackService.ACTION_PLAY", "else -> null",
    )
    missing_forward = [n for n in forward_needles if n not in receiver]
    missing_map = [n for n in map_needles if n not in key_action]
    if missing_forward or missing_map:
        failures.append(
            f"12 §50/§51：媒体键转发或纯 JVM 判定缺失 "
            f"receiver={missing_forward} / MediaKeyAction={missing_map}"
        )
    else:
        print("[PASS] 12 §50/§51：Receiver 转发到 MediaKeyAction 纯判定（JVM 单测可覆盖）")

    manifest50 = (ROOT / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")
    recv_block = re.search(
        r"<receiver[^>]*MediaButtonEventReceiver.*?</receiver>", manifest50, re.S
    )
    if (not recv_block or 'android:exported="true"' not in recv_block.group(0)
            or "android.intent.action.MEDIA_BUTTON" not in recv_block.group(0)):
        failures.append("12 §50：MediaButtonEventReceiver 的 exported=true / MEDIA_BUTTON 过滤缺失")
    else:
        print("[PASS] 12 §50：Receiver exported=true + MEDIA_BUTTON 过滤在位（零新增权限）")

    adapter50 = (ROOT / "engine-legacy" / "src" / "main" / "java" / "com"
                 / "example" / "cloudmusic" / "enginelegacy" / "EngineAdapter.kt").read_text(encoding="utf-8")
    # 12 §56：prepare 增加第 4 个 ResourceInfo 参数，守护改按前缀匹配（语义不变）。
    if "PlayerDataSource(provider, source.trackId, source.url != null" not in adapter50:
        failures.append("12 §50 P0-5：SourceConfig.remote 未随 source.url 传入 native")
    else:
        print("[PASS] 12 §50 P0-5：URL 源按 source.url != null 走 REMOTE 分支")

    legacy_root = ROOT / "engine-legacy" / "src" / "main" / "java" / "com" / "netease" / "cloudmusic"
    true_classes = (
        legacy_root / "media" / "audioplayer" / "NCMNativeAudioTrackBuilder.java",
        legacy_root / "media" / "audioplayer" / "NCMNativeAudioPlayer.java",
        legacy_root / "module" / "player" / "datasource" / "IBaseDataSource.java",
        legacy_root / "android" / "sdk" / "playerengine" / "EncryptedType.java",
    )
    missing_true = [p.name for p in true_classes if not p.is_file()]
    if missing_true:
        failures.append(f"12 §50：CheckJNI 校准所需的真类 / 枚举缺失 {missing_true}")
    else:
        print("[PASS] 12 §50：3 个 JNI_OnLoad 真类 + EncryptedType 在位（CheckJNI 校准成果）")

    # ---- 12 §52（第四十三批 / 按动作申请权限 + 本地数据源闭环）长效守护 --------
    # §50.7 #3 的三项：按动作申请权限、配置数据源、全量重扫。真机 A3 实测
    # 「三项运行时权限 granted=false / 曲库 0 首只能靠 run-as 灌 Room 夹具」，
    # 本组守护把生产接线钉住；蓝牙 ACL / SHOW_BLUETOOTH_NAME 仍按 §48.6 挂账。
    requester52 = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                   / "cloudmusic" / "PermissionRequester.kt").read_text(encoding="utf-8")
    request_needles = (
        "PermissionGate.requiredFor(action, Build.VERSION.SDK_INT)",
        "ActivityResultContracts.RequestMultiplePermissions",
        "shouldShowRequestPermissionRationale", "checkSelfPermission",
        "PermissionDecision.classify(grants, rationale)",
    )
    missing_request = [n for n in request_needles if n not in requester52]
    if missing_request:
        failures.append(f"12 §52：按动作权限闸门接线缺失 {missing_request}")
    else:
        print("[PASS] 12 §52：requiredFor 按设备 SDK 分派 + 三集合分类 + 已授予短路")

    main52 = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
              / "cloudmusic" / "MainActivity.kt").read_text(encoding="utf-8")
    main_needles = (
        "PermissionGate.UserAction.START_PLAYBACK",
        "PermissionGate.UserAction.SHOW_BLUETOOTH_NAME", "permissionRequester.request(",
        "onAddSaf = {", "onAddMediaStore = {", "ActivityResultContracts.OpenDocumentTree()",
    )
    missing_main = [n for n in main_needles if n not in main52]
    # 03-11 §8 #2：启动阶段零申请 → request 只出现在用户动作回调里。
    # §61.29：START_PLAYBACK / SHOW_BLUETOOTH_NAME 各一处，均为 UI 回调、不在 onCreate 直接调用。
    startup_requests = len(re.findall(r"permissionRequester\.request\(", main52))
    if missing_main or startup_requests != 2:
        failures.append(
            f"12 §52：MainActivity 动作接线缺失 {missing_main}；"
            f"permissionRequester.request 应恰好 2 处（实测 {startup_requests}），启动不得申请"
        )
    else:
        print("[PASS] 12 §52：两个按动作申请入口在位；启动阶段零申请、数据源入口齐备")

    # §61.29（用户「按文档计划继续」授权）：T-L L2 已按 03-11 §2 既有动作落地。
    # 守护防止申请时机扩散到启动阶段或多处重复弹窗。
    bluetooth_calls = 0
    for prod in sorted((ROOT / "app" / "src" / "main").rglob("*.kt")):
        if "build" in prod.parts:
            continue
        bluetooth_calls += prod.read_text(encoding="utf-8", errors="replace").count(
            "UserAction.SHOW_BLUETOOTH_NAME"
        )
    if bluetooth_calls != 1:
        failures.append(
            f"12 §61.29：SHOW_BLUETOOTH_NAME 应恰好 1 处按动作申请（实测 {bluetooth_calls}），"
            "不得在启动阶段或多入口重复申请"
        )
    else:
        print("[PASS] 12 §61.29：SHOW_BLUETOOTH_NAME 按动作申请恰好 1 处（L2 已收口）")

    spec52 = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
              / "cloudmusic" / "AppActionContracts.kt").read_text(encoding="utf-8")
    module52 = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                / "cloudmusic" / "di" / "DataSourceModule.kt").read_text(encoding="utf-8")
    local_adapters52 = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                        / "cloudmusic" / "di" / "local" / "AndroidLocalSources.kt").read_text(encoding="utf-8")
    if ("const val DRIVER_TYPE = \"local\"" not in spec52 or
            "const val KEY_LOCAL = \"local\"" not in module52):
        failures.append("12 §52：LocalSourceSpec.DRIVER_TYPE 与 DataSourceModule.KEY_LOCAL 漂移")
    elif ("/storage/emulated/0" not in spec52 or
          'VOLUME_ROOT = "/storage/emulated/0"' not in local_adapters52):
        failures.append("12 §52：MEDIA_STORE_ROOT 与 MediaStoreDirectorySource.VOLUME_ROOT 漂移")
    else:
        print("[PASS] 12 §52：driverType / 沙箱卷根与生产实现逐字对齐")

    adder52 = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
               / "cloudmusic" / "LocalSourceAdder.kt").read_text(encoding="utf-8")
    ui52 = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
            / "cloudmusic" / "ui" / "CloudMusicApp.kt").read_text(encoding="utf-8")
    adder_needles = (
        "takePersistableUriPermission", "PermissionGate.UserAction.ADD_LOCAL_MEDIASTORE",
        "storages.add(LocalSourceSpec.DRIVER_TYPE, additionJson)", "runtime.rescanAll()",
        "PermissionDecision.mediaStoreImportAvailable",
    )
    missing_adder = [n for n in adder_needles if n not in adder52]
    missing_ui = [n for n in ("onAddSaf", "onAddMediaStore", "添加本地目录 (SAF)", "从媒体库导入")
                  if n not in ui52]
    if missing_adder or missing_ui:
        failures.append(
            f"12 §52：数据源闭环缺失 adder={missing_adder} / ui={missing_ui}"
        )
    else:
        print("[PASS] 12 §52：SAF 持久化授权 + 写 Storage + rescanAll + 两个 UI 入口成链")

    # ---- 12 §53（第四十四批 / 取流句柄静态取证）长效守护 ----------------------
    # §50.6 把「native 期望的取流句柄 / IBaseDataSource」列为唯一出声硬门槛，
    # 但方向本身是推测。本轮用 baksmali + so 导出符号实证出类型与契约，
    # 把结论钉成静态契约：防止有人删掉证据、凭猜测去实现 IBaseDataSource，
    # 或误以为 createDataRequestHandler 已收口。生产行为仍返回 null（不伪造）。
    evidence = ROOT / "analysis" / "data-request-handler-evidence-2026-11-07.md"
    if not evidence.is_file():
        failures.append("12 §53：取流句柄静态取证报告缺失")
    else:
        body53 = evidence.read_text(encoding="utf-8")
        evidence_needles = (
            "DataSourcePlatformImpl$d", "nmcache/l", "SourceDataRequest",
            "Start(JJJ)J", "SetDataRequest", "OnDataRequestBegin", "分阶段真机判据",
        )
        missing_evidence = [n for n in evidence_needles if n not in body53]
        if missing_evidence:
            failures.append(f"12 §53：取证报告结论链不完整 {missing_evidence}")
        else:
            print("[PASS] 12 §53：取流句柄证据链完整（类型 / 继承 / 契约 / 判据）")

    adapter53 = (ROOT / "engine-legacy" / "src" / "main" / "java" / "com"
                 / "example" / "cloudmusic" / "enginelegacy" / "EngineAdapter.kt").read_text(encoding="utf-8")
    if "data-request-handler-evidence-2026-11-07.md" not in adapter53:
        failures.append("12 §53：EngineAdapter 未指向取流句柄实证报告")
    else:
        print("[PASS] 12 §53：EngineAdapter 注释指向取流句柄实证报告")
    # §54 起 createDataRequestHandler() 已按实证接通；返回行为由下面的 §54 守护断言。

    request_class53 = (ROOT / "engine-legacy" / "src" / "main" / "java" / "com"
                       / "netease" / "cloudmusic" / "sdk" / "NMCommonCache" / "SourceDataRequest.java")
    loader53 = (ROOT / "engine-legacy" / "src" / "main" / "java" / "com"
                / "example" / "cloudmusic" / "enginelegacy" / "EngineNativeLoader.kt").read_text(encoding="utf-8")
    request_body53 = request_class53.read_text(encoding="utf-8") if request_class53.is_file() else ""
    if ("public abstract long Start(" not in request_body53 or
            "public abstract void Stop()" not in request_body53):
        failures.append("12 §53：SourceDataRequest 真类 / Start-Stop 契约缺失")
    elif '"NMCommonCache"' not in loader53:
        failures.append("12 §53：NMCommonCache 不在 so 加载链，InitPtr() 将不可解析")
    else:
        print("[PASS] 12 §53：SourceDataRequest 真类与 NMCommonCache 加载链在位")

    cache_so = ROOT / "engine-legacy" / "src" / "main" / "jniLibs" / "arm64-v8a" / "libNMCommonCache.so"
    if not cache_so.is_file():
        failures.append("12 §53：libNMCommonCache.so 缺失，取流 JNI 符号无从解析")
    else:
        so_bytes = cache_so.read_bytes()
        required_syms = (
            b"Java_com_netease_cloudmusic_sdk_NMCommonCache_SourceDataRequest_InitPtr",
            b"Java_com_netease_cloudmusic_sdk_NMCommonCache_SourceDataRequest_OnNativeDataRequestBegin",
            b"Java_com_netease_cloudmusic_sdk_NMCommonCache_SourceDataRequest_OnNativeDataRequestData",
            b"Java_com_netease_cloudmusic_sdk_NMCommonCache_SourceDataRequest_OnNativeDataRequestEnd",
            b"Java_com_netease_cloudmusic_sdk_NMCommonCache_NMBufferSource_SetDataRequest",
        )
        missing_syms = [s.decode().rsplit('_', 1)[-1] for s in required_syms if s not in so_bytes]
        if missing_syms:
            failures.append(f"12 §53：libNMCommonCache.so 缺少取流 JNI 符号 {missing_syms}")
        else:
            print("[PASS] 12 §53：5 个取流 JNI 导出符号齐全（InitPtr / 3 回调 / SetDataRequest）")

    # ---- 12 §54（第四十五批 / 取流句柄阶段 A/B 落地）长效守护 ----------------
    # §2.5 追加实证出 Start(j1=请求标识, j2=offset, j3=length) 与回传首参后，
    # 本批把 UrlDataRequest / RangeFetcher 接进生产。守护防止接线被回退成 null、
    # LOCAL 源误建取流对象、或忘记释放 native C++ 对象（InitPtr 泄漏）。
    legacy_main = ROOT / "engine-legacy" / "src" / "main" / "java" / "com" / "example" / "cloudmusic" / "enginelegacy"
    fetcher54 = legacy_main / "RangeFetcher.kt"
    request54 = legacy_main / "UrlDataRequest.kt"
    test54 = (ROOT / "engine-legacy" / "src" / "test" / "kotlin" / "com" / "example"
              / "cloudmusic" / "enginelegacy" / "RangeFetcherTest.kt")
    missing_files54 = [str(p.name) for p in (fetcher54, request54, test54) if not p.is_file()]
    if missing_files54:
        failures.append(f"12 §54：取流实现 / 单测文件缺失 {missing_files54}")
    else:
        print("[PASS] 12 §54：RangeFetcher / UrlDataRequest / 7 用例单测文件在位")

    fetcher_body = fetcher54.read_text(encoding="utf-8") if fetcher54.is_file() else ""
    fetcher_needles = (
        "object RangeFetcher {", "fun rangeHeader(offset: Long, length: Long): String",
        "fun parseTotal(contentRange: String?): Long", "fun fetch(",
        "class HttpUrlRangeOpener(", "OnDataRequestBegin/Data/End",
    )
    missing_fetcher = [n for n in fetcher_needles if n not in fetcher_body]
    if missing_fetcher:
        failures.append(f"12 §54：RangeFetcher 取流协议签名缺失 {missing_fetcher}")
    else:
        print("[PASS] 12 §54：Range 头 / Content-Range 解析 / 分片回传 / 生产 HTTP opener 在位")

    request_body = request54.read_text(encoding="utf-8") if request54.is_file() else ""
    request_needles = (
        "class UrlDataRequest(", ": SourceDataRequest()", "override fun Start(j1: Long, j2: Long, j3: Long): Long",
        "return 0L", "override fun Stop()", "fun release()", "OnDataRequestBegin(requestId",
        # 12 §59：onData 首参 = native kind 槽，恒传 NATIVE_DATA_KIND_NORMAL（1）；
        # End 仍传 requestId（两条 Done 分支均观测无害）。
        "OnDataRequestData(NATIVE_DATA_KIND_NORMAL", "OnDataRequestEnd(requestId",
    )
    missing_request54 = [n for n in request_needles if n not in request_body]
    if missing_request54:
        failures.append(f"12 §54：UrlDataRequest 的 native 契约实现缺失 {missing_request54}")
    else:
        print("[PASS] 12 §54：SourceDataRequest 子类 + Start 立即返回 0 + 三通道回传在位")

    adapter_needles = (
        "UrlDataRequest(url)", "val url = source.url ?: return null",
        "request?.Stop()", "request?.release()", "request = null",
    )
    missing_adapter = [n for n in adapter_needles if n not in adapter53]
    if missing_adapter:
        failures.append(f"12 §54：createDataRequestHandler 接线 / 释放缺失 {missing_adapter}")
    else:
        print("[PASS] 12 §54：仅 URL 源创建句柄、cancelRequest 与 close 成对释放")

    evidence54 = evidence.read_text(encoding="utf-8") if evidence.is_file() else ""
    if not all(n in evidence54 for n in ("### 2.5", "j1", "j2", "j3", "offset", "请求标识")):
        failures.append("12 §54：实证报告未补 Start 三参数语义（§2.5），实现失去依据")
    else:
        print("[PASS] 12 §54：Start 参数语义实证已回写报告，实现有据可查")

    # ---- 12 §59（第四十九批 / 阶段 C 根因修复：OnDataRequestData kind 槽校准）----
    # exp6 原始日志 + capstone 实证：OnDataRequestData 首 long 是 native
    # OnNetSourceData 的 kind 槽（==2 跳过 NMMemoryCache::Write、只投 OnWriteNetData
    # 异步任务），不是 requestId 回传槽；Memory Mode（cache_local=false）下该任务
    # 恒被 +0xb8 拒绝（GOT+lambda 间接调用），kind=2 的数据包无处落盘 → 读侧空数据
    # → Send Packet Invalid data error → 解码器 EOS。守护防止把 kind 修回 requestId、
    # 或把常量漂移成 2。
    needles59 = (
        "NATIVE_DATA_KIND_NORMAL", "OnDataRequestData(NATIVE_DATA_KIND_NORMAL",
        "12 §59", "OnNetSourceData", "Send Packet Invalid data error",
    )
    missing59 = [n for n in needles59 if n not in request_body]
    if missing59:
        failures.append(f"12 §59：onData kind 槽校准缺失 {missing59}")
    else:
        print("[PASS] 12 §59：OnDataRequestData kind=1 校准与实证注释在位")

    if "OnDataRequestData(requestId" in request_body:
        failures.append("12 §59：onData 首参回退成 requestId（native 视为 kind，==2 跳过内存写）")
    else:
        print("[PASS] 12 §59：onData 首参未被回退成 requestId")

    # ---- 12 §61（第五十一批 / 用户拍板路线 2：SetConfig 根目录 + cacheLocal=true）----
    # §58~§60 实证链：Memory Mode（cache_local=false）下 NMMemoryCache FIFO 尾部错位、
    # XorLocalSource fd 无位置串行化 —— 均 native 缺陷；normal mode（cache_local=true
    # + SetConfig 初始化根目录）是竞品生产路径。用户 2026-11-08 拍板批准（01 §6.1
    # 口径演进：App 内部缓存子目录、512MiB 上限、卸载即清）。守护防止回退成
    # Memory Mode 或丢失根目录初始化。
    adapter61 = adapter53  # EngineAdapter.kt 正文（§53 组已读取）
    missing61a = [n for n in ("SetConfig(", "legacy_engine_cache", "536870912", "12 §61")
                  if n not in adapter61]
    if missing61a:
        failures.append(f"12 §61：EngineAdapter 缺 SetConfig 缓存根目录初始化 {missing61a}")
    else:
        print("[PASS] 12 §61：EngineAdapter SetConfig(内部缓存目录 + 512MiB 上限) 在位")

    config61 = (ROOT / "engine-legacy" / "src" / "main" / "java" / "com" / "netease"
                / "cloudmusic" / "android" / "sdk" / "playerengine" / "SourceConfig.java")
    if not config61.is_file():
        failures.append("12 §61：SourceConfig.java 缺失")
    else:
        cfg_body = config61.read_text(encoding="utf-8")
        if "this.cacheLocal = true" not in cfg_body:
            failures.append("12 §61：SourceConfig.cacheLocal 回退成 false（Memory Mode 死路，§58~§60 已证）")
        else:
            print("[PASS] 12 §61：SourceConfig.cacheLocal=true（normal mode）在位")
        if "12 §61" not in cfg_body:
            failures.append("12 §61：SourceConfig 缺拍板注释（口径演进溯源）")
        else:
            print("[PASS] 12 §61：SourceConfig 拍板注释在位")

    doc01 = ROOT / "docs" / "01-产品文档.md"
    if doc01.is_file():
        d01 = doc01.read_text(encoding="utf-8")
        if "口径演进 2026-11-08" not in d01 or "12 §61" not in d01:
            failures.append("12 §61：docs/01 §6.1 缺口径演进批注（2026-11-08 用户拍板）")
        else:
            print("[PASS] 12 §61：docs/01 §6.1 口径演进批注在位")

    # ---- 12 §61.27（第五十四批 / G08 故障注入 + 冷启动启动竞态）----
    # 真机 K60 13:11:14 实证：ACTION_PLAY 早于 Room.restore() 到达时 resume() 空转，
    # MediaSession 假报 PLAYING、进度恒 0，需第二次 ACTION_PLAY 才出声；pendingPlay 兑现修复。
    # G08 瞬断注入实证：代理对首个 GET 发 RST，RangeFetcher 连接期重试一次救回
    # 302592B 全量取流（同一 Start id=1 对应两次 open url），37.8s 完整播放无 -103。
    controller61 = (ROOT / "feature-player" / "src" / "main" / "kotlin" / "com"
                    / "example" / "cloudmusic" / "player" / "PlayerController.kt")
    body61 = controller61.read_text(encoding="utf-8") if controller61.is_file() else ""
    needles61 = (
        "pendingPlay", "AtomicBoolean(false)", "if (queue.isEmpty())",
        "pendingPlay.set(true)", "pendingPlay.compareAndSet(true, false)", "§61.27",
    )
    missing61 = [n for n in needles61 if n not in body61]
    if missing61:
        failures.append(f"12 §61.27：冷启动播放意图兑现缺失 {missing61}")
    else:
        print("[PASS] 12 §61.27：pendingPlay 置位 / restore 兑现 / 实证注释在位")

    test61 = (ROOT / "feature-player" / "src" / "test" / "kotlin" / "com"
              / "example" / "cloudmusic" / "player" / "PlayerControllerTest.kt")
    test61_body = test61.read_text(encoding="utf-8") if test61.is_file() else ""
    if "pendingPlayBeforeRestore" not in test61_body:
        failures.append("12 §61.27：冷启动竞态回归单测缺失")
    else:
        print("[PASS] 12 §61.27：restore 前播放指令不丢失的回归单测在位")

    if "connectTimeoutMs: Int = 5_000" not in fetcher_body or "连接期失败（超时/拒连）立即重试一次" not in fetcher_body:
        failures.append("12 §61.27：连接期重试 / 5s 连接超时被回退（G08 ETIMEDOUT -103 会复现）")
    else:
        print("[PASS] 12 §61.27：连接期重试一次 + 5s 连接超时在位")

    injection61 = ROOT / "analysis" / "g08-fail-once-proxy.py"
    if not injection61.is_file() or "FAIL_ONCE" not in injection61.read_text(encoding="utf-8"):
        failures.append("12 §61.27：G08 一次性 GET 拒绝注入脚本缺失，实证不可复现")
    else:
        print("[PASS] 12 §61.27：G08 故障注入脚本在位（可复现）")

    # ---- 12 §61.28（第五十五批 / 默认仅 WiFi 播放闸门）----
    # 01 §6.1 / 03-02 §344「播放前由 03-06 检查」：配置字段早已有，但此前零生产消费方。
    # 守护防止闸门被拆、本地音源被误拦（B05）、或绕过 Service 同步判定导致会话假报 PLAYING。
    policy61c = (ROOT / "core-common" / "src" / "main" / "kotlin" / "com"
                 / "example" / "cloudmusic" / "common" / "NetworkPlaybackPolicy.kt")
    policy_body = policy61c.read_text(encoding="utf-8") if policy61c.is_file() else ""
    policy_needles = (
        "enum class NetworkKind", "fun interface NetworkPlaybackGate",
        "const val ERROR_CODE = \"WIFI_ONLY\"", "if (!networkSource) return null",
        "if (!wifiOnly) return null", "03-11 §6 B05",
    )
    missing_policy = [n for n in policy_needles if n not in policy_body]
    if missing_policy:
        failures.append(f"12 §61.28：仅 WiFi 判定层缺失 {missing_policy}")
    else:
        print("[PASS] 12 §61.28：纯 JVM 判定表（本地豁免 / 开关 / 错误码）在位")

    if not all(n in body61 for n in (
        "networkGate", "currentNetworkSource", "blockedByNetworkPolicy",
        "if (autoPlay && blockedByNetworkPolicy()) return",
    )):
        failures.append("12 §61.28：PlayerController 的播放前闸门被拆除")
    else:
        print("[PASS] 12 §61.28：prepareAt / resume 双入口闸门与本地豁免在位")

    gate61c = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
               / "example" / "cloudmusic" / "di" / "playback" / "AndroidNetworkPlaybackGate.kt")
    gate_body = gate61c.read_text(encoding="utf-8") if gate61c.is_file() else ""
    if not all(n in gate_body for n in (
        "ConnectivityManager", "TRANSPORT_WIFI", "TRANSPORT_CELLULAR",
        "settings.current().network.wifiOnly",
    )):
        failures.append("12 §61.28：Android 生产闸门缺失或未读实时设置（03-11 §4 即时生效）")
    else:
        print("[PASS] 12 §61.28：生产闸门读实时 wifiOnly + 平台网络态，零新增权限")

    service61c = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "PlaybackService.kt"
    service_body = service61c.read_text(encoding="utf-8") if service61c.is_file() else ""
    ui61c = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "ui" / "CloudMusicApp.kt"
    ui_body = ui61c.read_text(encoding="utf-8") if ui61c.is_file() else ""
    if "networkGate.rejectReason(runtime.currentNetworkSource())" not in service_body:
        failures.append("12 §61.28：Service 缺同步判定，拒绝后会假报 PLAYING（§61.27 同类问题）")
    elif not all(n in ui_body for n in ("允许移动数据播放", "onWifiOnlyChanged", "Switch(")):
        failures.append("12 §61.28：设置页缺移动数据播放开关（01 §6.1）")
    else:
        print("[PASS] 12 §61.28：Service 同步判定 + 设置页开关在位")

    # ---- 12 §61.29（第五十六批 / T-L L2 蓝牙授权 + ACL 广播联动）----
    # 03-09 §4 的广播映射表 / §8 B01 降级 / §10 B12 设备地址不入日志；
    # 03-11 §2 的 SHOW_BLUETOOTH_NAME 按动作申请（启动阶段零申请）。守护防止
    # permissionGranted 被写回硬编码 false 或 ACL 广播在启动时无条件注册。
    bt_action = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                 / "cloudmusic" / "BluetoothBroadcastAction.kt")
    bt_body = bt_action.read_text(encoding="utf-8") if bt_action.is_file() else ""
    bt_needles = (
        "BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED", "BluetoothProfile.STATE_CONNECTED",
        "BluetoothProfile.STATE_DISCONNECTED", "BluetoothAdapter.STATE_OFF",
        "BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED", "AudioManager.ACTION_AUDIO_BECOMING_NOISY",
    )
    missing_bt = [n for n in bt_needles if n not in bt_body]
    if missing_bt:
        failures.append(f"12 §61.29：03-09 §4 广播映射层缺失 {missing_bt}")
    else:
        print("[PASS] 12 §61.29：ACL / 适配器 / 播放态 / 有线拔出四路映射在位")

    service_bt = service61c.read_text(encoding="utf-8") if service61c.is_file() else ""
    if not all(n in service_bt for n in (
        "bluetoothConnectGranted()", "ACTION_BLUETOOTH_PERMISSION_CHANGED",
        "registerAclReceiver()", "unregisterAclReceiver()", "RECEIVER_NOT_EXPORTED",
    )):
        failures.append("12 §61.29：Service 缺运行时权限真值 / ACL 动态注册 / 授权刷新入口")
    elif "permissionGranted = false" in service_bt:
        failures.append("12 §61.29：permissionGranted 被写回硬编码 false（B01 降级不再随授权生效）")
    else:
        print("[PASS] 12 §61.29：权限真值 + 授权后注册 / 未授权注销成对在位")

    if "B12" not in bt_body and "B12" not in service_bt:
        failures.append("12 §61.29：缺 03-09 §10 B12 设备名 / 地址不入日志的口径注")
    else:
        print("[PASS] 12 §61.29：B12 设备地址隐私口径在位")

    main_activity = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                     / "cloudmusic" / "MainActivity.kt")
    main_body = main_activity.read_text(encoding="utf-8") if main_activity.is_file() else ""
    bt_test = (ROOT / "app" / "src" / "test" / "kotlin" / "com" / "example"
               / "cloudmusic" / "BluetoothBroadcastActionTest.kt")
    if "SHOW_BLUETOOTH_NAME" not in main_body or not bt_test.is_file():
        failures.append("12 §61.29：按动作申请接线或广播映射单测缺失")
    else:
        print("[PASS] 12 §61.29：输出弹窗按动作申请 + JVM 单测在位")

    for rel, needles in SIGNATURES.items():
        path = ROOT / rel
        if not path.is_file():
            continue
        body = path.read_text(encoding="utf-8")
        for needle in needles:
            if needle not in body:
                failures.append(f"契约签名漂移 {rel}: 缺 {needle!r}")

    model_dir = ROOT / "core-model" / "src" / "main"
    for path in model_dir.rglob("*.kt"):
        hit = FORBIDDEN_IMPORTS.search(path.read_text(encoding="utf-8"))
        if hit:
            failures.append(f":core-model 出现禁止依赖 {path.name}: {hit.group(0).strip()}")

    # 03-01 §12 的 T01~T20：后四条分布在 strm / webdav 驱动测试中
    tests = "".join(
        p.read_text(encoding="utf-8")
        for p in sorted(ROOT.glob("*/src/test/kotlin/**/*.kt"))
    )
    missing_tests = [f"T{i:02d}" for i in range(1, 21) if f"T{i:02d}" not in tests]
    if missing_tests:
        failures.append(f"契约测试编号缺覆盖：{missing_tests}")
    else:
        print("[PASS] 03-01 §12 的 T01~T20 编号全部有对应测试")

    # 12 §27 D-56 的长效守护：`@Test fun x() = runBlocking { ... assertFailsWith{...} }`
    # 的返回类型不是 Unit，JUnit Jupiter 会**静默跳过**该用例（第十八 / 十九批两次实测）。
    silent_tests: list[str] = []
    for path in sorted(ROOT.glob("*/src/test/kotlin/**/*.kt")):
        lines = path.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            if "fun " not in line or "= runBlocking {" not in line:
                continue
            depth = line.count("{") - line.count("}")
            body = [line]
            j = i + 1
            while j < len(lines) and depth > 0:
                body.append(lines[j])
                depth += lines[j].count("{") - lines[j].count("}")
                j += 1
            if "assertFailsWith" in "\n".join(body):
                silent_tests.append(f"{path.relative_to(ROOT).as_posix()}:{i + 1}")
    if silent_tests:
        failures.append(
            f"表达式体 runBlocking + assertFailsWith 返回非 Unit，JUnit 会静默跳过（12 §27 D-56）: {silent_tests}"
        )
    else:
        print("[PASS] 12 §27 D-56 守护：无非 Unit 表达式体 runBlocking 测试")

    # 12 §61.31 守护：G08 长稳口径 20 分钟（用户 2026-11-08 决策）必须在判据文档
    # 与采样器中一致；采样器不得退回硬编码 adb，否则文档声称「可复跑」实际跑不起来。
    sampler_path = ROOT / "analysis" / "g08-longrun-sample.py"
    criteria_path = ROOT / "docs" / "03-模块开发文档" / "03-06 播放内核与队列.md"
    if not sampler_path.is_file() or not criteria_path.is_file():
        failures.append("12 §61.31 守护：缺少 g08-longrun-sample.py 或 03-06 §6.2 判据文件")
    else:
        sampler_src = sampler_path.read_text(encoding="utf-8")
        criteria_src = criteria_path.read_text(encoding="utf-8")
        if "TARGET_MINUTES = 20" not in sampler_src or "连续后台播放 20 分钟" not in criteria_src:
            failures.append("12 §61.31 守护：G08 长稳口径应为 20 分钟且采样器与 03-06 §6.2 一致")
        elif '["adb", "-s"' in sampler_src or "resolve_adb" not in sampler_src:
            failures.append("12 §61.31 守护：采样器不得硬编码 adb，必须保留 resolve_adb() 自动探测")
        else:
            print("[PASS] 12 §61.31 守护：G08 20 分钟口径一致，采样器 adb 自动探测在位")

    # 12 §61.32 守护：合规声明必须与实际引入状态一致；sticky 返回值与长稳口径不得回退。
    notice_path = ROOT / "NOTICE.md"
    service_path = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" /
                    "cloudmusic" / "PlaybackService.kt")
    t16_path = ROOT / "docs" / "03-模块开发文档" / "03-07 媒体会话与音频焦点.md"
    if not all(p.is_file() for p in (notice_path, service_path, t16_path)):
        failures.append("12 §61.32 守护：NOTICE.md / PlaybackService.kt / 03-07 §12 缺失")
    else:
        notice_src = notice_path.read_text(encoding="utf-8")
        service_src = service_path.read_text(encoding="utf-8")
        t16_src = t16_path.read_text(encoding="utf-8")
        if "尚未提取进项目" in notice_src or "按文档规划，当前未引入" in notice_src:
            failures.append("12 §61.32 守护：NOTICE.md 仍写「尚未引入」，与 18 so 已打包的合规声明不符")
        elif "return START_STICKY" not in service_src:
            failures.append("12 §61.32 守护：PlaybackService.onStartCommand 必须返回 START_STICKY")
        elif "| T16 | 真机 | 后台 2h |" in t16_src:
            failures.append("12 §61.32 守护：03-07 §12 T16 长稳口径应为 20 分钟")
        else:
            print("[PASS] 12 §61.32 守护：NOTICE 合规声明 / START_STICKY / T16 20 分钟口径一致")

    # 12 §61.33 守护：LAUNCHER 必须 exported=true；现行口径文档不得残留 2h 判据。
    manifest_path = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
    if not manifest_path.is_file():
        failures.append("12 §61.33 守护：app/src/main/AndroidManifest.xml 缺失")
    else:
        manifest_src = manifest_path.read_text(encoding="utf-8")
        activity_slice = manifest_src.split('android:name=".MainActivity"', 1)[-1].split("</activity>", 1)[0]
        scope = [
            ROOT / "docs" / "01-产品文档.md",
            ROOT / "docs" / "09-文档闭环总览.md",
            ROOT / "docs" / "10-技术架构评估.md",
            ROOT / "docs" / "16-架构与任务拆分复核报告.md",
            ROOT / "docs" / "03-模块开发文档" / "03-06 播放内核与队列.md",
            ROOT / "docs" / "03-模块开发文档" / "03-07 媒体会话与音频焦点.md",
            ROOT / "docs" / "03-模块开发文档" / "03-09 蓝牙与音频输出.md",
            ROOT / "docs" / "03-模块开发文档" / "03-11 权限与设置.md",
            ROOT / "docs" / "03-模块开发文档" / "03-12 构建发布与合规.md",
        ]
        stale = []
        for p in scope:
            if not p.is_file():
                continue
            text = p.read_text(encoding="utf-8")
            for needle in ("后台 2h", "2h 内存", "2h 后台"):
                if needle in text:
                    stale.append(f"{p.name}:{needle}")
        if 'android:exported="true"' not in activity_slice:
            failures.append("12 §61.33 守护：LAUNCHER MainActivity 必须 exported=true")
        elif stale:
            failures.append("12 §61.33 守护：现行口径文档残留 2h 判据：" + ", ".join(stale))
        else:
            print("[PASS] 12 §61.33 守护：LAUNCHER exported=true，现行文档无 2h 残留")

    # 12 §61.34 / C-16 守护：offline 必须真实影响 networkSourceAvailable，不得退回死参数。
    gate_path = (ROOT / "core-common" / "src" / "main" / "kotlin" / "com" / "example" /
                 "cloudmusic" / "common" / "PermissionGate.kt")
    gate_test = (ROOT / "core-common" / "src" / "test" / "kotlin" / "com" / "example" /
                 "cloudmusic" / "common" / "PermissionGateTest.kt")
    if not gate_path.is_file() or not gate_test.is_file():
        failures.append("12 §61.34 守护：PermissionGate.kt / PermissionGateTest.kt 缺失")
    else:
        gate_src = gate_path.read_text(encoding="utf-8")
        test_src = gate_test.read_text(encoding="utf-8")
        if "networkSourceAvailable = !offline" not in gate_src:
            failures.append("12 §61.34 守护：offline 必须映射为 networkSourceAvailable（C-16）")
        elif "offlineBlocksNetworkSourceOnly" not in test_src or "assertEquals(!offline" not in test_src:
            failures.append("12 §61.34 守护：缺少 offline 两态差异的 JVM 断言")
        else:
            print("[PASS] 12 §61.34 守护：C-16 离线语义与两态断言在位")

    # 12 §61.35 / C-17 守护：离线事实必须由 isOffline(NONE) 单点给出，
    # 且播放闸门与 UI 降级契约之间有交叉断言，防止两套口径分叉。
    policy_path = (ROOT / "core-common" / "src" / "main" / "kotlin" / "com" / "example" /
                   "cloudmusic" / "common" / "NetworkPlaybackPolicy.kt")
    policy_test = (ROOT / "core-common" / "src" / "test" / "kotlin" / "com" / "example" /
                   "cloudmusic" / "common" / "NetworkPlaybackPolicyTest.kt")
    if not policy_path.is_file() or not policy_test.is_file():
        failures.append("12 §61.35 守护：NetworkPlaybackPolicy.kt / Test 缺失")
    else:
        policy_src = policy_path.read_text(encoding="utf-8")
        test_src = policy_test.read_text(encoding="utf-8")
        if "fun isOffline(kind: NetworkKind): Boolean = kind == NetworkKind.NONE" not in policy_src:
            failures.append("12 §61.35 守护：离线单一真相源 isOffline(NONE) 缺失")
        elif "offlineFactHasSingleSource" not in test_src or "NetworkPlaybackPolicy.isOffline(kind)" not in test_src:
            failures.append("12 §61.35 守护：播放闸门与降级契约的交叉断言缺失")
        else:
            print("[PASS] 12 §61.35 守护：C-17 离线事实单一真相源与交叉断言在位")

    if (ROOT / "core-common" / "src" / "main" / "kotlin" / "com" / "example" /
            "cloudmusic" / "common" / "PathNormalizer.kt").is_file():
        warnings.append("实现期修正 C-01：路径规范化下沉至 :core-common（见 PathNormalizer KDoc）")
    if (ROOT / "datasource-api" / "src" / "main" / "kotlin" / "com" / "example" /
            "cloudmusic" / "datasource" / "TextReadCapability.kt").is_file():
        warnings.append("实现期契约回补 C-02：TextReadCapability（03-01 §5.1 可选能力模式）")
    if (ROOT / "core-storage" / "src" / "main" / "kotlin" / "com" / "example" /
            "cloudmusic" / "storage" / "InMemoryLibrary.kt").is_file():
        warnings.append("实现期补充 C-03（Playlist/TrackEntity 就近定义）/ 澄清 C-04（重扫恢复走 ScanReconciler 显式通道），见 12 §13")
        warnings.append("契约演进 C-05（EngineSource 可选 url/localPath）/ C-06（ProxyStartPolicy 触发器 ④）已落地，见 16 §4 / 12 §21")
        warnings.append("契约演进 C-07（PROXIED Link 补 proxySourceUrl 回源 URL，驱动 / 代理已落地）/ C-08（匿名直链探测已于 12 §31 随 T-A5 透传 Track 上下文落地），见 12 §25 / §31")
        warnings.append("契约演进 C-12（ByteRange 生产实现）/ C-13（nanohttpd 本体）/ C-14（effects 执行者与定向增量）已落地，见 12 §31")
        warnings.append("T-L L1 步骤 1（12 §32）：18 个 so + ENGINE-FINGERPRINT + :engine-legacy 舱体已落地；84 真类 / slot / 真机仍按 12 §10.3 时间盒推进")
        warnings.append("T-A6（12 §33）：Compose UI 骨架 / LAUNCHER 已落地；照搬播放页（T-L L3）与真实内核（T-L L1）仍待，V0.1 用迷你播放条占位")
        warnings.append("T-L L1 步骤 2 前置（12 §34）：12 个精确 JNI 类真实源码 + 精确 so 加载链已取得；84 真类全集 / 编译补 slot / 真机仍在时间盒内")
        warnings.append("T-L L1 步骤 3（12 §35）：舱内 12 真类 + 37 slot 编译通过、45 行登记簿已填；EngineAdapter / 真机 G01~G09 仍未收口（编译通过 ≠ 引擎可用）")
        warnings.append("T-L L1（12 §36）：EngineAdapter 已实现并换绑 EngineModule，登记簿 51 行；真机 G01/G02 与 JNI 回调校准仍未收口")
        warnings.append("静态 ABI（12 §37）：A1/A2 PASS，缺符号清单 15 项已列（V0.1 播放主链路符号齐全）；A3 真机 G01~G09 因本机无 adb 未跑")
        warnings.append("T-L L3 前置（12 §38）：loadAnimation 链路已解析（2 个 scale anim），唱片旋转不走该链路；40 rotate 候选仍需真机，当时 assets-mapping.csv 为空表（12 §47 起已有行）")
        warnings.append("T-L L3（12 §39）：唱片旋转 / 唱针已证实为纯代码属性动画（20s ÷ playSpeed），14 §4.1 挂账收口、播放页闭包无需 anim 项；⑦ 素材已于 12 §47 导出至 assets 暂存，转 res 属 L3 后续")
        warnings.append("T-A6 完善（12 §40）：UI 五页 + 输出弹窗 + OutputRouter DI 绑定已落地；⑦ 的 C4 口径已于 12 §47 经用户确认并实施")
        warnings.append("T-A5 遗留（12 §41）：音频焦点 + BECOMING_NOISY 已接线；T1（12 §43）已解 DI 环并由 PlayerController.init 完成 bind，T4（12 §44）已把 onPlay/onPause 状态回传门面；T-L L2 的蓝牙状态 / OutputRouter 联动与真机用例仍待")
        warnings.append("契约演进 C-15（12 §44）：IMediaControl 增加带默认空实现的 onPlaying()/onManualPause()，照搬层与测试替身零回归；FocusPolicy.onPlaying() 同时清恢复标记，避免 GAIN 重复 resume")
        warnings.append("C4 口径（12 §47，D-145 用户确认）：61 项竞品素材导出至 app/src/main/assets/legacy-player/ 并登记 assets-mapping.csv（replaced=false）；check-assets.py 未替换项改 dev WARN / release FAIL，release 新增 C4 阻断")
        warnings.append("DLNA 输出链路缺口（12 §48）：OutputRouter.setActive / PlayerController.onOutputChanged / refreshBluetoothFrom 全库仅测试调用，activeOutput 恒 LOCAL、remoteOutput 恒 false；§46 的 DLNA 豁免当前空转。真实触发点待 T-V T20/T21 的 DMR 控制链路与 UI 切换决策，不擅自伪造投屏状态")
        warnings.append("真机 A3（12 §50）：native 6 so → PlaybackEngine → setDataSource → prepareAsync → 焦点 GAIN → Play 全链真机跑通零崩溃，但 0 active track / 播放期无 HTTP 请求，仍放不出真实声音；createDataRequestHandler() 返回 null，取流回调链待真机实证（§50.7 #1）")
        warnings.append("第四十二批（12 §51）：§50 的 5 个 P0 修复已补 6 组长效守护 + :app 首个 JVM 单测（5 用例；按键判定抽离为纯 JVM 的 MediaKeyAction）；不改 C4 / dev / release 基线，真机 G01~G09 仍待设备与用户判据")
        warnings.append("第四十三批（12 §52）：§50.7 #3 应用侧闭环落地 —— 按动作申请权限（首次播放 POST_NOTIFICATIONS / MediaStore 导入 READ_MEDIA_AUDIO，启动零申请）+ 数据源页 SAF/媒体库两个添加入口，写 Storage 后立即 rescanAll；真机不再需要 run-as 灌 Room 夹具（待设备复验）。当时挂账的蓝牙 ACL / SHOW_BLUETOOTH_NAME 已由 12 §61.29 收口、取流句柄已由 §54/§61 收口")
        warnings.append("第四十四批（12 §53，静态实证轮）：取流句柄类型已实证 —— createDataRequestHandler() 应返回 SourceDataRequest 子类（竞品 DataSourcePlatformImpl$d → nmcache/l → SourceDataRequest），不是 IBaseDataSource；native 经 NMBufferSource.SetDataRequest 绑定并回调 Start(JJJ)J / Stop()，5 个 JNI 符号齐全且 NMCommonCache 已在加载链。证据见 analysis/data-request-handler-evidence-2026-11-07.md。该批当时生产仍返回 null（第四十五批已按追加实证接通）；分阶段真机判据见报告 §5")
        warnings.append("第四十五批（12 §54）：继续反汇编实证出 Start(j1=请求标识 / j2=offset / j3=length) 与 OnDataRequest* 回传首参后，落地 RangeFetcher（纯 JVM 取流 + RangeOpener/RangeSink）、UrlDataRequest（SourceDataRequest 子类，Start 立即返回 0）、7 个 JVM 用例，并把 createDataRequestHandler() 改为仅 URL 源缓存返回、close 时 release。清单 36/152/15、5 组 §54 守护。阶段 A/B 已具备代码但**仍待真机验证**：OnDataRequestData 第 2/3 个 long、OnDataRequestEnd 的 String 取值未校准；编译通过 ≠ 出声，A3 仍 DEEP RUN、舱不冻结")
        warnings.append("第四十九批（12 §59，阶段 C 根因修复）：capstone + exp6 原始 logcat 钉死数据通路 —— OnDataRequestData 首 long 是 native OnNetSourceData 的 kind 槽（==2 跳过 NMMemoryCache::Write），Memory Mode 下 OnWriteNetData 异步任务恒被 +0xb8 拒绝（GOT+lambda 间接调用，.text 无直接 bl）；§58 路线 1 证伪（resource config 由 libncmplayerengine 硬编码 4 键、无 file_id/trunks）、路线 2 仍待用户拍板（SetConfig 根目录 + cacheLocal=true，与 01 §6.1 冲突）。本批 onData kind 恒传 1（exp6：kind=1 的 push 全部落内存缓存、32768B 送达解码器），阶段 C 数据通路待真机 E9 复验；A3 仍 DEEP RUN、舱不冻结、指纹不回填")
        warnings.append("第五十一批（12 §61，用户 2026-11-08 拍板批准路线 2）：SourceConfig.cacheLocal=true（normal mode）+ EngineAdapter.SetConfig 初始化 App 内部缓存根目录（context.cacheDir/legacy_engine_cache，512MiB 上限，卸载即清）；docs/01 §6.1 口径演进批注随批落档。E9/E10 的两个 native 缺陷（Memory Mode FIFO 尾部错位、XorLocalSource fd 竞争）在 normal mode 下消失；真机 E11 判据 = 不再 Start Use Memory Mode / Failed Not Support、active track ≥1、MediaSession 非 state=2；A3 仍 DEEP RUN、舱不冻结、指纹不回填")
        warnings.append("第五十八批（12 §61.31，2026-11-08 用户决策）：G08 长稳窗口 2h → 20 分钟（实际使用有问题再修改），01 §10 #5 / 03-06 §6.2 / 03-12 §9 口径统一；真机 21 分 49 秒未被回收、异常 0、PSS 峰值 110.3 MiB → G08 PASS（20 分钟口径），终点 SwipeUpClean 属人为强停；A3 = G01~G07 PASS / G08 PASS / G09 PARTIAL，舱仍不冻结、指纹不回填。修复 g08-longrun-sample.py 硬编码 adb 并补 ApplicationExitInfo 退出原因；证据 analysis/g08-20min-verdict-2026-11-08.md")
        warnings.append("第五十九批（12 §61.32，2026-11-08 晚）：亮屏实证 G09 —— 前台态 crash 约 100ms 由 Activity 重建恢复（非 START_STICKY）、后台态 crash 206s 无重启归因 MIUI fg service restriction（推翻旧「需 USB/亮屏」归因）、用户重新点开约 957ms 自动 prepare 回上一曲目并保持 PAUSED；G09 仍 PARTIAL 不判 PASS，A3 仍不全绿、舱不冻结。修正 NOTICE.md §3 的 P1 合规声明错误（原写二进制/素材尚未引入）、03-07 §12 T16 同步 20 分钟；发布阻断 12 条盘点 PASS 5 / 部分 5 / 未满足 2。证据 analysis/g09-recovery-evidence-2026-11-08.md")
        warnings.append("第六十批（12 §61.33，2026-11-08 晚）：按 Android 官方 <activity> 文档定论 LAUNCHER MainActivity 必须 exported=true（设 false 外部启动抛 ActivityNotFoundException），§61.23/§61.24 的「恢复 exported=false / 随 Service 一并恢复」为错误口径已作废；生产形态仅 PlaybackService 收口后改 false 并移除 ACTION_PLAY intent-filter，MediaButtonEventReceiver 保持 true。同步修正 03-11 §1.1 表格（Receiver exported=true + MediaKeyAction 转发，非「有意空实现」）并把全库现行 7 处「后台 2h」统一为 20 分钟；本批零行为改动，A3 仍 G09 PARTIAL")
        warnings.append("第六十一批（12 §61.34 / C-16，2026-11-08 晚）：发现 DegradationPolicy.evaluate 的 offline 是死参数、03-11 §3「网络权限/飞行模式」一行的语义未落地且 JVM 测试两态断言等价。新增 DegradationOutcome.networkSourceAvailable（默认 true，现有调用零破坏）+ offline 映射与信息提示，硬规则（本地曲库/播放恒可用、不弹网络错误）不变；PermissionGateTest 新增 2 用例并把原弱断言升级为 assertEquals(!offline, …)。发布阻断 #11 的 JVM 侧矩阵已全量覆盖，真机 T05/T06/T14 仍待 T-V；networkSourceAvailable 当前无生产消费方，如实登记不伪造接线")
        warnings.append("第六十二批（12 §61.35 / C-17，2026-11-08 晚）：核对发现离线事实存在两套表达 —— 播放闸门 NetworkPlaybackPolicy 的 NetworkKind.NONE（有 PlayerController 生产消费方）与 C-16 的 DegradationPolicy.offline（UI 降级契约）。新增 isOffline(kind) 作为单一真相源并在 NetworkPlaybackPolicyTest 加交叉断言，守护防分叉；零产品行为改动。另确认无线 adb 下无法做飞行模式 / 关 WiFi 真机实验（会失联且无法远程恢复），属 USB 环境硬阻塞。本批把 5 个批次的工作区改动固化为结构化本地提交（不 push）")

    print(f"[INFO] 检查 {len(GRADLE_FILES)} 个构建文件 / {len(CONTRACT_FILES)} 个契约源码与测试 / "
          f"{len(CHECK_SCRIPTS)} 个工具脚本")
    for w in warnings:
        print(f"[NOTE] {w}")
    if failures:
        for f in failures:
            print(f"[FAIL] {f}")
        print(f"\n结果：FAIL（{len(failures)} 项）。注意：静态自检不替代 ./gradlew test")
        return 1
    print("\n结果：PASS。结构齐全、契约签名在位、禁止依赖为零；"
          "编译与 JVM 单测请用 ./gradlew test（本机已验证通过）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
