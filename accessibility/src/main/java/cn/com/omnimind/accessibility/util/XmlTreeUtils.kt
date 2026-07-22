package cn.com.omnimind.accessibility.util

import android.graphics.Rect
import android.os.Build
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST
import android.view.accessibility.AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS
import cn.com.omnimind.accessibility.action.AccessibilityNode
import cn.com.omnimind.accessibility.action.OmniScreenshotAction
import cn.com.omnimind.accessibility.action.XmlTreeNode
import cn.com.omnimind.baselib.util.OmniLog
import java.io.StringWriter

object XmlTreeUtils {
    fun buildXmlDirectly(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        val writer = StringWriter()
        val namespace = "http://schemas.android.com/apk/res/android"
        val serializer = Xml.newSerializer().apply {
            setOutput(writer)
            startDocument("UTF-8", true)
            setPrefix("", namespace)
            startTag(namespace, "hierarchy")
        }

        val visitedNodes = mutableSetOf<AccessibilityNodeInfo>()
        var nodeIdCounter = 0

        fun addAttr(name: String, value: String?) {
            if (!value.isNullOrEmpty()) {
                serializer.attribute(null, name, value)
            }
        }

        fun serializeNode(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null || depth > MAX_XML_DEPTH) return
            if (depth > 0 && !node.isVisibleToUser) return
            if (!visitedNodes.add(node)) return
            try {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                serializer.startTag(null, "node")
                serializer.attribute(null, "id", (nodeIdCounter++).toString())
                addAttr("text", sanitizeXmlString(node.text?.toString()))
                addAttr("content-desc", sanitizeXmlString(node.contentDescription?.toString()))
                addAttr("hintText", sanitizeXmlString(node.hintText?.toString()))
                addAttr("resource-id", sanitizeXmlString(node.viewIdResourceName))
                addAttr("class", sanitizeXmlString(node.className?.toString()))
                addAttr("package", sanitizeXmlString(node.packageName?.toString()))
                addAttr("clickable", node.isClickable.toString())
                addAttr("long-clickable", node.isLongClickable.toString())
                addAttr("context-clickable", node.isContextClickable.toString())
                addAttr("focusable", node.isFocusable.toString())
                addAttr("focused", node.isFocused.toString())
                addAttr("accessibility-focused", node.isAccessibilityFocused.toString())
                addAttr("scrollable", node.isScrollable.toString())
                addAttr("editable", node.isEditable.toString())
                addAttr("selected", node.isSelected.toString())
                addAttr("enabled", node.isEnabled.toString())
                addAttr("checkable", node.isCheckable.toString())
                addAttr("checked", node.isChecked.toString())
                addAttr("password", node.isPassword.toString())
                addAttr("dismissable", node.isDismissable.toString())
                addAttr("multi-line", node.isMultiLine.toString())
                addAttr("visible-to-user", node.isVisibleToUser.toString())
                addAttr("important-for-accessibility", node.isImportantForAccessibility.toString())
                if (node.isEditable) {
                    node.inputType.takeIf { it != 0 }?.let { addAttr("input-type", it.toString()) }
                    node.maxTextLength.takeIf { it > 0 }
                        ?.let { addAttr("max-text-length", it.toString()) }
                }
                addAttr("pane-title", sanitizeXmlString(node.paneTitle?.toString()))
                addAttr("state-description", sanitizeXmlString(node.stateDescription?.toString()))
                addAttr("tooltip-text", sanitizeXmlString(node.tooltipText?.toString()))
                addAttr("error", sanitizeXmlString(node.error?.toString()))
                node.drawingOrder.takeIf { it > 0 }
                    ?.let { addAttr("drawing-order", it.toString()) }
                serializer.attribute(
                    null,
                    "bounds",
                    "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]",
                )
                for (index in 0 until node.childCount) {
                    val child = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            node.getChild(
                                index,
                                FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or FLAG_PREFETCH_SIBLINGS,
                            )
                        } else {
                            node.getChild(index)
                        }
                    } catch (error: Exception) {
                        OmniLog.w(
                            OmniScreenshotAction.TAG,
                            "Failed to get child at index $index: ${error.message}",
                        )
                        continue
                    }
                    if (child != null) {
                        try {
                            serializeNode(child, depth + 1)
                        } finally {
                            @Suppress("DEPRECATION")
                            child.recycle()
                        }
                    }
                }
                serializer.endTag(null, "node")
            } finally {
                visitedNodes.remove(node)
            }
        }
        serializeNode(root)
        serializer.endTag(namespace, "hierarchy")
        serializer.endDocument()
        return writer.toString()
    }

    fun buildXmlTree(root: AccessibilityNodeInfo?): XmlTreeNode? =
        buildRecursive(root, 0, visitedNodes = mutableSetOf(), depth = 0).first

    // TODO: nodeId allocation algorithm is not optimal
    private fun buildRecursive(
        node: AccessibilityNodeInfo?,
        currentId: Int,
        visitedNodes: MutableSet<AccessibilityNodeInfo> = mutableSetOf(),
        depth: Int = 0
    ): Pair<XmlTreeNode?, Int> {
        // 添加最大深度限制，防止过深的递归调用
        val maxDepth = 50
        if (depth > maxDepth) {
            OmniLog.w(OmniScreenshotAction.TAG, "Maximum recursion depth reached: $maxDepth")
            return null to currentId
        }

        // 检查节点是否已经访问过，防止循环引用导致的无限递归
        if (node != null && visitedNodes.contains(node)) {
            OmniLog.w(OmniScreenshotAction.TAG, "Circular reference detected in accessibility tree")
            return null to currentId
        }

        if (node == null || (!node.isVisibleToUser && currentId != 0)) {
            return null to currentId
        }

        // 将当前节点添加到已访问节点集合中
        if (node != null) {
            visitedNodes.add(node)
        }

        val nodeId = currentId.toString()
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val hasText = !node.text.isNullOrEmpty()
        val interactive =
            node.isClickable || node.isLongClickable || node.isFocusable || node.isFocused || node.isScrollable || node.isPassword || node.isSelected || node.isEditable
        val show = hasText || interactive || currentId == 0 // Always show root node

        var nextId = currentId + 1
        val children = mutableListOf<XmlTreeNode>()
        for (i in 0 until node.childCount) {
            // 获取子节点时添加异常处理
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                OmniLog.w(OmniScreenshotAction.TAG, "Failed to get child at index $i: ${e.message}")
                continue
            }

            // 递归调用时传递visitedNodes和增加depth
            val (childTree, newId) = buildRecursive(child, nextId, visitedNodes, depth + 1)
            if (childTree != null) {
                children.add(childTree)
                nextId = newId
            }
        }

        // 从已访问节点集合中移除当前节点（确保在其他路径中可以再次访问）
        if (node != null) {
            visitedNodes.remove(node)
        }

        return XmlTreeNode(
            id = nodeId,
            node = AccessibilityNode(
                info = node,
                bounds = bounds,
                show = show,
                interactive = interactive,
            ),
            children = children,
        ) to nextId
    }

    fun extractNodeMap(tree: XmlTreeNode): Map<String, AccessibilityNode> {
        val map = mutableMapOf<String, AccessibilityNode>()

        fun dfs(node: XmlTreeNode) {
            map[node.id] = node.node
            node.children.forEach(::dfs)
        }
        dfs(tree)
        return map
    }

    private fun sanitizeXmlString(text: String?): String? {
        if (text == null) return null
        // This regex matches any character that is NOT a valid XML 1.0 character.
        val illegalXmlCharRegex = "[^\\u0009\\u000A\\u000D\\u0020-\\uD7FF\\uE000-\\uFFFD]"
        return text.replace(Regex(illegalXmlCharRegex), "")
    }

    fun serializeXml(tree: XmlTreeNode): String {
        val writer = StringWriter()
        val namespace = "http://schemas.android.com/apk/res/android"
        val serializer = Xml.newSerializer().apply {
            setOutput(writer)
            startDocument("UTF-8", true)
            setPrefix("", namespace)
            startTag(namespace, "hierarchy")
        }

        fun addAttr(
            name: String,
            value: String?,
        ) {
            if (!value.isNullOrEmpty()) {
                serializer.attribute(null, name, value)
            }
        }

        fun serializeNode(node: XmlTreeNode) {
            val info = node.node.info
            val bounds = node.node.bounds
            serializer.startTag(null, "node")
            serializer.attribute(null, "id", node.id)
            addAttr("text", sanitizeXmlString(info.text?.toString()))
            addAttr("content-desc", sanitizeXmlString(info.contentDescription?.toString()))
            addAttr("hintText", sanitizeXmlString(info.hintText?.toString()))
            addAttr("resource-id", sanitizeXmlString(info.viewIdResourceName))
            addAttr("class", sanitizeXmlString(info.className?.toString()))
            addAttr("package", sanitizeXmlString(info.packageName?.toString()))
            addAttr("clickable", info.isClickable.toString())
            addAttr("long-clickable", info.isLongClickable.toString())
            addAttr("context-clickable", info.isContextClickable.toString())
            addAttr("focusable", info.isFocusable.toString())
            addAttr("focused", info.isFocused.toString())
            addAttr("accessibility-focused", info.isAccessibilityFocused.toString())
            addAttr("scrollable", info.isScrollable.toString())
            addAttr("editable", info.isEditable.toString())
            addAttr("selected", info.isSelected.toString())
            addAttr("enabled", info.isEnabled.toString())
            addAttr("checkable", info.isCheckable.toString())
            addAttr("checked", info.isChecked.toString())
            addAttr("password", info.isPassword.toString())
            addAttr("dismissable", info.isDismissable.toString())
            addAttr("multi-line", info.isMultiLine.toString())
            addAttr("visible-to-user", info.isVisibleToUser.toString())
            addAttr("important-for-accessibility", info.isImportantForAccessibility.toString())
            if (info.isEditable) {
                info.inputType.takeIf { it != 0 }?.let { addAttr("input-type", it.toString()) }
                info.maxTextLength.takeIf { it > 0 }?.let { addAttr("max-text-length", it.toString()) }
            }
            addAttr("pane-title", sanitizeXmlString(info.paneTitle?.toString()))
            addAttr("state-description", sanitizeXmlString(info.stateDescription?.toString()))
            addAttr("tooltip-text", sanitizeXmlString(info.tooltipText?.toString()))
            addAttr("error", sanitizeXmlString(info.error?.toString()))
            info.drawingOrder.takeIf { it > 0 }?.let { addAttr("drawing-order", it.toString()) }
            serializer.attribute(
                null,
                "bounds",
                "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]",
            )
            node.children.forEach(::serializeNode)
            serializer.endTag(null, "node")
        }

        serializeNode(tree)

        serializer.endTag(namespace, "hierarchy")
        serializer.endDocument()
        return writer.toString()
    }

    private const val MAX_XML_DEPTH = 50
}
