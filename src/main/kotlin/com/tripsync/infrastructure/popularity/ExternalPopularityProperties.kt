package com.tripsync.infrastructure.popularity

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "external-popularity")
data class ExternalPopularityProperties(
    val sync: Sync = Sync(),
    val naver: Naver = Naver(),
    val google: Google = Google(),
) {
    data class Sync(
        val enabled: Boolean = true,
        val batchLimit: Int = 500,
        val requestIntervalMillis: Long = 0,
        val expiresAfterDays: Long = 14,
    )

    data class Naver(
        val clientId: String = "",
        val clientSecret: String = "",
        val searchUrl: String = "https://openapi.naver.com/v1/datalab/search",
        val lookbackDays: Int = 30,
    )

    data class Google(
        val apiKey: String = "",
        val matchRadiusMeters: Int = 500,
        val minNameSimilarity: Double = 0.45,
        val photoMaxWidth: Int = 900,
    )
}
