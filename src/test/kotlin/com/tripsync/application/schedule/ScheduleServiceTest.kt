package com.tripsync.application.schedule

import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.Place
import com.tripsync.domain.entity.RoomMember
import com.tripsync.domain.entity.Schedule
import com.tripsync.domain.entity.ScheduleSlot
import com.tripsync.domain.entity.TripRoom
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.AuthProvider
import com.tripsync.domain.enums.ReasonAxis
import com.tripsync.domain.enums.RoomMemberRole
import com.tripsync.domain.enums.ScheduleOptionType
import com.tripsync.domain.enums.SlotType
import com.tripsync.domain.enums.TripRoomStatus
import com.tripsync.domain.repository.PlaceRepository
import com.tripsync.domain.repository.RoomMemberRepository
import com.tripsync.domain.repository.ScheduleRepository
import com.tripsync.domain.repository.ScheduleSlotRepository
import com.tripsync.domain.repository.TripRoomRepository
import com.tripsync.domain.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@SpringBootTest
@ActiveProfiles("test")
class ScheduleServiceTest(
    @Autowired private val scheduleService: ScheduleService,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val tripRoomRepository: TripRoomRepository,
    @Autowired private val roomMemberRepository: RoomMemberRepository,
    @Autowired private val scheduleRepository: ScheduleRepository,
    @Autowired private val scheduleSlotRepository: ScheduleSlotRepository,
    @Autowired private val placeRepository: PlaceRepository,
) {
    @Test
    fun `host can search places and append a selected place to confirmed schedule`() {
        val fixture = createFixture()

        val searchBefore = scheduleService.searchPlacesForSchedule(fixture.schedule.id, fixture.host.id, "꽃지").data!!
        val placesBefore = searchBefore["places"] as List<*>
        val candidateBefore = placesBefore
            .map { it as Map<*, *> }
            .first { it["id"] == fixture.newPlace.id }
        assertEquals(false, candidateBefore["alreadyAdded"])

        val added = scheduleService.addScheduleSlot(fixture.schedule.id, fixture.host.id, fixture.newPlace.id).data!!
        val addedSlots = added["slots"] as List<*>
        val appended = addedSlots.last() as Map<*, *>
        assertEquals(2, appended["orderIndex"])
        assertEquals(fixture.newPlace.name, (appended["place"] as Map<*, *>)["name"])
        assertEquals("직접 추가한 장소입니다.", appended["reason"])
        assertNotNull(appended["slotId"])

        val searchAfter = scheduleService.searchPlacesForSchedule(fixture.schedule.id, fixture.host.id, "꽃지").data!!
        val candidateAfter = (searchAfter["places"] as List<*>)
            .map { it as Map<*, *> }
            .first { it["id"] == fixture.newPlace.id }
        assertEquals(true, candidateAfter["alreadyAdded"])
    }

    @Test
    fun `adding the same place twice is rejected`() {
        val fixture = createFixture()

        scheduleService.addScheduleSlot(fixture.schedule.id, fixture.host.id, fixture.newPlace.id)

        val error = assertThrows(DomainException::class.java) {
            scheduleService.addScheduleSlot(fixture.schedule.id, fixture.host.id, fixture.newPlace.id)
        }
        assertEquals("INVALID_REQUEST", error.code)
    }

    private fun createFixture(): Fixture {
        val suffix = System.nanoTime()
        val host = userRepository.save(
            User(
                nickname = "host-$suffix",
                email = "host-$suffix@example.com",
                authProvider = AuthProvider.LOCAL,
                passwordHash = "password",
            )
        )
        val room = tripRoomRepository.save(
            TripRoom(
                hostUser = host,
                shareCode = "S${suffix.toString().takeLast(10)}",
                destination = "충남",
                tripDate = LocalDate.now().plusDays(7),
                status = TripRoomStatus.COMPLETED,
            )
        )
        roomMemberRepository.save(RoomMember(room = room, user = host, role = RoomMemberRole.HOST))

        val firstPlace = placeRepository.save(place("existing-$suffix", "고향굴수산", "충청남도 보령시 전북면 홍보로 1061-103"))
        val newPlace = placeRepository.save(place("new-$suffix", "태안 안면도 꽃지해수욕장", "충청남도 태안군 안면읍 승언리"))
        val schedule = scheduleRepository.save(
            Schedule(
                room = room,
                version = 1,
                optionType = ScheduleOptionType.BALANCED,
                isConfirmed = true,
                generationInput = mapOf("destination" to "충남"),
                summary = "확정 일정",
                groupSatisfaction = 80,
            )
        )
        scheduleSlotRepository.save(
            ScheduleSlot(
                schedule = schedule,
                startTime = Instant.parse("2026-06-01T00:00:00Z"),
                endTime = Instant.parse("2026-06-01T02:00:00Z"),
                place = firstPlace,
                slotType = SlotType.COMMON,
                reasonAxis = ReasonAxis.COMMON,
                reasonText = "기존 장소",
                orderIndex = 1,
            )
        )

        return Fixture(host, schedule, newPlace)
    }

    private fun place(tourApiId: String, name: String, address: String): Place {
        return Place(
            tourApiId = tourApiId,
            name = name,
            address = address,
            latitude = BigDecimal("36.5000000"),
            longitude = BigDecimal("126.5000000"),
            category = "관광지",
            mobilityScore = 50,
            photoScore = 80,
            budgetScore = 60,
            themeScore = 40,
            metadataTags = mapOf("populationDeclineArea" to true),
        )
    }

    private data class Fixture(
        val host: User,
        val schedule: Schedule,
        val newPlace: Place,
    )
}
