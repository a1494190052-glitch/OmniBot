package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import com.ai.assistance.operit.terminal.TerminalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal data class PreparedOmniFlowRuntime(
    val manifest: OmniFlowRuntimeManifest,
    val shellSitePackagesPath: String,
)

internal object OmniFlowEmbeddedRuntime {
    private const val ASSET_ROOT = "omniflow-runtime"
    private const val MANIFEST_ASSET = "$ASSET_ROOT/manifest.properties"
    private const val BUNDLE_ASSET = "$ASSET_ROOT/bundle.zip"
    private const val INSTALL_MARKER = ".installed"
    private val prepareMutex = Mutex()

    @Volatile
    private var prepared: PreparedOmniFlowRuntime? = null

    suspend fun prepare(context: Context): PreparedOmniFlowRuntime {
        prepared?.let { return it }
        return prepareMutex.withLock {
            prepared?.let { return@withLock it }
            val appContext = context.applicationContext
            val manifest = appContext.assets.open(MANIFEST_ASSET).use(::parseOmniFlowRuntimeManifest)
            val targetDirectory = withContext(Dispatchers.IO) {
                installBundle(appContext, manifest)
                    .also { alignPythonStoreWithRuntime(appContext, manifest) }
            }
            ensurePython(appContext, manifest.pythonVersion)
            PreparedOmniFlowRuntime(
                manifest = manifest,
                shellSitePackagesPath = "/workspace/.omnibot/runtime/omniflow/${manifest.version}/site-packages",
            ).also { prepared = it }
        }
    }

    private fun installBundle(context: Context, manifest: OmniFlowRuntimeManifest): File {
        val runtimeRoot = File(
            AgentWorkspaceManager.internalRootDirectory(context),
            "runtime/omniflow",
        ).apply { mkdirs() }
        val target = File(runtimeRoot, manifest.version)
        val marker = File(target, INSTALL_MARKER)
        if (marker.readTextOrNull() == manifest.bundleSha256 && requiredFilesExist(target, manifest)) {
            return target
        }
        val bundle = File(context.cacheDir, "omniflow-runtime-${manifest.version}.zip")
        context.assets.open(BUNDLE_ASSET).use { input ->
            bundle.outputStream().buffered().use(input::copyTo)
        }
        val actualSha256 = sha256(bundle.inputStream())
        require(actualSha256 == manifest.bundleSha256) {
            "omniflow_runtime_bundle_checksum_mismatch"
        }
        val installing = File(runtimeRoot, ".${manifest.version}.installing")
        installing.deleteRecursively()
        extractOmniFlowRuntimeBundle(bundle.inputStream(), installing)
        require(requiredFilesExist(installing, manifest)) { "omniflow_runtime_bundle_incomplete" }
        File(installing, INSTALL_MARKER).writeText(manifest.bundleSha256)
        target.deleteRecursively()
        require(installing.renameTo(target)) { "omniflow_runtime_install_rename_failed" }
        bundle.delete()
        runtimeRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name != manifest.version && !it.name.startsWith('.') }
            .sortedByDescending(File::lastModified)
            .drop(1)
            .forEach(File::deleteRecursively)
        return target
    }

    private fun alignPythonStoreWithRuntime(
        context: Context,
        manifest: OmniFlowRuntimeManifest,
    ) {
        val storeDirectory = File(
            AgentWorkspaceManager.internalRootDirectory(context),
            "omniflow",
        ).apply { mkdirs() }
        alignOmniFlowStoreWithRuntime(
            storeDirectory = storeDirectory,
            runtimeFingerprint = manifest.runtimeFingerprint(),
        )
    }

    private suspend fun ensurePython(context: Context, expectedVersion: String) {
        val command = """
            expected='$expectedVersion'
            python_ready() {
              command -v python3 >/dev/null 2>&1 &&
              python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])' | grep -qx "${'$'}expected" &&
              test -e /usr/lib/libstdc++.so.6
            }
            if ! python_ready; then
              apk update >/dev/null && apk add --no-cache python3 libstdc++ >/dev/null
            fi
            python_ready
        """.trimIndent()
        val result = TerminalManager.getInstance(context).executeHiddenCommand(
            command = command,
            executorKey = "omniflow-python-runtime",
            timeoutMs = 5 * 60_000L,
        )
        require(result.isOk && result.exitCode == 0) {
            result.error.takeIf(String::isNotBlank)
                ?: result.output.takeLast(800).trim().ifBlank { "omniflow_python_runtime_unavailable" }
        }
    }

    private fun requiredFilesExist(
        root: File,
        manifest: OmniFlowRuntimeManifest,
    ): Boolean = listOf(
        "site-packages/oob_omniflow_bridge.py",
        "site-packages/omniflow/bridge.py",
        "site-packages/omnitransfer/runtime.py",
        "site-packages/omnitransfer/numpy_matcher.py",
        "site-packages/omnitransfer/${manifest.omniTransferCheckpoint}",
        "site-packages/numpy/__init__.py",
        "site-packages/schemas/oob/oob_canonical_actions.v1.json",
        "site-packages/schemas/oob/omniflow_canonical_run_log.v1.json",
        "site-packages/schemas/oob/omniflow_function.v2.json",
        "site-packages/schemas/oob/omniflow_checker_rule.v1.json",
        "site-packages/schemas/oob/omniflow_android_bridge.v2.json",
    ).all { File(root, it).isFile }

    private fun File.readTextOrNull(): String? =
        takeIf(File::isFile)?.runCatching(File::readText)?.getOrNull()?.trim()

    private fun OmniFlowRuntimeManifest.runtimeFingerprint(): String = listOf(
        version,
        protocol,
        bridgeContractSha256,
        omniFlowCommit,
        omniFlowSourceSha256,
        omniTransferCommit,
        omniTransferSourceSha256,
        omniTransferCheckpoint,
    ).joinToString(":")
}

internal fun alignOmniFlowStoreWithRuntime(
    storeDirectory: File,
    runtimeFingerprint: String,
) {
    storeDirectory.mkdirs()
    val marker = File(storeDirectory, ".runtime_fingerprint")
    if (marker.takeIf(File::isFile)?.readText()?.trim() == runtimeFingerprint) return
    File(storeDirectory, "omniflow.json.tmp").delete()
    marker.writeText(runtimeFingerprint)
}
