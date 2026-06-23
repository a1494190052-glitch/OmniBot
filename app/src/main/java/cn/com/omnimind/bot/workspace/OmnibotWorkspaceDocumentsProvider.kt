package cn.com.omnimind.bot.workspace

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.LinkedList
import java.util.Locale

class OmnibotWorkspaceDocumentsProvider : DocumentsProvider() {
    companion object {
        private const val ROOT_ID = "workspace"
        private const val ROOT_DOCUMENT_ID = "workspace:"
        private const val DEFAULT_MIME_TYPE = "application/octet-stream"
        private const val ALL_MIME_TYPES = "*/*"
        private const val MAX_SEARCH_RESULTS = 50

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
        )
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val rootDir = workspaceRoot()
        AgentWorkspaceManager(providerContext()).ensureRuntimeDirectories()
        return MatrixCursor(resolveProjection(projection, DEFAULT_ROOT_PROJECTION)).apply {
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, ROOT_ID)
                add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES)
                add(
                    Root.COLUMN_FLAGS,
                    Root.FLAG_LOCAL_ONLY or
                        Root.FLAG_SUPPORTS_CREATE or
                        Root.FLAG_SUPPORTS_SEARCH or
                        Root.FLAG_SUPPORTS_IS_CHILD
                )
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(Root.COLUMN_TITLE, "Omnibot Workspace")
                add(Root.COLUMN_SUMMARY, "/workspace")
                add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
                add(Root.COLUMN_AVAILABLE_BYTES, rootDir.usableSpace)
            }
        }
    }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?
    ): Cursor {
        val cursor = MatrixCursor(resolveProjection(projection, DEFAULT_DOCUMENT_PROJECTION))
        includeFile(cursor, fileForDocumentId(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val parent = fileForDocumentId(parentDocumentId)
        if (!parent.isDirectory) {
            throw FileNotFoundException("Document is not a directory: $parentDocumentId")
        }
        val cursor = MatrixCursor(resolveProjection(projection, DEFAULT_DOCUMENT_PROJECTION))
        parent.listFiles()
            .orEmpty()
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .forEach { child ->
                runCatching { includeFile(cursor, child) }
            }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = fileForDocumentId(documentId)
        if (file.isDirectory) {
            throw FileNotFoundException("Cannot open a directory: $documentId")
        }
        file.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        throw FileNotFoundException("Workspace thumbnails are not supported: $documentId")
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val parent = fileForDocumentId(parentDocumentId)
        if (!parent.isDirectory) {
            throw FileNotFoundException("Parent is not a directory: $parentDocumentId")
        }
        val target = resolveUniqueChild(parent, displayName)
        if (mimeType == Document.MIME_TYPE_DIR) {
            if (!target.mkdirs()) {
                throw IOException("Failed to create directory: ${target.name}")
            }
        } else {
            target.parentFile?.mkdirs()
            if (!target.createNewFile()) {
                throw IOException("Failed to create file: ${target.name}")
            }
        }
        return documentIdForFile(target)
    }

    override fun deleteDocument(documentId: String) {
        val file = fileForDocumentId(documentId)
        if (documentId == ROOT_DOCUMENT_ID) {
            throw UnsupportedOperationException("Cannot delete workspace root")
        }
        if (!file.deleteRecursively()) {
            throw IOException("Failed to delete document: $documentId")
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileForDocumentId(documentId)
        if (documentId == ROOT_DOCUMENT_ID) {
            throw UnsupportedOperationException("Cannot rename workspace root")
        }
        val targetName = sanitizeDisplayName(displayName)
        val target = File(file.parentFile ?: workspaceRoot(), targetName)
        val canonicalTarget = target.canonicalFile
        ensureWithinWorkspace(canonicalTarget)
        if (canonicalTarget.exists()) {
            throw IOException("Target already exists: $targetName")
        }
        if (!file.renameTo(canonicalTarget)) {
            throw IOException("Failed to rename document: $documentId")
        }
        return documentIdForFile(canonicalTarget)
    }

    override fun getDocumentType(documentId: String): String {
        return mimeTypeFor(fileForDocumentId(documentId))
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?
    ): Cursor {
        if (rootId != ROOT_ID) {
            throw FileNotFoundException("Unknown root id: $rootId")
        }
        val normalizedQuery = query.trim().lowercase(Locale.getDefault())
        val cursor = MatrixCursor(resolveProjection(projection, DEFAULT_DOCUMENT_PROJECTION))
        if (normalizedQuery.isBlank()) {
            return cursor
        }

        val pending = LinkedList<File>().apply { add(workspaceRoot()) }
        while (pending.isNotEmpty() && cursor.count < MAX_SEARCH_RESULTS) {
            val file = pending.removeFirst()
            runCatching {
                val canonicalFile = file.canonicalFile
                ensureWithinWorkspace(canonicalFile)
                if (canonicalFile.name.lowercase(Locale.getDefault()).contains(normalizedQuery)) {
                    includeFile(cursor, canonicalFile)
                }
                if (canonicalFile.isDirectory) {
                    canonicalFile.listFiles()?.forEach { pending.add(it) }
                }
            }
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return runCatching {
            val parent = fileForDocumentId(parentDocumentId).canonicalFile
            val child = fileForDocumentId(documentId).canonicalFile
            isWithin(parent, child)
        }.getOrDefault(false)
    }

    private fun providerContext() =
        context ?: throw IllegalStateException("Provider context is not attached")

    private fun workspaceRoot(): File {
        return AgentWorkspaceManager.rootDirectory(providerContext()).apply { mkdirs() }.canonicalFile
    }

    private fun includeFile(cursor: MatrixCursor, file: File) {
        val canonicalFile = file.canonicalFile
        ensureWithinWorkspace(canonicalFile)
        val mimeType = mimeTypeFor(canonicalFile)
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentIdForFile(canonicalFile))
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_DISPLAY_NAME, displayNameFor(canonicalFile))
            add(Document.COLUMN_LAST_MODIFIED, canonicalFile.lastModified())
            add(Document.COLUMN_FLAGS, flagsFor(canonicalFile, mimeType))
            add(Document.COLUMN_SIZE, if (canonicalFile.isFile) canonicalFile.length() else null)
        }
    }

    private fun fileForDocumentId(documentId: String): File {
        if (!documentId.startsWith(ROOT_DOCUMENT_ID)) {
            throw FileNotFoundException("Unknown document id: $documentId")
        }
        val relativePath = documentId.removePrefix(ROOT_DOCUMENT_ID).trim('/')
        val file = if (relativePath.isBlank()) {
            workspaceRoot()
        } else {
            File(workspaceRoot(), relativePath)
        }.canonicalFile
        ensureWithinWorkspace(file)
        return file
    }

    private fun documentIdForFile(file: File): String {
        val root = workspaceRoot()
        val canonicalFile = file.canonicalFile
        ensureWithinWorkspace(canonicalFile)
        if (canonicalFile == root) {
            return ROOT_DOCUMENT_ID
        }
        val relativePath = canonicalFile.relativeTo(root).path.replace(File.separatorChar, '/')
        return ROOT_DOCUMENT_ID + relativePath
    }

    private fun flagsFor(file: File, mimeType: String): Int {
        val writeFlags = if (file.canWrite()) {
            Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_WRITE
        } else {
            0
        }
        val createFlags = if (mimeType == Document.MIME_TYPE_DIR && file.canWrite()) {
            Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            0
        }
        return writeFlags or createFlags
    }

    private fun mimeTypeFor(file: File): String {
        if (file.isDirectory) {
            return Document.MIME_TYPE_DIR
        }
        val extension = file.extension.lowercase(Locale.US)
        if (extension.isBlank()) {
            return DEFAULT_MIME_TYPE
        }
        return when (extension) {
            "md" -> "text/markdown"
            "jsonl" -> "application/x-ndjson"
            "yaml", "yml" -> "application/yaml"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: DEFAULT_MIME_TYPE
        }
    }

    private fun displayNameFor(file: File): String {
        return if (file.canonicalFile == workspaceRoot()) {
            "workspace"
        } else {
            file.name
        }
    }

    private fun resolveUniqueChild(parent: File, displayName: String): File {
        val sanitized = sanitizeDisplayName(displayName)
        val baseName = sanitized.substringBeforeLast('.', sanitized)
        val extension = sanitized.substringAfterLast('.', missingDelimiterValue = "")
        var candidate = File(parent, sanitized)
        var suffix = 1
        while (candidate.exists()) {
            val nextName = if (extension.isBlank() || sanitized.startsWith('.')) {
                "$sanitized ($suffix)"
            } else {
                "$baseName ($suffix).$extension"
            }
            candidate = File(parent, nextName)
            suffix += 1
        }
        val canonicalCandidate = candidate.canonicalFile
        ensureWithinWorkspace(canonicalCandidate)
        return canonicalCandidate
    }

    private fun sanitizeDisplayName(displayName: String): String {
        return displayName
            .trim()
            .replace('/', '_')
            .replace('\\', '_')
            .ifBlank { "Untitled" }
    }

    private fun ensureWithinWorkspace(file: File) {
        val root = workspaceRoot()
        if (!isWithin(root, file)) {
            throw SecurityException("Document escapes workspace root: ${file.absolutePath}")
        }
    }

    private fun isWithin(parent: File, file: File): Boolean {
        val parentPath = parent.canonicalPath
        val filePath = file.canonicalPath
        return filePath == parentPath || filePath.startsWith("$parentPath/")
    }

    private fun resolveProjection(
        requested: Array<out String>?,
        defaultProjection: Array<String>
    ): Array<String> {
        return requested?.map { it }?.toTypedArray() ?: defaultProjection
    }
}
