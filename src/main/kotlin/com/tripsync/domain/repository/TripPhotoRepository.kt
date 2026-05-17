package com.tripsync.domain.repository

import com.tripsync.domain.entity.TripPhoto
import com.tripsync.domain.enums.PhotoStatus
import com.tripsync.domain.enums.YnFlag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TripPhotoRepository : JpaRepository<TripPhoto, Long> {
    fun findAllByScheduleIdAndDelYnAndStatusOrderByScheduleSlotOrderIndexAscCreatedAtAsc(
        scheduleId: Long,
        delYn: YnFlag,
        status: PhotoStatus,
    ): List<TripPhoto>

    fun findByIdAndDelYn(id: Long, delYn: YnFlag): TripPhoto?
    fun findByIdAndScheduleIdAndDelYn(id: Long, scheduleId: Long, delYn: YnFlag): TripPhoto?
    fun findByIdAndScheduleIdAndDelYnAndStatus(
        id: Long,
        scheduleId: Long,
        delYn: YnFlag,
        status: PhotoStatus,
    ): TripPhoto?
}
