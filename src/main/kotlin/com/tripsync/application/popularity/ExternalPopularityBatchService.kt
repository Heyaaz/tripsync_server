package com.tripsync.application.popularity

import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.ExternalPopularityMetric
import com.tripsync.domain.entity.Place
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.ExternalPopularityMetricRepository
import com.tripsync.domain.repository.PlaceRepository
import com.tripsync.infrastructure.popularity.ExternalPopularityProperties
import com.tripsync.infrastructure.popularity.GooglePlaceCandidate
import com.tripsync.infrastructure.popularity.GooglePlacesClient
import com.tripsync.infrastructure.popularity.NaverDataLabClient
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Service
class ExternalPopularityBatchService(
    private val placeRepository: PlaceRepository,
    private val metricRepository: ExternalPopularityMetricRepository,
    private val naverDataLabClient: NaverDataLabClient,
    private val googlePlacesClient: GooglePlacesClient,
    private val properties: ExternalPopularityProperties,
) {
    private val logger = KotlinLogging.logger {}

    @Scheduled(cron = "\${external-popularity.sync.cron:0 30 4 * * MON}")
    fun syncScheduled() {
        if (!properties.sync.enabled) {
            logger.info { "External popularity scheduled sync skipped: disabled" }
            return
        }

        runCatching { syncInternal(triggeredBy = "scheduled", operatorUserId = null) }
            .onSuccess { report -> logger.info { "External popularity scheduled sync completed: ${report.toMap()}" } }
            .onFailure { logger.warn(it) { "External popularity scheduled sync failed" } }
    }

    fun syncManually(user: User, limit: Int? = null): ApiResponse<Map<String, Any?>> {
        assertAdminUser(user)
        val boundedLimit = limit?.coerceIn(1, 1000)
        val report = syncInternal(triggeredBy = "manual", operatorUserId = user.id, limitOverride = boundedLimit)
        return ApiResponse.ok(report.toMap())
    }

    private fun syncInternal(triggeredBy: String, operatorUserId: Long?, limitOverride: Int? = null): ExternalPopularitySyncReport {
        val startedAt = Instant.now()
        val limit = (limitOverride ?: properties.sync.batchLimit).coerceAtLeast(1)
        val places = placeRepository.findByDelYn(YnFlag.N).take(limit)
        val rawResults = mutableListOf<ExternalPopularityRawResult>()

        places.forEachIndexed { index, place ->
            val raw = runCatching { collectPlaceSignals(place) }
                .getOrElse { error ->
                    logger.warn(error) { "External popularity collect failed placeId=${place.id}" }
                    recordFailure(place, error)
                    ExternalPopularityRawResult(place = place, error = error.message ?: error.javaClass.simpleName)
                }
            rawResults += raw
            throttleIfNeeded(index, places.lastIndex)
        }

        val successful = rawResults.filter { it.error == null }
        val maxReviewLog = successful.mapNotNull { it.googleUserRatingCount }
            .maxOfOrNull { ln(1.0 + it.toDouble()) }
            ?.takeIf { it > 0.0 }
            ?: 1.0

        var updated = 0
        successful.forEach { raw ->
            val normalized = normalizePopularity(raw, maxReviewLog)
            upsertMetric(raw, normalized)
            updated += 1
        }

        val report = ExternalPopularitySyncReport(
            triggeredBy = triggeredBy,
            operatorUserId = operatorUserId,
            startedAt = startedAt.toString(),
            finishedAt = Instant.now().toString(),
            scanned = places.size,
            updated = updated,
            failed = rawResults.count { it.error != null },
            failures = rawResults.filter { it.error != null }.take(20).map {
                mapOf(
                    "placeId" to it.place.id,
                    "placeName" to it.place.name,
                    "message" to it.error,
                )
            },
        )
        logger.info { "External popularity sync summary: ${report.toMap()}" }
        return report
    }

    private fun collectPlaceSignals(place: Place): ExternalPopularityRawResult {
        val keywords = buildNaverKeywords(place)
        val naverScore = runBlocking {
            naverDataLabClient.fetchSearchTrendScore(place.name, keywords)
        }
        val matched = runBlocking {
            val query = listOf(place.name, place.address).filter { it.isNotBlank() }.joinToString(" ")
            val candidates = googlePlacesClient.searchPlace(query, place.latitude, place.longitude)
            selectGoogleMatch(place, candidates)
        }

        return ExternalPopularityRawResult(
            place = place,
            naverSearchTrendScore = naverScore,
            googlePlaceId = matched?.place_id,
            googleRating = matched?.rating,
            googleUserRatingCount = matched?.user_ratings_total,
            googlePhotoReference = matched?.photos?.firstOrNull()?.photo_reference,
            error = null,
        )
    }

    private fun normalizePopularity(raw: ExternalPopularityRawResult, maxReviewLog: Double): Int? {
        val hasAnySignal = raw.naverSearchTrendScore != null || raw.googleUserRatingCount != null || raw.googleRating != null
        if (!hasAnySignal) return null

        val naver = raw.naverSearchTrendScore ?: 0
        val reviewVolume = raw.googleUserRatingCount
            ?.let { (ln(1.0 + it.toDouble()) / maxReviewLog * 100.0).roundToInt().coerceIn(0, 100) }
            ?: 0
        val ratingConfidence = if (raw.googleRating != null && raw.googleUserRatingCount != null) {
            (raw.googleRating.toDouble() / 5.0 * min(raw.googleUserRatingCount / 100.0, 1.0) * 100.0)
                .roundToInt()
                .coerceIn(0, 100)
        } else {
            0
        }

        return (naver * 0.60 + reviewVolume * 0.30 + ratingConfidence * 0.10)
            .roundToInt()
            .coerceIn(0, 100)
    }

    private fun upsertMetric(raw: ExternalPopularityRawResult, normalizedPopularityScore: Int?) {
        val now = Instant.now()
        val metric = metricRepository.findByPlaceId(raw.place.id)
            ?: ExternalPopularityMetric(place = raw.place, collectedAt = now)
        metric.naverSearchTrendScore = raw.naverSearchTrendScore
        metric.googlePlaceId = raw.googlePlaceId
        metric.googleRating = raw.googleRating?.setScale(1, RoundingMode.HALF_UP)
        metric.googleUserRatingCount = raw.googleUserRatingCount
        metric.googlePhotoReference = raw.googlePhotoReference
        metric.normalizedPopularityScore = normalizedPopularityScore
        metric.collectedAt = now
        metric.expiresAt = now.plusSeconds(properties.sync.expiresAfterDays * 24 * 60 * 60)
        metric.lastError = null
        metricRepository.save(metric)
    }

    private fun recordFailure(place: Place, error: Throwable) {
        val now = Instant.now()
        val metric = metricRepository.findByPlaceId(place.id)
            ?: ExternalPopularityMetric(place = place, collectedAt = now)
        metric.lastError = error.message ?: error.javaClass.simpleName
        metricRepository.save(metric)
    }

    private fun buildNaverKeywords(place: Place): List<String> {
        val regionTerms = buildList {
            metadataText(place, "region")?.let { add(it) }
            metadataText(place, "area")?.let { add(it) }
            extractRegionFromAddress(place.address)?.let { add(it) }
            extractSigunguFromAddress(place.address)?.let { add(it) }
        }

        return buildList {
            add(place.name)
            regionTerms.forEach { add("$it ${place.name}") }
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    private fun selectGoogleMatch(place: Place, candidates: List<GooglePlaceCandidate>): GooglePlaceCandidate? {
        return candidates
            .mapNotNull { candidate ->
                val location = candidate.geometry?.location ?: return@mapNotNull null
                val lat = location.lat ?: return@mapNotNull null
                val lng = location.lng ?: return@mapNotNull null
                val distanceMeters = haversineMeters(place.latitude.toDouble(), place.longitude.toDouble(), lat.toDouble(), lng.toDouble())
                if (distanceMeters > properties.google.matchRadiusMeters) return@mapNotNull null
                val nameSimilarity = nameSimilarity(place.name, candidate.name ?: "")
                if (nameSimilarity < properties.google.minNameSimilarity) return@mapNotNull null
                GoogleMatch(candidate, nameSimilarity, distanceMeters)
            }
            .sortedWith(
                compareByDescending<GoogleMatch> { it.nameSimilarity }
                    .thenByDescending { it.candidate.user_ratings_total ?: 0 }
                    .thenBy { it.distanceMeters }
            )
            .firstOrNull()
            ?.candidate
    }

    private fun nameSimilarity(left: String, right: String): Double {
        val leftTokens = bigrams(normalizeName(left))
        val rightTokens = bigrams(normalizeName(right))
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
        val intersection = leftTokens.intersect(rightTokens).size
        return (2.0 * intersection) / (leftTokens.size + rightTokens.size)
    }

    private fun bigrams(value: String): Set<String> {
        if (value.length <= 1) return if (value.isBlank()) emptySet() else setOf(value)
        return value.windowed(2).toSet()
    }

    private fun normalizeName(value: String): String {
        return value.lowercase().replace(Regex("[^0-9a-z가-힣]"), "")
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMeters = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMeters * c
    }

    private fun metadataText(place: Place, key: String): String? {
        return place.metadataTags?.get(key)?.toString()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun extractRegionFromAddress(address: String): String? {
        return address.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun extractSigunguFromAddress(address: String): String? {
        return address.trim().split(Regex("\\s+")).drop(1).firstOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun throttleIfNeeded(index: Int, lastIndex: Int) {
        if (index >= lastIndex || properties.sync.requestIntervalMillis <= 0) return
        runCatching { Thread.sleep(properties.sync.requestIntervalMillis) }
            .onFailure { Thread.currentThread().interrupt() }
    }

    private fun assertAdminUser(user: User) {
        if (user.isGuest || user.adminYn != YnFlag.Y) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "관리자 권한이 필요합니다.")
        }
    }

    private data class GoogleMatch(
        val candidate: GooglePlaceCandidate,
        val nameSimilarity: Double,
        val distanceMeters: Double,
    )
}

data class ExternalPopularityRawResult(
    val place: Place,
    val naverSearchTrendScore: Int? = null,
    val googlePlaceId: String? = null,
    val googleRating: BigDecimal? = null,
    val googleUserRatingCount: Int? = null,
    val googlePhotoReference: String? = null,
    val error: String? = null,
)

data class ExternalPopularitySyncReport(
    val triggeredBy: String,
    val operatorUserId: Long?,
    val startedAt: String,
    val finishedAt: String,
    val scanned: Int,
    val updated: Int,
    val failed: Int,
    val failures: List<Map<String, Any?>>,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "triggeredBy" to triggeredBy,
        "operatorUserId" to operatorUserId,
        "startedAt" to startedAt,
        "finishedAt" to finishedAt,
        "scanned" to scanned,
        "updated" to updated,
        "failed" to failed,
        "failures" to failures,
    )
}
