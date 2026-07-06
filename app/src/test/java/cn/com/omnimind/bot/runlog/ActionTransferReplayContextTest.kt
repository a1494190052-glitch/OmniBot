package cn.com.omnimind.bot.runlog

import cn.com.omnimind.assists.task.vlmserver.ActionExecutor
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.assists.task.vlmserver.OperationResult
import cn.com.omnimind.assists.task.vlmserver.UIContextManager
import cn.com.omnimind.baselib.runlog.OobActionSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionTransferReplayContextTest {

    @Test
    fun transferRewritesClickFromSourceContextToTargetContextVersionA() {
        val result = ActionTransfer.transfer(
            ActionTransfer.Request(
                action = OobActionSchema.TOOL_CLICK,
                args = mapOf(
                    "x" to 258f,
                    "y" to 186f,
                    "target_description" to "搜索框",
                ),
                sourceContext = mapOf("page" to sourceSearchPageXml()),
                currentContext = mapOf("page" to targetSearchPageXmlVersionA()),
            ),
        )

        assertTrue("expected action transfer to apply, diagnostics=${result.diagnostics}", result.applied)
        assertEquals(true, result.diagnostics["applied"])
        assertNotEquals("no_anchor_match", result.diagnostics["reason"])
        assertEquals(410f, result.args["x"] as Float, 0.5f)
        assertEquals(238f, result.args["y"] as Float, 0.5f)
    }

    @Test
    fun transferRewritesInputTextFromSourceContextToTargetContextVersionA() {
        val result = ActionTransfer.transfer(
            ActionTransfer.Request(
                action = OobActionSchema.TOOL_INPUT_TEXT,
                args = mapOf(
                    "x" to 258f,
                    "y" to 186f,
                    "text" to "清华大学",
                    "target_description" to "搜索框",
                ),
                sourceContext = mapOf("page" to sourceSearchPageXml()),
                currentContext = mapOf("page" to targetSearchPageXmlVersionA()),
            ),
        )

        assertTrue("expected action transfer to apply, diagnostics=${result.diagnostics}", result.applied)
        assertEquals(true, result.diagnostics["applied"])
        assertEquals("semantic_input", result.diagnostics["mode"])
        assertNotEquals("no_anchor_match", result.diagnostics["reason"])
        assertEquals("清华大学", result.args["text"])
        assertEquals(410f, result.args["x"] as Float, 0.5f)
        assertEquals(238f, result.args["y"] as Float, 0.5f)
    }

    @Test
    fun replayHelperUsesStepSourceContextAndCurrentDeviceXmlVersionB() {
        val step = replayClickStep(
            x = 258f,
            y = 186f,
            sourceXml = sourceSearchPageXml(),
        )

        val result = ReplayHelper.remapStepArgs(
            step,
            deviceOperator = FakeDeviceOperator(
                xml = targetSearchPageXmlVersionB(),
                packageName = "com.zhihu.android",
                activityName = "com.zhihu.android.SearchActivity",
            ),
        )

        assertEquals(true, result.meta["applied"])
        assertNotEquals("no_anchor_match", result.meta["reason"])
        val args = result.args as Map<*, *>
        assertEquals(326f, args["x"] as Float, 0.5f)
        assertEquals(286f, args["y"] as Float, 0.5f)
    }

    @Test
    fun replayHelperUsesStepSourceContextAndCurrentDeviceXmlForInputTextVersionB() {
        val step = replayInputTextStep(
            x = 258f,
            y = 186f,
            text = "清华大学",
            sourceXml = sourceSearchPageXml(),
        )

        val result = ReplayHelper.remapStepArgs(
            step,
            deviceOperator = FakeDeviceOperator(
                xml = targetSearchPageXmlVersionBFocusedKeyboardNoResource(),
                packageName = "com.zhihu.android",
                activityName = "com.zhihu.android.SearchActivity",
            ),
        )

        assertEquals(true, result.meta["applied"])
        assertEquals("focused_input", result.meta["mode"])
        assertNotEquals("no_anchor_match", result.meta["reason"])
        assertEquals("com.zhihu.android", result.meta["current_package_name"])
        val args = result.args as Map<*, *>
        assertEquals("清华大学", args["text"])
        assertEquals(480f, args["x"] as Float, 0.5f)
        assertEquals(238f, args["y"] as Float, 0.5f)
    }

    @Test
    fun replayHelperReportsCurrentOverlayPageInsteadOfPretendingReplayCanRun() {
        val step = replayClickStep(
            x = 258f,
            y = 186f,
            sourceXml = sourceSearchPageXml(),
        )

        val result = ReplayHelper.remapStepArgs(
            step,
            deviceOperator = FakeDeviceOperator(
                xml = omnibotFunctionResultDialogXml(),
                packageName = "cn.com.omnimind.bot.debug",
                activityName = "cn.com.omnimind.bot.MainActivity",
            ),
        )

        assertEquals(false, result.meta["applied"])
        assertTrue(
            "overlay current XML should not be treated as a valid target page, meta=${result.meta}",
            result.meta["reason"] in setOf("no_anchor_match", "missing_source_element", "matcher_abstain"),
        )
    }

    @Test
    fun actionExecutorPreservesActionTransferDiagnosticsWhenReplayContextFails() = runBlocking {
        val executor = ActionExecutor(
            deviceOperator = FakeDeviceOperator(
                xml = omnibotFunctionResultDialogXml(),
                packageName = "cn.com.omnimind.bot.debug",
                activityName = "cn.com.omnimind.bot.MainActivity",
            ),
            contextManager = UIContextManager(),
        )
        val failureMessage =
            "OOB_FUNCTION_SOURCE_NOT_REACHED: action transfer could not match the recorded source page: no_anchor_match"

        val result = executor.act(
            action = OobActionSchema.TOOL_INPUT_TEXT,
            args = mapOf(
                "x" to 258f,
                "y" to 186f,
                "text" to "清华大学",
                "target_description" to "搜索框",
            ),
            source = "function_replay",
            check = ActionExecutor.ActCheckConfig(
                actionTransfer = { _, args ->
                    ActionExecutor.ActArgsResult(
                        args = args,
                        diagnostics = mapOf(
                            "applied" to false,
                            "reason" to "no_anchor_match",
                            "source_xml_hash" to "source",
                            "current_xml_hash" to "overlay",
                            "current_sparse_overlay_page" to true,
                        ),
                        blockDispatch = true,
                        failureMessage = failureMessage,
                        failureErrorCode = "OOB_FUNCTION_SOURCE_NOT_REACHED",
                    )
                },
            ),
        )

        assertEquals(false, result.success)
        assertEquals("OOB_FUNCTION_SOURCE_NOT_REACHED", result.diagnostics["local_action_error_code"])
        assertTrue(
            "expected action_transfer diagnostics to be preserved, diagnostics=${result.diagnostics}",
            result.diagnostics["action_transfer"]?.contains("no_anchor_match") == true,
        )
        assertTrue(
            "expected current context diagnostics to be preserved, diagnostics=${result.diagnostics}",
            result.diagnostics["action_transfer"]?.contains("current_xml_hash=overlay") == true,
        )
    }

    private fun replayClickStep(
        x: Float,
        y: Float,
        sourceXml: String,
    ): Map<String, Any?> = linkedMapOf(
        OobActionSchema.ROOT_TOOL to OobActionSchema.TOOL_CLICK,
        OobActionSchema.ROOT_ARGS to linkedMapOf(
            "x" to x,
            "y" to y,
            "target_description" to "搜索框",
        ),
        "source_context" to linkedMapOf(
            "src_ctx" to linkedMapOf(
                "page" to sourceXml,
                "package_name" to "com.zhihu.android",
                "activity_name" to "com.zhihu.android.MainActivity",
            ),
            "action" to linkedMapOf(
                "tool" to OobActionSchema.TOOL_CLICK,
                "x" to x,
                "y" to y,
                "target_description" to "搜索框",
            ),
        ),
    )

    private fun replayInputTextStep(
        x: Float,
        y: Float,
        text: String,
        sourceXml: String,
    ): Map<String, Any?> = linkedMapOf(
        OobActionSchema.ROOT_TOOL to OobActionSchema.TOOL_INPUT_TEXT,
        OobActionSchema.ROOT_ARGS to linkedMapOf(
            "x" to x,
            "y" to y,
            "text" to text,
            "target_description" to "搜索框",
        ),
        "source_context" to linkedMapOf(
            "src_ctx" to linkedMapOf(
                "page" to sourceXml,
                "package_name" to "com.zhihu.android",
                "activity_name" to "com.zhihu.android.MainActivity",
            ),
            "action" to linkedMapOf(
                "tool" to OobActionSchema.TOOL_INPUT_TEXT,
                "x" to x,
                "y" to y,
                "text" to text,
                "target_description" to "搜索框",
            ),
        ),
    )

    private fun sourceSearchPageXml(): String = """
        <hierarchy rotation="0">
          <node index="0" class="android.widget.FrameLayout" package="com.zhihu.android" bounds="[0,0][1080,2400]" visible-to-user="true" enabled="true">
            <node index="1" class="android.widget.LinearLayout" package="com.zhihu.android" bounds="[0,80][1080,2400]" visible-to-user="true" enabled="true">
              <node index="2" class="android.widget.TextView" package="com.zhihu.android" text="知乎" resource-id="com.zhihu.android:id/title" bounds="[40,102][170,164]" visible-to-user="true" enabled="true"/>
              <node index="3" class="android.widget.EditText" package="com.zhihu.android" text="" hint="搜索知乎内容" content-desc="搜索框" resource-id="com.zhihu.android:id/search_input" clickable="true" focusable="true" editable="true" focused="false" enabled="true" visible-to-user="true" bounds="[96,148][420,224]"/>
              <node index="4" class="android.widget.TextView" package="com.zhihu.android" text="推荐" resource-id="com.zhihu.android:id/tab_recommend" clickable="true" enabled="true" visible-to-user="true" bounds="[80,280][210,350]"/>
              <node index="5" class="android.widget.TextView" package="com.zhihu.android" text="热榜" resource-id="com.zhihu.android:id/tab_hot" clickable="true" enabled="true" visible-to-user="true" bounds="[250,280][380,350]"/>
              <node index="6" class="android.widget.Button" package="com.zhihu.android" text="搜索" resource-id="com.zhihu.android:id/search_button" clickable="true" enabled="true" visible-to-user="true" bounds="[890,148][1010,224]"/>
            </node>
          </node>
        </hierarchy>
    """.trimIndent()

    private fun targetSearchPageXmlVersionA(): String = """
        <hierarchy rotation="0">
          <node index="0" class="android.widget.FrameLayout" package="com.zhihu.android" bounds="[0,0][1080,2400]" visible-to-user="true" enabled="true">
            <node index="1" class="android.widget.LinearLayout" package="com.zhihu.android" bounds="[0,96][1080,2400]" visible-to-user="true" enabled="true">
              <node index="2" class="android.widget.TextView" package="com.zhihu.android" text="知乎" resource-id="com.zhihu.android:id/title" bounds="[40,112][170,174]" visible-to-user="true" enabled="true"/>
              <node index="3" class="android.widget.EditText" package="com.zhihu.android" text="" hint="搜索知乎内容" content-desc="搜索框" resource-id="com.zhihu.android:id/search_input" clickable="true" focusable="true" editable="true" focused="false" enabled="true" visible-to-user="true" bounds="[210,196][610,280]"/>
              <node index="4" class="android.widget.TextView" package="com.zhihu.android" text="推荐" resource-id="com.zhihu.android:id/tab_recommend" clickable="true" enabled="true" visible-to-user="true" bounds="[80,318][210,388]"/>
              <node index="5" class="android.widget.TextView" package="com.zhihu.android" text="热榜" resource-id="com.zhihu.android:id/tab_hot" clickable="true" enabled="true" visible-to-user="true" bounds="[250,318][380,388]"/>
              <node index="6" class="android.widget.Button" package="com.zhihu.android" text="搜索" resource-id="com.zhihu.android:id/search_button" clickable="true" enabled="true" visible-to-user="true" bounds="[890,196][1010,280]"/>
            </node>
          </node>
        </hierarchy>
    """.trimIndent()

    private fun targetSearchPageXmlVersionB(): String = """
        <hierarchy rotation="0">
          <node index="0" class="android.widget.FrameLayout" package="com.zhihu.android" bounds="[0,0][1080,2400]" visible-to-user="true" enabled="true">
            <node index="1" class="android.widget.LinearLayout" package="com.zhihu.android" bounds="[0,120][1080,2400]" visible-to-user="true" enabled="true">
              <node index="2" class="android.widget.TextView" package="com.zhihu.android" text="知乎" resource-id="com.zhihu.android:id/title" bounds="[40,144][170,206]" visible-to-user="true" enabled="true"/>
              <node index="3" class="android.widget.EditText" package="com.zhihu.android" text="" hint="搜索知乎内容" content-desc="搜索框" resource-id="com.zhihu.android:id/search_input" clickable="true" focusable="true" editable="true" focused="false" enabled="true" visible-to-user="true" bounds="[160,244][492,328]"/>
              <node index="4" class="android.widget.TextView" package="com.zhihu.android" text="推荐" resource-id="com.zhihu.android:id/tab_recommend" clickable="true" enabled="true" visible-to-user="true" bounds="[80,376][210,446]"/>
              <node index="5" class="android.widget.TextView" package="com.zhihu.android" text="热榜" resource-id="com.zhihu.android:id/tab_hot" clickable="true" enabled="true" visible-to-user="true" bounds="[250,376][380,446]"/>
              <node index="6" class="android.widget.Button" package="com.zhihu.android" text="搜索" resource-id="com.zhihu.android:id/search_button" clickable="true" enabled="true" visible-to-user="true" bounds="[890,244][1010,328]"/>
            </node>
          </node>
        </hierarchy>
    """.trimIndent()

    private fun targetSearchPageXmlVersionBFocusedKeyboardNoResource(): String = """
        <hierarchy rotation="0">
          <node index="0" class="android.widget.FrameLayout" package="com.zhihu.android" bounds="[0,0][1080,2400]" visible-to-user="true" enabled="true">
            <node index="1" class="android.widget.LinearLayout" package="com.zhihu.android" bounds="[0,120][1080,1600]" visible-to-user="true" enabled="true">
              <node index="2" class="android.widget.TextView" package="com.zhihu.android" text="知乎" bounds="[40,144][170,206]" visible-to-user="true" enabled="true"/>
              <node index="3" class="android.widget.EditText" package="com.zhihu.android" text="" hint="搜索知乎内容" content-desc="" clickable="true" focusable="true" editable="true" focused="true" enabled="true" visible-to-user="true" bounds="[240,196][720,280]"/>
              <node index="4" class="android.widget.TextView" package="com.zhihu.android" text="推荐" clickable="true" enabled="true" visible-to-user="true" bounds="[80,340][210,410]"/>
              <node index="5" class="android.widget.TextView" package="com.zhihu.android" text="热榜" clickable="true" enabled="true" visible-to-user="true" bounds="[250,340][380,410]"/>
              <node index="6" class="android.widget.Button" package="com.zhihu.android" text="搜索" clickable="true" enabled="true" visible-to-user="true" bounds="[890,196][1010,280]"/>
            </node>
            <node index="7" class="android.inputmethodservice.KeyboardView" package="com.android.inputmethod.latin" bounds="[0,1600][1080,2400]" visible-to-user="true" enabled="true">
              <node index="8" class="android.widget.Button" package="com.android.inputmethod.latin" text="q" clickable="true" enabled="true" visible-to-user="true" bounds="[0,1620][100,1720]"/>
              <node index="9" class="android.widget.Button" package="com.android.inputmethod.latin" text="w" clickable="true" enabled="true" visible-to-user="true" bounds="[100,1620][200,1720]"/>
              <node index="10" class="android.widget.Button" package="com.android.inputmethod.latin" text="enter" clickable="true" enabled="true" visible-to-user="true" bounds="[850,2200][1060,2360]"/>
            </node>
          </node>
        </hierarchy>
    """.trimIndent()

    private fun omnibotFunctionResultDialogXml(): String = """
        <hierarchy rotation="0">
          <node index="0" class="android.widget.FrameLayout" package="cn.com.omnimind.bot.debug" bounds="[0,0][1080,2400]" visible-to-user="true" enabled="true">
            <node index="1" class="android.widget.LinearLayout" package="cn.com.omnimind.bot.debug" bounds="[80,620][1000,1600]" visible-to-user="true" enabled="true">
              <node index="2" class="android.widget.TextView" package="cn.com.omnimind.bot.debug" text="复用指令执行结果" resource-id="cn.com.omnimind.bot.debug:id/title" bounds="[140,700][940,780]" visible-to-user="true" enabled="true"/>
              <node index="3" class="android.widget.TextView" package="cn.com.omnimind.bot.debug" text="云端模型执行中" resource-id="cn.com.omnimind.bot.debug:id/status" bounds="[140,820][940,900]" visible-to-user="true" enabled="true"/>
              <node index="4" class="android.widget.Button" package="cn.com.omnimind.bot.debug" text="关闭" resource-id="cn.com.omnimind.bot.debug:id/close" clickable="true" enabled="true" visible-to-user="true" bounds="[760,1420][940,1500]"/>
            </node>
          </node>
        </hierarchy>
    """.trimIndent()

    private class FakeDeviceOperator(
        private val xml: String,
        private val packageName: String,
        private val activityName: String,
    ) : DeviceOperator {
        override suspend fun clickCoordinate(x: Float, y: Float): OperationResult = ok()
        override suspend fun longClickCoordinate(x: Float, y: Float, duration: Long): OperationResult = ok()
        override suspend fun inputText(text: String): OperationResult = ok()
        override suspend fun pressHotKey(key: String): OperationResult = ok()
        override suspend fun copyToClipboard(text: String): OperationResult = ok()
        override suspend fun getClipboard(): String? = null
        override suspend fun slideCoordinate(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): OperationResult = ok()
        override suspend fun goHome(): OperationResult = ok()
        override suspend fun goBack(): OperationResult = ok()
        override suspend fun launchApplication(packageName: String): OperationResult = ok()
        override suspend fun captureScreenshot(): String = ""
        override fun getLastScreenshotWidth(): Int = 1080
        override fun getLastScreenshotHeight(): Int = 2400
        override fun getDisplayWidth(): Int = 1080
        override fun getDisplayHeight(): Int = 2400
        override suspend fun showInfo(message: String) = Unit
        override fun isReady(): Boolean = true
        override fun currentXml(): String = xml
        override fun currentPackageName(): String = packageName
        override fun currentActivityName(): String = activityName
        override suspend fun hideKeyboard(): OperationResult = ok()

        private fun ok(): OperationResult = OperationResult(success = true, message = "ok")
    }
}
