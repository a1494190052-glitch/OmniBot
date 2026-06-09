package cn.com.omnimind.bot.runlog

import java.io.File

internal object RunLogXmlArtifacts {
    private const val MAX_XML_FILE_BYTES = 8L * 1024L * 1024L

    fun observationXml(observation: Map<String, Any?>): String {
        val inline = firstNonBlank(
            observation["observation_xml"],
            observation["observationXml"],
            observation["xml"],
            observation["page"],
        )
        if (inline.isNotBlank()) return inline
        return readXmlPath(
            observation["observation_xml_path"],
            observation["observationXmlPath"],
            observation["xml_path"],
            observation["xmlPath"],
            observation["page_path"],
            observation["pagePath"],
        )
    }

    fun pageXmlFromContext(context: Map<String, Any?>): String {
        val inline = firstNonBlank(
            context["page"],
            context["xml"],
            context["observation_xml"],
            context["observationXml"],
        )
        if (inline.isNotBlank()) return inline
        return readXmlPath(
            context["page_path"],
            context["pagePath"],
            context["xml_path"],
            context["xmlPath"],
            context["observation_xml_path"],
            context["observationXmlPath"],
        )
    }

    private fun readXmlPath(vararg values: Any?): String {
        val path = firstNonBlank(*values)
        if (path.isBlank()) return ""
        val file = File(path)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_XML_FILE_BYTES) {
            return ""
        }
        return runCatching { file.readText() }.getOrDefault("")
    }

    private fun firstNonBlank(vararg values: Any?): String {
        values.forEach { value ->
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }
}
