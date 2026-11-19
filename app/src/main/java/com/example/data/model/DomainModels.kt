package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "hcps",
    indices = [Index(value = ["targetId"], unique = true)]
)
data class HcpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val targetId: String,
    val name: String,
    val specialty: String,
    val isActive: Boolean = true,
    val quarterlyTarget: Int = 8,
    val defaultVisitDurationMinutes: Int = 15,
    val priorityScore: Int = 70,
    val territoryZone: String = "",
    val tagsCsv: String = "",
    val sourceWorkbook: String = "West.xlsx",
    val importedBaselineVisits: Int = 0,
    val baselineQuarterLabel: String = "2026-Q4",
    val historyIncomplete: Boolean = false,
    val notes: String = "",
    val customFieldsJson: String = "{}",
    val version: Int = 1,
    val isDeleted: Boolean = false,
    val syncStatus: String = "PENDING", // SYNCED, PENDING, CONFLICT
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "clinics",
    foreignKeys = [
        ForeignKey(
            entity = HcpEntity::class,
            parentColumns = ["id"],
            childColumns = ["hcpId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["hcpId"])]
)
data class ClinicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val hcpId: Long,
    val clinicIndex: Int = 1, // 1 for Clinic 1, 2 for Clinic 2
    val label: String, // e.g., "Clinic 1 - Mohandessin"
    val zone: String,
    val originalLocationText: String = "",
    val googleMapsUrl: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val geocodingStatus: String = "UNVERIFIED", // VERIFIED, EXTRACTED_URL, UNVERIFIED, MISSING
    val rawScheduleText: String = "",
    val originalExcelScheduleText: String = "",
    val normalizedDaysCsv: String = "",
    val excludedDaysCsv: String = "",
    val exactStartMinute: Int? = null,
    val exactEndMinute: Int? = null,
    val timeInfoType: String = "CLINIC_AVAILABILITY_RANGE",
    val scheduleClassification: String = "", // EXPLICIT_WEEKDAYS_AND_TIME, DAILY_DEFAULT_WITH_TIME, DAILY_WITH_EXCEPTIONS, MULTIPLE_WEEKLY_WINDOWS, PARTIALLY_PARSED, REQUIRES_MANUAL_CLARIFICATION, NO_SCHEDULE_INFORMATION
    val bookingInstructions: String = "",
    val aiInterpretedScheduleText: String = "",
    val parsingMethod: String = "DETERMINISTIC", // DETERMINISTIC, AI_ASSISTED, USER_CONFIRMED, IMPORTED
    val parsingConfidence: Double = 1.0,
    val validationStatus: String = "VALID", // VALID, INCOMPLETE, NEEDS_CLARIFICATION, NO_SCHEDULE, INVALID
    val locationDescription: String = "",
    val sourceFile: String = "",
    val sourceSheet: String = "",
    val sourceRow: Int = 0,
    val sourceColumn: String = "",
    val scheduleParseStatus: String = "UNPARSED", // CONFIRMED, PARSED_HIGH_CONFIDENCE, AMBIGUOUS, UNPARSED
    val scheduleAmbiguityReason: String = "",
    val userConfirmedSchedule: Boolean = false, // True when schedule is validated & usable by optimizer (deterministic, AI-verified, or manual)
    val isManualUserConfirmed: Boolean = false, // True ONLY when manually edited/confirmed by user in UI (protected against re-import overwrite)
    val lastVerifiedEpochMs: Long? = null,
    val customVisitDurationMinutes: Int? = null,
    val isActive: Boolean = true
)

@Entity(
    tableName = "bricks",
    indices = [Index(value = ["normalizedName"], unique = true)]
)
data class BrickEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val normalizedName: String,
    val isActive: Boolean = true,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

object BrickNormalizer {
    private val compositeDelimiterRegex = Regex("""\s*(?:•|\+|\|)\s*""")
    private val whitespaceRegex = Regex("""\s+""")

    fun normalizeBrickName(raw: String): String {
        val cleaned = raw.replace(Regex("""[\r\n\t\u00A0]+"""), " ")
            .replace(whitespaceRegex, " ")
            .trim()
        if (cleaned.isBlank() || cleaned.equals("Unspecified Zone", ignoreCase = true)) {
            return ""
        }
        return cleaned
    }

    fun normalizeBrickKey(raw: String): String {
        return normalizeBrickName(raw).lowercase( java.util.Locale.ROOT)
    }

    fun isCompositeBrickName(raw: String): Boolean {
        val trimmed = raw.trim()
        return trimmed.contains("•") || trimmed.contains(" + ") || trimmed.contains("|")
    }

    fun splitCompositeBrickName(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(compositeDelimiterRegex)
            .map { normalizeBrickName(it) }
            .filter { it.isNotBlank() }
    }

    fun extractUniqueIndividualBricks(rawZones: Iterable<String>): List<String> {
        val seenKeys = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (raw in rawZones) {
            val parts = if (isCompositeBrickName(raw)) {
                splitCompositeBrickName(raw)
            } else {
                listOf(normalizeBrickName(raw)).filter { it.isNotBlank() }
            }
            for (part in parts) {
                val key = normalizeBrickKey(part)
                if (key.isNotBlank() && seenKeys.add(key)) {
                    result.add(part)
                }
            }
        }
        return result.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun matchesAllowedBricks(clinicZone: String, allowedBricks: Set<String>): Boolean {
        if (allowedBricks.isEmpty()) return true
        val clinicKey = normalizeBrickKey(clinicZone)
        if (clinicKey.isBlank()) return false
        return allowedBricks.any { allowed ->
            val allowedKey = normalizeBrickKey(allowed)
            allowedKey.isNotBlank() && allowedKey == clinicKey
        }
    }
}

@Entity(
    tableName = "availability_windows",
    foreignKeys = [
        ForeignKey(
            entity = ClinicEntity::class,
            parentColumns = ["id"],
            childColumns = ["clinicId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["clinicId"]),
        Index(value = ["hcpId"])
    ]
)
data class AvailabilityWindowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val clinicId: Long,
    val dayOfWeek: Int, // 1 = Monday .. 6 = Saturday, 7 = Sunday (ISO-8601)
    val startMinuteOfDay: Int, // e.g. 14 * 60 = 840 (2:00 PM)
    val endMinuteOfDay: Int,   // e.g. 18 * 60 = 1080 (6:00 PM); when endsNextDay=true, represents clock time on next calendar day
    val endsNextDay: Boolean = false,
    val isConfirmed: Boolean = false,
    val isException: Boolean = false,
    val exceptionDateIso: String = "",
    val hcpId: Long = 0L,
    val originalExcelText: String = "",
    val parsedTimeWindows: String = "",
    val sourceSheet: String = "",
    val sourceRow: Int = 0,
    val sourceColumn: String = "",
    val parsingStatus: String = "CONFIRMED"
) {
    /** Stable primary key alias (`scheduleId`). */
    val scheduleId: Long
        get() = id

    fun durationMinutes(): Int {
        return if (endsNextDay) {
            (1440 - startMinuteOfDay) + endMinuteOfDay
        } else {
            endMinuteOfDay - startMinuteOfDay
        }
    }

    fun effectiveSameDayEndMinute(): Int {
        return if (endsNextDay) endMinuteOfDay + 1440 else endMinuteOfDay
    }
}

@Entity(
    tableName = "visits",
    indices = [Index(value = ["hcpId"]), Index(value = ["plannedDateIso"])]
)
data class VisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val hcpId: Long,
    val hcpTargetId: String,
    val clinicId: Long,
    val clinicLabel: String,
    val plannedDateIso: String, // YYYY-MM-DD
    val plannedStartMinute: Int = 600,
    val plannedEndMinute: Int = 620,
    val actualStartEpochMs: Long? = null,
    val actualEndEpochMs: Long? = null,
    val status: String = "PLANNED", // PLANNED, COMPLETED, CANCELLED, NOT_COMPLETED
    val qualifiesForCoverage: Boolean = true,
    val quarterLabel: String = "2026-Q4",
    val recordSource: String = "APP_EXECUTION", // APP_EXECUTION, HISTORICAL_IMPORT, MANUAL_BASELINE, PLANNER
    val notes: String = "",
    val version: Int = 1,
    val syncStatus: String = "PENDING",
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "daily_plans",
    indices = [Index(value = ["dateIso"])]
)
data class DailyPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val dateIso: String,
    val variantCode: String = "PLAN_A", // PLAN_A, PLAN_B, PLAN_C
    val variantLabel: String = "Plan A: Minimize Travel",
    val isSelected: Boolean = true,
    val startMinuteOfDay: Int = 540,
    val endMinuteOfDay: Int = 960,
    val totalDutySpanMinutes: Int = 0,
    val totalDrivingMinutes: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val totalWaitingMinutes: Int = 0,
    val plannedVisitsCount: Int = 0,
    val uniqueHcpsCount: Int = 0,
    val highPriorityCount: Int = 0,
    val quarterlyCoverageGain: Int = 0,
    val trafficFreshnessLabel: String = "TYPICAL_ROAD",
    val feasibilityStatus: String = "FEASIBLE", // FEASIBLE, PARTIAL_TARGET, INFEASIBLE
    val infeasibilityReasonsText: String = "",
    val advantagesText: String = "",
    val disadvantagesText: String = "",
    val objectiveScore: Double = 0.0,
    val isValidatorVerified: Boolean = true,
    val validatorReportText: String = "Verified: All hard constraints respected.",
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "planned_stops",
    indices = [Index(value = ["planId"])]
)
data class PlannedStopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val planId: Long,
    val stopOrder: Int,
    val hcpId: Long,
    val hcpTargetId: String,
    val hcpName: String,
    val specialty: String,
    val clinicId: Long,
    val clinicLabel: String,
    val zone: String,
    val latitude: Double?,
    val longitude: Double?,
    val locationVerified: Boolean,
    val departureFromPrevMinute: Int,
    val travelMinutesFromPrev: Int,
    val distanceKmFromPrev: Double,
    val trafficSource: String, // LIVE_TRAFFIC, FRESH_CACHED_ROAD, STALE_CACHED_ROAD, TYPICAL_ROAD, APPROXIMATE_HAVERSINE
    val arrivalMinute: Int,
    val waitingMinutes: Int,
    val visitStartMinute: Int,
    val visitDurationMinutes: Int,
    val visitEndMinute: Int,
    val windowStartMinute: Int,
    val windowEndMinute: Int,
    val priorityScore: Int,
    val remainingQuarterlyVisits: Int,
    val isLocked: Boolean = false,
    val executionStatus: String = "PLANNED", // PLANNED, COMPLETED, CANCELLED, NOT_COMPLETED
    val conflictReason: String = ""
)

