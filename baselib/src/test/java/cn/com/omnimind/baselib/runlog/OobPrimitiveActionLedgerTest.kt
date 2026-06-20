package cn.com.omnimind.baselib.runlog

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OobPrimitiveActionLedgerTest {
    @Test
    fun inputTextRecordsOnlyRedactedPlannerArgs() {
        OobPrimitiveActionLedger.resetForTesting()
        try {
            OobPrimitiveActionLedger.record(
                OobPrimitiveActionRecord(
                    source = "test",
                    tool = "input_text",
                    args = mapOf(
                        "target_description" to "Search",
                        "text" to "secret value",
                        "x" to 120,
                        "y" to 240,
                    ),
                    startedAtMs = 1000L,
                    finishedAtMs = 1200L,
                    success = true,
                )
            )

            val record = OobPrimitiveActionLedger.recentRecordsForTesting().single()
            assertEquals("input_text", record.tool)
            assertEquals("<redacted>", record.args["text"])
            assertEquals(true, record.args["text_present"])
            assertEquals(12, record.args["text_length"])
            assertEquals(true, record.args["text_redacted"])
            assertEquals("Search", record.args["target_description"])
        } finally {
            OobPrimitiveActionLedger.resetForTesting()
        }
    }

    @Test
    fun finishedMarkerIsNotRecordedAsPrimitivePlannerAction() {
        OobPrimitiveActionLedger.resetForTesting()
        try {
            OobPrimitiveActionLedger.record(
                OobPrimitiveActionRecord(
                    source = "test",
                    tool = "finished",
                    args = mapOf("content" to "done"),
                    startedAtMs = 1000L,
                    finishedAtMs = 1000L,
                    success = true,
                )
            )

            assertTrue(OobPrimitiveActionLedger.recentRecordsForTesting().isEmpty())
            assertFalse(OobPrimitiveActionLedger.shouldRecordForPlanner("finished"))
            assertTrue(OobPrimitiveActionLedger.shouldRecordForPlanner("click"))
        } finally {
            OobPrimitiveActionLedger.resetForTesting()
        }
    }

    @Test
    fun fileBackedRecordsCanBeReadForOfflinePlanner() {
        OobPrimitiveActionLedger.resetForTesting()
        val context = TempFilesContext()
        try {
            OobPrimitiveActionLedger.bind(context)
            OobPrimitiveActionLedger.record(
                OobPrimitiveActionRecord(
                    source = "mcp_act",
                    tool = "input_text",
                    args = mapOf(
                        "target_description" to "Search",
                        "text" to "private query",
                        "x" to 120,
                        "y" to 240,
                    ),
                    startedAtMs = 1000L,
                    finishedAtMs = 1200L,
                    success = true,
                )
            )

            val records = OobPrimitiveActionLedger.readRecentRecords(context, limit = 10)

            assertTrue(OobPrimitiveActionLedger.recordFile(context).exists())
            assertEquals(1, records.size)
            assertEquals("input_text", records.single().tool)
            assertEquals("<redacted>", records.single().args["text"])
            assertEquals(true, records.single().args["text_redacted"])
        } finally {
            OobPrimitiveActionLedger.resetForTesting()
            context.root.deleteRecursively()
        }
    }

    private class TempFilesContext : ContextWrapper(null) {
        val root: File = Files.createTempDirectory("primitive-action-ledger-test").toFile()

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = root
    }
}
