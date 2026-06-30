package cn.com.omnimind.baselib.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProviderConfigStoreTest {

    @Test
    fun normalizeBaseUrl_preservesCompatibleModeVersionBase() {
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            ModelProviderConfigStore.normalizeBaseUrl(
                "https://dashscope.aliyuncs.com/compatible-mode/v1/"
            )
        )
    }

    @Test
    fun hasVersionedBasePath_supportsV1AndCompatibleMode() {
        assertTrue(ModelProviderConfigStore.hasVersionedBasePath("https://api.example.com/v1"))
        assertTrue(
            ModelProviderConfigStore.hasVersionedBasePath(
                "https://dashscope.aliyuncs.com/compatible-mode/v1"
            )
        )
        assertFalse(ModelProviderConfigStore.hasVersionedBasePath("https://api.example.com"))
    }
}
