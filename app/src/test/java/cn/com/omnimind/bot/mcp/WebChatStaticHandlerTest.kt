package cn.com.omnimind.bot.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebChatStaticHandlerTest {
    @Test
    fun duplicatedFlutterAssetFallsBackToMobileAsset() {
        val candidates = WebChatStaticHandler.assetCandidatesForPath(
            "assets/assets/home/chatbox_bg.png"
        )

        assertEquals(
            listOf(
                "flutter_web/assets/assets/home/chatbox_bg.png",
                "assets/assets/home/chatbox_bg.png",
                "flutter_assets/assets/home/chatbox_bg.png",
            ),
            candidates,
        )
    }

    @Test
    fun webOnlyAssetDoesNotUseMobileFallback() {
        val candidates = WebChatStaticHandler.assetCandidatesForPath("fonts/OmnibotWebCjk.woff2")

        assertTrue(candidates.contains("flutter_web/fonts/OmnibotWebCjk.woff2"))
        assertFalse(candidates.any { it.startsWith("flutter_assets/") })
    }
}
