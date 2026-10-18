package io.github.mangi.eta.data.usage

import android.content.Context
import io.github.mangi.eta.agent.model.AgentHttpClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/** models.dev 的一条价格（美元 / 百万 token）；[normalizedId] 按 [ModelIds.normalize] 规整。 */
@Serializable
internal data class ModelsDevPrice(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val normalizedId: String,
    val name: String,
    val releaseDate: String,
    val input: Double,
    val output: Double,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
    val longThreshold: Long? = null,
    val longInput: Double? = null,
    val longOutput: Double? = null,
    val longCacheRead: Double? = null,
    val longCacheWrite: Double? = null,
) {
    val key: String get() = "$providerId/$modelId"

    fun toModelPrice(fetchedOn: String): ModelPrice = ModelPrice(
        currency = ModelPrice.USD,
        input = input,
        cachedInput = cacheRead,
        cacheWrite = cacheWrite,
        output = output,
        longContextThreshold = longThreshold,
        longInput = longInput,
        longCachedInput = longCacheRead,
        longCacheWrite = longCacheWrite,
        longOutput = longOutput,
        source = "models.dev · $providerName · $modelId · $fetchedOn",
        importedFrom = key,
        importedProvider = providerName,
    )
}

/** 拉取下来的 models.dev 价格与拉取日期，缓存在本地，避免每次下载完整数据。 */
@Serializable
internal data class ModelsDevSnapshot(val fetchedOn: String, val prices: List<ModelsDevPrice>)

/**
 * models.dev 的公开模型数据（https://models.dev/api.json），参照 CC Switch 的导入做法：
 * 只取有价格的文本模型，跳过已弃用、语音、图像、向量等；长上下文分档取 `tiers` 里的上下文档。
 */
internal object ModelsDev {
    const val API_URL = "https://models.dev/api.json"
    private const val CACHE_FILE = "models_dev_prices.json"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val NON_TEXT_MARKERS = listOf("audio", "deprecated", "embedding", "image", "moderation", "realtime", "transcribe", "tts", "video")
    private val NON_TEXT_OUTPUTS = setOf("audio", "image", "video")

    /** 匹配在用模型时优先的原厂来源；同名模型在聚合商那里常带加价或不同口径。 */
    val PREFERRED_PROVIDERS = listOf(
        "openai", "anthropic", "google", "deepseek", "moonshotai", "alibaba", "zai", "xai",
        "minimax", "xiaomi", "stepfun", "mistral",
    )

    suspend fun fetch(context: Context): ModelsDevSnapshot = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(API_URL).header("Accept", "application/json").build()
        val prices = AgentHttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty body")
            body.byteStream().use(::parse)
        }
        ModelsDevSnapshot(LocalDate.now(ZoneId.systemDefault()).toString(), prices).also { snapshot ->
            runCatching { cacheFile(context).writeText(json.encodeToString(ModelsDevSnapshot.serializer(), snapshot)) }
        }
    }

    fun cached(context: Context): ModelsDevSnapshot? = runCatching {
        cacheFile(context).takeIf(File::isFile)?.readText()?.let { json.decodeFromString(ModelsDevSnapshot.serializer(), it) }
    }.getOrNull()

    @OptIn(ExperimentalSerializationApi::class)
    fun parse(input: InputStream): List<ModelsDevPrice> {
        val root = json.decodeFromStream(JsonObject.serializer(), input)
        val prices = mutableListOf<ModelsDevPrice>()
        for ((providerId, providerValue) in root) {
            val provider = providerValue as? JsonObject ?: continue
            val providerName = provider.text("name").ifBlank { providerId }
            val models = provider["models"] as? JsonObject ?: continue
            for ((modelId, modelValue) in models) {
                val model = modelValue as? JsonObject ?: continue
                if (!isTextModel(modelId, model)) continue
                val cost = model["cost"] as? JsonObject ?: continue
                val input = cost.number("input")
                val output = cost.number("output")
                if (input == null && output == null) continue
                val normalizedId = ModelIds.normalize(modelId)
                if (normalizedId.isEmpty()) continue
                val tier = (cost["tiers"] as? JsonArray).orEmpty().asSequence()
                    .mapNotNull { it as? JsonObject }
                    .firstOrNull { (it["tier"] as? JsonObject)?.text("type") == "context" }
                prices += ModelsDevPrice(
                    providerId = providerId,
                    providerName = providerName,
                    modelId = modelId,
                    normalizedId = normalizedId,
                    name = model.text("name").ifBlank { modelId },
                    releaseDate = model.text("release_date"),
                    input = input ?: 0.0,
                    output = output ?: 0.0,
                    cacheRead = cost.number("cache_read"),
                    cacheWrite = cost.number("cache_write"),
                    longThreshold = (tier?.get("tier") as? JsonObject)?.let { (it["size"] as? JsonPrimitive)?.longOrNull },
                    longInput = tier?.number("input"),
                    longOutput = tier?.number("output"),
                    longCacheRead = tier?.number("cache_read"),
                    longCacheWrite = tier?.number("cache_write"),
                )
            }
        }
        return prices.sortedWith(compareByDescending<ModelsDevPrice> { it.releaseDate }.thenBy { it.name.lowercase() })
    }

    /** 给在用模型找价格：模型 ID 规整后相同的条目里，原厂来源优先，其次发布最新的。 */
    fun bestMatch(prices: List<ModelsDevPrice>, model: String): ModelsDevPrice? {
        val id = ModelIds.normalize(model)
        val candidates = prices.filter { it.normalizedId == id }
        return candidates.minByOrNull { price ->
            PREFERRED_PROVIDERS.indexOf(price.providerId).let { if (it < 0) PREFERRED_PROVIDERS.size else it }
        }
    }

    private fun isTextModel(modelId: String, model: JsonObject): Boolean {
        if (model.text("status").equals("deprecated", ignoreCase = true)) return false
        val outputs = ((model["modalities"] as? JsonObject)?.get("output") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content?.lowercase() }
        if (outputs.isNotEmpty() && ("text" !in outputs || outputs.any { it in NON_TEXT_OUTPUTS })) return false
        val searchable = "$modelId ${model.text("name")}".lowercase()
        return NON_TEXT_MARKERS.none { it in searchable }
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }

    private fun cacheFile(context: Context) = File(context.applicationContext.filesDir, CACHE_FILE)
}
