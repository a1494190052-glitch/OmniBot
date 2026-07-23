import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun prop(name: String): String = (project.findProperty(name) as String?)?.trim()
    ?: System.getenv(name)?.trim()
    ?: ""

fun buildConfigString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

val appUpdateChannel = prop("OMNIBOT_UPDATE_CHANNEL")
    .lowercase()
    .takeIf { it.matches(Regex("[a-z0-9][a-z0-9._-]{0,31}")) }
    ?: "public"
val defaultAppVersionCode = 2
val defaultAppVersionName = "0.5.6.2"
val appVersionCode = prop("OMNIBOT_VERSION_CODE")
    .toIntOrNull()
    ?.takeIf { it > 0 }
    ?: defaultAppVersionCode
val appVersionName = prop("OMNIBOT_VERSION_NAME")
    .takeIf { it.matches(Regex("""[0-9]+(?:\.[0-9]+){2,3}""")) }
    ?: defaultAppVersionName

fun localProp(name: String): String {
    val localPropertiesFile = rootProject.file("local.properties")
    if (!localPropertiesFile.isFile) return ""
    return Properties()
        .apply {
            localPropertiesFile.inputStream().use { input ->
                load(InputStreamReader(input, StandardCharsets.UTF_8))
            }
        }
        .getProperty(name)
        ?.trim()
        ?: ""
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val readBytes = input.read(buffer)
            if (readBytes < 0) break
            digest.update(buffer, 0, readBytes)
        }
    }
    return BigInteger(1, digest.digest()).toString(16).padStart(64, '0')
}

fun sha256Directory(directory: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    directory.walkTopDown()
        .filter(File::isFile)
        .filterNot { file ->
            val relative = file.relativeTo(directory).invariantSeparatorsPath
            relative.split('/').any { it == "__pycache__" } ||
                relative.endsWith(".pyc") ||
                relative.endsWith(".pyo") ||
                relative.endsWith(".DS_Store")
        }
        .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
        .forEach { file ->
            digest.update(file.relativeTo(directory).invariantSeparatorsPath.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val readBytes = input.read(buffer)
                    if (readBytes < 0) break
                    digest.update(buffer, 0, readBytes)
                }
            }
        }
    return BigInteger(1, digest.digest()).toString(16).padStart(64, '0')
}

fun commandOutput(workingDirectory: File, vararg command: String): String? = runCatching {
    ProcessBuilder(*command)
        .directory(workingDirectory)
        .redirectErrorStream(true)
        .start()
        .let { process ->
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            output.takeIf { process.waitFor() == 0 }
        }
}.getOrNull()

data class PythonRuntimeSource(
    val packageDirectory: File,
    val commit: String,
    val dirty: Boolean,
    val mode: String,
)

fun resolvePythonRuntimeSource(
    overridePath: String,
    packageRelativePath: String,
    embeddedPackageDirectory: File,
    embeddedCommit: String,
    allowDirty: Boolean,
    label: String,
): PythonRuntimeSource {
    if (overridePath.isBlank()) {
        return PythonRuntimeSource(
            packageDirectory = embeddedPackageDirectory,
            commit = embeddedCommit,
            dirty = false,
            mode = "embedded",
        )
    }
    val sourceRoot = File(overridePath).absoluteFile.normalize()
    val packageDirectory = when {
        File(sourceRoot, packageRelativePath).isDirectory -> File(sourceRoot, packageRelativePath)
        sourceRoot.name == packageRelativePath.substringAfterLast('/') && sourceRoot.isDirectory -> sourceRoot
        else -> throw GradleException(
            "$label source does not contain $packageRelativePath: ${sourceRoot.absolutePath}"
        )
    }
    val commit = commandOutput(packageDirectory, "git", "rev-parse", "HEAD")
        ?.lineSequence()
        ?.lastOrNull()
        ?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }
        ?: "unversioned"
    val dirty = commandOutput(
        packageDirectory,
        "git",
        "status",
        "--porcelain",
        "--untracked-files=all",
        "--",
        ".",
    ).let { it == null || it.isNotBlank() }
    if (dirty && !allowDirty) {
        throw GradleException(
            "$label source is dirty or unversioned: ${packageDirectory.absolutePath}. " +
                "Commit it first or set OOB_ALLOW_DIRTY_RUNTIME_SOURCES=1 explicitly."
        )
    }
    return PythonRuntimeSource(
        packageDirectory = packageDirectory,
        commit = commit,
        dirty = dirty,
        mode = "override",
    )
}

