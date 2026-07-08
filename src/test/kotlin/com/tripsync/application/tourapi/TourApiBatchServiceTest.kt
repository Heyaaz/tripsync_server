package com.tripsync.application.tourapi

import com.fasterxml.jackson.databind.ObjectMapper
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.Place
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.AuthProvider
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.PlaceRepository
import com.tripsync.infrastructure.tourapi.TourApiClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.`when`
import org.mockito.Mockito.anyList
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal

class TourApiBatchServiceTest {
    @Test
    fun `manual sync requires admin user`() {
        val service = service()
        val user = User(
            nickname = "member",
            authProvider = AuthProvider.LOCAL,
            isGuest = false,
            adminYn = YnFlag.N,
        )

        val error = assertThrows(DomainException::class.java) {
            service.syncChungnamPlaces(user)
        }

        assertEquals("FORBIDDEN", error.code)
    }

    @Test
    fun `manual sync returns operation report for admin user`() {
        val service = service()
        val admin = User(
            id = 7,
            nickname = "admin",
            authProvider = AuthProvider.LOCAL,
            adminYn = YnFlag.Y,
        )

        val report = service.syncChungnamPlaces(admin).data!!
        val byType = report["byType"] as List<*>

        assertEquals("manual", report["triggeredBy"])
        assertEquals(7L, report["operatorUserId"])
        assertEquals(0, report["totalFetched"])
        assertEquals(0, report["totalFailed"])
        assertEquals(1, byType.size)
        assertTrue(report.containsKey("startedAt"))
        assertTrue(report.containsKey("finishedAt"))
    }


    @Test
    fun `unchanged place merge does not update sync timestamp`() {
        val service = service()
        val existing = place(
            tourApiId = "same-1",
            name = "같은 장소",
            metadataTags = mapOf(
                "contentTypeId" to "12",
                "source" to "TourAPI",
                "sourceAreaCode" to "34",
                "lastSyncedAt" to "2026-01-01T00:00:00Z",
            ),
        )
        val incoming = place(
            tourApiId = "same-1",
            name = "같은 장소",
            metadataTags = mapOf(
                "contentTypeId" to "12",
                "source" to "TourAPI",
                "sourceAreaCode" to "34",
            ),
        )

        val changed = invokeMergePlace(service, existing, incoming, "12")

        assertEquals(false, changed)
        assertEquals("2026-01-01T00:00:00Z", existing.metadataTags?.get("lastSyncedAt"))
    }

    @Test
    fun `changed place merge preserves detail metadata and updates sync timestamp`() {
        val service = service()
        val existing = place(
            tourApiId = "changed-1",
            name = "기존 장소",
            metadataTags = mapOf(
                "contentTypeId" to "12",
                "detailEnrichedAt" to "2026-01-01T00:00:00Z",
                "lastSyncedAt" to "2026-01-01T00:00:00Z",
            ),
        )
        val incoming = place(
            tourApiId = "changed-1",
            name = "변경 장소",
            metadataTags = mapOf("sourceModifiedTime" to "20260517090000"),
        )

        val changed = invokeMergePlace(service, existing, incoming, "12")

        assertEquals(true, changed)
        assertEquals("변경 장소", existing.name)
        assertEquals("2026-01-01T00:00:00Z", existing.metadataTags?.get("detailEnrichedAt"))
        assertEquals("20260517090000", existing.metadataTags?.get("sourceModifiedTime"))
        assertTrue(existing.metadataTags?.get("lastSyncedAt") != "2026-01-01T00:00:00Z")
    }

    @Test
    fun `enrichment candidate loader keeps paging until it finds stale candidates`() {
        val placeRepository = mock(PlaceRepository::class.java)
        val service = service(placeRepository)
        val currentRows = listOf(
            place(
                id = 1,
                tourApiId = "current-1",
                metadataTags = currentDetailMetadata(),
            ),
            place(
                id = 2,
                tourApiId = "current-2",
                metadataTags = currentDetailMetadata(),
            ),
            place(
                id = 3,
                tourApiId = "current-3",
                metadataTags = currentDetailMetadata(),
            ),
        )
        val stale = place(
            id = 4,
            tourApiId = "stale-1",
            metadataTags = mapOf(
                "contentTypeId" to "12",
                "detailEnrichedAt" to "2026-01-01T00:00:00Z",
                "sourceModifiedTime" to "20260102000000",
            ),
        )
        `when`(placeRepository.findDetailEnrichmentCandidatesAfterId(0L, 3)).thenReturn(currentRows)
        `when`(placeRepository.findDetailEnrichmentCandidatesAfterId(3L, 3)).thenReturn(listOf(stale))

        val candidates = invokeLoadEnrichmentCandidates(service, limit = 1)

        assertEquals(1, candidates.size)
        assertEquals("stale-1", candidateField(candidates.first(), "tourApiId"))
        verify(placeRepository).findDetailEnrichmentCandidatesAfterId(3L, 3)
    }

