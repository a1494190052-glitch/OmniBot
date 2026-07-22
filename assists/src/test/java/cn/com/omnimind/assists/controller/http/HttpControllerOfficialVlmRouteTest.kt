package cn.com.omnimind.assists.controller.http

import cn.com.omnimind.baselib.llm.OfficialVlmOperationConfig
import cn.com.omnimind.baselib.llm.SceneOperationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpControllerOfficialVlmRouteTest {
    @Test
    fun `operation scene uses configured official service when enabled`() {
        val route = HttpController.resolveChatCompletionRouteInfoForTest(
            modelOrScene = "scene.vlm.operation.primary",
            sceneOperationConfig = SceneOperationConfig(useOfficialService = true),
            officialVlmOperationConfig = OfficialVlmOperationConfig(
                enabled = true,
                apiBase = "https://official.example/v1",
                apiKey = "official-key",
                model = "official-vlm-model"
            )
        )

        assertEquals("official-vlm-model", route.resolvedModel)
        assertEquals("https://official.example/v1", route.apiBase)
        assertEquals("official_vlm_operation", route.routeTag)
        assertEquals("openai_compatible", route.protocolType)
        assertTrue(route.overrideApplied)
    }

    @Test
    fun `operation scene keeps normal route when official service is disabled`() {
        val route = HttpController.resolveChatCompletionRouteInfoForTest(
            modelOrScene = "scene.vlm.operation.primary",
            sceneOperationConfig = SceneOperationConfig(useOfficialService = false),
            officialVlmOperationConfig = configuredOfficialService()
        )

        assertNotEquals("official-vlm-model", route.resolvedModel)
        assertNull(route.apiBase)
        assertNotEquals("official_vlm_operation", route.routeTag)
    }

    @Test
    fun `official operation service does not affect other scenes`() {
        val route = HttpController.resolveChatCompletionRouteInfoForTest(
            modelOrScene = "scene.compactor.context.chat",
            sceneOperationConfig = SceneOperationConfig(useOfficialService = true),
            officialVlmOperationConfig = configuredOfficialService()
        )

        assertNotEquals("official-vlm-model", route.resolvedModel)
        assertNull(route.apiBase)
        assertNotEquals("official_vlm_operation", route.routeTag)
    }

    private fun configuredOfficialService() = OfficialVlmOperationConfig(
        enabled = true,
        apiBase = "https://official.example/v1",
        apiKey = "official-key",
        model = "official-vlm-model"
    )
}
