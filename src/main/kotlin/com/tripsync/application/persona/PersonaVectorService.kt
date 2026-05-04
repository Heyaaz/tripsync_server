package com.tripsync.application.persona

import com.tripsync.domain.entity.AxisScores
import com.tripsync.domain.entity.PersonaVector
import com.tripsync.domain.repository.PersonaVectorRepository
import jakarta.annotation.PostConstruct
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.*

@Service
class PersonaVectorService(
    private val personaVectorRepository: PersonaVectorRepository,
) {
    private val logger = KotlinLogging.logger {}
    private lateinit var vectorPool: List<PersonaVectorData>

    data class PersonaVectorData(
        val uuid: UUID,
        val scores: AxisScores,
        val summary: String?,
    )

    data class MatchedPersona(
        val uuid: UUID,
        val similarity: Double,
        val scores: AxisScores,
        val summary: String?,
    )

    @PostConstruct
    fun initialize() {
        val start = System.currentTimeMillis()
        val entities = personaVectorRepository.findAll()
        vectorPool = entities.map {
            PersonaVectorData(
                uuid = it.uuid,
                scores = AxisScores(
                    mobility = it.mobility,
                    photo = it.photo,
                    budget = it.budget,
                    theme = it.theme,
                ),
                summary = it.personaSummary,
            )
        }
        logger.info {
            "PersonaVectorService loaded ${vectorPool.size} vectors in ${System.currentTimeMillis() - start}ms"
        }
    }

    fun findTopK(target: AxisScores, k: Int): List<MatchedPersona> {
        if (vectorPool.isEmpty()) {
            return emptyList()
        }
        return vectorPool
            .map {
                val distance = target.l1Distance(it.scores)
                val similarity = 1.0 - (distance / 400.0)
                MatchedPersona(
                    uuid = it.uuid,
                    similarity = similarity.coerceIn(0.0, 1.0),
                    scores = it.scores,
                    summary = it.summary,
                )
            }
            .sortedByDescending { it.similarity }
            .take(k)
    }

    fun matchForMember(
        memberScores: AxisScores,
        topK: Int = 3,
    ): List<MatchedPersona> {
        return findTopK(memberScores, topK)
    }
}
