package cn.com.omnimind.bot.plugin.runtime

import android.content.res.AssetManager
import cn.com.omnimind.bot.plugin.OmniPluginContract
import cn.com.omnimind.bot.plugin.OmniPluginDescriptor
import cn.com.omnimind.bot.plugin.OmniPluginKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

data class RuntimeBundleDefinition(
    val descriptor: OmniPluginDescriptor,
    val adapterId: String,
    val runtimeSkill: RuntimeSkillSpec,
)

class RuntimeBundleCatalog private constructor(
    val bundles: List<RuntimeBundleDefinition>,
) {
    fun require(pluginId: String): RuntimeBundleDefinition =
        bundles.firstOrNull { it.descriptor.id == pluginId }
            ?: throw IllegalArgumentException("Runtime bundle is not declared: $pluginId")

    companion object {
        private const val ASSET_PATH = "catalog.v1.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun load(assets: AssetManager): RuntimeBundleCatalog {
            val source = assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            return parse(source)
        }

        internal fun parse(source: String): RuntimeBundleCatalog {
            val catalog = json.decodeFromString<RuntimeBundleCatalogWire>(source)
            require(catalog.schemaVersion == 1) {
                "Unsupported runtime bundle catalog schema: ${catalog.schemaVersion}"
            }
            val bundles = catalog.plugins.map(RuntimeBundlePluginWire::toDefinition)
            val duplicateId = bundles.groupBy { it.descriptor.id }
                .entries.firstOrNull { it.value.size > 1 }
                ?.key
            require(duplicateId == null) { "Duplicate runtime bundle id: $duplicateId" }
            return RuntimeBundleCatalog(bundles)
        }
    }
}

@Serializable
private data class RuntimeBundleCatalogWire(
    val schemaVersion: Int = 0,
    val plugins: List<RuntimeBundlePluginWire> = emptyList(),
)

@Serializable
private data class RuntimeBundlePluginWire(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val interfaceVersion: Int = OmniPluginContract.CURRENT_INTERFACE_VERSION,
    val description: String = "",
    val publisher: String = "",
    val kind: String = OmniPluginKind.RUNTIME_BUNDLE.wireName,
    val downloadSizeBytes: Long = 0,
    val capabilities: List<String> = emptyList(),
    val settingsSchema: JsonObject = JsonObject(emptyMap()),
    val presentation: JsonObject = JsonObject(emptyMap()),
    val adapter: String = "",
    val runtimeSkill: RuntimeSkillWire = RuntimeSkillWire(),
) {
    fun toDefinition(): RuntimeBundleDefinition {
        require(PLUGIN_ID.matches(id)) { "Invalid runtime bundle id: $id" }
        require(name.isNotBlank()) { "Runtime bundle $id has no name" }
        require(version.isNotBlank()) { "Runtime bundle $id has no version" }
        require(publisher.isNotBlank()) { "Runtime bundle $id has no publisher" }
        require(downloadSizeBytes >= 0) { "Runtime bundle $id has a negative download size" }
        require(kind == OmniPluginKind.RUNTIME_BUNDLE.wireName) {
            "Runtime bundle $id declares unsupported kind: $kind"
        }
        require(adapter.isNotBlank()) { "Runtime bundle $id has no adapter" }
        return RuntimeBundleDefinition(
            descriptor = OmniPluginDescriptor(
                id = id,
                name = name,
                version = version,
                interfaceVersion = interfaceVersion,
                description = description,
                publisher = publisher,
                kind = OmniPluginKind.RUNTIME_BUNDLE,
                downloadSizeBytes = downloadSizeBytes,
                capabilities = capabilities,
                settingsSchema = settingsSchema,
                presentation = presentation,
            ),
            adapterId = adapter,
            runtimeSkill = runtimeSkill.toSpec(),
        )
    }

    private companion object {
        val PLUGIN_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+$")
    }
}

@Serializable
private data class RuntimeSkillWire(
    val id: String = "",
    val packagedAssetPath: String = "",
    val schemaAssetPath: String? = null,
    val markerFile: String = "PACKAGED_RUNTIME_SKILL",
    val bootstrapScript: String = "scripts/bootstrap_runtime.py",
    val runtimeDataPath: String = "scripts/runtime/.runtime",
    val bootstrapTimeoutSeconds: Int = 15 * 60,
) {
    fun toSpec(): RuntimeSkillSpec = RuntimeSkillSpec(
        id = id,
        packagedAssetPath = packagedAssetPath,
        schemaAssetPath = schemaAssetPath,
        markerFile = markerFile,
        bootstrapScript = bootstrapScript,
        runtimeDataPath = runtimeDataPath,
        bootstrapTimeoutSeconds = bootstrapTimeoutSeconds,
    ).validated()
}
