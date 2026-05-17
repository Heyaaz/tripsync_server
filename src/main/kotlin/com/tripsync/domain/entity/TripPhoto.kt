package com.tripsync.domain.entity

import com.tripsync.domain.enums.PhotoStatus
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(
    name = "trip_photos",
    indexes = [
        Index(name = "idx_trip_photos_schedule_slot", columnList = "schedule_id, schedule_slot_id"),
        Index(name = "idx_trip_photos_room", columnList = "room_id"),
        Index(name = "idx_trip_photos_uploader", columnList = "uploader_user_id"),
    ]
)
class TripPhoto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    var room: TripRoom,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    var schedule: Schedule,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_slot_id", nullable = false)
    var scheduleSlot: ScheduleSlot,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_id", nullable = false)
    var place: Place,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploader_user_id", nullable = false)
    var uploader: User,

    @Column(name = "original_filename", nullable = false, length = 255)
    var originalFilename: String,

    @Column(name = "content_type", nullable = false, length = 100)
    var contentType: String,

    @Column(name = "file_size", nullable = false)
    var fileSize: Long,

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "content", nullable = false, columnDefinition = "bytea")
    var content: ByteArray,

    @Column(name = "width")
    var width: Int? = null,

    @Column(name = "height")
    var height: Int? = null,

    @Column(name = "caption", length = 500)
    var caption: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: PhotoStatus = PhotoStatus.ACTIVE,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by_user_id")
    var deletedBy: User? = null,

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
) : BaseEntity() {
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
