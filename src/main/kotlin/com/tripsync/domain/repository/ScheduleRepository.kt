package com.tripsync.domain.repository

import com.tripsync.domain.entity.Schedule
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ScheduleRepository : JpaRepository<Schedule, Long> {
    fun findByRoomIdAndDelYn(roomId: Long, delYn: YnFlag): List<Schedule>
    fun findTopByRoomIdAndDelYnOrderByVersionDesc(roomId: Long, delYn: YnFlag): Schedule?
}
