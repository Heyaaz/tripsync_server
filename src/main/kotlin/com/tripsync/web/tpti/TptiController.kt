package com.tripsync.web.tpti

import com.tripsync.application.auth.CustomUserDetailsService
import com.tripsync.application.tpti.TptiService
import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.exception.DomainException
import com.tripsync.web.dto.SubmitTptiDto
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

@RestController
class TptiController(
    private val tptiService: TptiService,
    private val userDetailsService: CustomUserDetailsService,
) {

    @GetMapping("/tpti/questions")
    fun getQuestions(): ApiResponse<Map<String, Any>> {
        return tptiService.getQuestions()
    }

    @PostMapping("/tpti/submit")
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(@Valid @RequestBody dto: SubmitTptiDto): ApiResponse<Map<String, Any>> {
        return tptiService.submitResult(dto.answers, dto.manualAdjustments?.toAxisScores(), currentUser())
    }

    @GetMapping("/tpti/result/{userId}")
    fun getResult(@PathVariable userId: Long): ApiResponse<Map<String, Any>> {
        return tptiService.getLatestResult(userId, currentUser())
    }

    @GetMapping("/share/tpti/{resultId}")
    fun getShareResult(@PathVariable resultId: Long): ApiResponse<Map<String, Any>> {
        return tptiService.getPublicShareResult(resultId)
    }

    private fun currentUser(): com.tripsync.domain.entity.User {
        val auth = SecurityContextHolder.getContext().authentication
        val userId = auth?.name?.toLongOrNull()
            ?: throw DomainException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.")
        return userDetailsService.loadUserEntity(userId)
    }
}