fun verifyEmbeddedRuntimeSource(
    source: PythonRuntimeSource,
    expectedSha256: String,
    label: String,
) {
    if (source.mode != "embedded") return
    require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
        "$label embedded source SHA-256 is missing or invalid"
    }
    val actualSha256 = sha256Directory(source.packageDirectory)
    if (actualSha256 != expectedSha256.lowercase()) {
        throw GradleException(
            "$label embedded source drifted: expected=$expectedSha256 actual=$actualSha256. " +
                "Sync the canonical runtime and update runtime.properties in the same change."
        )
    }
}

fun downloadVerifiedFile(target: File, url: String, expectedSha256: String) {
    if (target.isFile && sha256(target) == expectedSha256) return
    target.delete()
    target.parentFile?.mkdirs()
    URI(url).toURL().openStream().use { input ->
        target.outputStream().use(input::copyTo)
    }
    val actualSha256 = sha256(target)
    if (actualSha256 != expectedSha256) {
        target.delete()
        throw GradleException(
            "Runtime download checksum mismatch for $url: expected=$expectedSha256 actual=$actualSha256"
        )
    }
}

fun unzipVerified(archive: File, targetDir: File) {
    val targetRoot = targetDir.canonicalFile
    ZipFile(archive).use { zip ->
        zip.entries().asSequence().forEach { entry ->
            val target = File(targetRoot, entry.name).canonicalFile
            check(target.path == targetRoot.path || target.path.startsWith(targetRoot.path + File.separator)) {
                "Zip entry escapes runtime directory: ${entry.name}"
            }
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use(input::copyTo)
                }
            }
        }
    }
}

fun zipDirectory(sourceDir: File, archive: File) {
    archive.parentFile?.mkdirs()
    ZipOutputStream(archive.outputStream().buffered()).use { output ->
        output.setLevel(Deflater.BEST_COMPRESSION)
        Files.walk(sourceDir.toPath()).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .sorted()
                .forEach { path ->
                    val relative = sourceDir.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                    output.putNextEntry(ZipEntry(relative).apply { time = 0L })
                    Files.newInputStream(path).use { it.copyTo(output) }
                    output.closeEntry()
                }
        }
    }
}

fun flutterCommand(): String {
    val executableName = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "flutter.bat" else "flutter"
    val configuredFlutterSdk = localProp("flutter.sdk")
    if (configuredFlutterSdk.isNotBlank()) {
        val configuredFlutter = File(configuredFlutterSdk, "bin/$executableName")
        if (configuredFlutter.isFile) return configuredFlutter.absolutePath
    }
    return executableName
}

fun shouldBundleFlutterWebForRequestedTasks(): Boolean {
    val skip = prop("OOB_SKIP_FLUTTER_WEB").lowercase() in setOf("1", "true", "yes", "on")
    if (skip) return false
    return when (prop("OOB_FLUTTER_WEB_MODE").lowercase()) {
        "include", "always", "true", "1", "yes" -> true
        "skip", "exclude", "none", "off", "false", "0", "no" -> false
        else -> gradle.startParameter.taskNames
            .map { it.substringAfterLast(':').lowercase() }
            .any { it.contains("release") || it == "assembleproduction" || it == "bundleproduction" }
    }
}

val omnibotImageBaseUrl = prop("OMNIBOT_IMAGE_BASE_URL")
    .ifBlank { "https://cloud.omnimind.com.cn" }
val omnibotImageModel = prop("OMNIBOT_IMAGE_MODEL")
    .ifBlank { "gpt-image-2" }
