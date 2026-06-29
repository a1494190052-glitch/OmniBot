package cn.com.omnimind.bot.runlog

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OobFunctionRunLogRecorderTest {
    @Test
    fun `records function step results as a finished internal run log`() {
        val context = TempFilesContext()
        try {
            val runId = "omniflow_run_recorder_${System.nanoTime()}"
            val startedAtMs = 1_700_000_000_000L
            val finishedAtMs = startedAtMs + 1_200L

            val record = OobFunctionRunLogRecorder.record(
                context = context,
                functionId = "fn_open_settings",
                functionSpec = linkedMapOf(
                    "function_id" to "fn_open_settings",
                    "name" to "Open Settings",
                    "description" to "Open Android Settings",
                ),
                runPayload = linkedMapOf(
                    "success" to true,
                    "run_id" to runId,
                    "audit_run_id" to runId,
                    "runner" to "fixed_replay",
                    "step_count" to 2,
                    "success_step_count" to 2,
                    "timing" to linkedMapOf(
                        "started_at_ms" to startedAtMs,
                        "finished_at_ms" to finishedAtMs,
                    ),
                    "step_results" to listOf(
	                        linkedMapOf(
	                            "step_id" to "open_app",
	                            "index" to 0,
	                            "tool" to "open_app",
	                            "executor" to "omniflow",
	                            "summary" to "Open Settings app",
	                            "success" to true,
	                            "started_at_ms" to startedAtMs,
                            "finished_at_ms" to startedAtMs + 300L,
                        ),
                        linkedMapOf(
                            "step_id" to "finished",
                            "index" to 1,
                            "tool" to "finished",
                            "summary" to "Settings opened",
                            "success" to true,
                            "started_at_ms" to startedAtMs + 300L,
                            "finished_at_ms" to finishedAtMs,
                        ),
                    ),
                ),
            )

            assertEquals(true, record["success"])

            val timeline = InternalRunLogStore.timelinePayload(context, runId)
            assertEquals(true, timeline["success"])
            assertEquals(true, timeline["run_finished"])
            assertEquals(true, timeline["run_success"])
            assertEquals("success", timeline["run_status"])
            assertEquals(startedAtMs, timeline["started_at_ms"])
            assertEquals(finishedAtMs, timeline["finished_at_ms"])

            val cards = timeline["cards"] as List<*>
            assertEquals(2, cards.size)
            val first = cards.first() as Map<*, *>
            assertEquals("open_app", first["tool_name"])
            assertEquals("Open Settings app", first["summary"])
            assertEquals("success", first["status"])
            assertEquals("hit", first["recall_kind"])
            assertEquals(null, first["compile_kind"])
            val header = first["header"] as Map<*, *>
            assertEquals("hit", header["recall_kind"])
            assertEquals(null, header["compile_kind"])
            assertTrue((first["duration_ms"] as Number).toLong() >= 300L)
        } finally {
            context.root.deleteRecursively()
        }
    }

    @Test
    fun `keeps replay evidence fields on function runlog cards`() {
        val context = TempFilesContext()
        try {
            val runId = "omniflow_run_evidence_${System.nanoTime()}"
            val screenshotPath = "/data/user/0/cn.com.omnimind.bot.debug/files/oob_runlog_artifacts/run/screenshots/0001_before.jpg"

            OobFunctionRunLogRecorder.record(
                context = context,
                functionId = "fn_click_contact",
                functionSpec = linkedMapOf(
                    "function_id" to "fn_click_contact",
                    "name" to "Click Contact",
                ),
                runPayload = linkedMapOf(
                    "success" to true,
                    "run_id" to runId,
                    "audit_run_id" to runId,
                    "runner" to "oob_function_direct_runner",
                    "step_count" to 1,
                    "success_step_count" to 1,
                    "step_results" to listOf(
                        linkedMapOf(
                            "step_id" to "step_1",
                            "index" to 0,
                            "tool" to "click",
                            "executor" to "omniflow",
                            "summary" to "Click Contacts",
                            "success" to true,
                            "args" to linkedMapOf("x" to 895.9, "y" to 2216.8),
                            "source_context" to linkedMapOf(
                                "src_ctx" to linkedMapOf(
                                    "xml_path" to "/tmp/before.xml",
                                    "screenshot_path" to screenshotPath,
                                ),
                                "action" to linkedMapOf("tool" to "click"),
                            ),
                            "before" to linkedMapOf(
                                "xml_path" to "/tmp/before.xml",
                                "screenshot_path" to screenshotPath,
                            ),
                        )
                    ),
                ),
            )

            val cards = InternalRunLogStore.timelinePayload(context, runId)["cards"] as List<*>
            val first = cards.first() as Map<*, *>
            val before = first["before"] as Map<*, *>
            val sourceContext = first["source_context"] as Map<*, *>
            val srcCtx = sourceContext["src_ctx"] as Map<*, *>
            assertEquals(screenshotPath, before["screenshot_path"])
            assertEquals(screenshotPath, srcCtx["screenshot_path"])
            assertEquals("click", first["tool_name"])
        } finally {
            context.root.deleteRecursively()
        }
    }

    private class TempFilesContext : ContextWrapper(null) {
        val root: File = Files.createTempDirectory("function-runlog-recorder-test").toFile()
        private val sharedPreferences = mutableMapOf<String, MemorySharedPreferences>()

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = root

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return sharedPreferences.getOrPut(name.orEmpty()) { MemorySharedPreferences() }
        }
    }

    private class MemorySharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = linkedMapOf<String, Any?>().apply {
            putAll(this@MemorySharedPreferences.values)
        }

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = values?.toSet() }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                apply { if (key != null) pending[key] = value }

            override fun remove(key: String?): SharedPreferences.Editor =
                apply { if (key != null) removals += key }

            override fun clear(): SharedPreferences.Editor = apply { clear = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clear) values.clear()
                removals.forEach(values::remove)
                values.putAll(pending)
            }
        }
    }
}
