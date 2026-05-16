package com.tripsync.application.consensus

import com.fasterxml.jackson.databind.ObjectMapper
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.AxisScores
import com.tripsync.infrastructure.llm.OpenAiClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.time.ZoneId

class ConsensusServiceTest {
    private val consensusService = ConsensusService(
        LlmService(
            OpenAiClient(
                webClient = WebClient.create(),
                objectMapper = ObjectMapper(),
                apiKey = "",
                model = "gpt-4o-mini",
            )
        )
    )

    @Test
    fun `schedule generation supports non MVP destination when matching place candidates exist`() = runBlocking {
        val options = consensusService.buildScheduleOptions(
            context(
                destination = "전북",
                startTime = "10:30",
                endTime = "15:30",
                members = members(6),
                places = places("전라북도 전주시 완산구"),
            )
        )

        assertEquals(3, options.size)
        options.forEach { option ->
            assertTrue(option.slots.isNotEmpty())
            assertEquals("10:30", option.slots.first().startTime.toSeoulTime())
            assertEquals("15:30", option.slots.last().endTime.toSeoulTime())
            assertTrue(option.slots.all { it.placeAddress.contains("전라북도") })
        }
        assertTrue(options.last().summary.contains("전북"))
    }

    @Test
    fun `schedule generation uses requested time window instead of fixed nine to twenty one`() = runBlocking {
        val options = consensusService.buildScheduleOptions(
            context(
                destination = "충남",
                startTime = "08:00",
                endTime = "12:00",
                members = members(3),
                places = places("충청남도 공주시"),
            )
        )

        options.forEach { option ->
            assertEquals("08:00", option.slots.first().startTime.toSeoulTime())
            assertEquals("12:00", option.slots.last().endTime.toSeoulTime())
        }
    }

    @Test
    fun `schedule generation reports missing candidates instead of rejecting non Chungnam destination`() {
        val error = assertThrows(DomainException::class.java) {
            runBlocking {
                consensusService.buildScheduleOptions(
                    context(
                        destination = "부산",
                        startTime = "09:00",
                        endTime = "21:00",
                        members = members(2),
                        places = places("충청남도 태안군"),
                    )
                )
            }
        }

        assertEquals("PLACE_CANDIDATE_EMPTY", error.code)
    }

    private fun context(
        destination: String,
        startTime: String,
        endTime: String,
        members: List<MemberSnapshot>,
        places: List<PlaceCandidate>,
    ) = OptionContext(
        roomId = 1L,
        destination = destination,
        tripDate = "2026-06-01",
        startTime = startTime,
        endTime = endTime,
        members = members,
        places = places,
    )

    private fun members(count: Int): List<MemberSnapshot> {
        return (1..count).map { index ->
            MemberSnapshot(
                userId = index.toLong(),
                nickname = "member-$index",
                scores = AxisScores(
                    mobility = if (index % 2 == 0) 90 else 20,
                    photo = 40 + index,
                    budget = if (index % 3 == 0) 85 else 35,
                    theme = 55,
                ),
                joinedOrder = index - 1,
            )
        }
    }

    private fun places(addressPrefix: String): List<PlaceCandidate> {
        val categories = listOf("tourist_attraction", "restaurant", "cultural_facility", "leisure_sports", "shopping", "festival", "accommodation")
        return (1..10).map { index ->
            PlaceCandidate(
                id = index.toLong(),
                name = "place-$index",
                address = "$addressPrefix $index",
                category = categories[(index - 1) % categories.size],
                mobilityScore = 30 + index * 3,
                photoScore = 80 - index,
                budgetScore = 50 + index,
                themeScore = 45 + index,
                metadataTags = mapOf("hiddenGem" to (index % 2 == 0)),
                operatingHours = mapOf("status" to "always"),
            )
        }
    }

    private fun java.time.Instant.toSeoulTime(): String {
        return atZone(ZoneId.of("Asia/Seoul")).toLocalTime().toString().take(5)
    }
}
