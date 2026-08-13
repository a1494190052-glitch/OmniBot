import java.math.BigInteger
import java.net.URI
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val gitCommitHash: Provider<String> =
    providers.exec { commandLine("git", "rev-parse", "--short=8", "HEAD") }.standardOutput.asText.map { it.trim() }

val fullGitCommitHash: Provider<String> =
    providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim() }

val gitCommitDate: Provider<String> =
    providers.exec { commandLine("git", "show", "-s", "--format=%cI", "HEAD") }.standardOutput.asText.map { it.trim() }

val termuxPackageBaseUrl = "https://packages-cf.termux.dev/apt/termux-main"
val bundledRuntimeDir = layout.projectDirectory.dir("src/main/embedded-terminal-runtime")
val prootDebFileName = "proot_5.1.107.77_aarch64.deb"
val prootDebFile = bundledRuntimeDir.file(prootDebFileName)
val prootDebChecksum = "f2cd07bafbebf625c62931994120d469934a8925a831f6e049bb08f91889a00d"
val libtallocDebUrl = "$termuxPackageBaseUrl/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
val libtallocDebChecksum = "ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da"
val alpineMiniRootfsUrl =
    "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz"
val alpineMiniRootfsChecksum = "f31202c4070c4ef7de9e157e1bd01cb4da3a2150035d74ea5372c5e86f1efac1"
val omnibotProfile = providers.gradleProperty("OMNIBOT_PROFILE").orElse("main")
val includeEmbeddedPythonEnvironment = providers.provider { true }
val includeEmbeddedUbuntuEnvironment = omnibotProfile.map { it == "investor" }
val alpineRepositoryBaseUrls = mapOf(
    "main" to "https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64",
    "community" to "https://dl-cdn.alpinelinux.org/alpine/v3.21/community/aarch64",
)
data class AlpinePackage(val repository: String, val fileName: String, val checksum: String)
val embeddedPythonPackages = listOf(
    AlpinePackage("main", "gdbm-1.24-r0.apk", "51da8e712c448965e5f2f652e9889dc0928ced665d4a4d11207e16adef680f9b"),
    AlpinePackage("main", "libbz2-1.0.8-r6.apk", "49945b5c46f2be9201c900df45ca173e1065adb45366c6ad51709381123daa5f"),
    AlpinePackage("main", "libcrypto3-3.3.7-r0.apk", "8bbd804ffb897d88abb6b10d7d5fd479aadd6f85f2735242fdbb45a687587ca2"),
    AlpinePackage("main", "libexpat-2.8.2-r0.apk", "e21971061695684dd6e6bc359f4425bb2d3800fb4a2026b3600d3c761a183777"),
    AlpinePackage("main", "libffi-3.4.7-r0.apk", "814579137f32e94e54370f2e5d93d992c4148515b426c168e87102d4d6f6ddc6"),
    AlpinePackage("main", "libgcc-14.2.0-r4.apk", "7cfdc00f102d3b98fe563b31e7a59a7b7ab61850c1751915f34e419e83ef2653"),
    AlpinePackage("main", "libgfortran-14.2.0-r4.apk", "c9d4b38f1fc497f9a2d4d67a871f6103bd4c8df8649c621990b131cbb30a2ea3"),
    AlpinePackage("main", "libncursesw-6.5_p20241006-r3.apk", "c1a5a3a552ad4d44c94454555f38de71b151dc2d608ea2246d92b3bd845b7f3a"),
    AlpinePackage("main", "libpanelw-6.5_p20241006-r3.apk", "11e2911f6fa4f3885e2fb7488847d58c4c8e166922b5fe259811a04f21802089"),
    AlpinePackage("main", "libssl3-3.3.7-r0.apk", "1c4cc0696d60a7b2bb5f71a296c6ecb3a2927b5057acecdfb2395cca873b5e62"),
    AlpinePackage("main", "libstdc++-14.2.0-r4.apk", "93dded5af4e42ca59a2e981ffc5acc59c5d7110cec4adaeb15b84dc224526b63"),
    AlpinePackage("main", "mpdecimal-4.0.0-r0.apk", "e14866cce8ab46653d2c2ffd679357abfa100fe5259f520509117be19d56cab9"),
    AlpinePackage("main", "musl-1.2.5-r11.apk", "721010e6bff908878d9c527428598661be59dde0d9f013f8431d01fd4dd16652"),
    AlpinePackage("main", "ncurses-terminfo-base-6.5_p20241006-r3.apk", "a86fb76ba2ac494ed3a550a5c42d032c486d942966bceae38f6ce95eaa384550"),
    AlpinePackage("community", "openblas-0.3.28-r0.apk", "621fecda7b98632879cf95eee902651ada07fd3aeee9fab8e6d51ba5d6145d7d"),
    AlpinePackage("community", "py3-numpy-2.1.3-r0.apk", "c99f6d88829e3d25b75b98fd6d3b52d76a2c8ff3217e4757bc49baa2ee89e0bb"),
    AlpinePackage("main", "py3-packaging-24.2-r0.apk", "bfb9b256b03ec2bd97a702d7e5f04f3ac11849e42b03f33d096dd234f6de206c"),
    AlpinePackage("main", "py3-parsing-3.1.4-r0.apk", "e0cb14deac8e79207789e128160fe2fab5d3a3308697612f0bec55de194caf38"),
    AlpinePackage("community", "py3-pip-24.3.1-r0.apk", "038d5fec30f3c39dbfa9657111a5d9b80f4c659ad85d36df7a0882e2dbb7431b"),
    AlpinePackage("main", "py3-setuptools-70.3.0-r0.apk", "17a4fdd0c0d2c7e8df459e71690076d8afe3d82b5d42cb8f6ddaaeb079ee534d"),
    AlpinePackage("main", "python3-3.12.13-r0.apk", "751f14e666d39b067b1ebf51b89e716882d0f7765c69b638819a793aed3d578e"),
    AlpinePackage("main", "readline-8.2.13-r0.apk", "7dad49f83ecbcfa00c5c7df044a5566b928ac1995651cfff219e1df1c2b93871"),
    AlpinePackage("main", "sqlite-libs-3.48.0-r4.apk", "0e4e80c156f65307d0b35c4caf5771fae2a8c3d7f5ffb642a72d13e9c6d3cd14"),
    AlpinePackage("main", "xz-libs-5.8.3-r0.apk", "992ee804cb54b0f7067f50ccfab641b253cab8659792be24ca6dd31795d87466"),
    AlpinePackage("main", "zlib-1.3.2-r0.apk", "89c230ee1c74c389c1607413c223ed7d27ce1b2e964038facbfc54245157101c"),
)
val embeddedPythonEnvironmentVersion = "alpine-3.21-python3.12-numpy2.1.3-v3"
val ubuntuBaseRootfsUrl =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
val ubuntuBaseRootfsChecksum = "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"

