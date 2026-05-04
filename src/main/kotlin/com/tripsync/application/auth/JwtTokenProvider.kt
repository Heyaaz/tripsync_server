package com.tripsync.application.auth

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
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
) {
    companion object {
        private const val MIN_HS256_KEY_BYTES = 32
    }
    private val key: SecretKey by lazy {
        val rawBytes = secret.toByteArray(Charsets.UTF_8)
        val keyBytes = if (rawBytes.size >= MIN_HS256_KEY_BYTES) {
            rawBytes
        } else {
            MessageDigest.getInstance("SHA-256").digest(rawBytes)
        }
        Keys.hmacShaKeyFor(keyBytes)
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
}
