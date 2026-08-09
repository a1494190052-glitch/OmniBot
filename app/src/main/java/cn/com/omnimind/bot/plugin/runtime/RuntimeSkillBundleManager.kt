package cn.com.omnimind.bot.plugin.runtime

import android.content.Context
import android.content.res.AssetManager
import android.os.SystemClock
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.SkillIndexEntry
import cn.com.omnimind.bot.agent.SkillIndexService
import cn.com.omnimind.bot.termux.TermuxCommandBuilder
import com.ai.assistance.operit.terminal.TerminalManager
import cn.com.omnimind.baselib.util.OmniLog
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.UUID

data class RuntimeSkillSpec(
    val id: String,
    val packagedAssetPath: String,
    val schemaAssetPath: String? = null,
    val markerFile: String = "PACKAGED_RUNTIME_SKILL",
    val bootstrapScript: String = "scripts/bootstrap_runtime.py",
    val runtimeDataPath: String = "scripts/runtime/.runtime",
    val prebuiltRuntimeArchive: String? = null,
    val prebuiltRuntimeSha256: String? = null,
    val bootstrapTimeoutSeconds: Int = 15 * 60,
) {
    internal fun validated(): RuntimeSkillSpec {
        require(id.matches(Regex("^[a-z0-9][a-z0-9-]*$"))) {
            "Invalid runtime skill id: $id"
        }
        requireSafeRelativePath(packagedAssetPath, "packagedAssetPath")
        schemaAssetPath?.let { requireSafeRelativePath(it, "schemaAssetPath") }
        requireSafeRelativePath(markerFile, "markerFile")
        requireSafeRelativePath(bootstrapScript, "bootstrapScript")
        requireSafeRelativePath(runtimeDataPath, "runtimeDataPath")
        prebuiltRuntimeArchive?.let { requireSafeRelativePath(it, "prebuiltRuntimeArchive") }
        require(prebuiltRuntimeArchive.isNullOrBlank() == prebuiltRuntimeSha256.isNullOrBlank()) {
            "Runtime skill prebuilt archive and SHA-256 must be configured together"
        }
        prebuiltRuntimeSha256?.let { digest ->
            require(digest.matches(Regex("^[a-f0-9]{64}$"))) {
                "Runtime skill prebuilt archive SHA-256 is invalid"
            }
        }
        require(bootstrapTimeoutSeconds in 1..3600) {
            "Invalid runtime bootstrap timeout: $bootstrapTimeoutSeconds"
        }
        return this
    }

    private fun requireSafeRelativePath(value: String, field: String) {
        require(value.isNotBlank() && !value.startsWith('/')) {
            "Runtime skill $field must be relative"
        }
        require(value.replace('\\', '/').split('/').none { it == ".." }) {
            "Runtime skill $field cannot escape its root"
        }
    }
}

data class RuntimeSkillLocation(
    val androidRoot: File,
    val shellRoot: String,
    val source: String,
)

internal fun packagedRuntimeSkillNeedsReplacement(
    refresh: Boolean,
    installedMarker: String?,
    packagedMarker: String,
): Boolean = refresh || installedMarker != packagedMarker

internal fun shouldSyncOfficialSkillsRepository(refresh: Boolean): Boolean = refresh

