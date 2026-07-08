package com.tripsync.application.schedule

import com.tripsync.domain.entity.AxisScores
import com.tripsync.application.consensus.MemberSnapshot
import com.tripsync.application.consensus.PlaceCandidate
import com.tripsync.application.consensus.ScheduleOptionDraft
import com.tripsync.application.consensus.ScheduleSlotDraft
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.Place
import com.tripsync.domain.entity.SatisfactionScore
import com.tripsync.domain.entity.Schedule
import com.tripsync.domain.entity.ScheduleSlot
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.ScheduleOptionType
import com.tripsync.domain.enums.TripRoomStatus
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.PlaceQueryRepository
import com.tripsync.domain.repository.PlaceRepository
import com.tripsync.domain.repository.ExternalPopularityMetricRepository
import com.tripsync.domain.repository.RoomMemberProfileRepository
import com.tripsync.domain.repository.SatisfactionScoreRepository
import com.tripsync.domain.repository.ScheduleRepository
import com.tripsync.domain.repository.ScheduleSlotRepository
import com.tripsync.domain.repository.TripRoomRepository
import com.tripsync.domain.repository.UserRepository
import com.tripsync.web.dto.GenerateScheduleDto
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ScheduleGenerationPersistenceService(
    private val scheduleRepository: ScheduleRepository,
    private val scheduleSlotRepository: ScheduleSlotRepository,
    private val satisfactionScoreRepository: SatisfactionScoreRepository,
    private val roomMemberProfileRepository: RoomMemberProfileRepository,
    private val placeRepository: PlaceRepository,
    private val placeQueryRepository: PlaceQueryRepository,
    private val externalPopularityMetricRepository: ExternalPopularityMetricRepository,
    private val userRepository: UserRepository,
    private val tripRoomRepository: TripRoomRepository,
    private val accessPolicy: ScheduleAccessPolicy,
) {
    @Transactional(readOnly = true)
    fun loadGenerationContext(roomId: Long, hostId: Long, destination: String): ScheduleGenerationContext {
        accessPolicy.validateHost(roomId, hostId)
        accessPolicy.getActiveRoom(roomId)
        val profiles = roomMemberProfileRepository.findAllByRoomIdAndDelYn(roomId, YnFlag.N)
            .sortedBy { it.createdAt }
        if (profiles.size < 2) {
            throw DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "ROOM_NOT_READY", "일정 생성 가능한 상태가 아닙니다.")
        }

        val members = profiles.mapIndexed { index, profile ->
            MemberSnapshot(
                userId = profile.user.id,
                nickname = profile.user.nickname,
                scores = AxisScores(
                    mobility = profile.mobilityScore,
                    photo = profile.photoScore,
                    budget = profile.budgetScore,
                    theme = profile.themeScore,
                ),
                joinedOrder = index,
            )
        }

        val places = placeQueryRepository.findScheduleCandidates(destination)
        val metricsByPlaceId = externalPopularityMetricRepository.findByPlaceIdIn(places.map { it.id })
            .associateBy { it.place.id }
        val placeCandidates = places.map {
            val metric = metricsByPlaceId[it.id]
            PlaceCandidate(
                id = it.id,
                name = it.name,
                address = it.address,
                latitude = it.latitude.toDouble(),
                longitude = it.longitude.toDouble(),
                category = it.category,
                mobilityScore = it.mobilityScore,
                photoScore = it.photoScore,
                budgetScore = it.budgetScore,
                themeScore = it.themeScore,
                metadataTags = it.metadataTags,
                operatingHours = it.operatingHours,
                externalPopularityScore = metric?.normalizedPopularityScore,
                externalSignalConfidence = externalSignalConfidence(metric),
                isRegionalBenefit = isRegionalBenefit(it.metadataTags, metric?.normalizedPopularityScore),
            )
        }

        return ScheduleGenerationContext(
            roomId = roomId,
            members = members,
            places = placeCandidates,
            placesById = places.associateBy { it.id },
        )
    }

    @Transactional
    fun saveGeneratedOptions(
        roomId: Long,
        dto: GenerateScheduleDto,
        options: List<ScheduleOptionDraft>,
        personaValidationByType: Map<ScheduleOptionType, Map<String, Any>>,
    ): SavedScheduleGeneration {
        val room = tripRoomRepository.findActiveByIdForUpdate(roomId, YnFlag.N)
            ?: throw DomainException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "존재하지 않는 방입니다.")
        val version = (scheduleRepository.findTopByRoomIdAndDelYnOrderByVersionDescIdDesc(room.id, YnFlag.N)?.version ?: 0) + 1
        val replacementCandidates = placeQueryRepository.findScheduleCandidates(dto.destination)
        val repairedOptions = options.map { rawOption ->
            rawOption.copy(slots = ensureUniqueSlots(rawOption.slots, replacementCandidates))
        }
        val placesById = loadActivePlacesById(repairedOptions.flatMap { option -> option.slots.map { it.placeId } })
        val usersById = loadUsersById(
            repairedOptions.flatMap { option ->
                option.slots.mapNotNull { it.targetUserId } + option.satisfactionByUser.map { it.userId }
            }
        )

        val saved = repairedOptions.map { option ->
            val personaValidation = personaValidationByType[option.optionType]
            val schedule = scheduleRepository.save(
                Schedule(
                    room = room,
                    version = version,
                    optionType = option.optionType,
                    generationInput = mapOf(
                        "destination" to dto.destination,
                        "tripDate" to dto.tripDate,
                        "startTime" to dto.startTime,
                        "endTime" to dto.endTime,
                        "tripStartDate" to (dto.tripStartDate ?: dto.tripDate),
                        "tripEndDate" to (dto.tripEndDate ?: dto.tripDate),
                        "llm" to mapOf(
                            "provider" to option.llmProvider,
                            "attemptedProvider" to option.llmAttemptedProvider,
                            "latencyMs" to option.llmLatencyMs,
                            "fallbackUsed" to option.fallbackUsed,
                            "fallbackReason" to option.llmFallbackReason,
                        ),
                    ),
                    summary = option.summary,
                    groupSatisfaction = option.groupSatisfaction,
                    personaValidation = personaValidation,
                    llmProvider = option.llmProvider,
                )
            )

            scheduleSlotRepository.saveAll(
                option.slots.map { slot ->
                    val place = placesById[slot.placeId]
                        ?: throw DomainException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다.")
                    ScheduleSlot(
                        schedule = schedule,
                        startTime = slot.startTime,
                        endTime = slot.endTime,
                        place = place,
                        slotType = slot.slotType,
                        targetUser = slot.targetUserId?.let { usersById[it] },
                        reasonAxis = slot.reasonAxis,
                        reasonText = slot.reasonText,
                        orderIndex = slot.orderIndex,
                    )
                }
            )

            satisfactionScoreRepository.saveAll(
                option.satisfactionByUser.map { sat ->
                    val user = usersById[sat.userId]
                        ?: throw DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다: ${sat.userId}")
                    SatisfactionScore(
                        schedule = schedule,
                        user = user,
                        score = sat.score,
                        breakdown = mapOf(
                            "overall" to sat.score,
                            "byAxis" to sat.breakdown.byAxis.mapKeys { it.key.name.lowercase() },
                        ),
                    )
                }
            )

            SavedScheduleOption(
                scheduleId = schedule.id,
                option = option,
                personaValidation = personaValidation,
            )
        }
        return SavedScheduleGeneration(version = version, options = saved)
    }
    private fun loadActivePlacesById(placeIds: List<Long>): Map<Long, Place> {
        val uniqueIds = placeIds.distinct()
        if (uniqueIds.isEmpty()) return emptyMap()
        val placesById = placeRepository.findAllById(uniqueIds)
            .filter { it.delYn == YnFlag.N }
            .associateBy { it.id }
        if (placesById.size != uniqueIds.size) {
            throw DomainException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다.")
        }
        return placesById
    }

    private fun loadUsersById(userIds: List<Long>): Map<Long, User> {
        val uniqueIds = userIds.distinct()
        if (uniqueIds.isEmpty()) return emptyMap()
        return userRepository.findAllById(uniqueIds).associateBy { it.id }
    }

    private fun ensureUniqueSlots(slots: List<ScheduleSlotDraft>, replacementCandidates: List<Place>): List<ScheduleSlotDraft> {
        if (slots.isEmpty()) return slots

        val candidates = replacementCandidates
            .filter { it.delYn == YnFlag.N }
            .distinctBy { it.id }
        val usedPlaceIds = mutableSetOf<Long>()
        val usedPlaceKeys = mutableSetOf<String>()

        return slots.map { slot ->
            val slotKeys = placeKeys(slot.placeName, slot.placeAddress)
            val isDuplicate = slot.placeId in usedPlaceIds || slotKeys.any { it in usedPlaceKeys }
            val uniqueSlot = if (isDuplicate) {
                candidates.firstOrNull { candidate ->
                    candidate.id !in usedPlaceIds && placeKeys(candidate.name, candidate.address).none { it in usedPlaceKeys }
                }?.let { replacement ->
                    slot.copy(
                        placeId = replacement.id,
                        placeName = replacement.name,
                        placeAddress = replacement.address,
                        isHiddenGem = isHiddenGem(replacement),
                    )
                } ?: throw DomainException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INSUFFICIENT_UNIQUE_PLACES",
                    "중복 장소를 대체할 수 있는 후보가 부족합니다.",
                )
            } else {
                slot
            }

            usedPlaceIds.add(uniqueSlot.placeId)
            usedPlaceKeys.addAll(placeKeys(uniqueSlot.placeName, uniqueSlot.placeAddress))
            uniqueSlot
        }
    }

    private fun isHiddenGem(place: Place): Boolean {
        return place.metadataTags?.get("hiddenGem") == true ||
            place.metadataTags?.get("populationDeclineArea") == true ||
            place.metadataTags?.get("regionalBenefit") == true ||
            place.metadataTags?.get("regionType") == "population_decline"
    }

    private fun placeKeys(name: String, address: String): Set<String> {
        val normalizedName = normalizePlaceText(name)
        val normalizedAddress = normalizePlaceText(address)
        return setOf(normalizedName, "$normalizedName|$normalizedAddress").filter { it.isNotBlank() }.toSet()
    }

    private fun normalizePlaceText(value: String): String {
        return value.trim().lowercase().replace(Regex("[\\s\\p{Punct}]+"), "")
    }


    @Transactional(readOnly = true)
    fun getRoomIdForRegeneration(scheduleId: Long, userId: Long): Long {
        val existing = accessPolicy.getActiveSchedule(scheduleId)
        val roomId = existing.room.id
        accessPolicy.validateHost(roomId, userId)
        return roomId
    }

    private fun externalSignalConfidence(metric: com.tripsync.domain.entity.ExternalPopularityMetric?): Int {
        if (metric == null || metric.normalizedPopularityScore == null) return 0
        var confidence = 40
        if (metric.naverSearchTrendScore != null) confidence += 25
        if ((metric.googleUserRatingCount ?: 0) > 0) confidence += 25
        if (metric.googleRating != null) confidence += 10
        return confidence.coerceIn(0, 100)
    }

    private fun isRegionalBenefit(metadataTags: Map<String, Any>?, externalPopularityScore: Int?): Boolean {
        val tagList = metadataTags?.get("tags") as? List<*>
        val tagged = tagList?.any { it in listOf("hidden_gem", "population_decline", "regional_benefit") } == true ||
            metadataTags?.get("hiddenGem") == true ||
            metadataTags?.get("populationDeclineArea") == true ||
            metadataTags?.get("regionalBenefit") == true ||
            metadataTags?.get("regionType") == "population_decline"
        return tagged || (externalPopularityScore != null && externalPopularityScore <= 35)
    }
}

data class ScheduleGenerationContext(
    val roomId: Long,
    val members: List<MemberSnapshot>,
    val places: List<PlaceCandidate>,
    val placesById: Map<Long, Place>,
)

data class SavedScheduleGeneration(
    val version: Int,
    val options: List<SavedScheduleOption>,
)

data class SavedScheduleOption(
    val scheduleId: Long,
    val option: ScheduleOptionDraft,
    val personaValidation: Map<String, Any>?,
)
