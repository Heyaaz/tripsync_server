package com.tripsync.web.place

import com.tripsync.application.popularity.GooglePlacePhotoService
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

@RestController
class PlaceController(
    private val googlePlacePhotoService: GooglePlacePhotoService,
) {
    @GetMapping("/places/{placeId}/photo")
    fun getPlacePhoto(@PathVariable placeId: Long): ResponseEntity<ByteArray> {
        val photo = googlePlacePhotoService.fetchPlacePhoto(placeId)
        val builder = ResponseEntity.ok()
            .contentType(photo.contentType)
            .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
        photo.cacheControl?.let { builder.header(HttpHeaders.CACHE_CONTROL, it) }
        return builder.body(photo.bytes)
    }
}
