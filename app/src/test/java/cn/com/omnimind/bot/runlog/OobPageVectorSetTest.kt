package cn.com.omnimind.bot.runlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OobPageVectorSetTest {
    @Test
    fun `page vector set encodes normalized 512 dimension page vector`() {
        val vector = requireNotNull(OobPageVectorSet.encode(SOURCE_XML, "com.example.settings"))

        assertEquals(OobPageVectorSet.PAGE_DIM, vector.vector.size)
        assertEquals("com.example.settings", vector.packageName)
        assertTrue(vector.elementCount >= 3)
        assertTrue(vector.actionableCount >= 1)
        assertEquals(false, ((vector.toMap()["privacy"] as Map<*, *>)["raw_xml_stored"]))
    }

    @Test
    fun `page vector prefers xml package when supplied foreground package is stale`() {
        val vector = requireNotNull(OobPageVectorSet.encode(SOURCE_XML, "com.android.launcher"))

        assertEquals("com.example.settings", vector.packageName)
    }

    @Test
    fun `page similarity is high for same page family and lower for different page`() {
        val source = requireNotNull(OobPageVectorSet.encode(SOURCE_XML, "com.example.settings"))
        val sameFamily = requireNotNull(OobPageVectorSet.encode(SOURCE_XML_VARIANT, "com.example.settings"))
        val other = requireNotNull(OobPageVectorSet.encode(OTHER_XML, "com.example.settings"))

        val sameScore = OobPageVectorSet.cosine(source.vector, sameFamily.vector)
        val otherScore = OobPageVectorSet.cosine(source.vector, other.vector)

        assertTrue("same page family should match, score=$sameScore", sameScore > 0.80f)
        assertTrue("different page should be lower, same=$sameScore other=$otherScore", sameScore > otherScore)
    }

    @Test
    fun `udeg node store recalls node by current page match`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val store = OobUdegNodeStore(context)
            val result = store.upsertFunction(
                functionId = "open_network_settings",
                functionSpec = functionSpecWithSourcePage("open_network_settings", SOURCE_XML),
            )
            assertEquals(true, result["success"])
            assertEquals(true, result["indexed"])

            val matches = store.recall(SOURCE_XML_VARIANT, "com.example.settings", topK = 3)
            assertTrue(matches.isNotEmpty())
            val first = matches.first()
            assertTrue(first.pageSimilarity > 0.80f)
            assertEquals(listOf("open_network_settings"), OobUdegNodeStore.functionIds(first.node))
            val nodeSkill = first.node["skill"] as? Map<*, *>
            assertEquals("udeg_node_skill", nodeSkill?.get("kind"))
            val activation = nodeSkill?.get("activation") as? Map<*, *>
            assertEquals("page_match", activation?.get("type"))
            assertEquals("decision_context", nodeSkill?.get("role"))
            assertEquals(OobUdegNodeStore.UDEG_DECISION_PATH, nodeSkill?.get("decision_path"))
            val frontmatter = nodeSkill?.get("frontmatter") as? Map<*, *>
            assertNotNull(frontmatter?.get("name"))
            assertNotNull(nodeSkill?.get("decision_guidance"))
            assertTrue(nodeSkill?.get("body")?.toString().orEmpty().contains("## Decision Context"))
            assertTrue(nodeSkill?.get("body")?.toString().orEmpty().contains("## Decision Rules"))

            val decisionContext = first.node["decision_context"] as? Map<*, *>
            assertEquals("decision", decisionContext?.get("role"))
            assertEquals("page_match_to_udeg_node", decisionContext?.get("entry_policy"))
            assertEquals(OobUdegNodeStore.UDEG_DECISION_PATH, decisionContext?.get("decision_path"))
            val recallMap = first.toMap()
            val recallDecisionContext = recallMap["decision_context"] as? Map<*, *>
            assertEquals("page_match_to_udeg_node", recallDecisionContext?.get("entry_policy"))
            assertEquals(OobUdegNodeStore.UDEG_DECISION_PATH, recallDecisionContext?.get("decision_path"))
            val nodeSkillContext = recallMap["node_skill_context"] as? Map<*, *>
            assertEquals("decision", nodeSkillContext?.get("role"))
            assertEquals("udeg_node_skill_like_decision_context", nodeSkillContext?.get("context_kind"))
            assertEquals("page_match_to_udeg_node", nodeSkillContext?.get("entry_policy"))
            assertEquals(OobUdegNodeStore.UDEG_DECISION_PATH, nodeSkillContext?.get("decision_path"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `udeg node recall soft penalizes package mismatch instead of hard filtering`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val store = OobUdegNodeStore(context)
            store.upsertFunction(
                functionId = "open_network_settings",
                functionSpec = functionSpecWithSourcePage("open_network_settings", SOURCE_XML),
            )

            val staleForegroundMatches = store.recall(SOURCE_XML_VARIANT, "com.android.launcher", topK = 3)
            assertTrue(staleForegroundMatches.isNotEmpty())
            assertEquals(listOf("open_network_settings"), OobUdegNodeStore.functionIds(staleForegroundMatches.first().node))

            val crossPackageMatches = store.recall(SOURCE_XML_OTHER_PACKAGE, "com.example.other", topK = 3)
            assertTrue(crossPackageMatches.isNotEmpty())
            val first = crossPackageMatches.first()
            assertTrue(first.reason.contains("package_soft_mismatch"))
            assertTrue(first.pageSimilarity < OobUdegNodeStore.STRONG_PAGE_MATCH_SCORE)
            assertEquals(listOf("open_network_settings"), OobUdegNodeStore.functionIds(first.node))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `observed page creates reusable udeg node analysis with vector match`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val store = OobUdegNodeStore(context)
            val first = requireNotNull(
                store.observePage(
                    OobUdegNodeStore.ObservedPage(
                        pageXml = SOURCE_XML,
                        packageName = "com.example.settings",
                        screenshotBase64 = "fake-screenshot",
                        goal = "open display settings",
                    )
                )
            )

            assertTrue(first.firstSeen)
            assertEquals(1.0f, first.pageSimilarity)
            val pageAnalysis = first.node["page_analysis"] as? Map<*, *>
            assertNotNull(pageAnalysis)
            val privacy = pageAnalysis?.get("privacy") as? Map<*, *>
            assertEquals(false, privacy?.get("raw_xml_stored"))
            assertEquals(false, privacy?.get("raw_screenshot_stored"))
            val visual = pageAnalysis?.get("visual_observation") as? Map<*, *>
            assertEquals(true, visual?.get("screenshot_present"))
            assertNotNull(visual?.get("screenshot_sha256"))
            assertTrue(pageAnalysis?.toString().orEmpty().contains("Display"))
            val summary = pageAnalysis?.get("summary") as? Map<*, *>
            assertTrue(summary?.get("actionables").toString().contains("Network"))
            assertTrue(first.toMap().toString().contains(OobUdegNodeStore.UDEG_DECISION_PATH))

            val second = requireNotNull(
                store.observePage(
                    OobUdegNodeStore.ObservedPage(
                        pageXml = SOURCE_XML,
                        packageName = "com.example.settings",
                    )
                )
            )
            assertEquals(false, second.firstSeen)
            assertEquals(first.node["node_id"], second.node["node_id"])
            val registry = second.node["_oob_registry"] as? Map<*, *>
            assertEquals(2, (registry?.get("seen_count") as Number).toInt())
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `observed page analysis does not persist editable field text`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val store = OobUdegNodeStore(context)
            val observed = requireNotNull(
                store.observePage(
                    OobUdegNodeStore.ObservedPage(
                        pageXml = EDITABLE_SECRET_XML,
                        packageName = "com.example.contacts",
                    )
                )
            )
            val pageAnalysisText = observed.node["page_analysis"].toString()
            assertTrue(pageAnalysisText.contains("Phone"))
            assertFalse(pageAnalysisText.contains("415-555-0130"))
            val privacy = ((observed.node["page_analysis"] as? Map<*, *>)?.get("privacy") as? Map<*, *>)
            assertEquals(false, privacy?.get("editable_text_stored"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `udeg store saves captured state artifacts and exports sanitized bundle`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val store = OobUdegNodeStore(context)
            val observedPage = OobUdegNodeStore.ObservedPage(
                pageXml = SOURCE_XML,
                packageName = "com.example.settings",
                activityName = "SettingsActivity",
                screenshotBase64 = "data:image/jpeg;base64,ZmFrZQ==",
                goal = "save current settings page",
            )
            val observed = requireNotNull(store.observePage(observedPage))
            val artifact = store.saveCapturedState(
                observedPage = observedPage,
                observation = observed,
                screenshotBytes = "fake".toByteArray(),
                capturedAtMs = 1234L,
            )

            assertTrue(java.io.File(artifact.xmlPath).exists())
            assertTrue(java.io.File(artifact.screenshotPath!!).exists())
            assertTrue(java.io.File(artifact.manifestPath).exists())
            assertEquals(observed.node["node_id"], artifact.nodeId)

            val export = store.exportBundle()
            assertEquals(true, export["success"])
            assertEquals("oob.udeg.export.v1", export["schema_version"])
            assertTrue(java.io.File(export["export_path"].toString()).exists())
            val payload = export["payload"] as? Map<*, *>
            assertEquals("oob_udeg_export", payload?.get("kind"))
            assertEquals(false, (payload?.get("privacy") as? Map<*, *>)?.get("export_contains_raw_xml"))
            assertTrue((payload?.get("nodes") as? List<*>)?.isNotEmpty() == true)
            assertFalse(payload.toString().contains(SOURCE_XML.trim()))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `udeg export contains function and call function edges without segment payloads`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val store = OobUdegNodeStore(context)
            val result = store.upsertFunction(
                functionId = "settings_two_step",
                functionSpec = multiStepFunctionSpecWithBoundaries(),
            )

            assertEquals(true, result["success"])
            assertEquals(2, (result["raw_action_edge_count"] as Number).toInt())
            assertEquals(3, (result["edge_count"] as Number).toInt())

            val payload = store.exportBundle()["payload"] as Map<*, *>
            val edges = (payload["edges"] as List<*>).mapNotNull { it as? Map<*, *> }
            val callEdges = edges.filter { it["kind"] == "function_call" }
            val functionEdges = edges.filter { it["kind"] == "function" }

            assertEquals(1, callEdges.size)
            assertEquals("settings_two_step", callEdges.first()["function_id"])
            assertEquals(true, callEdges.first()["callable"])
            val orderedFunctionEdges = functionEdges.sortedBy { (it["step_index"] as Number).toInt() }
            assertEquals(edges.toString(), 2, orderedFunctionEdges.size)
            assertEquals(listOf(0, 1), orderedFunctionEdges.map { (it["step_index"] as Number).toInt() })
            assertEquals(listOf("click", "input_text"), orderedFunctionEdges.map { it["action_type"] })

            val rawActionEdges = (payload["raw_action_edges"] as List<*>).mapNotNull { it as? Map<*, *> }
            assertEquals(functionEdges.size, rawActionEdges.size)
            assertTrue(rawActionEdges.all { it["kind"] == "function" })

            val serialized = payload.toString()
            assertFalse(serialized.contains("function_segment"))
            assertFalse(serialized.contains("segment_hit"))
            assertFalse(serialized.contains("segment_candidates"))
            assertFalse(serialized.contains("node_segment_capabilities"))
            assertFalse(serialized.contains("segment_start_step_index"))
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `udeg stores source page action pair without destination context`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val store = OobUdegNodeStore(context)
            val result = store.upsertFunction(
                functionId = "source_only_click",
                functionSpec = functionSpecWithSourcePage("source_only_click", SOURCE_XML),
            )

            assertEquals(true, result["success"])
            assertEquals(1, (result["raw_action_edge_count"] as Number).toInt())
            assertEquals(0, (result["skipped_edge_count"] as? Number)?.toInt() ?: 0)

            val payload = store.exportBundle()["payload"] as Map<*, *>
            val rawActionEdges = (payload["raw_action_edges"] as List<*>).mapNotNull { it as? Map<*, *> }
            val edge = rawActionEdges.first { it["function_id"] == "source_only_click" }
            assertEquals("click", edge["action_type"])
            assertTrue(edge["from_node_id"].toString().isNotBlank())
            assertEquals(null, edge["to_node_id"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `udeg finds route safe function from current node to target function start node`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val store = OobUdegNodeStore(context)
            store.upsertFunction(
                functionId = "search_contact",
                functionSpec = functionSpecWithTransition(
                    functionId = "search_contact",
                    name = "Search contact",
                    description = "Search a contact from the Contacts page.",
                    srcXml = OTHER_XML,
                    dstXml = SOURCE_XML_VARIANT,
                ),
            )
            val targetNodeId = store.bestNodeIdForPage(OTHER_XML, "com.example.settings")
            assertTrue(targetNodeId.isNotBlank())

            store.upsertFunction(
                functionId = "type_contacts_from_settings",
                functionSpec = functionSpecWithTransition(
                    functionId = "type_contacts_from_settings",
                    name = "Type contacts from settings",
                    description = "Semantic input function with the same endpoints.",
                    srcXml = SOURCE_XML,
                    dstXml = OTHER_XML,
                    tool = "input_text",
                ),
            )
            store.upsertFunction(
                functionId = "route_contacts_from_settings",
                functionSpec = functionSpecWithTransition(
                    functionId = "route_contacts_from_settings",
                    name = "Route contacts from settings",
                    description = "Move from Settings to Contacts.",
                    srcXml = SOURCE_XML,
                    dstXml = OTHER_XML,
                ),
            )

            val goTo = store.findGoToFunction(
                currentXml = SOURCE_XML_VARIANT,
                currentPackage = "com.example.settings",
                targetNodeId = targetNodeId,
            )

            assertEquals("route_contacts_from_settings", goTo["function_id"])
            assertEquals(targetNodeId, goTo["to_node_id"])
            assertEquals(true, goTo["callable"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `udeg does not treat input function edge as go to function`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val store = OobUdegNodeStore(context)
            val targetNodeId = store.bestNodeIdForPage(OTHER_XML, "com.example.settings")
                .ifBlank {
                    store.upsertFunction(
                        functionId = "target_contacts",
                        functionSpec = functionSpecWithTransition(
                            functionId = "target_contacts",
                            name = "Target contacts",
                            description = "Target setup.",
                            srcXml = OTHER_XML,
                            dstXml = SOURCE_XML_VARIANT,
                        ),
                    )
                    store.bestNodeIdForPage(OTHER_XML, "com.example.settings")
                }
            assertTrue(targetNodeId.isNotBlank())

            store.upsertFunction(
                functionId = "type_contact_from_settings",
                functionSpec = functionSpecWithTransition(
                    functionId = "type_contact_from_settings",
                    name = "Type contact from settings",
                    description = "A semantic input function, not a route bridge.",
                    srcXml = SOURCE_XML,
                    dstXml = OTHER_XML,
                    tool = "input_text",
                ),
            )

            val goTo = store.findGoToFunction(
                currentXml = SOURCE_XML,
                currentPackage = "com.example.settings",
                targetNodeId = targetNodeId,
            )

            assertTrue(goTo.isEmpty())
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `udeg composes route safe functions to target node`() {
        val context = OobOmniFlowLoopAcceptanceTest.TempFilesContext()
        try {
            val store = OobUdegNodeStore(context)
            val pageB = pageXml("B", "com.example.settings")
            val pageC = pageXml("C", "com.example.settings")
            store.upsertFunction(
                functionId = "target_c",
                functionSpec = functionSpecWithTransition(
                    functionId = "target_c",
                    name = "Target C",
                    description = "Target setup.",
                    srcXml = pageC,
                    dstXml = SOURCE_XML_VARIANT,
                    tool = "open_app",
                ),
            )
            val targetNodeId = store.bestNodeIdForPage(pageC, "com.example.settings")
            assertTrue(targetNodeId.isNotBlank())

            store.upsertFunction(
                functionId = "route_a_b",
                functionSpec = functionSpecWithTransition(
                    functionId = "route_a_b",
                    name = "Route A B",
                    description = "Route from A to B.",
                    srcXml = SOURCE_XML,
                    dstXml = pageB,
                    tool = "open_app",
                ),
            )
            store.upsertFunction(
                functionId = "route_b_c",
                functionSpec = functionSpecWithTransition(
                    functionId = "route_b_c",
                    name = "Route B C",
                    description = "Route from B to C.",
                    srcXml = pageB,
                    dstXml = pageC,
                    tool = "press_key",
                    args = mapOf("key" to "back"),
                ),
            )

            val path = store.findGoToFunctions(
                currentXml = SOURCE_XML,
                currentPackage = "com.example.settings",
                targetNodeId = targetNodeId,
            )

            assertEquals(listOf("route_a_b", "route_b_c"), path.map { it["function_id"] })
        } finally {
            context.root.deleteRecursively()
        }
    }

    private fun functionSpecWithSourcePage(functionId: String, xml: String): Map<String, Any?> = mapOf(
        "schema_version" to "oob.reusable_function.v1",
        "function_id" to functionId,
        "name" to "Open network settings",
        "description" to "Open network settings from the Settings page.",
        "parameters" to emptyList<Map<String, Any?>>(),
        "execution" to mapOf(
            "kind" to "tool_sequence",
            "steps" to listOf(
                mapOf(
                    "id" to "step_1",
                    "title" to "Tap Network",
                    "tool" to "click",
                    "omniflow_action" to "click",
                    "source_context" to mapOf(
                        "src_ctx" to mapOf(
                            "page" to xml,
                            "package_name" to "com.example.settings",
                        )
                    )
                )
            )
        )
    )

    private fun functionSpecWithTransition(
        functionId: String,
        name: String,
        description: String,
        srcXml: String,
        dstXml: String,
        tool: String = "click",
        args: Map<String, Any?>? = null,
    ): Map<String, Any?> = mapOf(
        "schema_version" to "oob.reusable_function.v1",
        "function_id" to functionId,
        "name" to name,
        "description" to description,
        "parameters" to emptyList<Map<String, Any?>>(),
        "source" to mapOf("run_id" to "run_$functionId"),
        "execution" to mapOf(
            "kind" to "tool_sequence",
            "steps" to listOf(
                mapOf(
                    "id" to "step_1",
                    "index" to 0,
                    "title" to name,
                    "tool" to tool,
                    "omniflow_action" to tool,
                    "args" to (args ?: argsForTestAction(tool, name)),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf(
                            "page" to srcXml,
                            "package_name" to "com.example.settings",
                        ),
                        "dst_ctx" to mapOf(
                            "page" to dstXml,
                            "package_name" to "com.example.settings",
                        ),
                    ),
                )
            )
        )
    )

    private fun argsForTestAction(tool: String, name: String): Map<String, Any?> =
        when (tool) {
            "open_app" -> mapOf("package_name" to "com.example.settings")
            "input_text" -> mapOf(
                "target_description" to name,
                "text" to "hello",
            )
            "press_key" -> mapOf("key" to "back")
            else -> mapOf(
                "target_description" to name,
                "x" to 540,
                "y" to 300,
            )
        }

    private fun pageXml(label: String, packageName: String): String = """
        <hierarchy>
          <node class="android.widget.FrameLayout" package="$packageName" bounds="[0,0][1080,1920]">
            <node class="android.widget.TextView" package="$packageName" text="$label" bounds="[32,64][400,160]" />
            <node class="android.widget.TextView" package="$packageName" text="Action $label" clickable="true" enabled="true" bounds="[32,240][1048,360]" />
          </node>
        </hierarchy>
    """.trimIndent()

    private fun multiStepFunctionSpecWithBoundaries(): Map<String, Any?> = mapOf(
        "schema_version" to "oob.reusable_function.v1",
        "function_id" to "settings_two_step",
        "name" to "Settings two step",
        "description" to "Tap Network and type a search term.",
        "parameters" to emptyList<Map<String, Any?>>(),
        "source" to mapOf("run_id" to "run_settings_two_step"),
        "execution" to mapOf(
            "kind" to "tool_sequence",
            "steps" to listOf(
                mapOf(
                    "id" to "step_1",
                    "index" to 0,
                    "title" to "Tap Network",
                    "tool" to "click",
                    "omniflow_action" to "click",
                    "args" to mapOf(
                        "target_description" to "Network",
                        "x" to 540,
                        "y" to 300,
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf(
                            "page" to SOURCE_XML,
                            "package_name" to "com.example.settings",
                        ),
                        "dst_ctx" to mapOf(
                            "page" to OTHER_XML,
                            "package_name" to "com.example.settings",
                        ),
                    ),
                ),
                mapOf(
                    "id" to "step_2",
                    "index" to 1,
                    "title" to "Type query",
                    "tool" to "input_text",
                    "omniflow_action" to "input_text",
                    "args" to mapOf(
                        "target_description" to "Phone",
                        "text" to "network",
                    ),
                    "source_context" to mapOf(
                        "src_ctx" to mapOf(
                            "page" to OTHER_XML,
                            "package_name" to "com.example.settings",
                        ),
                        "dst_ctx" to mapOf(
                            "page" to SOURCE_XML_VARIANT,
                            "package_name" to "com.example.settings",
                        ),
                    ),
                ),
            ),
        ),
    )

    private companion object {
        const val SOURCE_XML = """
            <hierarchy>
              <node class="android.widget.FrameLayout" package="com.example.settings" bounds="[0,0][1080,1920]">
                <node class="android.widget.TextView" package="com.example.settings" text="Settings" bounds="[32,64][400,160]" />
                <node class="android.widget.TextView" package="com.example.settings" text="Network" clickable="true" enabled="true" bounds="[32,240][1048,360]" resource-id="com.example.settings:id/network" />
                <node class="android.widget.TextView" package="com.example.settings" text="Display" clickable="true" enabled="true" bounds="[32,380][1048,500]" />
              </node>
            </hierarchy>
        """

        const val SOURCE_XML_VARIANT = """
            <hierarchy>
              <node class="android.widget.FrameLayout" package="com.example.settings" bounds="[0,0][1080,1920]">
                <node class="android.widget.TextView" package="com.example.settings" text="Settings" bounds="[40,70][410,165]" />
                <node class="android.widget.TextView" package="com.example.settings" text="Network" clickable="true" enabled="true" bounds="[40,250][1040,372]" resource-id="com.example.settings:id/network" />
                <node class="android.widget.TextView" package="com.example.settings" text="Display" clickable="true" enabled="true" bounds="[40,392][1040,514]" />
              </node>
            </hierarchy>
        """

        const val SOURCE_XML_OTHER_PACKAGE = """
            <hierarchy>
              <node class="android.widget.FrameLayout" package="com.example.other" bounds="[0,0][1080,1920]">
                <node class="android.widget.TextView" package="com.example.other" text="Settings" bounds="[40,70][410,165]" />
                <node class="android.widget.TextView" package="com.example.other" text="Network" clickable="true" enabled="true" bounds="[40,250][1040,372]" resource-id="com.example.other:id/network" />
                <node class="android.widget.TextView" package="com.example.other" text="Display" clickable="true" enabled="true" bounds="[40,392][1040,514]" />
              </node>
            </hierarchy>
        """

        const val OTHER_XML = """
            <hierarchy>
              <node class="android.widget.FrameLayout" package="com.example.settings" bounds="[0,0][1080,1920]">
                <node class="android.widget.TextView" package="com.example.settings" text="Contacts" bounds="[32,64][400,160]" />
                <node class="android.widget.EditText" package="com.example.settings" text="" hint-text="First name" editable="true" focusable="true" bounds="[32,240][1048,340]" />
                <node class="android.widget.EditText" package="com.example.settings" text="" hint-text="Phone" editable="true" focusable="true" bounds="[32,380][1048,480]" />
              </node>
            </hierarchy>
        """

        const val EDITABLE_SECRET_XML = """
            <hierarchy>
              <node class="android.widget.FrameLayout" package="com.example.contacts" bounds="[0,0][1080,1920]">
                <node class="android.widget.TextView" package="com.example.contacts" text="Create contact" bounds="[32,64][520,160]" />
                <node class="android.widget.EditText" package="com.example.contacts" text="415-555-0130" hint-text="Phone" editable="true" focusable="true" bounds="[32,240][1048,340]" />
                <node class="android.widget.TextView" package="com.example.contacts" text="Save" clickable="true" enabled="true" bounds="[850,64][1048,160]" />
              </node>
            </hierarchy>
        """
    }
}