class RuntimeSkillBundleManager(
    context: Context,
    private val spec: RuntimeSkillSpec,
) {
    private val tag = "[RuntimeSkillBundleManager:${spec.id}]"
    private val appContext = context.applicationContext

    suspend fun resolve(refresh: Boolean): RuntimeSkillLocation {
        val startedAt = SystemClock.elapsedRealtime()
        OmniLog.i(tag, "resolve_start refresh=$refresh")
        val workspace = AgentWorkspaceManager(appContext)
        val skills = SkillIndexService(appContext, workspace)
        var candidates = installedCandidates(skills)
        if (shouldSyncOfficialSkillsRepository(refresh)) {
            runCatching { skills.syncOfficialSkillsRepository() }
            candidates = installedCandidates(skills)
        }

        val packagedMarker = packagedMarker()
        val hasMarketCandidate = candidates.any { it.source == OFFICIAL_SOURCE }
        val marketCandidate = candidates.firstOrNull(::isCompleteMarketCandidate)
        if (!hasMarketCandidate &&
            removeOutdatedPackagedCandidates(skills, candidates, refresh, packagedMarker)
        ) {
            candidates = installedCandidates(skills)
        }

        val selected = marketCandidate
            ?: packagedCandidate(candidates)
            ?: candidates
                .filterNot { it.source == OFFICIAL_SOURCE }
                .let(::preferredCandidate)
            ?: installPackaged(skills, packagedMarker)
        if (!selected.enabled) {
            skills.setSkillEnabled(selected.id, true)
        }
        return RuntimeSkillLocation(
            androidRoot = File(selected.rootPath).canonicalFile,
            shellRoot = selected.shellRootPath,
            source = selected.source,
        ).also {
            OmniLog.i(
                tag,
                "resolve_ready durationMs=${SystemClock.elapsedRealtime() - startedAt} " +
                    "source=${it.source}",
            )
        }
    }

    fun resolvePackaged(refresh: Boolean): RuntimeSkillLocation {
        val workspace = AgentWorkspaceManager(appContext)
        val skills = SkillIndexService(appContext, workspace)
        val packagedMarker = packagedMarker()
        removeOutdatedPackagedCandidates(
            skills = skills,
            candidates = installedCandidates(skills),
            refresh = refresh,
            packagedMarker = packagedMarker,
        )
        val selected = packagedCandidate(installedCandidates(skills)) ?: installPackaged(
            skills = skills,
            packagedMarker = packagedMarker,
        )
        if (!selected.enabled) {
            skills.setSkillEnabled(selected.id, true)
        }
        return RuntimeSkillLocation(
            androidRoot = File(selected.rootPath).canonicalFile,
            shellRoot = selected.shellRootPath,
            source = selected.source,
        )
    }

    fun setEnabled(enabled: Boolean) {
        val workspace = AgentWorkspaceManager(appContext)
        val skills = SkillIndexService(appContext, workspace)
        val entry = preferredCandidate(installedCandidates(skills))
            ?: if (enabled) installPackaged(skills) else return
        if (entry.enabled != enabled) {
            skills.setSkillEnabled(entry.id, enabled)
        }
    }

    suspend fun bootstrap(location: RuntimeSkillLocation) {
        val startedAt = SystemClock.elapsedRealtime()
        OmniLog.i(tag, "bootstrap_start source=${location.source}")
        val skillRoot = TermuxCommandBuilder.quoteForShell(location.shellRoot)
        val script = TermuxCommandBuilder.quoteForShell(
            "${location.shellRoot}/${spec.bootstrapScript}"
        )
        val command = """
            set -eu
            SKILL_ROOT=$skillRoot
            python3 $script --skill-root "${'$'}SKILL_ROOT"
        """.trimIndent()
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = command,
            executorKey = "runtime-skill-${spec.id}",
            timeoutMs = spec.bootstrapTimeoutSeconds * 1000L,
            onOutputChunk = { chunk ->
                chunk.lineSequence()
                    .map(String::trim)
                    .filter { it.startsWith("OMNIFLOW_STAGE=") }
                    .forEach { stage -> OmniLog.i(tag, "bootstrap_$stage") }
            },
        )
        if (!result.isOk || result.exitCode != 0) {
            OmniLog.e(
                tag,
                "bootstrap_failed exitCode=${result.exitCode} " +
                    "state=${result.state} detail=" +
                    result.error.ifBlank { result.output.takeLast(1_200).trim() },
            )
        }
        require(result.isOk && result.exitCode == 0) {
            result.error.takeIf(String::isNotBlank)
                ?: result.output.takeLast(1_200).trim()
                    .ifBlank { "runtime_skill_bootstrap_failed:${spec.id}" }
        }
        OmniLog.i(
            tag,
            "bootstrap_ready durationMs=${SystemClock.elapsedRealtime() - startedAt}",
        )
    }

    fun reclaim() {
        val workspace = AgentWorkspaceManager(appContext)
        val skills = SkillIndexService(appContext, workspace)
        val entry = installedCandidates(skills).firstOrNull() ?: return
        if (isPackaged(entry.rootPath)) {
            require(skills.deleteSkill(entry.id)) { "runtime_skill_delete_failed:${spec.id}" }
            return
        }
        if (entry.enabled) {
            skills.setSkillEnabled(entry.id, false)
        }
        val runtime = File(entry.rootPath, spec.runtimeDataPath)
        require(!runtime.exists() || runtime.deleteRecursively()) {
            "runtime_skill_reclaim_failed:${spec.id}"
        }
    }

    private fun installedCandidates(skills: SkillIndexService): List<SkillIndexEntry> =
        skills.listSkillsForManagement().filter { it.id == spec.id && it.installed }

    private fun preferredCandidate(candidates: List<SkillIndexEntry>): SkillIndexEntry? =
        candidates.minByOrNull { candidate ->
            when {
                candidate.source == OFFICIAL_SOURCE -> 0
                !isPackaged(candidate.rootPath) -> 1
                else -> 2
            }
        }

    private fun packagedCandidate(candidates: List<SkillIndexEntry>): SkillIndexEntry? =
        candidates.firstOrNull { candidate -> isPackaged(candidate.rootPath) }

    private fun isCompleteMarketCandidate(candidate: SkillIndexEntry): Boolean {
        if (candidate.source != OFFICIAL_SOURCE) return false
        val root = File(candidate.rootPath)
        return File(root, "scripts/runtime/python/omniflow/bridge.py").isFile &&
            File(root, spec.runtimeDataPath).resolve("installed.json").isFile
    }

    private fun installPackaged(
        skills: SkillIndexService,
        packagedMarker: String = packagedMarker(),
    ): SkillIndexEntry =
        File(appContext.cacheDir, "runtime-skill-${spec.id}-${UUID.randomUUID()}").let { temporary ->
            val skillSource = File(temporary, spec.id)
            try {
                copyAssetTree(appContext.assets, spec.packagedAssetPath, skillSource)
                spec.prebuiltRuntimeArchive?.let { archivePath ->
                    unpackVerifiedPrebuiltRuntime(
                        archive = File(skillSource, archivePath),
                        target = File(skillSource, "scripts/runtime"),
                        expectedSha256 = requireNotNull(spec.prebuiltRuntimeSha256),
                        runtimeId = spec.id,
                    )
                }
                File(skillSource, spec.markerFile).writeText(packagedMarker)
                spec.schemaAssetPath?.let { assetPath ->
                    copyAssetTree(appContext.assets, assetPath, File(skillSource, "schemas"))
                }
                skills.installSkillFromDirectory(skillSource.absolutePath)
            } finally {
                temporary.deleteRecursively()
            }
        }

    private fun isPackaged(rootPath: String): Boolean =
        File(rootPath, spec.markerFile).isFile

    private fun installedMarker(rootPath: String): String? =
        File(rootPath, spec.markerFile).takeIf(File::isFile)?.readText()?.trim()

    private fun removeOutdatedPackagedCandidates(
        skills: SkillIndexService,
        candidates: List<SkillIndexEntry>,
        refresh: Boolean,
        packagedMarker: String,
    ): Boolean {
        val outdated = candidates.filter { candidate ->
            isPackaged(candidate.rootPath) && packagedRuntimeSkillNeedsReplacement(
                refresh = refresh,
                installedMarker = installedMarker(candidate.rootPath),
                packagedMarker = packagedMarker,
            )
        }
        outdated.forEach { candidate ->
            val root = File(candidate.rootPath)
            require(!root.exists() || root.deleteRecursively()) {
                "runtime_skill_upgrade_delete_failed:${spec.id}"
            }
        }
        if (outdated.isNotEmpty() && installedCandidates(skills).isEmpty()) {
            skills.deleteSkill(spec.id)
        }
        return outdated.isNotEmpty()
    }

    private fun packagedMarker(): String =
        appContext.assets.open("${spec.packagedAssetPath}/${spec.markerFile}")
            .bufferedReader()
            .use { it.readText().trim() }
            .also { marker -> require(marker.isNotEmpty()) { "Runtime skill marker is empty: ${spec.id}" } }

    private fun copyAssetTree(
        assets: AssetManager,
        assetPath: String,
        target: File,
    ) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                target.outputStream().use(input::copyTo)
            }
            return
        }
        target.mkdirs()
        children.forEach { child ->
            copyAssetTree(assets, "$assetPath/$child", File(target, child))
        }
    }

    private companion object {
        const val OFFICIAL_SOURCE = "official"
    }
}

