package com.tripsync.application.conflict

import com.tripsync.application.consensus.ConsensusService
import com.tripsync.application.consensus.MemberSnapshot
import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.*
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ConflictService(
    private val consensusService: ConsensusService,
    private val tripRoomRepository: TripRoomRepository,
    private val roomMemberRepository: RoomMemberRepository,
    private val roomMemberProfileRepository: RoomMemberProfileRepository,
    private val conflictMapRepository: ConflictMapRepository,
) {

    @Transactional
    fun getConflictMap(roomId: Long, user: User): ApiResponse<Map<String, Any>> {
        validateRoomMember(roomId, user.id)

        val room = tripRoomRepository.findById(roomId)
            .orElseThrow { DomainException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "존재하지 않는 방입니다.") }

        val profiles = roomMemberProfileRepository.findAllByRoomIdAndDelYn(roomId, YnFlag.N)
        if (profiles.size < 2) {
            throw DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "ROOM_NOT_READY", "갈등 지도를 계산할 프로필이 부족합니다.")
        }

        val members = profiles.mapIndexed { index, profile ->
            MemberSnapshot(
                userId = profile.user.id,
                nickname = profile.user.nickname,
                scores = com.tripsync.domain.entity.AxisScores(
                    mobility = profile.mobilityScore,
                    photo = profile.photoScore,
                    budget = profile.budgetScore,
                    theme = profile.themeScore,
                ),
                joinedOrder = index,
            )
        }

        val analysis = consensusService.analyzeGroup(members)
        val topConflict = analysis.conflictAxes.firstOrNull()
        val highMember = topConflict?.let { members.find { m -> m.userId == it.highUserId } }
        val lowMember = topConflict?.let { members.find { m -> m.userId == it.lowUserId } }
        val summaryText = topConflict?.let {
            "${highMember?.nickname ?: "A님"}과 ${lowMember?.nickname ?: "B님"}은 ${axisLabel(it.axis)}에서 ${it.gap}점 차이로 충돌합니다."
        } ?: "현재 그룹은 공통 지대가 넓습니다."

        val conflictMap = conflictMapRepository.save(
            com.tripsync.domain.entity.ConflictMap(
                room = room,
                commonAxes = analysis.commonAxes.map { it.name.lowercase() },
                conflictAxes = analysis.conflictAxes.map {
                    mapOf(
                        "axis" to it.axis.name.lowercase(),
                        "min" to it.min,
                        "max" to it.max,
                        "gap" to it.gap,
                        "severity" to it.severity.name.lowercase(),
                        "highUserId" to it.highUserId,
                        "lowUserId" to it.lowUserId,
                    )
                },
                summaryText = summaryText,
            )
        )

        return ApiResponse.ok(
            mapOf(
                "roomId" to roomId,
                "conflictMapId" to conflictMap.id,
                "commonAxes" to analysis.commonAxes.map { it.name.lowercase() },
                "conflictAxes" to analysis.conflictAxes.map {
                    mapOf(
                        "axis" to it.axis.name.lowercase(),
                        "gap" to it.gap,
                        "severity" to it.severity.name.lowercase(),
                    )
                },
                "summaryText" to summaryText,
                "members" to members.map {
                    mapOf(
                        "userId" to it.userId,
                        "nickname" to it.nickname,
                        "scores" to mapOf(
                            "mobility" to it.scores.mobility,
                            "photo" to it.scores.photo,
                            "budget" to it.scores.budget,
                            "theme" to it.scores.theme,
                        ),
                    )
                },
            )
        )
    }

    private fun validateRoomMember(roomId: Long, userId: Long) {
        if (!roomMemberRepository.existsByRoomIdAndUserIdAndDelYn(roomId, userId, YnFlag.N)) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "방 멤버만 접근할 수 있습니다.")
        }
    }

    private fun axisLabel(axis: com.tripsync.domain.enums.ScoreAxis): String {
        return when (axis) {
            com.tripsync.domain.enums.ScoreAxis.MOBILITY -> "활동성"
            com.tripsync.domain.enums.ScoreAxis.PHOTO -> "기록"
            com.tripsync.domain.enums.ScoreAxis.BUDGET -> "예산"
            com.tripsync.domain.enums.ScoreAxis.THEME -> "테마"
        }
    }
}
