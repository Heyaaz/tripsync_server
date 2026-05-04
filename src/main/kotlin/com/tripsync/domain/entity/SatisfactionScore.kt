package com.tripsync.domain.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(
    name = "satisfaction_scores",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_satisfaction_scores_schedule_user", columnNames = ["schedule_id", "user_id"])
    ]
)
class SatisfactionScore(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    var schedule: Schedule,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,

    @Column(nullable = false)
    var score: Int,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var breakdown: Map<String, Any>,
) : BaseEntity()
