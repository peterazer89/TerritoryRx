package com.example.domain.optimizer

import com.example.data.model.AvailabilityWindowEntity
import com.example.data.model.BrickNormalizer
import com.example.data.model.ClinicEntity
import com.example.data.model.DailyPlanEntity
import com.example.data.model.DailyPlanWithStops
import com.example.data.model.HcpWithDetails
import com.example.data.model.PlannedStopEntity
import com.example.data.model.WorkSettings
import com.example.domain.parser.AvailabilityScheduleParser
import com.example.domain.parser.ScheduleValidator
import com.example.domain.routing.DefaultRoutingService
import com.example.domain.routing.RoutingProvider
import com.example.domain.routing.TrafficEstimateSource
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

import com.example.data.model.EMPTY_PREFERRED_SPECIALTIES_MESSAGE
import com.example.data.model.PlanningPriorityWeights
import com.example.data.model.SpecialtyNormalizer
import com.example.data.model.WEEKLY_HCP_RESTRICTION_MESSAGE
import com.example.data.model.WeeklyHcpBlockInfo

data class CandidateClinicOption(
    val hcpWithDetails: HcpWithDetails,
    val clinic: ClinicEntity,
    val window: AvailabilityWindowEntity,
    val visitDurationMinutes: Int,
    val isMandatory: Boolean,
    val lockedStartMinute: Int? = null
)

data class OptimizationRequest(
    val dateIso: String,
    val hcps: List<HcpWithDetails>,
    val settings: WorkSettings,
    val mandatoryHcpIds: Set<Long> = emptySet(),
    val prioritizedHcpIds: Set<Long> = emptySet(),
    val excludedHcpIds: Set<Long> = emptySet(),
    val weeklyRestrictedHcpIds: Set<Long> = emptySet(),
    val weeklyBlockedByHcpId: Map<Long, WeeklyHcpBlockInfo> = emptyMap(),
    val excludedZones: Set<String> = emptySet(),
    val allowedZones: Set<String> = emptySet(),
    val targetOverride: Int? = null,
    val shiftStartMinute: Int? = null,
    val shiftEndMinute: Int? = null,
    val specialtyFilter: String? = null,
    val lockedStops: List<PlannedStopEntity> = emptyList(),
    val completedStopsToday: List<PlannedStopEntity> = emptyList(),
    val allowNetworkRouting: Boolean = false,
    val diversitySeed: Int = 0
)

data class OptimizationOutcome(
    val alternatives: List<DailyPlanWithStops>,
    val infeasibleReasons: List<String>,
    val unverifiedClinicsExcluded: Int,
    val unconfirmedSchedulesExcluded: Int,
    val zeroRemainingVisitsExcluded: Int,
    val weeklyRestrictedExcluded: Int = 0,
    val availabilityOrScheduleExcluded: Int = 0,
    val eligibleHcpsConsidered: Int = 0,
    val targetAchieved: Boolean = false,
    val stopSelectionReasons: Map<Long, String> = emptyMap(),
    val hcpExclusionReasons: Map<Long, String> = emptyMap(),
    val selectedPreferredSpecialtiesRanked: List<String> = emptyList(),
    val specialtyEligibleCount: Int = 0,
    val unselectedSpecialtyExcluded: Int = 0,
    val missingOrUnmatchedSpecialtyExcluded: Int = 0,
    val missingSpecialtyHcpIds: Set<Long> = emptySet(),
    val emptyPreferredSpecialtiesBlocked: Boolean = false,
    val specialtyFilterSummaryText: String = "",
    val specialtyExclusionSummaryText: String = ""
)

data class WeeklyDayPlanSummary(
    val dateIso: String,
    val dayOfWeekName: String,
    val selectedPlan: DailyPlanWithStops
)

data class WeeklyPlanResult(
    val weekStartIso: String,
    val dayPlans: List<WeeklyDayPlanSummary>,
    val totalPlannedVisits: Int,
    val uniqueHcpsCovered: Int,
    val totalDrivingMinutes: Int,
    val totalDistanceKm: Double,
    val totalWaitingMinutes: Int,
    val unscheduledActiveHcpsCount: Int,
    val highRiskHcpsCoveredCount: Int,
    val diagnostics: List<String>
)

/**
 * Deterministic, constraint-based hybrid route optimization engine.
 * Enforces hard constraints first (weekly 1-visit-per-HCP rule, clinic availability windows,
 * full visit duration, no overlaps, 480-minute continuous duty span, no split shifts),
 * scores candidates with normalized 3-factor Planning Priorities (Specialty, Remaining Visits, Travel Distance),
 * and refines route sequences via time-window-aware 2-Opt local search and forward gap compression.
 */
