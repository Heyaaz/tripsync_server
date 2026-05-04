package com.tripsync.web.auth

import com.tripsync.application.auth.CustomUserDetailsService
import com.tripsync.application.auth.GuestSessionService
import com.tripsync.application.auth.JwtAuthenticationFilter
import com.tripsync.application.auth.JwtTokenProvider
import com.tripsync.application.auth.OAuthSessionService
import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.AuthProvider
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.UserRepository
import com.tripsync.web.dto.CreateGuestSessionDto
import com.tripsync.web.dto.LoginDto
import com.tripsync.web.dto.RegisterDto
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.*
import java.time.Duration

@RestController
@RequestMapping("/auth")
class AuthController(
    private val jwtTokenProvider: JwtTokenProvider,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val userDetailsService: CustomUserDetailsService,
    private val guestSessionService: GuestSessionService,
    private val oAuthSessionService: OAuthSessionService,
) {

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody dto: RegisterDto, response: HttpServletResponse): ApiResponse<Map<String, Any>> {
        val email = dto.email.trim().lowercase()
        if (userRepository.findByEmailAndDelYn(email, YnFlag.N) != null) {
            return ApiResponse.error("INVALID_REQUEST", "이미 사용 중인 이메일입니다.")
        }

        val user = userRepository.save(
            User(
                nickname = dto.nickname,
                email = email,
                passwordHash = passwordEncoder.encode(dto.password),
                authProvider = AuthProvider.LOCAL,
                providerUserId = email,
            )
        )

        val token = issueSessionCookie(user, response)
        return authSuccess(user, token)
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody dto: LoginDto, response: HttpServletResponse): ApiResponse<Map<String, Any>> {
        val user = userRepository.findByEmailAndDelYn(dto.email.trim().lowercase(), YnFlag.N)
            ?: return ApiResponse.error("UNAUTHORIZED", "이메일 또는 비밀번호가 올바르지 않습니다.")

        if (user.authProvider != AuthProvider.LOCAL || user.passwordHash == null || !passwordEncoder.matches(dto.password, user.passwordHash)) {
            return ApiResponse.error("UNAUTHORIZED", "이메일 또는 비밀번호가 올바르지 않습니다.")
        }

        val token = issueSessionCookie(user, response)
        return authSuccess(user, token)
    }

    @PostMapping("/guest")
    @ResponseStatus(HttpStatus.CREATED)
    fun createGuestSession(@Valid @RequestBody dto: CreateGuestSessionDto, response: HttpServletResponse): ApiResponse<Map<String, Any>> {
        val result = guestSessionService.createGuestUser(dto.nickname)
        val token = issueSessionCookie(result, response)
        return authSuccess(result, token)
    }

    @GetMapping("/me")
    fun me(): ApiResponse<Map<String, Any>> {
        val user = currentUser()
        return authSuccess(user, null)
    }

    @PostMapping("/logout")
    fun logout(response: HttpServletResponse): ApiResponse<Nothing> {
        response.addHeader(HttpHeaders.SET_COOKIE, expiredSessionCookie().toString())
        return ApiResponse(success = true, data = null, error = null)
    }

    @GetMapping("/google")
    fun googleStart(@RequestParam(required = false) redirectPath: String?, response: HttpServletResponse) {
        val redirect = oAuthSessionService.buildRedirect(AuthProvider.GOOGLE, redirectPath)
        response.addHeader(HttpHeaders.SET_COOKIE, oauthStateCookie(redirect.state).toString())
        response.sendRedirect(redirect.redirectUrl)
    }

    @GetMapping("/kakao")
    fun kakaoStart(@RequestParam(required = false) redirectPath: String?, response: HttpServletResponse) {
        val redirect = oAuthSessionService.buildRedirect(AuthProvider.KAKAO, redirectPath)
        response.addHeader(HttpHeaders.SET_COOKIE, oauthStateCookie(redirect.state).toString())
        response.sendRedirect(redirect.redirectUrl)
    }

    @GetMapping("/google/callback")
    fun googleCallback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) redirectPath: String?,
        @RequestParam(required = false) error: String?,
        @CookieValue(name = OAUTH_STATE_COOKIE_NAME, required = false) expectedState: String?,
        response: HttpServletResponse,
    ) {
        val result = oAuthSessionService.handleCallback(AuthProvider.GOOGLE, code, state, expectedState, redirectPath, error)
        issueSessionCookie(result.user, response)
        response.addHeader(HttpHeaders.SET_COOKIE, expiredOAuthStateCookie().toString())
        response.sendRedirect(result.redirectUrl)
    }

    @GetMapping("/kakao/callback")
    fun kakaoCallback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) redirectPath: String?,
        @RequestParam(required = false) error: String?,
        @CookieValue(name = OAUTH_STATE_COOKIE_NAME, required = false) expectedState: String?,
        response: HttpServletResponse,
    ) {
        val result = oAuthSessionService.handleCallback(AuthProvider.KAKAO, code, state, expectedState, redirectPath, error)
        issueSessionCookie(result.user, response)
        response.addHeader(HttpHeaders.SET_COOKIE, expiredOAuthStateCookie().toString())
        response.sendRedirect(result.redirectUrl)
    }

    private fun issueSessionCookie(user: User, response: HttpServletResponse): String {
        val token = jwtTokenProvider.generateToken(user.id, user.isGuest)
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(token).toString())
        return token
    }

    private fun authSuccess(user: User, token: String?): ApiResponse<Map<String, Any>> {
        val data = mutableMapOf<String, Any>(
            "user" to userSummary(user),
            "expiresIn" to 60 * 60 * 24 * 7,
        )
        if (token != null) {
            data["token"] = token
        }
        return ApiResponse.ok(data)
    }

    private fun userSummary(user: User): Map<String, Any?> = mapOf(
        "id" to user.id,
        "nickname" to user.nickname,
        "email" to user.email,
        "authProvider" to user.authProvider.name.lowercase(),
        "isGuest" to user.isGuest,
    )

    private fun currentUser(): User {
        val auth = SecurityContextHolder.getContext().authentication
        val userId = auth?.name?.toLongOrNull()
            ?: throw DomainException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.")
        return userDetailsService.loadUserEntity(userId)
    }

    private fun sessionCookie(token: String): ResponseCookie = ResponseCookie
        .from(JwtAuthenticationFilter.SESSION_COOKIE_NAME, token)
        .httpOnly(true)
        .secure(false)
        .sameSite("Lax")
        .path("/")
        .maxAge(Duration.ofDays(7))
        .build()

    private fun expiredSessionCookie(): ResponseCookie = ResponseCookie
        .from(JwtAuthenticationFilter.SESSION_COOKIE_NAME, "")
        .httpOnly(true)
        .secure(false)
        .sameSite("Lax")
        .path("/")
        .maxAge(Duration.ZERO)
        .build()

    private fun oauthStateCookie(state: String): ResponseCookie = ResponseCookie
        .from(OAUTH_STATE_COOKIE_NAME, state)
        .httpOnly(true)
        .secure(false)
        .sameSite("Lax")
        .path("/")
        .maxAge(Duration.ofMinutes(10))
        .build()

    private fun expiredOAuthStateCookie(): ResponseCookie = ResponseCookie
        .from(OAUTH_STATE_COOKIE_NAME, "")
        .httpOnly(true)
        .secure(false)
        .sameSite("Lax")
        .path("/")
        .maxAge(Duration.ZERO)
        .build()

    companion object {
        const val OAUTH_STATE_COOKIE_NAME = "ts_oauth_state"
    }
}

