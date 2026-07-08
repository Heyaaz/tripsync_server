package com.tripsync.application.schedule

import com.tripsync.domain.entity.Schedule
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.RoomMemberProfileRepository
import com.tripsync.domain.repository.SatisfactionScoreRepository
import com.tripsync.domain.repository.ScheduleSlotRepository
import org.springframework.stereotype.Component

@Component
class ScheduleReadAssembler(
    private val roomMemberProfileRepository: RoomMemberProfileRepository,
    private val scheduleSlotRepository: ScheduleSlotRepository,
    private val satisfactionScoreRepository: SatisfactionScoreRepository,
    private val responseMapper: ScheduleResponseMapper,
) {
    fun formatStoredSchedule(schedule: Schedule): Map<String, Any?> {
        val details = loadDetails(schedule)
        return responseMapper.formatStoredSchedule(
            schedule = schedule,
            memberNicknames = details.memberNicknames,
            slots = details.slots,
            satisfactionScores = details.satisfactionScores,
        )
    }

    fun formatPublicShareSchedule(schedule: Schedule): Map<String, Any?> {
        val details = loadDetails(schedule)
        return responseMapper.formatPublicShareSchedule(
            schedule = schedule,
            memberNicknames = details.memberNicknames,
            slots = details.slots,
            satisfactionScores = details.satisfactionScores,
        )
    }

    private fun loadDetails(schedule: Schedule): ScheduleReadDetails {
        val roomId = schedule.room.id
        return ScheduleReadDetails(
            memberNicknames = roomMemberProfileRepository.findMemberNicknamesByRoomId(roomId, YnFlag.N)
                .associate { it.getUserId() to it.getNickname() },
            slots = scheduleSlotRepository.findAllByScheduleIdAndDelYnOrderByOrderIndexAsc(schedule.id, YnFlag.N),
            satisfactionScores = satisfactionScoreRepository.findAllByScheduleIdAndDelYn(schedule.id, YnFlag.N),
        )
    }
}

private data class ScheduleReadDetails(
    val memberNicknames: Map<Long, String>,
    val slots: List<com.tripsync.domain.entity.ScheduleSlot>,
    val satisfactionScores: List<com.tripsync.domain.entity.SatisfactionScore>,
)