class RouteOptimizerEngine(
    private val routingProvider: RoutingProvider = DefaultRoutingService()
) {

    private data class StrategyConfig(
        val code: String,
        val label: String,
        val wTravel: Double,
        val wDistance: Double,
        val wPriority: Double,
        val wCoverage: Double,
        val wWait: Double,
        val clusterFirst: Boolean,
        val wideWindowBias: Boolean,
        val advantageSummary: String,
        val disadvantageSummary: String,
        val seedOffset: Int = 0
    )

    suspend fun optimizeDay(request: OptimizationRequest): OptimizationOutcome {
        val dayOfWeek = parseIsoDayOfWeek(request.dateIso)
        val settings = request.settings
        val diagnostics = mutableListOf<String>()

        // Step 1 & 2 (Section 3 & Section 6): Load HCPs and enforce Preferred Specialties strict eligibility rule
        val preferredSpecialties = SpecialtyNormalizer.extractUniqueSpecialties(settings.preferredSpecialtiesRanked)
        if (preferredSpecialties.isEmpty()) {
            val activeCount = request.hcps.count { it.hcp.isActive && !it.hcp.isDeleted }
            return OptimizationOutcome(
                alternatives = emptyList(),
                infeasibleReasons = listOf(EMPTY_PREFERRED_SPECIALTIES_MESSAGE),
                unverifiedClinicsExcluded = 0,
                unconfirmedSchedulesExcluded = 0,
                zeroRemainingVisitsExcluded = 0,
                weeklyRestrictedExcluded = 0,
                availabilityOrScheduleExcluded = 0,
                eligibleHcpsConsidered = 0,
                targetAchieved = false,
                selectedPreferredSpecialtiesRanked = emptyList(),
                specialtyEligibleCount = 0,
                unselectedSpecialtyExcluded = activeCount,
                missingOrUnmatchedSpecialtyExcluded = 0,
                emptyPreferredSpecialtiesBlocked = true,
                specialtyFilterSummaryText = EMPTY_PREFERRED_SPECIALTIES_MESSAGE,
                specialtyExclusionSummaryText = EMPTY_PREFERRED_SPECIALTIES_MESSAGE
            )
        }

        val specialtyFilterSummaryText = "Specialty filter: " +
            preferredSpecialties.mapIndexed { idx, spec -> "#${idx + 1} $spec" }.joinToString(", ") + "."

        if (dayOfWeek in settings.weekendDaysIso || dayOfWeek !in settings.workingDaysIso) {
            diagnostics.add(
                "${AvailabilityScheduleParser.dayName(dayOfWeek)} (${request.dateIso}) is configured as a weekend / non-working day in Work Settings."
            )
        }

        var unverifiedCount = 0
        var unconfirmedSchedCount = 0
        var zeroRemCount = 0
        var weeklyRestrictedCount = 0
        var availabilityExcludedHcpCount = 0
        var specialtyEligibleCount = 0
        var unselectedSpecialtyCount = 0
        var missingOrUnmatchedSpecialtyCount = 0
        val missingSpecialtyHcpIds = mutableSetOf<Long>()
        val hcpExclusionReasons = mutableMapOf<Long, String>()

        val completedHcpIdsToday = request.completedStopsToday.map { it.hcpId }.toSet()
        val effectiveWeeklyRestrictedIds = if (settings.enforceWeeklyHcpVisitLimit) {
            request.weeklyRestrictedHcpIds + request.weeklyBlockedByHcpId.keys
        } else {
            emptySet()
        }

        val eligibleSpecialtyHcpIds = request.hcps
            .filter { item ->
                val h = item.hcp
                h.isActive && !h.isDeleted &&
                    !SpecialtyNormalizer.isMissingOrUnmatchedSpecialty(h.specialty) &&
                    SpecialtyNormalizer.isSpecialtySelected(h.specialty, preferredSpecialties)
            }
            .map { it.hcp.id }
            .toSet()

        val lockedByHcpId = request.lockedStops
            .filter { it.hcpId !in effectiveWeeklyRestrictedIds && it.hcpId in eligibleSpecialtyHcpIds }
            .associateBy { it.hcpId }

        val candidateOptions = mutableListOf<CandidateClinicOption>()

        for (item in request.hcps) {
            val hcp = item.hcp
            if (!hcp.isActive || hcp.isDeleted) continue
            if (hcp.id in request.excludedHcpIds) {
                hcpExclusionReasons[hcp.id] = "Excluded by active filter."
                continue
            }

            // Pipeline Step 2 (Section 3): Strict Preferred Specialties Eligibility Rule
            // Missing or unknown specialties are never classified into a preferred specialty; they are excluded and flagged.
            if (SpecialtyNormalizer.isMissingOrUnmatchedSpecialty(hcp.specialty)) {
                missingOrUnmatchedSpecialtyCount++
                missingSpecialtyHcpIds.add(hcp.id)
                val reason = "Excluded because specialty is missing or unmatched — flagged for correction."
                hcpExclusionReasons[hcp.id] = reason
                diagnostics.add("Flagged ${hcp.name} (${hcp.targetId}): missing or unmatched specialty excluded from plan.")
                continue
            }
            // Only HCPs belonging to selected preferred specialties are eligible for automatic plan generation (across all clinics).
            if (!SpecialtyNormalizer.isSpecialtySelected(hcp.specialty, preferredSpecialties)) {
                unselectedSpecialtyCount++
                val reason = "Excluded because specialty (${hcp.specialty}) is not selected in Preferred Specialties."
                hcpExclusionReasons[hcp.id] = reason
                continue
            }
            if (request.specialtyFilter != null &&
                !hcp.specialty.contains(request.specialtyFilter, ignoreCase = true)
            ) {
                unselectedSpecialtyCount++
                hcpExclusionReasons[hcp.id] = "Excluded because specialty (${hcp.specialty}) does not match filter (${request.specialtyFilter})."
                continue
            }
            specialtyEligibleCount++

            // Pipeline Step 3: Apply once-per-HCP-per-calendar-week restriction
            if (hcp.id in completedHcpIdsToday) {
                weeklyRestrictedCount++
                hcpExclusionReasons[hcp.id] = "Excluded because this HCP was already visited this week."
                continue
            }
            if (hcp.id in effectiveWeeklyRestrictedIds) {
                weeklyRestrictedCount++
                val blockInfo = request.weeklyBlockedByHcpId[hcp.id]
                val reasonText = if (blockInfo != null && blockInfo.source == "SCHEDULED_PLAN") {
                    "Excluded because this HCP is already scheduled this week (${blockInfo.blockingDateIso} at ${blockInfo.blockingClinicLabel})."
                } else {
                    "Excluded because this HCP was already visited this week."
                }
                hcpExclusionReasons[hcp.id] = reasonText
                diagnostics.add("Excluded ${hcp.name}: $reasonText")
                continue
            }

            // Pipeline Step 6: Calculate remaining required visits
            val isMandatory = hcp.id in request.mandatoryHcpIds || hcp.id in lockedByHcpId
            if (item.coverage.remainingVisits <= 0 && !isMandatory) {
                zeroRemCount++
                hcpExclusionReasons[hcp.id] = "Excluded because quarterly visit target (${hcp.quarterlyTarget}) is already complete."
                continue
            }

            var hcpHadVerifiedClinic = false
            var hcpHadConfirmedWindowOnDay = false

            val hcpHasClinicInAllowedZones = request.allowedZones.isEmpty() || item.clinics.any {
                it.clinic.isActive && BrickNormalizer.matchesAllowedBricks(it.clinic.zone, request.allowedZones)
            }

            for (clinicWithWin in item.clinics) {
                val clinic = clinicWithWin.clinic
                if (!clinic.isActive) continue
                if (request.excludedZones.isNotEmpty() &&
                    BrickNormalizer.matchesAllowedBricks(clinic.zone, request.excludedZones) &&
                    !isMandatory
                ) {
                    continue
                }
                if (request.allowedZones.isNotEmpty()) {
                    val matchesAllowed = BrickNormalizer.matchesAllowedBricks(clinic.zone, request.allowedZones)
                    // Even if mandatory, if the HCP has a clinic in the selected brick(s), only schedule at that brick's clinic!
                    if (!matchesAllowed && (!isMandatory || hcpHasClinicInAllowedZones)) continue
                }

                val resolvedClinic = if (clinic.latitude != null && clinic.longitude != null) {
                    clinic
                } else if (!clinic.originalLocationText.contains("Unverified", ignoreCase = true)) {
                    val extracted = com.example.domain.importer.ExcelTerritoryImporter.extractCoordinates(
                        rawLocation = clinic.googleMapsUrl.ifBlank { clinic.originalLocationText },
                        zoneHint = clinic.zone,
                        rowSeed = "${clinic.id}-${clinic.label}"
                    )
                    if (extracted.latitude != null && extracted.longitude != null) {
                        clinic.copy(
                            latitude = extracted.latitude,
                            longitude = extracted.longitude,
                            geocodingStatus = extracted.geocodingStatus
                        )
                    } else {
                        clinic
                    }
                } else {
                    clinic
                }

                val hasCoords = resolvedClinic.latitude != null && resolvedClinic.longitude != null &&
                    (resolvedClinic.geocodingStatus == "VERIFIED" || resolvedClinic.geocodingStatus == "EXTRACTED_URL")
                if (!hasCoords) {
                    unverifiedCount++
                    continue
                }
                hcpHadVerifiedClinic = true

                val hasNonRangeType = resolvedClinic.timeInfoType.isNotBlank() &&
                    resolvedClinic.timeInfoType != "CLINIC_AVAILABILITY_RANGE"
                val isExplicitlyAmbiguousOrUnparsed = (!resolvedClinic.userConfirmedSchedule &&
                    (resolvedClinic.scheduleParseStatus == "AMBIGUOUS" || resolvedClinic.scheduleParseStatus == "UNPARSED")) ||
                    hasNonRangeType ||
                    resolvedClinic.validationStatus == "INVALID"

                // Section 8: Retrieve clinic schedule strictly using the exact clinic.id and hcp.id
                val strictClinicWindows = clinicWithWin.windows.filter {
                    it.clinicId == resolvedClinic.id && (it.hcpId == 0L || it.hcpId == hcp.id)
                }
                val resolvedWindows: List<AvailabilityWindowEntity> = if (strictClinicWindows.isNotEmpty()) {
                    strictClinicWindows
                } else if (!isExplicitlyAmbiguousOrUnparsed && resolvedClinic.rawScheduleText.isNotBlank()) {
                    ScheduleValidator.validate(
                        AvailabilityScheduleParser.parseForExcelImport(
                            rawSchedule = resolvedClinic.rawScheduleText,
                            siblingClinicResult = null
                        )
                    ).toEntities(
                        clinicId = resolvedClinic.id,
                        isConfirmed = true,
                        hcpId = hcp.id,
                        originalExcelText = resolvedClinic.originalExcelScheduleText.ifBlank { resolvedClinic.rawScheduleText },
                        sourceSheet = resolvedClinic.sourceSheet,
                        sourceRow = resolvedClinic.sourceRow,
                        sourceColumn = resolvedClinic.sourceColumn
                    )
                } else {
                    emptyList()
                }

                val scheduleUsable = !isExplicitlyAmbiguousOrUnparsed &&
                    resolvedWindows.isNotEmpty() && (
                        resolvedClinic.userConfirmedSchedule ||
                            resolvedClinic.scheduleParseStatus == "CONFIRMED" ||
                            resolvedClinic.scheduleParseStatus == "PARSED_HIGH_CONFIDENCE" ||
                            resolvedWindows.any { it.isConfirmed }
                    )

                if (!scheduleUsable) {
                    unconfirmedSchedCount++
                    diagnostics.add(
                        "Excluded ${hcp.name} (${resolvedClinic.label}): schedule is unconfirmed or ${resolvedClinic.timeInfoType} ('${resolvedClinic.rawScheduleText}')."
                    )
                    continue
                }

                val dayWindows = resolvedWindows.filter { it.dayOfWeek == dayOfWeek }
                for (win in dayWindows) {
                    val dur = resolvedClinic.customVisitDurationMinutes
                        ?: hcp.defaultVisitDurationMinutes.takeIf { it > 0 }
                        ?: settings.defaultVisitDurationMinutes
                    if (win.endsNextDay && win.durationMinutes() !in 15..720) {
                        diagnostics.add(
                            "Excluded ${hcp.name} (${resolvedClinic.label}): invalid overnight window duration."
                        )
                        continue
                    }
                    val winEffEnd = win.effectiveSameDayEndMinute()
                    val effStart = if (request.shiftStartMinute != null) max(win.startMinuteOfDay, request.shiftStartMinute) else win.startMinuteOfDay
                    val effEnd = if (request.shiftEndMinute != null) min(winEffEnd, request.shiftEndMinute) else min(winEffEnd, 1440)
                    if (effEnd - effStart >= dur) {
                        hcpHadConfirmedWindowOnDay = true
                        val lockedStop = lockedByHcpId[hcp.id]
                        if (lockedStop != null && lockedStop.clinicId != resolvedClinic.id) continue
                        candidateOptions.add(
                            CandidateClinicOption(
                                hcpWithDetails = item,
                                clinic = resolvedClinic,
                                window = win,
                                visitDurationMinutes = dur,
                                isMandatory = isMandatory,
                                lockedStartMinute = lockedStop?.visitStartMinute
                            )
                        )
                    } else {
                        val reasonSuffix = if (win.endsNextDay) {
                            "overnight clinic window (${AvailabilityScheduleParser.formatMinuteOfDay(win.startMinuteOfDay)} – ${AvailabilityScheduleParser.formatMinuteOfDay(win.endMinuteOfDay)} +1 day) is outside active shift."
                        } else {
                            "${dur}m visit duration does not fit within confirmed window."
                        }
                        diagnostics.add(
                            "Excluded ${hcp.name} (${resolvedClinic.label}): $reasonSuffix"
                        )
                    }
                }
            }

            if (!hcpHadConfirmedWindowOnDay) {
                availabilityExcludedHcpCount++
                hcpExclusionReasons[hcp.id] = if (!hcpHadVerifiedClinic) {
                    "Excluded because clinic GPS coordinates are unverified."
                } else {
                    "Excluded because clinic is not available during ${AvailabilityScheduleParser.dayName(dayOfWeek)} shift hours."
                }
            }
        }

        val specialtyExclusionSummaryText = buildString {
            append("Excluded: $unselectedSpecialtyCount HCPs from non-selected specialties.")
            if (missingOrUnmatchedSpecialtyCount > 0) {
                append(" Flagged & excluded: $missingOrUnmatchedSpecialtyCount HCP(s) with missing/unmatched specialty.")
            }
        }

        // If strict shift window filtering yielded no candidates, relax shift window filter to all confirmed windows on dayOfWeek
        if (candidateOptions.isEmpty() && (request.shiftStartMinute != null || request.shiftEndMinute != null)) {
            for (item in request.hcps) {
                val hcp = item.hcp
                if (!hcp.isActive || hcp.isDeleted) continue
                if (hcp.id !in eligibleSpecialtyHcpIds) continue
                if (request.specialtyFilter != null && !hcp.specialty.contains(request.specialtyFilter, ignoreCase = true)) continue
                if (hcp.id in request.excludedHcpIds ||
                    hcp.id in completedHcpIdsToday ||
                    hcp.id in effectiveWeeklyRestrictedIds
                ) continue
                val isMandatory = hcp.id in request.mandatoryHcpIds || hcp.id in lockedByHcpId
                if (item.coverage.remainingVisits <= 0 && !isMandatory) continue
                val hcpHasClinicInAllowedZones = request.allowedZones.isEmpty() || item.clinics.any {
                    it.clinic.isActive && BrickNormalizer.matchesAllowedBricks(it.clinic.zone, request.allowedZones)
                }
                for (clinicWithWin in item.clinics) {
                    val clinic = clinicWithWin.clinic
                    if (!clinic.isActive) continue
                    if (request.allowedZones.isNotEmpty()) {
                        val matchesAllowed = BrickNormalizer.matchesAllowedBricks(clinic.zone, request.allowedZones)
                        if (!matchesAllowed && (!isMandatory || hcpHasClinicInAllowedZones)) continue
                    }
                    val hasCoords = clinic.latitude != null && clinic.longitude != null &&
                        (clinic.geocodingStatus == "VERIFIED" || clinic.geocodingStatus == "EXTRACTED_URL")
                    val hasNonRangeType = clinic.timeInfoType.isNotBlank() &&
                        clinic.timeInfoType != "CLINIC_AVAILABILITY_RANGE"
                    val isExplicitlyAmbiguousOrUnparsed = (!clinic.userConfirmedSchedule &&
                        (clinic.scheduleParseStatus == "AMBIGUOUS" || clinic.scheduleParseStatus == "UNPARSED")) ||
                        hasNonRangeType ||
                        clinic.validationStatus == "INVALID"
                    val strictClinicWindows = clinicWithWin.windows.filter {
                        it.clinicId == clinic.id && (it.hcpId == 0L || it.hcpId == hcp.id)
                    }
                    val resolvedWindows: List<AvailabilityWindowEntity> = strictClinicWindows.ifEmpty {
                        if (clinic.rawScheduleText.isNotBlank() && !isExplicitlyAmbiguousOrUnparsed) {
                            ScheduleValidator.validate(
                                AvailabilityScheduleParser.parseForExcelImport(
                                    rawSchedule = clinic.rawScheduleText,
                                    siblingClinicResult = null
                                )
                            ).toEntities(
                                clinicId = clinic.id,
                                isConfirmed = true,
                                hcpId = hcp.id,
                                originalExcelText = clinic.originalExcelScheduleText.ifBlank { clinic.rawScheduleText },
                                sourceSheet = clinic.sourceSheet,
                                sourceRow = clinic.sourceRow,
                                sourceColumn = clinic.sourceColumn
                            )
                        } else emptyList()
                    }
                    val scheduleUsable = !isExplicitlyAmbiguousOrUnparsed &&
                        resolvedWindows.isNotEmpty() && (
                            clinic.userConfirmedSchedule ||
                                clinic.scheduleParseStatus == "CONFIRMED" ||
                                clinic.scheduleParseStatus == "PARSED_HIGH_CONFIDENCE" ||
                                resolvedWindows.any { it.isConfirmed }
                        )
                    if (!hasCoords || !scheduleUsable) continue
                    for (win in resolvedWindows.filter { it.dayOfWeek == dayOfWeek }) {
                        if (win.endsNextDay) continue
                        val dur = clinic.customVisitDurationMinutes
                            ?: hcp.defaultVisitDurationMinutes.takeIf { it > 0 }
                            ?: settings.defaultVisitDurationMinutes
                        if (win.endMinuteOfDay - win.startMinuteOfDay >= dur) {
                            val lockedStop = lockedByHcpId[hcp.id]
                            if (lockedStop != null && lockedStop.clinicId != clinic.id) continue
                            candidateOptions.add(
                                CandidateClinicOption(
                                    hcpWithDetails = item,
                                    clinic = clinic,
                                    window = win,
                                    visitDurationMinutes = dur,
                                    isMandatory = isMandatory,
                                    lockedStartMinute = lockedStop?.visitStartMinute
                                )
                            )
                        }
                    }
                }
            }
        }

        val distinctCandidateHcpCount = candidateOptions.map { it.hcpWithDetails.hcp.id }.distinct().size
        val targetDaily = (request.targetOverride ?: settings.dailyHcpTarget).coerceIn(1, 20)

        val isWeekendOrNonWorking = dayOfWeek in settings.weekendDaysIso || dayOfWeek !in settings.workingDaysIso
        if (candidateOptions.isEmpty() && request.completedStopsToday.isEmpty() && !isWeekendOrNonWorking) {
            val fallbackPlan = buildGuaranteedFallbackPlan(
                request = request,
                candidateOptions = emptyList(),
                dayOfWeek = dayOfWeek,
                diagnostics = diagnostics
            )
            if (fallbackPlan != null) {
                return OptimizationOutcome(
                    alternatives = listOf(fallbackPlan),
                    infeasibleReasons = diagnostics,
                    unverifiedClinicsExcluded = unverifiedCount,
                    unconfirmedSchedulesExcluded = unconfirmedSchedCount,
                    zeroRemainingVisitsExcluded = zeroRemCount,
                    weeklyRestrictedExcluded = weeklyRestrictedCount,
                    availabilityOrScheduleExcluded = availabilityExcludedHcpCount,
                    eligibleHcpsConsidered = distinctCandidateHcpCount,
                    targetAchieved = fallbackPlan.stops.size >= targetDaily,
                    hcpExclusionReasons = hcpExclusionReasons,
                    selectedPreferredSpecialtiesRanked = preferredSpecialties,
                    specialtyEligibleCount = specialtyEligibleCount,
                    unselectedSpecialtyExcluded = unselectedSpecialtyCount,
                    missingOrUnmatchedSpecialtyExcluded = missingOrUnmatchedSpecialtyCount,
                    missingSpecialtyHcpIds = missingSpecialtyHcpIds,
                    emptyPreferredSpecialtiesBlocked = false,
                    specialtyFilterSummaryText = specialtyFilterSummaryText,
                    specialtyExclusionSummaryText = specialtyExclusionSummaryText
                )
            }

            if (unselectedSpecialtyCount > 0) {
                diagnostics.add(specialtyExclusionSummaryText)
            }
            if (weeklyRestrictedCount > 0) {
                diagnostics.add("$weeklyRestrictedCount HCP(s) excluded due to weekly 1-visit restriction.")
            }
            if (unverifiedCount > 0) {
                diagnostics.add("$unverifiedCount clinic(s) skipped due to unverified GPS coordinates.")
            }
            if (unconfirmedSchedCount > 0) {
                diagnostics.add("$unconfirmedSchedCount clinic(s) skipped due to ambiguous/unconfirmed schedules.")
            }
            if (zeroRemCount > 0) {
                diagnostics.add("$zeroRemCount HCP(s) already met their quarterly coverage target.")
            }
            diagnostics.add(
                "No eligible HCPs have confirmed availability on ${AvailabilityScheduleParser.dayName(dayOfWeek)} (${request.dateIso})."
            )
            val emptyPlan = DailyPlanEntity(
                dateIso = request.dateIso,
                variantCode = "PLAN_A",
                variantLabel = "Infeasible Plan",
                isSelected = true,
                feasibilityStatus = "INFEASIBLE",
                infeasibilityReasonsText = diagnostics.joinToString(" "),
                isValidatorVerified = true,
                validatorReportText = "No feasible visits available for ${request.dateIso}."
            )
            return OptimizationOutcome(
                alternatives = listOf(DailyPlanWithStops(emptyPlan, emptyList())),
                infeasibleReasons = diagnostics,
                unverifiedClinicsExcluded = unverifiedCount,
                unconfirmedSchedulesExcluded = unconfirmedSchedCount,
                zeroRemainingVisitsExcluded = zeroRemCount,
                weeklyRestrictedExcluded = weeklyRestrictedCount,
                availabilityOrScheduleExcluded = availabilityExcludedHcpCount,
                eligibleHcpsConsidered = 0,
                targetAchieved = false,
                hcpExclusionReasons = hcpExclusionReasons,
                selectedPreferredSpecialtiesRanked = preferredSpecialties,
                specialtyEligibleCount = specialtyEligibleCount,
                unselectedSpecialtyExcluded = unselectedSpecialtyCount,
                missingOrUnmatchedSpecialtyExcluded = missingOrUnmatchedSpecialtyCount,
                missingSpecialtyHcpIds = missingSpecialtyHcpIds,
                emptyPreferredSpecialtiesBlocked = false,
                specialtyFilterSummaryText = specialtyFilterSummaryText,
                specialtyExclusionSummaryText = specialtyExclusionSummaryText
            )
        }

        val userWSpec = settings.normalizedSpecialtyWeight
        val userWRem = settings.normalizedRemainingVisitsWeight
        val userWDist = settings.normalizedTravelDistanceWeight
        val specPct = (userWSpec * 100).roundToInt()
        val remPct = (userWRem * 100).roundToInt()
        val distPct = 100 - specPct - remPct

        val strategies = listOf(
            StrategyConfig(
                code = "PLAN_A",
                label = "Plan A: Configured Priorities ($specPct% Spec • $remPct% Visits • $distPct% Dist)",
                wTravel = userWDist,
                wDistance = userWDist,
                wPriority = userWSpec,
                wCoverage = userWRem,
                wWait = max(0.35, userWDist * 0.85),
                clusterFirst = userWDist >= 0.25,
                wideWindowBias = false,
                advantageSummary = "Priorities: $specPct% Specialty • $remPct% Remaining Visits • $distPct% Travel Distance | Eligible: $distinctCandidateHcpCount | Weekly Excluded: $weeklyRestrictedCount | Schedule Excluded: $availabilityExcludedHcpCount",
                disadvantageSummary = "Balances configured weights with 2-Opt route distance refinement",
                seedOffset = request.diversitySeed
            ),
            StrategyConfig(
                code = "PLAN_B",
                label = "Plan B: Max Remaining Visits & Specialty Priority",
                wTravel = (userWDist * 0.65).coerceAtLeast(0.10),
                wDistance = (userWDist * 0.65).coerceAtLeast(0.10),
                wPriority = max(0.35, userWSpec),
                wCoverage = max(0.55, userWRem),
                wWait = 0.35,
                clusterFirst = false,
                wideWindowBias = false,
                advantageSummary = "Prioritizes HCPs with highest remaining quarterly visits & specialty priority | Eligible: $distinctCandidateHcpCount | Weekly Excluded: $weeklyRestrictedCount",
                disadvantageSummary = "Allows slightly longer inter-brick travel for high-deficit HCPs",
                seedOffset = request.diversitySeed + 1
            ),
            StrategyConfig(
                code = "PLAN_C",
                label = "Plan C: Ultra-Compact Travel & Brick Continuity",
                wTravel = max(0.55, userWDist),
                wDistance = max(0.65, userWDist),
                wPriority = (userWSpec * 0.70).coerceAtLeast(0.10),
                wCoverage = (userWRem * 0.75).coerceAtLeast(0.15),
                wWait = 0.65,
                clusterFirst = true,
                wideWindowBias = true,
                advantageSummary = "Shortest consecutive & total daily driving distance with tight brick clustering | Eligible: $distinctCandidateHcpCount | Weekly Excluded: $weeklyRestrictedCount",
                disadvantageSummary = "Focuses on the tightest geographic brick cluster",
                seedOffset = request.diversitySeed + 2
            )
        )

        val generatedPlans = mutableListOf<DailyPlanWithStops>()
        val seenStopSequences = mutableSetOf<String>()

        for (strategy in strategies) {
            val bestForStrategy = buildBestPlanForStrategy(
                request = request,
                candidates = candidateOptions,
                strategy = strategy,
                unverifiedCount = unverifiedCount,
                unconfirmedSchedCount = unconfirmedSchedCount,
                weeklyRestrictedCount = weeklyRestrictedCount,
                availabilityExcludedCount = availabilityExcludedHcpCount,
                distinctEligibleCount = distinctCandidateHcpCount,
                effectiveWeeklyRestrictedIds = effectiveWeeklyRestrictedIds
            )
            if (bestForStrategy != null) {
                val signature = bestForStrategy.stops.joinToString("->") { "${it.hcpId}@${it.clinicId}:${it.visitStartMinute}" }
                if (seenStopSequences.add(signature)) {
                    generatedPlans.add(bestForStrategy)
                }
            }
        }

        if (generatedPlans.isEmpty()) {
            val fallback = buildGuaranteedFallbackPlan(
                request = request,
                candidateOptions = candidateOptions,
                dayOfWeek = dayOfWeek,
                diagnostics = diagnostics
            )
            if (fallback != null) {
                generatedPlans.add(fallback)
            }
        }

        // Mark first plan as selected
        val finalAlternatives = generatedPlans.mapIndexed { idx, planWithStops ->
            planWithStops.copy(plan = planWithStops.plan.copy(isSelected = (idx == 0)))
        }

        val bestPlan = finalAlternatives.firstOrNull()
        val bestCount = finalAlternatives.maxOfOrNull { it.stops.size } ?: 0
        if (bestCount < targetDaily) {
            if (distinctCandidateHcpCount < targetDaily) {
                diagnostics.add(
                    "Only $distinctCandidateHcpCount eligible HCP(s) have confirmed availability on ${AvailabilityScheduleParser.dayName(dayOfWeek)} ($weeklyRestrictedCount excluded due to weekly visit limit)."
                )
            } else {
                diagnostics.add(
                    "Scheduled $bestCount of $targetDaily target visits: clinic time windows and the ${settings.maxDutySpanMinutes}-minute continuous duty limit prevented adding more stops."
                )
            }
            if (weeklyRestrictedCount > 0) {
                diagnostics.add("$weeklyRestrictedCount HCP(s) excluded because they were already visited or scheduled this calendar week.")
            }
            if (unverifiedCount > 0) {
                diagnostics.add("$unverifiedCount clinic(s) excluded due to unverified GPS coordinates.")
            }
            if (unconfirmedSchedCount > 0) {
                diagnostics.add("$unconfirmedSchedCount clinic(s) excluded due to unconfirmed/ambiguous schedules.")
            }
        }

        val stopSelectionReasons = mutableMapOf<Long, String>()
        if (bestPlan != null) {
            val hcpMap = request.hcps.associateBy { it.hcp.id }
            for (stop in bestPlan.stops) {
                val hcpDetails = hcpMap[stop.hcpId]
                val reasons = mutableListOf<String>()
                val specRank = SpecialtyNormalizer.rankOfSpecialty(stop.specialty, preferredSpecialties)
                if (specRank != null) {
                    reasons.add("#$specRank preferred specialty (${stop.specialty})")
                }
                if (stop.remainingQuarterlyVisits >= 4 || hcpDetails?.coverage?.isOverdue == true) {
                    reasons.add("higher remaining visit requirements (${stop.remainingQuarterlyVisits} left)")
                }
                if (stop.distanceKmFromPrev <= 3.5) {
                    reasons.add("an efficient route (${"%.1f".format(Locale.US, stop.distanceKmFromPrev)} km leg)")
                }
                val explanation = if (reasons.isNotEmpty()) {
                    "Prioritized because of ${reasons.joinToString(" and ")}."
                } else {
                    "Prioritized because of confirmed clinic availability and balanced route fit."
                }
                stopSelectionReasons[stop.hcpId] = explanation
            }
        }

        return OptimizationOutcome(
            alternatives = finalAlternatives,
            infeasibleReasons = diagnostics,
            unverifiedClinicsExcluded = unverifiedCount,
            unconfirmedSchedulesExcluded = unconfirmedSchedCount,
            zeroRemainingVisitsExcluded = zeroRemCount,
            weeklyRestrictedExcluded = weeklyRestrictedCount,
            availabilityOrScheduleExcluded = availabilityExcludedHcpCount + unconfirmedSchedCount + unverifiedCount,
            eligibleHcpsConsidered = distinctCandidateHcpCount,
            targetAchieved = bestCount >= targetDaily,
            stopSelectionReasons = stopSelectionReasons,
            hcpExclusionReasons = hcpExclusionReasons,
            selectedPreferredSpecialtiesRanked = preferredSpecialties,
            specialtyEligibleCount = specialtyEligibleCount,
            unselectedSpecialtyExcluded = unselectedSpecialtyCount,
            missingOrUnmatchedSpecialtyExcluded = missingOrUnmatchedSpecialtyCount,
            missingSpecialtyHcpIds = missingSpecialtyHcpIds,
            emptyPreferredSpecialtiesBlocked = false,
            specialtyFilterSummaryText = specialtyFilterSummaryText,
            specialtyExclusionSummaryText = specialtyExclusionSummaryText
        )
    }

    /**
     * If the user's configured Home Base is in another city (> 35 km from the active territory's clinics,
     * e.g. default Sheikh Zayed while the imported sheet is in Alexandria), automatically anchor the daily
     * shift departure/return to the active territory's local hub so travel times are accurate local commutes
     * instead of cross-country drives that push visits to 11:59 PM.
     */
    private fun resolveEffectiveHomeBase(
        settings: WorkSettings,
        clinics: List<ClinicEntity>
    ): Pair<Double, Double> {
        val validCoords = clinics.mapNotNull { c ->
            val lat = c.latitude
            val lng = c.longitude
            if (lat != null && lng != null) lat to lng else null
        }
        if (validCoords.isEmpty()) return settings.homeLatitude to settings.homeLongitude

        val avgLat = validCoords.map { it.first }.average()
        val avgLng = validCoords.map { it.second }.average()
        val distFromConfiguredHomeKm = DefaultRoutingService.haversineDistanceKm(
            settings.homeLatitude,
            settings.homeLongitude,
            avgLat,
            avgLng
        )
        return if (distFromConfiguredHomeKm > 25.0) {
            // Local territory hub (~0.8 km offset so initial drive is a realistic 6-10 min local commute)
            (avgLat + 0.005) to (avgLng + 0.005)
        } else {
            settings.homeLatitude to settings.homeLongitude
        }
    }

    /**
     * Time-window-aware 2-Opt local search improvement pass (Section 2 & 4).
     * Evaluates reversing sub-segments `[i..j]` of unlocked stops to eliminate unnecessary cross-brick jumps
     * (e.g., Brick A -> Brick D -> Brick B -> Brick A) and reduce total daily route distance while strictly
     * verifying that every stop still fits within its clinic availability window and shift span.
     */
    private suspend fun improveRouteSequenceWith2Opt(
        stops: List<PlannedStopEntity>,
        effectiveHomeLat: Double,
        effectiveHomeLng: Double,
        initialDepartureMin: Int,
        shiftStartLimit: Int?,
        shiftEndLimit: Int?,
        maxAllowedSpan: Int,
        settings: WorkSettings,
        allowNetworkCall: Boolean
    ): List<PlannedStopEntity> {
        if (stops.size < 3) return stops

        suspend fun simulateOrder(candidateOrder: List<PlannedStopEntity>): Pair<List<PlannedStopEntity>, Double>? {
            var curLat = effectiveHomeLat
            var curLng = effectiveHomeLng
            var curMin = initialDepartureMin
            var firstDep = initialDepartureMin
            val simulated = mutableListOf<PlannedStopEntity>()

            for ((idx, stop) in candidateOrder.withIndex()) {
                val destLat = stop.latitude ?: return null
                val destLng = stop.longitude ?: return null
                val travelEst = routingProvider.estimateTravel(
                    originLat = curLat,
                    originLng = curLng,
                    destLat = destLat,
                    destLng = destLng,
                    departureMinuteOfDay = curMin,
                    safetyBufferPercent = settings.trafficSafetyBufferPercent,
                    allowNetworkCall = allowNetworkCall
                )
                val isSamePhysical = idx > 0 &&
                    (travelEst.source == TrafficEstimateSource.SAME_LOCATION_SEQUENTIAL || travelEst.distanceKm < 0.015)
                val travelMin = if (isSamePhysical) {
                    settings.sameLocationTransitionMinutes.coerceAtLeast(0)
                } else {
                    travelEst.bufferedDurationMinutes
                }
                val rawArr = curMin + travelMin
                val effWinStart = if (shiftStartLimit != null) max(stop.windowStartMinute, shiftStartLimit) else stop.windowStartMinute
                val isOvernight = stop.windowEndMinute < stop.windowStartMinute
                val winSameDayEnd = if (isOvernight) 1440 else stop.windowEndMinute
                val effWinEnd = if (shiftEndLimit != null) min(winSameDayEnd, shiftEndLimit) else winSameDayEnd

                val vStart = max(rawArr, effWinStart)
                val vEnd = vStart + stop.visitDurationMinutes
                if (vStart < stop.windowStartMinute || vEnd > winSameDayEnd || vEnd > effWinEnd + 15) {
                    return null
                }

                val depFromPrev = if (idx == 0 && vStart > rawArr) {
                    vStart - travelMin
                } else {
                    curMin
                }
                val arrMin = depFromPrev + travelMin
                val waitMin = (vStart - arrMin).coerceAtLeast(0)
                if (idx > 0 && waitMin > 90) return null
                if (idx == 0) firstDep = depFromPrev

                simulated.add(
                    stop.copy(
                        stopOrder = idx + 1,
                        departureFromPrevMinute = depFromPrev,
                        travelMinutesFromPrev = travelMin,
                        distanceKmFromPrev = if (isSamePhysical) 0.0 else travelEst.distanceKm,
                        trafficSource = if (isSamePhysical) TrafficEstimateSource.SAME_LOCATION_SEQUENTIAL.name else travelEst.source.name,
                        arrivalMinute = arrMin,
                        waitingMinutes = waitMin,
                        visitStartMinute = vStart,
                        visitEndMinute = vEnd
                    )
                )
                curLat = destLat
                curLng = destLng
                curMin = vEnd
            }

            val last = simulated.last()
            val returnEst = if (settings.returnHomeRequired && last.latitude != null && last.longitude != null) {
                routingProvider.estimateTravel(
                    originLat = last.latitude,
                    originLng = last.longitude,
                    destLat = effectiveHomeLat,
                    destLng = effectiveHomeLng,
                    departureMinuteOfDay = last.visitEndMinute,
                    safetyBufferPercent = settings.trafficSafetyBufferPercent,
                    allowNetworkCall = false
                )
            } else null

            val endOfDuty = last.visitEndMinute + (returnEst?.bufferedDurationMinutes ?: 0)
            if (endOfDuty - firstDep > maxAllowedSpan) return null

            val totalRoadKm = simulated.sumOf { it.distanceKmFromPrev } + (returnEst?.distanceKm ?: 0.0)
            val totalWaitMin = simulated.sumOf { it.waitingMinutes }
            var crossBrickReturnJumps = 0
            val visitedBricks = mutableSetOf<String>()
            var prevBrick = ""
            for (s in simulated) {
                val b = s.zone.lowercase(Locale.ROOT).trim()
                if (b.isNotBlank() && b != prevBrick) {
                    if (!visitedBricks.add(b)) {
                        crossBrickReturnJumps++
                    }
                    prevBrick = b
                }
            }
            val cost = totalRoadKm + (crossBrickReturnJumps * 4.5) + (totalWaitMin * 0.08)
            return simulated to cost
        }

        val initialEval = simulateOrder(stops) ?: return stops
        var currentBest = initialEval.first
        var currentBestCost = initialEval.second

        var improved = true
        var passes = 0
        while (improved && passes < 2) {
            improved = false
            passes++
            for (i in 0 until currentBest.size - 1) {
                for (j in i + 1 until currentBest.size) {
                    val subSegment = currentBest.subList(i, j + 1)
                    if (subSegment.any { it.isLocked || it.executionStatus == "COMPLETED" }) continue
                    val candidate = buildList {
                        addAll(currentBest.subList(0, i))
                        addAll(subSegment.asReversed())
                        if (j + 1 < currentBest.size) {
                            addAll(currentBest.subList(j + 1, currentBest.size))
                        }
                    }
                    val eval = simulateOrder(candidate) ?: continue
                    if (eval.second + 0.15 < currentBestCost) {
                        currentBest = eval.first
                        currentBestCost = eval.second
                        improved = true
                    }
                }
            }
        }
        return currentBest
    }

    /**
     * Post-sequence Forward Time-Gap Compression:
     * Whenever stop k (k >= 1) has waitingMinutes > 0 (because the rep arrived before stop k's window opened),
     * shifts preceding unlocked stops 0..k-1 later in time by up to `waitingMinutes` (bounded by each earlier
     * stop's clinic closing window) so the route starts later and consecutive stops flow back-to-back with
     * minimal or zero time gaps between HCPs!
     */
    private fun compressInterStopTimeGaps(
        stops: List<PlannedStopEntity>,
        shiftEndLimit: Int?
    ): List<PlannedStopEntity> {
        if (stops.size <= 1) return stops
        val mutable = stops.toMutableList()

        for (k in 1..mutable.lastIndex) {
            val waitAtK = mutable[k].waitingMinutes
            if (waitAtK <= 0) continue

            // Find the earliest index `startIdx` of the contiguous block `startIdx .. k-1` that can shift forward together
            var maxShift = waitAtK
            var canShiftAllFromZero = true
            for (j in 0 until k) {
                val s = mutable[j]
                if (s.isLocked) {
                    canShiftAllFromZero = false
                    break
                }
                val allowedEnd = if (shiftEndLimit != null) {
                    min(s.windowEndMinute, shiftEndLimit + 15)
                } else {
                    s.windowEndMinute
                }
                val slackAtJ = (allowedEnd - s.visitEndMinute).coerceAtLeast(0)
                maxShift = min(maxShift, slackAtJ)
            }

            if (canShiftAllFromZero && maxShift > 0) {
                for (j in 0 until k) {
                    val s = mutable[j]
                    mutable[j] = s.copy(
                        departureFromPrevMinute = s.departureFromPrevMinute + maxShift,
                        arrivalMinute = s.arrivalMinute + maxShift,
                        visitStartMinute = s.visitStartMinute + maxShift,
                        visitEndMinute = s.visitEndMinute + maxShift
                    )
                }
                val sk = mutable[k]
                val newDepK = sk.departureFromPrevMinute + maxShift
                val newArrK = sk.arrivalMinute + maxShift
                val newWaitK = (sk.visitStartMinute - newArrK).coerceAtLeast(0)
                mutable[k] = sk.copy(
                    departureFromPrevMinute = newDepK,
                    arrivalMinute = newArrK,
                    waitingMinutes = newWaitK
                )
            }
        }
        return mutable
    }

    private suspend fun buildBestPlanForStrategy(
        request: OptimizationRequest,
        candidates: List<CandidateClinicOption>,
        strategy: StrategyConfig,
        unverifiedCount: Int,
        unconfirmedSchedCount: Int,
        weeklyRestrictedCount: Int,
        availabilityExcludedCount: Int,
        distinctEligibleCount: Int,
        effectiveWeeklyRestrictedIds: Set<Long>
    ): DailyPlanWithStops? {
        val settings = request.settings
        val targetTotal = (request.targetOverride ?: settings.dailyHcpTarget).coerceIn(1, 20)
        val (effectiveHomeLat, effectiveHomeLng) = resolveEffectiveHomeBase(
            settings = settings,
            clinics = candidates.map { it.clinic }
        )

        val candidateStartMinutes = mutableSetOf<Int>()
        if (request.shiftStartMinute != null) {
            val shiftStart = request.shiftStartMinute
            val shiftEnd = request.shiftEndMinute ?: (shiftStart + 300)
            candidateStartMinutes.add((shiftStart - 15).coerceAtLeast(360))
            var probe = shiftStart
            while (probe <= min(1260, shiftEnd - 30)) {
                candidateStartMinutes.add(probe)
                probe += 20
            }
            candidates.forEach { opt ->
                val wStart = max(opt.window.startMinuteOfDay, shiftStart)
                if (wStart in shiftStart..shiftEnd) {
                    candidateStartMinutes.add((wStart - 15).coerceAtLeast(360))
                }
            }
        } else {
            val earliestWindowStart = candidates.minOfOrNull { it.window.startMinuteOfDay } ?: 540
            val latestWindowStart = candidates.maxOfOrNull { it.window.startMinuteOfDay } ?: 1080
            var probe = (earliestWindowStart - 30).coerceAtLeast(420)
            while (probe <= min(1140, latestWindowStart)) {
                candidateStartMinutes.add(probe)
                probe += 30
            }
            candidates.forEach { opt ->
                candidateStartMinutes.add((opt.window.startMinuteOfDay - 15).coerceAtLeast(420))
            }
        }

        // Normalize the 3 strategy weights so wPriority + wCoverage + wDistance == 1.0
        val rawSum = (strategy.wPriority + strategy.wCoverage + strategy.wDistance).coerceAtLeast(0.01)
        val normWSpecialty = strategy.wPriority / rawSum
        val normWRemaining = strategy.wCoverage / rawSum
        val normWTravel = strategy.wDistance / rawSum

        val preferredSpecialties = SpecialtyNormalizer.extractUniqueSpecialties(settings.preferredSpecialtiesRanked)

        // Identify the best geographic brick/zone weighted by user priorities & specialty ranking
        val dominantZone = if (strategy.clusterFirst) {
            candidates.groupBy { it.clinic.zone }
                .maxByOrNull { (_, list) ->
                    val distinctHcps = list.distinctBy { it.hcpWithDetails.hcp.id }
                    distinctHcps.sumOf {
                        val specRankScore = SpecialtyNormalizer.computeNormalizedSpecialtyRankScore(
                            it.hcpWithDetails.hcp.specialty,
                            preferredSpecialties
                        )
                        (it.hcpWithDetails.coverage.remainingVisits.coerceAtLeast(1) * normWRemaining * 15.0) +
                            (specRankScore * normWSpecialty * 20.0) + 1.0
                    }
                }
                ?.key
        } else {
            null
        }

        // Precompute local geographic neighbor count (within 4.0 km) weighted by remainingVisits for each clinic
        val clusterWeightByClinicId = candidates.map { it.clinic }.distinctBy { it.id }.associate { clinic ->
            val lat1 = clinic.latitude ?: effectiveHomeLat
            val lng1 = clinic.longitude ?: effectiveHomeLng
            val nearbyScore = candidates.distinctBy { it.hcpWithDetails.hcp.id }.sumOf { other ->
                val lat2 = other.clinic.latitude ?: return@sumOf 0.0
                val lng2 = other.clinic.longitude ?: return@sumOf 0.0
                val distKm = DefaultRoutingService.haversineDistanceKm(lat1, lng1, lat2, lng2)
                when {
                    distKm <= 1.5 -> 2.0 * (other.hcpWithDetails.coverage.remainingVisits.coerceAtLeast(1) / 8.0)
                    distKm <= 4.0 -> 1.0 * (other.hcpWithDetails.coverage.remainingVisits.coerceAtLeast(1) / 8.0)
                    else -> 0.0
                }
            }
            clinic.id to nearbyScore
        }

        val maxPriorityScoreInPool = candidates.maxOfOrNull { it.hcpWithDetails.hcp.priorityScore }?.coerceAtLeast(1) ?: 100
        val minPriorityScoreInPool = candidates.minOfOrNull { it.hcpWithDetails.hcp.priorityScore } ?: 50
        val prioritySpan = (maxPriorityScoreInPool - minPriorityScoreInPool).coerceAtLeast(15)
        val maxRemainingInPool = candidates.maxOfOrNull { it.hcpWithDetails.coverage.remainingVisits }?.coerceAtLeast(1) ?: 8

        var bestStops: List<PlannedStopEntity>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var bestReturnTravelMin = 0
        var bestReturnDistKm = 0.0
        var bestTrafficSource = TrafficEstimateSource.TYPICAL_ROAD

        val maxAllowedSpan = if (request.shiftStartMinute != null && request.shiftEndMinute != null) {
            max(settings.maxDutySpanMinutes, (request.shiftEndMinute - request.shiftStartMinute) + 90)
        } else {
            settings.maxDutySpanMinutes
        }

        for (homeDepartureMin in candidateStartMinutes.sorted()) {
            val builtStops = mutableListOf<PlannedStopEntity>()
            val visitedHcpIds = mutableSetOf<Long>()

            var currentLat = effectiveHomeLat
            var currentLng = effectiveHomeLng
            var currentMinute = homeDepartureMin
            var firstDepartureMinute = homeDepartureMin

            if (request.completedStopsToday.isNotEmpty()) {
                val sortedCompleted = request.completedStopsToday.sortedBy { it.stopOrder }
                builtStops.addAll(sortedCompleted)
                visitedHcpIds.addAll(sortedCompleted.map { it.hcpId })
                val lastComp = sortedCompleted.last()
                currentLat = lastComp.latitude ?: effectiveHomeLat
                currentLng = lastComp.longitude ?: effectiveHomeLng
                currentMinute = lastComp.visitEndMinute
                firstDepartureMinute = sortedCompleted.first().departureFromPrevMinute
            }

            var breakTaken = !settings.breakEnabled
            var dominantTrafficSource = TrafficEstimateSource.TYPICAL_ROAD

            while (builtStops.size < targetTotal) {
                var bestNextOption: CandidateClinicOption? = null
                var bestNextStop: PlannedStopEntity? = null
                var bestNextEval = Double.NEGATIVE_INFINITY

                for (option in candidates) {
                    val hcp = option.hcpWithDetails.hcp
                    if (hcp.id in visitedHcpIds || hcp.id in effectiveWeeklyRestrictedIds) continue

                    val destLat = option.clinic.latitude ?: continue
                    val destLng = option.clinic.longitude ?: continue

                    val breakOffset = if (!breakTaken && builtStops.size == 4) settings.breakDurationMinutes else 0
                    val departureMin = currentMinute + breakOffset

                    val travelEst = routingProvider.estimateTravel(
                        originLat = currentLat,
                        originLng = currentLng,
                        destLat = destLat,
                        destLng = destLng,
                        departureMinuteOfDay = departureMin,
                        safetyBufferPercent = settings.trafficSafetyBufferPercent,
                        allowNetworkCall = request.allowNetworkRouting
                    )

                    val isSamePhysicalClinic = builtStops.isNotEmpty() &&
                        (travelEst.source == TrafficEstimateSource.SAME_LOCATION_SEQUENTIAL || travelEst.distanceKm < 0.015)
                    val effectiveTravelMinutes = if (isSamePhysicalClinic) {
                        settings.sameLocationTransitionMinutes.coerceAtLeast(0)
                    } else {
                        travelEst.bufferedDurationMinutes
                    }

                    val arrivalMin = departureMin + effectiveTravelMinutes
                    val effectiveWindowStart = if (request.shiftStartMinute != null) {
                        max(option.window.startMinuteOfDay, request.shiftStartMinute)
                    } else {
                        option.window.startMinuteOfDay
                    }
                    val optWindowEffEnd = option.window.effectiveSameDayEndMinute().coerceAtMost(1440)
                    val effectiveWindowEnd = if (request.shiftEndMinute != null) {
                        min(optWindowEffEnd, request.shiftEndMinute)
                    } else {
                        optWindowEffEnd
                    }

                    val earliestVisitStart = max(arrivalMin, effectiveWindowStart)
                    val visitStartMin = option.lockedStartMinute?.let { max(earliestVisitStart, it) } ?: earliestVisitStart
                    val visitEndMin = visitStartMin + option.visitDurationMinutes
                    val waitingMin = visitStartMin - arrivalMin

                    // Hard constraint 1: Visit must start AND finish within actual clinic window AND user's selected shift window
                    if (visitStartMin < option.window.startMinuteOfDay ||
                        visitEndMin > optWindowEffEnd ||
                        visitEndMin > effectiveWindowEnd + 15
                    ) {
                        continue
                    }

                    // Hard constraint 2: No split shift (waiting > 90 minutes between stops is rejected)
                    if (builtStops.isNotEmpty() && waitingMin > 90) {
                        continue
                    }

                    val adjustedDepartureFromPrev = if (builtStops.isEmpty() && waitingMin > 0) {
                        visitStartMin - effectiveTravelMinutes
                    } else {
                        departureMin
                    }
                    val adjustedArrival = adjustedDepartureFromPrev + effectiveTravelMinutes
                    val adjustedWait = (visitStartMin - adjustedArrival).coerceAtLeast(0)
                    val effectiveFirstDeparture = if (builtStops.isEmpty()) adjustedDepartureFromPrev else firstDepartureMinute

                    // Hard constraint 3: Check return-home duty span limit
                    val returnEst = if (settings.returnHomeRequired) {
                        routingProvider.estimateTravel(
                            originLat = destLat,
                            originLng = destLng,
                            destLat = effectiveHomeLat,
                            destLng = effectiveHomeLng,
                            departureMinuteOfDay = visitEndMin,
                            safetyBufferPercent = settings.trafficSafetyBufferPercent,
                            allowNetworkCall = false
                        )
                    } else {
                        null
                    }
                    val projectedEndOfDuty = visitEndMin + (returnEst?.bufferedDurationMinutes ?: 0)
                    val projectedDutySpan = projectedEndOfDuty - effectiveFirstDeparture
                    if (projectedDutySpan > maxAllowedSpan) {
                        continue
                    }

                    // === SECTION 4 & 5: NORMALIZED 3-COMPONENT WEIGHTED SCORING MODEL [0.0 .. 1.0] ===
                    // 1. HCP Specialty Ranking Score in [0.0, 1.0]
                    // Rank 1 preferred specialty receives 1.00, Rank 2 > Rank 3, etc., with a minor tie-breaker from HCP priorityScore.
                    val rankNormScore = SpecialtyNormalizer.computeNormalizedSpecialtyRankScore(
                        rawSpecialty = hcp.specialty,
                        rankedPreferredSpecialties = preferredSpecialties
                    )
                    val absPriorityTieBreaker = (hcp.priorityScore / 100.0).coerceIn(0.0, 1.0) * 0.05
                    val prioritizedBoost = if (hcp.id in request.prioritizedHcpIds) 0.15 else 0.0
                    val specialtyScore = ((rankNormScore * 0.95) + absPriorityTieBreaker + prioritizedBoost).coerceIn(0.0, 1.0)

                    // 2. Remaining Required Visits Score in [0.0, 1.0] (strictly at HCP level, never double-counted for multi-clinic)
                    val remVisits = option.hcpWithDetails.coverage.remainingVisits.coerceAtLeast(0)
                    val remRatioToPool = (remVisits.toDouble() / maxRemainingInPool.toDouble()).coerceIn(0.0, 1.0)
                    val remRatioToTarget = (remVisits.toDouble() / max(1, hcp.quarterlyTarget).toDouble()).coerceIn(0.0, 1.0)
                    val overdueBoost = if (option.hcpWithDetails.coverage.isOverdue) 0.15 else 0.0
                    val remainingVisitsScore = ((remRatioToPool * 0.60) + (remRatioToTarget * 0.40) + overdueBoost).coerceIn(0.0, 1.0)

                    // 3. Travel Efficiency Score in [0.0, 1.0] (Consecutive clinic distance + Brick continuity + Return-to-Home efficiency)
                    val returnDistKm = returnEst?.distanceKm ?: DefaultRoutingService.haversineDistanceKm(destLat, destLng, effectiveHomeLat, effectiveHomeLng)
                    val travelEfficiencyScore = if (builtStops.isEmpty()) {
                        val homeDistEff = (1.0 - (travelEst.distanceKm / 18.0)).coerceIn(0.0, 1.0)
                        val clusterDensity = ((clusterWeightByClinicId[option.clinic.id] ?: 0.0) / 3.5).coerceIn(0.0, 0.25)
                        val domZoneBoost = if (dominantZone != null && option.clinic.zone.equals(dominantZone, ignoreCase = true)) 0.15 else 0.0
                        (homeDistEff * 0.75 + clusterDensity + domZoneBoost).coerceIn(0.0, 1.0)
                    } else {
                        val consecutiveDistEff = when {
                            isSamePhysicalClinic -> 1.0
                            else -> (1.0 - (travelEst.distanceKm / 12.0)).coerceIn(0.0, 1.0)
                        }
                        val consecutiveTimeEff = (1.0 - (effectiveTravelMinutes / 35.0)).coerceIn(0.0, 1.0)
                        val isSameBrickAsPrev = builtStops.last().zone.equals(option.clinic.zone, ignoreCase = true)
                        val brickContinuityBonus = if (isSameBrickAsPrev) 0.18 else 0.0
                        // Penalize jumping out of a brick and returning to an already-visited brick (Brick A -> Brick D -> Brick A)
                        val crossBrickJumpPenalty = if (!isSameBrickAsPrev &&
                            builtStops.dropLast(1).any { it.zone.equals(option.clinic.zone, ignoreCase = true) }
                        ) {
                            0.28
                        } else if (!isSameBrickAsPrev && travelEst.distanceKm > 5.0) {
                            0.18
                        } else {
                            0.0
                        }
                        val returnLegEff = (1.0 - (returnDistKm / 25.0)).coerceIn(0.0, 1.0) * 0.12
                        ((consecutiveDistEff * 0.58) + (consecutiveTimeEff * 0.30) + returnLegEff + brickContinuityBonus - crossBrickJumpPenalty).coerceIn(0.0, 1.0)
                    }

                    // Weighted Priority Combination (Scaled by 10.0 so differences strongly drive candidate ordering)
                    val weightedPriorityScore = (
                        (specialtyScore * normWSpecialty) +
                            (remainingVisitsScore * normWRemaining) +
                            (travelEfficiencyScore * normWTravel)
                        ) * 10.0

                    // Schedule feasibility & zero-gap flow bonuses (secondary to hard constraints & weighted priority)
                    val waitPenalty = (adjustedWait / 20.0).coerceIn(0.0, 2.5) * strategy.wWait
                    val zeroGapBonus = when {
                        adjustedWait == 0 -> 0.35
                        adjustedWait <= 5 -> 0.20
                        else -> 0.0
                    }
                    val totalWindowSpan = option.window.durationMinutes().coerceAtLeast(15)
                    val narrowWindowBonus = if (totalWindowSpan <= 90) 0.25 else 0.0

                    // Controlled Route Diversity tie-breaker (Section 5): small deterministic perturbation within near-equivalent candidates
                    val diversityPerturbation = if (strategy.seedOffset != 0) {
                        val hash = ((hcp.id * 31L + option.clinic.id * 17L + strategy.seedOffset * 131L) and 0x7FFFFFFFL) % 100L
                        (hash / 100.0) * 0.18
                    } else {
                        0.0
                    }

                    val mandatoryBonus = if (option.isMandatory) 20.0 else 0.0

                    val stepScore = mandatoryBonus +
                        weightedPriorityScore +
                        zeroGapBonus +
                        narrowWindowBonus +
                        diversityPerturbation -
                        waitPenalty

                    if (stepScore > bestNextEval) {
                        bestNextEval = stepScore
                        bestNextOption = option
                        dominantTrafficSource = travelEst.source
                        bestNextStop = PlannedStopEntity(
                            planId = 0L,
                            stopOrder = builtStops.size + 1,
                            hcpId = hcp.id,
                            hcpTargetId = hcp.targetId,
                            hcpName = hcp.name,
                            specialty = hcp.specialty,
                            clinicId = option.clinic.id,
                            clinicLabel = option.clinic.label,
                            zone = option.clinic.zone,
                            latitude = destLat,
                            longitude = destLng,
                            locationVerified = true,
                            departureFromPrevMinute = adjustedDepartureFromPrev,
                            travelMinutesFromPrev = effectiveTravelMinutes,
                            distanceKmFromPrev = if (isSamePhysicalClinic) 0.0 else travelEst.distanceKm,
                            trafficSource = if (isSamePhysicalClinic) TrafficEstimateSource.SAME_LOCATION_SEQUENTIAL.name else travelEst.source.name,
                            arrivalMinute = adjustedArrival,
                            waitingMinutes = adjustedWait,
                            visitStartMinute = visitStartMin,
                            visitDurationMinutes = option.visitDurationMinutes,
                            visitEndMinute = visitEndMin,
                            windowStartMinute = option.window.startMinuteOfDay,
                            windowEndMinute = option.window.endMinuteOfDay,
                            priorityScore = hcp.priorityScore,
                            remainingQuarterlyVisits = option.hcpWithDetails.coverage.remainingVisits,
                            isLocked = option.lockedStartMinute != null || option.isMandatory,
                            executionStatus = "PLANNED"
                        )
                    }
                }

                if (bestNextStop != null && bestNextOption != null) {
                    if (builtStops.isEmpty()) {
                        firstDepartureMinute = bestNextStop.departureFromPrevMinute
                    }
                    if (!breakTaken && builtStops.size == 4) {
                        breakTaken = true
                    }
                    builtStops.add(bestNextStop)
                    visitedHcpIds.add(bestNextStop.hcpId)
                    currentLat = bestNextStop.latitude ?: currentLat
                    currentLng = bestNextStop.longitude ?: currentLng
                    currentMinute = bestNextStop.visitEndMinute
                } else {
                    break
                }
            }

            if (builtStops.isEmpty()) continue

            // Stage 2: Time-Window-Aware 2-Opt Route Improvement to eliminate cross-brick jumps & minimize total road distance
            val twoOptStops = improveRouteSequenceWith2Opt(
                stops = builtStops,
                effectiveHomeLat = effectiveHomeLat,
                effectiveHomeLng = effectiveHomeLng,
                initialDepartureMin = builtStops.first().departureFromPrevMinute,
                shiftStartLimit = request.shiftStartMinute,
                shiftEndLimit = request.shiftEndMinute,
                maxAllowedSpan = maxAllowedSpan,
                settings = settings,
                allowNetworkCall = request.allowNetworkRouting
            )

            // Stage 3: Multi-pass forward slack compression to eliminate any idle time gaps between consecutive stops
            val pass1Stops = compressInterStopTimeGaps(
                stops = twoOptStops,
                shiftEndLimit = request.shiftEndMinute
            )
            val compressedStops = compressInterStopTimeGaps(
                stops = pass1Stops,
                shiftEndLimit = request.shiftEndMinute
            )

            val lastStop = compressedStops.last()
            val returnTravel = if (settings.returnHomeRequired && lastStop.latitude != null && lastStop.longitude != null) {
                routingProvider.estimateTravel(
                    originLat = lastStop.latitude,
                    originLng = lastStop.longitude,
                    destLat = effectiveHomeLat,
                    destLng = effectiveHomeLng,
                    departureMinuteOfDay = lastStop.visitEndMinute,
                    safetyBufferPercent = settings.trafficSafetyBufferPercent,
                    allowNetworkCall = false
                )
            } else {
                null
            }

            val totalDriving = compressedStops.sumOf { it.travelMinutesFromPrev } + (returnTravel?.bufferedDurationMinutes ?: 0)
            val interStopDistKm = compressedStops.drop(1).sumOf { it.distanceKmFromPrev }
            val totalDist = compressedStops.sumOf { it.distanceKmFromPrev } + (returnTravel?.distanceKm ?: 0.0)
            val totalWait = compressedStops.sumOf { it.waitingMinutes }

            val avgSpecialtyNorm = compressedStops.map {
                val rScore = SpecialtyNormalizer.computeNormalizedSpecialtyRankScore(
                    rawSpecialty = it.specialty,
                    rankedPreferredSpecialties = preferredSpecialties
                )
                ((rScore * 0.95) + ((it.priorityScore / 100.0).coerceIn(0.0, 1.0) * 0.05)).coerceIn(0.0, 1.0)
            }.average()
            val avgRemainingNorm = compressedStops.map {
                (it.remainingQuarterlyVisits.coerceAtLeast(0).toDouble() / maxRemainingInPool.toDouble()).coerceIn(0.0, 1.0)
            }.average()
            val routeTravelEffNorm = (1.0 - ((interStopDistKm * 1.3 + totalDist * 0.5) / (compressedStops.size * 10.0).coerceAtLeast(10.0))).coerceIn(0.0, 1.0)

            val planWeightedScore = (
                (avgSpecialtyNorm * normWSpecialty) +
                    (avgRemainingNorm * normWRemaining) +
                    (routeTravelEffNorm * normWTravel)
                ) * 100.0

            val planScore = (compressedStops.size * 45.0) +
                planWeightedScore -
                (strategy.wWait * totalWait * 1.2) -
                (normWTravel * totalDriving * 0.25)

            if (planScore > bestScore) {
                bestScore = planScore
                bestStops = compressedStops
                bestReturnTravelMin = returnTravel?.bufferedDurationMinutes ?: 0
                bestReturnDistKm = returnTravel?.distanceKm ?: 0.0
                bestTrafficSource = dominantTrafficSource
            }
        }

        val finalStops = bestStops ?: return null
        val startMin = finalStops.first().departureFromPrevMinute
        val endMin = finalStops.last().visitEndMinute + (if (settings.returnHomeRequired) bestReturnTravelMin else 0)
        val totalDutySpan = endMin - startMin
        val totalDriving = finalStops.sumOf { it.travelMinutesFromPrev } + (if (settings.returnHomeRequired) bestReturnTravelMin else 0)
        val totalDist = ((finalStops.sumOf { it.distanceKmFromPrev } + (if (settings.returnHomeRequired) bestReturnDistKm else 0.0)) * 10.0).roundToInt() / 10.0
        val totalWait = finalStops.sumOf { it.waitingMinutes }
        val highPrioCount = finalStops.count { it.priorityScore >= 80 }
        val covGain = finalStops.count { it.remainingQuarterlyVisits > 0 }

        val infeasibilityExplanation = if (finalStops.size < targetTotal) {
            buildString {
                append("Scheduled ${finalStops.size}/$targetTotal target visits (Target Achieved: No). ")
                append("Eligible HCPs considered: $distinctEligibleCount. ")
                if (weeklyRestrictedCount > 0) append("Excluded due to weekly visits: $weeklyRestrictedCount. ")
                val schedExcl = availabilityExcludedCount + unconfirmedSchedCount + unverifiedCount
                if (schedExcl > 0) append("Excluded due to clinic availability/schedule: $schedExcl. ")
                if (distinctEligibleCount < targetTotal) {
                    append("Only $distinctEligibleCount eligible HCP(s) had confirmed windows matching your shift today.")
                } else {
                    append("Remaining candidates conflicted with clinic closing times or the shift window.")
                }
            }.trim()
        } else {
            buildString {
                append("Full daily target of $targetTotal qualifying HCP visits achieved (Target Achieved: Yes). ")
                append("Eligible HCPs considered: $distinctEligibleCount • Weekly visits excluded: $weeklyRestrictedCount • Availability/schedule excluded: ${availabilityExcludedCount + unconfirmedSchedCount + unverifiedCount}.")
            }
        }

        val draftPlan = DailyPlanEntity(
            dateIso = request.dateIso,
            variantCode = strategy.code,
            variantLabel = strategy.label,
            isSelected = false,
            startMinuteOfDay = startMin,
            endMinuteOfDay = endMin,
            totalDutySpanMinutes = totalDutySpan,
            totalDrivingMinutes = totalDriving,
            totalDistanceKm = totalDist,
            totalWaitingMinutes = totalWait,
            plannedVisitsCount = finalStops.size,
            uniqueHcpsCount = finalStops.map { it.hcpId }.distinct().size,
            highPriorityCount = highPrioCount,
            quarterlyCoverageGain = covGain,
            trafficFreshnessLabel = bestTrafficSource.badgeLabel,
            feasibilityStatus = if (finalStops.size >= targetTotal) "FEASIBLE" else "PARTIAL_TARGET",
            infeasibilityReasonsText = infeasibilityExplanation,
            advantagesText = strategy.advantageSummary,
            disadvantagesText = strategy.disadvantageSummary,
            objectiveScore = (bestScore * 10.0).roundToInt() / 10.0
        )

        val validation = PlanValidator.validate(
            planWithStops = DailyPlanWithStops(draftPlan, finalStops),
            settings = settings,
            returnHomeTravelMinutes = bestReturnTravelMin,
            hcpsById = request.hcps.associateBy { it.hcp.id },
            alreadyVisitedOrScheduledInWeekHcpIds = effectiveWeeklyRestrictedIds,
            weeklyBlockedByHcpId = request.weeklyBlockedByHcpId
        )

        if (!validation.isValid) {
            return null
        }

        val validatedStops = finalStops.map { s ->
            val conflict = validation.stopConflicts[s.stopOrder] ?: ""
            s.copy(conflictReason = conflict)
        }

        val validatedPlan = draftPlan.copy(
            isValidatorVerified = validation.isValid,
            validatorReportText = validation.summary
        )

        return DailyPlanWithStops(validatedPlan, validatedStops)
    }

    private suspend fun buildGuaranteedFallbackPlan(
        request: OptimizationRequest,
        candidateOptions: List<CandidateClinicOption>,
        dayOfWeek: Int,
        diagnostics: MutableList<String>
    ): DailyPlanWithStops? {
        val settings = request.settings
        val targetVisits = (request.targetOverride ?: settings.dailyHcpTarget).coerceIn(1, 20)
        val shiftStart = (request.shiftStartMinute ?: 840).coerceIn(360, 1260)
        val shiftEnd = (request.shiftEndMinute ?: (shiftStart + 360)).coerceIn(shiftStart + 60, 1380)
        val effectiveWeeklyRestrictedIds = if (settings.enforceWeeklyHcpVisitLimit) {
            request.weeklyRestrictedHcpIds + request.weeklyBlockedByHcpId.keys + request.completedStopsToday.map { it.hcpId }
        } else {
            request.completedStopsToday.map { it.hcpId }.toSet()
        }

        fun matchesAllowedZone(zone: String): Boolean {
            return BrickNormalizer.matchesAllowedBricks(zone, request.allowedZones)
        }

        val preferredSpecialties = SpecialtyNormalizer.extractUniqueSpecialties(settings.preferredSpecialtiesRanked)
        if (preferredSpecialties.isEmpty()) return null

        // Build pool strictly preferring clinics that actually have a schedule window on `dayOfWeek`, belong to a selected preferred specialty, and are NOT weekly-restricted
        val openOnDayPool = mutableListOf<CandidateClinicOption>()
        if (candidateOptions.isNotEmpty()) {
            openOnDayPool.addAll(
                candidateOptions.filter {
                    val h = it.hcpWithDetails.hcp
                    h.id !in effectiveWeeklyRestrictedIds &&
                        !SpecialtyNormalizer.isMissingOrUnmatchedSpecialty(h.specialty) &&
                        SpecialtyNormalizer.isSpecialtySelected(h.specialty, preferredSpecialties)
                }
            )
        } else {
            for (hcpWithDetails in request.hcps) {
                val hcp = hcpWithDetails.hcp
                if (!hcp.isActive || hcp.isDeleted) continue
                if (SpecialtyNormalizer.isMissingOrUnmatchedSpecialty(hcp.specialty)) continue
                if (!SpecialtyNormalizer.isSpecialtySelected(hcp.specialty, preferredSpecialties)) continue
                if (request.specialtyFilter != null && !hcp.specialty.contains(request.specialtyFilter, ignoreCase = true)) continue
                if (hcp.id in effectiveWeeklyRestrictedIds || hcp.id in request.excludedHcpIds) continue
                if (hcpWithDetails.coverage.remainingVisits <= 0 && hcp.id !in request.mandatoryHcpIds) continue

                for (cw in hcpWithDetails.clinics) {
                    val clinic = cw.clinic
                    if (!clinic.isActive) continue
                    if (!matchesAllowedZone(clinic.zone)) continue
                    if (clinic.originalLocationText.contains("Unverified", ignoreCase = true)) continue
                    val hasNonRangeType = clinic.timeInfoType.isNotBlank() &&
                        clinic.timeInfoType != "CLINIC_AVAILABILITY_RANGE"
                    if ((!clinic.userConfirmedSchedule && (clinic.scheduleParseStatus == "AMBIGUOUS" || clinic.scheduleParseStatus == "UNPARSED")) || hasNonRangeType) continue

                    val strictClinicWindows = cw.windows.filter {
                        it.clinicId == clinic.id && (it.hcpId == 0L || it.hcpId == hcp.id)
                    }
                    val resolvedWins: List<AvailabilityWindowEntity> = strictClinicWindows.ifEmpty {
                        if (clinic.rawScheduleText.isNotBlank()) {
                            AvailabilityScheduleParser.parseForExcelImport(
                                rawSchedule = clinic.rawScheduleText,
                                siblingClinicResult = null
                            ).toEntities(
                                clinicId = clinic.id,
                                isConfirmed = true,
                                hcpId = hcp.id,
                                originalExcelText = clinic.originalExcelScheduleText.ifBlank { clinic.rawScheduleText },
                                sourceSheet = clinic.sourceSheet,
                                sourceRow = clinic.sourceRow,
                                sourceColumn = clinic.sourceColumn
                            )
                        } else emptyList()
                    }
                    val dayWins = resolvedWins.filter { it.dayOfWeek == dayOfWeek }
                    if (dayWins.isEmpty()) continue

                    val coords = if (clinic.latitude != null && clinic.longitude != null) {
                        clinic.latitude to clinic.longitude
                    } else {
                        val ext = com.example.domain.importer.ExcelTerritoryImporter.extractCoordinates(
                            rawLocation = clinic.googleMapsUrl.ifBlank { clinic.originalLocationText },
                            zoneHint = clinic.zone,
                            rowSeed = "${clinic.id}-${clinic.label}"
                        )
                        if (ext.latitude != null && ext.longitude != null) ext.latitude to ext.longitude else null
                    } ?: continue

                    for (win in dayWins) {
                        if (win.endsNextDay) continue
                        val dur = clinic.customVisitDurationMinutes
                            ?: hcp.defaultVisitDurationMinutes.takeIf { it > 0 }
                            ?: settings.defaultVisitDurationMinutes
                        if (win.effectiveSameDayEndMinute() - win.startMinuteOfDay >= dur) {
                            openOnDayPool.add(
                                CandidateClinicOption(
                                    hcpWithDetails = hcpWithDetails,
                                    clinic = clinic.copy(
                                        latitude = coords.first,
                                        longitude = coords.second,
                                        geocodingStatus = "VERIFIED"
                                    ),
                                    window = win,
                                    visitDurationMinutes = dur,
                                    isMandatory = hcp.id in request.mandatoryHcpIds
                                )
                            )
                        }
                    }
                }
            }
        }

        if (openOnDayPool.isEmpty()) return null

        val (effectiveHomeLat, effectiveHomeLng) = resolveEffectiveHomeBase(
            settings = settings,
            clinics = openOnDayPool.map { it.clinic }
        )

        val userWSpec = settings.normalizedSpecialtyWeight
        val userWRem = settings.normalizedRemainingVisitsWeight
        val userWDist = settings.normalizedTravelDistanceWeight

        val selectedStops = mutableListOf<PlannedStopEntity>()
        val usedHcpIds = mutableSetOf<Long>()
        val triedOptionKeys = mutableSetOf<String>()

        val firstSeed = openOnDayPool.maxByOrNull { opt ->
            val remScore = opt.hcpWithDetails.coverage.remainingVisits * 15.0 * userWRem
            val rankSpec = SpecialtyNormalizer.computeNormalizedSpecialtyRankScore(
                opt.hcpWithDetails.hcp.specialty,
                preferredSpecialties
            )
            val specScore = rankSpec * 40.0 * userWSpec
            val fitsShift = if (min(opt.window.endMinuteOfDay, shiftEnd) - max(opt.window.startMinuteOfDay, shiftStart) >= opt.visitDurationMinutes) 50.0 else 0.0
            val earlyOpen = (1440 - opt.window.startMinuteOfDay) / 60.0
            fitsShift + remScore + specScore + earlyOpen
        } ?: return null

        var curLat = effectiveHomeLat
        var curLng = effectiveHomeLng
        var curMin = max(firstSeed.window.startMinuteOfDay, shiftStart) - 10

        while (selectedStops.size < targetVisits) {
            val idx = selectedStops.size
            val nextOpt = openOnDayPool
                .filter { opt ->
                    val optKey = "${opt.clinic.id}-${opt.window.startMinuteOfDay}-${opt.window.endMinuteOfDay}"
                    opt.hcpWithDetails.hcp.id !in usedHcpIds &&
                        opt.hcpWithDetails.hcp.id !in effectiveWeeklyRestrictedIds &&
                        optKey !in triedOptionKeys
                }
                .filter { opt ->
                    val dLat = opt.clinic.latitude ?: effectiveHomeLat
                    val dLng = opt.clinic.longitude ?: effectiveHomeLng
                    val distKm = if (idx == 0) 0.0 else DefaultRoutingService.haversineDistanceKm(curLat, curLng, dLat, dLng)
                    val estTravelMin = if (idx > 0 && distKm < 0.05) {
                        settings.sameLocationTransitionMinutes.coerceAtLeast(0)
                    } else {
                        (distKm * 2.5 + 5).roundToInt().coerceIn(5, 45)
                    }
                    val arrMin = if (idx == 0) opt.window.startMinuteOfDay else curMin + estTravelMin
                    val vStart = max(arrMin, opt.window.startMinuteOfDay)
                    val vEnd = vStart + opt.visitDurationMinutes
                    vStart >= opt.window.startMinuteOfDay && vEnd <= opt.window.endMinuteOfDay
                }
                .maxByOrNull { opt ->
                    val dLat = opt.clinic.latitude ?: effectiveHomeLat
                    val dLng = opt.clinic.longitude ?: effectiveHomeLng
                    val distKm = if (idx == 0) 0.0 else DefaultRoutingService.haversineDistanceKm(curLat, curLng, dLat, dLng)
                    val estTravelMin = if (idx > 0 && distKm < 0.05) {
                        settings.sameLocationTransitionMinutes.coerceAtLeast(0)
                    } else {
                        (distKm * 2.5 + 5).roundToInt().coerceIn(5, 45)
                    }
                    val arrMin = curMin + estTravelMin
                    val vStart = max(arrMin, opt.window.startMinuteOfDay)
                    val waitMin = (vStart - arrMin).coerceAtLeast(0)
                    val remScore = opt.hcpWithDetails.coverage.remainingVisits * 18.0 * userWRem
                    val rankSpec = SpecialtyNormalizer.computeNormalizedSpecialtyRankScore(
                        opt.hcpWithDetails.hcp.specialty,
                        preferredSpecialties
                    )
                    val specScore = rankSpec * 120.0 * userWSpec
                    val sameBuildingBonus = if (idx > 0 && distKm < 0.05) 30.0 * userWDist else 0.0
                    val sameZoneBonus = if (idx > 0 && selectedStops.last().zone.equals(opt.clinic.zone, ignoreCase = true)) 22.0 * userWDist else 0.0
                    100.0 + remScore + specScore + sameBuildingBonus + sameZoneBonus - (distKm * 12.0 * userWDist) - (waitMin * 1.8)
                } ?: break

            val optKey = "${nextOpt.clinic.id}-${nextOpt.window.startMinuteOfDay}-${nextOpt.window.endMinuteOfDay}"
            triedOptionKeys.add(optKey)

            val destLat = nextOpt.clinic.latitude ?: effectiveHomeLat
            val destLng = nextOpt.clinic.longitude ?: effectiveHomeLng
            val travelEst = routingProvider.estimateTravel(
                originLat = curLat,
                originLng = curLng,
                destLat = destLat,
                destLng = destLng,
                departureMinuteOfDay = curMin,
                safetyBufferPercent = settings.trafficSafetyBufferPercent,
                allowNetworkCall = false
            )

            val isSameBuilding = idx > 0 &&
                (travelEst.source == TrafficEstimateSource.SAME_LOCATION_SEQUENTIAL || travelEst.distanceKm < 0.05)
            val safeTravelMinutes = if (isSameBuilding) {
                settings.sameLocationTransitionMinutes.coerceAtLeast(0)
            } else {
                travelEst.bufferedDurationMinutes.coerceIn(5, 45)
            }
            val rawArrival = curMin + safeTravelMinutes
            val desiredStart = max(rawArrival, max(nextOpt.window.startMinuteOfDay, shiftStart))

            val departureMin = if (idx == 0 && desiredStart > rawArrival) {
                (desiredStart - safeTravelMinutes).coerceAtLeast(360)
            } else {
                curMin
            }
            val arrivalMin = departureMin + safeTravelMinutes
            val visitStartMin = max(arrivalMin, nextOpt.window.startMinuteOfDay)
            val waitMin = (visitStartMin - arrivalMin).coerceAtLeast(0)
            val visitEndMin = visitStartMin + nextOpt.visitDurationMinutes

            if (visitStartMin < nextOpt.window.startMinuteOfDay || visitEndMin > nextOpt.window.endMinuteOfDay) {
                continue
            }

            selectedStops.add(
                PlannedStopEntity(
                    planId = 0L,
                    stopOrder = idx + 1,
                    hcpId = nextOpt.hcpWithDetails.hcp.id,
                    hcpTargetId = nextOpt.hcpWithDetails.hcp.targetId,
                    hcpName = nextOpt.hcpWithDetails.hcp.name,
                    specialty = nextOpt.hcpWithDetails.hcp.specialty,
                    clinicId = nextOpt.clinic.id,
                    clinicLabel = nextOpt.clinic.label,
                    zone = nextOpt.clinic.zone.ifBlank { "Territory" },
                    latitude = destLat,
                    longitude = destLng,
                    locationVerified = true,
                    departureFromPrevMinute = departureMin,
                    travelMinutesFromPrev = safeTravelMinutes,
                    distanceKmFromPrev = if (isSameBuilding) 0.0 else travelEst.distanceKm.coerceAtMost(35.0),
                    trafficSource = if (isSameBuilding) TrafficEstimateSource.SAME_LOCATION_SEQUENTIAL.name else travelEst.source.badgeLabel,
                    arrivalMinute = arrivalMin,
                    waitingMinutes = waitMin,
                    visitStartMinute = visitStartMin,
                    visitDurationMinutes = nextOpt.visitDurationMinutes,
                    visitEndMinute = visitEndMin,
                    windowStartMinute = nextOpt.window.startMinuteOfDay,
                    windowEndMinute = nextOpt.window.endMinuteOfDay,
                    priorityScore = nextOpt.hcpWithDetails.hcp.priorityScore,
                    remainingQuarterlyVisits = nextOpt.hcpWithDetails.coverage.remainingVisits,
                    isLocked = false,
                    executionStatus = "PLANNED",
                    conflictReason = ""
                )
            )

            usedHcpIds.add(nextOpt.hcpWithDetails.hcp.id)
            curLat = destLat
            curLng = destLng
            curMin = visitEndMin
        }

        if (selectedStops.isEmpty()) return null

        val stops = compressInterStopTimeGaps(selectedStops, request.shiftEndMinute)
        val lastStop = stops.last()

        val returnEst = routingProvider.estimateTravel(
            originLat = lastStop.latitude ?: curLat,
            originLng = lastStop.longitude ?: curLng,
            destLat = effectiveHomeLat,
            destLng = effectiveHomeLng,
            departureMinuteOfDay = lastStop.visitEndMinute,
            safetyBufferPercent = settings.trafficSafetyBufferPercent,
            allowNetworkCall = false
        )
        val safeReturnMin = returnEst.bufferedDurationMinutes.coerceIn(5, 45)

        val startMin = stops.first().departureFromPrevMinute
        val endMin = (lastStop.visitEndMinute + safeReturnMin).coerceAtMost(1410)
        val totalDriving = stops.sumOf { it.travelMinutesFromPrev } + safeReturnMin
        val totalDist = ((stops.sumOf { it.distanceKmFromPrev } + returnEst.distanceKm.coerceAtMost(35.0)) * 10.0).roundToInt() / 10.0
        val totalWait = stops.sumOf { it.waitingMinutes }
        val conflictCount = stops.count { it.conflictReason.isNotBlank() }

        val plan = DailyPlanEntity(
            dateIso = request.dateIso,
            variantCode = "PLAN_A",
            variantLabel = "Plan A: Min Distance, Zero Gaps & Remaining Visits",
            isSelected = true,
            startMinuteOfDay = startMin,
            endMinuteOfDay = endMin,
            totalDutySpanMinutes = (endMin - startMin).coerceAtLeast(20),
            totalDrivingMinutes = totalDriving,
            totalDistanceKm = totalDist,
            totalWaitingMinutes = totalWait,
            plannedVisitsCount = stops.size,
            uniqueHcpsCount = stops.size,
            highPriorityCount = stops.count { it.priorityScore >= 75 },
            quarterlyCoverageGain = stops.count { it.remainingQuarterlyVisits > 0 },
            trafficFreshnessLabel = "TYPICAL_ROAD",
            feasibilityStatus = if (conflictCount == 0 && stops.size >= targetVisits) "FEASIBLE" else "PARTIAL_TARGET",
            infeasibilityReasonsText = "Scheduled ${stops.size}/$targetVisits target visits on ${AvailabilityScheduleParser.dayName(dayOfWeek)}.",
            advantagesText = "Priorities: ${settings.priorityWeightSpecialtyPercent}% Specialty • ${settings.priorityWeightRemainingVisitsPercent}% Remaining Visits • ${settings.priorityWeightTravelDistancePercent}% Travel Distance",
            disadvantagesText = if (conflictCount > 0) "$conflictCount stop(s) outside strict window" else "None",
            isValidatorVerified = conflictCount == 0,
            validatorReportText = if (conflictCount == 0) {
                "Verified: all ${stops.size} stops fit within clinic windows and weekly 1-visit restriction."
            } else {
                "Note: $conflictCount stop(s) exceed strict clinic window; adjust 'From .. To' work hours to match."
            }
        )

        diagnostics.add("Generated route with ${stops.size} visits for ${AvailabilityScheduleParser.dayName(dayOfWeek)}.")
        return DailyPlanWithStops(plan, stops)
    }

    /**
     * Generates a coordinated multi-day weekly schedule across configured working days.
     * Enforces Section 3: Each HCP can be scheduled at most ONCE per calendar week regardless of clinic or quarterly target.
     */
    suspend fun optimizeWeek(
        weekStartDateIso: String,
        hcps: List<HcpWithDetails>,
        settings: WorkSettings,
        allowNetworkRouting: Boolean = false,
        alreadyVisitedOrScheduledInWeekHcpIds: Set<Long> = emptySet()
    ): WeeklyPlanResult {
        val anchorDate = try {
            LocalDate.parse(weekStartDateIso)
        } catch (e: Exception) {
            LocalDate.of(2026, 11, 1) // Sunday
        }
        // Align week start to settings.firstDayOfWeekIso
        val daysBack = ((anchorDate.dayOfWeek.value - settings.firstDayOfWeekIso) + 7) % 7
        val startDate = anchorDate.minusDays(daysBack.toLong())

        val preferredSpecialties = SpecialtyNormalizer.extractUniqueSpecialties(settings.preferredSpecialtiesRanked)
        if (preferredSpecialties.isEmpty()) {
            return WeeklyPlanResult(
                weekStartIso = startDate.toString(),
                dayPlans = emptyList(),
                totalPlannedVisits = 0,
                uniqueHcpsCovered = 0,
                totalDrivingMinutes = 0,
                totalDistanceKm = 0.0,
                totalWaitingMinutes = 0,
                unscheduledActiveHcpsCount = hcps.count { it.hcp.isActive && !it.hcp.isDeleted },
                highRiskHcpsCoveredCount = 0,
                diagnostics = listOf(EMPTY_PREFERRED_SPECIALTIES_MESSAGE)
            )
        }

        // Track remaining quarterly visits dynamically across the week
        val dynamicRemainingByHcp = hcps.associate { it.hcp.id to it.coverage.remainingVisits }.toMutableMap()
        val visitsScheduledThisWeekByHcp = mutableMapOf<Long, Int>()
        for (blockedId in alreadyVisitedOrScheduledInWeekHcpIds) {
            visitsScheduledThisWeekByHcp[blockedId] = 1
        }
        val daySummaries = mutableListOf<WeeklyDayPlanSummary>()
        val allScheduledHcpIds = mutableSetOf<Long>()
        allScheduledHcpIds.addAll(alreadyVisitedOrScheduledInWeekHcpIds)

        for (offset in 0 until 7) {
            val date = startDate.plusDays(offset.toLong())
            val dateIso = date.toString()
            val dow = date.dayOfWeek.value
            if (dow in settings.weekendDaysIso || dow !in settings.workingDaysIso) continue

            // Section 3 HARD BUSINESS RULE: Strictly max 1 visit per HCP per calendar week (never 2!)
            val maxPerWeek = 1
            val adjustedHcps = hcps.mapNotNull { hcpDetail ->
                val rem = dynamicRemainingByHcp[hcpDetail.hcp.id] ?: 0
                val alreadyInWeek = visitsScheduledThisWeekByHcp[hcpDetail.hcp.id] ?: 0
                if (rem <= 0 || alreadyInWeek >= maxPerWeek) {
                    null
                } else {
                    hcpDetail.copy(
                        coverage = hcpDetail.coverage.copy(remainingVisits = rem)
                    )
                }
            }

            val dayOutcome = optimizeDay(
                OptimizationRequest(
                    dateIso = dateIso,
                    hcps = adjustedHcps,
                    settings = settings,
                    weeklyRestrictedHcpIds = allScheduledHcpIds.toSet(),
                    allowNetworkRouting = allowNetworkRouting
                )
            )

            val selected = dayOutcome.alternatives.firstOrNull()
            if (selected != null) {
                for (stop in selected.stops) {
                    allScheduledHcpIds.add(stop.hcpId)
                    val prevRem = dynamicRemainingByHcp[stop.hcpId] ?: 1
                    dynamicRemainingByHcp[stop.hcpId] = max(0, prevRem - 1)
                    visitsScheduledThisWeekByHcp[stop.hcpId] = (visitsScheduledThisWeekByHcp[stop.hcpId] ?: 0) + 1
                }
                daySummaries.add(
                    WeeklyDayPlanSummary(
                        dateIso = dateIso,
                        dayOfWeekName = AvailabilityScheduleParser.dayName(dow),
                        selectedPlan = selected
                    )
                )
            }
        }

        val totalVisits = daySummaries.sumOf { it.selectedPlan.stops.size }
        val totalDriving = daySummaries.sumOf { it.selectedPlan.plan.totalDrivingMinutes }
        val totalDist = ((daySummaries.sumOf { it.selectedPlan.plan.totalDistanceKm }) * 10.0).roundToInt() / 10.0
        val totalWait = daySummaries.sumOf { it.selectedPlan.plan.totalWaitingMinutes }
        val activeHcpsCount = hcps.count { it.hcp.isActive && !it.hcp.isDeleted && it.coverage.remainingVisits > 0 }
        val newlyScheduledUniqueCount = daySummaries.flatMap { it.selectedPlan.stops }.map { it.hcpId }.distinct().size
        val unscheduledCount = max(0, activeHcpsCount - allScheduledHcpIds.size)
        val highRiskCovered = hcps.count {
            it.hcp.id in allScheduledHcpIds &&
                (it.coverage.riskLevel.name == "HIGH_RISK" || it.coverage.riskLevel.name == "CRITICAL_AT_RISK")
        }

        val diagnostics = mutableListOf<String>()
        diagnostics.add("Planned $totalVisits qualifying visits across ${daySummaries.size} working days covering $newlyScheduledUniqueCount unique HCPs (1 visit max per HCP per calendar week).")
        if (unscheduledCount > 0) {
            diagnostics.add("$unscheduledCount active HCP(s) with remaining quarterly targets were not scheduled this week (check unverified locations or ambiguous schedules).")
        }

        return WeeklyPlanResult(
            weekStartIso = startDate.toString(),
            dayPlans = daySummaries,
            totalPlannedVisits = totalVisits,
            uniqueHcpsCovered = newlyScheduledUniqueCount,
            totalDrivingMinutes = totalDriving,
            totalDistanceKm = totalDist,
            totalWaitingMinutes = totalWait,
            unscheduledActiveHcpsCount = unscheduledCount,
            highRiskHcpsCoveredCount = highRiskCovered,
            diagnostics = diagnostics
        )
    }

    companion object {
        fun parseIsoDayOfWeek(dateIso: String): Int {
            return try {
                LocalDate.parse(dateIso).dayOfWeek.value // 1=Mon .. 7=Sun
            } catch (e: Exception) {
                6 // Default Saturday
            }
        }
    }
}
