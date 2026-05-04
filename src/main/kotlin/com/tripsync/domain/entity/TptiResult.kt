package com.tripsync.domain.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "tpti_results")
class TptiResult(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,

    @Column(name = "mobility_score", nullable = false)
    var mobilityScore: Int,

    @Column(name = "photo_score", nullable = false)
    var photoScore: Int,

    @Column(name = "budget_score", nullable = false)
    var budgetScore: Int,

    @Column(name = "theme_score", nullable = false)
    var themeScore: Int,

    @Column(name = "character_name", nullable = false, length = 100)
    var characterName: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_answers", columnDefinition = "jsonb", nullable = false)
    var sourceAnswers: List<Int>,

    @Column(name = "is_manually_adjusted", nullable = false)
    var isManuallyAdjusted: Boolean = false,
) : BaseEntity()
