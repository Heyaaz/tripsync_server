package com.tripsync.application.schedule

import com.tripsync.domain.entity.ExternalPopularityMetric
import com.tripsync.domain.entity.Place
import com.tripsync.domain.entity.Schedule
import com.tripsync.domain.entity.TripRoom
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.AuthProvider
import com.tripsync.domain.enums.ScheduleOptionType
import com.tripsync.domain.enums.TripRoomStatus
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.ExternalPopularityMetricRepository
import com.tripsync.domain.repository.RoomMemberProfileRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class ScheduleResponseMapperTest {
    private val roomMemberProfileRepository = mock(RoomMemberProfileRepository::class.java)
    private val externalPopularityMetricRepository = mock(ExternalPopularityMetricRepository::class.java)
    private val mapper = ScheduleResponseMapper(roomMemberProfileRepository, externalPopularityMetricRepository, "http://localhost:8080/api")

    @Test
    fun `stored schedule response includes persisted llm metadata`() {
        val schedule = scheduleWithLlmMetadata()
        `when`(roomMemberProfileRepository.findAllByRoomIdAndDelYn(schedule.room.id, YnFlag.N)).thenReturn(emptyList())

        val response = mapper.formatStoredSchedule(schedule)

        assertEquals("deterministic-consensus", response["llmProvider"])
        assertEquals("openai/gpt-4o-mini", response["llmAttemptedProvider"])
        assertEquals(35L, response["llmLatencyMs"])
        assertEquals(true, response["fallbackUsed"])
        assertEquals("response_schema_invalid", response["llmFallbackReason"])
    }

    @Test
    fun `public share response omits llm operational metadata`() {
        val schedule = scheduleWithLlmMetadata()
        `when`(roomMemberProfileRepository.findAllByRoomIdAndDelYn(schedule.room.id, YnFlag.N)).thenReturn(emptyList())

        val response = mapper.formatPublicShareSchedule(schedule)

        assertFalse(response.containsKey("llmProvider"))
        assertFalse(response.containsKey("llmAttemptedProvider"))
        assertFalse(response.containsKey("llmLatencyMs"))
        assertFalse(response.containsKey("fallbackUsed"))
        assertFalse(response.containsKey("llmFallbackReason"))
    }

    @Test
    fun `place response prefers TourAPI image and hides raw popularity score`() {
        val place = place(imageUrl = "https://tour.example/image.jpg")
        val metric = ExternalPopularityMetric(
            place = place,
            normalizedPopularityScore = 82,
            naverSearchTrendScore = 64,
            googleRating = BigDecimal("4.5"),
            googleUserRatingCount = 182,
            collectedAt = Instant.parse("2026-05-20T00:00:00Z"),
        )
        `when`(externalPopularityMetricRepository.findByPlaceId(place.id)).thenReturn(metric)

        val response = mapper.formatPlace(place, place.id, place.name, place.address)
        val popularity = response["popularity"] as Map<*, *>

        assertEquals("https://tour.example/image.jpg", response["imageUrl"])
        assertEquals("tourapi", response["imageSource"])
        assertNull(response["fallbackImageUrl"])
        assertNull(response["fallbackImageSource"])
        assertEquals("popular_anchor", popularity["role"])
        assertFalse(popularity.containsKey("score"))
        assertEquals(82, popularity["normalizedPopularityScore"])
        assertEquals(64, popularity["naverSearchTrendScore"])
        assertEquals(4.5, popularity["googleRating"])
        assertEquals(182, popularity["googleUserRatingCount"])
        assertEquals(metric.updatedAt.toString(), popularity["sourceUpdatedAt"])
    }

    @Test
    fun `place response falls back to proxied Google Places photo`() {
        val place = place(imageUrl = null)
        val metric = ExternalPopularityMetric(
            place = place,
            googlePhotoReference = "photo-ref",
            collectedAt = Instant.parse("2026-05-20T00:00:00Z"),
        )
        `when`(externalPopularityMetricRepository.findByPlaceId(place.id)).thenReturn(metric)

        val response = mapper.formatPlace(place, place.id, place.name, place.address)

        assertEquals("http://localhost:8080/api/places/${place.id}/photo", response["imageUrl"])
        assertEquals("google_places", response["imageSource"])
    }

    @Test
    fun `place response includes Google fallback image when TourAPI image exists`() {
        val place = place(imageUrl = "https://tour.example/image.jpg")
        val metric = ExternalPopularityMetric(
            place = place,
            googlePhotoReference = "photo-ref",
            collectedAt = Instant.parse("2026-05-20T00:00:00Z"),
        )
        `when`(externalPopularityMetricRepository.findByPlaceId(place.id)).thenReturn(metric)

        val response = mapper.formatPlace(place, place.id, place.name, place.address)

        assertEquals("https://tour.example/image.jpg", response["imageUrl"])
        assertEquals("tourapi", response["imageSource"])
        assertEquals("http://localhost:8080/api/places/${place.id}/photo", response["fallbackImageUrl"])
        assertEquals("google_places", response["fallbackImageSource"])
    }

    @Test
    fun `place response marks external signal missing without excluding place`() {
        val place = place(imageUrl = null)
        `when`(externalPopularityMetricRepository.findByPlaceId(place.id)).thenReturn(null)

        val response = mapper.formatPlace(place, place.id, place.name, place.address)
        val popularity = response["popularity"] as Map<*, *>

        assertNull(response["imageUrl"])
        assertNull(response["imageSource"])
        assertEquals("unverified", popularity["role"])
        assertEquals(false, popularity["hasExternalSignal"])
    }

    private fun scheduleWithLlmMetadata(): Schedule {
        val host = User(
            id = 10L,
            nickname = "host",
            authProvider = AuthProvider.LOCAL,
        )
        val room = TripRoom(
            id = 1L,
            hostUser = host,
            shareCode = "ABC123456789",
            destination = "충남",
            roomName = "충남 여행 계획",
            tripDate = LocalDate.parse("2026-06-01"),
            status = TripRoomStatus.COMPLETED,
        )
        return Schedule(
            id = 100L,
            room = room,
            version = 1,
            optionType = ScheduleOptionType.BALANCED,
            generationInput = mapOf(
                "destination" to "충남",
                "llm" to mapOf(
                    "provider" to "deterministic-consensus",
                    "attemptedProvider" to "openai/gpt-4o-mini",
                    "latencyMs" to 35L,
                    "fallbackUsed" to true,
                    "fallbackReason" to "response_schema_invalid",
                ),
            ),
            summary = "요약",
            groupSatisfaction = 72,
            personaValidation = null,
            llmProvider = "deterministic-consensus",
        )
    }

    private fun place(imageUrl: String?): Place {
        return Place(
            id = 200L,
            tourApiId = "tour-200",
            name = "공산성",
            address = "충청남도 공주시",
            latitude = BigDecimal("36.4625000"),
            longitude = BigDecimal("127.1249000"),
            category = "tourist_attraction",
            imageUrl = imageUrl,
            mobilityScore = 60,
            photoScore = 70,
            budgetScore = 50,
            themeScore = 55,
            metadataTags = emptyMap(),
        )
    }
}
