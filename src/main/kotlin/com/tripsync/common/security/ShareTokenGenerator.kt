package com.tripsync.common.security

import java.security.SecureRandom
import java.util.Base64

object ShareTokenGenerator {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun generate(byteLength: Int = 18): String {
        val bytes = ByteArray(byteLength)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }
}
