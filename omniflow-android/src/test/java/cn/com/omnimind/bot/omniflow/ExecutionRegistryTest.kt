package cn.com.omnimind.bot.omniflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionRegistryTest {
    @Test
    fun `targeted stop cancels the active execution exactly once`() {
        val registry = ExecutionRegistry()
        var stopCount = 0
        val registration = registry.begin("run-1") { stopCount += 1 }

        assertFalse(registry.stop("another-run"))
        assertTrue(registry.stop("run-1"))
        assertFalse(registry.stop("run-1"))
        assertTrue(stopCount == 1)

        registry.end(registration)
        assertFalse(registry.stop())
    }
}
