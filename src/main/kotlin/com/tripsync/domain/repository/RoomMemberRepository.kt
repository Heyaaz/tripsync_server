package com.tripsync.domain.repository

import com.tripsync.domain.entity.RoomMember
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface RoomMemberRepository : JpaRepository<RoomMember, Long> {
    fun findByRoomIdAndUserIdAndDelYn(roomId: Long, userId: Long, delYn: YnFlag): RoomMember?
    fun existsByRoomIdAndUserIdAndDelYn(roomId: Long, userId: Long, delYn: YnFlag): Boolean
    fun findAllByRoomIdAndDelYn(roomId: Long, delYn: YnFlag): List<RoomMember>
    fun findAllByUserIdAndDelYn(userId: Long, delYn: YnFlag): List<RoomMember>
}
