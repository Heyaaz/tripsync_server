package com.tripsync.application.persona

import com.tripsync.domain.entity.AxisScores
import com.tripsync.domain.enums.ScheduleOptionType
import com.tripsync.domain.enums.SlotType
import com.tripsync.domain.repository.PersonaVectorRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.UUID

class PersonaValidationServiceTest {

    @Test
    fun `validation uses real slot axis scores instead of place id derived placeholders`() {
        val vectorService = personaVectorServiceWith(
            PersonaVectorService.PersonaVectorData(
                uuid = UUID.randomUUID(),
                scores = AxisScores(mobility = 90, photo = 80, budget = 70, theme = 60),
                summary = "활동적인 참고군",
            )
        )
        val service = PersonaValidationService(vectorService)

        val result = service.validateOptions(
            options = listOf(
                optionWithSlotScores(AxisScores(mobility = 90, photo = 80, budget = 70, theme = 60)),
            ),
            members = listOf(
                PersonaValidationService.MemberSnapshot(
                    userId = 1L,
                    scores = AxisScores(mobility = 90, photo = 80, budget = 70, theme = 60),
                )
            ),
        )[ScheduleOptionType.BALANCED]!!

        assertEquals(100, result.personaAcceptanceScore)
        assertEquals(1, result.matchedPersonaCount)
    }

    @Test
    fun `validation returns empty map when no persona matches are available`() {
        val service = PersonaValidationService(personaVectorServiceWith())

        val result = service.validateOptions(
            options = listOf(optionWithSlotScores(AxisScores(mobility = 10, photo = 20, budget = 30, theme = 40))),
            members = listOf(
                PersonaValidationService.MemberSnapshot(
                    userId = 1L,
                    scores = AxisScores(mobility = 10, photo = 20, budget = 30, theme = 40),
                )
            ),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `validation explains why similar personas would like the option`() {
        val service = PersonaValidationService(
            personaVectorServiceWith(
                PersonaVectorService.PersonaVectorData(
                    uuid = UUID.randomUUID(),
                    scores = AxisScores(mobility = 80, photo = 75, budget = 55, theme = 45),
                    summary = "사진과 이동을 즐기는 참고군",
                )
            )
        )

        val result = service.validateOptions(
            options = listOf(
                PersonaValidationService.ScheduleOptionForValidation(
                    optionType = ScheduleOptionType.INDIVIDUAL,
                    groupSatisfaction = 85,
                    slots = listOf(
                        PersonaValidationService.ScheduleSlotForValidation(
                            slotType = SlotType.COMMON,
                            reasonText = "공통 취향 반영",
                            scores = AxisScores(mobility = 80, photo = 75, budget = 40, theme = 50),
                        ),
                        PersonaValidationService.ScheduleSlotForValidation(
                            slotType = SlotType.PERSONAL,
                            reasonText = "사진 취향 반영",
                            scores = AxisScores(mobility = 65, photo = 90, budget = 50, theme = 55),
                        ),
                    ),
                    satisfactionByUser = listOf(
                        PersonaValidationService.SatisfactionForValidation(userId = 1L, score = 85),
                    ),
                )
            ),
            members = listOf(
                PersonaValidationService.MemberSnapshot(
                    userId = 1L,
                    scores = AxisScores(mobility = 82, photo = 76, budget = 50, theme = 45),
                )
            ),
        )[ScheduleOptionType.INDIVIDUAL]!!

        assertTrue(result.topPositiveSignals.any { it.contains("사진 기록") || it.contains("이동") })
        assertTrue(result.topPositiveSignals.any { it.contains("${result.personaAcceptanceScore}점") })
        assertTrue(result.topPositiveSignals.none { it.contains("취향 반영") })
        assertEquals("사진을 남기기 좋은 장소가 포함되어 기록형 여행자에게 매력적입니다.", result.persuasionPoints.single())
    }

    private fun optionWithSlotScores(scores: AxisScores): PersonaValidationService.ScheduleOptionForValidation {
        return PersonaValidationService.ScheduleOptionForValidation(
            optionType = ScheduleOptionType.BALANCED,
            groupSatisfaction = 80,
            slots = listOf(
                PersonaValidationService.ScheduleSlotForValidation(
                    slotType = SlotType.COMMON,
                    reasonText = "그룹 전원의 평균 취향 반영",
                    scores = scores,
                )
            ),
            satisfactionByUser = listOf(
                PersonaValidationService.SatisfactionForValidation(userId = 1L, score = 80),
            ),
        )
    }

    private fun personaVectorServiceWith(
        vararg vectors: PersonaVectorService.PersonaVectorData,
    ): PersonaVectorService {
        val repository = Mockito.mock(PersonaVectorRepository::class.java)
        val service = PersonaVectorService(repository)
        val field = PersonaVectorService::class.java.getDeclaredField("vectorPool")
        field.isAccessible = true
        field.set(service, vectors.toList())
        return service
    }

    @Test
    fun `personal reason text is converted to persona benefit copy without exposing member names`() {
        val service = PersonaValidationService(
            personaVectorServiceWith(
                PersonaVectorService.PersonaVectorData(
                    uuid = UUID.randomUUID(),
                    scores = AxisScores(mobility = 60, photo = 70, budget = 50, theme = 65),
                    summary = "기록형 참고군",
                )
            )
        )

        val result = service.validateOptions(
            options = listOf(
                PersonaValidationService.ScheduleOptionForValidation(
                    optionType = ScheduleOptionType.BALANCED,
                    groupSatisfaction = 79,
                    slots = listOf(
                        PersonaValidationService.ScheduleSlotForValidation(
                            slotType = SlotType.COMMON,
                            reasonText = "그룹 전원의 평균 취향 반영",
                            scores = AxisScores(mobility = 65, photo = 70, budget = 50, theme = 60),
                        ),
                        PersonaValidationService.ScheduleSlotForValidation(
                            slotType = SlotType.PERSONAL,
                            reasonText = "hee의 기록 취향 반영",
                            scores = AxisScores(mobility = 55, photo = 75, budget = 50, theme = 65),
                        ),
                    ),
                    satisfactionByUser = listOf(
                        PersonaValidationService.SatisfactionForValidation(userId = 1, score = 79),
                    ),
                ),
            ),
            members = listOf(
                PersonaValidationService.MemberSnapshot(
                    userId = 1,
                    scores = AxisScores(mobility = 60, photo = 70, budget = 50, theme = 65),
                ),
            ),
        )[ScheduleOptionType.BALANCED]!!

        assertTrue(result.topPositiveSignals.any { it.contains(result.personaAcceptanceScore.toString()) })
        assertTrue(result.topPositiveSignals.none { it.contains("그룹 만족도") })
        assertEquals(listOf("사진을 남기기 좋은 장소가 포함되어 기록형 여행자에게 매력적입니다."), result.persuasionPoints)
        assertTrue(result.persuasionPoints.none { it.contains("hee") || it.contains("취향 반영") })
    }
}
