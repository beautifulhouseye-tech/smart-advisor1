package com.example.network.smartadvisor

import com.example.engine.ProductMessage
import com.example.engine.ResponseType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Defensive JSON parser and response-type classifier for Smart Advisor
 * `/whatsapp/reply` and `/whatsapp/assistant/knowledge` responses.
 */
object SmartAdvisorResponseParser {

    data class ParsedPayload(
        val responseType: ResponseType,
        val products: List<ProductMessage> = emptyList(),
        val selectedProduct: ProductMessage? = null,
        val replyText: String = "",
        val suggestedReply: String = "",
        val storeName: String = "",
        val intent: String = "general",
        val categoryId: Long? = null,
        val categoryName: String = "",
        val categoryUrl: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val CATEGORY_INTENTS = setOf("CATEGORY_BROWSE", "CATEGORY_LINK")
    private val COMPARISON_INTENTS = setOf("PRODUCT_COMPARE", "PRODUCT_COMPARISON")

    fun parseReplyEndpoint(
        body: String,
        preferredReplyKey: String = "reply",
        fallbackStoreName: String = "",
        selectedProductId: Long? = null
    ): ParsedPayload? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return null
        val dataObj = (root["data"] as? JsonObject) ?: root

        val storeName = firstNonBlank(
            str(root["store_name"]),
            str(dataObj["store_name"]),
            fallbackStoreName
        )
        val intent = firstNonBlank(
            str(root["intent"]),
            str(dataObj["intent"]),
            "general"
        )
        val categoryId = (root["category_id"] ?: dataObj["category_id"])?.jsonPrimitive?.longOrNull
        val categoryName = firstNonBlank(
            str(root["category_name"]),
            str(dataObj["category_name"])
        )
        val categoryUrl = firstNonBlank(
            str(root["category_url"]),
            str(dataObj["category_url"])
        ).takeIf { ProductMessage.isValidWebUrl(it) }

        val rawProducts = extractProducts(root, dataObj, storeName)

        val directReply = firstNonBlank(
            extractNestedText(root, preferredReplyKey),
            str(root["reply"]),
            str(dataObj["reply"]),
            str(root["text"]),
            str(root["message"]),
            str(root["suggested_reply"]),
            str(dataObj["suggested_reply"])
        )
        val suggested = firstNonBlank(
            str(root["suggested_reply"]),
            str(dataObj["suggested_reply"]),
            directReply
        )

        return classifyPayload(
            intent = intent,
            products = rawProducts,
            replyText = directReply,
            suggestedReply = suggested,
            storeName = storeName,
            categoryId = categoryId,
            categoryName = categoryName,
            categoryUrl = categoryUrl,
            selectedProductId = selectedProductId
        )
    }

    fun parseKnowledgeItems(
        itemsJson: String,
        storeName: String = "",
        intent: String = "general",
        summary: String = "",
        categoryUrl: String? = null,
        selectedProductId: Long? = null
    ): ParsedPayload {
        val arr = runCatching {
            if (itemsJson.isBlank()) null else json.parseToJsonElement(itemsJson) as? JsonArray
        }.getOrNull()

        val products = arr?.mapNotNull { el ->
            (el as? JsonObject)?.let { parseProductObject(it, storeName) }
        }.orEmpty()

        return classifyPayload(
            intent = intent,
            products = products,
            replyText = summary,
            suggestedReply = summary,
            storeName = storeName,
            categoryId = null,
            categoryName = "",
            categoryUrl = categoryUrl?.takeIf { ProductMessage.isValidWebUrl(it) },
            selectedProductId = selectedProductId
        )
    }

