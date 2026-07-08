package com.tripsync.domain.repository

import com.tripsync.domain.entity.Schedule
import com.tripsync.domain.enums.ScheduleOptionType
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ScheduleRepository : JpaRepository<Schedule, Long> {
    fun findTopByRoomIdAndDelYnOrderByVersionDescIdDesc(roomId: Long, delYn: YnFlag): Schedule?
    fun findTopByRoomIdAndDelYnAndIsConfirmedTrueOrderByVersionDescIdDesc(roomId: Long, delYn: YnFlag): Schedule?

    @EntityGraph(attributePaths = ["room"])
    fun findByIdAndDelYn(id: Long, delYn: YnFlag): Schedule?

    fun findAllByRoomIdAndDelYnAndVersion(roomId: Long, delYn: YnFlag, version: Int): List<Schedule>
    fun findByRoomIdAndDelYnAndVersionAndOptionType(
        roomId: Long,
        delYn: YnFlag,
        version: Int,
        optionType: ScheduleOptionType,
    ): Schedule?

    @Query(
        nativeQuery = true,
        value = """
        select
            s.room_id as "roomId",
            count(s.id) as "scheduleCount",
            max(s.version) as "latestVersion",
            (array_agg(s.id order by s.version desc, s.id desc) filter (where s.is_confirmed = true))[1] as "confirmedScheduleId"
        from schedules s
        where s.room_id in (:roomIds)
          and s.del_yn = 'N'
        group by s.room_id
        """
    )
    fun findSummariesByRoomIds(@Param("roomIds") roomIds: Collection<Long>): List<ScheduleRoomSummaryProjection>

    @Modifying(clearAutomatically = false, flushAutomatically = false)
    @Query(
        """
        update Schedule schedule
        set schedule.isConfirmed = false
        where schedule.room.id = :roomId
          and schedule.delYn = :delYn
          and schedule.isConfirmed = true
        """
    )
    fun clearConfirmedByRoomId(
        @Param("roomId") roomId: Long,
        @Param("delYn") delYn: YnFlag,
    ): Int

    @Modifying(clearAutomatically = false, flushAutomatically = false)
    @Query(
        """
        update Schedule schedule
        set schedule.isConfirmed = true
        where schedule.id = :scheduleId
          and schedule.delYn = :delYn
        """
    )
    fun markConfirmed(
        @Param("scheduleId") scheduleId: Long,
        @Param("delYn") delYn: YnFlag,
    ): Int
}

interface ScheduleRoomSummaryProjection {
    fun getRoomId(): Long
    fun getScheduleCount(): Long
    fun getLatestVersion(): Int?
    fun getConfirmedScheduleId(): Long?
}
