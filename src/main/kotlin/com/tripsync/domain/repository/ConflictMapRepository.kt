package com.tripsync.domain.repository

import com.tripsync.domain.entity.ConflictMap
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ConflictMapRepository : JpaRepository<ConflictMap, Long> {
    fun findTopByRoomIdAndDelYnOrderByCreatedAtDesc(roomId: Long, delYn: YnFlag): ConflictMap?
    fun findAllByRoomIdAndDelYnOrderByCreatedAtDescIdDesc(roomId: Long, delYn: YnFlag): List<ConflictMap>
    fun findAllByRoomIdAndDelYn(roomId: Long, delYn: YnFlag): List<ConflictMap>
}