android {
    namespace = "com.rk.terminal"
    android.buildFeatures.buildConfig = true
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("generated/jniLibs/embeddedTerminalRuntime"))
            assets.srcDir(layout.buildDirectory.dir("generated/assets/embeddedTerminalRuntime"))
        }
    }

    buildTypes {
        release {
            buildConfigField("String", "GIT_COMMIT_HASH", "\"${fullGitCommitHash.get()}\"")
            buildConfigField("String", "GIT_SHORT_COMMIT_HASH", "\"${gitCommitHash.get()}\"")
            buildConfigField("String", "GIT_COMMIT_DATE", "\"${gitCommitDate.get()}\"")

            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
        debug{
            buildConfigField("String", "GIT_COMMIT_HASH", "\"${fullGitCommitHash.get()}\"")
            buildConfigField("String", "GIT_SHORT_COMMIT_HASH", "\"${gitCommitHash.get()}\"")
            buildConfigField("String", "GIT_COMMIT_DATE", "\"${gitCommitDate.get()}\"")
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }


}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
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

fun downloadRuntimeFile(localPath: String, remoteUrl: String, expectedChecksum: String? = null) {
    val file = file(localPath)
    if (file.exists()) {
        val checksum = sha256(file)
        if (expectedChecksum == null || checksum == expectedChecksum) return
        file.delete()
    }

    file.parentFile?.mkdirs()
    val digest = MessageDigest.getInstance("SHA-256")
    val connection = URI(remoteUrl).toURL().openConnection()
    connection.getInputStream().use { input ->
        file.outputStream().use { output ->
            val buffer = ByteArray(8192)
            while (true) {
                val readBytes = input.read(buffer)
                if (readBytes < 0) break
                output.write(buffer, 0, readBytes)
                digest.update(buffer, 0, readBytes)
            }
        }
    }
    var checksum = BigInteger(1, digest.digest()).toString(16)
    while (checksum.length < 64) checksum = "0$checksum"
    if (expectedChecksum != null && checksum != expectedChecksum) {
        file.delete()
        throw GradleException(
            "Wrong checksum for $remoteUrl:\nExpected: $expectedChecksum\nActual:   $checksum"
        )
    }
}

fun copyVerifiedRuntimeFile(source: File, target: File, expectedChecksum: String? = null) {
    check(source.isFile && source.length() > 0) { "Missing bundled runtime file: ${source.absolutePath}" }
    val checksum = sha256(source)
    if (expectedChecksum != null && checksum != expectedChecksum) {
        throw GradleException(
            "Wrong checksum for ${source.absolutePath}:\nExpected: $expectedChecksum\nActual:   $checksum"
        )
    }
    target.parentFile?.mkdirs()
    source.copyTo(target, overwrite = true)
}

fun extractDebMember(debFile: File, memberName: String, target: File) {
    target.parentFile?.mkdirs()
    RandomAccessFile(debFile, "r").use { input ->
        val globalHeader = ByteArray(8)
        input.readFully(globalHeader)
        check(String(globalHeader) == "!<arch>\n") { "Invalid deb archive: ${debFile.absolutePath}" }

        while (input.filePointer < input.length()) {
            val header = ByteArray(60)
            input.readFully(header)
            val name = String(header, 0, 16).trim().removeSuffix("/")
            val size = String(header, 48, 10).trim().toLong()
            if (name == memberName) {
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var remaining = size
                    while (remaining > 0) {
                        val readBytes = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        check(readBytes >= 0) { "Unexpected EOF while reading $memberName from ${debFile.name}" }
                        output.write(buffer, 0, readBytes)
                        remaining -= readBytes
                    }
                }
                return
            }
            input.seek(input.filePointer + size + (size % 2))
        }
    }
    error("Missing $memberName in ${debFile.absolutePath}")
}

