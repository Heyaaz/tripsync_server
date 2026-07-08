package com.tripsync.domain.repository

import com.tripsync.domain.entity.ScheduleSlot
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface ScheduleSlotRepository : JpaRepository<ScheduleSlot, Long> {
    fun findAllByScheduleIdAndDelYn(scheduleId: Long, delYn: YnFlag): List<ScheduleSlot>
    fun findByIdAndDelYn(id: Long, delYn: YnFlag): ScheduleSlot?

    @EntityGraph(attributePaths = ["place", "targetUser"])
    fun findAllByScheduleIdAndDelYnOrderByOrderIndexAsc(scheduleId: Long, delYn: YnFlag): List<ScheduleSlot>

    @Query("select slot.place.id from ScheduleSlot slot where slot.schedule.id = :scheduleId and slot.delYn = :delYn")
    fun findActivePlaceIdsByScheduleId(scheduleId: Long, delYn: YnFlag = YnFlag.N): List<Long>
}
