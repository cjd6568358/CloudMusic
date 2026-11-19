# V0.3 M5/M6 选型报告：指纹去重 + NFS 挂载（2026-11-19，§61.244 预研批）

> 定位：`analysis/review-2026-11-19-v03-dev-plan.md` V3-M5（指纹去重）/ V3-M6（NFS 挂载）
> 的**选型前置**（计划纪律：选型报告先行，不拍板不开工）。本文只做事实核清 + 方案对比 +
> 拍板选项，**零代码**。
> 许可证硬约束（PRD 原则 9）：项目 AGPL-3.0 开源——LGPL/Apache/MIT 依赖动态链接均兼容；
> GPL 类依赖需整仓同化（除非豁免），AGPL 同质兼容。

---

## 1. 指纹去重（V3-M5，PRD V1.0 列「扫描：指纹去重」）

### 1.1 问题定义

「同一首歌多份拷贝」在曲库中并存（不同目录 / 不同格式转码 / 标签不同）。PRD 语义 =
**音频指纹**级去重（内容相同即重复，不看元数据）。

### 1.2 现实约束（一手）

1. **扫描期做指纹不可行**：指纹需要对音频**解码到 PCM**——扫描链只有 tag/魔数读取
   （JAudioTagger / AudioProbe），零解码能力；全曲解码是重 CPU/IO 操作，挂扫描期违反
   §61.155 长稳教训（扫描成本纪律）。**唯一可行形态 = 按需批处理**（§61.233
   CoverScrapeSession 先例：手动触发、限数、不挂扫描期）。
2. 解码依赖 Android 侧能力（Media3 Extractor / MediaCodec）→ 批处理天然落在
   `:app`/`:core-di` Android 侧，**非纯 JVM**。

### 1.3 候选方案对比