    fun classifyPayload(
        intent: String,
        products: List<ProductMessage>,
        replyText: String,
        suggestedReply: String = replyText,
        storeName: String = "",
        categoryId: Long? = null,
        categoryName: String = "",
        categoryUrl: String? = null,
        selectedProductId: Long? = null
    ): ParsedPayload {
        val normalizedIntent = intent.trim().uppercase()

        // 1. Category browse / link MUST never turn into product images.
        if (normalizedIntent in CATEGORY_INTENTS) {
            return ParsedPayload(
                responseType = ResponseType.CATEGORY_LINK,
                products = emptyList(),
                selectedProduct = null,
                replyText = replyText,
                suggestedReply = suggestedReply,
                storeName = storeName,
                intent = intent,
                categoryId = categoryId,
                categoryName = categoryName,
                categoryUrl = categoryUrl
            )
        }

        // 2. Product comparison MUST remain comparison text (never random images).
        if (normalizedIntent in COMPARISON_INTENTS) {
            return ParsedPayload(
                responseType = ResponseType.PRODUCT_COMPARISON,
                products = products,
                selectedProduct = null,
                replyText = replyText,
                suggestedReply = suggestedReply,
                storeName = storeName,
                intent = intent,
                categoryId = categoryId,
                categoryName = categoryName,
                categoryUrl = categoryUrl
            )
        }

        // 3. Explicit selected product (from AI Orchestrator) if verified with valid image
        if (selectedProductId != null) {
            val picked = products.firstOrNull { it.productId == selectedProductId }
            if (picked != null && picked.canSendAsImage()) {
                val caption = picked.buildCaption()
                return ParsedPayload(
                    responseType = ResponseType.PRODUCT_IMAGE,
                    products = products,
                    selectedProduct = picked,
                    replyText = caption.ifBlank { replyText },
                    suggestedReply = suggestedReply,
                    storeName = storeName,
                    intent = intent,
                    categoryId = categoryId,
                    categoryName = categoryName,
                    categoryUrl = categoryUrl
                )
            }
        }

        // 4. Single product -> PRODUCT_IMAGE only if image URL is valid & non-placeholder
        if (products.size == 1) {
            val single = products.first()
            if (single.canSendAsImage()) {
                val caption = single.buildCaption()
                return ParsedPayload(
                    responseType = ResponseType.PRODUCT_IMAGE,
                    products = products,
                    selectedProduct = single,
                    replyText = caption.ifBlank { replyText },
                    suggestedReply = suggestedReply,
                    storeName = storeName,
                    intent = intent,
                    categoryId = categoryId,
                    categoryName = categoryName,
                    categoryUrl = categoryUrl
                )
            }
            val textFallback = replyText.ifBlank { single.buildTextFallbackReply() }
            return ParsedPayload(
                responseType = ResponseType.TEXT_MESSAGE,
                products = products,
                selectedProduct = null,
                replyText = textFallback,
                suggestedReply = suggestedReply,
                storeName = storeName,
                intent = intent,
                categoryId = categoryId,
                categoryName = categoryName,
                categoryUrl = categoryUrl
            )
        }

        // 5. Multiple products -> structured PRODUCT_LIST text message
        if (products.size > 1) {
            val effectiveText = replyText.ifBlank { formatProductListFallback(products) }
            return ParsedPayload(
                responseType = ResponseType.PRODUCT_LIST,
                products = products,
                selectedProduct = null,
                replyText = effectiveText,
                suggestedReply = suggestedReply,
                storeName = storeName,
                intent = intent,
                categoryId = categoryId,
                categoryName = categoryName,
                categoryUrl = categoryUrl
            )
        }

        // 6. Plain text message
        return ParsedPayload(
            responseType = ResponseType.TEXT_MESSAGE,
            products = emptyList(),
            selectedProduct = null,
            replyText = replyText,
            suggestedReply = suggestedReply,
            storeName = storeName,
            intent = intent,
            categoryId = categoryId,
            categoryName = categoryName,
            categoryUrl = categoryUrl
        )
    }

