package com.askfirst.ui

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * M5 落盘（方案 §3.5）：SAF 一次性授权一个目录 → 之后每份方案直接写进去。
 * 不写 App 私有目录（手机上等于找不到），也不申请存储权限。
 */
object SafExport {

    const val MIME_MARKDOWN = "text/markdown"

    /** 记住这个目录（跨重启有效），失败不致命。 */
    fun rememberPermission(resolver: ContentResolver, treeUri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun hasPermission(resolver: ContentResolver, treeUri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }

    /** 写一份 .md；同名加 -2/-3，**绝不覆盖**别人的文件。返回真正落下来的文件名。 */
    fun write(resolver: ContentResolver, treeUri: Uri, fileName: String, body: String): String {
        val unique = uniqueName(resolver, treeUri, fileName)
        // 注意：createDocument 要的是「父目录的 document uri」，直接把 tree uri 塞进去，
        // AOSP 的 ExternalStorageProvider 会抛 Invalid URI（真机第一轮就撞上了）。
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val document = runCatching {
            DocumentsContract.createDocument(resolver, parent, MIME_MARKDOWN, unique)
        }.getOrElse { error ->
            throw IllegalStateException("这个文件夹不支持写入（${error.message ?: "被系统拒了"}），换一个试试")
        } ?: throw IllegalStateException("这个文件夹写不进去，换一个试试")
        val stream = resolver.openOutputStream(document)
            ?: throw IllegalStateException("写文件的时候出错了，换个文件夹试试")
        stream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return unique
    }

    /** 给人看的目录名：`primary:Download/AskFirst` → `Download/AskFirst`。 */
    fun folderLabel(treeUri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
        return id.substringAfter(':', id).ifBlank { "选中的文件夹" }
    }

    private fun uniqueName(resolver: ContentResolver, treeUri: Uri, fileName: String): String {
        val existing = childNames(resolver, treeUri)
        if (fileName !in existing) return fileName
        val base = fileName.removeSuffix(".md")
        var index = 2
        while ("$base-$index.md" in existing) index++
        return "$base-$index.md"
    }

    private fun childNames(resolver: ContentResolver, treeUri: Uri): Set<String> {
        val children = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        }.getOrNull() ?: return emptySet()
        val names = mutableSetOf<String>()
        runCatching {
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) names += cursor.getString(0).orEmpty()
            }
        }
        return names
    }
}
