package cn.com.omnimind.uikit.api.uievent

import android.content.Context

interface UITaskEvent {
    fun onUIInit(context: Context)

    suspend fun startCompanionAndDoingTask()

    suspend fun waitingUserAction(message: String): UserTakeoverAction

    suspend fun pauseTask(message: String)

    suspend fun readyDoingTask(message: String)

    suspend fun startDoingAutoTask(
        message: String,
        subMessage: String
    )

    suspend fun finishDoingTask(message: String)

    suspend fun setDoing(message: String, showTakeOver: Boolean)

    suspend fun showScheduledTip(closeTimer: Long, doTaskTimer: Long)
}
