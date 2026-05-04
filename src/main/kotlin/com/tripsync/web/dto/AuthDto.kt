package com.tripsync.web.dto

import com.tripsync.domain.entity.AxisScores
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

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
    val manualAdjustments: AxisScores? = null,
)

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
