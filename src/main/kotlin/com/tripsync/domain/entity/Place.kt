package com.tripsync.domain.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "places")
class Place(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "tour_api_id", nullable = false, length = 100, unique = true)
    var tourApiId: String,

    @Column(nullable = false, length = 255)
    var name: String,

    @Column(nullable = false, length = 255)
    var address: String,

    @Column(nullable = false, precision = 10, scale = 7)
    var latitude: BigDecimal,

    @Column(nullable = false, precision = 10, scale = 7)
    var longitude: BigDecimal,

    @Column(nullable = false, length = 100)
    var category: String,

    @Column(name = "image_url", columnDefinition = "TEXT")
    var imageUrl: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "operating_hours", columnDefinition = "jsonb")
    var operatingHours: Map<String, Any>? = null,

    @Column(name = "admission_fee", length = 100)
    var admissionFee: String? = null,

    @Column(name = "mobility_score", nullable = false)
    var mobilityScore: Int,

    @Column(name = "photo_score", nullable = false)
    var photoScore: Int,

    @Column(name = "budget_score", nullable = false)
    var budgetScore: Int,

    @Column(name = "theme_score", nullable = false)
    var themeScore: Int,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_tags", columnDefinition = "jsonb")
    var metadataTags: Map<String, Any>? = null,
) : BaseEntity() {
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
