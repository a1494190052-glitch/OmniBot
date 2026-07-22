package cn.com.omnimind.assists.task.vlmserver

fun interface ControlActExecutor {
    suspend fun act(tool: String, args: Map<String, Any?>, state: State?): OperationResult
}

fun interface ControlActExecutorFactory {
    fun create(deviceOperator: DeviceOperator): ControlActExecutor
}

object ControlActExecutorRegistry {
    @Volatile
    private var factory: ControlActExecutorFactory? = null

    fun register(value: ControlActExecutorFactory) {
        factory = value
    }

    fun requireFactory(): ControlActExecutorFactory =
        requireNotNull(factory) { "control_act_executor_not_registered" }
}