fun unpackDebData(debFile: File, targetDir: File) {
    val dataArchive = File(targetDir.parentFile, "${debFile.name}.data.tar.xz")
    extractDebMember(debFile, "data.tar.xz", dataArchive)
    targetDir.deleteRecursively()
    targetDir.mkdirs()
    exec {
        commandLine("tar", "-xJf", dataArchive.absolutePath, "-C", targetDir.absolutePath)
    }
}

fun copyRuntimeFile(source: File, target: File, executable: Boolean) {
    check(source.isFile && source.length() > 0) { "Missing runtime file: ${source.absolutePath}" }
    target.parentFile?.mkdirs()
    source.copyTo(target, overwrite = true)
    target.setReadable(true, false)
    target.setWritable(true, true)
    if (executable) {
        target.setExecutable(true, false)
    }
}

val prepareEmbeddedTerminalRuntime by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/assets/embeddedTerminalRuntime/embedded-terminal-runtime")
    val jniOutputDir = layout.buildDirectory.dir("generated/jniLibs/embeddedTerminalRuntime")
    inputs.file(prootDebFile).withPropertyName("prootDebFile")
    inputs.property("prootDebChecksum", prootDebChecksum)
    inputs.property("libtallocDebUrl", libtallocDebUrl)
    inputs.property("libtallocDebChecksum", libtallocDebChecksum)
    inputs.property("alpineMiniRootfsUrl", alpineMiniRootfsUrl)
    inputs.property("alpineMiniRootfsChecksum", alpineMiniRootfsChecksum)
    inputs.property("includeEmbeddedPythonEnvironment", includeEmbeddedPythonEnvironment)
    inputs.property("includeEmbeddedUbuntuEnvironment", includeEmbeddedUbuntuEnvironment)
    inputs.property("embeddedPythonEnvironmentVersion", embeddedPythonEnvironmentVersion)
    inputs.property("embeddedPythonPackages", embeddedPythonPackages.joinToString { "${it.repository}/${it.fileName}:${it.checksum}" })
    inputs.property("ubuntuBaseRootfsUrl", ubuntuBaseRootfsUrl)
    inputs.property("ubuntuBaseRootfsChecksum", ubuntuBaseRootfsChecksum)
    outputs.dir(outputDir)
    outputs.dir(jniOutputDir)
    doLast {
        val root = outputDir.get().asFile
        val jniRoot = jniOutputDir.get().asFile
        root.deleteRecursively()
        jniRoot.deleteRecursively()
        root.mkdirs()
        jniRoot.mkdirs()
        val workDir = temporaryDir.apply {
            deleteRecursively()
            mkdirs()
        }
        val downloadCache = layout.buildDirectory.dir("embedded-terminal-downloads").get().asFile.apply { mkdirs() }

        val prootDeb = workDir.resolve("proot.deb")
        copyVerifiedRuntimeFile(
            source = prootDebFile.asFile,
            target = prootDeb,
            expectedChecksum = prootDebChecksum
        )
        val prootPackageRoot = workDir.resolve("proot")
        unpackDebData(prootDeb, prootPackageRoot)
        val prootPrefix = prootPackageRoot.resolve("data/data/com.termux/files/usr")
        copyRuntimeFile(
            source = prootPrefix.resolve("bin/proot"),
            target = root.resolve("proot"),
            executable = true
        )
        copyRuntimeFile(
            source = prootPrefix.resolve("libexec/proot/loader"),
            target = jniRoot.resolve("arm64-v8a/libproot-loader.so"),
            executable = true
        )
        copyRuntimeFile(
            source = prootPrefix.resolve("libexec/proot/loader32"),
            target = jniRoot.resolve("arm64-v8a/libproot-loader32.so"),
            executable = true
        )

        val libtallocDeb = downloadCache.resolve("libtalloc.deb")
        downloadRuntimeFile(
            localPath = libtallocDeb.absolutePath,
            remoteUrl = libtallocDebUrl,
            expectedChecksum = libtallocDebChecksum
        )
        val libtallocPackageRoot = workDir.resolve("libtalloc")
        unpackDebData(libtallocDeb, libtallocPackageRoot)
        copyRuntimeFile(
            source = libtallocPackageRoot.resolve("data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3"),
            target = root.resolve("libtalloc.so.2"),
            executable = false
        )

        if (includeEmbeddedPythonEnvironment.get()) {
            val alpineBaseArchive = downloadCache.resolve("alpine-base.tar.gz")
            downloadRuntimeFile(
                localPath = alpineBaseArchive.absolutePath,
                remoteUrl = alpineMiniRootfsUrl,
                expectedChecksum = alpineMiniRootfsChecksum
            )
            val alpineRoot = workDir.resolve("alpine-root").apply { mkdirs() }
            exec { commandLine("tar", "-xzf", alpineBaseArchive.absolutePath, "-C", alpineRoot.absolutePath) }
            embeddedPythonPackages.forEach { packageSpec ->
                val packageFile = downloadCache.resolve(packageSpec.fileName)
                downloadRuntimeFile(
                    localPath = packageFile.absolutePath,
                    remoteUrl = "${alpineRepositoryBaseUrls.getValue(packageSpec.repository)}/${packageSpec.fileName}",
                    expectedChecksum = packageSpec.checksum,
                )
                exec {
                    commandLine(
                        "tar",
                        "-xzf",
                        packageFile.absolutePath,
                        "--ignore-zeros",
                        "-C",
                        alpineRoot.absolutePath
                    )
                }
            }
            alpineRoot.resolve("etc/omnibot-python-environment").apply {
                parentFile.mkdirs()
                writeText("$embeddedPythonEnvironmentVersion\n")
            }
            exec {
                commandLine("find", alpineRoot.absolutePath, "-type", "f", "(", "-name", ".PKGINFO", "-o", "-name", ".SIGN.RSA.*", "-o", "-name", ".INSTALL", ")", "-delete")
            }
            val embeddedAlpineArchive = root.resolve("alpine.tar.gz")
            exec { commandLine("tar", "-czf", embeddedAlpineArchive.absolutePath, "-C", alpineRoot.absolutePath, ".") }
        } else {
            val alpineBaseArchive = downloadCache.resolve("alpine-base.tar.gz")
            downloadRuntimeFile(
                localPath = alpineBaseArchive.absolutePath,
                remoteUrl = alpineMiniRootfsUrl,
                expectedChecksum = alpineMiniRootfsChecksum
            )
            copyVerifiedRuntimeFile(alpineBaseArchive, root.resolve("alpine.tar.gz"), alpineMiniRootfsChecksum)
        }
        if (includeEmbeddedUbuntuEnvironment.get()) {
            val ubuntuBaseArchive = downloadCache.resolve("ubuntu-base.tar.gz")
            downloadRuntimeFile(
                localPath = ubuntuBaseArchive.absolutePath,
                remoteUrl = ubuntuBaseRootfsUrl,
                expectedChecksum = ubuntuBaseRootfsChecksum
            )
            copyVerifiedRuntimeFile(ubuntuBaseArchive, root.resolve("ubuntu.tar.gz"), ubuntuBaseRootfsChecksum)
        }
        root.resolve("runtime-manifest").writeText(
            buildString {
                if (includeEmbeddedPythonEnvironment.get()) {
                    appendLine("version=$embeddedPythonEnvironmentVersion")
                }
                root.listFiles().orEmpty().filter { it.isFile && it.name != "runtime-manifest" }.forEach { file ->
                    appendLine("${file.name}=${file.length()}")
                }
            }
        )
    }
}

tasks.named("preBuild") {
    dependsOn(prepareEmbeddedTerminalRuntime)
}


dependencies {
    api(libs.appcompat)
    api(libs.material)
    api(libs.constraintlayout)
    api(libs.navigation.fragment)
    api(libs.navigation.ui)
    api(libs.navigation.fragment.ktx)
    api(libs.navigation.ui.ktx)
    api(libs.activity)
    api(libs.lifecycle.viewmodel.ktx)
    api(libs.lifecycle.runtime.ktx)
    api(libs.activity.compose)
    api(platform(libs.compose.bom))
    api(libs.ui)
    api(libs.ui.graphics)
    api(libs.material3)
    api(libs.navigation.compose)
    api(project(":core:terminal-view"))
    api(project(":core:terminal-emulator"))
    api(libs.utilcode)
    //api(libs.commons.net)
    api(libs.okhttp)
    api(libs.anrwatchdog)
    api(libs.androidx.material.icons.core)
    api(libs.androidx.palette)
    api(libs.accompanist.systemuicontroller)
//    api(libs.termux.shared)

    api(project(":core:resources"))
    api(project(":core:components"))
}
