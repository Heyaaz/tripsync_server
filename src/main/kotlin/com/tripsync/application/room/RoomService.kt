package com.tripsync.application.room

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
import java.time.LocalDate
import java.util.*

@Service
class RoomService(
    private val tripRoomRepository: TripRoomRepository,
    private val roomMemberRepository: RoomMemberRepository,
    private val roomMemberProfileRepository: RoomMemberProfileRepository,
    private val tptiResultRepository: TptiResultRepository,
) {

    @Transactional
    fun createRoom(host: User, destination: String, tripDate: LocalDate): ApiResponse<Map<String, Any>> {
        if (host.isGuest) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "방장 권한이 필요합니다.")
        }
        if (!tripDate.isAfter(LocalDate.now())) {
            throw DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "tripDate는 오늘 이후여야 합니다.")
        }

        val room = tripRoomRepository.save(
            TripRoom(
                hostUser = host,
                shareCode = generateShareCode(),
                destination = destination,
                tripDate = tripDate,
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
                "shareCode" to room.shareCode,
                "status" to room.status.name.lowercase(),
            )
        )
    }

    @Transactional(readOnly = true)
    fun getRoom(roomId: Long, user: User): ApiResponse<Map<String, Any>> {
        validateRoomMember(roomId, user.id)
        val room = getActiveRoom(roomId)
        val memberCount = roomMemberRepository.findAllByRoomIdAndDelYn(room.id, YnFlag.N).size

        return ApiResponse.ok(roomSummary(room, memberCount))
    }

    @Transactional(readOnly = true)
    fun getMyRooms(user: User): ApiResponse<Map<String, Any>> {
        if (user.isGuest) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "방장 계정으로 로그인해주세요.")
        }

        val rooms = roomMemberRepository.findAllByUserIdAndDelYn(user.id, YnFlag.N)
            .map { it.room }
            .filter { it.delYn == YnFlag.N }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<TripRoom> { it.createdAt }.thenByDescending { it.id })
            .map { room ->
                val memberCount = roomMemberRepository.findAllByRoomIdAndDelYn(room.id, YnFlag.N).size
                roomSummary(room, memberCount)
            }

        return ApiResponse.ok(mapOf("rooms" to rooms))
    }

    @Transactional(readOnly = true)
    fun getShareRoom(shareCode: String): ApiResponse<Map<String, Any>> {
        val room = tripRoomRepository.findByShareCodeAndDelYn(shareCode, YnFlag.N)
            ?: throw DomainException(HttpStatus.NOT_FOUND, "INVALID_SHARE_CODE", "유효하지 않은 공유 코드입니다.")
        val memberCount = roomMemberRepository.findAllByRoomIdAndDelYn(room.id, YnFlag.N).size
        return ApiResponse.ok(
            roomSummary(room, memberCount) + mapOf("hostNickname" to room.hostUser.nickname)
        )
    }

    @Transactional
    fun joinRoom(shareCode: String, tptiResultId: Long?, user: User): ApiResponse<Map<String, Any>> {
        val room = tripRoomRepository.findByShareCodeAndDelYn(shareCode, YnFlag.N)
            ?: throw DomainException(HttpStatus.NOT_FOUND, "INVALID_SHARE_CODE", "유효하지 않은 공유 코드입니다.")

        val existingMember = roomMemberRepository.findByRoomIdAndUserIdAndDelYn(room.id, user.id, YnFlag.N)
        if (existingMember == null) {
            roomMemberRepository.save(
                RoomMember(
                    room = room,
                    user = user,
                    role = if (room.hostUser.id == user.id) RoomMemberRole.HOST else RoomMemberRole.MEMBER,
                )
            )
        }

        if (tptiResultId != null) {
            val result = tptiResultRepository.findById(tptiResultId).orElse(null)
            if (result == null || result.user.id != user.id || result.delYn != YnFlag.N) {
                throw DomainException(HttpStatus.NOT_FOUND, "TPTI_INCOMPLETE", "유효한 TPTI 결과를 찾을 수 없습니다.")
            }
            upsertMemberProfile(room, user, result)
        }

        val roomStatus = refreshRoomStatus(room.id)
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
    fun getMembers(roomId: Long, user: User): ApiResponse<Map<String, Any>> {
        validateRoomMember(roomId, user.id)
        val members = roomMemberRepository.findAllByRoomIdAndDelYn(roomId, YnFlag.N)
        val profilesByUserId = roomMemberProfileRepository.findAllByRoomIdAndDelYn(roomId, YnFlag.N)
            .associateBy { it.user.id }

        return ApiResponse.ok(
            mapOf(
                "roomId" to roomId,
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

    private fun roomSummary(room: TripRoom, memberCount: Int): Map<String, Any> = mapOf(
        "roomId" to room.id,
        "destination" to room.destination,
        "tripDate" to room.tripDate.toString(),
        "tripStartDate" to room.tripDate.toString(),
        "tripEndDate" to room.tripDate.toString(),
        "shareCode" to room.shareCode,
        "status" to room.status.name.lowercase(),
        "hostUserId" to room.hostUser.id,
        "memberCount" to memberCount,
        "createdAt" to room.createdAt.toString(),
    )

    private fun generateShareCode(): String {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(5).uppercase()
        return "CNAM${LocalDate.now().year.toString().takeLast(2)}$suffix"
    }
}