val omnibotImageApiKey = prop("OMNIBOT_IMAGE_API_KEY")
val bundledDebugLlmBaseUrl = prop("OMNIBOT_DEBUG_LLM_BASE_URL")
    .ifBlank { "https://llmapi.paratera.com" }
val bundledDebugLlmApiKey = System.getenv("LLMTHU_API_KEY")?.trim().orEmpty()
    .ifBlank { prop("OMNIBOT_DEBUG_LLM_API_KEY") }
val bundledDebugAgentModel = prop("OMNIBOT_DEBUG_AGENT_MODEL")
    .ifBlank { "DeepSeek-V4-Pro" }
val bundledDebugVlmModel = prop("OMNIBOT_DEBUG_VLM_MODEL")
    .ifBlank { "Qwen3-VL-235B-A22B-Instruct" }
val bundledDebugLlmProfileName = prop("OMNIBOT_DEBUG_LLM_PROFILE_NAME")
    .ifBlank { "LLM API Debug" }

val flutterWebBuildDir = rootProject.file("ui/build/web")
val flutterWebAssetsRootDir = layout.buildDirectory.dir("generated/omnibot_assets").get().asFile
val flutterWebAssetsDir = File(flutterWebAssetsRootDir, "flutter_web")
val flutterMobileAssetsSourceDir = rootProject.file("ui/assets")
val omniFlowRuntimeDir = rootProject.file("embedded/omniflow")
val omniFlowRuntimePropertiesFile = File(omniFlowRuntimeDir, "runtime.properties")
val omniFlowRuntimeProperties = Properties().apply {
    omniFlowRuntimePropertiesFile.inputStream().use(::load)
}
val omniFlowBridgeContractFile = rootProject.file(
    "schemas/oob/omniflow_android_bridge.v2.json"
)
val omniFlowBridgeContract = JsonSlurper().parse(omniFlowBridgeContractFile) as Map<*, *>
val omniFlowBridgeProtocol = omniFlowBridgeContract["protocol_version"]
    ?.toString()
    ?.takeIf(String::isNotBlank)
    ?: throw GradleException("Bridge contract protocol_version is required")
val omniFlowBridgeCapabilities = (omniFlowBridgeContract["operations"] as? Map<*, *>)
    ?.keys
    ?.map(Any?::toString)
    ?.filter(String::isNotBlank)
    ?.sorted()
    ?.takeIf(List<String>::isNotEmpty)
    ?: throw GradleException("Bridge contract operations are required")
val allowDirtyOmniFlowSources = prop("OOB_ALLOW_DIRTY_RUNTIME_SOURCES")
    .lowercase() in setOf("1", "true", "yes", "on")
val omniFlowPythonSource = resolvePythonRuntimeSource(
    overridePath = prop("OOB_OMNIFLOW_SOURCE_DIR"),
    packageRelativePath = "omniflow",
    embeddedPackageDirectory = File(omniFlowRuntimeDir, "python/omniflow"),
    embeddedCommit = omniFlowRuntimeProperties.getProperty("omniflow.commit"),
    allowDirty = allowDirtyOmniFlowSources,
    label = "OmniFlow",
)
val omniTransferPythonSource = resolvePythonRuntimeSource(
    overridePath = prop("OOB_OMNITRANSFER_SOURCE_DIR"),
    packageRelativePath = "src/omnitransfer",
    embeddedPackageDirectory = File(omniFlowRuntimeDir, "python/omnitransfer"),
    embeddedCommit = omniFlowRuntimeProperties.getProperty("omnitransfer.commit"),
    allowDirty = allowDirtyOmniFlowSources,
    label = "OmniTransfer",
)
verifyEmbeddedRuntimeSource(
    source = omniFlowPythonSource,
    expectedSha256 = omniFlowRuntimeProperties.getProperty("omniflow.source.sha256").orEmpty(),
    label = "OmniFlow",
)
verifyEmbeddedRuntimeSource(
    source = omniTransferPythonSource,
    expectedSha256 = omniFlowRuntimeProperties.getProperty("omnitransfer.source.sha256").orEmpty(),
    label = "OmniTransfer",
)
val omniFlowRuntimeAssetsRootDir = layout.buildDirectory.dir("generated/omniflow_runtime_assets").get().asFile

