package com.tripsync.domain.entity

import com.tripsync.domain.enums.RoomMemberRole
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "room_members",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_room_members_room_user", columnNames = ["room_id", "user_id"])
    ]
)
class RoomMember(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    var room: TripRoom,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var role: RoomMemberRole,

    @Column(name = "joined_at", nullable = false)
    var joinedAt: Instant = Instant.now(),
) : BaseEntity()
