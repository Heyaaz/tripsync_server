package com.tripsync.domain.repository

import com.tripsync.domain.entity.TptiResult
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TptiResultRepository : JpaRepository<TptiResult, Long> {
    fun findTopByUserIdAndDelYnOrderByCreatedAtDesc(userId: Long, delYn: YnFlag): TptiResult?
}
