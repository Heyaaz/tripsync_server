package com.tripsync.infrastructure.popularity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.math.BigDecimal

@Component
class GooglePlacesClient(
    private val webClient: WebClient,
    private val properties: ExternalPopularityProperties,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun searchPlace(query: String, latitude: BigDecimal, longitude: BigDecimal): List<GooglePlaceCandidate> {
        val apiKey = properties.google.apiKey
        if (apiKey.isBlank()) {
            logger.warn { "Google Places API key not configured" }
            return emptyList()
        }

        val response = webClient.get()
            .uri { builder ->
                builder
                    .scheme("https")
                    .host("maps.googleapis.com")
                    .path("/maps/api/place/textsearch/json")
                    .queryParam("query", query)
                    .queryParam("location", "${latitude.toPlainString()},${longitude.toPlainString()}")
                    .queryParam("radius", properties.google.matchRadiusMeters)
                    .queryParam("language", "ko")
                    .queryParam("key", apiKey)
                    .build()
            }
            .retrieve()
            .bodyToMono<GoogleTextSearchResponse>()
            .awaitSingle()

        return response.results
    }

    suspend fun fetchPhoto(photoReference: String): GooglePlacePhoto {
        val apiKey = properties.google.apiKey
        if (apiKey.isBlank()) {
            throw IllegalStateException("Google Places API key not configured")
        }

        val entity = webClient.get()
            .uri { builder ->
                builder
                    .scheme("https")
                    .host("maps.googleapis.com")
                    .path("/maps/api/place/photo")
                    .queryParam("maxwidth", properties.google.photoMaxWidth)
                    .queryParam("photo_reference", photoReference)
                    .queryParam("key", apiKey)
                    .build()
            }
            .retrieve()
            .toEntity(ByteArray::class.java)
            .awaitSingle()

        val contentType = entity.headers.contentType ?: MediaType.IMAGE_JPEG
        return GooglePlacePhoto(
            bytes = entity.body ?: ByteArray(0),
            contentType = contentType,
            cacheControl = entity.headers[HttpHeaders.CACHE_CONTROL]?.firstOrNull(),
        )
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class GoogleTextSearchResponse(
    val results: List<GooglePlaceCandidate> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GooglePlaceCandidate(
    val place_id: String? = null,
    val name: String? = null,
    val formatted_address: String? = null,
    val geometry: GoogleGeometry? = null,
    val rating: BigDecimal? = null,
    val user_ratings_total: Int? = null,
    val photos: List<GooglePlacePhotoReference> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GoogleGeometry(
    val location: GoogleLocation? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GoogleLocation(
    val lat: BigDecimal? = null,
    val lng: BigDecimal? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GooglePlacePhotoReference(
    val photo_reference: String? = null,
)

data class GooglePlacePhoto(
    val bytes: ByteArray,
    val contentType: MediaType,
    val cacheControl: String?,
)
