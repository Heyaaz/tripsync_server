package com.tripsync.web.conflict

import com.tripsync.application.auth.CustomUserDetailsService
import com.tripsync.application.conflict.ConflictService
import com.tripsync.common.dto.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/rooms")
class ConflictController(
    private val conflictService: ConflictService,
    private val userDetailsService: CustomUserDetailsService,
) {

    @GetMapping("/{roomId}/conflict-map")
    fun getConflictMap(@PathVariable roomId: Long): ApiResponse<Map<String, Any>> {
        val user = getCurrentUser()
        return conflictService.getConflictMap(roomId, user)
    }

    private fun getCurrentUser(): com.tripsync.domain.entity.User {
        val auth = org.springframework.security.core.context.SecurityContextHolder.getContext().authentication
        return userDetailsService.loadUserEntity(auth.name.toLong())
    }
}