@Entity(tableName = "import_logs")
data class ImportLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fileName: String,
    val worksheetName: String,
    val importMode: String,
    val detectedRows: Int,
    val addedHcps: Int,
    val updatedHcps: Int,
    val unchangedHcps: Int,
    val ambiguousSchedulesCount: Int,
    val unverifiedLocationsCount: Int,
    val addedHcpTargetIdsCsv: String,
    val isRolledBack: Boolean = false,
    val importedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "route_cache")
data class RouteCacheEntity(
    @PrimaryKey val cacheKey: String,
    val originLat: Double,
    val originLng: Double,
    val destLat: Double,
    val destLng: Double,
    val travelMinutes: Int,
    val distanceKm: Double,
    val sourceType: String, // LIVE_TRAFFIC, FRESH_CACHED_ROAD, TYPICAL_ROAD
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val role: String, // "user" or "model"
    val content: String,
    val modelId: String = "gemini-4-argon",
    val taskTierLabel: String = "Complex Strategy",
    val timestampEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "import_mapping_profiles")
data class ImportMappingProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val profileName: String,
    val headerSignature: String,
    val fieldToColumnJson: String,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class ClinicWithAvailability(
    val clinic: ClinicEntity,
    val windows: List<AvailabilityWindowEntity>
)

