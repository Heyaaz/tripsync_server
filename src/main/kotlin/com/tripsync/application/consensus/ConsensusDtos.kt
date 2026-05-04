package com.tripsync.application.consensus

import com.tripsync.domain.entity.AxisScores
import com.tripsync.domain.enums.ConflictSeverity
import com.tripsync.domain.enums.ReasonAxis
import com.tripsync.domain.enums.ScheduleOptionType
import com.tripsync.domain.enums.ScoreAxis
import com.tripsync.domain.enums.SlotType
import java.time.Instant

data class MemberSnapshot(
    val userId: Long,
    val nickname: String,
    val scores: AxisScores,
    val joinedOrder: Int,
)

data class PlaceCandidate(
    val id: Long,
    val name: String,
    val address: String,
    val category: String,
    val mobilityScore: Int,
    val photoScore: Int,
    val budgetScore: Int,
    val themeScore: Int,
    val metadataTags: Map<String, Any>? = null,
    val operatingHours: Map<String, Any>? = null,
)

data class ConflictAxisAnalysis(
    val axis: ScoreAxis,
    val min: Int,
    val max: Int,
    val gap: Int,
    val severity: ConflictSeverity,
    val highUserId: Long,
    val lowUserId: Long,
)

data class ScheduleSlotDraft(
    val orderIndex: Int,
    val slotType: SlotType,
    val targetUserId: Long?,
    val reasonAxis: ReasonAxis,
    val reasonText: String,
    val startTime: Instant,
    val endTime: Instant,
    val placeId: Long,
    val placeName: String,
    val placeAddress: String,
    val isHiddenGem: Boolean,
)

data class SatisfactionDraft(
    val userId: Long,
    val score: Int,
    val breakdown: Breakdown,
) {
    data class Breakdown(
        val overall: Int,
        val byAxis: Map<ScoreAxis, Double>,
    )
}

data class ScheduleOptionDraft(
    val optionType: ScheduleOptionType,
    val label: String,
    val summary: String,
    val groupSatisfaction: Int,
    val slots: List<ScheduleSlotDraft>,
    val satisfactionByUser: List<SatisfactionDraft>,
    val llmProvider: String,
    val llmLatencyMs: Long?,
    val fallbackUsed: Boolean,
)

data class OptionContext(
    val roomId: Long,
    val destination: String,
    val tripDate: String,
    val startTime: String,
    val endTime: String,
    val members: List<MemberSnapshot>,
    val places: List<PlaceCandidate>,
)
