package com.tripsync.application.photo

import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.Schedule
import com.tripsync.domain.entity.ScheduleSlot
import com.tripsync.domain.entity.TripPhoto
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.PhotoStatus
import com.tripsync.domain.enums.RoomMemberRole
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.RoomMemberRepository
import com.tripsync.domain.repository.ScheduleSlotRepository
import com.tripsync.domain.repository.TripPhotoAlbumRow
import com.tripsync.domain.repository.TripPhotoRepository
import com.tripsync.domain.repository.UserRepository
import com.tripsync.application.schedule.ScheduleAccessPolicy
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.time.Instant

@Service
class PhotoService(
    private val tripPhotoRepository: TripPhotoRepository,
    private val scheduleSlotRepository: ScheduleSlotRepository,
    private val roomMemberRepository: RoomMemberRepository,
    private val userRepository: UserRepository,
    private val accessPolicy: ScheduleAccessPolicy,
) {
    @Transactional(readOnly = true)
    fun getAlbum(scheduleId: Long, userId: Long): ApiResponse<Map<String, Any?>> {
        val schedule = requireConfirmedMemberSchedule(scheduleId, userId)
        val slots = scheduleSlotRepository.findAllByScheduleIdAndDelYn(schedule.id, YnFlag.N)
            .sortedBy { it.orderIndex }
        val photosBySlotId = tripPhotoRepository
            .findAlbumRowsByScheduleIdAndDelYnAndStatus(
                schedule.id,
                YnFlag.N,
                PhotoStatus.ACTIVE,
            )
            .groupBy { it.scheduleSlotId }

        return ApiResponse.ok(
            mapOf(
                "scheduleId" to schedule.id,
                "roomId" to schedule.room.id,
                "destination" to schedule.room.destination,
                "tripDate" to schedule.room.tripStartDate.toString(),
                "tripStartDate" to schedule.room.tripStartDate.toString(),
                "tripEndDate" to schedule.room.tripEndDate.toString(),
                "isConfirmed" to schedule.isConfirmed,
                "totalPhotoCount" to photosBySlotId.values.sumOf { it.size },
                "slots" to slots.map { slot -> formatSlot(slot, photosBySlotId[slot.id].orEmpty()) },
            )
        )
    }

    @Transactional
    fun uploadPhoto(
        scheduleId: Long,
        slotId: Long,
        userId: Long,
        file: MultipartFile,
        caption: String?,
    ): ApiResponse<Map<String, Any?>> {
        val schedule = requireConfirmedMemberSchedule(scheduleId, userId)
        val slot = requireActiveSlot(schedule.id, slotId)
        val uploader = requireActiveUser(userId)
        validateAlbumQuota(schedule.id, slot.id, uploader.id)
        val content = validateAndRead(file)
        val normalizedCaption = normalizeCaption(caption)
        val originalFilename = normalizeFilename(file.originalFilename)
        val contentType = normalizeContentType(file.contentType)

        val photo = tripPhotoRepository.save(
            TripPhoto(
                room = schedule.room,
                schedule = schedule,
                scheduleSlot = slot,
                place = slot.place,
                uploader = uploader,
                originalFilename = originalFilename,
                contentType = contentType,
                fileSize = content.size.toLong(),
                content = content,
                caption = normalizedCaption,
                status = PhotoStatus.ACTIVE,
            )
        )

        return ApiResponse.ok(mapOf("photo" to formatPhoto(photo)))
    }

    @Transactional(readOnly = true)
    fun getPhotoContent(scheduleId: Long, photoId: Long, userId: Long): PhotoContent {
        val schedule = requireConfirmedMemberSchedule(scheduleId, userId)
        val photo = tripPhotoRepository.findByIdAndScheduleIdAndDelYnAndStatus(
            photoId,
            schedule.id,
            YnFlag.N,
            PhotoStatus.ACTIVE,
        ) ?: throw DomainException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND", "사진을 찾을 수 없습니다.")

        return PhotoContent(
            content = photo.content,
            contentType = photo.contentType,
            filename = photo.originalFilename,
            size = photo.fileSize,
        )
    }

    @Transactional
    fun updateCaption(scheduleId: Long, photoId: Long, userId: Long, caption: String?): ApiResponse<Map<String, Any?>> {
        val schedule = requireConfirmedMemberSchedule(scheduleId, userId)
        val photo = requireMutablePhoto(schedule.id, photoId)
        validateUploader(photo, userId)
        photo.caption = normalizeCaption(caption)
        return ApiResponse.ok(mapOf("photo" to formatPhoto(photo)))
    }

    @Transactional
    fun hidePhoto(scheduleId: Long, photoId: Long, userId: Long): ApiResponse<Map<String, Any?>> {
        val schedule = requireConfirmedMemberSchedule(scheduleId, userId)
        validateHost(schedule.room.id, userId)
        val photo = requireMutablePhoto(schedule.id, photoId)
        photo.status = PhotoStatus.HIDDEN
        return ApiResponse.ok(mapOf("photoId" to photo.id, "status" to photo.status.name.lowercase()))
    }

    @Transactional
    fun deletePhoto(scheduleId: Long, photoId: Long, userId: Long): ApiResponse<Map<String, Any?>> {
        val schedule = requireConfirmedMemberSchedule(scheduleId, userId)
        val photo = requireMutablePhoto(schedule.id, photoId)
        validateUploaderOrHost(photo, userId)
        val deleter = requireActiveUser(userId)
        photo.status = PhotoStatus.DELETED
        photo.delYn = YnFlag.Y
        photo.deletedBy = deleter
        photo.deletedAt = Instant.now()
        return ApiResponse.ok(mapOf("photoId" to photo.id, "status" to "deleted"))
    }

    private fun requireConfirmedMemberSchedule(scheduleId: Long, userId: Long): Schedule {
        val schedule = accessPolicy.getActiveSchedule(scheduleId)
        accessPolicy.validateRoomMember(schedule.room.id, userId)
        accessPolicy.validateConfirmedSchedule(schedule)
        return schedule
    }

    private fun requireActiveSlot(scheduleId: Long, slotId: Long): ScheduleSlot {
        val slot = scheduleSlotRepository.findByIdAndDelYn(slotId, YnFlag.N)
            ?: throw DomainException(HttpStatus.NOT_FOUND, "SLOT_NOT_FOUND", "일정 슬롯을 찾을 수 없습니다.")
        if (slot.schedule.id != scheduleId) {
            throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "해당 일정에 포함되지 않은 슬롯입니다.")
        }
        return slot
    }

    private fun requireActiveUser(userId: Long): User {
        val user = userRepository.findById(userId)
            .orElseThrow { DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.") }
        if (user.delYn != YnFlag.N) {
            throw DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.")
        }
        return user
    }

    private fun requireMutablePhoto(scheduleId: Long, photoId: Long): TripPhoto {
        val photo = tripPhotoRepository.findByIdAndScheduleIdAndDelYn(photoId, scheduleId, YnFlag.N)
            ?: throw DomainException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND", "사진을 찾을 수 없습니다.")
        if (photo.status == PhotoStatus.DELETED) {
            throw DomainException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND", "사진을 찾을 수 없습니다.")
        }
        return photo
    }

    private fun validateHost(roomId: Long, userId: Long) {
        val member = roomMemberRepository.findByRoomIdAndUserIdAndDelYn(roomId, userId, YnFlag.N)
            ?: throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "방 멤버만 접근할 수 있습니다.")
        if (member.role != RoomMemberRole.HOST) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "방장만 사진을 숨김 처리할 수 있습니다.")
        }
    }

    private fun validateUploader(photo: TripPhoto, userId: Long) {
        if (photo.uploader.id != userId) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "업로더만 사진 설명을 수정할 수 있습니다.")
        }
    }

    private fun validateUploaderOrHost(photo: TripPhoto, userId: Long) {
        if (photo.uploader.id == userId) return
        val member = roomMemberRepository.findByRoomIdAndUserIdAndDelYn(photo.room.id, userId, YnFlag.N)
            ?: throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "방 멤버만 접근할 수 있습니다.")
        if (member.role != RoomMemberRole.HOST) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "업로더 또는 방장만 사진을 삭제할 수 있습니다.")
        }
    }

    private fun validateAlbumQuota(scheduleId: Long, slotId: Long, uploaderId: Long) {
        if (tripPhotoRepository.countByScheduleIdAndDelYnAndStatus(scheduleId, YnFlag.N, PhotoStatus.ACTIVE) >= MAX_PHOTOS_PER_SCHEDULE) {
            throw DomainException(HttpStatus.BAD_REQUEST, "PHOTO_ALBUM_LIMIT_EXCEEDED", "일정 사진첩에는 최대 ${MAX_PHOTOS_PER_SCHEDULE}장까지 업로드할 수 있습니다.")
        }
        if (tripPhotoRepository.countByScheduleSlotIdAndDelYnAndStatus(slotId, YnFlag.N, PhotoStatus.ACTIVE) >= MAX_PHOTOS_PER_SLOT) {
            throw DomainException(HttpStatus.BAD_REQUEST, "PHOTO_SLOT_LIMIT_EXCEEDED", "장소별 사진은 최대 ${MAX_PHOTOS_PER_SLOT}장까지 업로드할 수 있습니다.")
        }
        if (tripPhotoRepository.countByScheduleIdAndUploaderIdAndDelYnAndStatus(scheduleId, uploaderId, YnFlag.N, PhotoStatus.ACTIVE) >= MAX_PHOTOS_PER_USER_PER_SCHEDULE) {
            throw DomainException(HttpStatus.BAD_REQUEST, "PHOTO_USER_LIMIT_EXCEEDED", "한 사용자는 일정당 최대 ${MAX_PHOTOS_PER_USER_PER_SCHEDULE}장까지 업로드할 수 있습니다.")
        }
    }

    private fun validateAndRead(file: MultipartFile): ByteArray {
        if (file.isEmpty) {
            throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_PHOTO", "사진 파일을 첨부해야 합니다.")
        }
        if (file.size > MAX_FILE_SIZE_BYTES) {
            throw DomainException(HttpStatus.BAD_REQUEST, "PHOTO_TOO_LARGE", "사진은 최대 10MB까지 업로드할 수 있습니다.")
        }
        val contentType = normalizeContentType(file.contentType)
        val content = file.bytes
        if (!matchesContentTypeSignature(content, contentType)) {
            throw DomainException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_PHOTO_TYPE", "파일 내용과 사진 형식이 일치하지 않습니다.")
        }
        return content
    }

    private fun normalizeContentType(contentType: String?): String {
        val normalized = contentType?.lowercase()?.trim().orEmpty()
        if (normalized !in ALLOWED_CONTENT_TYPES) {
            throw DomainException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_PHOTO_TYPE", "jpeg, png, webp 형식만 업로드할 수 있습니다.")
        }
        return normalized
    }

    private fun matchesContentTypeSignature(content: ByteArray, contentType: String): Boolean = when (contentType) {
        "image/jpeg" -> content.size >= 3 &&
            content[0] == 0xFF.toByte() &&
            content[1] == 0xD8.toByte() &&
            content[2] == 0xFF.toByte()
        "image/png" -> content.size >= 8 &&
            content[0] == 0x89.toByte() &&
            content[1] == 0x50.toByte() &&
            content[2] == 0x4E.toByte() &&
            content[3] == 0x47.toByte() &&
            content[4] == 0x0D.toByte() &&
            content[5] == 0x0A.toByte() &&
            content[6] == 0x1A.toByte() &&
            content[7] == 0x0A.toByte()
        "image/webp" -> content.size >= 12 &&
            content[0] == 'R'.code.toByte() &&
            content[1] == 'I'.code.toByte() &&
            content[2] == 'F'.code.toByte() &&
            content[3] == 'F'.code.toByte() &&
            content[8] == 'W'.code.toByte() &&
            content[9] == 'E'.code.toByte() &&
            content[10] == 'B'.code.toByte() &&
            content[11] == 'P'.code.toByte()
        else -> false
    }

    private fun normalizeFilename(filename: String?): String {
        val cleaned = filename
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
            ?: "photo"
        return cleaned.take(MAX_FILENAME_LENGTH)
    }

    private fun normalizeCaption(caption: String?): String? {
        val normalized = caption?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized != null && normalized.length > MAX_CAPTION_LENGTH) {
            throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_CAPTION", "사진 설명은 최대 500자까지 입력할 수 있습니다.")
        }
        return normalized
    }

    private fun formatSlot(slot: ScheduleSlot, photos: List<TripPhotoAlbumRow>): Map<String, Any?> = mapOf(
        "slotId" to slot.id,
        "scheduleSlotId" to slot.id,
        "orderIndex" to slot.orderIndex,
        "startTime" to slot.startTime.toString(),
        "endTime" to slot.endTime.toString(),
        "place" to mapOf(
            "id" to slot.place.id,
            "name" to slot.place.name,
            "address" to slot.place.address,
            "imageUrl" to slot.place.imageUrl,
            "category" to slot.place.category,
            "latitude" to slot.place.latitude.toDouble(),
            "longitude" to slot.place.longitude.toDouble(),
            "isDepopulationArea" to isDepopulationArea(slot.place.metadataTags),
        ),
        "photos" to photos.map { formatPhoto(it) },
    )

    private fun formatPhoto(photo: TripPhoto): Map<String, Any?> = mapOf(
        "id" to photo.id,
        "photoId" to photo.id,
        "scheduleId" to photo.schedule.id,
        "slotId" to photo.scheduleSlot.id,
        "scheduleSlotId" to photo.scheduleSlot.id,
        "placeId" to photo.place.id,
        "uploader" to mapOf(
            "id" to photo.uploader.id,
            "nickname" to photo.uploader.nickname,
        ),
        "uploaderUserId" to photo.uploader.id,
        "uploaderNickname" to photo.uploader.nickname,
        "originalFilename" to photo.originalFilename,
        "contentType" to photo.contentType,
        "fileSize" to photo.fileSize,
        "sizeBytes" to photo.fileSize,
        "caption" to photo.caption,
        "status" to photo.status.name.lowercase(),
        "contentUrl" to "/api/schedules/${photo.schedule.id}/album/photos/${photo.id}/content",
        "createdAt" to photo.createdAt.toString(),
        "updatedAt" to photo.updatedAt.toString(),
    )

    private fun formatPhoto(photo: TripPhotoAlbumRow): Map<String, Any?> = mapOf(
        "id" to photo.id,
        "photoId" to photo.id,
        "scheduleId" to photo.scheduleId,
        "slotId" to photo.scheduleSlotId,
        "scheduleSlotId" to photo.scheduleSlotId,
        "placeId" to photo.placeId,
        "uploader" to mapOf(
            "id" to photo.uploaderUserId,
            "nickname" to photo.uploaderNickname,
        ),
        "uploaderUserId" to photo.uploaderUserId,
        "uploaderNickname" to photo.uploaderNickname,
        "originalFilename" to photo.originalFilename,
        "contentType" to photo.contentType,
        "fileSize" to photo.fileSize,
        "sizeBytes" to photo.fileSize,
        "caption" to photo.caption,
        "status" to photo.status.name.lowercase(),
        "contentUrl" to "/api/schedules/${photo.scheduleId}/album/photos/${photo.id}/content",
        "createdAt" to photo.createdAt.toString(),
        "updatedAt" to photo.updatedAt.toString(),
    )

    private fun isDepopulationArea(metadataTags: Map<String, Any>?): Boolean {
        return metadataTags?.get("populationDeclineArea") == true || metadataTags?.get("regionType") == "population_decline"
    }


    companion object {
        const val MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L
        private const val MAX_PHOTOS_PER_SCHEDULE = 200L
        private const val MAX_PHOTOS_PER_SLOT = 50L
        private const val MAX_PHOTOS_PER_USER_PER_SCHEDULE = 100L
        private const val MAX_FILENAME_LENGTH = 255
        private const val MAX_CAPTION_LENGTH = 500
        private val ALLOWED_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}

data class PhotoContent(
    val content: ByteArray,
    val contentType: String,
    val filename: String,
    val size: Long,
)
