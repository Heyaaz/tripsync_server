package com.tripsync.application.consensus

import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.AxisScores
import com.tripsync.domain.enums.ConflictSeverity
import com.tripsync.domain.enums.ReasonAxis
import com.tripsync.domain.enums.ScheduleOptionType
import com.tripsync.domain.enums.ScoreAxis
import com.tripsync.domain.enums.SlotType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

@Service
class ConsensusService(
    private val llmService: LlmService,
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        private const val DETERMINISTIC_PROVIDER = "deterministic-consensus"
        private val SLOT_TEMPLATES = mapOf(
            5 to listOf(150, 120, 150, 120, 180),
            6 to listOf(120, 120, 120, 120, 120, 120),
            7 to listOf(90, 120, 90, 120, 90, 120, 90),
        )
        private val SCORE_AXES = ScoreAxis.entries
    }

    fun analyzeGroup(members: List<MemberSnapshot>): GroupAnalysis {
        if (members.size < 2) {
            throw DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "ROOM_NOT_READY", "갈등 분석을 위해 최소 2명의 멤버가 필요합니다.")
        }

        val conflictAxes = SCORE_AXES.map { axis ->
            val sorted = members.sortedWith(
                compareBy<MemberSnapshot> { it.scores.let { s -> axis.getValue(s) } }
                    .thenBy { it.joinedOrder }
            )
            val lowMember = sorted.first()
            val highMember = sorted.last()
            val min = axis.getValue(lowMember.scores)
            val max = axis.getValue(highMember.scores)
            val gap = max - min
            ConflictAxisAnalysis(
                axis = axis,
                min = min,
                max = max,
                gap = gap,
                severity = classifySeverity(gap),
                highUserId = highMember.userId,
                lowUserId = lowMember.userId,
            )
        }

        val commonAxes = conflictAxes.filter { it.gap <= 20 }.map { it.axis }
        val onlyConflicts = conflictAxes.filter { it.gap > 20 }.sortedByDescending { it.gap }

        return GroupAnalysis(
            commonAxes = commonAxes,
            conflictAxes = onlyConflicts,
            criticalAxes = onlyConflicts.filter { it.severity == ConflictSeverity.CRITICAL }.map { it.axis },
            priorityAxes = onlyConflicts.map { it.axis },
            allAxes = conflictAxes,
        )
    }

    suspend fun buildScheduleOptions(context: OptionContext): List<ScheduleOptionDraft> {
        if (context.destination != "충남") {
            throw DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "MVP에서는 충남 일정만 생성할 수 있습니다.")
        }
        if (context.startTime != "09:00" || context.endTime != "21:00") {
            throw DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", "MVP 일정 생성 시간은 09:00~21:00으로 고정됩니다.")
        }
        if (context.members.size !in 2..5) {
            throw DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "ROOM_NOT_READY", "일정 생성 가능한 멤버 수는 2~5명입니다.")
        }
        if (context.places.isEmpty()) {
            throw DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "PLACE_CANDIDATE_EMPTY", "일정 생성 후보 장소가 부족합니다.")
        }

        val analysis = analyzeGroup(context.members)
        val slotTemplate = buildSlotTemplate(context.tripDate, analysis, context.members.size)
        val baseShapes = buildIndividualSlotShapes(slotTemplate, analysis, context.members)
        val averageVector = getAverageScores(context.members)

        return coroutineScope {
            val individualDeferred = async {
                materializeOption(
                    optionType = ScheduleOptionType.INDIVIDUAL,
                    label = "개성형",
                    summary = "각자의 취향이 살아있는 교대 배분 일정",
                    targets = baseShapes.map { buildIndividualTarget(it, analysis, context.members, averageVector) },
                    places = context.places,
                    threshold = 60,
                    preferHiddenGem = false,
                    members = context.members,
                    tripDate = context.tripDate,
                    analysis = analysis,
                    context = context,
                )
            }
            val balancedDeferred = async {
                materializeOption(
                    optionType = ScheduleOptionType.BALANCED,
                    label = "균형형",
                    summary = "모두가 조금씩 만족하는 안전한 선택",
                    targets = baseShapes.map {
                        TargetVector(
                            scores = averageVector,
                            targetUserId = null,
                            slotType = SlotType.COMMON,
                            reasonAxis = ReasonAxis.COMMON,
                            reasonText = "그룹 전원의 평균 취향 반영",
                            startTime = it.startTime,
                            endTime = it.endTime,
                        )
                    },
                    places = context.places,
                    threshold = 65,
                    preferHiddenGem = false,
                    members = context.members,
                    tripDate = context.tripDate,
                    analysis = analysis,
                    context = context,
                )
            }
            val discoveryDeferred = async {
                materializeOption(
                    optionType = ScheduleOptionType.DISCOVERY,
                    label = "지역 발굴형",
                    summary = "충남 인구감소지역 숨은 명소 중심 탐험 일정",
                    targets = baseShapes.map { buildIndividualTarget(it, analysis, context.members, averageVector) },
                    places = context.places,
                    threshold = 55,
                    preferHiddenGem = true,
                    members = context.members,
                    tripDate = context.tripDate,
                    analysis = analysis,
                    context = context,
                )
            }

            val individual = individualDeferred.await()
            val balanced = balancedDeferred.await()
            val discovery = discoveryDeferred.await()

            listOf(balanced, individual, discovery)
        }
    }

    private fun classifySeverity(gap: Int): ConflictSeverity {
        return when {
            gap <= 20 -> ConflictSeverity.COMMON
            gap <= 40 -> ConflictSeverity.MINOR
            gap <= 60 -> ConflictSeverity.MODERATE
            else -> ConflictSeverity.CRITICAL
        }
    }

    private fun buildSlotTemplate(
        tripDate: String,
        analysis: GroupAnalysis,
        memberCount: Int,
    ): List<SlotTemplate> {
        val slotCount = when {
            analysis.criticalAxes.isNotEmpty() || memberCount >= 4 -> 7
            analysis.conflictAxes.size >= 2 || analysis.conflictAxes.any { it.severity == ConflictSeverity.MODERATE } -> 6
            else -> 5
        }

        val durations = SLOT_TEMPLATES[slotCount] ?: SLOT_TEMPLATES[5]!!
        var currentMinutes = 9 * 60

        return durations.mapIndexed { index, duration ->
            val startTime = toSeoulInstant(tripDate, currentMinutes)
            currentMinutes += duration
            val endTime = toSeoulInstant(tripDate, currentMinutes)
            SlotTemplate(
                orderIndex = index + 1,
                duration = duration,
                startTime = startTime,
                endTime = endTime,
            )
        }
    }

    private fun buildIndividualSlotShapes(
        slots: List<SlotTemplate>,
        analysis: GroupAnalysis,
        members: List<MemberSnapshot>,
    ): List<SlotShape> {
        val commonSlotIndexes = mutableSetOf(1, slots.size)
        if (analysis.commonAxes.size >= 2 && slots.size >= 6) {
            commonSlotIndexes.add(Math.ceil(slots.size / 2.0).toInt())
        }

        val personalSlots = slots.filter { it.orderIndex !in commonSlotIndexes }
        val allocation = allocateConflictAxes(personalSlots.size, analysis.conflictAxes)
        val personalTargets = mutableListOf<PersonalTarget>()

        for (axisEntry in analysis.conflictAxes) {
            val count = allocation[axisEntry.axis] ?: 0
            repeat(count) { index ->
                val userId = if (index % 2 == 0) axisEntry.highUserId else axisEntry.lowUserId
                val member = members.find { it.userId == userId }
                personalTargets.add(
                    PersonalTarget(
                        slotType = SlotType.PERSONAL,
                        targetUserId = userId,
                        reasonAxis = ReasonAxis.valueOf(axisEntry.axis.name),
                        reasonText = "${member?.nickname ?: "동행자"}의 ${axisLabel(axisEntry.axis)} 취향 반영",
                    )
                )
            }
        }

        while (personalTargets.size < personalSlots.size) {
            personalTargets.add(
                PersonalTarget(
                    slotType = SlotType.COMMON,
                    targetUserId = null,
                    reasonAxis = ReasonAxis.COMMON,
                    reasonText = "그룹 공통 지대 반영",
                )
            )
        }

        return slots.map { slot ->
            if (slot.orderIndex in commonSlotIndexes) {
                SlotShape(
                    orderIndex = slot.orderIndex,
                    slotType = SlotType.COMMON,
                    targetUserId = null,
                    reasonAxis = ReasonAxis.COMMON,
                    reasonText = "그룹 공통 지대 반영",
                    startTime = slot.startTime,
                    endTime = slot.endTime,
                )
            } else {
                val next = personalTargets.removeFirst()
                SlotShape(
                    orderIndex = slot.orderIndex,
                    slotType = next.slotType,
                    targetUserId = next.targetUserId,
                    reasonAxis = next.reasonAxis,
                    reasonText = next.reasonText,
                    startTime = slot.startTime,
                    endTime = slot.endTime,
                )
            }
        }
    }

    private fun allocateConflictAxes(
        personalSlotCount: Int,
        conflictAxes: List<ConflictAxisAnalysis>,
    ): Map<ScoreAxis, Int> {
        val allocation = mutableMapOf<ScoreAxis, Int>()
        if (personalSlotCount <= 0 || conflictAxes.isEmpty()) {
            return allocation
        }

        val totalGap = conflictAxes.sumOf { it.gap }
        var allocated = 0

        for (axis in conflictAxes) {
            val baseCount = if (totalGap == 0) 0 else Math.round((axis.gap.toDouble() / totalGap) * personalSlotCount).toInt()
            val adjusted = minOf(personalSlotCount, maxOf(if (axis.severity == ConflictSeverity.CRITICAL) 1 else 0, baseCount))
            allocation[axis.axis] = adjusted
            allocated += adjusted
        }

        while (allocated < personalSlotCount) {
            for (axis in conflictAxes) {
                if (allocated >= personalSlotCount) break
                allocation[axis.axis] = (allocation[axis.axis] ?: 0) + 1
                allocated += 1
            }
        }

        while (allocated > personalSlotCount) {
            for (axis in conflictAxes.reversed()) {
                if (allocated <= personalSlotCount) break
                val current = allocation[axis.axis] ?: 0
                val minimum = if (axis.severity == ConflictSeverity.CRITICAL) 1 else 0
                if (current > minimum) {
                    allocation[axis.axis] = current - 1
                    allocated -= 1
                }
            }
        }

        return allocation
    }

    private fun buildIndividualTarget(
        shape: SlotShape,
        analysis: GroupAnalysis,
        members: List<MemberSnapshot>,
        averageVector: AxisScores,
    ): TargetVector {
        val commonFallback = TargetVector(
            scores = averageVector,
            targetUserId = null,
            slotType = SlotType.COMMON,
            reasonAxis = ReasonAxis.COMMON,
            reasonText = "그룹 공통 지대 반영",
            startTime = shape.startTime,
            endTime = shape.endTime,
        )

        if (shape.slotType == SlotType.COMMON || shape.targetUserId == null || shape.reasonAxis == ReasonAxis.COMMON) {
            return commonFallback
        }

        val targetMember = members.find { it.userId == shape.targetUserId }
            ?: return commonFallback

        val reasonScoreAxis = ScoreAxis.valueOf(shape.reasonAxis.name)
        val mergedScores = AxisScores(
            mobility = if (ScoreAxis.MOBILITY == reasonScoreAxis) targetMember.scores.mobility
            else if (analysis.commonAxes.contains(ScoreAxis.MOBILITY)) averageVector.mobility
            else Math.round(targetMember.scores.mobility * 0.7 + averageVector.mobility * 0.3).toInt(),
            photo = if (ScoreAxis.PHOTO == reasonScoreAxis) targetMember.scores.photo
            else if (analysis.commonAxes.contains(ScoreAxis.PHOTO)) averageVector.photo
            else Math.round(targetMember.scores.photo * 0.7 + averageVector.photo * 0.3).toInt(),
            budget = if (ScoreAxis.BUDGET == reasonScoreAxis) targetMember.scores.budget
            else if (analysis.commonAxes.contains(ScoreAxis.BUDGET)) averageVector.budget
            else Math.round(targetMember.scores.budget * 0.7 + averageVector.budget * 0.3).toInt(),
            theme = if (ScoreAxis.THEME == reasonScoreAxis) targetMember.scores.theme
            else if (analysis.commonAxes.contains(ScoreAxis.THEME)) averageVector.theme
            else Math.round(targetMember.scores.theme * 0.7 + averageVector.theme * 0.3).toInt(),
        )

        return TargetVector(
            scores = mergedScores,
            targetUserId = shape.targetUserId,
            slotType = shape.slotType,
            reasonAxis = shape.reasonAxis,
            reasonText = shape.reasonText,
            startTime = shape.startTime,
            endTime = shape.endTime,
        )
    }

    private suspend fun materializeOption(
        optionType: ScheduleOptionType,
        label: String,
        summary: String,
        targets: List<TargetVector>,
        places: List<PlaceCandidate>,
        threshold: Int,
        preferHiddenGem: Boolean,
        members: List<MemberSnapshot>,
        tripDate: String,
        analysis: GroupAnalysis,
        context: OptionContext,
    ): ScheduleOptionDraft {
        val chosenPlaces = mutableListOf<PlaceCandidate>()
        val forcedHiddenGemIndex = if (preferHiddenGem) pickForcedHiddenGemSlot(targets) else -1
        val shortlistedPerSlot = mutableListOf<SlotShortlist>()

        val slots = targets.mapIndexed { index, target ->
            val profile = buildSlotSelectionProfile(target.startTime, target.endTime, index + 1, targets.size)
            val rankedPlaces = rankPlaces(
                targetVector = target.scores,
                places = places,
                usedPlaceIds = chosenPlaces.map { it.id }.toSet(),
                previousPlace = chosenPlaces.lastOrNull(),
                preferHiddenGem = preferHiddenGem,
                mustBeHiddenGem = index == forcedHiddenGemIndex,
                tripDate = tripDate,
                profile = profile,
            )
            val place = rankedPlaces.firstOrNull()
                ?: throw DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "PLACE_CANDIDATE_EMPTY", "일정 생성 후보 장소가 부족합니다.")

            chosenPlaces.add(place)
            shortlistedPerSlot.add(
                SlotShortlist(
                    orderIndex = index + 1,
                    startTime = target.startTime.atZone(ZoneId.of("Asia/Seoul")).toLocalTime().toString().take(5),
                    endTime = target.endTime.atZone(ZoneId.of("Asia/Seoul")).toLocalTime().toString().take(5),
                    slotType = target.slotType,
                    targetUserId = target.targetUserId,
                    reasonAxis = target.reasonAxis,
                    candidatePlaces = rankedPlaces.take(5).map {
                        CandidatePlace(it.id, it.name, it.category, it.address)
                    },
                    deterministicPlaceId = place.id,
                    deterministicReason = target.reasonText,
                )
            )

            ScheduleSlotDraft(
                orderIndex = index + 1,
                slotType = target.slotType,
                targetUserId = target.targetUserId,
                reasonAxis = target.reasonAxis,
                reasonText = target.reasonText,
                startTime = target.startTime,
                endTime = target.endTime,
                placeId = place.id,
                placeName = place.name,
                placeAddress = place.address,
                isHiddenGem = isHiddenGem(place),
            )
        }

        val llmRefined = llmService.refineScheduleOption(
            optionType = optionType,
            label = label,
            summary = summary,
            room = RoomRef(roomId = context.roomId, destination = context.destination, tripDate = context.tripDate),
            commonAxes = analysis.commonAxes,
            priorityAxes = analysis.priorityAxes,
            members = members.map { MemberRef(it.userId, it.nickname) },
            slotPlan = shortlistedPerSlot,
        )

        val finalSummary = llmRefined?.summary ?: summary
        val placesById = places.associateBy { it.id }
        val finalPlacesByOrder = mutableMapOf<Int, PlaceCandidate>()
        llmRefined?.slots?.forEach { slot ->
            placesById[slot.placeId]?.let { finalPlacesByOrder[slot.orderIndex] = it }
        }

        val refinedByOrder = llmRefined?.slots?.associateBy { it.orderIndex } ?: emptyMap()
        val finalSlots = slots.map { slot ->
            val refined = refinedByOrder[slot.orderIndex]
            val refinedPlace = refined?.let { finalPlacesByOrder[it.orderIndex] }
            if (refined == null || refinedPlace == null) {
                slot
            } else {
                slot.copy(
                    placeId = refinedPlace.id,
                    placeName = refinedPlace.name,
                    placeAddress = refinedPlace.address,
                    reasonText = refined.reason,
                    isHiddenGem = isHiddenGem(refinedPlace),
                )
            }
        }

        val finalPlaces = finalSlots.mapIndexed { index, slot ->
            finalPlacesByOrder[slot.orderIndex] ?: chosenPlaces[index]
        }

        val satisfactionByUser = buildSatisfaction(optionType, finalSlots, finalPlaces, members)
        val groupSatisfaction = maxOf(threshold, satisfactionByUser.minOf { it.score })

        logger.info {
            "schedule_option optionType=$optionType roomId=${context.roomId} provider=${llmRefined?.provider ?: DETERMINISTIC_PROVIDER} latencyMs=${llmRefined?.latencyMs ?: 0} fallbackUsed=${llmRefined == null} groupSatisfaction=$groupSatisfaction"
        }

        return ScheduleOptionDraft(
            optionType = optionType,
            label = label,
            summary = finalSummary,
            groupSatisfaction = groupSatisfaction,
            slots = finalSlots,
            satisfactionByUser = satisfactionByUser,
            llmProvider = llmRefined?.provider ?: DETERMINISTIC_PROVIDER,
            llmLatencyMs = llmRefined?.latencyMs,
            fallbackUsed = llmRefined == null,
        )
    }

    private fun buildSatisfaction(
        optionType: ScheduleOptionType,
        slots: List<ScheduleSlotDraft>,
        places: List<PlaceCandidate>,
        members: List<MemberSnapshot>,
    ): List<SatisfactionDraft> {
        return members.map { member ->
            val userId = member.userId
            val userVector = member.scores
            val weightedScore = slots.foldIndexed(0.0) { index, sum, slot ->
                val durationMinutes = (slot.endTime.epochSecond - slot.startTime.epochSecond) / 60.0
                val matchScore = calculateVectorMatch(userVector, placeScores(places[index]))
                val ownershipBonus = if (slot.targetUserId == userId && optionType != ScheduleOptionType.BALANCED) 0.05 else 0.0
                sum + durationMinutes * minOf(1.0, matchScore + ownershipBonus)
            }
            val totalDuration = slots.sumOf { (it.endTime.epochSecond - it.startTime.epochSecond) / 60.0 }
            val overall = Math.round((weightedScore / totalDuration) * 100).toInt()

            val byAxis = ScoreAxis.entries.associateWith { axis ->
                val axisAverage = places.map { place ->
                    1.0 - abs(axis.getValue(placeScores(place)) - axis.getValue(userVector)) / 100.0
                }.average()
                axisAverage
            }

            SatisfactionDraft(
                userId = userId,
                score = overall,
                breakdown = SatisfactionDraft.Breakdown(
                    overall = overall,
                    byAxis = byAxis,
                ),
            )
        }
    }

    private fun pickForcedHiddenGemSlot(targets: List<TargetVector>): Int {
        val personalIndex = targets.indexOfFirst { it.slotType == SlotType.PERSONAL }
        return if (personalIndex >= 0) personalIndex else targets.size / 2
    }

    private fun rankPlaces(
        targetVector: AxisScores,
        places: List<PlaceCandidate>,
        usedPlaceIds: Set<Long>,
        previousPlace: PlaceCandidate?,
        preferHiddenGem: Boolean,
        mustBeHiddenGem: Boolean,
        tripDate: String,
        profile: SlotSelectionProfile,
    ): List<PlaceCandidate> {
        val source = if (mustBeHiddenGem) places.filter { isHiddenGem(it) } else places
        var pool = if (source.isNotEmpty()) source else places

        val validFestivalPool = pool.filter { !isFestivalPlace(it) || isFestivalAvailableOnTripDate(it, tripDate) != false }
        if (validFestivalPool.isNotEmpty()) pool = validFestivalPool

        val openNowPool = pool.filter { isPlaceOpenDuringSlot(it, profile) != false }
        if (openNowPool.isNotEmpty()) pool = openNowPool

        if (!profile.isFinalSlot) {
            val withoutAccommodation = pool.filter { !isAccommodationPlace(it) }
            if (withoutAccommodation.isNotEmpty()) pool = withoutAccommodation
        }

        if (profile.isMealSlot) {
            val restaurants = pool.filter { isRestaurantPlace(it) }
            if (restaurants.isNotEmpty()) pool = restaurants
        }

        return pool.sortedByDescending {
            placeRankingScore(it, targetVector, previousPlace, preferHiddenGem, usedPlaceIds, tripDate, profile)
        }
    }

    private fun placeRankingScore(
        place: PlaceCandidate,
        targetVector: AxisScores,
        previousPlace: PlaceCandidate?,
        preferHiddenGem: Boolean,
        usedPlaceIds: Set<Long>,
        tripDate: String,
        profile: SlotSelectionProfile,
    ): Double {
        var score = calculateVectorMatch(targetVector, placeScores(place))
        if (usedPlaceIds.contains(place.id)) score -= 0.2
        if (previousPlace != null && previousPlace.category == place.category) score -= 0.08
        if (hasUnknownHours(place)) score -= 0.08
        if (preferHiddenGem && isHiddenGem(place)) score += 0.2

        val operatingAvailability = isPlaceOpenDuringSlot(place, profile)
        when (operatingAvailability) {
            true -> score += 0.05
            false -> score -= 0.35
            else -> {}
        }

        score += placeCategoryModifier(place, tripDate, profile)
        return score
    }

    private fun placeCategoryModifier(place: PlaceCandidate, tripDate: String, profile: SlotSelectionProfile): Double {
        var modifier = 0.0

        if (isRestaurantPlace(place)) {
            modifier += if (profile.isMealSlot) 0.35 else -0.12
        }

        if (isShoppingPlace(place)) {
            modifier += when {
                profile.isLateSlot -> 0.18
                profile.isEarlySlot -> -0.12
                else -> -0.03
            }
        }

        if (isAccommodationPlace(place)) {
            modifier += if (profile.isFinalSlot) -0.08 else -0.45
        }

        val festivalAvailability = isFestivalAvailableOnTripDate(place, tripDate)
        when (festivalAvailability) {
            true -> modifier += if (profile.isLateSlot) 0.18 else 0.1
            false -> modifier -= 0.5
            else -> if (isFestivalPlace(place)) modifier -= 0.05
        }

        if (!profile.isMealSlot && !profile.isLateSlot && isDayActivityPlace(place)) {
            modifier += 0.08
        }

        return modifier
    }

    private fun calculateVectorMatch(target: AxisScores, candidate: AxisScores): Double {
        val total = ScoreAxis.entries.sumOf {
            1.0 - abs(it.getValue(target) - it.getValue(candidate)) / 100.0
        }
        return total / ScoreAxis.entries.size
    }

    private fun hasUnknownHours(place: PlaceCandidate): Boolean {
        val hours = place.operatingHours ?: return true
        val status = hours["status"] as? String
        return status == "unknown"
    }

    private fun isHiddenGem(place: PlaceCandidate): Boolean {
        val tags = place.metadataTags ?: return false
        val tagList = tags["tags"] as? List<*>
        if (tagList != null) {
            return tagList.any { it in listOf("hidden_gem", "population_decline") }
        }
        return tags["hiddenGem"] == true || tags["populationDeclineArea"] == true || tags["regionType"] == "population_decline"
    }

    private fun buildSlotSelectionProfile(
        startTime: Instant,
        endTime: Instant,
        orderIndex: Int,
        totalSlots: Int,
    ): SlotSelectionProfile {
        val startMinutes = getSeoulMinutes(startTime)
        val endMinutes = getSeoulMinutes(endTime)
        val overlapsLunch = startMinutes < 13 * 60 + 30 && endMinutes > 11 * 60 + 30
        val overlapsDinner = startMinutes < 20 * 60 && endMinutes > 17 * 60

        return SlotSelectionProfile(
            isMealSlot = overlapsLunch || overlapsDinner,
            isFinalSlot = orderIndex == totalSlots,
            isEarlySlot = orderIndex == 1 || startMinutes < 11 * 60,
            isLateSlot = startMinutes >= 16 * 60 || endMinutes > 18 * 60,
            startMinutes = startMinutes,
            endMinutes = endMinutes,
        )
    }

    private fun getSeoulMinutes(instant: Instant): Int {
        val zdt = instant.atZone(ZoneId.of("Asia/Seoul"))
        return zdt.hour * 60 + zdt.minute
    }

    private fun getPlaceContentTypeId(place: PlaceCandidate): String? {
        val metadata = place.metadataTags ?: return null
        return metadata["contentTypeId"] as? String
    }

    private fun isRestaurantPlace(place: PlaceCandidate) =
        place.category == "restaurant" || getPlaceContentTypeId(place) == "39"

    private fun isShoppingPlace(place: PlaceCandidate) =
        place.category == "shopping" || getPlaceContentTypeId(place) == "38"

    private fun isAccommodationPlace(place: PlaceCandidate) =
        place.category == "accommodation" || getPlaceContentTypeId(place) == "32"

    private fun isFestivalPlace(place: PlaceCandidate) =
        place.category == "festival" || getPlaceContentTypeId(place) == "15"

    private fun isDayActivityPlace(place: PlaceCandidate) =
        place.category in listOf("tourist_attraction", "cultural_facility", "leisure_sports", "festival")

    private fun isFestivalAvailableOnTripDate(place: PlaceCandidate, tripDate: String): Boolean? {
        if (!isFestivalPlace(place)) return null

        val metadata = place.metadataTags ?: return null
        val introFields = metadata["introFields"] as? Map<*, *>

        val start = parseYmdDate(
            introFields?.get("eventstartdate")
                ?: introFields?.get("eventStartDate")
                ?: metadata["eventstartdate"]
                ?: metadata["eventStartDate"]
        )
        val end = parseYmdDate(
            introFields?.get("eventenddate")
                ?: introFields?.get("eventEndDate")
                ?: metadata["eventenddate"]
                ?: metadata["eventEndDate"]
        )

        if (start == null || end == null) return null

        val normalizedTripDate = tripDate.replace("-", "")
        return normalizedTripDate >= start && normalizedTripDate <= end
    }

    private fun parseYmdDate(value: Any?): String? {
        if (value == null) return null
        val digits = value.toString().replace(Regex("\\D"), "")
        return if (digits.length >= 8) digits.take(8) else null
    }

    private fun isPlaceOpenDuringSlot(place: PlaceCandidate, profile: SlotSelectionProfile): Boolean? {
        val hours = place.operatingHours ?: return null
        if (hours.isEmpty()) return null

        val status = hours["status"] as? String
        if (status == "always") return true
        if (status != "known") return null

        val entries = hours["entries"] as? List<*> ?: return null
        if (entries.isEmpty()) return null

        return entries.any { entry ->
            val record = entry as? Map<*, *> ?: return@any false
            val openMinutes = (record["openMinutes"] as? Number)?.toInt()
            val closeMinutes = (record["closeMinutes"] as? Number)?.toInt()
            if (openMinutes == null || closeMinutes == null) return@any false

            profile.startMinutes >= openMinutes && profile.endMinutes <= closeMinutes
        }
    }

    private fun placeScores(place: PlaceCandidate): AxisScores {
        return AxisScores(
            mobility = place.mobilityScore,
            photo = place.photoScore,
            budget = place.budgetScore,
            theme = place.themeScore,
        )
    }

    private fun getAverageScores(members: List<MemberSnapshot>): AxisScores {
        val count = members.size
        return AxisScores(
            mobility = members.sumOf { it.scores.mobility } / count,
            photo = members.sumOf { it.scores.photo } / count,
            budget = members.sumOf { it.scores.budget } / count,
            theme = members.sumOf { it.scores.theme } / count,
        )
    }

    private fun axisLabel(axis: ScoreAxis): String {
        return when (axis) {
            ScoreAxis.MOBILITY -> "활동성"
            ScoreAxis.PHOTO -> "기록"
            ScoreAxis.BUDGET -> "예산"
            ScoreAxis.THEME -> "테마"
        }
    }

    private fun toSeoulInstant(tripDate: String, minutesFromMidnight: Int): Instant {
        val hours = (minutesFromMidnight / 60).toString().padStart(2, '0')
        val minutes = (minutesFromMidnight % 60).toString().padStart(2, '0')
        return LocalDate.parse(tripDate)
            .atTime(hours.toInt(), minutes.toInt())
            .atZone(ZoneId.of("Asia/Seoul"))
            .toInstant()
    }

    private fun ScoreAxis.getValue(scores: AxisScores): Int {
        return when (this) {
            ScoreAxis.MOBILITY -> scores.mobility
            ScoreAxis.PHOTO -> scores.photo
            ScoreAxis.BUDGET -> scores.budget
            ScoreAxis.THEME -> scores.theme
        }
    }

    data class GroupAnalysis(
        val commonAxes: List<ScoreAxis>,
        val conflictAxes: List<ConflictAxisAnalysis>,
        val criticalAxes: List<ScoreAxis>,
        val priorityAxes: List<ScoreAxis>,
        val allAxes: List<ConflictAxisAnalysis>,
    )

    data class SlotTemplate(
        val orderIndex: Int,
        val duration: Int,
        val startTime: Instant,
        val endTime: Instant,
    )

    data class SlotShape(
        val orderIndex: Int,
        val slotType: SlotType,
        val targetUserId: Long?,
        val reasonAxis: ReasonAxis,
        val reasonText: String,
        val startTime: Instant,
        val endTime: Instant,
    )

    data class PersonalTarget(
        val slotType: SlotType,
        val targetUserId: Long?,
        val reasonAxis: ReasonAxis,
        val reasonText: String,
    )

    data class TargetVector(
        val scores: AxisScores,
        val targetUserId: Long?,
        val slotType: SlotType,
        val reasonAxis: ReasonAxis,
        val reasonText: String,
        val startTime: Instant,
        val endTime: Instant,
    )

    data class SlotSelectionProfile(
        val isMealSlot: Boolean,
        val isFinalSlot: Boolean,
        val isEarlySlot: Boolean,
        val isLateSlot: Boolean,
        val startMinutes: Int,
        val endMinutes: Int,
    )

    data class SlotShortlist(
        val orderIndex: Int,
        val startTime: String,
        val endTime: String,
        val slotType: SlotType,
        val targetUserId: Long?,
        val reasonAxis: ReasonAxis,
        val candidatePlaces: List<CandidatePlace>,
        val deterministicPlaceId: Long,
        val deterministicReason: String,
    )

    data class CandidatePlace(
        val id: Long,
        val name: String,
        val category: String,
        val address: String,
    )

    data class RoomRef(
        val roomId: Long,
        val destination: String,
        val tripDate: String,
    )

    data class MemberRef(
        val userId: Long,
        val nickname: String,
    )
}
