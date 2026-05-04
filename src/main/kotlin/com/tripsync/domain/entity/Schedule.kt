package com.tripsync.domain.entity

import com.tripsync.domain.enums.ScheduleOptionType
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(
    name = "schedules",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_schedules_room_version_option", columnNames = ["room_id", "version", "option_type"])
    ]
)
class Schedule(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    var room: TripRoom,

    @Column(nullable = false)
    var version: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "option_type", nullable = false)
    var optionType: ScheduleOptionType,

    @Column(name = "is_confirmed", nullable = false)
    var isConfirmed: Boolean = false,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "generation_input", columnDefinition = "jsonb", nullable = false)
    var generationInput: Map<String, Any>,

    @Column(columnDefinition = "TEXT")
    var summary: String? = null,

    @Column(name = "group_satisfaction", nullable = false)
    var groupSatisfaction: Int,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "persona_validation", columnDefinition = "jsonb")
    var personaValidation: Map<String, Any>? = null,

    @Column(name = "llm_provider", length = 50)
    var llmProvider: String? = null,
) : BaseEntity() {
    @OneToMany(mappedBy = "schedule", fetch = FetchType.LAZY, cascade = [CascadeType.ALL], orphanRemoval = true)
    val slots: MutableList<ScheduleSlot> = mutableListOf()

    @OneToMany(mappedBy = "schedule", fetch = FetchType.LAZY, cascade = [CascadeType.ALL], orphanRemoval = true)
    val satisfactionScores: MutableList<SatisfactionScore> = mutableListOf()
}