enum class CoverageRiskLevel(val label: String) {
    ON_TRACK("On Track"),
    MODERATE_RISK("Moderate Risk"),
    HIGH_RISK("High Risk"),
    CRITICAL_AT_RISK("Critical Risk")
}

data class HcpCoverageMetrics(
    val quarterlyTarget: Int,
    val completedVisitsThisQuarter: Int,
    val remainingVisits: Int,
    val lastCompletedVisitDateIso: String?,
    val daysSinceLastVisit: Int?,
    val isOverdue: Boolean,
    val overdueReason: String,
    val riskLevel: CoverageRiskLevel,
    val riskScore: Double,
    val riskFormulaExplanation: String,
    val projectedQuarterEndVisits: Int
)

data class HcpWithDetails(
    val hcp: HcpEntity,
    val clinics: List<ClinicWithAvailability>,
    val visits: List<VisitEntity>,
    val coverage: HcpCoverageMetrics
)

data class DailyPlanWithStops(
    val plan: DailyPlanEntity,
    val stops: List<PlannedStopEntity>
)

data class WorkSettings(
    val dailyHcpTarget: Int = 8,
    val maxDutySpanMinutes: Int = 480, // 8 hours continuous span
    val homeAddress: String = "Sheikh Zayed City, Giza, Egypt",
    val homeLatitude: Double = 30.0444,
    val homeLongitude: Double = 30.9760,
    val returnHomeRequired: Boolean = true,
    val defaultVisitDurationMinutes: Int = 15,
    val sameLocationTransitionMinutes: Int = 0, // 0 min default transition when two HCPs share same verified clinic/building
    val breakEnabled: Boolean = true,
    val breakDurationMinutes: Int = 15,
    val weekendDaysIso: Set<Int> = setOf(4, 5, 6), // Thursday (4), Friday (5), Saturday (6)
    val workingDaysIso: Set<Int> = setOf(7, 1, 2, 3), // Sunday (7), Monday (1), Tuesday (2), Wednesday (3)
    val firstDayOfWeekIso: Int = 7, // 7 = Sunday, 6 = Saturday, 1 = Monday (independent of working days)
    val territoryTimeZoneId: String = "Africa/Cairo",
    val currentQuarterLabel: String = "2026-Q4",
    val remainingQuarterWorkdays: Int = 42,
    val overdueThresholdDays: Int = 14,
    val trafficSafetyBufferPercent: Int = 10,
    val priorityWeightSpecialtyPercent: Int = PlanningPriorityWeights.DEFAULT_SPECIALTY_PCT, // Default 30%
    val priorityWeightRemainingVisitsPercent: Int = PlanningPriorityWeights.DEFAULT_REMAINING_VISITS_PCT, // Default 40%
    val priorityWeightTravelDistancePercent: Int = PlanningPriorityWeights.DEFAULT_TRAVEL_DISTANCE_PCT, // Default 30%
    val preferredSpecialtiesRanked: List<String> = SpecialtyNormalizer.DEFAULT_INITIAL_SPECIALTIES,
    val availableSpecialtiesCatalog: List<String> = emptyList(),
    val hasUserConfiguredPreferredSpecialties: Boolean = false,
    val enforceWeeklyHcpVisitLimit: Boolean = true, // Mandatory default rule: max 1 visit per HCP per calendar week
    val weightTravelTime: Double = 0.30,
    val weightDistance: Double = 0.30,
    val weightPriority: Double = 0.30,
    val weightCoverage: Double = 0.40,
    val weightWaitingTime: Double = 0.10,
    val defaultImportMode: String = "UPDATE_EXISTING", // ADD_NEW_ONLY, UPDATE_EXISTING, REVIEW_AND_SYNC
    val blankCellOverwritesVerified: Boolean = false,
    val cloudSyncEnabled: Boolean = true
) {
    val totalPriorityWeightPercent: Int
        get() = priorityWeightSpecialtyPercent + priorityWeightRemainingVisitsPercent + priorityWeightTravelDistancePercent

    val normalizedSpecialtyWeight: Double
        get() {
            val normalized = PlanningPriorityWeights.normalizeTriple(
                priorityWeightSpecialtyPercent,
                priorityWeightRemainingVisitsPercent,
                priorityWeightTravelDistancePercent
            )
            return normalized.first / 100.0
        }

    val normalizedRemainingVisitsWeight: Double
        get() {
            val normalized = PlanningPriorityWeights.normalizeTriple(
                priorityWeightSpecialtyPercent,
                priorityWeightRemainingVisitsPercent,
                priorityWeightTravelDistancePercent
            )
            return normalized.second / 100.0
        }

    val normalizedTravelDistanceWeight: Double
        get() {
            val normalized = PlanningPriorityWeights.normalizeTriple(
                priorityWeightSpecialtyPercent,
                priorityWeightRemainingVisitsPercent,
                priorityWeightTravelDistancePercent
            )
            return normalized.third / 100.0
        }
}

