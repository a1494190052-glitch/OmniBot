package cn.com.omnimind.baselib.runlog

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class InternalRunLogStoreTimelineTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        storageDir().deleteRecursively()
    }

    @After
    fun tearDown() {
        storageDir().deleteRecursively()
    }

    @Test
    fun timelinePayloadPreservesPersistedStateXml() {
        val runId = "timeline-preserves-state-xml"
        val beforeStateId = "$runId-before"
        val afterStateId = "$runId-after"
        val beforeXml = "<hierarchy><node text=\"before\" /></hierarchy>"
        val afterXml = "<hierarchy><node text=\"after\" /></hierarchy>"

        InternalRunLogStore.beginRun(
            context = context,
            runId = runId,
            goal = "preserve state XML",
            source = "manual",
        )
        InternalRunLogStore.upsertRecordedStep(
            context = context,
            runId = runId,
            record = RunLogStepRecord(
                step = canonicalStep(beforeStateId, afterStateId),
                states = listOf(
                    mapOf("state_id" to beforeStateId, "xml" to beforeXml),
                    mapOf("state_id" to afterStateId, "xml" to afterXml),
                ),
            ),
        )

        assertEquals(beforeXml, InternalRunLogStore.statePayload(context, beforeStateId)["xml"])
        assertEquals(afterXml, InternalRunLogStore.statePayload(context, afterStateId)["xml"])

        InternalRunLogStore.timelinePayload(context, runId)

        assertEquals(beforeXml, InternalRunLogStore.statePayload(context, beforeStateId)["xml"])
        assertEquals(afterXml, InternalRunLogStore.statePayload(context, afterStateId)["xml"])
    }

    @Test
    fun referenceOnlyStepUpdatePreservesPersistedStateXml() {
        val runId = "reference-update-preserves-state-xml"
        val stepId = "$runId-step"
        val beforeStateId = "$runId-before"
        val afterStateId = "$runId-after"
        val beforeXml = "<hierarchy><node text=\"before\" /></hierarchy>"
        val afterXml = "<hierarchy><node text=\"after\" /></hierarchy>"

        InternalRunLogStore.beginRun(
            context = context,
            runId = runId,
            goal = "preserve immutable state XML",
            source = "manual",
        )
        InternalRunLogStore.upsertRecordedStep(
            context = context,
            runId = runId,
            record = RunLogStepRecord(
                step = canonicalStep(beforeStateId, afterStateId),
                states = listOf(
                    mapOf("state_id" to beforeStateId, "xml" to beforeXml),
                    mapOf("state_id" to afterStateId, "xml" to afterXml),
                ),
            ),
        )
        InternalRunLogStore.upsertRecordedStep(
            context = context,
            runId = runId,
            record = RunLogStepRecord(
                step = canonicalStep(beforeStateId, afterStateId),
                states = listOf(
                    mapOf("state_id" to beforeStateId),
                    mapOf("state_id" to afterStateId),
                ),
            ),
        )

        assertEquals(beforeXml, InternalRunLogStore.statePayload(context, beforeStateId)["xml"])
        assertEquals(afterXml, InternalRunLogStore.statePayload(context, afterStateId)["xml"])
    }

    @Test
    fun snapshotIsCanonicalRunLogWithoutStoragePathsOrCamelCaseAliases() {
        val runId = "canonical-snapshot"
        InternalRunLogStore.beginRun(
            context = context,
            runId = runId,
            goal = "save canonical runlog",
            source = "manual",
        )
        InternalRunLogStore.finishRun(
            context = context,
            runId = runId,
            success = true,
            doneReason = "completed",
        )

        val snapshot = storageDir().listFiles()
            .orEmpty()
            .single { it.isFile && it.extension == "json" }
            .readText()
        val json = JSONObject(snapshot)
        val metadata = json.getJSONObject("metadata")

        assertEquals("omniflow.canonical_run_log.v1", json.getString("schema_version"))
        assertEquals(runId, json.getString("run_id"))
        assertEquals(true, json.getBoolean("success"))
        assertEquals("manual", metadata.getString("source"))
        assertEquals(false, json.has("runId"))
        assertEquals(false, json.has("finishedAtMs"))
        assertEquals(false, json.has("finalState"))
        assertEquals(false, metadata.has("event_log_path"))
        assertEquals(false, metadata.has("run_storage_dir"))
    }

    private fun canonicalStep(
        beforeStateId: String,
        afterStateId: String,
    ): Map<String, Any?> = linkedMapOf(
        "step_index" to 0,
        "before_state_id" to beforeStateId,
        "action" to mapOf("tool" to "wait", "args" to mapOf("duration_ms" to 1000)),
        "result" to mapOf("success" to true),
        "after_state_id" to afterStateId,
        "metadata" to mapOf(
            "step_id" to "test-step-0",
            "status" to "succeeded",
            "summary" to "waited",
            "source" to "test",
        ),
    )

    private fun storageDir(): File = File(context.filesDir, "run_logs")
}
