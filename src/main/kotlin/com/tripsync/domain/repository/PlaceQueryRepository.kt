package com.tripsync.domain.repository

import com.querydsl.core.BooleanBuilder
import com.querydsl.core.types.dsl.Expressions
import com.querydsl.jpa.impl.JPAQueryFactory
import com.tripsync.domain.entity.Place
import com.tripsync.domain.entity.QPlace
import com.tripsync.domain.enums.YnFlag
import org.springframework.stereotype.Repository

@Repository
class PlaceQueryRepository(
    private val jpaQueryFactory: JPAQueryFactory,
) {
    private val qPlace = QPlace.place

    fun findScheduleCandidates(destination: String, limit: Long = SCHEDULE_CANDIDATE_LIMIT): List<Place> {
        val predicate = BooleanBuilder(qPlace.delYn.eq(YnFlag.N))
        destinationPredicate(destination)?.let { predicate.and(it) }

        return jpaQueryFactory
            .selectFrom(qPlace)
            .where(predicate)
            .orderBy(qPlace.name.asc(), qPlace.id.asc())
            .limit(limit)
            .fetch()
    }

    fun searchActivePlaces(query: String, limit: Long = SEARCH_LIMIT): List<Place> {
        val normalizedQuery = normalizeSearchQuery(query)
        val predicate = BooleanBuilder(qPlace.delYn.eq(YnFlag.N))
        if (normalizedQuery.isNotBlank()) {
            predicate.and(textSearchPredicate(normalizedQuery))
        }

        return jpaQueryFactory
            .selectFrom(qPlace)
            .where(predicate)
            .orderBy(populationDeclineRank().desc(), qPlace.name.asc(), qPlace.id.asc())
            .limit(limit)
            .fetch()
    }

    private fun populationDeclineRank() = Expressions.numberTemplate(
        Int::class.java,
        "case when function('jsonb_extract_path_text', {0}, 'populationDeclineArea') = 'true' " +
            "or function('jsonb_extract_path_text', {0}, 'regionType') = 'population_decline' then 1 else 0 end",
        qPlace.metadataTags,
    )

    private fun destinationPredicate(destination: String): BooleanBuilder? {
        val terms = destinationTerms(destination)
        if (terms.isEmpty()) return null

        return terms.fold(BooleanBuilder()) { builder, term ->
            builder.or(textSearchPredicate(term))
        }
    }

    private fun textSearchPredicate(term: String): BooleanBuilder {
        val like = "%${term}%"
        return BooleanBuilder()
            .or(qPlace.name.lower().like(like))
            .or(qPlace.address.lower().like(like))
            .or(qPlace.category.lower().like(like))
            .or(jsonText("region").lower().like(like))
            .or(jsonText("area").lower().like(like))
    }

    private fun jsonText(key: String) = Expressions.stringTemplate(
        "function('jsonb_extract_path_text', {0}, {1})",
        qPlace.metadataTags,
        Expressions.constant(key),
    )

    private fun destinationTerms(destination: String): Set<String> {
        val normalized = normalize(destination)
        if (normalized.isBlank() || normalized in UNBOUNDED_DESTINATIONS) return emptySet()

        return REGION_ALIASES[normalized] ?: setOf(normalized)
    }

    private fun normalize(value: String): String = value.trim().lowercase().replace(Regex("\\s+"), "")

    private fun normalizeSearchQuery(value: String): String = value.trim().lowercase()

    companion object {
        const val SEARCH_LIMIT = 120L
        const val SCHEDULE_CANDIDATE_LIMIT = 500L

        private val UNBOUNDED_DESTINATIONS = setOf("전국", "전체", "all")
        private val REGION_ALIASES = mapOf(
            "충남" to setOf("충남", "충청남도"),
            "충청남도" to setOf("충남", "충청남도"),
            "충북" to setOf("충북", "충청북도"),
            "충청북도" to setOf("충북", "충청북도"),
            "전북" to setOf("전북", "전라북도"),
            "전라북도" to setOf("전북", "전라북도"),
            "전남" to setOf("전남", "전라남도"),
            "전라남도" to setOf("전남", "전라남도"),
            "경북" to setOf("경북", "경상북도"),
            "경상북도" to setOf("경북", "경상북도"),
            "경남" to setOf("경남", "경상남도"),
            "경상남도" to setOf("경남", "경상남도"),
        )
    }
}
