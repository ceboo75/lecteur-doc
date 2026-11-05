package fr.ceb.lecteur

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.InputStream

enum class DocType(val mime: String, val label: String, val extension: String) {
    PDF("application/pdf", "PDF", ".pdf"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "DOCX", ".docx");

    companion object {
        fun fromName(name: String): DocType? = when {
            name.endsWith(".pdf", ignoreCase = true) -> PDF
            name.endsWith(".docx", ignoreCase = true) -> DOCX
            else -> null
        }
    }
}

data class DocItem(val file: File, val type: DocType, val lastOpened: Long, val size: Long) {
    val name: String get() = file.name
}

class UnsupportedDocException(message: String) : Exception(message)

/**
 * Chaque document ouvert est copié dans l'espace privé de l'app (docs/<id>/<nom>),
 * ce qui permet de le rouvrir depuis « Récents » et de le partager sous son vrai nom.
 */
class DocStore(private val context: Context) {

    private val root = File(context.filesDir, "docs").apply { mkdirs() }

    fun recents(): List<DocItem> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir -> dir.listFiles().orEmpty().firstOrNull { it.isFile }?.let(::itemFor) }
            .sortedByDescending { it.lastOpened }

    fun fromPath(path: String): DocItem? = File(path).takeIf { it.isFile }?.let(::itemFor)

    fun touch(item: DocItem) {
        item.file.setLastModified(System.currentTimeMillis())
    }

    fun remove(item: DocItem) {
        item.file.parentFile?.deleteRecursively()
    }

    fun import(uri: Uri): DocItem {
        val resolver = context.contentResolver
        val head = resolver.openInputStream(uri)?.use { readHead(it) }
            ?: throw IOException("Lecture impossible")

        val type = detectType(head)
        var name = sanitize(queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document")
        if (!name.endsWith(type.extension, ignoreCase = true)) name += type.extension

        val dir = File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
        val target = File(dir, name)
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: throw IOException("Lecture impossible")
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }

        // Même document déjà présent dans les récents : on garde l'ancien.
        val duplicate = recents().firstOrNull {
            it.file.parentFile != dir && it.name == name && it.size == target.length()
        }
        if (duplicate != null) {
            dir.deleteRecursively()
            touch(duplicate)
            return itemFor(duplicate.file)!!
        }

        trim()
        return itemFor(target)!!
    }

    private fun trim() {
        recents().drop(MAX_RECENTS).forEach { remove(it) }
    }

    private fun itemFor(file: File): DocItem? =
        DocType.fromName(file.name)?.let { DocItem(file, it, file.lastModified(), file.length()) }

    private fun detectType(head: ByteArray): DocType {
        if (head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) return DocType.DOCX
        if (String(head, Charsets.ISO_8859_1).contains("%PDF")) return DocType.PDF
        if (head.size >= 4 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() &&
            head[2] == 0x11.toByte() && head[3] == 0xE0.toByte()
        ) {
            throw UnsupportedDocException(
                "Ce fichier est au format .doc (ancien Word), qui n'est pas pris en charge. " +
                    "Enregistrez-le en .docx ou en PDF pour l'ouvrir."
            )
        }
        throw UnsupportedDocException(
            "Format non pris en charge. Lecteur Ceb ouvre les PDF et les documents Word (.docx)."
        )
    }

    private fun readHead(input: InputStream): ByteArray {
        val buffer = ByteArray(1024)
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n <= 0) break
            total += n
        }
        return buffer.copyOf(total)
    }

    private fun queryName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().take(150).ifBlank { "document" }

    private companion object {
        const val MAX_RECENTS = 25
    }
}
