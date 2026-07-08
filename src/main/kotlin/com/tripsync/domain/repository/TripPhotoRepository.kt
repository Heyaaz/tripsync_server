package com.tripsync.domain.repository

import com.tripsync.domain.entity.TripPhoto
import com.tripsync.domain.enums.PhotoStatus
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface TripPhotoRepository : JpaRepository<TripPhoto, Long> {
    @Query(
        """
        select new com.tripsync.domain.repository.TripPhotoAlbumRow(
            photo.id,
            photo.schedule.id,
            photo.scheduleSlot.id,
            photo.place.id,
            photo.uploader.id,
            photo.uploader.nickname,
            photo.originalFilename,
            photo.contentType,
            photo.fileSize,
            photo.caption,
            photo.status,
            photo.createdAt,
            photo.updatedAt
        )
        from TripPhoto photo
        where photo.schedule.id = :scheduleId
          and photo.delYn = :delYn
          and photo.status = :status
        order by photo.scheduleSlot.orderIndex asc, photo.createdAt asc
        """
    )
    fun findAlbumRowsByScheduleIdAndDelYnAndStatus(
        @Param("scheduleId") scheduleId: Long,
        @Param("delYn") delYn: YnFlag,
        @Param("status") status: PhotoStatus,
    ): List<TripPhotoAlbumRow>

    fun findAllByScheduleIdAndDelYnAndStatusOrderByScheduleSlotOrderIndexAscCreatedAtAsc(
        scheduleId: Long,
        delYn: YnFlag,
        status: PhotoStatus,
    ): List<TripPhoto>

    fun findAllByRoomIdAndDelYn(roomId: Long, delYn: YnFlag): List<TripPhoto>

    fun countByScheduleIdAndDelYnAndStatus(scheduleId: Long, delYn: YnFlag, status: PhotoStatus): Long
    fun countByScheduleSlotIdAndDelYnAndStatus(scheduleSlotId: Long, delYn: YnFlag, status: PhotoStatus): Long
    fun countByScheduleIdAndUploaderIdAndDelYnAndStatus(scheduleId: Long, uploaderId: Long, delYn: YnFlag, status: PhotoStatus): Long

    fun findByIdAndDelYn(id: Long, delYn: YnFlag): TripPhoto?
    fun findByIdAndScheduleIdAndDelYn(id: Long, scheduleId: Long, delYn: YnFlag): TripPhoto?
    fun findByIdAndScheduleIdAndDelYnAndStatus(
        id: Long,
        scheduleId: Long,
        delYn: YnFlag,
        status: PhotoStatus,
    ): TripPhoto?
}


data class TripPhotoAlbumRow(
    val id: Long,
    val scheduleId: Long,
    val scheduleSlotId: Long,
    val placeId: Long,
    val uploaderUserId: Long,
    val uploaderNickname: String,
    val originalFilename: String,
    val contentType: String,
    val fileSize: Long,
    val caption: String?,
    val status: PhotoStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)
