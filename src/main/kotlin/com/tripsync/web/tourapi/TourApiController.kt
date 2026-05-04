package com.tripsync.web.tourapi

import com.tripsync.application.auth.CustomUserDetailsService
import com.tripsync.application.tourapi.TourApiBatchService
import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.exception.DomainException
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class EnrichPlacesDto(val limit: Int? = null)

@RestController
@RequestMapping("/tour-api")
class TourApiController(
    private val tourApiBatchService: TourApiBatchService,
    private val userDetailsService: CustomUserDetailsService,
) {
    @PostMapping("/sync/chungnam")
    fun syncChungnam(): ApiResponse<Map<String, Any>> {
        return tourApiBatchService.syncChungnamPlaces(currentUser())
    }

    @PostMapping("/enrich/chungnam")
    fun enrichChungnam(@RequestBody(required = false) dto: EnrichPlacesDto?): ApiResponse<Map<String, Any>> {
        return tourApiBatchService.enrichChungnamPlaces(currentUser(), dto?.limit ?: 50)
    }

    private fun currentUser(): com.tripsync.domain.entity.User {
        val auth = SecurityContextHolder.getContext().authentication
        val userId = auth?.name?.toLongOrNull()
            ?: throw DomainException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.")
        return userDetailsService.loadUserEntity(userId)
    }
}
