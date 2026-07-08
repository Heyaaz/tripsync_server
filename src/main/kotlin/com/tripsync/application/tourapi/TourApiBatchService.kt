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
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@ConfigurationProperties(prefix = "tourapi.sync")
data class TourApiSyncProperties(
    val enabled: Boolean = true,
    val areaCode: String = "34",
    val contentTypeIds: List<String> = listOf("12", "14", "15", "28", "32", "38", "39"),
    val numOfRows: Int = 100,
    val maxPages: Int = 1,
    val retryMaxAttempts: Int = 3,
    val retryBackoffMillis: Long = 500,
    val requestIntervalMillis: Long = 0,
    val enrichLimit: Int = 50,
)

@Service
class TourApiBatchService(
    private val tourApiClient: TourApiClient,
    private val placeRepository: PlaceRepository,
    private val syncProperties: TourApiSyncProperties,
    transactionManager: PlatformTransactionManager,
) {
    private val logger = KotlinLogging.logger {}
    private val writeTransaction = TransactionTemplate(transactionManager)
    private val readOnlyTransaction = TransactionTemplate(transactionManager).apply {
        isReadOnly = true
    }

    @Scheduled(cron = "\${tourapi.sync.cron:0 0 3 * * *}")
    fun syncPlaces() {
        if (!syncProperties.enabled) {
            logger.info { "TourAPI scheduled sync skipped: disabled" }
            return
        }
        runCatching { syncConfiguredAreaInternal(triggeredBy = "scheduled", operatorUserId = null) }
            .onSuccess { report ->
                if (report.totalFailed > 0) {
                    logger.warn { "TourAPI scheduled sync completed with failures: ${report.toMap()}" }
                } else {
                    logger.info { "TourAPI scheduled sync completed: ${report.toMap()}" }
                }
            }
            .onFailure { logger.warn(it) { "TourAPI scheduled sync failed" } }
    }

    fun syncChungnamPlaces(user: User): ApiResponse<Map<String, Any?>> {
        assertAdminUser(user)
        val report = syncConfiguredAreaInternal(triggeredBy = "manual", operatorUserId = user.id)
        return ApiResponse.ok(report.toMap())
    }

    fun enrichChungnamPlaces(user: User, limit: Int = syncProperties.enrichLimit): ApiResponse<Map<String, Any?>> {
        assertAdminUser(user)
        val boundedLimit = limit.coerceIn(1, 200)
        val candidates = loadEnrichmentCandidates(boundedLimit)
        var enriched = 0
        var skipped = 0
        var failed = 0
        val failures = mutableListOf<Map<String, Any>>()
        candidates.forEachIndexed { index, candidate ->
            val contentTypeId = candidate.contentTypeId
            if (contentTypeId.isNullOrBlank()) {
                skipped += 1
                return@forEachIndexed
            }
            val result = runCatching {
                val common = retryTourApiCall("detailCommon", candidate.tourApiId) {
                    runBlocking { tourApiClient.fetchDetailCommon(candidate.tourApiId) }
                }
                val intro = retryTourApiCall("detailIntro", candidate.tourApiId) {
                    runBlocking { tourApiClient.fetchDetailIntro(candidate.tourApiId, contentTypeId) }
                }
                saveEnrichedPlace(candidate.placeId, common, intro)
            }
            if (result.isSuccess) {
                if (result.getOrDefault(false)) enriched += 1 else skipped += 1
            } else {
                failed += 1
                val error = result.exceptionOrNull()
                logger.warn(error) { "Failed to enrich place tourApiId=${candidate.tourApiId}" }
                failures += failureOf(candidate.tourApiId, contentTypeId, error)
            }
            throttleIfNeeded(index, candidates.lastIndex)
        }
        val report = mapOf(
            "triggeredBy" to "manual",
            "operatorUserId" to user.id,
            "scanned" to candidates.size,
            "enriched" to enriched,
            "skipped" to skipped,
            "failed" to failed,
            "failures" to failures.take(20),
        )
        logger.info { "TourAPI enrich completed: $report" }
        return ApiResponse.ok(report)
    }

    private fun loadEnrichmentCandidates(limit: Int): List<TourApiEnrichmentCandidate> {
        return readOnlyTransaction.execute {
            val pageSize = (limit * 3).coerceIn(limit, 200)
            val candidates = mutableListOf<TourApiEnrichmentCandidate>()
            var afterId = 0L

            while (candidates.size < limit) {
                val page = placeRepository.findDetailEnrichmentCandidatesAfterId(afterId, pageSize)
                if (page.isEmpty()) break
                afterId = page.last().id

                page.asSequence()
                    .filter { needsDetailEnrichment(it) }
                    .take(limit - candidates.size)
                    .mapTo(candidates) {
                        TourApiEnrichmentCandidate(
                            placeId = it.id,
                            tourApiId = it.tourApiId,
                            contentTypeId = it.metadataTags?.get("contentTypeId")?.toString(),
                        )
                    }
            }

            candidates
        } ?: emptyList()
    }

    private fun saveEnrichedPlace(
        placeId: Long,
        common: Map<String, Any?>?,
        intro: Map<String, Any?>?,
    ): Boolean {
        return writeTransaction.execute {
            val place = placeRepository.findById(placeId).orElse(null) ?: return@execute false
            if (place.delYn != YnFlag.N || !needsDetailEnrichment(place)) return@execute false
            enrichPlace(place, common, intro)
            true
        } ?: false
    }

    private fun needsDetailEnrichment(place: Place): Boolean {
        val metadata = place.metadataTags ?: return true
        val enrichedAt = metadata["detailEnrichedAt"]?.toString() ?: return true
        val sourceModified = metadata["sourceModifiedTime"]?.toString()?.filter { it.isDigit() }
        if (sourceModified.isNullOrBlank()) return false
        val sourceEpoch = parseTourApiTimestamp(sourceModified) ?: return false
        val enrichedEpoch = runCatching { Instant.parse(enrichedAt).toEpochMilli() }.getOrNull() ?: return true
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
            "detailEnrichedAt" to Instant.now().toString(),
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

    private fun syncConfiguredAreaInternal(triggeredBy: String, operatorUserId: Long?): TourApiSyncReport {
        val startedAt = Instant.now()
        val byType = syncProperties.contentTypeIds.map { contentTypeId ->
            syncContentType(contentTypeId)
        }
        val report = TourApiSyncReport(
            areaCode = syncProperties.areaCode,
            contentTypeIds = syncProperties.contentTypeIds,
            triggeredBy = triggeredBy,
            operatorUserId = operatorUserId,
            startedAt = startedAt.toString(),
            finishedAt = Instant.now().toString(),
            byType = byType,
        )
        logger.info { "TourAPI sync summary: ${report.toMap()}" }
        return report
    }

    private fun syncContentType(contentTypeId: String): TourApiContentTypeReport {
        var totalFetched = 0
        var created = 0
        var updated = 0
        var unchanged = 0
        var failed = 0
        val failures = mutableListOf<Map<String, Any>>()

        for (pageNo in 1..syncProperties.maxPages.coerceAtLeast(1)) {
            val fetched = runCatching {
                retryTourApiCall("areaBasedList", "contentTypeId=$contentTypeId,page=$pageNo") {
                    runBlocking {
                        tourApiClient.fetchAreaBasedList(
                            areaCode = syncProperties.areaCode,
                            pageNo = pageNo,
                            contentTypeId = contentTypeId,
                            numOfRows = syncProperties.numOfRows,
                        )
                    }
                }
            }.getOrElse { error ->
                failed += 1
                logger.warn(error) { "TourAPI sync page failed contentTypeId=$contentTypeId pageNo=$pageNo" }
                failures += failureOf("page=$pageNo", contentTypeId, error)
                emptyList()
            }

            totalFetched += fetched.size
            fetched.chunked(50).forEach { chunk ->
                val result = runCatching { upsertPlacesChunk(contentTypeId, chunk) }
                if (result.isSuccess) {
                    val counts = result.getOrThrow()
                    created += counts.created
                    updated += counts.updated
                    unchanged += counts.unchanged
                    failed += counts.failed
                    failures += counts.failures
                } else {
                    val error = result.exceptionOrNull()
                    failed += chunk.size
                    logger.warn(error) { "TourAPI place upsert chunk failed contentTypeId=$contentTypeId size=${chunk.size}" }
                    failures += chunk.map { failureOf(it.tourApiId, contentTypeId, error) }
                }
            }
            throttleIfNeeded(pageNo, syncProperties.maxPages)
            if (fetched.size < syncProperties.numOfRows) break
        }

        return TourApiContentTypeReport(
            contentTypeId = contentTypeId,
            totalFetched = totalFetched,
            created = created,
            updated = updated,
            unchanged = unchanged,
            failed = failed,
            failures = failures.take(20),
        )
    }

    private fun upsertPlacesChunk(contentTypeId: String, incomingPlaces: List<Place>): TourApiUpsertCounts {
        if (incomingPlaces.isEmpty()) return TourApiUpsertCounts()
        return writeTransaction.execute {
            val uniqueIncomingPlaces = incomingPlaces.associateBy { it.tourApiId }.values.toList()
            val existingByTourApiId = placeRepository.findByTourApiIdIn(uniqueIncomingPlaces.map { it.tourApiId })
                .associateBy { it.tourApiId }
            val toSave = mutableListOf<Place>()
            var created = 0
            var updated = 0
            var unchanged = 0
            var failed = 0
            val failures = mutableListOf<Map<String, Any>>()

            uniqueIncomingPlaces.forEach { incoming ->
                runCatching {
                    val existing = existingByTourApiId[incoming.tourApiId]
                    when {
                        existing == null -> {
                            incoming.metadataTags = operationMetadata(incoming.metadataTags, contentTypeId, markSyncedAt = true)
                            toSave += incoming
                            created += 1
                        }
                        mergePlace(existing, incoming, contentTypeId) -> {
                            toSave += existing
                            updated += 1
                        }
                        else -> unchanged += 1
                    }
                }.onFailure { error ->
                    failed += 1
                    logger.warn(error) { "TourAPI place upsert failed tourApiId=${incoming.tourApiId}" }
                    failures += failureOf(incoming.tourApiId, contentTypeId, error)
                }
            }

            if (toSave.isNotEmpty()) {
                placeRepository.saveAll(toSave)
            }
            TourApiUpsertCounts(
                created = created,
                updated = updated,
                unchanged = unchanged,
                failed = failed,
                failures = failures,
            )
        } ?: TourApiUpsertCounts()
    }

    private fun <T> retryTourApiCall(operation: String, target: String, block: () -> T): T {
        val maxAttempts = syncProperties.retryMaxAttempts.coerceAtLeast(1)
        var lastError: Throwable? = null
        repeat(maxAttempts) { attemptIndex ->
            try {
                return block()
            } catch (error: Throwable) {
                lastError = error
                val attempt = attemptIndex + 1
                logger.warn(error) { "TourAPI $operation failed target=$target attempt=$attempt/$maxAttempts" }
                if (attempt < maxAttempts) sleepQuietly(syncProperties.retryBackoffMillis * attempt)
            }
        }
        throw lastError ?: IllegalStateException("TourAPI $operation failed target=$target")
    }

    private fun mergePlace(existing: Place, incoming: Place, contentTypeId: String): Boolean {
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
        val nextMetadata = operationMetadata(
            metadata = (existing.metadataTags ?: emptyMap()) + (incoming.metadataTags ?: emptyMap()),
            contentTypeId = contentTypeId,
            markSyncedAt = false,
        )
        update(existing.metadataTags, nextMetadata) { existing.metadataTags = it }
        if (existing.delYn != YnFlag.N) {
            existing.delYn = YnFlag.N
            changed = true
        }
        if (changed) {
            existing.metadataTags = operationMetadata(existing.metadataTags, contentTypeId, markSyncedAt = true)
        }
        return changed
    }

    private fun operationMetadata(metadata: Map<String, Any>?, contentTypeId: String, markSyncedAt: Boolean): Map<String, Any> {
        val base = (metadata ?: emptyMap()) + mapOf(
            "contentTypeId" to contentTypeId,
            "source" to "TourAPI",
            "sourceAreaCode" to syncProperties.areaCode,
        )
        return if (markSyncedAt) base + ("lastSyncedAt" to Instant.now().toString()) else base
    }

    private fun failureOf(target: String, contentTypeId: String, error: Throwable?): Map<String, Any> {
        return mapOf(
            "target" to target,
            "contentTypeId" to contentTypeId,
            "errorType" to (error?.javaClass?.simpleName ?: "Unknown"),
            "message" to (error?.message ?: ""),
        )
    }

    private fun throttleIfNeeded(index: Int, lastIndex: Int) {
        if (index < lastIndex) sleepQuietly(syncProperties.requestIntervalMillis)
    }

    private fun sleepQuietly(millis: Long) {
        if (millis <= 0) return
        runCatching { Thread.sleep(millis) }
            .onFailure { Thread.currentThread().interrupt() }
    }

    private fun assertAdminUser(user: User) {
        if (user.isGuest || user.adminYn != YnFlag.Y) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "관리자 권한이 필요합니다.")
        }
    }
}

