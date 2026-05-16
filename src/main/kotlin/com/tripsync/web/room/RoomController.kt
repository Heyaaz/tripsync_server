package com.tripsync.web.room

import com.tripsync.application.room.RoomService
import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.security.CurrentUser
import com.tripsync.domain.entity.User
import com.tripsync.web.dto.CreateRoomDto
import com.tripsync.web.dto.JoinRoomDto
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/rooms")
class RoomController(
    private val roomService: RoomService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createRoom(@Valid @RequestBody dto: CreateRoomDto, @CurrentUser user: User): ApiResponse<Map<String, Any>> {
        return roomService.createRoom(user, dto.destination, LocalDate.parse(dto.tripDate))
    }

    @GetMapping("/my")
    fun getMyRooms(@CurrentUser user: User): ApiResponse<Map<String, Any>> {
        return roomService.getMyRooms(user)
    }

    @GetMapping("/share/{shareCode}")
    fun getShareRoom(@PathVariable shareCode: String): ApiResponse<Map<String, Any>> {
        return roomService.getShareRoom(shareCode)
    }

    @PostMapping("/{shareCode}/join")
    @ResponseStatus(HttpStatus.CREATED)
    fun joinRoom(
        @PathVariable shareCode: String,
        @RequestBody(required = false) dto: JoinRoomDto?,
        @CurrentUser user: User,
    ): ApiResponse<Map<String, Any>> {
        return roomService.joinRoom(shareCode, dto?.tptiResultId, user)
    }

    @PostMapping("/join/{shareCode}")
    @ResponseStatus(HttpStatus.CREATED)
    fun joinRoomCompat(
        @PathVariable shareCode: String,
        @RequestBody(required = false) dto: JoinRoomDto?,
        @CurrentUser user: User,
    ): ApiResponse<Map<String, Any>> {
        return joinRoom(shareCode, dto, user)
    }

    @GetMapping("/{roomId}/members")
    fun getMembers(@PathVariable roomId: Long, @CurrentUser user: User): ApiResponse<Map<String, Any>> {
        return roomService.getMembers(roomId, user)
    }

    @GetMapping("/{roomId}")
    fun getRoom(@PathVariable roomId: Long, @CurrentUser user: User): ApiResponse<Map<String, Any>> {
        return roomService.getRoom(roomId, user)
    }
}