    fun parseProductObject(obj: JsonObject, defaultStoreName: String = ""): ProductMessage? {
        val name = firstNonBlank(
            str(obj["name"]),
            str(obj["title"]),
            str(obj["product_name"])
        ).let { ProductMessage.normalizeInlineText(it) }
        if (name.isEmpty()) return null

        val id = (obj["id"] ?: obj["product_id"])?.jsonPrimitive?.longOrNull
        val rawImage = extractProductImageUrl(obj)
        val rawPrice = firstNonBlank(
            str(obj["price"]),
            str(obj["price_html"]),
            obj["price_numeric"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0.0 }?.let { num ->
                if (num % 1.0 == 0.0) num.toLong().toString() else num.toString()
            }.orEmpty()
        )
        val currency = str(obj["currency"]).trim().takeIf { it.isNotEmpty() }
        val desc = firstNonBlank(
            str(obj["short_description"]),
            str(obj["description"])
        ).takeIf { it.isNotBlank() }?.let { ProductMessage.normalizeBlockText(it) }
            ?.takeIf { it.isNotBlank() }

        val url = firstNonBlank(
            str(obj["url"]),
            str(obj["permalink"]),
            str(obj["product_url"])
        ).takeIf { ProductMessage.isValidWebUrl(it) }

        val itemStoreName = firstNonBlank(
            str(obj["store_name"]),
            defaultStoreName
        )

        return ProductMessage(
            productId = id,
            storeName = ProductMessage.normalizeInlineText(itemStoreName),
            imageUrl = rawImage?.takeIf { ProductMessage.isValidProductImageUrl(it) },
            productName = name,
            price = rawPrice.takeIf { it.isNotBlank() },
            currency = currency,
            shortDescription = desc,
            productUrl = url
        )
    }

    private fun extractProducts(
        root: JsonObject,
        dataObj: JsonObject,
        storeName: String
    ): List<ProductMessage> {
        val arrayCandidates = listOfNotNull(
            root["products"] as? JsonArray,
            root["items"] as? JsonArray,
            dataObj["products"] as? JsonArray,
            dataObj["items"] as? JsonArray
        )
        val arrayProducts = arrayCandidates.firstOrNull()?.mapNotNull { el ->
            (el as? JsonObject)?.let { parseProductObject(it, storeName) }
        }.orEmpty()
        if (arrayProducts.isNotEmpty()) return arrayProducts

        val singleObj = (root["product"] as? JsonObject) ?: (dataObj["product"] as? JsonObject)
        if (singleObj != null) {
            val single = parseProductObject(singleObj, storeName)
            if (single != null) return listOf(single)
        }
        return emptyList()
    }

    private fun extractProductImageUrl(obj: JsonObject): String? {
        val direct = firstNonBlank(
            str(obj["image"]),
            str(obj["image_url"]),
            str(obj["thumbnail"]),
            str(obj["featured_image"])
        )
        if (direct.isNotBlank()) return direct

        val imagesArr = obj["images"] as? JsonArray
        val firstImg = imagesArr?.firstOrNull()
        return when (firstImg) {
            is JsonObject -> firstNonBlank(str(firstImg["src"]), str(firstImg["url"]))
                .takeIf { it.isNotBlank() }
            else -> firstImg?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    private fun formatProductListFallback(products: List<ProductMessage>): String {
        return buildString {
            append("إليك المنتجات المتوفرة:\n")
            products.take(5).forEach { p ->
                append("• ").append(p.productName)
                ProductMessage.formatPriceLine(p.price, p.currency)?.let {
                    append(" — ").append(it)
                }
                p.productUrl?.let { append("\n").append(it) }
                append('\n')
            }
        }.trim()
    }

    private fun extractNestedText(obj: JsonObject, dotPath: String): String {
        val parts = dotPath.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        var cur: JsonElement? = obj
        for (p in parts) {
            cur = (cur as? JsonObject)?.get(p)
        }
        return str(cur)
    }

    private fun str(element: JsonElement?): String {
        val prim = element as? kotlinx.serialization.json.JsonPrimitive ?: return ""
        return prim.contentOrNull?.trim().orEmpty()
    }

    private fun firstNonBlank(vararg values: String): String {
        return values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }
}
