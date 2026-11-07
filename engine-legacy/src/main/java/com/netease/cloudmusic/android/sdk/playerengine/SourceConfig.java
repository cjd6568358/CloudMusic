package com.netease.cloudmusic.android.sdk.playerengine;

/**
 * 12 §50 真机 A3 · native 字段校准（jadx 1.5.1 / classes14.dex 实证）。
 *
 * 原 V0.1 空壳让 libncmplayerengine.so 的 nativeEngineSetDataSource 在
 * GetFieldID(SourceConfig, "type", "L.../SourceConfig$Type;") 处带
 * NoSuchFieldError 进入 CheckJNI abort。这里按反编译的真实 Kotlin data class
 * 字段逐一补齐；去掉 @Metadata / Intrinsics 依赖（超长注解会破坏 light-class 解析）。
 *
 * 与竞品唯一差异：无参构造给非空默认值（原实现全部为 null），避免 native 读取
 * type / encryptedType 时解引用空指针；字段名与类型描述符保持完全一致。
 */
public final class SourceConfig {

    private Type type;
    private EncryptedType encryptedType;
    private boolean cacheLocal;
    private boolean cacheOnly;
    private EngineCacheLoadConfig engineCacheLoadConfig;

    public SourceConfig() {
        this(false);
    }

    /**
     * 12 §50：native 依据 type 决定走 localPath 还是 DataProvider.getUrl() 取流。
     * V0.1 的 localPath 源走 LOCAL，URL / 回环代理源必须走 REMOTE，否则
     * BufferSource open 后拿不到数据（真机实测：焦点 / Play 都成功但 0 个 active track）。
     */
    public SourceConfig(boolean remote) {
        this.type = remote ? Type.REMOTE : Type.LOCAL;
        this.encryptedType = EncryptedType.None;
        // 12 §61（第五十一批 · 用户 2026-11-08 拍板批准路线 2）：置 true —— normal mode。
        // §58/§59/§60 实证链：Memory Mode（cache_local=false）下 NMMemoryCache 是 FIFO
        // 管道、OnWriteNetData 被拒、尾部错位、XorLocalSource fd 无位置串行化 —— 全部
        // native 缺陷在 normal mode 下消失（竞品生产路径）。配套前提是 EngineAdapter
        // 先调 NMVirtualFileManager.SetConfig 初始化缓存根目录（App 内部缓存子目录，
        // 512MiB 上限，卸载即清；口径演进见 docs/01 §6.1 的 2026-11-08 批注）。
        this.cacheLocal = true;
        this.cacheOnly = false;
        this.engineCacheLoadConfig = new EngineCacheLoadConfig();
    }

    public Type getType() { return type; }
    public EncryptedType getEncryptedType() { return encryptedType; }
    public boolean getCacheLocal() { return cacheLocal; }
    public boolean getCacheOnly() { return cacheOnly; }
    public EngineCacheLoadConfig getEngineCacheLoadConfig() { return engineCacheLoadConfig; }

    /** 竞品内部枚举，描述符必须是 SourceConfig$Type（native 硬编码）。 */
    public enum Type {
        LOCAL,
        REMOTE
    }
}
