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

    @Test
    fun filterDeletedOfficialProfiles_onlyRemovesOfficialProfiles() {
        val profiles = listOf(
            DeepSeekProvider.officialProfile(),
            ModelProviderProfile(id = "custom-provider", name = "Custom")
        )

        val filtered = ModelProviderConfigStore.filterDeletedOfficialProfiles(
            profiles,
            setOf(DeepSeekProvider.OFFICIAL_PROFILE_ID, "custom-provider")
        )

        assertEquals(listOf("custom-provider"), filtered.map { it.id })
    }

    @Test
    fun deletedOfficialProfileIds_roundTripDropsUnknownIds() {
        val encoded = ModelProviderConfigStore.encodeDeletedOfficialProfileIds(
            setOf(
                "missing-provider",
                MoonshotProvider.OFFICIAL_PROFILE_ID,
                DeepSeekProvider.OFFICIAL_PROFILE_ID
            )
        )

        val decoded = ModelProviderConfigStore.decodeDeletedOfficialProfileIds(encoded)

        assertEquals(
            setOf(DeepSeekProvider.OFFICIAL_PROFILE_ID, MoonshotProvider.OFFICIAL_PROFILE_ID),
            decoded
        )
    }
}
