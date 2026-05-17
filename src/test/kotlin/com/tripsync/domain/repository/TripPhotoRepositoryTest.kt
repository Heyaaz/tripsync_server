package com.tripsync.domain.repository

import com.tripsync.domain.entity.Place
import com.tripsync.domain.entity.RoomMember
import com.tripsync.domain.entity.Schedule
import com.tripsync.domain.entity.ScheduleSlot
import com.tripsync.domain.entity.TripPhoto
import com.tripsync.domain.entity.TripRoom
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.AuthProvider
import com.tripsync.domain.enums.PhotoStatus
import com.tripsync.domain.enums.ReasonAxis
import com.tripsync.domain.enums.RoomMemberRole
import com.tripsync.domain.enums.ScheduleOptionType
import com.tripsync.domain.enums.SlotType
import com.tripsync.domain.enums.TripRoomStatus
import com.tripsync.domain.enums.YnFlag
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
class TripPhotoRepositoryTest(
    @Autowired private val tripPhotoRepository: TripPhotoRepository,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val tripRoomRepository: TripRoomRepository,
    @Autowired private val roomMemberRepository: RoomMemberRepository,
    @Autowired private val scheduleRepository: ScheduleRepository,
    @Autowired private val scheduleSlotRepository: ScheduleSlotRepository,
    @Autowired private val placeRepository: PlaceRepository,
    @Autowired private val entityManager: EntityManager,
) {
    @Test
    fun `active photo query returns only visible not deleted photos in slot order`() {
        val fixture = createFixture()
        val activeLaterSlot = tripPhotoRepository.save(
            photo(fixture, fixture.secondSlot, "second.webp", "image/webp", byteArrayOf(2), status = PhotoStatus.ACTIVE)
        )
        val activeFirstSlot = tripPhotoRepository.save(
            photo(fixture, fixture.firstSlot, "first.jpg", "image/jpeg", byteArrayOf(1), status = PhotoStatus.ACTIVE)
        )
        tripPhotoRepository.save(
            photo(fixture, fixture.firstSlot, "hidden.png", "image/png", byteArrayOf(3), status = PhotoStatus.HIDDEN)
        )
        val softDeleted = tripPhotoRepository.save(
            photo(fixture, fixture.secondSlot, "deleted.jpg", "image/jpeg", byteArrayOf(4), status = PhotoStatus.ACTIVE)
        )
        softDeleted.delYn = YnFlag.Y
        tripPhotoRepository.saveAndFlush(softDeleted)

        val visible = tripPhotoRepository.findAllByScheduleIdAndDelYnAndStatusOrderByScheduleSlotOrderIndexAscCreatedAtAsc(
            fixture.schedule.id,
            YnFlag.N,
            PhotoStatus.ACTIVE,
        )

        assertEquals(listOf(activeFirstSlot.id, activeLaterSlot.id), visible.map { it.id })
        assertEquals(listOf("first.jpg", "second.webp"), visible.map { it.originalFilename })
    }

    @Test
    fun `photo content is persisted in the database with MIME and size metadata`() {
        val fixture = createFixture()
        val content = byteArrayOf(0x49, 0x4d, 0x47, 0x01, 0x02)
        val saved = tripPhotoRepository.saveAndFlush(
            photo(
                fixture = fixture,
                slot = fixture.firstSlot,
                filename = "memory.png",
                contentType = "image/png",
                content = content,
            )
        )
        entityManager.clear()

        val reloaded = tripPhotoRepository.findByIdAndDelYn(saved.id, YnFlag.N)

        assertNotNull(reloaded)
        assertEquals("image/png", reloaded!!.contentType)
        assertEquals(content.size.toLong(), reloaded.fileSize)
        assertArrayEquals(content, reloaded.content)
    }


    @Test
    fun `album row query returns metadata without requiring photo content in response shape`() {
        val fixture = createFixture()
        val content = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50)
        val saved = tripPhotoRepository.saveAndFlush(
            photo(fixture, fixture.firstSlot, "album.webp", "image/webp", content)
        )
        entityManager.clear()

        val rows = tripPhotoRepository.findAlbumRowsByScheduleIdAndDelYnAndStatus(
            fixture.schedule.id,
            YnFlag.N,
            PhotoStatus.ACTIVE,
        )

        assertEquals(1, rows.size)
        assertEquals(saved.id, rows.single().id)
        assertEquals(fixture.firstSlot.id, rows.single().scheduleSlotId)
        assertEquals(fixture.host.id, rows.single().uploaderUserId)
        assertEquals("photo-host-", rows.single().uploaderNickname.take("photo-host-".length))
        assertEquals("album.webp", rows.single().originalFilename)
        assertEquals(content.size.toLong(), rows.single().fileSize)
    }

    @Test
    fun `active quota counters ignore hidden and soft deleted photos`() {
        val fixture = createFixture()
        tripPhotoRepository.save(photo(fixture, fixture.firstSlot, "active.jpg", "image/jpeg", byteArrayOf(1), status = PhotoStatus.ACTIVE))
        tripPhotoRepository.save(photo(fixture, fixture.firstSlot, "hidden.jpg", "image/jpeg", byteArrayOf(2), status = PhotoStatus.HIDDEN))
        val deleted = tripPhotoRepository.save(photo(fixture, fixture.firstSlot, "deleted.jpg", "image/jpeg", byteArrayOf(3), status = PhotoStatus.ACTIVE))
        deleted.delYn = YnFlag.Y
        tripPhotoRepository.saveAndFlush(deleted)

        assertEquals(1, tripPhotoRepository.countByScheduleIdAndDelYnAndStatus(fixture.schedule.id, YnFlag.N, PhotoStatus.ACTIVE))
        assertEquals(1, tripPhotoRepository.countByScheduleSlotIdAndDelYnAndStatus(fixture.firstSlot.id, YnFlag.N, PhotoStatus.ACTIVE))
        assertEquals(1, tripPhotoRepository.countByScheduleIdAndUploaderIdAndDelYnAndStatus(fixture.schedule.id, fixture.host.id, YnFlag.N, PhotoStatus.ACTIVE))
    }

    @Test
    fun `deleted photos are not returned by active id lookup`() {
        val fixture = createFixture()
        val saved = tripPhotoRepository.saveAndFlush(
            photo(fixture, fixture.firstSlot, "soft-deleted.jpg", "image/jpeg", byteArrayOf(7))
        )
        saved.delYn = YnFlag.Y
        tripPhotoRepository.saveAndFlush(saved)
        entityManager.clear()

        assertNull(tripPhotoRepository.findByIdAndDelYn(saved.id, YnFlag.N))
    }

    private fun createFixture(): Fixture {
        val suffix = System.nanoTime()
        val host = userRepository.save(
            User(
                nickname = "photo-host-$suffix",
                email = "photo-host-$suffix@example.com",
                authProvider = AuthProvider.LOCAL,
                passwordHash = "password",
            )
        )
        val room = tripRoomRepository.save(
            TripRoom(
                hostUser = host,
                shareCode = "P${suffix.toString().takeLast(10)}",
                destination = "충남",
                tripDate = LocalDate.now().minusDays(1),
                status = TripRoomStatus.COMPLETED,
            )
        )
        roomMemberRepository.save(RoomMember(room = room, user = host, role = RoomMemberRole.HOST))
        val firstPlace = placeRepository.save(place("photo-first-$suffix", "첫 장소"))
        val secondPlace = placeRepository.save(place("photo-second-$suffix", "둘째 장소"))
        val schedule = scheduleRepository.save(
            Schedule(
                room = room,
                version = 1,
                optionType = ScheduleOptionType.BALANCED,
                isConfirmed = true,
                generationInput = mapOf("destination" to "충남"),
                summary = "확정 일정",
                groupSatisfaction = 90,
            )
        )
        val firstSlot = scheduleSlotRepository.save(
            slot(schedule, firstPlace, orderIndex = 1, start = "2026-06-01T00:00:00Z", end = "2026-06-01T01:00:00Z")
        )
        val secondSlot = scheduleSlotRepository.save(
            slot(schedule, secondPlace, orderIndex = 2, start = "2026-06-01T01:00:00Z", end = "2026-06-01T02:00:00Z")
        )
        return Fixture(host, room, schedule, firstSlot, secondSlot)
    }

    private fun photo(
        fixture: Fixture,
        slot: ScheduleSlot,
        filename: String,
        contentType: String,
        content: ByteArray,
        status: PhotoStatus = PhotoStatus.ACTIVE,
    ): TripPhoto {
        return TripPhoto(
            room = fixture.room,
            schedule = fixture.schedule,
            scheduleSlot = slot,
            place = slot.place,
            uploader = fixture.host,
            originalFilename = filename,
            contentType = contentType,
            fileSize = content.size.toLong(),
            content = content,
            caption = "사진 설명",
            status = status,
        )
    }

    private fun slot(schedule: Schedule, place: Place, orderIndex: Int, start: String, end: String): ScheduleSlot {
        return ScheduleSlot(
            schedule = schedule,
            startTime = Instant.parse(start),
            endTime = Instant.parse(end),
            place = place,
            slotType = SlotType.COMMON,
            reasonAxis = ReasonAxis.COMMON,
            reasonText = "장소 $orderIndex",
            orderIndex = orderIndex,
        )
    }

    private fun place(tourApiId: String, name: String): Place {
        return Place(
            tourApiId = tourApiId,
            name = name,
            address = "충청남도 보령시",
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
        val room: TripRoom,
        val schedule: Schedule,
        val firstSlot: ScheduleSlot,
        val secondSlot: ScheduleSlot,
    )
}
