package com.tripsync.application.tourapi

import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.Place
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.PlaceRepository
import com.tripsync.infrastructure.tourapi.TourApiClient
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TourApiBatchService(
    private val tourApiClient: TourApiClient,
    private val placeRepository: PlaceRepository,
) {
    private val logger = KotlinLogging.logger {}

    @Scheduled(cron = "0 0 3 * * *")
    fun syncPlaces() {
        runCatching { syncChungnamPlacesInternal() }
            .onFailure { logger.warn(it) { "TourAPI daily sync failed" } }
    }

    @Transactional
    fun syncChungnamPlaces(user: User): ApiResponse<Map<String, Any>> {
        assertHostUser(user)
        return ApiResponse.ok(syncChungnamPlacesInternal())
    }

    @Transactional
    fun enrichChungnamPlaces(user: User, limit: Int = 50): ApiResponse<Map<String, Any>> {
        assertHostUser(user)
        val candidates = placeRepository.findByDelYn(YnFlag.N)
            .filter { needsDetailEnrichment(it) }
            .take(limit.coerceIn(1, 200))
        var enriched = 0
        var skipped = 0
        var failed = 0
        candidates.forEach { place ->
            val contentTypeId = place.metadataTags?.get("contentTypeId")?.toString()
            if (contentTypeId.isNullOrBlank()) {
                skipped += 1
                return@forEach
            }
            try {
                val common = runBlocking { tourApiClient.fetchDetailCommon(place.tourApiId) }
                val intro = runBlocking { tourApiClient.fetchDetailIntro(place.tourApiId, contentTypeId) }
                enrichPlace(place, common, intro)
                enriched += 1
            } catch (error: Exception) {
                logger.warn(error) { "Failed to enrich place tourApiId=${place.tourApiId}" }
                failed += 1
            }
        }
        return ApiResponse.ok(
            mapOf(
                "scanned" to candidates.size,
                "enriched" to enriched,
                "skipped" to skipped,
                "failed" to failed,
            )
        )
    }


    private fun needsDetailEnrichment(place: Place): Boolean {
        val metadata = place.metadataTags ?: return true
        val enrichedAt = metadata["detailEnrichedAt"]?.toString() ?: return true
        val sourceModified = metadata["sourceModifiedTime"]?.toString()?.filter { it.isDigit() }
        if (sourceModified.isNullOrBlank()) return false
        val sourceEpoch = parseTourApiTimestamp(sourceModified) ?: return false
        val enrichedEpoch = runCatching { java.time.Instant.parse(enrichedAt).toEpochMilli() }.getOrNull() ?: return true
        return sourceEpoch > enrichedEpoch
    }

    private fun parseTourApiTimestamp(value: String): Long? {
        if (value.length < 8) return null
        val year = value.substring(0, 4)
        val month = value.substring(4, 6)
        val day = value.substring(6, 8)
        val hour = if (value.length >= 10) value.substring(8, 10) else "00"
        val minute = if (value.length >= 12) value.substring(10, 12) else "00"
        val second = if (value.length >= 14) value.substring(12, 14) else "00"
        return runCatching { java.time.OffsetDateTime.parse("$year-$month-${day}T$hour:$minute:$second+09:00").toInstant().toEpochMilli() }.getOrNull()
    }

    private fun enrichPlace(place: Place, common: Map<String, Any?>?, intro: Map<String, Any?>?) {
        place.name = common?.get("title")?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: place.name
        val nextAddress = listOf(common?.get("addr1"), common?.get("addr2")).mapNotNull { it?.toString() }.joinToString(" ").trim()
        if (nextAddress.isNotBlank()) place.address = nextAddress
        common?.get("mapy")?.toString()?.toBigDecimalOrNull()?.let { place.latitude = it }
        common?.get("mapx")?.toString()?.toBigDecimalOrNull()?.let { place.longitude = it }
        common?.get("firstimage")?.toString()?.takeIf { it.isNotBlank() }?.let { place.imageUrl = it }
        if (intro != null) {
            place.operatingHours = extractOperatingHours(intro)
            place.admissionFee = extractAdmissionFee(intro)
        }
        val detailMetadata = mutableMapOf<String, Any>(
            "detailEnrichedAt" to java.time.Instant.now().toString(),
        )
        common?.get("tel")?.let { detailMetadata["tel"] = it }
        common?.get("homepage")?.let { detailMetadata["homepage"] = it }
        common?.get("overview")?.let { detailMetadata["overview"] = it }
        intro?.let { detailMetadata["introFields"] = it }
        place.metadataTags = (place.metadataTags ?: emptyMap()) + detailMetadata
    }

    private fun extractOperatingHours(intro: Map<String, Any?>): Map<String, Any> {
        val rawFields = intro.filter { (key, value) ->
            value != null && listOf("usetime", "restdate", "checkintime", "checkouttime").any { key.lowercase().contains(it) }
        }.mapValues { it.value.toString() }
        if (rawFields.isEmpty()) return mapOf("status" to "unknown")
        val joined = rawFields.values.joinToString(" ")
        if (Regex("24시간|상시|연중무휴|항시").containsMatchIn(joined)) {
            return mapOf("status" to "always", "rawFields" to rawFields)
        }
        return mapOf("status" to "partial", "rawFields" to rawFields)
    }

    private fun extractAdmissionFee(intro: Map<String, Any?>): String? {
        val values = intro.filter { (key, value) ->
            value != null && listOf("usefee", "parkingfee").any { key.lowercase().contains(it) }
        }.map { (key, value) -> "$key: $value" }
        return values.takeIf { it.isNotEmpty() }?.joinToString(" | ")
    }

    private fun syncChungnamPlacesInternal(): Map<String, Any> {
        val contentTypeIds = listOf("12", "14", "15", "28", "32", "38", "39")
        val byType = mutableListOf<Map<String, Any>>()
        var totalFetched = 0
        var totalSynced = 0
        var totalUnchanged = 0

        contentTypeIds.forEach { contentTypeId ->
            val fetched = runBlocking { tourApiClient.fetchAreaBasedList(contentTypeId = contentTypeId) }
            var synced = 0
            var unchanged = 0
            fetched.forEach { incoming ->
                val existing = placeRepository.findByTourApiId(incoming.tourApiId)
                if (existing == null) {
                    placeRepository.save(incoming)
                    synced += 1
                } else if (mergePlace(existing, incoming)) {
                    synced += 1
                } else {
                    unchanged += 1
                }
            }
            totalFetched += fetched.size
            totalSynced += synced
            totalUnchanged += unchanged
            byType += mapOf(
                "contentTypeId" to contentTypeId.toInt(),
                "totalCount" to fetched.size,
                "synced" to synced,
                "skipped" to 0,
                "unchanged" to unchanged,
            )
        }

        return mapOf(
            "areaCode" to 34,
            "contentTypeIds" to contentTypeIds.map { it.toInt() },
            "totalFetched" to totalFetched,
            "totalSynced" to totalSynced,
            "totalSkipped" to 0,
            "totalUnchanged" to totalUnchanged,
            "byType" to byType,
        )
    }

    private fun mergePlace(existing: Place, incoming: Place): Boolean {
        var changed = false
        fun <T> update(current: T, next: T, setter: (T) -> Unit) {
            if (current != next) {
                setter(next)
                changed = true
            }
        }
        update(existing.name, incoming.name) { existing.name = it }
        update(existing.address, incoming.address) { existing.address = it }
        update(existing.latitude, incoming.latitude) { existing.latitude = it }
        update(existing.longitude, incoming.longitude) { existing.longitude = it }
        update(existing.category, incoming.category) { existing.category = it }
        update(existing.imageUrl, incoming.imageUrl) { existing.imageUrl = it }
        update(existing.metadataTags, incoming.metadataTags) { existing.metadataTags = it }
        existing.delYn = YnFlag.N
        return changed
    }

    private fun assertHostUser(user: User) {
        if (user.isGuest) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "방장 권한이 필요합니다.")
        }
    }
}
