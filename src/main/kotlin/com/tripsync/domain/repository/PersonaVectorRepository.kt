package com.tripsync.domain.repository

import com.tripsync.domain.entity.PersonaVector
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface PersonaVectorRepository : JpaRepository<PersonaVector, Long> {
    fun findByUuid(uuid: UUID): PersonaVector?
}