val checkOobActionSchemaGenerated by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fail when generated Kotlin or Dart action contracts drift from JSON."
    workingDir(rootProject.projectDir)
    commandLine(
        "python3",
        rootProject.file("scripts/generate-oob-action-schema.py").absolutePath,
        "--check",
    )
    inputs.file(rootProject.file("schemas/oob/oob_canonical_actions.v1.json"))
    inputs.file(rootProject.file("scripts/generate-oob-action-schema.py"))
    inputs.file(rootProject.file("baselib/src/main/java/cn/com/omnimind/baselib/runlog/OobActionSchema.kt"))
    inputs.file(rootProject.file("ui/lib/features/task/run_log/oob_canonical_action_schema.dart"))
}

val checkOmniFlowFunctionSchemasGenerated by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fail when generated Kotlin Function contracts drift from JSON."
    workingDir(rootProject.projectDir)
    commandLine(
        "python3",
        rootProject.file("scripts/generate-omniflow-function-schemas.py").absolutePath,
        "--check",
    )
    inputs.files(
        rootProject.file("schemas/oob/omniflow_function.v2.json"),
        rootProject.file("schemas/oob/omniflow_checker_rule.v1.json"),
        rootProject.file("scripts/generate-omniflow-function-schemas.py"),
        rootProject.file(
            "app/src/main/java/cn/com/omnimind/bot/function/GeneratedFunctionContractSchemas.kt",
        ),
    )
}

