package cn.com.omnimind.assists.task.vlmserver

import kotlinx.coroutines.channels.Channel

internal sealed interface ManualTakeoverResolution {
    data object Continue : ManualTakeoverResolution
    data class Complete(val message: String) : ManualTakeoverResolution
    data object Cancel : ManualTakeoverResolution
}

internal class ManualTakeoverController {
    private val resolutionChannel = Channel<ManualTakeoverResolution>(Channel.CONFLATED)

    @Volatile
    var isActive: Boolean = false
        private set

    fun request() {
        isActive = true
    }

    fun resume(): Boolean = resolve(ManualTakeoverResolution.Continue)

    fun complete(message: String): Boolean {
        return resolve(ManualTakeoverResolution.Complete(message))
    }

    fun cancel() {
        if (!isActive) return
        resolutionChannel.trySend(ManualTakeoverResolution.Cancel)
    }

    suspend fun awaitResolution(): ManualTakeoverResolution {
        return try {
            resolutionChannel.receive()
        } finally {
            isActive = false
        }
    }

    private fun resolve(resolution: ManualTakeoverResolution): Boolean {
        if (!isActive) return false
        return resolutionChannel.trySend(resolution).isSuccess
    }
}
