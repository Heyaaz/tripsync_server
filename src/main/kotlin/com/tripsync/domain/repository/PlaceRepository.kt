package com.tripsync.domain.repository

import com.tripsync.domain.entity.Place
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface PlaceRepository : JpaRepository<Place, Long> {
    fun findByCategoryAndDelYn(category: String, delYn: YnFlag): List<Place>
    fun findByTourApiIdIn(tourApiIds: Collection<String>): List<Place>

    @Query(
        nativeQuery = true,
        value = """
        select *
        from places p
        where p.del_yn = 'N'
          and p.id > :afterId
          and (
            p.metadata_tags is null
            or not jsonb_exists(p.metadata_tags, 'detailEnrichedAt')
            or jsonb_exists(p.metadata_tags, 'sourceModifiedTime')
          )
        order by p.id
        limit :limit
        """
    )
    fun findDetailEnrichmentCandidatesAfterId(
        @Param("afterId") afterId: Long,
        @Param("limit") limit: Int,
    ): List<Place>
}