val prepareOmniFlowRuntime by tasks.registering {
    group = "omniflow"
    description = "Build the pinned embedded OmniFlow Python runtime."
    val outputDir = File(omniFlowRuntimeAssetsRootDir, "omniflow-runtime")
    val bundleFile = File(outputDir, "bundle.zip")
    val manifestFile = File(outputDir, "manifest.properties")
    inputs.file(File(omniFlowRuntimeDir, "python/oob_omniflow_bridge.py"))
    inputs.file(omniFlowBridgeContractFile)
    inputs.files(fileTree(omniFlowPythonSource.packageDirectory) {
        exclude("**/__pycache__/**", "**/*.pyc", "**/*.pyo", "**/.DS_Store")
    })
    inputs.files(fileTree(omniTransferPythonSource.packageDirectory) {
        exclude("**/__pycache__/**", "**/*.pyc", "**/*.pyo", "**/.DS_Store")
    })
    inputs.file(omniFlowRuntimePropertiesFile)
    inputs.property("omniflow.commit", omniFlowPythonSource.commit)
    inputs.property("omniflow.source.dirty", omniFlowPythonSource.dirty)
    inputs.property("omniflow.source.mode", omniFlowPythonSource.mode)
    inputs.property("omnitransfer.commit", omniTransferPythonSource.commit)
    inputs.property("omnitransfer.source.dirty", omniTransferPythonSource.dirty)
    inputs.property("omnitransfer.source.mode", omniTransferPythonSource.mode)
    inputs.files(
        rootProject.file("schemas/oob/README.md"),
        rootProject.file("schemas/oob/oob_canonical_actions.v1.json"),
        rootProject.file("schemas/oob/omniflow_canonical_run_log.v1.json"),
        rootProject.file("schemas/oob/omniflow_function.v2.json"),
        rootProject.file("schemas/oob/omniflow_checker_rule.v1.json"),
        omniFlowBridgeContractFile,
    )
    outputs.files(bundleFile, manifestFile)
    doLast {
        val runtimeVersion = omniFlowRuntimeProperties.getProperty("runtime.version")
            ?: throw GradleException("runtime.version is required")
        val numpyUrl = omniFlowRuntimeProperties.getProperty("numpy.url")
            ?: throw GradleException("numpy.url is required")
        val numpySha256 = omniFlowRuntimeProperties.getProperty("numpy.sha256")
            ?: throw GradleException("numpy.sha256 is required")
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        val stagingDir = File(temporaryDir, "bundle").apply {
            deleteRecursively()
            mkdirs()
        }
        val sitePackagesDir = File(stagingDir, "site-packages").apply { mkdirs() }
        File(omniFlowRuntimeDir, "python/oob_omniflow_bridge.py")
            .copyTo(File(sitePackagesDir, "oob_omniflow_bridge.py"), overwrite = true)
        copy {
            from(omniFlowPythonSource.packageDirectory)
            into(File(sitePackagesDir, "omniflow"))
            exclude("**/__pycache__/**", "**/*.pyc", "**/*.pyo", "**/.DS_Store")
        }
        copy {
            from(omniTransferPythonSource.packageDirectory)
            into(File(sitePackagesDir, "omnitransfer"))
            exclude(
                "**/__pycache__/**",
                "**/*.pyc",
                "**/*.pyo",
                "**/*.pt",
                "**/.DS_Store",
            )
        }
        check(File(sitePackagesDir, "omniflow/bridge.py").isFile) {
            "OmniFlow runtime source is incomplete"
        }
        check(File(sitePackagesDir, "omnitransfer/runtime.py").isFile) {
            "OmniTransfer runtime source is incomplete"
        }
        check(File(sitePackagesDir, "omnitransfer/numpy_matcher.py").isFile) {
            "OmniTransfer NumPy runtime is incomplete"
        }
        check(
            File(
                sitePackagesDir,
                "omnitransfer/checkpoints/" +
                    "pair_evidence_mutual_v2_e3e9e2f0_20260722/seeded_visual_seed17.npz",
            ).isFile
        ) {
            "OmniTransfer NumPy checkpoint is missing"
        }
        val schemaDir = File(sitePackagesDir, "schemas/oob").apply { mkdirs() }
        listOf(
            rootProject.file("schemas/oob/README.md"),
            rootProject.file("schemas/oob/oob_canonical_actions.v1.json"),
            rootProject.file("schemas/oob/omniflow_canonical_run_log.v1.json"),
            rootProject.file("schemas/oob/omniflow_function.v2.json"),
            rootProject.file("schemas/oob/omniflow_checker_rule.v1.json"),
            omniFlowBridgeContractFile,
        ).forEach { source -> source.copyTo(File(schemaDir, source.name), overwrite = true) }
        val numpyWheel = File(temporaryDir, "numpy.whl")
        downloadVerifiedFile(numpyWheel, numpyUrl, numpySha256)
        unzipVerified(numpyWheel, sitePackagesDir)
        val effectiveRuntimeProperties = Properties().apply {
            putAll(omniFlowRuntimeProperties)
            setProperty("omniflow.commit", omniFlowPythonSource.commit)
            setProperty("omniflow.source.mode", omniFlowPythonSource.mode)
            setProperty("omniflow.source.dirty", omniFlowPythonSource.dirty.toString())
            setProperty(
                "omniflow.source.sha256",
                sha256Directory(File(sitePackagesDir, "omniflow")),
            )
            setProperty("omnitransfer.commit", omniTransferPythonSource.commit)
            setProperty("omnitransfer.source.mode", omniTransferPythonSource.mode)
            setProperty("omnitransfer.source.dirty", omniTransferPythonSource.dirty.toString())
            setProperty(
                "omnitransfer.source.sha256",
                sha256Directory(File(sitePackagesDir, "omnitransfer")),
            )
        }
        File(stagingDir, "runtime.properties").writeText(
            effectiveRuntimeProperties.stringPropertyNames()
                .sorted()
                .joinToString(separator = "\n", postfix = "\n") { key ->
                    "$key=${effectiveRuntimeProperties.getProperty(key)}"
                }
        )
        zipDirectory(stagingDir, bundleFile)
        val manifest = linkedMapOf(
            "runtime.version" to runtimeVersion,
            "runtime.protocol" to omniFlowBridgeProtocol,
            "runtime.capabilities" to omniFlowBridgeCapabilities.joinToString(","),
            "runtime.python" to effectiveRuntimeProperties.getProperty("runtime.python"),
            "runtime.platform" to effectiveRuntimeProperties.getProperty("runtime.platform"),
            "bridge.contract.sha256" to sha256(omniFlowBridgeContractFile),
            "omniflow.commit" to effectiveRuntimeProperties.getProperty("omniflow.commit"),
            "omniflow.source.sha256" to effectiveRuntimeProperties.getProperty("omniflow.source.sha256"),
            "omnitransfer.commit" to effectiveRuntimeProperties.getProperty("omnitransfer.commit"),
            "omnitransfer.source.sha256" to effectiveRuntimeProperties.getProperty("omnitransfer.source.sha256"),
            "numpy.version" to effectiveRuntimeProperties.getProperty("numpy.version"),
            "bundle.sha256" to sha256(bundleFile),
        )
        manifestFile.writeText(
            manifest.entries.joinToString(separator = "\n", postfix = "\n") { (key, value) -> "$key=$value" }
        )
    }
}

