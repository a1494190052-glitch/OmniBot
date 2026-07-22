package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.ControlActExecutor
import cn.com.omnimind.assists.task.vlmserver.ControlActExecutorFactory

internal fun omniFlowControlActExecutorFactory(context: Context): ControlActExecutorFactory {
    val appContext = context.applicationContext
    return ControlActExecutorFactory { deviceOperator ->
        val adapter = OmniFlowReplayAdapter(appContext, deviceOperator)
        ControlActExecutor { tool, args, state ->
            adapter.controlAct(action = tool, args = args, state = state)
        }
    }
}
