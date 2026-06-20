package cn.com.omnimind.assists.task.vlmserver

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PromptTemplateTest {
    @Test
    fun `turn prompt only renders focused installed apps`() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val prompt = PromptTemplate.buildTurnUserPrompt(
                UIContext(
                    overallTask = "Open Android Settings and then open Display",
                    targetPackageName = "com.android.settings",
                    currentPackageName = "com.android.launcher3",
                    installedApplications = linkedMapOf(
                        "com.android.settings" to "Settings",
                        "com.android.launcher3" to "Launcher",
                        "com.google.android.contacts" to "Contacts",
                        "com.google.android.apps.messaging" to "Messages",
                        "com.example.unrelated" to "Unrelated"
                    )
                )
            )

            assertTrue(prompt.contains("Relevant installed apps"))
            assertTrue(prompt.contains("com.android.settings -> Settings"))
            assertTrue(prompt.contains("com.android.launcher3 -> Launcher"))
            assertFalse(prompt.contains("com.google.android.contacts -> Contacts"))
            assertFalse(prompt.contains("com.google.android.apps.messaging -> Messages"))
            assertFalse(prompt.contains("com.example.unrelated -> Unrelated"))
            assertTrue(prompt.contains("only focused candidates are shown"))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `turn prompt uses compact output reminder instead of repeating full protocol`() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val prompt = PromptTemplate.buildTurnUserPrompt(
                UIContext(
                    overallTask = "Open Settings",
                    targetPackageName = "com.android.settings",
                    installedApplications = linkedMapOf("com.android.settings" to "Settings")
                )
            )

            assertTrue(prompt.contains("Allowed tools this turn"))
            assertTrue(prompt.contains("exactly one native tool_call"))
            assertTrue(prompt.contains("schema.required"))
            assertTrue(prompt.contains("assistant.content may be empty"))
            assertTrue(prompt.contains("about-20-word summary only"))
            assertTrue(prompt.contains("click"))
            assertFalse(prompt.contains("input_text"))
            assertFalse(prompt.contains("long_press only for context menus"))
            assertTrue(prompt.contains("Action choice: use click for visible buttons"))
            assertFalse(prompt.contains("use input_text when known text must be typed"))
            assertTrue(prompt.contains("first decide whether the user's goal is already satisfied"))
            assertTrue(prompt.contains("search box or input field"))
            assertFalse(prompt.contains("required=["))
            assertFalse(prompt.contains("Unified action schema"))
            assertFalse(prompt.contains("observation/thought JSON"))
            assertFalse(prompt.contains("\"action\":\"swipe\""))
            assertFalse(prompt.contains("Accessibility tree"))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `turn prompt includes input text for search query tasks`() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val prompt = PromptTemplate.buildTurnUserPrompt(
                UIContext(
                    overallTask = "Search cats in Xiaohongshu",
                    targetPackageName = "com.xingin.xhs",
                    currentPackageName = "com.xingin.xhs"
                )
            )

            assertTrue(prompt.contains("Allowed tools this turn"))
            assertTrue(prompt.contains("click"))
            assertTrue(prompt.contains("input_text"))
            assertTrue(prompt.contains("use input_text when known text must be typed"))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `system prompt stays tool-call only for vlm`() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val prompt = PromptTemplate.buildSystemPrompt("scene.vlm.operation.primary")

            assertTrue(prompt.contains("tools[] JSON schema"))
            assertTrue(prompt.contains("schema.required"))
            assertTrue(prompt.contains("optional fields cannot replace required fields"))
            assertTrue(prompt.contains("assistant.content may be empty"))
            assertTrue(prompt.contains("about 20 words"))
            assertTrue(prompt.contains("First check whether the goal is already satisfied"))
            assertFalse(prompt.contains("observation"))
            assertFalse(prompt.contains("thought"))
            assertFalse(prompt.contains("required=["))
            assertFalse(prompt.contains("get_state"))
            assertFalse(prompt.contains("click/long_press use x/y"))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `turn prompt renders recent results without duplicating page or function tools`() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val prompt = PromptTemplate.buildTurnUserPrompt(
                UIContext(
                    overallTask = "Search cats in Xiaohongshu",
                    currentPackageName = "com.xingin.xhs",
                    currentPageSummary = "Current page: Xiaohongshu home",
                    trace = listOf(
                        UIStep(
                            observation = "home",
                            thought = "tap search",
                            action = ClickAction(
                                targetDescription = "Search",
                                x = 1170f,
                                y = 249f,
                            ),
                            result = "Click search did not change the page",
                            summary = "search click no effect",
                        )
                    ),
                    dynamicToolDefinitions = listOf(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", "xhs_search_keyword")
                            put("description", "Search Xiaohongshu by keyword")
                            put("parameters", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("keyword", buildJsonObject { put("type", "string") })
                                })
                                put("required", buildJsonArray { add("keyword") })
                            })
                        })
                    })
                )
            )

            assertTrue(prompt.contains("[Page Explanation]"))
            assertTrue(prompt.contains("Current page: Xiaohongshu home"))
            assertTrue(prompt.contains("[Recent Results]"))
            assertTrue(prompt.contains("click Search"))
            assertTrue(prompt.contains("Click search did not change the page"))
            assertFalse(prompt.contains("[Current Page]"))
            assertFalse(prompt.contains("Current package: com.xingin.xhs"))
            assertFalse(prompt.contains("[Available Functions]"))
            assertFalse(prompt.contains("tool=xhs_search_keyword"))
            assertFalse(prompt.contains("required=["))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `focused app ranking is capped and keeps exact target first`() {
        val apps = linkedMapOf<String, String>()
        apps["com.android.settings"] = "Settings"
        repeat(20) { index ->
            apps["com.example.settings$index"] = "Settings Tool $index"
        }

        val focused = PromptTemplate.focusedInstalledAppEntries(
            UIContext(
                overallTask = "Open settings",
                targetPackageName = "com.android.settings",
                installedApplications = apps
            )
        )

        assertTrue(focused.size <= 12)
        assertTrue(focused.first().key == "com.android.settings")
    }
}
