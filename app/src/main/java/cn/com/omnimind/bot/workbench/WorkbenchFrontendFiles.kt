package cn.com.omnimind.bot.workbench

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.Instant

internal class WorkbenchFrontendFileStore(
    private val gson: Gson,
    private val projectDir: (String) -> File,
    private val nowIso: () -> String
) {
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type

    fun normalizeMarkdownFiles(value: Any?): List<Pair<String, String>> {
        if (value == null) return emptyList()
        if (value is Map<*, *>) {
            val map = asStringKeyMap(value)
            val directContent = map["content"] ?: map["source"] ?: map["text"] ?: map["markdown"]
            val directPath = map["path"] ?: map["relativePath"] ?: map["filePath"] ?: "index.md"
            if (directContent != null) {
                return listOf(markdownFileSpec(directPath, directContent))
            }
            val nested = map["files"] ?: map["items"]
            if (nested is Iterable<*>) {
                return normalizeMarkdownFiles(nested)
            }
            return map.mapNotNull { (path, content) ->
                if (path == "files" || path == "items") null else markdownFileSpec(path, content)
            }.distinctBy { it.first }
        }
        val raw = value as? Iterable<*> ?: return emptyList()
        return raw.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val file = asStringKeyMap(map)
            val path = file["path"] ?: file["relativePath"] ?: file["filePath"] ?: "index.md"
            val content = file["content"] ?: file["source"] ?: file["text"] ?: file["markdown"]
                ?: return@mapNotNull null
            markdownFileSpec(path, content)
        }.distinctBy { it.first }
    }

    fun writeMarkdownFiles(
        projectId: String,
        files: List<Pair<String, String>>
    ): List<Map<String, Any?>> {
        if (files.isEmpty()) return emptyList()
        val markdownDir = File(projectDir(projectId), "frontend/markdown")
        markdownDir.mkdirs()
        val root = markdownDir.canonicalFile
        val rootPrefix = root.path + File.separator
        val writtenAt = nowIso()
        val written = files.map { (relativePath, content) ->
            val target = File(root, relativePath).canonicalFile
            require(target.path == root.path || target.path.startsWith(rootPrefix)) {
                "Markdown source file path cannot escape frontend/markdown/."
            }
            target.parentFile?.mkdirs()
            target.writeText(content)
            linkedMapOf<String, Any?>(
                "path" to "frontend/markdown/$relativePath",
                "bytes" to content.toByteArray(Charsets.UTF_8).size,
                "updatedAt" to writtenAt
            )
        }
        writeMarkdownManifest(projectId)
        return written
    }

    fun readMarkdownPayload(
        projectId: String,
        includeSources: Boolean = true
    ): Map<String, Any?> {
        val markdownDir = File(projectDir(projectId), "frontend/markdown")
        if (!markdownDir.exists()) return emptyMap()
        val root = markdownDir.canonicalFile
        val sources = linkedMapOf<String, String>()
        val files = mutableListOf<Map<String, Any?>>()
        markdownDir.walkTopDown()
            .filter { it.isFile && it.name != "manifest.json" && it.name != "README.md" }
            .sortedBy { it.absolutePath }
            .forEach { file ->
                val relative = root.toPath()
                    .relativize(file.canonicalFile.toPath())
                    .toString()
                    .replace(File.separatorChar, '/')
                files += linkedMapOf<String, Any?>(
                    "path" to "frontend/markdown/$relative",
                    "relativePath" to relative,
                    "bytes" to file.length(),
                    "updatedAt" to Instant.ofEpochMilli(file.lastModified()).toString(),
                    "kind" to "source"
                )
                if (includeSources) {
                    sources[relative] = file.readText()
                }
            }
        if (files.isEmpty()) return emptyMap()
        val manifestFile = File(markdownDir, "manifest.json")
        val manifest = readManifest(manifestFile)
        val relativePaths = files.mapNotNull { it["relativePath"]?.toString() }
        val entryFile = manifest["entryFile"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: when {
                relativePaths.contains("index.md") -> "index.md"
                relativePaths.any { it.endsWith(".md") } -> relativePaths.first { it.endsWith(".md") }
                else -> relativePaths.firstOrNull().orEmpty()
            }
        val payload = linkedMapOf<String, Any?>(
            "runtime" to WORKBENCH_MARKDOWN_RENDERER,
            "renderer" to WORKBENCH_MARKDOWN_RENDERER,
            "entryFile" to entryFile,
            "files" to files,
            "manifest" to manifest
        )
        if (includeSources) {
            payload["sources"] = sources
        } else {
            payload["sourceCount"] = files.size
        }
        return payload
    }

    fun writeMarkdownManifest(projectId: String): List<Map<String, Any?>> {
        val markdownDir = File(projectDir(projectId), "frontend/markdown")
        markdownDir.mkdirs()
        val root = markdownDir.canonicalFile
        val files = markdownDir.walkTopDown()
            .filter { it.isFile && it.name != "manifest.json" }
            .map { file ->
                val relative = root.toPath()
                    .relativize(file.canonicalFile.toPath())
                    .toString()
                    .replace(File.separatorChar, '/')
                linkedMapOf<String, Any?>(
                    "path" to "frontend/markdown/$relative",
                    "relativePath" to relative,
                    "bytes" to file.length(),
                    "updatedAt" to Instant.ofEpochMilli(file.lastModified()).toString(),
                    "kind" to "source"
                )
            }
            .sortedBy { it["path"]?.toString().orEmpty() }
            .toList()
        val entryFile = files.firstOrNull { it["relativePath"] == "index.md" }?.get("relativePath")
            ?: files.firstOrNull {
                it["relativePath"]?.toString()?.endsWith(".md") == true
            }?.get("relativePath")
            ?: files.firstOrNull()?.get("relativePath")
        File(markdownDir, "manifest.json").writeText(
            gson.toJson(
                linkedMapOf(
                    "generatedAt" to nowIso(),
                    "runtimeBoundary" to "markdown_live_runtime",
                    "entryFile" to entryFile,
                    "files" to files
                )
            )
        )
        return files
    }

    fun normalizeHtmlFiles(value: Any?): List<Pair<String, String>> {
        if (value == null) return emptyList()
        if (value is Map<*, *>) {
            val map = asStringKeyMap(value)
            val directPath = map["path"] ?: map["relativePath"] ?: map["filePath"]
            val directContent = map["content"] ?: map["source"] ?: map["text"]
            if (directPath != null && directContent != null) {
                return listOf(htmlFileSpec(directPath, directContent))
            }
            val nested = map["files"] ?: map["items"]
            if (nested is Iterable<*>) {
                return normalizeHtmlFiles(nested)
            }
            return map.mapNotNull { (path, content) ->
                if (path == "files" || path == "items") null else htmlFileSpec(path, content)
            }.distinctBy { it.first }
        }
        val raw = value as? Iterable<*> ?: return emptyList()
        return raw.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val file = asStringKeyMap(map)
            val path = file["path"] ?: file["relativePath"] ?: file["filePath"] ?: return@mapNotNull null
            val content = file["content"] ?: file["source"] ?: file["text"] ?: return@mapNotNull null
            htmlFileSpec(path, content)
        }.distinctBy { it.first }
    }

    fun writeHtmlFiles(
        projectId: String,
        files: List<Pair<String, String>>
    ): List<Map<String, Any?>> {
        if (files.isEmpty()) return emptyList()
        val htmlDir = File(projectDir(projectId), "frontend/html")
        htmlDir.mkdirs()
        val root = htmlDir.canonicalFile
        val rootPrefix = root.path + File.separator
        val writtenAt = nowIso()
        val written = files.map { (relativePath, content) ->
            val target = File(root, relativePath).canonicalFile
            require(target.path == root.path || target.path.startsWith(rootPrefix)) {
                "HTML source file path cannot escape frontend/html/."
            }
            target.parentFile?.mkdirs()
            target.writeText(content)
            linkedMapOf<String, Any?>(
                "path" to "frontend/html/$relativePath",
                "bytes" to content.toByteArray(Charsets.UTF_8).size,
                "updatedAt" to writtenAt
            )
        }
        writeHtmlManifest(projectId)
        return written
    }

    fun readHtmlPayload(
        projectId: String,
        includeSources: Boolean = true
    ): Map<String, Any?> {
        val htmlDir = File(projectDir(projectId), "frontend/html")
        if (!htmlDir.exists()) return emptyMap()
        val root = htmlDir.canonicalFile
        val sources = linkedMapOf<String, String>()
        val assets = mutableListOf<Map<String, Any?>>()
        htmlDir.walkTopDown()
            .filter { it.isFile && it.name != "manifest.json" && it.name != "README.md" }
            .sortedBy { it.absolutePath }
            .forEach { file ->
                val relative = root.toPath()
                    .relativize(file.canonicalFile.toPath())
                    .toString()
                    .replace(File.separatorChar, '/')
                val payload = linkedMapOf<String, Any?>(
                    "path" to "frontend/html/$relative",
                    "relativePath" to relative,
                    "bytes" to file.length(),
                    "updatedAt" to Instant.ofEpochMilli(file.lastModified()).toString()
                )
                if (isTextHtmlFile(file)) {
                    if (includeSources) {
                        sources[relative] = file.readText()
                    }
                    assets += payload + ("kind" to "source")
                } else {
                    assets += payload + ("kind" to "asset")
                }
            }
        if (assets.isEmpty()) return emptyMap()
        val manifestFile = File(htmlDir, "manifest.json")
        val manifest = readManifest(manifestFile)
        val sourcePaths = assets
            .filter { it["kind"] == "source" }
            .mapNotNull { it["relativePath"]?.toString() }
        val entryFile = manifest["entryFile"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: when {
                sourcePaths.contains("index.html") -> "index.html"
                sourcePaths.any { it.endsWith(".html") } -> sourcePaths.first { it.endsWith(".html") }
                else -> sourcePaths.firstOrNull().orEmpty()
            }
        val entry = entryFile.takeIf { it.isNotEmpty() }?.let { File(root, it).canonicalFile }
        val entryPath = entry?.takeIf { file ->
            val rootPrefix = root.path + File.separator
            file.path == root.path || file.path.startsWith(rootPrefix)
        }?.absolutePath.orEmpty()
        val payload = linkedMapOf<String, Any?>(
            "runtime" to WORKBENCH_HTML_RENDERER,
            "renderer" to WORKBENCH_HTML_RENDERER,
            "entryFile" to entryFile,
            "entryPath" to entryPath,
            "assets" to assets,
            "manifest" to manifest
        )
        if (includeSources) {
            payload["sources"] = sources
        } else {
            payload["sourceCount"] = sourcePaths.size
        }
        return payload
    }

    fun writeHtmlManifest(projectId: String): List<Map<String, Any?>> {
        val htmlDir = File(projectDir(projectId), "frontend/html")
        htmlDir.mkdirs()
        val root = htmlDir.canonicalFile
        val files = htmlDir.walkTopDown()
            .filter { it.isFile && it.name != "manifest.json" }
            .map { file ->
                val relative = root.toPath()
                    .relativize(file.canonicalFile.toPath())
                    .toString()
                    .replace(File.separatorChar, '/')
                linkedMapOf<String, Any?>(
                    "path" to "frontend/html/$relative",
                    "relativePath" to relative,
                    "bytes" to file.length(),
                    "updatedAt" to Instant.ofEpochMilli(file.lastModified()).toString(),
                    "kind" to if (isTextHtmlFile(file)) "source" else "asset"
                )
            }
            .sortedBy { it["path"]?.toString().orEmpty() }
            .toList()
        val entryFile = files.firstOrNull { it["relativePath"] == "index.html" }?.get("relativePath")
            ?: files.firstOrNull {
                it["relativePath"]?.toString()?.endsWith(".html") == true
            }?.get("relativePath")
            ?: files.firstOrNull()?.get("relativePath")
        File(htmlDir, "manifest.json").writeText(
            gson.toJson(
                linkedMapOf(
                    "generatedAt" to nowIso(),
                    "runtimeBoundary" to "html_webview_live_runtime",
                    "entryFile" to entryFile,
                    "files" to files,
                    "security" to linkedMapOf(
                        "nativeBridge" to "Project Tool whitelist only",
                        "externalNavigation" to "blocked in Workbench Display",
                        "remoteSubresources" to "allowed for demo/CDN; prefer vendored assets for production"
                    )
                )
            )
        )
        return files
    }

    fun normalizeFlutterFiles(value: Any?): List<Pair<String, String>> {
        if (value == null) return emptyList()
        if (value is Map<*, *>) {
            val map = asStringKeyMap(value)
            val directPath = map["path"] ?: map["relativePath"] ?: map["filePath"]
            val directContent = map["content"] ?: map["source"] ?: map["text"]
            if (directPath != null && directContent != null) {
                return listOf(flutterFileSpec(directPath, directContent))
            }
            val nested = map["files"] ?: map["items"]
            if (nested is Iterable<*>) {
                return normalizeFlutterFiles(nested)
            }
            return map.mapNotNull { (path, content) ->
                if (path == "files" || path == "items") null else flutterFileSpec(path, content)
            }.distinctBy { it.first }
        }
        val raw = value as? Iterable<*> ?: return emptyList()
        return raw.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val file = asStringKeyMap(map)
            val path = file["path"] ?: file["relativePath"] ?: file["filePath"] ?: return@mapNotNull null
            val content = file["content"] ?: file["source"] ?: file["text"] ?: return@mapNotNull null
            flutterFileSpec(path, content)
        }.distinctBy { it.first }
    }

    fun writeFlutterFiles(
        projectId: String,
        files: List<Pair<String, String>>
    ): List<Map<String, Any?>> {
        if (files.isEmpty()) return emptyList()
        val flutterDir = File(projectDir(projectId), "frontend/flutter")
        flutterDir.mkdirs()
        val root = flutterDir.canonicalFile
        val rootPrefix = root.path + File.separator
        val writtenAt = nowIso()
        val written = files.map { (relativePath, content) ->
            val target = File(root, relativePath).canonicalFile
            require(target.path == root.path || target.path.startsWith(rootPrefix)) {
                "Flutter source file path cannot escape frontend/flutter/."
            }
            target.parentFile?.mkdirs()
            target.writeText(content)
            linkedMapOf<String, Any?>(
                "path" to "frontend/flutter/$relativePath",
                "bytes" to content.toByteArray(Charsets.UTF_8).size,
                "updatedAt" to writtenAt
            )
        }
        writeFlutterManifest(projectId)
        return written
    }

    fun readFlutterPayload(
        projectId: String,
        includeSources: Boolean = true
    ): Map<String, Any?> {
        val flutterDir = File(projectDir(projectId), "frontend/flutter")
        if (!flutterDir.exists()) return emptyMap()
        val root = flutterDir.canonicalFile
        val sources = linkedMapOf<String, String>()
        val files = mutableListOf<Map<String, Any?>>()
        flutterDir.walkTopDown()
            .filter { it.isFile && it.name != "manifest.json" && it.name != "README.md" }
            .sortedBy { it.absolutePath }
            .forEach { file ->
                val relative = root.toPath()
                    .relativize(file.canonicalFile.toPath())
                    .toString()
                    .replace(File.separatorChar, '/')
                files += linkedMapOf<String, Any?>(
                    "path" to "frontend/flutter/$relative",
                    "relativePath" to relative,
                    "bytes" to file.length(),
                    "updatedAt" to Instant.ofEpochMilli(file.lastModified()).toString(),
                    "kind" to "source"
                )
                if (includeSources) {
                    sources[relative] = file.readText()
                }
            }
        if (files.isEmpty()) return emptyMap()
        val manifestFile = File(flutterDir, "manifest.json")
        val manifest = readManifest(manifestFile)
        val relativePaths = files.mapNotNull { it["relativePath"]?.toString() }
        val entryFile = manifest["entryFile"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: when {
                relativePaths.contains("lib/main.dart") -> "lib/main.dart"
                relativePaths.contains("main.dart") -> "main.dart"
                relativePaths.contains("frontend/flutter/lib/main.dart") -> "frontend/flutter/lib/main.dart"
                relativePaths.any { it.endsWith("/main.dart") } -> relativePaths.first { it.endsWith("/main.dart") }
                else -> relativePaths.firstOrNull().orEmpty()
            }
        val entryClass = manifest["entryClass"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "OobProjectWidget"
        val payload = linkedMapOf<String, Any?>(
            "runtime" to "flutter_eval",
            "entryFile" to entryFile,
            "entryClass" to entryClass,
            "files" to files,
            "manifest" to manifest
        )
        if (includeSources) {
            payload["sources"] = sources
        } else {
            payload["sourceCount"] = files.size
        }
        return payload
    }

    fun writeFlutterManifest(projectId: String): List<Map<String, Any?>> {
        val flutterDir = File(projectDir(projectId), "frontend/flutter")
        flutterDir.mkdirs()
        val root = flutterDir.canonicalFile
        val files = flutterDir.walkTopDown()
            .filter { it.isFile && it.name != "manifest.json" }
            .map { file ->
                val relative = root.toPath()
                    .relativize(file.canonicalFile.toPath())
                    .toString()
                    .replace(File.separatorChar, '/')
                linkedMapOf<String, Any?>(
                    "path" to "frontend/flutter/$relative",
                    "bytes" to file.length(),
                    "updatedAt" to Instant.ofEpochMilli(file.lastModified()).toString()
                )
            }
            .sortedBy { it["path"]?.toString().orEmpty() }
            .toList()
        File(flutterDir, "manifest.json").writeText(
            gson.toJson(
                linkedMapOf(
                    "generatedAt" to nowIso(),
                    "runtimeBoundary" to "flutter_eval_live_runtime",
                    "entryFile" to when {
                        files.any { it["path"] == "frontend/flutter/lib/main.dart" } -> "lib/main.dart"
                        files.any { it["path"] == "frontend/flutter/main.dart" } -> "main.dart"
                        else -> "lib/main.dart"
                    },
                    "entryClass" to "OobProjectWidget",
                    "files" to files
                )
            )
        )
        return files
    }

    private fun markdownFileSpec(path: Any?, content: Any?): Pair<String, String> {
        val normalized = cleanMarkdownPath(path?.toString().orEmpty())
        return normalized to content?.toString().orEmpty()
    }

    private fun cleanMarkdownPath(rawPath: String): String {
        val normalized = rawPath.replace('\\', '/')
            .trim()
            .removePrefix("/")
            .removePrefix("frontend/markdown/")
            .removePrefix("markdown/")
            .ifBlank { "index.md" }
        require(!normalized.contains(":")) { "Markdown source file path must be relative." }
        val parts = normalized.split('/').filter { it.isNotBlank() }
        require(parts.none { it == ".." }) { "Markdown source file path cannot escape frontend/markdown/." }
        require(parts.lastOrNull() != "manifest.json") {
            "frontend/markdown/manifest.json is generated by OOB."
        }
        val leaf = parts.lastOrNull().orEmpty().lowercase()
        require(leaf.endsWith(".md") || leaf.endsWith(".markdown") || leaf.endsWith(".txt")) {
            "Markdown Display source files must end with .md, .markdown, or .txt."
        }
        return parts.joinToString("/")
    }

    private fun htmlFileSpec(path: Any?, content: Any?): Pair<String, String> {
        val normalized = cleanHtmlPath(path?.toString().orEmpty())
        return normalized to content?.toString().orEmpty()
    }

    private fun cleanHtmlPath(rawPath: String): String {
        var normalized = rawPath.replace('\\', '/').trim().removePrefix("/")
        require(normalized.isNotEmpty()) { "HTML source file path is required." }
        require(!normalized.contains(":")) { "HTML source file path must be relative." }
        val parts = normalized.split('/').filter { it.isNotBlank() }
        require(parts.none { it == ".." }) { "HTML source file path cannot escape frontend/html/." }
        require(parts.lastOrNull() != "manifest.json") {
            "frontend/html/manifest.json is generated by OOB."
        }
        val prefixes = listOf("frontend/html/", "frontend\\html\\", "html/")
        for (prefix in prefixes) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.removePrefix(prefix)
                break
            }
        }
        return normalized.split('/').filter { it.isNotBlank() }.joinToString("/")
    }

    private fun isTextHtmlFile(file: File): Boolean {
        val name = file.name.lowercase()
        return listOf(
            ".html",
            ".htm",
            ".css",
            ".js",
            ".mjs",
            ".json",
            ".svg",
            ".txt",
            ".md",
            ".csv"
        ).any { name.endsWith(it) }
    }

    private fun flutterFileSpec(path: Any?, content: Any?): Pair<String, String> {
        val normalized = cleanFlutterPath(path?.toString().orEmpty())
        return normalized to content?.toString().orEmpty()
    }

    private fun cleanFlutterPath(rawPath: String): String {
        val normalized = rawPath.replace('\\', '/')
            .trim()
            .removePrefix("/")
            .removePrefix("frontend/flutter/")
            .removePrefix("flutter/")
        require(normalized.isNotEmpty()) { "Flutter source file path is required." }
        require(!normalized.contains(":")) { "Flutter source file path must be relative." }
        val parts = normalized.split('/').filter { it.isNotBlank() }
        require(parts.none { it == ".." }) { "Flutter source file path cannot escape frontend/flutter/." }
        require(parts.lastOrNull() != "manifest.json") {
            "frontend/flutter/manifest.json is generated by OOB."
        }
        return parts.joinToString("/")
    }

    private fun readManifest(file: File): Map<String, Any?> {
        return if (file.exists()) {
            runCatching {
                gson.fromJson<Map<String, Any?>>(file.readText(), mapType)
            }.getOrNull().orEmpty()
        } else {
            emptyMap()
        }
    }

    private fun asStringKeyMap(value: Any?): Map<String, Any?> {
        val raw = value as? Map<*, *> ?: return emptyMap()
        return raw.entries.associate { entry -> entry.key.toString() to entry.value }
    }
}
