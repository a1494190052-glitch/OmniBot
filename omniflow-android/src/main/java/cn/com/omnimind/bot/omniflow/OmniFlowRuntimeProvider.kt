package cn.com.omnimind.bot.omniflow

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PreparedOmniFlowRuntime(
    val manifest: OmniFlowRuntimeManifest,
    val shellSitePackagesPath: String,
    val source: String,
)

class OmniFlowRuntimeProvider {
    private val prepareMutex = Mutex()

    @Volatile
    private var prepared: PreparedOmniFlowRuntime? = null

    suspend fun prepare(
        context: Context,
        platform: OmniFlowPlatform,
    ): PreparedOmniFlowRuntime {
        prepared?.let { return it }
        return prepareMutex.withLock {
            prepared?.let { return@withLock it }
            val appContext = context.applicationContext
            val runtime = withContext(Dispatchers.IO) {
                val source = resolveSource(appContext)
                val manifest = source.openManifest().use(::parseOmniFlowRuntimeManifest)
                installBundle(appContext, manifest, source.openBundle)
                alignPythonStoreWithRuntime(appContext, manifest)
                PreparedOmniFlowRuntime(
                    manifest = manifest,
                    shellSitePackagesPath =
                        "/workspace/.omnibot/runtime/omniflow/${manifest.version}/site-packages",
                    source = source.name,
                )
            }
            platform.ensurePython(appContext, runtime.manifest.pythonVersion)
            runtime.also { prepared = it }
        }
    }

    suspend fun invalidate() {
        prepareMutex.withLock {
            prepared = null
        }
    }

    private fun resolveSource(context: Context): RuntimeSource {
        val overrideDirectory = File(context.filesDir, DEBUG_OVERRIDE_DIRECTORY)
        val overrideManifest = File(overrideDirectory, MANIFEST_FILE)
        val overrideBundle = File(overrideDirectory, BUNDLE_FILE)
        return when (
            selectOmniFlowRuntimeSource(
                debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                overrideManifestExists = overrideManifest.isFile,
                overrideBundleExists = overrideBundle.isFile,
            )
        ) {
            OmniFlowRuntimeSource.APK_ASSETS -> RuntimeSource(
                name = "apk_assets",
                openManifest = { context.assets.open("$ASSET_ROOT/$MANIFEST_FILE") },
                openBundle = { context.assets.open("$ASSET_ROOT/$BUNDLE_FILE") },
            )
            OmniFlowRuntimeSource.DEBUG_FILES -> RuntimeSource(
                name = "debug_files",
                openManifest = overrideManifest::inputStream,
                openBundle = overrideBundle::inputStream,
            )
        }
    }

    private fun installBundle(
        context: Context,
        manifest: OmniFlowRuntimeManifest,
        openBundle: () -> InputStream,
    ): File {
        val runtimeRoot = File(
            omniFlowInternalRoot(context),
            "runtime/omniflow",
        ).apply { mkdirs() }
        val target = File(runtimeRoot, manifest.version)
        val marker = File(target, INSTALL_MARKER)
        if (marker.readTextOrNull() == manifest.bundleSha256 && requiredFilesExist(target, manifest)) {
            return target
        }
        val bundle = File.createTempFile(
            "omniflow-runtime-${manifest.version}-",
            ".zip",
            context.cacheDir,
        )
        try {
            openBundle().use { input ->
                bundle.outputStream().buffered().use(input::copyTo)
            }
            val actualSha256 = sha256(bundle.inputStream())
            require(actualSha256 == manifest.bundleSha256) {
                "omniflow_runtime_bundle_checksum_mismatch"
            }
            val installing = File(runtimeRoot, ".${manifest.version}.installing")
            installing.deleteRecursively()
            extractOmniFlowRuntimeBundle(bundle.inputStream(), installing)
            require(requiredFilesExist(installing, manifest)) {
                "omniflow_runtime_bundle_incomplete"
            }
            File(installing, INSTALL_MARKER).writeText(manifest.bundleSha256)
            target.deleteRecursively()
            require(installing.renameTo(target)) { "omniflow_runtime_install_rename_failed" }
            runtimeRoot.listFiles()
                .orEmpty()
                .filter { it.isDirectory && it.name != manifest.version && !it.name.startsWith('.') }
                .sortedByDescending(File::lastModified)
                .drop(1)
                .forEach(File::deleteRecursively)
            return target
        } finally {
            bundle.delete()
        }
    }

    private fun alignPythonStoreWithRuntime(
        context: Context,
        manifest: OmniFlowRuntimeManifest,
    ) {
        val storeDirectory = File(
            omniFlowInternalRoot(context),
            "omniflow",
        ).apply { mkdirs() }
        alignOmniFlowStoreWithRuntime(
            storeDirectory = storeDirectory,
            runtimeFingerprint = manifest.runtimeFingerprint(),
        )
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

    private data class RuntimeSource(
        val name: String,
        val openManifest: () -> InputStream,
        val openBundle: () -> InputStream,
    )

    companion object {
        const val DEBUG_OVERRIDE_DIRECTORY = "omniflow-runtime-provider"
        private const val ASSET_ROOT = "omniflow-runtime"
        private const val MANIFEST_FILE = "manifest.properties"
        private const val BUNDLE_FILE = "bundle.zip"
        private const val INSTALL_MARKER = ".installed"
    }
}

internal enum class OmniFlowRuntimeSource {
    APK_ASSETS,
    DEBUG_FILES,
}

internal fun selectOmniFlowRuntimeSource(
    debuggable: Boolean,
    overrideManifestExists: Boolean,
    overrideBundleExists: Boolean,
): OmniFlowRuntimeSource {
    if (!debuggable) return OmniFlowRuntimeSource.APK_ASSETS
    require(overrideManifestExists == overrideBundleExists) {
        "omniflow_debug_runtime_override_incomplete"
    }
    return if (overrideManifestExists) {
        OmniFlowRuntimeSource.DEBUG_FILES
    } else {
        OmniFlowRuntimeSource.APK_ASSETS
    }
}

fun alignOmniFlowStoreWithRuntime(
    storeDirectory: File,
    runtimeFingerprint: String,
) {
    storeDirectory.mkdirs()
    val marker = File(storeDirectory, ".runtime_fingerprint")
    if (marker.takeIf(File::isFile)?.readText()?.trim() == runtimeFingerprint) return
    File(storeDirectory, "omniflow.json.tmp").delete()
    marker.writeText(runtimeFingerprint)
}
