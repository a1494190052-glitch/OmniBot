package cn.com.omnimind.assists.task.vlmserver

data class AccessibilityXmlHealth(
    val nodeCount: Int,
    val semanticNodeCount: Int,
    val charCount: Int,
) {
    val isUsable: Boolean
        get() = semanticNodeCount > 0 || nodeCount >= MIN_STRUCTURAL_NODE_COUNT

    private companion object {
        private const val MIN_STRUCTURAL_NODE_COUNT = 8
    }
}

object AccessibilityXml {
    fun health(xml: String?): AccessibilityXmlHealth {
        if (xml.isNullOrBlank()) return AccessibilityXmlHealth(0, 0, 0)
        val nodes = NODE_TAG.findAll(xml).toList()
        return AccessibilityXmlHealth(
            nodeCount = nodes.size,
            semanticNodeCount = nodes.count { SEMANTIC_ATTRIBUTE.containsMatchIn(it.value) },
            charCount = xml.length,
        )
    }

    fun packageName(xml: String?): String? = xml
        ?.let(PACKAGE_ATTRIBUTE::find)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private val NODE_TAG = Regex("<node\\b[^>]*>")
    private val SEMANTIC_ATTRIBUTE = Regex(
        """(?:text|content-desc|resource-id)="[^"]+"|(?:clickable|long-clickable|editable|scrollable)="true"""
    )
    private val PACKAGE_ATTRIBUTE = Regex("""package="([^"]+)"""")
}
