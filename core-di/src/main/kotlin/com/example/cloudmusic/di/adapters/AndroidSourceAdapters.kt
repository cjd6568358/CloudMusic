package com.example.cloudmusic.di.adapters

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import com.example.cloudmusic.bridge.FdOpener
import com.example.cloudmusic.bridge.JvmFileFdOpener
import com.example.cloudmusic.bridge.SeekableSource
import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.output.dlna.MulticastLockPort
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 第十六批：T-P2 / T-P3 端口的 **Android 框架侧实现**（`12 §25`）。
 * 仅此文件引用 Android 框架；纯 JVM 单测不实例化这里的类。
 */

/**
 * 03-02 §3.2 / 03-06 §7：`content://`（SAF）用 `ContentResolver.openFileDescriptor`，
 * 普通文件路径委托 [JvmFileFdOpener]（扫描期与 JVM 侧同一套随机读语义）。
 *
 * `FileChannel.read(buf, position)` 是无状态按偏移读，满足 `IDataSource.readAt`，
 * 且不需要 API 27+ 的 `Os.pread`（minSdk 26）。
 */
class ContentResolverFdOpener(private val resolver: ContentResolver) : FdOpener {

    private val jvm = JvmFileFdOpener()

    override fun open(path: String): SeekableSource {
        if (!path.startsWith("content://", ignoreCase = true)) return jvm.open(path)
        val pfd = resolver.openFileDescriptor(Uri.parse(path), "r")
            ?: throw CloudMusicError.DsUnreachable("无法打开 SAF 文件句柄")
        val stream = FileInputStream(pfd.fileDescriptor)
        val channel: FileChannel = stream.channel
        return object : SeekableSource {
            override fun size(): Long =
                if (pfd.statSize >= 0) pfd.statSize else channel.size()

            @Synchronized
            override fun readAt(offset: Long, buf: ByteArray, len: Int): Int {
                val n = channel.read(ByteBuffer.wrap(buf, 0, len), offset)
                return if (n < 0) 0 else n
            }

            override fun close() {
                runCatching { channel.close() }
                runCatching { stream.close() }
                runCatching { pfd.close() }
            }
        }
    }
}

/**
 * 03-08 §4.1 / §12 B12：SSDP 多播期间持有 `WifiManager.MulticastLock`，
 * 发现结束或退出投屏必须释放；引用计数 + held 标志保证幂等，不重复 acquire。
 * 权限 `CHANGE_WIFI_MULTICAST_STATE` 已在 `:app` Manifest（03-11 §1）声明。
 */
class WifiMulticastLockPort(context: Context) : MulticastLockPort {

    @Suppress("DEPRECATION")
    private val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("cloudmusic:ssdp")

    @Volatile
    private var held = false

    init {
        lock.setReferenceCounted(true)
    }

    @Synchronized
    override fun acquire() {
        if (!held) {
            lock.acquire()
            held = true
        }
    }

    @Synchronized
    override fun release() {
        if (held) {
            lock.release()
            held = false
        }
    }
}
