package com.tripsync.domain.repository

import com.tripsync.domain.entity.Schedule
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ScheduleRepository : JpaRepository<Schedule, Long> {
    fun findByRoomIdAndDelYn(roomId: Long, delYn: YnFlag): List<Schedule>
    fun findTopByRoomIdAndDelYnOrderByVersionDesc(roomId: Long, delYn: YnFlag): Schedule?

    @Query(
        """
        select schedule
        from Schedule schedule
        where schedule.room.id = :roomId
          and schedule.delYn = :delYn
          and schedule.isConfirmed = true
        order by schedule.version desc, schedule.id desc
        """
    )
    fun findConfirmedByRoomId(
        @Param("roomId") roomId: Long,
        @Param("delYn") delYn: YnFlag,
    ): List<Schedule>
}
