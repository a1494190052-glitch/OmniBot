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

            assertTrue(prompt.contains("Turn reminder"))
            assertTrue(prompt.contains("exactly one native tool_call"))
            assertTrue(prompt.contains("black/blank"))
            assertTrue(prompt.contains("The system refreshes page state automatically each turn"))
            assertTrue(prompt.contains("do not output refresh-state"))
            assertTrue(prompt.contains("Do not output text actions"))
            assertTrue(prompt.contains("Raw XML is internal-only"))
            assertFalse(prompt.contains("1. Pick the next action directly from the tools list"))
            assertFalse(prompt.contains("8. Do not output any idle"))
            assertFalse(prompt.contains("fallback JSON"))
            assertFalse(prompt.contains("\"action\":\"swipe\""))
            assertFalse(prompt.contains("Accessibility tree"))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `system prompt does not expose stale get state or type tools`() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val prompt = PromptTemplate.buildSystemPrompt("scene.vlm.operation.primary")

            assertTrue(prompt.contains("tools[]"))
            assertTrue(prompt.contains("Raw XML is internal-only"))
            assertTrue(prompt.contains("compact indexed page evidence"))
            assertFalse(prompt.contains("get_state"))
            assertFalse(prompt.contains("type,"))
            assertFalse(prompt.contains("Accessibility tree"))
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
            assertFalse(prompt.contains("keyword:string:required"))
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
