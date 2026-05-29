package com.tripsync.application.persona

import com.tripsync.domain.entity.AxisScores
import com.tripsync.domain.enums.ScheduleOptionType
import com.tripsync.domain.enums.SlotType
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class PersonaValidationService(
    private val personaVectorService: PersonaVectorService,
) {
    private val logger = KotlinLogging.logger {}

    data class ValidationResult(
        val source: String = "synthetic_research",
        val dataset: String = "nvidia/Nemotron-Personas-Korea",
        val personaAcceptanceScore: Int,
        val matchedPersonaCount: Int,
        val matchedPersonas: List<MatchedPersonaDetail>,
        val topPositiveSignals: List<String>,
        val objectionReasons: List<String>,
        val persuasionPoints: List<String>,
    )

    data class MatchedPersonaDetail(
        val matchedUserId: Long,
        val similarity: Double,
        val personaSummary: String?,
        val scores: AxisScores,
    )

    data class MemberSnapshot(
        val userId: Long,
        val scores: AxisScores,
    )

    data class ScheduleOptionForValidation(
        val optionType: ScheduleOptionType,
        val groupSatisfaction: Int,
        val slots: List<ScheduleSlotForValidation>,
        val satisfactionByUser: List<SatisfactionForValidation>,
    )

    data class ScheduleSlotForValidation(
        val slotType: SlotType,
        val reasonText: String,
        val scores: AxisScores,
    )

    data class SatisfactionForValidation(
        val userId: Long,
        val score: Int,
    )

    fun validateOptions(
        options: List<ScheduleOptionForValidation>,
        members: List<MemberSnapshot>,
    ): Map<ScheduleOptionType, ValidationResult> {
        val start = System.currentTimeMillis()

        val allMatchedPersonas = members.flatMap { member ->
            personaVectorService.matchForMember(member.scores, topK = 3)
        }.distinctBy { it.uuid }

        if (allMatchedPersonas.isEmpty()) {
            logger.info { "No persona matches available for ${members.size} members in ${System.currentTimeMillis() - start}ms" }
            return emptyMap()
        }

        logger.info { "Matched ${allMatchedPersonas.size} unique personas for ${members.size} members in ${System.currentTimeMillis() - start}ms" }

        return options.associate { option ->
            val slotScores = option.slots.map { slot ->
                val avgMatch = allMatchedPersonas.map { persona ->
                    1.0 - (slot.scores.l1Distance(persona.scores) / 400.0)
                }.average()
                avgMatch
            }

            val acceptanceScore = if (slotScores.isEmpty()) {
                0
            } else {
                (slotScores.average() * 100).toInt().coerceIn(0, 100)
            }

            val positiveSignals = buildPositiveSignals(option, acceptanceScore)
            val objections = buildObjections(option, members)
            val persuasions = buildPersuasionPoints(option)

            option.optionType to ValidationResult(
                personaAcceptanceScore = acceptanceScore,
                matchedPersonaCount = allMatchedPersonas.size,
                matchedPersonas = members.map { member ->
                    val topMatch = personaVectorService.matchForMember(member.scores, 1).firstOrNull()
                    MatchedPersonaDetail(
                        matchedUserId = member.userId,
                        similarity = topMatch?.similarity ?: 0.0,
                        personaSummary = topMatch?.summary,
                        scores = topMatch?.scores ?: member.scores,
                    )
                },
                topPositiveSignals = positiveSignals,
                objectionReasons = objections,
                persuasionPoints = persuasions,
            )
        }
    }

    private fun buildPositiveSignals(option: ScheduleOptionForValidation, acceptanceScore: Int): List<String> {
        val commonSlotCount = option.slots.count { it.slotType == SlotType.COMMON }
        val leadingAxes = option.slots
            .flatMap { slot -> slot.scores.leadingAxisLabels() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .take(2)

        val preferenceSummary = when (leadingAxes.size) {
            0 -> "무리 없는 일정 밀도와 균형 잡힌 장소 구성이 좋게 평가됩니다."
            1 -> "${leadingAxes[0]} 성향의 여행자에게 일정 구성이 잘 맞습니다."
            else -> "${leadingAxes.joinToString("·")} 성향의 여행자에게 일정 구성이 잘 맞습니다."
        }

        return listOf(
            preferenceSummary,
            "${commonSlotCount}개 공통 코스로 동행자와 함께 움직이기 쉽습니다.",
            "참고군 기준 수용도가 ${acceptanceScore}점으로 안정적입니다.",
        )
    }

    private fun buildObjections(option: ScheduleOptionForValidation, members: List<MemberSnapshot>): List<String> {
        val lowSatisfaction = option.satisfactionByUser.filter { it.score < 60 }
        return lowSatisfaction.map {
            val member = members.find { m -> m.userId == it.userId }
            "${member?.let { m -> "User ${m.userId}" } ?: "특정 멤버"}의 만족도가 ${it.score}점으로 다소 낮을 수 있습니다."
        }
    }

    private fun buildPersuasionPoints(option: ScheduleOptionForValidation): List<String> {
        return option.slots
            .filter { it.slotType == SlotType.PERSONAL }
            .mapNotNull { slot -> slot.reasonText.toPersonaFriendlyReason() }
            .distinct()
    }

    private fun AxisScores.leadingAxisLabels(): List<String> {
        return listOf(
            mobility to "이동을 즐기는",
            photo to "사진 기록을 중시하는",
            budget to "예산 효율을 보는",
            theme to "테마 경험을 선호하는",
        )
            .filter { (score, _) -> score >= 60 }
            .sortedByDescending { (score, _) -> score }
            .map { (_, label) -> label }
            .take(2)
    }

    private fun String.toPersonaFriendlyReason(): String? {
        val cleaned = trim().trimEnd('.', '!', '?', '。').takeIf { it.isNotBlank() } ?: return null
        return when {
            cleaned.contains("사진") || cleaned.contains("기록") -> "사진을 남기기 좋은 장소가 포함되어 기록형 여행자에게 매력적입니다."
            cleaned.contains("예산") -> "부담 없는 비용 흐름이라 실속형 여행자도 수용하기 좋습니다."
            cleaned.contains("테마") -> "여행 테마가 분명해 취향이 비슷한 참고군에게 설득력이 있습니다."
            cleaned.contains("이동") || cleaned.contains("활동성") -> "이동 부담을 고려한 구성이라 활동형 여행자도 따라가기 쉽습니다."
            cleaned.contains("취향 반영") -> null
            else -> cleaned
        }
    }
}
