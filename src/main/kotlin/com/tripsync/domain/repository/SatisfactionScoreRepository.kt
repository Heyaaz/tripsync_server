package com.tripsync.domain.repository

import com.tripsync.domain.entity.SatisfactionScore
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface SatisfactionScoreRepository : JpaRepository<SatisfactionScore, Long>
