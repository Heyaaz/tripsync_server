package com.tripsync.web.photo

import com.tripsync.application.photo.PhotoService
import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.security.CurrentUser
import com.tripsync.domain.entity.User
import com.tripsync.web.dto.UpdatePhotoCaptionDto
import jakarta.validation.Valid
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.nio.charset.StandardCharsets

@RestController
class PhotoController(
    private val photoService: PhotoService,
) {
    @GetMapping("/schedules/{scheduleId}/photos", "/schedules/{scheduleId}/album")
    fun getAlbum(
        @PathVariable scheduleId: Long,
        @CurrentUser user: User,
    ): ApiResponse<Map<String, Any?>> = photoService.getAlbum(scheduleId, user.id)

    @PostMapping(
        "/schedules/{scheduleId}/slots/{slotId}/photos",
        "/schedules/{scheduleId}/album/slots/{slotId}/photos",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
    )
    @ResponseStatus(HttpStatus.CREATED)
    fun uploadPhoto(
        @PathVariable scheduleId: Long,
        @PathVariable slotId: Long,
        @RequestParam("file") file: MultipartFile,
        @RequestParam("caption", required = false) caption: String?,
        @CurrentUser user: User,
    ): ApiResponse<Map<String, Any?>> = photoService.uploadPhoto(scheduleId, slotId, user.id, file, caption)

    @GetMapping("/schedules/{scheduleId}/photos/{photoId}/content", "/schedules/{scheduleId}/album/photos/{photoId}/content")
    fun getPhotoContent(
        @PathVariable scheduleId: Long,
        @PathVariable photoId: Long,
        @CurrentUser user: User,
    ): ResponseEntity<ByteArray> {
        val content = photoService.getPhotoContent(scheduleId, photoId, user.id)
        val disposition = ContentDisposition.inline()
            .filename(content.filename, StandardCharsets.UTF_8)
            .build()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.contentType))
            .contentLength(content.size)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(content.content)
    }

    @PatchMapping("/schedules/{scheduleId}/photos/{photoId}", "/schedules/{scheduleId}/album/photos/{photoId}")
    fun updateCaption(
        @PathVariable scheduleId: Long,
        @PathVariable photoId: Long,
        @Valid @RequestBody dto: UpdatePhotoCaptionDto,
        @CurrentUser user: User,
    ): ApiResponse<Map<String, Any?>> = photoService.updateCaption(scheduleId, photoId, user.id, dto.caption)

    @PatchMapping("/schedules/{scheduleId}/photos/{photoId}/hide", "/schedules/{scheduleId}/album/photos/{photoId}/hide")
    fun hidePhoto(
        @PathVariable scheduleId: Long,
        @PathVariable photoId: Long,
        @CurrentUser user: User,
    ): ApiResponse<Map<String, Any?>> = photoService.hidePhoto(scheduleId, photoId, user.id)

    @DeleteMapping("/schedules/{scheduleId}/photos/{photoId}", "/schedules/{scheduleId}/album/photos/{photoId}")
    fun deletePhoto(
        @PathVariable scheduleId: Long,
        @PathVariable photoId: Long,
        @CurrentUser user: User,
    ): ApiResponse<Map<String, Any?>> = photoService.deletePhoto(scheduleId, photoId, user.id)
}
