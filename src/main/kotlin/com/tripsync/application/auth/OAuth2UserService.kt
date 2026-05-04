package com.tripsync.application.auth

import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.AuthProvider
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.UserRepository
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Service

@Service
class OAuth2UserService(
    private val userRepository: UserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
) : DefaultOAuth2UserService() {
    private val logger = KotlinLogging.logger {}

    fun processGoogleLogin(oAuth2User: OAuth2User): ApiResponse<Map<String, Any>> {
        val email = oAuth2User.getAttribute<String>("email")
            ?: throw DomainException(HttpStatus.BAD_REQUEST, "OAUTH_EMAIL_MISSING", "이메일 정보를 가져올 수 없습니다.")
        val name = oAuth2User.getAttribute<String>("name") ?: "User"
        val picture = oAuth2User.getAttribute<String>("picture")
        val providerId = oAuth2User.getAttribute<String>("sub")
            ?: throw DomainException(HttpStatus.BAD_REQUEST, "OAUTH_ID_MISSING", "사용자 ID를 가져올 수 없습니다.")

        val existingUser = userRepository.findByAuthProviderAndProviderUserId(AuthProvider.GOOGLE, providerId)

        val user = if (existingUser != null) {
            existingUser
        } else {
            userRepository.save(
                User(
                    nickname = name,
                    email = email,
                    authProvider = AuthProvider.GOOGLE,
                    providerUserId = providerId,
                    profileImageUrl = picture,
                )
            )
        }

        val token = jwtTokenProvider.generateToken(user.id, user.isGuest)
        @Suppress("UNCHECKED_CAST")
        return ApiResponse.ok(
            mapOf(
                "token" to token,
                "userId" to user.id,
                "nickname" to user.nickname,
                "email" to (user.email ?: ""),
                "authProvider" to user.authProvider.name,
            ) as Map<String, Any>
        )
    }

    fun processKakaoLogin(oAuth2User: OAuth2User): ApiResponse<Map<String, Any>> {
        val attributes = oAuth2User.attributes
        val kakaoAccount = attributes["kakao_account"] as? Map<*, *>
        val profile = kakaoAccount?.get("profile") as? Map<*, *>

        val email = kakaoAccount?.get("email") as? String
        val nickname = profile?.get("nickname") as? String ?: "User"
        val profileImage = profile?.get("profile_image_url") as? String
        val providerId = attributes["id"]?.toString()
            ?: throw DomainException(HttpStatus.BAD_REQUEST, "OAUTH_ID_MISSING", "사용자 ID를 가져올 수 없습니다.")

        val existingUser = userRepository.findByAuthProviderAndProviderUserId(AuthProvider.KAKAO, providerId)

        val user = if (existingUser != null) {
            existingUser
        } else {
            userRepository.save(
                User(
                    nickname = nickname,
                    email = email,
                    authProvider = AuthProvider.KAKAO,
                    providerUserId = providerId,
                    profileImageUrl = profileImage,
                )
            )
        }

        val token = jwtTokenProvider.generateToken(user.id, user.isGuest)
        @Suppress("UNCHECKED_CAST")
        return ApiResponse.ok(
            mapOf(
                "token" to token,
                "userId" to user.id,
                "nickname" to user.nickname,
                "email" to (user.email ?: ""),
                "authProvider" to user.authProvider.name,
            ) as Map<String, Any>
        )
    }
}
