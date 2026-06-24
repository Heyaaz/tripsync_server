package com.tripsync.application.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.enums.AuthProvider
import com.tripsync.domain.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.reactive.function.client.WebClient

class SecurityConfigurationUnitTest {
    @Test
    fun `oauth fallback is rejected when provider client id is blank and fallback is disabled`() {
        val service = OAuthSessionService(
            userRepository = mock(UserRepository::class.java),
            webClient = WebClient.create(),
            objectMapper = ObjectMapper(),
            frontendBaseUrl = "http://localhost:3001",
            oauthCallbackBaseUrl = "http://localhost:8080",
            googleClientId = "",
            googleClientSecret = "",
            googleAuthorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
            googleTokenUrl = "https://oauth2.googleapis.com/token",
            googleUserInfoUrl = "https://openidconnect.googleapis.com/v1/userinfo",
            kakaoClientId = "",
            kakaoClientSecret = "",
            kakaoAuthorizeUrl = "https://kauth.kakao.com/oauth/authorize",
            kakaoTokenUrl = "https://kauth.kakao.com/oauth/token",
            kakaoUserInfoUrl = "https://kapi.kakao.com/v2/user/me",
            devFallbackEnabled = false,
        )

        val error = assertThrows(DomainException::class.java) {
            service.buildRedirect(AuthProvider.GOOGLE, "/rooms/new")
        }

        assertEquals("OAUTH_NOT_CONFIGURED", error.code)
    }

    @Test
    fun `jwt provider rejects blank secret`() {
        val provider = JwtTokenProvider(
            secret = "",
            expiration = 604800000L,
            environment = MockEnvironment().apply { setActiveProfiles("prod") },
        )

        assertThrows(IllegalArgumentException::class.java) {
            provider.generateToken(1L, false)
        }
    }

    @Test
    fun `jwt provider rejects development default outside local or test profiles`() {
        val provider = JwtTokenProvider(
            secret = "local-development-secret-key-256-bits-min",
            expiration = 604800000L,
            environment = MockEnvironment().apply { setActiveProfiles("prod") },
        )

        assertThrows(IllegalArgumentException::class.java) {
            provider.generateToken(1L, false)
        }
    }

    @Test
    fun `jwt provider allows development default in test profile`() {
        val provider = JwtTokenProvider(
            secret = "test-development-secret-key-256-bits-min",
            expiration = 604800000L,
            environment = MockEnvironment().apply { setActiveProfiles("test") },
        )

        assertDoesNotThrow {
            provider.generateToken(1L, false)
        }
    }
}
