package com.tripsync.application.room

import com.tripsync.application.schedule.ScheduleResponseMapper
import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.RoomMember
import com.tripsync.domain.entity.RoomMemberProfile
import com.tripsync.domain.entity.TripRoom
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.RoomMemberRole
import com.tripsync.domain.enums.TripRoomStatus
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.*

@Service
class RoomService(
    private val tripRoomRepository: TripRoomRepository,
    private val roomMemberRepository: RoomMemberRepository,
    private val roomMemberProfileRepository: RoomMemberProfileRepository,
    private val tptiResultRepository: TptiResultRepository,
    private val scheduleRepository: ScheduleRepository,
    private val scheduleSlotRepository: ScheduleSlotRepository,
    private val satisfactionScoreRepository: SatisfactionScoreRepository,
    private val conflictMapRepository: ConflictMapRepository,
    private val tripPhotoRepository: TripPhotoRepository,
    private val scheduleResponseMapper: ScheduleResponseMapper,
) {

    @Transactional
    fun createRoom(
        host: User,
        destination: String,
        tripStartDate: LocalDate,
        tripEndDate: LocalDate,
        roomName: String? = null,
    ): ApiResponse<Map<String, Any?>> {
        if (host.isGuest) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "방장 권한이 필요합니다.")
        }
        if (!tripStartDate.isAfter(LocalDate.now())) {
            throw DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "tripStartDate는 오늘 이후여야 합니다.")
        }
        if (tripEndDate.isBefore(tripStartDate)) {
            throw DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "tripEndDate는 tripStartDate보다 빠를 수 없습니다.")
        }

        val normalizedRoomName = normalizeRoomName(roomName, destination)
        val room = tripRoomRepository.save(
            TripRoom(
                hostUser = host,
                shareCode = generateShareCode(),
                destination = destination,
                roomName = normalizedRoomName,
                tripDate = tripStartDate,
                tripStartDate = tripStartDate,
                tripEndDate = tripEndDate,
                status = TripRoomStatus.WAITING,
            )
        )

        roomMemberRepository.save(
            RoomMember(
                room = room,
                user = host,
                role = RoomMemberRole.HOST,
            )
        )
        tptiResultRepository.findTopByUserIdAndDelYnOrderByCreatedAtDesc(host.id, YnFlag.N)
            ?.let { upsertMemberProfile(room, host, it) }
        refreshRoomStatus(room.id)

        return ApiResponse.ok(
            mapOf(
                "roomId" to room.id,
                "roomName" to room.roomName,
                "shareCode" to room.shareCode,
                "tripDate" to room.tripDate.toString(),
                "tripStartDate" to room.tripStartDate.toString(),
                "tripEndDate" to room.tripEndDate.toString(),
                "status" to room.status.name.lowercase(),
            )
        )
    }

    @Transactional(readOnly = true)
    fun getRoom(roomId: Long, user: User): ApiResponse<Map<String, Any?>> {
        val room = getActiveRoom(roomId)
        validateRoomMember(room.id, user.id)
        val memberCount = roomMemberRepository.findAllByRoomIdAndDelYn(room.id, YnFlag.N).size

        return ApiResponse.ok(roomSummary(room, memberCount, includeScheduleState = true))
    }

    @Transactional(readOnly = true)
    fun getMyRooms(user: User): ApiResponse<Map<String, Any?>> {
        if (user.isGuest) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "로그인한 계정으로만 내 여행 계획을 확인할 수 있습니다.")
        }

        val rooms = roomMemberRepository.findAllByUserIdAndDelYn(user.id, YnFlag.N)
            .map { it.room }
            .filter { it.delYn == YnFlag.N }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<TripRoom> { it.createdAt }.thenByDescending { it.id })
            .map { room ->
                val memberCount = roomMemberRepository.findAllByRoomIdAndDelYn(room.id, YnFlag.N).size
                roomSummary(room, memberCount, includeScheduleState = false)
            }

        return ApiResponse.ok(mapOf("rooms" to rooms))
    }

    @Transactional(readOnly = true)
    fun getShareRoom(shareCode: String): ApiResponse<Map<String, Any?>> {
        val room = tripRoomRepository.findByShareCodeAndDelYn(shareCode, YnFlag.N)
            ?: throw DomainException(HttpStatus.NOT_FOUND, "INVALID_SHARE_CODE", "유효하지 않은 공유 코드입니다.")
        val memberCount = roomMemberRepository.findAllByRoomIdAndDelYn(room.id, YnFlag.N).size
        return ApiResponse.ok(
            roomSummary(room, memberCount, includeScheduleState = false) + mapOf("hostNickname" to room.hostUser.nickname)
        )
    }

    @Transactional
    fun joinRoom(shareCode: String, tptiResultId: Long?, user: User): ApiResponse<Map<String, Any?>> {
        if (user.isGuest) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "로그인한 계정으로만 방에 참여할 수 있습니다.")
        }

        val room = tripRoomRepository.findByShareCodeAndDelYn(shareCode, YnFlag.N)
            ?: throw DomainException(HttpStatus.NOT_FOUND, "INVALID_SHARE_CODE", "유효하지 않은 공유 코드입니다.")

        val existingMember = roomMemberRepository.findByRoomIdAndUserId(room.id, user.id)
        if (existingMember == null) {
            roomMemberRepository.save(
                RoomMember(
                    room = room,
                    user = user,
                    role = if (room.hostUser.id == user.id) RoomMemberRole.HOST else RoomMemberRole.MEMBER,
                )
            )
        } else {
            existingMember.delYn = YnFlag.N
            existingMember.role = if (room.hostUser.id == user.id) RoomMemberRole.HOST else existingMember.role
        }

        if (tptiResultId != null) {
            val result = tptiResultRepository.findById(tptiResultId).orElse(null)
            if (result == null || result.user.id != user.id || result.delYn != YnFlag.N) {
                throw DomainException(HttpStatus.NOT_FOUND, "TPTI_INCOMPLETE", "유효한 TPTI 결과를 찾을 수 없습니다.")
            }
            upsertMemberProfile(room, user, result)
        }

        val roomStatus = if (room.status == TripRoomStatus.COMPLETED) room.status else refreshRoomStatus(room.id)
        return ApiResponse.ok(
            mapOf(
                "roomId" to room.id,
                "userId" to user.id,
                "status" to "joined",
                "roomStatus" to roomStatus.name.lowercase(),
            )
        )
    }

    @Transactional(readOnly = true)
    fun getMembers(roomId: Long, user: User): ApiResponse<Map<String, Any?>> {
        val room = getActiveRoom(roomId)
        validateRoomMember(room.id, user.id)
        val members = roomMemberRepository.findAllByRoomIdAndDelYn(room.id, YnFlag.N)
        val profilesByUserId = roomMemberProfileRepository.findAllByRoomIdAndDelYn(room.id, YnFlag.N)
            .associateBy { it.user.id }

        return ApiResponse.ok(
            mapOf(
                "roomId" to room.id,
                "members" to members.sortedBy { it.joinedAt }.map { member ->
                    val profile = profilesByUserId[member.user.id]
                    mapOf(
                        "userId" to member.user.id,
                        "nickname" to member.user.nickname,
                        "role" to member.role.name.lowercase(),
                        "tptiCompleted" to (profile != null),
                        "scores" to profile?.let {
                            mapOf(
                                "mobility" to it.mobilityScore,
                                "photo" to it.photoScore,
                                "budget" to it.budgetScore,
                                "theme" to it.themeScore,
                            )
                        },
                        "characterName" to profile?.characterName,
                    )
                },
            )
        )
    }

    @Transactional
    fun deleteRoom(roomId: Long, user: User): ApiResponse<Map<String, Any?>> {
        if (user.isGuest) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "로그인한 계정으로만 여행 방을 삭제하거나 나갈 수 있습니다.")
        }

        val room = getActiveRoom(roomId)
        val member = roomMemberRepository.findByRoomIdAndUserIdAndDelYn(room.id, user.id, YnFlag.N)
            ?: throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "방 멤버만 여행 방을 삭제하거나 나갈 수 있습니다.")

        if (member.role != RoomMemberRole.HOST) {
            return leaveRoom(room, member)
        }

        val schedules = scheduleRepository.findByRoomIdAndDelYn(room.id, YnFlag.N)
        schedules.forEach { schedule ->
            satisfactionScoreRepository.findAllByScheduleIdAndDelYn(schedule.id, YnFlag.N).forEach { it.delYn = YnFlag.Y }
            scheduleSlotRepository.findAllByScheduleIdAndDelYn(schedule.id, YnFlag.N).forEach { it.delYn = YnFlag.Y }
            schedule.delYn = YnFlag.Y
        }

        val deletedAt = Instant.now()
        tripPhotoRepository.findAllByRoomIdAndDelYn(room.id, YnFlag.N).forEach { photo ->
            photo.delYn = YnFlag.Y
            photo.deletedBy = user
            photo.deletedAt = deletedAt
        }
        conflictMapRepository.findAllByRoomIdAndDelYn(room.id, YnFlag.N).forEach { it.delYn = YnFlag.Y }
        roomMemberProfileRepository.findAllByRoomIdAndDelYn(room.id, YnFlag.N).forEach { it.delYn = YnFlag.Y }
        roomMemberRepository.findAllByRoomIdAndDelYn(room.id, YnFlag.N).forEach { it.delYn = YnFlag.Y }
        room.delYn = YnFlag.Y

        return ApiResponse.ok(mapOf("roomId" to room.id, "deleted" to true, "left" to false))
    }

    private fun leaveRoom(room: TripRoom, member: RoomMember): ApiResponse<Map<String, Any?>> {
        member.delYn = YnFlag.Y
        roomMemberProfileRepository.findByRoomIdAndUserId(room.id, member.user.id)?.delYn = YnFlag.Y
        satisfactionScoreRepository.findAllByScheduleRoomIdAndUserIdAndDelYn(room.id, member.user.id, YnFlag.N)
            .forEach { it.delYn = YnFlag.Y }
        if (room.status != TripRoomStatus.COMPLETED) {
            refreshRoomStatus(room.id)
        }
        return ApiResponse.ok(mapOf("roomId" to room.id, "deleted" to false, "left" to true))
    }


    private fun upsertMemberProfile(room: TripRoom, user: User, result: com.tripsync.domain.entity.TptiResult): RoomMemberProfile {
        val profile = roomMemberProfileRepository.findByRoomIdAndUserId(room.id, user.id)
        if (profile == null) {
            return roomMemberProfileRepository.save(
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

        profile.tptiResult = result
        profile.mobilityScore = result.mobilityScore
        profile.photoScore = result.photoScore
        profile.budgetScore = result.budgetScore
        profile.themeScore = result.themeScore
        profile.characterName = result.characterName
        profile.delYn = YnFlag.N
        return profile
    }

    private fun refreshRoomStatus(roomId: Long): TripRoomStatus {
        val profileCount = roomMemberProfileRepository.findAllByRoomIdAndDelYn(roomId, YnFlag.N).size
        val room = getActiveRoom(roomId)
        val next = if (profileCount >= 2) TripRoomStatus.READY else TripRoomStatus.WAITING
        room.status = next
        return next
    }

    private fun getActiveRoom(roomId: Long): TripRoom {
        val room = tripRoomRepository.findById(roomId)
            .orElseThrow { DomainException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "존재하지 않는 방입니다.") }
        if (room.delYn != YnFlag.N) {
            throw DomainException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "존재하지 않는 방입니다.")
        }
        return room
    }

    private fun validateRoomMember(roomId: Long, userId: Long) {
        if (!roomMemberRepository.existsByRoomIdAndUserIdAndDelYn(roomId, userId, YnFlag.N)) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "방 멤버만 접근할 수 있습니다.")
        }
    }

    private fun roomSummary(room: TripRoom, memberCount: Int, includeScheduleState: Boolean): Map<String, Any?> {
        val schedules = scheduleRepository.findByRoomIdAndDelYn(room.id, YnFlag.N)
        val confirmed = schedules
            .filter { it.isConfirmed }
            .maxWithOrNull(compareBy<com.tripsync.domain.entity.Schedule> { it.version }.thenBy { it.id })
        val latestVersion = schedules.maxOfOrNull { it.version }
        val base = mapOf(
            "roomId" to room.id,
            "roomName" to room.roomName,
            "destination" to room.destination,
            "tripDate" to room.tripStartDate.toString(),
            "tripStartDate" to room.tripStartDate.toString(),
            "tripEndDate" to room.tripEndDate.toString(),
            "shareCode" to room.shareCode,
            "status" to room.status.name.lowercase(),
            "hostUserId" to room.hostUser.id,
            "memberCount" to memberCount,
            "createdAt" to room.createdAt.toString(),
            "hasGeneratedSchedule" to schedules.isNotEmpty(),
            "confirmedScheduleId" to confirmed?.id,
            "latestScheduleVersion" to latestVersion,
        )
        if (!includeScheduleState) return base

        val latestOptions = latestVersion
            ?.let { version -> schedules.filter { it.version == version }.sortedBy { it.optionType.ordinal } }
            .orEmpty()
        val scheduleState = when {
            confirmed != null -> mapOf(
                "status" to "confirmed",
                "confirmedSchedule" to scheduleResponseMapper.formatStoredSchedule(confirmed),
                "options" to latestOptions.map { scheduleResponseMapper.formatStoredSchedule(it) },
            )
            latestOptions.isNotEmpty() -> mapOf(
                "status" to "generated",
                "confirmedSchedule" to null,
                "options" to latestOptions.map { scheduleResponseMapper.formatStoredSchedule(it) },
            )
            else -> mapOf(
                "status" to "empty",
                "confirmedSchedule" to null,
                "options" to emptyList<Map<String, Any?>>(),
            )
        }

        return base + mapOf("scheduleState" to scheduleState)
    }

    private fun normalizeRoomName(roomName: String?, destination: String): String {
        val normalized = roomName?.trim()?.takeIf { it.isNotBlank() } ?: defaultRoomName(destination)
        if (normalized.length > ROOM_NAME_MAX_LENGTH) {
            throw DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "방 이름은 100자 이하여야 합니다.")
        }
        return normalized
    }

    private fun defaultRoomName(destination: String): String {
        val destinationLimit = ROOM_NAME_MAX_LENGTH - ROOM_NAME_SUFFIX.length
        return destination.trim().take(destinationLimit) + ROOM_NAME_SUFFIX
    }

    private fun generateShareCode(): String {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(5).uppercase()
        return "CNAM${LocalDate.now().year.toString().takeLast(2)}$suffix"
    }

    private companion object {
        const val ROOM_NAME_MAX_LENGTH = 100
        const val ROOM_NAME_SUFFIX = " 여행 계획"
    }
}
