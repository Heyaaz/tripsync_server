package com.tripsync.domain.entity

import com.tripsync.domain.enums.TripRoomStatus
import jakarta.persistence.*
import java.time.LocalDate

@Entity
@Table(name = "trip_rooms")
class TripRoom(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_user_id", nullable = false)
    var hostUser: User,

    @Column(name = "share_code", nullable = false, length = 12, unique = true)
    var shareCode: String,

    @Column(nullable = false, length = 100)
    var destination: String,

    @Column(name = "trip_date", nullable = false)
    var tripDate: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: TripRoomStatus = TripRoomStatus.WAITING,
) : BaseEntity() {
    @OneToMany(mappedBy = "room", fetch = FetchType.LAZY)
    val members: MutableList<RoomMember> = mutableListOf()

    @OneToMany(mappedBy = "room", fetch = FetchType.LAZY)
    val memberProfiles: MutableList<RoomMemberProfile> = mutableListOf()

    @OneToMany(mappedBy = "room", fetch = FetchType.LAZY)
    val conflictMaps: MutableList<ConflictMap> = mutableListOf()

    @OneToMany(mappedBy = "room", fetch = FetchType.LAZY)
    val schedules: MutableList<Schedule> = mutableListOf()
}