object SpecialtyNormalizer {
    private val whitespaceRegex = Regex("""[\r\n\t\u00A0\s]+""")
    private val invalidPlaceholderKeys = setOf(
        "",
        "unspecified",
        "unspecified specialty",
        "unknown",
        "unknown specialty",
        "unmatched",
        "n/a",
        "na",
        "none",
        "null",
        "-",
        "--",
        "?"
    )

    val DEFAULT_INITIAL_SPECIALTIES: List<String> = listOf(
        "Cardiology",
        "Internal Medicine",
        "Neurology",
        "Pediatrics",
        "General Practice",
        "Chest",
        "Vascular Surgery",
        "vascular",
        "Tropical Medicine & Gastroenterology",
        "Endocrinology",
        "Orthopedics",
        "Dermatology"
    )

    /**
     * Normalizes harmless formatting differences (extra spaces, tabs/newlines, all-lowercase/uppercase casing)
     * without merging genuinely different specialties or mapping unknown specialties to preferred ones.
     */
    fun normalizeSpecialtyDisplay(raw: String): String {
        val collapsed = raw.replace(whitespaceRegex, " ").trim()
        val lower = collapsed.lowercase(java.util.Locale.ROOT)
        if (lower in invalidPlaceholderKeys || collapsed.contains("?")) {
            return ""
        }
        // If all lowercase or all uppercase (and > 3 chars), convert to clean Title Case while preserving short acronyms like ENT
        if (collapsed.length > 3 && (collapsed == collapsed.lowercase(java.util.Locale.ROOT) || collapsed == collapsed.uppercase(java.util.Locale.ROOT))) {
            return collapsed.split(" ").joinToString(" ") { word ->
                if (word.isEmpty()) ""
                else word.lowercase(java.util.Locale.ROOT).replaceFirstChar { it.titlecase(java.util.Locale.ROOT) }
            }
        }
        return collapsed
    }

