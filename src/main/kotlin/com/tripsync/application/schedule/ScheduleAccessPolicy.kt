package com.tripsync.application.schedule

import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.Schedule
import com.tripsync.domain.entity.TripRoom
import com.tripsync.domain.enums.RoomMemberRole
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.RoomMemberRepository
import com.tripsync.domain.repository.ScheduleRepository
import com.tripsync.domain.repository.TripRoomRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class ScheduleAccessPolicy(
    private val tripRoomRepository: TripRoomRepository,
    private val scheduleRepository: ScheduleRepository,
    private val roomMemberRepository: RoomMemberRepository,
) {
    fun getActiveRoom(roomId: Long): TripRoom {
        val room = tripRoomRepository.findById(roomId)
            .orElseThrow { DomainException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "존재하지 않는 방입니다.") }
        if (room.delYn != YnFlag.N) {
            throw DomainException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "존재하지 않는 방입니다.")
        }
        return room
    }

    fun getActiveSchedule(scheduleId: Long): Schedule {
        return scheduleRepository.findByIdAndDelYn(scheduleId, YnFlag.N)
            ?: throw DomainException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "일정을 찾을 수 없습니다.")
    }

    fun validateHost(roomId: Long, userId: Long) {
        val member = roomMemberRepository.findByRoomIdAndUserIdAndDelYn(roomId, userId, YnFlag.N)
            ?: throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "방장만 일정을 생성할 수 있습니다.")
        if (member.role != RoomMemberRole.HOST) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "방장만 일정을 생성할 수 있습니다.")
        }
    }

    fun validateRoomMember(roomId: Long, userId: Long) {
        if (!roomMemberRepository.existsByRoomIdAndUserIdAndDelYn(roomId, userId, YnFlag.N)) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "방 멤버만 접근할 수 있습니다.")
        }
    }

    fun validateConfirmedSchedule(schedule: Schedule) {
        if (!schedule.isConfirmed) {
            throw DomainException(HttpStatus.CONFLICT, "SCHEDULE_NOT_CONFIRMED", "확정된 일정만 수정할 수 있습니다.")
        }
    }
}
