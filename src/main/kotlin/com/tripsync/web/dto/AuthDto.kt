package com.tripsync.web.dto

import com.fasterxml.jackson.annotation.JsonAlias
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.AxisScores
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus

data class LoginDto(
    @field:Email
    @field:NotBlank
    val email: String,

    @field:NotBlank
    val password: String,
)

data class RegisterDto(
    @field:NotBlank
    @field:Size(min = 2, max = 12)
    val nickname: String,

    @field:Email
    @field:NotBlank
    val email: String,

    @field:NotBlank
    @field:Size(min = 8)
    val password: String,
)

data class CreateGuestSessionDto(
    @field:NotBlank
    @field:Size(min = 2, max = 12)
    val nickname: String,
    val shareCode: String? = null,
)

data class SubmitTptiDto(
    @field:NotEmpty
    val answers: List<Int>,
    val manualAdjustments: ManualTptiAdjustmentsDto? = null,
)

data class ManualTptiAdjustmentsDto(
    @JsonAlias("mobilityScore")
    val mobility: Int? = null,
    @JsonAlias("photoScore")
    val photo: Int? = null,
    @JsonAlias("budgetScore")
    val budget: Int? = null,
    @JsonAlias("themeScore")
    val theme: Int? = null,
) {
    fun toAxisScores(): AxisScores {
        return AxisScores(
            mobility = mobility ?: throw invalidManualAdjustments(),
            photo = photo ?: throw invalidManualAdjustments(),
            budget = budget ?: throw invalidManualAdjustments(),
            theme = theme ?: throw invalidManualAdjustments(),
        )
    }

    private fun invalidManualAdjustments(): DomainException = DomainException(
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        "수동 보정 점수는 mobilityScore/photoScore/budgetScore/themeScore 또는 mobility/photo/budget/theme를 모두 포함해야 합니다.",
    )
}

data class CreateRoomDto(
    @field:NotBlank
    val destination: String,
    @field:NotBlank
    val tripDate: String,
    val tripStartDate: String? = null,
    val tripEndDate: String? = null,
)

data class JoinRoomDto(
    val tptiResultId: Long? = null,
)

data class GenerateScheduleDto(
    @field:NotBlank
    val destination: String,
    @field:NotBlank
    val tripDate: String,
    @field:NotBlank
    val startTime: String,
    @field:NotBlank
    val endTime: String,
    val tripStartDate: String? = null,
    val tripEndDate: String? = null,
)

data class ConfirmScheduleDto(
    @field:NotBlank
    val optionType: String,
)

data class RegenerateScheduleDto(
    @field:NotBlank
    val destination: String,
    @field:NotBlank
    val tripDate: String,
    @field:NotBlank
    val startTime: String,
    @field:NotBlank
    val endTime: String,
    val tripStartDate: String? = null,
    val tripEndDate: String? = null,
    val reason: String? = null,
)
