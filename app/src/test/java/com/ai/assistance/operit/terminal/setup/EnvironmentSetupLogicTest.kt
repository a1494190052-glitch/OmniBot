package com.ai.assistance.operit.terminal.setup

import com.rk.settings.UbuntuPackageMirror
import com.rk.terminal.runtime.UbuntuRepositoryManager
import com.rk.terminal.ui.screens.settings.WorkingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EnvironmentSetupLogicTest {

    @Test
    fun buildInstallCommands_usesAlpinePackagesAndUvBootstrap() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("python", "pip", "uv", "nodejs", "ssh_client"),
            repositorySetupCommand = ""
        )

        val apkAdd = commands.first { it.startsWith("apk add ") }
        assertTrue(apkAdd.contains("python3"))
        assertTrue(apkAdd.contains("py3-pip"))
        assertTrue(apkAdd.contains("nodejs"))
        assertTrue(apkAdd.contains("npm"))
        assertTrue(apkAdd.contains("openssh-client-default"))

        assertTrue(commands.contains("ln -sf /usr/bin/python3 /usr/local/bin/python || true"))
        assertTrue(commands.contains("ln -sf /usr/bin/pip3 /usr/local/bin/pip || true"))
        assertTrue(
            commands.contains(
                "if ! apk add --no-cache uv; then python3 -m pip install --break-system-packages --upgrade uv; fi"
            )
        )
    }

    @Test
    fun buildInstallCommands_prependsRepositorySetupWhenProvided() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("curl"),
            repositorySetupCommand = "echo mirror-ready"
        )

        assertEquals("echo mirror-ready", commands.first())
        assertTrue(commands.any { it == "apk add --no-cache curl" })
    }

    @Test
    fun buildInstallCommands_usesUbuntuPackagesAndApt() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("python", "pip", "uv", "nodejs", "ssh_client", "xz"),
            repositorySetupCommand = UbuntuRepositoryManager.buildRepositorySetupCommand(
                UbuntuPackageMirror.TSINGHUA
            ),
            workingMode = WorkingMode.UBUNTU
        )

        val ubuntuRepositorySetup = commands.first()
        assertTrue(ubuntuRepositorySetup.contains("mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"))
        assertTrue(ubuntuRepositorySetup.contains("ports.ubuntu.com/ubuntu-ports"))
        assertTrue(ubuntuRepositorySetup.contains("ubuntu.sources"))

        val nodeRepositorySetup = commands.first { it.contains("deb.nodesource.com/node_22.x") }
        assertTrue(nodeRepositorySetup.contains("nodesource-repo.gpg.key"))
        assertTrue(nodeRepositorySetup.contains("Architectures: %s"))

        val aptInstall = commands.last { it.startsWith("apt-get update") }
        assertTrue(aptInstall.contains("python3"))
        assertTrue(aptInstall.contains("python3-pip"))
        assertTrue(aptInstall.contains("nodejs"))
        assertTrue(!aptInstall.split(Regex("\\s+")).contains("npm"))
        assertTrue(aptInstall.contains("openssh-client"))
        assertTrue(aptInstall.contains("xz-utils"))
        assertTrue(commands.contains("python3 -m pip install --break-system-packages --upgrade uv"))
    }

    @Test
    fun buildInstallCommands_codexInstallsOfficialCliAndRuntimeDependencies() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("codex"),
            repositorySetupCommand = ""
        )

        val apkAdd = commands.first { it.startsWith("apk add ") }
        assertTrue(apkAdd.contains("nodejs"))
        assertTrue(apkAdd.contains("npm"))
        assertTrue(apkAdd.contains("git"))
        assertTrue(commands.contains("npm config set prefix /root/.npm-global"))
        assertTrue(
            commands.contains(
                "npm install -g --no-audit --no-fund @openai/codex@latest"
            )
        )
        assertTrue(
            commands.contains(
                "ln -sf /root/.npm-global/bin/codex /usr/local/bin/codex || true"
            )
        )
    }

    @Test
    fun buildInventoryProbeCommand_codexChecksCliFromManagedNpmPath() {
        val command = EnvironmentSetupLogic.buildInventoryProbeCommand(listOf("codex"))

        assertTrue(command.contains("/root/.npm-global/bin"))
        assertTrue(command.contains("command -v codex"))
        assertTrue(command.contains("codex --version"))
    }

    @Test
    fun buildInstallCommands_installsClaudeCodeAndOpenCodeInManagedNpmPath() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("claude_code", "opencode"),
            repositorySetupCommand = ""
        )

        val apkAdd = commands.first { it.startsWith("apk add ") }
        assertTrue(apkAdd.contains("nodejs"))
        assertTrue(apkAdd.contains("npm"))
        assertTrue(commands.count { it == "npm config set prefix /root/.npm-global" } == 1)
        assertTrue(
            commands.contains(
                "npm install -g --no-audit --no-fund @anthropic-ai/claude-code@latest"
            )
        )
        assertTrue(
            commands.contains(
                "ln -sf /root/.npm-global/bin/claude /usr/local/bin/claude || true"
            )
        )
        assertTrue(
            commands.contains(
                "npm install -g --no-audit --no-fund opencode-ai@latest"
            )
        )
        assertTrue(
            commands.contains(
                "ln -sf /root/.npm-global/bin/opencode /usr/local/bin/opencode || true"
            )
        )
    }

    @Test
    fun buildInventoryProbeCommand_detectsClaudeCodeAndOpenCode() {
        val command = EnvironmentSetupLogic.buildInventoryProbeCommand(
            listOf("claude_code", "opencode")
        )

        assertTrue(command.contains("/root/.npm-global/bin"))
        assertTrue(command.contains("command -v claude"))
        assertTrue(command.contains("claude --version"))
        assertTrue(command.contains("command -v opencode"))
        assertTrue(command.contains("opencode --version"))
    }

    @Test
    fun buildInventoryProbeCommand_validatesRuntimeCwdForNodeAndPython() {
        val command = EnvironmentSetupLogic.buildInventoryProbeCommand(listOf("nodejs", "python", "pip"))

        assertTrue(command.contains("node -e 'process.cwd();"))
        assertTrue(command.contains("process.versions.node"))
        assertTrue(command.contains("python3 -c 'import os; os.getcwd()'"))
        assertTrue(command.contains("pip3 --version"))
    }

    @Test
    fun buildSetupScript_validatesSelectedPackagesBeforeSuccess() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("nodejs", "python", "pip"),
            repositorySetupCommand = ""
        )
        val script = EnvironmentSetupLogic.buildSetupScript(
            commands = commands,
            selectedPackageIds = listOf("nodejs", "python", "pip")
        )

        assertTrue(script.contains("run_validate()"))
        assertTrue(script.contains("校验基础目录操作"))
        assertTrue(script.contains("node -e 'process.cwd();"))
        assertTrue(script.contains("python3 -c 'import os; os.getcwd()'"))
        assertTrue(script.contains("pip3 --version"))
        assertTrue(script.indexOf("run_setup && run_validate") < script.indexOf("选中的环境已准备完成"))
    }

    @Test
    fun buildValidationCommands_wrapsChecksForAndJoinedExecution() {
        val commands = EnvironmentSetupLogic.buildValidationCommands(listOf("nodejs"))

        assertTrue(commands.isNotEmpty())
        assertTrue(commands.all { it.startsWith("{ ") && it.endsWith("; }") })
        assertTrue(commands.any { it.contains("exit 1") })
    }

    @Test
    fun buildSetupScript_isShellSafeForEveryPackageCombination() {
        val packageIds = EnvironmentSetupLogic.packageDefinitions.map { it.id }
        val tempDir = Files.createTempDirectory("omni-setup-script-test").toFile()

        try {
            val total = 1 shl packageIds.size
            for (mask in 1 until total) {
                val selectedPackageIds = packageIds.filterIndexed { index, _ ->
                    mask and (1 shl index) != 0
                }
                listOf(WorkingMode.ALPINE, WorkingMode.UBUNTU).forEach { workingMode ->
                    val repositorySetupCommand = if (workingMode == WorkingMode.UBUNTU) {
                        UbuntuRepositoryManager.buildRepositorySetupCommand(
                            UbuntuPackageMirror.TSINGHUA
                        )
                    } else {
                        ""
                    }
                    val distroCommands = EnvironmentSetupLogic.buildInstallCommands(
                        selectedPackageIds = selectedPackageIds,
                        repositorySetupCommand = repositorySetupCommand,
                        workingMode = workingMode
                    )
                    val scriptFile = File(tempDir, "setup-$workingMode-$mask.sh")
                    scriptFile.writeText(
                        EnvironmentSetupLogic.buildSetupScript(
                            commands = distroCommands,
                            selectedPackageIds = selectedPackageIds,
                            workingMode = workingMode
                        )
                    )

                    val process = ProcessBuilder("/bin/sh", "-n", scriptFile.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                    val exitCode = process.waitFor()

                    assertEquals(
                        "Shell syntax check failed for mode=$workingMode $selectedPackageIds: $output",
                        0,
                        exitCode
                    )
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
