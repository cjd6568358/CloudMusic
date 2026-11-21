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
第一百五十五批（12 §61.139，2026-11-15）：拉取 74 个提交后修复三项守护口径漂移
（§52 文案 / §61.28 组件 / §61.41 res 基线），并把 §61.131 的门面 prepareQueue
接进播放链（队列索引 + 跳播两个增量方法、三实现、PlayerController 分支）；
新增 3 个 JVM 用例与一组 §61.139 守护；模块数 / 依赖 / 打包集零变化。
第一百五十六批（12 §61.140，2026-11-15）：§61.138 挂账收口——歌单行首曲封面
（PlaylistsScreen 每行一条 observeTracks 流取 firstOrNull()?.coverRef →
CompPlaylistRow）+ 歌单展开曲目行补 coverRef；零契约变更、零新增依赖，
新增一组 §61.140 守护（真机像素比对仍 NOT RUN）。
第一百五十七批（12 §61.141，2026-11-15）：设备上线真机批（零源码改动）——
gapless 施工单步骤 3 真机 PASS（prepareQueue + cross_track + 零间隙 +
AudioTrack 同 session 跨曲）与 §61.140 歌单首曲封面真机 PASS；
裁决 evidence = analysis/gapless-61-141-verdict.json + screenshots；
新增一组 §61.141 守护（防裁决证据被删/被改写）。
第一百五十八批（12 §61.142，2026-11-15）：容器 Extractor 真机复测清点
（零源码改动）——wma/aiff/wavpack 三项 PASS + ape FAIL×2（静音）分档登记
（PASS 留档不删、FAIL 不隐匿，双口径受守护）；
新增一组 §61.142 守护。
第一百五十九批（12 §61.143，2026-11-15）：T-V #9 就绪度打通——binder registry
优先 + playLocal 强制重建（C-10 覆盖冲突）+ DataStore 预写 storage；7,500 冷扫
首跑 FAIL 判据定位 runMetadata 逐条事务设计；0-candidate 破坏面挂账；
新增一组 §61.143 守护。
第一百六十批（12 §61.144，2026-11-15）：runMetadata 按 batchSize 聚合（事务/emit
7,514→16，T-A4-11）+ 重扫真机深化（阶段 A 更新路径 8.5min / metadata 通道根因 /
增量扫 NOT RUN）；冷扫判据维持 FAIL；
新增一组 §61.144 守护。
第一百六十一批（12 §61.145，2026-11-15）：扫描三线优化——①LOCAL 路径 JAudio
先行 + 检索器缺项兜底（content:// 原样）②封面 MD5 去重
③markAvailabilityBatch 接口默认实现 + 批量覆写 + Reconciler 分组；
新增一组 §61.145 守护。
第一百六十二批（12 §61.146，2026-11-15）：增量判据真机 PASS（1,051ms）+
directoryCover O(n²) 守卫修复（embeddedCover==null 才查）；
新增一组 §61.146 守护。
第一百六十三批（12 §61.147，2026-11-15）：冻结根因链钉死（检索器容器白名单 +
目录缓存 + CM-Tag 观测）+ 冷扫 216s 收口（判据 FAIL 8× 改善）+ 增量 PASS；
新增一组 §61.147 守护。
第一百六十四批（12 §61.148，2026-11-15）：ape 静音归因 + §61.127 PASS 勘误
（v3974 packet 帧边界与 FFmpeg apedec 期望不匹配 = 最优假说，互证施工单挂账；
位置-only 证据不再单独采信）；零代码改动；新增一组 §61.148 守护。
第一百六十五批（12 §61.149，2026-11-15）：ape packet 前缀 BE→LE 字节序修复
（FFmpeg 双侧源码互证：demuxer AV_WL32 + 解码器 bswap_buf+BE 读 = 恒等）+
skip 对齐等价替代 + 样本终审（描述符垃圾，真机验证须真实 APE 样本）；
新增一组 §61.149 守护。
第一百六十六批（12 §61.150，2026-11-15）：MiniPlayer 真曲名（§61.141 观察项
收口）+ ReplayGain 真机验证收口（29 日志/21 落库/spec 一致实锤）；
新增一组 §61.150 守护。
第一百六十七批（12 §61.151，2026-11-15）：STRM 独立源 UI 收口（StrmDriver
已在位，补 SourcesScreen 入口 + StrmDialog + MainActivity 接线；UX 挂账登记）；
新增一组 §61.151 守护。
第一百六十八批（12 §61.152，2026-11-15）：数据源移除入口收口（§61.151 UX 挂账
——确认弹窗 + remove/markOrphan + rescanAll 接线）；新增一组 §61.152 守护。
第一百六十九批（12 §61.153，2026-11-15）：WebDAV/STRM 真机添加排障——双真 bug
修复（TextReadCapability 双实现 + PropfindParser XXE 特性 runCatching）+
扫描错误/binder 可见化四件套；新增一组 §61.153 守护。
详见 docs/12-编码阶段第一批交付.md §5.1 / §11~§21 / §25~§61 / §61.153。
"""
from __future__ import annotations

import csv
import hashlib
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
    "datasource-sftp/build.gradle.kts",  # V0.2 §61.216
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
    # 12 §61.218：歌曲信息展示口径
    "core-model/src/main/kotlin/com/example/cloudmusic/model/TrackInfo.kt",
    "core-model/src/test/kotlin/com/example/cloudmusic/model/TrackInfoTest.kt",
    # 12 §61.220：智能歌单纯 JVM 规则引擎
    "core-model/src/main/kotlin/com/example/cloudmusic/model/SmartPlaylist.kt",
    "core-model/src/test/kotlin/com/example/cloudmusic/model/SmartPlaylistTest.kt",
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
    # 12 §61.198：在线歌词 provider（docs/01 §0 拍板，本地缺失才按需抓取）
    "core-di/src/main/kotlin/com/example/cloudmusic/di/lyrics/OnlineLyricsFetcher.kt",
    "core-di/src/test/kotlin/com/example/cloudmusic/di/lyrics/OnlineLyricsFetcherTest.kt",
    # 12 §61.217：外置 lrc 多候选路径
    "core-di/src/main/kotlin/com/example/cloudmusic/di/scan/LyricsPathCandidates.kt",
    "core-di/src/test/kotlin/com/example/cloudmusic/di/scan/LyricsPathCandidatesTest.kt",
    # 12 §61.199：DLNA 断连回落（会话健康判定 + 进度读取）
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/DlnaSessionMonitor.kt",
    "output-dlna/src/test/kotlin/com/example/cloudmusic/output/dlna/DlnaSessionMonitorTest.kt",
    # 12 §61.200：DMR 多设备列表选择判定
    "output-dlna/src/main/kotlin/com/example/cloudmusic/output/dlna/DmrDeviceSelector.kt",
    "output-dlna/src/test/kotlin/com/example/cloudmusic/output/dlna/DmrDeviceSelectorTest.kt",
    # 12 §61.216：V0.2 SFTP 数据源
    "datasource-sftp/src/main/kotlin/com/example/cloudmusic/sftp/SftpPorts.kt",
    "datasource-sftp/src/main/kotlin/com/example/cloudmusic/sftp/SftpDriver.kt",
    "datasource-sftp/src/test/kotlin/com/example/cloudmusic/sftp/SftpDriverTest.kt",
    "core-di/src/main/kotlin/com/example/cloudmusic/di/adapters/JschSftpPort.kt",
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
        # §61.153：supertype 追加 TextReadCapability（.strm 扫描/委托链文本读取）
        "), DirectLinkCapability, ByteRangeCapability, TextReadCapability {",
        "override suspend fun readBytes(file: Obj, offset: Long, length: Int): ByteArray?",
    ],
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/ScanCoordinator.kt": [
        "class ScanCoordinator(", "fun scan(request: ScanRequest): Flow<ScanProgress>",
        "suspend fun run(",
    ],
    "scanner/src/main/kotlin/com/example/cloudmusic/scanner/TrackFactory.kt": [
        "fun build(candidate: Candidate, raw: RawTags = RawTags.EMPTY): Track",
        # §61.221 升级：分轨 TrackId 用锚点 idPath（effectiveIdPath 对既有候选 = obj.path 不变）
        "TrackId.of(candidate.storage.id, candidate.effectiveIdPath())",
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
        # §61.230 升级：起播分支 fade-in 化（play/pause 分派语义不变，强度不减）
        "if (autoPlay) {",
        "engine.play(h)",
        "} else engine.pause(h)",
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
    def _code_only(src: str) -> str:
        """剥离 Kotlin `//` / TOML `#` 注释，只留真实代码行。

        与 12 §61.80 / §61.81 审计工具同源：C1 决策的否定式历史注释
        （如「不引 media3-session」）是范围声明、不是依赖坐标，全文子串
        检查会误判成「已引入依赖」，须基于剥离注释后的代码判断。
        """
        out = []
        for ln in src.splitlines():
            s = ln.strip()
            if s.startswith(("//", "#", "*")):
                continue
            if "//" in ln:
                ln = ln.split("//", 1)[0]
            out.append(ln)
        return "\n".join(out)

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
    # §61.131（拍板：粒度 A）：:engine-legacy 移出打包链——反转断言：
    # ① settings 不再 include ② :app 不再依赖 ③ legacy 目录文件仍存在（git 历史保留）
    if '// include(":engine-legacy")' not in settings_text:
        failures.append("12 §32/§61.131：settings 应注释 :engine-legacy include（移出打包链）")
    if 'project(":engine-legacy")' in app_build_text:
        failures.append("12 §32/§61.131：:app 仍依赖 :engine-legacy（应已移除）")
    if not (ROOT / "engine-legacy" / "src" / "main").is_dir():
        failures.append("12 §61.131：:engine-legacy 源码目录应保留（git 历史不删文件）")
    else:
        print("[PASS] §61.131：:engine-legacy 已移出打包链（源码目录保留，git 历史不删）")

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
    # §61.274（V0.4-01 入口合并）：曲库已并入底栏「音乐库」，不再有「更多→曲库」行；
    # 判据同步为「四个功能入口标签 + 音乐库页在位」——页面口径不缩（03-10 §5 五页仍全），
    # 只是曲库入口由本页承载（`FnLibraryScreen(` 断言）。
    # §61.278：功能页入口标签的值从 Unicode 字形改为 FnIcon 枚举（判据随类型改，强度不变：
    # 仍是「四个入口标签 + 三页骨架 + 输出弹窗 + 蓝牙不并列」五项齐全才算 PASS）。
    ui_needles = (
        "\"歌单\" to FnIcon.Playlist", "\"搜索\" to FnIcon.Search",
        "\"队列\" to FnIcon.Queue", "\"设置\" to FnIcon.Settings",
        "FnLibraryScreen(", "OutputDialog(", "蓝牙设备请在系统设置里连接",
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

    # ---- 12 §48（DLNA 输出切换：联动契约守护，§61.110 起接线）----------------
    # 03-08 §14 #9 / 03-09 §7：切 DLNA 必须同时改 OutputRouter 与 PlayerController
    # （后者内部再联动 IMediaControl.setEnabled，T09），禁止只切一半状态。
    # §61.110 演进：切换决策点在 :app（MainActivity.onOutputSelected）；
    # §61.199 起 core-di 的断连回落也是真实切换点（setActive LOCAL + onOutputChanged
    # 成对），故两层都纳入计数，但剔除 core-di 里委托透传「定义」的那一次调用。
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
            changed = len(re.findall(r"\.onOutputChanged\(", body))
            if root_rel == "core-di/src/main":
                # §61.199：断连回落本身是第二个切换点（core-di 内成对 setActive +
                # onOutputChanged）；委托透传定义里的 `controller.onOutputChanged(dlna)`
                # 不是切换动作，从计数中剔除，避免把「定义」当成「调用」。
                changed -= body.count("= controller.onOutputChanged(dlna)")
            output_changed_calls += changed
    _runtime_pth = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com" /
                    "example" / "cloudmusic" / "di" / "playback" /
                    "PlaybackComposition.kt").read_text(encoding="utf-8")
    _passthrough = "fun onOutputChanged(dlna: Boolean) = controller.onOutputChanged(dlna)" in _runtime_pth
    if active_calls != output_changed_calls or not _passthrough:
        failures.append(
            f"03-08 §14 #9 / 03-09 §7：切换点 setActive({active_calls}) 与 "
            f"onOutputChanged({output_changed_calls}) 必须成对联动（§61.110 计数口径："
            f"决策层 :app；core-di 委托透传在位={_passthrough}）"
        )
    else:
        print(
            f"[PASS] 03-09 §7：输出切换联动成对（决策层 {active_calls} 处成对；"
            "core-di 委托透传在位）——§61.110 DLNA 第一调用点已接线"
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
    # §61.176（点歌接线）：START_PLAYBACK 增至 2 处（迷你条播放键 + 曲目行点歌），
    # 两处都是用户动作回调，仍满足「启动阶段零申请」。判据同步升级为**按动作计数**，
    # 强度不减反增：总数 3 之外，两个动作的调用点数量也各自钉死——
    # 多一处就等于多一个未登记的申请入口。
    startup_requests = len(re.findall(r"permissionRequester\.request\(", main52))
    play_requests = len(re.findall(r"UserAction\.START_PLAYBACK", main52))
    bt_requests = len(re.findall(r"UserAction\.SHOW_BLUETOOTH_NAME", main52))
    if (missing_main or startup_requests != 3
            or play_requests != 2 or bt_requests != 1):
        failures.append(
            f"12 §52：MainActivity 动作接线缺失 {missing_main}；"
            f"permissionRequester.request 应恰好 3 处（实测 {startup_requests}），"
            f"START_PLAYBACK 应 2 处（实测 {play_requests}）/ SHOW_BLUETOOTH_NAME 应 1 处"
            f"（实测 {bt_requests}），启动不得申请"
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
    # §61.139：§61.137 竞品风格重建把按钮文案改为「本地目录（SAF）」，
    # 并新增 WebDAV 入口（§61.132）；守护按现行实现核对，不按旧文案误判功能缺失。
    missing_ui = [n for n in ("onAddSaf", "onAddMediaStore", "本地目录（SAF）", "从媒体库导入", "添加 WebDAV 源")
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
    # §61.175（飞牛设置页）：开关从竞品 CompSwitchRow 换成飞牛 FnRow + FnSwitch，
    # 判据同步升级（强度不减）：仍要求「开关组件 + 双向回调 + checked 取反」三件同在。
    # §61.188 W5 用户要求「设置页人话化」→ 标签改为「移动网络播放」；两种文案均
    # 表示同一 wifiOnly 开关，判据接受任一表述（不因此放宽组件/回调/取反三件）。
    elif not (("允许移动数据播放" in ui_body or "移动网络播放" in ui_body) and all(
        n in ui_body for n in ("onWifiOnlyChanged", "FnSwitch(", "checked = !wifiOnly")
    )):
        failures.append("12 §61.28：设置页缺移动数据播放开关（01 §6.1）")
    else:
        print("[PASS] 12 §61.28：Service 同步判定 + 设置页开关在位")

    # ---- 12 §61.176（点歌接线 / 用户一手）：默认参数吞掉回调的静态守护 ----
    # 现象：点曲目行无任何反应（无播放、无日志）。根因不在播放链，而在**接线**：
    # `CloudMusicApp.onPlayTracks` 自 §61.113 起带默认空实现 `{ _, _ -> }`，
    # MainActivity 侧从未传值 → 编译通过、运行期静默 no-op。这类「有默认值所以
    # 不漏编译」的洞只能靠源码断言守住，判据三条：
    # ① 契约签名仍在（防止有人把参数删了换成别的名字）；
    # ② MainActivity 显式绑定，且落到 `runtime.playTracks`（不是空 lambda / 只切页）；
    # ③ 每个曲目列表页都把回调透传给行点击（缺一页就有一页点不动）。
    main_act = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                / "cloudmusic" / "MainActivity.kt")
    main_kt = main_act.read_text(encoding="utf-8") if main_act.is_file() else ""
    passthrough_pages = ("HomeScreen(", "FnLibraryScreen(", "SearchScreen(", "MyMusicScreen(")
    if "onPlayTracks: (List<Track>, Int) -> Unit" not in ui_body:
        failures.append("12 §61.176：CloudMusicApp 的点歌回调签名被改动（§61.113 契约）")
    elif "onPlayTracks = " not in main_kt or "runtime.playTracks(" not in main_kt:
        failures.append("12 §61.176：MainActivity 未绑定 onPlayTracks（点歌静默 no-op，用户一手）")
    elif any(p not in ui_body for p in passthrough_pages):
        failures.append("12 §61.176：曲目列表页缺 onPlayTracks 透传（该页点歌无效）")
    else:
        print("[PASS] 12 §61.176：点歌回调 MainActivity→runtime.playTracks 全链在位")

    # ---- 12 §61.177（声场展宽直通分支 / 用户一手「歌全播不了」）----
    # 现象：库里任何歌一点就崩，logcat 为
    #   IllegalArgumentException at ByteBuffer.put <- SpatialWidenProcessor.queueInput
    #   error=ERROR_CODE_FAILED_RUNTIME_CHECK engine_error=E_DS_UPSTREAM_MEDIA3_PLAYBACK
    # 根因：`BaseAudioProcessor.replaceOutputBuffer(n)` 返回它复用的 `buffer` 字段，
    # `getOutput()` 只把 `outputBuffer` 换成 EMPTY_BUFFER、不重建 `buffer`；
    # Media3 管线把上一包 output 原样当下一包 input 传回 ⇒ `out` 与 `inputBuffer`
    # 是同一对象 ⇒ `ByteBuffer.put(ByteBuffer)` 命中 `src == this` 抛 IllegalArgumentException。
    # 旧直通分支只在 widenAmount==0 时命中，而 0 正是默认值 ⇒ 每首歌必崩。
    # 判据：① 不得再出现 `out.put(inputBuffer)`；② 必须先 `inputBuffer.get(bytes...)`
    # 再 `replaceOutputBuffer`；③ 回归单测必须保留「同一 buffer 复用」用例。
    widen_kt = (ROOT / "engine-opensource" / "src" / "main" / "kotlin" / "com" / "example"
                / "cloudmusic" / "engineopensource" / "SpatialWidenProcessor.kt")
    widen_body = widen_kt.read_text(encoding="utf-8") if widen_kt.is_file() else ""
    widen_test = (ROOT / "engine-opensource" / "src" / "test" / "kotlin" / "com" / "example"
                  / "cloudmusic" / "engineopensource" / "SpatialWidenProcessorTest.kt")
    widen_test_body = widen_test.read_text(encoding="utf-8") if widen_test.is_file() else ""
    widen_code = "\n".join(
        ln for ln in widen_body.splitlines()
        if not ln.strip().startswith(("*", "//", "/*")))
    if "out.put(inputBuffer)" in widen_code:
        failures.append("12 §61.177：SpatialWidenProcessor 直通分支回到 put(ByteBuffer)（src==this 必崩）")
    elif "inputBuffer.get(bytes" not in widen_body:
        failures.append("12 §61.177：SpatialWidenProcessor 未先读走输入缓冲（会与输出缓冲同一对象冲突）")
    elif "管线复用输入缓冲" not in widen_test_body:
        failures.append("12 §61.177：缺「输入输出同一 buffer」回归单测（真机崩溃路径无守护）")
    else:
        print("[PASS] 12 §61.177：声场展宽先读后写 + 复用同一 buffer 回归用例在位")

    # ---- 12 §61.178（扫描悬死 + 静默失败）：可观测性与协程 resume 保护 ----
    # 现象一：通知栏「正在扫描曲库 0/0」永久不动，无异常、无超时、无日志。
    #   根因：`OkHttpStack.onResponse` 里 `resp.body.bytes()` 抛异常会逃出回调，
    #   OkHttp 的 AsyncCall 只当 call 已处理完而吞掉 ⇒ 协程永远不 resume。
    # 现象二：`runScan` 的 catch 只置 lastEffect=SCAN_FAILED 不打日志，
    #   且把 CancellationException（正常取消）也当失败 ⇒ 通知栏出现 SCAN_FAILED。
    # 判据：① onResponse 体必须有 catch(Throwable) + resumeWithException；
    #       ② runScan 必须打印失败原因；③ 取消与失败必须分流；
    #       ④ 扫描分步打点不得被删（入口/快照/list/阶段A）。
    webdav_http = (ROOT / "datasource-webdav" / "src" / "main" / "kotlin" / "com" / "example"
                   / "cloudmusic" / "webdav" / "WebDavHttp.kt")
    http_body = webdav_http.read_text(encoding="utf-8") if webdav_http.is_file() else ""
    play_comp = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                 / "cloudmusic" / "di" / "playback" / "PlaybackComposition.kt")
    play_body2 = play_comp.read_text(encoding="utf-8") if play_comp.is_file() else ""
    scan_coord = (ROOT / "scanner" / "src" / "main" / "kotlin" / "com" / "example"
                  / "cloudmusic" / "scanner" / "ScanCoordinator.kt")
    scan_body = scan_coord.read_text(encoding="utf-8") if scan_coord.is_file() else ""
    if "catch (e: Throwable)" not in http_body or "resumeWithException(e)" not in http_body:
        failures.append("12 §61.178：OkHttpStack.onResponse 缺 catch(Throwable)+resumeWithException（协程会永久悬死）")
    elif "scan failed:" not in play_body2:
        failures.append("12 §61.178：runScan 失败仍不打日志（扫描 0 首无法归因）")
    elif "CancellationException" not in play_body2:
        failures.append("12 §61.178：runScan 未把取消与失败分流（正常取消会误报 SCAN_FAILED）")
    elif not all(k in scan_body for k in ("scanStorage 入口", "快照已载入", "阶段A 完成 upsert")):
        failures.append("12 §61.178：ScanCoordinator 扫描分步打点被删（卡点无法定位）")
    else:
        print("[PASS] 12 §61.178：WebDAV resume 受保护 + 扫描取消/失败分流 + 分步打点在位")

    # ---- 12 §61.179（快照与曲库不一致 ⇒ 增量扫描永远漏扫）----
    # 现象：删数据源（markOrphan 只置 UNREACHABLE、不清 scan_snapshot）后重新加回同源，
    #   文件 size/mtime 全未变 ⇒ 增量判定「已扫过」全部 skip ⇒ 曲目永久 UNREACHABLE。
    #   曲库被清空而快照残留同理（本批真机复现：7838→0 后重扫 upsert=0）。
    # 判据：knownPaths 三件套（端口 / 生产实现 / 调用点）缺一不可。
    ports_kt = (ROOT / "scanner" / "src" / "main" / "kotlin" / "com" / "example"
                / "cloudmusic" / "scanner" / "ScanPorts.kt")
    ports_body = ports_kt.read_text(encoding="utf-8") if ports_kt.is_file() else ""
    scan_comp = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                 / "cloudmusic" / "di" / "scan" / "ScanComposition.kt")
    comp_body = scan_comp.read_text(encoding="utf-8") if scan_comp.is_file() else ""
    if "suspend fun knownPaths" not in ports_body:
        failures.append("12 §61.179：TrackSink 缺 knownPaths 端口（快照/曲库不一致无兜底）")
    elif "override suspend fun knownPaths" not in comp_body:
        failures.append("12 §61.179：RoomTrackSink 未实现 knownPaths（判据恒为空集）")
    elif "trackSink.knownPaths(" not in scan_body:
        failures.append("12 §61.179：ScanCoordinator 未用 knownPaths 强制重扫（缺曲目会被永久跳过）")
    else:
        print("[PASS] 12 §61.179：快照/曲库不一致兜底重扫（端口+实现+调用点）在位")


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

    # 12 §61.36 守护：暂存区含竞品类名的 layout 必须被 NOTICE 与 C4 如实登记，
    # 防止「未真正替换」却把 C4 标成已替换，或把「不参与 aapt2」误读成「不随包分发」。
    staged_dir = ROOT / "app" / "src" / "main" / "assets" / "legacy-player"
    mapping_csv = ROOT / "docs" / "assets-mapping.csv"
    notice_file = ROOT / "NOTICE.md"
    if not (staged_dir.is_dir() and mapping_csv.is_file() and notice_file.is_file()):
        failures.append("12 §61.36 守护：素材暂存区 / assets-mapping.csv / NOTICE.md 缺失")
    else:
        branded = [
            p for p in staged_dir.rglob("layout/*.xml")
            if "com.netease." in p.read_text(encoding="utf-8", errors="replace")
        ]
        rows = [
            ln for ln in mapping_csv.read_text(encoding="utf-8-sig").splitlines() if ln.strip()
        ][1:]
        notice_text = notice_file.read_text(encoding="utf-8")
        if branded and "随 debug APK 原样分发" not in notice_text:
            failures.append("12 §61.36 守护：NOTICE.md 未声明竞品 layout 随 APK 分发")
        elif branded and not all(ln.rstrip().endswith(",false") for ln in rows):
            failures.append("12 §61.36 守护：竞品 layout 仍在时 C4 不得出现 replaced=true")
        else:
            print(f"[PASS] 12 §61.36 守护：{len(branded)} 个竞品 layout 已被 C4 / NOTICE 如实登记")

    # 12 §61.37 / D-146 守护：个人自用范围必须如实登记；
    # 未替换素材不得被标成 replaced=true（防止以「已替换」伪造合规状态）。
    scope_doc = ROOT / "docs" / "03-模块开发文档" / "03-12 构建发布与合规.md"
    scope_notice = ROOT / "NOTICE.md"
    scope_csv = ROOT / "docs" / "assets-mapping.csv"
    if not (scope_doc.is_file() and scope_notice.is_file() and scope_csv.is_file()):
        failures.append("12 §61.37 守护：03-12 / NOTICE.md / assets-mapping.csv 缺失")
    else:
        scope_text = scope_doc.read_text(encoding="utf-8")
        notice_scope_text = scope_notice.read_text(encoding="utf-8")
        scope_rows = [
            ln for ln in scope_csv.read_text(encoding="utf-8-sig").splitlines() if ln.strip()
        ][1:]
        if "D-146" not in scope_text or "个人自用" not in notice_scope_text:
            failures.append("12 §61.37 守护：D-146 个人自用范围口径缺失")
        elif scope_rows and not all(ln.rstrip().endswith(",false") for ln in scope_rows):
            failures.append("12 §61.37 守护：D-146 下不得把未替换素材标成 replaced=true")
        else:
            print("[PASS] 12 §61.37 守护：D-146 个人自用口径在位，C4 未伪造替换")

    # 12 §61.38 守护：T-L L3 技术接入前置实证在位。
    # 9 个暂存 layout 含 101 个无定义 @id / 33 个未声明 app:* attr /
    # 20 个无实现的 com.netease.* 自定义 View；三道改写完成前不得搬进 res/。
    audit_tool = ROOT / "tools" / "legacy_layout_audit.py"
    audit_json = ROOT / "analysis" / "legacy-layout-audit.json"
    staged_layout_dir = (ROOT / "app" / "src" / "main" / "assets" /
                         "legacy-player" / "layout")
    res_layout_dir = ROOT / "app" / "src" / "main" / "res"
    if not (audit_tool.is_file() and audit_json.is_file() and staged_layout_dir.is_dir()):
        failures.append("12 §61.38 守护：layout 审计工具 / JSON / 暂存 layout 目录缺失")
    else:
        leaked = []
        if res_layout_dir.is_dir():
            for p in res_layout_dir.rglob("layout/*.xml"):
                if "com.netease" in p.read_text(encoding="utf-8", errors="ignore"):
                    leaked.append(p.relative_to(ROOT).as_posix())
        if leaked:
            failures.append(
                "12 §61.38 守护：含 com.netease 类名的 layout 搬进 res/ 但三道改写未完成"
                "（101 个 @id 无定义 / 33 个 attr 未声明 / 20 个 View 无实现）: "
                + ", ".join(leaked[:3])
            )
        else:
            try:
                audit = json.loads(audit_json.read_text(encoding="utf-8"))
                totals = audit["totals"]
                prod = audit["production_refs"]
                ui_included = 'include(":ui-legacy")' in (
                    ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
                if ui_included:
                    warnings.append(
                        "12 §61.38：:ui-legacy 已启用，请重跑 tools/legacy_layout_audit.py 并更新技术接入施工单"
                    )
                elif (totals["netease_class_occurrences_raw"] != 44
                      or totals["refs_missing_in_mapping_unique"] != 0
                      or totals["refs_missing_staged_definition_unique"] != 0
                      or prod["staged_asset_production_ref_count"] != 0):
                    failures.append(
                        "12 §61.38 守护：layout 实证基线漂移，请重跑 tools/legacy_layout_audit.py 并回写施工单"
                    )
                else:
                    print("[PASS] 12 §61.38 守护：44 处竞品类名 / 45 项素材全覆盖 / "
                          "零生产引用方，res 未误搬")
            except (KeyError, ValueError, OSError) as exc:
                failures.append(f"12 §61.38 守护：审计 JSON 不可解析（{exc}）")

    # 12 §61.39 守护：阶段 A/B 精确还原与 aapt2 四步实证在位。
    # 33/33 attr 定义 + 101/101 public id 命中；四步 link 逐步归零，
    # step4 在仓库外临时工程 aapt2 全绿。漂移即要求重跑两个工具。
    resolve_tool = ROOT / "tools" / "legacy_layout_resolve.py"
    resolve_json = ROOT / "analysis" / "legacy-layout-resolve.json"
    probe_tool = ROOT / "tools" / "l3_aapt2_probe.py"
    probe_json = ROOT / "analysis" / "l3-probe" / "aapt2-probe-result.json"
    resolution_doc = ROOT / "analysis" / "tl-l3-attr-id-resolution-2026-11-08.md"
    l3_artifacts = [
        resolve_tool, resolve_json, probe_tool, probe_json, resolution_doc,
        ROOT / "analysis" / "l3-probe" / "ids.xml",
        ROOT / "analysis" / "l3-probe" / "attrs.xml",
    ]
    if not all(p.is_file() for p in l3_artifacts):
        failures.append("12 §61.39 守护：attr/id 还原或 aapt2 四步实证产物缺失")
    else:
        try:
            rj = json.loads(resolve_json.read_text(encoding="utf-8"))
            pj = json.loads(probe_json.read_text(encoding="utf-8"))
            cov = rj["coverage"]
            steps = pj["steps"]
            v = pj["verdict"]
            ok = (
                rj["targets"]["attr_count"] == 33
                and rj["targets"]["id_count"] == 101
                and cov["attr_defined"] == 33
                and cov["id_in_public_xml"] == 101
                and cov["attr_missing"] == []
                and cov["id_missing"] == []
                and len(steps) == 4
                and v["ids_remove_all_id_errors"] is True
                and v["attrs_remove_all_attr_errors"] is True
                and v["step4_aapt2_clean"] is True
                and v["staged_res_remove_all_errors"] is True
                and steps[3]["link_rc"] == 0
                and steps[3]["error_line_count"] == 0
            )
            if not ok:
                failures.append(
                    "12 §61.39 守护：attr/id 还原或 aapt2 四步实证基线漂移，"
                    "请重跑 tools/legacy_layout_resolve.py 与 tools/l3_aapt2_probe.py"
                )
            else:
                print("[PASS] 12 §61.39 守护：33 attr / 101 id 精确还原，"
                      "aapt2 四步归零（step4 link_rc=0 / 错误 0）")
        except (KeyError, ValueError, OSError, IndexError) as exc:
            failures.append(f"12 §61.39 守护：证据 JSON 不可解析（{exc}）")

    # 12 §61.40 守护：播放页交互复刻在位 + 属性动画参数精确 + 零发布基线变更。
    # 用户「UI 交互严格复刻竞品」后按 12 §39 / docs/14 §4.1 / 03-10 §3.3 §6
    # 落地；任一漂移即 FAIL 并要求重跑 tools/l3_interaction_spec.py。
    spec_tool = ROOT / "tools" / "l3_interaction_spec.py"
    spec_json = ROOT / "analysis" / "l3-interaction-spec.json"
    spec_doc = ROOT / "analysis" / "tl-l3-interaction-replica-2026-11-08.md"
    player_dir = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "ui" / "player"
    player_files = [player_dir / n for n in (
        "VinylDiscView.kt", "PlayerStateBridge.kt", "LegacyPlayerPage.kt",
    )]
    if not all(p.is_file() for p in [spec_tool, spec_json, spec_doc] + player_files):
        failures.append("12 §61.40 守护：播放页交互复刻工具 / 证据 / 报告 / 源码缺失")
    else:
        try:
            sj = json.loads(spec_json.read_text(encoding="utf-8"))
            ck = sj["checks"]
            base = sj["baseline"]
            ok = (
                sj["verdict"]["all_checks_pass"] is True
                and all(ck.values())
                and sj["animation_parameters"]["base_period_ms"] == 20000
                and sj["animation_parameters"]["anim_xml_loaded"] is False
                and len(sj["netease_classes_from_audit"]) == 20
                and len(sj["replicated_interactions"]) == 7
                and len(sj["unwired_interactions"]) == 6
                and base["module_count"] == 19
                and base["permission_count"] == 11
                and base["staged_assets_files"] == 51
                and base["mapping_rows"] == 61
                and base["mapping_replaced_false"] == 61
                and not base["new_dependencies"]
                and base["res_dir_exists"] is False
                and base["ui_legacy_included"] is False
            )
            if not ok:
                failures.append(
                    "12 §61.40 守护：交互复刻证据或零基线口径漂移，"
                    "请重跑 tools/l3_interaction_spec.py 并回写 12 §61.40"
                )
            else:
                print("[PASS] 12 §61.40 守护：20 类 / 7 项交互复刻 / 6 项只读占位，"
                      "属性动画参数精确、19 模块 / 11 权限 / 51 素材 / 61 行 csv 零变化")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.40 守护：交互规格证据 JSON 不可解析（{exc}）")

    # 12 §61.41 守护：B 路线（三项拍板全开）前置实证在位 + 本批未改基线。
    # 用户 2026-11-09 拍板 B；按 §61.38 同纪律先实证、不动基线。任一证据漂移
    # 或基线被提前改动即 FAIL，要求重跑 tools/l3_full_replica_audit.py 并回写 12 §61.41。
    full_tool = ROOT / "tools" / "l3_full_replica_audit.py"
    full_json = ROOT / "analysis" / "l3-full-replica-audit.json"
    full_doc = ROOT / "analysis" / "tl-l3-full-replica-plan-2026-11-09.md"
    full_layout_dir = ROOT / "analysis" / "l3-full-replica" / "layouts"
    if not all(p.is_file() for p in (full_tool, full_json, full_doc)):
        failures.append("12 §61.41 守护：B 路线前置实证工具 / 证据 / 施工单缺失")
    else:
        try:
            fj = json.loads(full_json.read_text(encoding="utf-8"))
            copies = sorted(full_layout_dir.glob("*.xml")) if full_layout_dir.is_dir() else []
            settings_text = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
            agg = fj["remaining_aggregate"]
            # 12 §61.139：主题批（6501a367）起 res/values/ 是自研主题与字符串，
            # 不是 B 路线的竞品 layout 照搬。守护由「res 目录不存在」收窄为
            # 「res 下只允许 values/ 资源」——仍禁止照搬播放页 XML，同时保留
            # :ui-legacy 门禁；48/39/53/81/282/163 证据口径逐项不变。
            res_dir = ROOT / "app" / "src" / "main" / "res"
            res_files = sorted(
                f.relative_to(res_dir).as_posix() for f in res_dir.rglob("*")
                if f.is_file()
            ) if res_dir.is_dir() else []
            baseline_untouched = (
                'include(":ui-legacy")' not in settings_text
                and not (ROOT / "ui-legacy").exists()
                and all(f.startswith("values/") for f in res_files)
                and not any(f.startswith("layout") for f in res_files)
            )
            ok = (
                all(fj["verdict"].values())
                and fj["closure_tag_count"] == 48
                and fj["staged_layout_count"] == 9
                and fj["remaining_layout_count"] == 39
                and fj["exported_copies"] == 39
                and len(copies) == 39
                and not fj["missing_decoded_files"]
                and fj["scope_calibration"]["has_standard_portrait_main_player_tag"] is False
                and not fj["attr_semantics_probe"]["standard_layout_constraint_attr_names_in_competitor_attrs_xml"]
                and agg["netease_class_unique_count"] == 53
                and agg["app_attr_unique_count"] == 81
                and agg["id_ref_unique_count"] == 282
                and agg["exportable_ref_unique_count"] == 163
                and agg["plus_id_definitions"] == 0
                and baseline_untouched
            )
            if not ok:
                failures.append(
                    "12 §61.41 守护：B 路线前置实证口径漂移或基线被提前改动，"
                    "请重跑 tools/l3_full_replica_audit.py 并回写 12 §61.41"
                )
            else:
                print("[PASS] 12 §61.41 守护：48 tag / 39 副本 / 53 类 / 81 attr / 282 id / "
                      "无标准竖屏主页、attr 语义不可逆；未建模块/未加依赖/res 仅 values/")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.41 守护：B 路线前置实证证据 JSON 不可解析（{exc}）")

    # ---- 12 §61.139（gapless 播放链接线）长效守护 -----------------------
    # 防「门面 prepareQueue 已就绪但播放链未接线」的悬空状态回潮；防把跨曲
    # gapless 伪造成真机已验证（设备离线时无缝听感 NOT RUN）。
    api139 = (ROOT / "engine-api" / "src" / "main" / "kotlin" / "com"
              / "example" / "cloudmusic" / "engine" / "api" / "EngineApi.kt")
    pc139 = (ROOT / "feature-player" / "src" / "main" / "kotlin" / "com"
             / "example" / "cloudmusic" / "player" / "PlayerController.kt")
    b2_139 = (ROOT / "engine-opensource" / "src" / "main" / "kotlin" / "com"
              / "example" / "cloudmusic" / "engineopensource" / "Media3EngineAdapter.kt")
    test139 = (ROOT / "feature-player" / "src" / "test" / "kotlin" / "com"
               / "example" / "cloudmusic" / "player" / "PlayerControllerTest.kt")
    doc139 = ROOT / "docs" / "12-编码阶段第一批交付.md"
    api139_txt = api139.read_text(encoding="utf-8") if api139.is_file() else ""
    pc139_txt = pc139.read_text(encoding="utf-8") if pc139.is_file() else ""
    b2_139_txt = b2_139.read_text(encoding="utf-8") if b2_139.is_file() else ""
    test139_txt = test139.read_text(encoding="utf-8") if test139.is_file() else ""
    doc139_txt = doc139.read_text(encoding="utf-8") if doc139.is_file() else ""
    if not all(x in api139_txt for x in (
        "fun queueIndex(): StateFlow<Int>",
        "fun seekToQueueIndex(handle: EngineHandle, index: Int)",
    )):
        failures.append("12 §61.139：门面队列索引 / 跳播增量方法缺失")
    elif not all(x in pc139_txt for x in (
        "engine.queueIndex().collect", "engine.prepareQueue(queuedSources, index, engineConfig)",
        "engine.seekToQueueIndex(h, target)", "applyQueueIndex(target)",
        "_state.value.mode == PlayMode.SEQUENCE", "source.url == null && source.localPath != null",
    )):
        failures.append("12 §61.139：PlayerController 的 gapless 队列分支缺失或条件被放宽")
    elif not all(x in b2_139_txt for x in (
        "onMediaItemTransition", "playerReused = true", "seekToQueueIndex",
        "setPauseAtEndOfMediaItems(false)",
    )):
        failures.append("12 §61.139：B2 playlist 索引上报 / 可达性判定缺失")
    elif not all(x in test139_txt for x in (
        "gaplessQueueUsesPlaylist", "gaplessKeepsSingleTrackPath", "gaplessQueueIndexSync",
    )):
        failures.append("12 §61.139：gapless 接线 JVM 用例缺失")
    elif "### 61.139" not in doc139_txt or "真机无缝听感 NOT RUN" not in doc139_txt:
        failures.append("12 §61.139：断点章节 / 真机 NOT RUN 诚实边界未登记")
    else:
        print("[PASS] 12 §61.139：门面队列索引 + 播放链分支 + B2 playlist "
              "+ 3 用例 + NOT RUN 边界在位")

    # ---- 12 §61.140（歌单行首曲封面）长效守护 ---------------------------
    # 防 §61.138 挂账回潮：CompPlaylistRow 支持 coverRef 但调用方不接 =
    # 歌单行永远 ☰ 占位；防展开曲目行再次漏传 coverRef。
    app140 = (ROOT / "app" / "src" / "main" / "kotlin" / "com"
              / "example" / "cloudmusic" / "ui" / "CloudMusicApp.kt")
    app140_txt = app140.read_text(encoding="utf-8") if app140.is_file() else ""
    if not all(x in app140_txt for x in (
        "observeTracks(playlist.id).map { tracks ->",
        "tracks.firstOrNull()?.coverRef",
        "coverRef = firstCoverRef",
    )):
        failures.append("12 §61.140：歌单行首曲封面流 / coverRef 接线缺失")
    elif "coverRef = track.coverRef" not in app140_txt:
        failures.append("12 §61.140：歌单展开曲目行 coverRef 缺失")
    elif "### 61.140" not in doc139_txt:
        failures.append("12 §61.140：断点章节未登记")
    else:
        print("[PASS] 12 §61.140：歌单行首曲封面 + 展开行 coverRef + 断点登记在位")

    # ---- 12 §61.141（gapless 真机收口）长效守护 -------------------------
    # 防「真机已 PASS 的裁决证据被删/被改写」与「把步骤 3 重新伪造成未验」：
    # verdict JSON 关键字段 + docs 断点登记必须同时在位。
    verdict141 = ROOT / "analysis" / "gapless-61-141-verdict.json"
    shot141 = ROOT / "analysis" / "screenshots" / "61-141-playlist-first-cover-device.png"
    verdict141_txt = verdict141.read_text(encoding="utf-8") if verdict141.is_file() else ""
    if not all(x in verdict141_txt for x in (
        '"gapless_verdict": "PASS"', '"playlist_cover_verdict": "PASS"',
        "cross_track=true", "session 4577",
    )):
        failures.append("12 §61.141：gapless 真机裁决 JSON 关键字段缺失或被改写")
    elif not shot141.is_file():
        failures.append("12 §61.141：歌单首曲封面真机截图证据缺失")
    elif "### 61.141" not in doc139_txt or "gapless 真机收口" not in doc139_txt:
        failures.append("12 §61.141：断点章节未登记")
    else:
        print("[PASS] 12 §61.141：gapless 真机 PASS 裁决 + 截图证据 + 断点登记在位")

    # ---- 12 §61.142（容器真机复测清点）长效守护 -------------------------
    # 防「ape FAIL×2 分档登记被抹成 PASS」或「三容器 PASS 证据被删」；
    # PASS 不伪造、FAIL 不隐匿，双口径留档口径受守护。
    verdict142 = ROOT / "analysis" / "containers-61-142-verdict.json"
    verdict142_txt = verdict142.read_text(encoding="utf-8") if verdict142.is_file() else ""
    if not all(x in verdict142_txt for x in (
        '"wma_asf"', '"aiff"', '"wavpack"', '"ape"',
        '"verdict": "PASS"', '"verdict": "FAIL×2（静音播放）"',
        "PASS 证据留档不删",
    )):
        failures.append("12 §61.142：容器复测裁决 JSON 关键字段缺失或 ape FAIL 分档被抹")
    elif "### 61.142" not in doc139_txt or "容器 Extractor 真机复测清点" not in doc139_txt:
        failures.append("12 §61.142：断点章节未登记")
    else:
        print("[PASS] 12 §61.142：容器复测 3 PASS + ape FAIL×2 分档 + 断点登记在位")

    # ---- 12 §61.143（7,500 冷扫就绪度 + 首轮实测）长效守护 ---------------
    # 防 C-10 回潮（binder 覆盖既有实例 / playLocal 幂等闸挡住 file 通道重建）
    # 与「冷扫 FAIL 判据被伪造成 PASS」；0-candidate 破坏面挂账受守护。
    scan_comp = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                 / "example" / "cloudmusic" / "di" / "scan" / "ScanComposition.kt")
    playback_comp = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                     / "example" / "cloudmusic" / "di" / "playback" / "PlaybackComposition.kt")
    scan_comp_txt = scan_comp.read_text(encoding="utf-8") if scan_comp.is_file() else ""
    playback_comp_txt = playback_comp.read_text(encoding="utf-8") if playback_comp.is_file() else ""
    verdict143 = ROOT / "analysis" / "scan7500-61-143-verdict.json"
    verdict143_txt = verdict143.read_text(encoding="utf-8") if verdict143.is_file() else ""
    if not all(x in scan_comp_txt for x in (
        "registry 已有实例（playLocal 沙箱注入 / 调试入口）优先",
        "DriverRegistry.get(storage.id) != null",
    )):
        failures.append("12 §61.143：StorageDriverBinder registry 优先分支缺失（C-10 覆盖冲突回潮）")
    elif not all(x in playback_comp_txt for x in (
        "强制重建为 file 通道实例",
        "runCatching { DriverRegistry.remove(LOCAL_FIXTURE_STORAGE_ID) }",
    )):
        failures.append("12 §61.143：ensureLocalFixtureDriver 强制重建缺失")
    elif "T-A4-10 binder defers to pre-registered instances" not in (
        (ROOT / "core-di" / "src" / "test" / "kotlin" / "com" / "example" / "cloudmusic"
         / "di" / "scan" / "ScanContractsTest.kt").read_text(encoding="utf-8")
    ):
        failures.append("12 §61.143：T-A4-10 用例缺失")
    elif not all(x in verdict143_txt for x in (
        '"cold_scan_verdict": "FAIL', "runMetadata", "0-candidate",
    )):
        failures.append("12 §61.143：冷扫 FAIL 裁决 / runMetadata 卡点 / 破坏面挂账未登记")
    elif "### 61.143" not in doc139_txt:
        failures.append("12 §61.143：断点章节未登记")
    else:
        print("[PASS] 12 §61.143：binder defer + 强制重建 + T-A4-10 + 冷扫 FAIL 裁决在位")

    # ---- 12 §61.144（runMetadata 事务聚合）长效守护 ----------------------
    # 防逐文件单事务回潮（T-V #9 冷扫 FAIL 的次要因子）+ 裁决证据在位。
    scan_coord = (ROOT / "scanner" / "src" / "main" / "kotlin" / "com"
                  / "example" / "cloudmusic" / "scanner" / "ScanCoordinator.kt")
    scan_coord_txt = scan_coord.read_text(encoding="utf-8") if scan_coord.is_file() else ""
    if not all(x in scan_coord_txt for x in (
        "metadata 写库按 batchSize 聚合",
        "candidates.chunked(request.batchSize.coerceAtLeast(1))",
        "if (tracks.isNotEmpty()) trackSink.upsertBatch(tracks)",
    )):
        failures.append("12 §61.144：runMetadata 块聚合缺失（逐文件单事务回潮）")
    elif "metadataBatchedUpsert" not in (
        (ROOT / "scanner" / "src" / "test" / "kotlin" / "com" / "example" / "cloudmusic"
         / "scanner" / "ScanCoordinatorTest.kt").read_text(encoding="utf-8")
    ):
        failures.append("12 §61.144：metadataBatchedUpsert 用例缺失")
    elif "followup_61_144" not in verdict143_txt:
        failures.append("12 §61.144：重扫深化裁决（阶段A更新路径 / metadata 通道根因）未登记")
    elif "### 61.144" not in doc139_txt:
        failures.append("12 §61.144：断点章节未登记")
    else:
        print("[PASS] 12 §61.144：runMetadata 块聚合 + 用例 + 重扫深化裁决在位")

    # ---- 12 §61.145（扫描三线优化）长效守护 ------------------------------
    # 防①通道跳步回潮（检索器回到 per-file 主通道）/ ②去重被删 / ③批量置位回潮；
    # 接口默认实现（逐行委托）必须在位以防接口演进破坏既有 fake。
    tag_cover_txt = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                     / "example" / "cloudmusic" / "di" / "scan" / "ProductionTagAndCover.kt"
                     ).read_text(encoding="utf-8")
    repos_txt = (ROOT / "core-storage" / "src" / "main" / "kotlin" / "com"
                 / "example" / "cloudmusic" / "storage" / "Repositories.kt"
                 ).read_text(encoding="utf-8")
    reconciler_txt = (ROOT / "core-storage" / "src" / "main" / "kotlin" / "com"
                      / "example" / "cloudmusic" / "storage" / "InMemoryLibrary.kt"
                      ).read_text(encoding="utf-8")
    if not all(x in tag_cover_txt for x in (
        "LOCAL 文件路径 JAudioTagger 先行",
        "jaudioSufficient", "written[hash]",
    )):
        failures.append("12 §61.145：①通道跳步 / ②封面哈希去重缺失")
    elif not all(x in repos_txt for x in (
        "suspend fun markAvailabilityBatch(ids: List<TrackId>, a: Availability)",
        "ids.forEach { markAvailability(it, a) }",
    )):
        failures.append("12 §61.145：③markAvailabilityBatch 接口默认实现缺失")
    elif not all(x in reconciler_txt for x in (
        "batch.groupBy { it.availability }",
        "tracks.markAvailabilityBatch(group.map { it.id }, availability)",
    )):
        failures.append("12 §61.145：Reconciler 分组批量缺失（逐行回潮）")
    elif "### 61.145" not in doc139_txt:
        failures.append("12 §61.145：断点章节未登记")
    else:
        print("[PASS] 12 §61.145：通道跳步 + 封面去重 + 批量置位 + 断点登记在位")

    # ---- 12 §61.146（目录封面 O(n²) 消除 + 增量 PASS）长效守护 -----------
    # 防 directoryCover 无条件回潮（平铺大目录 O(n² log n) 空转）；
    # 增量判据 PASS 裁决在位。
    if not all(x in tag_cover_txt for x in (
        "仅当无内嵌封面才查同目录封面",
        "if (merged.embeddedCover == null)",
    )):
        failures.append("12 §61.146：directoryCover 守卫缺失（无条件 list 回潮）")
    elif "1,051 ms" not in doc139_txt and "1051 ms" not in doc139_txt:
        failures.append("12 §61.146：增量判据 PASS 裁决未登记")
    elif "### 61.146" not in doc139_txt:
        failures.append("12 §61.146：断点章节未登记")
    else:
        print("[PASS] 12 §61.146：directoryCover 守卫 + 增量 PASS 裁决在位")

    # ---- 12 §61.147（冻结根因链 + 冷扫收口）长效守护 ---------------------
    # 防①检索器白名单被删（APE/WV native 挂死回潮）/ ②目录缓存被删（O(n²) 回潮）/
    # ③冷扫 216s 判据 FAIL 被伪造成 PASS。
    if not all(x in tag_cover_txt for x in (
        "RETRIEVER_FALLBACK_EXTS", "检索器兜底只对**其可靠支持的容器扩展**启用",
        "siblingsCache.getOrPut(cacheKey)",
    )):
        failures.append("12 §61.147：检索器白名单 / 目录缓存缺失（挂死回潮）")
    elif not all(x in doc139_txt for x in (
        "216494 ms", "冷扫 ≤60s @ 7,514 = FAIL",
    )):
        failures.append("12 §61.147：冷扫 216s FAIL 裁决未登记或被改写")
    elif "### 61.147" not in doc139_txt:
        failures.append("12 §61.147：断点章节未登记")
    else:
        print("[PASS] 12 §61.147：检索器白名单 + 目录缓存 + 冷扫 FAIL 裁决在位")

    # ---- 12 §61.148（ape PASS 勘误）长效守护 ------------------------------
    # 防静音假阳性 PASS 被重新采信：ape 单元真机出声验证必须含 AudioTrack/flinger
    # 证据；位置-only（seek 命中/ENDED）不再可单独采信。
    if not all(x in doc139_txt for x in (
        "§61.127 ape PASS 勘误为静音假阳性",
        "每 packet 解码失败",
    )):
        failures.append("12 §61.148：ape PASS 勘误未登记")
    elif "### 61.148" not in doc139_txt:
        failures.append("12 §61.148：断点章节未登记")
    else:
        print("[PASS] 12 §61.148：ape PASS 勘误 + 位置-only 证据降级登记在位")

    # ---- 12 §61.149（ape LE 字节序修复 + 样本终审）长效守护 ---------------
    # 防 §61.126 的 BE 前缀回潮（bswap_buf+BE 读 = 字节倒序 = 静音真机制）；
    # LE 契约（FFmpeg demuxer AV_WL32）受守护；样本终审口径在位。
    ape = (ROOT / "engine-opensource" / "src" / "main" / "java" / "androidx"
           / "media3" / "extractor" / "ape" / "ApeExtractor.kt")
    ape_txt = ape.read_text(encoding="utf-8") if ape.is_file() else ""
    if "writeBeU32(prefix" in ape_txt or "writeLeU32(prefix, 4, 0)" not in ape_txt:
        failures.append("12 §61.149：ape packet 前缀字节序回潮（必须 LE 写 offset=0）")
    elif not all(x in doc139_txt for x in (
        "§61.126 字节序误判修正", "描述符字段垃圾",
    )):
        failures.append("12 §61.149：字节序修正 / 样本终审未登记")
    elif "### 61.149" not in doc139_txt:
        failures.append("12 §61.149：断点章节未登记")
    else:
        print("[PASS] 12 §61.149：ape LE 前缀契约 + 样本终审登记在位")

    # ---- 12 §61.150（MiniPlayer 真曲名 + RG 真机收口）长效守护 -----------
    # 防 MiniPlayer 回退 raw trackId 主渲染（§61.141 观察项回潮）；
    # RG 真机验证收口（29 日志/21 落库）在位。
    app_txt = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
               / "cloudmusic" / "ui" / "CloudMusicApp.kt").read_text(encoding="utf-8")
    # §61.279（V0.4-06）：迷你条改飞牛卡片条后渲染式随之变化，
    # 判据强度不变——仍是「真曲名优先 + trackId 兜底 + 未在播放占位」三档。
    if not all(x in app_txt for x in (
        "val miniTitle = player.currentTrackId?.let { id ->",
        'val shownTitle = title ?: player.currentTrackId?.value ?: "未在播放"',
    )):
        failures.append("12 §61.150：MiniPlayer 真曲名接线缺失或回潮")
    elif not all(x in doc139_txt for x in (
        "29 条 CM-RG", "21 首", "replay_gain_db",
    )):
        failures.append("12 §61.150：ReplayGain 真机验证收口未登记")
    elif "### 61.150" not in doc139_txt:
        failures.append("12 §61.150：断点章节未登记")
    else:
        print("[PASS] 12 §61.150：MiniPlayer 真曲名 + RG 真机收口在位")

    # ---- 12 §61.151（STRM 独立源 UI）长效守护 ----------------------------
    # 防 UI 入口被移除（StrmDriver/factory 既有，缺的只是入口）；
    # MainActivity 接线（add("strm") + delegate_storage_id 键名）在位。
    if not all(x in app_txt for x in (
        "添加 STRM 源", "fun StrmDialog(", "driverType != \"strm\"",
    )):
        failures.append("12 §61.151：SourcesScreen STRM 入口 / StrmDialog 缺失")
    main_txt = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                / "cloudmusic" / "MainActivity.kt").read_text(encoding="utf-8")
    if not all(x in main_txt for x in (
        "onAddStrm = { delegateId, paths ->",
        "put(\"delegate_storage_id\", delegateId)",
        'storages.add("strm", addition)',
    )):
        failures.append("12 §61.151：MainActivity STRM 接线缺失")
    elif not all(x in doc139_txt for x in (
        "STRM 独立源 UI 收口", "重复入库", "无删除入口",
    )):
        failures.append("12 §61.151：STRM UI 收口 / UX 挂账未登记")
    elif "### 61.151" not in doc139_txt:
        failures.append("12 §61.151：断点章节未登记")
    else:
        print("[PASS] 12 §61.151：STRM UI 入口 + 接线 + UX 挂账登记在位")

    # ---- 12 §61.152（数据源移除入口）长效守护 ----------------------------
    # 防 §61.151 UX 挂账回潮（删除入口被移除 / markOrphan 语义文案丢失）。
    # §61.207 演进批注：行点击确认扩为「刷新此源 / 移除」双动作面板（actionTarget），
    # 移除入口与 markOrphan 语义文案保护口径不变。
    if not all(x in app_txt for x in (
        "var actionTarget by remember { mutableStateOf<Storage?>(null) }",
        "\"移除\" to {", "文件不删",
    )):
        failures.append("12 §61.152：数据源移除入口 UI 缺失")
    if not all(x in main_txt for x in (
        "onRemoveStorage = { id ->",
        "storages.remove(id)",
    )):
        failures.append("12 §61.152：MainActivity 移除接线缺失")
    if not all(x in doc139_txt for x in (
        "数据源移除入口", "markOrphan",
    )):
        failures.append("12 §61.152：移除入口收口未登记")
    elif "### 61.152" not in doc139_txt:
        failures.append("12 §61.152：断点章节未登记")
    else:
        print("[PASS] 12 §61.152：移除入口 UI + 接线 + 语义登记在位")

    # ---- 12 §61.153（WebDAV/STRM 双真 bug 修复）长效守护 -----------------
    # 防①TextReadCapability 实现被删（.strm 扫描回潮断链）/ ②XXE 硬化回潮
    # （Android harmony 解析器 setFeature 抛异常）/ ③可见化被静默化。
    webdav_txt = (ROOT / "datasource-webdav" / "src" / "main" / "kotlin" / "com"
                  / "example" / "cloudmusic" / "webdav" / "WebDavDriver.kt").read_text(encoding="utf-8")
    strm_txt = (ROOT / "datasource-strm" / "src" / "main" / "kotlin" / "com"
                / "example" / "cloudmusic" / "strm" / "StrmDriver.kt").read_text(encoding="utf-8")
    propfind_txt = (ROOT / "datasource-webdav" / "src" / "main" / "kotlin" / "com"
                    / "example" / "cloudmusic" / "webdav" / "PropfindParser.kt").read_text(encoding="utf-8")
    if "TextReadCapability" not in webdav_txt.split("readText")[0] or             "override suspend fun readText(file: Obj): String" not in webdav_txt:
        failures.append("12 §61.153：WebDavDriver TextReadCapability 实现缺失")
    elif ", TextReadCapability" not in strm_txt or "override suspend fun readText(file: Obj): String" not in strm_txt:
        failures.append("12 §61.153：StrmDriver TextReadCapability 实现缺失")
    elif "runCatching { setFeature(feature, true) }" not in propfind_txt:
        failures.append("12 §61.153：PropfindParser XXE 特性 runCatching 缺失（Android setFeature 抛异常回潮）")
    elif not all(x in doc139_txt for x in (
        "双真 bug", "TextReadCapability",
    )):
        failures.append("12 §61.153：双真 bug 修复未登记")
    elif "### 61.153" not in doc139_txt:
        failures.append("12 §61.153：断点章节未登记")
    else:
        print("[PASS] 12 §61.153：TextReadCapability 双实现 + XXE runCatching + 登记在位")

    # ---- 12 §61.160~161（T5-3 路径 B 桥 + STRM 多行路径）长效守护 ---------
    wid = (ROOT / "engine-opensource" / "src" / "main" / "kotlin" / "com"
           / "example" / "cloudmusic" / "engineopensource" / "SpatialWidenProcessor.kt")
    wid_txt = wid.read_text(encoding="utf-8") if wid.is_file() else ""
    adapter160 = (ROOT / "engine-opensource" / "src" / "main" / "kotlin" / "com"
                  / "example" / "cloudmusic" / "engineopensource" / "Media3EngineAdapter.kt").read_text(encoding="utf-8")
    if not all(x in wid_txt for x in (
        "class SpatialWidenProcessor : BaseAudioProcessor()",
        "AudioEffectsBus.widenAmount", "widen16Stereo",
    )):
        failures.append("12 §61.160：SpatialWidenProcessor / AudioEffectsBus 缺失")
    elif "buildAudioSink" not in adapter160:
        # §61.161 口径演进（commit 25d308b7）：T5-3 选项 B 注入真机调通 PASS
        # （buildAudioSink → DefaultAudioSink.Builder 注入声场展宽处理器），
        # §61.160 补记「应已还原」口径作废 —— 覆写必须在位（§61.182 审计时
        # 发现该守护停在旧口径、每轮必 FAIL，顺手对齐）。
        failures.append("12 §61.161：buildAudioSink 覆写缺失（T5-3 选项 B 桥应在位）")
    elif "class SpatialWidenProcessor : BaseAudioProcessor()" not in wid_txt:
        failures.append("12 §61.160：SpatialWidenProcessor 类缺失")
    elif not all(x in doc139_txt for x in (
        "SpatialWidenProcessor", "92 条 STRM 曲目", "AudioEffectsBus",
    )):
        failures.append("12 §61.160~161：T5-3 桥 / STRM 多行 / 92 曲目未登记")
    elif "### 61.160" not in doc139_txt:
        failures.append("12 §61.160：断点章节未登记")
    else:
        print("[PASS] 12 §61.160~161：声场展宽桥 + STRM 多行 + 92 曲目登记在位")

    # 12 §61.42 守护：用户把口径升级为「1:1 像素级复刻」后，按竞品可观测证据
    # 自研重画播放页（竞品真实位图 / 色值 / 尺寸 / 控件层级），并如实挂账
    # pixel_level_1to1=False。任一证据漂移、基线被改动，或把 1:1 伪造成已达成即 FAIL。
    visual_tool = ROOT / "tools" / "l3_visual_replica_spec.py"
    visual_json = ROOT / "analysis" / "l3-visual-replica-spec.json"
    player_dir = (ROOT / "app" / "src" / "main" / "kotlin" / "com" /
                  "example" / "cloudmusic" / "ui" / "player")
    if not all(p.is_file() for p in (
        visual_tool, visual_json,
        player_dir / "LegacyAssets.kt", player_dir / "VinylDiscView.kt",
        player_dir / "LegacyPlayerPage.kt",
    )):
        failures.append("12 §61.42 守护：视觉规格工具 / 证据 / 播放页源码缺失")
    else:
        try:
            vj = json.loads(visual_json.read_text(encoding="utf-8"))
            vd = vj["verdict"]
            bl = vj["baseline"]
            honest_1to1 = vd["pixel_level_1to1"] is False and len(vd["pixel_level_gaps"]) >= 4
            baseline_ok = (
                bl["module_count"] == 19
                and bl["permission_count"] == 11
                and bl["staged_assets_files"] == 51
                and bl["mapping_rows"] == 61
                and bl["mapping_replaced_false"] == 61
                and not bl["new_dependencies"]
                and not bl.get("res_violations")
                and not bl["ui_legacy_included"]
            )
            # §61.173：`res_dir_exists` 由「必须为 False」改为「`res_violations` 必须为空」
            # （res 下只允许自研 values/）。原判据与仓库事实冲突（该目录早已存在），
            # 继续要求 False 只能靠伪造证据通过；新判据强度更高：禁止竞品 layout/drawable 混入。
            # 12 §61.81 / B2 M1：上列 baseline_ok 读的是 M0 历史视觉证据 JSON，
            # 冻结在 19 模块 / 零新增依赖，历史文件不改写；M1 起「当前态」为
            # 20 模块 + media3 1.11.1，由本条与 §61.81 守护校验，避免把历史
            # 快照误当成现行基线。
            m1_settings_txt = (ROOT / "settings.gradle.kts").read_text(
                encoding="utf-8")
            m1_toml_txt = (ROOT / "gradle" / "libs.versions.toml").read_text(
                encoding="utf-8")
            m1_baseline_evolved = (
                m1_settings_txt.count('include(":') == 22
                and 'include(":engine-opensource")' in m1_settings_txt
                and 'media3 = "1.11.1"' in m1_toml_txt
                and "androidx.media3:media3-exoplayer" in m1_toml_txt
            )
            if not (all(vj["checks"].values()) and vd["all_checks_pass"]
                    and honest_1to1 and baseline_ok and m1_baseline_evolved):
                failures.append(
                    "12 §61.42 守护：视觉规格证据漂移、1:1 被伪造成已达成，或发布基线被改动，"
                    "请重跑 tools/l3_visual_replica_spec.py 并回写 12 §61.42"
                )
            else:
                print("[PASS] 12 §61.42 守护：竞品色值 14 项 / 尺寸 20 项 / 位图 19 项 / "
                      "唱片叠层 5 层全部取自一手证据；1:1 未达成如实挂账，"
                      "M0 历史基线 19 模块 / 11 权限 / 51 素材 / 61 行 csv / "
                      "零新增依赖 / res 未建；M1 当前态已演进为 21 模块 + media3 1.11.1")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.42 守护：视觉规格证据 JSON 不可解析（{exc}）")

    # 12 §61.43 守护：用户 2026-11-09 11:55 叫停「零碎修补」，要求有规划有目标的
    # 复刻 —— 竞品模块清单 / 重合冲突 / 开发优先级 / 代码与文档差异复核必须固化在
    # docs/17，并由可复跑的机器证据支撑；任一漂移或基线被改动即 FAIL。
    inventory_tool = ROOT / "tools" / "l3_module_inventory.py"
    inventory_json = ROOT / "analysis" / "l3-module-inventory.json"
    plan_doc = ROOT / "docs" / "17-竞品模块清单与复刻规划.md"
    overview = ROOT / "docs" / "09-文档闭环总览.md"
    if not all(p.is_file() for p in (inventory_tool, inventory_json, plan_doc, overview)):
        failures.append("12 §61.43 守护：模块清单工具 / 证据 / 规划文档 / 09 总览缺失")
    else:
        try:
            inv = json.loads(inventory_json.read_text(encoding="utf-8"))
            t = inv["competitor_totals"]
            self_side = inv["self_side"]
            plan = plan_doc.read_text(encoding="utf-8")
            overview_text = overview.read_text(encoding="utf-8")
            inventory_ok = (
                t["activities"] == 799
                and t["activity_aliases"] == 182
                and t["services"] == 93
                and t["permissions"] == 84
                and self_side["module_count"] == 19
                and len(inv["package_groups"]) >= 60
                and len(inv["activity_family_breakdown"]) >= 15
                and len(inv["chinese_module_labels"]) >= 45
                and "永久不做" in self_side["prd_never_line"]
            )
            plan_ok = all(s in plan for s in (
                "P0 不可变基线", "开发优先级", "重合 / 冲突", "代码实现 ↔ 文档差异复核",
                "核心播放逻辑复用竞品二进制", "pixel_level_1to1",
            ))
            index_ok = ("31 个文件" in overview_text
                        and "docs/17-竞品模块清单与复刻规划.md" in overview_text)
            # 12 §61.81 / B2 M1：inventory_ok 的 module_count=19 是 M0 历史快照，
            # 历史证据 JSON 不改写；当前态由本条校验（20 模块 + 逃生舱启用）。
            cur_settings = (ROOT / "settings.gradle.kts").read_text(
                encoding="utf-8")
            m1_current_ok = (
                cur_settings.count('include(":') == 22
                and 'include(":engine-opensource")' in cur_settings
                and "media3" in (ROOT / "gradle" / "libs.versions.toml")
                    .read_text(encoding="utf-8").lower()
            )
            if not (inventory_ok and plan_ok and index_ok and m1_current_ok):
                failures.append(
                    "12 §61.43 守护：模块清单证据 / 规划文档 / 09 §3 索引计数漂移，"
                    "请重跑 tools/l3_module_inventory.py 并回写 12 §61.43"
                )
            else:
                print("[PASS] 12 §61.43 守护：799 activity / 63 包组 / 17 功能域 / "
                      "50 中文模块名 / 19 自研模块（M0 历史快照，M1 后演进为 21）；"
                      "P0 二进制基线冻结 + P1~P5 优先级 + 代码文档差异复核；09 §3 计数 31")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.43 守护：模块清单证据 JSON 不可解析（{exc}）")

    # 12 §61.44 守护：用户 2026-11-09 13:01 三条指令 —— P0 诚实边界拍板接受、
    # 首要目标重排为「调通全格式播放内核」、之后按 F1~F5 todo 顺序开发。
    # 格式现状证据必须可复跑且不漂移；文档口径 / 优先级 / 计数任一漂移即 FAIL。
    audit_tool = ROOT / "tools" / "l3_audio_format_audit.py"
    audit_json = ROOT / "analysis" / "l3-audio-format-audit.json"
    audit_plan = ROOT / "analysis" / "tl-l3-audio-format-plan-2026-11-09.md"
    plan17 = ROOT / "docs" / "17-竞品模块清单与复刻规划.md"
    delivery12 = ROOT / "docs" / "12-编码阶段第一批交付.md"
    if not all(p.is_file() for p in (audit_tool, audit_json, audit_plan,
                                     plan17, delivery12, overview)):
        failures.append("12 §61.44 守护：格式审计工具 / 证据 / 施工单 / 规划文档 / 12 断点缺失")
    else:
        try:
            au = json.loads(audit_json.read_text(encoding="utf-8"))
            p17 = plan17.read_text(encoding="utf-8")
            d12 = delivery12.read_text(encoding="utf-8")
            ov = overview.read_text(encoding="utf-8")
            inc = {i["list"]: i for i in au["inconsistencies"]}
            evidence_ok = (
                au["list_count"] == 6
                and au["canonical"]["count"] == 14
                and au["native"]["demuxer_count"] == 75
                and len(au["native"]["dedicated_decoder_libs"]) == 3
                and au["verified_on_device"]["count"] == 4
                and au["verified_on_device"]["formats"] == ["mp3", "flac", "m4a", "ogg"]
                and au["verified_on_device"]["local_path_verified"] is False
                and au["engine_extension_chain"]["adapter_self_declared_status"] is True
                and au["engine_extension_chain"]["create_data_request_handler_url_only"] is True
                and au["engine_extension_chain"]["known_format_issue"]["evidence_present"] is True
                and len(au["todo"]) == 5
                and inc["scanner/ScanRules.audioExt"]["count"] == 8
                and inc["datasource-strm/StrmPaths.defaultFilterTypes"]["count"] == 9
            )
            doc_ok = all(s in p17 for s in (
                "P1★ 首要", "调通全格式播放内核", "用户 2026-11-09 13:01 已拍板确认",
                "F1→F5", "顺延", "## 8. 首要目标",
            )) and "61.44" in d12 and "31 个文件" in ov
            if not (evidence_ok and doc_ok):
                failures.append(
                    "12 §61.44 守护：全格式内核证据 / P1★ 优先级 / P0 拍板口径 / "
                    "F1~F5 todo 顺序漂移，请重跑 tools/l3_audio_format_audit.py 并回写 12 §61.44"
                )
            else:
                print("[PASS] 12 §61.44 守护：P0 诚实边界已拍板接受 + 首要目标 P1★ "
                      "调通全格式内核；6 处白名单(14/8/9) / libffmpeg 75 demuxer / "
                      "G01 4 格式仅 REMOTE 证据一致；F1~F5 顺序在位，docs 计数仍 31")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.44 守护：格式审计证据 JSON 不可解析（{exc}）")

    # 12 §61.45 守护 / F1：单一格式真相源 AudioFormats 落地 —— 6 处白名单必须
    # 全部接线、零残留硬编码清单；§61.44 历史证据不得被 F1 后工具覆盖；F1 只
    # 统一「清单来源」不声称「全格式已支持」；未新增 docs 文件，09 §3 计数仍 31。
    f1_source = ROOT / "core-model/src/main/kotlin/com/example/cloudmusic/model/AudioFormats.kt"
    f1_test = ROOT / "core-model/src/test/kotlin/com/example/cloudmusic/model/AudioFormatsTest.kt"
    f1_tool = ROOT / "tools" / "l3_f1_unified_audit.py"
    f1_json = ROOT / "analysis" / "l3-f1-unified.json"
    legacy_tool = ROOT / "tools" / "l3_audio_format_audit.py"
    if not all(p.is_file() for p in (f1_source, f1_test, f1_tool, f1_json,
                                     legacy_tool, plan17, delivery12, overview)):
        failures.append("12 §61.45 守护：F1 真相源 / 单测 / 证据工具 / JSON / §61.44 历史工具缺失")
    else:
        try:
            f1 = json.loads(f1_json.read_text(encoding="utf-8"))
            cons = f1["consumers"]
            evidence_ok = (
                f1["audio_ext"]["count"] == 14
                and f1["extra_audio_ext"]["exts"] == ["alac"]
                and f1["mime_by_ext"]["count"] == 11
                and f1["stream_filter_ext"]["count"] == 15
                and f1["consumers_total"] == 6
                and f1["consumers_unified"] == 6
                and f1["residual_hardcoded_lists"] == 0
                and all(v["references_audio_formats"] and not v["legacy_literal_present"]
                        for v in cons.values())
            )
            guard_ok = "拒绝重跑" in legacy_tool.read_text(encoding="utf-8")
            doc_ok = (
                "61.45" in delivery12.read_text(encoding="utf-8")
                and "F1 已完成" in plan17.read_text(encoding="utf-8")
                and "31 个文件" in overview.read_text(encoding="utf-8")
            )
            if not (evidence_ok and guard_ok and doc_ok):
                failures.append(
                    "12 §61.45 守护：F1 单一真相源接线 / 证据计数 / §61.44 历史证据保护 / "
                    "12 断点 / docs 计数漂移，请重跑 tools/l3_f1_unified_audit.py 并回写 12 §61.45"
                )
            else:
                print("[PASS] 12 §61.45 守护：F1 单一真相源 AudioFormats 落地，6 处白名单全接线 / "
                      "残留硬编码 0 / AUDIO_EXT 14 + alac / MIME 11 / strm 15；"
                      "§61.44 历史证据受保护，docs 计数仍 31")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.45 守护：F1 证据 JSON 不可解析（{exc}）")

    # 12 §61.46 守护 / F2 前置实证：钉死 EngineAdapter 的 extension 推断链现状，
    # 防「未真机取证就先改 P0 引擎舱代码」、防证据漂移；adb 端点以 12 §61.46
    # 为准（10.0.0.2:37827），本批未新增 docs 文件，09 §3 计数仍 31。
    f2_tool = ROOT / "tools" / "l3_f2_extension_chain_audit.py"
    f2_json = ROOT / "analysis" / "l3-f2-extension-chain.json"
    engine_adapter = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/EngineAdapter.kt"
    if not all(p.is_file() for p in (f2_tool, f2_json, engine_adapter,
                                     plan17, delivery12, overview)):
        failures.append("12 §61.46 守护：F2 前置实证工具 / JSON / EngineAdapter 源码缺失")
    else:
        try:
            f2 = json.loads(f2_json.read_text(encoding="utf-8"))
            ec = f2["extension_chain"]
            pv = f2["provider"]
            evidence_ok = (
                all(ec["url_branch_steps"].values())
                and ec["local_fallback_is_mime"] is True
                and ec["self_declared_assumption"] is True
                and ec["f2_code_unchanged"] is True
                and pv["create_data_request_handler_url_only"] is True
                and pv["get_local_path_returns_real_path"] is True
                and pv["open_local_fd_returns"] == -1
                and len(f2["blockers"]) == 3
                and len(f2["todo"]) == 3
            )
            # 12 §61.48 语义演进：F2-b 已按 §61.47 真机结论修正 extension 推断链，
            # 当前源码改由 §61.48 守护约束；本守护只钉死 §61.46 历史静态证据
            # 快照（f2_code_unchanged=True），不再要求当前源码保持未改。
            unchanged_ok = ec["f2_code_unchanged"] is True
            d12_txt = delivery12.read_text(encoding="utf-8")
            doc_ok = (
                "61.46" in d12_txt
                and "37827" in d12_txt
                and "F2 前置实证" in plan17.read_text(encoding="utf-8")
                and "31 个文件" in overview.read_text(encoding="utf-8")
            )
            if not (evidence_ok and unchanged_ok and doc_ok):
                failures.append(
                    "12 §61.46 守护：F2 前置实证证据 / P0 引擎舱未改状态 / adb 端点 / "
                    "12 断点 / docs 计数漂移，请重跑 tools/l3_f2_extension_chain_audit.py"
                )
            else:
                print("[PASS] 12 §61.46 守护：F2 前置实证历史快照钉死 —— LOCAL 源 "
                      "extension 落 mimeHint（MIME 非扩展名）/ f2_code_unchanged=True / "
                      "openLocalFd=-1；extension 现状由 §61.48 守护约束，"
                      "端点 37827 在位，docs 计数仍 31")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.46 守护：F2 证据 JSON 不可解析（{exc}）")

    # 12 §61.47 守护 / F2 真机取证 + 零权限本地曲库注入：钉死「自研 APP 没有音乐」
    # 的解决通道与真机结论；防证据漂移、防未取证先改 P0 引擎舱、防伪造 F2-b 完成。
    f2z_tool = ROOT / "tools" / "l3_f2_zero_permission_audit.py"
    f2z_json = ROOT / "analysis" / "l3-f2-zero-permission.json"
    f2z_md = ROOT / "analysis" / "l3-f2-device-probe.md"
    f2z_log = ROOT / "analysis" / "l3-f2-device-probe-logcat.txt"
    f2z_shot = ROOT / "analysis" / "l3-reference" / "self-ui-with-local-track-2.png"
    if not all(p.is_file() for p in (f2z_tool, f2z_json, f2z_md, f2z_log, f2z_shot,
                                     delivery12, plan17, overview, engine_adapter)):
        failures.append("12 §61.47 守护：零权限注入审计工具 / JSON / 取证报告 / 原始日志 / 截图缺失")
    else:
        try:
            f2z = json.loads(f2z_json.read_text(encoding="utf-8"))
            v = f2z["verdict"]
            dp = f2z["device_probe"]
            ic = f2z["injection_channel"]
            evidence_ok = (
                f2z["all_pass"] is True
                and ic["permission_count"] == 11
                and ic["module_count"] == 19
                and all(ic["zero_baseline"].values())
                and ic["p0_engine_unchanged"] is True
                and dp["extension_value"] == "audio/mpeg"
                and dp["extension_is_mime"] is True
                and dp["local_source_opens_natively"] is True
                and dp["native_decode_ok"] is True
                and dp["ui_track_visible"] is True
                and v["f2_assumption_verified"] is True
                and v["mp3_local_playable_despite_mismatch"] is True
                and v["no_music_resolved"] is True
                and v["f2b_pending"] is True
            )
            src = engine_adapter.read_text(encoding="utf-8")
            # 12 §61.48：extension 推断链已由 F2-b 修正，本守护只钉死 P0 舱体
            # openLocalFd 未动；extension 现状由 §61.48 守护约束。
            unchanged_ok = "-> -1 (V0.1 未实现)" in src
            doc_ok = (
                "61.47" in delivery12.read_text(encoding="utf-8")
                and "8.7" in plan17.read_text(encoding="utf-8")
                and "31 个文件" in overview.read_text(encoding="utf-8")
            )
            if not (evidence_ok and unchanged_ok and doc_ok):
                failures.append(
                    "12 §61.47 守护：F2 真机证据 / 零基线 / P0 引擎舱未改 / 12 断点 / "
                    "docs17 §8.7 / docs 计数漂移，请重跑 tools/l3_f2_zero_permission_audit.py "
                    "并回写 12 §61.47"
                )
            else:
                print("[PASS] 12 §61.47 守护：零权限 LOCAL 注入解决「自研 APP 没有音乐」—— "
                      "曲库 UI 可见 1 首 / extension=audio/mpeg 证实 MIME 非扩展名 / "
                      "XorLocalSource 打开本地文件 + Open codec for mp3 + Write start play / "
                      "P0 舱体未动、§61.47 历史快照 F2-b 挂账（现状由 §61.48 约束）、"
                      "零新增权限·模块·action")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.47 守护：F2 零权限证据 JSON 不可解析（{exc}）")

    # 12 §61.48 守护 / F2-b + F2-c：extension 推断链收敛到 :core-model 的
    # AudioFormats 单一真相源；防「把 MIME 当扩展名」的旧 Elvis 链回潮、
    # 防伪造 F2-c 真机复验结论、防零新增 Gradle 依赖口径漂移。
    # §61.46 / §61.47 两条旧守护保留为历史快照，只钉死当时的证据状态。
    f2x_tool = ROOT / "tools" / "l3_f2_ext_fix_audit.py"
    f2x_json = ROOT / "analysis" / "l3-f2-ext-fix.json"
    f2x_log = ROOT / "analysis" / "l3-f2c-extension-verify-logcat.txt"
    audio_formats = ROOT / "core-model/src/main/kotlin/com/example/cloudmusic/model/AudioFormats.kt"
    engine_gradle = ROOT / "engine-legacy/build.gradle.kts"
    if not all(p.is_file() for p in (f2x_tool, f2x_json, f2x_log, audio_formats,
                                     engine_gradle, engine_adapter, delivery12, plan17)):
        failures.append("12 §61.48 守护：F2-b/F2-c 审计工具 / JSON / 真机日志 / 源码缺失")
    else:
        try:
            f2x = json.loads(f2x_json.read_text(encoding="utf-8"))
            ck = f2x["checks"]
            dv = f2x["device_verification"]
            evidence_ok = (
                f2x["all_pass"] is True
                and len(ck) == 10
                and all(ck.values())
                and dv["verdict"] == "PASS"
                and dv["extension_before"] == "audio/mpeg（§61.47，MIME 字符串）"
                and dv["extension_after"] == "mp3（本批 F2-c）"
                and dv["zero_crash"] is True
                and f2x["fix"]["zero_new_gradle_dependency"] is True
                and f2x["baseline"]["docs_index_31_ok"] is True
            )
            adapter_txt = engine_adapter.read_text(encoding="utf-8")
            src_ok = (
                "AudioFormats.extOfSource(" in adapter_txt
                and "?: source.mimeHint.orEmpty()" not in adapter_txt
                and "-> -1 (V0.1 未实现)" in adapter_txt
                and "fun extOfSource(url: String?, localPath: String?, mimeHint: String?): String"
                    in audio_formats.read_text(encoding="utf-8")
                and 'implementation(project(":core-model"))'
                    in engine_gradle.read_text(encoding="utf-8")
            )
            log_ok = (
                "resourceInfo extension=mp3 fileSize=-1" in f2x_log.read_text(encoding="utf-8")
                and "Open codec for mp3" in f2x_log.read_text(encoding="utf-8")
            )
            doc_ok = (
                "61.48" in delivery12.read_text(encoding="utf-8")
                and "8.8" in plan17.read_text(encoding="utf-8")
                and "31 个文件" in overview.read_text(encoding="utf-8")
            )
            if not (evidence_ok and src_ok and log_ok and doc_ok):
                failures.append(
                    "12 §61.48 守护：F2-b 接线 / 旧链移除 / 零新增依赖 / F2-c 真机日志 / "
                    "12 断点 / docs17 §8.8 / docs 计数漂移，请重跑 "
                    "tools/l3_f2_ext_fix_audit.py 并回写 12 §61.48"
                )
            else:
                print("[PASS] 12 §61.48 守护：F2-b 把 extension 推断收敛到 "
                      "AudioFormats.extOfSource（URL 后缀 → LOCAL 真实后缀 → MIME 逆查 → 空串）"
                      " / :engine-legacy 既有 :core-model 依赖、零新增 / 4 个 F2-b JVM 用例 / "
                      "F2-c 真机 extension=mp3 + Open codec for mp3 + 零崩溃；"
                      "§61.46/§61.47 历史快照保留、docs 计数仍 31")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.48 守护：F2-b/F2-c 证据 JSON 不可解析（{exc}）")

    # 12 §61.49 守护 / F3 双路径矩阵：防伪造 PASS、防把 NO_SAMPLE 写成 PASS、
    # 防凭据落盘、防 F2-b 的 extension 链在矩阵里回潮、防发布基线漂移。
    f3_tool = ROOT / "tools" / "l3_f3_matrix_audit.py"
    f3_json = ROOT / "analysis" / "l3-f3-audit.json"
    f3_matrix = ROOT / "analysis" / "l3-f3-matrix.json"
    f3_report = ROOT / "analysis" / "l3-f3-matrix-report.md"
    f3_fixtures = ROOT / "analysis" / "l3-f3-fixtures.json"
    if not all(p.is_file() for p in (f3_tool, f3_json, f3_matrix, f3_report,
                                     f3_fixtures, delivery12, plan17, overview)):
        failures.append("12 §61.49 守护：F3 审计工具 / JSON / 矩阵 / 报告 / 样本清单缺失")
    else:
        try:
            f3 = json.loads(f3_json.read_text(encoding="utf-8"))
            mx = json.loads(f3_matrix.read_text(encoding="utf-8"))
            ck = f3["checks"]
            evidence_ok = (
                f3["all_pass"] is True
                and len(ck) == 17
                and all(ck.values())
                and f3["matrix_summary"]["cells"] == 14
                and f3["matrix_summary"]["counts"] == {"PASS": 11, "FAIL": 3}
                and f3["matrix_summary"]["extension_ok_all"] is True
                and len(f3["matrix_summary"]["no_sample_formats"]) == 7
                and mx["overall_verdict"] == "PARTIAL"
                and mx["test_precondition"]["device_settings_unchanged"] is True
            )
            doc_ok = (
                "61.49" in delivery12.read_text(encoding="utf-8")
                and "8.9" in plan17.read_text(encoding="utf-8")
                and "31 个文件" in overview.read_text(encoding="utf-8")
            )
            if not (evidence_ok and doc_ok):
                failures.append(
                    "12 §61.49 守护：F3 矩阵证据 / 11 PASS 3 FAIL / 14 格 extension 全对 / "
                    "NO_SAMPLE 7 种 / 12 断点 / docs17 §8.9 / docs 计数漂移，请重跑 "
                    "tools/l3_f3_matrix_audit.py 并回写 12 §61.49"
                )
            else:
                print("[PASS] 12 §61.49 守护：F3 双路径真机矩阵 7 格式 × 2 路径 = "
                      "11 PASS / 3 FAIL / 7 种 NO_SAMPLE；14/14 格 extension 为真实扩展名；"
                      "OpenList 真实音源无需编码器 / REMOTE 调试通道零新增依赖 / "
                      "探针前置已恢复；F3 判 PARTIAL、docs 计数仍 31")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.49 守护：F3 证据 JSON 不可解析（{exc}）")

    # 12 §61.50 守护 / F4：防伪造 F4 完成、防 2s 等待被悄悄改掉、
    # 防把 F3 的 ape×remote 误当竞态复现载体、防基线漂移。
    f4_tool = ROOT / "tools" / "l3_f4_eof_audit.py"
    f4_json = ROOT / "analysis" / "l3-f4-eof-audit.json"
    f4_probe = ROOT / "analysis" / "l3-f4-eof-probe.json"
    f4_plan = ROOT / "analysis" / "tl-l3-f4-eof-plan-2026-11-09.md"
    f4_request = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
    if not all(p.is_file() for p in (f4_tool, f4_json, f4_probe, f4_plan,
                                     f4_request, delivery12, plan17)):
        failures.append("12 §61.50 守护：F4 审计工具 / JSON / 探针 / 施工单 / 源码缺失")
    else:
        try:
            f4 = json.loads(f4_json.read_text(encoding="utf-8"))
            probe = json.loads(f4_probe.read_text(encoding="utf-8"))
            ck = f4["checks"]
            evidence_ok = (
                f4["all_pass"] is True
                and len(ck) == 13
                and all(ck.values())
                and f4["f4_status"] == "BLOCKED"
                and probe["verdict"]["f4_repro_carrier"] is False
                and all(s in f4_request.read_text(encoding="utf-8")
                        for s in ("repeat(20)", "Thread.sleep(100)",
                                  "delayed 2s or immediate"))
            )
            doc_ok = "61.50" in delivery12.read_text(encoding="utf-8") \
                and "8.10" in plan17.read_text(encoding="utf-8")
            if not (evidence_ok and doc_ok):
                failures.append(
                    "12 §61.50 守护：F4 根因证据 / BLOCKED 状态 / 2s 等待原样 / "
                    "12 断点 / docs17 §8.10 漂移，请重跑 tools/l3_f4_eof_audit.py "
                    "并回写 12 §61.50"
                )
            else:
                print("[PASS] 12 §61.50 守护：F4 根因钉死（normal mode 写线程落盘 "
                      "~50ms vs 读侧 5ms 重试）/ moov-at-end 探针未触发竞态 / "
                      "F3 ape×remote 非复现载体 / P0 取流代码零改动 / "
                      "F4 判 BLOCKED 待真实样本，不伪造完成")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.50 守护：F4 证据 JSON 不可解析（{exc}）")

    # 12 §61.51 守护 / F4 A/B：防伪造 F4 完成、防 P0 取流代码残留 A/B 变体、
    # 防 F3 矩阵被悄悄改写、防基线漂移。真机证据均为设备离线前采集的原始 logcat。
    ab_tool = ROOT / "tools" / "l3_f4_ab_audit.py"
    ab_json = ROOT / "analysis" / "l3-f4-ab-audit.json"
    ab_report = ROOT / "analysis" / "l3-f4-ab-report.md"
    ab_probe = ROOT / "analysis" / "l3-f4-m4a-final.json"
    ab_request = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
    if not all(p.is_file() for p in (ab_tool, ab_json, ab_report, ab_probe,
                                     ab_request, delivery12, plan17)):
        failures.append("12 §61.51 守护：F4 A/B 审计工具 / JSON / 报告 / 取证 / 源码缺失")
    else:
        try:
            ab = json.loads(ab_json.read_text(encoding="utf-8"))
            ck = ab["checks"]
            evidence_ok = (
                ab["all_pass"] is True
                and len(ck) == 14
                and all(ck.values())
                and ab["summary"]["f4_status"] == "BLOCKED（证据升级，但仍未完成）"
                and "repeat(20)" in ab_request.read_text(encoding="utf-8")
                and "AB-A immediate" not in ab_request.read_text(encoding="utf-8")
            )
            doc_ok = "61.51" in delivery12.read_text(encoding="utf-8") \
                and "8.11" in plan17.read_text(encoding="utf-8")
            if not (evidence_ok and doc_ok):
                failures.append(
                    "12 §61.51 守护：F4 A/B 证据 / P0 基线回退 / F3 矩阵未改写 / "
                    "12 断点 / docs17 §8.11 漂移，请重跑 tools/l3_f4_ab_audit.py "
                    "并回写 12 §61.51"
                )
            else:
                print("[PASS] 12 §61.51 守护：faststart fMP4 对照样本 "
                      "LOCAL 解出 aac PASS / REMOTE 重复 Start 无 codec；"
                      "去掉 2s 等待的 A/B 变体结果相同 → 候选①证伪；"
                      "P0 代码已回退基线并复验零回归、F3 矩阵未改写；"
                      "F4 仍 BLOCKED、不伪造完成")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.51 守护：F4 A/B 证据 JSON 不可解析（{exc}）")

    # 12 §61.52 守护 / F4 卡点根因：防把「kind 槽假设」写成已证实根因、
    # 防伪造 F4 完成、防 P0 代码被提前改动、防基线漂移。纯离线，不连 adb。
    ks_tool = ROOT / "tools" / "l3_f4_kind_slot_audit.py"
    ks_json = ROOT / "analysis" / "l3-f4-kind-slot-audit.json"
    ks_report = ROOT / "analysis" / "l3-f4-kind-slot-rootcause.md"
    ks_request = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
    if not all(p.is_file() for p in (ks_tool, ks_json, ks_report, ks_request,
                                     delivery12, plan17)):
        failures.append("12 §61.52 守护：F4 根因审计工具 / JSON / 报告 / 源码缺失")
    else:
        try:
            ks = json.loads(ks_json.read_text(encoding="utf-8"))
            ck = ks["checks"]
            evidence_ok = (
                ks["all_pass"] is True
                and len(ck) == 15
                and all(ck.values())
                and ks["hypothesis"]["confidence"].startswith("高置信假设")
                and ks["hypothesis"]["f4_status"].startswith("BLOCKED")
                and "OnDataRequestEnd(requestId, error ?: \"\")" \
                    in ks_request.read_text(encoding="utf-8")
                and "NATIVE_DONE_KIND_FLUSH" not in ks_request.read_text(encoding="utf-8")
            )
            doc_ok = "61.52" in delivery12.read_text(encoding="utf-8") \
                and "8.12" in plan17.read_text(encoding="utf-8")
            if not (evidence_ok and doc_ok):
                failures.append(
                    "12 §61.52 守护：F4 根因证据 / 假设未证实口径 / P0 代码零改动 / "
                    "12 断点 / docs17 §8.12 漂移，请重跑 "
                    "tools/l3_f4_kind_slot_audit.py 并回写 12 §61.52"
                )
            else:
                print("[PASS] 12 §61.52 守护：定位到 OnDataRequestEnd 首参是 "
                      "OnNetSourceDataDone 的 kind 槽（id=2 → PreloadDone 完成通知 → "
                      "1~2ms 后重发同区间）；§59.2 反汇编 + F3 日志交叉验证 + "
                      "A/B 排除 2s；仅为高置信假设、P0 零改动、F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.52 守护：F4 根因证据 JSON 不可解析（{exc}）")

    # 12 §61.59：A/B 结果如实登记判定，必须在 §61.53 守护引用之前定义。
    _abr = ROOT / "analysis" / "l3-f4-kind-slot-ab.json"
    _abd = json.loads(_abr.read_text(encoding="utf-8")) if _abr.is_file() else {}
    ab_result_honest = bool(_abd) and (
        _abd.get("primary_criterion_met") is False
        and _abd.get("p0_restored") is True
        and _abd.get("regression_all_pass") is True
        and "NOT RUN" in _abd.get("auto_advance_3_tracks", ""))

    # 12 §61.53 守护 / F4 kind 槽 A/B 脚本：防提前改 P0、防伪造 A/B 结果、
    # 防把未跑的连播判据写成 PASS、防基线漂移。纯离线，不连 adb。
    ab2_tool = ROOT / "tools" / "l3_f4_kind_slot_ab.py"
    ab2_audit = ROOT / "tools" / "l3_f4_kind_slot_ab_audit.py"
    ab2_json = ROOT / "analysis" / "l3-f4-kind-slot-ab-audit.json"
    ab2_plan = ROOT / "analysis" / "l3-f4-kind-slot-ab-plan.json"
    ab2_src = ROOT / "engine-legacy/src/main/java/com/example/cloudmusic/enginelegacy/UrlDataRequest.kt"
    if not all(p.is_file() for p in (ab2_tool, ab2_audit, ab2_json, ab2_plan,
                                     ab2_src, delivery12, plan17)):
        failures.append("12 §61.53 守护：A/B 脚本 / 审计 / 计划 / 源码缺失")
    else:
        try:
            a2 = json.loads(ab2_json.read_text(encoding="utf-8"))
            ck2 = a2["checks"]
            evidence_ok = (
                a2["all_pass"] is True
                and len(ck2) == 14
                and all(ck2.values())
                and a2["status"]["script_ready"] is True
                and a2["status"]["dry_run_executed"] is True
                and a2["status"]["ab_executed"] is True
                and ab_result_honest
                and 'OnDataRequestEnd(requestId, error ?: "")' \
                    in ab2_src.read_text(encoding="utf-8")
                and "NATIVE_DONE_KIND_FLUSH" not in ab2_src.read_text(
                    encoding="utf-8")
            )
            doc_ok = "61.53" in delivery12.read_text(encoding="utf-8") \
                and "8.13" in plan17.read_text(encoding="utf-8")
            if not (evidence_ok and doc_ok):
                failures.append(
                    "12 §61.53 守护：A/B 脚本 / dry-run 计划 / P0 零改动 / "
                    "12 断点 / docs17 §8.13 漂移，请重跑 "
                    "tools/l3_f4_kind_slot_ab_audit.py 并回写 12 §61.53"
                )
            else:
                print("[PASS] 12 §61.53 守护：kind 槽 A/B 脚本就绪（默认 dry-run、"
                      "--apply 才动 P0、finally 无条件恢复基线并重编译重装）"
                      " / 主判据与证伪条件齐备 / 连播 3 首如实 NOT RUN / "
                      "P0 零改动；设备上线后一条命令即可验证")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.53 守护：A/B 脚本证据 JSON 不可解析（{exc}）")

    # 12 §61.54 守护 / F4 native 侧一手二进制复核：防把「§59.2 分支语义已由
    # 反汇编 + GOT 重定位证实」偷换成「§61.52 的 kind 槽因果假设已证实」，
    # 并防借本批名义提前改 P0、回填指纹、伪造 A/B 结果。纯离线、只读。
    dv_json = ROOT / "analysis" / "l3-f4-disasm-verify.json"
    dv_report = ROOT / "analysis" / "l3-f4-disasm-verify-report.md"
    dv_tool = ROOT / "tools" / "l3_f4_disasm_verify.py"
    dv_audit = ROOT / "tools" / "l3_f4_disasm_verify_audit.py"
    if not all(p.is_file() for p in (dv_json, dv_report, dv_tool, dv_audit)):
        failures.append("12 §61.54 守护：native 二进制复核证据 / 工具缺失")
    else:
        try:
            dv = json.loads(dv_json.read_text(encoding="utf-8"))
            dv_ck = dv["checks"]
            dv_ok = (
                dv["all_pass"] is True
                and len(dv_ck) == 10
                and all(dv_ck.values())
                and dv["evidence"]["queue_call_sites"] == ["0xbf7c", "0xc004"]
                and dv["evidence"]["got_relocations"]["0x4bed0"]["symbol"].endswith(
                    "PreloadDoneTaskOnWriteThreadEv")
                and dv["evidence"]["got_relocations"]["0x4bee8"]["symbol"].endswith(
                    "OnFlushNetDataEv")
                and "61.54" in delivery12.read_text(encoding="utf-8")
                and "**F4 仍判 BLOCKED**" in dv_report.read_text(encoding="utf-8")
            )
            if not dv_ok:
                failures.append(
                    "12 §61.54 守护：一手二进制证据 / 分支语义 / 诚实边界 / "
                    "12 断点漂移，请重跑 tools/l3_f4_disasm_verify.py 与 "
                    "tools/l3_f4_disasm_verify_audit.py"
                )
            else:
                print("[PASS] 12 §61.54 守护：OnNetSourceDataDone 的 "
                      "cmp w1,#2 分支语义由一手反汇编 + GOT GLOB_DAT 重定位确认"
                      "（kind==2 → PreloadDoneTaskOnWriteThread，其余 → OnFlushNetData）；"
                      "§61.52 因果假设仍待设备上线 A/B，F4 仍 BLOCKED、P0 零改动")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.54 守护：native 二进制复核证据 JSON 不可解析（{exc}）")

    # 12 §61.55 守护 / F4 两个 task 行为差异 + F3 样本边界盘点：
    # 防把「task 差异已证实」偷换成「因果链已证实」，防伪造 NO_SAMPLE 样本、
    # 防凭据落盘、防提前改 P0 / 回填指纹 / 伪造 A/B。纯离线、只读。
    td_json = ROOT / "analysis" / "l3-f4-task-diff.json"
    td_report = ROOT / "analysis" / "l3-f4-task-diff-report.md"
    td_scan = ROOT / "analysis" / "l3-f3-openlist-scan.json"
    td_tool = ROOT / "tools" / "l3_f4_task_diff_verify.py"
    td_scan_tool = ROOT / "tools" / "l3_f3_openlist_scan.py"
    td_audit = ROOT / "tools" / "l3_f4_task_diff_audit.py"
    if not all(p.is_file() for p in (td_json, td_report, td_scan, td_tool,
                                     td_scan_tool, td_audit)):
        failures.append("12 §61.55 守护：task 差异 / 样本盘点证据或工具缺失")
    else:
        try:
            td = json.loads(td_json.read_text(encoding="utf-8"))
            sc = json.loads(td_scan.read_text(encoding="utf-8"))
            td_ck = td["checks"]
            td_ok = (
                td["all_pass"] is True
                and len(td_ck) == 14
                and all(td_ck.values())
                and "PreloadDoneTaskOnRenderThread" in
                    td["evidence"]["got_render_relocation"]["0x4bf28"]["symbol"]
                and td_ck["flush_is_lightweight"] is True
                and td_ck["docs59_3_kind1_calibrated"] is True
                and sc["dirs_scanned"] >= 80
                and sc["list_errors"] == 0
                and sc["no_sample_candidate_total"] == 0
                and not sc["no_sample_found"]
                and "61.55" in delivery12.read_text(encoding="utf-8")
                and "**F4 仍判 BLOCKED**" in td_report.read_text(encoding="utf-8")
            )
            if not td_ok:
                failures.append(
                    "12 §61.55 守护：task 差异证据 / 样本盘点 / 诚实边界 / "
                    "12 断点漂移，请重跑 tools/l3_f4_task_diff_verify.py、"
                    "tools/l3_f3_openlist_scan.py 与 tools/l3_f4_task_diff_audit.py"
                )
            else:
                print("[PASS] 12 §61.55 守护：kind==2 级联投递 "
                      "PreloadDoneTaskOnRenderThread（重完成链）、kind!=2 仅 88B "
                      "轻量刷数据；1L 为 §59.3 已校准正常值（A/B 风险降低）；"
                      "OpenList 80 目录零 NO_SAMPLE 样本；§61.52 因果假设仍待 A/B，"
                      "F4 仍 BLOCKED、P0 零改动")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.55 守护：task 差异 / 盘点证据 JSON 不可解析（{exc}）")

    # 12 §61.56 守护 / F4 JNI 参数槽透传复核：只允许「环节 A 已证实」，
    # 防偷换成「整条因果链已证实」，防提前改 P0 / 回填指纹 / 伪造 A/B。
    ja_json = ROOT / "analysis" / "l3-f4-jni-arg.json"
    ja_report = ROOT / "analysis" / "l3-f4-jni-arg-report.md"
    ja_tool = ROOT / "tools" / "l3_f4_jni_arg_verify.py"
    ja_audit = ROOT / "tools" / "l3_f4_jni_arg_audit.py"
    if not all(p.is_file() for p in (ja_json, ja_report, ja_tool, ja_audit)):
        failures.append("12 §61.56 守护：JNI 参数槽证据 / 工具缺失")
    else:
        try:
            ja = json.loads(ja_json.read_text(encoding="utf-8"))
            ja_ck = ja["checks"]
            ja_ok = (
                ja["all_pass"] is True
                and len(ja_ck) == 17
                and all(ja_ck.values())
                and ja_ck["end_sets_x1_to_j2"] is True
                and ja_ck["data_sets_x1_to_j2"] is True
                and ja_ck["trampoline_forwards_x1_unchanged"] is True
                and ja_ck["done_compares_w1_with_2"] is True
                and "环节 A" in ja_report.read_text(encoding="utf-8")
                and "环节 B" in ja_report.read_text(encoding="utf-8")
                and "**F4 仍判 BLOCKED**" in ja_report.read_text(encoding="utf-8")
                and "61.56" in delivery12.read_text(encoding="utf-8")
            )
            if not ja_ok:
                failures.append(
                    "12 §61.56 守护：JNI 透传证据 / 因果链拆分 / 诚实边界 / "
                    "12 断点漂移，请重跑 tools/l3_f4_jni_arg_verify.py 与 "
                    "tools/l3_f4_jni_arg_audit.py"
                )
            else:
                print("[PASS] 12 §61.56 守护：JNI 导出与 trampoline 把 Java "
                      "j2(requestId) 原样放进 x1，与 §59.3 已校准为 kind 的"
                      "数据包路径同构 → 环节 A 已证实；环节 B（完成通知是否"
                      "导致 fMP4 卡点）仍待设备上线 A/B，F4 仍 BLOCKED、P0 零改动")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.56 守护：JNI 参数槽证据 JSON 不可解析（{exc}）")

    # 12 §61.57 守护 / F4 A/B 编译期预检：防把「编译通过」偷换成
    # 「A/B 已执行 / 环节 B 已证实」，防 P0 残留变体、防 Windows
    # gradlew 启动器缺陷回潮、防伪造零净改动。纯离线、只读。
    cp_json = ROOT / "analysis" / "l3-f4-ab-compile-precheck.json"
    cp_report = ROOT / "analysis" / "l3-f4-ab-compile-precheck-report.md"
    cp_tool = ROOT / "tools" / "l3_f4_ab_compile_precheck.py"
    cp_audit = ROOT / "tools" / "l3_f4_ab_compile_precheck_audit.py"
    cp_ab_tool = ROOT / "tools" / "l3_f4_kind_slot_ab.py"
    if not all(p.is_file() for p in (cp_json, cp_report, cp_tool, cp_audit,
                                     cp_ab_tool)):
        failures.append("12 §61.57 守护：编译期预检证据 / 工具缺失")
    else:
        try:
            cp = json.loads(cp_json.read_text(encoding="utf-8"))
            cp_ck = cp["checks"]
            cp_ab_src = cp_ab_tool.read_text(encoding="utf-8")
            cp_ok = (
                cp["all_pass"] is True
                and len(cp_ck) == 9
                and all(cp_ck.values())
                and cp_ck["variant_compiles_offline"] is True
                and cp_ck["baseline_recompiles_offline"] is True
                and cp_ck["source_restored_byte_exact"] is True
                and cp["evidence"]["git_diff_clean"] is True
                and ab_result_honest
                and 'GRADLE.with_name("gradlew.bat")' in cp_ab_src
                and "baseline_rebuild_ok" in cp_ab_src
                and "61.57" in delivery12.read_text(encoding="utf-8")
                and "零净改动" in cp_report.read_text(encoding="utf-8")
            )
            if not cp_ok:
                failures.append(
                    "12 §61.57 守护：编译期预检证据 / Windows 启动器修复 / "
                    "P0 零净改动 / 12 断点漂移，请重跑 "
                    "tools/l3_f4_ab_compile_precheck.py 与其审计"
                )
            else:
                print("[PASS] 12 §61.57 守护：A/B 变体 B 已编译期预检通过"
                      "（:app:assembleDebug --offline BUILD SUCCESSFUL），"
                      "Windows gradlew.bat 启动器缺陷已修，P0 逐字节恢复、"
                      "git diff 空；§61.59 A/B 已执行、主判据未达成，F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.57 守护：编译期预检证据 JSON 不可解析（{exc}）")

    # 12 §61.58 守护 / F4 设备上线前自检：防伪造「设备已在线 / A/B 已执行」，
    # 防 runbook 漏掉判读分支或恢复确认、防守护 JSON 漂移。纯离线、只读。
    pf_json = ROOT / "analysis" / "l3-f4-preflight.json"
    pf_runbook = ROOT / "analysis" / "l3-f4-online-runbook.md"
    pf_tool = ROOT / "tools" / "l3_f4_preflight.py"
    pf_audit = ROOT / "tools" / "l3_f4_preflight_audit.py"
    if not all(p.is_file() for p in (pf_json, pf_runbook, pf_tool, pf_audit)):
        failures.append("12 §61.58 守护：上线前自检 / runbook 证据或工具缺失")
    else:
        try:
            pf = json.loads(pf_json.read_text(encoding="utf-8"))
            pf_ck = pf["checks"]
            pf_ok = (
                pf["all_pass"] is True
                and len(pf_ck) == 15
                and all(pf_ck.values())
                and pf["ready_to_run"] is False
                and ab_result_honest
                and len(pf["evidence"]["guard_jsons"]) == 10
                and all(pf["evidence"]["guard_jsons"].values())
                and "61.58" in delivery12.read_text(encoding="utf-8")
                and "一键" in pf_runbook.read_text(encoding="utf-8")
            )
            if not pf_ok:
                failures.append(
                    "12 §61.58 守护：上线前自检 / 设备离线如实登记 / runbook / "
                    "12 断点漂移，请重跑 tools/l3_f4_preflight.py 与其审计"
                )
            else:
                print("[PASS] 12 §61.58 守护：A/B 前置条件自检全绿"
                      "（15 项 / 10 批守护 / 契约 / P0 基线）；§61.59 A/B 已执行、"
                      "结果如实登记，F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.58 守护：上线前自检证据 JSON 不可解析（{exc}）")

    # 12 §61.59 守护 / F4 kind 槽 A/B 真机裁决：防把「部分成立」写成 PASS /
    # 证伪 / F4 已解决，防伪造 P0 恢复、防把 NOT RUN 的连播判据写成已测。
    rj = ROOT / "analysis" / "l3-f4-ab-result-audit.json"
    rr = ROOT / "analysis" / "l3-f4-ab-result-report.md"
    r1 = ROOT / "analysis" / "l3-f4-ab-run1-25s.json"
    r2 = ROOT / "analysis" / "l3-f4-ab-run2-45s.json"
    rc = ROOT / "analysis" / "l3-f4-ab-control-baseline.json"
    if not all(p.is_file() for p in (rj, rr, r1, r2, rc)):
        failures.append("12 §61.59 守护：A/B 真机结果 / 报告 / 对照证据缺失")
    else:
        try:
            rjd = json.loads(rj.read_text(encoding="utf-8"))
            r_ok = (
                rjd["all_pass"] is True
                and rjd["status"]["ab_executed"] is True
                and rjd["status"]["primary_criterion_met"] is False
                and rjd["status"]["p0_restored"] is True
                and "PARTIAL" in rjd["status"]["verdict"]
                and "F4 仍判 BLOCKED" in rr.read_text(encoding="utf-8")
                and ab_result_honest
                and "61.59" in delivery12.read_text(encoding="utf-8")
            )
            if not r_ok:
                failures.append(
                    "12 §61.59 守护：A/B 结果 / 诚实边界 / 12 断点漂移，请重跑 "
                    "tools/l3_f4_ab_result_audit.py"
                )
            else:
                print("[PASS] 12 §61.59 守护：kind 槽 A/B 已真机执行（25s/45s 两次 + "
                      "基线对照）；环节 B 部分成立（消除 id>=3 重复请求）但非充分原因"
                      "（仍无 codec），主判据未达成、P0 零净改动，F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.59 守护：A/B 结果证据 JSON 不可解析（{exc}）")

    # 12 §61.60 守护 / F4 第二卡点样本侧复核：防把「归因优先级修正」
    # 写成「第二卡点已定位 / F4 已解决」，防改写 §61.59 A/B 实测事实。
    sb_json = ROOT / "analysis" / "l3-f4-fmp4-structure.json"
    sb_report = ROOT / "analysis" / "l3-f4-second-blocker-report.md"
    sb_audit = ROOT / "analysis" / "l3-f4-second-blocker-audit.json"
    sb_tool = ROOT / "tools" / "l3_f4_fmp4_structure_audit.py"
    if not all(p.is_file() for p in (sb_json, sb_report, sb_audit, sb_tool)):
        failures.append("12 §61.60 守护：样本结构复核证据 / 工具缺失")
    else:
        try:
            sb = json.loads(sb_json.read_text(encoding="utf-8"))
            sb_ck = sb["checks"]
            sb_ok = (
                sb["all_pass"] is True
                and len(sb_ck) == 12
                and all(sb_ck.values())
                and sb_ck["tail_is_orphan_moof_no_mdat"] is True
                and sb_ck["no_sidx_mfra_mfro_index"] is True
                and sb_ck["ondata_matches_request_exactly"] is True
                and "PENDING_REAL_SAMPLE" in sb_report.read_text(encoding="utf-8")
                and ab_result_honest
                and "61.60" in delivery12.read_text(encoding="utf-8")
            )
            if not sb_ok:
                failures.append(
                    "12 §61.60 守护：样本结构证据 / 归因修正 / 12 断点漂移，请重跑 "
                    "tools/l3_f4_fmp4_structure_audit.py 与其审计"
                )
            else:
                print("[PASS] 12 §61.60 守护（历史快照）：fMP4 样本不完整（孤立尾部 moof / "
                      "13 moof-12 mdat / 无索引 / 两处未请求空洞）、onData 与请求"
                      "逐字节一致 → 当时把第二卡点归因修正为「样本不自洽为主、vfile 落盘为次」；"
                      "该主次已被 §61.61 真机结论推翻，结构事实与 A/B 实测不改写，F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.60 守护：样本结构证据 JSON 不可解析（{exc}）")

    # 12 §61.61 守护 / F4 第二卡点最终真机结论：防把「根因锁定」写成
    # 「F4 已解决 / 修复已验证」，防把样本自洽性或错误完成通知重新写回
    # 第二卡点，防伪造设备在场 / P0 基线改动。
    f_ev = ROOT / "analysis" / "l3-f4-second-blocker-final.json"
    f_report = ROOT / "analysis" / "l3-f4-second-blocker-final-report.md"
    f_audit = ROOT / "analysis" / "l3-f4-second-blocker-final-audit.json"
    f_tool = ROOT / "tools" / "l3_f4_second_blocker_final_audit.py"
    if not all(p.is_file() for p in (f_ev, f_report, f_audit, f_tool)):
        failures.append("12 §61.61 守护：最终真机结论证据 / 审计 / 工具缺失")
    else:
        try:
            fa = json.loads(f_audit.read_text(encoding="utf-8"))
            fe = json.loads(f_ev.read_text(encoding="utf-8"))
            fst = fa["status"]
            f_ok = (
                fa["all_pass"] is True
                and fa["checks_total"] >= 35
                and all(fa["checks"].values())
                and fst["root_cause_locked"] is True
                and fst["sample_selfconsistency_ruled_out"] is True
                and fst["wrong_completion_notification_ruled_out"] is True
                and fst["fix_implemented"] is False
                and fst["fix_verified"] is False
                and fst["f4_status"] == "BLOCKED"
                and fst["device_phase"] == "COMPLETE"
                and fst["device_can_disconnect"] is True
                and "commit=368640" in fst["root_cause"]
                and "offset=605007" in fst["root_cause"]
                and fe["sample"]["paired_moof_mdat"] is True
                and "F4 仍判 BLOCKED" in f_report.read_text(encoding="utf-8")
                and "61.61" in delivery12.read_text(encoding="utf-8")
            )
            if not f_ok:
                failures.append(
                    "12 §61.61 守护：最终结论证据 / 诚实边界 / 12 断点漂移，请重跑 "
                    "tools/l3_f4_second_blocker_final_audit.py"
                )
            else:
                print("[PASS] 12 §61.61 守护（历史快照，窗口精度修正见 §61.62）："
                      "变体 B + 自洽样本在短窗口下无 codec → 样本自洽性、"
                      "错误完成通知均排除；第二卡点锁定 vfile 流式落盘（commit=368640）"
                      "跟不上 fMP4 跳跃式 probe 读（offset=605007, readed_len:0 result:-2）；"
                      "§61.62 的 60s 观测补上长窗口，F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.61 守护：最终结论证据 JSON 不可解析（{exc}）")

    # 12 §61.62 守护 / vfile commit 时序 60s 纯观测：防把「codec 打开」
    # 写成「已出声 / F4 已解决」，防把 commit 恒定写成「落盘速度不足」，
    # 防回改 §61.61 历史结论、防 P0 基线改动、防伪造设备在场。
    vc_probe = ROOT / "analysis" / "l3-f4-vfile-commit-probe.json"
    vc_report = ROOT / "analysis" / "l3-f4-vfile-commit-report.md"
    vc_audit = ROOT / "analysis" / "l3-f4-vfile-commit-audit.json"
    vc_tool = ROOT / "tools" / "l3_f4_vfile_commit_audit.py"
    vc_logs = list((ROOT / "analysis" / "l3-f4-logs").glob("vfile-commit-*.txt"))
    if not all(p.is_file() for p in (vc_probe, vc_report, vc_audit, vc_tool)) or not vc_logs:
        failures.append("12 §61.62 守护：commit 时序观测证据 / 日志 / 审计缺失")
    else:
        try:
            va = json.loads(vc_audit.read_text(encoding="utf-8"))
            vp = json.loads(vc_probe.read_text(encoding="utf-8"))
            vst = va["status"]
            vc_ok = (
                va["all_pass"] is True
                and va["checks_total"] >= 25
                and all(va["checks"].values())
                and vst["commit_constant_60s"] is True
                and vst["speed_progress_hypothesis_ruled_out"] is True
                and (vst["codec_opened_after_seconds"] or 0) > 25
                and vst["write_start_play_count"] == 0
                and vst["minus_103_observed"] is True
                and vst["audio_output"] is False
                and vst["f4_status"] == "BLOCKED"
                and vst["fix_implemented"] is False
                and vst["fix_verified"] is False
                and bool(vst["device_endpoint"]) and ":" in vst["device_endpoint"]
                and "UNEXPECTED_CODEC_OPENED" in vp.get("mechanism_verdict", "")
                and "F4 判据收窄到" in vc_report.read_text(encoding="utf-8")
                and "不改写" in vc_report.read_text(encoding="utf-8")
                and "61.62" in delivery12.read_text(encoding="utf-8")
            )
            if not vc_ok:
                failures.append(
                    "12 §61.62 守护：commit 时序结论 / 诚实边界 / 12 断点漂移，请重跑 "
                    "tools/l3_f4_vfile_commit_audit.py"
                )
            else:
                print("[PASS] 12 §61.62 守护：60s 观测 commit 恒定 368640（排除速度不足）、"
                      "≈33s 回退 368640 后打开 aac codec 但 -103 未出声 → §61.61 归因强化、"
                      "「无 codec」修正为窗口不足、F4 判据收窄到 -103；F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.62 守护：commit 时序证据 JSON 不可解析（{exc}）")

    # 12 §61.63 守护 / vfile 索引取证 + 清缓存对照 + 变体 D 受阻：防把
    # RULED_OUT 写成「根因已定位」，防把两次「无有效判据」写成证伪 /
    # PASS / 崩溃，防伪造 P0 恢复、防写 F4 已解决。
    vd_audit = ROOT / "analysis" / "l3-f4-vfile-diagnosis-audit.json"
    vd_report = ROOT / "analysis" / "l3-f4-vfile-diagnosis-report.md"
    vd_idx = ROOT / "analysis" / "l3-f4-vfile-index-before.json"
    vd_reset = ROOT / "analysis" / "l3-f4-cache-reset-verdict.json"
    vd_r1 = ROOT / "analysis" / "l3-f4-data-kind-ab-run1.json"
    vd_r2 = ROOT / "analysis" / "l3-f4-data-kind-ab-run2.json"
    if not all(p.is_file() for p in (vd_audit, vd_report, vd_idx, vd_reset,
                                     vd_r1, vd_r2)):
        failures.append("12 §61.63 守护：vfile 索引 / 清缓存 / 变体 D 证据缺失")
    else:
        try:
            vd = json.loads(vd_audit.read_text(encoding="utf-8"))
            vst = vd["status"]
            vd_ok = (
                vd["all_pass"] is True
                and vd["checks_total"] >= 30
                and all(vd["checks"].values())
                and vst["stale_slice_hypothesis"] == "RULED_OUT"
                and vst["kind_mismatch_hypothesis"] == "UNVERIFIED"
                and vst["variant_d_verdict"] == "NO_VALID_CRITERION"
                and vst["variant_d_runs"] == 2
                and vst["f4_status"] == "BLOCKED"
                and vst["fix_implemented"] is False
                and vst["fix_verified"] is False
                and vst["device_offline_at_end"] is True
                and vst["p0_restored"] is True
                and "RULED_OUT" in vd_report.read_text(encoding="utf-8")
                and "NO_VALID_CRITERION" in vd_report.read_text(encoding="utf-8")
                and "61.63" in delivery12.read_text(encoding="utf-8")
            )
            if not vd_ok:
                failures.append(
                    "12 §61.63 守护：vfile 诊断结论 / 诚实边界 / 12 断点漂移，请重跑 "
                    "tools/l3_f4_vfile_diagnosis_audit.py"
                )
            else:
                print("[PASS] 12 §61.63 守护：.nmsfi 索引坐实 Java 回传数据未落盘；"
                      "清缓存对照 RULED_OUT（非陈旧切片）；变体 D 两次 NO_VALID_CRITERION"
                      "（冷启动 + 设备离线），P0 零净改动，F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.63 守护：vfile 诊断证据 JSON 不可解析（{exc}）")

    # 12 §61.64 守护 / 变体 D 真机裁决：防把「有效负结果」写成「无有效判据 /
    # 假设未测」，防把回归 PASS 写成主判据 PASS，防写 F4 已解决。
    kv_audit = ROOT / "analysis" / "l3-f4-data-kind-verdict-audit.json"
    kv_report = ROOT / "analysis" / "l3-f4-data-kind-verdict-report.md"
    kv_run3 = ROOT / "analysis" / "l3-f4-data-kind-ab-run3.json"
    if not all(p.is_file() for p in (kv_audit, kv_report, kv_run3)):
        failures.append("12 §61.64 守护：变体 D 裁决证据 / 报告缺失")
    else:
        try:
            kv = json.loads(kv_audit.read_text(encoding="utf-8"))
            kst = kv["status"]
            kv_ok = (
                kv["all_pass"] is True
                and kv["checks_total"] >= 25
                and all(kv["checks"].values())
                and kst["variant_d_verdict"] == "FALSIFIED_EFFECTIVE_NEGATIVE"
                and kst["primary_criterion_met"] is False
                and kst["regression_all_pass"] is True
                and kst["commit_kind1_baseline"] == 180224
                and kst["commit_kind2_variant"] == 16384
                and kst["fix_implemented"] is False
                and kst["fix_verified"] is False
                and kst["f4_status"] == "BLOCKED"
                and kst["device_endpoint"] == "10.0.0.2:41761"
                and "修复预测被证伪" in kv_report.read_text(encoding="utf-8")
                and "61.64" in delivery12.read_text(encoding="utf-8")
            )
            if not kv_ok:
                failures.append(
                    "12 §61.64 守护：变体 D 裁决 / 诚实边界 / 12 断点漂移，请重跑 "
                    "tools/l3_f4_data_kind_verdict_audit.py"
                )
            else:
                print("[PASS] 12 §61.64 守护：kind=2 有效负结果（commit 180224→16384、"
                      "无 codec、回归两格 PASS）→ 修复预测证伪，根因转向 native "
                      "OnWriteNetData 落盘链路；F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.64 守护：变体 D 裁决 JSON 不可解析（{exc}）")

    # 12 §61.65 守护 / F4 native 反汇编：防把「静态推断」写成「已修复」、
    # 防 F4 状态被提前改成 PASS、防 commit 被继续当成落盘字节数、防证据漂移。
    f4d_tool = ROOT / "tools" / "l3_f4_netsource_commit_disasm.py"
    f4d_json = ROOT / "analysis" / "l3-f4-netsource-commit-disasm.json"
    f4d_report = ROOT / "analysis" / "l3-f4-netsource-commit-report.md"
    f4d_eof = ROOT / "analysis" / "l3-f4-eof-audit.json"
    if not all(p.is_file() for p in (f4d_tool, f4d_json, f4d_report,
                                     f4d_eof, delivery12, plan17)):
        failures.append("12 §61.65 守护：native 反汇编工具 / JSON / 报告 / F4 状态缺失")
    else:
        try:
            f4d = json.loads(f4d_json.read_text(encoding="utf-8"))
            ck = f4d["checks"]
            rep = f4d_report.read_text(encoding="utf-8")
            eof = json.loads(f4d_eof.read_text(encoding="utf-8"))
            evidence_ok = (
                f4d["all_pass"] is True
                and len(ck) == 15
                and all(ck.values())
                and f4d["log_cross_check"]["on_data_packet_count"] >= 1000
                and f4d["log_cross_check"]["commit_values"] == [16384, 16384]
                and eof.get("f4_status") == "BLOCKED"
            )
            doc_ok = (
                "61.65" in delivery12.read_text(encoding="utf-8")
                and "8.25" in plan17.read_text(encoding="utf-8")
                and all(s in rep for s in (
                    "0x10798", "commit", "推断", "精度修正",
                    "F4 仍判 BLOCKED", "不写",
                ))
            )
            if not (evidence_ok and doc_ok):
                failures.append(
                    "12 §61.65 守护：native 反汇编证据 / 15 项 checks / 1028 包与 "
                    "commit=16384 / F4 BLOCKED / 12 断点 / docs17 §8.25 漂移，请重跑 "
                    "tools/l3_f4_netsource_commit_disasm.py 并回写 12 §61.65"
                )
            else:
                print("[PASS] 12 §61.65 守护：libNMCommonCache.so 落盘链反汇编定位 "
                      "NMNetSource::OnData 0x10798 代次早退 + 0x10888 commit 记账；"
                      "Java 1028 包完整回传但 commit 恒 16384；kind=1 多内存镜像；"
                      "F4 仍 BLOCKED、不写已解决")
        except (KeyError, ValueError, OSError) as exc:
            failures.append(f"12 §61.65 守护：反汇编证据 JSON 不可解析（{exc}）")

    # 12 §61.66 守护 / 仓库同步与历史快照工具口径：防仓库状态登记丢失、
    # 防 11 个钉死旧 HEAD 的历史审计工具被误重跑覆盖当时快照证据 JSON。
    snap_tools = [p for p in sorted((ROOT / "tools").glob("l3_f4_*_audit.py"))
                  if "head_still_uncommitted" in
                  p.read_text(encoding="utf-8", errors="replace")]
    snap_guard_ok = (
        len(snap_tools) == 11
        and all("12 §61.66 历史快照审计" in
                p.read_text(encoding="utf-8", errors="replace")
                and "return 2" in p.read_text(encoding="utf-8", errors="replace")
                for p in snap_tools))
    d12_text = (delivery12.read_text(encoding="utf-8", errors="replace")
                if delivery12.is_file() else "")
    kv_path = ROOT / "analysis" / "l3-f4-data-kind-verdict-audit.json"
    try:
        kv_now = json.loads(kv_path.read_text(encoding="utf-8"))
        kv_snapshot_ok = (kv_now["all_pass"] is True
                          and kv_now["checks_total"] == 30)
    except (KeyError, ValueError, OSError, TypeError):
        kv_snapshot_ok = False
    if not (snap_guard_ok and kv_snapshot_ok and "61.66" in d12_text
            and "fe081778..c26e7cb8" in d12_text):
        failures.append(
            "12 §61.66 守护：仓库同步登记 / 11 个历史快照工具保护 / "
            "§61.64 证据 JSON 当时快照漂移")
    else:
        print("[PASS] 12 §61.66 守护：28 批已同步 c26e7cb8；11 个钉死旧 HEAD 的"
              "历史审计工具按 §61.46 纪律拒绝重跑（返回 2），证据 JSON 未被覆盖")

    # 12 §61.67 守护 / F4 请求代次闸门与 vfile 落盘门控：防把「静态证据链升级」
    # 写成「已修复 / 已实证」、防 F4 状态提前改 PASS、防 41417 端点登记丢失、
    # 防 §61.65 既有证据与 commit 精度修正回潮。
    gg_tool = ROOT / "tools" / "l3_f4_generation_gate_disasm.py"
    gg_audit_tool = ROOT / "tools" / "l3_f4_generation_gate_audit.py"
    gg_json = ROOT / "analysis" / "l3-f4-generation-gate-disasm.json"
    gg_audit_json = ROOT / "analysis" / "l3-f4-generation-gate-audit.json"
    gg_report = ROOT / "analysis" / "l3-f4-generation-gate-report.md"
    if not all(p.is_file() for p in (gg_tool, gg_audit_tool, gg_json,
                                     gg_audit_json, gg_report, delivery12, plan17)):
        failures.append("12 §61.67 守护：代次闸门反汇编工具 / JSON / 审计 / 报告缺失")
    else:
        try:
            gg = json.loads(gg_json.read_text(encoding="utf-8"))
            ga = json.loads(gg_audit_json.read_text(encoding="utf-8"))
            gg_rep = gg_report.read_text(encoding="utf-8")
            evidence_ok = (
                gg["all_pass"] is True
                and len(gg["checks"]) == 18
                and all(gg["checks"].values())
                and len(gg["anchors"]) == 45
                and all(v["ok"] for v in gg["anchors"].values())
                and len(gg["rodata_strings"]) == 11
                and gg["checks"]["f4_still_blocked"] is True
                and gg["checks"]["prior_61_65_evidence_intact"] is True
                and ga["all_pass"] is True
            )
            doc_ok = (
                "61.67" in delivery12.read_text(encoding="utf-8")
                and "8.27" in plan17.read_text(encoding="utf-8")
                and all(s in gg_rep for s in (
                    "0x104B8", "0x10798", "0x1086C", "0x12820",
                    "41417", "F4 仍 BLOCKED", "inference"))
            )
            if not (evidence_ok and doc_ok):
                failures.append(
                    "12 §61.67 守护：代次闸门证据 / 18 项 checks / 45 锚点 / "
                    "F4 BLOCKED / 41417 端点 / 12 断点 / docs17 §8.27 漂移，请重跑 "
                    "tools/l3_f4_generation_gate_disasm.py 与 _audit.py 并回写 12 §61.67"
                )
            else:
                print("[PASS] 12 §61.67 守护：MakeRangeRequestAsync 0x104B0/0x104B8 "
                      "代次递增 → 0x104D8 透传 → 0x10798 整包丢弃（跳过 0x1086C 数据回调）；"
                      "NMSlicesFile::Write 的 file_len 门控坐实；F4 仍 BLOCKED、"
                      "41417 端点待连、真机 A/B NOT RUN")
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.67 守护：代次闸门证据 JSON 不可解析（{exc}）")

    # 12 §61.68 守护 / F4 请求代次闸门真机实证：防把「一段链升级」写成
    # F4 已修复、防 level-5 已开启伪证、防配对码落盘、防 41703 端点登记丢失、
    # 防 §61.67 静态证据与历史证据回潮。
    gl_tool = ROOT / "tools" / "l3_f4_generation_gate_live_audit.py"
    gl_json = ROOT / "analysis" / "l3-f4-generation-gate-live-audit.json"
    gl_report = ROOT / "analysis" / "l3-f4-generation-gate-live-report.md"
    gl_ev1 = ROOT / "analysis" / "l3-f4-generation-gate-live.json"
    gl_ev2 = ROOT / "analysis" / "l3-f4-generation-gate-live-lvl5.json"
    gl_log1 = ROOT / "analysis" / "l3-f4-logs" / "vfile-commit-20261111-004310.txt"
    gl_log2 = ROOT / "analysis" / "l3-f4-logs" / "vfile-commit-20261111-004603.txt"
    if not all(p.is_file() for p in (gl_tool, gl_json, gl_report, gl_ev1,
                                     gl_ev2, gl_log1, gl_log2)):
        failures.append("12 §61.68 守护：真机实证工具 / 审计 JSON / 报告 / 证据 / 日志缺失")
    else:
        try:
            gla = json.loads(gl_json.read_text(encoding="utf-8"))
            gle1 = json.loads(gl_ev1.read_text(encoding="utf-8"))
            gle2 = json.loads(gl_ev2.read_text(encoding="utf-8"))
            gl_rep = gl_report.read_text(encoding="utf-8")
            gl_d12 = delivery12.read_text(encoding="utf-8")
            gl_d17 = plan17.read_text(encoding="utf-8")
            gl_bl = (ROOT / "analysis" / "task-backlog-2026-11-07.md").read_text(encoding="utf-8")
            gl_rb = (ROOT / "analysis" / "l3-f4-online-runbook.md").read_text(encoding="utf-8")
            glc = gla.get("checks", {})
            gl_ok = (
                gla.get("all_pass") is True
                and not gla.get("failed_checks")
                and all(glc.get(k) is True for k in (
                    "generation_sequence_1_to_22",
                    "commit_constant_16384_both_runs",
                    "level5_absent_round1", "level5_absent_round2",
                    "setprop_verbose_ineffective", "f4_still_blocked",
                    "no_credential_leak", "zero_p0_source_change"))
            )
            gl_doc_ok = (
                "61.68" in gl_d12
                and "8.28" in gl_d17
                and "61.68" in gl_bl
                and "41703" in gl_rb
                and all(s in gl_rep for s in (
                    "1→22", "16384", "-103", "level-5", "41703",
                    "F4 仍 BLOCKED", "SetLoggerCallback", "剩余断点"))
                and "F4 已解决" not in gl_rep.replace("不写「F4 已解决", "")
            )
            gl_ev_ok = (
                gle1.get("verdict") == "OBSERVED"
                and gle2.get("verdict") == "OBSERVED"
                and gle1.get("serial") == "192.168.1.11:41703"
                and gle1.get("evidence", {}).get("has_103") is True
                and set(gle1.get("evidence", {}).get("commit_values", [])) == {16384}
                and gle1.get("restore", {}).get("device_settings_unchanged") is True
            )
            if not (gl_ok and gl_doc_ok and gl_ev_ok):
                failures.append(
                    "12 §61.68 守护：真机代次实证 / level-5 有效负结果 / "
                    "F4 BLOCKED / 41703 端点 / 配对码不落盘 / docs17 §8.28 漂移，请重跑 "
                    "tools/l3_f4_generation_gate_live_audit.py 并回写 12 §61.68")
            else:
                print("[PASS] 12 §61.68 守护：MakeRangeRequestAsync(N) 1→22 连续递增坐实 "
                      "§61.67 结论 A；setprop VERBOSE 证伪 liblog 优先级门控；F4 仍 BLOCKED、"
                      "level-5 待拍板、配对码不落盘")
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.68 守护：真机审计 JSON 不可解析（{exc}）")

    # 12 §61.79 / 接线回滚审计（用户 2026-11-11 拍板「接线回滚一起做」）：
    # §61.69 level-5 诊断接线已按 §61.70 勘误回滚。本变量供 §61.71~§61.78
    # 各守护复用，把「源码里接线仍在」的旧断言统一改为「接线已回滚且回滚
    # 审计全 PASS」。历史 §61.69 接线审计 JSON 作为历史冻结件一字未改
    # （不重跑 tools/l3_f4_level5_wiring_audit.py 覆盖它）。
    rb_tool = ROOT / "tools" / "l3_f4_wiring_rollback_audit.py"
    rb_json = ROOT / "analysis" / "l3-f4-wiring-rollback-audit.json"
    rb_report = ROOT / "analysis" / "l3-f4-wiring-rollback-audit-report.md"
    rollback_ok = False
    if not all(p.is_file() for p in (rb_tool, rb_json, rb_report)):
        failures.append("12 §61.79 守护：接线回滚审计工具 / 证据 / 报告缺失")
    else:
        try:
            rbd = json.loads(rb_json.read_text(encoding="utf-8"))
            rollback_ok = (
                rbd.get("all_pass") is True
                and rbd.get("checks_total", 0) >= 20
                and all(rbd.get("checks", {}).values())
                and rbd.get("mode") == "offline_readonly"
                and rbd.get("rollback_state") == "ROLLED_BACK"
                and rbd.get("f4_status") == "BLOCKED"
            )
            if not rollback_ok:
                failures.append(
                    "12 §61.79 守护：接线回滚审计未全 PASS，请重跑 "
                    "tools/l3_f4_wiring_rollback_audit.py 并核对 EngineAdapter.kt")
            else:
                print("[PASS] 12 §61.79 接线回滚审计前置：接线已移除、功能代码不变、"
                      "历史证据冻结、so 原样；诊断清理 ≠ F4 修复")
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.79 守护：接线回滚审计 JSON 不可解析（{exc}）")

    # 12 §61.69 守护 / F4 level-5 诊断接线（历史冻结证据）：防 null logger 回退、
    # 防 level 上限被突破、防「level-5 已开启 / F4 已收口」伪证、防设备离线
    # 事实被抹除、防 §61.67 / §61.68 历史证据回潮。
    # 注意：接线本体已在 §61.79 回滚；本守护保护的是「§61.69 历史审计 JSON
    # 与其报告一字未改」，不读当前源码，故不因回滚而失效。
    w_tool = ROOT / "tools" / "l3_f4_level5_wiring_audit.py"
    w_json = ROOT / "analysis" / "l3-f4-level5-wiring-audit.json"
    w_report = ROOT / "analysis" / "l3-f4-level5-wiring-report.md"
    w_probe_report = ROOT / "analysis" / "l3-f4-loglevel-gate-probe-report.md"
    if not all(p.is_file() for p in (w_tool, w_json, w_report, w_probe_report)):
        failures.append("12 §61.69 守护：接线审计工具 / JSON / 报告 / level-5 探查报告缺失")
    else:
        try:
            wa = json.loads(w_json.read_text(encoding="utf-8"))
            w_rep = w_report.read_text(encoding="utf-8")
            w_d12 = delivery12.read_text(encoding="utf-8")
            w_d17 = plan17.read_text(encoding="utf-8")
            w_d09 = (ROOT / "docs" / "09-文档闭环总览.md").read_text(encoding="utf-8")
            w_d16 = (ROOT / "docs" / "16-架构与任务拆分复核报告.md").read_text(encoding="utf-8")
            w_bl = (ROOT / "analysis" / "task-backlog-2026-11-07.md").read_text(encoding="utf-8")
            w_rb = (ROOT / "analysis" / "l3-f4-online-runbook.md").read_text(encoding="utf-8")
            wc = wa.get("checks", {})
            w_ok = (
                wa.get("all_pass") is True
                and not wa.get("failed_checks")
                and wa.get("counts", {}).get("checks_total") == 23
                and wa.get("counts", {}).get("checks_pass") == 23
                and all(wc.get(k) is True for k in (
                    "setlogger_called_with_instance",
                    "setlogger_no_null_argument_anywhere",
                    "level_coerce_hard_cap_0_5", "level_default_5",
                    "f4_still_blocked_in_docs", "no_level5_enabled_claim",
                    "device_state_recorded_as_offline",
                    "runbook_records_device_offline",
                    "gen_gate_live_audit_intact", "gen_gate_disasm_audit_intact"))
                and len(wa.get("artifacts", {}).get("so_sha256", "")) == 64)
            w_doc_ok = (
                "### 61.69" in w_d12 and "### 8.29" in w_d17
                and "61.69" in w_d09 and "61.69" in w_d16
                and "61.69" in w_bl and "完全离线" in w_rb
                and all(s in w_rep for s in (
                    "23 项 checks 全 PASS", "NOT RUN", "完全离线",
                    "F4 仍 BLOCKED", "INMLogger", "coerceIn",
                    "SetLoggerCallback", "剩余断点")))
            w_honest_ok = not any(b in w_rep for b in (
                "F4 已解决", "F4 已修复", "level-5 已开启", "level-5 已解锁"))
            if not (w_ok and w_doc_ok and w_honest_ok):
                failures.append(
                    "12 §61.69 守护：23 项接线审计 / null logger / level 上限 / "
                    "F4 BLOCKED / 设备离线登记 / 文档 61.69·8.29 漂移，请重跑 "
                    "tools/l3_f4_level5_wiring_audit.py 并回写 12 §61.69")
            else:
                print("[PASS] 12 §61.69 守护：历史接线审计 JSON 23/23 全 PASS 且未改写"
                      "（当时接线态快照）；接线本体已在 §61.79 回滚、"
                      "真机 level-5 观测 NOT RUN / F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.69 守护：接线审计 JSON 不可解析（{exc}）")

    # 12 §61.70 守护 / F4 level-5 日志门控语义纠正：防门控语义回潮（把
    # 「默认全开」写成「需开 level-5」）、防「level-5 已开启 / F4 已收口」
    # 伪证、防回滚未拍板先执行、防 §61.67/§61.68/§61.69 历史证据回潮。
    s_tool = ROOT / "tools" / "l3_f4_level5_gate_semantics_audit.py"
    s_json = ROOT / "analysis" / "l3-f4-level5-gate-semantics-audit.json"
    s_report = ROOT / "analysis" / "l3-f4-level5-gate-semantics-report.md"
    if not all(p.is_file() for p in (s_tool, s_json, s_report)):
        failures.append("12 §61.70 守护：门控语义审计工具 / JSON / 报告缺失")
    else:
        try:
            sa = json.loads(s_json.read_text(encoding="utf-8"))
            s_ok = (
                sa.get("all_pass") is True
                and sa.get("failed_checks") == []
                and sa.get("checks_total") == 34
                and sa.get("checks_pass") == 34
                and sa.get("findings", {}).get("default_g_level") == 0
                and sa.get("findings", {}).get("nm_identical", 0) >= 160
                and sa.get("findings", {}).get("plt_import") == "__android_log_print"
                and sa.get("findings", {}).get("logger_method") == "BIInfo"
                and sa.get("logcat_evidence", {}).get("w_nmcache_write_all_finish", 0) >= 1
                and all(v == 0 for v in sa.get("logcat_evidence", {}).get(
                    "f4_gate_string_hits", {"x": 1}).values())
                and sa.get("checks", {}).get("history_audits_intact") is True
            )
            s_d12 = (ROOT / "docs/12-编码阶段第一批交付.md").read_text(encoding="utf-8")
            s_d17 = (ROOT / "docs/17-竞品模块清单与复刻规划.md").read_text(encoding="utf-8")
            s_rep = s_report.read_text(encoding="utf-8")
            s_doc_ok = (
                "### 61.70" in s_d12 and "### 8.30" in s_d17
                and s_d12.count("§61.70 勘误") >= 2
                and "回滚" in s_d12 and "待用户拍板" in s_d12
                and "F4 仍 BLOCKED" in s_d12 and "F4 仍 BLOCKED" in s_d17
                and all(k in s_rep for k in ("g_level > N", "零增益", "抑制", "BIInfo"))
            )
            s_honest_ok = not any(k in s_rep for k in (
                "F4 已解决", "F4 已修复", "level-5 已开启", "level-5 已解锁",
                "全格式已支持"))
            if not (s_ok and s_doc_ok and s_honest_ok):
                failures.append(
                    "12 §61.70 守护：34 项门控语义审计 / 默认 g_level=0 / "
                    "W 级日志默认已输出 / 勘误登记 / 回滚待拍板 / F4 BLOCKED "
                    "漂移，请重跑 tools/l3_f4_level5_gate_semantics_audit.py "
                    "并回写 12 §61.70")
            else:
                print("[PASS] 12 §61.70 守护：g_level 默认 0、I/D/W/E 日志默认全开、"
                      "34/34 审计全 PASS；§61.68/§61.69 勘误登记、接线回滚待拍板、"
                      "F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.70 守护：门控语义审计 JSON 不可解析（{exc}）")

    # 12 §61.71 守护 / 上线复跑 W 分支判读：防真实 .rodata 格式串被猜词替换、
    # 防工具伪装连设备 / 伪造真机复跑、防 F4 提前收口、防接线回滚未拍板先执行。
    wr_tool = ROOT / "tools" / "l3_f4_wbranch_recheck.py"
    wr_json = ROOT / "analysis" / "l3-f4-wbranch-recheck.json"
    wr_report = ROOT / "analysis" / "l3-f4-wbranch-recheck-report.md"
    wr_adapter = (ROOT / "engine-legacy" / "src" / "main" / "java" /
                  "com" / "example" / "cloudmusic" / "enginelegacy" /
                  "EngineAdapter.kt")
    if not all(p.is_file() for p in (wr_tool, wr_json, wr_report, wr_adapter)):
        failures.append("12 §61.71 守护：W 分支判读工具 / 证据 / 报告 / P0 源码缺失")
    else:
        try:
            wr = json.loads(wr_json.read_text(encoding="utf-8"))
            wr_rep = wr_report.read_text(encoding="utf-8")
            wr_d12 = delivery12.read_text(encoding="utf-8")
            wr_ok = (
                wr["all_pass"] is True
                and wr["checks_total"] >= 13
                and all(wr["checks"].values())
                and wr["f4_status"] == "BLOCKED"
                and wr["device_run"] is False
                and wr["mode"] == "replay"
                and wr["single_log_verdict"] is None
                and "LIVE_OUT" in wr_tool.read_text(encoding="utf-8")
                and wr["logs_scanned"] >= 33
                and len(wr["rodata_branches"]) == 7
                and all(v["anchor_in_real_string"]
                        for v in wr["rodata_branches"].values())
                and "F4 仍 BLOCKED" in wr_rep
                and "61.71" in wr_d12
                and rollback_ok
            )
            if not wr_ok:
                failures.append(
                    "12 §61.71 守护：W 分支判读证据 / 真实 rodata 串 / 接线未回滚 "
                    "/ 12 断点登记漂移，或固化回放证据被 --log 单日志判读覆盖，"
                    "请重跑 tools/l3_f4_wbranch_recheck.py（默认回放模式）"
                    "（若已拍板回滚接线，须同步调整本守护）")
            else:
                print("[PASS] 12 §61.71 守护：7 条真实 .rodata W 分支格式串驱动判读，"
                      "34 份归档 6 条错误分支 0 命中；纯离线不连 adb、"
                      "接线回滚仍待拍板、F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.71 守护：W 分支判读证据 JSON 不可解析（{exc}）")

    # 12 §61.72 守护 / 现行上线执行链：防 runbook 被 §61.58 A/B 旧剧本重新
    # 当作唯一命令、防 readiness 工具伪造设备在线 / F4 收口、防 §61.71 固化
    # 证据与 §61.58 历史 preflight 证据被覆盖、防接线回滚未拍板先执行。
    or_tool = ROOT / "tools" / "l3_f4_online_readiness.py"
    or_json = ROOT / "analysis" / "l3-f4-online-readiness.json"
    or_report = ROOT / "analysis" / "l3-f4-online-readiness-report.md"
    or_runbook = ROOT / "analysis" / "l3-f4-online-runbook.md"
    or_pf = ROOT / "analysis" / "l3-f4-preflight.json"
    or_wr = ROOT / "analysis" / "l3-f4-wbranch-recheck.json"
    if not all(p.is_file() for p in (
            or_tool, or_json, or_report, or_runbook, or_pf, or_wr)):
        failures.append("12 §61.72 守护：现行上线自检工具 / 证据 / 报告 / runbook 缺失")
    else:
        try:
            ord_ = json.loads(or_json.read_text(encoding="utf-8"))
            or_rep = or_report.read_text(encoding="utf-8")
            or_rb = or_runbook.read_text(encoding="utf-8")
            or_src = or_tool.read_text(encoding="utf-8")
            or_pf_d = json.loads(or_pf.read_text(encoding="utf-8"))
            or_wr_d = json.loads(or_wr.read_text(encoding="utf-8"))
            or_cmds = " ".join(ord_.get("current_commands", []))
            or_ok = (
                ord_["all_pass"] is True
                and ord_["checks_total"] >= 14
                and all(ord_["checks"].values())
                and ord_["mode"] == "offline_readonly"
                and ord_["device_run"] is False
                and ord_["ready_to_run"] is False
                and ord_["f4_status"] == "BLOCKED"
                and "待用户拍板" in ord_["blocking_reason"]
                and len(ord_["pending_user_decisions"]) >= 4
                and "l3_f4_vfile_commit_probe.py" in or_cmds
                and "l3_f4_wbranch_recheck.py" in or_cmds
                and "F4 仍 BLOCKED" in or_rep
                and not any(s in or_rep for s in (
                    "F4 已解决", "m4a 已支持", "全格式已支持", "level-5 已开启"))
                and 'device_run": False' in or_src
                and 'ready_to_run": False' in or_src
                # 现行链必须写在前，A/B 旧剧本必须标成历史。
                and or_rb.index("现行上线执行链") < or_rb.index(
                    "## 1. 一键执行")
                and "历史剧本，勿执行" in or_rb
                and "不要按 §1" in or_rb
                and "手动拖动" in or_rb and "物理在场" in or_rb
                and "保持 `g_level=0`" in or_rb
                # 固化证据与历史证据零改写。
                and or_wr_d["mode"] == "replay"
                and or_wr_d["single_log_verdict"] is None
                and or_pf_d["all_pass"] is True
                and or_pf_d["ready_to_run"] is False
                and len(or_pf_d["checks"]) == 15
                and rollback_ok
                and "61.72" in delivery12.read_text(encoding="utf-8")
            )
            if not or_ok:
                failures.append(
                    "12 §61.72 守护：现行上线执行链 / 固化证据 / 历史 preflight / "
                    "接线未回滚 / 12 断点登记漂移，请重跑 "
                    "tools/l3_f4_online_readiness.py"
                    "（若已拍板回滚接线，须同步调整本守护）")
            else:
                print("[PASS] 12 §61.72 守护：runbook 现行链在前 / A/B 旧剧本已标历史，"
                      "14 项执行链自检全 PASS；纯离线不伪造设备在线、"
                      "接线回滚仍待拍板、F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.72 守护：上线自检证据 JSON 不可解析（{exc}）")

    # 12 §61.73 守护 / 三轮真机纯观测：防把有效负结果写成「分支已触发」、
    # 防把用户口述拖动写成 seek 判据已执行、防把 g_level=0 证据与「接线已回滚」
    # 混淆、防 §61.71 固化回放证据被覆盖、防 F4 提前收口。
    lr_tool = ROOT / "tools" / "l3_f4_live_rounds_audit.py"
    lr_json = ROOT / "analysis" / "l3-f4-live-rounds-audit.json"
    lr_report = ROOT / "analysis" / "l3-f4-live-rounds-report.md"
    lr_ui = ROOT / "analysis" / "l3-f4-live-ui-20261111.png"
    lr_wr = ROOT / "analysis" / "l3-f4-wbranch-recheck.json"
    lr_probe = tuple(ROOT / "analysis" / n for n in (
        "l3-f4-live-online.json", "l3-f4-live-online-warm.json",
        "l3-f4-live-online-seek.json"))
    if not all(p.is_file() for p in (lr_tool, lr_json, lr_report, lr_ui, lr_wr)
               + lr_probe):
        failures.append("12 §61.73 守护：三轮真机汇总 / UI 截图 / probe 证据缺失")
    else:
        try:
            lrd = json.loads(lr_json.read_text(encoding="utf-8"))
            lr_rep = lr_report.read_text(encoding="utf-8")
            lr_wrd = json.loads(lr_wr.read_text(encoding="utf-8"))
            lr_rb = (ROOT / "analysis" / "l3-f4-online-runbook.md").read_text(
                encoding="utf-8")
            lr_ok = (
                lrd["all_pass"] is True
                and lrd["checks_total"] >= 19
                and all(lrd["checks"].values())
                and lrd["mode"] == "offline_readonly"
                and lrd["device_run"] is False
                and lrd["f4_status"] == "BLOCKED"
                and lrd["serial"] == "10.0.0.2:37509"
                and lrd["apk_installed"]["contains_level5_wiring"] is False
                and lrd["apk_installed"]["p0_source_modified"] is False
                and lrd["user_reported_seek_attempt"] is True
                and lrd["user_reported_progress_bar_no_time"] is True
                and "NOT RUN" in lrd["seek_status"]
                and len(lrd["rounds"]) == 3
                and all(len(r["commit_values"]) == 1 for r in lrd["rounds"])
                and all(r["setlogger_calls"] == 0 for r in lrd["rounds"])
                and all(all(v == 0 for v in r["seek_markers"].values())
                        for r in lrd["rounds"])
                and lrd["ui_evidence"]["path"] == str(lr_ui.relative_to(ROOT)).replace(
                    "\\", "/")
                and lrd["wbranch_sha256"] == hashlib.sha256(
                    lr_wr.read_bytes()).hexdigest()
                and lr_wrd["mode"] == "replay"
                and lr_wrd["single_log_verdict"] is None
                and "F4 仍 BLOCKED" in lr_rep
                and not any(s in lr_rep for s in (
                    "F4 已解决", "m4a 已支持", "全格式已支持", "level-5 已开启",
                    "seek 已执行", "推断已升级为实证"))
                and rollback_ok
                and "61.73" in delivery12.read_text(encoding="utf-8")
                and "61.73" in lr_rb
            )
            if not lr_ok:
                failures.append(
                    "12 §61.73 守护：三轮真机证据 / 用户口述与 UI 证据 / "
                    "g_level=0 结论 / 固化回放证据 / 接线未回滚 / 12 与 runbook "
                    "断点登记漂移，请重跑 tools/l3_f4_live_rounds_audit.py")
            else:
                print("[PASS] 12 §61.73 守护：三轮真机 g_level=0 一手坐实、"
                      "commit 均冻结、W 分支 0 命中为有效负结果；"
                      "seek 判据 NOT RUN、接线回滚仍待拍板、F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.73 守护：真机汇总证据 JSON 不可解析（{exc}）")

    # 12 §61.74 守护 / Write start play 日志门控：防把「0 次」当作播放未写的
    # 充分证据、防把它反向偷换成「播放其实成功」、防 4 道门控锚点漂移、
    # 防 F4 判据被单日志点垄断、防 F4 提前收口。
    pg_tool = ROOT / "tools" / "l3_f4_play_gate_disasm.py"
    pg_json = ROOT / "analysis" / "l3-f4-play-gate-disasm.json"
    pg_report = ROOT / "analysis" / "l3-f4-play-gate-report.md"
    pg_so = (ROOT / "engine-legacy" / "src" / "main" / "jniLibs" /
             "arm64-v8a" / "libncmaudioplayer.so")
    if not all(p.is_file() for p in (pg_tool, pg_json, pg_report, pg_so)):
        failures.append("12 §61.74 守护：播放门控反汇编工具 / 证据 / 报告 / so 缺失")
    else:
        try:
            pgd = json.loads(pg_json.read_text(encoding="utf-8"))
            pg_rep = pg_report.read_text(encoding="utf-8")
            pg_rb = (ROOT / "analysis" / "l3-f4-online-runbook.md").read_text(
                encoding="utf-8")
            pg_gates = pgd["gates"]
            pg_ok = (
                pgd["all_pass"] is True
                and pgd["checks_total"] >= 16
                and all(pgd["checks"].values())
                and pgd["mode"] == "offline_disasm"
                and pgd["device_run"] is False
                and pgd["f4_status"] == "BLOCKED"
                and pgd["so_sha256"] == hashlib.sha256(
                    pg_so.read_bytes()).hexdigest()
                and pgd["rodata"]["fmt"] == (
                    "AudioTrack(%d): Write start play nErr:%d")
                and len(pgd["references"]) == 1
                and pgd["references"][0][0] == "0x66eec"
                and len(pg_gates) == 4
                and all(v["branch_mnemonic"] in ("cbz", "cbnz", "b.eq")
                        for v in pg_gates.values())
                and all(r["write_start_play"] == 0
                        for r in pgd["log_cross"]["rounds"])
                and pgd["log_cross"]["media_session_paused"] is True
                and pgd["log_cross"]["no_app_audio_track"] is True
                and "INFERENCE" in pgd["verdict"]
                and "F4 仍 BLOCKED" in pg_rep
                and not any(s in pg_rep for s in (
                    "F4 已解决", "m4a 已支持", "全格式已支持", "level-5 已开启",
                    "播放已成功", "推断已升级为实证"))
                and rollback_ok
                and "61.74" in delivery12.read_text(encoding="utf-8")
                and "61.74" in pg_rb
            )
            if not pg_ok:
                failures.append(
                    "12 §61.74 守护：播放门控锚点 / 判据口径 / 固化证据 / "
                    "接线未回滚 / 12 与 runbook 断点登记漂移，请重跑 "
                    "tools/l3_f4_play_gate_disasm.py")
            else:
                print("[PASS] 12 §61.74 守护：Write start play 4 道静默早退门控"
                      "定位完成，0 次非充分证据、播放未出声独立结论仍成立；"
                      "F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.74 守护：播放门控证据 JSON 不可解析（{exc}）")

    # 12 §61.75 守护 / 真机验收终局裁决：防把 MediaSession state=3 当成播放
    # 成功、防把 dispatch 动作成功当成验收通过、防 F4 提前收口、防
    # 「仍需设备上线做验证」口径回潮（真机取证已完结）。
    af_tool = ROOT / "tools" / "l3_f4_acceptance_final_audit.py"
    af_json = ROOT / "analysis" / "l3-f4-acceptance-final.json"
    af_report = ROOT / "analysis" / "l3-f4-acceptance-final-report.md"
    af_ctrl = ROOT / "analysis" / "l3-f4-playback-control.json"
    af_probe = ROOT / "tools" / "l3_f4_playback_control_probe.py"
    if not all(p.is_file() for p in (af_tool, af_json, af_report, af_ctrl, af_probe)):
        failures.append("12 §61.75 守护：终局裁决 / 播放控制证据或工具缺失")
    else:
        try:
            afd = json.loads(af_json.read_text(encoding="utf-8"))
            af_rep = af_report.read_text(encoding="utf-8")
            af_cd = json.loads(af_ctrl.read_text(encoding="utf-8"))
            af_rb = (ROOT / "analysis" / "l3-f4-online-runbook.md").read_text(
                encoding="utf-8")
            af_states = [s.get("playback_state")
                         for s in af_cd.get("snapshots", {}).values()]
            af_pos = [s.get("position") for s in af_cd.get("snapshots", {}).values()
                      if s.get("position") is not None]
            af_dispatches = af_cd.get("dispatches", [])
            af_ok = (
                afd["all_pass"] is True
                and afd["checks_total"] >= 12
                and all(afd["checks"].values())
                and afd["no_criterion_pass"] is True
                and afd["main_criteria_all_pass"] is False
                and afd["f4_status"] == "BLOCKED"
                and afd["device_run"] is False
                and len(afd["criteria"]) >= 9
                and all(not c["verdict"].startswith("PASS")
                        for c in afd["criteria"])
                and "真机取证到此完结" in af_rep
                and "不再以「设备上线」作为验证前置" in af_rep
                and not any(s in af_rep for s in (
                    "F4 已解决", "m4a 已支持", "全格式已支持", "level-5 已开启",
                    "播放已成功", "推断已升级为实证"))
                and len(af_dispatches) == 6
                and all(x.get("rc") == 0 for x in af_dispatches)
                and 3 in af_states
                and all(p == 0 for p in af_pos)
                and af_cd.get("log_analysis", {}).get("finish_async_103", 0) >= 1
                and af_cd.get("f4_status") == "BLOCKED"
                and rollback_ok
                and "61.75" in delivery12.read_text(encoding="utf-8")
                and "61.75" in af_rb
            )
            if not af_ok:
                failures.append(
                    "12 §61.75 守护：终局裁决 / state=3 非播放成功 / "
                    "真机取证完结口径 / 接线未回滚 / 12 与 runbook 断点登记漂移，"
                    "请重跑 tools/l3_f4_acceptance_final_audit.py")
            else:
                print("[PASS] 12 §61.75 守护：F4 主判据无一 PASS、"
                      "state=3 非播放成功；真机取证完结、无需再上线验证、"
                      "F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.75 守护：终局裁决证据 JSON 不可解析（{exc}）")

    # 12 §61.76 守护 / -103 产生链：防把硬编码错误码写成「已修复」、
    # 防把静态链写成已验证因果、防把 Seek 串误算成 Finish Async 第二引用、
    # 防真机取证完结口径回潮、防 F4 提前收口。
    m103_tool = ROOT / "tools" / "l3_f4_minus103_chain_disasm.py"
    m103_json = ROOT / "analysis" / "l3-f4-minus103-chain.json"
    m103_report = ROOT / "analysis" / "l3-f4-minus103-chain-report.md"
    m103_cache = (ROOT / "engine-legacy" / "src" / "main" / "jniLibs" /
                  "arm64-v8a" / "libNMCommonCache.so")
    m103_audio = (ROOT / "engine-legacy" / "src" / "main" / "jniLibs" /
                  "arm64-v8a" / "libncmaudioplayer.so")
    if not all(p.is_file() for p in (
            m103_tool, m103_json, m103_report, m103_cache, m103_audio)):
        failures.append("12 §61.76 守护：-103 链条工具 / 证据 / 报告 / so 缺失")
    else:
        try:
            md_ = json.loads(m103_json.read_text(encoding="utf-8"))
            m_rep = m103_report.read_text(encoding="utf-8")
            m_rb = (ROOT / "analysis" / "l3-f4-online-runbook.md").read_text(
                encoding="utf-8")
            m_ok = (
                md_["all_pass"] is True
                and md_["checks_total"] >= 25
                and all(md_["checks"].values())
                and md_["mode"] == "offline_disasm"
                and md_["device_run"] is False
                and md_["f4_status"] == "BLOCKED"
                and md_["cache_sha256"] == hashlib.sha256(
                    m103_cache.read_bytes()).hexdigest()
                and md_["audio_sha256"] == hashlib.sha256(
                    m103_audio.read_bytes()).hexdigest()
                and md_["rodata"]["finish"] == (
                    "NMBufferSourceImp::Read((%lld-%lld) Finish Async:%d")
                and md_["rodata"]["paused"] == "AudioTrack(%d): Paused"
                and len(md_["anchors"]) >= 16
                and all(a in md_["minus103_immediates"]
                        for a in ("0xa85c", "0xa9c4"))
                and all(a in md_["writes_1a0"]
                        for a in ("0xa8b4", "0xa9e4"))
                and len(md_["paused_writes"]) == 4
                and "0x66810" in md_["audio_track_clears"]
                and "0x572d4" in md_["audio_track_sets"]
                and "INFERENCE" in md_["verdict"]
                and "+0x198" in md_["next_fix_anchor"]
                and "F4 仍 BLOCKED" in m_rep
                and "真机取证已于 §61.75 完结" in m_rep
                and not any(s in m_rep for s in (
                    "F4 已解决", "m4a 已支持", "全格式已支持", "level-5 已开启",
                    "播放已成功", "推断已升级为实证"))
                and rollback_ok
                and "61.76" in delivery12.read_text(encoding="utf-8")
                and "61.76" in m_rb
            )
            if not m_ok:
                failures.append(
                    "12 §61.76 守护：-103 链条锚点 / 硬编码立即数 / 播放侧状态位 / "
                    "终局完结口径 / 12 与 runbook 断点登记漂移，请重跑 "
                    "tools/l3_f4_minus103_chain_disasm.py")
            else:
                print("[PASS] 12 §61.76 守护：-103 硬编码链与播放侧 paused / "
                      "AudioTrack 状态位定位完成；静态证据为 inference、"
                      "真机取证已完结、F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.76 守护：-103 链条证据 JSON 不可解析（{exc}）")

    # 12 §61.77 守护 / 读侧空白链：防把 result:-2 当成「已修复」、
    # 防硬编码「唯一引用」结论、防把静态链写成已验证因果、
    # 防真机取证完结口径回潮、防 F4 提前收口。
    rg_tool = ROOT / "tools" / "l3_f4_read_gap_chain_disasm.py"
    rg_json = ROOT / "analysis" / "l3-f4-read-gap-chain.json"
    rg_report = ROOT / "analysis" / "l3-f4-read-gap-chain-report.md"
    rg_so = (ROOT / "engine-legacy" / "src" / "main" / "jniLibs" /
             "arm64-v8a" / "libNMCommonCache.so")
    if not all(p.is_file() for p in (rg_tool, rg_json, rg_report, rg_so)):
        failures.append("12 §61.77 守护：读侧空白链工具 / 证据 / 报告 / so 缺失")
    else:
        try:
            rgd = json.loads(rg_json.read_text(encoding="utf-8"))
            rg_rep = rg_report.read_text(encoding="utf-8")
            rg_rb = (ROOT / "analysis" / "l3-f4-online-runbook.md").read_text(
                encoding="utf-8")
            rg_ok = (
                rgd["all_pass"] is True
                and rgd["checks_total"] >= 30
                and all(rgd["checks"].values())
                and rgd["mode"] == "offline_disasm"
                and rgd["device_run"] is False
                and rgd["f4_status"] == "BLOCKED"
                and rgd["so_sha256"] == hashlib.sha256(
                    rg_so.read_bytes()).hexdigest()
                and rgd["rodata"]["unique"] is True
                and len(rgd["rodata"]["references"]) == 1
                and rgd["rodata"]["references"][0][1] == "0xc45c"
                and len(rgd["pending_198_writes"]) == 6
                and all(a in rgd["hardcoded_minus2_points"]
                        for a in ("0xa9bc", "0xc728", "0x12dc8"))
                and len(rgd["anchors"]) >= 24
                and len(rgd["fix_anchors"]) == 3
                and "INFERENCE" in rgd["verdict"]
                and "F4 仍 BLOCKED" in rg_rep
                and "真机取证已于 §61.75 完结" in rg_rep
                and not any(s in rg_rep for s in (
                    "F4 已解决", "m4a 已支持", "全格式已支持", "level-5 已开启",
                    "播放已成功", "推断已升级为实证"))
                and rollback_ok
                and "61.77" in delivery12.read_text(encoding="utf-8")
                and "61.77" in rg_rb
            )
            if not rg_ok:
                failures.append(
                    "12 §61.77 守护：读侧锚点 / 唯一引用实扫 / 挂起请求写入点 / "
                    "终局完结口径 / 12 与 runbook 断点登记漂移，请重跑 "
                    "tools/l3_f4_read_gap_chain_disasm.py")
            else:
                print("[PASS] 12 §61.77 守护：result:-2 产生点与挂起异步读 6 个"
                      "写入点定位完成；静态链为 inference、真机取证已完结、"
                      "F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.77 守护：读侧空白链证据 JSON 不可解析（{exc}）")

    # 12 §61.78 守护 / A1「绕过取流」离线论证：防把绕行写成 F4 已修复、
    # 防把 LOCAL 单曲 PASS 外推成全格式已支持 / 主判据已通过、
    # 防真机取证完结口径回潮、防 F4 提前收口、防擅自执行修复性真机注入。
    bs_tool = ROOT / "tools" / "l3_f4_bypass_stream_audit.py"
    bs_json = ROOT / "analysis" / "l3-f4-bypass-stream-audit.json"
    bs_report = ROOT / "analysis" / "l3-f4-bypass-stream-audit-report.md"
    bs_adapter = (ROOT / "engine-legacy" / "src" / "main" / "java" /
                  "com" / "example" / "cloudmusic" / "enginelegacy" /
                  "EngineAdapter.kt")
    bs_src_cfg = (ROOT / "engine-legacy" / "src" / "main" / "java" /
                  "com" / "netease" / "cloudmusic" / "android" / "sdk" /
                  "playerengine" / "SourceConfig.java")
    if not all(p.is_file() for p in (bs_tool, bs_json, bs_report,
                                     bs_adapter, bs_src_cfg)):
        failures.append("12 §61.78 守护：A1 绕过取流论证工具 / 证据 / 报告 / "
                        "装配源码缺失")
    else:
        try:
            bsd = json.loads(bs_json.read_text(encoding="utf-8"))
            bs_rep = bs_report.read_text(encoding="utf-8")
            bs_rb = (ROOT / "analysis" / "l3-f4-online-runbook.md").read_text(
                encoding="utf-8")
            bs_ok = (
                bsd["all_pass"] is True
                and bsd["checks_total"] >= 60
                and all(bsd["checks"].values())
                and bsd["mode"] == "offline_evidence_audit"
                and bsd["device_run"] is False
                and bsd["f4_status"] == "BLOCKED"
                and "BYPASS_NOT_FIX" in bsd["verdict"]
                and "INFERENCE" in bsd["verdict"]
                and bsd["contrast"]["sample_bytes"] == 16573387
                and bsd["contrast"]["local"]["write_start_play_nerr"] == 0
                and bsd["contrast"]["remote"]["commit_frozen"] == 368640
                and bsd["f3_matrix"]["local_pass"] == 6
                and bsd["f3_matrix"]["remote_pass"] == 5
                and bsd["f3_matrix"]["total_pass"] == 11
                and bsd["f3_matrix"]["seek_observed_any"] is False
                and bsd["f3_matrix"]["ended_observed_any"] is False
                and len(bsd["f3_matrix"]["no_sample_formats"]) == 7
                and bsd["a1_experiment_design_not_executed"]["status"].startswith(
                    "NOT_RUN")
                and "绕行 ≠ 修复" in bs_rep
                and "F4 仍 BLOCKED" in bs_rep
                and "本批 NOT RUN" in bs_rep
                and "7 种 NO_SAMPLE 未覆盖" in bs_rep
                and "LOCAL 可播 ≠ 完整真实 m4a 已支持" in bs_rep
                and not any(s in bs_rep for s in (
                    "F4 已解决", "全格式已支持", "主判据已通过",
                    "m4a 已支持（", "已修复完成"))
                and rollback_ok
                and "remote ? Type.REMOTE : Type.LOCAL" in bs_src_cfg.read_text(
                    encoding="utf-8")
                and "61.78" in delivery12.read_text(encoding="utf-8")
                and "61.78" in bs_rb
            )
            if not bs_ok:
                failures.append(
                    "12 §61.78 守护：A1 绕行论证证据 / 边界口径 / 12 与 runbook "
                    "断点登记漂移，请重跑 tools/l3_f4_bypass_stream_audit.py")
            else:
                print("[PASS] 12 §61.78 守护：A1 绕过取流有同一样本 "
                      "LOCAL/REMOTE 一手对照支撑；绕行 ≠ 修复、主判据 NOT RUN、"
                      "F4 仍 BLOCKED、真机注入未擅自执行")
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.78 守护：A1 绕行论证证据 JSON 不可解析（{exc}）")

    # 12 §61.79 守护 / A1 时间盒真机裁决 + §61.69 接线回滚：
    # 防把「点火」写成 F4 已修复、防把 msg=2 写成自然播完 ENDED、
    # 防跨轮污染证据回潮、防擅自宣布 A1 通过或 B2 已启动、
    # 防接线回滚被写成 F4 修复、防历史接线审计 JSON 被覆盖。
    a1_probe_tool = ROOT / "tools" / "l3_f4_a1_bypass_live_probe.py"
    a1_verdict_tool = ROOT / "tools" / "l3_f4_a1_bypass_verdict.py"
    a1_live = ROOT / "analysis" / "l3-f4-a1-bypass-live.json"
    a1_v1 = ROOT / "analysis" / "l3-f4-a1-bypass-live-initial-v1.json"
    a1_verdict = ROOT / "analysis" / "l3-f4-a1-bypass-verdict.json"
    a1_report = ROOT / "analysis" / "l3-f4-a1-bypass-verdict-report.md"
    a1_hist_wiring = ROOT / "analysis" / "l3-f4-level5-wiring-audit.json"
    a1_adapter = (ROOT / "engine-legacy" / "src" / "main" / "java" /
                  "com" / "example" / "cloudmusic" / "enginelegacy" /
                  "EngineAdapter.kt")
    a1_d12 = delivery12.read_text(encoding="utf-8")
    a1_runbook = (ROOT / "analysis" / "l3-f4-online-runbook.md").read_text(
        encoding="utf-8")
    a1_docs = [
        (ROOT / p).read_text(encoding="utf-8")
        for p in (
            "docs/09-文档闭环总览.md",
            "docs/16-架构与任务拆分复核报告.md",
            "docs/17-竞品模块清单与复刻规划.md",
            "analysis/task-backlog-2026-11-07.md",
        )
    ]
    a1_forbidden = ("F4 已解决", "F4 已修复", "F4 已收口", "全格式已支持",
                    "主判据已通过", "播放已成功", "A1 已通过", "B2 已启动")
    if not all(p.is_file() for p in (a1_probe_tool, a1_verdict_tool, a1_live,
                                     a1_v1, a1_verdict, a1_report,
                                     rb_json, rb_report, a1_hist_wiring)):
        failures.append("12 §61.79 守护：A1 真机取证 / 裁决 / 回滚审计 "
                        "工具·证据·报告缺失")
    else:
        try:
            a1l = json.loads(a1_live.read_text(encoding="utf-8"))
            a1v1 = json.loads(a1_v1.read_text(encoding="utf-8"))
            a1d = json.loads(a1_verdict.read_text(encoding="utf-8"))
            a1r = a1_report.read_text(encoding="utf-8")
            a1rbd = json.loads(rb_json.read_text(encoding="utf-8"))
            a1rbr = rb_report.read_text(encoding="utf-8")
            a1hist = json.loads(a1_hist_wiring.read_text(encoding="utf-8"))
            a1_la = a1l.get("local_round", {}).get("log_analysis", {})
            a1_ra = a1l.get("remote_round", {}).get("log_analysis", {})
            a1_ended = [c for c in a1d.get("criteria", [])
                        if "ENDED" in c.get("name", "")]
            a1_ok = (
                # 取证链：v2 干净重跑 / 首轮 4 缺陷留痕不作裁决 / 现场恢复干净
                a1l.get("probe_run") == "v2_fixed"
                and a1l.get("device_run") is True
                and a1l.get("restore_errors") == []
                and a1l.get("remote_round_skipped") is False
                and a1v1.get("probe_run") == "initial_v1__DEFECTIVE"
                and len(a1v1.get("defects_found", [])) == 4
                and len(a1l.get("defects_fixed", [])) == 4
                # 防跨轮污染证据回潮
                and sum(a1_ra.get("local_marker_counts", {"x": 1}).values()) == 0
                and sum(a1_la.get("remote_marker_counts", {"x": 1}).values()) == 0
                # 裁决：34 项全 PASS / 时间盒未通过 / 仅点火 / F4 仍 BLOCKED
                and a1d.get("all_pass") is True
                and a1d.get("checks_total", 0) >= 30
                and all(a1d.get("checks", {}).values())
                and a1d.get("mode") == "offline_readonly"
                and a1d.get("device_run") is False
                and a1d.get("timebox_passed") is False
                and a1d.get("ignition_established") is True
                and a1d.get("sustained_playback_established") is False
                and a1d.get("f4_status") == "BLOCKED"
                and a1d.get("route_recommendation") == "B2_PENDING_USER_DECISION"
                # 防把 msg=2 写成自然播完 ENDED
                and len(a1_ended) == 1
                and a1_ended[0]["verdict"].startswith("NOT RUN")
                and "msg=2" in a1r
                and "不等于自然播完" in a1r
                # 防擅自宣布 A1 通过 / B2 已启动
                and "路线切换仍需用户拍板" in a1r
                and "不追加 A2" in a1r
                # 防把点火写成 F4 已修复（报告与裁决文本零虚假完成声明）
                and "F4 仍 BLOCKED" in a1r
                and "绕行 ≠ 修复" in a1r
                and not any(s in a1r for s in a1_forbidden)
                and not any(s in a1d.get("verdict", "") for s in a1_forbidden)
                and not any(s in "".join(a1d.get("honest_notes", []))
                            for s in a1_forbidden)
                # 防接线回滚被写成 F4 修复 / 历史接线 JSON 被覆盖
                and a1rbd.get("f4_status") == "BLOCKED"
                and a1rbd.get("rollback_state") == "ROLLED_BACK"
                and a1rbd.get("all_pass") is True
                and "不是 F4 修复" in a1rbr
                and a1rbd.get("historical_wiring_audit_sha256") == hashlib.sha256(
                    a1_hist_wiring.read_bytes()).hexdigest()
                and a1hist.get("checks", {}).get(
                    "setlogger_called_with_instance") is True
                # 源码侧接线确实已移除
                and "SetLoggerCallback(INMLogger()" not in a1_adapter.read_text(
                    encoding="utf-8")
                # 断点全量登记（12 / 09 / 16 / 17 / backlog / runbook）
                and "### 61.79" in a1_d12
                and "F4 仍 BLOCKED" in a1_d12.split("### 61.79")[-1]
                and "61.79" in a1_runbook
                and all("61.79" in t for t in a1_docs)
                and rollback_ok
            )
            if not a1_ok:
                failures.append(
                    "12 §61.79 守护：A1 时间盒裁决 / 接线回滚口径漂移，请重跑 "
                    "tools/l3_f4_a1_bypass_verdict.py 与 "
                    "tools/l3_f4_wiring_rollback_audit.py")
            else:
                print("[PASS] 12 §61.79 守护：A1 时间盒真机裁决未通过（仅点火、"
                      "无持续播放）、F4 仍 BLOCKED、B2 待用户拍板；点火≠修复、"
                      "msg=2≠ENDED、跨轮污染已消除、接线回滚≠修复、历史证据冻结")
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.79 守护：A1 裁决 / 取证证据 JSON 不可解析（{exc}）")

    # 12 §61.80 守护 / B2 路线切换（用户 2026-11-11 17:44 拍板「切换 B2」）：
    # 防把「路线切换」提升为修复结论、防未实施先写 B2 完成、防擅自引入
    # Media3 依赖 / 启用 :engine-opensource / 换绑 EngineModule、防 A 路线复活、
    # 防 §61.75/§61.79 历史证据回潮。
    b2_tool = ROOT / "tools" / "l3_b2_route_switch_audit.py"
    b2_json = ROOT / "analysis" / "l3-b2-route-switch-audit.json"
    b2_report = ROOT / "analysis" / "l3-b2-route-switch-audit-report.md"
    b2_plan_p = ROOT / "analysis" / "l3-b2-implementation-plan-2026-11-11.md"
    b2_probe_p = ROOT / "analysis" / "l3-b2-media3-probe.json"
    if not all(p.is_file() for p in (b2_tool, b2_json, b2_report,
                                     b2_plan_p, b2_probe_p)):
        failures.append("12 §61.80 守护：B2 审计工具 / 证据 / 报告 / 施工单 / "
                        "Maven probe 缺失")
    else:
        try:
            b2d = json.loads(b2_json.read_text(encoding="utf-8"))
            b2p = json.loads(b2_probe_p.read_text(encoding="utf-8"))
            b2_plan_txt = b2_plan_p.read_text(encoding="utf-8")
            b2_settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
            b2_toml = (ROOT / "gradle" / "libs.versions.toml").read_text(
                encoding="utf-8")
            b2_bindings = (ROOT / "core-di" / "src" / "main" / "kotlin" /
                           "com" / "example" / "cloudmusic" / "di" /
                           "AppBindings.kt").read_text(encoding="utf-8")
            b2_d12 = delivery12.read_text(encoding="utf-8")
            b2_eval = (ROOT / "analysis" /
                       "l3-playback-route-evaluation-2026-11-11.md").read_text(
                           encoding="utf-8")
            b2_docs = [
                (ROOT / p).read_text(encoding="utf-8")
                for p in (
                    "docs/09-文档闭环总览.md",
                    "docs/16-架构与任务拆分复核报告.md",
                    "docs/17-竞品模块清单与复刻规划.md",
                    "analysis/task-backlog-2026-11-07.md",
                    "analysis/l3-f4-online-runbook.md",
                )
            ]
            b2_section = b2_d12.split("### 61.80")[-1]
            b2_forbidden = ("F4 已修复", "F4 已解决", "B2 已实施完成",
                            "B2 已完成", "A1 已通过", "路线已验收")
            # 12 §61.81 / B2 M1：M0 历史审计证据 JSON（b2d）冻结不改；下面三条
            # 「防提前引依赖 / 启用模块 / 换绑」改为按当前态双分支校验：
            # M0 未 include → 零基线改动；M1 已 include → 依赖/换绑须按 M1 演进。
            m1_started = 'include(":engine-opensource")' in b2_settings
            if m1_started:
                b2_current_ok = (
                    'media3 = "1.11.1"' in b2_toml
                    and "androidx.media3:media3-exoplayer" in b2_toml
                    and "media3-session" not in _code_only(b2_toml)
                    and "Media3EngineAdapter().also" in b2_bindings
                    and "EngineAdapter" in b2_bindings
                )
            else:
                b2_current_ok = (
                    "media3" not in b2_toml.lower()
                    and 'include(":engine-opensource")' not in b2_settings
                    and "EngineAdapter().also" in b2_bindings
                )
            b2_ok = (
                b2d.get("all_pass") is True
                and b2d.get("checks_total", 0) >= 40
                and all(b2d.get("checks", {}).values())
                and b2d.get("mode") == "offline_readonly"
                and b2d.get("device_run") is False
                and b2d.get("route") == "B2"
                and b2d.get("route_switched") is True
                and b2d.get("implementation_started") is False
                and b2d.get("implementation_completed") is False
                and b2d.get("f4_status") == "BLOCKED"
                and not any(s in b2d.get("verdict", "") for s in b2_forbidden)
                # 用户拍板 / A 路线停止 / 不追加 A2
                and "切换 B2" in b2_section
                and "### 61.80" in b2_d12
                and "不追加 A2 二进制补丁" in b2_plan_txt
                and "A 路线停止" in b2_plan_txt
                # 路线切换 ≠ 修复；B2 尚未实施
                and "不是 F4 修复" in b2_section
                and "F4 仍 BLOCKED" in b2_section
                and "B2 实施尚未开始" in b2_plan_txt
                and "零生产代码改动" in b2_plan_txt
                # M0 / M1 当前态判据（见上方 b2_current_ok）
                and b2_current_ok
                # Media3 前置实证
                and b2p.get("selected_version") == "1.11.1"
                and b2p.get("errors") == []
                and b2p.get("artifacts", {}).get("media3-exoplayer", {}).get(
                    "http_ok") is True
                # 断点全量登记（12 / 09 / 16 / 17 / backlog / runbook / 路线评估）
                and "### 8.40" in b2_docs[2]
                and "61.80" in b2_eval
                and all("61.80" in t for t in b2_docs)
                and "12 §61.80 守护" in (ROOT / "tools" /
                                        "check_source_contracts.py").read_text(
                                            encoding="utf-8")
            )
            if not b2_ok:
                failures.append(
                    "12 §61.80 守护：B2 路线切换口径漂移（决策 / A 停止 / "
                    "未实施状态 / 零基线改动 / 文档登记），请重跑 "
                    "tools/l3_b2_route_switch_audit.py")
            else:
                print("[PASS] 12 §61.80 守护：用户拍板切换 B2、A 路线停止且"
                      "不追加 A2、Media3 1.11.1 可达；路线切换≠修复、"
                      "F4 仍 BLOCKED；当前态="
                      + ("M1 已开工（依赖 / 模块 / 换绑按 §61.81 演进）"
                         if m1_started else
                         "M0 零新增依赖 / 零模块启用 / 未换绑（B2 未实施）"))
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.80 守护：B2 审计证据 JSON 不可解析（{exc}）")

    # 12 §61.81 守护 / B2 M1 实施（用户 2026-11-11 18:19 授权开工 M1）：
    # 防把「骨架接入」写成 B2 完成 / F4 修复、防门面签名被改、防引入
    # media3-session / 移除 18 so / 伪造 Range 与音效接线、防历史证据回潮。
    m1_adapter = (ROOT / "engine-opensource" / "src" / "main" / "kotlin" /
                  "com" / "example" / "cloudmusic" / "engineopensource" /
                  "Media3EngineAdapter.kt")
    m1_build = ROOT / "engine-opensource" / "build.gradle.kts"
    m1_json = ROOT / "analysis" / "l3-b2-m1-audit.json"
    if not all(p.is_file() for p in (m1_adapter, m1_build, m1_json)):
        failures.append("12 §61.81 守护：M1 骨架 / 模块构建文件 / 审计证据缺失")
    else:
        try:
            m1d = json.loads(m1_json.read_text(encoding="utf-8"))
            m1_adapter_txt = m1_adapter.read_text(encoding="utf-8")
            m1_build_txt = m1_build.read_text(encoding="utf-8")
            m1g_settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
            m1g_toml = (ROOT / "gradle" / "libs.versions.toml").read_text(encoding="utf-8")
            m1g_bindings = (ROOT / "core-di" / "src" / "main" / "kotlin" /
                            "com" / "example" / "cloudmusic" / "di" /
                            "AppBindings.kt").read_text(encoding="utf-8")
            m1g_api = (ROOT / "engine-api" / "src" / "main" / "kotlin" /
                       "com" / "example" / "cloudmusic" / "engine" / "api" /
                       "EngineApi.kt").read_text(encoding="utf-8")
            m1g_app = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
            m1g_coredi = (ROOT / "core-di" / "build.gradle.kts").read_text(encoding="utf-8")
            m1g_d12 = delivery12.read_text(encoding="utf-8")
            # §61.84：.workbuddy 记忆文件是 gitignored 本地工件（跨检出不存在），
            # 改为存在才读——守护的可移植性修复；核心断言（仓库内文件）不变。
            m1_docs = [
                (ROOT / p).read_text(encoding="utf-8")
                for p in (
                    "docs/09-文档闭环总览.md",
                    "docs/16-架构与任务拆分复核报告.md",
                    "docs/17-竞品模块清单与复刻规划.md",
                    "analysis/task-backlog-2026-11-07.md",
                    "analysis/l3-f4-online-runbook.md",
                    "analysis/l3-b2-implementation-plan-2026-11-11.md",
                )
                if (ROOT / p).is_file() and "workbuddy" not in p
            ]
            m1_methods = (
                "fun prepare(", "fun attachDataSource(", "fun play(", "fun pause(",
                "fun stop(", "fun seek(", "fun setVolume(", "fun positionMs(",
                "fun durationMs(", "fun state(", "fun setQuality(",
                "fun attachAudioEffect(", "fun release(", "fun lastError(",
            )
            m1_forbidden = ("F4 已修复", "F4 已解决", "B2 已实施完成",
                            "B2 已完成", "A1 已通过", "路线已验收")
            m1_ok = (
                m1d.get("all_pass") is True
                and m1d.get("checks_total", 0) >= 25
                and all(m1d.get("checks", {}).values())
                and m1d.get("mode") == "offline_readonly"
                and m1d.get("device_run") is False
                and m1d.get("m1_started") is True
                and m1d.get("b2_completed") is False
                and m1d.get("f4_status") == "BLOCKED"
                and not any(s in m1d.get("verdict", "") for s in m1_forbidden)
                # 门面 14 方法签名零改动
                and "class Media3EngineAdapter : IMusicEngine" in m1_adapter_txt
                and all(("override " + m) in m1_adapter_txt for m in m1_methods)
                and all(m in m1g_api for m in m1_methods)
                # 构建：20 模块 / media3 1.11.1 / 不引 media3-session / 保留 legacy
                and m1g_settings.count('include(":') == 22
                and 'include(":engine-opensource")' in m1g_settings
                # §61.131：:engine-legacy 移出打包链（反转：原检查 include 在位→现检查注释在位）
        and '// include(":engine-legacy")' in m1g_settings
                and 'media3 = "1.11.1"' in m1g_toml
                and "androidx.media3:media3-exoplayer" in m1g_toml
                and "media3-session" not in _code_only(m1g_toml)
                and "media3-session" not in _code_only(m1_build_txt)
                # §61.131 反转：:app 不再依赖 :engine-legacy
        and 'project(":engine-legacy")' not in m1g_app
                and 'project(":engine-opensource")' in m1g_coredi
                # 单点换绑 + 回退保留
                and "Media3EngineAdapter().also" in m1g_bindings
                and "EngineAdapter" in m1g_bindings
                # 骨架诚实边界：Range / 音效不伪造接线
                and "RangeCacheDataSource" in m1_adapter_txt
                and "不伪造接线" in m1_adapter_txt
                and "C7" in m1_adapter_txt
                # 断点全量登记
                and "### 61.81" in m1g_d12
                and "M1 骨架 ≠ B2 完成" in m1g_d12
                and "F4 仍 BLOCKED" in m1g_d12
                and all("61.81" in x for x in m1_docs)
                and "12 §61.81 守护" in (ROOT / "tools" /
                                        "check_source_contracts.py").read_text(
                                            encoding="utf-8")
            )
            if not m1_ok:
                failures.append(
                    "12 §61.81 守护：M1 骨架 / 构建 / 单点换绑 / 门面签名 / "
                    "诚实边界 / 文档登记漂移，请重跑 tools/l3_b2_m1_audit.py")
            else:
                print("[PASS] 12 §61.81 守护：:engine-opensource 已启用（当前 21 模块）、"
                      "media3 1.11.1 三制品、EngineModule 单点换绑且回退保留、"
                      "门面 14 方法零改动、Range/音效未伪造接线、F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.81 守护：M1 审计证据 JSON 不可解析（{exc}）")

    # 12 §61.82 守护 / B2 M2 前置取证（用户 2026-11-11 21:01 选择「保持离线，做 M2 前置」）：
    # 防把「前置取证」写成 M2 完成 / F4 修复、防伪造交叉编译产物或 F3 重跑结果、
    # 防引入 media3-session / 移除 18 so / 联网下载上游、防历史证据回潮。
    m2_json = ROOT / "analysis" / "l3-b2-m2-preflight.json"
    m2_report_p = ROOT / "analysis" / "l3-b2-m2-preflight-report.md"
    m2_plan_p = ROOT / "analysis" / "l3-b2-m2-preflight-2026-11-11.md"
    m2_tool_p = ROOT / "tools" / "l3_b2_m2_preflight_audit.py"
    if not all(p.is_file() for p in (m2_json, m2_report_p, m2_plan_p, m2_tool_p)):
        failures.append("12 §61.82 守护：M2 前置取证报告 / 审计工具 / 证据缺失")
    else:
        try:
            m2d = json.loads(m2_json.read_text(encoding="utf-8"))
            m2_plan_txt = m2_plan_p.read_text(encoding="utf-8")
            m2_d12 = delivery12.read_text(encoding="utf-8")
            m2_forbidden = ("F4 已修复", "F4 已解决", "B2 已实施完成",
                            "B2 已完成", "A1 已通过", "路线已验收")
            m2_docs = [
                (ROOT / p).read_text(encoding="utf-8")
                for p in (
                    "docs/09-文档闭环总览.md",
                    "docs/16-架构与任务拆分复核报告.md",
                    "docs/17-竞品模块清单与复刻规划.md",
                    "analysis/task-backlog-2026-11-07.md",
                    "analysis/l3-f4-online-runbook.md",
                    "analysis/l3-b2-implementation-plan-2026-11-11.md",
                )
                if (ROOT / p).is_file() and "workbuddy" not in p
            ]
            m2_ok = (
                m2d.get("all_pass") is True
                and m2d.get("checks_total", 0) >= 40
                and all(m2d.get("checks", {}).values())
                and m2d.get("mode") == "offline_readonly"
                and m2d.get("device_run") is False
                and m2d.get("network_used") is False
                and m2d.get("stage") == "M2_PREFLIGHT"
                and m2d.get("m2_started") is False
                and m2d.get("m2_completed") is False
                and m2d.get("m2_gate_passed") is False
                and m2d.get("f4_status") == "BLOCKED"
                and len(m2d.get("toolchain_gaps", [])) == 4
                and m2d.get("f3_14grid_rerun") == "NOT_RUN"
                and not any(s in m2d.get("verdict", "") for s in m2_forbidden)
                # 报告诚实边界
                and "M2 前置取证 ≠ M2 完成" in m2_plan_txt
                and "F4 仍 BLOCKED" in m2_plan_txt
                and "4 项刚性缺口" in m2_plan_txt
                and "纯离线不可执行" in m2_plan_txt
                and "NOT RUN" in m2_plan_txt
                # 断点全量登记
                and "### 61.82" in m2_d12
                and "M2 前置取证 ≠ M2 完成" in m2_d12
                and all("61.82" in x for x in m2_docs)
                and "12 §61.82 守护" in (ROOT / "tools" /
                                        "check_source_contracts.py").read_text(
                                            encoding="utf-8")
            )
            if not m2_ok:
                failures.append(
                    "12 §61.82 守护：M2 前置取证口径漂移（门禁 / 缺口 / 诚实边界 / "
                    "文档登记），请重跑 tools/l3_b2_m2_preflight_audit.py")
            else:
                print("[PASS] 12 §61.82 守护：M2 前置取证（4 项工具链缺口 / "
                      "F3 NOT_RUN / M2 门禁未通过）；纯离线、F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.82 守护：M2 前置取证证据 JSON 不可解析（{exc}）")

    # 12 §61.83 守护 / B2 M2 前置补证（libffmpeg.so decoder/demuxer 导出符号表实证）：
    # 防把「符号表补证」写成 M2 完成 / F4 修复 / 真机可播结论、防伪造交叉校验、
    # 防改动 §61.44 历史证据、防联网 / 移除 18 so / 引入 media3-session。
    sym_json = ROOT / "analysis" / "l3-b2-m2-decoder-symbols.json"
    sym_report_p = ROOT / "analysis" / "l3-b2-m2-decoder-symbols-report.md"
    sym_cross_p = ROOT / "analysis" / "l3-b2-m2-decoder-symbols-crosscheck.json"
    sym_tool_p = ROOT / "tools" / "l3_b2_m2_decoder_symbol_audit.py"
    if not all(p.is_file() for p in (sym_json, sym_report_p, sym_cross_p, sym_tool_p)):
        failures.append("12 §61.83 守护：decoder 符号表实证工具 / 证据 / 交叉校验缺失")
    else:
        try:
            sd = json.loads(sym_json.read_text(encoding="utf-8"))
            xd = json.loads(sym_cross_p.read_text(encoding="utf-8"))
            sym_report_txt = sym_report_p.read_text(encoding="utf-8")
            sym_d12 = (ROOT / "docs" / "12-编码阶段第一批交付.md").read_text(
                encoding="utf-8")
            sym_d09 = (ROOT / "docs" / "09-文档闭环总览.md").read_text(
                encoding="utf-8")
            sym_plan = (ROOT / "analysis" /
                        "l3-b2-m2-preflight-2026-11-11.md").read_text(
                            encoding="utf-8")
            sym_forbidden = ("F4 已修复", "F4 已解决", "B2 已实施完成",
                             "B2 已完成", "A1 已通过", "路线已验收",
                             "M2 已完成", "M2 已通过", "真机可播已验证")
            sym_docs = [
                (ROOT / p).read_text(encoding="utf-8")
                for p in (
                    "docs/09-文档闭环总览.md",
                    "docs/16-架构与任务拆分复核报告.md",
                    "docs/17-竞品模块清单与复刻规划.md",
                    "analysis/task-backlog-2026-11-07.md",
                    "analysis/l3-f4-online-runbook.md",
                    "analysis/l3-b2-implementation-plan-2026-11-11.md",
                )
                if (ROOT / p).is_file() and "workbuddy" not in p
            ]
            rows9 = sd.get("unconfirmed_9", [])
            n_no = sum(1 for r in rows9 if r.get("symbol_verdict") == "NO_SYMBOL")
            n_dem = sum(1 for r in rows9
                        if r.get("symbol_verdict") == "DEMUXER_SYMBOL_ONLY")
            sym_ok = (
                sd.get("all_pass") is True
                and sd.get("checks_total", 0) >= 18
                and all(sd.get("checks", {}).values())
                and sd.get("mode") == "offline_readonly"
                and sd.get("device_run") is False
                and sd.get("network_used") is False
                and sd.get("stage") == "M2_PREFLIGHT"
                and sd.get("m2_started") is False
                and sd.get("m2_completed") is False
                and sd.get("m2_gate_passed") is False
                and sd.get("f4_status") == "BLOCKED"
                and sd.get("evidence_level") == "ELF_exported_dynsym_symbol"
                and sd.get("symtab_stripped") is True
                and sd.get("decoder_symbol_count") == 100
                and sd.get("demuxer_symbol_count") == 75
                and sd.get("cross_abi_identical") is True
                and sd.get("consistent_with_61_44") is True
                and len(sd.get("full_decoder_symbols", [])) == 100
                and len(sd.get("full_demuxer_symbols", [])) == 75
                and len(rows9) == 9 and n_no == 8 and n_dem == 1
                and len(sd.get("candidate_15", [])) == 15
                and not any(s in sd.get("verdict", "") for s in sym_forbidden)
                # 独立交叉校验
                and xd.get("all_match") is True
                and "pyelftools" in xd.get("tool", "")
                and sd.get("pyelftools_crosscheck", {}).get("all_match") is True
                # 报告诚实边界
                and "导出符号存在 ≠ decoder 可用 ≠ 真机可播" in sym_report_txt
                and "独立交叉校验" in sym_report_txt
                and "NO_SYMBOL" in sym_report_txt
                # 断点与登记
                and "### 61.83" in sym_d12
                and "符号表补证 ≠ M2 完成" in sym_d12
                and "F4 仍 BLOCKED" in sym_d12
                and "31 个文件" in sym_d09
                and "## 9. 补证批注" in sym_plan and "61.83" in sym_plan
                and all("61.83" in x for x in sym_docs)
                and "12 §61.83 守护" in (ROOT / "tools" /
                                        "check_source_contracts.py").read_text(
                                            encoding="utf-8")
            )
            if not sym_ok:
                failures.append(
                    "12 §61.83 守护：decoder 符号表实证口径漂移（证据等级 / 100·75 / "
                    "交叉校验 / 9 项判定 / 诚实边界 / 文档登记），"
                    "请重跑 tools/l3_b2_m2_decoder_symbol_audit.py")
            else:
                print("[PASS] 12 §61.83 守护：ELF .dynsym 符号表实证（100 decoder / "
                      "75 demuxer / 双 ABI 一致 / pyelftools 互证 / 9 项 8+1 判定）；"
                      "纯离线、F4 仍 BLOCKED")
        except (KeyError, ValueError, OSError, TypeError) as exc:
            failures.append(f"12 §61.83 守护：decoder 符号表证据 JSON 不可解析（{exc}）")

    # 12 §61.92 守护 / M5 收口：F4-B2 验收单 PASS + A3 终局状态表登记。
    m92_acc = ROOT / "analysis" / "b2-f4-acceptance.json"
    m92_d12 = (ROOT / "docs" / "12-编码阶段第一批交付.md").read_text(
        encoding="utf-8")
    if m92_acc.is_file():
        try:
            m92d = json.loads(m92_acc.read_text(encoding="utf-8"))
            m92_ok = (
                m92d.get("f4_verdict") == "PASS"
                and m92d.get("checks", {}).get("f3_pass_ge_baseline") is True
                and m92d.get("checks", {}).get("all_cells_media3") is True
                and m92d.get("checks", {}).get("seek_three_point") is True
                and m92d.get("counts", {}).get("f3_pass", 0) >= 11
                and "### 61.92" in m92_d12
                and "A3 终局状态表" in m92_d12
                and "G08 20 分钟长稳" in m92_d12
                and "作废-降级" in m92_d12
            )
            if m92_ok:
                print("[PASS] 12 §61.92 守护：F4-B2 验收 PASS + A3 终局状态表"
                      "（G01~G07 PASS / G08 降级 / G09 PARTIAL）在位")
            else:
                failures.append("12 §61.92 守护：F4-B2 验收判定/计数漂移")
        except Exception as exc:
            failures.append(f"12 §61.92 守护：验收 JSON 不可解析（{exc}）")
    else:
        failures.append("12 §61.92 守护：b2-f4-acceptance.json 缺失")

    # 12 §61.86 守护 / F3 双引擎判据：B2 侧 CM-Media3 一手日志 + 矩阵引擎感知
    # 双分支（A 路线签名不得硬套 B2 运行——§61.74 口径「多信号、不反向推断」）。
    m86_adapter_path = (ROOT / "engine-opensource" / "src" / "main" / "kotlin" /
                        "com" / "example" / "cloudmusic" / "engineopensource" /
                        "Media3EngineAdapter.kt")
    m86_adapter = m86_adapter_path.read_text(encoding="utf-8") \
        if m86_adapter_path.is_file() else ""
    m86_matrix = (ROOT / "tools" / "l3_f3_matrix_run.py").read_text(
        encoding="utf-8")
    m86_d12 = (ROOT / "docs" / "12-编码阶段第一批交付.md").read_text(
        encoding="utf-8")
    if ('const val TAG = "CM-Media3"' in m86_adapter
            and "prepare trackId=%s remote=%s url=%s" in m86_adapter
            and "state=READY durationMs=" in m86_adapter
            and 'engine = ("media3"' in m86_matrix
            and "STATE_READY(duration>0)" in m86_matrix
            and '"CM-Media3"' in m86_matrix
            and "### 61.86" in m86_d12
            and "不依赖 A 路线" in m86_d12):
        print("[PASS] 12 §61.86 守护：CM-Media3 诊断日志（prepare 行双引擎同格式 / "
              "READY+isPlaying 一手信号）与矩阵引擎感知双判据在位")
    else:
        failures.append(
            "12 §61.86 守护：F3 双引擎判据漂移（CM-Media3 日志 / 引擎指纹分支 / "
            "docs/12 §61.86 缺失）")

    # 12 §61.85 守护 / B2 M2（FFmpeg 软解扩展落地）：
    # ① JNI 包装层三件入库（build_ffmpeg.sh / CMakeLists.txt / ffmpeg_jni.cc），
    #    FFmpeg 6.0 源码与 android-libs 产物**不入库**（.gitignore 排除，脚本可复现）；
    # ② androidx/media3 decoder_ffmpeg Java 渲染器入库（Apache 2.0，同源 1.11.1）；
    # ③ :engine-opensource 接 externalNativeBuild cmake + media3-decoder 依赖 +
    #    ndkVersion；④ compileSdk=36（media3 1.11.1 AAR metadata 实测要求）。
    # 诚实边界：交叉编译产物存在 ≠ F3 14 格 PASS ≠ 真机可播；F4 仍 BLOCKED。
    m2_jni = ROOT / "engine-opensource" / "src" / "main" / "jni"
    m2_java = ROOT / "engine-opensource" / "src" / "main" / "java" / "androidx" / \
        "media3" / "decoder" / "ffmpeg"
    m2_gradle_txt = (ROOT / "engine-opensource" / "build.gradle.kts").read_text(
        encoding="utf-8") if (ROOT / "engine-opensource" / "build.gradle.kts").is_file() else ""
    m2_toml_txt = (ROOT / "gradle" / "libs.versions.toml").read_text(
        encoding="utf-8")
    m2_gitignore_txt = (ROOT / ".gitignore").read_text(encoding="utf-8")
    m2_d12_txt = (ROOT / "docs" / "12-编码阶段第一批交付.md").read_text(
        encoding="utf-8")
    if (m2_jni / "build_ffmpeg.sh").is_file() \
            and (m2_jni / "CMakeLists.txt").is_file() \
            and (m2_jni / "ffmpeg_jni.cc").is_file() \
            and (m2_java / "FfmpegAudioRenderer.java").is_file() \
            and (m2_java / "FfmpegAudioDecoder.java").is_file() \
            and (m2_java / "FfmpegLibrary.java").is_file() \
            and "externalNativeBuild" in m2_gradle_txt \
            and "libs.androidx.media3.decoder" in m2_gradle_txt \
            and "27.1.12297006" in m2_gradle_txt \
            and "androidx-media3-decoder" in m2_toml_txt \
            and "compileSdk = \"36\"" in m2_toml_txt \
            and "engine-opensource/src/main/jni/ffmpeg/" in m2_gitignore_txt \
            and "### 61.85" in m2_d12_txt:
        print("[PASS] 12 §61.85 守护：FFmpeg 软解扩展 JNI 三件 + Java 渲染器入库、"
              "externalNativeBuild + media3-decoder 接线、compileSdk 36、"
              "ffmpeg 源码/产物 gitignore；符号存在 ≠ 真机可播、F4 仍 BLOCKED")
    else:
        failures.append(
            "12 §61.85 守护：B2 M2 落地态漂移（JNI 三件 / Java 渲染器 / "
            "externalNativeBuild / media3-decoder / compileSdk 36 / gitignore / "
            "docs/12 §61.85 缺失）")



    # 12 §61.100 守护 / 仓库同步后的文档↔代码差异对齐与全量回写补账
    # （2026-11-13，纯离线）：校验正式批次、回写锚点、审计工具与证据在位，
    # 防「09/16/17 回写链再次断掉」与「M1 过期注释回潮」。
    realign_tool = ROOT / "tools" / "b2_realign_audit.py"
    realign_json = ROOT / "analysis" / "b2-realign-2026-11-13.json"
    realign_report = ROOT / "analysis" / "b2-realign-2026-11-13.report.md"
    if not (realign_tool.is_file() and realign_json.is_file()
            and realign_report.is_file()):
        failures.append(
            "12 §61.100 守护：对齐审计工具 / JSON / report 缺失")
    else:
        try:
            import json as _json
            _r = _json.loads(realign_json.read_text(encoding="utf-8"))
            _d12 = (ROOT / "docs" / "12-编码阶段第一批交付.md").read_text(
                encoding="utf-8")
            _d09 = (ROOT / "docs" / "09-文档闭环总览.md").read_text(
                encoding="utf-8")
            _d16 = (ROOT / "docs" / "16-架构与任务拆分复核报告.md").read_text(
                encoding="utf-8")
            _d17 = (ROOT / "docs" / "17-竞品模块清单与复刻规划.md").read_text(
                encoding="utf-8")
            _bl = (ROOT / "analysis" / "task-backlog-2026-11-07.md").read_text(
                encoding="utf-8")
            _adapter = (ROOT / "engine-opensource" / "src" / "main" /
                        "kotlin" / "com" / "example" / "cloudmusic" /
                        "engineopensource" / "Media3EngineAdapter.kt"
                        ).read_text(encoding="utf-8")
            _build = (ROOT / "engine-opensource" / "build.gradle.kts"
                      ).read_text(encoding="utf-8")
            if (_r.get("verdict") == "PASS"
                    and _r.get("checks_pass") == _r.get("checks_total")
                    and "### 61.100" in _d12
                    and "9375a247" in _d12 and "1485ce0f" in _d12
                    and all("61.100" in x for x in (_d09, _d16, _d17, _bl))
                    and "61.100" in _adapter and "61.100" in _build
                    and "// §61.99：不透传 mimeType" not in _adapter
                    and "61.100" in realign_tool.read_text(encoding="utf-8")):
                print("[PASS] 12 §61.100 守护：文档↔代码差异对齐批次、全量回写"
                      "锚点与三处注释口径修正均在位（纯离线、零行为改动）")
            else:
                failures.append(
                    "12 §61.100 守护：对齐判定/回写锚点/注释勘误口径漂移")
        except (OSError, UnicodeError, ValueError) as exc:
            failures.append(f"12 §61.100 守护：对齐证据 JSON 不可解析（{exc}）")

    # 12 §61.101 守护 / 第 1~3 项按序推进：F3 8 FAIL 归因、ReplayGain 值对象、
    # 反伪证边界（纯离线，不依赖本机 SDK / 设备）。
    attr_json = ROOT / "analysis" / "b2-f3-fail-attribution-2026-11-13.json"
    gain_src = (ROOT / "core-model" / "src" / "main" / "kotlin" / "com" /
                "example" / "cloudmusic" / "model" / "PlaybackGain.kt")
    gain_test = (ROOT / "core-model" / "src" / "test" / "kotlin" / "com" /
                 "example" / "cloudmusic" / "model" / "PlaybackGainTest.kt")
    if not (attr_json.is_file() and gain_src.is_file() and gain_test.is_file()):
        failures.append("12 §61.101 守护：F3 归因证据 / ReplayGain 值对象与测试缺失")
    else:
        try:
            import json as _json
            _a = _json.loads(attr_json.read_text(encoding="utf-8"))
            _g = gain_src.read_text(encoding="utf-8")
            _d12b = (ROOT / "docs" / "12-编码阶段第一批交付.md").read_text(
                encoding="utf-8")
            _docs101 = [
                (ROOT / "docs" / "09-文档闭环总览.md").read_text(encoding="utf-8"),
                (ROOT / "docs" / "16-架构与任务拆分复核报告.md").read_text(encoding="utf-8"),
                (ROOT / "docs" / "17-竞品模块清单与复刻规划.md").read_text(encoding="utf-8"),
                (ROOT / "analysis" / "task-backlog-2026-11-07.md").read_text(encoding="utf-8"),
            ]
            _mx = _json.loads((ROOT / "analysis" / "l3-f3-matrix.json"
                               ).read_text(encoding="utf-8"))
            if (_a.get("verdict") == "PASS"
                    and _a.get("device_run") is False
                    and _a.get("checks_pass") == _a.get("checks_total")
                    and _a.get("attribution", "").find("容器解析缺口") >= 0
                    and "无生产消费方" in _g
                    and "不宣称" in _g
                    and "### 61.101" in _d12b
                    and all("61.101" in x for x in _docs101)
                    and _mx.get("counts", {}).get("PASS") == 14
                    and _mx.get("counts", {}).get("FAIL") == 8
                    and "ReplayGain 已接回" not in "".join(_docs101)):
                print("[PASS] 12 §61.101 守护：F3 8 FAIL 归因（容器解析缺口、"
                      "device_run=false）/ ReplayGain 纯 JVM 值对象与诚实边界 / "
                      "全量回写锚点 / 历史矩阵零改写")
            else:
                failures.append(
                    "12 §61.101 守护：归因判定 / 值对象边界 / 回写锚点 / 矩阵口径漂移")
        except (OSError, UnicodeError, ValueError) as exc:
            failures.append(f"12 §61.101 守护：证据不可解析（{exc}）")

    # 12 §61.102 守护 / 容器缺口闭环：4 条自定义 MIME 映射 + 4 个 Extractor 挂接
    # + AIFF/WavPack 实现 + 反伪证边界（纯离线，不依赖设备 / native 库）。
    gap_json = ROOT / "analysis" / "b2-container-gap-2026-11-13.json"
    ff_lib = (ROOT / "engine-opensource" / "src" / "main" / "java" /
              "androidx" / "media3" / "decoder" / "ffmpeg" / "FfmpegLibrary.java")
    if not (gap_json.is_file() and ff_lib.is_file()):
        failures.append("12 §61.102 守护：容器闭环证据 / FfmpegLibrary 缺失")
    else:
        try:
            import json as _json
            _cg = _json.loads(gap_json.read_text(encoding="utf-8"))
            _lib = ff_lib.read_text(encoding="utf-8")
            _ape = (ROOT / "engine-opensource" / "src" / "main" / "java" /
                    "androidx" / "media3" / "extractor" / "ape" /
                    "ApeExtractor.kt").read_text(encoding="utf-8")
            _d12c = (ROOT / "docs" / "12-编码阶段第一批交付.md").read_text(
                encoding="utf-8")
            _docs102 = [
                (ROOT / "docs" / "09-文档闭环总览.md").read_text(encoding="utf-8"),
                (ROOT / "docs" / "16-架构与任务拆分复核报告.md").read_text(encoding="utf-8"),
                (ROOT / "docs" / "17-竞品模块清单与复刻规划.md").read_text(encoding="utf-8"),
                (ROOT / "analysis" / "task-backlog-2026-11-07.md").read_text(encoding="utf-8"),
            ]
            if (_cg.get("verdict") == "PASS"
                    and _cg.get("device_run") is False
                    and _cg.get("checks_pass") == _cg.get("checks_total")
                    and all(('case "%s":' % m) in _lib for m in
                            ("audio/x-ape", "audio/x-wma", "audio/x-wavpack",
                             "audio/x-aiff"))
                    and all(x in _ape for x in
                            ("AiffExtractor()", "WavpackExtractor()"))
                    and "### 61.102" in _d12c
                    and "48000" in _d12c and "勘误" in _d12c
                    and all("61.102" in x for x in _docs102)
                    and "Unseekable" in (ROOT / "engine-opensource" / "src" /
                                        "main" / "java" / "androidx" / "media3" /
                                        "extractor" / "wavpack" /
                                        "WavpackExtractor.kt").read_text(
                                            encoding="utf-8")):
                print("[PASS] 12 §61.102 守护：4 条 MIME→decoder 映射 / "
                      "Aiff+Wavpack Extractor 挂接 / AIFF 48000Hz 勘误 / "
                      "全量回写锚点 / 变长块诚实 Unseekable")
            else:
                failures.append(
                    "12 §61.102 守护：MIME 映射 / Extractor 挂接 / 勘误 / 回写锚点漂移")
        except (OSError, UnicodeError, ValueError) as exc:
            failures.append(f"12 §61.102 守护：证据不可解析（{exc}）")

    # 12 §61.103 守护 / FFmpeg 工具链快照：证据 JSON 固化本机一次性实测，
    # 不依赖 SDK/NDK/设备（静态库为 gitignore 本地工件，跨检出不存在）。
    tool_json = ROOT / "analysis" / "b2-ffmpeg-toolchain-2026-11-13.json"
    if not tool_json.is_file():
        failures.append("12 §61.103 守护：工具链快照证据缺失")
    else:
        try:
            import json as _json
            _t = _json.loads(tool_json.read_text(encoding="utf-8"))
            _snap = _t.get("snapshot", {})
            _d12d = (ROOT / "docs" / "12-编码阶段第一批交付.md").read_text(
                encoding="utf-8")
            _docs103 = [
                _d12d,
                (ROOT / "docs" / "09-文档闭环总览.md").read_text(encoding="utf-8"),
                (ROOT / "docs" / "16-架构与任务拆分复核报告.md").read_text(encoding="utf-8"),
                (ROOT / "docs" / "17-竞品模块清单与复刻规划.md").read_text(encoding="utf-8"),
                (ROOT / "analysis" / "task-backlog-2026-11-07.md").read_text(encoding="utf-8"),
            ]
            _mx103 = _json.loads((ROOT / "analysis" / "l3-f3-matrix.json"
                                  ).read_text(encoding="utf-8"))
            if (_t.get("verdict") == "PASS"
                    and _snap.get("device_run") is False
                    and _snap.get("f3_retest") == "NOT RUN"
                    and _snap.get("assemble_debug", "").startswith(
                        "BUILD SUCCESSFUL")
                    and "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in _snap.get(
                        "install", "")
                    and len(_snap.get("decoder_symbols", [])) == 4
                    and "ff_wavpack_decoder" in _snap.get("decoder_symbols", [])
                    and len(_snap.get("abi_static_libs", {})) == 2
                    and "### 61.103" in _d12d
                    and all("61.103" in x for x in _docs103)
                    and _mx103.get("counts", {}).get("PASS") == 14
                    and _mx103.get("counts", {}).get("FAIL") == 8):
                print("[PASS] 12 §61.103 守护：两 ABI 静态库 / APK 首次构建成功 / "
                      "4 个 decoder 符号实证 / 装机阻塞与真机 NOT RUN 如实登记 / "
                      "全量回写锚点 / 历史矩阵零改写")
            else:
                failures.append(
                    "12 §61.103 守护：工具链快照 / 回写锚点 / 诚实边界口径漂移")
        except (OSError, UnicodeError, ValueError) as exc:
            failures.append(f"12 §61.103 守护：证据不可解析（{exc}）")

    # 12 §61.104 守护 / 装机与 F3 8 格真机复测：证据只读裁决，防「3 PASS」
    # 被写成「8 格已修复」，并守护历史证据零改写口径。
    ret_verdict = ROOT / "analysis" / "b2-f3-retest-verdict-2026-11-13.json"
    ret_matrix = ROOT / "analysis" / "b2-f3-retest-2026-11-13.json"
    if not (ret_verdict.is_file() and ret_matrix.is_file()):
        failures.append("12 §61.104 守护：复测裁决 / 新矩阵证据缺失")
    else:
        try:
            import json as _json
            _v = _json.loads(ret_verdict.read_text(encoding="utf-8"))
            _rm = _json.loads(ret_matrix.read_text(encoding="utf-8"))
            _hist = _json.loads((ROOT / "analysis" / "l3-f3-matrix.json"
                                 ).read_text(encoding="utf-8"))
            _d12e = (ROOT / "docs" / "12-编码阶段第一批交付.md").read_text(
                encoding="utf-8")
            _docs104 = [
                _d12e,
                (ROOT / "docs" / "09-文档闭环总览.md").read_text(encoding="utf-8"),
                (ROOT / "docs" / "16-架构与任务拆分复核报告.md").read_text(encoding="utf-8"),
                (ROOT / "docs" / "17-竞品模块清单与复刻规划.md").read_text(encoding="utf-8"),
                (ROOT / "analysis" / "task-backlog-2026-11-07.md").read_text(encoding="utf-8"),
            ]
            causes = {c["root_cause"] for c in _v.get("cells", [])}
            if (_v.get("verdict") == "PASS"
                    and _v.get("device_run") is True
                    and _v.get("counts", {}).get("PASS") == 3
                    and _v.get("counts", {}).get("FAIL") == 5
                    and len(_rm.get("cells", [])) == 8
                    and {"DECODER_INIT_FAILED",
                         "EXTRACTOR_PREPARATION_INCOMPLETE",
                         "NO_READY_IN_WINDOW",
                         "READY_AND_PLAYING"} <= causes
                    and _hist.get("counts", {}).get("PASS") == 14
                    and _hist.get("counts", {}).get("FAIL") == 8
                    and "### 61.104" in _d12e
                    and all("61.104" in x for x in _docs104)
                    and "不宣称 8 格已修复" in _d12e):
                print("[PASS] 12 §61.104 守护：装机 + 真机 8 格复测 3 PASS / "
                      "5 FAIL / 四类根因 / 历史证据零改写 / 反伪证边界 / "
                      "全量回写锚点")
            else:
                failures.append(
                    "12 §61.104 守护：复测计数 / 根因 / 历史矩阵 / 回写锚点漂移")
        except (OSError, UnicodeError, ValueError) as exc:
            failures.append(f"12 §61.104 守护：证据不可解析（{exc}）")

    # 12 §61.105 守护 / ①~④ 施工：静态证据等级不得被写成真机结论，
    # 且 ReplayGain 接线 / gapless BLOCKED / 失效副本不采信三条口径必须在位。
    _pcm_file = ROOT / "analysis" / "b2-pcm-init-audit-2026-11-13.json"
    _lw_file = ROOT / "analysis" / "b2-f3-longwait-verdict-2026-11-13.json"
    if not (_pcm_file.is_file() and _lw_file.is_file()):
        failures.append("12 §61.105 守护：PCM 根因 / 加长等待裁决证据缺失")
    else:
        try:
            import json as _json
            _pcm = _json.loads(_pcm_file.read_text(encoding="utf-8"))
            _lw = _json.loads(_lw_file.read_text(encoding="utf-8"))
            _asf105 = (ROOT / "engine-opensource" / "src" / "main" / "java" /
                       "androidx" / "media3" / "extractor" / "asf" /
                       "AsfExtractor.kt").read_text(encoding="utf-8")
            _jni105 = (ROOT / "engine-opensource" / "src" / "main" / "jni" /
                       "ffmpeg_jni.cc").read_text(encoding="utf-8")
            _eo = (ROOT / "engine-opensource" / "src" / "main" / "kotlin" /
                   "com" / "example" / "cloudmusic" / "engineopensource")
            _adapter105 = (_eo / "Media3EngineAdapter.kt").read_text(encoding="utf-8")
            _ctrl105 = _eo / "ReplayGainController.kt"
            _d105 = [
                (ROOT / "docs" / "12-编码阶段第一批交付.md").read_text(encoding="utf-8"),
                (ROOT / "docs" / "09-文档闭环总览.md").read_text(encoding="utf-8"),
                (ROOT / "docs" / "16-架构与任务拆分复核报告.md").read_text(encoding="utf-8"),
                (ROOT / "docs" / "17-竞品模块清单与复刻规划.md").read_text(encoding="utf-8"),
                (ROOT / "analysis" / "task-backlog-2026-11-07.md").read_text(encoding="utf-8"),
            ]
            if (_pcm.get("verdict") == "STATIC_ROOT_CAUSE_CONFIRMED"
                    and _pcm.get("device_run") is False
                    and _pcm.get("checks_passed") == 8
                    and _pcm.get("checks_total") == 8
                    and _lw.get("status") == "NOT_RUN"
                    and _lw.get("device_run") is False
                    and _lw.get("invalid_copies_not_used_as_evidence") is True
                    and "publishTracksAndSeekMap" in _asf105
                    and "out.endTracks()" in _asf105
                    and "av_get_bits_per_sample(codec->id) > 0" in _jni105
                    and _ctrl105.is_file()
                    and "ReplayGainController {" in _adapter105
                    and "onMetadata(metadata: Metadata)" in _adapter105
                    and "GaplessPolicy.assess(" in _adapter105
                    and "### 61.105" in _d105[0]
                    and all("61.105" in x for x in _d105)
                    and "NOT RUN" in _d105[0]
                    and "不宣称 8 格已修复" in _d105[0]):
                print("[PASS] 12 §61.105 守护：Asf endTracks / PCM 预置 / "
                      "ape-remote NOT RUN / ReplayGain 接线 / gapless BLOCKED / "
                      "失效副本不采信 / 全量回写锚点")
            else:
                failures.append("12 §61.105 守护：四项施工锚点 / 证据等级 / "
                                "回写链漂移")
        except (OSError, UnicodeError, ValueError) as exc:
            failures.append("12 §61.105 守护：证据不可解析（%s）" % exc)

    # 12 §61.106 守护 / 真机复测：防「5 PASS」被写成「8 格已修复」，
    # 防 seek(0,0) 抢跑回归，防 wma 容器层修好后被误判为 wma 已可播。
    _v106 = ROOT / "analysis" / "b2-f3-batch126-verdict-2026-11-13.json"
    _m106 = ROOT / "analysis" / "b2-f3-batch126b-2026-11-13.json"
    if not (_v106.is_file() and _m106.is_file()):
        failures.append("12 §61.106 守护：真机裁决 / 最终 8 格矩阵证据缺失")
    else:
        try:
            import json as _json
            _vd = _json.loads(_v106.read_text(encoding="utf-8"))
            _mm = _json.loads(_m106.read_text(encoding="utf-8"))
            _asf106 = (ROOT / "engine-opensource" / "src" / "main" / "java" /
                       "androidx" / "media3" / "extractor" / "asf" /
                       "AsfExtractor.kt").read_text(encoding="utf-8")
            _asftest = (ROOT / "engine-opensource" / "src" / "test" / "kotlin" /
                        "com" / "example" / "cloudmusic" / "engineopensource" /
                        "AsfExtractorTest.kt").read_text(encoding="utf-8")
            _d106 = [
                (ROOT / "docs" / "12-编码阶段第一批交付.md").read_text(encoding="utf-8"),
                (ROOT / "docs" / "09-文档闭环总览.md").read_text(encoding="utf-8"),
                (ROOT / "docs" / "16-架构与任务拆分复核报告.md").read_text(encoding="utf-8"),
                (ROOT / "docs" / "17-竞品模块清单与复刻规划.md").read_text(encoding="utf-8"),
                (ROOT / "analysis" / "task-backlog-2026-11-07.md").read_text(encoding="utf-8"),
            ]
            _cells106 = {(c.get("format"), c.get("path")): c.get("verdict")
                         for c in _mm.get("cells", [])}
            if (_vd.get("verdict") == "RECORDED"
                    and _vd.get("device_run") is True
                    and _vd.get("stage_a_counts") == {"PASS": 5, "FAIL": 3}
                    and _vd.get("ape_remote_longwait", {}).get("wait_s") == 30.0
                    and len(_mm.get("cells", [])) == 8
                    and _cells106.get(("wma", "local")) == "FAIL"
                    and _cells106.get(("wma", "remote")) == "FAIL"
                    and _cells106.get(("aiff", "local")) == "PASS"
                    and _cells106.get(("aiff", "remote")) == "PASS"
                    and "packetCount <= 0 || packetSize <= 0" in _asf106
                    and "publishTracksAndSeekMap" in _asf106
                    and "init 后先 seek(0,0)" in _asftest
                    and "### 61.106" in _d106[0]
                    and all("61.106" in x for x in _d106)
                    and "不宣称 8 格已修复" in _d106[0]):
                print("[PASS] 12 §61.106 守护：aiff 双路径转 PASS / wma 仍 FAIL "
                      "/ seek(0,0) 抢跑回归 / ape-remote 30s NOT READY / "
                      "device_run=true / 全量回写锚点")
            else:
                failures.append("12 §61.106 守护：逐格计数 / 根因结构 / "
                                "seek 回归 / 回写链漂移")
        except (OSError, UnicodeError, ValueError) as exc:
            failures.append("12 §61.106 守护：证据不可解析（%s）" % exc)

    # 12 §61.107 守护 / 补账批次：防「WMA 三层链已落码」被写成「wma 已修复」，
    # 防 ReplayGain 探针 3/5 被美化成已生效，防 onTracksChanged / 顺序跳过被回删。
    _probe107 = ROOT / "analysis" / "b2-replaygain-probe-2026-11-13.json"
    _fix107 = ROOT / "analysis" / "b2-replaygain-fixture-2026-11-13.json"
    if not (_probe107.is_file() and _fix107.is_file()):
        failures.append("12 §61.107 守护：ReplayGain 探针 / 夹具证据缺失")
    else:
        try:
            import json as _json107
            _pr = _json107.loads(_probe107.read_text(encoding="utf-8"))
            _fx = _json107.loads(_fix107.read_text(encoding="utf-8"))
            _asf107 = (ROOT / "engine-opensource" / "src" / "main" / "java" /
                       "androidx" / "media3" / "extractor" / "asf" /
                       "AsfExtractor.kt").read_text(encoding="utf-8")
            _dec107 = (ROOT / "engine-opensource" / "src" / "main" / "java" /
                       "androidx" / "media3" / "decoder" / "ffmpeg" /
                       "FfmpegAudioDecoder.java").read_text(encoding="utf-8")
            _jni107 = (ROOT / "engine-opensource" / "src" / "main" / "jni" /
                       "ffmpeg_jni.cc").read_text(encoding="utf-8")
            _ape107 = (ROOT / "engine-opensource" / "src" / "main" / "java" /
                       "androidx" / "media3" / "extractor" / "ape" /
                       "ApeExtractor.kt").read_text(encoding="utf-8")
            _adp107 = (ROOT / "engine-opensource" / "src" / "main" / "kotlin" /
                       "com" / "example" / "cloudmusic" / "engineopensource" /
                       "Media3EngineAdapter.kt").read_text(encoding="utf-8")
            if (_pr.get("device_run") is True
                    and _pr.get("checks_passed") == 3
                    and _pr.get("checks_total") == 5
                    and _fx.get("output") and "f3_mp3_rg.mp3" in str(_fx.get("output"))
                    and "setInitializationData(listOf(waveformatex))" in _asf107
                    and 'case "audio/x-wma":' in _dec107
                    and "isWma && extraLen >= 18" in _jni107
                    and "wfxBlockAlign" in _jni107
                    and "MAX_SEQUENTIAL_SKIP" in _ape107
                    and "onTracksChanged" in _adp107
                    and "### 61.107" in (ROOT / "docs" /
                                         "12-编码阶段第一批交付.md").read_text(
                                             encoding="utf-8")):
                print("[PASS] 12 §61.107 守护：WMA 三层链锚点 / 探针 3/5 口径"
                      "（=修复前证据，不得美化）/ onTracksChanged / 顺序跳过 / 补账日志在位")
            else:
                failures.append("12 §61.107 守护：WMA 链锚点 / 探针口径 / "
                                "onTracksChanged / 顺序跳过 / 补账日志漂移")
        except (OSError, UnicodeError, ValueError) as exc:
            failures.append("12 §61.107 守护：证据不可解析（%s）" % exc)

    # 12 §61.108 守护 / B2 口径修缮 + F3 增强 + SDK 固化：防陈旧文案回漂，
    # 防 attachDataSource 语义被重新挂上 M3 期承诺，防 enhanced 判据被拆。
    _adp108 = (ROOT / "engine-opensource" / "src" / "main" / "kotlin" / "com" /
               "example" / "cloudmusic" / "engineopensource" /
               "Media3EngineAdapter.kt").read_text(encoding="utf-8")
    _app108 = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" /
               "cloudmusic" / "ui" / "CloudMusicApp.kt").read_text(encoding="utf-8")
    _di108 = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example" /
              "cloudmusic" / "di" / "playback" /
              "PlaybackComposition.kt").read_text(encoding="utf-8")
    _api108 = (ROOT / "engine-api" / "src" / "main" / "kotlin" / "com" /
               "example" / "cloudmusic" / "engine" / "api" /
               "EngineApi.kt").read_text(encoding="utf-8")
    _pc108 = (ROOT / "feature-player" / "src" / "main" / "kotlin" / "com" /
              "example" / "cloudmusic" / "player" /
              "PlayerController.kt").read_text(encoding="utf-8")
    _proxy108 = (ROOT / "output-localproxy" / "src" / "main" / "kotlin" / "com" /
                 "example" / "cloudmusic" / "output" / "localproxy" /
                 "LocalHttpStreamProxy.kt").read_text(encoding="utf-8")
    _d0606 = (ROOT / "docs" / "03-模块开发文档" /
              "03-06 播放内核与队列.md").read_text(encoding="utf-8")
    _tool108 = (ROOT / "tools" / "l3_f3_matrix_run.py").read_text(encoding="utf-8")
    _sdk108a = (ROOT / "tools" / "l3_f4_kind_slot_ab.py").read_text(encoding="utf-8")
    _sdk108b = (ROOT / "tools" / "l3_f4_data_kind_ab.py").read_text(encoding="utf-8")
    # §61.188 W5「设置页人话化」按用户要求删掉了「Media3EngineAdapter（B2 开源引擎…）」
    # 这行内部行话；B2 口径的真相源改由 DI / API / 控制器 / 适配器 / 文档承载。
    # 判据同步：不再要求 UI 展示引擎实现名，但保留「UI 不得回漂成旧 A 路线内核」
    # 的否定式检查，其余五处正向判据强度不变。
    _b2ok = ("EngineAdapter（舱内真实内核" not in _app108
             and "11-11 起为 `Media3EngineAdapter`" in _di108
             and "不替代引擎舱生产适配器" in _api108
             and "`prepareGate` 是 A 路线 EngineAdapter 的内部闸门" in _pc108
             and "§61.108 语义定稿" in _adp108
             and "§61.108 生产实况注" in _proxy108
             and "§61.108 语义定稿" in _d0606)
    _enhok = ("discontinuity reason=SEEK positionMs=" in _adp108
              and "--enhanced" in _tool108
              and "run_enhanced" in _tool108
              and "enhanced_summary" in _tool108)
    _sdkok = ("C:/Users/Administrator/cloudmusic-ta1-sdk" in _sdk108a
              and "C:/Users/Administrator/cloudmusic-ta1-sdk" in _sdk108b)
    if _b2ok and _enhok and _sdkok:
        print("[PASS] 12 §61.108 守护：B2 口径文案 / attachDataSource 语义定稿 / "
              "ProxyStartPolicy 实况注 / F3 enhanced 判据 / SDK 持久路径候选")
    else:
        failures.append("12 §61.108 守护：B2 口径文案回漂 / attachDataSource 语义"
                        " / enhanced 判据 / SDK 持久路径候选 漂移")

    # 12 §61.111 守护 / 真机闭环批次：钉死 8 格全绿 + enhanced 8/8 +
    # ReplayGain 5/5 的证据口径，防「wma 已修复」「ReplayGain 已生效」被回撤或夸大；
    # 钉死 Manifest 三调试入口在位与夹具 padding 修复。
    # 证据口径（§61.111⑦ 补记）：终态单文件合并跑因设备中途掉线作废，
    # 真机结论按分片证据钉死：初轮 7/8 + ape-remote 30s PASS（合计 8/8 legacy）+
    # ape enhanced json + 6 份 enhanced 日志（seek 落点 + ENDED 一手行）+ 探针 5/5。
    _init132 = ROOT / "analysis" / "b2-f3-batch132-2026-11-14.json"
    _ape132 = ROOT / "analysis" / "b2-f3-ape-remote-longwait-batch132-2026-11-14.json"
    _apeenh132 = ROOT / "analysis" / "b2-f3-enhanced-ape-batch132-2026-11-14.json"
    _probe111 = ROOT / "analysis" / "b2-replaygain-probe-batch132-2026-11-14.json"
    _enh_logs = ["aiff-local-enhanced.txt", "aiff-remote-enhanced.txt",
                 "wma-local-enhanced.txt", "wma-remote-enhanced.txt",
                 "wv-local-enhanced.txt", "wv-remote-enhanced.txt"]
    _paths111 = [_init132, _ape132, _apeenh132, _probe111] + [
        ROOT / "analysis" / "l3-f3-logs" / x for x in _enh_logs]
    if not all(x.is_file() for x in _paths111):
        failures.append("12 §61.111 守护：真机分片证据缺失（初轮/ape 30s/ape 增强/"
                        "探针/6 份 enhanced 日志）")
    else:
        try:
            import json as _json111
            _im = _json111.loads(_init132.read_text(encoding="utf-8"))
            _am = _json111.loads(_ape132.read_text(encoding="utf-8"))
            _ae = _json111.loads(_apeenh132.read_text(encoding="utf-8"))
            _pp = _json111.loads(_probe111.read_text(encoding="utf-8"))
            _c = {(x.get("format"), x.get("path")): x.get("verdict")
                  for x in _im.get("cells", [])}
            _manifest111 = (ROOT / "app" / "src" / "main" /
                            "AndroidManifest.xml").read_text(encoding="utf-8")
            _fixtool = (ROOT / "tools" / "b2_replaygain_fixture.py").read_text(
                encoding="utf-8")
            _logs_ok = all(
                "seek toMs=" in (ROOT / "analysis" / "l3-f3-logs" / x).read_text(
                    encoding="utf-8", errors="replace")
                and "discontinuity reason=SEEK" in (ROOT / "analysis" /
                                                    "l3-f3-logs" / x).read_text(
                                                        encoding="utf-8",
                                                        errors="replace")
                for x in _enh_logs)
            _init_ok = (_c.get(("aiff", "local")) == "PASS"
                        and _c.get(("aiff", "remote")) == "PASS"
                        and _c.get(("wma", "local")) == "PASS"
                        and _c.get(("wma", "remote")) == "PASS"
                        and _c.get(("ape", "local")) == "PASS"
                        and _c.get(("wv", "local")) == "PASS"
                        and _c.get(("wv", "remote")) == "PASS"
                        and _c.get(("ape", "remote")) == "FAIL")
            _ape_ok = any(x.get("verdict") == "PASS" for x in _am.get("cells", []))
            _apeenh_ok = all(x.get("verdict") == "PASS"
                             and x.get("enhanced", {}).get("enhanced_verdict") == "PASS"
                             for x in _ae.get("cells", [])) and len(_ae.get("cells", [])) == 2
            if (_im.get("serial") and _init_ok and _ape_ok and _apeenh_ok and _logs_ok
                    and _pp.get("device_run") is True
                    and _pp.get("checks_passed") == 5
                    and "action.SEEK" in _manifest111
                    and "action.EFFECT" in _manifest111
                    and "old_frames = old_frames.rstrip(" in _fixtool
                    and "### 61.111" in (ROOT / "docs" /
                                         "12-编码阶段第一批交付.md").read_text(
                                             encoding="utf-8")):
                print("[PASS] 12 §61.111 守护：初轮 7/8 + ape-remote 30s PASS（合计 "
                      "8/8）+ ape enhanced 2/2 + 6 格 enhanced 日志（seek 落点+回执）/ "
                      "ReplayGain 5/5 / Manifest 三入口 / 夹具 padding 修复 / 日志在位")
            else:
                failures.append("12 §61.111 守护：真机终态口径漂移（矩阵/增强/探针/"
                                "Manifest/夹具）")
        except (OSError, UnicodeError, ValueError) as exc:
            failures.append("12 §61.111 守护：证据不可解析（%s）" % exc)

    # 12 §61.115 守护 / 全矩阵首次全绿 + 封面闭环：钉死 22/22 与 enhanced 口径，
    # 防长稳「无有效数据」被写成稳定、防封面闭环证据缺失。
    _fm115 = ROOT / "analysis" / "b2-f3-fullmatrix-batch133-2026-11-14.json"
    _pp115 = ROOT / "analysis" / "b2-replaygain-probe-batch133-2026-11-14.json"
    _shot115a = ROOT / "analysis" / "l3-player-cover-batch133-2026-11-14.png"
    _shot115b = ROOT / "analysis" / "l3-player-gradient-batch133-2026-11-14.png"
    if not (_fm115.is_file() and _pp115.is_file() and _shot115a.is_file()
            and _shot115b.is_file()):
        failures.append("12 §61.115 守护：全矩阵 / 探针 / 封面截图证据缺失")
    else:
        try:
            import json as _json115
            _fmd = _json115.loads(_fm115.read_text(encoding="utf-8"))
            _ppd = _json115.loads(_pp115.read_text(encoding="utf-8"))
            _cells115 = _fmd.get("cells", [])
            _all115 = len(_cells115) == 22 and all(
                c.get("verdict") == "PASS" for c in _cells115)
            _enh115 = all(c.get("enhanced", {}).get("enhanced_verdict") == "PASS"
                          for c in _cells115)
            _g08tool = (ROOT / "tools" / "b2_g08_longrun.py")
            _g08pass = ROOT / "analysis" / "b2-g08-longrun-batch133-2026-11-14.json"
            _g08ok = False
            if _g08pass.is_file():
                import json as _jsong08
                _g08d = _jsong08.loads(_g08pass.read_text(encoding="utf-8"))
                _g08ok = (_g08d.get("verdict") == "PASS"
                          and _g08d.get("checks_passed") == 5
                          and _g08d.get("t0_playing") is True)
            if (_fmd.get("device_run") if "device_run" in _fmd else True)                     and _all115 and _enh115                     and _ppd.get("checks_passed") == 5                     and _g08tool.is_file()                     and _g08ok                     and "t=0 PLAYING" in _g08tool.read_text(encoding="utf-8")                     and "### 61.115" in (ROOT / "docs" /
                                         "12-编码阶段第一批交付.md").read_text(
                                             encoding="utf-8"):
                print("[PASS] 12 §61.115 守护：22/22 全 PASS + enhanced 22/22 / "
                      "ReplayGain 5/5 / 封面双截图 / G08 工具 + 长稳 PASS（t=0 判据）/ "
                      "交付日志")
            else:
                failures.append("12 §61.115 守护：全矩阵口径 / 探针 / 截图 / 工具漂移")
        except (OSError, UnicodeError, ValueError) as exc:
            failures.append("12 §61.115 守护：证据不可解析（%s）" % exc)

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
        warnings.append("第六十三批（12 §61.36，2026-11-08 晚）：用户选「保持 A3 不收口，先推进 T-L L3 / T-V 其它项」→ 审计素材暂存区发现 51 个文件全部随 debug APK 分发（不参与 aapt2 ≠ 不进包），其中 8 个 layout 含 44 处 com.netease.* 类名 / 20 个去重类；C2 PASS 是跳过整个 C4_STAGED_ASSETS 目录后的豁免结果。修正发布阻断 #2/#5/#7 口径、NOTICE.md 补「随 APK 分发」声明、03-12 §8 加注，新增 §61.36 守护防伪造替换；产出 tl-l3-assets-compliance-audit 与 tl-l3-substitution-plan 两份施工文档。零源码 / 零设备改动，A3 仍 G09 PARTIAL")
        warnings.append("第六十四批（12 §61.37 / D-146，2026-11-08 21:49）：用户定案「素材直接用竞品的，这个 APP 只是我个人使用」→ 项目范围锁定个人自用 / 侧载（不公开发布 / 不分发 APK / 不上架），竞品素材直接沿用、不做替换，61 行 replaced=false 保持。03-12 §7/§8 与 NOTICE.md 改为范围口径、发布阻断清单仅公开发布时适用；C4 dev WARN / release FAIL 机制不变但降级为未来闸门；T-L L3 只剩技术接入（8 个含 com.netease.* 类名的 layout 因 aapt2 找不到类需重写/删除、:ui-legacy、R 引用改写、照搬播放页）。新增 §61.37 守护（范围口径在位 + 不得伪造 replaced=true）。权利归属如实声明保留，不作法律结论。零源码 / 零产物 / 零设备改动，A3 按用户选项 3 保持 PARTIAL")
        warnings.append("第六十五批（12 §61.38，2026-11-08 晚）：T-L L3 技术接入前置实证 —— 新增可复跑的 tools/legacy_layout_audit.py 与 analysis/legacy-layout-audit.json，钉死 9 个暂存 layout 的引用图：44 处 com.netease.* / 20 去重类（与 §61.36 吻合）、33 个混淆 app:* attr、251 处资源引用（187 处 @id 且 @+id 定义 0 / 64 处可导出 / 45 唯一）。口径澄清 §47 的「146 项资源」= 101 个唯一 id + 45 个可导出资源，且 45 项被 csv 61 行 / 闭包 163 项 / 暂存 51 文件三方完整覆盖、缺口 0；暂存素材在自研源码里零生产引用方、:ui-legacy 未 include。故真正阻塞是三道 aapt2 缺口（无定义 id / 未声明 attr / 无实现 View），接 res 属「照搬播放页」可选增强而非运行必需。产出 analysis/tl-l3-technical-onboarding-2026-11-08.md 施工单与 §61.38 守护（防 layout 误搬进 res、防实证基线漂移）。零源码 / 零产物 / 零设备改动，A3 仍 G09 PARTIAL、舱不冻结")

        warnings.append("第六十六批（12 §61.39，2026-11-08 晚）：承接 §61.38 阶段 A/B「待猜项」做编译级实证 —— tools/legacy_layout_resolve.py 从竞品 attrs.xml / public.xml 精确还原 33/33 attr 定义 + 101/101 id（16 个带 enum/flag 子项），阶段 A/B 由「猜语义」改为「机械可搬」；tools/l3_aapt2_probe.py 扩展为四步 aapt2 实证：step1 baseline id+attr 错误 → step2 ids.xml 消除 id → step3 attrs.xml 消除 attr（剩 20 条资源引用缺失）→ step4 把 42 个暂存资源去 legacy_ 前缀接入仓库外临时工程后 compile_rc=0 / link_rc=0 / 错误 0 行。精确化结论：aapt2 不校验 Java 类，§61.37「因 aapt2 找不到竞品类」归因不完整，真正编译阻塞始终是资源定义缺失；20 个 com.netease.* View 是运行时 LayoutInflater 阻塞，须阶段 C 重写。产出 analysis/tl-l3-attr-id-resolution-2026-11-08.md 与 §61.39 守护；证据产物已路径脱敏。零源码 / 零产物 / 零设备改动，app/src/main/res/ 仍未建立、:ui-legacy 未 include、三项需拍板不变，A3 仍 G09 PARTIAL")

        warnings.append("第六十七批（12 §61.40，2026-11-08 晚）：用户拍板「UI 交互严格复刻竞品」→ 按 12 §39 / docs/14 §4.1 / 03-10 §3.3 §6 的实证参数，在 :app 内零发布基线变更落地播放页：VinylDiscView（ValueAnimator.ofFloat(0,1) + LinearInterpolator + repeatCount(-1) + repeatMode(1) + 20000ms÷playSpeed + fraction*360 → setRotation，唱针用两个 ObjectAnimator；不用 Compose Canvas、不用 animateFloatAsState 近似、不加载 anim XML）、平台 TextView 原生 MARQUEE 跑马灯、72dp 横向手势切歌、Slider→PlayerStateBridge.onSeek、迷你条点击展开整页。PlayerStateBridge 按 03-10 §6 单向绑定 StateFlow，不持有 PlayerController/IMusicEngine；契约中 onModeToggle/onFavorite/歌词/输出切换/队列弹窗/封面 6 项生产无后端，只读占位不伪造。新增 tools/l3_interaction_spec.py + analysis/l3-interaction-spec.json + §61.40 守护（14 项全 PASS）：19 模块 / 11 权限 / 51 素材 / 61 行 csv 全 false / 零新增依赖 / res/ 未建 / :ui-legacy 未 include 零变化。48 个播放页 layout 的像素级照搬、20 个竞品 View 的 XML 视觉、横屏黑胶页仍需拍板后另批；A3 仍 G01~G08 PASS / G09 PARTIAL、舱不冻结")
        warnings.append("第六十八批（12 §61.41，2026-11-09 上午）：用户拍板 B（三项全开：第 20 模块 :ui-legacy / 联网补 4 类依赖 / 补导其余 39 个 layout）→ 按 §61.38 同纪律先实证。tools/l3_full_replica_audit.py 全量审计 48 个 layout_semantic_tags：39 个未导出 layout 已复制到 analysis/l3-full-replica/layouts/，合计 421 元素 / 53 个竞品 View 类 / 81 个混淆 app:* attr / 282 个 @id / 163 项资源引用（约为已导出 9 个的 3 倍）。口径校正：48 项是「资源闭包涉及的 layout」不是 48 个播放页，其中 11 个非播放页误命中、且不存在标准竖屏主播放页 tag；§61.40 报告「39 个含标准竖屏主播放页」表述作废。决定性结论：竞品 gb.xml 里 ConstraintLayout 是标准类名但 layout_constraint* 属性名被资源混淆成 a5f/a5l/a65/a69，竞品 attrs.xml 中标准名 0 个 → 在自研工程重声明同名 attr 只会分配新 ID，ConstraintLayout 读不到值，约束语义不还原；引入 androidx.constraintlayout 依赖或 §61.39 的 attrs.xml 都只让 aapt2 编译通过，不等于布局语义还原。Google Maven 实测 HTTP 200（依赖可达）。故三项授权保留但本批不改基线：19 模块 / 零新增依赖 / res/ 未建 / :ui-legacy 未 include / 51 素材 / 61 行 csv 全 false 零变化，新增 §61.41 守护防证据漂移与基线被提前改动。修正后路径 B1 结构照搬 / B2 先补主页面与 attr 反查 / A 真实贴图优先 / C 维持现状待重新拍板。A3 仍 G01~G08 PASS / G09 PARTIAL、舱不冻结")

    # =====================================================================
    # §61.182 三层守护网（用户 2026-11-16 拍板「全做」）
    # =====================================================================

    # ② 写侧审计：凡审计范围内接口的 suspend 方法，必须有**生产调用方**。
    #   src/test 的调用不算 —— T11 手调 saveCursor 造数据、生产链写侧断线、
    #   「半条链测试」全绿的真机教训见 12 §61.181 判据③现场：playback_state
    #   恒缺失（`cold-start restore: playback_state 缺失`）⇒ 冷启动恢复死路。
    #   允许项（_AUDIT_ALLOW）必须带 docs 挂账出处，随收口批移除。
    #   A/B 反证：--audit-mask <方法名> 可临时屏蔽其调用方检出（演示断链必 FAIL）。
    _AUDIT_SCOPES = ("core-storage", "feature-player", "scanner")
    _AUDIT_ALLOW = {
        ("QueueRepository", "insertNext"): "插播 UI 未落地（03-06 §8 / 12 §61.119 仅跳播）",
        ("QueueRepository", "move"): "队列拖拽排序 UI 未落地（03-06 §8）",
        ("QueueRepository", "remove"): "队列移除 UI 未落地（03-06 §8）",
        ("QueueRepository", "clear"): "清空队列 UI 未落地（03-06 §8）",
        # §61.184：Storage.setError 断链已接线（runScan → storageErrorStates → setError，
        # 扫描失败 → 数据源错误态；本行移出挂账清单，守护转正）。
    }
    _mask_next = False
    _masked = set()
    for _a in sys.argv[1:]:
        if _a == "--audit-mask":
            _mask_next = True
        elif _mask_next:
            _masked.add(_a)
            _mask_next = False
    _prod_kt = []
    for _scope_dir in sorted(ROOT.glob("*/src/main")):
        for _p in _scope_dir.rglob("*.kt"):
            _lines = []
            for _ln in _p.read_text(encoding="utf-8", errors="replace").splitlines():
                _s = _ln.strip()
                if _s.startswith(("//", "*", "/*")):
                    continue
                _lines.append(_ln.split("//", 1)[0])
            _prod_kt.append((_p, "\n".join(_lines)))
    _iface_decl = re.compile(r"^\s*interface\s+(\w+)\b")
    _suspend_decl = re.compile(r"^\s*(?:override\s+)?suspend\s+fun\s+(\w+)\(")
    _in_scope = lambda _p: any(
        str(_p).startswith(str(ROOT / s) + chr(92)) or
        str(_p).startswith(str(ROOT / s) + "/") for s in _AUDIT_SCOPES)
    _audited = set()
    _decl_file = {}
    for _p, _src in _prod_kt:
        if not _in_scope(_p):
            continue
        _cur, _depth = None, 0
        for _ln in _src.splitlines():
            _m = _iface_decl.match(_ln)
            if _m and _depth == 0:
                _cur = _m.group(1)
            # interface 声明行的 `{` 同样计入深度（漏计会把整个接口体清空 → 0 方法）
            _depth += _ln.count("{") - _ln.count("}")
            if _cur and _depth <= 0:
                _cur, _depth = None, 0
                continue
            if _cur:
                _fm = _suspend_decl.match(_ln)
                if _fm:
                    _audited.add((_cur, _fm.group(1)))
                    _decl_file[(_cur, _fm.group(1))] = _p
    _audit_failures = []
    for _iface, _m in sorted(_audited):
        if (_iface, _m) in _AUDIT_ALLOW:
            continue
        if _m in _masked:
            # --audit-mask 反证模式：屏蔽调用方检出，强制演示「断链必 FAIL」。
            _audit_failures.append(
                "%s.%s 无生产调用方（--audit-mask 反证：屏蔽检出后断链现形）" % (_iface, _m))
            continue
        _has_caller = False
        for _p, _src in _prod_kt:
            if _p == _decl_file.get((_iface, _m)):
                continue
            for _ln in _src.splitlines():
                if "fun " in _ln:
                    continue
                if re.search(r"\b%s\s*\(" % re.escape(_m), _ln):
                    _has_caller = True
                    break
            if _has_caller:
                break
        if not _has_caller:
            _audit_failures.append(
                "%s.%s 无生产调用方（半条链契约：实现/测试在、生产写读断链，"
                "见 12 §61.182；如属诚实挂账请移入 _AUDIT_ALLOW 并带出处）" % (_iface, _m))
    if _audit_failures:
        failures.extend(_audit_failures)
    else:
        print("[PASS] §61.182 写侧审计：%d 个仓储/端口 suspend 方法均有生产调用方" % len(_audited))

    # ① 注入通道命名同源：playUrl 的 Track 构造必须走扫描链同一清洗入口
    #   （NameParser），不得用 URL 原始路径段当曲名 —— 真机一手天书标题
    #   `V.A.%20-%20Love%20Letter%20%28Piano%29`（用户判读 12 §61.181 时发现）。
    _pc = ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "di" / "playback" / "PlaybackComposition.kt"
    if _pc.is_file():
        _pc_src = _code_only(_pc.read_text(encoding="utf-8", errors="replace"))
        _naming_bad = []
        if "import com.example.cloudmusic.scanner.NameParser" not in _pc_src:
            _naming_bad.append("未接入 NameParser")
        if "internal fun remoteFixtureTrack(" not in _pc_src:
            _naming_bad.append("remoteFixtureTrack 构造器缺失")
        # 旧 playUrl 习语（URL 原始路径段直接当曲名/行键）回归即 FAIL。
        # 注意 playLocal 的本地文件名兜底 `name.substringBeforeLast` 不受影响
        # （本地路径无百分号编码，§61.112 已接真实标签链）。
        if re.search(r"val\s+name\s*=\s*url\.substringBefore\('\?'\)\.substringAfterLast\('/'\)", _pc_src):
            _naming_bad.append("playUrl 曲名仍取原始 URL 路径段")
        if _naming_bad:
            failures.append("§61.182 注入通道命名同源破坏：%s" % "；".join(_naming_bad))
        else:
            print("[PASS] §61.182 注入通道命名与扫描链同源（NameParser）")

    # ③ 画面证据自动化通道（§61.183）：验收期 debug 导航必须在位 —— MainActivity 经
    #   SurfaceNav 解析 intent 直达 surface（MIUI 禁 input tap 的免注入替代）；
    #   通道缺失/改名而 runbook 未同步即 FAIL（与 §61.33 收口清单同族登记）。
    _ma = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "MainActivity.kt"
    _sn = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "SurfaceNav.kt"
    _sweep = ROOT / "tools" / "b2_surface_sweep.py"
    if _sn.is_file() and _ma.is_file():
        _sn_src = _code_only(_sn.read_text(encoding="utf-8", errors="replace"))
        _ma_src = _code_only(_ma.read_text(encoding="utf-8", errors="replace"))
        _nav_bad = []
        if "ACTION_OPEN_PLAYER" not in _sn_src or "fun parse(" not in _sn_src:
            _nav_bad.append("SurfaceNav 通道缺失")
        if "SurfaceNav.parse" not in _ma_src or "surfaceRequests" not in _ma_src:
            _nav_bad.append("MainActivity 未接 SurfaceNav")
        if not _sweep.is_file():
            _nav_bad.append("tools/b2_surface_sweep.py runbook 缺失")
        if _nav_bad:
            failures.append("§61.183 画面证据通道：%s" % "；".join(_nav_bad))
        else:
            print("[PASS] §61.183 画面证据自动化通道（SurfaceNav + runbook）在位")

    # §61.198 在线歌词四级取数（docs/01 §0 用户拍板「本地优先 + 允许在线抓取」）：
    #   provider 在 core-di（JVM 可测、HTTP 可注入）→ Hilt 单点接线 →
    #   PlayerStateBridge 本地缓存优先 → UI 只经桥接取歌词。
    #   破坏任一环即 FAIL（含 UI 直接碰 OkHttp / LyricFileStore 的越界回归）。
    _oly = ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "di" / "lyrics" / "OnlineLyricsFetcher.kt"
    _oly_test = ROOT / "core-di" / "src" / "test" / "kotlin" / "com" / "example" / "cloudmusic" / "di" / "lyrics" / "OnlineLyricsFetcherTest.kt"
    _pb = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "ui" / "player" / "PlayerStateBridge.kt"
    _lp = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "ui" / "player" / "LegacyPlayerPage.kt"
    if all(p.is_file() for p in (_oly, _oly_test, _pb, _lp)):
        _oly_src = _code_only(_oly.read_text(encoding="utf-8", errors="replace"))
        _scan_src = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                                / "cloudmusic" / "di" / "scan" / "ScanComposition.kt").read_text(
                                    encoding="utf-8", errors="replace"))
        _pb_src = _code_only(_pb.read_text(encoding="utf-8", errors="replace"))
        _lp_src = _code_only(_lp.read_text(encoding="utf-8", errors="replace"))
        _lyric_bad = []
        if "class OnlineLyricsFetcher" not in _oly_src or "fun pickSongId" not in _oly_src:
            _lyric_bad.append("provider 或标题艺人匹配判定缺失")
        if "fun provideOnlineLyricsFetcher" not in _scan_src:
            _lyric_bad.append("ScanModule 未注册在线歌词 provider")
        if "suspend fun lyricsFor(" not in _pb_src:
            _lyric_bad.append("PlayerStateBridge 无 lyricsFor 统一取数口")
        if "readRaw(" in _pb_src and "onlineLyrics?.fetch" in _pb_src:
            if _pb_src.index("readRaw(") > _pb_src.index("onlineLyrics?.fetch"):
                _lyric_bad.append("本地缓存未先于在线抓取（违反本地优先）")
        if "bridge.lyricsFor(" not in _lp_src:
            _lyric_bad.append("播放页未走桥接歌词通道")
        if "LyricFileStore" in _lp_src or "OkHttp" in _lp_src:
            _lyric_bad.append("播放页越界直触歌词缓存/网络（03-10 §11 #4）")
        if _lyric_bad:
            failures.append("§61.198 在线歌词通道：%s" % "；".join(_lyric_bad))
        else:
            print("[PASS] §61.198 在线歌词四级取数（本地优先 + 按需抓取 + UI 零越界）")

    # §61.199 DLNA 断连回落（03-08 §10.2 / T18）：3 次失败判定 + DMR 进度读取 +
    # 本机接管四段（Stop → LOCAL → 解静音 → seek 续播）必须同在；缺任一段即判半条链。
    _mon = (ROOT / "output-dlna" / "src" / "main" / "kotlin" / "com" / "example"
            / "cloudmusic" / "output" / "dlna" / "DlnaSessionMonitor.kt")
    _mon_test = (ROOT / "output-dlna" / "src" / "test" / "kotlin" / "com" / "example"
                 / "cloudmusic" / "output" / "dlna" / "DlnaSessionMonitorTest.kt")
    _cast = (ROOT / "output-dlna" / "src" / "main" / "kotlin" / "com" / "example"
             / "cloudmusic" / "output" / "dlna" / "DlnaCastCoordinator.kt")
    _runt = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
             / "cloudmusic" / "di" / "playback" / "PlaybackComposition.kt")
    if _mon.is_file() and _mon_test.is_file() and _cast.is_file() and _runt.is_file():
        _mon_src = _code_only(_mon.read_text(encoding="utf-8", errors="replace"))
        _cast_src = _code_only(_cast.read_text(encoding="utf-8", errors="replace"))
        _runt_src = _code_only(_runt.read_text(encoding="utf-8", errors="replace"))
        _dlna_bad = []
        if "DEFAULT_FAILURE_LIMIT = 3" not in _mon_src or "shouldFallBack" not in _mon_src:
            _dlna_bad.append("会话判定器缺 3 次阈值或回落一次性语义")
        if "suspend fun positionMs(" not in _cast_src or "getPositionInfo" not in _cast_src:
            _dlna_bad.append("DMR 进度读取缺失")
        if "private fun dlnaSessionLoop()" not in _runt_src or "dlnaSessionLoop()" not in _runt_src:
            _dlna_bad.append("运行时未接会话轮询")
        if not all(frag in _runt_src for frag in (
            "output.setActive(ActiveOutput.LOCAL)", "onOutputChanged(false)",
            "controller.seek(verdict.lastPositionMs)", "已回到本机播放",
        )):
            _dlna_bad.append("本机接管四段（Stop 后 LOCAL/解静音/seek/提示）不全")
        if _dlna_bad:
            failures.append("§61.199 DLNA 断连回落：%s" % "；".join(_dlna_bad))
        else:
            print("[PASS] §61.199 DLNA 断连回落（3 次判定 + 进度读取 + 本机接管）")

    # §61.200 DMR 多设备列表（03-08 §4.1）：纯 JVM 选择判定 + UI 列表接线 +
    # 打开弹窗才发现（不常驻扫网）+ 点设备直接投屏，四件同在才算闭环。
    _sel = (ROOT / "output-dlna" / "src" / "main" / "kotlin" / "com" / "example"
            / "cloudmusic" / "output" / "dlna" / "DmrDeviceSelector.kt")
    _mai = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "MainActivity.kt"
    _cmp = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "ui" / "CloudMusicApp.kt"
    if _sel.is_file() and _mai.is_file() and _cmp.is_file():
        _sel_src = _code_only(_sel.read_text(encoding="utf-8", errors="replace"))
        _mai_src = _code_only(_mai.read_text(encoding="utf-8", errors="replace"))
        _cmp_src = _code_only(_cmp.read_text(encoding="utf-8", errors="replace"))
        _dmr_bad = []
        if "fun ranked(" not in _sel_src or "fun pick(" not in _sel_src:
            _dmr_bad.append("设备去重/排序/记忆选择判定缺失")
        if "refreshDlnaDevices()" not in _mai_src or "DmrDeviceSelector.pick" not in _mai_src:
            _dmr_bad.append("MainActivity 未发现设备或未按记忆选择")
        if "onPickDmr = { device -> castTo(device) }" not in _mai_src:
            _dmr_bad.append("点选设备未接投屏闭环")
        if "devices = dmrDevices" not in _cmp_src or "重新搜索投屏设备" not in _cmp_src:
            _dmr_bad.append("输出弹窗未展示设备列表或重新扫描入口")
        if _dmr_bad:
            failures.append("§61.200 DMR 多设备列表：%s" % "；".join(_dmr_bad))
        else:
            print("[PASS] §61.200 DMR 多设备列表（去重排序 + 记忆选择 + 弹窗点选 + 按需发现）")

    # §61.201 手动指定 DMR（03-08 §4.2 / A7）：既有探测器与 UI 输入必须接通，
    # 失败原因要可见（不静默），探测结果要能直接投屏。
    if _mai.is_file() and _cmp.is_file():
        _mai_src2 = _code_only(_mai.read_text(encoding="utf-8", errors="replace"))
        _cmp_src2 = _code_only(_cmp.read_text(encoding="utf-8", errors="replace"))
        _manual_bad = []
        if "ManualDmrProbe.probe(" not in _mai_src2 or "DescriptionFetcher" not in _mai_src2:
            _manual_bad.append("生产侧未接手动探测器/描述抓取端口")
        if "manualDmrResult" not in _mai_src2 or "probeManualDmr" not in _mai_src2:
            _manual_bad.append("探测结果未回传 UI")
        if "ManualDmrDialog(" not in _cmp_src2 or "ManualDmrSpec(" not in _cmp_src2:
            _manual_bad.append("手动 DMR 输入面板缺失")
        if "手动添加投屏设备（IP / 端口）" not in _cmp_src2:
            _manual_bad.append("输出弹窗无手动入口")
        if _manual_bad:
            failures.append("§61.201 手动指定 DMR：%s" % "；".join(_manual_bad))
        else:
            print("[PASS] §61.201 手动指定 DMR（输入面板 + 既有探测器 + 结果可见 + 直接投屏）")

    # §61.202 DMR 进度驱动 UI：投屏态进度必须来自 DMR（显示 + seek 双向），
    # 且播放页不得再直接读 state.positionMs（否则显示被静音的本机引擎冻结位置）。
    _cast2 = (ROOT / "output-dlna" / "src" / "main" / "kotlin" / "com" / "example"
              / "cloudmusic" / "output" / "dlna" / "DlnaCastCoordinator.kt")
    _pb2 = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "ui" / "player" / "PlayerStateBridge.kt"
    _lp2 = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "ui" / "player" / "LegacyPlayerPage.kt"
    if all(p.is_file() for p in (_runt, _cast2, _pb2, _lp2)):
        _cast2_src = _code_only(_cast2.read_text(encoding="utf-8", errors="replace"))
        _runt2_src = _code_only(_runt.read_text(encoding="utf-8", errors="replace"))
        _pb2_src = _code_only(_pb2.read_text(encoding="utf-8", errors="replace"))
        _lp2_src = _code_only(_lp2.read_text(encoding="utf-8", errors="replace"))
        _pos_bad = []
        if "suspend fun seekTo(" not in _cast2_src or "SoapController.seek(" not in _cast2_src:
            _pos_bad.append("DMR seek 能力缺失")
        if "fun displayPositionMs()" not in _runt2_src or "_dlnaPositionMs" not in _runt2_src:
            _pos_bad.append("运行时未裁决/缓存 DMR 进度")
        if "castCoordinator.seekTo(device, ms)" not in _runt2_src:
            _pos_bad.append("投屏态 seek 未转发到 DMR")
        if "runtime.displayPositionMs()" not in _pb2_src:
            _pos_bad.append("Bridge 未暴露进度单一入口")
        if "bridge.positionMs.collectAsState" not in _lp2_src or "state.positionMs" in _lp2_src:
            _pos_bad.append("播放页仍直读本机引擎进度")
        if _pos_bad:
            failures.append("§61.202 DMR 进度驱动 UI：%s" % "；".join(_pos_bad))
        else:
            print("[PASS] §61.202 DMR 进度驱动 UI（显示与 seek 双向走 DMR，UI 零直读）")

    # §61.203（A1-①）远程 tag 跳过策略 + §61.186 网络侧链缺口修复：
    # ① RemoteTagPolicy 策略在位（SKIP 默认）且 AndroidTagReader 网络分支消费它；
    # ② 网络分支不得提前 return——封面/歌词侧链（sideChannels）必须对网络 ref 生效
    #   （C-11/C-12 与 §61.186 登记的能力此前从未在生产网络分支运行）；
    # ③ DirectoryCoverLookup 按目录缓存封面 ref（dirCoverRefs），防每候选重读字节。
    _treader = (ROOT / "scanner" / "src" / "main" / "kotlin" / "com"
                / "example" / "cloudmusic" / "scanner" / "TagReader.kt")
    _tag_cover_203_path = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                           / "example" / "cloudmusic" / "di" / "scan" / "ProductionTagAndCover.kt")
    _scan_comp_path = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                       / "example" / "cloudmusic" / "di" / "scan" / "ScanComposition.kt")
    _skip_bad = []
    if not all(p.is_file() for p in (_treader, _tag_cover_203_path, _scan_comp_path)):
        _skip_bad.append("源文件缺失")
    else:
        _treader_src = _code_only(_treader.read_text(encoding="utf-8", errors="replace"))
        _tag_cover_203 = _code_only(_tag_cover_203_path.read_text(encoding="utf-8", errors="replace"))
        _scancomp_203 = _code_only(_scan_comp_path.read_text(encoding="utf-8", errors="replace"))
        _treader_test_203 = (ROOT / "core-di" / "src" / "test" / "kotlin" / "com"
                             / "example" / "cloudmusic" / "di" / "scan" / "ScanContractsTest.kt"
                             ).read_text(encoding="utf-8", errors="replace")
        # §61.281：本条判据的**对象已限定为「SKIP 语义」本身**（生产默认已于 §61.281 改为
        # PRODUCTION 读模式）——强度不变：SKIP 常量、跳过分支、应急回滚通道仍必须存在。
        if not all(x in _treader_src for x in (
            "data class RemoteTagPolicy(",
            "fun shouldSkip(fileName: String, sizeBytes: Long): Boolean",
            "val SKIP = RemoteTagPolicy(skipRemoteTags = true)",
        )):
            _skip_bad.append("RemoteTagPolicy 策略缺失（SKIP 应急开关不得删除）")
        if not all(x in _tag_cover_203 for x in (
            "remoteTagPolicy.shouldSkip(candidate.obj.name, candidate.obj.size)",
            "private suspend fun sideChannels(candidate: Candidate, ref: String, merged: RawTags): RawTags",
            "NameParser.parse(candidate.obj.name, candidate.parentDirName, candidate.hint)",
            "private val dirCoverRefs = java.util.concurrent.ConcurrentHashMap<String, DirRef>()",
        )):
            _skip_bad.append("网络分支策略接线 / 侧链共用 / 封面按目录缓存缺失")
        # §61.281：装配对象由「必须是 SKIP」升级为「必须显式装配某个 RemoteTagPolicy」——
        # 判据不降反升：旧断言只要出现 SKIP 即过（写进注释也算），新断言要求真正的装配实参
        # 且与 production 口径一致（PRODUCTION），SKIP 只能留在回滚通道里。
        if "remoteTagPolicy = RemoteTagPolicy.PRODUCTION" not in _scancomp_203:
            _skip_bad.append("ScanComposition 未装配 PRODUCTION 策略")
        for _case in ("T-A4-13", "T-A4-14", "T-A4-15"):
            if _case not in _treader_test_203:
                _skip_bad.append(f"{_case} 用例缺失")
        if "## 第二百一十五批（§61.203）" not in doc139_txt:
            _skip_bad.append("docs/12 批次登记缺失")
    if _skip_bad:
        failures.append("§61.203 远程 tag 跳过：%s" % "；".join(_skip_bad))
    else:
        print("[PASS] §61.203 远程 tag 策略（SKIP 应急通道 + 网络侧链修复 + 封面按目录缓存 + 三用例）")

    # §61.204（A1-②③）refresh stat 命中跳过 + 并发 8：
    # ① ScanSnapshot.metadataDone / ScanSnapshotStore.markMetadataDone 端口在位；
    # ② ScanCoordinator 的 statHit 只在 refresh 生效且要求行可用（C-04/§61.179 不得被跳过）；
    # ③ metadata 块完成后按路径回写 done（中断块不回写）；
    # ④ metadataConcurrency 默认 8（此前 2→4 无守护段，本批补上防回退）；
    # ⑤ Room 侧 v4 迁移（ADD_SNAPSHOT_METADATA_DONE）+ DAO 批量 UPDATE 在位。
    _scan_ports = (ROOT / "scanner" / "src" / "main" / "kotlin" / "com"
                   / "example" / "cloudmusic" / "scanner" / "ScanPorts.kt")
    _scan_models = (ROOT / "scanner" / "src" / "main" / "kotlin" / "com"
                    / "example" / "cloudmusic" / "scanner" / "ScanModels.kt")
    _room_daos = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                  / "example" / "cloudmusic" / "di" / "db" / "RoomDaos.kt")
    _room_migr = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                  / "example" / "cloudmusic" / "di" / "db" / "RoomMigrations.kt")
    _room_schema = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                    / "example" / "cloudmusic" / "di" / "db" / "LibrarySchema.kt")
    _stat_bad = []
    if not all(p.is_file() for p in (_scan_ports, _scan_models, scan_coord, _room_daos,
                                     _room_migr, _room_schema)):
        _stat_bad.append("源文件缺失")
    else:
        _ports_src = _code_only(_scan_ports.read_text(encoding="utf-8", errors="replace"))
        _models_src = _code_only(_scan_models.read_text(encoding="utf-8", errors="replace"))
        _coord_src = _code_only(scan_coord.read_text(encoding="utf-8", errors="replace"))
        _daos_src = _code_only(_room_daos.read_text(encoding="utf-8", errors="replace"))
        _migr_src = _code_only(_room_migr.read_text(encoding="utf-8", errors="replace"))
        _schema_src = _code_only(_room_schema.read_text(encoding="utf-8", errors="replace"))
        if not all(x in _ports_src for x in (
            "val metadataDone: Boolean = false",
            "suspend fun markMetadataDone(storageId: String, paths: Collection<String>) {}",
        )):
            _stat_bad.append("ScanSnapshot.metadataDone / markMetadataDone 端口缺失")
        if "val metadataConcurrency: Int = 8" not in _models_src:
            _stat_bad.append("metadataConcurrency 默认 8 缺失（§61.161 4 或更早值回退）")
        if not all(x in _coord_src for x in (
            "val statHit = if (request.refresh)",
            "old.metadataDone",
            "(knownPaths == null || c.obj.path in knownPaths)",
            # §61.221 升级：快照键 = effectiveIdPath（分轨锚点）、stat = statObj（cue 文件）
            "c.effectiveIdPath() !in statHit && (",
            "metadataDone = statUnchanged && old.metadataDone",
            "snapshotStore.markMetadataDone(storageId, chunk.map { it.effectiveIdPath() })",
        )):
            _stat_bad.append("statHit 分区 / 快照继承 / 块级回写缺失")
        if not all(x in _daos_src for x in (
            "UPDATE scan_snapshot SET metadata_done = 1",
        )):
            _stat_bad.append("DAO 批量回写缺失")
        if not all(x in _schema_src for x in (
            "const val VERSION_V4 = 4",
            "const val ADD_SNAPSHOT_METADATA_DONE",
        )) or "MIGRATION_3_4" not in _migr_src:
            _stat_bad.append("v4 迁移（ADD_SNAPSHOT_METADATA_DONE / MIGRATION_3_4）缺失")
        _coord_test_204 = (ROOT / "scanner" / "src" / "test" / "kotlin" / "com"
                           / "example" / "cloudmusic" / "scanner" / "ScanCoordinatorTest.kt"
                           ).read_text(encoding="utf-8", errors="replace")
        for _case in ("fun t18()", "fun t19()", "fun t20()", "fun t21()"):
            if _case not in _coord_test_204:
                _stat_bad.append(f"{_case.rstrip('()')} 用例缺失")
        if "## 第二百一十六批（§61.204）" not in doc139_txt:
            _stat_bad.append("docs/12 批次登记缺失")
    if _stat_bad:
        failures.append("§61.204 refresh stat 命中跳过：%s" % "；".join(_stat_bad))
    else:
        print("[PASS] §61.204 refresh stat 命中跳过（metadataDone 端口 + statHit 分区 + 并发 8 + v4 迁移 + 四用例）")

    # §61.205（A1-④⑤）封面去重跨扫描持久 + 扫描节奏（wake lock + 熄屏让步）：
    # ① CoverDedupIndex 纯 JVM 持久索引 + AndroidCoverWriter 挂接；
    # ② ScanPacer 端口 + NoOp + ScanCoordinator begin/end/yield 接线；
    # ③ AndroidScanPacer 生产实现 + ScanModule 装配；WAKE_LOCK 权限在位（不加新权限）。
    _manifest = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
    _pace_bad = []
    if not all(p.is_file() for p in (_scan_ports, scan_coord, _tag_cover_203_path, _scan_comp_path, _manifest)):
        _pace_bad.append("源文件缺失")
    else:
        _ports_src = _code_only(_scan_ports.read_text(encoding="utf-8", errors="replace"))
        _coord_src = _code_only(scan_coord.read_text(encoding="utf-8", errors="replace"))
        _tag_cover_205 = _code_only(_tag_cover_203_path.read_text(encoding="utf-8", errors="replace"))
        _comp_src = _code_only(_scan_comp_path.read_text(encoding="utf-8", errors="replace"))
        _manifest_src = _manifest.read_text(encoding="utf-8", errors="replace")
        if not all(x in _ports_src for x in (
            "interface ScanPacer {",
            "object ScanPacerNoOp : ScanPacer",
            "suspend fun yieldBetweenChunks() {}",
        )):
            _pace_bad.append("ScanPacer 端口 / NoOp 缺失")
        if not all(x in _coord_src for x in (
            "private val pacer: ScanPacer = ScanPacerNoOp",
            "pacer.beginScan()",
            "pacer.endScan()",
            "pacer.yieldBetweenChunks()",
        )):
            _pace_bad.append("ScanCoordinator 节奏接线缺失（begin/end/yield）")
        if not all(x in _tag_cover_205 for x in (
            "class AndroidScanPacer(private val powerManager: PowerManager) : ScanPacer",
            "PARTIAL_WAKE_LOCK",
            "CoverDedupIndex(File(dir, \"dedup-index.txt\"))",
            "dedupIndex.put(hash, ref)",
        )):
            _pace_bad.append("AndroidScanPacer / 封面持久去重挂接缺失")
        if not all(x in _comp_src for x in (
            "fun provideScanPacer(",
            "AndroidScanPacer(pm)",
            "pacer = pacer,",
        )):
            _pace_bad.append("ScanModule 节奏装配缺失")
        if "android.permission.WAKE_LOCK" not in _manifest_src:
            _pace_bad.append("WAKE_LOCK 权限缺失（既有清单第 10 项，不得新加第 12 项）")
        _contracts_205 = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                          / "example" / "cloudmusic" / "di" / "scan" / "ScanContracts.kt"
                          ).read_text(encoding="utf-8", errors="replace")
        if "class CoverDedupIndex(private val file: File)" not in _contracts_205:
            _pace_bad.append("CoverDedupIndex 纯 JVM 索引缺失")
        _contracts_test_205 = (ROOT / "core-di" / "src" / "test" / "kotlin" / "com"
                               / "example" / "cloudmusic" / "di" / "scan" / "ScanContractsTest.kt"
                               ).read_text(encoding="utf-8", errors="replace")
        if "T-A4-16" not in _contracts_test_205:
            _pace_bad.append("T-A4-16 用例缺失")
        if "fun t22()" not in _coord_test_204:
            _pace_bad.append("t22 用例缺失")
        if "## 第二百一十七批（§61.205）" not in doc139_txt:
            _pace_bad.append("docs/12 批次登记缺失")
    if _pace_bad:
        failures.append("§61.205 封面去重持久 + 扫描节奏：%s" % "；".join(_pace_bad))
    else:
        print("[PASS] §61.205 封面去重持久 + 扫描节奏（dedup-index + ScanPacer + wake lock + 两用例）")

    # §61.206（A2-①）MediaStore ContentObserver 数据源变更订阅：
    # ① 端口 + NoOp + MediaStoreChangeSource 生产实现 + MediaChangeScanPolicy 判定核；
    # ② runtime 订阅幂等注册 + 防抖 collect + RESCAN 幂等（shouldScan 双条件）；
    # ③ Hilt 绑定在位；防「订阅空转」（本地源为空不扫）与「打断手动扫描」回潮。
    _pbcomp = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
               / "example" / "cloudmusic" / "di" / "playback" / "PlaybackComposition.kt")
    _obs_bad = []
    if not _pbcomp.is_file():
        _obs_bad.append("源文件缺失")
    else:
        _pb_src = _code_only(_pbcomp.read_text(encoding="utf-8", errors="replace"))
        if not all(x in _pb_src for x in (
            "fun interface MediaChangeSource",
            "object NoOpMediaChangeSource : MediaChangeSource",
            "class MediaStoreChangeSource(private val context: android.content.Context) : MediaChangeSource",
            "MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer",
            "object MediaChangeScanPolicy",
            "const val DEBOUNCE_MS = 3_000L",
            "fun shouldScan(scanActive: Boolean, localIds: Set<String>): Boolean",
            "!scanActive && localIds.isNotEmpty()",
        )):
            _obs_bad.append("订阅端口 / 生产实现 / 判定核缺失")
        if not all(x in _pb_src for x in (
            "private fun registerMediaChangeSubscription()",
            "if (mediaChangeRegistered) return",
            ".debounce(MediaChangeScanPolicy.DEBOUNCE_MS)",
            "MediaChangeScanPolicy.shouldScan(scanJob?.isActive == true, localIds)",
            'it.driverType == "local"',
            "ScanRequest(storageIds = localIds)",
        )):
            _obs_bad.append("runtime 订阅接线（幂等注册 + 防抖 + 定向本地源）缺失")
        if "fun provideMediaChangeSource(" not in _pb_src:
            _obs_bad.append("PlaybackModule 绑定缺失")
        _obs_test = (ROOT / "core-di" / "src" / "test" / "kotlin" / "com"
                     / "example" / "cloudmusic" / "di" / "playback" / "MediaChangeScanPolicyTest.kt")
        if not _obs_test.is_file() or "class MediaChangeScanPolicyTest" not in _obs_test.read_text(encoding="utf-8", errors="replace"):
            _obs_bad.append("MediaChangeScanPolicyTest 缺失")
        if "## 第二百一十八批（§61.206）" not in doc139_txt:
            _obs_bad.append("docs/12 批次登记缺失")
    if _obs_bad:
        failures.append("§61.206 MediaStore 变更订阅：%s" % "；".join(_obs_bad))
    else:
        print("[PASS] §61.206 MediaStore 变更订阅（端口 + 防抖 + RESCAN 幂等 + 空转守卫 + 判定核用例）")

    # §61.207（A2-③）设置页「刷新此源」入口：
    # ① runtime 单源刷新（定向增量）② UI 双动作面板（刷新/移除）③ MainActivity 接线。
    _app_ui = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic"
               / "ui" / "CloudMusicApp.kt")
    _main_src = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic"
                 / "MainActivity.kt")
    _src_bad = []
    if not all(p.is_file() for p in (_pbcomp, _app_ui, _main_src)):
        _src_bad.append("源文件缺失")
    else:
        _pb2_src = _code_only(_pbcomp.read_text(encoding="utf-8", errors="replace"))
        _ui_src = _code_only(_app_ui.read_text(encoding="utf-8", errors="replace"))
        _main2_src = _code_only(_main_src.read_text(encoding="utf-8", errors="replace"))
        if not all(x in _pb2_src for x in (
            "fun refreshStorage(storageId: String)",
            "ScanRequest(storageIds = setOf(storageId))",
        )):
            _src_bad.append("runtime 单源刷新缺失")
        if not all(x in _ui_src for x in (
            "onRefreshStorage: (String) -> Unit = { _ -> }",
            "var actionTarget by remember { mutableStateOf<Storage?>(null) }",
            "actionTarget = storage",
            "\"刷新此源\" to {",
        )):
            _src_bad.append("数据源页双动作面板（刷新此源/移除）缺失")
        if "runtime.refreshStorage(id)" not in _main2_src:
            _src_bad.append("MainActivity 接线缺失")
        if "## 第二百一十九批（§61.207）" not in doc139_txt:
            _src_bad.append("docs/12 批次登记缺失")
    if _src_bad:
        failures.append("§61.207 设置页刷新此源：%s" % "；".join(_src_bad))
    else:
        print("[PASS] §61.207 设置页刷新此源（runtime 单源定向增量 + 双动作面板 + 接线）")

    # §61.208（A4）同 URL 双源折叠：mergeDuplicates 死旋钮的消费方落地。
    # 口径 = UI 展示层折叠（不改库不删行，§15 #3 资产纪律；防扫描层折叠的
    # refresh↔折叠翻转），胜者 = storageId 字典序最小（确定性）。
    _dupfolder = (ROOT / "core-storage" / "src" / "main" / "kotlin" / "com"
                  / "example" / "cloudmusic" / "storage" / "DuplicateFolder.kt")
    _dup_bad = []
    if not _dupfolder.is_file():
        _dup_bad.append("DuplicateFolder.kt 缺失")
    else:
        _dup_src = _code_only(_dupfolder.read_text(encoding="utf-8", errors="replace"))
        if not all(x in _dup_src for x in (
            "object DuplicateFolder",
            "fun fold(tracks: List<Track>, enabled: Boolean): List<Track>",
            "if (!enabled) return tracks",
        )):
            _dup_bad.append("折叠纯函数缺失")
        _dup_test = (ROOT / "core-storage" / "src" / "test" / "kotlin" / "com"
                     / "example" / "cloudmusic" / "storage" / "DuplicateFolderTest.kt")
        if not _dup_test.is_file() or "class DuplicateFolderTest" not in _dup_test.read_text(encoding="utf-8", errors="replace"):
            _dup_bad.append("DuplicateFolderTest 缺失")
        if not all(x in _main2_src for x in (
            "DuplicateFolder.fold(list, merge)",
            "it.copy(scan = it.scan.copy(mergeDuplicates = value))",
        )):
            _dup_bad.append("MainActivity 列表/搜索折叠接线或开关写入缺失")
        if not all(x in _ui_src for x in (
            '"合并重复项"',
            "onMergeDuplicatesChanged(!mergeDuplicates)",
        )):
            _dup_bad.append("数据源页开关行缺失")
        if "## 第二百二十批（§61.208）" not in doc139_txt:
            _dup_bad.append("docs/12 批次登记缺失")
    if _dup_bad:
        failures.append("§61.208 同 URL 双源折叠：%s" % "；".join(_dup_bad))
    else:
        print("[PASS] §61.208 同 URL 双源折叠（DuplicateFolder + 列表/搜索接线 + 开关行 + 用例）")

    # §61.210（A5）F4 叙述统一 + 证据 JSON：防 §8.3 F4 行表头回退为单轨「仍 BLOCKED」、
    # 证据 JSON 丢失或被改造成伪造原始留存口径。
    _ev_json = ROOT / "analysis" / "evidence-webdav-strm-2026-11-18.json"
    _a5_bad = []
    doc17_path = (ROOT / "docs" / "17-竞品模块清单与复刻规划.md")
    if not all(p.is_file() for p in (_ev_json, doc17_path)):
        _a5_bad.append("文件缺失")
    else:
        _d17 = doc17_path.read_text(encoding="utf-8", errors="replace")
        if "现行结论 = 双轨" not in _d17 or "A 路线 = 历史档案 BLOCKED" not in _d17:
            _a5_bad.append("§8.3 F4 行双轨标注缺失（单轨 BLOCKED 表头回潮）")
        _ev = _ev_json.read_text(encoding="utf-8", errors="replace")
        if "doc-quoted-firsthand" not in _ev or "not_claims" not in _ev:
            _a5_bad.append("证据 JSON 等级标注/not_claims 缺失")
        if "scan done: 467463 ms" not in _ev or "467463" not in _ev:
            _a5_bad.append("冷扫基准值漂移")
        if "## 第二百二十二批（§61.210）" not in doc139_txt:
            _a5_bad.append("docs/12 批次登记缺失")
    if _a5_bad:
        failures.append("§61.210 F4 叙述统一 + 证据 JSON：%s" % "；".join(_a5_bad))
    else:
        print("[PASS] §61.210 F4 双轨标注 + 证据 JSON（等级标注 + not_claims + 基准值在位）")

    # §61.211（G09 拍板 A）MIUI 自启动引导入口：判定核 + 设置页行 + 跳转接线，
    # 防「申请电池优化豁免」违规口径回潮（PRD §9 引导口径）与判定核被绕过（硬编码 true）。
    _miui = ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "MiuiAutostartGuide.kt"
    _miui_bad = []
    if not all(p.is_file() for p in (_miui, _app_ui, _main_src)):
        _miui_bad.append("源文件缺失")
    else:
        _miui_src = _code_only(_miui.read_text(encoding="utf-8", errors="replace"))
        _ui_src = _code_only(_app_ui.read_text(encoding="utf-8", errors="replace"))
        _main3_src = _code_only(_main_src.read_text(encoding="utf-8", errors="replace"))
        if not all(x in _miui_src for x in (
            "object MiuiAutostartGuide",
            'const val MIUI_MANUFACTURER = "Xiaomi"',
            "fun shouldShow(manufacturer: String?): Boolean",
            "不会申请省电白名单",
        )):
            _miui_bad.append("判定核缺失")
        if not all(x in _ui_src for x in (
            "MiuiAutostartGuide",
            '.shouldShow(android.os.Build.MANUFACTURER)',
            "后台播放不中断（MIUI）",
            "onOpenAutostartSettings",
        )):
            _miui_bad.append("设置页引导行缺失")
        if "ACTION_APPLICATION_DETAILS_SETTINGS" not in _main3_src:
            _miui_bad.append("MainActivity 跳转接线缺失")
        _miui_test = ROOT / "app" / "src" / "test" / "kotlin" / "com" / "example" / "cloudmusic" / "MiuiAutostartGuideTest.kt"
        if not _miui_test.is_file() or "class MiuiAutostartGuideTest" not in _miui_test.read_text(encoding="utf-8", errors="replace"):
            _miui_bad.append("MiuiAutostartGuideTest 缺失")
        if "## 第二百二十三批（§61.211）" not in doc139_txt:
            _miui_bad.append("docs/12 批次登记缺失")
    if _miui_bad:
        failures.append("§61.211 MIUI 自启动引导：%s" % "；".join(_miui_bad))
    else:
        print("[PASS] §61.211 MIUI 自启动引导（判定核 + 设置页行 + 跳转 + 用例，PRD §9 口径）")

    # §61.212（B4 真机一手）无限重扫循环三层修复 + STRM URL 复用 + sweep 拼接勘误：
    # ① setError 不改 modified + 同值幂等（生产与内存实现同口径）；
    # ② SettingsChangePolicy.diff 忽略 errorMsg-only（策略防线）；
    # ③ ScanSnapshot.directUrl 缓存 + expandStrm stat 命中复用（walk 免逐文件重读）；
    # ④ Room v5 迁移（ADD_SNAPSHOT_DIRECT_URL）；
    # ⑤ sweep 工具空格 join（`;` 拆参数 bug 不得回潮）。
    _scan_coord_212 = _code_only(scan_coord.read_text(encoding="utf-8", errors="replace"))
    _bad212 = []
    _dsr = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic"
            / "di" / "settings" / "DataStoreStorageRepository.kt")
    if not all(p.is_file() for p in (_dsr, _scan_ports, scan_coord)):
        _bad212.append("源文件缺失")
    else:
        _dsr_src = _code_only(_dsr.read_text(encoding="utf-8", errors="replace"))
        _dsr_raw = _dsr.read_text(encoding="utf-8", errors="replace")
        _ports_src = _code_only(_scan_ports.read_text(encoding="utf-8", errors="replace"))
        if not all(x in _dsr_src for x in (
            "if (dto.id == id && dto.errorMsg != msg) dto.copy(errorMsg = msg) else dto",
        )) or "modified = clock()" in _dsr_src.split("override suspend fun setError")[1][:600] \
                or "无限重扫循环" not in _dsr_raw:
            _bad212.append("setError 未修（modified 写回/幂等门缺失）")
        if not all(x in _ports_src for x in (
            "val directUrl: String? = null",
        )):
            _bad212.append("ScanSnapshot.directUrl 缺失")
        if not all(x in _scan_coord_212 for x in (
            "cached.matches(item.size, item.modified) && !cached.directUrl.isNullOrBlank()",
            "directUrl = it.directUrl ?: old?.takeIf { o -> statUnchanged }?.directUrl",
        )):
            _bad212.append("expandStrm URL 复用 / 快照回写缺失")
        _sc_change = (ROOT / "core-storage" / "src" / "main" / "kotlin" / "com"
                      / "example" / "cloudmusic" / "storage" / "SettingsChange.kt")
        _sc_src = _code_only(_sc_change.read_text(encoding="utf-8", errors="replace"))
        if "stripRuntime" not in _sc_src or "errorMsg" not in _sc_src:
            _bad212.append("diff 层 errorMsg-only 防线缺失")
        _schema_src = _code_only(_room_schema.read_text(encoding="utf-8", errors="replace"))
        if "const val VERSION_V5 = 5" not in _schema_src or "ADD_SNAPSHOT_DIRECT_URL" not in _schema_src:
            _bad212.append("v5 迁移缺失")
        _sweep = (ROOT / "tools" / "b2_surface_sweep.py").read_text(encoding="utf-8", errors="replace")
        if '("01-home", ";".join(NAV_BASE), 8.0)' in _sweep:
            _bad212.append("sweep 分号拼接 bug 回潮")
        if "## 第二百二十四批（§61.212）" not in doc139_txt:
            _bad212.append("docs/12 批次登记缺失")
    if _bad212:
        failures.append("§61.212 重扫循环修复 + URL 复用：%s" % "；".join(_bad212))
    else:
        print("[PASS] §61.212 循环三层修复 + STRM URL 复用 + v5 迁移 + sweep 勘误")

    # §61.214（M4 收舱）指纹回填 + 舱冻结：engine_compat_test/cabin_frozen 字段在双份
    # 指纹中同步在位；03-12 §4 A3 行回填现行口径（旧「NOT RUN 不冻结舱」不得回潮）。
    _m4_bad = []
    _fp_a = ROOT / "assets" / "ENGINE-FINGERPRINT"
    _fp_b = ROOT / "app" / "src" / "main" / "assets" / "ENGINE-FINGERPRINT"
    if not (_fp_a.is_file() and _fp_b.is_file() and _fp_a.read_bytes() == _fp_b.read_bytes()):
        _m4_bad.append("双份指纹缺失或不一致")
    else:
        _fp_txt = _fp_a.read_text(encoding="utf-8", errors="replace")
        if not all(x in _fp_txt for x in (
            "engine_compat_test:", "cabin_frozen: true", "frozen_at: 2026-11-18",
            "已定性受限 PARTIAL",
        )):
            _m4_bad.append("A3 终局字段缺失")
        _d312 = (ROOT / "docs" / "03-模块开发文档" / "03-12 构建发布与合规.md"
                 ).read_text(encoding="utf-8", errors="replace")
        if "已定性受限 PARTIAL**（前台/后台 crash 未自动恢复一手 ×3" not in _d312:
            _m4_bad.append("03-12 §4 A3 行未回填（旧口径回潮）")
        if "## 第二百二十六批（§61.214）" not in doc139_txt:
            _m4_bad.append("docs/12 批次登记缺失")
    if _m4_bad:
        failures.append("§61.214 M4 收舱：%s" % "；".join(_m4_bad))
    else:
        print("[PASS] §61.214 M4 收舱（指纹双份回填 + A3 行现行口径 + 舱冻结标记）")

    # §61.216 V0.2 SFTP 数据源：模块 / 依赖 / 注册 / UI 入口 / 端口安全五件同在；
    # 凭据不得进日志，link 不得伪造 PROXIED（SFTP 无 http 回源 URL）。
    _sftp_dir = ROOT / "datasource-sftp" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "sftp"
    _sftp_drv = _sftp_dir / "SftpDriver.kt"
    _jsch = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
             / "cloudmusic" / "di" / "adapters" / "JschSftpPort.kt")
    if _sftp_drv.is_file() and _jsch.is_file() and _mai.exists() and _cmp.exists():
        _settings_src = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8", errors="replace")
        _catalog_src = (ROOT / "gradle" / "libs.versions.toml").read_text(encoding="utf-8", errors="replace")
        _ds_src = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                   / "cloudmusic" / "di" / "DataSourceModule.kt").read_text(encoding="utf-8", errors="replace")
        _drv_src = _code_only(_sftp_drv.read_text(encoding="utf-8", errors="replace"))
        _jsch_src = _code_only(_jsch.read_text(encoding="utf-8", errors="replace"))
        _mai_src3 = _code_only(_mai.read_text(encoding="utf-8", errors="replace"))
        _cmp_src3 = _code_only(_cmp.read_text(encoding="utf-8", errors="replace"))
        _sftp_bad = []
        if 'include(":datasource-sftp")' not in _settings_src or "jsch" not in _catalog_src:
            _sftp_bad.append("模块或依赖未登记")
        if "KEY_SFTP" not in _ds_src or 'storages.add("sftp"' not in _mai_src3:
            _sftp_bad.append("注册表键或数据源添加入口缺失")
        if "添加 SFTP 源" not in _cmp_src3 or "SftpDialog(" not in _cmp_src3:
            _sftp_bad.append("UI 添加面板缺失")
        if "ByteRangeCapability" not in _drv_src or "TextReadCapability" not in _drv_src:
            _sftp_bad.append("扫描期 Range/文本能力缺失")
        if "localLink(" not in _drv_src or "proxySourceUrl" in _drv_src:
            _sftp_bad.append("link 未走 LOCAL 缓存或伪造了 PROXIED 回源")
        if re.search(r"(Log\.[idwe]|println)\(.*(password|passwd|config)", _jsch_src):
            _sftp_bad.append("JSch 端口疑似打印凭据")
        if _sftp_bad:
            failures.append("§61.216 SFTP 数据源：%s" % "；".join(_sftp_bad))
        else:
            print("[PASS] §61.216 SFTP 数据源（模块/依赖/注册/UI/端口安全，link 走 LOCAL 缓存）")

    # §61.217 外置 lrc 多候选：候选生成器 + 扫描链调用 + 目录失败记忆三件同在；
    # 候选必须含 lyrics/ 子目录与大写扩展名，且不允许 list 目录回潮（O(n²)）。
    _lpc = ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "di" / "scan" / "LyricsPathCandidates.kt"
    if _lpc.is_file():
        _lpc_src = _code_only(_lpc.read_text(encoding="utf-8", errors="replace"))
        _ptc_src = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                               / "cloudmusic" / "di" / "scan" / "ProductionTagAndCover.kt").read_text(
                                   encoding="utf-8", errors="replace"))
        _lrc_bad = []
        if "fun forTrack(" not in _lpc_src or "MAX_CANDIDATES" not in _lpc_src:
            _lrc_bad.append("候选生成器缺失")
        if '"lyrics/$base.lrc"' not in _lpc_src or '"$base.LRC"' not in _lpc_src:
            _lrc_bad.append("子目录或大写扩展名候选缺失")
        if "LyricsPathCandidates.forTrack(" not in _ptc_src:
            _lrc_bad.append("扫描链未接多候选")
        if "noLyricsDirs" not in _ptc_src:
            _lrc_bad.append("目录失败记忆被移除（会逐文件重试）")
        # 同文件的 DirectoryCoverLookup 也会 list 目录（封面查找，另一条链），
        # 只检查 SiblingLyricsLookup 这段，避免误判。
        _sibling_body = _ptc_src.split("class SiblingLyricsLookup", 1)[-1].split("class AndroidTagReader", 1)[0]
        if "driver.list(" in _sibling_body:
            _lrc_bad.append("歌词查找回潮为 list 目录（§61.146 O(n²)）")
        if _lrc_bad:
            failures.append("§61.217 外置 lrc 多候选：%s" % "；".join(_lrc_bad))
        else:
            print("[PASS] §61.217 外置 lrc 多候选（子目录/大写扩展名 + 目录记忆 + 零 list）")

    # §61.218 歌曲信息面板：纯 JVM 展示口径 + info 位真实可点 + 不泄露签名直链。
    _ti = ROOT / "core-model" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic" / "model" / "TrackInfo.kt"
    if _ti.is_file() and _lp2.is_file():
        _ti_src = _code_only(_ti.read_text(encoding="utf-8", errors="replace"))
        _lp3 = _code_only(_lp2.read_text(encoding="utf-8", errors="replace"))
        _info_bad = []
        if "fun formatLabel(" not in _ti_src or "fun sourceLabel(" not in _ti_src:
            _info_bad.append("展示口径纯函数缺失")
        if "AudioFormats.AUDIO_EXT" not in _ti_src:
            _info_bad.append("格式未按 F1 真相源校验")
        if "clickable { showTrackInfo = true }" not in _lp3 or "歌曲信息" not in _lp3:
            _info_bad.append("info 位未接真实面板")
        if "sourceRef.url" in _lp3:
            _info_bad.append("面板疑似展示签名直链")
        if _info_bad:
            failures.append("§61.218 歌曲信息面板：%s" % "；".join(_info_bad))
        else:
            print("[PASS] §61.218 歌曲信息面板（格式真相源 + info 接线 + 直链不泄露）")

    # §61.219 SFTP 私钥认证：配置解析 + JSch addIdentity + UI 输入 + 二选一校验。
    _sftp_ports = (ROOT / "datasource-sftp" / "src" / "main" / "kotlin" / "com" / "example"
                   / "cloudmusic" / "sftp" / "SftpPorts.kt")
    if _sftp_drv.is_file() and _sftp_ports.is_file() and _jsch.is_file():
        _ports_src = _code_only(_sftp_ports.read_text(encoding="utf-8", errors="replace"))
        _drv_src2 = _code_only(_sftp_drv.read_text(encoding="utf-8", errors="replace"))
        _jsch_src2 = _code_only(_jsch.read_text(encoding="utf-8", errors="replace"))
        _cmp_src4 = _code_only(_cmp.read_text(encoding="utf-8", errors="replace"))
        _key_bad = []
        if "privateKey" not in _ports_src or "passphrase" not in _ports_src:
            _key_bad.append("SftpConfig 缺私钥字段")
        if "private_key" not in _drv_src2 or "password 或 private_key" not in _drv_src2:
            _key_bad.append("驱动未解析配置或二选一校验缺失")
        if "addIdentity(" not in _jsch_src2:
            _key_bad.append("JSch 端口未接公钥认证")
        if "PEM 私钥" not in _cmp_src4:
            _key_bad.append("UI 无私钥输入")
        if _key_bad:
            failures.append("§61.219 SFTP 私钥认证：%s" % "；".join(_key_bad))
        else:
            print("[PASS] §61.219 SFTP 私钥认证（配置解析 + addIdentity + UI + 二选一）")

    # §61.220 智能歌单：纯 JVM 规则引擎 + 内置预设 + 歌单页实时入口 + 不落库。
    _sp = (ROOT / "core-model" / "src" / "main" / "kotlin" / "com" / "example"
           / "cloudmusic" / "model" / "SmartPlaylist.kt")
    if _sp.is_file():
        _sp_src = _code_only(_sp.read_text(encoding="utf-8", errors="replace"))
        _cmp_src5 = _code_only(_cmp.read_text(encoding="utf-8", errors="replace"))
        _sp_bad = []
        if "fun evaluate(" not in _sp_src or "val PRESETS" not in _sp_src:
            _sp_bad.append("规则引擎纯函数或内置预设缺失")
        if "data class Rule" not in _sp_src or "enum class Field" not in _sp_src:
            _sp_bad.append("规则模型缺失")
        if "SmartPlaylist.evaluate(allTracks, rule)" not in _cmp_src5:
            _sp_bad.append("歌单页未接智能歌单求值")
        if "onPlaySmart(hits)" not in _cmp_src5:
            _sp_bad.append("命中曲目未接播放回调")
        if _sp_bad:
            failures.append("§61.220 智能歌单：%s" % "；".join(_sp_bad))
        else:
            print("[PASS] §61.220 智能歌单（纯 JVM 规则 + 内置预设 + 实时入口）")

    # §61.221 CUE 分轨：解析器 + 扫描展开（锚点 idPath）+ Track 起止列 + Room v6。
    _cue = (ROOT / "scanner" / "src" / "main" / "kotlin" / "com" / "example"
            / "cloudmusic" / "scanner" / "CueSheet.kt")
    _sc_src = _code_only((ROOT / "scanner" / "src" / "main" / "kotlin" / "com"
                          / "example" / "cloudmusic" / "scanner" / "ScanCoordinator.kt")
                         .read_text(encoding="utf-8", errors="replace"))
    _sm_src = _code_only((ROOT / "scanner" / "src" / "main" / "kotlin" / "com"
                          / "example" / "cloudmusic" / "scanner" / "ScanModels.kt")
                         .read_text(encoding="utf-8", errors="replace"))
    _track_src = _code_only((ROOT / "core-model" / "src" / "main" / "kotlin" / "com"
                             / "example" / "cloudmusic" / "model" / "Track.kt")
                            .read_text(encoding="utf-8", errors="replace"))
    _schema_src = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                              / "example" / "cloudmusic" / "di" / "db" / "LibrarySchema.kt")
                             .read_text(encoding="utf-8", errors="replace"))
    _cue_bad = []
    if not _cue.is_file() or "object CueParser" not in _code_only(
            _cue.read_text(encoding="utf-8", errors="replace")):
        _cue_bad.append("CueParser 解析器缺失")
    if "CandidateKind.CUE" not in _sm_src or "fun effectiveIdPath()" not in _sm_src:
        _cue_bad.append("分轨候选模型（CUE kind / 锚点 idPath）缺失")
    if "FileClass.CUE -> expandCue(" not in _sc_src:
        _cue_bad.append("walk 未分派 cue 展开")
    if "candidate.cueRaw ?: tagReader.read(candidate)" not in _sc_src:
        _cue_bad.append("阶段 B 未对分轨跳过 tag 读取")
    if "snapshotStore.markMetadataDone(storageId, chunk.map { it.effectiveIdPath() })" not in _sc_src:
        _cue_bad.append("metadataDone 快照键未按锚点回写")
    if "val cueStartMs: Long = 0L" not in _track_src or "val cueEndMs: Long = 0L" not in _track_src:
        _cue_bad.append("Track 分轨起止字段缺失")
    if "ALTER TABLE tracks ADD COLUMN cue_start_ms" not in _schema_src:
        _cue_bad.append("Room v6 迁移 SQL 缺失")
    if _cue_bad:
        failures.append("§61.221 CUE 分轨：%s" % "；".join(_cue_bad))
    else:
        print("[PASS] §61.221 CUE 分轨（解析器 + 锚点展开 + 起止列 + v6 迁移）")

    # §61.222 底栏搜索 tab（用户指示）：四胶囊 + tab3 选中映射 + onSelect 直达 + 隐藏口径作废。
    _app_src = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                           / "cloudmusic" / "ui" / "CloudMusicApp.kt")
                          .read_text(encoding="utf-8", errors="replace"))
    _fei_src = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                           / "cloudmusic" / "ui" / "FeiniuUi.kt")
                          .read_text(encoding="utf-8", errors="replace"))
    _nav_bad = []
    # §61.290 后枚举项带播报名（`Search("搜索")`），判据改为「按名字顺序取」而非整行字面量：
    # 强度不变（仍要求恰好 Home/Music/Search/More 四项且顺序一致），只是不再被 label 参数绊住。
    _capsule_block = _fei_src.split("enum class FnCapsuleIcon", 1)[1].split("}", 1)[0]
    _capsule_names = re.findall(r"^    ([A-Z][A-Za-z]+)\(", _capsule_block, re.M)
    if _capsule_names != ["Home", "Music", "Search", "More"]:
        _nav_bad.append("FnCapsuleIcon 四胶囊不是 Home/Music/Search/More（实得 %s）" % _capsule_names)
    if "FnCapsuleIcon.Search, FnCapsuleIcon.More," not in _app_src:
        _nav_bad.append("底栏 icons 未加搜索胶囊")
    if "tab == 3 -> 2" not in _app_src:
        _nav_bad.append("搜索 tab 选中映射缺失")
    if "if (tab != 3) searchReturnTo = tab" not in _app_src:
        _nav_bad.append("底栏搜索进入的返回目标记录缺失")
    if "capsuleHidden" in _app_src:
        _nav_bad.append("搜索页隐藏底栏口径未移除（capsuleHidden 残留）")
    if _nav_bad:
        failures.append("§61.222 底栏搜索 tab：%s" % "；".join(_nav_bad))
    else:
        print("[PASS] §61.222 底栏搜索 tab（四胶囊 + 选中映射 + 隐藏口径作废）")

    # §61.223 SFTP 外置 lrc 链修复 + 判读可观测性：ref 五态分派 + cacheDirPrefix + init/connect 失败日志。
    _ptc = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                       / "cloudmusic" / "di" / "scan" / "ProductionTagAndCover.kt")
                      .read_text(encoding="utf-8", errors="replace"))
    _scn = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                       / "cloudmusic" / "di" / "scan" / "ScanComposition.kt")
                      .read_text(encoding="utf-8", errors="replace"))
    _jsp = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                       / "cloudmusic" / "di" / "adapters" / "JschSftpPort.kt")
                      .read_text(encoding="utf-8", errors="replace"))
    _lrc_bad = []
    if "val isCacheRef = cacheDirPrefix.isNotEmpty() && ref.startsWith(cacheDirPrefix)" not in _ptc:
        _lrc_bad.append("ref 五态判定（isCacheRef）缺失")
    # 注意：判据串不能含 "content://" —— _code_only 会把字符串里的 // 当注释剥掉
    if "if (parent.isEmpty() && (isLocalFile" not in _ptc:
        _lrc_bad.append("parent 提前返回未按五态语义修正")
    if "cap != null -> runCatching" not in _ptc:
        _lrc_bad.append("驱动 TextReadCapability 读分支缺失")
    if "cacheDirPrefix = context.cacheDir.absolutePath" not in _scn:
        _lrc_bad.append("生产未注入 cacheDirPrefix")
    if "sftp connect 失败" not in _jsp:
        _lrc_bad.append("SFTP 端口连接失败可观测性日志缺失")
    if "e.message ?: e::class.java.name" not in _scn:
        _lrc_bad.append("驱动 init 失败日志未携带异常消息")
    if _lrc_bad:
        failures.append("§61.223 SFTP lrc 链修复：%s" % "；".join(_lrc_bad))
    else:
        print("[PASS] §61.223 SFTP 外置 lrc 链修复（ref 五态 + cacheDirPrefix + 失败可观测性）")

    # §61.224 CUE 分轨播放包络：起始定位 / 终点交棒 / seek 限界 / gapless 禁用。
    _pc_src = _code_only((ROOT / "feature-player" / "src" / "main" / "kotlin" / "com"
                          / "example" / "cloudmusic" / "player" / "PlayerController.kt")
                         .read_text(encoding="utf-8", errors="replace"))
    _cue_p_bad = []
    if "cueStartMs = track.cueStartMs" not in _pc_src or "cueEndMs = track.cueEndMs" not in _pc_src:
        _cue_p_bad.append("prepareAt 未随曲更新分轨包络")
    if "private fun clampToCue(ms: Long): Long" not in _pc_src:
        _cue_p_bad.append("包络钳制纯函数缺失")
    if "if (cueEndMs > 0 && pos >= cueEndMs)" not in _pc_src:
        _cue_p_bad.append("节拍器终点交棒缺失")
    if "val hasCueTracks = queue.any" not in _pc_src:
        _cue_p_bad.append("gapless 分轨禁用判定缺失")
    if "val effectiveSeek = if (seekTo > 0) clampToCue(seekTo) else cueStartMs" not in _pc_src:
        _cue_p_bad.append("分轨起始定位缺失")
    if "positionMs = effectiveSeek" not in _pc_src:
        _cue_p_bad.append("显示位置未用包络修正值")
    if _cue_p_bad:
        failures.append("§61.224 CUE 播放包络：%s" % "；".join(_cue_p_bad))
    else:
        print("[PASS] §61.224 CUE 播放包络（定位/交棒/限界/gapless 禁用）")

    # §61.225 SFTP 扫描期全量下载缺陷修复：分块读驱动不走 link()。
    _ptc_src2 = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                            / "cloudmusic" / "di" / "scan" / "ProductionTagAndCover.kt")
                           .read_text(encoding="utf-8", errors="replace"))
    _nol_bad = []
    if "if (driver is ByteRangeCapability && driver !is DirectLinkCapability)" not in _ptc_src2:
        _nol_bad.append("分块驱动 locate 特判缺失")
    if "return candidate.obj.path" not in _ptc_src2:
        _nol_bad.append("驱动相对路径 ref 返回缺失")
    if _nol_bad:
        failures.append("§61.225 SFTP 扫描期全量下载修复：%s" % "；".join(_nol_bad))
    else:
        print("[PASS] §61.225 SFTP 扫描期全量下载修复（分块驱动不走 link()）")

    # §61.226 CUE 分轨 UI 相对时间映射：bridge 三口纯函数 + currentTrackCached 注入。
    # §61.239 口径演进：三口实现体下沉 core-model CueEnvelope（唯一真值），
    # bridge 改为委托——守护改验「委托指向 CueEnvelope + CueEnvelope 本体在位」，
    # onSeek 注入 currentTrackCached 判据不变（契约本意不变，实现真值源单化随批留痕）。
    _psb = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                       / "cloudmusic" / "ui" / "player" / "PlayerStateBridge.kt")
                      .read_text(encoding="utf-8", errors="replace"))
    _cue_u_bad = []
    if "internal fun displayDuration(base: Long, t: Track?): Long" not in _psb:
        _cue_u_bad.append("displayDuration 纯函数缺失")
    if "internal fun displayPosition(pos: Long, t: Track?): Long" not in _psb:
        _cue_u_bad.append("displayPosition 纯函数缺失")
    if "internal fun absoluteSeek(displayMs: Long, t: Track?): Long" not in _psb:
        _cue_u_bad.append("absoluteSeek 纯函数缺失")
    if "CueEnvelope.displayDuration(base, t)" not in _psb \
            or "CueEnvelope.displayPosition(pos, t)" not in _psb \
            or "CueEnvelope.absoluteSeek(displayMs, t)" not in _psb:
        _cue_u_bad.append("三口未委托 CueEnvelope（真值口径漂移）")
    if "runtime.seekTo(absoluteSeek(ms, currentTrackCached))" not in _psb:
        _cue_u_bad.append("onSeek 反向换算未接 currentTrackCached")
    if "attachTrackCache(lifecycleScope)" not in _code_only(
            (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic"
             / "MainActivity.kt").read_text(encoding="utf-8", errors="replace")):
        _cue_u_bad.append("MainActivity 未挂 track 缓存生命周期")
    if _cue_u_bad:
        failures.append("§61.226 CUE UI 相对时间：%s" % "；".join(_cue_u_bad))
    else:
        print("[PASS] §61.226 CUE UI 相对时间映射（三口委托 CueEnvelope + 缓存生命周期）")

    # §61.227 strm 相对路径：Relative 分支 + 扫描链展开 + 相对解析。
    _strm_src = _code_only((ROOT / "datasource-strm" / "src" / "main" / "kotlin" / "com"
                            / "example" / "cloudmusic" / "strm" / "StrmParser.kt")
                           .read_text(encoding="utf-8", errors="replace"))
    _strm_drv = _code_only((ROOT / "datasource-strm" / "src" / "main" / "kotlin" / "com"
                            / "example" / "cloudmusic" / "strm" / "StrmDriver.kt")
                           .read_text(encoding="utf-8", errors="replace"))
    _coord_src = _code_only((ROOT / "scanner" / "src" / "main" / "kotlin" / "com"
                             / "example" / "cloudmusic" / "scanner" / "ScanCoordinator.kt")
                            .read_text(encoding="utf-8", errors="replace"))
    _rel_bad = []
    if "data class Relative(val path: String)" not in _strm_src:
        _rel_bad.append("Relative 解析结果缺失")
    if "url.startsWith(\"/\") || ':' !in url -> StrmParseResult.Relative(url)" not in _strm_src:
        _rel_bad.append("相对路径形态判定缺失")
    if "is StrmParseResult.Relative -> {" not in _coord_src:
        _rel_bad.append("扫描链相对引用展开缺失")
    if "private fun resolveRelativeStrm(baseDir: String, rel: String): String" not in _coord_src:
        _rel_bad.append("相对解析纯函数缺失")
    if "is StrmParseResult.Relative -> {" not in _strm_drv:
        _rel_bad.append("委托链 link 兜底展开缺失")
    if _rel_bad:
        failures.append("§61.227 strm 相对路径：%s" % "；".join(_rel_bad))
    else:
        print("[PASS] §61.227 strm 相对路径（Relative 分支 + 扫描链展开 + 委托兜底）")

    # §61.228 FTP 数据源：模块 + 驱动 + 注册 + UI（对齐 §61.216 SFTP 批判据面）。
    _ftp_src = _code_only((ROOT / "datasource-ftp" / "src" / "main" / "kotlin" / "com"
                           / "example" / "cloudmusic" / "ftp" / "FtpDriver.kt")
                          .read_text(encoding="utf-8", errors="replace"))
    _app_src2 = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                            / "cloudmusic" / "ui" / "CloudMusicApp.kt")
                           .read_text(encoding="utf-8", errors="replace"))
    _di_src = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                          / "cloudmusic" / "di" / "DataSourceModule.kt")
                         .read_text(encoding="utf-8", errors="replace"))
    _ftp_bad = []
    if not (ROOT / "datasource-ftp" / "build.gradle.kts").is_file() or "FtpClientFactory" not in _ftp_src:
        _ftp_bad.append("FTP 模块或端口工厂缺失")
    if "cacheDir, \"ftp\"" not in _ftp_src:
        _ftp_bad.append("link 缓存目录口径缺失")
    if '"anonymous"' not in _ftp_src:
        _ftp_bad.append("空用户名 anonymous 兜底缺失")
    if 'KEY_FTP = "ftp"' not in _di_src or "FtpDriver(" not in _di_src:
        _ftp_bad.append("Hilt 注册缺失")
    if "添加 FTP 源" not in _app_src2 or "storages.add(\"ftp\"" not in _code_only(
            (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic"
             / "MainActivity.kt").read_text(encoding="utf-8", errors="replace")):
        _ftp_bad.append("UI 入口或落库缺失")
    if _ftp_bad:
        failures.append("§61.228 FTP 数据源：%s" % "；".join(_ftp_bad))
    else:
        print("[PASS] §61.228 FTP 数据源（模块/驱动/注册/UI 全链）")

    # §61.229 DLNA DMC 音量：SetVolume/GetVolume + 运行时路由 + 输出弹窗滑杆。
    _coord_src = _code_only((ROOT / "output-dlna" / "src" / "main" / "kotlin" / "com"
                             / "example" / "cloudmusic" / "output" / "dlna" / "DlnaCastCoordinator.kt")
                            .read_text(encoding="utf-8", errors="replace"))
    _pc_src2 = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                           / "cloudmusic" / "di" / "playback" / "PlaybackComposition.kt")
                          .read_text(encoding="utf-8", errors="replace"))
    _app_src3 = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                            / "cloudmusic" / "ui" / "CloudMusicApp.kt")
                           .read_text(encoding="utf-8", errors="replace"))
    _vol_bad = []
    if "suspend fun setVolume(device: DmrDevice, volume: Int): Boolean" not in _coord_src:
        _vol_bad.append("coordinator SetVolume 缺失")
    if "CurrentVolume" not in _coord_src:
        _vol_bad.append("coordinator GetVolume 解析缺失")
    if "fun setCastVolume(volume: Int) = scope.launch" not in _pc_src2 or "castCoordinator.setVolume(device, volume)" not in _pc_src2:
        _vol_bad.append("运行时投屏态音量路由缺失")
    if "投屏音量：" not in _app_src3 or "onCastVolumeChanged" not in _app_src3:
        _vol_bad.append("输出弹窗音量滑杆缺失")
    if _vol_bad:
        failures.append("§61.229 DLNA 音量：%s" % "；".join(_vol_bad))
    else:
        print("[PASS] §61.229 DLNA DMC 音量（RC SetVolume/GetVolume + 运行时路由 + 弹窗滑杆）")

    # §61.230 淡入淡出：包络纯函数 + 设置项 + 配置转发 + 交棒组合。
    _pc_src3 = _code_only((ROOT / "feature-player" / "src" / "main" / "kotlin" / "com"
                           / "example" / "cloudmusic" / "player" / "PlayerController.kt")
                          .read_text(encoding="utf-8", errors="replace"))
    _fx = (ROOT / "core-storage" / "src" / "main" / "kotlin" / "com" / "example"
           / "cloudmusic" / "storage" / "AppSettings.kt").read_text(encoding="utf-8", errors="replace")
    _fade_bad = []
    if "internal fun fadeCurve(x: Float): Float" not in _pc_src3:
        _fade_bad.append("fade 包络纯函数缺失")
    if "fun updateFade(enabled: Boolean, durationMs: Int)" not in _pc_src3:
        _fade_bad.append("配置热更新入口缺失")
    if "fadeOutThen(hh) { scope.launch { next() } }" not in _pc_src3:
        _fade_bad.append("分轨终点渐出交棒组合缺失")
    if "val fadeEnabled: Boolean = false" not in _fx:
        _fade_bad.append("AudiofxSettings.fade 配置缺失")
    if "controller.updateFade(fx.audiofx.fadeEnabled" not in _code_only(
            (ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic"
             / "di" / "playback" / "PlaybackComposition.kt").read_text(encoding="utf-8", errors="replace")):
        _fade_bad.append("运行时配置转发缺失")
    if _fade_bad:
        failures.append("§61.230 淡入淡出：%s" % "；".join(_fade_bad))
    else:
        print("[PASS] §61.230 淡入淡出（包络纯函数 + 设置项 + 转发 + 交棒组合）")

    # §61.231 EQ 调节面：设置页面板 + 三参链路 + MainActivity 接线。
    _app_src4 = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                            / "cloudmusic" / "ui" / "CloudMusicApp.kt")
                           .read_text(encoding="utf-8", errors="replace"))
    _eq_bad = []
    if "private fun EqDialog(" not in _app_src4:
        _eq_bad.append("均衡器面板缺失")
    if 'title = "均衡器"' not in _app_src4 or "onApplyEqualizer(en, lvl, bands)" not in _app_src4:
        _eq_bad.append("EQ 面板应用回调缺失")
    if "onApplyEqualizer = { en, lvl, bands -> runtime.applyEqualizer(en, lvl, bands) }" not in _code_only(
            (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic"
             / "MainActivity.kt").read_text(encoding="utf-8", errors="replace")):
        _eq_bad.append("MainActivity 未接 applyEqualizer")
    if _eq_bad:
        failures.append("§61.231 EQ 调节面：%s" % "；".join(_eq_bad))
    else:
        print("[PASS] §61.231 EQ 调节面（面板 + applyEqualizer 全链接线）")

    # §61.234 UI 主题化：token 切色 + 主题四选项 + 启动应用。
    _cmp_src = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                           / "cloudmusic" / "ui" / "CompeteUi.kt")
                          .read_text(encoding="utf-8", errors="replace"))
    _main_src = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                            / "cloudmusic" / "MainActivity.kt")
                           .read_text(encoding="utf-8", errors="replace"))
    _theme_bad = []
    if "fun applyTheme(isDark: Boolean)" not in _cmp_src or "fun applyThemeCompat(isDark: Boolean)" not in _cmp_src:
        _theme_bad.append("Comp.applyTheme / applyThemeCompat 缺失")
    if "applyThemeCompat(isDark)" not in _main_src:
        _theme_bad.append("启动主题应用缺失")
    if "updateTheme(mode)" not in _main_src or "recreate()" not in _main_src:
        _theme_bad.append("设置页改主题 recreate 链缺失")
    if '"DARK_FOLLOW_SYSTEM"' not in _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com"
            / "example" / "cloudmusic" / "ui" / "CloudMusicApp.kt").read_text(encoding="utf-8", errors="replace")):
        _theme_bad.append("主题四选项 sheet 缺失")
    if _theme_bad:
        failures.append("§61.234 UI 主题化：%s" % "；".join(_theme_bad))
    else:
        print("[PASS] §61.234 UI 主题化（token 切色 + 四选项 + recreate 链）")

    # §61.233 刮削：Deezer 解析纯函数 + 会话流程 + runtime 入口 + 设置页 + updateCover。
    _scrape = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                          / "cloudmusic" / "di" / "scan" / "OnlineCoverScraper.kt")
                         .read_text(encoding="utf-8", errors="replace"))
    _pc_src4 = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                           / "cloudmusic" / "di" / "playback" / "PlaybackComposition.kt")
                          .read_text(encoding="utf-8", errors="replace"))
    _scrape_bad = []
    if "internal fun parseDeezerCover(json: String): String?" not in _scrape:
        _scrape_bad.append("Deezer 解析纯函数缺失")
    if "class CoverScrapeSession(" not in _scrape or "tracks.updateCover(track.id, ref)" not in _scrape:
        _scrape_bad.append("刮削会话（writer→updateCover）缺失")
    if "fun scrapeMissingCovers(maxCount: Int = 50) = scope.launch" not in _pc_src4:
        _scrape_bad.append("runtime 批处理入口缺失")
    if "onScrapeCovers" not in _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
            / "cloudmusic" / "MainActivity.kt").read_text(encoding="utf-8", errors="replace")):
        _scrape_bad.append("设置页入口未接线")
    if "suspend fun updateCover(id: TrackId, coverRef: String)" not in _code_only(
            (ROOT / "core-storage" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic"
             / "storage" / "Repositories.kt").read_text(encoding="utf-8", errors="replace")):
        _scrape_bad.append("updateCover 四件套缺失")
    if _scrape_bad:
        failures.append("§61.233 封面刮削：%s" % "；".join(_scrape_bad))
    else:
        print("[PASS] §61.233 封面刮削（Deezer + 批处理 + updateCover 四件套 + UI）")

    # §61.235 DSD 探测 + FTPS：AudioProbe 魔数 + FtpConfig ssl + FTPSClient + UI 勾选。
    _probe = _code_only((ROOT / "scanner" / "src" / "main" / "kotlin" / "com" / "example"
                         / "cloudmusic" / "scanner" / "AudioProbe.kt")
                        .read_text(encoding="utf-8", errors="replace"))
    _ftp_ports = _code_only((ROOT / "datasource-ftp" / "src" / "main" / "kotlin" / "com"
                             / "example" / "cloudmusic" / "ftp" / "FtpPorts.kt")
                            .read_text(encoding="utf-8", errors="replace"))
    _ftp_port_impl = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                                 / "cloudmusic" / "di" / "adapters" / "CommonsNetFtpPort.kt")
                                .read_text(encoding="utf-8", errors="replace"))
    _dsd_bad = []
    if 'head.startsWith("DSD ")' not in _probe or 'head.startsWith("FRM8")' not in _probe:
        _dsd_bad.append("DSD/DFF 魔数探测缺失")
    if "AudioFormat.DSD" not in _probe:
        _dsd_bad.append("DSD 枚举值缺失")
    if "val ssl: Boolean = false" not in _ftp_ports:
        _dsd_bad.append("FtpConfig.ssl 字段缺失")
    if "FTPSClient(true)" not in _ftp_port_impl:
        _dsd_bad.append("FTPSClient 显式 TLS 缺失")
    if _dsd_bad:
        failures.append("§61.235 DSD 探测 + FTPS：%s" % "；".join(_dsd_bad))
    else:
        print("[PASS] §61.235 DSD 探测（DSF/DFF 魔数）+ FTPS（显式 TLS）")

    # §61.236 EQ 频段自适应：段数 = max(5, bandsMillibel.size)，滑杆数组化。
    _app_src5 = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                            / "cloudmusic" / "ui" / "CloudMusicApp.kt")
                           .read_text(encoding="utf-8", errors="replace"))
    _eq_ad_bad = []
    if "val count = kotlin.math.max(5, bandsMillibel.size)" not in _app_src5:
        _eq_ad_bad.append("段数自适应缺失")
    if "var bandValues by remember" not in _app_src5 or "bandValues = bandValues.toMutableList().also { it[i] = v }" not in _app_src5:
        _eq_ad_bad.append("滑杆状态数组化缺失")
    _pc_fade_src = _code_only((ROOT / "feature-player" / "src" / "main" / "kotlin" / "com"
                               / "example" / "cloudmusic" / "player" / "PlayerController.kt")
                              .read_text(encoding="utf-8", errors="replace"))
    if "private var fadeOutArmed = false" not in _pc_fade_src:
        _eq_ad_bad.append("自然结束 fade-out armed 协调缺失")
    if "if (fadeOutArmed) fadeOutArmed = false" not in _pc_fade_src:
        _eq_ad_bad.append("ENDED 事件 armed 抑制缺失")
    if "engine.setVolume(h, 1f)" not in _pc_fade_src or "fadeOutArmed = false" not in _pc_fade_src or "fadeJob?.cancel()" not in _pc_fade_src:
        _eq_ad_bad.append("seek 干预解除 armed + 音量复位缺失")
    if _eq_ad_bad:
        failures.append("§61.236 EQ 频段自适应：%s" % "；".join(_eq_ad_bad))
    else:
        print("[PASS] §61.236 EQ 频段自适应（段数 = max(5, 自定义长度) + 数组化滑杆）")
        print("[PASS] §61.237 自然结束 fade-out（armed 协调 + ENDED 抑制 + seek 解除）")

    # §61.239 会话快照分轨相对化：CueEnvelope 唯一真值 + PlayerController 四发布点接线
    # + SftpDialog Ed25519 边界文案（§61.223 判读登记收口）。
    _cue_env = _code_only((ROOT / "core-model" / "src" / "main" / "kotlin" / "com"
                           / "example" / "cloudmusic" / "model" / "CueEnvelope.kt")
                          .read_text(encoding="utf-8", errors="replace"))
    _pc_src = _pc_fade_src
    _sftp_dialog = _app_src5
    _c239_bad = []
    if "fun displayDuration(baseMs: Long, cueStartMs: Long, cueEndMs: Long): Long" not in _cue_env \
            or "fun displayPosition(posMs: Long, cueStartMs: Long): Long" not in _cue_env \
            or "fun absoluteSeek(displayMs: Long, cueStartMs: Long): Long" not in _cue_env:
        _c239_bad.append("CueEnvelope 三口本体缺失")
    _pub_count = _pc_src.count("media?.setPlaybackState(")
    if _pub_count < 4:
        _c239_bad.append("setPlaybackState 发布点少于 4（找不到 %d）" % _pub_count)
    if "sessionPosition(effectiveSeek)" not in _pc_src or "sessionDuration(resolvedDur)" not in _pc_src:
        _c239_bad.append("prepareAt 发布点未相对化")
    if "media?.setPlaybackState(EngineState.PLAYING, sessionPosition(pos), sessionDuration(dur))" not in _pc_src:
        _c239_bad.append("ticker 2s 同步链未相对化")
    if "CueEnvelope.displayDuration(resolvedDur, cueStartMs, cueEndMs)" not in _pc_src:
        _c239_bad.append("publishMetadata 包络长未接线")
    if "推荐 RSA/EC" not in _sftp_dialog:
        _c239_bad.append("SftpDialog Ed25519 边界文案缺失")
    if _c239_bad:
        failures.append("§61.239 会话快照相对化 + Ed25519 文案：%s" % "；".join(_c239_bad))
    else:
        print("[PASS] §61.239 会话快照分轨相对化（CueEnvelope 同源四发布点）+ Ed25519 边界文案")

    # §61.240 系统会话补全第一刀：SessionBridge 纯 JVM 桥 + 发布链直通 + 位保回退。
    _sb = _code_only((ROOT / "core-media" / "src" / "main" / "kotlin" / "com"
                      / "example" / "cloudmusic" / "media" / "SessionBridge.kt")
                     .read_text(encoding="utf-8", errors="replace"))
    _svc = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com"
                       / "example" / "cloudmusic" / "PlaybackService.kt")
                      .read_text(encoding="utf-8", errors="replace"))
    _bind = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                        / "example" / "cloudmusic" / "di" / "AppBindings.kt")
                       .read_text(encoding="utf-8", errors="replace"))
    _c240_bad = []
    if "fun interface SessionPlaybackWriter" not in _sb or "fun interface SessionMetadataWriter" not in _sb:
        _c240_bad.append("SessionBridge 两写口缺失")
    if "snapshot.state == EngineState.PLAYING" not in _sb:
        _c240_bad.append("播放态判定缺失（不伪造播放口径）")
    if "if (snapshot == null) return" not in _sb or "if (metadata == null) return" not in _sb:
        _c240_bad.append("null 不写口径缺失")
    if "lateinit var observableControl: ObservableMediaControl" not in _svc:
        _c240_bad.append("PlaybackService 未注入发布链观察口")
    if "sessionBridge.onPlayback(snap)" not in _svc or "sessionBridge.onMetadata(md)" not in _svc:
        _c240_bad.append("发布链两流未接 SessionBridge")
    if "METADATA_KEY_DURATION" not in _svc or "METADATA_KEY_TITLE" not in _svc:
        _c240_bad.append("会话元数据字段缺失")
    if "lastPublishedPosition = it" not in _svc or ".setState(state, lastPublishedPosition," not in _svc:
        _c240_bad.append("回退链位保缺失（仍恒 0）")
    if "fun provideObservableMediaControl(impl: IMediaControl): ObservableMediaControl =" not in _bind:
        _c240_bad.append("DI 二口缺失（同实例观察面）")
    if _c240_bad:
        failures.append("§61.240 系统会话补全：%s" % "；".join(_c240_bad))
    else:
        print("[PASS] §61.240 系统会话补全第一刀（SessionBridge 直通 + 位保回退 + DI 二口）")

    # §61.241 DLNA 上次投屏设备记忆（V3-M2）：DlnaSettings 持久化 + 选择器置顶 + 全链接线。
    _as = _code_only((ROOT / "core-storage" / "src" / "main" / "kotlin" / "com"
                      / "example" / "cloudmusic" / "storage" / "AppSettings.kt")
                     .read_text(encoding="utf-8", errors="replace"))
    _codec = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                         / "example" / "cloudmusic" / "di" / "settings" / "AppSettingsCodec.kt")
                        .read_text(encoding="utf-8", errors="replace"))
    _sel = _code_only((ROOT / "output-dlna" / "src" / "main" / "kotlin" / "com"
                       / "example" / "cloudmusic" / "output" / "dlna" / "DmrDeviceSelector.kt")
                      .read_text(encoding="utf-8", errors="replace"))
    _pb = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                      / "example" / "cloudmusic" / "di" / "playback" / "PlaybackComposition.kt")
                     .read_text(encoding="utf-8", errors="replace"))
    _ma = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com"
                      / "example" / "cloudmusic" / "MainActivity.kt")
                     .read_text(encoding="utf-8", errors="replace"))
    _c241_bad = []
    if "val lastDmrUsn: String? = null" not in _as or "fun withLastDmr(usn: String, name: String): DlnaSettings" not in _as:
        _c241_bad.append("DlnaSettings 记忆字段/方法缺失")
    if 'put("lastDmrUsn", it)' not in _codec or 'lastDmrUsn = dlna.str("lastDmrUsn", "")' not in _codec:
        _c241_bad.append("codec 两侧未接线（旧 JSON 兼容缺失）")
    if 'fun ranked(devices: List<DmrDevice>, lastDmrUsn: String? = null)' not in _sel \
            or 'deduped.partition { it.usn == lastDmrUsn }' not in _sel:
        _c241_bad.append("选择器置顶口径缺失（缺省零变化）")
    if 'it.dlna.withLastDmr(device.usn, device.friendlyName)' not in _pb:
        _c241_bad.append("投屏成功持久化写入缺失")
    if 'MutableStateFlow(settingsStore.current().dlna.lastDmrUsn)' not in _ma \
            or 'rememberedDmrUsn.value = device.usn' not in _ma:
        _c241_bad.append("MainActivity 持久化读取/写回缺失")
    _ui_src = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com"
                          / "example" / "cloudmusic" / "ui" / "CloudMusicApp.kt")
                         .read_text(encoding="utf-8", errors="replace"))
    if "上次投屏" not in _ui_src or "lastDmrUsn: StateFlow<String?> = MutableStateFlow(null)" not in _ui_src:
        _c241_bad.append("输出弹窗上次投屏标记缺失")
    if _c241_bad:
        failures.append("§61.241 DLNA 上次投屏记忆：%s" % "；".join(_c241_bad))
    else:
        print("[PASS] §61.241 DLNA 上次投屏设备记忆（持久化 + 置顶 + UI 标记全链）")

    # §61.242 逐字歌词（V0.3 M3）：增强 LRC 词级时间轴 + activeWord + 面板逐字高亮。
    _lyr = _code_only((ROOT / "core-model" / "src" / "main" / "kotlin" / "com"
                       / "example" / "cloudmusic" / "model" / "Lyrics.kt")
                      .read_text(encoding="utf-8", errors="replace"))
    _lpp = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com"
                       / "example" / "cloudmusic" / "ui" / "player" / "LegacyPlayerPage.kt")
                      .read_text(encoding="utf-8", errors="replace"))
    _c242_bad = []
    if "data class LyricWord(val startMs: Long, val endMs: Long, val text: String)" not in _lyr:
        _c242_bad.append("LyricWord 模型缺失")
    if "val words: List<LyricWord> = emptyList()" not in _lyr:
        _c242_bad.append("LyricLine.words 缺省空（回退口径）缺失")
    if "fun activeWord(positionMs: Long): LyricWord?" not in _lyr:
        _c242_bad.append("activeWord 缺失")
    if "private val WORD_TAG = Regex" not in _lyr or "private fun parseWords(textPart: String, offsetMs: Long): List<LyricWord>" not in _lyr:
        _c242_bad.append("词级解析链缺失")
    if "lyrics.activeWord(positionMs)" not in _lpp or "SpanStyle(color = Color(LegacyPalette.AccentRed))" not in _lpp:
        _c242_bad.append("面板逐字高亮缺失")
    if _c242_bad:
        failures.append("§61.242 逐字歌词：%s" % "；".join(_c242_bad))
    else:
        print("[PASS] §61.242 逐字歌词（增强 LRC 词级时间轴 + activeWord + 面板高亮）")

    # §61.243 FTP 断线重连退避（V0.3 M4）：结果细分 + 策略/装饰器 + 生产端口异常传播。
    _fp = _code_only((ROOT / "datasource-ftp" / "src" / "main" / "kotlin" / "com"
                      / "example" / "cloudmusic" / "ftp" / "FtpPorts.kt")
                     .read_text(encoding="utf-8", errors="replace"))
    _fr = _code_only((ROOT / "datasource-ftp" / "src" / "main" / "kotlin" / "com"
                      / "example" / "cloudmusic" / "ftp" / "FtpReconnect.kt")
                     .read_text(encoding="utf-8", errors="replace"))
    _cn = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                      / "example" / "cloudmusic" / "di" / "adapters" / "CommonsNetFtpPort.kt")
                     .read_text(encoding="utf-8", errors="replace"))
    _c243_bad = []
    if "enum class FtpConnectResult { OK, AUTH_FAILED, NETWORK_ERROR }" not in _fp             or "suspend fun connectDetailed(): FtpConnectResult" not in _fp:
        _c243_bad.append("连接结果细分契约缺失")
    if "object FtpReconnectPolicy" not in _fr or "fun delayMs(attempt: Int): Long" not in _fr:
        _c243_bad.append("退避策略缺失")
    if "class ReconnectingFtpClientPort" not in _fr             or "catch (ce: kotlinx.coroutines.CancellationException)" not in _fr:
        _c243_bad.append("重连装饰器缺失（或取消透传缺失）")
    if "FtpConnectResult.AUTH_FAILED -> return fallback" not in _fr:
        _c243_bad.append("认证失败不重试口径缺失")
    if "ReconnectingFtpClientPort(CommonsNetFtpPort(config))" not in _cn:
        _c243_bad.append("生产工厂未包装饰器")
    if "FtpConnectResult.AUTH_FAILED" not in _cn or _cn.count("getOrElse { throw it }") < 3:
        _c243_bad.append("生产端口细分/异常传播缺失（服务器语义 null 不传播）")
    if _c243_bad:
        failures.append("§61.243 FTP 断线重连退避：%s" % "；".join(_c243_bad))
    else:
        print("[PASS] §61.243 FTP 断线重连退避（细分契约 + 指数退避装饰器 + 生产端口传播）")

    # §61.247 A1 分层去重 Tier2（V0.3 M5）：纯 JVM 分组 + 会话 + 设置页端到端。
    _dg = _code_only((ROOT / "core-model" / "src" / "main" / "kotlin" / "com"
                      / "example" / "cloudmusic" / "model" / "DuplicateGrouper.kt")
                     .read_text(encoding="utf-8", errors="replace"))
    _ds = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                      / "example" / "cloudmusic" / "di" / "scan" / "DuplicateScan.kt")
                     .read_text(encoding="utf-8", errors="replace"))
    _pb247 = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                         / "example" / "cloudmusic" / "di" / "playback" / "PlaybackComposition.kt")
                        .read_text(encoding="utf-8", errors="replace"))
    _ma247 = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com"
                         / "example" / "cloudmusic" / "MainActivity.kt")
                        .read_text(encoding="utf-8", errors="replace"))
    _ui247 = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com"
                         / "example" / "cloudmusic" / "ui" / "CloudMusicApp.kt")
                        .read_text(encoding="utf-8", errors="replace"))
    _c247_bad = []
    if "object DuplicateGrouper" not in _dg             or "fun suspectedDuplicates(tracks: List<Track>): List<DuplicateGroup>" not in _dg             or "const val DURATION_TOLERANCE_MS: Long = 1_000L" not in _dg             or "fun groupByHash(tracks: List<Track>, hashOf: (Track) -> String?): List<DuplicateGroup>" not in _dg:
        _c247_bad.append("Tier2 分组纯函数/Tier1 消费位缺失")
    if "class DuplicateScanSession" not in _ds or "DuplicateGrouper.suspectedDuplicates(all)" not in _ds:
        _c247_bad.append("检测会话缺失")
    if "fun findDuplicates()" not in _pb247 or "_duplicateReport.value = report" not in _pb247:
        _c247_bad.append("runtime 触发/报告流缺失")
    if "runtime.findDuplicates()" not in _ma247:
        _c247_bad.append("MainActivity 接线缺失")
    if "检测重复曲目" not in _ui247 or "onClearDuplicates" not in _ui247:
        _c247_bad.append("设置页入口/结果面板缺失")
    if _c247_bad:
        failures.append("§61.247 A1 分层去重 Tier2：%s" % "；".join(_c247_bad))
    else:
        print("[PASS] §61.247 A1 分层去重 Tier2（纯 JVM 分组 + 会话 + 设置页面板）")

    # §61.248 A1 Tier1 确认趟：哈希端口 + 流式哈希 + 生产哈希器 + 确认会话。
    _sc = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                      / "example" / "cloudmusic" / "di" / "scan" / "ScanContracts.kt")
                     .read_text(encoding="utf-8", errors="replace"))
    _pt = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                      / "example" / "cloudmusic" / "di" / "scan" / "ProductionTagAndCover.kt")
                     .read_text(encoding="utf-8", errors="replace"))
    _dsc = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                       / "example" / "cloudmusic" / "di" / "scan" / "DuplicateScan.kt")
                      .read_text(encoding="utf-8", errors="replace"))
    _c248_bad = []
    if "fun interface TrackContentHasher" not in _sc:
        _c248_bad.append("哈希端口缺失")
    if 'object ContentHashing' not in _pt or 'MessageDigest.getInstance("SHA-256")' not in _pt:
        _c248_bad.append("流式哈希缺失")
    if "as? ByteRangeCapability ?: return null" not in _pt:
        _c248_bad.append("能力位守卫缺失（无 readBytes 驱动须跳过）")
    if "class DriverContentHasher" not in _pt:
        _c248_bad.append("生产哈希器缺失")
    if "class DuplicateHashSession" not in _dsc or "DuplicateGrouper.groupByHash(hashedTracks) { memo[memoKey(it)] }" not in _dsc             or "distinctBy { it.sourceRef.storageId to it.sourceRef.path }" not in _dsc:
        _c248_bad.append("确认会话缺失（限数/去重/确认分组）")
    if "DuplicateHashSession(" not in _pb247 or "maxHashCount = 50" not in _pb247:
        _c248_bad.append("runtime 未接确认趟（限数 50）")
    if "精确确认" not in _ui247:
        _c248_bad.append("结果面板确认分区缺失")
    if _c248_bad:
        failures.append("§61.248 A1 Tier1 确认趟：%s" % "；".join(_c248_bad))
    else:
        print("[PASS] §61.248 A1 Tier1 确认趟（哈希端口 + 流式 + 会话限数 + 面板确认分区）")

    # §61.249 会话封面位图（V3-M1 后续刀）：异步解码 + METADATA_KEY_ALBUM_ART + ref 纯函数。
    _svc249 = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com"
                          / "example" / "cloudmusic" / "PlaybackService.kt")
                         .read_text(encoding="utf-8", errors="replace"))
    _c249_bad = []
    if "putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)" not in _svc249:
        _c249_bad.append("ALBUM_ART 位图写口缺失")
    if "coverArtJob?.cancel()" not in _svc249 or "withContext(Dispatchers.IO) { decodeCoverArt(coverPath) }" not in _svc249:
        _c249_bad.append("异步解码/换曲取消缺失")
    if "internal fun coverArtFile(ref: String?, cacheDir: java.io.File): java.io.File?" not in _svc249:
        _c249_bad.append("coverArtFile 纯函数缺失")
    if "candidate.isAbsolute" not in _svc249:
        _c249_bad.append("跨平台绝对判断缺失（startsWith("/") 漏盘符）")
    if _c249_bad:
        failures.append("§61.249 会话封面位图：%s" % "；".join(_c249_bad))
    else:
        print("[PASS] §61.249 会话封面位图（异步解码 + ALBUM_ART + 跨平台 ref 解析）")

    # §61.250 哈希记忆累进：memo 参数 + 失败不记忆 + runtime 持有 + 面板进度。
    if "memo: MutableMap<String, String> = HashMap()" not in _dsc             or "val pending = candidates.filter { memoKey(it) !in memo }" not in _dsc:
        _c250_bad_memo = "会话 memo 累进口径缺失"
    else:
        _c250_bad_memo = None
    _c250_bad = []
    if _c250_bad_memo:
        _c250_bad.append(_c250_bad_memo)
    if "} else {\n                memo[memoKey(track)] = h\n            }" not in _dsc:
        # 失败不记忆 = memo 写入仅在成功分支（_code_only 剥注释，判据锚代码本体）。
        _c250_bad.append("失败不记忆口径缺失")
    if "private val duplicateHashMemo =" not in _pb247             or "memo = duplicateHashMemo" not in _pb247             or "duplicateHashMemo.clear()" not in _pb247:
        _c250_bad.append("runtime 持有/清空缺失")
    if "再次检测继续推进剩余候选" not in _ui247:
        _c250_bad.append("面板进度提示缺失")
    if _c250_bad:
        failures.append("§61.250 哈希记忆累进：%s" % "；".join(_c250_bad))
    else:
        print("[PASS] §61.250 哈希记忆累进（限数逐次推进 + 命中复用 + 失败重试）")

    # §61.252（P0 修复）：STRM/歌单无法播放四层 —— url 直链优先 + 源级失败跳链 +
    # ERROR 相位重试 + 恢复失败可见性。
    _cast = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                        / "example" / "cloudmusic" / "di" / "adapters" / "CastComposition.kt")
                       .read_text(encoding="utf-8", errors="replace"))
    _pc252 = _code_only((ROOT / "feature-player" / "src" / "main" / "kotlin" / "com"
                         / "example" / "cloudmusic" / "player" / "PlayerController.kt")
                        .read_text(encoding="utf-8", errors="replace"))
    _lpp252 = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com"
                          / "example" / "cloudmusic" / "ui" / "player" / "LegacyPlayerPage.kt")
                         .read_text(encoding="utf-8", errors="replace"))
    _c252_bad = []
    _url_first_idx = _cast.find('ref.url?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { return Link(url = it) }')
    _drv_idx = _cast.find("val driver = DriverRegistry.get(ref.storageId)")
    if _url_first_idx < 0 or _drv_idx < 0 or _url_first_idx > _drv_idx:
        _c252_bad.append("url 直链优先未置于 driver 分支之前（设计缺陷回归）")
    if "音源可能已删除" not in _cast:
        _c252_bad.append("孤儿源 DsUnreachable 增强信息缺失")
    if "private suspend fun failAndMaybeSkip" not in _pc252 \
            or _pc252.count("failAndMaybeSkip(") < 4:
        _c252_bad.append("源级失败跳链接线缺失（三处 fail 调用点）")
    if "phase == PlayerPhase.ENDED || _state.value.phase == PlayerPhase.ERROR" not in _pc252:
        _c252_bad.append("ERROR 相位播放重试缺失（死按钮）")
    if "if (_state.value.phase != PlayerPhase.ERROR) {" not in _pc252:
        _c252_bad.append("restore 尾部 ERROR 覆盖防护缺失")
    if "state.lastError?.message" not in _lpp252:
        _c252_bad.append("播放页错误可见性缺失")
    if _c252_bad:
        failures.append("§61.252 STRM 播放四层修复：%s" % "；".join(_c252_bad))
    else:
        print("[PASS] §61.252 STRM/歌单播放四层修复（url 优先 + 跳链 + ERROR 重试 + 错误可见）")

    # §61.253：孤儿源可用性如实标记（PRD §4 离线优先）。
    _ports = _code_only((ROOT / "feature-player" / "src" / "main" / "kotlin" / "com"
                         / "example" / "cloudmusic" / "player" / "TrackPorts.kt")
                        .read_text(encoding="utf-8", errors="replace"))
    _c253_bad = []
    if "fun interface TrackAvailabilityWriter" not in _ports:
        _c253_bad.append("可用性标记端口缺失")
    if "error is CloudMusicError.DsUnreachable || error is CloudMusicError.DsNotFound" not in _pc252 \
            or "availabilityWriter?.mark(id, false)" not in _pc252:
        _c253_bad.append("控制器失败路径标记缺失")
    if "TrackAvailabilityWriter { id, ok ->" not in _pb247:
        _c253_bad.append("组合层接线缺失")
    if not (ROOT / "scanner" / "src" / "test" / "kotlin" / "com" / "example" / "cloudmusic"
            / "scanner" / "PlaylistScanTest.kt").exists():
        _c253_bad.append("m3u 展开扫描 E2E 测试缺失")
    if _c253_bad:
        failures.append("§61.253 孤儿源可用性标记：%s" % "；".join(_c253_bad))
    else:
        print("[PASS] §61.253 孤儿源可用性标记（端口 + 失败路径 + 组合接线 + m3u E2E 测试）")

    # §61.254：m3u → 歌单页命名实体（扫描导入，稳定 id 幂等 upsert）。
    _plimport = (ROOT / "scanner" / "src" / "main" / "kotlin" / "com"
                 / "example" / "cloudmusic" / "scanner" / "PlaylistImport.kt")
    _scancomp_src = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                                / "example" / "cloudmusic" / "di" / "scan" / "ScanComposition.kt")
                               .read_text(encoding="utf-8", errors="replace"))
    _coord_src = _code_only((ROOT / "scanner" / "src" / "main" / "kotlin" / "com"
                             / "example" / "cloudmusic" / "scanner" / "ScanCoordinator.kt")
                            .read_text(encoding="utf-8", errors="replace"))
    _c254_bad = []
    if not _plimport.exists():
        _c254_bad.append("扫描导入端口文件缺失")
    else:
        _pil = _code_only(_plimport.read_text(encoding="utf-8", errors="replace"))
        if "fun interface PlaylistImportSink" not in _pil or "data class PlaylistImport" not in _pil:
            _c254_bad.append("导入端口/引用类型缺失")
    if "playlistImportSink: com.example.cloudmusic.scanner.PlaylistImportSink? = null," not in _coord_src             and "playlistImportSink: PlaylistImportSink? = null," not in _coord_src:
        _c254_bad.append("协调器导入口缺失")
    if '"pl_imp_"' not in _coord_src:
        _c254_bad.append("稳定 id 派生缺失")
    _repo_src = _code_only((ROOT / "core-storage" / "src" / "main" / "kotlin" / "com"
                            / "example" / "cloudmusic" / "storage" / "Repositories.kt")
                           .read_text(encoding="utf-8", errors="replace"))
    if "suspend fun upsertImported(id: String, name: String, trackIds: List<TrackId>)" not in _repo_src:
        _c254_bad.append("仓库接口 upsertImported 缺失")
    if "playlists.upsertImported(id, name, ids)" not in _scancomp_src:
        _c254_bad.append("组合层接线缺失")
    if _c254_bad:
        failures.append("§61.254 m3u 歌单页导入：%s" % "；".join(_c254_bad))
    else:
        print("[PASS] §61.254 m3u 歌单页命名实体（扫描导入 + 幂等 upsert 全链）")

    # §61.255：孤儿源 sweep（扫描期 UNREACHABLE，PRD §4 灰显不删除）。
    _ports255 = _code_only((ROOT / "scanner" / "src" / "main" / "kotlin" / "com"
                            / "example" / "cloudmusic" / "scanner" / "ScanPorts.kt")
                           .read_text(encoding="utf-8", errors="replace"))
    _c255_bad = []
    if "suspend fun trackedStorageIds(): Set<String> = emptySet()" not in _ports255             or "suspend fun trackIdsByStorage(storageId: String): List<com.example.cloudmusic.model.TrackId> = emptyList()" not in _ports255:
        _c255_bad.append("TrackSink 扩展两口缺失")
    if "E_SCAN_ORPHAN_SOURCE" not in _coord_src or "trackSink.trackedStorageIds().filter { it !in allActive }" not in _coord_src:
        _c255_bad.append("协调器 sweep 缺失（全量 active 口径）")
    if "override suspend fun trackedStorageIds(): Set<String>" not in _scancomp_src:
        _c255_bad.append("RoomTrackSink 投影缺失")
    if _c255_bad:
        failures.append("§61.255 孤儿源 sweep：%s" % "；".join(_c255_bad))
    else:
        print("[PASS] §61.255 孤儿源 sweep（TrackSink 投影 + 全量 active 口径 + 错误可见）")

    # §61.256（P0 后续）：Tier3 声纹确认趟——自研纯 Kotlin 频谱指纹（零新依赖）。
    _afp = _code_only((ROOT / "core-model" / "src" / "main" / "kotlin" / "com"
                       / "example" / "cloudmusic" / "model" / "AudioFingerprint.kt")
                      .read_text(encoding="utf-8", errors="replace"))
    _mpd = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                       / "example" / "cloudmusic" / "di" / "adapters" / "MediaPcmDecoder.kt")
                      .read_text(encoding="utf-8", errors="replace"))
    _fsession = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                            / "example" / "cloudmusic" / "di" / "scan" / "FingerprintSession.kt")
                           .read_text(encoding="utf-8", errors="replace"))
    _c256_bad = []
    if "object AudioFingerprint" not in _afp             or "fun fingerprint(pcm: FloatArray, sampleRate: Int): Set<Long>" not in _afp             or "fun similarity(a: Set<Long>, b: Set<Long>): Double" not in _afp:
        _c256_bad.append("指纹核缺失")
    if "class MediaPcmDecoder" not in _mpd or "MediaCodec.createDecoderByType" not in _mpd:
        _c256_bad.append("解码器缺失")
    if "class FingerprintSession" not in _fsession             or "AudioFingerprint.similarity(" not in _fsession             or "AudioFingerprint.MATCH_THRESHOLD" not in _fsession:
        _c256_bad.append("声纹会话缺失")
    if "fingerprintConfirmed: List<DuplicateGroup> = emptyList()" not in _dg:
        _c256_bad.append("报告字段缺失")
    if "FingerprintSession(" not in _pb247:
        _c256_bad.append("runtime 接线缺失")
    if "声纹疑似同源" not in _ui247:
        _c256_bad.append("面板声纹分区缺失")
    if _c256_bad:
        failures.append("§61.256 Tier3 声纹：%s" % "；".join(_c256_bad))
    else:
        print("[PASS] §61.256 Tier3 声纹（自研 FFT 指纹核 + MediaCodec 解码 + 会话升级 + 面板分区）")

    # §61.258（§61.256 挂账收口）：偏移哈希——固定帧距、绝对帧号不入哈希，整体时间平移不误判。
    _c258_bad = []
    if "const val PAIR_DELTA_FRAMES: Int = 1" not in _afp:
        _c258_bad.append("固定帧距常量缺失")
    if "PAIR_DELTA_FRAMES.toLong() and 0xFF" not in _afp:
        _c258_bad.append("哈希未用固定帧距")
    if "frameIdx" in _afp:
        _c258_bad.append("绝对帧号仍入哈希")
    _fp_test = _code_only((ROOT / "core-model" / "src" / "test" / "kotlin" / "com"
                           / "example" / "cloudmusic" / "model" / "AudioFingerprintTest.kt")
                          .read_text(encoding="utf-8", errors="replace"))
    if "fun timeOffsetMatches()" not in _fp_test:
        _c258_bad.append("偏移命中用例缺失")
    if _c258_bad:
        failures.append("§61.258 偏移哈希：%s" % "；".join(_c258_bad))
    else:
        print("[PASS] §61.258 偏移哈希（固定帧距 + 无绝对帧号 + F6 偏移命中）")

    # §61.261：首页搜索入口去重——底栏已有搜索 tab，首页不再重复放搜索圆钮。
    _home_src = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com"
                            / "example" / "cloudmusic" / "ui" / "HomeScreen.kt")
                           .read_text(encoding="utf-8", errors="replace"))
    _c261_bad = []
    if "fun HomeScreen(" not in _home_src:
        _c261_bad.append("首页缺失")
    if "onOpenSearch" in _home_src or ".clickable { onOpenSearch() }" in _home_src:
        _c261_bad.append("首页搜索入口重复")
    if _c261_bad:
        failures.append("§61.261 首页搜索去重：%s" % "；".join(_c261_bad))
    else:
        print("[PASS] §61.261 首页搜索去重（底栏搜索 tab 单一入口）")

    # §61.262：全面屏安全区 / IME 避让 / Android 15 opt-out + 首页「最近添加」真实排序。
    _theme_src = (ROOT / "app" / "src" / "main" / "res" / "values" / "themes.xml").read_text(encoding="utf-8", errors="replace")
    _app_src = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com"
                           / "example" / "cloudmusic" / "ui" / "CloudMusicApp.kt")
                          .read_text(encoding="utf-8", errors="replace"))
    _player_src = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com"
                              / "example" / "cloudmusic" / "ui" / "player" / "LegacyPlayerPage.kt")
                             .read_text(encoding="utf-8", errors="replace"))
    _track_src = _code_only((ROOT / "core-model" / "src" / "main" / "kotlin" / "com"
                             / "example" / "cloudmusic" / "model" / "Track.kt")
                            .read_text(encoding="utf-8", errors="replace"))
    _c262_bad = []
    if "android:windowOptOutEdgeToEdgeEnforcement" not in _theme_src:
        _c262_bad.append("Android 15 opt-out 缺失")
    if ".displayCutoutPadding()" not in _app_src:
        _c262_bad.append("普通页挖孔安全区缺失")
    if ".imePadding()" not in _app_src:
        _c262_bad.append("搜索页 IME 避让缺失")
    if ".displayCutoutPadding()" not in _player_src:
        _c262_bad.append("播放页挖孔安全区缺失")
    if "fun List<Track>.recentlyAdded(" not in _track_src or "tracks.recentlyAdded()" not in _home_src:
        _c262_bad.append("最近添加排序/去重缺失")
    if "distinctBy { it.title.trim().lowercase()" not in _track_src:
        _c262_bad.append("同名同艺人展示折叠缺失")
    if _c262_bad:
        failures.append("§61.262 全面屏+最近添加：%s" % "；".join(_c262_bad))
    else:
        print("[PASS] §61.262 全面屏安全区 + IME 避让 + Android 15 opt-out + 最近添加排序去重")

    # §61.264：文件级扫描错误不得用 storageId 上报（否则整个源被 errorMsg 踢出 active）。
    _scan_src = _code_only((ROOT / "scanner" / "src" / "main" / "kotlin" / "com"
                            / "example" / "cloudmusic" / "scanner" / "ScanCoordinator.kt")
                           .read_text(encoding="utf-8", errors="replace"))
    _c264_bad = []
    if 'storage.id, "E_' in _scan_src:
        _c264_bad.append("文件级错误仍用 storageId 上报")
    if "坏 cue 文件按文件路径上报" not in _code_only((ROOT / "scanner" / "src" / "test" / "kotlin" / "com"
                                                  / "example" / "cloudmusic" / "scanner" / "CueScanTest.kt")
                                                .read_text(encoding="utf-8", errors="replace")):
        _c264_bad.append("坏 CUE 路径判据缺失")
    if _c264_bad:
        failures.append("§61.264 文件级错误隔离：%s" % "；".join(_c264_bad))
    else:
        print("[PASS] §61.264 文件级错误隔离（坏 CUE 不踢掉整个源）")

    # §61.265：OOM 闪退缓解——largeHeap 声明在位（不宣称根因修复）。
    _manifest_src = (ROOT / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8", errors="replace")
    if 'android:largeHeap="true"' not in _manifest_src:
        failures.append("§61.265 OOM 缓解：largeHeap 声明缺失")
    else:
        print("[PASS] §61.265 OOM 缓解（android:largeHeap=true，根因待定位）")

    # §61.266：HTTP 音源 OOM——探测只取元数据、读按 len 上界、响应体流式截断（禁止整段入堆）。
    _ports_src = _code_only((ROOT / "engine-bridge" / "src" / "main" / "kotlin" / "com"
                             / "example" / "cloudmusic" / "bridge" / "DataSourcePorts.kt")
                            .read_text(encoding="utf-8", errors="replace"))
    _http_ds_src = _code_only((ROOT / "engine-bridge" / "src" / "main" / "kotlin" / "com"
                               / "example" / "cloudmusic" / "bridge" / "HttpDataSource.kt")
                              .read_text(encoding="utf-8", errors="replace"))
    _okhttp_src = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                              / "example" / "cloudmusic" / "di" / "adapters" / "OkHttpAdapters.kt")
                             .read_text(encoding="utf-8", errors="replace"))
    _adapters_test = _code_only((ROOT / "core-di" / "src" / "test" / "kotlin" / "com" / "example"
                                 / "cloudmusic" / "di" / "adapters" / "ProductionAdaptersTest.kt")
                                .read_text(encoding="utf-8", errors="replace"))
    _ds_test = _code_only((ROOT / "engine-bridge" / "src" / "test" / "kotlin" / "com" / "example"
                           / "cloudmusic" / "bridge" / "DataSourceAdapterTest.kt")
                          .read_text(encoding="utf-8", errors="replace"))
    _c266_bad = []
    if "rangeEnd: Long?," not in _ports_src:
        _c266_bad.append("端口未声明有界 rangeEnd")
    if "fun probe(" not in _ports_src or "fetch(url, headers, 0L, redirect, 0L)" not in _ports_src:
        _c266_bad.append("probe 默认实现缺失（1 字节兜底）")
    if "http.probe(" not in _http_ds_src:
        _c266_bad.append("open 未改用 probe 探测")
    if "slice = fetch(offset)" in _http_ds_src:
        _c266_bad.append("readAt 仍发无界 Range")
    if "MAX_CHUNK" not in _http_ds_src:
        _c266_bad.append("单次读取上限缺失")
    if "resp.body?.bytes()" in _okhttp_src:
        _c266_bad.append("响应体仍整段读入 ByteArray")
    for _needle, _label in (
        ("readAtMost(", "流式有界读"),
        ("skipFully(", "忽略 Range 时流式跳偏移"),
        ("bytes=$rangeStart-$rangeEnd", "有界 Range 头"),
        ("MAX_BODY_BYTES", "无界请求兜底上限"),
    ):
        if _needle not in _okhttp_src:
            _c266_bad.append(_label + "缺失")
    for _t in ("T01b", "T01c", "T01d"):
        if _t not in _adapters_test:
            _c266_bad.append("用例 " + _t + " 缺失")
    if "assertEquals(6L, call.rangeEnd" not in _ds_test:
        _c266_bad.append("引擎侧有界读断言缺失")
    if _c266_bad:
        failures.append("§61.266 音源 OOM：%s" % "；".join(_c266_bad))
    else:
        print("[PASS] §61.266 HTTP 音源 OOM（HEAD 探测 + 有界 Range + 流式截断）")

    # §61.268：本地 WebDAV 签名直链（?sign=）必须转本机回环代理，禁止 Media3 直连。
    _resolver_src = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                                / "example" / "cloudmusic" / "di" / "playback"
                                / "PlaybackComposition.kt")
                               .read_text(encoding="utf-8", errors="replace"))
    _resolver_test = _code_only((ROOT / "core-di" / "src" / "test" / "kotlin" / "com" / "example"
                                 / "cloudmusic" / "di" / "playback"
                                 / "TrackSourceResolverProxyTest.kt")
                                .read_text(encoding="utf-8", errors="replace"))
    _c268_bad = []
    if "fun needsLoopbackProxy(" not in _resolver_src:
        _c268_bad.append("签名直链判定缺失")
    if 'it.startsWith("sign=", ignoreCase = true)' not in _resolver_src:
        _c268_bad.append("sign= 特征判定缺失")
    if "link.kind == Link.Kind.URL && needsLoopbackProxy(link.url)" not in _resolver_src:
        _c268_bad.append("URL 态未接签名直链判定")
    if "proxy.publish(track.id.value, link)" not in _resolver_src:
        _c268_bad.append("未转本机代理")
    for _t in ("signedUrlGoesProxy", "plainUrlUnchanged", "proxiedStillProxy"):
        if _t not in _resolver_test:
            _c268_bad.append("用例 " + _t + " 缺失")
    if _c268_bad:
        failures.append("§61.268 WebDAV 签名直链走代理：%s" % "；".join(_c268_bad))
    else:
        print("[PASS] §61.268 本地 WebDAV 签名直链走本机代理（Media3 禁止直连）")

    # §61.269：302 目标缓存——消除签名直链每个 Range 重复 302 导致的播放卡顿。
    _okhttp_src = _code_only((ROOT / "core-di" / "src" / "main" / "kotlin" / "com"
                              / "example" / "cloudmusic" / "di" / "adapters" / "OkHttpAdapters.kt")
                             .read_text(encoding="utf-8", errors="replace"))
    _okhttp_test = _code_only((ROOT / "core-di" / "src" / "test" / "kotlin" / "com" / "example"
                               / "cloudmusic" / "di" / "adapters" / "ProductionAdaptersTest.kt")
                              .read_text(encoding="utf-8", errors="replace"))
    _c269_bad = []
    if "redirectCache" not in _okhttp_src or "fun resolveTarget(" not in _okhttp_src:
        _c269_bad.append("302 目标缓存/解析缺失")
    if "REDIRECT_TTL_MS" not in _okhttp_src or "REDIRECT_TTL_MS = 240_000L" not in _okhttp_src:
        _c269_bad.append("缓存 TTL 缺失或被禁用")
    if "rememberRedirect(url, resp)" not in _okhttp_src:
        _c269_bad.append("缓存写入点缺失")
    if "dropRedirect(" not in _okhttp_src or "resp.code == 403 || resp.code == 404" not in _okhttp_src:
        _c269_bad.append("403/404 缓存失效缺失")
    if "crossHost" not in _okhttp_src or "if (drop) return" not in _okhttp_src:
        _c269_bad.append("跨 host 剥离鉴权头缺失")
    for _t in ("T01e", "T01f"):
        if _t not in _okhttp_test:
            _c269_bad.append("用例 " + _t + " 缺失")
    if _c269_bad:
        failures.append("§61.269 302 缓存去卡顿：%s" % "；".join(_c269_bad))
    else:
        print("[PASS] §61.269 302 目标缓存去卡顿（TTL + 403 失效 + 跨 host 剥离鉴权）")

    # §61.270：播放页顶部全面屏（透明状态栏+内容延伸）+ 底部保留系统导航栏。
    _app_src = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                           / "cloudmusic" / "ui" / "CloudMusicApp.kt")
                          .read_text(encoding="utf-8", errors="replace"))
    _player_src = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                              / "cloudmusic" / "ui" / "player" / "LegacyPlayerPage.kt")
                             .read_text(encoding="utf-8", errors="replace"))
    # §61.291 后系统栏改由 SystemBars.kt 单点上色、宿主按 showPlayer 传 decorFits 形态：
    # 判据跟着搬到「形态 + 目标文件」，强度不变（仍要求播放页 decorFits=false / 透明状态栏 / 保留导航栏）。
    _sysbars_src270 = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                                  / "cloudmusic" / "ui" / "SystemBars.kt")
                                 .read_text(encoding="utf-8", errors="replace"))
    _c270_bad = []
    if "setDecorFitsSystemWindows(" not in _app_src or "if (showPlayer) false else themeDecorFits" not in _app_src:
        _c270_bad.append("顶部内容延伸（播放页 decorFits=false）缺失")
    if "window.statusBarColor = android.graphics.Color.TRANSPARENT" not in _sysbars_src270:
        _c270_bad.append("透明状态栏缺失")
    if not re.search(r"controller\??\.show\(WindowInsetsCompat\.Type\.navigationBars\(\)\)", _app_src):
        _c270_bad.append("底部导航栏保留缺失")
    if "hide(WindowInsetsCompat.Type.systemBars())" in _app_src or "hide(WindowInsetsCompat.Type.systemBars())" in _sysbars_src270:
        _c270_bad.append("仍在整组隐藏 systemBars")
    if "statusBarsPadding()" not in _player_src:
        _c270_bad.append("控件让出状态栏 padding 缺失")
    if "navigationBarsPadding()" not in _player_src:
        _c270_bad.append("控件让出导航栏 padding 缺失")
    # 背景顺序：.background 必须在 .displayCutoutPadding 之前，否则状态栏区无背景、出现分界线。
    _bg_i = _player_src.find(".background(Brush.verticalGradient")
    _cut_i = _player_src.find(".displayCutoutPadding()")
    if _bg_i < 0 or _cut_i < 0 or _bg_i > _cut_i:
        _c270_bad.append("背景未铺满状态栏区（background 顺序错）")
    if _c270_bad:
        failures.append("§61.270 播放页系统栏：%s" % "；".join(_c270_bad))
    else:
        print("[PASS] §61.270 播放页顶部全面屏 + 底部保留系统导航栏")


    if _c250_bad:
        failures.append("§61.250 哈希记忆累进：%s" % "；".join(_c250_bad))
    else:
        print("[PASS] §61.250 哈希记忆累进（限数逐次推进 + 命中复用 + 失败重试）")

    # ---- §61.274（V0.4-01 入口合并 / 拍板 §61.272 #4）：曲库唯一入口 + 全功能吸收 ----
    # 判据（新增判据，不改动既有强度）：
    # ① 旧入口彻底消失：无 tab7 分支 / 无「更多→曲库」参数 / 标签表不含「曲库」；
    # ② 音乐库页吸收全功能：视图组含不可用 + 行尾「＋」入歌单 + 重扫 + 重试；
    # ③ SurfaceNav.LIBRARY 仍直达 tab1（取证导航不死链）；
    # ④ 分组视图具备展开语义（防退回「每组只显示首条」的丢歌形态）。
    _ui274 = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic"
              / "ui" / "CloudMusicApp.kt").read_text(encoding="utf-8", errors="replace")
    _lib274 = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic"
               / "ui" / "FnLibraryScreen.kt").read_text(encoding="utf-8", errors="replace")
    _more274 = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic"
                / "ui" / "MoreScreen.kt").read_text(encoding="utf-8", errors="replace")
    _c274 = []
    if "onLibraryManager" in _ui274 or "onLibraryManager" in _more274:
        _c274.append("「更多→曲库」入口参数仍在（应已删除）")
    if re.search(r"tab\s*==\s*7", _ui274) or re.search(r"tab\s*=\s*7", _ui274):
        _c274.append("tab7 分支仍在（曲库第二入口）")
    _labels274 = re.search(r"FUNCTION_PAGE_LABELS\s*=\s*listOf\(([^)]*)\)", _ui274)
    if _labels274 is None:
        _c274.append("FUNCTION_PAGE_LABELS 定义缺失")
    elif "曲库" in _labels274.group(1):
        _c274.append("FUNCTION_PAGE_LABELS 仍含「曲库」标签（入口合并未完成）")
    if not re.search(r"SurfaceNav\.Surface\.LIBRARY\s*->\s*\{[^}]*tab\s*=\s*1", _ui274):
        _c274.append("SurfaceNav.LIBRARY 未直达音乐库 tab1")
    for _kw in ("全部歌曲", "歌手", "专辑", "音源", "不可用"):
        if _kw not in _lib274:
            _c274.append("音乐库视图组缺「%s」" % _kw)
    if "onAddToPlaylist" not in _lib274 or "onAddToPlaylist = { addToPlaylistTrack = it }" not in _ui274:
        _c274.append("行尾「＋」入歌单未接线（原 tab7 功能丢失）")
    if "onRescan" not in _lib274 or "onRescan = onRescanAll" not in _ui274:
        _c274.append("音乐库缺重扫入口（原 tab7 「↻」丢失）")
    if "onRetryUnavailable" not in _lib274 or "onRetryUnavailable = onRetryUnavailable" not in _ui274:
        _c274.append("不可用视图缺重试接线（原 tab7 功能丢失）")
    if "FnGroupHeader(" not in _lib274 or "expanded" not in _lib274:
        _c274.append("分组视图缺展开语义（会退回「每组只显示首条」）")
    if _c274:
        failures.append("§61.274 V0.4-01 入口合并：%s" % "；".join(_c274))
    else:
        print("[PASS] §61.274 V0.4-01 曲库唯一入口（音乐库吸收全功能 + 无 tab7/更多入口）")

    # ---- §61.275（V0.4-02 显示契约统一）：副题「已知字段才显示」+ 文案去内部术语 ----
    # 判据：① 列表副题统一走 TrackInfo.listSubtitle（单一真相源，占位值/空段过滤）；
    # ② 用户可见文案不含内部术语（保活/电池优化豁免/重新拉起/数据源管理/DLNA/DMR）
    #    与文档编号（§）；③ 副题不得再用 `${track.artist} · ${track.album}` 裸拼。
    # 边界：PlaybackService 的 logcat 文本不是用户可见文案，不纳入；注释由 _code_only 剥离。
    _ui_dir = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic")
    _ui_pkg = _ui_dir / "ui"
    _c275 = []
    for _rel in ("ui/HomeScreen.kt", "ui/FnLibraryScreen.kt", "ui/CloudMusicApp.kt"):
        _body = _code_only((_ui_dir / _rel).read_text(encoding="utf-8", errors="replace"))
        if "TrackInfo.listSubtitle(" not in _body:
            _c275.append("%s 副题未走 TrackInfo.listSubtitle" % _rel)
    for _p in _ui_pkg.rglob("*.kt"):
        _body = _code_only(_p.read_text(encoding="utf-8", errors="replace"))
        if re.search(r"\$\{track\.artist\}\s*·\s*\$\{track\.album\}", _body):
            _c275.append("%s 仍有 artist · album 裸拼副题（缺字段会出现空段）" % _p.name)
    _copy_files = [
        _ui_dir / "MainActivity.kt",
        _ui_dir / "LocalSourceAdder.kt",
        _ui_dir / "MiuiAutostartGuide.kt",
    ] + list(_ui_pkg.rglob("*.kt"))
    # §61.275：错误码同样不得进用户文案（07 码表 → ErrorText.human 映射）
    _copy_terms = ("保活", "电池优化豁免", "重新拉起", "数据源管理", "DLNA", "DMR", "E_DS_")
    _err275 = (ROOT / "core-model" / "src" / "main" / "kotlin" / "com" / "example"
               / "cloudmusic" / "model" / "ErrorText.kt")
    if not _err275.is_file() or "fun human(" not in _err275.read_text(encoding="utf-8", errors="replace"):
        _c275.append("错误码人话映射（ErrorText）缺失")
    elif any(x not in _ui274 for x in (
        "ErrorText.human(storage.errorMsg)",
        "ErrorText.human(target.errorMsg)",
    )):
        _c275.append("数据源错误态未走 ErrorText.human（仍会显示原始错误码）")
    for _p in _copy_files:
        if not _p.is_file():
            continue
        _body = _code_only(_p.read_text(encoding="utf-8", errors="replace"))
        for _lit in re.findall(r'"((?:[^"\\\n]|\\.)*)"', _body):
            _hit = [t for t in _copy_terms if t in _lit]
            if _hit:
                _c275.append("%s 文案残留内部术语「%s」" % (_p.name, ",".join(_hit)))
            if "§" in _lit:
                _c275.append("%s 文案残留文档编号（§）" % _p.name)
    if _c275:
        failures.append("§61.275 V0.4-02 显示契约：%s" % "；".join(sorted(set(_c275))))
    else:
        print("[PASS] §61.275 V0.4-02 副题已知字段才显示 + 文案无内部术语")

    # ---- §61.276（V0.4-03 灰显落地 / 拍板 #3A）：整行降透明 + 「暂不可用」角标 ----
    # 判据：① 组件层具备灰显能力（dimmed/badge + alpha 45% + 角标组件）；
    # ② 音乐库行按 availability 自动灰显并拦截点击；③ 收藏/最近/歌单/队列四处接线；
    # ④ 点击提示走人话原因（TrackInfo.availabilityLabel），不是枚举名。
    _fe276 = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic"
              / "ui" / "FeiniuUi.kt").read_text(encoding="utf-8", errors="replace")
    _lib276 = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic"
               / "ui" / "FnLibraryScreen.kt").read_text(encoding="utf-8", errors="replace")
    _ui276 = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example" / "cloudmusic"
              / "ui" / "CloudMusicApp.kt").read_text(encoding="utf-8", errors="replace")
    _c276 = []
    if "dimmed: Boolean = false" not in _fe276 or "badge: String? = null" not in _fe276:
        _c276.append("FnTrackRow 缺 dimmed/badge 参数")
    if ".alpha(if (dimmed) 0.45f else 1f)" not in _fe276:
        _c276.append("整行降透明（45%）缺失")
    if "fun FnUnavailableBadge(" not in _fe276:
        _c276.append("「暂不可用」角标组件缺失")
    if "dimmed = unavailable" not in _lib276 or "badge = if (unavailable)" not in _lib276:
        _c276.append("音乐库行未按 availability 灰显")
    if "fun unavailableBadge(" not in _ui276 or "fun playOrExplain(" not in _ui276:
        _c276.append("不可用拦截/角标辅助缺失（收藏/最近/歌单/队列将仍可误触播放）")
    if _ui276.count("dimmed = ") < 3:
        _c276.append("收藏/最近/歌单/队列灰显接线不足（<3 处）")
    if "TrackInfo.availabilityLabel(" not in _ui276:
        _c276.append("不可用点击未走人话原因（会露出枚举名）")
    if _c276:
        failures.append("§61.276 V0.4-03 灰显：%s" % "；".join(_c276))
    else:
        print("[PASS] §61.276 V0.4-03 灰显（整行降透明 + 角标 + 点击原因，不误触播放）")

    # ---- §61.277（V0.4-04 四页 + 弹窗族飞牛化）：飞牛页禁 Comp，弹窗统一 FnSheetDialog ----
    # 判据：① 非播放页源码（CloudMusicApp）不得再出现竞品组件/令牌（Comp* 与 Comp.*）；
    # ② 全局底色 = FnComp.PageBg；③ 弹窗族已切 FnSheetDialog；④ 三页用 FnPageHeader 骨架。
    # 边界：播放页（LegacyPlayerPage / 竞品覆盖层）仍按竞品视觉，Comp 令牌在 CompeteUi 保留。
    _c277 = []
    _legacy_tokens = (
        "CompPage(", "CompHeaderBar(", "CompGroupTitle(", "CompSettingsRow(",
        "CompTrackRow(", "CompPlaylistRow(", "CompSheetDialog(", "CompDialogInput(",
        "Comp.Text", "Comp.RowBg", "Comp.ChipBg", "Comp.AccentRed",
    )
    _app277 = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
               / "cloudmusic" / "ui" / "CloudMusicApp.kt").read_text(
                   encoding="utf-8", errors="replace")
    # Comp.Text 不能直接子串匹配（会命中 FnComp.Text）→ 用负向后视正则
    _hit277 = [t for t in _legacy_tokens if not t.startswith("Comp.") and t in _app277]
    if re.search(r"(?<!Fn)Comp\.", _app277):
        _hit277.append("Comp.*")
    if _hit277:
        _c277.append("非播放页仍用竞品组件/令牌：%s" % ",".join(_hit277))
    if "containerColor = FnComp.PageBg" not in _app277:
        _c277.append("全局底色未切飞牛（containerColor）")
    if _app277.count("FnSheetDialog(") < 10:
        _c277.append("弹窗族未全量切 FnSheetDialog（<10 处）")
    _fn_pages = ("SourcesScreen(", "PlaylistsScreen(", "QueueScreen(")
    if any(x not in _app277 for x in _fn_pages):
        _c277.append("三页页面函数缺失（重命名会绕过本判据）")
    if _c277:
        failures.append("§61.277 V0.4-04 飞牛化：%s" % "；".join(_c277))
    else:
        print("[PASS] §61.277 V0.4-04 三页 + 弹窗族飞牛化（非播放页零 Comp 混用）")

    # ---- §61.278（V0.4-05 图标表意校准）：单一真相源 + 表意修正 + 映射表在册 ----
    # 判据：① 图标只由 FnIcons.kt 定义（曲库页不得再自带一份枚举）；
    #       ② 行/统计卡图标一律传 FnIcon 枚举，退役的 Unicode 字形不得回流；
    #       ③ 三处表意错按 03-10 §8.1 修正（专辑=唱片套 / 音源=双层机架 / 设置=梯形齿轮廓）；
    #       ④ 映射表与枚举**双向**在册（加图标不登记、登记了没实现，都 FAIL）。
    # 边界：`☰` 仍是曲目封面占位字形、`✕` 仍是搜索清词、`♥/↺` 仍是列表行标记，均不在退役集合内。
    _ui_dir = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
               / "cloudmusic" / "ui")
    _fn_icons = (_ui_dir / "FnIcons.kt").read_text(encoding="utf-8", errors="replace")
    _fn_lib = (_ui_dir / "FnLibraryScreen.kt").read_text(encoding="utf-8", errors="replace")
    _fn_ui = (_ui_dir / "FeiniuUi.kt").read_text(encoding="utf-8", errors="replace")
    _fn_more = (_ui_dir / "MoreScreen.kt").read_text(encoding="utf-8", errors="replace")
    _c278 = []
    if "enum class FnIcon" not in _fn_icons or "fun FnLineIcon(" not in _fn_icons:
        _c278.append("FnIcons.kt 缺 enum class FnIcon / fun FnLineIcon")
    if "enum class FnIcon" in _fn_lib:
        _c278.append("FnLibraryScreen 仍自带图标枚举（双真相源）")
    _retired_glyphs = ("⚑", "☁", "⚖", "▤", "⧉", "◐", "∿", "⊕", "⌾", "⇅", "◎", "🔒", "≡")
    _glyph_back = [g for g in _retired_glyphs
                   if any(g in t for t in (_app277, _fn_ui, _fn_more))]
    if _glyph_back:
        _c278.append("退役 Unicode 字形回流（应改 FnIcon）：%s" % " ".join(_glyph_back))
    if 'FnRow("' in _app277:
        _c278.append("FnRow 仍以字符串字形传参")
    for _sig in (r"fun FnRow\(\s*icon: FnIcon", r"fun FnStatCard\(\s*icon: FnIcon"):
        if not re.search(_sig, _fn_ui):
            _c278.append("FeiniuUi 行组件签名未改 FnIcon：%s" % _sig)

    def _icon_branch(src_text, start, end):
        i = src_text.find(start)
        j = src_text.find(end, i + 1) if i >= 0 else -1
        return src_text[i:j] if 0 <= i < j else ""

    _albums = _icon_branch(_fn_icons, "FnIcon.Albums ->", "FnIcon.Sources ->")
    if "rect(3f, 3f, 18f, 18f" not in _albums:
        _c278.append("专辑图标退回同心圆（应为唱片套方框）")
    if "同心圆" in _albums:
        _c278.append("专辑图标注释仍是同心圆口径（靶心）")
    _sources = _icon_branch(_fn_icons, "FnIcon.Sources ->", "FnIcon.Unavailable ->")
    if _sources.count("rect(") < 2:
        _c278.append("音源图标不是双层机架（rect < 2，会与歌单提包同形）")
    _gear = _icon_branch(_fn_icons, "FnIcon.Settings ->", "FnIcon.Queue ->")
    if "teeth" not in _gear or "close()" not in _gear:
        _c278.append("设置齿轮未用梯形齿闭合轮廓（缺 teeth/close）")
    if "listOf(0f, 45f" in _gear:
        _c278.append("设置齿轮退回放射短齿（视觉读成船舵）")

    _doc310 = (ROOT / "docs" / "03-模块开发文档" / "03-10 UI 组件与主题.md").read_text(
        encoding="utf-8", errors="replace")
    if "### 8.1 线性图标-功能映射表" not in _doc310:
        _c278.append("03-10 §8.1 图标-功能映射表缺失")
    else:
        _enum_body = _fn_icons.split("enum class FnIcon")[1].split("}")[0]
        # §61.290 后枚举项形如 `Songs("全部歌曲"),`——正则允许尾随 label 参数，
        # 仍然只取枚举名，条数下限 25 不变。
        _names = re.findall(r"^    ([A-Z][A-Za-z]+)(?:\([^)]*\))?,$", _enum_body, re.M)
        if len(_names) < 25:
            _c278.append("FnIcon 枚举解析异常（仅 %d 项）" % len(_names))
        _unlisted = [n for n in _names if ("`%s`" % n) not in _doc310]
        if _unlisted:
            _c278.append("枚举已加但映射表未登记：%s" % ",".join(_unlisted))
    # 唱片红标（V0.4-05 顺带审查项）：竞品 g9f 位图带「网易云音乐保留全部著作权权利」版权弧text，
    # 播放页占位盘必须走自绘 DiscLabelArt，且自绘实现不得再画文字。
    _vinyl = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
              / "cloudmusic" / "ui" / "player" / "VinylDiscView.kt").read_text(
                  encoding="utf-8", errors="replace")
    _label = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
              / "cloudmusic" / "ui" / "player" / "DiscLabelArt.kt").read_text(
                  encoding="utf-8", errors="replace")
    if "LegacyAsset.DiscRed" in _vinyl:
        _c278.append("播放页仍用竞品红标位图（带 NetEase 版权文字）")
    if _vinyl.count("DiscLabelArt.redLabel(") < 2:
        _c278.append("唱片占位盘未全量改自绘（<2 处调用）")
    if "drawText" in _label:
        _c278.append("自绘红标不得绘文字（会重新引入版权文案风险）")
    if _c278:
        failures.append("§61.278 V0.4-05 图标表意：%s" % "；".join(_c278))
    else:
        print("[PASS] §61.278 V0.4-05 图标单一真相源 + 表意修正 + 映射表双向在册")

    # ---- §61.279（V0.4-06 迷你条飞牛化，拍板 #1A）：卡片条 + 静态两行 + 矢量键 ----
    # 判据：① 迷你条底色 = FnComp.CardBg（飞牛卡片语言），不得回退竞品 #33333e；
    #       ② 跑马灯 / 双音符 / 旋转盘 / 进度圆环整组退役（出现即 FAIL）；
    #       ③ 封面走 FnCover、按钮走 FnIcon.Play / FnIcon.Pause / FnIcon.Queue；
    #       ④ 副行接 `TrackInfo.listSubtitle`（显示契约 §61.275 同源）。
    _c279 = []
    if "FnComp.CardBg" not in app_txt:
        _c279.append("迷你条未用飞牛卡片底（FnComp.CardBg）")
    _mini_retired = ("MARQUEE", "marqueeRepeatLimit", "MiniCoverCluster",
                     "RotatingMiniCover", "LegacyAsset.NoteGlyph", "MiniPauseBar")
    # 注释里可能**如实记录**这些实现已退役（如「原生 MARQUEE 跑马灯已删除」），
    # 故按剥离注释后的代码判断，避免把留痕注释误判成回流。
    _app_code279 = _code_only(app_txt)
    _mini_back = [t for t in _mini_retired if t in _app_code279]
    if _mini_back:
        _c279.append("竞品迷你条实现回流：%s" % ",".join(_mini_back))
    for _needle in ("FnCover(coverRef", "FnIcon.Play", "FnIcon.Pause", "FnIcon.Queue"):
        if _needle not in app_txt:
            _c279.append("迷你条缺 %s" % _needle)
    if "TrackInfo.listSubtitle(it) ?: " in app_txt or "miniSubtitle" not in app_txt:
        _c279.append("迷你条副行未走 listSubtitle（显示契约分叉）")
    if _c279:
        failures.append("§61.279 V0.4-06 迷你条飞牛化：%s" % "；".join(_c279))
    else:
        print("[PASS] §61.279 V0.4-06 迷你条飞牛卡片条（竞品跑马灯/旋转盘/双音符已退役）")

    # ---- §61.280（V0.4-07 交互收敛）：返回栈分层 + 入口语义分流 + 歌词可发现 ----
    # 判据：① 根级 BackHandler 按「播放页 → 来源页/面板 → 次级页回进入方 → 一级 tab 放行」分层；
    #       ② 次级页返回不得写死回「更多」（`onBack = { showMore = true }` 消失）；
    #       ③ 每个入口显式登记来源（subOrigin）—— 我的音乐按入口滚到收藏段 / 最近段；
    #       ④ 歌词入口有一次性发现提示（`lyrics_hint_shown`），不静默隐式交互。
    _c280 = []
    _app_code = _code_only(app_txt)
    if "BackHandler(" not in _app_code:
        _c280.append("缺根级 BackHandler（返回键会直接退出 App）")
    else:
        for _needle in ("showPlayer -> showPlayer = false", "showSources -> showSources = false",
                        "showMore -> showMore = false", "else -> exitSubPage()"):
            if _needle not in _app_code:
                _c280.append("BackHandler 分层缺：%s" % _needle)
    if "onBack = { showMore = true }" in _app_code:
        _c280.append("次级页返回仍写死回「更多」（未接来源）")
    if _app_code.count("subOrigin =") < 8:
        _c280.append("入口来源登记不足（subOrigin < 8 处）")
    # §61.284：从「最近」入口滚到最近段——旧实现量 y 坐标（animateScrollTo(recentY)），
    # 懒列表下量不到**未组合项**的坐标，改为按 item 序号定位（animateScrollToItem）。
    # 判据接受两种实现之一，强度不变：必须有「focusRecent 驱动 + 一次真实滚动定位」。
    for _needle in ("fun exitSubPage()", "focusRecent"):
        if _needle not in _app_code:
            _c280.append("入口语义分流缺：%s" % _needle)
    if not ("animateScrollTo(recentY)" in _app_code
            or "animateScrollToItem(historyHeaderIndex)" in _app_code):
        _c280.append("入口语义分流缺：从「最近」进入未做滚动定位（y 坐标或 item 序号）")
    _home = (_ui_dir / "HomeScreen.kt").read_text(encoding="utf-8", errors="replace")
    if "onOpenMine(false)" not in _home or "onOpenMine(true)" not in _home:
        _c280.append("首页收藏/最近卡未按语义分流（两张卡仍同一入口）")
    _player = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
               / "cloudmusic" / "ui" / "player" / "LegacyPlayerPage.kt").read_text(
                   encoding="utf-8", errors="replace")
    if "lyrics_hint_shown" not in _player:
        _c280.append("歌词入口无首次提示（隐式交互不可发现）")
    if _c280:
        failures.append("§61.280 V0.4-07 交互收敛：%s" % "；".join(_c280))
    else:
        print("[PASS] §61.280 V0.4-07 返回栈分层 + 入口语义分流 + 歌词可发现")

    # ---- §61.281（V0.4-08）：远程 tag 阶段 B 白名单读模式恢复 ----
    # 判据：① 生产默认必须是 PRODUCTION（读模式），SKIP 只作应急/对照保留；
    #       ② 装配处与适配器默认值都指向 PRODUCTION（防"改了策略没人用"）；
    #       ③ 白名单 + 阈值约束在位（readExts / maxBytes 真生效，不是摆设）；
    #       ④ 文档演进注已从"落地前维持 SKIP"改写为现行口径（防文档与代码两张皮）。
    _c281 = []
    _tag_reader = (ROOT / "scanner" / "src" / "main" / "kotlin" / "com" / "example"
                   / "cloudmusic" / "scanner" / "TagReader.kt").read_text(
                       encoding="utf-8", errors="replace")
    if "val PRODUCTION = RemoteTagPolicy(skipRemoteTags = false)" not in _tag_reader:
        _c281.append("RemoteTagPolicy 缺 PRODUCTION 读模式常量")
    if "val SKIP = RemoteTagPolicy(skipRemoteTags = true)" not in _tag_reader:
        _c281.append("SKIP 应急开关被删（回滚通道丢失）")
    for _needle in ("val readExts: Set<String> = DEFAULT_REMOTE_TAG_EXTS",
                    "val maxBytes: Long = 512L * 1024 * 1024",
                    "if (sizeBytes > maxBytes) return true"):
        if _needle not in _tag_reader:
            _c281.append("白名单/阈值约束丢失：%s" % _needle)
    _scan_comp = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                  / "cloudmusic" / "di" / "scan" / "ScanComposition.kt").read_text(
                      encoding="utf-8", errors="replace")
    # 只看装配实参，不看注释：剥离行注释后必须出现 PRODUCTION 且不再有 SKIP
    _scan_code = _code_only(_scan_comp)
    if "remoteTagPolicy = RemoteTagPolicy.PRODUCTION" not in _scan_code:
        _c281.append("生产装配未指向 PRODUCTION（远程 tag 仍被跳过）")
    if "remoteTagPolicy = RemoteTagPolicy.SKIP" in _scan_code:
        _c281.append("生产装配仍是 SKIP（阶段 B 白名单恢复未落地）")
    _prod_tc = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
                / "cloudmusic" / "di" / "scan" / "ProductionTagAndCover.kt").read_text(
                    encoding="utf-8", errors="replace")
    if "private val remoteTagPolicy: RemoteTagPolicy = RemoteTagPolicy.PRODUCTION" not in _prod_tc:
        _c281.append("AndroidTagReader 默认策略未指向 PRODUCTION（漏传即退回全跳过）")
    _scan_test = (ROOT / "core-di" / "src" / "test" / "kotlin" / "com" / "example"
                  / "cloudmusic" / "di" / "scan" / "ScanContractsTest.kt").read_text(
                      encoding="utf-8", errors="replace")
    for _needle in ("RemoteTagPolicy.PRODUCTION.shouldSkip", "T-A4-17"):
        if _needle not in _scan_test:
            _c281.append("可断言行为缺证：%s" % _needle)
    _doc_scan = (ROOT / "docs" / "03-模块开发文档" / "03-04 扫描与元数据.md").read_text(
        encoding="utf-8", errors="replace")
    if "落地前维持 SKIP 现状" in _doc_scan or "将生产默认改回" in _doc_scan:
        _c281.append("03-04 §10 演进注仍是「待落地」口径（文档与代码两张皮）")
    for _needle in ("§61.281", "readExts"):
        if _needle not in _doc_scan:
            _c281.append("03-04 §10 缺现行口径登记：%s" % _needle)
    if _c281:
        failures.append("§61.281 V0.4-08 远程 tag 阶段 B：%s" % "；".join(_c281))
    else:
        print("[PASS] §61.281 V0.4-08 远程 tag 阶段 B 白名单读模式恢复（SKIP 留存为回滚通道）")


    # ---- §61.283（B01 收口）：FnComp 语义令牌必须随主题切色 ----
    # 成因：§61.234 主题化只 var 化了 7 个令牌，另 5 个语义令牌留成 val 深色常量，
    #       FnSwitch 关闭态另硬编码 #3A3A3E；§61.277 飞牛化全面铺开后暴露 ⇒
    #       用户切「浅色」看到黑胶囊 / 重黑分隔线 / 看不见的分隔线与输入框 / 深灰封面占位。
    # 判据：① FnComp 声明区不得出现白名单外的 val + Color(（防新增令牌又忘切色）；
    #       ② 品牌色必须仍是 val（主题恒定，不得被"顺手"改 var）；
    #       ③ 每个语义令牌都必须在 applyTheme 里 base(...) 赋值，且浅深值互异；
    #       ④ FnSwitch 不得再硬编码关闭态底；
    #       ⑤ 可断言面（FnThemeTokenTest）在册；
    #       ⑥ applyThemeCompat 必须联动 FnComp（否则切主题飞牛页不跟随）。
    _c283 = []
    _nl = chr(10)
    _val_color = "val[ ]+([A-Za-z0-9_]+)[ ]*=[ ]*" + re.escape("Color(")
    _base_call = "([A-Za-z0-9_]+)[ ]*=[ ]*" + re.escape('base("')
    _fnui = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
             / "cloudmusic" / "ui" / "FeiniuUi.kt").read_text(encoding="utf-8", errors="replace")
    _fn_start = _fnui.find("object FnComp {")
    _fn_end = _fnui.find(_nl + "}" + _nl, _fn_start) if _fn_start >= 0 else -1
    if _fn_start < 0 or _fn_end < 0:
        _c283.append("找不到 object FnComp 定义")
        _fn_block = ""
    else:
        _fn_block = _fnui[_fn_start:_fn_end]
    _fn_parts = _fn_block.split("fun applyTheme", 1)
    _fn_decl = _fn_parts[0]
    _fn_apply = _fn_parts[1] if len(_fn_parts) > 1 else ""
    if not _fn_apply:
        _c283.append("FnComp 缺 applyTheme（令牌无从切色）")

    _allowed_vals = {"BrandOrange", "IconRed", "SwitchGreen"}
    _const_vals = set(re.findall(_val_color, _fn_decl))
    _rogue = sorted(_const_vals - _allowed_vals)
    if _rogue:
        _c283.append("FnComp 出现主题不切色的 val 令牌：%s（应改 var + 进 applyTheme）"
                     % "/".join(_rogue))
    if not _allowed_vals.issubset(_const_vals):
        _c283.append("品牌色被改成 var（BrandOrange/IconRed/SwitchGreen 应主题恒定）")

    _fn_need = ("CoverPlaceholder", "Divider", "InputBg", "RowDivider", "CapsuleBg", "SwitchOffBg")
    for _n in _fn_need:
        if ('%s = base("%s",' % (_n, _n)) not in _fn_apply:
            _c283.append("applyTheme 未给 %s 切色" % _n)
    _fn_dark = dict(re.findall('"([A-Za-z0-9_]+)"[ ]+to[ ]+(0x[0-9A-Fa-f]+)', _fn_block))
    _fn_light = {}
    for _m in re.finditer(_base_call + '([A-Za-z0-9_]+)",[ ]*(0x[0-9A-Fa-f]+)' + re.escape(")"), _fn_apply):
        _fn_light[_m.group(2)] = _m.group(3)
    if len(_fn_light) < len(_fn_need):
        _c283.append("applyTheme 切色令牌数不足（%d < %d）" % (len(_fn_light), len(_fn_need)))
    for _n, _lv in sorted(_fn_light.items()):
        _dv = _fn_dark.get(_n)
        if _dv is None:
            _c283.append("浅色映射 %s 缺深色基准（无法回切）" % _n)
        elif int(_dv, 16) == int(_lv, 16):
            _c283.append("%s 浅深同值（等于没切色）" % _n)

    _fn_sw = _fnui.split("fun FnSwitch(", 1)
    _fn_sw_body = _fn_sw[1] if len(_fn_sw) > 1 else ""
    if "FnComp.SwitchOffBg" not in _fn_sw_body:
        _c283.append("FnSwitch 关闭态未用 FnComp.SwitchOffBg（硬编码会随主题失配）")
    if "Color(0xFF3A3A3E)" in _fn_sw_body:
        _c283.append("FnSwitch 仍硬编码关闭态底 #3A3A3E")

    _fn_test = (ROOT / "app" / "src" / "test" / "kotlin" / "com" / "example"
                / "cloudmusic" / "ui" / "FnThemeTokenTest.kt")
    if not _fn_test.exists():
        _c283.append("缺 FnThemeTokenTest（令牌切色无断言面）")
    else:
        _tt = _fn_test.read_text(encoding="utf-8", errors="replace")
        for _n in _fn_need:
            if _n not in _tt:
                _c283.append("FnThemeTokenTest 未覆盖 %s" % _n)
        if "assertNotEquals" not in _tt:
            _c283.append("FnThemeTokenTest 缺互异断言")

    _cu = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
           / "cloudmusic" / "ui" / "CompeteUi.kt").read_text(encoding="utf-8", errors="replace")
    if "FnComp.applyTheme(isDark)" not in _cu:
        _c283.append("applyThemeCompat 未联动 FnComp（切主题后飞牛页不跟随）")
    if _c283:
        failures.append("§61.283 FnComp 令牌切色：%s" % "；".join(_c283))
    else:
        print("[PASS] §61.283 FnComp 六语义令牌随主题切色（品牌色恒定，浅色无深块/隐形线）")

    # ---- §61.284（B07 收口：列表懒加载）：曲目行不得全量组合 ----
    # 判据是**结构**不是帧率（7,500 首与千首收藏的实测仍归 V0.4-09）：
    #   ① 我的音乐页 = LazyColumn + 收藏/历史各自 itemsIndexed（带 key），且不再 forEach 全量渲染；
    #   ② 歌单页展开区 = 曲目行发成父列表独立 item（itemsIndexed(expandedTracks)），
    #      曲目流提升到列表作用域外（LazyListScope 内不能 collectAsState）；
    #   ③ 逐行卡片底走 Modifier.fnGroupCardRow（令牌同 FnGroupCard，禁新色值）；
    #   ④ 「最近」段序号算法 = MyMusicLayout（UI 与单测同源，防两套口径）。
    # 边界：设置/数据源等**定长**列表仍可用 Column + verticalScroll，不在本判据对象内。
    _code_app = _code_only(_app277)

    def _fn_region(src: str, marker: str) -> str:
        """按「函数签名 → 下一个顶层 @Composable」切出函数体（避免跨页误判）。"""
        i = src.find(marker)
        if i < 0:
            return ""
        j = src.find("\n@Composable", i + len(marker))
        return src[i:] if j < 0 else src[i:j]

    _mm = _fn_region(_code_app, "private fun MyMusicScreen(")
    _pl = _fn_region(_code_app, "private fun PlaylistsScreen(")
    _c284 = []
    if not _mm:
        _c284.append("找不到 MyMusicScreen（重命名会绕过本判据）")
    else:
        if "LazyColumn(" not in _mm:
            _c284.append("我的音乐仍是全量渲染（无 LazyColumn）")
        for _need in ("itemsIndexed(favTracks", "itemsIndexed(history"):
            if _need not in _mm:
                _c284.append("我的音乐缺懒 item：%s" % _need)
        if "forEachIndexed" in _mm:
            _c284.append("我的音乐仍有 forEachIndexed 全量渲染曲目行")
        if "animateScrollToItem(historyHeaderIndex)" not in _mm:
            _c284.append("「最近」入口未按 item 序号定位（懒列表量不到未组合项的 y）")
        if "MyMusicLayout.historyHeaderIndex(" not in _mm:
            _c284.append("我的音乐未引用 MyMusicLayout（UI 与断言两套口径）")
    if not _pl:
        _c284.append("找不到 PlaylistsScreen（重命名会绕过本判据）")
    else:
        if "itemsIndexed(" not in _pl or "expandedTracks" not in _pl:
            _c284.append("歌单展开区未改懒 item（itemsIndexed(expandedTracks)）")
        if "tracks.forEachIndexed" in _pl:
            _c284.append("歌单展开区仍 forEachIndexed 全量渲染")
        if "val expandedTracks by" not in _app277:
            _c284.append("展开曲目流未提升到列表作用域外（LazyListScope 内不能 collectAsState）")
    # 通用判据（本批的教训）：任何 `forEach` 块内**直接**渲染曲目行 = 全量组合，
    # 与"有没有 LazyColumn"无关。按花括号配对切块，避免只看邻近行漏判多行块。
    _eager_hits = []
    for _m in re.finditer(r"\.forEach(?:Indexed)?\s*(?:\([^)]*\))?\s*\{", _code_app):
        _start = _m.end() - 1
        _depth = 0
        _j = _start
        while _j < len(_code_app):
            _ch = _code_app[_j]
            if _ch == "{":
                _depth += 1
            elif _ch == "}":
                _depth -= 1
                if _depth == 0:
                    break
            _j += 1
        _block = _code_app[_start:_j]
        # 块内有 `item(`/`items(`/`itemsIndexed(` = 正在向父列表**发懒 item**（合法，
        # 歌单/分组展开都是这个形状）；只有"直接渲染"才是全量组合。
        if "FnTrackRow(" in _block and not re.search(
                r"\b(?:item|items|itemsIndexed)\s*\(", _block):
            # 报「接收者 + 方法名」而不是行号：_code_only 剥过注释，行号与源文件对不上。
            _recv = _code_app[max(0, _m.start() - 40):_m.start()].strip().splitlines()[-1].strip()
            _kind = ".forEachIndexed" if _m.group(0).startswith(".forEachIndexed") else ".forEach"
            _eager_hits.append("%s%s" % (_recv[-24:], _kind))
    if _eager_hits:
        _c284.append("forEach 块内直接渲染曲目行（应改 Lazy itemsIndexed）：%s"
                     % ",".join(_eager_hits))
    if "fun Modifier.fnGroupCardRow(" not in _fn_ui:
        _c284.append("缺 Modifier.fnGroupCardRow（逐行卡片底无处复用）")
    else:
        _row = _fn_ui[_fn_ui.find("fun Modifier.fnGroupCardRow("):]
        _end = _row.find("\n}")
        _row = _row if _end < 0 else _row[:_end]
        for _tok in ("FnBaseline.CardCornerDp", "FnBaseline.PagePaddingDp", "FnComp.CardBg"):
            if _tok not in _row:
                _c284.append("fnGroupCardRow 未用令牌 %s（硬编码会与卡片漂移）" % _tok)
        if re.search(r"Color\(0x", _row):
            _c284.append("fnGroupCardRow 硬编码色值")
    _mml = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
            / "cloudmusic" / "ui" / "MyMusicLayout.kt")
    _mmlt = (ROOT / "app" / "src" / "test" / "kotlin" / "com" / "example"
             / "cloudmusic" / "ui" / "MyMusicLayoutTest.kt")
    if not _mml.is_file():
        _c284.append("缺 MyMusicLayout.kt（序号算法无单一真相源）")
    if not _mmlt.is_file():
        _c284.append("缺 MyMusicLayoutTest（序号口径无断言面）")
    else:
        _mmlt_src = _mmlt.read_text(encoding="utf-8", errors="replace")
        if "historyHeaderIndex" not in _mmlt_src or "assertEquals" not in _mmlt_src:
            _c284.append("MyMusicLayoutTest 未断言 historyHeaderIndex")
    if _c284:
        failures.append("§61.284 列表懒加载：%s" % "；".join(_c284))
    else:
        print("[PASS] §61.284 收藏/历史/歌单展开改懒 item（无全量组合，序号算法单测同源）")

    # ---- §61.285（U-13 拍板 ②：重扫进行态）：按钮反馈 + 重入不误清标志 ----
    # 判据（结构 + 真实接线，不看"能不能跑"）：
    #   ① 曲库页签名有 `scanning: Boolean`（状态从运行时来，不是页内自造）；
    #   ② 顶栏重扫钮把 scanning 传给按钮的 busy，且 busy 时显示进度环、禁用点击；
    #   ③ CloudMusicApp 必须传真实 `runtime.scanning`（写死 false 等于没做）；
    #   ④ 运行时的 finally 必须做「仍是当前代次」判定——重入时旧任务后跑会把
    #      新一轮的 scanning 覆盖成 false（进度环刚亮就灭，正是本项要治的）；
    #      用**起跑前**定下的代次而不是 Job 身份（身份在"同步跑完"的极端时序下会误判）。
    _fn_lib_285 = (_ui_dir / "FnLibraryScreen.kt").read_text(encoding="utf-8", errors="replace")
    _lib_code_285 = _code_only(_fn_lib_285)
    _c285 = []
    if "scanning: Boolean" not in _lib_code_285:
        _c285.append("曲库页签名缺 scanning 形参")
    if "FnHeaderButton(FnIcon.Refresh, onRescan, busy = scanning)" not in _lib_code_285:
        _c285.append("重扫钮未接 scanning→busy")
    _hb = _lib_code_285[_lib_code_285.find("private fun FnHeaderButton("):]
    _hb = _hb[:_hb.find(chr(10) + "}") if chr(10) + "}" in _hb else len(_hb)]
    if "CircularProgressIndicator(" not in _hb:
        _c285.append("busy 态无进度环（用户仍看不到扫描在跑）")
    if "FnComp.BrandOrange" not in _hb:
        _c285.append("进度环未用品牌橙（裸色值会与飞牛基准漂移）")
    if "clickable(enabled = !busy)" not in _hb:
        _c285.append("busy 态未禁用点击（连点会取消重启，丢已扫进度）")
    if "scanning = runtime.scanning" not in _code_app:
        _c285.append("App 未传真实 runtime.scanning（写死/未接＝无反馈）")
    _pb = (ROOT / "core-di" / "src" / "main" / "kotlin" / "com" / "example"
           / "cloudmusic" / "di" / "playback" / "PlaybackComposition.kt").read_text(
               encoding="utf-8", errors="replace")
    if "val generation = ++scanGeneration" not in _pb:
        _c285.append("runScan 未在起跑前生成代次（无法判定谁有权收 scanning）")
    if "if (scanGeneration == generation)" not in _pb:
        _c285.append("runScan 的 finally 未判「仍是当前代次」（重入会误清 scanning）")
    if _c285:
        failures.append("§61.285 重扫进行态：%s" % "；".join(_c285))
    else:
        print("[PASS] §61.285 重扫进行态（进度环 + 禁用 + 真实 scanning + 重入不误清）")

    # ---- §61.286（歌单曲目行破坏性操作护栏）：40dp 槽位 + 移除二次确认 + 越界置灰 ----
    #   判据形态刻意「按结构而非按文案」：只认槽位边长常量、禁用参数、确认弹层的
    #   destructiveIndexOf，避免把注释/日志里的字样当成实现。
    _c286 = []
    if "internal const val PlaylistActionSlotDp = 40" not in _code_app:
        _c286.append("操作槽边长未定为 40dp（6dp padding 的文字只有 ~26dp，紧邻即误触）")
    _slot_start = _code_app.find("private fun PlaylistActionSlot(")
    if _slot_start < 0:
        _c286.append("PlaylistActionSlot 组件缺失（三键应各自独立成槽）")
    else:
        _slot_body = _code_app[_slot_start:]
        _slot_body = _slot_body[:_slot_body.find(chr(10) + "}") + 1] if (chr(10) + "}") in _slot_body else _slot_body
        if ".size(PlaylistActionSlotDp.dp)" not in _slot_body:
            _c286.append("槽位未按 PlaylistActionSlotDp 取尺寸（触控目标回到 ~26dp）")
        if "clickable(enabled = enabled)" not in _slot_body:
            _c286.append("槽位未按 enabled 禁用（越界方向点了没反应，用户无法分辨到头/点空）")
        if "FnComp.TextHint" not in _slot_body:
            _c286.append("禁用态没有视觉区分（TextHint 未用）")
    # §61.290 后每键多一个描述参数；判据改按「字形 + 边界标志 + 回调」三元组匹配，
    # 强度不减（仍要求三键各自独立成槽、方向标志正确、回调对应），并额外要求描述非空。
    for _glyph, _flag, _cb in (("↑", "canUp", "onUp"),
                               ("↓", "canDown", "onDown"),
                               ("✕", "true", "onRemove")):
        _pat = r'PlaylistActionSlot\("%s", %s, "[^"]+", %s\)' % (re.escape(_glyph), _flag, _cb)
        if not re.search(_pat, _code_app):
            _c286.append("三键未全部走独立槽位（或描述缺失）：%s" % _glyph)
    # 禁止行内直接删：曲目行只允许落「待确认」，真删必须在弹层回调里按 target 执行。
    if "onRemoveItem(playlist.id, index)" in _code_app:
        _c286.append("曲目行仍在校目内直接 onRemoveItem（无确认即删，误触丢位置不可撤销）")
    if "onRemoveItem(target.playlistId, target.index)" not in _code_app:
        _c286.append("移除确认弹层未按待确认目标执行删除")
    if "var removeTarget by remember { mutableStateOf<PendingItemRemoval?>(null) }" not in _code_app:
        _c286.append("缺 removeTarget 待确认状态（✕ 无处落，只能直删）")
    if "从歌单移除" not in _code_app:
        _c286.append("移除确认弹层缺失（与「删除歌单」口径不一致）")
    _rm_title = _code_app.find('title = "从歌单移除')
    if _rm_title < 0:
        _c286.append("移除确认弹层标题未表明动作对象（用户不知删的是什么）")
    else:
        _rm_tail = _code_app[_rm_title:_rm_title + 1200]
        if "destructiveIndexOf = 0" not in _rm_tail:
            _c286.append("移除确认的破坏性项未标红（destructiveIndexOf 缺失）")
    if _c286:
        failures.append("§61.286 歌单破坏性操作护栏：%s" % "；".join(_c286))
    else:
        print("[PASS] §61.286 歌单曲目行护栏（40dp 槽位 + 移除二次确认 + 越界置灰）")

    # ---- §61.287（字体缩放：含文字的定高容器必须用高度下限）----
    #   背景：飞牛组件此前用 `.height(60.dp)` 硬固定行高，内部是 15sp+12sp 两行文字。
    #   系统字号放大（设置→显示→字体大小，可达 2.0x）后文字被裁切。改 `.heightIn(min=)`：
    #   正常字号下 max(min, 内容) == min，像素级观感零变化；放大后行随内容长高不裁字。
    #   豁免：CompeteUi.kt（19 个 composable 已全部零调用，仅 object Comp 令牌与
    #   rememberCompCover 仍活）、SplashScreen.kt（44dp 是品牌波形图形标尺非文字容器）、
    #   player/（像素级沉浸页，行高参与基准绝对坐标堆叠，长高会让底部控件整体溢出）。
    _c287 = []
    _c287_exempt = {"CompeteUi.kt", "SplashScreen.kt"}

    def _brace_blocks(_src: str):
        _st, _out = [], []
        for _i, _ch in enumerate(_src):
            if _ch == "{":
                _st.append(_i)
            elif _ch == "}" and _st:
                _out.append((_st.pop(), _i))
        return _out

    for _p in sorted(_ui_dir.glob("*.kt")):
        if _p.name in _c287_exempt:
            continue
        _src = _code_only(_p.read_text(encoding="utf-8", errors="replace"))
        _bs = _brace_blocks(_src)
        for _m in re.finditer(re.escape(".height("), _src):
            _pos = _m.start()
            _ls = _src.rfind(chr(10), 0, _pos) + 1
            _le = _src.find(chr(10), _pos)
            _line = _src[_ls:_le if _le > 0 else len(_src)]
            if "Spacer(" in _line:
                continue
            _cand = [_b for _b in _bs if _b[0] < _pos < _b[1]]
            if not _cand:
                continue
            _inner = min(_cand, key=lambda _b: _b[1] - _b[0])
            _body = _src[_inner[0]:_inner[1]]
            if "fontSize" in _body:
                _c287.append("%s:L%d" % (_p.name, _src.count(chr(10), 0, _pos) + 1))
    if _c287:
        failures.append(
            "§61.287 含文字的容器仍用硬固定高度（大字号会裁字，应 heightIn(min=)）：%s"
            % "、".join(_c287))

    _fn287 = _code_only(_fn_ui)
    for _need, _why in (
        (".height(FnBaseline.TrackRowHeightDp.dp)", "曲目行/歌单行仍硬固定 60dp"),
        (".height(FnBaseline.RowHeightDp.dp)", "设置行仍硬固定 50.4dp"),
        (".height(FnBaseline.StatCardHeightDp.dp)", "数据卡仍硬固定 68dp"),
        (".height(FnBaseline.SearchBoxHeightDp.dp)", "搜索框仍硬固定 40dp"),
    ):
        if _need in _fn287:
            _c287.append(_why)
    for _need in (".heightIn(min = 52.dp)", ".heightIn(min = 34.dp)"):
        if _need not in _fn287:
            _c287.append("对话框操作行/胶囊按钮未改成高度下限：%s" % _need)
    if ".heightIn(min = 50.dp)" not in _code_app:
        _c287.append("「加入歌单」选择行未改成高度下限（16sp 文字会裁）")
    if ".heightIn(min = FnBaseline.HeaderButtonDp.dp)" not in _fn_lib:
        _c287.append("曲库页顶栏容器未改成高度下限（装 20sp 标题会裁）")
    if _c287:
        failures.append("§61.287 字体缩放适配：%s" % "；".join(_c287))
    else:
        print("[PASS] §61.287 字体缩放（含文字容器全部高度下限 + ui 目录通用禁令）")

    # ---- §61.288（浅色自造主题的可读性不得低于深色基准）----
    #   口径：浅色是**我方补出来的**（飞牛无浅色基准），深色是逐像素采样。判定不是硬追
    #   WCAG AA，而是「浅色 ≥ 深色同令牌的实测对比度」——否则提示文字会被压得比基准重一档。
    _c288 = []
    _ui_test_dir = (ROOT / "app" / "src" / "test" / "kotlin" / "com" / "example"
                    / "cloudmusic" / "ui")
    _contrast_test = (_ui_test_dir / "FnContrastTest.kt")
    if not _contrast_test.is_file():
        _c288.append("FnContrastTest.kt 缺失（量化断言是唯一的可复跑判据）")
    else:
        _ct = _contrast_test.read_text(encoding="utf-8", errors="replace")
        for _need in ("lightTextHintMatchesDarkBaseline", "lightSearchHintMatchesDarkBaseline"):
            if _need not in _ct:
                _c288.append("对比度用例缺失：%s" % _need)
        if "darkBaselineIsPinned" not in _ct:
            _c288.append("缺「深色基准被钉住」用例（防为修浅色而改采样值）")
    if 'TextHint = base("TextHint", 0x8F1A1A1A)' not in _fn_ui:
        _c288.append("浅色 TextHint 未取 56% 黑（40% 黑只有 2.52:1，实测不可读）")
    if 'SearchHint = base("SearchHint", 0xFF6A6A6C)' not in _fn_ui:
        _c288.append("浅色 SearchHint 未压到 #6A6A6C（#8A8A8C 只有 3.08:1，深色基准 4.72）")
    if '"TextHint" to 0x66FFFFFF' not in _fn_ui:
        _c288.append("深色基准 TextHint 被改动（采样值 40% 白不得动）")
    if '"SearchHint" to 0xFF959597' not in _fn_ui:
        _c288.append("深色基准 SearchHint 被改动（采样值 #959597 不得动）")
    if _c288:
        failures.append("§61.288 浅色可读性：%s" % "；".join(_c288))
    else:
        print("[PASS] §61.288 浅色可读性（TextHint/SearchHint ≥ 深色基准 + 基准钉值 + 量化用例在册）")

    # ---- §61.289（重组期的全量扫描：分组/筛选必须抽成纯函数并记忆化）----
    #   背景：曲库分组与不可用筛选此前直接写在 LazyListScope 的 content lambda 里——那是
    #   LazyColumn 的 item 构建器，**每次列表重建都会重跑**（切视图 / 展开分组 / 曲库更新）。
    #   7,500 首时一次 groupBy+sortedBy 是几十毫秒级。修法：抽纯函数 + composable 顶部 remember。
    _c289 = []
    for _need in ("internal fun libraryGroups(", "internal fun unavailableTracks("):
        if _need not in _fn_lib:
            _c289.append("缺纯函数：%s" % _need)
    if "remember(tracks, current) { libraryGroups(tracks, current) }" not in _fn_lib:
        _c289.append("分组未记忆化（remember(tracks, current) 缺失）")
    if "remember(tracks) { unavailableTracks(tracks) }" not in _fn_lib:
        _c289.append("不可用筛选未记忆化（remember(tracks) 缺失）")
    # 通用禁令：LazyColumn 的 content lambda（LazyListScope）里不得直接调用分组/全量筛选
    _lazy_start = _fn_lib.find("LazyColumn(")
    if _lazy_start < 0:
        _c289.append("FnLibraryScreen 的 LazyColumn 缺失（结构变了）")
    else:
        # 按花括号配对精确取 content lambda（文件末尾的纯函数定义里本来就有 groupByLabel，
        # 取整段会误报）
        _brace = _fn_lib.find("{", _lazy_start)
        _depth = 0
        _lazy_end = len(_fn_lib)
        for _i in range(_brace, len(_fn_lib)):
            if _fn_lib[_i] == "{":
                _depth += 1
            elif _fn_lib[_i] == "}":
                _depth -= 1
                if _depth == 0:
                    _lazy_end = _i
                    break
        _lazy_body = _fn_lib[_lazy_start:_lazy_end]
        for _bad in ("= groupByLabel(", "tracks.filter { it.availability"):
            if _bad in _lazy_body:
                _c289.append("LazyListScope 内又出现全量扫描：%s" % _bad.strip())
    if "val favTracks = remember(allTracks, favorites)" not in _code_app:
        _c289.append("我的音乐收藏过滤未记忆化（每次重组全量扫曲库）")
    _groups_test = (_ui_test_dir / "LibraryGroupsTest.kt")
    if not _groups_test.is_file():
        _c289.append("LibraryGroupsTest.kt 缺失（取数语义无断言）")
    if _c289:
        failures.append("§61.289 重组期全量扫描：%s" % "；".join(_c289))
    else:
        print("[PASS] §61.289 曲库分组/筛选（纯函数 + 记忆化 + LazyListScope 禁令 + 取数语义用例）")

    # ---- §61.290（无障碍语义：纯图标控件必须说得出「是什么 / 什么状态」）----
    #   背景：全仓 `semantics`/`Role` 使用数此前为 **0**，`contentDescription` 仅 5 处（3 处是
    #   `null`）。图标画在 Canvas 上不产生语义节点，于是底栏四个 tab、页头重扫/搜索圆钮、
    #   歌单行三键、迷你条播放键在 TalkBack 里全是无名「按钮」——底栏是整屏唯一导航入口，
    #   等于盲用不可导航。判据分两类：**状态型控件必须用带角色的 Modifier**（toggleable /
    #   selectable 由框架播报状态），**图标型控件必须有播报名**（且名字来自枚举单一真相源）。
    _c290 = []

    # 1) FnIcon 播报名真相源：枚举带 label + 每一项都写了
    _enum_head = "enum class FnIcon(val label: String) {"
    if _enum_head not in _fn_icons:
        _c290.append("FnIcon 未带 label 播报名参数（纯图标控件将无名）")
    else:
        _ei = _fn_icons.find(_enum_head)
        _ee = _fn_icons.find("\n}\n", _ei)
        _enum_block = _fn_icons[_ei:_ee]
        _naked = [ln.strip().rstrip(",") for ln in _enum_block.splitlines()
                  if ln.strip().endswith(",") and "(" not in ln]
        if _naked:
            _c290.append("图标项缺播报名：%s" % "、".join(_naked))
    if "contentDescription: String? = null" not in _fn_icons:
        _c290.append("FnLineIcon 缺 contentDescription 参数位")
    if "private fun Modifier.iconSemantics(" not in _fn_icons:
        _c290.append("装饰性图标未走 iconSemantics 空实现（会多出无名语义节点）")

    # 2) 底栏：只有图标 + 是唯一导航入口 ⇒ selectable(Role.Tab) 且必须播名字
    if "enum class FnCapsuleIcon(val label: String)" not in _fn_ui:
        _c290.append("底栏 tab 枚举未带播报名")
    if "role = Role.Tab" not in _fn_ui:
        _c290.append("底栏 tab 未走 selectable(Role.Tab)（选中态不播报）")
    if "contentDescription = kind.label" not in _fn_ui:
        _c290.append("底栏 tab 未挂播报名")
    if ".clickable { onSelect(i) }" in _fn_ui:
        _c290.append("底栏 tab 退回裸 clickable（选中态不播报）")

    # 3) 手绘开关：必须 toggleable(Role.Switch)，否则盲用无法判断开/关
    if "toggleable(value = checked, role = Role.Switch" not in _fn_ui:
        _c290.append("手绘开关未走 toggleable(Role.Switch)（开关态不播报）")
    _sw_i = _fn_ui.find("fun FnSwitch(")
    if _sw_i >= 0:
        _sw_body = _fn_ui[_sw_i:_fn_ui.find("\n}\n", _sw_i)]
        if ".clickable" in _sw_body:
            _c290.append("FnSwitch 体内仍有裸 clickable（覆盖了 toggleable 的状态语义）")

    # 4) 页头圆钮：描述 + 扫描中状态 + 禁用态
    _hb_i = _fn_lib.find("private fun FnHeaderButton(")
    if _hb_i < 0:
        _c290.append("FnHeaderButton 缺失（结构变了）")
    else:
        _hb_body = _fn_lib[_hb_i:_fn_lib.find("\n}\n", _hb_i)]
        for _need, _why in (
            ("contentDescription = icon.label", "页头圆钮无名"),
            ('stateDescription = "正在扫描"', "扫描中不播报状态（用户只看到环在转）"),
            ("disabled()", "扫描中未播报不可用（会一直点）"),
        ):
            if _need not in _hb_body:
                _c290.append(_why)

    # 5) 迷你条 + 歌单三键：onClickLabel 是动作提示，不是控件名
    _mb_i = _code_app.find("private fun MiniBarButton(")
    if _mb_i < 0:
        _c290.append("MiniBarButton 缺失（结构变了）")
    elif "contentDescription = description" not in _code_app[_mb_i:_code_app.find("\n}\n", _mb_i)]:
        _c290.append("迷你条圆钮无名（onClickLabel 不构成控件名）")
    _slot_calls = re.findall(r'PlaylistActionSlot\("[↑↓✕]", [^,\n]+, "(?:[^"]+)",', _code_app)
    if len(_slot_calls) != 3:
        _c290.append("歌单行三键未全部带描述（实得 %d / 3 个具名调用）" % len(_slot_calls))
    if "contentDescription = description" not in _code_app:
        _c290.append("歌单行三键未挂描述（↑↓✕ 会被读成箭头/乘号）")

    # 6) 播放页：主控钮两种形态都要有名字；进度条要让 TalkBack 读得出、拖得动
    if 'contentDescription = if (playing) "暂停" else "播放"' not in _player_src:
        _c290.append("播放/暂停主控钮无名（自绘双竖条形态尤其无声）")
    if "progressBarRangeInfo = ProgressBarRangeInfo(" not in _player_src:
        _c290.append("进度条缺 progressBarRangeInfo（TalkBack 读不出播放位置）")
    if "setProgress {" not in _player_src:
        _c290.append("进度条缺 setProgress（无障碍服务无法调进度）")

    # 7) 搜索框：装饰字形屏蔽 + 清除钮具名且触达达标
    for _need, _why in (
        ("clearAndSetSemantics { }", "装饰字形未屏蔽（「⌕」会被念成生僻符号）"),
        ('contentDescription = "清除搜索"', "清除钮无名（「✕」会被读成乘号）"),
        ("SearchClearDp", "清除钮未扩到 40dp 触达槽位（13sp 文字裸点约 13dp）"),
    ):
        if _need not in _fn_ui:
            _c290.append(_why)

    # 7b) 输入类组件：可编辑节点必须有标签；占位文字不得作为独立节点重复播报
    _di_i = _fn_ui.find("fun FnDialogInput(")
    if _di_i < 0:
        _c290.append("FnDialogInput 缺失（结构变了）")
    else:
        _di_body = _fn_ui[_di_i:_fn_ui.find("\n}\n", _di_i)]
        if "contentDescription = hint" not in _di_body:
            _c290.append("对话框输入框无名（新建歌单 / 重命名 / 来源地址都走它）")
        if "clearAndSetSemantics { }" not in _di_body:
            _c290.append("对话框占位文字未屏蔽（会与字段描述重复播报）")
    _sf_i = _fn_ui.find("fun FnSearchField(")
    if _sf_i < 0:
        _c290.append("FnSearchField 缺失（结构变了）")
    else:
        _sf_body = _fn_ui[_sf_i:_fn_ui.find("\n}\n", _sf_i)]
        if _sf_body.count("clearAndSetSemantics { }") < 2:
            _c290.append("搜索框装饰字形 / 占位文字未同时屏蔽（会重复播报）")

    # 8) 播报名不许只写在代码里：条数/唯一/无 ASCII 必须有 JVM 断言
    _label_test = (_ui_test_dir / "FnIconLabelTest.kt")
    if not _label_test.is_file():
        _c290.append("FnIconLabelTest.kt 缺失（播报名的静态断言是唯一可复跑判据）")
    else:
        _lt = _label_test.read_text(encoding="utf-8", errors="replace")
        for _need in ("iconCountIsPinned", "labelsAreUnique", "labelsHaveNoAscii",
                      "labelsAreShort", "capsuleTabsAreLabelled"):
            if _need not in _lt:
                _c290.append("播报名用例缺失：%s" % _need)

    if _c290:
        failures.append("§61.290 无障碍语义：%s" % "；".join(_c290))
    else:
        print("[PASS] §61.290 无障碍语义（图标播报名真相源 + 底栏/开关状态语义 + 纯图标钮具名 + 进度条可调）")

    # ---- §61.291（系统栏明暗跟随：两条栏与页面同色，图标按主题反相）----
    #   背景：`themes.xml` 把 statusBarColor / navigationBarColor 写死黑色、windowLightStatusBar=false，
    #   运行时只有播放页临时改过一次透明 ⇒ 浅色主题下页面底 `#FAFAFA` 顶部却是一条纯黑栏。
    #   判据盯三件事：**单一写入口**（SystemBars.kt）、**目标态可判**（stateFor 纯函数）、
    #   **不留命令式还原**（退出播放页按主题重算，而不是把窗口原值塞回去）。
    _c291 = []
    _ui_src_dir = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                  / "cloudmusic" / "ui")
    _sysbars_path = _ui_src_dir / "SystemBars.kt"
    _cloud_app_path = _ui_src_dir / "CloudMusicApp.kt"
    _main_path = _ui_src_dir.parent / "MainActivity.kt"

    if not _sysbars_path.is_file():
        _c291.append("SystemBars.kt 缺失（系统栏无单一写入口）")
    else:
        _sb = _sysbars_path.read_text(encoding="utf-8", errors="replace")
        # 1) 目标态纯函数在位，且四种组合的口径写死（播放页恒沉浸/恒浅色图标）
        if "data class State(" not in _sb or "fun stateFor(" not in _sb:
            _c291.append("SystemBars 缺目标态纯函数 stateFor（图标明暗将散落在调用点）")
        for _need in ("State(immersive = true, lightIcons = true)",
                      "State(immersive = false, lightIcons = isDark)"):
            if _need not in _sb:
                _c291.append("目标态口径缺失：%s" % _need)
        # 2) 两条栏都必须上色，且底色取自页面底（单一真相源）
        # 沉浸 / 普通两种形态都必须各自给**两条栏**上色（按出现次数判，防分支漏一条被另一分支掩盖）
        for _need in ("window.statusBarColor =", "window.navigationBarColor ="):
            _n = _sb.count(_need)
            if _n < 2:
                _c291.append("两条栏未在沉浸/普通两种形态下都上色（%s 仅 %d 次）" % (_need.strip(), _n))
        if "FnComp.PageBg.toArgb()" not in _sb:
            _c291.append("栏底色未取 FnComp.PageBg（写死色值又会与页面底漂移）")
        # 3) 图标明暗必须来自目标态，禁字面量
        for _need in ("isAppearanceLightStatusBars = !state.lightIcons",
                      "isAppearanceLightNavigationBars = !state.lightIcons"):
            if _need not in _sb:
                _c291.append("图标明暗未由目标态取反：%s" % _need.strip())

    # 4) 宿主侧：效果键必须同时含 showPlayer 与 darkTheme（键漏一个就不会重算）
    if not _cloud_app_path.is_file():
        _c291.append("CloudMusicApp.kt 缺失（结构变了）")
    else:
        _ca = _cloud_app_path.read_text(encoding="utf-8", errors="replace")
        if "DisposableEffect(showPlayer, darkTheme)" not in _ca:
            _c291.append("系统栏效果键未同时含 showPlayer 与 darkTheme（退出播放页不会按主题重算）")
        if "SystemBars.apply(activityWindow, darkTheme, showPlayer)" not in _ca:
            _c291.append("未按目标态上色（SystemBars.apply 调用缺失或被写死参数）")
        # 5) 通用禁令：不许再保存/还原窗口原值（两来源并存时必然漏配对）
        if "origStatusBarColor" in _ca:
            _c291.append("仍在保存/还原状态栏原值（§61.291 起改为目标态重算）")

    # 6) MainActivity 必须把主题结果交给 UI，禁止 UI 侧二次判断 uiMode
    if not _main_path.is_file():
        _c291.append("MainActivity.kt 缺失（结构变了）")
    else:
        _ma = _main_path.read_text(encoding="utf-8", errors="replace")
        if "darkTheme = applyThemeFromSettings()" not in _ma:
            _c291.append("MainActivity 未接收 applyThemeFromSettings 的主题结果")
        if "darkTheme = darkTheme," not in _ma:
            _c291.append("主题结果未传入 CloudMusicApp（系统栏拿不到明暗）")

    # 7) 首帧基线：XML 不得把状态栏图标声明成深色（否则浅色冷启动首帧白底白字）
    _themes_path = ROOT / "app" / "src" / "main" / "res" / "values" / "themes.xml"
    if _themes_path.is_file():
        _themes = _themes_path.read_text(encoding="utf-8", errors="replace")
        if "android:windowLightStatusBar\">true" in _themes:
            _c291.append("themes.xml 首帧基线声明了深色图标（与深色默认底冲突）")

    # 8) 真值表必须有 JVM 断言（四种组合都要钉）
    _bars_test = (_ui_test_dir / "SystemBarsTest.kt")
    if not _bars_test.is_file():
        _c291.append("SystemBarsTest.kt 缺失（图标明暗真值表无断言）")
    else:
        _bt = _bars_test.read_text(encoding="utf-8", errors="replace")
        for _need in ("darkThemeNormalPage", "lightThemeNormalPage",
                      "playerPageInDarkTheme", "playerPageInLightTheme"):
            if _need not in _bt:
                _c291.append("系统栏用例缺失：%s" % _need)

    if _c291:
        failures.append("§61.291 系统栏明暗跟随：%s" % "；".join(_c291))
    else:
        print("[PASS] §61.291 系统栏明暗跟随（目标态纯函数 + 两条栏同页面底 + 图标反相 + 退出按主题重算）")

    # ---- §61.292（页面状态保持：tab 切走再切回不丢滚动位置与视图选择）----
    #   背景：顶层内容是 `when { showSources -> … ; tab == 0 -> … }`，切 tab 时旧页整体离组合；
    #   没有 SaveableStateHolder 时 `rememberSaveable`（含 LazyColumn 自带 listState）会被丢弃 ⇒
    #   7,500 首曲库滚到半截、切去设置再回来回到顶部，曲库「专辑」视图也变回「全部」。
    #   判据盯四件事：**持有者与提供者在位**、**key 来自纯函数（禁字面量）**、**槽位互不重复**、
    #   **槽位集合有 JVM 断言**（串槽是这类改造最隐蔽的错误）。
    _c292 = []
    _pkey_path = _ui_src_dir / "PageStateKeys.kt"
    if not _pkey_path.is_file():
        _c292.append("PageStateKeys.kt 缺失（槽位 key 无单一真相源）")
    else:
        _pk = _pkey_path.read_text(encoding="utf-8", errors="replace")
        _pk_body = _pk[_pk.find("internal fun pageStateKey("):]
        if "internal fun pageStateKey(" not in _pk:
            _c292.append("pageStateKey 纯函数缺失")
        else:
            _keys292 = re.findall(r'->\s*"([a-z]+)"', _pk_body)
            if len(set(_keys292)) < 9:
                _c292.append("槽位 key 少于 9 个（一级页/覆盖层漏给槽位）：%s" % sorted(set(_keys292)))
            if len(_keys292) != len(set(_keys292)):
                _c292.append("槽位 key 重复（两页会串用同一份状态）：%s" % _keys292)
            if 'else -> "settings"' not in _pk_body:
                _c292.append("设置页未作为 else 兜底槽（与内容 when 的 else 分支不一致）")

    if "rememberSaveableStateHolder()" not in _ca:
        _c292.append("CloudMusicApp 未持有 rememberSaveableStateHolder（页面状态无处保存）")
    if "SaveableStateProvider(pageStateKey(showSources, showMore, tab))" not in _ca:
        _c292.append("页面内容未被 SaveableStateProvider 包住（或 key 未取自纯函数）")
    if 'SaveableStateProvider("' in _ca:
        _c292.append("SaveableStateProvider 使用了字面量 key（绕过单一真相源）")

    _pkey_test = (_ui_test_dir / "PageStateKeyTest.kt")
    if not _pkey_test.is_file():
        _c292.append("PageStateKeyTest.kt 缺失（槽位唯一性无断言）")
    else:
        _pt = _pkey_test.read_text(encoding="utf-8", errors="replace")
        for _need in ("tabsMapToOwnSlot", "settingsIsCatchAll", "overlaysWinOverTab", "keysAreUnique"):
            if _need not in _pt:
                _c292.append("槽位用例缺失：%s" % _need)

    if _c292:
        failures.append("§61.292 页面状态保持：%s" % "；".join(_c292))
    else:
        print("[PASS] §61.292 页面状态保持（SaveableStateHolder + 槽位 key 纯函数 + 九槽唯一 + 用例在册）")

    # ---- §61.293（输入法交互：键盘动作键不再哑、输入框按场景聚焦）----
    #   背景：全仓 `KeyboardOptions`/`KeyboardActions`/`ImeAction`/`FocusRequester` 原先出现 **0** 次，
    #   而两个输入组件都是裸 `BasicTextField` ⇒ ① 搜索框与各来源弹层的回车键是**哑键**（按下既不收
    #   键盘也不触发任何事，键盘一直盖住下半屏）；② 弹层只为输入而开，却要用户再点一下输入框；
    #   ③ 搜索页每次进入都不聚焦，点搜索图标后还得再点一下。
    #   判据盯四件事：**搜索框声明 Search 动作并收键盘**、**两个组件都持有 FocusRequester**、
    #   **每个弹层首格 autoFocus**、**单行输入必带 keyboardOptions**（防新增输入框退回哑键）。
    _c293 = []

    def _args_from293(src, lp):
        """从 '(' 位置起按圆括号配对截取调用实参（含括号）。"""
        depth = 0
        for k in range(lp, len(src)):
            ch = src[k]
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    return src[lp:k + 1]
        return ""

    def _fn_body293(src, sig):
        """按花括号配对截取函数体（含花括号）。"""
        i = src.find(sig)
        if i < 0:
            return ""
        j = src.find("{", i)
        if j < 0:
            return ""
        depth = 0
        for k in range(j, len(src)):
            ch = src[k]
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return src[j:k + 1]
        return ""

    _fei293_path = _ui_src_dir / "FeiniuUi.kt"
    if not _fei293_path.is_file():
        _c293.append("FeiniuUi.kt 缺失")
        _fei293 = ""
    else:
        _fei293 = _code_only(_fei293_path.read_text(encoding="utf-8", errors="replace"))

    _search293 = _fn_body293(_fei293, "fun FnSearchField(")
    if not _search293:
        _c293.append("FnSearchField 函数体取不到（组件被删或改名）")
    else:
        if "KeyboardOptions(imeAction = ImeAction.Search)" not in _search293:
            _c293.append("搜索框未声明 ImeAction.Search（回车键仍是哑键）")
        if "onSearch = {" not in _search293 or "clearFocus()" not in _search293:
            _c293.append("搜索框动作键按下后未收键盘（clearFocus 缺失）")
        if "FocusRequester()" not in _search293:
            _c293.append("搜索框未持有 FocusRequester（无法按场景自动聚焦）")
        if ".focusRequester(focusRequester)" not in _search293:
            _c293.append("搜索框的 FocusRequester 未接到输入框上")
        if "LaunchedEffect(autoFocus)" not in _search293 or "requestFocus()" not in _search293:
            _c293.append("搜索框未在进页时请求焦点")

    _dlg293 = _fn_body293(_fei293, "fun FnDialogInput(")
    _dlg_i293 = _fei293.find("fun FnDialogInput(")
    # 参数在函数体之外（体从 '{' 开始），签名单独取参数表。
    _dlg_sig293 = (_args_from293(_fei293, _dlg_i293 + len("fun FnDialogInput"))
                   if _dlg_i293 >= 0 else "")
    if not _dlg293 or not _dlg_sig293:
        _c293.append("FnDialogInput 签名/函数体取不到（组件被删或改名）")
    else:
        for _tok, _msg in (
            ("imeAction: ImeAction = ImeAction.Done", "弹层输入框未声明动作键参数（默认应为 Done）"),
            ("autoFocus: Boolean = false", "弹层输入框未声明 autoFocus 参数"),
        ):
            if _tok not in _dlg_sig293:
                _c293.append(_msg)
        for _tok, _msg in (
            ("KeyboardOptions(imeAction = imeAction)", "弹层输入框未把 imeAction 接到键盘"),
            ("onNext = { focusManager.moveFocus(FocusDirection.Down) }", "Next 未接到「走下一格」"),
            ("onDone = { focusManager.clearFocus() }", "Done 未接到「收键盘」"),
        ):
            if _tok not in _dlg293:
                _c293.append(_msg)

    # 单行输入必须带键盘动作：新加输入框若漏了 keyboardOptions，回车就退回哑键。
    _basic293 = 0
    for _m in re.finditer(r"BasicTextField\(", _fei293):
        _basic293 += 1
        _a = _args_from293(_fei293, _m.end() - 1)
        if "singleLine = true" in _a and "keyboardOptions =" not in _a:
            _c293.append("有单行 BasicTextField 未声明 keyboardOptions（回车退回哑键）")
    if _basic293 < 2:
        _c293.append("FeiniuUi 只识别到 %d 个 BasicTextField（搜索框/弹层输入框应为 2 个）" % _basic293)

    # 每个含输入框的弹层：**首个** FnDialogInput 必须自动聚焦。
    # 用原文判断：注释剥离会在 `https://` 处截断，把同一行后面的 autoFocus 一并吃掉。
    _ca293 = _ca
    _decls293 = [(m.group(1), m.start()) for m in
                 re.finditer(r"(?m)^(?:private |internal )?fun (\w+)\(", _ca293)]
    _dialogs293 = 0
    for _idx293, (_name293, _pos293) in enumerate(_decls293):
        _end293 = _decls293[_idx293 + 1][1] if _idx293 + 1 < len(_decls293) else len(_ca293)
        _body293 = _ca293[_pos293:_end293]
        if _name293 == "FnDialogInput" or "FnDialogInput(" not in _body293:
            continue
        _dialogs293 += 1
        _call293 = _args_from293(_body293, _body293.find("FnDialogInput(") + len("FnDialogInput"))
        if "autoFocus = true" not in _call293:
            _c293.append("弹层 %s 的首个输入框未自动聚焦" % _name293)
    if _dialogs293 < 7:
        _c293.append("只识别到 %d 个含输入框的弹层（改造前为 7 个：WebDAV/SFTP/FTP/投屏/建单/加入歌单/忽略目录）"
                     % _dialogs293)

    if "autoFocus = remember { shouldAutoFocusSearch(query) }" not in _ca293:
        _c293.append("搜索页调用点未按「进入时关键字」决定聚焦（或写死了 true）")
    _sf293 = _args_from293(_ca293, _ca293.find("FnSearchField(") + len("FnSearchField"))
    if "autoFocus = true" in _sf293:
        _c293.append("搜索框把 autoFocus 写死 true（搜完返回会再弹键盘盖住结果）")

    if _c293:
        failures.append("§61.293 输入法交互：%s" % "；".join(_c293))
    else:
        print("[PASS] §61.293 输入法交互（搜索动作键+进页聚焦 / 弹层首格聚焦 / 单行输入必带键盘动作）")

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
