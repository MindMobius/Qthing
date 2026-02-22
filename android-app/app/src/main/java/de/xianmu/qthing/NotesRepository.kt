package de.xianmu.qthing

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotesRepository(
    private val appContext: Context,
    private val notesTreeUri: Uri,
) {
    fun resolveRootDirectory(): DocumentFile? {
        val root = DocumentFile.fromTreeUri(appContext, notesTreeUri) ?: return null
        return root
    }

    private fun toTreeDocumentUri(uri: Uri): Uri {
        if (DocumentsContract.isTreeUri(uri)) return uri
        val docId =
            try {
                DocumentsContract.getDocumentId(uri)
            } catch (_: IllegalArgumentException) {
                DocumentsContract.getTreeDocumentId(uri)
            }
        return DocumentsContract.buildDocumentUriUsingTree(notesTreeUri, docId)
    }

    fun resolveByUri(uri: Uri): DocumentFile? {
        return DocumentFile.fromSingleUri(appContext, uri) ?: DocumentFile.fromTreeUri(appContext, uri)
    }

    private fun resolveTreeDocument(uri: Uri): DocumentFile? {
        val treeUri =
            try {
                toTreeDocumentUri(uri)
            } catch (_: Exception) {
                uri
            }
        return DocumentFile.fromTreeUri(appContext, treeUri)
    }

    fun listChildren(directory: DocumentFile): List<DocumentFile> {
        return directory.listFiles()
            .filter { it.isDirectory || (it.isFile && it.name?.endsWith(".md", ignoreCase = true) == true) }
            .sortedWith(
                compareBy<DocumentFile>(
                    { !it.isDirectory },
                    { it.name?.lowercase(Locale.getDefault()) ?: "" },
                ),
            )
    }

    fun listChildrenByUri(uri: Uri): List<DocumentFile> {
        val dir = resolveTreeDocument(uri) ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return listChildren(dir)
    }

    fun createNewNoteInRoot(): DocumentFile? {
        val root = resolveRootDirectory() ?: return null
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
        val fileName = "$timestamp.md"
        return root.createFile("text/markdown", fileName)
    }

    fun deleteDocument(uri: Uri): Boolean {
        val doc = resolveByUri(uri) ?: return false
        return try {
            doc.delete()
        } catch (_: Exception) {
            false
        }
    }

    fun findFileInRootByName(fileName: String): DocumentFile? {
        val root = resolveRootDirectory() ?: return null
        return root.findFile(fileName)
    }

    fun resolveDocument(uri: Uri): DocumentFile? {
        return DocumentFile.fromSingleUri(appContext, uri)
    }

    fun readText(uri: Uri): String {
        val inputStream = appContext.contentResolver.openInputStream(uri) ?: return ""
        inputStream.use { stream ->
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                return reader.readText()
            }
        }
    }

    fun writeText(uri: Uri, content: String) {
        val outputStream = appContext.contentResolver.openOutputStream(uri, "wt") ?: return
        outputStream.use { stream ->
            OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                writer.write(content)
            }
        }
    }

    fun renameDocument(uri: Uri, newFileName: String): Uri? {
        val trimmed = newFileName.trim()
        if (trimmed.isEmpty()) return null

        val targetName = if (trimmed.endsWith(".md", ignoreCase = true)) trimmed else "$trimmed.md"
        return try {
            val renamed = DocumentsContract.renameDocument(appContext.contentResolver, uri, targetName) ?: return null
            toTreeDocumentUri(renamed)
        } catch (_: Exception) {
            null
        }
    }
}