    /**
     * Returns a stable normalized key for exact specialty matching (trimmed, collapsed whitespace, lowercase).
     * Never fuzzy-matches or maps an unknown specialty to a preferred specialty.
     */
    fun normalizeSpecialtyKey(raw: String): String {
        val display = normalizeSpecialtyDisplay(raw)
        if (display.isBlank()) return ""
        return display.lowercase(java.util.Locale.ROOT)
    }

    /**
     * Returns true if the HCP's specialty is missing, blank, placeholder, or uncertain/unmatched.
     */
    fun isMissingOrUnmatchedSpecialty(raw: String): Boolean {
        return normalizeSpecialtyKey(raw).isBlank()
    }

    /**
     * Extracts unique valid specialties from a collection of raw strings, preserving clean display names.
     */
    fun extractUniqueSpecialties(rawSpecialties: Iterable<String>): List<String> {
        val seenKeys = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (raw in rawSpecialties) {
            val display = normalizeSpecialtyDisplay(raw)
            val key = normalizeSpecialtyKey(display)
            if (key.isNotBlank() && seenKeys.add(key)) {
                result.add(display)
            }
        }
        return result
    }

    /**
     * Merges existing catalog, current HCP specialties, and saved preferred specialties into a complete
     * available specialties list without deleting saved preferences or auto-selecting newly imported specialties.
     */
    fun mergeAvailableSpecialties(
        existingAvailable: Iterable<String>,
        hcpSpecialties: Iterable<String>,
        preferredSpecialties: Iterable<String>
    ): List<String> {
        val combined = mutableListOf<String>()
        combined.addAll(preferredSpecialties)
        combined.addAll(existingAvailable)
        combined.addAll(hcpSpecialties)
        return extractUniqueSpecialties(combined)
    }

