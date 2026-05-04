package com.tripsync.domain.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "conflict_maps")
class ConflictMap(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    var room: TripRoom,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "common_axes", columnDefinition = "jsonb", nullable = false)
    var commonAxes: List<String>,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conflict_axes", columnDefinition = "jsonb", nullable = false)
    var conflictAxes: List<Map<String, Any>>,

    @Column(name = "summary_text", nullable = false, columnDefinition = "TEXT")
    var summaryText: String,
) : BaseEntity()
