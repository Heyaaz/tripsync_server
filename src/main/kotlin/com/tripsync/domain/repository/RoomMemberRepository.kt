package com.tripsync.domain.repository

import com.tripsync.domain.entity.RoomMember
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface RoomMemberRepository : JpaRepository<RoomMember, Long> {
    fun findByRoomIdAndUserIdAndDelYn(roomId: Long, userId: Long, delYn: YnFlag): RoomMember?
    fun existsByRoomIdAndUserIdAndDelYn(roomId: Long, userId: Long, delYn: YnFlag): Boolean

    @EntityGraph(attributePaths = ["user"])
    fun findAllByRoomIdAndDelYn(roomId: Long, delYn: YnFlag): List<RoomMember>

    @EntityGraph(attributePaths = ["room", "room.hostUser"])
    fun findAllByUserIdAndDelYn(userId: Long, delYn: YnFlag): List<RoomMember>
    fun countByRoomIdAndDelYn(roomId: Long, delYn: YnFlag): Long

    @Query(
        """
        select member.room.id as roomId, count(member.id) as memberCount
        from RoomMember member
        where member.room.id in :roomIds
          and member.delYn = :delYn
        group by member.room.id
        """
    )
    fun countActiveMembersByRoomIds(
        @Param("roomIds") roomIds: Collection<Long>,
        @Param("delYn") delYn: YnFlag,
    ): List<RoomMemberCountProjection>
}

interface RoomMemberCountProjection {
    fun getRoomId(): Long
    fun getMemberCount(): Long
}
