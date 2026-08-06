package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PreparedOmniFlowRuntime(
    val manifest: OmniFlowRuntimeManifest,
    val shellPythonSourcePath: String,
    val shellSitePackagesPath: String,
    val shellOmniTransferRoot: String,
    val shellOmniTransferCheckpointPath: String,
    val source: String,
)

class OmniFlowRuntimeProvider {
    private val prepareMutex = Mutex()

    @Volatile
    private var prepared: PreparedOmniFlowRuntime? = null

    suspend fun install(
        context: Context,
        platform: OmniFlowPlatform,
    ): PreparedOmniFlowRuntime = prepare(context, platform)

    suspend fun update(
        context: Context,
        platform: OmniFlowPlatform,
    ): PreparedOmniFlowRuntime = prepareMutex.withLock {
        prepared = null
        prepareFresh(context.applicationContext, platform, refresh = true)
    }

    suspend fun prepare(
        context: Context,
        platform: OmniFlowPlatform,
    ): PreparedOmniFlowRuntime {
        prepared?.let { return it }
        return prepareMutex.withLock {
            prepared?.let { return@withLock it }
            prepareFresh(context.applicationContext, platform, refresh = false)
        }
    }

    suspend fun reclaim(
        context: Context,
        platform: OmniFlowPlatform,
    ) = prepareMutex.withLock {
        prepared = null
        platform.reclaimRuntimeSkill(context.applicationContext)
    }

    private suspend fun prepareFresh(
        appContext: Context,
        platform: OmniFlowPlatform,
        refresh: Boolean,
    ): PreparedOmniFlowRuntime {
        val startedAt = System.currentTimeMillis()
        log("prepare_start refresh=$refresh")
        val location = platform.resolveRuntimeSkill(appContext, refresh = refresh)
        log(
            "prepare_skill_resolved durationMs=${System.currentTimeMillis() - startedAt} " +
                "source=${location.source}",
        )
        val manifest = withContext(Dispatchers.IO) {
            val manifestFile = File(location.androidRoot, MANIFEST_PATH)
            require(manifestFile.isFile) { "omniflow_skill_manifest_missing" }
            manifestFile.inputStream().use(::parseOmniFlowRuntimeManifest)
        }
        platform.ensurePython(appContext, manifest.pythonVersion)
        log(
            "prepare_python_ready durationMs=${System.currentTimeMillis() - startedAt} " +
                "python=${manifest.pythonVersion}",
        )
        platform.bootstrapRuntimeSkill(appContext, location)
        log(
            "prepare_bootstrap_ready durationMs=${System.currentTimeMillis() - startedAt}",
        )
        val runtime = withContext(Dispatchers.IO) {
            requireRuntimeFiles(location.androidRoot, manifest)
            alignPythonStoreWithRuntime(appContext, manifest)
            PreparedOmniFlowRuntime(
                manifest = manifest,
                shellPythonSourcePath = "${location.shellRoot}/scripts/runtime/python",
                shellSitePackagesPath =
                    "${location.shellRoot}/scripts/runtime/.runtime/site-packages",
                shellOmniTransferRoot =
                    "${location.shellRoot}/scripts/runtime/.runtime/omnitransfer",
                shellOmniTransferCheckpointPath =
                    "${location.shellRoot}/scripts/runtime/.runtime/omnitransfer/" +
                        "src/omnitransfer/${manifest.omniTransferCheckpoint}",
                source = location.source,
            )
        }
        return runtime.also {
            prepared = it
            log(
                "prepare_ready durationMs=${System.currentTimeMillis() - startedAt} " +
                    "runtime=${manifest.version}",
            )
        }
    }

    private fun log(message: String) {
        runCatching { OmniLog.i(TAG, message) }
    }

    private fun requireRuntimeFiles(
        skillRoot: File,
        manifest: OmniFlowRuntimeManifest,
    ) {
        val required = listOf(
            "scripts/runtime/python/omniflow/bridge.py",
            "scripts/runtime/python/src/integrations/runlog.py",
            "scripts/runtime/python/schemas/oob/oob_canonical_actions.v1.json",
            "scripts/runtime/python/schemas/oob/omniflow_canonical_run_log.v1.json",
            "scripts/runtime/python/schemas/oob/omniflow_function.v2.json",
            "scripts/runtime/python/schemas/oob/omniflow_checker_rule.v1.json",
            "scripts/runtime/python/schemas/oob/omniflow_android_bridge.v2.json",
            "scripts/runtime/.runtime/site-packages/numpy/__init__.py",
            "scripts/runtime/.runtime/site-packages/json_repair/__init__.py",
            "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/runtime.py",
            "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/numpy_matcher.py",
            "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/numpy_v9_matcher.py",
            "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/${manifest.omniTransferCheckpoint}",
            "scripts/runtime/.runtime/installed.json",
        )
        require(required.all { File(skillRoot, it).isFile }) {
            "omniflow_skill_runtime_incomplete"
        }
    }

    private fun alignPythonStoreWithRuntime(
        context: Context,
        manifest: OmniFlowRuntimeManifest,
    ) {
        val storeDirectory = File(omniFlowInternalRoot(context), "omniflow").apply { mkdirs() }
        alignOmniFlowStoreWithRuntime(
            storeDirectory = storeDirectory,
            runtimeFingerprint = manifest.runtimeFingerprint(),
        )
    }

    private fun OmniFlowRuntimeManifest.runtimeFingerprint(): String = listOf(
        version,
        protocol,
        bridgeContractSha256,
        omniFlowCommit,
        omniFlowSourceSha256,
        omniTransferCommit,
        omniTransferSourceSha256,
        omniTransferCheckpoint,
        numpyVersion,
        jsonRepairVersion,
    ).joinToString(":")

    companion object {
        private const val TAG = "[OmniFlowRuntimeProvider]"
        const val SKILL_ID = "omniflow-gui-runtime"
        private const val MANIFEST_PATH = "scripts/runtime/runtime.properties"
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
