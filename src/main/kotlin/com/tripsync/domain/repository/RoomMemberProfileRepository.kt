package com.tripsync.domain.repository

import com.tripsync.domain.entity.RoomMemberProfile
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface RoomMemberProfileRepository : JpaRepository<RoomMemberProfile, Long> {
    fun findAllByRoomIdAndDelYn(roomId: Long, delYn: YnFlag): List<RoomMemberProfile>
    fun findByRoomIdAndUserId(roomId: Long, userId: Long): RoomMemberProfile?
}
