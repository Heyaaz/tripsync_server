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
}
