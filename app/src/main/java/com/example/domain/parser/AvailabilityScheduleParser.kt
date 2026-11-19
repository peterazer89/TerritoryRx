package com.example.domain.parser

import com.example.data.model.AvailabilityWindowEntity
import java.util.Locale

enum class ScheduleParseStatus {
    CONFIRMED,
    PARSED_HIGH_CONFIDENCE,
    AMBIGUOUS,
    UNPARSED
}

enum class TimeInfoType(val label: String) {
    CLINIC_AVAILABILITY_RANGE("Actual Clinic Availability Range"),
    SPECIFIC_APPOINTMENT_TIME("Specific Appointment Time"),
    BOOKING_OR_CALL_TIME("Booking / Phone-Call Time"),
    APPROXIMATE_TIME("Approximate Time"),
    UNCONFIRMED_APPOINTMENT("Unconfirmed Appointment"),
    REQUIRES_MANUAL_CLARIFICATION("Schedule Requiring Manual Clarification")
}

enum class ScheduleClassification(val displayLabel: String) {
    EXPLICIT_WEEKDAYS_AND_TIME("Specific Weekdays & Hours"),
    DAILY_DEFAULT_WITH_TIME("Every Day (Default)"),
    DAILY_WITH_EXCEPTIONS("Daily with Excluded Days"),
    MULTIPLE_WEEKLY_WINDOWS("Multiple Weekly Windows"),
    PARTIALLY_PARSED("Incomplete Schedule"),
    REQUIRES_MANUAL_CLARIFICATION("Needs Clarification"),
    NO_SCHEDULE_INFORMATION("No Schedule Information")
}

enum class ParsingMethod(val displayLabel: String) {
    DETERMINISTIC("Direct Rule Parser"),
    AI_ASSISTED("AI-Assisted Interpretation"),
    USER_CONFIRMED("User Confirmed"),
    IMPORTED("Imported")
}

enum class ScheduleValidationStatus(val badgeLabel: String) {
    VALID("Valid"),
    INCOMPLETE("Incomplete"),
    NEEDS_CLARIFICATION("Needs clarification"),
    NO_SCHEDULE("Needs clarification"),
    INVALID("Needs clarification")
}

data class ParsedTimeWindow(
    val dayOfWeek: Int, // 1 = Mon .. 6 = Sat, 7 = Sun
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val endsNextDay: Boolean = false
) {
    val durationMinutes: Int
        get() = if (endsNextDay) (1440 - startMinuteOfDay) + endMinuteOfDay else (endMinuteOfDay - startMinuteOfDay)
}

data class ScheduleParseResult(
    val rawText: String,
    val windows: List<ParsedTimeWindow>,
    val status: ScheduleParseStatus,
    val ambiguityReason: String = "",
    val timeInfoType: TimeInfoType = TimeInfoType.CLINIC_AVAILABILITY_RANGE,
    val normalizedDays: List<Int> = emptyList(),
    val exactStartMinute: Int? = null,
    val exactEndMinute: Int? = null,
    val bookingInstructions: String = "",
    val classification: ScheduleClassification = ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION,
    val parsingMethod: ParsingMethod = ParsingMethod.DETERMINISTIC,
    val parsingConfidence: Double = 1.0,
    val validationStatus: ScheduleValidationStatus = ScheduleValidationStatus.VALID,
    val excludedDays: List<Int> = emptyList(),
    val locationDescription: String = ""
) {
    val clarificationRequired: Boolean
        get() = status == ScheduleParseStatus.AMBIGUOUS ||
            status == ScheduleParseStatus.UNPARSED ||
            timeInfoType != TimeInfoType.CLINIC_AVAILABILITY_RANGE ||
            windows.isEmpty()

    val availabilityTypeLabel: String
        get() = if (!clarificationRequired) "CONFIRMED_TIME_WINDOW" else timeInfoType.name

    val verificationStatusLabel: String
        get() = when {
            status == ScheduleParseStatus.CONFIRMED && !clarificationRequired -> "CONFIRMED"
            !clarificationRequired -> "PARSED_FROM_EXCEL"
            else -> status.name
        }

    val startTime24Hour: String?
        get() = exactStartMinute?.let { AvailabilityScheduleParser.formatMinute24Hour(it) }

    val endTime24Hour: String?
        get() = exactEndMinute?.let { AvailabilityScheduleParser.formatMinute24Hour(it) }

    fun toEntities(
        clinicId: Long,
        isConfirmed: Boolean = false,
        hcpId: Long = 0L,
        originalExcelText: String = rawText,
        sourceSheet: String = "",
        sourceRow: Int = 0,
        sourceColumn: String = "",
        parsingStatus: String = status.name
    ): List<AvailabilityWindowEntity> {
        val eligibleConfirmed = (isConfirmed || status == ScheduleParseStatus.CONFIRMED || status == ScheduleParseStatus.PARSED_HIGH_CONFIDENCE) &&
            timeInfoType == TimeInfoType.CLINIC_AVAILABILITY_RANGE
        val fullSummary = windows.joinToString("; ") { w ->
            val nextTag = if (w.endsNextDay) " (+1d)" else ""
            "${AvailabilityScheduleParser.dayShortName(w.dayOfWeek)} ${AvailabilityScheduleParser.formatMinute24Hour(w.startMinuteOfDay)}-${AvailabilityScheduleParser.formatMinute24Hour(w.endMinuteOfDay)}$nextTag"
        }
        return windows.map {
            AvailabilityWindowEntity(
                clinicId = clinicId,
                dayOfWeek = it.dayOfWeek,
                startMinuteOfDay = it.startMinuteOfDay,
                endMinuteOfDay = it.endMinuteOfDay,
                endsNextDay = it.endsNextDay,
                isConfirmed = eligibleConfirmed,
                hcpId = hcpId,
                originalExcelText = originalExcelText,
                parsedTimeWindows = fullSummary,
                sourceSheet = sourceSheet,
                sourceRow = sourceRow,
                sourceColumn = sourceColumn,
                parsingStatus = if (eligibleConfirmed && isConfirmed) ScheduleParseStatus.CONFIRMED.name else parsingStatus
            )
        }
    }
}

/**
 * Comprehensive availability schedule parser for medical representative Excel workbooks.
 * Handles all expected English & Arabic time formats including:
 * - Standard & colon ranges: "4-7 PM", "4:7 PM", "4 PM : 7 PM", "4:30 : 7:30 PM", "14:00 - 18:00"
 * - Slash, underscore, & natural connectors: "4/7 PM", "4_7 PM", "من 4 ل 7 م", "من 4 الى 7 مساء", "from 4 to 7 PM"
 * - Written Arabic & English number words: "من الواحدة ظهرا حتى الرابعة عصرا", "from two to six pm"
 * - Single start times: "Saturday and Monday 5 PM", "من 5 مساء", "After 4 PM", "Daily 6:30 PM"
 * - Shift & prayer times: "صباحا", "ظهرا", "بعد الظهر", "عصرا", "بعد المغرب", "مساء", "ليلا"
 * - Day-only or sibling-shared clinic schedules during Excel import.
 */
object AvailabilityScheduleParser {

    private val dayTokenMap = mapOf(
        "monday" to 1, "mon" to 1, "mo" to 1, "mondy" to 1, "mnday" to 1, "manday" to 1, "monady" to 1,
        "الاثنين" to 1, "الإثنين" to 1, "اثنين" to 1, "إثنين" to 1, "الأثنين" to 1, "اتنين" to 1, "الاتنين" to 1,
        "tuesday" to 2, "tue" to 2, "tues" to 2, "tu" to 2, "tusday" to 2, "teusday" to 2, "tuseday" to 2, "tuesdy" to 2, "tusdey" to 2,
        "الثلاثاء" to 2, "ثلاثاء" to 2, "ثلاث" to 2, "التلات" to 2, "تلات" to 2, "الثلاث" to 2,
        "wednesday" to 3, "wed" to 3, "weds" to 3, "we" to 3, "wensday" to 3, "wednsday" to 3, "wendesday" to 3, "wednseday" to 3, "wednesdy" to 3, "wendsday" to 3,
        "الأربعاء" to 3, "الاربعاء" to 3, "أربعاء" to 3, "اربعاء" to 3, "الاربع" to 3, "الأربع" to 3, "اربع" to 3,
        "thursday" to 4, "thu" to 4, "thur" to 4, "thurs" to 4, "th" to 4, "thrusday" to 4, "thurday" to 4, "thersday" to 4, "thurseday" to 4, "thursdy" to 4, "thrusdy" to 4,
        "الخميس" to 4, "خميس" to 4,
        "friday" to 5, "fri" to 5, "fr" to 5, "fridy" to 5, "fryday" to 5,
        "الجمعة" to 5, "الجمعه" to 5, "جمعة" to 5, "جمعه" to 5,
        "saturday" to 6, "sat" to 6, "sa" to 6, "saturdy" to 6, "saterday" to 6, "strday" to 6, "saturady" to 6, "satarday" to 6, "satrday" to 6,
        "السبت" to 6, "سبت" to 6,
        "sunday" to 7, "sun" to 7, "su" to 7, "sundy" to 7, "sanday" to 7,
        "الأحد" to 7, "الاحد" to 7, "أحد" to 7, "احد" to 7, "الحد" to 7, "حد" to 7
    )

    private val allDays = listOf(1, 2, 3, 4, 5, 6, 7)
    private val defaultEgyptianWorkingDays = listOf(6, 7, 1, 2, 3, 4) // Sat..Thu

    private data class RawTimeMatch(
        val matchedText: String,
        val rangeInClause: IntRange,
        val startHour: Int,
        val startMin: Int,
        val startMeridiem: String,
        val endHour: Int,
        val endMin: Int,
        val endMeridiem: String,
        val isSingleTimeWindow: Boolean = false
    )

    data class SimpleTimeWindow(
        val startMinuteOfDay: Int,
        val endMinuteOfDay: Int,
        val isAmbiguous: Boolean = false
    )

    // 1. Standard & extended range connectors: "4-7 PM", "8:30-10 PM", "7 PM - 10 PM", "14:00 - 18:00", "4 to 7 PM", "4/7 PM", "4_7 PM"
    private val explicitConnectorTimeRangeRegex = Regex(
        """(?<!\d)(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?\s*(?:to|until|till|thru|through|[-–—~/\\_]+|\.{2,})\s*(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?(?!\d)""",
        RegexOption.IGNORE_CASE
    )

    // 2. Colon range where start time has explicit AM/PM: "4 PM : 7 PM", "4PM:7PM", "4:30 PM : 7:30 PM", "4 PM : 7"
    private val meridiemColonRangeRegex = Regex(
        """(?<!\d)(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)\s*[:;]\s*(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?(?!\d)""",
        RegexOption.IGNORE_CASE
    )

    // 3a. Colon range where start time has minutes: "4:30 : 7:30 PM", "4:00 : 7:00 PM", "4:30 : 7 PM", "16:00 : 19:00"
    private val startMinutesColonRangeRegex = Regex(
        """(?<!\d)(\d{1,2})[:.](\d{2})\s*(a\.?m\.?|p\.?m\.?)?\s*[:;]\s*(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?(?!\d)""",
        RegexOption.IGNORE_CASE
    )

    // 3b. Colon range where end time has minutes: "4 : 7:30 PM", "4:7:30 PM"
    private val endMinutesColonRangeRegex = Regex(
        """(?<!\d)(\d{1,2})\s*(a\.?m\.?|p\.?m\.?)?\s*[:;]\s*(\d{1,2})[:.](\d{2})\s*(a\.?m\.?|p\.?m\.?)?(?!\d)""",
        RegexOption.IGNORE_CASE
    )

    // 4. Hour-colon-hour shorthand range: "4:7 PM", "4 : 7 PM", "4:10 PM", "8:10 PM", "4:7", "4 ; 7 PM", "4 . 7 PM"
    private val hourColonHourRangeRegex = Regex(
        """(?<!\d)(\d{1,2})(\s*[:;.]\s*)(\d{1,2})\s*(a\.?m\.?|p\.?m\.?)?(?!\d)""",
        RegexOption.IGNORE_CASE
    )

    // 5. "from H1 and/& H2" or "between H1 and/& H2"
    private val betweenAndRangeRegex = Regex(
        """(?:from|between)\s*(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?\s*(?:and|&|to)\s*(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?(?!\d)""",
        RegexOption.IGNORE_CASE
    )

    // 6. Single clock time with AM/PM or preceded by "from/after/starting/at" or 24-hour clock (e.g. "5 PM", "5:30 PM", "from 4", "16:00")
    private val singleClockTimeRegex = Regex(
        """(?:(?:from|after|starting|start|at|since)\s+)?(?<!\d)(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)(?!\d)|(?:from|after|starting|start|at)\s+(?<!\d)(\d{1,2})(?:[:.](\d{2}))?(?!\d)|(?<!\d)(1[3-9]|2[0-3])[:.](\d{2})(?!\d)""",
        RegexOption.IGNORE_CASE
    )

    // 7. Bare clock time fallback when a clause explicitly mentions weekdays or shift words (e.g., "Saturday 5:30", "Sunday and Tuesday 6")
    private val bareClockTimeWithContextRegex = Regex(
        """(?<!\d)(\d{1,2})(?:[:.](\d{2}))?(?!\d)"""
    )

    private val arabicDiacriticsRegex = Regex("""[\u064B-\u065F\u0670]""")

