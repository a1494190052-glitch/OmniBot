package cn.com.omnimind.baselib.llm

object OfficialVlmOperationRouteResolver {
    const val PROFILE_ID = "official-chatgpt-luna-vlm"
    const val PROFILE_NAME = "ChatGPT Luna"
    const val ROUTE_TAG = "official_chatgpt_luna_vlm"

    fun resolve(
        sceneId: String?,
        hasExplicitRoute: Boolean,
        hasSceneBinding: Boolean,
        sceneConfig: SceneOperationConfig,
        officialConfig: OfficialVlmOperationConfig
    ): OfficialVlmOperationConfig? {
        return officialConfig.takeIf {
            sceneId == SceneOperationConfigStore.SCENE_ID &&
                !hasExplicitRoute &&
                !hasSceneBinding &&
                sceneConfig.useOfficialService &&
                it.isConfigured()
        }
    }
}