    /**
     * Returns true if [rawSpecialty] is in [preferredSpecialtiesRanked] (by normalized key).
     * Never matches missing/unknown specialties.
     */
    fun isSpecialtySelected(rawSpecialty: String, preferredSpecialtiesRanked: List<String>): Boolean {
        val key = normalizeSpecialtyKey(rawSpecialty)
        if (key.isBlank()) return false
        return preferredSpecialtiesRanked.any { normalizeSpecialtyKey(it) == key }
    }

    /**
     * Returns the 1-based rank (1 = highest priority) of [rawSpecialty] in [preferredSpecialtiesRanked],
     * or null if [rawSpecialty] is not selected or is missing.
     */
    fun rankOfSpecialty(rawSpecialty: String, preferredSpecialtiesRanked: List<String>): Int? {
        val key = normalizeSpecialtyKey(rawSpecialty)
        if (key.isBlank()) return null
        val uniqueRanked = extractUniqueSpecialties(preferredSpecialtiesRanked)
        val idx = uniqueRanked.indexOfFirst { normalizeSpecialtyKey(it) == key }
        return if (idx >= 0) idx + 1 else null
    }

    /**
     * Computes a normalized Specialty Score in [0.0, 1.0] based on the specialty's individual rank
     * within [rankedPreferredSpecialties] (Section 4):
     * - Rank 1 receives 1.00
     * - Rank k of N (N > 1) receives 1.0 - 0.75 * ((k - 1) / (N - 1)), so Rank 1 > Rank 2 > ... > Rank N >= 0.25
     * - Unselected or missing specialty receives 0.0
     */
    fun computeNormalizedSpecialtyRankScore(
        rawSpecialty: String,
        rankedPreferredSpecialties: List<String>
    ): Double {
        val uniqueRanked = extractUniqueSpecialties(rankedPreferredSpecialties)
        if (uniqueRanked.isEmpty()) return 0.0
        val key = normalizeSpecialtyKey(rawSpecialty)
        if (key.isBlank()) return 0.0
        val idx = uniqueRanked.indexOfFirst { normalizeSpecialtyKey(it) == key }
        if (idx < 0) return 0.0
        if (uniqueRanked.size == 1) return 1.0
        val fraction = idx.toDouble() / (uniqueRanked.size - 1).toDouble()
        return (1.0 - 0.75 * fraction).coerceIn(0.25, 1.0)
    }

    /**
     * Toggles selection of [specialty] in [currentRanked].
     * If already selected, removes it; if not selected, appends it at the end of the ranked list.
     */
    fun toggleSpecialtySelection(currentRanked: List<String>, specialty: String): List<String> {
        val cleanDisplay = normalizeSpecialtyDisplay(specialty)
        val targetKey = normalizeSpecialtyKey(cleanDisplay)
        if (targetKey.isBlank()) return extractUniqueSpecialties(currentRanked)
        val normalizedList = extractUniqueSpecialties(currentRanked).toMutableList()
        val existingIdx = normalizedList.indexOfFirst { normalizeSpecialtyKey(it) == targetKey }
        if (existingIdx >= 0) {
            normalizedList.removeAt(existingIdx)
        } else {
            normalizedList.add(cleanDisplay)
        }
        return normalizedList
    }