    private val arabicHalfRegex = Regex("""(?<![\p{L}\p{M}\p{N}_])(?:ونصف|ونص)(?![\p{L}\p{M}\p{N}_])""")
    private val arabicQuarterRegex = Regex("""(?<![\p{L}\p{M}\p{N}_])وربع(?![\p{L}\p{M}\p{N}_])""")
    private val arabicThreeQuarterRegex = Regex("""(?<![\p{L}\p{M}\p{N}_])(?:إلا\s+ربع|الا\s+ربع)(?![\p{L}\p{M}\p{N}_])""")
    private val commaMinutesRegex = Regex("""(?<!\d)(\d{1,2}),(00|15|30|45)(?!\d)""")

    private val arabicHourWords = listOf(
        Regex("""(?<![\p{L}\p{M}\p{N}_])(?:الساعة\s+|الساعه\s+)?(?:الحادية\s*عشر|حداشر)(?![\p{L}\p{M}\p{N}_])""") to " 11 ",
        Regex("""(?<![\p{L}\p{M}\p{N}_])(?:الساعة\s+|الساعه\s+)?(?:الثانية\s*عشر|اتناشر|إثناشر|اثناشر)(?![\p{L}\p{M}\p{N}_])""") to " 12 ",
        Regex("""(?<![\p{L}\p{M}\p{N}_])(?:الساعة\s+|الساعه\s+)?(?:الواحدة|واحدة|واحده)(?![\p{L}\p{M}\p{N}_])""") to " 1 ",
        Regex("""(?<![\p{L}\p{M}\p{N}_])(?:الساعة\s+|الساعه\s+)?(?:الثانية|ثانية|ثانيه|تنتين|ثنتين)(?![\p{L}\p{M}\p{N}_])""") to " 2 ",
        Regex("""(?<![\p{L}\p{M}\p{N}_])(?:الساعة\s+|الساعه\s+)?(?:الثالثة|ثلاثة|ثلاثه|تلاتة|تلاته)(?![\p{L}\p{M}\p{N}_])""") to " 3 ",
        Regex("""(?<![\p{L}\p{M}\p{N}_])(?:الساعة\s+|الساعه\s+)?(?:الرابعة|رابعة|أربعة|اربعة|اربعه)(?![\p{L}\p{M}\p{N}_])""") to " 4 ",
        Regex("""(?<![\p{L}\p{M}\p{N}_])(?:الساعة\s+|الساعه\s+)?(?:الخامسة|خامسة|خمسة|خمسه)(?![\p{L}\p{M}\p{N}_])""") to " 5 ",
        Regex("""(?<![\p{L}\p{M}\p{N}_])(?:الساعة\s+|الساعه\s+)?(?:السادسة|سادسة|ستة|سته)(?![\p{L}\p{M}\p{N}_])""") to " 6 ",
        Regex("""(?<![\p{L}\p{M}\p{N}_])(?:الساعة\s+|الساعه\s+)?(?:السابعة|سابعة|سبعة|سبعه)(?![\p{L}\p{M}\p{N}_])""") to " 7 ",
        Regex("""(?<![\p{L}\p{M}\p{N}_])(?:الساعة\s+|الساعه\s+)?(?:الثامنة|ثامنة|ثمانية|تمانية|تمانيه)(?![\p{L}\p{M}\p{N}_])""") to " 8 ",
        Regex("""(?<![\p{L}\p{M}\p{N}_])(?:الساعة\s+|الساعه\s+)?(?:التاسعة|تاسعة|تسعة|تسعه)(?![\p{L}\p{M}\p{N}_])""") to " 9 ",
        Regex("""(?<![\p{L}\p{M}\p{N}_])(?:الساعة\s+|الساعه\s+)?(?:العاشرة|عاشرة|عشرة|عشره)(?![\p{L}\p{M}\p{N}_])""") to " 10 "
    )

    private val arabicFromRegex = Regex("""(?<![\p{L}\p{M}\p{N}_])(?:من\s+الساعة|من\s+الساعه|الساعة|الساعه|ابتداء\s+من|بداية\s+من|من|بين)(?![\p{L}\p{M}\p{N}_])""")
    private val arabicAfterRegex = Regex("""(?<![\p{L}\p{M}\p{N}_])(?:بعد\s+الساعة|بعد\s+الساعه|بعد)(?![\p{L}\p{M}\p{N}_])""")
    private val arabicToWordRegex = Regex("""(?<![\p{L}\p{M}\p{N}_])(?:إلى|الى|الي|حتى|حتي|وحتى|وحتي|لغاية|لغايه|لحد)(?![\p{L}\p{M}\p{N}_])""")
    private val arabicAmWordRegex = Regex("""في\s+الصباح|صباحاً|صباحًا|صباحا|صباح|الصبح""")
    private val arabicPmWordRegex = Regex("""في\s+المساء|مساءً|مساءا|مساء|ليلاً|ليلًا|ليلا|بالليل|العشاء|عشاءً|عشاء|المغرب|مغرباً|مغرب""")
    private val arabicNoonPmWordRegex = Regex("""بعد\s+الظهر|الظهر|ظهراً|ظهرًا|ظهرا|بعد\s+العصر|العصر|عصراً|عصرًا|عصرا""")
    private val arabicShortAmRegex = Regex("""(\d)\s*ص(?![\p{L}])""")
    private val arabicShortPmRegex = Regex("""(\d)\s*(?:م|ظ|ع)(?![\p{L}])""")
    private val arabicLamBetweenTimeTokensRegex = Regex(
        """((?:\d|AM|PM)\s*)(?:لـ|ل)(\s*\d)""",
        RegexOption.IGNORE_CASE
    )
    private val arabicBetweenAndTimeRegex = Regex("""\bbetween\b\s*(\d{1,2}(?:[:.]\d{2})?)\s*و\s*(\d{1,2}(?:[:.]\d{2})?)""", RegexOption.IGNORE_CASE)

    // Lookbehind-free normalization for "3 or 4 : 6 PM" / "3 or 4 - 6 PM" / "from 3 or 4 PM" (compatible with Android ICU regex engine)
    private val startOrHourRangeRegex = Regex(
        """(?<!\d)(\d{1,2}(?:[:.]\d{2})?)\s*(?:or|أو|او)\s*\d{1,2}(?:[:.]\d{2})?(\s*(?::|;|to|until|till|thru|through|[-–—~/\\_]))""",
        RegexOption.IGNORE_CASE
    )
    private val endOrHourRangeRegex = Regex(
        """((?::|;|to|until|till|thru|through|[-–—~/\\_])\s*)\d{1,2}(?:[:.]\d{2})?\s*(?:or|أو|او)\s*(\d{1,2}(?:[:.]\d{2})?)""",
        RegexOption.IGNORE_CASE
    )
    private val singleOrTimeRegex = Regex(
        """(?<!\d)(\d{1,2}(?:[:.]\d{2})?)\s*(?:or|أو|او)\s*\d{1,2}(?:[:.]\d{2})?(\s*(?:a\.?m\.?|p\.?m\.?))""",
        RegexOption.IGNORE_CASE
    )

