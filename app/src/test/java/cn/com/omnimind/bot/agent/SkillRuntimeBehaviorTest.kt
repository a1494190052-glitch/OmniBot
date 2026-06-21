package cn.com.omnimind.bot.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import cn.com.omnimind.bot.agent.tool.handlers.decodeImageWriteContentForFileName
import cn.com.omnimind.bot.agent.tool.handlers.ImageGenerationToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.normalizeSvgWriteContentForFileName
import cn.com.omnimind.bot.agent.tool.handlers.normalizeHatchPetWrite
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SkillRuntimeBehaviorTest {
    private fun entry(
        id: String,
        description: String,
        enabled: Boolean = true,
        installed: Boolean = true
    ): SkillIndexEntry {
        return SkillIndexEntry(
            id = id,
            name = id,
            description = description,
            rootPath = "/tmp/$id",
            shellRootPath = "/workspace/.omnibot/skills/$id",
            skillFilePath = "/tmp/$id/SKILL.md",
            shellSkillFilePath = "/workspace/.omnibot/skills/$id/SKILL.md",
            hasScripts = false,
            hasReferences = false,
            hasAssets = false,
            hasEvals = false,
            enabled = enabled,
            installed = installed
        )
    }

    @Test
    fun resolveMatchesSkipsDisabledAndUninstalledSkills() {
        val matches = SkillTriggerMatcher.resolveMatches(
            userMessage = "请用 skill-creator 帮我创建一个新的技能",
            entries = listOf(
                entry(
                    id = "skill-creator",
                    description = "用于创建和更新技能",
                    enabled = false
                ),
                entry(
                    id = "skill-creator",
                    description = "用于创建和更新技能",
                    installed = false
                )
            )
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun resolveMatchesFindSkillsFromChineseTriggerPhrase() {
        val matches = SkillTriggerMatcher.resolveMatches(
            userMessage = "帮我找个 skill 来处理 changelog",
            entries = listOf(
                entry(
                    id = "find-install-skills",
                    description = "Find and install relevant Omnibot skills. Use when the user asks \"找个 skill\", \"有没有这个功能的 skill\", \"find a skill for X\", \"is there a skill for X\", or wants to extend the agent with an installable workflow."
                )
            )
        )

        assertTrue(matches.any { it.entry.id == "find-install-skills" })
    }

    @Test
    fun omniflowVlmStepGuidanceKeepsExecutionRulesWithinCompactBudget() {
        val skill = ResolvedSkillContext(
            skillId = "omniflow",
            frontmatter = mapOf("name" to "omniflow"),
            bodyMarkdown = """
                ---
                name: omniflow
                ---

                # OmniFlow

                ## Online VLM Execution

                - Pass `package_name` when the target app is known; otherwise derive the package from installed-app or current-state evidence.
                - Use one fresh observation for each action turn: current package, Accessibility XML, indexed UI evidence, screenshot, display size, previous action result, and short history.
                - Choose exactly one executable UI action per step, then validate from the returned post-action observation before choosing the next step.
                - Prefer stable UI evidence: visible label, role, `node_id`, `element_index`, or `scrollable_index`. Include absolute screen-pixel coordinates only as fallback.
                - If an editable field is focused, use `input_text`. If it is visible but not focused, ground the field by label/index/coordinates before typing.
                - Do not finish after a single action unless the requested final state is directly visible.

                ## RunLog And Function Flow

                Replay registered Functions when the current package and page evidence match. Fall back to live VLM execution when guards fail, targets are missing, or user intent diverges.
                Auto-registration saves the replayable Function first. Do not call update_function or enhance inline before VLM RunLog registration, direct replay, recall-hit replay, or debug convert-and-replay.

                ## Validation

                Verify at least two visible UI states for nontrivial workflows.
            """.trimIndent(),
            triggerReason = "test"
        )

        val guidance = skill.vlmStepGuidance(maxChars = 900)

        assertTrue(guidance.length <= 900)
        assertTrue(guidance.contains("fresh observation"))
        assertTrue(guidance.contains("exactly one executable UI action"))
        assertTrue(guidance.contains("current package and page evidence match"))
        assertTrue(guidance.contains("fall back to live VLM"))
        assertTrue(guidance.contains("Do not call update_function or enhance inline"))
        assertFalse(guidance.contains("Verify at least two visible UI states"))
    }

    @Test
    fun vlmStepGuidanceSkipsUnrelatedSkills() {
        val skill = ResolvedSkillContext(
            skillId = "skill-creator",
            frontmatter = mapOf("name" to "skill-creator"),
            bodyMarkdown = """
                # Skill Creator

                ## Step Guidance Essentials

                - Create files in .omnibot/skills.
                - Write examples and references.
            """.trimIndent(),
            triggerReason = "test"
        )

        assertEquals("", skill.vlmStepGuidance())
    }

    @Test
    fun vlmStepGuidanceDoesNotTreatRetiredVlmSkillAsSeparateEntry() {
        val skill = ResolvedSkillContext(
            skillId = "vlm-android-gui",
            frontmatter = mapOf("name" to "vlm-android-gui"),
            bodyMarkdown = "# Retired VLM skill",
            triggerReason = "test"
        )

        assertEquals("", skill.vlmStepGuidance())
    }

    @Test
    fun resolveMatchesHatchPetFromStructuredChinesePetPrompt() {
        val matches = SkillTriggerMatcher.resolveMatches(
            userMessage = """
                帮我创建一只电子宠物。
                宠物名称：mimibear
                宠物类型：像素风小白熊
                视觉风格：极简，圆润可爱，轮廓清晰，适合桌面悬浮
                性格设定：温柔但认真
            """.trimIndent(),
            entries = listOf(
                entry(
                    id = "hatch-pet",
                    description = "Create an Omnibot 电子宠物 / 悬浮窗宠物 from prompts with 宠物名称, 宠物类型, 视觉风格, 性格设定."
                )
            )
        )

        assertTrue(matches.any { it.entry.id == "hatch-pet" })
    }

    @Test
    fun resolveMatchesHatchPetWhenUserOmitsHyphen() {
        val matches = SkillTriggerMatcher.resolveMatches(
            userMessage = "我想用 hatch pet 功能生成一个金毛宠物",
            entries = listOf(
                entry(
                    id = "hatch-pet",
                    description = "Create custom pets."
                )
            )
        )

        assertTrue(matches.any { it.entry.id == "hatch-pet" })
    }

    @Test
    fun resolveMatchesOmniFlowFromLegacyFunctionEnhancementAndCheckerPhrases() {
        val omniflowSkill = File("src/main/assets/builtin_skills/omniflow/SKILL.md")
            .readText()
        val omniflowEntry = entry(
            id = "omniflow",
            description = omniflowSkill
        )
        val requests = listOf(
            "把上一条 runlog 注册成复用指令",
            "增强 function，应该点外卖而不是点美食",
            "给这个复用指令加一个 hi 升级 checker",
            "debug package open-app checker"
        )

        requests.forEach { request ->
            val matches = SkillTriggerMatcher.resolveMatches(
                userMessage = request,
                entries = listOf(omniflowEntry)
            )
            assertTrue("Expected omniflow to match: $request", matches.any { it.entry.id == "omniflow" })
        }
    }

    @Test
    fun omniflowManifestDescriptionKeepsSingleSkillTriggerTermsInSync() {
        val manifest = Json.parseToJsonElement(
            File("src/main/assets/builtin_skills/manifest.json").readText()
        ).jsonObject
        val skillIds = manifest["skills"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        val omniflowEntry = manifest["skills"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["id"]?.jsonPrimitive?.content == "omniflow" }
        val omniflowDescription = omniflowEntry["description"]!!
            .jsonPrimitive
            .content

        listOf(
            "oob-function-management",
            "omniflow-function-enhancer",
            "omniflow-checker-maintainer",
            "vlm-android-gui"
        ).forEach { retiredId ->
            assertFalse("Retired focused skill must not be model-visible: $retiredId", retiredId in skillIds)
        }

        listOf(
            "reusable command",
            "execution-record",
            "vlm_task",
            "update_function",
            "automatic checker creation/management",
            "runtime checker",
            "global checker",
            "hi 升级 checker",
            "upgrade/update popup checker",
            "package/open-app checker",
            "复用指令",
            "轨迹"
        ).forEach { phrase ->
            assertTrue("Expected OmniFlow manifest trigger phrase: $phrase", omniflowDescription.contains(phrase))
        }
    }

    @Test
    fun retiredOmniFlowBuiltinSkillsAreHiddenUnlessUserInstalled() {
        val retiredIds = listOf(
            "oob-function-management",
            "omniflow-function-enhancer",
            "omniflow-checker-maintainer",
            "vlm-android-gui"
        )

        retiredIds.forEach { skillId ->
            assertEquals("omniflow", resolveRetiredBuiltinSkillAlias(skillId))
            assertEquals(
                "omniflow",
                resolveRetiredBuiltinSkillAlias("/workspace/.omnibot/skills/$skillId/SKILL.md")
            )
            assertTrue(
                "Expected retired builtin to be hidden without registry source: $skillId",
                shouldHideRetiredBuiltinSkill(skillId, null)
            )
            assertTrue(
                "Expected retired builtin to be hidden when it came from bundled assets: $skillId",
                shouldHideRetiredBuiltinSkill(skillId, "builtin")
            )
            assertTrue(
                "Expected retired builtin to be hidden when it came from official sync: $skillId",
                shouldHideRetiredBuiltinSkill(skillId, "official")
            )
            assertFalse(
                "User-installed skills are user content and must not be hidden: $skillId",
                shouldHideRetiredBuiltinSkill(skillId, "user")
            )
        }

        assertFalse(shouldHideRetiredBuiltinSkill("omniflow", null))
        assertEquals("omniflow", resolveRetiredBuiltinSkillAlias("omniflow"))
        assertEquals("custom-skill", resolveRetiredBuiltinSkillAlias("custom-skill"))
    }

    @Test
    fun builtinInstallCanonicalizesRetiredOmniFlowSkillIds() {
        val retiredIds = listOf(
            "oob-function-management",
            "omniflow-function-enhancer",
            "omniflow-checker-maintainer",
            "vlm-android-gui"
        )

        retiredIds.forEach { skillId ->
            assertEquals("omniflow", canonicalBuiltinSkillInstallId(skillId))
            assertEquals(
                "omniflow",
                canonicalBuiltinSkillInstallId("/workspace/.omnibot/skills/$skillId/SKILL.md")
            )
        }
        assertEquals("omniflow", canonicalBuiltinSkillInstallId("omniflow"))
        assertEquals("hatch-pet", canonicalBuiltinSkillInstallId("hatch-pet"))
    }

    @Test
    fun retiredOmniFlowSkillNamesMatchSingleOmniFlowEntry() {
        val omniflowEntry = entry(
            id = "omniflow",
            description = "Single OOB phone automation and reusable command skill."
        )
        val retiredIds = listOf(
            "oob-function-management",
            "omniflow-function-enhancer",
            "omniflow-checker-maintainer",
            "vlm-android-gui"
        )

        retiredIds.forEach { retiredId ->
            assertEquals(
                setOf(retiredId.replace("-", ""), "omniflow"),
                normalizedSkillLookupTerms(retiredId)
            )
            assertTrue(skillEntryMatchesLookupQuery(omniflowEntry, retiredId))
            assertTrue(skillEntryMatchesLookupQuery(omniflowEntry, "/workspace/.omnibot/skills/$retiredId/SKILL.md"))

            val matches = SkillTriggerMatcher.resolveMatches(
                userMessage = "请使用 $retiredId",
                entries = listOf(omniflowEntry)
            )

            assertTrue("Expected $retiredId to resolve to omniflow", matches.any { it.entry.id == "omniflow" })
        }
    }

    @Test
    fun builtinHatchPetSkillKeepsStandardPetOutputContract() {
        val skillFile = File("src/main/assets/builtin_skills/hatch-pet/SKILL.md")
        val text = skillFile.readText()

        assertTrue(text.contains("宠物名称"))
        assertTrue(text.contains("use that exact name") || text.contains("exact name"))
        assertTrue(text.contains("/workspace/.omnibot/pets/<pet-id>/spritesheet.webp"))
        assertTrue(text.contains("/workspace/.omnibot/pets/<pet-id>/pet.json"))
        assertTrue(text.contains("\"displayName\": \"mimibear\""))
        assertTrue(text.contains("\"spritesheetPath\": \"spritesheet.webp\""))
        assertTrue(text.contains("https://cloud.omnimind.com.cn"))
        assertTrue(text.contains("gpt-image-2"))
        assertFalse(Regex("""sk-[A-Za-z0-9]{20,}""").containsMatchIn(text))
        assertTrue(text.contains("Do not create files outside `/workspace/.omnibot/pets/<pet-id>/`"))
        assertTrue(text.contains("Never write"))
        assertTrue(text.contains("/workspace/.codex/pets"))
    }

    @Test
    fun imageGenerationDefaultsUseOmnimindImageProvider() {
        assertEquals(
            "https://cloud.omnimind.com.cn",
            ImageGenerationToolHandler.DEFAULT_IMAGE_BASE_URL
        )
        assertEquals("gpt-image-2", ImageGenerationToolHandler.DEFAULT_IMAGE_MODEL)
        assertTrue(AgentToolDefinitions.imageGenerateTool.toString().contains("gpt-image-2"))
    }

    @Test
    fun imageGenerationEndpointSupportsBaseAndFullEndpointUrls() {
        assertEquals(
            "https://cloud.omnimind.com.cn/v1/images/generations",
            ImageGenerationToolHandler.resolveImageGenerationEndpoint(
                "https://cloud.omnimind.com.cn",
                "sk-test"
            )
        )
        assertEquals(
            "https://cloud.omnimind.com.cn/v1/images/generations",
            ImageGenerationToolHandler.resolveImageGenerationEndpoint(
                "https://cloud.omnimind.com.cn/v1/images/generations",
                "sk-test"
            )
        )
        assertEquals(
            "https://cloud.omnimind.com.cn/custom/images",
            ImageGenerationToolHandler.resolveImageGenerationEndpoint(
                "https://cloud.omnimind.com.cn/custom/images#",
                "sk-test"
            )
        )
    }

    @Test
    fun hatchPetImageGenerationUsesBundledProviderEvenWhenUserProviderHasKey() {
        assertTrue(
            ImageGenerationToolHandler.shouldUseBundledImageProvider(
                activeSkillIds = setOf("hatch-pet"),
                profileApiKey = "dashscope-user-key",
                bundledApiKey = "sk-bundled"
            )
        )
        assertFalse(
            ImageGenerationToolHandler.shouldUseBundledImageProvider(
                activeSkillIds = emptySet(),
                profileApiKey = "dashscope-user-key",
                bundledApiKey = "sk-bundled"
            )
        )
        assertTrue(
            ImageGenerationToolHandler.shouldUseBundledImageProvider(
                activeSkillIds = emptySet(),
                profileApiKey = "",
                bundledApiKey = "sk-bundled"
            )
        )
    }

    @Test
    fun normalizeHatchPetWritePinsFilesToPromptPetName() {
        val override = normalizeHatchPetWrite(
            rawPath = "/workspace/.omnibot/pets/white-bear/pet.json",
            rawContent = """
                {
                  "id": "white-bear",
                  "displayName": "小白熊",
                  "imagePath": "pet.svg"
                }
            """.trimIndent(),
            userMessage = """
                帮我创建一只电子宠物。
                宠物名称：mimibear
                宠物类型：像素风小白熊
                视觉风格：极简，圆润可爱，轮廓清晰，适合桌面悬浮
                性格设定：温柔但认真，发现任务完成会开心提醒
            """.trimIndent(),
            activeSkillIds = setOf("hatch-pet")
        )

        assertNotNull(override)
        assertEquals("/workspace/.omnibot/pets/mimibear/pet.json", override!!.path)
        assertTrue(override.content.contains("\"id\": \"mimibear\""))
        assertTrue(override.content.contains("\"displayName\": \"mimibear\""))
        assertTrue(override.content.contains("\"imagePath\": \"current.svg\""))
    }

    @Test
    fun normalizeHatchPetWriteUsesChinesePromptNameForInlineFields() {
        val override = normalizeHatchPetWrite(
            rawPath = "/workspace/.omnibot/pets/pet-1cb4cefe6f/current.svg",
            rawContent = "<svg viewBox=\"0 0 512 512\"></svg>",
            userMessage = "帮我创建一只电子宠物。 宠物名称：我是猫头鹰  宠物类型：像素风小猫头鹰 视觉风格：极简，聪明安静，眼睛明显，适合桌面悬浮 性格设定：稳重但贴心，等待输入时会安静陪伴 主要颜色：棕色 + 米白色脸盘 标志元素：脖子上戴一个小绿色书签吊坠",
            activeSkillIds = setOf("hatch-pet")
        )

        assertNotNull(override)
        assertEquals("/workspace/.omnibot/pets/我是猫头鹰/current.svg", override!!.path)
        assertEquals("/workspace/.omnibot/pets/我是猫头鹰/pet.json", override.petJsonPath)
        assertTrue(override.petJsonContent!!.contains("\"displayName\": \"我是猫头鹰\""))
        assertTrue(override.petJsonContent!!.contains("\"petType\": \"像素风小猫头鹰\""))
        assertTrue(override.petJsonContent!!.contains("\"mainColors\": \"棕色 + 米白色脸盘\""))
        assertTrue(override.petJsonContent!!.contains("\"signatureElements\": \"脖子上戴一个小绿色书签吊坠\""))
    }

    @Test
    fun normalizeHatchPetWriteCreatesMetadataForImageOnlyPetWrites() {
        val override = normalizeHatchPetWrite(
            rawPath = "/workspace/pets/jinmao.svg",
            rawContent = "<svg viewBox=\"0 0 512 512\"></svg>",
            userMessage = """
                帮我创建一只电子宠物。
                宠物名称：jinmao
                宠物类型：像素风金毛
                视觉风格：极简，轮廓清晰，适合桌面悬浮
                性格设定：活泼但靠谱
            """.trimIndent(),
            activeSkillIds = setOf("hatch-pet")
        )

        assertNotNull(override)
        assertEquals("/workspace/.omnibot/pets/jinmao/current.svg", override!!.path)
        assertEquals("/workspace/.omnibot/pets/jinmao/pet.json", override.petJsonPath)
        assertTrue(override.petJsonContent!!.contains("\"displayName\": \"jinmao\""))
        assertTrue(override.petJsonContent!!.contains("\"imagePath\": \"current.svg\""))
        assertTrue(override.petJsonContent!!.contains("像素风金毛"))
    }

    @Test
    fun normalizeHatchPetWriteKeepsGeneratedDescriptionShort() {
        val override = normalizeHatchPetWrite(
            rawPath = "/workspace/pets/mini-bear.svg",
            rawContent = "<svg viewBox=\"0 0 512 512\"></svg>",
            userMessage = """
                pet name: mini-bear
                pet type: pixel white bear
                visual style: minimal, round and cute, clear outline, suitable for desktop floating
                personality: gentle but serious, happily reminds you when tasks are done
                main colors: white + pale blue ears
                signature elements: small blue star badge on the chest
            """.trimIndent(),
            activeSkillIds = setOf("hatch-pet")
        )

        assertNotNull(override)
        val description = Regex("\"description\"\\s*:\\s*\"([^\"]+)\"")
            .find(override!!.petJsonContent!!)
            ?.groupValues
            ?.get(1)
        assertNotNull(description)
        assertTrue(description!!.length <= 34)
        assertFalse(description.contains("main colors"))
        assertFalse(description.contains("signature"))
    }

    @Test
    fun normalizeHatchPetWriteUsesPathNameWhenPromptNameIsMissing() {
        val override = normalizeHatchPetWrite(
            rawPath = "/workspace/.codex/pets/sparkfox/spritesheet.webp",
            rawContent = "webp-bytes-placeholder",
            userMessage = "用 hatch pet 生成一个红狐狸宠物",
            activeSkillIds = setOf("hatch-pet")
        )

        assertNotNull(override)
        assertEquals("/workspace/.omnibot/pets/sparkfox/spritesheet.webp", override!!.path)
        assertEquals("/workspace/.omnibot/pets/sparkfox/pet.json", override.petJsonPath)
        assertTrue(override.petJsonContent!!.contains("\"displayName\": \"sparkfox\""))
        assertTrue(override.petJsonContent!!.contains("\"spritesheetPath\": \"spritesheet.webp\""))
    }

    @Test
    fun decodeImageWriteContentAcceptsBase64DataUriForBinaryPetImages() {
        val pngBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D
        )
        val decoded = decodeImageWriteContentForFileName(
            "current.png",
            "data:image/png;base64,iVBORw0KGgoAAAAN"
        )

        assertArrayEquals(pngBytes, decoded)
        assertEquals(null, decodeImageWriteContentForFileName("current.png", "not-image-data"))
        assertEquals(null, decodeImageWriteContentForFileName("current.svg", "iVBORw0KGgoAAAAN"))
    }

    @Test
    fun normalizeSvgWriteContentExtractsSvgFromMarkdownFence() {
        val svg = normalizeSvgWriteContentForFileName(
            "current.svg",
            """
                ```svg
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                  <circle cx="12" cy="12" r="6"/>
                </svg>
                ```
            """.trimIndent()
        )

        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.endsWith("</svg>"))
        assertFalse(svg.contains("```"))
    }

    @Test
    fun normalizeSvgWriteContentInlinesSimpleClassStyles() {
        val svg = normalizeSvgWriteContentForFileName(
            "current.svg",
            """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                  <defs>
                    <style>
                      .body { fill: #ffdc50; }
                      .accent { fill: #8b5a2b; stroke: #111111; stroke-width: 2; }
                    </style>
                  </defs>
                  <rect class="body" x="4" y="4" width="16" height="16"/>
                  <circle class="accent" cx="12" cy="12" r="4"/>
                </svg>
            """.trimIndent()
        )

        assertFalse(svg.contains("<style>"))
        assertFalse(svg.contains("""class="""))
        assertTrue(svg.contains("""fill="#ffdc50""""))
        assertTrue(svg.contains("""fill="#8b5a2b""""))
        assertTrue(svg.contains("""stroke="#111111""""))
    }

    @Test
    fun normalizeHatchPetWriteIgnoresNonPetWrites() {
        val override = normalizeHatchPetWrite(
            rawPath = "/workspace/.omnibot/skills/skill-creator/SKILL.md",
            rawContent = "hello",
            userMessage = "帮我创建一只电子宠物。",
            activeSkillIds = setOf("hatch-pet")
        )

        assertEquals(null, override)
    }

    @Test
    fun buildOmitsDisabledAndUninstalledSkillsFromPromptIndex() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-1",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = listOf(
                entry(
                    id = "active-skill",
                    description = "可正常使用的技能"
                ),
                entry(
                    id = "disabled-skill",
                    description = "已禁用的技能",
                    enabled = false
                ),
                entry(
                    id = "removed-skill",
                    description = "已删除但可恢复的技能",
                    installed = false
                )
            ),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null
        )

        assertTrue(prompt.contains("id=active-skill"))
        assertFalse(prompt.contains("id=disabled-skill"))
        assertFalse(prompt.contains("id=removed-skill"))
    }

    @Test
    fun failureHookWritesErrorsLogAndFindsRelatedHints() {
        val skillsRoot = Files.createTempDirectory("self-improving-skill-test").toFile()
        val skillRoot = skillsRoot.resolve(SelfImprovingSkillFailureHook.SKILL_ID)
        val dataDir = skillRoot.resolve("data").apply { mkdirs() }
        val errorsFile = dataDir.resolve("ERRORS.md")
        errorsFile.writeText(
            """
            # Errors

            ## [ERR-20260409-OLD] terminal_execute

            **记录时间**: 2026-04-09T00:00:00Z
            **优先级**: high
            **状态**: pending
            **领域**: runtime

            ### 摘要
            旧的终端失败

            ---
            """.trimIndent() + "\n"
        )

        val payload = SelfImprovingSkillFailureHook.capture(
            skillsRoot = skillsRoot,
            skill = ResolvedSkillContext(
                skillId = SelfImprovingSkillFailureHook.SKILL_ID,
                frontmatter = mapOf("name" to SelfImprovingSkillFailureHook.SKILL_ID),
                bodyMarkdown = "先检查失败原因\n不要重复相同步骤",
                triggerReason = "test"
            ),
            userMessage = "修复终端命令报错",
            toolName = "terminal_execute",
            toolType = "terminal",
            argumentsJson = """{"command":"bad cmd"}""",
            result = ToolExecutionResult.TerminalResult(
                toolName = "terminal_execute",
                summaryText = "命令执行失败",
                previewJson = "{}",
                rawResultJson = """{"stderr":"not found"}""",
                success = false
            )
        )

        assertNotNull(payload)
        assertTrue(errorsFile.readText().contains("命令执行失败"))
        assertTrue(payload!!.guidance.contains("self-improving-agent"))
        assertTrue(payload.relatedHints.any { it.contains("ERR-20260409-OLD") })
    }
}