val buildFlutterWebBundle by tasks.registering(Exec::class) {
    group = "flutter web"
    description = "Build the dedicated web chat Flutter bundle."
    workingDir = rootProject.file("ui")
    commandLine(
        flutterCommand(),
        "build",
        "web",
        "--target",
        "lib/web_main.dart",
        "--base-href",
        "/webchat/",
        "--no-tree-shake-icons",
        "--no-wasm-dry-run"
    )
    inputs.dir(rootProject.file("ui/lib"))
    inputs.dir(rootProject.file("ui/web"))
    inputs.file(rootProject.file("ui/pubspec.yaml"))
    outputs.dir(flutterWebBuildDir)
    doFirst {
        delete(flutterWebBuildDir)
    }
}

val syncFlutterWebBundle by tasks.registering(Copy::class) {
    group = "flutter web"
    description = "Copy Flutter Web build output into Android assets."
    dependsOn(buildFlutterWebBundle)
    from(flutterWebBuildDir)
    into(flutterWebAssetsDir)
    outputs.upToDateWhen { false }
    exclude("**/*.symbols")
    eachFile {
        val webPath = relativePath.pathString
        if (webPath.startsWith("assets/assets/")) {
            val mobilePath = webPath.removePrefix("assets/assets/")
            val mobileAsset = File(flutterMobileAssetsSourceDir, mobilePath)
            if (mobileAsset.isFile && file.length() == mobileAsset.length() &&
                Files.mismatch(file.toPath(), mobileAsset.toPath()) == -1L
            ) {
                exclude()
            }
        }
    }
    doFirst {
        delete(flutterWebAssetsRootDir)
    }
}

gradle.projectsEvaluated {
    rootProject.findProject(":flutter")?.tasks
        ?.matching { it.name.startsWith("compileFlutterBuild") }
        ?.configureEach {
            val flutterCompileTask = this
            buildFlutterWebBundle.configure {
                mustRunAfter(flutterCompileTask)
            }
        }
}