internal fun unpackVerifiedPrebuiltRuntime(
    archive: File,
    target: File,
    expectedSha256: String,
    runtimeId: String,
) {
    require(archive.isFile) { "prebuilt_runtime_archive_missing:$runtimeId" }
    require(sha256Hex(archive) == expectedSha256) {
        "prebuilt_runtime_archive_checksum_mismatch:$runtimeId"
    }
    val canonicalTarget = target.canonicalFile
    ZipInputStream(archive.inputStream().buffered()).use { input ->
        while (true) {
            val entry = input.nextEntry ?: break
            val output = File(canonicalTarget, entry.name).canonicalFile
            require(
                output == canonicalTarget ||
                    output.path.startsWith(canonicalTarget.path + File.separator)
            ) {
                "prebuilt_runtime_archive_unsafe_entry:${entry.name}"
            }
            if (entry.isDirectory) {
                output.mkdirs()
            } else {
                output.parentFile?.mkdirs()
                output.outputStream().buffered().use(input::copyTo)
            }
            input.closeEntry()
        }
    }
    require(File(canonicalTarget, "python/omniflow/bridge.py").isFile) {
        "prebuilt_runtime_archive_incomplete:$runtimeId"
    }
    require(File(canonicalTarget, ".runtime/installed.json").isFile) {
        "prebuilt_runtime_archive_manifest_missing:$runtimeId"
    }
    check(archive.delete() || !archive.exists()) {
        "prebuilt_runtime_archive_cleanup_failed:$runtimeId"
    }
}

private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