    /**
     * Reorders a selected specialty from [fromIndex] to [toIndex] (0-based) in [currentRanked].
     */
    fun moveSpecialtyRank(currentRanked: List<String>, fromIndex: Int, toIndex: Int): List<String> {
        val list = extractUniqueSpecialties(currentRanked).toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) {
            return list
        }
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        return list
    }
}

const val EMPTY_PREFERRED_SPECIALTIES_MESSAGE =
    "Please select at least one preferred specialty before generating your plan."

fun buildManualUnselectedSpecialtyWarning(hcpName: String, specialty: String): String {
    val cleanSpec = SpecialtyNormalizer.normalizeSpecialtyDisplay(specialty).ifBlank { "Missing/Unmatched Specialty" }
    return "Warning: $hcpName's specialty ($cleanSpec) is not in your Preferred Specialties list."
}

object PlanningPriorityWeights {
    const val DEFAULT_SPECIALTY_PCT = 30
    const val DEFAULT_REMAINING_VISITS_PCT = 40
    const val DEFAULT_TRAVEL_DISTANCE_PCT = 30

    const val DEFAULT_SPECIALTY_PERCENT = DEFAULT_SPECIALTY_PCT
    const val DEFAULT_REMAINING_VISITS_PERCENT = DEFAULT_REMAINING_VISITS_PCT
    const val DEFAULT_TRAVEL_DISTANCE_PERCENT = DEFAULT_TRAVEL_DISTANCE_PCT

    fun redistributePair(remainingTotal: Int, currentA: Int, currentB: Int): Pair<Int, Int> {
        val clampedTotal = remainingTotal.coerceIn(0, 100)
        if (clampedTotal == 0) return 0 to 0
        val safeA = currentA.coerceAtLeast(0)
        val safeB = currentB.coerceAtLeast(0)
        val sumAB = safeA + safeB
        if (sumAB <= 0) {
            val firstHalf = clampedTotal / 2
            return firstHalf to (clampedTotal - firstHalf)
        }
        val newA = kotlin.math.round((clampedTotal.toDouble() * safeA.toDouble()) / sumAB.toDouble())
            .toInt()
            .coerceIn(0, clampedTotal)
        val newB = clampedTotal - newA
        return newA to newB
    }

    fun normalizeTriple(specialty: Int, remainingVisits: Int, travelDistance: Int): Triple<Int, Int, Int> {
        val s = specialty.coerceIn(0, 100)
        val r = remainingVisits.coerceIn(0, 100)
        val t = travelDistance.coerceIn(0, 100)
        val total = s + r + t
        if (total == 100) return Triple(s, r, t)
        if (total <= 0) {
            return Triple(DEFAULT_SPECIALTY_PCT, DEFAULT_REMAINING_VISITS_PCT, DEFAULT_TRAVEL_DISTANCE_PCT)
        }
        val scaledS = kotlin.math.round((s * 100.0) / total).toInt().coerceIn(0, 100)
        val remAfterS = (100 - scaledS).coerceAtLeast(0)
        val (scaledR, scaledT) = redistributePair(remAfterS, r, t)
        return Triple(scaledS, scaledR, scaledT)
    }

    fun adjustSpecialtyWeight(settings: WorkSettings, newSpecialtyPct: Int): WorkSettings {
        val clampedS = newSpecialtyPct.coerceIn(0, 100)
        val (newR, newT) = redistributePair(
            remainingTotal = 100 - clampedS,
            currentA = settings.priorityWeightRemainingVisitsPercent,
            currentB = settings.priorityWeightTravelDistancePercent
        )
        return applyWeights(settings, clampedS, newR, newT)
    }

    fun adjustRemainingVisitsWeight(settings: WorkSettings, newRemainingVisitsPct: Int): WorkSettings {
        val clampedR = newRemainingVisitsPct.coerceIn(0, 100)
        val (newS, newT) = redistributePair(
            remainingTotal = 100 - clampedR,
            currentA = settings.priorityWeightSpecialtyPercent,
            currentB = settings.priorityWeightTravelDistancePercent
        )
        return applyWeights(settings, newS, clampedR, newT)
    }

