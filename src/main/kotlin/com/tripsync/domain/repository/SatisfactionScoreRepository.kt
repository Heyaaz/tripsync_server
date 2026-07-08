package com.tripsync.domain.repository

import com.tripsync.domain.entity.SatisfactionScore
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface SatisfactionScoreRepository : JpaRepository<SatisfactionScore, Long> {
    @EntityGraph(attributePaths = ["user"])
    fun findAllByScheduleIdAndDelYn(scheduleId: Long, delYn: YnFlag): List<SatisfactionScore>
    fun findAllByScheduleRoomIdAndUserIdAndDelYn(roomId: Long, userId: Long, delYn: YnFlag): List<SatisfactionScore>
}
