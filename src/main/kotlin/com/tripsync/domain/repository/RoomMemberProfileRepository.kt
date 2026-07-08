package com.tripsync.domain.repository

import com.tripsync.domain.entity.RoomMemberProfile
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface RoomMemberProfileRepository : JpaRepository<RoomMemberProfile, Long> {
    @EntityGraph(attributePaths = ["user"])
    fun findAllByRoomIdAndDelYn(roomId: Long, delYn: YnFlag): List<RoomMemberProfile>

    @Query(
        """
        select
            profile.user.id as userId,
            profile.user.nickname as nickname
        from RoomMemberProfile profile
        where profile.room.id = :roomId
          and profile.delYn = :delYn
        """
    )
    fun findMemberNicknamesByRoomId(
        @Param("roomId") roomId: Long,
        @Param("delYn") delYn: YnFlag,
    ): List<MemberNicknameProjection>

    fun findByRoomIdAndUserId(roomId: Long, userId: Long): RoomMemberProfile?
    fun countByRoomIdAndDelYn(roomId: Long, delYn: YnFlag): Long
}

interface MemberNicknameProjection {
    fun getUserId(): Long
    fun getNickname(): String
}