    fun adjustTravelDistanceWeight(settings: WorkSettings, newTravelDistancePct: Int): WorkSettings {
        val clampedT = newTravelDistancePct.coerceIn(0, 100)
        val (newS, newR) = redistributePair(
            remainingTotal = 100 - clampedT,
            currentA = settings.priorityWeightSpecialtyPercent,
            currentB = settings.priorityWeightRemainingVisitsPercent
        )
        return applyWeights(settings, newS, newR, clampedT)
    }

    fun restoreDefaults(settings: WorkSettings): WorkSettings {
        return applyWeights(
            settings = settings,
            specialtyPct = DEFAULT_SPECIALTY_PCT,
            remainingVisitsPct = DEFAULT_REMAINING_VISITS_PCT,
            travelDistancePct = DEFAULT_TRAVEL_DISTANCE_PCT
        )
    }

    fun applyWeights(
        settings: WorkSettings,
        specialtyPct: Int,
        remainingVisitsPct: Int,
        travelDistancePct: Int
    ): WorkSettings {
        val (s, r, t) = normalizeTriple(specialtyPct, remainingVisitsPct, travelDistancePct)
        return settings.copy(
            priorityWeightSpecialtyPercent = s,
            priorityWeightRemainingVisitsPercent = r,
            priorityWeightTravelDistancePercent = t,
            weightPriority = s / 100.0,
            weightCoverage = r / 100.0,
            weightDistance = t / 100.0,
            weightTravelTime = t / 100.0
        )
    }
}

data class CalendarWeekBounds(
    val weekStartIso: String,
    val weekEndIso: String,
    val firstDayOfWeekIso: Int
) {
    val weekKey: String get() = "$weekStartIso..$weekEndIso"

    fun containsDate(dateIso: String): Boolean {
        return dateIso >= weekStartIso && dateIso <= weekEndIso
    }
}

object CalendarWeekCalculator {
    fun boundsForDate(dateIso: String, firstDayOfWeekIso: Int): CalendarWeekBounds {
        val normalizedFirstDay = firstDayOfWeekIso.coerceIn(1, 7)
        val parsed = try {
            java.time.LocalDate.parse(dateIso.trim())
        } catch (_: Exception) {
            java.time.LocalDate.of(2026, 11, 2)
        }
        val currentDow = parsed.dayOfWeek.value // 1 = Monday .. 7 = Sunday
        val daysSinceStart = ((currentDow - normalizedFirstDay) + 7) % 7
        val weekStart = parsed.minusDays(daysSinceStart.toLong())
        val weekEnd = weekStart.plusDays(6L)
        return CalendarWeekBounds(
            weekStartIso = weekStart.toString(),
            weekEndIso = weekEnd.toString(),
            firstDayOfWeekIso = normalizedFirstDay
        )
    }

    fun isSameCalendarWeek(dateIsoA: String, dateIsoB: String, firstDayOfWeekIso: Int): Boolean {
        val boundsA = boundsForDate(dateIsoA, firstDayOfWeekIso)
        return boundsA.containsDate(dateIsoB.trim())
    }

    fun datesInCalendarWeek(dateIso: String, firstDayOfWeekIso: Int): List<String> {
        val bounds = boundsForDate(dateIso, firstDayOfWeekIso)
        val start = java.time.LocalDate.parse(bounds.weekStartIso)
        return (0L..6L).map { offset -> start.plusDays(offset).toString() }
    }
}

data class WeeklyHcpBlockInfo(
    val hcpId: Long,
    val hcpTargetId: String,
    val hcpName: String,
    val blockingDateIso: String,
    val blockingClinicId: Long = 0L,
    val blockingClinicLabel: String = "",
    val blockingStatus: String,
    val source: String = "VISIT" // "COMPLETED_VISIT", "ACTIVE_VISIT", "SCHEDULED_PLAN"
)

const val WEEKLY_HCP_RESTRICTION_MESSAGE =
    "This HCP has already been visited or scheduled during this calendar week."

