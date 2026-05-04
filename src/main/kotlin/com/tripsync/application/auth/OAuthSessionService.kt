package com.tripsync.application.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.AuthProvider
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

@Service
class OAuthSessionService(
    private val userRepository: UserRepository,
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
    @Value("\${frontend.base-url:http://localhost:3001}") private val frontendBaseUrl: String,
    @Value("\${oauth.callback-base-url:\${frontend.base-url:http://localhost:3001}}") private val oauthCallbackBaseUrl: String,
    @Value("\${google.client-id:}") private val googleClientId: String,
    @Value("\${google.client-secret:}") private val googleClientSecret: String,
    @Value("\${google.authorize-url:https://accounts.google.com/o/oauth2/v2/auth}") private val googleAuthorizeUrl: String,
    @Value("\${google.token-url:https://oauth2.googleapis.com/token}") private val googleTokenUrl: String,
    @Value("\${google.user-info-url:https://openidconnect.googleapis.com/v1/userinfo}") private val googleUserInfoUrl: String,
    @Value("\${kakao.client-id:}") private val kakaoClientId: String,
    @Value("\${kakao.client-secret:}") private val kakaoClientSecret: String,
    @Value("\${kakao.authorize-url:https://kauth.kakao.com/oauth/authorize}") private val kakaoAuthorizeUrl: String,
    @Value("\${kakao.token-url:https://kauth.kakao.com/oauth/token}") private val kakaoTokenUrl: String,
    @Value("\${kakao.user-info-url:https://kapi.kakao.com/v2/user/me}") private val kakaoUserInfoUrl: String,
) {
    data class OAuthRedirect(val state: String, val redirectUrl: String)
    data class OAuthResult(val user: User, val redirectUrl: String)
    private data class OAuthProfile(val providerUserId: String, val nickname: String, val email: String?, val profileImageUrl: String?)

    fun buildRedirect(provider: AuthProvider, redirectPath: String?): OAuthRedirect {
        val normalizedRedirectPath = sanitizeRedirectPath(redirectPath)
        val state = UUID.randomUUID().toString()
        val combinedState = "$state|$normalizedRedirectPath"
        val callbackUrl = callbackUrl(provider)
        val clientId = clientId(provider)

        if (clientId.isBlank()) {
            return OAuthRedirect(
                state = state,
                redirectUrl = "$callbackUrl?code=local-${provider.name.lowercase()}-code&state=${enc(combinedState)}&redirectPath=${enc(normalizedRedirectPath)}",
            )
        }

        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to callbackUrl,
            "state" to combinedState,
        )
        if (provider == AuthProvider.GOOGLE) {
            params["scope"] = "openid profile email"
            params["access_type"] = "offline"
            params["prompt"] = "consent"
            params["include_granted_scopes"] = "true"
        }
        return OAuthRedirect(state, "${authorizeUrl(provider)}?${query(params)}")
    }

    @Transactional
    fun handleCallback(provider: AuthProvider, code: String?, state: String?, expectedState: String?, redirectPath: String?, error: String?): OAuthResult {
        if (error != null) {
            throw DomainException(HttpStatus.BAD_GATEWAY, "OAUTH_PROVIDER_ERROR", "OAuth 공급자 오류가 발생했습니다.")
        }
        val (stateValue, redirectPathFromState) = (state ?: "").split("|", limit = 2).let {
            it.getOrElse(0) { "" } to it.getOrElse(1) { null }
        }
        if (stateValue.isBlank() || expectedState.isNullOrBlank() || stateValue != expectedState) {
            throw DomainException(HttpStatus.UNAUTHORIZED, "OAUTH_STATE_INVALID", "OAuth state 검증에 실패했습니다.")
        }
        val authCode = code ?: throw DomainException(HttpStatus.BAD_GATEWAY, "OAUTH_PROVIDER_ERROR", "OAuth authorization code가 없습니다.")
        val profile = fetchOAuthProfile(provider, authCode)
        val user = upsertOAuthUser(provider, profile)
        return OAuthResult(
            user = user,
            redirectUrl = frontendRedirect(sanitizeRedirectPath(redirectPath ?: redirectPathFromState), provider),
        )
    }

    private fun fetchOAuthProfile(provider: AuthProvider, code: String): OAuthProfile {
        if (clientId(provider).isBlank()) {
            return OAuthProfile(
                providerUserId = code,
                nickname = if (provider == AuthProvider.KAKAO) "kakao-host" else "google-host",
                email = null,
                profileImageUrl = null,
            )
        }
        val accessToken = exchangeAuthorizationCode(provider, code)
        val payload = webClient.get()
            .uri(userInfoUrl(provider))
            .headers { it.setBearerAuth(accessToken) }
            .retrieve()
            .bodyToMono(String::class.java)
            .block() ?: throw DomainException(HttpStatus.BAD_GATEWAY, "OAUTH_PROVIDER_ERROR", "OAuth 사용자 정보 조회에 실패했습니다.")
        return mapProfile(provider, objectMapper.readTree(payload))
    }

    private fun exchangeAuthorizationCode(provider: AuthProvider, code: String): String {
        val body = linkedMapOf(
            "grant_type" to "authorization_code",
            "client_id" to clientId(provider),
            "redirect_uri" to callbackUrl(provider),
            "code" to code,
        )
        clientSecret(provider).takeIf { it.isNotBlank() }?.let { body["client_secret"] = it }
        val payload = webClient.post()
            .uri(tokenUrl(provider))
            .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
            .body(BodyInserters.fromFormData(body.entries.fold(org.springframework.util.LinkedMultiValueMap<String, String>()) { acc, entry ->
                acc.add(entry.key, entry.value)
                acc
            }))
            .retrieve()
            .bodyToMono(String::class.java)
            .block() ?: throw DomainException(HttpStatus.BAD_GATEWAY, "OAUTH_PROVIDER_ERROR", "OAuth 토큰 교환에 실패했습니다.")
        val token = objectMapper.readTree(payload).path("access_token").asText("")
        if (token.isBlank()) {
            throw DomainException(HttpStatus.BAD_GATEWAY, "OAUTH_PROVIDER_ERROR", "OAuth access token이 응답에 없습니다.")
        }
        return token
    }

    private fun mapProfile(provider: AuthProvider, payload: JsonNode): OAuthProfile {
        return if (provider == AuthProvider.KAKAO) {
            val account = payload.path("kakao_account")
            val profile = account.path("profile")
            OAuthProfile(
                providerUserId = payload.path("id").asText(),
                nickname = profile.path("nickname").asText("kakao-user"),
                email = account.path("email").asText(null)?.lowercase(),
                profileImageUrl = profile.path("profile_image_url").asText(null),
            )
        } else {
            OAuthProfile(
                providerUserId = payload.path("sub").asText(),
                nickname = payload.path("name").asText(payload.path("email").asText("google-user").substringBefore("@")),
                email = payload.path("email").asText(null)?.lowercase(),
                profileImageUrl = payload.path("picture").asText(null),
            )
        }
    }

    private fun upsertOAuthUser(provider: AuthProvider, profile: OAuthProfile): User {
        val existing = userRepository.findByAuthProviderAndProviderUserId(provider, profile.providerUserId)
        if (existing != null) {
            existing.nickname = profile.nickname
            existing.email = profile.email
            existing.profileImageUrl = profile.profileImageUrl
            existing.isGuest = false
            existing.delYn = YnFlag.N
            return existing
        }
        return userRepository.save(
            User(
                nickname = profile.nickname,
                email = profile.email,
                authProvider = provider,
                providerUserId = profile.providerUserId,
                profileImageUrl = profile.profileImageUrl,
                isGuest = false,
            )
        )
    }

    private fun sanitizeRedirectPath(path: String?): String = path?.takeIf { it.startsWith("/") } ?: "/rooms/new"
    private fun frontendRedirect(path: String, provider: AuthProvider): String = URI.create(frontendBaseUrl + path).toString() + "?login=success&provider=${provider.name.lowercase()}"
    private fun callbackUrl(provider: AuthProvider): String {
        val baseUrl = oauthCallbackBaseUrl.trimEnd('/')
        return when (provider) {
            AuthProvider.KAKAO -> "$baseUrl/api/auth/kakao/callback"
            else -> "$baseUrl/api/auth/google/callback"
        }
    }
    private fun authorizeUrl(provider: AuthProvider) = if (provider == AuthProvider.KAKAO) kakaoAuthorizeUrl else googleAuthorizeUrl
    private fun tokenUrl(provider: AuthProvider) = if (provider == AuthProvider.KAKAO) kakaoTokenUrl else googleTokenUrl
    private fun userInfoUrl(provider: AuthProvider) = if (provider == AuthProvider.KAKAO) kakaoUserInfoUrl else googleUserInfoUrl
    private fun clientId(provider: AuthProvider) = if (provider == AuthProvider.KAKAO) kakaoClientId else googleClientId
    private fun clientSecret(provider: AuthProvider) = if (provider == AuthProvider.KAKAO) kakaoClientSecret else googleClientSecret
    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
    private fun query(params: Map<String, String>) = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
}
