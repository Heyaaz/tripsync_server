package com.tripsync.domain.entity

import jakarta.persistence.*
import java.util.*

@Entity
@Table(name = "persona_vectors")
class PersonaVector(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    var uuid: UUID,

    @Column(nullable = false)
    var mobility: Int,

    @Column(nullable = false)
    var photo: Int,

    @Column(nullable = false)
    var budget: Int,

    @Column(nullable = false)
    var theme: Int,

    @Column(name = "persona_summary", columnDefinition = "TEXT")
    var personaSummary: String? = null,
) : BaseEntity()
