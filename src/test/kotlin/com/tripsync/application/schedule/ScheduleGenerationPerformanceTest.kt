package com.tripsync.application.schedule

import com.tripsync.application.consensus.ScheduleOptionDraft
import com.tripsync.application.consensus.ScheduleSlotDraft
import com.tripsync.application.consensus.SatisfactionDraft
import com.tripsync.domain.entity.Place
import com.tripsync.domain.entity.Schedule
import com.tripsync.domain.entity.TripRoom
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.AuthProvider
import com.tripsync.domain.enums.ReasonAxis
import com.tripsync.domain.enums.ScheduleOptionType
import com.tripsync.domain.enums.ScoreAxis
import com.tripsync.domain.enums.SlotType
import com.tripsync.domain.enums.TripRoomStatus
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.ExternalPopularityMetricRepository
import com.tripsync.domain.repository.PlaceQueryRepository
import com.tripsync.domain.repository.PlaceRepository
import com.tripsync.domain.repository.RoomMemberProfileRepository
import com.tripsync.domain.repository.SatisfactionScoreRepository
import com.tripsync.domain.repository.ScheduleRepository
import com.tripsync.domain.repository.ScheduleSlotRepository
import com.tripsync.domain.repository.TripRoomRepository
import com.tripsync.domain.repository.UserRepository
import com.tripsync.web.dto.GenerateScheduleDto
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class ScheduleGenerationPerformanceTest {
    private val scheduleRepository = mock(ScheduleRepository::class.java)
    private val scheduleSlotRepository = mock(ScheduleSlotRepository::class.java)
    private val satisfactionScoreRepository = mock(SatisfactionScoreRepository::class.java)
    private val roomMemberProfileRepository = mock(RoomMemberProfileRepository::class.java)
    private val placeRepository = mock(PlaceRepository::class.java)
    private val placeQueryRepository = mock(PlaceQueryRepository::class.java)
    private val externalPopularityMetricRepository = mock(ExternalPopularityMetricRepository::class.java)
    private val userRepository = mock(UserRepository::class.java)
    private val tripRoomRepository = mock(TripRoomRepository::class.java)
    private val accessPolicy = mock(ScheduleAccessPolicy::class.java)

    private val service = ScheduleGenerationPersistenceService(
        scheduleRepository = scheduleRepository,
        scheduleSlotRepository = scheduleSlotRepository,
        satisfactionScoreRepository = satisfactionScoreRepository,
        roomMemberProfileRepository = roomMemberProfileRepository,
        placeRepository = placeRepository,
        placeQueryRepository = placeQueryRepository,
        externalPopularityMetricRepository = externalPopularityMetricRepository,
        userRepository = userRepository,
        tripRoomRepository = tripRoomRepository,
        accessPolicy = accessPolicy,
    )

    @Test
    fun `generated schedule persistence batches place and user lookup and child saves`() {
        val host = user(101)
        val companion = user(102)
        val room = TripRoom(
            id = 10,
            hostUser = host,
            shareCode = "ROOM00000010",
            destination = "충남",
            roomName = "충남 여행",
            tripDate = LocalDate.parse("2026-06-01"),
            status = TripRoomStatus.WAITING,
        )
        val places = (1L..4L).map { id -> place(id) }
        val options = listOf(
            generatedOption(ScheduleOptionType.BALANCED, listOf(1L, 2L), listOf(host.id, companion.id)),
            generatedOption(ScheduleOptionType.INDIVIDUAL, listOf(2L, 3L), listOf(host.id, companion.id)),
            generatedOption(ScheduleOptionType.DISCOVERY, listOf(3L, 4L), listOf(host.id, companion.id)),
        )

        `when`(tripRoomRepository.findActiveByIdForUpdate(room.id, YnFlag.N)).thenReturn(room)
        `when`(scheduleRepository.findTopByRoomIdAndDelYnOrderByVersionDescIdDesc(room.id, YnFlag.N)).thenReturn(null)
        `when`(placeQueryRepository.findScheduleCandidates("충남")).thenReturn(places)
        `when`(placeRepository.findAllById(listOf(1L, 2L, 3L, 4L))).thenReturn(places)
        `when`(userRepository.findAllById(listOf(host.id, companion.id))).thenReturn(listOf(host, companion))
        `when`(scheduleRepository.save(any(Schedule::class.java))).thenAnswer { invocation -> invocation.arguments[0] }

        service.saveGeneratedOptions(
            roomId = room.id,
            dto = GenerateScheduleDto(
                destination = "충남",
                tripDate = "2026-06-01",
                startTime = "09:00",
                endTime = "12:00",
            ),
            options = options,
            personaValidationByType = emptyMap(),
        )

        verify(placeRepository).findAllById(listOf(1L, 2L, 3L, 4L))
        verify(userRepository).findAllById(listOf(host.id, companion.id))
        verify(scheduleSlotRepository, times(3)).saveAll(anyIterable())
        verify(satisfactionScoreRepository, times(3)).saveAll(anyIterable())
        verify(placeRepository, never()).findById(anyLong())
        verify(userRepository, never()).findById(anyLong())
    }

    private fun generatedOption(
        optionType: ScheduleOptionType,
        placeIds: List<Long>,
        userIds: List<Long>,
    ): ScheduleOptionDraft {
        val start = Instant.parse("2026-06-01T00:00:00Z")
        return ScheduleOptionDraft(
            optionType = optionType,
            label = optionType.name.lowercase(),
            summary = "성능 테스트",
            groupSatisfaction = 80,
            slots = placeIds.mapIndexed { index, placeId ->
                ScheduleSlotDraft(
                    orderIndex = index + 1,
                    slotType = SlotType.COMMON,
                    targetUserId = userIds.getOrNull(index),
                    reasonAxis = ReasonAxis.COMMON,
                    reasonText = "공통 선호 장소",
                    startTime = start.plusSeconds(index * 3600L),
                    endTime = start.plusSeconds((index + 1) * 3600L),
                    placeId = placeId,
                    placeName = "place-$placeId",
                    placeAddress = "충청남도 공주시 $placeId",
                    isHiddenGem = false,
                )
            },
            satisfactionByUser = userIds.map { userId ->
                SatisfactionDraft(
                    userId = userId,
                    score = 80,
                    breakdown = SatisfactionDraft.Breakdown(
                        overall = 80,
                        byAxis = mapOf(ScoreAxis.MOBILITY to 80.0),
                    ),
                )
            },
            llmProvider = "deterministic-consensus",
            llmAttemptedProvider = "test",
            llmLatencyMs = null,
            fallbackUsed = true,
            llmFallbackReason = "test",
        )
    }

    private fun user(id: Long): User {
        return User(
            id = id,
            nickname = "user-$id",
            email = "user-$id@example.com",
            authProvider = AuthProvider.LOCAL,
            passwordHash = "password",
        )
    }

    private fun place(id: Long): Place {
        return Place(
            id = id,
            tourApiId = "place-$id",
            name = "place-$id",
            address = "충청남도 공주시 $id",
            latitude = BigDecimal("36.5000000"),
            longitude = BigDecimal("126.5000000"),
            category = "관광지",
            mobilityScore = 50,
            photoScore = 60,
            budgetScore = 70,
            themeScore = 80,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyIterable(): Iterable<T> {
        any(Iterable::class.java)
        return emptyList()
    }
}
