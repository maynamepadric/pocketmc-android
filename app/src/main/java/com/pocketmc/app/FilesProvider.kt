package com.pocketmc.app

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

// Sistemin "Dosyalar" uygulamasına ve SAF destekli tüm dosya yöneticilerine "PocketMC" kaynağı olarak sunucu klasörünü ve yedekleri açar.
// Kök yetkisi gerekmez; erişimi kullanıcı dosya yöneticisinde verir.
class FilesProvider : DocumentsProvider() {
    companion object {
        const val AUTH = "com.pocketmc.app.docs"
        const val ROOT = "pocketmc"
        val TOP = setOf("server", "backups")
        val ROOT_COLS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES)
        val DOC_COLS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    }

    private val base: File get() = context!!.filesDir.canonicalFile

    override fun onCreate() = true

    private fun file(id: String): File {
        val rel = id.removePrefix(ROOT).trimStart('/')
        val b = base
        val f = if (rel.isEmpty()) b else File(b, rel).canonicalFile
        if (f != b && !f.path.startsWith(b.path + File.separator)) throw FileNotFoundException("Yol dışı")
        if (rel.isNotEmpty() && rel.substringBefore('/') !in TOP) throw FileNotFoundException("Gizli klasör")
        return f
    }

    private fun idOf(f: File): String {
        val rel = f.canonicalFile.relativeTo(base).path
        return if (rel.isEmpty() || rel == ".") ROOT else "$ROOT/$rel"
    }

    private fun mime(f: File): String =
        if (f.isDirectory) Document.MIME_TYPE_DIR
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"

    private fun row(c: MatrixCursor, f: File) {
        val isRoot = f == base
        val top = f.parentFile?.canonicalFile == base
        var flags = 0
        if (f.isDirectory) { if (!isRoot && f.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE }
        else if (f.canWrite()) flags = flags or Document.FLAG_SUPPORTS_WRITE
        if (!isRoot && !top) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, idOf(f)).add(Document.COLUMN_DISPLAY_NAME, if (isRoot) "PocketMC" else f.name)
            .add(Document.COLUMN_SIZE, if (f.isFile) f.length() else 0L).add(Document.COLUMN_MIME_TYPE, mime(f))
            .add(Document.COLUMN_LAST_MODIFIED, f.lastModified()).add(Document.COLUMN_FLAGS, flags)
    }

    private fun safe(n: String) = n.replace('/', '_').ifBlank { "yeni" }

    private fun notifyDir(parentId: String) {
        context!!.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTH, parentId), null)
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_COLS)
        val b = base
        TOP.forEach { File(b, it).mkdirs() }
        c.newRow().add(Root.COLUMN_ROOT_ID, ROOT).add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher).add(Root.COLUMN_TITLE, "PocketMC")
            .add(Root.COLUMN_SUMMARY, "Sunucu klasörü").add(Root.COLUMN_DOCUMENT_ID, ROOT)
            .add(Root.COLUMN_AVAILABLE_BYTES, b.usableSpace)
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val c = MatrixCursor(projection ?: DOC_COLS); row(c, file(documentId)); return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: DOC_COLS)
        val p = file(parentDocumentId)
        val kids = if (p == base) TOP.map { File(base, it) }.filter { it.exists() } else p.listFiles()?.toList() ?: emptyList()
        kids.forEach { row(c, it) }
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val p = file(parentDocumentId)
        if (!p.isDirectory || p == base) throw FileNotFoundException("Burada oluşturulamaz")
        var name = safe(displayName); var f = File(p, name); var n = 1
        while (f.exists()) { f = File(p, "${f.nameWithoutExtension} (${n++})" + (if (f.extension.isEmpty()) "" else "." + f.extension)) }
        val ok = if (mimeType == Document.MIME_TYPE_DIR) f.mkdir() else f.createNewFile()
        if (!ok) throw FileNotFoundException("Oluşturulamadı")
        notifyDir(parentDocumentId)
        return idOf(f)
    }

    override fun deleteDocument(documentId: String) {
        val f = file(documentId)
        if (f == base || f.parentFile?.canonicalFile == base) throw SecurityException("Bu klasör silinemez")
        if (!f.deleteRecursively()) throw FileNotFoundException("Silinemedi")
        notifyDir(idOf(f.parentFile!!))
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val f = file(documentId)
        if (f == base || f.parentFile?.canonicalFile == base) throw SecurityException("Bu klasör yeniden adlandırılamaz")
        val t = File(f.parentFile, safe(displayName))
        if (t.exists() || !f.renameTo(t)) throw FileNotFoundException("Yeniden adlandırılamadı")
        notifyDir(idOf(f.parentFile!!))
        return idOf(t)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        val p = file(parentDocumentId); val d = file(documentId)
        d == p || d.path.startsWith(p.path + File.separator)
    } catch (_: Exception) { false }

    override fun getDocumentType(documentId: String): String = mime(file(documentId))
}