    @Test
    fun `sync chunk deduplicates repeated tour api ids before save all`() {
        val placeRepository = mock(PlaceRepository::class.java)
        val service = service(placeRepository)
        val first = place(
            tourApiId = "dup-1",
            name = "중복 장소 1",
            metadataTags = mapOf("sourceModifiedTime" to "20260101000000"),
        )
        val second = place(
            tourApiId = "dup-1",
            name = "중복 장소 2",
            metadataTags = mapOf("sourceModifiedTime" to "20260102000000"),
        )
        `when`(placeRepository.findByTourApiIdIn(listOf("dup-1"))).thenReturn(emptyList())
        `when`(placeRepository.saveAll(anyList<Place>())).thenAnswer { invocation -> invocation.arguments[0] }

        val counts = invokeUpsertPlacesChunk(service, "12", listOf(first, second))

        assertEquals(1, countField(counts, "created"))
        @Suppress("UNCHECKED_CAST")
        val savedCaptor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<Place>>
        verify(placeRepository).saveAll(savedCaptor.capture())
        assertEquals(1, savedCaptor.value.size)
        assertEquals("중복 장소 2", savedCaptor.value.first().name)
    }

    private fun service(placeRepository: PlaceRepository = mock(PlaceRepository::class.java)): TourApiBatchService {
        val client = TourApiClient(
            webClient = WebClient.create(),
            objectMapper = ObjectMapper(),
            apiKey = "",
            baseUrl = "https://example.invalid",
            mobileOs = "ETC",
            mobileApp = "TripSyncTest",
            responseType = "json",
        )
        return TourApiBatchService(
            tourApiClient = client,
            placeRepository = placeRepository,
            syncProperties = TourApiSyncProperties(
                contentTypeIds = listOf("12"),
                maxPages = 1,
                retryMaxAttempts = 1,
                requestIntervalMillis = 0,
            ),
            transactionManager = NoopTransactionManager(),
        )
    }

    private fun invokeLoadEnrichmentCandidates(service: TourApiBatchService, limit: Int): List<Any> {
        val method = TourApiBatchService::class.java.getDeclaredMethod(
            "loadEnrichmentCandidates",
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(service, limit) as List<Any>
    }

    private fun candidateField(candidate: Any, fieldName: String): Any? {
        val field = candidate.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(candidate)
    }

    private fun invokeUpsertPlacesChunk(service: TourApiBatchService, contentTypeId: String, incomingPlaces: List<Place>): Any {
        val method = TourApiBatchService::class.java.getDeclaredMethod(
            "upsertPlacesChunk",
            String::class.java,
            List::class.java,
        )
        method.isAccessible = true
        return method.invoke(service, contentTypeId, incomingPlaces)
    }

    private fun countField(counts: Any, fieldName: String): Any? {
        val field = counts.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(counts)
    }

    private fun invokeMergePlace(
        service: TourApiBatchService,
        existing: Place,
        incoming: Place,
        contentTypeId: String,
    ): Boolean {
        val method = TourApiBatchService::class.java.getDeclaredMethod(
            "mergePlace",
            Place::class.java,
            Place::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(service, existing, incoming, contentTypeId) as Boolean
    }

    private fun currentDetailMetadata(): Map<String, Any> = mapOf(
        "contentTypeId" to "12",
        "detailEnrichedAt" to "2026-01-02T00:00:00Z",
        "sourceModifiedTime" to "20260101000000",
    )

    private fun place(
        tourApiId: String,
        name: String = "테스트 장소",
        metadataTags: Map<String, Any>,
        id: Long = 0,
    ): Place {
        return Place(
            id = id,
            tourApiId = tourApiId,
            name = name,
            address = "충청남도 테스트군",
            latitude = BigDecimal("36.5000000"),
            longitude = BigDecimal("126.5000000"),
            category = "tourist_attraction",
            imageUrl = "",
            mobilityScore = 50,
            photoScore = 50,
            budgetScore = 50,
            themeScore = 50,
            metadataTags = metadataTags,
        )
    }

    private class NoopTransactionManager : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()
        override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
        override fun doCommit(status: DefaultTransactionStatus) = Unit
        override fun doRollback(status: DefaultTransactionStatus) = Unit
    }
}
