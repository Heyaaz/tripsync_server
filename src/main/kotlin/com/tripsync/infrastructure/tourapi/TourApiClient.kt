package com.tripsync.infrastructure.tourapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tripsync.domain.entity.Place
import com.tripsync.domain.repository.PlaceRepository
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.math.BigDecimal

@Component
class TourApiClient(
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
    private val placeRepository: PlaceRepository,
    @Value("\${tourapi.key:}")
    private val apiKey: String,
    @Value("\${tourapi.base-url:https://apis.data.go.kr/B551011/KorService2}")
    private val baseUrl: String,
    @Value("\${tourapi.mobile-os:ETC}")
    private val mobileOs: String,
    @Value("\${tourapi.mobile-app:TripSync}")
    private val mobileApp: String,
    @Value("\${tourapi.response-type:json}")
    private val responseType: String,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun fetchAreaBasedList(areaCode: String = "34", sigunguCode: String? = null, pageNo: Int = 1, contentTypeId: String? = null): List<Place> {
        if (apiKey.isBlank()) {
            logger.warn { "TourAPI key not configured" }
            return emptyList()
        }

        val uriBuilder = org.springframework.web.util.UriComponentsBuilder.fromHttpUrl("$baseUrl/areaBasedList2")
            .queryParam("serviceKey", apiKey)
            .queryParam("numOfRows", 100)
            .queryParam("pageNo", pageNo)
            .queryParam("MobileOS", mobileOs)
            .queryParam("MobileApp", mobileApp)
            .queryParam("_type", responseType)
            .queryParam("areaCode", areaCode)

        sigunguCode?.let { uriBuilder.queryParam("sigunguCode", it) }
        contentTypeId?.let { uriBuilder.queryParam("contentTypeId", it) }

        val response = webClient.get()
            .uri(uriBuilder.build().toUri())
            .retrieve()
            .bodyToMono<String>()
            .awaitSingle()

        return parsePlaces(response)
    }


    suspend fun fetchDetailCommon(contentId: String): Map<String, Any?>? {
        return fetchDetail("detailCommon2", mapOf("contentId" to contentId))
    }

    suspend fun fetchDetailIntro(contentId: String, contentTypeId: String): Map<String, Any?>? {
        return fetchDetail("detailIntro2", mapOf("contentId" to contentId, "contentTypeId" to contentTypeId))
    }

    private suspend fun fetchDetail(path: String, params: Map<String, String>): Map<String, Any?>? {
        if (apiKey.isBlank()) {
            logger.warn { "TourAPI key not configured" }
            return null
        }
        val uriBuilder = org.springframework.web.util.UriComponentsBuilder.fromHttpUrl("$baseUrl/$path")
            .queryParam("serviceKey", apiKey)
            .queryParam("MobileOS", mobileOs)
            .queryParam("MobileApp", mobileApp)
            .queryParam("_type", responseType)
        params.forEach { (key, value) -> uriBuilder.queryParam(key, value) }
        val response = webClient.get()
            .uri(uriBuilder.build().toUri())
            .retrieve()
            .bodyToMono<String>()
            .awaitSingle()
        val item = objectMapper.readTree(response).path("response").path("body").path("items").path("item")
        val node = if (item.isArray) item.firstOrNull() else item
        if (node == null || node.isMissingNode || node.isNull) return null
        return objectMapper.convertValue(node, object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {})
    }

    private fun parsePlaces(response: String): List<Place> {
        val root = objectMapper.readTree(response)
        val items = root.path("response").path("body").path("items").path("item")

        if (!items.isArray) return emptyList()

        return items.mapNotNull { item ->
            try {
                parsePlace(item)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse place: ${item.path("title").asText()}" }
                null
            }
        }
    }

    private fun parsePlace(item: JsonNode): Place {
        val contentId = item.path("contentid").asText()
        val existing = placeRepository.findByTourApiId(contentId)

        val place = existing ?: Place(
            tourApiId = contentId,
            name = item.path("title").asText(),
            address = item.path("addr1").asText(""),
            latitude = BigDecimal(item.path("mapy").asText("0")),
            longitude = BigDecimal(item.path("mapx").asText("0")),
            category = inferCategory(item.path("contenttypeid").asText()),
            imageUrl = item.path("firstimage").asText(),
            mobilityScore = baseScores(item.path("contenttypeid").asText(), item.path("title").asText())["mobility"] ?: 50,
            photoScore = baseScores(item.path("contenttypeid").asText(), item.path("title").asText())["photo"] ?: 50,
            budgetScore = baseScores(item.path("contenttypeid").asText(), item.path("title").asText())["budget"] ?: 50,
            themeScore = baseScores(item.path("contenttypeid").asText(), item.path("title").asText())["theme"] ?: 50,
            metadataTags = mapOf(
                "contentTypeId" to item.path("contenttypeid").asText(),
                "areaCode" to item.path("areacode").asText(),
                "sigunguCode" to item.path("sigungucode").asText(),
            ),
        )

        return place
    }

    private fun inferCategory(contentTypeId: String): String {
        return when (contentTypeId) {
            "12" -> "tourist_attraction"
            "14" -> "cultural_facility"
            "15" -> "festival"
            "28" -> "leisure_sports"
            "32" -> "accommodation"
            "38" -> "shopping"
            "39" -> "restaurant"
            else -> "etc"
        }
    }

    private fun baseScores(contentTypeId: String, title: String): Map<String, Int> {
        val base = when (contentTypeId) {
            "12" -> mapOf("mobility" to 60, "photo" to 60, "budget" to 45, "theme" to 45)
            "14" -> mapOf("mobility" to 40, "photo" to 55, "budget" to 45, "theme" to 60)
            "15" -> mapOf("mobility" to 55, "photo" to 75, "budget" to 55, "theme" to 65)
            "28" -> mapOf("mobility" to 85, "photo" to 55, "budget" to 60, "theme" to 45)
            "32" -> mapOf("mobility" to 15, "photo" to 35, "budget" to 70, "theme" to 35)
            "38" -> mapOf("mobility" to 55, "photo" to 60, "budget" to 70, "theme" to 75)
            "39" -> mapOf("mobility" to 30, "photo" to 50, "budget" to 55, "theme" to 65)
            else -> mapOf("mobility" to 50, "photo" to 50, "budget" to 50, "theme" to 50)
        }
        var mobility = base["mobility"]!!
        var photo = base["photo"]!!
        var budget = base["budget"]!!
        var theme = base["theme"]!!
        fun hasAny(vararg words: String) = words.any { title.contains(it, ignoreCase = true) }
        if (hasAny("해변", "해수욕장", "산", "트레킹", "둘레길", "케이블카", "레일바이크")) { mobility += 15; photo += 5; theme -= 10 }
        if (hasAny("수목원", "공원", "정원", "사찰", "숲", "휴양림")) { theme -= 20; photo += 5 }
        if (hasAny("시장", "거리", "쇼핑", "아울렛")) { budget += 15; theme += 20 }
        if (hasAny("전망대", "포토", "미디어", "빛축제", "벽화")) { photo += 20 }
        if (hasAny("호텔", "리조트", "풀빌라", "스파")) { budget += 15; mobility -= 10 }
        if (hasAny("맛집", "식당", "카페", "베이커리")) { budget += 5; theme += 10 }
        fun clamp(v: Int) = v.coerceIn(0, 100)
        return mapOf("mobility" to clamp(mobility), "photo" to clamp(photo), "budget" to clamp(budget), "theme" to clamp(theme))
    }

}
