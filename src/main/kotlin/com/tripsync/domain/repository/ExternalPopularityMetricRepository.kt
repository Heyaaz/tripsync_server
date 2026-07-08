package com.tripsync.domain.repository

import com.tripsync.domain.entity.ExternalPopularityMetric
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ExternalPopularityMetricRepository : JpaRepository<ExternalPopularityMetric, Long> {
    @EntityGraph(attributePaths = ["place"])
    fun findByPlaceId(placeId: Long): ExternalPopularityMetric?
    fun findByPlaceIdIn(placeIds: Collection<Long>): List<ExternalPopularityMetric>
}