android {
    namespace = "cn.com.omnimind.bot"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.com.omnimind.bot"
        minSdk = 29
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "APP_UPDATE_CHANNEL", buildConfigString(appUpdateChannel))
        buildConfigField("String", "IMAGE_BASE_URL", buildConfigString(omnibotImageBaseUrl))
        buildConfigField("String", "IMAGE_MODEL", buildConfigString(omnibotImageModel))
        buildConfigField("String", "IMAGE_API_KEY", buildConfigString(omnibotImageApiKey))


        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }

    }
    // 添加 flavor 维度
    flavorDimensions += listOf("version", "edition")

    productFlavors {
        create("develop") {
            dimension = "version"
            buildConfigField("String", "BASE_URL", "\"${prop("OMNIBOT_BASE_URL")}\"")
            buildConfigField("String", "APP_UPDATE_WORKER_URL", "\"${prop("OMNIBOT_UPDATE_WORKER_URL")}\"")
            resValue("bool", "is_accessibility_tool", "true")
        }

        create("production") {
            dimension = "version"
            buildConfigField("String", "BASE_URL", "\"${prop("OMNIBOT_BASE_URL")}\"")
            buildConfigField("String", "APP_UPDATE_WORKER_URL", "\"${prop("OMNIBOT_UPDATE_WORKER_URL")}\"")
            resValue("bool", "is_accessibility_tool", "true")
        }

        create("standard") {
            dimension = "edition"
            buildConfigField("boolean", "LOCAL_MODEL_FEATURE_ENABLED", "false")
            buildConfigField("String", "APP_EDITION", "\"standard\"")
        }

        create("omniinfer") {
            dimension = "edition"
            buildConfigField("boolean", "LOCAL_MODEL_FEATURE_ENABLED", "true")
            buildConfigField("String", "APP_EDITION", "\"omniinfer\"")
        }
    }
    signingConfigs {
        create("release") {
            // 引用全局gradle.properties中的变量
            storeFile = project.findProperty("OMNI_RELEASE_STORE_FILE")?.let { file(it) }
            storePassword = project.findProperty("OMNI_RELEASE_STORE_PWD") as String?
            keyAlias = project.findProperty("OMNI_RELEASE_KEY_ALIAS") as String?
            keyPassword = project.findProperty("OMNI_RELEASE_KEY_PWD") as String?

            // V2/V3签名配置（minSdk=30）
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("String", "BUNDLED_LLM_BASE_URL", "\"\"")
            buildConfigField("String", "BUNDLED_LLM_API_KEY", "\"\"")
            buildConfigField("String", "BUNDLED_AGENT_MODEL", "\"\"")
            buildConfigField("String", "BUNDLED_VLM_MODEL", "\"\"")
            buildConfigField("String", "BUNDLED_LLM_PROFILE_NAME", "\"\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            buildConfigField(
                "String",
                "BUNDLED_LLM_BASE_URL",
                buildConfigString(bundledDebugLlmBaseUrl)
            )
            buildConfigField(
                "String",
                "BUNDLED_LLM_API_KEY",
                buildConfigString(bundledDebugLlmApiKey)
            )
            buildConfigField(
                "String",
                "BUNDLED_AGENT_MODEL",
                buildConfigString(bundledDebugAgentModel)
            )
            buildConfigField(
                "String",
                "BUNDLED_VLM_MODEL",
                buildConfigString(bundledDebugVlmModel)
            )
            buildConfigField(
                "String",
                "BUNDLED_LLM_PROFILE_NAME",
                buildConfigString(bundledDebugLlmProfileName)
            )
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf(
                "**/libc++_shared.so"
            )
        }
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs(
                "src/main/assets",
                "../skills",
                flutterWebAssetsRootDir,
                omniFlowRuntimeAssetsRootDir,
            )
        }
        getByName("omniinfer") {
            assets.srcDirs("src/omniinfer/assets")
        }
    }

    lint {
        // 使用项目根目录的 lint.xml 配置
        lintConfig = file("../lint.xml")
        // 将错误视为警告继续构建
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.named("preBuild").configure {
    dependsOn(checkOobActionSchemaGenerated, checkOmniFlowFunctionSchemasGenerated)
    dependsOn(prepareOmniFlowRuntime)
    if (shouldBundleFlutterWebForRequestedTasks()) {
        dependsOn(syncFlutterWebBundle)
    }
}
dependencies {
    implementation(project(":flutter"))
    implementation(project(":uikit"))
    implementation(project(":baselib"))
    findProject(":omniinfer-server")?.let {
        add("omniinferImplementation", it)
    }
    implementation(project(":core:main"))
    implementation(project(":core:terminal-view"))
    implementation(project(":core:terminal-emulator"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar","*.jar"))))
    implementation(project(":assists"))
//    implementation(project(":lib"))

    implementation(libs.openilink.sdk.java)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.work.runtime)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.shizuku.provider)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest )
}
