package com.tripsync.web.schedule

import com.tripsync.application.auth.CustomUserDetailsService
import com.tripsync.application.schedule.ScheduleService
import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.exception.DomainException
import com.tripsync.web.dto.AddScheduleSlotDto
import com.tripsync.web.dto.ConfirmScheduleDto
import com.tripsync.web.dto.GenerateScheduleDto
import com.tripsync.web.dto.RegenerateScheduleDto
import com.tripsync.web.dto.ReorderScheduleSlotsDto
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

@RestController
class ScheduleController(
    private val scheduleService: ScheduleService,
    private val userDetailsService: CustomUserDetailsService,
) {

    @PostMapping("/rooms/{roomId}/generate-schedule")
    @ResponseStatus(HttpStatus.CREATED)
    fun generateSchedule(
        @PathVariable roomId: Long,
        @Valid @RequestBody dto: GenerateScheduleDto,
    ): ApiResponse<Map<String, Any?>> {
        return scheduleService.generateSchedule(roomId, currentUser().id, dto)
    }

    @PostMapping("/schedules/rooms/{roomId}/generate")
    @ResponseStatus(HttpStatus.CREATED)
    fun generateScheduleCompat(
        @PathVariable roomId: Long,
        @Valid @RequestBody dto: GenerateScheduleDto,
    ): ApiResponse<Map<String, Any?>> = generateSchedule(roomId, dto)

    @PostMapping("/rooms/{roomId}/confirm-schedule")
    @ResponseStatus(HttpStatus.CREATED)
    fun confirmSchedule(
        @PathVariable roomId: Long,
        @Valid @RequestBody dto: ConfirmScheduleDto,
    ): ApiResponse<Map<String, Any?>> {
        return scheduleService.confirmSchedule(roomId, currentUser().id, dto.optionType)
    }

    @GetMapping("/schedules/{scheduleId}")
    fun getSchedule(@PathVariable scheduleId: Long): ApiResponse<Map<String, Any?>> {
        return scheduleService.getSchedule(scheduleId, currentUser().id)
    }

    @GetMapping("/schedules/{scheduleId}/places/search")
    fun searchSchedulePlaces(
        @PathVariable scheduleId: Long,
        @RequestParam(required = false, defaultValue = "") query: String,
    ): ApiResponse<Map<String, Any?>> {
        return scheduleService.searchPlacesForSchedule(scheduleId, currentUser().id, query)
    }

    @PostMapping("/schedules/{scheduleId}/slots")
    @ResponseStatus(HttpStatus.CREATED)
    fun addScheduleSlot(
        @PathVariable scheduleId: Long,
        @Valid @RequestBody dto: AddScheduleSlotDto,
    ): ApiResponse<Map<String, Any?>> {
        return scheduleService.addScheduleSlot(scheduleId, currentUser().id, dto.placeId!!)
    }

    @PatchMapping("/schedules/{scheduleId}/slots/order")
    fun reorderScheduleSlots(
        @PathVariable scheduleId: Long,
        @Valid @RequestBody dto: ReorderScheduleSlotsDto,
    ): ApiResponse<Map<String, Any?>> {
        return scheduleService.reorderScheduleSlots(scheduleId, currentUser().id, dto.slotIds)
    }

    @PostMapping("/schedules/{scheduleId}/regenerate")
    @ResponseStatus(HttpStatus.CREATED)
    fun regenerateSchedule(
        @PathVariable scheduleId: Long,
        @Valid @RequestBody dto: RegenerateScheduleDto,
    ): ApiResponse<Map<String, Any?>> {
        return scheduleService.regenerateSchedule(scheduleId, currentUser().id, dto)
    }

    @GetMapping("/share/schedules/{scheduleId}")
    fun getPublicShareSchedule(@PathVariable scheduleId: Long): ApiResponse<Map<String, Any?>> {
        return scheduleService.getPublicShareSchedule(scheduleId)
    }

    private fun currentUser(): com.tripsync.domain.entity.User {
        val auth = SecurityContextHolder.getContext().authentication
        val userId = auth?.name?.toLongOrNull()
            ?: throw DomainException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.")
        return userDetailsService.loadUserEntity(userId)
    }
}
