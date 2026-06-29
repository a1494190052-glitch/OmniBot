package cn.com.omnimind.bot.runlog

import org.junit.Assert.assertEquals
import org.junit.Test

class RunLogPagePackageInferenceTest {
    @Test
    fun `replay step source package falls back to node resource id`() {
        val step = mapOf(
            "args" to mapOf(
                "node_resource_id" to "com.google.android.dialer:id/tab_contacts",
            ),
            "source_context" to mapOf(
                "src_ctx" to mapOf(
                    "page_path" to "/tmp/before.xml",
                    "require_unique_action_signature" to false,
                ),
                "action" to mapOf(
                    "tool" to "click",
                    "node_resource_id" to "com.google.android.dialer:id/tab_contacts",
                ),
            ),
        )

        assertEquals("com.google.android.dialer", ReplayHelper.stepSourcePackage(step))
    }

    @Test
    fun `infers package from flattened activity component when package and xml are blank`() {
        assertEquals(
            "com.android.settings",
            RunLogPagePackageInference.effectivePackage(
                recordedPackage = "",
                xml = "",
                activityName = "com.android.settings/.Settings",
            )
        )
    }

    @Test
    fun `infers package from component info activity name`() {
        assertEquals(
            "com.google.android.deskclock",
            RunLogPagePackageInference.effectivePackage(
                recordedPackage = "android",
                xml = "",
                activityName = "ComponentInfo{com.google.android.deskclock/com.android.deskclock.DeskClock}",
            )
        )
    }

    @Test
    fun `xml package remains authoritative over activity fallback`() {
        val xml = """
            <hierarchy>
              <node package="com.example.target" resource-id="com.example.target:id/title" />
            </hierarchy>
        """.trimIndent()

        assertEquals(
            "com.example.target",
            RunLogPagePackageInference.effectivePackage(
                recordedPackage = "",
                xml = xml,
                activityName = "com.example.other/.MainActivity",
            )
        )
    }

    @Test
    fun `extracts class package prefix from fully qualified activity class`() {
        assertEquals(
            "com.example.app.ui",
            RunLogPagePackageInference.packageFromActivity("com.example.app.ui.MainActivity")
        )
    }
}