| 方案 | 内容 | 许可证 | 成本 | 精度 | 备注 |
|---|---|---|---|---|---|
| **A1 分层去重（推荐基座）** | Tier1 文件内容 SHA-256 精确去重（纯 JVM 扫描期可选开关）+ Tier2 时长±1s × 标题/艺人归一化模糊匹配（纯 JVM） | 无依赖 | 小 | 精确重复全覆盖；转码/重标签重复靠 Tier2 兜底 | 先落地即解决 90% 场景 |
| **A2 纯 JVM 声纹** | [Chromaprint.scala](https://github.com/mgdigital/Chromaprint.scala)——chromaprint 算法纯 JVM 重实现（官方 chromaprint 仓库收录的 Scala/JVM 版），无 native；**Maven Central 无官方构件**（JitPack 或源码 vendored） | 待核实（原版 chromaprint 为 LGPL-2.1/部分 Apache——**引入前必须读 LICENSE 文件**） | 中（解码到 PCM 需 Android 侧 Media3 批处理 + Scala 依赖引入 Android 的字节码/体积验证） | 与 AcoustID 指纹兼容 | 无 NDK/so，符合纯 JVM 偏好；Scala 运行时体积 ~几 MB |
| **A3 NDK chromaprint** | [chromaprint C 库](https://github.com/acoustid/chromaprint)（[LGPL-2.1](https://acoustid.org/license)）NDK 交叉编译 arm64 .so + 自写 JNI（社区无现成 Android binding） | LGPL-2.1 ✓（动态链接） | 大（NDK 构建 + JNI 层 + 版本演进管理——项目有 B2 NDK 工具链先例 `analysis/b2-ffmpeg-toolchain-*`） | 最准、生态兼容 | 舱冻结纪律不适用（scanner 域新 so，非 engine-legacy 舱体），但**新增 so = 新版本演进面** |
| **A4 只做 A1，声纹挂账** | A1 落地 + Tier3 挂账 V1.0 | 无依赖 | 最小 | 见 A1 | 最保守 |

### 1.4 推荐

**A1 先行（可独立拍板落地），Tier3（A2 或 A3）作为增量拍板项**。理由：分层方案无
依赖风险、纯 JVM 可测、立即有用户价值；声纹层收益只剩「转码重复」场景（个人曲库场景
占比低），值得单独拍板。若拍板声纹：**A2 优先**（无 native、无舱纪律牵连；风险 = 许可证
核实 + Scala on Android 验证，两项都可 spike 半天级验证）。

### 1.5 落地形态（若拍板）

- 批处理模式照搬 §61.233：设置页入口「重复曲目检测」→ 后台批处理（限数 50/次）→
  结果面板（分组：精确重复 / 疑似重复）→ 用户手动处置（不自动删除——PRD 原则 6 读优先）。
- Tier1 哈希 = 扫描期可选计算（大文件 SHA-256 有 IO 成本——默认关，设置项开启）。

---

## 2. NFS 挂载（V3-M6，PRD V1.0 列）

### 2.1 现实约束（一手）

1. Android **无系统级 NFS 挂载 API**（mount 需 root）——只能用户态协议实现。
2. NFS 服务端典型端口：NFS 2049（非特权 ✓）；**mountd/mount 协议常在随机高位端口**，
   portmapper(111) 查询——移动网络环境可达性需真机验证。
3. 现有驱动接口 `IDataSource`（list/get/link 语义 + ByteRange/TextRead 能力位）完全
   容纳 NFS（NFSv3 READDIR/LOOKUP/READ 对应良好）。

### 2.2 候选方案对比

| 方案 | 内容 | 许可证 | 成本 | 风险 |
|---|---|---|---|---|
| **B1 nfs4j-basic-client（推荐）** | [org.dcache:nfs4j-basic-client](https://mvnrepository.com/artifact/org.dcache/nfs4j-basic-client)——**Maven Central 现成纯 Java NFS 客户端**（NFSv3/v4.x，64 个版本，最新 2026-08，[维护中](https://github.com/dcache/nfs4j)）；底层 oncrpc4j 纯 Java ONC-RPC | **LGPL** ✓ | 小-中（第 23 模块 `:datasource-nfs` 端口化，§61.228 FTP 同型流程） | Android 兼容性需 spike：Java 17 字节码（AGP desugaring）+ 依赖树（jetty/log4j 类传递依赖?）+ mountd 交互真机验证 |
| **B2 libnfs + JNA/JNI** | [sahlberg/libnfs](https://github.com/sahlberg/libnfs)（[LGPL-2.1+](https://formulae.brew.sh/formula/libnfs)，**NDK 实测过 Android**）NDK 编译 + 自写 JNA 绑定（无现成 wrapper） | LGPL-2.1+ ✓ | 大（NDK 工具链 + 绑定层 + so 版本演进面） | native 体积/崩溃面；B2 工具链先例可复用 |
| **B3 自研 NFSv3 客户端** | 基于 oncrpc4j 手写 mount+NFSv3 最小子集（LOOKUP/READDIR/STAT/READ） | oncrpc4j LGPL ✓ | 大（协议细节多：file handle 语义/重传/cookie 分页） | 工期不可控，不推荐 |
| **B4 挂账 V1.0 不做** | 现有 WebDAV/SFTP/FTP/FTPS 已覆盖家用 NAS 主流场景 | — | 零 | NFS 收益（局域网高吞吐）搁置 |

### 2.3 推荐

**B1 spike 先行**（半天级：拉 `nfs4j-basic-client` 进临时工程 → Android 依赖分析
（dex/字节码/传递依赖）→ 对真 NAS 的 NFSv3 导出做 list/read 冒烟）→ spike PASS 再拍板
开第 23 模块；spike FAIL（依赖不可用/协议不可达）→ 如实回落 B4 挂账。**B2 保留为 B1
spike 失败后的备选**（NFS 收益足够大时）。

### 2.4 若拍板 B1 的落地序（草案）

`:datasource-nfs`（第 23 模块，NfsDriver + nfs4j 端口）→ Hilt KEY_NFS + 数据源页入口 →
凭据纪律（NFS 无凭据，host/export 记录；凭据零泄漏口径照搬）→ link 流式落
`cacheDir/nfs/<hash>` 返回 LOCAL Link（SFTP/FTP 同型）→ 真机判读（挂载→扫描→点播出声）。

---

## 3. 拍板请求（三选 N）

| # | 选项 | 说明 |
|---|---|---|
| ① | **A1 + B1 spike**（推荐） | 分层去重纯 JVM 落地 + nfs4j Android spike；两者都不需设备在场（spike 的真 NFS 冒烟需设备/真 NAS，静态依赖分析不需要） |
| ② | A1/A2/A3 中自选 + B1/B2/B4 中自选 | 按上表自由组合 |
| ③ | 全部挂账 V1.0 | V0.3 就此收官，等设备窗口清判读 |

**信息来源**：[AcoustID License](https://acoustid.org/license) / [chromaprint](https://github.com/acoustid/chromaprint) /
[Chromaprint.scala](https://github.com/mgdigital/Chromaprint.scala) / [libnfs](https://github.com/sahlberg/libnfs) /
[nfs4j](https://github.com/dcache/nfs4j) / [nfs4j-basic-client](https://mvnrepository.com/artifact/org.dcache/nfs4j-basic-client) /
[Homebrew libnfs formula](https://formulae.brew.sh/formula/libnfs)。

> AI生成
