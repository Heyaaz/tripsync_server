package com.tripsync.domain.repository

import com.tripsync.domain.entity.ScheduleSlot
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ScheduleSlotRepository : JpaRepository<ScheduleSlot, Long> {
    fun findAllByScheduleIdAndDelYn(scheduleId: Long, delYn: YnFlag): List<ScheduleSlot>
}
