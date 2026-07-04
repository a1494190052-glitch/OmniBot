package cn.com.omnimind.assists.util

import kotlinx.coroutines.delay

suspend fun <T : Any> pollUntilReady(
    intervalMs: Long = 100L,
    timeoutMs: Long = 3000L,
    check: suspend () -> T?
): T? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val result = runCatching { check() }.getOrNull()
        if (result != null) return result
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) break
        delay(minOf(intervalMs, remaining))
    }
    return null
}
