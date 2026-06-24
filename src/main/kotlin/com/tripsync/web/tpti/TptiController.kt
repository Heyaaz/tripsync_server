package com.tripsync.web.tpti

import com.tripsync.application.tpti.TptiService
import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.security.CurrentUser
import com.tripsync.domain.entity.User
import com.tripsync.web.dto.SubmitTptiDto
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
class TptiController(
    private val tptiService: TptiService,
) {

    @GetMapping("/tpti/questions")
    fun getQuestions(): ApiResponse<Map<String, Any>> {
        return tptiService.getQuestions()
    }

    @PostMapping("/tpti/submit")
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(@Valid @RequestBody dto: SubmitTptiDto, @CurrentUser user: User): ApiResponse<Map<String, Any>> {
        return tptiService.submitResult(dto.answers, dto.manualAdjustments?.toAxisScores(), user)
    }

    @GetMapping("/tpti/result/{userId}")
    fun getResult(@PathVariable userId: Long, @CurrentUser user: User): ApiResponse<Map<String, Any>> {
        return tptiService.getLatestResult(userId, user)
    }

    @GetMapping("/share/tpti/{shareToken}")
    fun getShareResult(@PathVariable shareToken: String): ApiResponse<Map<String, Any>> {
        return tptiService.getPublicShareResult(shareToken)
    }
}
