package com.tripsync.domain.entity

import com.tripsync.domain.enums.AuthProvider
import com.tripsync.domain.enums.YnFlag
import jakarta.persistence.*

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 50)
    var nickname: String,

    @Column(length = 255)
    var email: String? = null,

    @Column(name = "password_hash", length = 255)
    var passwordHash: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false)
    var authProvider: AuthProvider,

    @Column(name = "provider_user_id", length = 100)
    var providerUserId: String? = null,

    @Column(name = "profile_image_url", columnDefinition = "TEXT")
    var profileImageUrl: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "admin_yn", nullable = false, length = 1)
    var adminYn: YnFlag = YnFlag.N,

    @Column(name = "is_guest", nullable = false)
    var isGuest: Boolean = false,
) : BaseEntity() {
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    val tptiResults: MutableList<TptiResult> = mutableListOf()

    @OneToMany(mappedBy = "hostUser", fetch = FetchType.LAZY)
    val hostedRooms: MutableList<TripRoom> = mutableListOf()

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    val roomMembers: MutableList<RoomMember> = mutableListOf()

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    val roomProfiles: MutableList<RoomMemberProfile> = mutableListOf()

    @OneToMany(mappedBy = "targetUser", fetch = FetchType.LAZY)
    val targetSlots: MutableList<ScheduleSlot> = mutableListOf()

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    val satisfactionScores: MutableList<SatisfactionScore> = mutableListOf()
}