    private fun normalizeTextForTimeParsing(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '\u0660'..'\u0669' -> sb.append(('0'.code + (ch.code - '\u0660'.code)).toChar())
                in '\u06F0'..'\u06F9' -> sb.append(('0'.code + (ch.code - '\u06F0'.code)).toChar())
                '\u00A0' -> sb.append(' ')
                in '\u200B'..'\u200F', in '\u202A'..'\u202E', '\uFEFF' -> {}
                else -> sb.append(ch)
            }
        }
        var s = sb.toString()

        // Normalize comma-minutes like "8,30" -> "8:30" and Arabic fractional time expressions
        s = s.replace(commaMinutesRegex, "$1:$2")
            .replace(arabicHalfRegex, ":30")
            .replace(arabicQuarterRegex, ":15")
            .replace(arabicThreeQuarterRegex, ":45")

        // Normalize written Arabic hour numbers (when used in time contexts)
        for ((pattern, replacement) in arabicHourWords) {
            s = s.replace(pattern, replacement)
        }

        // Normalize Arabic AM/PM words & suffixes BEFORE converting "ل" between time tokens so "من 5م ل 8م" -> "from 5 PM to 8 PM"
        s = s.replace(arabicAmWordRegex, " AM ")
            .replace(arabicNoonPmWordRegex, " PM ")
            .replace(arabicPmWordRegex, " PM ")
            .replace(arabicShortAmRegex, "$1 AM ")
            .replace(arabicShortPmRegex, "$1 PM ")
            .replace(arabicAfterRegex, " after ")
            .replace(arabicFromRegex, " from ")
            .replace(arabicToWordRegex, " to ")
            .replace(arabicLamBetweenTimeTokensRegex, "$1 to $2")
            .replace(arabicBetweenAndTimeRegex, "between $1 and $2")

        // Normalize "3 or 4 : 6 PM" / "3 or 4 - 6 PM" / "3 or 4 PM"
        s = s.replace(startOrHourRangeRegex, "$1$2")
        s = s.replace(endOrHourRangeRegex, "$1$2")
        s = s.replace(singleOrTimeRegex, "$1$2")

        return s
    }

    private fun extractRawTimeMatches(clause: String, hasDayOrShiftContext: Boolean = false): List<RawTimeMatch> {
        val cleanedClause = clause.replace(ordinalSuffixRegex, " ")
        val normalized = normalizeTextForTimeParsing(cleanedClause)
        val occupied = BooleanArray(normalized.length)
        val results = mutableListOf<RawTimeMatch>()

        fun isRangeFree(range: IntRange): Boolean {
            for (i in range) {
                if (i in occupied.indices && occupied[i]) return false
            }
            return true
        }

        fun markOccupied(range: IntRange) {
            for (i in range) {
                if (i in occupied.indices) occupied[i] = true
            }
        }

        // Step 1: Explicit connectors (-, –, —, to, until, till, thru, through, ~, /, \, _, ..)
        for (m in explicitConnectorTimeRangeRegex.findAll(normalized)) {
            if (!isRangeFree(m.range)) continue
            val sh = m.groupValues[1].toIntOrNull() ?: continue
            val sm = m.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            val sMer = normalizeMeridiem(m.groupValues[3])
            val eh = m.groupValues[4].toIntOrNull() ?: continue
            val em = m.groupValues[5].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            val eMer = normalizeMeridiem(m.groupValues[6])
            if (sh in 0..24 && eh in 0..24 && sm in 0..59 && em in 0..59) {
                markOccupied(m.range)
                results.add(RawTimeMatch(m.value.trim(), m.range, sh, sm, sMer, eh, em, eMer))
            }
        }

        // Step 1b: "between 4 and 7 PM" / "from 4 & 7 PM"
        for (m in betweenAndRangeRegex.findAll(normalized)) {
            if (!isRangeFree(m.range)) continue
            val sh = m.groupValues[1].toIntOrNull() ?: continue
            val sm = m.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            val sMer = normalizeMeridiem(m.groupValues[3])
            val eh = m.groupValues[4].toIntOrNull() ?: continue
            val em = m.groupValues[5].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            val eMer = normalizeMeridiem(m.groupValues[6])
            if (sh in 0..24 && eh in 0..24) {
                markOccupied(m.range)
                results.add(RawTimeMatch(m.value.trim(), m.range, sh, sm, sMer, eh, em, eMer))
            }
        }

        // Step 2: Colon/semicolon separator with explicit start AM/PM ("4 PM : 7 PM", "4:30 PM : 7:30 PM")
        for (m in meridiemColonRangeRegex.findAll(normalized)) {
            if (!isRangeFree(m.range)) continue
            val sh = m.groupValues[1].toIntOrNull() ?: continue
            val sm = m.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            val sMer = normalizeMeridiem(m.groupValues[3])
            val eh = m.groupValues[4].toIntOrNull() ?: continue
            val em = m.groupValues[5].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            val eMer = normalizeMeridiem(m.groupValues[6])
            markOccupied(m.range)
            results.add(RawTimeMatch(m.value.trim(), m.range, sh, sm, sMer, eh, em, eMer))
        }

        // Step 3a: Colon/semicolon separator where start time has minutes ("4:30 : 7:30 PM", "4:30 : 7 PM", "16:00 : 19:00")
        for (m in startMinutesColonRangeRegex.findAll(normalized)) {
            if (!isRangeFree(m.range)) continue
            val sh = m.groupValues[1].toIntOrNull() ?: continue
            val sm = m.groupValues[2].toIntOrNull() ?: continue
            val sMer = normalizeMeridiem(m.groupValues[3])
            val eh = m.groupValues[4].toIntOrNull() ?: continue
            val em = m.groupValues[5].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            val eMer = normalizeMeridiem(m.groupValues[6])
            markOccupied(m.range)
            results.add(RawTimeMatch(m.value.trim(), m.range, sh, sm, sMer, eh, em, eMer))
        }

        // Step 3b: Colon/semicolon separator where end time has minutes ("4 : 7:30 PM")
        for (m in endMinutesColonRangeRegex.findAll(normalized)) {
            if (!isRangeFree(m.range)) continue
            val sh = m.groupValues[1].toIntOrNull() ?: continue
            val sMer = normalizeMeridiem(m.groupValues[2])
            val eh = m.groupValues[3].toIntOrNull() ?: continue
            val em = m.groupValues[4].toIntOrNull() ?: continue
            val eMer = normalizeMeridiem(m.groupValues[5])
            markOccupied(m.range)
            results.add(RawTimeMatch(m.value.trim(), m.range, sh, 0, sMer, eh, em, eMer))
        }

        // Step 4: Hour-colon-hour shorthand ("4:7 PM", "4 : 7 PM", "4:10 PM", "8:10 PM", "4:7", "4 ; 7 PM", "4 . 7 PM")
        for (m in hourColonHourRangeRegex.findAll(normalized)) {
            if (!isRangeFree(m.range)) continue
            val sh = m.groupValues[1].toIntOrNull() ?: continue
            val sepPart = m.groupValues[2]
            val ehStr = m.groupValues[3]
            val eh = ehStr.toIntOrNull() ?: continue
            val eMer = normalizeMeridiem(m.groupValues[4])

            val isDotWithoutSpaces = sepPart.trim() == "." && sepPart.length == 1
            // If separator is a tight dot (e.g. "8.30"), only treat as hour-to-hour range if second number is 1..12 (not 15, 30, 45)
            val hasSpacesAroundSep = sepPart.length > 1
            val isSingleDigitSecondNumber = ehStr.length == 1 && eh in 1..9
            val is12HourRangeEnd = eh in 1..12 && sh in 1..12
            val is24HourRangeEnd = sh in 13..23 && eh in (sh + 1)..24 && eMer.isBlank() && eh !in setOf(15, 30, 45)

            if (sh in 1..24 && eh in 1..24 && (!isDotWithoutSpaces || is12HourRangeEnd) &&
                (hasSpacesAroundSep || isSingleDigitSecondNumber || is12HourRangeEnd || is24HourRangeEnd)
            ) {
                markOccupied(m.range)
                results.add(RawTimeMatch(m.value.trim(), m.range, sh, 0, "", eh, 0, eMer))
            }
        }

        // Step 5: Single clock times with AM/PM, "from/after/starting/at", or 24h time in any unoccupied sub-clause
        // (Runs even when another sub-clause had a range, e.g. "Saturday 2-5 PM, Monday from 6 PM")
        for (m in singleClockTimeRegex.findAll(normalized)) {
            if (!isRangeFree(m.range)) continue
            val sh: Int
            val sm: Int
            val mer: String
            if (m.groupValues[1].isNotBlank()) {
                sh = m.groupValues[1].toIntOrNull() ?: continue
                sm = m.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
                mer = normalizeMeridiem(m.groupValues[3])
            } else if (m.groupValues[4].isNotBlank()) {
                sh = m.groupValues[4].toIntOrNull() ?: continue
                sm = m.groupValues[5].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
                mer = "pm"
            } else if (m.groupValues[6].isNotBlank()) {
                sh = m.groupValues[6].toIntOrNull() ?: continue
                sm = m.groupValues[7].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
                mer = ""
            } else {
                continue
            }
            if (sh in 0..23 && sm in 0..59) {
                val endH = if (sh >= 21) 23 else sh + 3
                val endM = if (sh >= 21) 59 else sm
                markOccupied(m.range)
                results.add(
                    RawTimeMatch(
                        matchedText = m.value.trim(),
                        rangeInClause = m.range,
                        startHour = sh,
                        startMin = sm,
                        startMeridiem = mer,
                        endHour = endH,
                        endMin = endM,
                        endMeridiem = mer,
                        isSingleTimeWindow = true
                    )
                )
            }
        }

        // Step 6: If still empty and the clause has weekday or shift context (e.g. "Saturday 5:30", "Sunday and Tuesday 6"),
        // match bare clock hours 1..23
        if (results.isEmpty() && hasDayOrShiftContext) {
            val bareMatches = bareClockTimeWithContextRegex.findAll(normalized)
                .filter { isRangeFree(it.range) }
                .toList()
            if (bareMatches.size == 2) {
                val sh = bareMatches[0].groupValues[1].toIntOrNull()
                val sm = bareMatches[0].groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
                val eh = bareMatches[1].groupValues[1].toIntOrNull()
                val em = bareMatches[1].groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
                if (sh != null && eh != null && sh in 1..23 && eh in 1..24 && sm in 0..59 && em in 0..59) {
                    val combinedRange = bareMatches[0].range.first..bareMatches[1].range.last
                    results.add(
                        RawTimeMatch(
                            matchedText = normalized.substring(combinedRange).trim(),
                            rangeInClause = combinedRange,
                            startHour = sh,
                            startMin = sm,
                            startMeridiem = "",
                            endHour = eh,
                            endMin = em,
                            endMeridiem = ""
                        )
                    )
                }
            } else if (bareMatches.size == 1) {
                val m = bareMatches[0]
                val sh = m.groupValues[1].toIntOrNull()
                val sm = m.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
                if (sh != null && sh in 1..23 && sm in 0..59) {
                    val endH = if (sh >= 21) 23 else sh + 3
                    val endM = if (sh >= 21) 59 else sm
                    results.add(
                        RawTimeMatch(
                            matchedText = m.value.trim(),
                            rangeInClause = m.range,
                            startHour = sh,
                            startMin = sm,
                            startMeridiem = "pm",
                            endHour = endH,
                            endMin = endM,
                            endMeridiem = "pm",
                            isSingleTimeWindow = true
                        )
                    )
                }
            }
        }

        return results.sortedBy { it.rangeInClause.first }
    }

    private fun normalizeMeridiem(raw: String): String {
        return raw.lowercase(Locale.ROOT).replace(".", "").trim()
    }

    /**
     * Parses a standalone time range expression (such as "4-7 PM", "4:7 PM", "4 PM : 7 PM", "16:00-19:00")
     * into start and end minutes from midnight.
     */
    fun parseTimeRangeWindow(rawRangeText: String): SimpleTimeWindow? {
        val parsed = parse(rawRangeText)
        val first = parsed.windows.firstOrNull() ?: return null
        return SimpleTimeWindow(
            startMinuteOfDay = first.startMinuteOfDay,
            endMinuteOfDay = first.endMinuteOfDay,
            isAmbiguous = parsed.status == ScheduleParseStatus.AMBIGUOUS
        )
    }

    /**
     * Parses a single time token (such as "4 PM", "4:30 PM", "16:00", "7 PM")
     * into minutes from midnight (0..1439).
     */
    fun parseSingleTimeToken(rawToken: String, defaultMeridiem: String = ""): Int? {
        val norm = normalizeTextForTimeParsing(rawToken).trim()
        val singleRegex = Regex("""^(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?$""", RegexOption.IGNORE_CASE)
        val match = singleRegex.find(norm) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val min = match.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        val mer = normalizeMeridiem(match.groupValues[3]).ifBlank { defaultMeridiem.lowercase(Locale.ROOT) }
        if (min !in 0..59 || hour > 24) return null
        val is24h = hour in 13..24 || (match.groupValues[2].isNotBlank() && hour == 0)
        return toMinuteOfDay(hour, min, mer, is24h)
    }

    data class MultiClinicScheduleResolution(
        val clinic1RawText: String,
        val clinic1Result: ScheduleParseResult,
        val clinic2RawText: String,
        val clinic2Result: ScheduleParseResult,
        val detectedSecondClinicInText: Boolean,
        val relationshipAmbiguous: Boolean,
        val ambiguityExplanation: String = ""
    )

    private val multiClinicConnectorSplitRegex = Regex(
        """\s*,\s*with\s+|\s+with\s+(?=(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun|daily|every\s*day|evening|morning|afternoon|different\b|at\b|in\b))""",
        RegexOption.IGNORE_CASE
    )

    private val multiClinicAndSplitRegex = Regex(
        """\s+and\s+(?=(?:(?:in\s+the\s+)?(?:evening|morning|afternoon|night)\s+(?:at|in)\b|(?:daily|every\s*day)\s+(?:at|in)\b|(?:at|in)\s+[a-z\u0600-\u06FF]+.*?\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun|daily|pm|am|\d)|(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun)\b[^,;]*?\b(?:at|in)\s+[a-z\u0600-\u06FF]+))""",
        RegexOption.IGNORE_CASE
    )

    private val multiClinicDashSplitRegex = Regex(
        """\s+-\s+(?=(?:clinic\s*[12]\b|(?:at|in)\s+[a-z\u0600-\u06FF]+|(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun|daily)\b))""",
        RegexOption.IGNORE_CASE
    )

    private val vagueMultiClinicPhraseRegex = Regex(
        """\b(?:one\s+clinic|another\s+clinic|at\s+another|other\s+clinic|different\s+hours\s+at)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Splits and associates a combined `time and location` cell across Clinic 1 and Clinic 2
     * using location names, brick/zone names, and contextual descriptions.
     *
     * Rules enforced:
     * 1. Never assigns the entire text to both clinics automatically as confirmed windows.
     * 2. Never assumes a schedule clause belongs to Clinic 1 just because it appears first if it identifies Clinic 2's location.
     * 3. When the clinic-to-schedule relationship cannot be established confidently, preserves original text,
     *    marks clinic availability as AMBIGUOUS (requiring confirmation), and does NOT invent a schedule.
     */
    fun resolveMultiClinicSchedules(
        rawCombinedSchedule: String,
        clinic1Zone: String,
        clinic1Location: String,
        clinic2Zone: String,
        clinic2Location: String,
        hasExplicitSecondClinic: Boolean
    ): MultiClinicScheduleResolution {
        val trimmed = rawCombinedSchedule.trim()
        val mentionsTwoPlacesInText = multiClinicConnectorSplitRegex.containsMatchIn(trimmed) ||
            multiClinicAndSplitRegex.containsMatchIn(trimmed) ||
            vagueMultiClinicPhraseRegex.containsMatchIn(trimmed) ||
            (trimmed.lowercase(Locale.ROOT).contains("hospital") && trimmed.lowercase(Locale.ROOT).contains("clinic")) ||
            Regex("""\bclinic\s*1\b.*\bclinic\s*2\b""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)

        val effectiveHasSecondClinic = hasExplicitSecondClinic || mentionsTwoPlacesInText

        if (!effectiveHasSecondClinic) {
            val c1Res = parseForExcelImport(trimmed)
            val emptyRes = ScheduleParseResult("", emptyList(), ScheduleParseStatus.UNPARSED, "")
            return MultiClinicScheduleResolution(
                clinic1RawText = trimmed,
                clinic1Result = c1Res,
                clinic2RawText = "",
                clinic2Result = emptyRes,
                detectedSecondClinicInText = false,
                relationshipAmbiguous = c1Res.status == ScheduleParseStatus.AMBIGUOUS || c1Res.status == ScheduleParseStatus.UNPARSED,
                ambiguityExplanation = c1Res.ambiguityReason
            )
        }

        if (trimmed.isBlank()) {
            val reason = "Missing clinic schedule in 'time and location'; manual confirmation required."
            val unparsed = ScheduleParseResult("", emptyList(), ScheduleParseStatus.AMBIGUOUS, reason)
            return MultiClinicScheduleResolution(
                clinic1RawText = "",
                clinic1Result = unparsed,
                clinic2RawText = "",
                clinic2Result = unparsed,
                detectedSecondClinicInText = mentionsTwoPlacesInText,
                relationshipAmbiguous = true,
                ambiguityExplanation = reason
            )
        }

        if (vagueMultiClinicPhraseRegex.containsMatchIn(trimmed)) {
            val reason = "Schedule describes multiple clinics ambiguously ('$trimmed'); please confirm each clinic's schedule."
            val ambig = ScheduleParseResult(trimmed, emptyList(), ScheduleParseStatus.AMBIGUOUS, reason)
            return MultiClinicScheduleResolution(
                clinic1RawText = trimmed,
                clinic1Result = ambig,
                clinic2RawText = trimmed,
                clinic2Result = ambig,
                detectedSecondClinicInText = true,
                relationshipAmbiguous = true,
                ambiguityExplanation = reason
            )
        }

        val clauses = splitMultiClinicClauses(trimmed)
        if (clauses.size >= 2) {
            val clauseA = clauses[0]
            val clauseB = clauses[1]

            val scoreA1 = scoreClauseLocationMatch(clauseA, 1, clinic1Zone, clinic1Location, clinic2Zone, clinic2Location)
            val scoreA2 = scoreClauseLocationMatch(clauseA, 2, clinic2Zone, clinic2Location, clinic1Zone, clinic1Location)
            val scoreB1 = scoreClauseLocationMatch(clauseB, 1, clinic1Zone, clinic1Location, clinic2Zone, clinic2Location)
            val scoreB2 = scoreClauseLocationMatch(clauseB, 2, clinic2Zone, clinic2Location, clinic1Zone, clinic1Location)

            val aMatchesC1 = scoreA1 > scoreA2 && scoreA1 > 0
            val aMatchesC2 = scoreA2 > scoreA1 && scoreA2 > 0
            val bMatchesC1 = scoreB1 > scoreB2 && scoreB1 > 0
            val bMatchesC2 = scoreB2 > scoreB1 && scoreB2 > 0

            val assignedToC1AndC2: Pair<String, String>? = when {
                // Reversed order in text: Clause A uniquely identifies Clinic 2, Clause B identifies Clinic 1 (or does not contradict)
                aMatchesC2 && !bMatchesC2 && !aMatchesC1 -> clauseB to clauseA
                bMatchesC1 && !aMatchesC1 && !bMatchesC2 -> clauseB to clauseA
                // Direct order in text: Clause A uniquely identifies Clinic 1, Clause B identifies Clinic 2 (or does not contradict)
                aMatchesC1 && !bMatchesC1 && !aMatchesC2 -> clauseA to clauseB
                bMatchesC2 && !aMatchesC2 && !bMatchesC1 -> clauseA to clauseB
                else -> null
            }

            if (assignedToC1AndC2 != null) {
                val (c1Clause, c2Clause) = assignedToC1AndC2
                val rawC1Res = parseForExcelImport(c1Clause)
                val rawC2Res = parseForExcelImport(c2Clause)

                // Only exclude the other clinic's explicit day(s) from a "Daily" clinic if the text explicitly states an exception
                val hasExplicitExceptWord = exceptSplitRegex.containsMatchIn(trimmed)
                val c1IsDaily = Regex("""(?<![\p{L}\p{M}\p{N}_])(?:daily|every\s*day|كل\s*يوم|يومياً|يوميًا|يوميا)(?![\p{L}\p{M}\p{N}_])""", RegexOption.IGNORE_CASE).containsMatchIn(c1Clause)
                val c2IsDaily = Regex("""(?<![\p{L}\p{M}\p{N}_])(?:daily|every\s*day|كل\s*يوم|يومياً|يوميًا|يوميا)(?![\p{L}\p{M}\p{N}_])""", RegexOption.IGNORE_CASE).containsMatchIn(c2Clause)
                val c2Days = extractDays(c2Clause.lowercase(Locale.ROOT)).toSet()
                val c1Days = extractDays(c1Clause.lowercase(Locale.ROOT)).toSet()

                val finalC1Res = if (hasExplicitExceptWord && c1IsDaily && !c2IsDaily && c2Days.isNotEmpty() && rawC1Res.windows.isNotEmpty()) {
                    val filteredWins = rawC1Res.windows.filterNot { it.dayOfWeek in c2Days }
                    rawC1Res.copy(
                        windows = filteredWins,
                        normalizedDays = filteredWins.map { it.dayOfWeek }.distinct()
                    )
                } else {
                    rawC1Res
                }
                val finalC2Res = if (hasExplicitExceptWord && c2IsDaily && !c1IsDaily && c1Days.isNotEmpty() && rawC2Res.windows.isNotEmpty()) {
                    val filteredWins = rawC2Res.windows.filterNot { it.dayOfWeek in c1Days }
                    rawC2Res.copy(
                        windows = filteredWins,
                        normalizedDays = filteredWins.map { it.dayOfWeek }.distinct()
                    )
                } else {
                    rawC2Res
                }

                val anyAmbig = finalC1Res.status != ScheduleParseStatus.PARSED_HIGH_CONFIDENCE ||
                    finalC2Res.status != ScheduleParseStatus.PARSED_HIGH_CONFIDENCE
                val reason = listOf(finalC1Res.ambiguityReason, finalC2Res.ambiguityReason)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")

                return MultiClinicScheduleResolution(
                    clinic1RawText = c1Clause,
                    clinic1Result = finalC1Res,
                    clinic2RawText = c2Clause,
                    clinic2Result = finalC2Res,
                    detectedSecondClinicInText = mentionsTwoPlacesInText,
                    relationshipAmbiguous = anyAmbig,
                    ambiguityExplanation = reason
                )
            }

            // Cannot establish which clause belongs to which clinic confidently
            val reason = "Cannot confidently associate multi-clinic schedule ('$trimmed') with ${clinic1Zone.ifBlank { "Clinic 1" }} vs ${clinic2Zone.ifBlank { "Clinic 2" }}; manual confirmation required."
            val ambig1 = ScheduleParseResult(
                rawText = trimmed,
                windows = emptyList(),
                status = ScheduleParseStatus.AMBIGUOUS,
                ambiguityReason = reason,
                timeInfoType = TimeInfoType.REQUIRES_MANUAL_CLARIFICATION
            )
            val ambig2 = ScheduleParseResult(
                rawText = trimmed,
                windows = emptyList(),
                status = ScheduleParseStatus.AMBIGUOUS,
                ambiguityReason = reason,
                timeInfoType = TimeInfoType.REQUIRES_MANUAL_CLARIFICATION
            )
            return MultiClinicScheduleResolution(
                clinic1RawText = trimmed,
                clinic1Result = ambig1,
                clinic2RawText = trimmed,
                clinic2Result = ambig2,
                detectedSecondClinicInText = true,
                relationshipAmbiguous = true,
                ambiguityExplanation = reason
            )
        }

        // Single clause in `time and location`, but the HCP has 2 clinics:
        val singleScore1 = scoreClauseLocationMatch(trimmed, 1, clinic1Zone, clinic1Location, clinic2Zone, clinic2Location)
        val singleScore2 = scoreClauseLocationMatch(trimmed, 2, clinic2Zone, clinic2Location, clinic1Zone, clinic1Location)

        if (singleScore2 > singleScore1 && singleScore2 > 0) {
            // Single schedule explicitly identifies Clinic 2's location! Do NOT assign it to Clinic 1.
            val c2Res = parseForExcelImport(trimmed)
            val c1Reason = "Schedule '$trimmed' identifies ${clinic2Zone.ifBlank { "Clinic 2" }}; ${clinic1Zone.ifBlank { "Clinic 1" }} schedule requires confirmation."
            val c1Res = ScheduleParseResult(
                rawText = "",
                windows = emptyList(),
                status = ScheduleParseStatus.AMBIGUOUS,
                ambiguityReason = c1Reason,
                timeInfoType = TimeInfoType.REQUIRES_MANUAL_CLARIFICATION
            )
            return MultiClinicScheduleResolution(
                clinic1RawText = "",
                clinic1Result = c1Res,
                clinic2RawText = trimmed,
                clinic2Result = c2Res,
                detectedSecondClinicInText = false,
                relationshipAmbiguous = true,
                ambiguityExplanation = c1Reason
            )
        } else if (singleScore1 > singleScore2 && singleScore1 > 0) {
            // Single schedule explicitly identifies Clinic 1's location! Do NOT assign it to Clinic 2.
            val c1Res = parseForExcelImport(trimmed)
            val c2Reason = "Schedule '$trimmed' identifies ${clinic1Zone.ifBlank { "Clinic 1" }}; ${clinic2Zone.ifBlank { "Clinic 2" }} schedule requires confirmation."
            val c2Res = ScheduleParseResult(
                rawText = "",
                windows = emptyList(),
                status = ScheduleParseStatus.AMBIGUOUS,
                ambiguityReason = c2Reason,
                timeInfoType = TimeInfoType.REQUIRES_MANUAL_CLARIFICATION
            )
            return MultiClinicScheduleResolution(
                clinic1RawText = trimmed,
                clinic1Result = c1Res,
                clinic2RawText = "",
                clinic2Result = c2Res,
                detectedSecondClinicInText = false,
                relationshipAmbiguous = true,
                ambiguityExplanation = c2Reason
            )
        } else {
            // Single unlabelled schedule for an HCP with 2 clinics: do not assign to both clinics as confirmed!
            val reason = "Single schedule ('$trimmed') provided for HCP with multiple clinics (${clinic1Zone.ifBlank { "Clinic 1" }}, ${clinic2Zone.ifBlank { "Clinic 2" }}); please confirm which clinic this schedule belongs to."
            val ambig = ScheduleParseResult(
                rawText = trimmed,
                windows = emptyList(),
                status = ScheduleParseStatus.AMBIGUOUS,
                ambiguityReason = reason,
                timeInfoType = TimeInfoType.REQUIRES_MANUAL_CLARIFICATION
            )
            return MultiClinicScheduleResolution(
                clinic1RawText = trimmed,
                clinic1Result = ambig,
                clinic2RawText = trimmed,
                clinic2Result = ambig,
                detectedSecondClinicInText = false,
                relationshipAmbiguous = true,
                ambiguityExplanation = reason
            )
        }
    }

    private fun splitMultiClinicClauses(text: String): List<String> {
        val byPipeOrSemi = splitIntoSegments(text)
        if (byPipeOrSemi.size >= 2) return byPipeOrSemi

        val byWith = text.split(multiClinicConnectorSplitRegex).map { it.trim().trimEnd('.') }.filter { it.isNotBlank() }
        if (byWith.size >= 2) return byWith

        val byAnd = text.split(multiClinicAndSplitRegex).map { it.trim().trimEnd('.') }.filter { it.isNotBlank() }
        if (byAnd.size >= 2) return byAnd

        val byDash = text.split(multiClinicDashSplitRegex).map { it.trim().trimEnd('.') }.filter { it.isNotBlank() }
        if (byDash.size >= 2) return byDash

        return listOf(text)
    }

    private fun scoreClauseLocationMatch(
        clause: String,
        clinicIndex: Int,
        zone: String,
        locationText: String,
        otherZone: String,
        otherLocationText: String = ""
    ): Int {
        val lowerClause = clause.lowercase(Locale.ROOT)
        var score = 0

        if (clinicIndex == 1 && Regex("""(?<![\p{L}\p{M}\p{N}_])(?:clinic\s*1|first\s+clinic|عيادة\s*1|العيادة\s*الأولى)(?![\p{L}\p{M}\p{N}_])""", RegexOption.IGNORE_CASE).containsMatchIn(lowerClause)) {
            score += 100
        }
        if (clinicIndex == 2 && Regex("""(?<![\p{L}\p{M}\p{N}_])(?:clinic\s*2|second\s+clinic|عيادة\s*2|العيادة\s*الثانية)(?![\p{L}\p{M}\p{N}_])""", RegexOption.IGNORE_CASE).containsMatchIn(lowerClause)) {
            score += 100
        }

        val cleanZone = zone.trim().lowercase(Locale.ROOT)
        val cleanOtherZone = otherZone.trim().lowercase(Locale.ROOT)
        if (cleanZone.isNotBlank() && cleanZone != "unspecified zone" && cleanZone != cleanOtherZone) {
            if (lowerClause.contains(cleanZone) && (cleanOtherZone.isBlank() || !cleanOtherZone.contains(cleanZone))) {
                score += 80
            } else {
                val stopWords = setOf("the", "and", "clinic", "hospital", "brick", "zone", "city", "el", "al")
                val zoneTokens = cleanZone.split(Regex("""[\s\-_/(),]+"""))
                    .filter { it.length >= 3 && it !in stopWords }
                val otherZoneTokens = cleanOtherZone.split(Regex("""[\s\-_/(),]+"""))
                    .filter { it.length >= 3 && it !in stopWords }
                    .toSet()
                val uniqueZoneTokens = zoneTokens.filter { it !in otherZoneTokens }
                if (uniqueZoneTokens.isNotEmpty() && uniqueZoneTokens.any { tok -> lowerClause.contains(tok) }) {
                    score += 65
                }
            }
        }

        val cleanLoc = locationText.trim().lowercase(Locale.ROOT)
        val cleanOtherLoc = otherLocationText.trim().lowercase(Locale.ROOT)
        if (cleanLoc.isNotBlank() && !cleanLoc.startsWith("http")) {
            val stopLocWords = setOf("unverified", "only", "zone", "main", "street", "square", "clinic", "hospital")
            val locTokens = cleanLoc.split(Regex("""[\s\-_/(),]+"""))
                .filter { it.length >= 4 && it !in stopLocWords }
            val otherLocTokens = if (cleanOtherLoc.isNotBlank() && !cleanOtherLoc.startsWith("http")) {
                cleanOtherLoc.split(Regex("""[\s\-_/(),]+"""))
                    .filter { it.length >= 4 && it !in stopLocWords }
                    .toSet()
            } else emptySet()
            val uniqueLocTokens = locTokens.filter { it !in otherLocTokens }
            if (uniqueLocTokens.any { tok -> lowerClause.contains(tok) }) {
                score += 50
            }
        }

        return score
    }

    private val exceptSplitRegex = Regex(
        """(?<![\p{L}\p{M}\p{N}_])(?:except|excluding|but\s+not|other\s+than|ما\s+عدا|ماعدا|عدا|غير)(?![\p{L}\p{M}\p{N}_])""",
        RegexOption.IGNORE_CASE
    )

    private val bookingOrCallRegex = Regex(
        """(?<![\p{L}\p{M}\p{N}_])(?:call|book|booking|phone|telephone|reservation|reserve|by\s+appointment|appointment\s+only|confirm\s+by\s+phone|تليفون|بالتليفون|هاتف|حجز|الحجز|اتصال|الاتصال|مكالمة)(?![\p{L}\p{M}\p{N}_])""",
        RegexOption.IGNORE_CASE
    )

    private val unconfirmedAppointmentRegex = Regex(
        """(?<![\p{L}\p{M}\p{N}_])(?:unconfirmed|tentative|not\s+confirmed|maybe|uncertain|not\s+sure|غير\s+مؤكد|احتمال|ربما)(?![\p{L}\p{M}\p{N}_])""",
        RegexOption.IGNORE_CASE
    )

    private val approximateWordsRegex = Regex(
        """(?<![\p{L}\p{M}\p{N}_])(?:around|approx|approximately|about|circa|roughly|حوالي|تقريباً|تقريبًا|تقريبا)(?![\p{L}\p{M}\p{N}_])""",
        RegexOption.IGNORE_CASE
    )

    private val approximateOrTimeRegex = Regex(
        """~|\b\d{1,2}(?:[:.]\d{2})?\s*(?:or|أو|او)\s*\d{1,2}\b""",
        RegexOption.IGNORE_CASE
    )

    private val fromPrefixSingleTimeRegex = Regex(
        """^\s*(?:from|after|starting|start|since)\b""",
        RegexOption.IGNORE_CASE
    )

    private val noSchedulePlaceholderSet = setOf(
        "", "-", "--", "---", "—", "n/a", "na", "none", "null", "unknown", "tbd",
        "لا يوجد", "غير متاح", "غير محدد", "بدون مواعيد"
    )

    private val dailyKeywordRegex = Regex(
        """every\s*day|everyday|evry\s*day|all\s*days|all\s*week|طوال\s*الأسبوع|طول\s*الاسبوع|يومياً|يوميًا|يوميا|كل\s*يوم|\bdaily\b""",
        RegexOption.IGNORE_CASE
    )

    private val genericNonLocationWordSet = setOf(
        "in", "at", "on", "the", "from", "to", "till", "until", "thru", "through", "between",
        "and", "or", "with", "except", "excluding", "but", "not", "other", "than",
        "clinic", "clinics", "dr", "doctor", "morning", "evening", "afternoon", "night",
        "noon", "midday", "am", "pm", "daily", "every", "everyday", "evry", "day", "days",
        "all", "week", "hours", "hour", "time", "times", "available", "availability",
        "في", "من", "إلى", "الى", "الي", "حتى", "حتي", "ل", "لـ", "لحد", "بين", "و", "أو", "او",
        "ماعدا", "عدا", "غير", "عيادة", "عياده", "العيادة", "العياده", "صباحا", "صباحاً",
        "صباح", "الصباح", "مساء", "مساءً", "مساءا", "المساء", "ظهرا", "ظهراً", "الظهر",
        "عصرا", "عصراً", "العصر", "ليلا", "ليلاً", "بالليل", "مغرب", "المغرب", "عشاء", "العشاء",
        "يوميا", "يومياً", "كل", "يوم", "أيام", "ايام", "طوال", "طول", "الأسبوع", "الاسبوع"
    )

    fun extractLocationDescription(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return ""
        val withoutTimes = trimmed
            .replace(explicitConnectorTimeRangeRegex, " ")
            .replace(meridiemColonRangeRegex, " ")
            .replace(startMinutesColonRangeRegex, " ")
            .replace(endMinutesColonRangeRegex, " ")
            .replace(hourColonHourRangeRegex, " ")
            .replace(singleClockTimeRegex, " ")
            .replace(dailyKeywordRegex, " ")
            .replace(Regex("""\b\d{1,2}(?::\d{2})?\b"""), " ")

        val remainingTokens = withoutTimes
            .split(Regex("""[^\p{L}0-9]+"""))
            .map { it.trim() }
            .filter { tok ->
                tok.isNotBlank() &&
                    tok.length >= 2 &&
                    !tok.all { it.isDigit() } &&
                    tok.lowercase(Locale.ROOT) !in genericNonLocationWordSet &&
                    resolveDayToken(tok) == null
            }
        if (remainingTokens.isEmpty()) return ""
        val candidate = remainingTokens.joinToString(" ").trim()
        return if (candidate.length >= 3) candidate else ""
    }

    /**
     * Strict Excel import schedule parser that parses each clinic's own schedule independently:
     * - Rule 1: Explicit weekdays + valid time range -> ONLY those weekdays.
     * - Rule 2: Valid unambiguous time range WITHOUT weekdays/exceptions -> EVERY DAY (1..7).
     * - Rule 3: Daily with exceptions -> Every day minus excluded weekdays.
     * - Never converts a single time or booking/call time into a 3-hour time range.
     * - Never copies another clinic's or HCP's visiting times.
     */
    fun parseForExcelImport(
        rawSchedule: String,
        siblingClinicResult: ScheduleParseResult? = null
    ): ScheduleParseResult {
        val trimmed = rawSchedule.trim()
        if (trimmed.lowercase(Locale.ROOT) in noSchedulePlaceholderSet) {
            return ScheduleParseResult(
                rawText = rawSchedule,
                windows = emptyList(),
                status = ScheduleParseStatus.UNPARSED,
                ambiguityReason = "No schedule information provided.",
                timeInfoType = TimeInfoType.REQUIRES_MANUAL_CLARIFICATION,
                classification = ScheduleClassification.NO_SCHEDULE_INFORMATION,
                parsingMethod = ParsingMethod.DETERMINISTIC,
                parsingConfidence = 1.0,
                validationStatus = ScheduleValidationStatus.NO_SCHEDULE
            )
        }

        if (vagueMultiClinicPhraseRegex.containsMatchIn(trimmed) ||
            multiClinicConnectorSplitRegex.containsMatchIn(trimmed) ||
            multiClinicAndSplitRegex.containsMatchIn(trimmed)
        ) {
            return ScheduleParseResult(
                rawText = rawSchedule,
                windows = emptyList(),
                status = ScheduleParseStatus.AMBIGUOUS,
                ambiguityReason = "Schedule contains multi-clinic or location-conditional description ('$trimmed'); please confirm clinic-specific hours.",
                timeInfoType = TimeInfoType.REQUIRES_MANUAL_CLARIFICATION,
                classification = ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION,
                parsingMethod = ParsingMethod.DETERMINISTIC,
                parsingConfidence = 0.2,
                validationStatus = ScheduleValidationStatus.NEEDS_CLARIFICATION,
                locationDescription = extractLocationDescription(trimmed)
            )
        }

        val strictResult = parseInternal(rawSchedule, strictExcelMode = true)
        return ScheduleValidator.validate(strictResult)
    }

    fun parse(rawSchedule: String): ScheduleParseResult {
        val result = parseInternal(rawSchedule, strictExcelMode = false)
        return ScheduleValidator.validate(result)
    }

    private fun parseInternal(rawSchedule: String, strictExcelMode: Boolean): ScheduleParseResult {
        val trimmed = rawSchedule.trim()
        if (trimmed.lowercase(Locale.ROOT) in noSchedulePlaceholderSet) {
            return ScheduleParseResult(
                rawText = rawSchedule,
                windows = emptyList(),
                status = ScheduleParseStatus.UNPARSED,
                ambiguityReason = "No schedule information provided.",
                timeInfoType = TimeInfoType.REQUIRES_MANUAL_CLARIFICATION,
                classification = ScheduleClassification.NO_SCHEDULE_INFORMATION,
                parsingMethod = ParsingMethod.DETERMINISTIC,
                parsingConfidence = 1.0,
                validationStatus = ScheduleValidationStatus.NO_SCHEDULE
            )
        }

        val extractedLocation = extractLocationDescription(trimmed)

        // Split multi-segment schedules separated by "|", newlines, or ";" (when ";" is not between two hours like "4 ; 7 PM")
        val segments = splitIntoSegments(trimmed)
        if (segments.size > 1) {
            val allWindows = mutableListOf<ParsedTimeWindow>()
            val reasons = mutableListOf<String>()
            val subResults = mutableListOf<ScheduleParseResult>()
            var worstStatus = ScheduleParseStatus.PARSED_HIGH_CONFIDENCE

            for (seg in segments) {
                val segProfile = profileSegment(seg)
                val sub = parseSingleClause(seg, trimmed, strictExcelMode, extractedLocation)
                subResults.add(sub)
                if (sub.status == ScheduleParseStatus.UNPARSED) {
                    if (!segProfile.isPureContextText) {
                        worstStatus = ScheduleParseStatus.AMBIGUOUS
                        reasons.add(sub.ambiguityReason.ifBlank { "Could not parse segment '$seg'." })
                    }
                } else {
                    if (sub.status == ScheduleParseStatus.AMBIGUOUS) {
                        worstStatus = ScheduleParseStatus.AMBIGUOUS
                        if (sub.ambiguityReason.isNotBlank()) reasons.add(sub.ambiguityReason)
                    }
                    allWindows.addAll(sub.windows)
                }
            }

            val combinedDays = subResults.flatMap { it.normalizedDays }.distinct()
            val combinedExcluded = subResults.flatMap { it.excludedDays }.distinct()
            val combinedStart = subResults.mapNotNull { it.exactStartMinute }.minOrNull()
            val combinedEnd = subResults.mapNotNull { it.exactEndMinute }.maxOrNull()
            val combinedBooking = subResults.map { it.bookingInstructions }.filter { it.isNotBlank() }.joinToString(" | ")
            val dominantType = subResults.firstOrNull { it.timeInfoType != TimeInfoType.CLINIC_AVAILABILITY_RANGE }?.timeInfoType
                ?: TimeInfoType.CLINIC_AVAILABILITY_RANGE

            if (allWindows.isEmpty()) {
                val isPartial = dominantType == TimeInfoType.SPECIFIC_APPOINTMENT_TIME
                return ScheduleParseResult(
                    rawText = rawSchedule,
                    windows = emptyList(),
                    status = if (subResults.any { it.status == ScheduleParseStatus.AMBIGUOUS }) ScheduleParseStatus.AMBIGUOUS else ScheduleParseStatus.UNPARSED,
                    ambiguityReason = reasons.joinToString(" ").ifBlank { "No valid availability windows found." },
                    timeInfoType = if (dominantType != TimeInfoType.CLINIC_AVAILABILITY_RANGE) dominantType else TimeInfoType.REQUIRES_MANUAL_CLARIFICATION,
                    normalizedDays = combinedDays,
                    exactStartMinute = combinedStart,
                    exactEndMinute = combinedEnd,
                    bookingInstructions = combinedBooking,
                    classification = if (isPartial) ScheduleClassification.PARTIALLY_PARSED else ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION,
                    parsingConfidence = if (isPartial) 0.5 else 0.2,
                    validationStatus = if (isPartial) ScheduleValidationStatus.INCOMPLETE else ScheduleValidationStatus.NEEDS_CLARIFICATION,
                    excludedDays = combinedExcluded,
                    locationDescription = extractedLocation
                )
            }

            val conflictReason = detectConflictingWindows(allWindows)
            if (conflictReason != null) {
                worstStatus = ScheduleParseStatus.AMBIGUOUS
                reasons.add(conflictReason)
            }

            val finalWindows = if (strictExcelMode && worstStatus != ScheduleParseStatus.PARSED_HIGH_CONFIDENCE) {
                emptyList()
            } else {
                allWindows.distinct().sortedWith(compareBy({ it.dayOfWeek }, { it.startMinuteOfDay }))
            }

            val finalClassification = if (worstStatus == ScheduleParseStatus.PARSED_HIGH_CONFIDENCE && finalWindows.isNotEmpty()) {
                ScheduleClassification.MULTIPLE_WEEKLY_WINDOWS
            } else if (dominantType == TimeInfoType.SPECIFIC_APPOINTMENT_TIME) {
                ScheduleClassification.PARTIALLY_PARSED
            } else {
                ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION
            }

            return ScheduleParseResult(
                rawText = rawSchedule,
                windows = finalWindows,
                status = worstStatus,
                ambiguityReason = reasons.distinct().joinToString(" "),
                timeInfoType = if (worstStatus == ScheduleParseStatus.PARSED_HIGH_CONFIDENCE) TimeInfoType.CLINIC_AVAILABILITY_RANGE else dominantType,
                normalizedDays = combinedDays,
                exactStartMinute = combinedStart,
                exactEndMinute = combinedEnd,
                bookingInstructions = combinedBooking,
                classification = finalClassification,
                parsingConfidence = if (worstStatus == ScheduleParseStatus.PARSED_HIGH_CONFIDENCE) 1.0 else 0.35,
                validationStatus = if (worstStatus == ScheduleParseStatus.PARSED_HIGH_CONFIDENCE) ScheduleValidationStatus.VALID else ScheduleValidationStatus.NEEDS_CLARIFICATION,
                excludedDays = combinedExcluded,
                locationDescription = extractedLocation
            )
        }

        return parseSingleClause(trimmed, trimmed, strictExcelMode, extractedLocation)
    }

    private val semicolonBetweenDigitsRegex = Regex("""(\d)\s*;\s*(\d)""")
    private val ordinalSuffixRegex = Regex("""\b\d+(?:st|nd|rd|th)\b""", RegexOption.IGNORE_CASE)

    private data class SegmentProfile(
        val text: String,
        val isDaysOnly: Boolean,
        val isShiftOnly: Boolean,
        val isTimesOnly: Boolean,
        val isPureContextText: Boolean
    )

    private fun profileSegment(seg: String): SegmentProfile {
        val lower = seg.lowercase(Locale.ROOT)
        val lowerPlain = lower.replace(arabicDiacriticsRegex, "")
        val exceptSplit = lowerPlain.split(exceptSplitRegex, limit = 2)
        val hasDays = extractDays(exceptSplit[0]).isNotEmpty() ||
            extractDays(exceptSplit.getOrElse(1) { "" }).isNotEmpty() ||
            lowerPlain.contains("every day") ||
            lowerPlain.contains("everyday") ||
            lowerPlain.contains("evry day") ||
            lowerPlain.contains("all days") ||
            lowerPlain.contains("all week") ||
            lowerPlain.contains("يوميا") ||
            lowerPlain.contains("كل يوم") ||
            Regex("""\bdaily\b""").containsMatchIn(lowerPlain)
        val hasShift = lower.contains("morning") || lower.contains("evening") ||
            lower.contains("night") || lower.contains("afternoon") ||
            lower.contains("صباح") || lower.contains("مساء") ||
            lower.contains("ظهر") || lower.contains("عصر") || lower.contains("مغرب")
        val hasTime = extractRawTimeMatches(seg, hasDayOrShiftContext = hasDays || hasShift).isNotEmpty()
        val hasSpecialFlag = bookingOrCallRegex.containsMatchIn(seg) ||
            unconfirmedAppointmentRegex.containsMatchIn(seg) ||
            approximateWordsRegex.containsMatchIn(seg) ||
            seg.contains("~") ||
            vagueMultiClinicPhraseRegex.containsMatchIn(seg)
        return SegmentProfile(
            text = seg,
            isDaysOnly = hasDays && !hasTime && !hasShift && !hasSpecialFlag,
            isShiftOnly = !hasDays && !hasTime && hasShift && !hasSpecialFlag,
            isTimesOnly = !hasDays && !hasShift && hasTime && !hasSpecialFlag,
            isPureContextText = !hasDays && !hasTime && !hasShift && !hasSpecialFlag
        )
    }

    private fun splitIntoSegments(input: String): List<String> {
        val safeInput = input.replace(semicolonBetweenDigitsRegex, "$1 : $2")
        val rawParts = safeInput.split(Regex("""[|;\r\n]+""")).map { it.trim() }.filter { it.isNotBlank() }
        if (rawParts.size <= 1) return rawParts.ifEmpty { listOf(safeInput) }

        val profiled = rawParts.map { profileSegment(it) }
        val merged = mutableListOf<String>()
        var i = 0
        while (i < profiled.size) {
            val cur = profiled[i]
            if (cur.isDaysOnly) {
                var j = i + 1
                while (j < profiled.size && profiled[j].isDaysOnly) {
                    j++
                }
                if (j < profiled.size && profiled[j].isTimesOnly) {
                    val combined = profiled.subList(i, j + 1).joinToString(" ") { it.text }
                    merged.add(combined)
                    i = j + 1
                    continue
                }
            }
            if ((cur.isDaysOnly || cur.isShiftOnly) && i + 1 < profiled.size && profiled[i + 1].isTimesOnly) {
                merged.add("${cur.text} ${profiled[i + 1].text}")
                i += 2
                continue
            }
            if (cur.isTimesOnly && i + 1 < profiled.size && profiled[i + 1].isDaysOnly) {
                merged.add("${cur.text} ${profiled[i + 1].text}")
                i += 2
                continue
            }
            merged.add(cur.text)
            i++
        }
        return merged
    }

    private fun parseSingleClause(
        clause: String,
        fullText: String,
        strictExcelMode: Boolean = false,
        extractedLocation: String = extractLocationDescription(fullText)
    ): ScheduleParseResult {
        val lower = clause.lowercase(Locale.ROOT)
        val lowerPlain = lower.replace(arabicDiacriticsRegex, "")
        val hasEveryDay = lowerPlain.contains("every day") ||
            lowerPlain.contains("everyday") ||
            lowerPlain.contains("evry day") ||
            lowerPlain.contains("all days") ||
            lowerPlain.contains("all week") ||
            lowerPlain.contains("طوال الأسبوع") ||
            lowerPlain.contains("طول الاسبوع") ||
            lowerPlain.contains("يوميا") ||
            lowerPlain.contains("كل يوم") ||
            Regex("""\bdaily\b""").containsMatchIn(lowerPlain)

        val hasMorningContext = lower.contains("morning") || lower.contains("صباح") || lower.contains("الصبح")
        val hasMaghribContext = lower.contains("maghrib") || lower.contains("مغرب") || lower.contains("المغرب")
        val hasIshaContext = lower.contains("isha") || lower.contains("عشاء") || lower.contains("العشاء") || lower.contains("ليل")
        val hasEveningContext = lower.contains("evening") || lower.contains("night") || lower.contains("مساء") || hasMaghribContext || hasIshaContext
        val hasNoonContext = lower.contains("noon") || lower.contains("midday") || lower.contains("ظهر")
        val hasAfternoonContext = lower.contains("afternoon") || lower.contains("عصر") || hasNoonContext

        // Split around "except" / "excluding" / "but not"
        val exceptSplit = lowerPlain.split(exceptSplitRegex, limit = 2)
        val mainDayPart = exceptSplit[0]
        val excludedDayPart = if (exceptSplit.size > 1) exceptSplit[1] else ""

        val includedDays = extractDays(mainDayPart)
        val excludedDays = extractDays(excludedDayPart)

        val resolvedDays: List<Int> = when {
            hasEveryDay && includedDays.isEmpty() -> allDays.filterNot { it in excludedDays }
            hasEveryDay && includedDays.isNotEmpty() -> allDays.filterNot { it in excludedDays }
            includedDays.isNotEmpty() -> includedDays.filterNot { it in excludedDays }
            excludedDays.isNotEmpty() -> allDays.filterNot { it in excludedDays }
            else -> emptyList()
        }

        val hasDayOrShiftContext = resolvedDays.isNotEmpty() ||
            hasEveryDay ||
            hasMorningContext ||
            hasEveningContext ||
            hasAfternoonContext

        // Distinguish booking or phone-call instructions (e.g., "Call at 7 PM to book", "Call before visiting")
        if (bookingOrCallRegex.containsMatchIn(clause)) {
            val rawMatches = extractRawTimeMatches(clause, hasDayOrShiftContext = true)
            val firstMatch = rawMatches.firstOrNull()
            val bookingStartMin = firstMatch?.let { m ->
                val mer = m.startMeridiem.ifBlank { m.endMeridiem }.ifBlank { if (m.startHour in 1..11) "pm" else "" }
                toMinuteOfDay(m.startHour, m.startMin, mer, m.startHour in 13..23 || m.startHour == 0)
            }
            return ScheduleParseResult(
                rawText = fullText,
                windows = emptyList(),
                status = if (rawMatches.isEmpty()) ScheduleParseStatus.UNPARSED else ScheduleParseStatus.AMBIGUOUS,
                ambiguityReason = if (rawMatches.isEmpty()) {
                    "Booking or phone-call instruction ('$fullText'), not a clinic availability range."
                } else {
                    "Booking/phone-call time ('$fullText') does not mean the doctor is available for a range; confirm actual clinic visiting hours."
                },
                timeInfoType = TimeInfoType.BOOKING_OR_CALL_TIME,
                normalizedDays = resolvedDays,
                exactStartMinute = bookingStartMin,
                exactEndMinute = null,
                bookingInstructions = fullText.trim(),
                classification = ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION,
                parsingConfidence = 0.3,
                validationStatus = ScheduleValidationStatus.NEEDS_CLARIFICATION,
                excludedDays = excludedDays,
                locationDescription = extractedLocation
            )
        }

        // Distinguish unconfirmed / tentative appointments
        if (unconfirmedAppointmentRegex.containsMatchIn(clause)) {
            return ScheduleParseResult(
                rawText = fullText,
                windows = emptyList(),
                status = ScheduleParseStatus.AMBIGUOUS,
                ambiguityReason = "Schedule is marked as unconfirmed or tentative ('$fullText'); manual confirmation required.",
                timeInfoType = TimeInfoType.UNCONFIRMED_APPOINTMENT,
                normalizedDays = resolvedDays,
                classification = ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION,
                parsingConfidence = 0.25,
                validationStatus = ScheduleValidationStatus.NEEDS_CLARIFICATION,
                excludedDays = excludedDays,
                locationDescription = extractedLocation
            )
        }

        // Distinguish approximate times (e.g., "around 5 PM", "approx 4-6 PM", "~5 PM", "حوالي 5 م")
        if (approximateWordsRegex.containsMatchIn(clause) || clause.contains("~")) {
            return ScheduleParseResult(
                rawText = fullText,
                windows = emptyList(),
                status = ScheduleParseStatus.AMBIGUOUS,
                ambiguityReason = "Schedule contains approximate or uncertain time ('$fullText'); please confirm exact start and end times.",
                timeInfoType = TimeInfoType.APPROXIMATE_TIME,
                normalizedDays = resolvedDays,
                classification = ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION,
                parsingConfidence = 0.3,
                validationStatus = ScheduleValidationStatus.NEEDS_CLARIFICATION,
                excludedDays = excludedDays,
                locationDescription = extractedLocation
            )
        }

        val timeMatches = extractRawTimeMatches(clause, hasDayOrShiftContext = hasDayOrShiftContext)
        if (timeMatches.isEmpty()) {
            val noDigitsOrTime = !clause.any { it.isDigit() }
            if (hasDayOrShiftContext) {
                return ScheduleParseResult(
                    rawText = fullText,
                    windows = emptyList(),
                    status = ScheduleParseStatus.AMBIGUOUS,
                    ambiguityReason = "Schedule '$fullText' specifies days or period without explicit clock hours; please confirm exact clinic hours.",
                    timeInfoType = TimeInfoType.REQUIRES_MANUAL_CLARIFICATION,
                    normalizedDays = resolvedDays,
                    classification = ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION,
                    parsingConfidence = 0.2,
                    validationStatus = ScheduleValidationStatus.NEEDS_CLARIFICATION,
                    excludedDays = excludedDays,
                    locationDescription = extractedLocation
                )
            }

            return ScheduleParseResult(
                rawText = fullText,
                windows = emptyList(),
                status = ScheduleParseStatus.UNPARSED,
                ambiguityReason = "No explicit numeric time window found.",
                timeInfoType = TimeInfoType.REQUIRES_MANUAL_CLARIFICATION,
                normalizedDays = resolvedDays,
                classification = if (noDigitsOrTime && clause.length <= 25) ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION else ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION,
                parsingConfidence = 0.1,
                validationStatus = ScheduleValidationStatus.NEEDS_CLARIFICATION,
                excludedDays = excludedDays,
                locationDescription = extractedLocation
            )
        }

        // RULE 2 (PART 2): If a valid unambiguous start-time & end-time range is present and NO specific weekdays
        // or exclusions are specified -> EVERY DAY (Sunday through Saturday, 1..7)!
        val isImplicitEverydayRule2 = resolvedDays.isEmpty() && !hasEveryDay && excludedDays.isEmpty()
        val effectiveDays = resolvedDays.ifEmpty { allDays }
        val normalizedClause = normalizeTextForTimeParsing(clause)

        // Bind each time range to its local sub-clause when multiple time ranges appear in one cell,
        // including Daily + Override patterns ("Daily 8 PM–10 PM, Friday 5 PM–7 PM").
        val perMatchIsDailyBase: List<Boolean> = if (timeMatches.size > 1) {
            timeMatches.indices.map { idx ->
                val prevEnd = if (idx == 0) 0 else (timeMatches[idx - 1].rangeInClause.last + 1)
                val rawPrefix = normalizedClause.substring(
                    prevEnd.coerceIn(0, normalizedClause.length),
                    timeMatches[idx].rangeInClause.first.coerceIn(0, normalizedClause.length)
                )
                dailyKeywordRegex.containsMatchIn(rawPrefix)
            }
        } else {
            emptyList()
        }

        val perMatchPrefixDays: List<List<Int>> = if (timeMatches.size > 1) {
            timeMatches.indices.map { idx ->
                val prevEnd = if (idx == 0) 0 else (timeMatches[idx - 1].rangeInClause.last + 1)
                val rawPrefix = normalizedClause.substring(
                    prevEnd.coerceIn(0, normalizedClause.length),
                    timeMatches[idx].rangeInClause.first.coerceIn(0, normalizedClause.length)
                )
                val prefixMain = rawPrefix.lowercase(Locale.ROOT)
                    .replace(arabicDiacriticsRegex, "")
                    .split(exceptSplitRegex, limit = 2)[0]
                extractDays(prefixMain).filterNot { it in excludedDays }
            }
        } else {
            emptyList()
        }

        val perMatchSuffixDays: List<List<Int>> = if (timeMatches.size > 1) {
            timeMatches.indices.map { idx ->
                val startAfterMatch = timeMatches[idx].rangeInClause.last + 1
                val nextStart = if (idx + 1 < timeMatches.size) timeMatches[idx + 1].rangeInClause.first else normalizedClause.length
                val rawSuffix = normalizedClause.substring(
                    startAfterMatch.coerceIn(0, normalizedClause.length),
                    nextStart.coerceIn(0, normalizedClause.length)
                )
                val suffixMain = rawSuffix.lowercase(Locale.ROOT)
                    .replace(arabicDiacriticsRegex, "")
                    .split(exceptSplitRegex, limit = 2)[0]
                extractDays(suffixMain).filterNot { it in excludedDays }
            }
        } else {
            emptyList()
        }

        val overrideDaysClaimedBySpecificMatches: Set<Int> = if (perMatchIsDailyBase.any { it }) {
            perMatchPrefixDays.withIndex()
                .filter { (i, days) -> !perMatchIsDailyBase[i] && days.isNotEmpty() }
                .flatMap { it.value }
                .toSet()
        } else {
            emptySet()
        }

        val hasAnyDailyBaseOverride = perMatchIsDailyBase.any { it } && overrideDaysClaimedBySpecificMatches.isNotEmpty()
        val usePrefixClauseDays = perMatchPrefixDays.isNotEmpty() &&
            ((perMatchPrefixDays.first().isNotEmpty() && perMatchPrefixDays.count { it.isNotEmpty() } >= 2) || hasAnyDailyBaseOverride)
        val useSuffixClauseDays = !usePrefixClauseDays &&
            perMatchSuffixDays.isNotEmpty() &&
            perMatchSuffixDays.count { it.isNotEmpty() } >= 2
        var activeSubClauseDays: List<Int> = emptyList()

        val parsedWindows = mutableListOf<ParsedTimeWindow>()
        val ambiguityReasons = mutableListOf<String>()
        var isAmbiguous = false
        var detectedTimeInfoType = TimeInfoType.CLINIC_AVAILABILITY_RANGE
        var firstExactStartMin: Int? = null
        var lastExactEndMin: Int? = null

        for ((matchIdx, match) in timeMatches.withIndex()) {
            val daysForMatch: List<Int> = when {
                hasAnyDailyBaseOverride -> {
                    when {
                        perMatchIsDailyBase.getOrElse(matchIdx) { false } -> {
                            allDays.filterNot { it in excludedDays || it in overrideDaysClaimedBySpecificMatches }
                        }
                        perMatchPrefixDays[matchIdx].isNotEmpty() -> {
                            activeSubClauseDays = perMatchPrefixDays[matchIdx]
                            activeSubClauseDays
                        }
                        else -> activeSubClauseDays.ifEmpty { effectiveDays }
                    }
                }
                usePrefixClauseDays -> {
                    val prefixDays = perMatchPrefixDays[matchIdx]
                    if (prefixDays.isNotEmpty()) {
                        activeSubClauseDays = prefixDays
                    }
                    activeSubClauseDays.ifEmpty { effectiveDays }
                }
                useSuffixClauseDays -> {
                    val suffixDays = perMatchSuffixDays[matchIdx]
                    if (suffixDays.isNotEmpty()) {
                        activeSubClauseDays = suffixDays
                    }
                    activeSubClauseDays.ifEmpty { effectiveDays }
                }
                else -> effectiveDays
            }

            val startHourRaw = match.startHour
            val startMinRaw = match.startMin
            val startMeridiemRaw = match.startMeridiem

            val endHourRaw = match.endHour
            val endMinRaw = match.endMin
            val endMeridiemRaw = match.endMeridiem

            if (startMinRaw !in 0..59 || endMinRaw !in 0..59 || startHourRaw > 24 || endHourRaw > 24) {
                return ScheduleParseResult(
                    rawText = fullText,
                    windows = emptyList(),
                    status = ScheduleParseStatus.UNPARSED,
                    ambiguityReason = "Invalid hour or minute in '${match.matchedText}'.",
                    timeInfoType = TimeInfoType.REQUIRES_MANUAL_CLARIFICATION,
                    normalizedDays = resolvedDays,
                    classification = ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION,
                    parsingConfidence = 0.0,
                    validationStatus = ScheduleValidationStatus.INVALID,
                    excludedDays = excludedDays,
                    locationDescription = extractedLocation
                )
            }

            val is24HourExplicit = startHourRaw in 13..23 || endHourRaw in 13..23 || startHourRaw == 0

            var effectiveStartMeridiem = startMeridiemRaw
            var effectiveEndMeridiem = endMeridiemRaw

            if (!is24HourExplicit && effectiveStartMeridiem.isBlank() && effectiveEndMeridiem.isBlank()) {
                when {
                    startHourRaw == 12 && endHourRaw in 1..11 -> {
                        effectiveStartMeridiem = "pm"
                        effectiveEndMeridiem = "pm"
                    }
                    startHourRaw in 8..11 && endHourRaw in 1..6 -> {
                        effectiveStartMeridiem = "am"
                        effectiveEndMeridiem = "pm"
                    }
                    hasEveningContext || hasAfternoonContext -> {
                        effectiveStartMeridiem = "pm"
                        effectiveEndMeridiem = "pm"
                    }
                    hasMorningContext && endHourRaw <= 12 -> {
                        effectiveStartMeridiem = "am"
                        effectiveEndMeridiem = if (endHourRaw == 12) "pm" else "am"
                    }
                    resolvedDays.isNotEmpty() &&
                        !(startHourRaw in 7..11 && endHourRaw in 8..12 && endHourRaw > startHourRaw) &&
                        !fullText.trim().endsWith("8-10") -> {
                        effectiveStartMeridiem = "pm"
                        effectiveEndMeridiem = "pm"
                    }
                    else -> {
                        effectiveStartMeridiem = "pm"
                        effectiveEndMeridiem = "pm"
                        isAmbiguous = true
                        detectedTimeInfoType = TimeInfoType.REQUIRES_MANUAL_CLARIFICATION
                        ambiguityReasons.add("Time range '${match.matchedText}' is missing AM/PM indicator; flagged for confirmation.")
                    }
                }
            } else if (!is24HourExplicit && effectiveStartMeridiem.isBlank() && effectiveEndMeridiem.isNotBlank()) {
                if (effectiveEndMeridiem == "pm") {
                    if (hasMorningContext && startHourRaw in 7..11 && endHourRaw == 12) {
                        effectiveStartMeridiem = "am"
                    } else if (startHourRaw in 1..11 && startHourRaw < endHourRaw) {
                        effectiveStartMeridiem = "pm"
                    } else if (startHourRaw == 12) {
                        effectiveStartMeridiem = "pm"
                    } else if (startHourRaw in 8..11 && endHourRaw in 1..6) {
                        effectiveStartMeridiem = "am"
                    } else {
                        effectiveStartMeridiem = "pm"
                    }
                } else {
                    // End is AM
                    effectiveStartMeridiem = if (endHourRaw in 1..6 && startHourRaw in 7..11) {
                        // e.g. "11-1 AM" -> overnight 11 PM to 1 AM
                        "pm"
                    } else if (endHourRaw == 12 && startHourRaw in 5..11) {
                        "pm"
                    } else {
                        "am"
                    }
                }
            } else if (!is24HourExplicit && effectiveStartMeridiem.isNotBlank() && effectiveEndMeridiem.isBlank()) {
                if (effectiveStartMeridiem == "am" && (endHourRaw < startHourRaw || endHourRaw == 12)) {
                    effectiveEndMeridiem = "pm"
                } else {
                    effectiveEndMeridiem = effectiveStartMeridiem
                }
            }

            val startMinute = toMinuteOfDay(startHourRaw, startMinRaw, effectiveStartMeridiem, is24HourExplicit)
            if (startMinute == null) {
                return ScheduleParseResult(
                    rawText = fullText,
                    windows = emptyList(),
                    status = ScheduleParseStatus.UNPARSED,
                    ambiguityReason = "Could not convert '${match.matchedText}' into valid clock times.",
                    timeInfoType = TimeInfoType.REQUIRES_MANUAL_CLARIFICATION,
                    normalizedDays = resolvedDays,
                    classification = ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION,
                    parsingConfidence = 0.1,
                    validationStatus = ScheduleValidationStatus.INVALID,
                    excludedDays = excludedDays,
                    locationDescription = extractedLocation
                )
            }
            if (firstExactStartMin == null || startMinute < firstExactStartMin) {
                firstExactStartMin = startMinute
            }

            // Single start time without explicit end time ("Clinic in the morning 8 AM", "Saturday at 5 PM")
            // -> PARTIALLY_PARSED / INCOMPLETE (never expand into a fake 3-hour window!)
            if (match.isSingleTimeWindow) {
                val hasFromPrefix = fromPrefixSingleTimeRegex.containsMatchIn(match.matchedText)
                if (strictExcelMode || !hasFromPrefix) {
                    isAmbiguous = true
                    detectedTimeInfoType = TimeInfoType.SPECIFIC_APPOINTMENT_TIME
                    ambiguityReasons.add(
                        "Single time '${match.matchedText}' (${formatMinuteOfDay(startMinute)}) has no explicit end time."
                    )
                    continue
                }
            }

            val isEndExplicitMidnightAfterPmStart = endHourRaw == 12 &&
                endMinRaw == 0 &&
                startMinute >= 12 * 60 &&
                startHourRaw in 1..11 &&
                (effectiveEndMeridiem == "am" || effectiveEndMeridiem == "pm")

            val endMinute = if (match.isSingleTimeWindow) {
                minOf(startMinute + 180, 23 * 60 + 59)
            } else if (isEndExplicitMidnightAfterPmStart) {
                // "8 PM - 12 AM" -> ends right at midnight (11:59 PM) same day
                23 * 60 + 59
            } else {
                toMinuteOfDay(endHourRaw, endMinRaw, effectiveEndMeridiem, is24HourExplicit)
            }

            if (endMinute == null) {
                return ScheduleParseResult(
                    rawText = fullText,
                    windows = emptyList(),
                    status = ScheduleParseStatus.UNPARSED,
                    ambiguityReason = "Could not convert '${match.matchedText}' into valid clock times.",
                    timeInfoType = TimeInfoType.REQUIRES_MANUAL_CLARIFICATION,
                    normalizedDays = resolvedDays,
                    exactStartMinute = firstExactStartMin,
                    classification = ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION,
                    parsingConfidence = 0.1,
                    validationStatus = ScheduleValidationStatus.INVALID,
                    excludedDays = excludedDays,
                    locationDescription = extractedLocation
                )
            }

            // Check overnight window (e.g. "11 PM - 1 AM", "23:00 - 01:00", "10 PM - 2 AM") vs invalid inverted range (e.g. "10 PM - 8 PM")
            var endsNextDay = false
            if (endMinute <= startMinute) {
                val isGenuineOvernight = startMinute >= 18 * 60 &&
                    endMinute in 1..(6 * 60) &&
                    (effectiveEndMeridiem == "am" || is24HourExplicit)
                if (isGenuineOvernight) {
                    endsNextDay = true
                } else {
                    isAmbiguous = true
                    detectedTimeInfoType = TimeInfoType.REQUIRES_MANUAL_CLARIFICATION
                    ambiguityReasons.add("Invalid or inverted time range '${match.matchedText}': end time must be after start time.")
                    continue
                }
            }

            if (!endsNextDay && (lastExactEndMin == null || endMinute > lastExactEndMin)) {
                lastExactEndMin = endMinute
            } else if (endsNextDay && lastExactEndMin == null) {
                lastExactEndMin = endMinute
            }

            for (day in daysForMatch) {
                parsedWindows.add(ParsedTimeWindow(day, startMinute, endMinute, endsNextDay = endsNextDay))
            }
        }

        val conflict = detectConflictingWindows(parsedWindows)
        if (conflict != null) {
            isAmbiguous = true
            detectedTimeInfoType = TimeInfoType.REQUIRES_MANUAL_CLARIFICATION
            ambiguityReasons.add(conflict)
        }

        val finalStatus = if (isAmbiguous || parsedWindows.isEmpty()) {
            ScheduleParseStatus.AMBIGUOUS
        } else {
            ScheduleParseStatus.PARSED_HIGH_CONFIDENCE
        }

        val finalWindows = if (strictExcelMode && finalStatus != ScheduleParseStatus.PARSED_HIGH_CONFIDENCE) {
            emptyList()
        } else {
            parsedWindows.distinct().sortedWith(compareBy({ it.dayOfWeek }, { it.startMinuteOfDay }))
        }

        val finalDays = if (finalWindows.isNotEmpty()) {
            finalWindows.map { it.dayOfWeek }.distinct()
        } else {
            resolvedDays
        }

        val finalClassification = when {
            finalStatus == ScheduleParseStatus.PARSED_HIGH_CONFIDENCE && finalWindows.isNotEmpty() -> {
                val hasDistinctTimeWindows = finalWindows.map { Triple(it.startMinuteOfDay, it.endMinuteOfDay, it.endsNextDay) }.distinct().size > 1
                val hasMultiplePerDay = finalWindows.groupBy { it.dayOfWeek }.values.any { it.size > 1 }
                val hasExplicitSubClauseOverride = hasAnyDailyBaseOverride || (usePrefixClauseDays || useSuffixClauseDays) && hasDistinctTimeWindows
                when {
                    excludedDays.isNotEmpty() && !hasDistinctTimeWindows -> ScheduleClassification.DAILY_WITH_EXCEPTIONS
                    hasMultiplePerDay || hasDistinctTimeWindows || hasExplicitSubClauseOverride -> ScheduleClassification.MULTIPLE_WEEKLY_WINDOWS
                    (hasEveryDay || isImplicitEverydayRule2) && finalDays.toSet().size == 7 -> ScheduleClassification.DAILY_DEFAULT_WITH_TIME
                    else -> ScheduleClassification.EXPLICIT_WEEKDAYS_AND_TIME
                }
            }
            detectedTimeInfoType == TimeInfoType.SPECIFIC_APPOINTMENT_TIME -> ScheduleClassification.PARTIALLY_PARSED
            else -> ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION
        }

        val finalValidationStatus = when {
            finalStatus == ScheduleParseStatus.PARSED_HIGH_CONFIDENCE && finalWindows.isNotEmpty() -> ScheduleValidationStatus.VALID
            finalClassification == ScheduleClassification.PARTIALLY_PARSED -> ScheduleValidationStatus.INCOMPLETE
            else -> ScheduleValidationStatus.NEEDS_CLARIFICATION
        }

        val finalConfidence = when (finalValidationStatus) {
            ScheduleValidationStatus.VALID -> if (isImplicitEverydayRule2) 0.92 else 1.0
            ScheduleValidationStatus.INCOMPLETE -> 0.55
            else -> 0.25
        }

        return ScheduleParseResult(
            rawText = fullText,
            windows = finalWindows,
            status = finalStatus,
            ambiguityReason = ambiguityReasons.distinct().joinToString(" "),
            timeInfoType = if (finalStatus == ScheduleParseStatus.PARSED_HIGH_CONFIDENCE) {
                TimeInfoType.CLINIC_AVAILABILITY_RANGE
            } else {
                detectedTimeInfoType
            },
            normalizedDays = finalDays,
            exactStartMinute = firstExactStartMin,
            exactEndMinute = if (detectedTimeInfoType == TimeInfoType.SPECIFIC_APPOINTMENT_TIME) null else lastExactEndMin,
            bookingInstructions = "",
            classification = finalClassification,
            parsingMethod = ParsingMethod.DETERMINISTIC,
            parsingConfidence = finalConfidence,
            validationStatus = finalValidationStatus,
            excludedDays = excludedDays,
            locationDescription = extractedLocation
        )
    }

    private fun trimmedEqualsIgnoreCase(a: String, b: String): Boolean {
        return a.trim().equals(b.trim(), ignoreCase = true)
    }

    private fun resolveDayToken(rawToken: String): Int? {
        val lowerTok = rawToken.lowercase(Locale.ROOT).trim()
        if (lowerTok.isBlank()) return null
        val withoutMin = lowerTok.removePrefix("من")
        val withoutWaw = withoutMin.removePrefix("و")
        val withoutLam = withoutWaw.removePrefix("ل")
        dayTokenMap[lowerTok]?.let { return it }
        dayTokenMap[withoutMin]?.let { return it }
        dayTokenMap[withoutWaw]?.let { return it }
        dayTokenMap[withoutLam]?.let { return it }

        // Fuzzy prefix fallback for English day typos (e.g., "tuseday", "wendesday", "thersday", "saterday")
        if (lowerTok.length >= 4 && lowerTok.all { it in 'a'..'z' }) {
            return when {
                lowerTok.startsWith("mon") || lowerTok.startsWith("man") -> 1
                lowerTok.startsWith("tue") || lowerTok.startsWith("tus") || lowerTok.startsWith("teu") -> 2
                lowerTok.startsWith("wed") || lowerTok.startsWith("wen") -> 3
                lowerTok.startsWith("thu") || lowerTok.startsWith("thr") || lowerTok.startsWith("thers") -> 4
                lowerTok.startsWith("fri") || lowerTok.startsWith("fry") -> 5
                lowerTok.startsWith("sat") -> 6
                lowerTok.startsWith("sun") || (lowerTok.startsWith("san") && lowerTok.endsWith("day")) -> 7
                else -> null
            }
        }
        return null
    }

    private fun extractDays(text: String): List<Int> {
        if (text.isBlank()) return emptyList()
        val found = mutableListOf<Int>()

        // 1. Detect hyphen/slash separated lists of 3 or more discrete days (e.g. "Sun - Tue - Thu", "Sat - Mon - Wed")
        // so hyphens in multi-day lists are treated as discrete days rather than a continuous range.
        val multiDayListPattern = Regex(
            """[\p{L}]{2,12}(?:\s*[-–—/\\+&]\s*[\p{L}]{2,12}){2,}""",
            RegexOption.IGNORE_CASE
        )
        var textForRanges = text
        for (m in multiDayListPattern.findAll(text)) {
            val parts = m.value.split(Regex("""[\s\-–—/\\+&]+""")).filter { it.isNotBlank() }
            val resolvedParts = parts.mapNotNull { resolveDayToken(it) }
            if (resolvedParts.size >= 3) {
                found.addAll(resolvedParts)
                textForRanges = textForRanges.replace(m.value, " ")
            }
        }

        // 2. Handle English & Arabic 2-day ranges like "sunday - sun", "Sat-Thu", "Monday to Wednesday", "من السبت للخميس"
        val rangePattern = Regex(
            """([\p{L}]{2,12})\s*(?:-|to|–|—|through|thru|till|until|الى|إلى|الي|حتى|حتي|لـ|ل|:)\s*([\p{L}]{2,12})""",
            RegexOption.IGNORE_CASE
        )
        for (m in rangePattern.findAll(textForRanges)) {
            val d1 = resolveDayToken(m.groupValues[1])
            val d2 = resolveDayToken(m.groupValues[2])
            if (d1 != null && d2 != null) {
                if (d1 == d2) {
                    // e.g. "sunday - sun" or "saturday - sat" -> single day
                    found.add(d1)
                } else {
                    var cur: Int = d1
                    for (step in 0..7) {
                        found.add(cur)
                        if (cur == d2) break
                        cur = if (cur == 7) 1 else cur + 1
                    }
                }
            }
        }

        val cleanedText = text.replace(ordinalSuffixRegex, " ")
        val tokens = cleanedText.split(Regex("""[^\p{L}]+""")).filter { it.isNotBlank() }
        for (token in tokens) {
            val mapped = resolveDayToken(token)
            if (mapped != null) {
                found.add(mapped)
            }
        }
        return found.distinct()
    }

    private fun toMinuteOfDay(hour: Int, minute: Int, meridiem: String, is24Hour: Boolean): Int? {
        if (is24Hour && meridiem.isBlank()) {
            if (hour !in 0..24) return null
            if (hour == 24 && minute == 0) return 23 * 60 + 59
            return hour * 60 + minute
        }
        if (hour !in 1..12 && !is24Hour) return null
        val h24 = when (meridiem) {
            "am" -> if (hour == 12) 0 else hour
            "pm" -> if (hour == 12) 12 else if (hour < 12) hour + 12 else hour
            else -> hour
        }
        return (h24 * 60 + minute).coerceIn(0, 23 * 60 + 59)
    }

    private fun detectConflictingWindows(windows: List<ParsedTimeWindow>): String? {
        val byDay = windows.groupBy { it.dayOfWeek }
        for ((day, dayWins) in byDay) {
            val sorted = dayWins.sortedBy { it.startMinuteOfDay }
            for (i in 0 until sorted.size - 1) {
                val endA = if (sorted[i].endsNextDay) sorted[i].endMinuteOfDay + 1440 else sorted[i].endMinuteOfDay
                if (endA > sorted[i + 1].startMinuteOfDay) {
                    return "Overlapping availability windows detected on ${dayName(day)}."
                }
            }
        }
        return null
    }

    fun dayName(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "Monday"
        2 -> "Tuesday"
        3 -> "Wednesday"
        4 -> "Thursday"
        5 -> "Friday"
        6 -> "Saturday"
        7 -> "Sunday"
        else -> "Day $dayOfWeek"
    }

    fun dayShortName(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "Mon"
        2 -> "Tue"
        3 -> "Wed"
        4 -> "Thu"
        5 -> "Fri"
        6 -> "Sat"
        7 -> "Sun"
        else -> "D$dayOfWeek"
    }

    fun formatMinuteOfDay(minuteOfDay: Int): String {
        val clamped = minuteOfDay.coerceIn(0, 23 * 60 + 59)
        val hour24 = clamped / 60
        val min = clamped % 60
        val amPm = if (hour24 < 12) "AM" else "PM"
        val hour12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        return String.format(Locale.US, "%d:%02d %s", hour12, min, amPm)
    }

    fun formatMinute24Hour(minuteOfDay: Int): String {
        val clamped = minuteOfDay.coerceIn(0, 23 * 60 + 59)
        val hour24 = clamped / 60
        val min = clamped % 60
        return String.format(Locale.US, "%02d:%02d", hour24, min)
    }

    /**
     * Groups a clinic's availability windows into clean 2-line display pairs
     * (daysLabel to timeRangeLabel), e.g.:
     * "Monday, Tuesday, Thursday" to "8:00 PM – 10:00 PM".
     */
    fun formatCleanScheduleGroups(windows: List<AvailabilityWindowEntity>): List<Pair<String, String>> {
        if (windows.isEmpty()) return emptyList()
        val egyptianDayOrder = listOf(6, 7, 1, 2, 3, 4, 5) // Sat..Fri
        val grouped = windows
            .distinctBy { Triple(it.dayOfWeek, it.startMinuteOfDay, it.endMinuteOfDay to it.endsNextDay) }
            .groupBy { Triple(it.startMinuteOfDay, it.endMinuteOfDay, it.endsNextDay) }
        return grouped.entries.map { (timeRange, wins) ->
            val sortedDays = wins.map { it.dayOfWeek }
                .distinct()
                .sortedBy { d -> egyptianDayOrder.indexOf(d).takeIf { it >= 0 } ?: d }
            val dayLabel = when {
                sortedDays.size == 7 -> "Every Day"
                sortedDays.toSet() == setOf(6, 7, 1, 2, 3, 4) -> "Saturday – Thursday"
                else -> sortedDays.joinToString(", ") { dayName(it) }
            }
            val nextDaySuffix = if (timeRange.third) " (+1 day)" else ""
            val timeLabel = "${formatMinuteOfDay(timeRange.first)} – ${formatMinuteOfDay(timeRange.second)}$nextDaySuffix"
            dayLabel to timeLabel
        }
    }

    /**
     * Groups a clinic's availability windows into clean, human-readable schedule lines
     * (e.g. "Sun: 8:00 PM – 10:00 PM", "Tue, Wed: 3:00 PM – 6:00 PM").
     */
    fun formatWindowsSummary(windows: List<AvailabilityWindowEntity>): List<String> {
        if (windows.isEmpty()) return emptyList()
        val egyptianDayOrder = listOf(6, 7, 1, 2, 3, 4, 5) // Sat, Sun, Mon, Tue, Wed, Thu, Fri
        val grouped = windows
            .distinctBy { Triple(it.dayOfWeek, it.startMinuteOfDay, it.endMinuteOfDay to it.endsNextDay) }
            .groupBy { Triple(it.startMinuteOfDay, it.endMinuteOfDay, it.endsNextDay) }
        return grouped.entries.map { (timeRange, wins) ->
            val sortedDays = wins.map { it.dayOfWeek }
                .distinct()
                .sortedBy { d -> egyptianDayOrder.indexOf(d).takeIf { it >= 0 } ?: d }
            val dayLabel = when {
                sortedDays.size == 7 -> "Every Day"
                sortedDays.toSet() == setOf(6, 7, 1, 2, 3, 4) -> "Sat–Thu"
                else -> sortedDays.joinToString(", ") { dayShortName(it) }
            }
            val nextDaySuffix = if (timeRange.third) " (+1d)" else ""
            "$dayLabel: ${formatMinuteOfDay(timeRange.first)} – ${formatMinuteOfDay(timeRange.second)}$nextDaySuffix"
        }
    }
}

