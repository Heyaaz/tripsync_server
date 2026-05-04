package com.tripsync.application.consensus

import com.tripsync.domain.enums.ScheduleOptionType
import com.tripsync.infrastructure.llm.OpenAiClient
import org.springframework.stereotype.Service

@Service
class LlmService(
    private val openAiClient: OpenAiClient,
) {

    data class RefinedSlot(
        val orderIndex: Int,
        val placeId: Long,
        val reason: String,
    )

    data class RefinementResult(
        val summary: String,
        val provider: String,
        val latencyMs: Long,
        val slots: List<RefinedSlot>,
    )

    suspend fun refineScheduleOption(
        optionType: ScheduleOptionType,
        label: String,
        summary: String,
        room: ConsensusService.RoomRef,
        commonAxes: List<com.tripsync.domain.enums.ScoreAxis>,
        priorityAxes: List<com.tripsync.domain.enums.ScoreAxis>,
        members: List<ConsensusService.MemberRef>,
        slotPlan: List<ConsensusService.SlotShortlist>,
    ): RefinementResult? {
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
