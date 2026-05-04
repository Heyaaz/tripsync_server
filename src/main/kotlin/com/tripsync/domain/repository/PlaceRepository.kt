package com.tripsync.domain.repository

import com.tripsync.domain.entity.Place
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PlaceRepository : JpaRepository<Place, Long> {
    fun findByCategoryAndDelYn(category: String, delYn: YnFlag): List<Place>
    fun findByDelYn(delYn: YnFlag): List<Place>
    fun findByTourApiId(tourApiId: String): Place?
}
