package com.tripsync.infrastructure.popularity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.LocalDate

@Component
class NaverDataLabClient(
    private val webClient: WebClient,
    private val properties: ExternalPopularityProperties,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun fetchSearchTrendScore(groupName: String, keywords: List<String>): Int? {
        val clientId = properties.naver.clientId
        val clientSecret = properties.naver.clientSecret
        if (clientId.isBlank() || clientSecret.isBlank()) {
            logger.warn { "Naver DataLab credentials not configured" }
            return null
        }
        if (keywords.isEmpty()) return null

        val endDate = LocalDate.now().minusDays(1)
        val startDate = endDate.minusDays(properties.naver.lookbackDays.toLong())
        val request = NaverDataLabSearchRequest(
            startDate = startDate.toString(),
            endDate = endDate.toString(),
            timeUnit = "date",
            keywordGroups = listOf(
                NaverKeywordGroup(
                    groupName = groupName.take(20),
                    keywords = keywords.distinct().take(20),
                )
            ),
        )

        val response = webClient.post()
            .uri(properties.naver.searchUrl)
            .header("X-Naver-Client-Id", clientId)
            .header("X-Naver-Client-Secret", clientSecret)
            .bodyValue(request)
            .retrieve()
            .bodyToMono<NaverDataLabSearchResponse>()
            .awaitSingle()

        val ratios = response.results.flatMap { it.data }.map { it.ratio }
        if (ratios.isEmpty()) return null
        return ratios.average().toInt().coerceIn(0, 100)
    }
}

data class NaverDataLabSearchRequest(
    val startDate: String,
    val endDate: String,
    val timeUnit: String,
    val keywordGroups: List<NaverKeywordGroup>,
)

data class NaverKeywordGroup(
    val groupName: String,
    val keywords: List<String>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NaverDataLabSearchResponse(
    val results: List<NaverDataLabResult> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NaverDataLabResult(
    val data: List<NaverDataLabPoint> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NaverDataLabPoint(
    val ratio: Double = 0.0,
)
