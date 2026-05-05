package com.tripsync.web.room

import com.tripsync.application.auth.CustomUserDetailsService
import com.tripsync.application.room.RoomService
import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.exception.DomainException
import com.tripsync.web.dto.CreateRoomDto
import com.tripsync.web.dto.JoinRoomDto
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/rooms")
class RoomController(
    private val roomService: RoomService,
    private val userDetailsService: CustomUserDetailsService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createRoom(@Valid @RequestBody dto: CreateRoomDto): ApiResponse<Map<String, Any>> {
        return roomService.createRoom(currentUser(), dto.destination, LocalDate.parse(dto.tripDate))
    }

    @GetMapping("/my")
    fun getMyRooms(): ApiResponse<Map<String, Any>> {
        return roomService.getMyRooms(currentUser())
    }

    @GetMapping("/share/{shareCode}")
    fun getShareRoom(@PathVariable shareCode: String): ApiResponse<Map<String, Any>> {
        return roomService.getShareRoom(shareCode)
    }

    @PostMapping("/{shareCode}/join")
    @ResponseStatus(HttpStatus.CREATED)
    fun joinRoom(@PathVariable shareCode: String, @RequestBody(required = false) dto: JoinRoomDto?): ApiResponse<Map<String, Any>> {
        return roomService.joinRoom(shareCode, dto?.tptiResultId, currentUser())
    }

    @PostMapping("/join/{shareCode}")
    @ResponseStatus(HttpStatus.CREATED)
    fun joinRoomCompat(@PathVariable shareCode: String, @RequestBody(required = false) dto: JoinRoomDto?): ApiResponse<Map<String, Any>> {
        return joinRoom(shareCode, dto)
    }

    @GetMapping("/{roomId}/members")
    fun getMembers(@PathVariable roomId: Long): ApiResponse<Map<String, Any>> {
        return roomService.getMembers(roomId, currentUser())
    }

    @GetMapping("/{roomId}")
    fun getRoom(@PathVariable roomId: Long): ApiResponse<Map<String, Any>> {
        return roomService.getRoom(roomId, currentUser())
    }

    private fun currentUser(): com.tripsync.domain.entity.User {
        val auth = SecurityContextHolder.getContext().authentication
        val userId = auth?.name?.toLongOrNull()
            ?: throw DomainException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.")
        return userDetailsService.loadUserEntity(userId)
    }
}
