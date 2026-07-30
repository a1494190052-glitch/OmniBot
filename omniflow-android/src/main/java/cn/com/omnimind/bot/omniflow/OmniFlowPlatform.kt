package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import java.io.File

data class OmniFlowSkillLocation(
    val androidRoot: File,
    val shellRoot: String,
    val source: String,
)

interface OmniFlowPlatform {
    suspend fun startProcess(
        context: Context,
        command: String,
        environment: Map<String, String>,
    ): Process

    suspend fun ensurePython(context: Context, expectedVersion: String)

    suspend fun resolveRuntimeSkill(
        context: Context,
        refresh: Boolean,
    ): OmniFlowSkillLocation

    suspend fun bootstrapRuntimeSkill(
        context: Context,
        location: OmniFlowSkillLocation,
    )

    suspend fun reclaimRuntimeSkill(context: Context)

    suspend fun completeJson(request: ChatCompletionRequest): String
}
