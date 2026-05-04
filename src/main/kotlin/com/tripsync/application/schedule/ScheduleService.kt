package com.tripsync.application.schedule

import com.tripsync.application.consensus.*
import com.tripsync.application.persona.PersonaValidationService
import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.*
import com.tripsync.domain.enums.ScheduleOptionType
import com.tripsync.domain.enums.TripRoomStatus
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.*
import com.tripsync.web.dto.GenerateScheduleDto
import com.tripsync.web.dto.RegenerateScheduleDto
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

@Service
class ScheduleService(
    private val scheduleRepository: ScheduleRepository,
    private val scheduleSlotRepository: ScheduleSlotRepository,
    private val satisfactionScoreRepository: SatisfactionScoreRepository,
    private val tripRoomRepository: TripRoomRepository,
    private val roomMemberRepository: RoomMemberRepository,
    private val roomMemberProfileRepository: RoomMemberProfileRepository,
    private val placeRepository: PlaceRepository,
    private val userRepository: UserRepository,
    private val consensusService: ConsensusService,
    private val personaValidationService: PersonaValidationService,
) {
    private val logger = KotlinLogging.logger {}

    @Transactional
    fun generateSchedule(roomId: Long, hostId: Long, dto: GenerateScheduleDto): ApiResponse<Map<String, Any?>> {
        validateHost(roomId, hostId)
        val room = getActiveRoom(roomId)
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

        val places = placeRepository.findByDelYn(YnFlag.N)
        val placeCandidates = places.map {
            PlaceCandidate(
                id = it.id,
                name = it.name,
                address = it.address,
                category = it.category,
                mobilityScore = it.mobilityScore,
                photoScore = it.photoScore,
                budgetScore = it.budgetScore,
                themeScore = it.themeScore,
                metadataTags = it.metadataTags,
                operatingHours = it.operatingHours,
            )
        }

        val context = OptionContext(
            roomId = roomId,
            destination = dto.destination,
            tripDate = dto.tripDate,
            startTime = dto.startTime,
            endTime = dto.endTime,
            members = members,
            places = placeCandidates,
        )

        val options = runBlocking { consensusService.buildScheduleOptions(context) }
        val placesById = places.associateBy { it.id }
        val personaValidationByType = safePersonaValidationByType(options, members, placesById)
        val version = (scheduleRepository.findTopByRoomIdAndDelYnOrderByVersionDesc(room.id, YnFlag.N)?.version ?: 0) + 1
        val saved = options.map { option ->
            saveScheduleOption(room, version, dto, option, personaValidationByType[option.optionType])
        }
        room.status = TripRoomStatus.COMPLETED

        val memberNicknames = members.associate { it.userId to it.nickname }
        return ApiResponse.ok(
            mapOf(
                "roomId" to roomId,
                "version" to version,
                "options" to saved.map { (schedule, option) ->
                    formatGeneratedOption(
                        scheduleId = schedule.id,
                        option = option,
                        personaValidation = schedule.personaValidation,
                        memberNicknames = memberNicknames,
                        placesById = placesById,
                    )
                },
            )
        )
    }

    @Transactional(readOnly = true)
    fun getSchedule(scheduleId: Long, userId: Long): ApiResponse<Map<String, Any?>> {
        val schedule = getActiveSchedule(scheduleId)
        validateRoomMember(schedule.room.id, userId)
        return ApiResponse.ok(formatStoredSchedule(schedule))
    }


    @Transactional(readOnly = true)
    fun searchPlacesForSchedule(scheduleId: Long, userId: Long, query: String): ApiResponse<Map<String, Any?>> {
        val schedule = getActiveSchedule(scheduleId)
        validateHost(schedule.room.id, userId)
        validateConfirmedSchedule(schedule)

        val normalizedQuery = query.trim().lowercase()
        val usedPlaceIds = scheduleSlotRepository.findAllByScheduleIdAndDelYn(schedule.id, YnFlag.N)
            .map { it.place.id }
            .toSet()
        val places = placeRepository.findByDelYn(YnFlag.N)
            .asSequence()
            .filter { place ->
                normalizedQuery.isBlank() ||
                    place.name.lowercase().contains(normalizedQuery) ||
                    place.address.lowercase().contains(normalizedQuery) ||
                    place.category.lowercase().contains(normalizedQuery)
            }
            .sortedWith(
                compareByDescending<Place> { isDepopulationArea(it.metadataTags) }
                    .thenBy { it.name }
            )
            .take(30)
            .map { place ->
                formatPlace(place, place.id, place.name, place.address) + mapOf(
                    "alreadyAdded" to usedPlaceIds.contains(place.id),
                )
            }
            .toList()

        return ApiResponse.ok(
            mapOf(
                "places" to places,
                "query" to query.trim(),
            )
        )
    }

    @Transactional
    fun addScheduleSlot(scheduleId: Long, userId: Long, placeId: Long): ApiResponse<Map<String, Any?>> {
        val schedule = getActiveSchedule(scheduleId)
        validateHost(schedule.room.id, userId)
        validateConfirmedSchedule(schedule)

        val place = placeRepository.findById(placeId)
            .orElseThrow { DomainException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다.") }
        if (place.delYn != YnFlag.N) {
            throw DomainException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다.")
        }

        val slots = scheduleSlotRepository.findAllByScheduleIdAndDelYn(schedule.id, YnFlag.N)
        if (slots.any { it.place.id == place.id }) {
            throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "이미 일정에 포함된 장소입니다.")
        }

        val orderedSlots = slots.sortedBy { it.orderIndex }
        val nextOrderIndex = (orderedSlots.maxOfOrNull { it.orderIndex } ?: 0) + 1
        val startTime = orderedSlots.maxByOrNull { it.orderIndex }?.endTime ?: defaultSlotStart(schedule)
        val endTime = startTime.plus(Duration.ofHours(2))

        val newSlot = scheduleSlotRepository.save(
            ScheduleSlot(
                schedule = schedule,
                startTime = startTime,
                endTime = endTime,
                place = place,
                slotType = com.tripsync.domain.enums.SlotType.COMMON,
                targetUser = null,
                reasonAxis = com.tripsync.domain.enums.ReasonAxis.COMMON,
                reasonText = "직접 추가한 장소입니다.",
                orderIndex = nextOrderIndex,
            )
        )
        redistributeSlotsWithinScheduleWindow(schedule, orderedSlots + newSlot)

        return ApiResponse.ok(formatStoredSchedule(schedule))
    }

    @Transactional
    fun reorderScheduleSlots(scheduleId: Long, userId: Long, slotIds: List<Long>): ApiResponse<Map<String, Any?>> {
        val schedule = getActiveSchedule(scheduleId)
        validateHost(schedule.room.id, userId)

        val slots = scheduleSlotRepository.findAllByScheduleIdAndDelYn(schedule.id, YnFlag.N)
        val slotsById = slots.associateBy { it.id }
        val uniqueSlotIds = slotIds.distinct()
        if (uniqueSlotIds.size != slotIds.size || uniqueSlotIds.size != slots.size || uniqueSlotIds.any { slotsById[it] == null }) {
            throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "일정에 포함된 모든 슬롯 ID를 중복 없이 전달해야 합니다.")
        }

        uniqueSlotIds.forEachIndexed { index, slotId ->
            slotsById.getValue(slotId).orderIndex = index + 1
        }

        return ApiResponse.ok(formatStoredSchedule(schedule))
    }

    @Transactional
    fun confirmSchedule(roomId: Long, hostId: Long, optionType: String): ApiResponse<Map<String, Any?>> {
        validateHost(roomId, hostId)
        val type = parseOptionType(optionType)
        val latest = scheduleRepository.findTopByRoomIdAndDelYnOrderByVersionDesc(roomId, YnFlag.N)
            ?: throw DomainException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "확정할 일정 옵션이 없습니다.")
        val target = scheduleRepository.findByRoomIdAndDelYn(roomId, YnFlag.N)
            .firstOrNull { it.version == latest.version && it.optionType == type }
            ?: throw DomainException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "선택한 일정 옵션을 찾을 수 없습니다.")

        scheduleRepository.findByRoomIdAndDelYn(roomId, YnFlag.N).forEach { it.isConfirmed = false }
        target.isConfirmed = true

        return ApiResponse.ok(
            mapOf(
                "scheduleId" to target.id,
                "roomId" to roomId,
                "optionType" to target.optionType.name.lowercase(),
                "status" to "confirmed",
            )
        )
    }

    @Transactional
    fun regenerateSchedule(scheduleId: Long, userId: Long, dto: RegenerateScheduleDto): ApiResponse<Map<String, Any?>> {
        val existing = getActiveSchedule(scheduleId)
        validateHost(existing.room.id, userId)
        val generated = generateSchedule(
            existing.room.id,
            userId,
            GenerateScheduleDto(
                destination = dto.destination,
                tripDate = dto.tripDate,
                startTime = dto.startTime,
                endTime = dto.endTime,
                tripStartDate = dto.tripStartDate,
                tripEndDate = dto.tripEndDate,
            )
        )
        return ApiResponse.ok(
            mapOf(
                "scheduleId" to scheduleId,
                "reason" to (dto.reason ?: ""),
                "tripDate" to dto.tripDate,
                "startTime" to dto.startTime,
                "endTime" to dto.endTime,
                "regenerated" to generated.data,
                "message" to "새 버전 일정이 생성되었습니다.",
            )
        )
    }

    @Transactional(readOnly = true)
    fun getPublicShareSchedule(scheduleId: Long): ApiResponse<Map<String, Any?>> {
        val schedule = getActiveSchedule(scheduleId)
        return ApiResponse.ok(formatStoredSchedule(schedule))
    }

    private fun saveScheduleOption(
        room: TripRoom,
        version: Int,
        dto: GenerateScheduleDto,
        option: ScheduleOptionDraft,
        personaValidation: Map<String, Any>?,
    ): Pair<Schedule, ScheduleOptionDraft> {
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
                ),
                summary = option.summary,
                groupSatisfaction = option.groupSatisfaction,
                personaValidation = personaValidation,
                llmProvider = option.llmProvider,
            )
        )

        option.slots.forEach { slot ->
            val place = placeRepository.findById(slot.placeId)
                .orElseThrow { DomainException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다.") }
            val targetUser = slot.targetUserId?.let { userRepository.findById(it).orElse(null) }
            scheduleSlotRepository.save(
                ScheduleSlot(
                    schedule = schedule,
                    startTime = slot.startTime,
                    endTime = slot.endTime,
                    place = place,
                    slotType = slot.slotType,
                    targetUser = targetUser,
                    reasonAxis = slot.reasonAxis,
                    reasonText = slot.reasonText,
                    orderIndex = slot.orderIndex,
                )
            )
        }

        option.satisfactionByUser.forEach { sat ->
            val user = userRepository.findById(sat.userId)
                .orElseThrow { DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다: ${sat.userId}") }
            satisfactionScoreRepository.save(
                SatisfactionScore(
                    schedule = schedule,
                    user = user,
                    score = sat.score,
                    breakdown = mapOf(
                        "overall" to sat.score,
                        "byAxis" to sat.breakdown.byAxis.mapKeys { it.key.name.lowercase() },
                    ),
                )
            )
        }

        return schedule to option
    }

    private fun formatGeneratedOption(
        scheduleId: Long,
        option: ScheduleOptionDraft,
        personaValidation: Map<String, Any>?,
        memberNicknames: Map<Long, String>,
        placesById: Map<Long, Place>,
    ): Map<String, Any?> = mapOf(
        "scheduleId" to scheduleId,
        "optionType" to option.optionType.name.lowercase(),
        "label" to option.label,
        "summary" to option.summary,
        "groupSatisfaction" to option.groupSatisfaction,
        "personaValidation" to personaValidation,
        "llmProvider" to option.llmProvider,
        "llmLatencyMs" to option.llmLatencyMs,
        "fallbackUsed" to option.fallbackUsed,
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
                "place" to formatPlace(place, slot.placeId, slot.placeName, slot.placeAddress),
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

    private fun formatStoredSchedule(schedule: Schedule): Map<String, Any?> {
        val memberNicknames = roomMemberProfileRepository.findAllByRoomIdAndDelYn(schedule.room.id, YnFlag.N)
            .associate { it.user.id to it.user.nickname }
        return mapOf(
            "id" to schedule.id,
            "roomId" to schedule.room.id,
            "destination" to schedule.room.destination,
            "tripDate" to schedule.room.tripDate.toString(),
            "version" to schedule.version,
            "optionType" to schedule.optionType.name.lowercase(),
            "isConfirmed" to schedule.isConfirmed,
            "groupSatisfaction" to schedule.groupSatisfaction,
            "summary" to (schedule.summary ?: ""),
            "personaValidation" to schedule.personaValidation,
            "slots" to schedule.slots.filter { it.delYn == YnFlag.N }.sortedBy { it.orderIndex }.map { slot ->
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
                    "place" to formatPlace(slot.place, slot.place.id, slot.place.name, slot.place.address),
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

    private fun formatPlace(place: Place?, id: Long, name: String, address: String): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "address" to address,
        "category" to place?.category,
        "latitude" to place?.latitude?.toDouble(),
        "longitude" to place?.longitude?.toDouble(),
        "isDepopulationArea" to isDepopulationArea(place?.metadataTags),
    )

    private fun safePersonaValidationByType(
        options: List<ScheduleOptionDraft>,
        members: List<MemberSnapshot>,
        placesById: Map<Long, Place>,
    ): Map<ScheduleOptionType, Map<String, Any>> {
        return try {
            val personaMembers = members.map {
                PersonaValidationService.MemberSnapshot(
                    userId = it.userId,
                    scores = it.scores,
                )
            }
            val validationOptions = options.map { option ->
                PersonaValidationService.ScheduleOptionForValidation(
                    optionType = option.optionType,
                    groupSatisfaction = option.groupSatisfaction,
                    slots = option.slots.map { slot ->
                        val place = placesById[slot.placeId]
                            ?: throw DomainException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다.")
                        PersonaValidationService.ScheduleSlotForValidation(
                            slotType = slot.slotType,
                            reasonText = slot.reasonText,
                            scores = AxisScores(
                                mobility = place.mobilityScore,
                                photo = place.photoScore,
                                budget = place.budgetScore,
                                theme = place.themeScore,
                            ),
                        )
                    },
                    satisfactionByUser = option.satisfactionByUser.map {
                        PersonaValidationService.SatisfactionForValidation(
                            userId = it.userId,
                            score = it.score,
                        )
                    },
                )
            }

            personaValidationService.validateOptions(validationOptions, personaMembers)
                .filterValues { it.matchedPersonaCount > 0 }
                .mapValues { (_, result) -> result.toPersonaValidationMap() }
        } catch (error: Exception) {
            logger.warn(error) { "Persona validation failed. Schedule generation will continue with personaValidation=null." }
            emptyMap()
        }
    }

    private fun PersonaValidationService.ValidationResult.toPersonaValidationMap(): Map<String, Any> {
        return mapOf(
            "source" to source,
            "dataset" to dataset,
            "summary" to "비슷한 여행자 참고군 ${matchedPersonaCount}명 기준 수용도 ${personaAcceptanceScore}점",
            "personaAcceptanceScore" to personaAcceptanceScore,
            "matchedPersonaCount" to matchedPersonaCount,
            "matchedPersonas" to matchedPersonas.map { persona ->
                mapOf(
                    "matchedUserId" to persona.matchedUserId,
                    "similarity" to persona.similarity,
                    "personaSummary" to persona.personaSummary,
                    "scores" to mapOf(
                        "mobility" to persona.scores.mobility,
                        "photo" to persona.scores.photo,
                        "budget" to persona.scores.budget,
                        "theme" to persona.scores.theme,
                    ),
                )
            },
            "topPositiveSignals" to topPositiveSignals,
            "objectionReasons" to objectionReasons,
            "persuasionPoints" to persuasionPoints,
        )
    }



    private fun validateConfirmedSchedule(schedule: Schedule) {
        if (!schedule.isConfirmed) {
            throw DomainException(HttpStatus.CONFLICT, "SCHEDULE_NOT_CONFIRMED", "확정된 일정만 수정할 수 있습니다.")
        }
    }

    private fun redistributeSlotsWithinScheduleWindow(schedule: Schedule, slots: List<ScheduleSlot>) {
        val activeSlots = slots.sortedBy { it.orderIndex }
        if (activeSlots.isEmpty()) return

        val window = scheduleTimeWindow(schedule)
        var cursor = window.first

        activeSlots.forEachIndexed { index, slot ->
            val remainingSlots = activeSlots.size - index
            val remainingMinutes = Duration.between(cursor, window.second).toMinutes().coerceAtLeast(remainingSlots.toLong())
            val duration = if (index == activeSlots.lastIndex) {
                remainingMinutes
            } else {
                (remainingMinutes / remainingSlots).coerceAtLeast(1)
            }
            slot.startTime = cursor
            slot.endTime = if (index == activeSlots.lastIndex) window.second else cursor.plus(Duration.ofMinutes(duration))
            cursor = slot.endTime
        }
    }

    private fun scheduleTimeWindow(schedule: Schedule): Pair<java.time.Instant, java.time.Instant> {
        val input = schedule.generationInput
        val startText = input["startTime"]?.toString()?.takeIf { it.isNotBlank() } ?: "09:00"
        val endText = input["endTime"]?.toString()?.takeIf { it.isNotBlank() } ?: "21:00"
        val tripDate = LocalDate.parse(schedule.room.tripDate.toString())
        val zone = ZoneId.of("Asia/Seoul")
        val startParts = startText.split(":")
        val endParts = endText.split(":")
        val start = tripDate
            .atTime(startParts.getOrNull(0)?.toIntOrNull() ?: 9, startParts.getOrNull(1)?.toIntOrNull() ?: 0)
            .atZone(zone)
            .toInstant()
        var end = tripDate
            .atTime(endParts.getOrNull(0)?.toIntOrNull() ?: 21, endParts.getOrNull(1)?.toIntOrNull() ?: 0)
            .atZone(zone)
            .toInstant()
        if (!end.isAfter(start)) {
            end = start.plus(Duration.ofHours(12))
        }
        return start to end
    }

    private fun defaultSlotStart(schedule: Schedule): java.time.Instant {
        return scheduleTimeWindow(schedule).first
    }

    private fun isDepopulationArea(metadataTags: Map<String, Any>?): Boolean {
        return metadataTags?.get("populationDeclineArea") == true || metadataTags?.get("regionType") == "population_decline"
    }

    private fun getActiveRoom(roomId: Long): TripRoom {
        val room = tripRoomRepository.findById(roomId)
            .orElseThrow { DomainException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "존재하지 않는 방입니다.") }
        if (room.delYn != YnFlag.N) {
            throw DomainException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "존재하지 않는 방입니다.")
        }
        return room
    }

    private fun getActiveSchedule(scheduleId: Long): Schedule {
        val schedule = scheduleRepository.findById(scheduleId)
            .orElseThrow { DomainException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "일정을 찾을 수 없습니다.") }
        if (schedule.delYn != YnFlag.N) {
            throw DomainException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "일정을 찾을 수 없습니다.")
        }
        return schedule
    }

    private fun validateHost(roomId: Long, userId: Long) {
        val member = roomMemberRepository.findByRoomIdAndUserIdAndDelYn(roomId, userId, YnFlag.N)
            ?: throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "방장만 일정을 생성할 수 있습니다.")
        if (member.role != com.tripsync.domain.enums.RoomMemberRole.HOST) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "방장만 일정을 생성할 수 있습니다.")
        }
    }

    private fun validateRoomMember(roomId: Long, userId: Long) {
        if (!roomMemberRepository.existsByRoomIdAndUserIdAndDelYn(roomId, userId, YnFlag.N)) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "방 멤버만 접근할 수 있습니다.")
        }
    }

    private fun parseOptionType(optionType: String): ScheduleOptionType {
        return runCatching { ScheduleOptionType.valueOf(optionType.uppercase()) }
            .getOrElse { throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "지원하지 않는 일정 옵션입니다.") }
    }
}
