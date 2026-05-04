package com.tripsync.domain.repository

import com.tripsync.domain.entity.QRoomMember
import com.tripsync.domain.entity.QTripRoom
import com.tripsync.domain.entity.RoomMember
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

@Repository
class RoomMemberQueryRepository(
    private val jpaQueryFactory: JPAQueryFactory,
) {
    private val qRoomMember = QRoomMember.roomMember
    private val qTripRoom = QTripRoom.tripRoom

    fun findSharedRoomMember(requesterId: Long, targetUserId: Long): RoomMember? {
        return jpaQueryFactory
            .selectFrom(qRoomMember)
            .where(
                qRoomMember.user.id.eq(requesterId)
                    .and(qRoomMember.delYn.eq(com.tripsync.domain.enums.YnFlag.N))
                    .and(qRoomMember.room.delYn.eq(com.tripsync.domain.enums.YnFlag.N))
                    .and(
                        qRoomMember.room.id.`in`(
                            jpaQueryFactory
                                .select(qRoomMember.room.id)
                                .from(qRoomMember)
                                .where(
                                    qRoomMember.user.id.eq(targetUserId)
                                        .and(qRoomMember.delYn.eq(com.tripsync.domain.enums.YnFlag.N))
                                )
                        )
                    )
            )
            .fetchFirst()
    }

    fun findActiveMembersByRoomId(roomId: Long): List<RoomMember> {
        return jpaQueryFactory
            .selectFrom(qRoomMember)
            .where(
                qRoomMember.room.id.eq(roomId)
                    .and(qRoomMember.delYn.eq(com.tripsync.domain.enums.YnFlag.N))
            )
            .fetch()
    }
}