data class TourApiSyncReport(
    val areaCode: String,
    val contentTypeIds: List<String>,
    val triggeredBy: String,
    val operatorUserId: Long?,
    val startedAt: String,
    val finishedAt: String,
    val byType: List<TourApiContentTypeReport>,
) {
    val totalFetched: Int = byType.sumOf { it.totalFetched }
    val totalCreated: Int = byType.sumOf { it.created }
    val totalUpdated: Int = byType.sumOf { it.updated }
    val totalSynced: Int = totalCreated + totalUpdated
    val totalUnchanged: Int = byType.sumOf { it.unchanged }
    val totalFailed: Int = byType.sumOf { it.failed }

    fun toMap(): Map<String, Any?> = mapOf(
        "areaCode" to areaCode,
        "contentTypeIds" to contentTypeIds.mapNotNull { it.toIntOrNull() },
        "triggeredBy" to triggeredBy,
        "operatorUserId" to operatorUserId,
        "startedAt" to startedAt,
        "finishedAt" to finishedAt,
        "totalFetched" to totalFetched,
        "totalCreated" to totalCreated,
        "totalUpdated" to totalUpdated,
        "totalSynced" to totalSynced,
        "totalUnchanged" to totalUnchanged,
        "totalFailed" to totalFailed,
        "byType" to byType.map { it.toMap() },
    )
}

data class TourApiContentTypeReport(
    val contentTypeId: String,
    val totalFetched: Int,
    val created: Int,
    val updated: Int,
    val unchanged: Int,
    val failed: Int,
    val failures: List<Map<String, Any>>,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "contentTypeId" to (contentTypeId.toIntOrNull() ?: contentTypeId),
        "totalFetched" to totalFetched,
        "created" to created,
        "updated" to updated,
        "synced" to created + updated,
        "unchanged" to unchanged,
        "failed" to failed,
        "failures" to failures,
    )
}

private data class TourApiEnrichmentCandidate(
    val placeId: Long,
    val tourApiId: String,
    val contentTypeId: String?,
)

private data class TourApiUpsertCounts(
    val created: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val failed: Int = 0,
    val failures: List<Map<String, Any>> = emptyList(),
)
