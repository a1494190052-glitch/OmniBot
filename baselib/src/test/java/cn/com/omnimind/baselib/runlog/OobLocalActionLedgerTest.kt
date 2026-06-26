package cn.com.omnimind.baselib.runlog

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OobLocalActionLedgerTest {
    @Test
    fun inputTextRecordsOnlyRedactedPlannerArgs() {
        OobLocalActionLedger.resetForTesting()
        try {
            OobLocalActionLedger.record(
                OobLocalActionRecord(
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

            val record = OobLocalActionLedger.recentRecordsForTesting().single()
            assertEquals("input_text", record.tool)
            assertEquals("<redacted>", record.args["text"])
            assertEquals(true, record.args["text_present"])
            assertEquals(12, record.args["text_length"])
            assertEquals(true, record.args["text_redacted"])
            assertEquals("Search", record.args["target_description"])
        } finally {
            OobLocalActionLedger.resetForTesting()
        }
    }

    @Test
    fun finishedMarkerIsNotRecordedAsPrimitivePlannerAction() {
        OobLocalActionLedger.resetForTesting()
        try {
            OobLocalActionLedger.record(
                OobLocalActionRecord(
                    source = "test",
                    tool = "finished",
                    args = mapOf("content" to "done"),
                    startedAtMs = 1000L,
                    finishedAtMs = 1000L,
                    success = true,
                )
            )

            assertTrue(OobLocalActionLedger.recentRecordsForTesting().isEmpty())
            assertFalse(OobLocalActionLedger.shouldRecordForPlanner("finished"))
            assertTrue(OobLocalActionLedger.shouldRecordForPlanner("click"))
        } finally {
            OobLocalActionLedger.resetForTesting()
        }
    }

    @Test
    fun fileBackedRecordsCanBeReadForOfflinePlanner() {
        OobLocalActionLedger.resetForTesting()
        val context = TempFilesContext()
        try {
            OobLocalActionLedger.bind(context)
            OobLocalActionLedger.record(
                OobLocalActionRecord(
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

            val records = OobLocalActionLedger.readRecentRecords(context, limit = 10)

            assertTrue(OobLocalActionLedger.recordFile(context).exists())
            assertEquals(1, records.size)
            assertEquals("input_text", records.single().tool)
            assertEquals("<redacted>", records.single().args["text"])
            assertEquals(true, records.single().args["text_redacted"])
        } finally {
            OobLocalActionLedger.resetForTesting()
            context.root.deleteRecursively()
        }
    }

    private class TempFilesContext : ContextWrapper(null) {
        val root: File = Files.createTempDirectory("primitive-action-ledger-test").toFile()

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = root
    }
}
