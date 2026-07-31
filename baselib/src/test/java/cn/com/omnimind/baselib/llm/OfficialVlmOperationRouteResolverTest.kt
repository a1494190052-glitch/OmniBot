package cn.com.omnimind.baselib.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialVlmOperationRouteResolverTest {
    private val configured = OfficialVlmOperationConfig(
        enabled = true,
        apiBase = "https://chatgpt.example/codex",
        apiKey = "secret",
        model = "gpt-5.6-sol",
        wireApi = OpenAiWireApi.RESPONSES
    )

    @Test
    fun `official ChatGPT Luna route is the fresh default`() {
        val route = OfficialVlmOperationRouteResolver.resolve(
            sceneId = SceneOperationConfigStore.SCENE_ID,
            hasExplicitRoute = false,
            hasSceneBinding = false,
            sceneConfig = SceneOperationConfig(),
            officialConfig = configured
        )

        assertEquals(configured, route)
        assertEquals(OpenAiWireApi.RESPONSES, route?.wireApi)
    }

    @Test
    fun `explicit provider binding wins over official default`() {
        assertNull(
            OfficialVlmOperationRouteResolver.resolve(
                sceneId = SceneOperationConfigStore.SCENE_ID,
                hasExplicitRoute = false,
                hasSceneBinding = true,
                sceneConfig = SceneOperationConfig(),
                officialConfig = configured
            )
        )
    }

    @Test
    fun `incomplete official config is never selected`() {
        assertNull(
            OfficialVlmOperationRouteResolver.resolve(
                sceneId = SceneOperationConfigStore.SCENE_ID,
                hasExplicitRoute = false,
                hasSceneBinding = false,
                sceneConfig = SceneOperationConfig(),
                officialConfig = configured.copy(apiKey = "")
            )
        )
    }
}
