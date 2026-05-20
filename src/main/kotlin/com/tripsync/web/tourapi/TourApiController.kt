package com.tripsync.web.tourapi

import com.tripsync.application.popularity.ExternalPopularityBatchService
import com.tripsync.application.tourapi.TourApiBatchService
import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.security.CurrentUser
import com.tripsync.domain.entity.User
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class EnrichPlacesDto(val limit: Int? = null)

@RestController
@RequestMapping("/tour-api")
class TourApiController(
    private val tourApiBatchService: TourApiBatchService,
    private val externalPopularityBatchService: ExternalPopularityBatchService,
) {
    @PostMapping("/sync/chungnam")
    fun syncChungnam(@CurrentUser user: User): ApiResponse<Map<String, Any?>> {
        return tourApiBatchService.syncChungnamPlaces(user)
    }

    @PostMapping("/enrich/chungnam")
    fun enrichChungnam(
        @RequestBody(required = false) dto: EnrichPlacesDto?,
        @CurrentUser user: User,
    ): ApiResponse<Map<String, Any?>> {
        return tourApiBatchService.enrichChungnamPlaces(user, dto?.limit ?: 50)
    }

    @PostMapping("/external-popularity/sync")
    fun syncExternalPopularity(
        @RequestBody(required = false) dto: EnrichPlacesDto?,
        @CurrentUser user: User,
    ): ApiResponse<Map<String, Any?>> {
        return externalPopularityBatchService.syncManually(user, dto?.limit)
    }
}
