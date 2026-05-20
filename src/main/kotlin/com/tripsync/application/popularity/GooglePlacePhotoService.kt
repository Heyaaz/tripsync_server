package com.tripsync.application.popularity

import com.tripsync.common.exception.DomainException
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.ExternalPopularityMetricRepository
import com.tripsync.infrastructure.popularity.GooglePlacePhoto
import com.tripsync.infrastructure.popularity.GooglePlacesClient
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class GooglePlacePhotoService(
    private val metricRepository: ExternalPopularityMetricRepository,
    private val googlePlacesClient: GooglePlacesClient,
) {
    fun fetchPlacePhoto(placeId: Long): GooglePlacePhoto {
        val metric = metricRepository.findByPlaceId(placeId)
            ?: throw DomainException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND", "장소 사진을 찾을 수 없습니다.")
        if (metric.place.delYn != YnFlag.N) {
            throw DomainException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다.")
        }
        val reference = metric.googlePhotoReference?.takeIf { it.isNotBlank() }
            ?: throw DomainException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND", "장소 사진을 찾을 수 없습니다.")

        return runBlocking { googlePlacesClient.fetchPhoto(reference) }
    }
}
