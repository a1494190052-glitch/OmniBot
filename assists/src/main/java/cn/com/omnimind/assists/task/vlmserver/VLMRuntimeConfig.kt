package cn.com.omnimind.assists.task.vlmserver

data class VLMRuntimeConfig(
    val primarySceneId: String = "scene.vlm.operation.primary",
    val maxCompletionTokens: Int = 384,
    val temperature: Double = 0.2,
    val defaultMaxSteps: Int = 12,
    val maxHistoryRounds: Int = 4,
    val maxHistoryActionChars: Int = 160,
    val maxHistoryResultChars: Int = 220,
    val maxToolResultChars: Int = 900,
)

object VLMRuntimeConfigRegistry {
    @Volatile
    private var config = VLMRuntimeConfig()

    fun set(config: VLMRuntimeConfig) {
        this.config = config
    }

    fun get(): VLMRuntimeConfig = config
}
