package com.tripsync.application.auth

import com.tripsync.common.dto.ApiResponse
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.AuthProvider
import com.tripsync.domain.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GuestSessionService(
    private val userRepository: UserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
) {

    @Transactional
    fun createGuestUser(nickname: String): User {
        return userRepository.save(
            User(
                nickname = nickname,
                email = null,
                authProvider = AuthProvider.GUEST,
                isGuest = true,
            )
        )
    }

    @Transactional
    fun createGuestSession(nickname: String): ApiResponse<Map<String, Any>> {
        val user = createGuestUser(nickname)
        val token = jwtTokenProvider.generateToken(user.id, isGuest = true)
        return ApiResponse.ok(
            mapOf(
                "token" to token,
                "user" to mapOf(
                    "id" to user.id,
                    "nickname" to user.nickname,
                    "isGuest" to true,
                    "authProvider" to user.authProvider.name.lowercase(),
                ),
                "expiresIn" to 60 * 60 * 24 * 7,
            )
        )
    }
}
