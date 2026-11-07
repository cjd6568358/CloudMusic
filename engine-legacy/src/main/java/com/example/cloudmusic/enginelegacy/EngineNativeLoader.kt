package com.example.cloudmusic.enginelegacy

/**
 * T-L L1（`12 §35` / `§34 D-104`）· 竞品 so 加载链的自研承接点。
 *
 * 反编译出的 12 个精确 JNI 类在静态块里调用混淆封装器 `o2.c.e(String)` 与
 * `zv.a.b(Context, String, String)` 逐个 `loadLibrary`；`o2.c` / `zv.a` 两个 slot
 * 已把调用委托到本类，因此这里就是「Java 层 → native 库」的唯一入口。
 *
 * **实证的按类加载链（`12 §34 D-104`）**：
 * - `PlaybackEngine`：`ffmpeg → NMCommonCache → ncmusbaudio → ncmaudioplayer → neaudioeffects → ncmplayerengine`
 * - `AudioPlayerUnit` / `Playback`：同上但无 `ncmusbaudio`（5 个）
 * - `PlaylistJniBridge`：仅 `nmplaylist`
 * 调用方静态块本身已保证顺序，本类按调用顺序去重加载即可，不重排、不预加载。
 * `libc++_shared.so` 与 4 个按名加载的软解器不在静态块里，由 linker / native 层自行解析。
 *
 * **边界（V0.1）**：仅完成静态编译链路；`System.loadLibrary` 的失败一律显式抛出
 * `UnsatisfiedLinkError`，不静默吞掉（便于 G01 定位缺库）。JNI 注册方式
 * （静态 `Java_*` 符号 vs `JNI_OnLoad` 动态注册）与 native 回调缺类仍待真机 G01/G02 收口。
 */
object EngineNativeLoader {

    private val loaded = LinkedHashSet<String>()

    /** 供 `o2.c.e` 与 `zv.a.b` 调用；同名库只加载一次。 */
    @JvmStatic
    fun load(name: String) {
        synchronized(loaded) {
            if (name in loaded) return
            System.loadLibrary(name)   // 失败抛 UnsatisfiedLinkError，不吞异常
            loaded.add(name)
        }
    }

    /** 供 `EngineAdapter` 显式按链加载（顺序即 `§34 D-104` 的实证顺序）。 */
    @JvmStatic
    fun loadChain(vararg names: String) {
        names.forEach(::load)
    }

    /** 诊断用：已成功加载的库名（不含凭据 / 路径）。 */
    @JvmStatic
    fun loadedLibraries(): List<String> = synchronized(loaded) { loaded.toList() }

    /** `PlaybackEngine` 的完整链，供后续 `EngineAdapter` 复用。 */
    @JvmField
    val PLAYBACK_ENGINE_CHAIN = arrayOf(
        "ffmpeg", "NMCommonCache", "ncmusbaudio", "ncmaudioplayer",
        "neaudioeffects", "ncmplayerengine",
    )
}
