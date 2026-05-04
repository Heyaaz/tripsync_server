package com.tripsync.domain.entity

import jakarta.persistence.*

@Entity
@Table(
    name = "room_member_profiles",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_room_member_profiles_room_user", columnNames = ["room_id", "user_id"])
    ]
)
class RoomMemberProfile(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    var room: TripRoom,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tpti_result_id", nullable = false)
    var tptiResult: TptiResult,

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
) : BaseEntity()
