package com.tripsync.web.conflict

import com.tripsync.application.conflict.ConflictService
import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.security.CurrentUser
import com.tripsync.domain.entity.User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/rooms")
class ConflictController(
    private val conflictService: ConflictService,
) {

    @GetMapping("/{roomId}/conflict-map")
    fun getConflictMap(@PathVariable roomId: Long, @CurrentUser user: User): ApiResponse<Map<String, Any>> {
        return conflictService.getConflictMap(roomId, user)
    }
}
