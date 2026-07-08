package com.tripsync.application.schedule

import com.tripsync.domain.entity.Schedule
import com.tripsync.domain.entity.TripRoom
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.AuthProvider
import com.tripsync.domain.enums.ScheduleOptionType
import com.tripsync.domain.enums.TripRoomStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ScheduleResponseMapperTest {
    private val mapper = ScheduleResponseMapper()

    @Test
    fun `stored schedule response includes persisted llm metadata`() {
        val schedule = scheduleWithLlmMetadata()

        val response = mapper.formatStoredSchedule(schedule, emptyMap(), emptyList(), emptyList())

        assertEquals("deterministic-consensus", response["llmProvider"])
        assertEquals("openai/gpt-4o-mini", response["llmAttemptedProvider"])
        assertEquals(35L, response["llmLatencyMs"])
        assertEquals(true, response["fallbackUsed"])
        assertEquals("response_schema_invalid", response["llmFallbackReason"])
    }

    @Test
    fun `public share response omits llm operational metadata`() {
        val schedule = scheduleWithLlmMetadata()

        val response = mapper.formatPublicShareSchedule(schedule, emptyMap(), emptyList(), emptyList())

        assertFalse(response.containsKey("llmProvider"))
        assertFalse(response.containsKey("llmAttemptedProvider"))
        assertFalse(response.containsKey("llmLatencyMs"))
        assertFalse(response.containsKey("fallbackUsed"))
        assertFalse(response.containsKey("llmFallbackReason"))
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
}
