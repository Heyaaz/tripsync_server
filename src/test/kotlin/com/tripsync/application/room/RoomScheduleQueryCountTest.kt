package com.tripsync.application.room

import com.tripsync.application.schedule.ScheduleService
import com.tripsync.domain.entity.Place
import com.tripsync.domain.entity.RoomMember
import com.tripsync.domain.entity.RoomMemberProfile
import com.tripsync.domain.entity.Schedule
import com.tripsync.domain.entity.ScheduleSlot
import com.tripsync.domain.entity.SatisfactionScore
import com.tripsync.domain.entity.TptiResult
import com.tripsync.domain.entity.TripRoom
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.AuthProvider
import com.tripsync.domain.enums.ReasonAxis
import com.tripsync.domain.enums.RoomMemberRole
import com.tripsync.domain.enums.ScheduleOptionType
import com.tripsync.domain.enums.SlotType
import com.tripsync.domain.enums.TripRoomStatus
import com.tripsync.domain.repository.PlaceRepository
import com.tripsync.domain.repository.RoomMemberProfileRepository
import com.tripsync.domain.repository.RoomMemberRepository
import com.tripsync.domain.repository.ScheduleRepository
import com.tripsync.domain.repository.ScheduleSlotRepository
import com.tripsync.domain.repository.SatisfactionScoreRepository
import com.tripsync.domain.repository.TptiResultRepository
import com.tripsync.domain.repository.TripRoomRepository
import com.tripsync.domain.repository.UserRepository
import com.tripsync.testsupport.HibernateQueryCounter
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RoomScheduleQueryCountTest(
    @Autowired private val roomService: RoomService,
    @Autowired private val scheduleService: ScheduleService,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val tripRoomRepository: TripRoomRepository,
    @Autowired private val roomMemberRepository: RoomMemberRepository,
    @Autowired private val roomMemberProfileRepository: RoomMemberProfileRepository,
    @Autowired private val tptiResultRepository: TptiResultRepository,
    @Autowired private val scheduleRepository: ScheduleRepository,
    @Autowired private val scheduleSlotRepository: ScheduleSlotRepository,
    @Autowired private val satisfactionScoreRepository: SatisfactionScoreRepository,
    @Autowired private val placeRepository: PlaceRepository,
    @Autowired private val entityManager: EntityManager,
    @Autowired entityManagerFactory: EntityManagerFactory,
) {
    private val queryCounter = HibernateQueryCounter(entityManagerFactory)

    @Test
    fun `rooms my uses batched member counts and schedule summaries`() {
        val fixture = createFixture(roomCount = 3, slotCount = 1, scoreCount = 2)
        flushAndClear()

        val measured = queryCounter.count {
            roomService.getMyRooms(fixture.host).data!!
        }
        val rooms = measured.result["rooms"] as List<*>

        assertEquals(3, rooms.size)
        assertTrue(
            measured.prepareStatementCount <= 5,
            "expected /rooms/my to stay within 5 statements, got ${measured.prepareStatementCount}",
        )
    }

    @Test
    fun `schedule detail uses bounded explicit detail reads`() {
        val fixture = createFixture(roomCount = 1, slotCount = 6, scoreCount = 3)
        val schedule = fixture.schedules.first()
        flushAndClear()

        val measured = queryCounter.count {
            scheduleService.getSchedule(schedule.id, fixture.host.id).data!!
        }
        val slots = measured.result["slots"] as List<*>
        val scores = measured.result["satisfactionByUser"] as List<*>

        assertEquals(6, slots.size)
        assertEquals(3, scores.size)
        assertTrue(
            measured.prepareStatementCount <= 7,
            "expected schedule detail to stay within 7 statements, got ${measured.prepareStatementCount}",
        )
    }

    private fun createFixture(roomCount: Int, slotCount: Int, scoreCount: Int): QueryFixture {
        val suffix = System.nanoTime()
        val host = user("host-$suffix", "host-$suffix@example.com")
        val members = (1..scoreCount).map { user("member-$it-$suffix", "member-$it-$suffix@example.com") }
        val place = placeRepository.save(place("place-$suffix"))
        val schedules = mutableListOf<Schedule>()

        repeat(roomCount) { roomIndex ->
            val room = tripRoomRepository.save(
                TripRoom(
                    hostUser = host,
                    shareCode = "Q${suffix.toString().takeLast(8)}${roomIndex + 1}",
                    destination = "충남",
                    roomName = "충남 query $roomIndex",
                    tripDate = LocalDate.now().plusDays(10 + roomIndex.toLong()),
                    status = TripRoomStatus.COMPLETED,
                )
            )
            roomMemberRepository.save(RoomMember(room = room, user = host, role = RoomMemberRole.HOST))
            (members.take(scoreCount - 1)).forEach {
                roomMemberRepository.save(RoomMember(room = room, user = it, role = RoomMemberRole.MEMBER))
            }
            (listOf(host) + members.take(scoreCount - 1)).forEachIndexed { index, user ->
                val result = tptiResultRepository.save(tpti(user, index))
                roomMemberProfileRepository.save(
                    RoomMemberProfile(
                        room = room,
                        user = user,
                        tptiResult = result,
                        mobilityScore = result.mobilityScore,
                        photoScore = result.photoScore,
                        budgetScore = result.budgetScore,
                        themeScore = result.themeScore,
                        characterName = result.characterName,
                    )
                )
            }

            val schedule = scheduleRepository.save(
                Schedule(
                    room = room,
                    version = 1,
                    optionType = ScheduleOptionType.BALANCED,
                    isConfirmed = true,
                    generationInput = mapOf("destination" to "충남", "startTime" to "09:00", "endTime" to "21:00"),
                    summary = "query-count schedule",
                    groupSatisfaction = 80,
                )
            )
            schedules += schedule
            repeat(slotCount) { slotIndex ->
                scheduleSlotRepository.save(
                    ScheduleSlot(
                        schedule = schedule,
                        startTime = Instant.parse("2026-06-01T0${slotIndex % 9}:00:00Z"),
                        endTime = Instant.parse("2026-06-01T0${slotIndex % 9}:30:00Z"),
                        place = place,
                        slotType = SlotType.COMMON,
                        reasonAxis = ReasonAxis.COMMON,
                        reasonText = "slot-$slotIndex",
                        orderIndex = slotIndex + 1,
                    )
                )
            }
            (listOf(host) + members.take(scoreCount - 1)).forEach { user ->
                satisfactionScoreRepository.save(
                    SatisfactionScore(
                        schedule = schedule,
                        user = user,
                        score = 80,
                        breakdown = mapOf("overall" to 80),
                    )
                )
            }
        }
        return QueryFixture(host = host, schedules = schedules)
    }

    private fun user(nickname: String, email: String): User {
        return userRepository.save(
            User(
                nickname = nickname,
                email = email,
                authProvider = AuthProvider.LOCAL,
                passwordHash = "password",
            )
        )
    }

    private fun tpti(user: User, offset: Int): TptiResult {
        return TptiResult(
            user = user,
            mobilityScore = 40 + offset,
            photoScore = 50 + offset,
            budgetScore = 60 + offset,
            themeScore = 70 + offset,
            characterName = "캐릭터-$offset",
            sourceAnswers = listOf(1, 2, 3, 4, 5, 1, 2, 3),
        )
    }

    private fun place(tourApiId: String): Place {
        return Place(
            tourApiId = tourApiId,
            name = "테스트 장소",
            address = "충청남도 테스트군",
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

    private fun flushAndClear() {
        entityManager.flush()
        entityManager.clear()
    }

    private data class QueryFixture(
        val host: User,
        val schedules: List<Schedule>,
    )
}
