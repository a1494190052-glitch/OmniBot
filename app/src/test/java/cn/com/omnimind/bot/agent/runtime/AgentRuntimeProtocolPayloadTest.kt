package cn.com.omnimind.bot.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeProtocolPayloadTest {
    @Test
    fun managedAcpCatalogIncludesSupportedAgentsWithoutGemini() {
        assertEquals(
            listOf("Codex", "Claude Code", "OpenCode"),
            AcpAgentProfileStore.OFFICIAL_AGENTS.map { it.name }
        )
        assertTrue(AcpAgentProfileStore.OFFICIAL_AGENTS.all { it.builtIn })
        val codex = AcpAgentProfileStore.OFFICIAL_AGENTS.first()
        assertEquals(
            "codex",
            AcpAgentProfileStore.officialRuntime(codex)?.discoveryCommand
        )
        assertEquals(
            "@agentclientprotocol/codex-acp@1.1.7",
            AcpAgentProfileStore.officialRuntime(codex)?.managedAdapterPackage
        )
    }

    @Test
    fun acpStreamReadFailureIsSuppressedWhileConnectionIsClosing() {
        assertTrue(
            shouldSuppressAcpStreamReadFailure(
                closing = true,
                currentProcess = true,
                processAlive = true
            )
        )
        assertTrue(
            shouldSuppressAcpStreamReadFailure(
                closing = false,
                currentProcess = false,
                processAlive = true
            )
        )
        assertFalse(
            shouldSuppressAcpStreamReadFailure(
                closing = false,
                currentProcess = true,
                processAlive = true
            )
        )
    }


    @Test
    fun sanitizeAgentRuntimeAbsolutePathKeepsLastCleanAbsolutePath() {
        val path = sanitizeAgentRuntimeAbsolutePath(
            """
            init-host: shell warmup
            /workspace
            warning: ignored trailing log
            """.trimIndent()
        )

        assertEquals("/workspace", path)
    }

    @Test
    fun sanitizeAgentRuntimeAbsolutePathRejectsRelativeOutput() {
        assertNull(sanitizeAgentRuntimeAbsolutePath("workspace"))
    }

    @Test
    fun buildAgentTextInputMatchesAppServerTextShape() {
        val input = buildAgentTextInput(" hello ")

        assertEquals(1, input.size)
        assertEquals("text", input[0]["type"])
        assertEquals("hello", input[0]["text"])
        assertTrue(input[0].containsKey("text_elements"))
    }

    @Test
    fun buildAgentTurnInputUsesLocalImageAndWorkspaceFileHint() {
        val input = buildAgentTurnInput(
            text = "Inspect these attachments",
            attachments = listOf(
                mapOf(
                    "name" to "screen.png",
                    "path" to "/android/cache/screen.png",
                    "promptPath" to "/workspace/.omnibot/attachments/screen.png",
                    "mimeType" to "image/png",
                    "isImage" to true
                ),
                mapOf(
                    "name" to "notes.txt",
                    "path" to "/android/cache/notes.txt",
                    "promptPath" to "/workspace/.omnibot/attachments/notes.txt",
                    "mimeType" to "text/plain",
                    "isImage" to false,
                    "sendToModel" to false
                )
            ),
            preferLocalImagePaths = true
        )

        assertEquals("localImage", input[0]["type"])
        assertEquals(
            "/workspace/.omnibot/attachments/screen.png",
            input[0]["path"]
        )
        assertEquals("text", input[1]["type"])
        assertTrue(input[1]["text"].toString().contains("Inspect these attachments"))
        assertTrue(
            input[1]["text"].toString()
                .contains("/workspace/.omnibot/attachments/notes.txt")
        )
    }

    @Test
    fun buildAgentTurnInputUsesInlineImageForRemoteRuntime() {
        val input = buildAgentTurnInput(
            text = "",
            attachments = listOf(
                mapOf(
                    "name" to "screen.png",
                    "dataUrl" to "data:image/png;base64,AA==",
                    "mimeType" to "image/png",
                    "isImage" to true
                )
            ),
            preferLocalImagePaths = false
        )

        assertEquals(1, input.size)
        assertEquals("image", input[0]["type"])
        assertEquals("data:image/png;base64,AA==", input[0]["url"])
    }

    @Test
    fun buildAgentSandboxPolicyUsesAbsoluteWritableRoot() {
        val policy = buildAgentSandboxPolicy("noise\n/workspace")

        assertEquals("workspaceWrite", policy["type"])
        assertEquals(listOf("/workspace"), policy["writableRoots"])
        assertEquals(true, policy["networkAccess"])
        assertEquals(false, policy["excludeTmpdirEnvVar"])
        assertEquals(false, policy["excludeSlashTmp"])
    }

    @Test
    fun resolveAgentSandboxModeUsesCurrentThreadStartEnum() {
        assertEquals(
            "danger-full-access",
            resolveAgentSandboxMode(mapOf("type" to "dangerFullAccess"))
        )
        assertEquals(
            "read-only",
            resolveAgentSandboxMode(mapOf("type" to "readOnly"))
        )
        assertEquals(
            "workspace-write",
            resolveAgentSandboxMode(buildAgentSandboxPolicy("/workspace"))
        )
    }

    @Test
    fun localAcpPermissionBehaviorFollowsComposerPolicy() {
        assertEquals(
            AcpPermissionBehavior.ALLOW_WITHOUT_PROMPT,
            resolveAcpPermissionBehavior(
                mapOf(
                    "approvalPolicy" to "never",
                    "sandboxPolicy" to mapOf("type" to "dangerFullAccess")
                )
            )
        )
        assertEquals(
            AcpPermissionBehavior.ASK_USER,
            resolveAcpPermissionBehavior(
                mapOf(
                    "approvalPolicy" to "on-request",
                    "approvalsReviewer" to "user"
                )
            )
        )
        assertEquals(
            AcpPermissionBehavior.ASK_USER,
            resolveAcpPermissionBehavior(
                mapOf(
                    "approvalPolicy" to "on-request",
                    "approvalsReviewer" to "auto_review"
                )
            )
        )
    }

    @Test
    fun reviewThreadSettingsKeepSelectedFullAccessPolicy() {
        val params = buildAgentThreadSettingsUpdateParams(
            args = mapOf(
                "approvalPolicy" to "never",
                "approvalsReviewer" to "user",
                "sandboxPolicy" to mapOf("type" to "dangerFullAccess"),
                "model" to "gpt-5-codex",
                "effort" to "high"
            ),
            cwd = "/workspace",
            threadId = "thread-1"
        )

        assertEquals("thread-1", params["threadId"])
        assertEquals("/workspace", params["cwd"])
        assertEquals("never", params["approvalPolicy"])
        assertEquals("user", params["approvalsReviewer"])
        assertEquals(
            mapOf("type" to "dangerFullAccess"),
            params["sandboxPolicy"]
        )
        assertEquals("gpt-5-codex", params["model"])
        assertEquals("high", params["effort"])
    }

    @Test
    fun addAgentOptionalRunParamsForwardsModelAndPlanMode() {
        val params = linkedMapOf<String, Any?>("threadId" to "thread-1")

        addAgentOptionalRunParams(
            params,
            mapOf(
                "model" to "gpt-5-codex",
                "effort" to "high",
                "collaborationMode" to "plan",
                "serviceTier" to "auto"
            )
        )

        assertEquals("gpt-5-codex", params["model"])
        assertEquals("high", params["effort"])
        val collaborationMode = params["collaborationMode"] as? Map<*, *>
        val settings = collaborationMode?.get("settings") as? Map<*, *>
        assertEquals("plan", collaborationMode?.get("mode"))
        assertEquals("gpt-5-codex", settings?.get("model"))
        assertEquals("high", settings?.get("reasoning_effort"))
        assertEquals("auto", params["serviceTier"])
    }

    @Test
    fun resolveAgentCollaborationModeFillsStructuredModeSettings() {
        val mode = resolveAgentCollaborationMode(
            mapOf(
                "model" to "gpt-5-codex",
                "collaborationMode" to mapOf(
                    "mode" to "plan",
                    "settings" to mapOf("developer_instructions" to "Use a checklist.")
                )
            )
        )
        val settings = mode?.get("settings") as? Map<*, *>

        assertEquals("plan", mode?.get("mode"))
        assertEquals("gpt-5-codex", settings?.get("model"))
        assertEquals("Use a checklist.", settings?.get("developer_instructions"))
    }

    @Test
    fun resolveAgentCollaborationModeRequiresModel() {
        val params = linkedMapOf<String, Any?>("threadId" to "thread-1")

        addAgentOptionalRunParams(
            params,
            mapOf("collaborationMode" to "plan")
        )

        assertEquals(false, params.containsKey("collaborationMode"))
    }

    @Test
    fun resolveCodexReviewTargetDefaultsToUncommittedChanges() {
        val target = resolveCodexReviewTarget(null)

        assertEquals("uncommittedChanges", target["type"])
    }

    @Test
    fun resolveCodexReviewTargetPreservesExplicitTarget() {
        val target = resolveCodexReviewTarget(
            mapOf(
                "type" to "baseBranch",
                "branch" to "main"
            )
        )

        assertEquals("baseBranch", target["type"])
        assertEquals("main", target["branch"])
    }

    @Test
    fun remoteBridgeConfigRequiresUrlAndCwd() {
        assertTrue(
            CodexRemoteBridgeConfig(
                bridgeUrl = "ws://127.0.0.1:17321/codex",
                cwd = "/Users/ocean/code/project"
            ).isConfigured
        )
        assertEquals(
            false,
            CodexRemoteBridgeConfig(
                bridgeUrl = "ws://127.0.0.1:17321/codex",
                cwd = ""
            ).isConfigured
        )
    }

    @Test
    fun normalizeBridgeUrlsAcceptHostPortAndDefaultPaths() {
        assertEquals(
            "ws://192.168.1.10:17321/codex",
            normalizeCodexBridgeWebSocketUrl("192.168.1.10:17321")
        )
        assertEquals(
            "http://192.168.1.10:17321/health",
            normalizeCodexBridgeHealthUrl("ws://192.168.1.10:17321/codex")
        )
        assertEquals(
            "http://192.168.1.10:17321/fs/list",
            normalizeCodexBridgeFsListUrl("ws://192.168.1.10:17321/codex")
        )
        assertEquals(
            "http://192.168.1.10:17321/fs/upload",
            normalizeCodexBridgeFsUploadUrl("ws://192.168.1.10:17321/codex")
        )
    }

    @Test
    fun defaultThreadSourceKindsUseCurrentCodexAppServerVariants() {
        assertTrue(DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("cli"))
        assertTrue(DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("appServer"))
        assertTrue(DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("subAgentOther"))
        assertEquals(false, DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("interactive"))
        assertEquals(false, DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("background"))
        assertEquals(false, DEFAULT_CODEX_THREAD_SOURCE_KINDS.contains("subAgentInteractive"))
    }

    @Test
    fun withLocalIdsInjectsActiveAndActiveTurnIdWhenActive() {
        val response = mapOf<String, Any?>("thread" to mapOf("id" to "thread-1"))

        val enriched = response.withLocalIds(
            threadId = "thread-1",
            conversationId = 42L,
            turnId = "turn-7",
            active = true,
        )

        assertEquals("thread-1", enriched["threadId"])
        assertEquals(42L, enriched["conversationId"])
        assertEquals("turn-7", enriched["turnId"])
        assertEquals("turn-7", enriched["activeTurnId"])
        assertEquals(true, enriched["active"])
    }

    @Test
    fun withLocalIdsSurfacesInactiveWithoutActiveTurnId() {
        val response = mapOf<String, Any?>("thread" to mapOf("id" to "thread-1"))

        val enriched = response.withLocalIds(
            threadId = "thread-1",
            conversationId = 99L,
            turnId = null,
            active = false,
        )

        assertEquals(false, enriched["active"])
        assertNull(enriched["turnId"])
        assertNull(enriched["activeTurnId"])
    }

    @Test
    fun withLocalIdsOmitsActiveFieldsWhenNotProvided() {
        val response = mapOf<String, Any?>("thread" to mapOf("id" to "thread-1"))

        val enriched = response.withLocalIds(
            threadId = "thread-1",
            conversationId = null,
        )

        assertEquals("thread-1", enriched["threadId"])
        assertEquals(false, enriched.containsKey("active"))
        assertEquals(false, enriched.containsKey("activeTurnId"))
        assertEquals(false, enriched.containsKey("turnId"))
    }

    @Test
    fun buildCodexAgentFilesUseAuthJsonAndResponsesProviderConfig() {
        val config = buildCodexConfigToml(
            baseUrl = "https://example.com/v1",
            model = "custom-codex"
        )
        val auth = buildCodexAuthJson("sk-test")

        assertTrue(config.contains("model_provider = \"omnimind\""))
        assertTrue(config.contains("model = \"custom-codex\""))
        assertTrue(config.contains("base_url = \"https://example.com/v1\""))
        assertTrue(config.contains("wire_api = \"responses\""))
        assertTrue(config.contains("requires_openai_auth = true"))
        assertFalse(config.contains("env_key"))
        assertTrue(auth.contains("\"OPENAI_API_KEY\": \"sk-test\""))
    }
}
