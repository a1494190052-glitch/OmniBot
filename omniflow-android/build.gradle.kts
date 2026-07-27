import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

fun prop(name: String): String = (project.findProperty(name) as String?)?.trim()
    ?: System.getenv(name)?.trim()
    ?: ""

val omniFlowRuntimeBuilder = rootProject.file("scripts/contracts/build-embedded-omniflow-runtime.py")
val omniFlowRuntimeRoot = rootProject.file("embedded/omniflow")
val omniFlowRuntimeAssetsRootDir = layout.buildDirectory.dir("generated/omniflow_runtime_assets").get().asFile
val omniFlowRuntimeAssetsDir = File(omniFlowRuntimeAssetsRootDir, "omniflow-runtime")
val omniFlowRuntimeCacheDir = layout.buildDirectory.dir("omniflow_runtime_cache").get().asFile

val prepareOmniFlowRuntime by tasks.registering(Exec::class) {
    group = "omniflow"
    description = "Build the versioned embedded OmniFlow Python runtime assets."
    workingDir(rootProject.projectDir)
    environment("PYTHONDONTWRITEBYTECODE", "1")
    val arguments = mutableListOf(
        "python3",
        omniFlowRuntimeBuilder.absolutePath,
        "--repo-root",
        rootProject.projectDir.absolutePath,
        "--output-dir",
        omniFlowRuntimeAssetsDir.absolutePath,
        "--cache-dir",
        omniFlowRuntimeCacheDir.absolutePath,
    )
    prop("OOB_OMNIFLOW_SOURCE_DIR").takeIf(String::isNotBlank)?.let {
        arguments += listOf("--omniflow-source", it)
    }
    prop("OOB_OMNITRANSFER_SOURCE_DIR").takeIf(String::isNotBlank)?.let {
        arguments += listOf("--omnitransfer-source", it)
    }
    if (prop("OOB_ALLOW_DIRTY_RUNTIME_SOURCES").lowercase() in setOf("1", "true", "yes", "on")) {
        arguments += "--allow-dirty"
    }
    commandLine(arguments)
    inputs.file(omniFlowRuntimeBuilder)
    inputs.dir(omniFlowRuntimeRoot)
    inputs.dir(rootProject.file("schemas/oob"))
    prop("OOB_OMNIFLOW_SOURCE_DIR").takeIf(String::isNotBlank)?.let { inputs.dir(it) }
    prop("OOB_OMNITRANSFER_SOURCE_DIR").takeIf(String::isNotBlank)?.let { inputs.dir(it) }
    outputs.files(
        File(omniFlowRuntimeAssetsDir, "bundle.zip"),
        File(omniFlowRuntimeAssetsDir, "manifest.properties"),
    )
}

android {
    namespace = "cn.com.omnimind.bot.omniflow"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(omniFlowRuntimeAssetsRootDir)
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareOmniFlowRuntime)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":androidgui"))
    implementation(project(":baselib"))
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
