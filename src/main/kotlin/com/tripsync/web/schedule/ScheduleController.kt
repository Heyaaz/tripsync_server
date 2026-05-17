package com.tripsync.web.schedule

import com.tripsync.application.schedule.ScheduleService
import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.security.CurrentUser
import com.tripsync.domain.entity.User
import com.tripsync.web.dto.AddScheduleSlotDto
import com.tripsync.web.dto.ConfirmScheduleDto
import com.tripsync.web.dto.GenerateScheduleDto
import com.tripsync.web.dto.RegenerateScheduleDto
import com.tripsync.web.dto.ReorderScheduleSlotsDto
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class ScheduleController(
    private val scheduleService: ScheduleService,
) {

    @PostMapping("/rooms/{roomId}/generate-schedule")
    @ResponseStatus(HttpStatus.CREATED)
    fun generateSchedule(
        @PathVariable roomId: Long,
        @Valid @RequestBody dto: GenerateScheduleDto,
        @CurrentUser user: User,
    ): ApiResponse<Map<String, Any?>> {
        return scheduleService.generateSchedule(roomId, user.id, dto)
    }

    @PostMapping("/schedules/rooms/{roomId}/generate")
    @ResponseStatus(HttpStatus.CREATED)
    fun generateScheduleCompat(
        @PathVariable roomId: Long,
        @Valid @RequestBody dto: GenerateScheduleDto,
        @CurrentUser user: User,
    ): ApiResponse<Map<String, Any?>> = generateSchedule(roomId, dto, user)

    @PostMapping("/rooms/{roomId}/confirm-schedule")
    @ResponseStatus(HttpStatus.CREATED)
    fun confirmSchedule(
        @PathVariable roomId: Long,
        @Valid @RequestBody dto: ConfirmScheduleDto,
        @CurrentUser user: User,
    ): ApiResponse<Map<String, Any?>> {
        return scheduleService.confirmSchedule(roomId, user.id, dto.optionType)
    }

    @GetMapping("/schedules/{scheduleId}")
    fun getSchedule(@PathVariable scheduleId: Long, @CurrentUser user: User): ApiResponse<Map<String, Any?>> {
        return scheduleService.getSchedule(scheduleId, user.id)
    }

    @GetMapping("/rooms/{roomId}/confirmed-schedule")
    fun getConfirmedSchedule(@PathVariable roomId: Long, @CurrentUser user: User): ApiResponse<Map<String, Any?>> {
        return scheduleService.getConfirmedSchedule(roomId, user.id)
    }

    @GetMapping("/schedules/{scheduleId}/places/search")
    fun searchSchedulePlaces(
        @PathVariable scheduleId: Long,
        @RequestParam(required = false, defaultValue = "") query: String,
        @CurrentUser user: User,
    ): ApiResponse<Map<String, Any?>> {
        return scheduleService.searchPlacesForSchedule(scheduleId, user.id, query)
    }

    @PostMapping("/schedules/{scheduleId}/slots")
    @ResponseStatus(HttpStatus.CREATED)
    fun addScheduleSlot(
        @PathVariable scheduleId: Long,
        @Valid @RequestBody dto: AddScheduleSlotDto,
        @CurrentUser user: User,
    ): ApiResponse<Map<String, Any?>> {
        return scheduleService.addScheduleSlot(scheduleId, user.id, dto.placeId)
    }

    @PatchMapping("/schedules/{scheduleId}/slots/order")
    fun reorderScheduleSlots(
        @PathVariable scheduleId: Long,
        @Valid @RequestBody dto: ReorderScheduleSlotsDto,
        @CurrentUser user: User,
    ): ApiResponse<Map<String, Any?>> {
        return scheduleService.reorderScheduleSlots(scheduleId, user.id, dto.slotIds)
    }

    @PostMapping("/schedules/{scheduleId}/regenerate")
    @ResponseStatus(HttpStatus.CREATED)
    fun regenerateSchedule(
        @PathVariable scheduleId: Long,
        @Valid @RequestBody dto: RegenerateScheduleDto,
        @CurrentUser user: User,
    ): ApiResponse<Map<String, Any?>> {
        return scheduleService.regenerateSchedule(scheduleId, user.id, dto)
    }

    @GetMapping("/share/schedules/{scheduleId}")
    fun getPublicShareSchedule(@PathVariable scheduleId: Long): ApiResponse<Map<String, Any?>> {
        return scheduleService.getPublicShareSchedule(scheduleId)
    }
}
