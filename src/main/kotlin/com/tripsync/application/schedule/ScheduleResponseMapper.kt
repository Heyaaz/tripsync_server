package com.tripsync.application.schedule

import com.tripsync.application.consensus.ScheduleOptionDraft
import com.tripsync.domain.entity.ExternalPopularityMetric
import com.tripsync.domain.entity.Place
import com.tripsync.domain.entity.Schedule
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.ExternalPopularityMetricRepository
import com.tripsync.domain.repository.RoomMemberProfileRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ScheduleResponseMapper(
    private val roomMemberProfileRepository: RoomMemberProfileRepository,
    private val externalPopularityMetricRepository: ExternalPopularityMetricRepository,
    @Value("\${api.base-url:http://localhost:8080/api}")
    private val apiBaseUrl: String,
) {
    fun formatGeneratedOption(
        scheduleId: Long,
        option: ScheduleOptionDraft,
        personaValidation: Map<String, Any>?,
        memberNicknames: Map<Long, String>,
        placesById: Map<Long, Place>,
    ): Map<String, Any?> {
        val metricsByPlaceId = loadMetricsByPlaceId(option.slots.map { it.placeId })
        return mapOf(
            "scheduleId" to scheduleId,
            "optionType" to option.optionType.name.lowercase(),
            "label" to option.label,
            "summary" to option.summary,
            "groupSatisfaction" to option.groupSatisfaction,
            "personaValidation" to personaValidation,
            "llmProvider" to option.llmProvider,
            "llmAttemptedProvider" to option.llmAttemptedProvider,
            "llmLatencyMs" to option.llmLatencyMs,
            "fallbackUsed" to option.fallbackUsed,
            "llmFallbackReason" to option.llmFallbackReason,
            "slots" to option.slots.sortedBy { it.orderIndex }.map { slot ->
                val place = placesById[slot.placeId]
                mapOf(
                    "slotId" to null,
                    "orderIndex" to slot.orderIndex,
                    "startTime" to slot.startTime.toString(),
                    "endTime" to slot.endTime.toString(),
                    "slotType" to slot.slotType.name.lowercase(),
                    "targetUserId" to slot.targetUserId,
                    "targetNickname" to slot.targetUserId?.let { memberNicknames[it] },
                    "reasonAxis" to slot.reasonAxis.name.lowercase(),
                    "reasonText" to slot.reasonText,
                    "reason" to slot.reasonText,
                    "place" to formatPlace(place, slot.placeId, slot.placeName, slot.placeAddress, metricsByPlaceId[slot.placeId]),
                )
            },
            "satisfactionByUser" to option.satisfactionByUser.map {
                mapOf(
                    "userId" to it.userId,
                    "nickname" to memberNicknames[it.userId],
                    "score" to it.score,
                )
            },
        )
    }

    fun formatStoredSchedule(schedule: Schedule): Map<String, Any?> {
        val memberNicknames = roomMemberProfileRepository.findAllByRoomIdAndDelYn(schedule.room.id, YnFlag.N)
            .associate { it.user.id to it.user.nickname }
        val llmMetadata = formatLlmMetadata(schedule)
        val activeSlots = schedule.slots.filter { it.delYn == YnFlag.N }.sortedBy { slot -> slot.orderIndex }
        val metricsByPlaceId = loadMetricsByPlaceId(activeSlots.map { it.place.id })
        return mapOf(
            "id" to schedule.id,
            "roomId" to schedule.room.id,
            "destination" to schedule.room.destination,
            "tripDate" to schedule.room.tripStartDate.toString(),
            "tripStartDate" to schedule.room.tripStartDate.toString(),
            "tripEndDate" to schedule.room.tripEndDate.toString(),
            "version" to schedule.version,
            "optionType" to schedule.optionType.name.lowercase(),
            "isConfirmed" to schedule.isConfirmed,
            "groupSatisfaction" to schedule.groupSatisfaction,
            "summary" to (schedule.summary ?: ""),
            "personaValidation" to schedule.personaValidation,
            "llmProvider" to llmMetadata["provider"],
            "llmAttemptedProvider" to llmMetadata["attemptedProvider"],
            "llmLatencyMs" to llmMetadata["latencyMs"],
            "fallbackUsed" to llmMetadata["fallbackUsed"],
            "llmFallbackReason" to llmMetadata["fallbackReason"],
            "slots" to activeSlots.map { slot ->
                mapOf(
                    "slotId" to slot.id,
                    "orderIndex" to slot.orderIndex,
                    "startTime" to slot.startTime.toString(),
                    "endTime" to slot.endTime.toString(),
                    "slotType" to slot.slotType.name.lowercase(),
                    "targetUserId" to slot.targetUser?.id,
                    "targetNickname" to slot.targetUser?.id?.let { memberNicknames[it] },
                    "reasonAxis" to slot.reasonAxis.name.lowercase(),
                    "reasonText" to slot.reasonText,
                    "reason" to slot.reasonText,
                    "place" to formatPlace(slot.place, slot.place.id, slot.place.name, slot.place.address, metricsByPlaceId[slot.place.id]),
                )
            },
            "satisfactionByUser" to schedule.satisfactionScores.filter { it.delYn == YnFlag.N }.map { score ->
                mapOf(
                    "userId" to score.user.id,
                    "nickname" to memberNicknames[score.user.id],
                    "score" to score.score,
                )
            },
        )
    }

    fun formatPlaces(places: List<Place>): List<Map<String, Any?>> {
        val metricsByPlaceId = loadMetricsByPlaceId(places.map { it.id })
        return places.map { place ->
            formatPlace(place, place.id, place.name, place.address, metricsByPlaceId[place.id])
        }
    }

    fun formatPublicShareSchedule(schedule: Schedule): Map<String, Any?> {
        val publicKeys = setOf(
            "id",
            "roomId",
            "destination",
            "tripDate",
            "tripStartDate",
            "tripEndDate",
            "version",
            "optionType",
            "isConfirmed",
            "groupSatisfaction",
            "summary",
            "personaValidation",
            "slots",
            "satisfactionByUser",
        )
        return formatStoredSchedule(schedule).filterKeys { it in publicKeys }
    }

    @Suppress("UNCHECKED_CAST")
    private fun formatLlmMetadata(schedule: Schedule): Map<String, Any?> {
        val metadata = schedule.generationInput["llm"] as? Map<String, Any?> ?: emptyMap()
        val provider = schedule.llmProvider ?: metadata["provider"]
        return mapOf(
            "provider" to provider,
            "attemptedProvider" to (metadata["attemptedProvider"] ?: provider),
            "latencyMs" to metadata["latencyMs"],
            "fallbackUsed" to (metadata["fallbackUsed"] ?: (provider == "deterministic-consensus")),
            "fallbackReason" to metadata["fallbackReason"],
        )
    }

    fun formatPlace(place: Place?, id: Long, name: String, address: String): Map<String, Any?> {
        val metric = externalPopularityMetricRepository.findByPlaceId(id)
        return formatPlace(place, id, name, address, metric)
    }

    private fun formatPlace(place: Place?, id: Long, name: String, address: String, metric: ExternalPopularityMetric?): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "address" to address,
        "category" to place?.category,
        "latitude" to place?.latitude?.toDouble(),
        "longitude" to place?.longitude?.toDouble(),
        "isDepopulationArea" to isDepopulationArea(place?.metadataTags),
    ) + formatExternalSignals(place, id, metric)

    fun isDepopulationArea(metadataTags: Map<String, Any>?): Boolean {
        return metadataTags?.get("populationDeclineArea") == true || metadataTags?.get("regionType") == "population_decline"
    }

    private fun formatExternalSignals(place: Place?, placeId: Long, metric: ExternalPopularityMetric?): Map<String, Any?> {
        val image = formatImage(place, metric, placeId)
        return mapOf(
            "imageUrl" to image.url,
            "imageSource" to image.source,
            "isRegionalBenefit" to isRegionalBenefit(place?.metadataTags, metric),
            "popularity" to formatPopularity(metric, place?.metadataTags),
        )
    }

    private fun loadMetricsByPlaceId(placeIds: Collection<Long>): Map<Long, ExternalPopularityMetric> {
        val ids = placeIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        return externalPopularityMetricRepository.findByPlaceIdIn(ids).associateBy { it.place.id }
    }

    private fun formatImage(place: Place?, metric: ExternalPopularityMetric?, placeId: Long): PlaceImage {
        val tourApiImage = place?.imageUrl?.trim()?.takeIf { it.isNotBlank() }
        if (tourApiImage != null) return PlaceImage(tourApiImage, "tourapi")
        val hasGooglePhoto = metric?.googlePhotoReference?.isNotBlank() == true
        return if (hasGooglePhoto) {
            PlaceImage("${apiBaseUrl.trimEnd('/')}/places/$placeId/photo", "google_places")
        } else {
            PlaceImage(null, null)
        }
    }

    private fun formatPopularity(metric: ExternalPopularityMetric?, metadataTags: Map<String, Any>?): Map<String, Any?> {
        val score = metric?.normalizedPopularityScore
        val role = when {
            score != null && score >= 70 -> "popular_anchor"
            isRegionalBenefit(metadataTags, metric) -> "regional_benefit"
            score == null -> "unverified"
            else -> "balanced"
        }
        val label = when (role) {
            "popular_anchor" -> "많이 찾는 대표 장소"
            "regional_benefit" -> "지역상생 추천 장소"
            "unverified" -> "외부 신호 미확인"
            else -> "취향 균형 장소"
        }
        return mapOf(
            "role" to role,
            "label" to label,
            "hasExternalSignal" to (score != null),
        )
    }

    private fun isRegionalBenefit(metadataTags: Map<String, Any>?, metric: ExternalPopularityMetric?): Boolean {
        val tagList = metadataTags?.get("tags") as? List<*>
        return tagList?.any { it in listOf("hidden_gem", "population_decline", "regional_benefit") } == true ||
            metadataTags?.get("hiddenGem") == true ||
            metadataTags?.get("populationDeclineArea") == true ||
            metadataTags?.get("regionalBenefit") == true ||
            metadataTags?.get("regionType") == "population_decline" ||
            ((metric?.normalizedPopularityScore ?: 101) <= 35)
    }

    private data class PlaceImage(
        val url: String?,
        val source: String?,
    )
}
