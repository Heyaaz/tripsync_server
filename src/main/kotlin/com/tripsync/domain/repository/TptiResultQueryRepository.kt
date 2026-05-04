package com.tripsync.domain.repository

import com.tripsync.domain.entity.QTptiResult
import com.tripsync.domain.entity.TptiResult
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

@Repository
class TptiResultQueryRepository(
    private val jpaQueryFactory: JPAQueryFactory,
) {
    private val qTptiResult = QTptiResult.tptiResult

    fun findLatestByUserId(userId: Long): TptiResult? {
        return jpaQueryFactory
            .selectFrom(qTptiResult)
            .where(
                qTptiResult.user.id.eq(userId)
                    .and(qTptiResult.delYn.eq(com.tripsync.domain.enums.YnFlag.N))
            )
            .orderBy(qTptiResult.createdAt.desc())
            .fetchFirst()
    }

    fun findAllByRoomId(roomId: Long): List<TptiResult> {
        return jpaQueryFactory
            .selectFrom(qTptiResult)
            .where(
                qTptiResult.user.id.`in`(
                    jpaQueryFactory
                        .select(com.tripsync.domain.entity.QRoomMember.roomMember.user.id)
                        .from(com.tripsync.domain.entity.QRoomMember.roomMember)
                        .where(
                            com.tripsync.domain.entity.QRoomMember.roomMember.room.id.eq(roomId)
                                .and(com.tripsync.domain.entity.QRoomMember.roomMember.delYn.eq(com.tripsync.domain.enums.YnFlag.N))
                        )
                )
                    .and(qTptiResult.delYn.eq(com.tripsync.domain.enums.YnFlag.N))
            )
            .fetch()
    }
}
