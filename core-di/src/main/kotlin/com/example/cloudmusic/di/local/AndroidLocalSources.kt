package com.example.cloudmusic.di.local

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.example.cloudmusic.common.PathNormalizer
import com.example.cloudmusic.local.LocalChannel
import com.example.cloudmusic.local.LocalDirectorySource
import com.example.cloudmusic.local.LocalEntry
import com.example.cloudmusic.local.LocalEntryGrouper
import com.example.cloudmusic.local.LocalPathMapping
import com.example.cloudmusic.local.LocalRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * docs/03-02 §3.2 双通道的 Android 生产实现（第十九批 / T-A3）。
 *
 * 与 `:datasource-local` 的纯 JVM 驱动以 `LocalDirectorySource` 端口解耦：
 * SAF 用 `DocumentsContract.buildChildDocumentsUriUsingTree`，MediaStore 用
 * `MediaStore.Audio.Media`。所有框架调用都放在 `Dispatchers.IO`，且不把 `Cursor` /
 * `Uri` 泄漏到上层，两通道统一产出 [LocalEntry]。
 */
class SafDirectorySource(
    private val resolver: ContentResolver,
) : LocalDirectorySource {

    override val channel: LocalChannel = LocalChannel.SAF

    /** 03-02 §3.4：授权被回收时必须让 init 明确失败，不静默降级到宽泛权限。 */
    override fun permissionValid(root: LocalRoot): Boolean {
        val tree = root.treeUri ?: return false
        return resolver.persistedUriPermissions.any {
            it.uri.toString() == tree && it.isReadPermission
        }
    }

    override suspend fun list(root: LocalRoot, absolutePath: String): List<LocalEntry> =
        withContext(Dispatchers.IO) {
            val tree = root.treeUri ?: return@withContext emptyList()
            val treeDocId = root.treeDocumentId ?: return@withContext emptyList()
            val treeUri = Uri.parse(tree)
            val documentId = LocalPathMapping.safDocumentId(
                treeDocId,
                LocalPathMapping.relativePath(root, absolutePath),
            )
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val result = mutableListOf<LocalEntry>()
            resolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val timeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    val childId = cursor.string(idIdx) ?: continue
                    // §3.3：documentId 必须由 tree 派生，越界直接丢弃
                    val relative = LocalPathMapping.safRelativeFromDocumentId(treeDocId, childId)
                        ?: continue
                    val name = cursor.string(nameIdx) ?: continue
                    if (!LocalPathMapping.isValidEntryName(name)) continue
                    val isDir = cursor.string(mimeIdx) == DocumentsContract.Document.MIME_TYPE_DIR
                    result += LocalEntry(
                        name = name,
                        path = LocalPathMapping.absolutePath(root, relative),
                        isDir = isDir,
                        size = cursor.long(sizeIdx),
                        modified = cursor.long(timeIdx) / 1000L, // B13：毫秒 → 秒
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId).toString(),
                    )
                }
            }
            result
        }

    override suspend fun find(root: LocalRoot, absolutePath: String): LocalEntry? {
        val parent = absolutePath.substringBeforeLast('/', "")
        return list(root, parent).firstOrNull {
            PathNormalizer.normalize(it.path) == PathNormalizer.normalize(absolutePath)
        }
    }

    companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}

/**
 * MediaStore 是平表：按 `RELATIVE_PATH`（API 29+）或兜底 `DATA` 拼出绝对路径，
 * 再由 [LocalEntryGrouper] 聚出直接子目录，使上层看不到与 SAF 通道的差异。
 *
 * V0.1 边界：主外部存储卷（`/storage/emulated/0`）为唯一 MediaStore 根；
 * 多卷 / 可移动 SD 卡的枚举属后续批次（03-02 §5 V0.2 预留）。
 */
class MediaStoreDirectorySource(
    private val resolver: ContentResolver,
) : LocalDirectorySource {

    override val channel: LocalChannel = LocalChannel.MEDIA_STORE

    override suspend fun list(root: LocalRoot, absolutePath: String): List<LocalEntry> =
        withContext(Dispatchers.IO) {
            LocalEntryGrouper.group(absolutePath, queryAll())
        }

    override suspend fun find(root: LocalRoot, absolutePath: String): LocalEntry? =
        withContext(Dispatchers.IO) {
            val wanted = PathNormalizer.normalize(absolutePath)
            queryAll().firstOrNull { PathNormalizer.normalize(it.path) == wanted }
        }

    @Suppress("DEPRECATION") // MediaStore.Audio.Media.DATA 是 API 26~28 的唯一绝对路径来源
    private fun queryAll(): List<LocalEntry> {
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val pathColumn = if (useRelativePath) {
            MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            MediaStore.Audio.Media.DATA
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            pathColumn,
        )
        val result = mutableListOf<LocalEntry>()
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val nameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
            val timeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
            val pathIdx = cursor.getColumnIndex(pathColumn)
            while (cursor.moveToNext()) {
                val name = cursor.string(nameIdx) ?: continue
                if (!LocalPathMapping.isValidEntryName(name)) continue
                val rawPath = cursor.string(pathIdx) ?: continue
                val absolute = if (useRelativePath) {
                    VOLUME_ROOT + "/" + rawPath.trimEnd('/').trimStart('/') + "/" + name
                } else {
                    rawPath
                }
                val id = cursor.long(idIdx)
                result += LocalEntry(
                    name = name,
                    path = PathNormalizer.normalize(absolute),
                    isDir = false,
                    size = cursor.long(sizeIdx),
                    modified = cursor.long(timeIdx), // MediaStore 已是 Unix 秒
                    uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id,
                    ).toString(),
                )
            }
        }
        return result
    }

    companion object {
        const val VOLUME_ROOT = "/storage/emulated/0"
    }
}

private fun android.database.Cursor.string(index: Int): String? =
    if (index < 0 || isNull(index)) null else getString(index)

private fun android.database.Cursor.long(index: Int): Long =
    if (index < 0 || isNull(index)) 0L else getLong(index)
