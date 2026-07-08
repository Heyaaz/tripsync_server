package com.tripsync.application.consensus

import com.tripsync.domain.enums.ScheduleOptionType
import com.tripsync.infrastructure.llm.OpenAiClient
import org.springframework.stereotype.Service

@Service
open class LlmService(
    private val openAiClient: OpenAiClient,
) {
    open val attemptedProvider: String
        get() = openAiClient.providerName


    data class RefinedSlot(
        val orderIndex: Int,
        val placeId: Long,
        val reason: String,
    )

    enum class FallbackReason(val code: String) {
        API_KEY_MISSING("api_key_missing"),
        API_HTTP_ERROR("api_http_error"),
        API_CALL_FAILED("api_call_failed"),
        RESPONSE_PARSE_FAILED("response_parse_failed"),
        RESPONSE_SCHEMA_INVALID("response_schema_invalid"),
    }

    data class RefinementResult(
        val summary: String,
        val provider: String,
        val latencyMs: Long,
        val slots: List<RefinedSlot>,
    )

    data class RefinementAttempt(
        val result: RefinementResult?,
        val attemptedProvider: String,
        val latencyMs: Long?,
        val fallbackUsed: Boolean,
        val fallbackReason: FallbackReason?,
        val failureDetail: String? = null,
    )

    open suspend fun refineScheduleOption(
        optionType: ScheduleOptionType,
        label: String,
        summary: String,
        room: ConsensusService.RoomRef,
        commonAxes: List<com.tripsync.domain.enums.ScoreAxis>,
        priorityAxes: List<com.tripsync.domain.enums.ScoreAxis>,
        members: List<ConsensusService.MemberRef>,
        slotPlan: List<ConsensusService.SlotShortlist>,
    ): RefinementAttempt {
        return openAiClient.refineSchedule(
            optionType = optionType,
            label = label,
            summary = summary,
            room = room,
            commonAxes = commonAxes,
            priorityAxes = priorityAxes,
            members = members,
            slotPlan = slotPlan,
        )
    }
}
