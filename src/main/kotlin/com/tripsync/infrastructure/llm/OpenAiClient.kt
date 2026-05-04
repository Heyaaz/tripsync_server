package com.tripsync.infrastructure.llm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tripsync.application.consensus.ConsensusService
import com.tripsync.application.consensus.LlmService
import com.tripsync.domain.enums.ScheduleOptionType
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@Component
class OpenAiClient(
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
    @Value("\${openai.api-key:}")
    private val apiKey: String,
    @Value("\${openai.model:gpt-4o-mini}")
    private val model: String,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun refineSchedule(
        optionType: ScheduleOptionType,
        label: String,
        summary: String,
        room: ConsensusService.RoomRef,
        commonAxes: List<com.tripsync.domain.enums.ScoreAxis>,
        priorityAxes: List<com.tripsync.domain.enums.ScoreAxis>,
        members: List<ConsensusService.MemberRef>,
        slotPlan: List<ConsensusService.SlotShortlist>,
    ): LlmService.RefinementResult? {
        if (apiKey.isBlank()) {
            logger.warn { "OpenAI API key not configured" }
            return null
        }

        val start = System.currentTimeMillis()

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
                .awaitSingle()

            val latencyMs = System.currentTimeMillis() - start
            parseResponse(response, latencyMs)
        } catch (e: Exception) {
            logger.error(e) { "LLM refinement failed" }
            null
        }
    }

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

            응답 형식:
            {
              "summary": "개선된 요약 문장",
              "slots": [
                {"orderIndex": 1, "placeId": 123, "reason": "선택 이유"}
              ]
            }
        """.trimIndent()
    }

    private fun parseResponse(response: String, latencyMs: Long): LlmService.RefinementResult? {
        val root = objectMapper.readTree(response)
        val content = root.path("choices").get(0)?.path("message")?.path("content")?.asText()
            ?: return null

        val result = objectMapper.readTree(content)
        val summary = result.path("summary").asText()
        val slots = result.path("slots").mapNotNull { slot ->
            val orderIndex = slot.path("orderIndex").asInt()
            val placeId = slot.path("placeId").asLong()
            val reason = slot.path("reason").asText()
            if (orderIndex > 0 && placeId > 0) {
                LlmService.RefinedSlot(orderIndex, placeId, reason)
            } else null
        }

        return LlmService.RefinementResult(
            summary = summary,
            provider = "openai/$model",
            latencyMs = latencyMs,
            slots = slots,
        )
    }
}
