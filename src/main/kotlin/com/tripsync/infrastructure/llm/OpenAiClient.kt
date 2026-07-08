package com.tripsync.infrastructure.llm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import com.tripsync.application.consensus.ConsensusService
import com.tripsync.application.consensus.LlmService
import com.tripsync.domain.enums.ScheduleOptionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration
import java.util.concurrent.TimeUnit

@Component
class OpenAiClient(
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
    @Value("\${openai.api-key:}")
    private val apiKey: String,
    @Value("\${openai.model:gpt-4o-mini}")
    private val model: String,
    @Value("\${openai.timeout-seconds:10}")
    private val timeoutSeconds: Long = 10,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}
    private val providerName: String
        get() = "openai/$model"

    suspend fun refineSchedule(
        optionType: ScheduleOptionType,
        label: String,
        summary: String,
        room: ConsensusService.RoomRef,
        commonAxes: List<com.tripsync.domain.enums.ScoreAxis>,
        priorityAxes: List<com.tripsync.domain.enums.ScoreAxis>,
        members: List<ConsensusService.MemberRef>,
        slotPlan: List<ConsensusService.SlotShortlist>,
    ): LlmService.RefinementAttempt {
        if (apiKey.isBlank()) {
            recordMetrics(optionType, "fallback", LlmService.FallbackReason.API_KEY_MISSING.code, 0)
            logger.warn {
                "llm_refinement outcome=fallback reason=${LlmService.FallbackReason.API_KEY_MISSING.code} provider=$providerName optionType=$optionType roomId=${room.roomId}"
            }
            return fallbackAttempt(
                reason = LlmService.FallbackReason.API_KEY_MISSING,
                latencyMs = 0,
                detail = "OpenAI API key not configured",
            )
        }

        val startNanos = System.nanoTime()
        val prompt = buildPrompt(optionType, label, summary, room, commonAxes, priorityAxes, members, slotPlan)
        val requestBody = mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf(
                    "role" to "system",
                    "content" to "당신은 여행 일정 조정 전문가입니다. 제공된 슬롯 후보 목록에서 각 슬롯에 가장 적합한 장소를 선택하고, 일정 전체 요약을 개선해주세요. 응답은 반드시 JSON 형식이어야 합니다.",
                ),
                mapOf("role" to "user", "content" to prompt),
            ),
            "response_format" to mapOf("type" to "json_object"),
            "temperature" to 0.3,
        )

        return try {
            val response = webClient.post()
                .uri("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono<String>()
                .timeout(Duration.ofSeconds(timeoutSeconds.coerceAtLeast(1)))
                .awaitSingle()

            val latencyMs = elapsedMillis(startNanos)
            val parsed = parseResponse(response, latencyMs)
            validateRefinement(parsed, slotPlan)
            recordMetrics(optionType, "success", "none", latencyMs)
            logger.info {
                "llm_refinement outcome=success provider=$providerName optionType=$optionType roomId=${room.roomId} latencyMs=$latencyMs slots=${parsed.slots.size}"
            }
            LlmService.RefinementAttempt(
                result = parsed,
                attemptedProvider = providerName,
                latencyMs = latencyMs,
                fallbackUsed = false,
                fallbackReason = null,
            )
        } catch (e: LlmParseException) {
            val latencyMs = elapsedMillis(startNanos)
            recordMetrics(optionType, "fallback", e.reason.code, latencyMs)
            logger.warn(e) {
                "llm_refinement outcome=fallback reason=${e.reason.code} provider=$providerName optionType=$optionType roomId=${room.roomId} latencyMs=$latencyMs"
            }
            fallbackAttempt(e.reason, latencyMs, e.message)
        } catch (e: WebClientResponseException) {
            val latencyMs = elapsedMillis(startNanos)
            recordMetrics(optionType, "fallback", LlmService.FallbackReason.API_HTTP_ERROR.code, latencyMs)
            logger.error(e) {
                "llm_refinement outcome=fallback reason=${LlmService.FallbackReason.API_HTTP_ERROR.code} provider=$providerName optionType=$optionType roomId=${room.roomId} latencyMs=$latencyMs status=${e.statusCode.value()}"
            }
            fallbackAttempt(LlmService.FallbackReason.API_HTTP_ERROR, latencyMs, "HTTP ${e.statusCode.value()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val latencyMs = elapsedMillis(startNanos)
            recordMetrics(optionType, "fallback", LlmService.FallbackReason.API_CALL_FAILED.code, latencyMs)
            logger.error(e) {
                "llm_refinement outcome=fallback reason=${LlmService.FallbackReason.API_CALL_FAILED.code} provider=$providerName optionType=$optionType roomId=${room.roomId} latencyMs=$latencyMs"
            }
            fallbackAttempt(LlmService.FallbackReason.API_CALL_FAILED, latencyMs, e.javaClass.simpleName)
        }
    }

    private fun elapsedMillis(startNanos: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos).coerceAtLeast(0)
    }

    private fun recordMetrics(
        optionType: ScheduleOptionType,
        outcome: String,
        reason: String,
        latencyMs: Long,
    ) {
        val tags = listOf(
            "provider", providerName,
            "optionType", optionType.name.lowercase(),
            "outcome", outcome,
            "reason", reason,
        )
        val tagArray = tags.toTypedArray()
        meterRegistry.counter("tripsync.llm.refinement.calls", *tagArray).increment()
        Timer.builder("tripsync.llm.refinement.latency")
            .tags(*tagArray)
            .register(meterRegistry)
            .record(latencyMs, TimeUnit.MILLISECONDS)
    }

    private fun fallbackAttempt(
        reason: LlmService.FallbackReason,
        latencyMs: Long,
        detail: String?,
    ): LlmService.RefinementAttempt = LlmService.RefinementAttempt(
        result = null,
        attemptedProvider = providerName,
        latencyMs = latencyMs,
        fallbackUsed = true,
        fallbackReason = reason,
        failureDetail = detail,
    )

    private fun buildPrompt(
        optionType: ScheduleOptionType,
        label: String,
        summary: String,
        room: ConsensusService.RoomRef,
        commonAxes: List<com.tripsync.domain.enums.ScoreAxis>,
        priorityAxes: List<com.tripsync.domain.enums.ScoreAxis>,
        members: List<ConsensusService.MemberRef>,
        slotPlan: List<ConsensusService.SlotShortlist>,
    ): String {
        val slotDescriptions = slotPlan.joinToString("\n") { slot ->
            val candidates = slot.candidatePlaces.joinToString(", ") { "${it.name}(${it.id})" }
            "${slot.orderIndex}. ${slot.startTime}~${slot.endTime} [${slot.slotType}] ${slot.reasonAxis} - 후보: $candidates"
        }

        return """
            여행 일정 최적화 요청

            방 ID: ${room.roomId}
            목적지: ${room.destination}
            날짜: ${room.tripDate}
            옵션 유형: $label (${optionType.name})

            공통 지대: ${commonAxes.joinToString()}
            우선 축: ${priorityAxes.joinToString()}
            멤버: ${members.joinToString { it.nickname }}

            슬롯 계획:
            $slotDescriptions

            각 슬롯의 후보 장소 중에서 가장 적합한 장소를 선택하고, 일정 전체 요약을 50자 이내로 개선해주세요.
            같은 일정 안에서는 동일한 장소 ID를 두 번 이상 선택하지 마세요.

            응답 형식:
            {
              "summary": "개선된 요약 문장",
              "slots": [
                {"orderIndex": 1, "placeId": 123, "reason": "선택 이유"}
              ]
            }
        """.trimIndent()
    }

    internal fun parseResponse(response: String, latencyMs: Long): LlmService.RefinementResult {
        val root = readJson(response, LlmService.FallbackReason.RESPONSE_PARSE_FAILED)
        val choices = root.path("choices")
        if (!choices.isArray || choices.size() == 0) {
            throw LlmParseException(LlmService.FallbackReason.RESPONSE_SCHEMA_INVALID, "OpenAI response choices missing")
        }

        val content = choices.get(0)?.path("message")?.path("content")?.asText()?.trim().orEmpty()
        if (content.isBlank()) {
            throw LlmParseException(LlmService.FallbackReason.RESPONSE_SCHEMA_INVALID, "OpenAI response content missing")
        }

        val result = readJson(content, LlmService.FallbackReason.RESPONSE_PARSE_FAILED)
        val summary = result.path("summary").asText().trim()
        if (summary.isBlank()) {
            throw LlmParseException(LlmService.FallbackReason.RESPONSE_SCHEMA_INVALID, "LLM summary missing")
        }

        val rawSlots = result.path("slots")
        if (!rawSlots.isArray) {
            throw LlmParseException(LlmService.FallbackReason.RESPONSE_SCHEMA_INVALID, "LLM slots missing")
        }

        val slots = rawSlots.mapNotNull { slot ->
            val orderIndex = slot.path("orderIndex").asInt()
            val placeId = slot.path("placeId").asLong()
            val reason = slot.path("reason").asText().trim()
            if (orderIndex > 0 && placeId > 0) {
                LlmService.RefinedSlot(orderIndex, placeId, reason.ifBlank { "LLM 추천" })
            } else null
        }
        if (slots.isEmpty()) {
            throw LlmParseException(LlmService.FallbackReason.RESPONSE_SCHEMA_INVALID, "LLM slots contain no valid place selection")
        }

        return LlmService.RefinementResult(
            summary = summary,
            provider = providerName,
            latencyMs = latencyMs,
            slots = slots,
        )
    }

    internal fun validateRefinement(
        result: LlmService.RefinementResult,
        slotPlan: List<ConsensusService.SlotShortlist>,
    ) {
        val validPlaceIdsByOrder = slotPlan.associate { slot ->
            slot.orderIndex to slot.candidatePlaces.map { it.id }.toSet()
        }
        val expectedOrders = validPlaceIdsByOrder.keys
        val actualOrders = result.slots.map { it.orderIndex }
        if (actualOrders.toSet() != expectedOrders || actualOrders.size != expectedOrders.size) {
            throw LlmParseException(LlmService.FallbackReason.RESPONSE_SCHEMA_INVALID, "LLM slots must contain exactly one selection for every shortlisted slot")
        }

        val hasInvalidSelection = result.slots.any { slot ->
            slot.placeId !in validPlaceIdsByOrder.getOrDefault(slot.orderIndex, emptySet())
        }
        if (hasInvalidSelection) {
            throw LlmParseException(LlmService.FallbackReason.RESPONSE_SCHEMA_INVALID, "LLM slots contain place selection outside shortlist")
        }

        if (result.slots.map { it.placeId }.toSet().size != result.slots.size) {
            throw LlmParseException(LlmService.FallbackReason.RESPONSE_SCHEMA_INVALID, "LLM slots contain duplicated place selection")
        }
    }

    private fun readJson(value: String, reason: LlmService.FallbackReason): JsonNode {
        return try {
            objectMapper.readTree(value)
        } catch (e: Exception) {
            throw LlmParseException(reason, "LLM JSON parse failed", e)
        }
    }

    internal class LlmParseException(
        val reason: LlmService.FallbackReason,
        message: String,
        cause: Throwable? = null,
    ) : RuntimeException(message, cause)
}
