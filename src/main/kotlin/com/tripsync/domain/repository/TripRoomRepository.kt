package com.tripsync.domain.repository

import com.tripsync.domain.entity.TripRoom
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TripRoomRepository : JpaRepository<TripRoom, Long> {
    fun findByShareCode(shareCode: String): TripRoom?
    fun findByShareCodeAndDelYn(shareCode: String, delYn: YnFlag): TripRoom?
}
