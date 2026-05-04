package com.tripsync.domain.entity

import com.tripsync.domain.enums.ReasonAxis
import com.tripsync.domain.enums.SlotType
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "schedule_slots")
class ScheduleSlot(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    var schedule: Schedule,

    @Column(name = "start_time", nullable = false)
    var startTime: Instant,

    @Column(name = "end_time", nullable = false)
    var endTime: Instant,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_id", nullable = false)
    var place: Place,

    @Enumerated(EnumType.STRING)
    @Column(name = "slot_type", nullable = false)
    var slotType: SlotType,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id")
    var targetUser: User? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_axis", nullable = false, length = 20)
    var reasonAxis: ReasonAxis,

    @Column(name = "reason_text", length = 100)
    var reasonText: String? = null,

    @Column(name = "order_index", nullable = false)
    var orderIndex: Int,
) : BaseEntity()
