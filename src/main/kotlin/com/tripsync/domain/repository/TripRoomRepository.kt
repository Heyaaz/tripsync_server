package com.tripsync.domain.repository

import com.tripsync.domain.entity.TripRoom
import com.tripsync.domain.enums.YnFlag
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface TripRoomRepository : JpaRepository<TripRoom, Long> {
    fun findByShareCode(shareCode: String): TripRoom?
    fun findByShareCodeAndDelYn(shareCode: String, delYn: YnFlag): TripRoom?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select room
        from TripRoom room
        where room.id = :roomId
          and room.delYn = :delYn
        """
    )
    fun findActiveByIdForUpdate(
        @Param("roomId") roomId: Long,
        @Param("delYn") delYn: YnFlag,
    ): TripRoom?
}
