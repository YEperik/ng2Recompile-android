package com.ng2.dawn

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** What a default.xex says about the game it belongs to. */
data class XexInfo(val titleId: Long, val discNumber: Int, val discCount: Int) {
    val isNinjaGaiden2 get() = titleId == TITLE_ID_NG2
}

const val TITLE_ID_NG2 = 0x544307D5L

object GameStorage {

    /**
     * Where the game lives: app-specific external storage. It has a real
     * filesystem path (the runtime needs one - it rejects anything that is not
     * a directory), needs no storage permission, and is deleted with the app.
     */
    fun gameDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "game")
    }

    fun hasGame(dir: File) = File(dir, "default.xex").isFile

    /**
     * Reads the title id out of a default.xex header (XEX2, big-endian).
     * Optional header 0x00040006 is the execution info; its value is a file
     * offset to {media id, version, base version, title id, ...}.
     */
    fun parseXex(head: ByteArray): XexInfo? {
        fun be32(o: Int): Long =
            ((head[o].toLong() and 0xFF) shl 24) or ((head[o + 1].toLong() and 0xFF) shl 16) or
            ((head[o + 2].toLong() and 0xFF) shl 8) or (head[o + 3].toLong() and 0xFF)
        if (head.size < 0x20 || String(head, 0, 4, Charsets.ISO_8859_1) != "XEX2") return null
        val count = be32(0x14).toInt()
        if (count < 0 || count > 256 || 0x18 + count * 8 > head.size) return null
        for (i in 0 until count) {
            val key = be32(0x18 + i * 8)
            if (key != 0x00040006L) continue
            val off = be32(0x18 + i * 8 + 4).toInt()
            if (off < 0 || off + 24 > head.size) return null
            return XexInfo(
                titleId = be32(off + 12),
                discNumber = head[off + 18].toInt() and 0xFF,
                discCount = head[off + 19].toInt() and 0xFF,
            )
        }
        return null
    }

    fun readXex(file: File): XexInfo? = try {
        file.inputStream().use { s ->
            val buf = ByteArray(0x4000)
            val n = s.read(buf)
            if (n <= 0) null else parseXex(buf.copyOf(n))
        }
    } catch (_: IOException) { null }

    // ---- copying a game folder out of the document picker --------------------

    data class Progress(val doneBytes: Long, val totalBytes: Long, val file: String)

    class InsufficientSpace(val need: Long, val have: Long) : IOException()

    /**
     * Copies a folder chosen with ACTION_OPEN_DOCUMENT_TREE into [dest].
     * Resumable: a file already present at the right size is skipped, so an
     * interrupted 6.7 GB copy continues instead of restarting.
     */
    suspend fun copyTree(
        context: Context,
        tree: Uri,
        dest: File,
        cancel: AtomicBoolean,
        onProgress: (Progress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, tree)
            ?: throw IOException("Folder tidak bisa dibuka")
        dest.mkdirs()

        val files = ArrayList<Pair<DocumentFile, File>>()
        fun walk(d: DocumentFile, out: File) {
            for (c in d.listFiles()) {
                val name = c.name ?: continue
                if (name.contains('/') || name == ".." || name == ".") continue   // never escape dest
                if (c.isDirectory) { File(out, name).mkdirs(); walk(c, File(out, name)) }
                else if (c.isFile) files.add(c to File(out, name))
            }
        }
        walk(root, dest)

        val total = files.sumOf { it.first.length() }
        val already = files.sumOf { (src, dst) -> if (dst.length() == src.length()) src.length() else 0L }
        val need = total - already
        val have = StatFs(dest.absolutePath).availableBytes
        if (need > have) throw InsufficientSpace(need, have)

        var done = already
        val buf = ByteArray(1 shl 20)
        for ((src, dst) in files) {
            if (cancel.get()) throw IOException("Dibatalkan")
            if (dst.length() == src.length()) continue
            dst.parentFile?.mkdirs()
            val tmp = File(dst.path + ".part")
            context.contentResolver.openInputStream(src.uri)?.use { input ->
                tmp.outputStream().use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (cancel.get()) throw IOException("Dibatalkan")
                        onProgress(Progress(done, total, src.name ?: ""))
                    }
                }
            } ?: throw IOException("Tidak bisa membaca ${src.name}")
            if (!tmp.renameTo(dst)) throw IOException("Tidak bisa menulis ${dst.name}")
        }
        onProgress(Progress(total, total, ""))
    }
}
