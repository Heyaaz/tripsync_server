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
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Service
class ConsensusService(
    private val llmService: LlmService,
) {
    private val logger = KotlinLogging.logger {}

    companion object {
        private const val DETERMINISTIC_PROVIDER = "deterministic-consensus"
        private val SLOT_TEMPLATES = mapOf(
            3 to listOf(1, 1, 1),
            5 to listOf(150, 120, 150, 120, 180),
            6 to listOf(120, 120, 120, 120, 120, 120),
            7 to listOf(90, 120, 90, 120, 90, 120, 90),
        )
        private const val NEARBY_LOCALITY_RADIUS_KM = 45.0
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
        if (context.members.size < 2) {
            throw DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "ROOM_NOT_READY", "일정 생성을 위해 최소 2명의 멤버가 필요합니다.")
        }

        val windows = parseScheduleWindows(context.tripDate, context.tripEndDate, context.startTime, context.endTime)
        val candidatePlaces = filterPlacesForDestination(context.destination, context.places)
        if (candidatePlaces.isEmpty()) {
            throw DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "PLACE_CANDIDATE_EMPTY", "목적지에 맞는 일정 생성 후보 장소가 부족합니다.")
        }

        val analysis = analyzeGroup(context.members)
        val slotTemplate = buildSlotTemplates(windows, analysis, context.members.size)
        val baseShapes = buildIndividualSlotShapes(slotTemplate, analysis, context.members)
        val averageVector = getAverageScores(context.members)
        val optionPlaceScopes = selectOptionPlaceScopes(candidatePlaces, baseShapes.size)

        val usedPlaceIdsByOrder = mutableMapOf<Int, MutableSet<Long>>()
        val usedPlaceKeysByOrder = mutableMapOf<Int, MutableSet<String>>()
        val balanced = prepareOption(
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
            places = optionPlaceScopes.getValue(ScheduleOptionType.BALANCED),
            threshold = 65,
            preferHiddenGem = false,
            tripDate = context.tripDate,
            context = context,
            avoidPlaceIdsByOrder = usedPlaceIdsByOrder,
            avoidPlaceKeysByOrder = usedPlaceKeysByOrder,
        ).also { rememberUsedPlacesByOrder(usedPlaceIdsByOrder, usedPlaceKeysByOrder, it.slots) }

        val individual = prepareOption(
            optionType = ScheduleOptionType.INDIVIDUAL,
            label = "개성형",
            summary = "각자의 취향이 살아있는 교대 배분 일정",
            targets = baseShapes.map { buildIndividualTarget(it, analysis, context.members, averageVector) },
            places = optionPlaceScopes.getValue(ScheduleOptionType.INDIVIDUAL),
            threshold = 60,
            preferHiddenGem = false,
            tripDate = context.tripDate,
            context = context,
            avoidPlaceIdsByOrder = usedPlaceIdsByOrder,
            avoidPlaceKeysByOrder = usedPlaceKeysByOrder,
        ).also { rememberUsedPlacesByOrder(usedPlaceIdsByOrder, usedPlaceKeysByOrder, it.slots) }

        val discovery = prepareOption(
            optionType = ScheduleOptionType.DISCOVERY,
            label = "지역 발굴형",
            summary = "${destinationLabel(context.destination)} 숨은 명소 중심 탐험 일정",
            targets = baseShapes.map { buildIndividualTarget(it, analysis, context.members, averageVector) },
            places = optionPlaceScopes.getValue(ScheduleOptionType.DISCOVERY),
            threshold = 55,
            preferHiddenGem = true,
            tripDate = context.tripDate,
            context = context,
            avoidPlaceIdsByOrder = usedPlaceIdsByOrder,
            avoidPlaceKeysByOrder = usedPlaceKeysByOrder,
        )

        return coroutineScope {
            val balancedDeferred = async { refinePreparedOption(balanced, context.members, analysis, context) }
            val individualDeferred = async { refinePreparedOption(individual, context.members, analysis, context) }
            val discoveryDeferred = async { refinePreparedOption(discovery, context.members, analysis, context) }
            listOf(balancedDeferred.await(), individualDeferred.await(), discoveryDeferred.await())
        }
    }

    private fun parseScheduleWindows(tripStartDate: String, tripEndDate: String?, startTime: String, endTime: String): List<ScheduleWindow> {
        val startDate = runCatching { LocalDate.parse(tripStartDate) }
            .getOrElse { throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "tripDate는 yyyy-MM-dd 형식이어야 합니다.") }
        val endDate = runCatching { LocalDate.parse(tripEndDate ?: tripStartDate) }
            .getOrElse { throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "tripEndDate는 yyyy-MM-dd 형식이어야 합니다.") }
        if (endDate.isBefore(startDate)) {
            throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "tripEndDate는 tripStartDate보다 빠를 수 없습니다.")
        }
        val start = parseScheduleTime(startTime, "startTime")
        val end = parseScheduleTime(endTime, "endTime")
        val startMinutes = start.hour * 60 + start.minute
        val endMinutes = end.hour * 60 + end.minute
        if (endMinutes <= startMinutes) {
            throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "endTime은 startTime보다 늦어야 합니다.")
        }
        if (endMinutes - startMinutes < 60) {
            throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "일정 생성 시간 범위는 최소 1시간 이상이어야 합니다.")
        }
        val dayCount = ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1
        return (0 until dayCount).map { offset ->
            ScheduleWindow(startDate.plusDays(offset.toLong()), startMinutes, endMinutes)
        }
    }

    private fun parseScheduleTime(value: String, fieldName: String): LocalTime {
        return try {
            LocalTime.parse(value.trim(), DateTimeFormatter.ofPattern("H:mm"))
        } catch (ex: DateTimeParseException) {
            throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "${fieldName}은 HH:mm 형식이어야 합니다.")
        }
    }

    private fun filterPlacesForDestination(destination: String, places: List<PlaceCandidate>): List<PlaceCandidate> {
        val tokens = destinationSearchTokens(destination)
        if (tokens.isEmpty()) return places
        return places.filter { place ->
            val searchable = listOf(place.name, place.address, place.metadataTags?.get("region")?.toString(), place.metadataTags?.get("area")?.toString())
                .filterNotNull()
                .joinToString(" ")
                .let(::normalizeRegionText)
            tokens.any { token -> searchable.contains(token) }
        }
    }

    private fun destinationSearchTokens(destination: String): Set<String> {
        val normalized = normalizeRegionText(destination)
        if (normalized.isBlank() || normalized in setOf("전국", "전체", "all")) return emptySet()

        val shortToFull = mapOf(
            "충남" to "충청남도",
            "충북" to "충청북도",
            "전북" to "전라북도",
            "전남" to "전라남도",
            "경북" to "경상북도",
            "경남" to "경상남도",
        )
        val fullToShort = shortToFull.entries.associate { (shortName, fullName) -> fullName to shortName }
        val expanded = shortToFull.entries.fold(normalized) { acc, (shortName, fullName) -> acc.replace(shortName, fullName) }
        val shortened = fullToShort.entries.fold(normalized) { acc, (fullName, shortName) -> acc.replace(fullName, shortName) }

        return buildSet {
            add(normalized)
            add(expanded)
            add(shortened)
            shortToFull[normalized]?.let { add(normalizeRegionText(it)) }
            fullToShort[normalized]?.let { add(normalizeRegionText(it)) }
        }.filter { it.isNotBlank() }.toSet()
    }

    private fun destinationLabel(destination: String): String {
        return destination.trim().ifBlank { "지역" }
    }

    private fun normalizeRegionText(value: String): String {
        return value.lowercase().replace(Regex("\\s+"), "")
    }

    private fun selectOptionPlaceScopes(
        places: List<PlaceCandidate>,
        targetSlotCount: Int,
    ): Map<ScheduleOptionType, List<PlaceCandidate>> {
        val grouped = places.groupBy { extractPrimaryLocality(it.address) }
            .filterKeys { it != null }
            .mapKeys { it.key!! }
        if (grouped.isEmpty()) {
            return mapOf(
                ScheduleOptionType.BALANCED to places,
                ScheduleOptionType.INDIVIDUAL to places,
                ScheduleOptionType.DISCOVERY to places,
            )
        }

        val minimumUsefulSize = minOf(targetSlotCount, 5)
        val candidates = grouped.filterValues { it.size >= minimumUsefulSize }.ifEmpty { grouped }

        val balancedLocality = pickLocality(candidates, ScheduleOptionType.BALANCED, targetSlotCount, emptySet())
        val individualLocality = pickLocality(candidates, ScheduleOptionType.INDIVIDUAL, targetSlotCount, setOfNotNull(balancedLocality))
            ?: balancedLocality
        val discoveryLocality = pickLocality(
            candidates,
            ScheduleOptionType.DISCOVERY,
            targetSlotCount,
            setOfNotNull(balancedLocality, individualLocality),
        ) ?: pickLocality(candidates, ScheduleOptionType.DISCOVERY, targetSlotCount, setOfNotNull(balancedLocality)) ?: balancedLocality

        return mapOf(
            ScheduleOptionType.BALANCED to scopedPlacesForLocality(places, grouped, balancedLocality, targetSlotCount),
            ScheduleOptionType.INDIVIDUAL to scopedPlacesForLocality(places, grouped, individualLocality, targetSlotCount),
            ScheduleOptionType.DISCOVERY to scopedPlacesForLocality(places, grouped, discoveryLocality, targetSlotCount),
        )
    }

    private fun pickLocality(
        candidates: Map<String, List<PlaceCandidate>>,
        optionType: ScheduleOptionType,
        targetSlotCount: Int,
        excludedLocalities: Set<String>,
    ): String? {
        return candidates
            .filterKeys { it !in excludedLocalities }
            .maxByOrNull { (_, group) -> localityScore(group, optionType, targetSlotCount) }
            ?.key
    }

    private fun scopedPlacesForLocality(
        places: List<PlaceCandidate>,
        grouped: Map<String, List<PlaceCandidate>>,
        selectedLocality: String?,
        targetSlotCount: Int,
    ): List<PlaceCandidate> {
        if (selectedLocality == null) return places
        val sameLocality = grouped.getValue(selectedLocality)
        if (sameLocality.size >= targetSlotCount) return sameLocality

        val anchor = sameLocality.maxByOrNull { it.externalPopularityScore ?: 0 } ?: return sameLocality
        val sameIds = sameLocality.map { place -> place.id }.toSet()
        val nearby = places
            .filter { it.id !in sameLocality.map { place -> place.id }.toSet() }
            .filter { distanceKm(anchor, it)?.let { distance -> distance <= NEARBY_LOCALITY_RADIUS_KM } == true }
            .sortedBy { distanceKm(anchor, it) ?: Double.MAX_VALUE }

        val scoped = (sameLocality + nearby.filter { it.id !in sameIds }).distinctBy { it.id }
        return if (scoped.size >= targetSlotCount) scoped else places
    }

    private fun localityScore(group: List<PlaceCandidate>, optionType: ScheduleOptionType, targetSlotCount: Int): Double {
        val anchorCount = group.count { (it.externalPopularityScore ?: 0) >= 70 }
        val regionalCount = group.count { it.isRegionalBenefit || isHiddenGem(it) }
        val restaurantCount = group.count { isRestaurantPlace(it) }
        val dayActivityCount = group.count { isDayActivityPlace(it) }
        val sizeFit = minOf(group.size, targetSlotCount) / targetSlotCount.toDouble()
        val mixScore = when (optionType) {
            ScheduleOptionType.DISCOVERY -> regionalCount * 2.2 + anchorCount * 0.7
            ScheduleOptionType.BALANCED -> anchorCount * 1.6 + regionalCount * 1.2
            ScheduleOptionType.INDIVIDUAL -> anchorCount * 1.4 + regionalCount
            else -> (anchorCount + regionalCount).toDouble()
        }
        return mixScore + restaurantCount * 0.35 + dayActivityCount * 0.25 + sizeFit
    }

    private fun extractPrimaryLocality(address: String): String? {
        val normalized = address.trim()
        val provinceRemoved = normalized.replace(Regex("^(충청남도|충남|전라북도|전북|전라남도|전남|경상북도|경북|경상남도|경남|충청북도|충북)\\s*"), "")
        return Regex("([가-힣]+(?:시|군))").find(provinceRemoved)?.value
    }

    private fun classifySeverity(gap: Int): ConflictSeverity {
        return when {
            gap <= 20 -> ConflictSeverity.COMMON
            gap <= 40 -> ConflictSeverity.MINOR
            gap <= 60 -> ConflictSeverity.MODERATE
            else -> ConflictSeverity.CRITICAL
        }
    }

    private fun buildSlotTemplates(
        windows: List<ScheduleWindow>,
        analysis: GroupAnalysis,
        memberCount: Int,
    ): List<SlotTemplate> {
        var nextOrderIndex = 1
        return windows.flatMap { window ->
            buildDaySlotTemplate(window, analysis, memberCount).map { slot ->
                slot.copy(orderIndex = nextOrderIndex++)
            }
        }
    }

    private fun buildDaySlotTemplate(
        window: ScheduleWindow,
        analysis: GroupAnalysis,
        memberCount: Int,
    ): List<SlotTemplate> {
        val desiredSlotCount = 3
        val maxSlotsByWindow = (window.totalMinutes / 60).coerceAtLeast(1)
        val slotCount = desiredSlotCount.coerceAtMost(maxSlotsByWindow)
        val weights = SLOT_TEMPLATES[slotCount] ?: List(slotCount) { 1 }
        val weightTotal = weights.sum().toDouble()
        val offsets = weights.runningFold(0) { acc, weight -> acc + weight }
            .map { ((it / weightTotal) * window.totalMinutes).roundToInt() }

        return (0 until slotCount).map { index ->
            val startOffset = offsets[index]
            val endOffset = if (index == slotCount - 1) window.totalMinutes else offsets[index + 1]
            val startMinutes = window.startMinutes + startOffset
            val endMinutes = window.startMinutes + endOffset.coerceAtLeast(startOffset + 1)
            SlotTemplate(
                orderIndex = index + 1,
                duration = endMinutes - startMinutes,
                startTime = toSeoulInstant(window.tripDate.toString(), startMinutes),
                endTime = toSeoulInstant(window.tripDate.toString(), endMinutes),
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

    private fun prepareOption(
        optionType: ScheduleOptionType,
        label: String,
        summary: String,
        targets: List<TargetVector>,
        places: List<PlaceCandidate>,
        threshold: Int,
        preferHiddenGem: Boolean,
        tripDate: String,
        context: OptionContext,
        avoidPlaceIdsByOrder: Map<Int, Set<Long>>,
        avoidPlaceKeysByOrder: Map<Int, Set<String>>,
    ): PreparedScheduleOption {
        val chosenPlaces = mutableListOf<PlaceCandidate>()
        val forcedHiddenGemIndex = if (preferHiddenGem) pickForcedHiddenGemSlot(targets) else -1
        val shortlistedPerSlot = mutableListOf<SlotShortlist>()

        val slots = targets.mapIndexed { index, target ->
            val profile = buildSlotSelectionProfile(target.startTime, target.endTime, index + 1, targets.size)
            val orderIndex = index + 1
            val currentOptionPlaceIds = chosenPlaces.map { it.id }.toSet()
            val currentOptionPlaceKeys = chosenPlaces.flatMap { placeCandidateKeys(it) }.toSet()
            val rankedPlaces = rankPlaces(
                targetVector = target.scores,
                places = places,
                usedPlaceIds = currentOptionPlaceIds,
                usedPlaceKeys = currentOptionPlaceKeys,
                avoidPlaceIds = avoidPlaceIdsByOrder[orderIndex].orEmpty(),
                avoidPlaceKeys = avoidPlaceKeysByOrder[orderIndex].orEmpty(),
                previousPlace = previousPlaceInSameTripDay(targets, index, chosenPlaces),
                preferHiddenGem = preferHiddenGem,
                mustBeHiddenGem = index == forcedHiddenGemIndex,
                tripDate = tripDate,
                profile = profile,
                recentPlaceIds = context.recentPlaceIds,
                diversitySalt = context.diversitySalt,
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
                    candidatePlaces = rankedPlaces.distinctBy { normalizedPlaceName(it) }.take(5).map {
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

        return PreparedScheduleOption(
            optionType = optionType,
            label = label,
            summary = summary,
            threshold = threshold,
            places = places,
            slots = slots,
            chosenPlaces = chosenPlaces,
            shortlistedPerSlot = shortlistedPerSlot,
        )
    }

    private suspend fun refinePreparedOption(
        prepared: PreparedScheduleOption,
        members: List<MemberSnapshot>,
        analysis: GroupAnalysis,
        context: OptionContext,
    ): ScheduleOptionDraft {
        val llmAttempt = llmService.refineScheduleOption(
            optionType = prepared.optionType,
            label = prepared.label,
            summary = prepared.summary,
            room = RoomRef(roomId = context.roomId, destination = context.destination, tripDate = context.tripDate),
            commonAxes = analysis.commonAxes,
            priorityAxes = analysis.priorityAxes,
            members = members.map { MemberRef(it.userId, it.nickname) },
            slotPlan = prepared.shortlistedPerSlot,
        )
        val llmRefined = llmAttempt.result

        val finalSummary = llmRefined?.summary ?: prepared.summary
        val placesById = prepared.places.associateBy { it.id }
        val finalPlacesByOrder = mutableMapOf<Int, PlaceCandidate>()
        llmRefined?.slots?.forEach { slot ->
            placesById[slot.placeId]?.let { finalPlacesByOrder[slot.orderIndex] = it }
        }

        val refinedByOrder = llmRefined?.slots?.associateBy { it.orderIndex } ?: emptyMap()
        val finalSlots = prepared.slots.map { slot ->
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
            finalPlacesByOrder[slot.orderIndex] ?: prepared.chosenPlaces[index]
        }

        val satisfactionByUser = buildSatisfaction(prepared.optionType, finalSlots, finalPlaces, members)
        val groupSatisfaction = maxOf(prepared.threshold, satisfactionByUser.minOf { it.score })

        logger.info {
            "schedule_option optionType=${prepared.optionType} roomId=${context.roomId} provider=${llmRefined?.provider ?: DETERMINISTIC_PROVIDER} attemptedProvider=${llmAttempt.attemptedProvider} latencyMs=${llmAttempt.latencyMs ?: 0} fallbackUsed=${llmAttempt.fallbackUsed} fallbackReason=${llmAttempt.fallbackReason?.code ?: "none"} groupSatisfaction=$groupSatisfaction"
        }

        return ScheduleOptionDraft(
            optionType = prepared.optionType,
            label = prepared.label,
            summary = finalSummary,
            groupSatisfaction = groupSatisfaction,
            slots = finalSlots,
            satisfactionByUser = satisfactionByUser,
            llmProvider = llmRefined?.provider ?: DETERMINISTIC_PROVIDER,
            llmAttemptedProvider = llmAttempt.attemptedProvider,
            llmLatencyMs = llmAttempt.latencyMs,
            fallbackUsed = llmAttempt.fallbackUsed,
            llmFallbackReason = llmAttempt.fallbackReason?.code,
        )
    }

    private data class PreparedScheduleOption(
        val optionType: ScheduleOptionType,
        val label: String,
        val summary: String,
        val threshold: Int,
        val places: List<PlaceCandidate>,
        val slots: List<ScheduleSlotDraft>,
        val chosenPlaces: List<PlaceCandidate>,
        val shortlistedPerSlot: List<SlotShortlist>,
    )

    private fun rememberUsedPlacesByOrder(
        usedPlaceIdsByOrder: MutableMap<Int, MutableSet<Long>>,
        usedPlaceKeysByOrder: MutableMap<Int, MutableSet<String>>,
        slots: List<ScheduleSlotDraft>,
    ) {
        slots.forEach { slot ->
            usedPlaceIdsByOrder.getOrPut(slot.orderIndex) { mutableSetOf() }.add(slot.placeId)
            usedPlaceKeysByOrder.getOrPut(slot.orderIndex) { mutableSetOf() }.addAll(scheduleSlotPlaceKeys(slot))
        }
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

    private fun previousPlaceInSameTripDay(
        targets: List<TargetVector>,
        currentIndex: Int,
        chosenPlaces: List<PlaceCandidate>,
    ): PlaceCandidate? {
        if (currentIndex <= 0) return null
        val previousTarget = targets.getOrNull(currentIndex - 1) ?: return null
        val currentTarget = targets.getOrNull(currentIndex) ?: return null
        val sameTripDay = previousTarget.startTime.atZone(ZoneId.of("Asia/Seoul")).toLocalDate() ==
            currentTarget.startTime.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
        return chosenPlaces.lastOrNull()?.takeIf { sameTripDay }
    }

    private fun rankPlaces(
        targetVector: AxisScores,
        places: List<PlaceCandidate>,
        usedPlaceIds: Set<Long>,
        usedPlaceKeys: Set<String>,
        avoidPlaceIds: Set<Long>,
        avoidPlaceKeys: Set<String>,
        previousPlace: PlaceCandidate?,
        preferHiddenGem: Boolean,
        mustBeHiddenGem: Boolean,
        tripDate: String,
        profile: SlotSelectionProfile,
        recentPlaceIds: Set<Long>,
        diversitySalt: Long,
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

        val currentOptionPlaceKeys = usedPlaceKeys + places
            .filter { it.id in usedPlaceIds }
            .flatMap { placeCandidateKeys(it) }
            .toSet()
        val softAvoidPlaceKeys = avoidPlaceKeys + places
            .filter { it.id in avoidPlaceIds }
            .flatMap { placeCandidateKeys(it) }
            .toSet()
        fun List<PlaceCandidate>.withoutCurrentOptionPlaces(): List<PlaceCandidate> = filter {
            it.id !in usedPlaceIds && placeCandidateKeys(it).none { key -> key in currentOptionPlaceKeys }
        }
        fun List<PlaceCandidate>.withoutSoftAvoidPlaces(): List<PlaceCandidate> = filter {
            it.id !in avoidPlaceIds && placeCandidateKeys(it).none { key -> key in softAvoidPlaceKeys }
        }

        val currentOptionUnused = pool.withoutCurrentOptionPlaces()
        val preferredUnused = currentOptionUnused.withoutSoftAvoidPlaces()
        val broadlyPreferredUnused = places.withoutCurrentOptionPlaces().withoutSoftAvoidPlaces()
        val broadlyCurrentOptionUnused = places.withoutCurrentOptionPlaces()
        pool = when {
            preferredUnused.isNotEmpty() -> preferredUnused
            broadlyPreferredUnused.isNotEmpty() -> broadlyPreferredUnused
            currentOptionUnused.isNotEmpty() -> currentOptionUnused
            broadlyCurrentOptionUnused.isNotEmpty() -> broadlyCurrentOptionUnused
            else -> emptyList()
        }

        return pool.distinctBy { normalizedPlaceName(it) }.sortedByDescending {
            placeRankingScore(it, targetVector, previousPlace, preferHiddenGem, usedPlaceIds + avoidPlaceIds, recentPlaceIds, tripDate, profile, diversitySalt)
        }
    }

    private fun placeCandidateKeys(place: PlaceCandidate): Set<String> {
        val name = normalizedPlaceName(place)
        val address = normalizedPlaceAddress(place.address)
        return setOf(name, "$name|$address").filter { it.isNotBlank() }.toSet()
    }

    private fun scheduleSlotPlaceKeys(slot: ScheduleSlotDraft): Set<String> {
        val name = normalizePlaceText(slot.placeName)
        val address = normalizedPlaceAddress(slot.placeAddress)
        return setOf(name, "$name|$address").filter { it.isNotBlank() }.toSet()
    }

    private fun normalizedPlaceName(place: PlaceCandidate): String = normalizePlaceText(place.name)

    private fun normalizedPlaceAddress(address: String): String = normalizePlaceText(address)

    private fun normalizePlaceText(value: String): String {
        return value.trim().lowercase().replace(Regex("[\\s\\p{Punct}]+"), "")
    }

    private fun placeRankingScore(
        place: PlaceCandidate,
        targetVector: AxisScores,
        previousPlace: PlaceCandidate?,
        preferHiddenGem: Boolean,
        usedPlaceIds: Set<Long>,
        recentPlaceIds: Set<Long>,
        tripDate: String,
        profile: SlotSelectionProfile,
        diversitySalt: Long,
    ): Double {
        var score = calculateVectorMatch(targetVector, placeScores(place))
        if (usedPlaceIds.contains(place.id)) score -= 0.2
        if (recentPlaceIds.contains(place.id)) score -= 0.28
        if (previousPlace != null && previousPlace.category == place.category) score -= 0.08
        if (previousPlace != null) {
            val distance = distanceKm(previousPlace, place)
            when {
                distance == null -> score -= 0.03
                distance <= 3.0 -> score += 0.08
                distance <= 8.0 -> score += 0.03
                distance > 20.0 -> score -= 0.35
                distance > 12.0 -> score -= 0.18
            }
        }
        if (hasUnknownHours(place)) score -= 0.08
        score += regionalCoexistenceModifier(place, preferHiddenGem, profile)

        val operatingAvailability = isPlaceOpenDuringSlot(place, profile)
        when (operatingAvailability) {
            true -> score += 0.05
            false -> score -= 0.35
            else -> {}
        }

        score += placeCategoryModifier(place, tripDate, profile)
        score += diversityJitter(place.id, diversitySalt)
        return score
    }

    private fun diversityJitter(placeId: Long, salt: Long): Double {
        if (salt == 0L) return 0.0
        val mixed = placeId * 1103515245L + salt * 12345L
        val bucket = Math.floorMod(mixed, 1000L)
        return bucket / 1000.0 * 0.04
    }

    private fun distanceKm(from: PlaceCandidate, to: PlaceCandidate): Double? {
        val fromLat = from.latitude ?: return null
        val fromLon = from.longitude ?: return null
        val toLat = to.latitude ?: return null
        val toLon = to.longitude ?: return null
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(toLat - fromLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusKm * c
    }

    private fun regionalCoexistenceModifier(
        place: PlaceCandidate,
        preferRegionalBenefit: Boolean,
        profile: SlotSelectionProfile,
    ): Double {
        val popularity = place.externalPopularityScore
        val confidenceBonus = (place.externalSignalConfidence / 100.0) * 0.04
        val regionalBenefitBonus = if (place.isRegionalBenefit || isHiddenGem(place)) {
            if (preferRegionalBenefit) 0.22 else 0.08
        } else {
            0.0
        }
        val anchorBridgeBonus = if (!preferRegionalBenefit && popularity != null && popularity >= 70 && (profile.isEarlySlot || profile.isFinalSlot)) {
            0.10
        } else {
            0.0
        }
        val overPopularPenalty = if (preferRegionalBenefit && popularity != null && popularity >= 80) -0.06 else 0.0
        return confidenceBonus + regionalBenefitBonus + anchorBridgeBonus + overPopularPenalty
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
        if (place.isRegionalBenefit) return true
        val tags = place.metadataTags ?: return false
        val tagList = tags["tags"] as? List<*>
        if (tagList != null) {
            return tagList.any { it in listOf("hidden_gem", "population_decline", "regional_benefit") }
        }
        return tags["hiddenGem"] == true ||
            tags["populationDeclineArea"] == true ||
            tags["regionalBenefit"] == true ||
            tags["regionType"] == "population_decline"
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

    data class ScheduleWindow(
        val tripDate: LocalDate,
        val startMinutes: Int,
        val endMinutes: Int,
    ) {
        val totalMinutes: Int = endMinutes - startMinutes
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