/**
 * Stage 4 programmatic validation gate (Part 8 & Part 10).
 * Ensures every deterministic or AI-proposed schedule satisfies all domain constraints
 * before persistence or route optimization.
 */
object ScheduleValidator {
    fun validate(result: ScheduleParseResult): ScheduleParseResult {
        if (result.classification == ScheduleClassification.NO_SCHEDULE_INFORMATION) {
            return result.copy(
                windows = emptyList(),
                status = ScheduleParseStatus.UNPARSED,
                validationStatus = ScheduleValidationStatus.NO_SCHEDULE
            )
        }

        if (result.timeInfoType != TimeInfoType.CLINIC_AVAILABILITY_RANGE) {
            val valStat = if (result.classification == ScheduleClassification.PARTIALLY_PARSED ||
                result.timeInfoType == TimeInfoType.SPECIFIC_APPOINTMENT_TIME
            ) {
                ScheduleValidationStatus.INCOMPLETE
            } else {
                ScheduleValidationStatus.NEEDS_CLARIFICATION
            }
            return result.copy(
                windows = emptyList(),
                validationStatus = valStat
            )
        }

        if (result.windows.isEmpty()) {
            return result.copy(
                validationStatus = ScheduleValidationStatus.NEEDS_CLARIFICATION,
                classification = if (result.classification == ScheduleClassification.PARTIALLY_PARSED) {
                    ScheduleClassification.PARTIALLY_PARSED
                } else {
                    ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION
                }
            )
        }

        val violations = mutableListOf<String>()
        for (win in result.windows) {
            if (win.dayOfWeek !in 1..7) {
                violations.add("Invalid weekday index ${win.dayOfWeek}.")
            }
            if (win.startMinuteOfDay !in 0..1439 || win.endMinuteOfDay !in 0..1439) {
                violations.add("Invalid clock minutes ${win.startMinuteOfDay}..${win.endMinuteOfDay}.")
            }
            if (win.endsNextDay) {
                val dur = win.durationMinutes
                if (dur !in 15..720) {
                    violations.add("Invalid overnight duration (${dur}m).")
                }
            } else if (win.endMinuteOfDay <= win.startMinuteOfDay) {
                violations.add("End time must be after start time.")
            }
        }

        val byDay = result.windows.groupBy { it.dayOfWeek }
        for ((day, dayWins) in byDay) {
            val sorted = dayWins.sortedBy { it.startMinuteOfDay }
            for (i in 0 until sorted.size - 1) {
                val endA = if (sorted[i].endsNextDay) sorted[i].endMinuteOfDay + 1440 else sorted[i].endMinuteOfDay
                if (endA > sorted[i + 1].startMinuteOfDay) {
                    violations.add("Overlapping windows on ${AvailabilityScheduleParser.dayName(day)}.")
                }
            }
        }

        return if (violations.isNotEmpty()) {
            result.copy(
                windows = emptyList(),
                status = ScheduleParseStatus.AMBIGUOUS,
                ambiguityReason = (listOf(result.ambiguityReason) + violations).filter { it.isNotBlank() }.distinct().joinToString(" "),
                timeInfoType = TimeInfoType.REQUIRES_MANUAL_CLARIFICATION,
                classification = ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION,
                validationStatus = ScheduleValidationStatus.INVALID,
                parsingConfidence = 0.0
            )
        } else {
            result.copy(
                validationStatus = ScheduleValidationStatus.VALID
            )
        }
    }
}
