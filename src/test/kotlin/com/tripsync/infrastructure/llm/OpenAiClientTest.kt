package com.tripsync.infrastructure.llm

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.tripsync.application.consensus.ConsensusService
import com.tripsync.application.consensus.LlmService
import com.tripsync.domain.enums.ScheduleOptionType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

class OpenAiClientTest {
    private val client = OpenAiClient(
        webClient = WebClient.create(),
        objectMapper = ObjectMapper(),
        apiKey = "test-key",
        model = "gpt-test",
        meterRegistry = SimpleMeterRegistry(),
    )

    @Test
    fun `refine schedule records fallback metrics when api key is missing`() = runBlocking {
        val meterRegistry = SimpleMeterRegistry()
        val blankKeyClient = OpenAiClient(
            webClient = WebClient.create(),
            objectMapper = ObjectMapper(),
            apiKey = "",
            model = "gpt-test",
            meterRegistry = meterRegistry,
        )

        val attempt = blankKeyClient.refineSchedule(
            optionType = ScheduleOptionType.BALANCED,
            label = "균형형",
            summary = "요약",
            room = ConsensusService.RoomRef(roomId = 1, destination = "충남", tripDate = "2026-06-01"),
            commonAxes = emptyList(),
            priorityAxes = emptyList(),
            members = emptyList(),
            slotPlan = emptyList(),
        )

        assertTrue(attempt.fallbackUsed)
        assertEquals(LlmService.FallbackReason.API_KEY_MISSING, attempt.fallbackReason)
        assertEquals(1.0, meterRegistry.counter(
            "tripsync.llm.refinement.calls",
            "provider", "openai/gpt-test",
            "optionType", "balanced",
            "outcome", "fallback",
            "reason", "api_key_missing",
        ).count())
    }

    @Test
    fun `refine schedule falls back quickly when openai call exceeds configured timeout`() = runBlocking {
        val slowWebClient = WebClient.builder()
            .exchangeFunction {
                Mono.delay(Duration.ofSeconds(3))
                    .thenReturn(ClientResponse.create(HttpStatus.OK).body("{}").build())
            }
            .build()
        val timeoutClient = OpenAiClient(
            webClient = slowWebClient,
            objectMapper = ObjectMapper(),
            apiKey = "test-key",
            model = "gpt-test",
            timeoutSeconds = 1,
            meterRegistry = SimpleMeterRegistry(),
        )

        val startNanos = System.nanoTime()
        val attempt = timeoutClient.refineSchedule(
            optionType = ScheduleOptionType.BALANCED,
            label = "균형형",
            summary = "요약",
            room = ConsensusService.RoomRef(roomId = 1, destination = "충남", tripDate = "2026-06-01"),
            commonAxes = emptyList(),
            priorityAxes = emptyList(),
            members = emptyList(),
            slotPlan = emptyList(),
        )
        val elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis()

        assertTrue(attempt.fallbackUsed)
        assertEquals(LlmService.FallbackReason.API_CALL_FAILED, attempt.fallbackReason)
        assertTrue(elapsedMillis < 2_500, "OpenAI timeout fallback should happen near the configured timeout")
    }

    @Test
    fun `parse response returns refinement result with latency and provider`() {
        val response = """
            {
              "choices": [
                {
                  "message": {
                    "content": "{\"summary\":\"차분한 하루\",\"slots\":[{\"orderIndex\":1,\"placeId\":10,\"reason\":\"공통 취향\"}]}"
                  }
                }
              ]
            }
        """.trimIndent()

        val result = client.parseResponse(response, latencyMs = 123)

        assertEquals("차분한 하루", result.summary)
        assertEquals("openai/gpt-test", result.provider)
        assertEquals(123, result.latencyMs)
        assertEquals(1, result.slots.size)
        assertEquals(10, result.slots.first().placeId)
    }

    @Test
    fun `validate refinement rejects partial slot selection`() {
        val result = LlmService.RefinementResult(
            summary = "요약",
            provider = "openai/gpt-test",
            latencyMs = 10,
            slots = listOf(LlmService.RefinedSlot(orderIndex = 1, placeId = 10, reason = "첫 슬롯만")),
        )
        val slotPlan = listOf(
            shortlist(orderIndex = 1, placeId = 10),
            shortlist(orderIndex = 2, placeId = 20),
        )

        val error = assertThrows(OpenAiClient.LlmParseException::class.java) {
            client.validateRefinement(result, slotPlan)
        }

        assertEquals(LlmService.FallbackReason.RESPONSE_SCHEMA_INVALID, error.reason)
    }

    @Test
    fun `validate refinement rejects place outside shortlist`() {
        val result = LlmService.RefinementResult(
            summary = "요약",
            provider = "openai/gpt-test",
            latencyMs = 10,
            slots = listOf(LlmService.RefinedSlot(orderIndex = 1, placeId = 999, reason = "후보 밖")),
        )
        val slotPlan = listOf(shortlist(orderIndex = 1, placeId = 10))

        val error = assertThrows(OpenAiClient.LlmParseException::class.java) {
            client.validateRefinement(result, slotPlan)
        }

        assertEquals(LlmService.FallbackReason.RESPONSE_SCHEMA_INVALID, error.reason)
    }

    @Test
    fun `validate refinement rejects duplicated place selections in one schedule`() {
        val result = LlmService.RefinementResult(
            summary = "요약",
            provider = "openai/gpt-test",
            latencyMs = 10,
            slots = listOf(
                LlmService.RefinedSlot(orderIndex = 1, placeId = 10, reason = "첫 슬롯"),
                LlmService.RefinedSlot(orderIndex = 2, placeId = 10, reason = "중복 슬롯"),
            ),
        )
        val slotPlan = listOf(
            shortlist(orderIndex = 1, placeId = 10),
            shortlist(orderIndex = 2, placeId = 10),
        )

        val error = assertThrows(OpenAiClient.LlmParseException::class.java) {
            client.validateRefinement(result, slotPlan)
        }

        assertEquals(LlmService.FallbackReason.RESPONSE_SCHEMA_INVALID, error.reason)
    }

    @Test
    fun `parse response classifies malformed json as parse failure`() {
        val error = assertThrows(OpenAiClient.LlmParseException::class.java) {
            client.parseResponse("not-json", latencyMs = 1)
        }

        assertEquals(LlmService.FallbackReason.RESPONSE_PARSE_FAILED, error.reason)
    }

    @Test
    fun `parse response classifies missing slots as schema failure`() {
        val response = """
            {
              "choices": [
                {
                  "message": {
                    "content": "{\"summary\":\"요약\",\"slots\":[]}"
                  }
                }
              ]
            }
        """.trimIndent()

        val error = assertThrows(OpenAiClient.LlmParseException::class.java) {
            client.parseResponse(response, latencyMs = 1)
        }

        assertEquals(LlmService.FallbackReason.RESPONSE_SCHEMA_INVALID, error.reason)
    }

    private fun shortlist(orderIndex: Int, placeId: Long): ConsensusService.SlotShortlist {
        return ConsensusService.SlotShortlist(
            orderIndex = orderIndex,
            startTime = "09:00",
            endTime = "11:00",
            slotType = com.tripsync.domain.enums.SlotType.COMMON,
            targetUserId = null,
            reasonAxis = com.tripsync.domain.enums.ReasonAxis.COMMON,
            candidatePlaces = listOf(ConsensusService.CandidatePlace(placeId, "장소", "카테고리", "주소")),
            deterministicPlaceId = placeId,
            deterministicReason = "기본",
        )
    }
}
