package com.tripsync.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "external_popularity_metrics")
class ExternalPopularityMetric(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_id", nullable = false)
    var place: Place,

    @Column(name = "naver_search_trend_score")
    var naverSearchTrendScore: Int? = null,

    @Column(name = "google_place_id", length = 255)
    var googlePlaceId: String? = null,

    @Column(name = "google_rating", precision = 2, scale = 1)
    var googleRating: BigDecimal? = null,

    @Column(name = "google_user_rating_count")
    var googleUserRatingCount: Int? = null,

    @Column(name = "google_photo_reference", columnDefinition = "TEXT")
    var googlePhotoReference: String? = null,

    @Column(name = "normalized_popularity_score")
    var normalizedPopularityScore: Int? = null,

    @Column(name = "collected_at", nullable = false)
    var collectedAt: Instant,

    @Column(name = "expires_at")
    var expiresAt: Instant? = null,

    @Column(name = "last_error", columnDefinition = "TEXT")
    var lastError: String? = null,
) {
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
