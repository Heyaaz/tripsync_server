package com.tripsync.application.persona

import com.tripsync.application.consensus.ConsensusService
import com.tripsync.application.consensus.ScheduleOptionDraft
import com.tripsync.domain.entity.AxisScores
import com.tripsync.domain.entity.User
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

    fun validateOptions(
        options: List<ScheduleOptionDraft>,
        members: List<MemberSnapshot>,
    ): Map<com.tripsync.domain.enums.ScheduleOptionType, ValidationResult> {
        val start = System.currentTimeMillis()

        val allMatchedPersonas = members.flatMap { member ->
            personaVectorService.matchForMember(member.scores, topK = 3)
        }.distinctBy { it.uuid }

        logger.info { "Matched ${allMatchedPersonas.size} unique personas for ${members.size} members in ${System.currentTimeMillis() - start}ms" }

        return options.associate { option ->
            val slotScores = option.slots.map { slot ->
                val slotVector = AxisScores(
                    mobility = slot.placeId.toInt() % 100,
                    photo = slot.placeId.toInt() % 100,
                    budget = slot.placeId.toInt() % 100,
                    theme = slot.placeId.toInt() % 100,
                )
                val avgMatch = allMatchedPersonas.map { persona ->
                    1.0 - (slotVector.l1Distance(persona.scores) / 400.0)
                }.average()
                avgMatch
            }

            val acceptanceScore = (slotScores.average() * 100).toInt().coerceIn(0, 100)

            val positiveSignals = buildPositiveSignals(option, members)
            val objections = buildObjections(option, members)
            val persuasions = buildPersuasionPoints(option, members)

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

    private fun buildPositiveSignals(option: ScheduleOptionDraft, members: List<MemberSnapshot>): List<String> {
        return listOf(
            "${members.size}명의 취향이 ${option.slots.count { it.slotType == com.tripsync.domain.enums.SlotType.COMMON }}개 공통 슬롯에서 조화를 이룹니다.",
            "그룹 만족도 ${option.groupSatisfaction}점으로 안정적인 선택입니다.",
        )
    }

    private fun buildObjections(option: ScheduleOptionDraft, members: List<MemberSnapshot>): List<String> {
        val lowSatisfaction = option.satisfactionByUser.filter { it.score < 60 }
        return lowSatisfaction.map {
            val member = members.find { m -> m.userId == it.userId }
            "${member?.let { m -> "User ${m.userId}" } ?: "특정 멤버"}의 만족도가 ${it.score}점으로 다소 낮을 수 있습니다."
        }
    }

    private fun buildPersuasionPoints(option: ScheduleOptionDraft, members: List<MemberSnapshot>): List<String> {
        return option.slots.filter { it.slotType == com.tripsync.domain.enums.SlotType.PERSONAL }.map { slot ->
            "${slot.reasonText}로 개인 취향을 반영하여 갈등을 최소화했습니다."
        }.distinct()
    }
}
