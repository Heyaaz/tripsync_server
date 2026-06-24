package com.tripsync.application.auth

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.*
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    @Value("\${jwt.secret}")
    private val secret: String,
    @Value("\${jwt.expiration:604800000}")
    private val expiration: Long,
    private val environment: Environment,
) {
    companion object {
        private const val MIN_HS256_KEY_BYTES = 32
        private val DEV_PROFILES = setOf("local", "test")
        private val WEAK_DEFAULT_SECRETS = setOf(
            "your-256-bit-secret-key-here-for-development-only",
            "local-development-secret-key-256-bits-min",
            "test-development-secret-key-256-bits-min",
        )
    }
    private val key: SecretKey by lazy {
        validateSecret()
        val rawBytes = secret.toByteArray(Charsets.UTF_8)
        val keyBytes = if (rawBytes.size >= MIN_HS256_KEY_BYTES) {
            rawBytes
        } else {
            MessageDigest.getInstance("SHA-256").digest(rawBytes)
        }
        Keys.hmacShaKeyFor(keyBytes)
    }

    @PostConstruct
    fun validateConfiguration() {
        validateSecret()
    }

    fun generateToken(userId: Long, isGuest: Boolean): String {
        val now = Date()
        val expiry = Date(now.time + expiration)

        return Jwts.builder()
            .subject(userId.toString())
            .claim("isGuest", isGuest)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(key)
            .compact()
    }

    fun validateToken(token: String): Boolean {
        return try {
            parseClaims(token)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getUserId(token: String): Long {
        return parseClaims(token).subject.toLong()
    }

    fun isGuest(token: String): Boolean {
        return parseClaims(token)["isGuest"] as? Boolean ?: false
    }

    private fun parseClaims(token: String): Claims {
        return Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
    }

    private fun validateSecret() {
        val normalized = secret.trim()
        require(normalized.isNotBlank()) { "JWT_SECRET must be configured" }
        val activeProfiles = environment.activeProfiles.toSet()
        val isDevProfile = activeProfiles.any { it in DEV_PROFILES }
        require(isDevProfile || normalized !in WEAK_DEFAULT_SECRETS) {
            "JWT_SECRET must not use a development default outside local/test profiles"
        }
    }
}
