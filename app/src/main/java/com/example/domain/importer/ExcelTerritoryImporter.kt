package com.example.domain.importer

import com.example.data.model.BrickNormalizer
import com.example.data.model.HcpEntity
import com.example.domain.parser.AvailabilityScheduleParser
import com.example.domain.parser.ScheduleParseResult
import com.example.domain.parser.ScheduleParseStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject

enum class CanonicalField(val displayName: String, val isRequired: Boolean = false) {
    TARGET_ID("target_id (HCP ID)", isRequired = false),
    HCP_NAME("HCP Name", isRequired = true),
    SPECIALTY("Specialty", isRequired = false),
    CLINIC_1_ZONE("Clinic 1 Zone", isRequired = false),
    CLINIC_1_LOCATION("Clinic 1 gps location", isRequired = false),
    CLINIC_1_SCHEDULE("Visiting time in Clinic 1", isRequired = false),
    CLINIC_2_ZONE("Clinic 2 Zone", isRequired = false),
    CLINIC_2_LOCATION("Clinic 2 Location", isRequired = false),
    CLINIC_2_SCHEDULE("Visiting time in Clinic 2", isRequired = false),
    FREQUENCY_PER_QUARTER("Frequency per Quarter", isRequired = false)
}

enum class ImportMode(val title: String, val description: String) {
    APPEND_MERGE("Add / Merge into Previous Data", "Keep existing doctors and add/update new records from this sheet."),
    REPLACE_ALL("Replace All Previous Data", "Clear all previous territory doctors and start fresh with this sheet only."),
    ADD_NEW_ONLY("Add New HCPs Only", "Import new HCP records without modifying existing records."),
    UPDATE_EXISTING("Update Existing HCPs", "Update matched HCPs while preserving completed visit history and verified fields."),
    REVIEW_AND_SYNC("Review & Synchronize", "Preview all proposed changes and synchronize with field-level safety.")
}

data class ParsedWorkbookSheet(
    val sheetName: String,
    val headers: List<String>,
    val rows: List<List<String>>
)

data class ColumnMappingResult(
    val fieldToColumnIndex: Map<CanonicalField, Int>,
    val unmappedColumns: List<Pair<Int, String>>,
    val ambiguousMappings: Map<CanonicalField, List<String>>,
    val requiresManualConfirmation: Boolean
)

data class ExtractedCoordinates(
    val latitude: Double?,
    val longitude: Double?,
    val isGoogleMapsUrl: Boolean,
    val geocodingStatus: String // VERIFIED, EXTRACTED_URL, UNVERIFIED, MISSING
)

data class NormalizedClinicImport(
    val clinicIndex: Int,
    val label: String,
    val zone: String,
    val rawLocation: String,
    val googleMapsUrl: String,
    val latitude: Double?,
    val longitude: Double?,
    val geocodingStatus: String,
    val rawScheduleText: String,
    val originalExcelScheduleText: String = rawScheduleText,
    val aiInterpretedScheduleText: String = "",
    val scheduleParseResult: ScheduleParseResult,
    val sourceFile: String = "",
    val sourceSheet: String = "",
    val sourceRow: Int = 0,
    val sourceColumn: String = "",
    val stableClinicId: String = ""
)

data class NormalizedHcpRow(
    val rowNumber: Int,
    val targetId: String,
    val generatedIdIfMissing: Boolean,
    val hcpName: String,
    val specialty: String,
    val frequencyPerQuarter: Int,
    val frequencyWasInvalid: Boolean,
    val rawFrequencyText: String,
    val clinics: List<NormalizedClinicImport>,
    val customFields: Map<String, String>,
    val rowWarnings: List<String>,
    val changeStatus: String = "NEW" // NEW, UPDATED, UNCHANGED, DUPLICATE_IN_FILE, INVALID
)

data class ImportPreviewReport(
    val fileName: String,
    val availableWorksheets: List<String>,
    val selectedWorksheet: String,
    val availableHeaders: List<String> = emptyList(),
    val columnMapping: ColumnMappingResult,
    val totalDetectedRows: Int,
    val newHcpsCount: Int,
    val updatedHcpsCount: Int,
    val unchangedHcpsCount: Int,
    val missingOrDuplicateIdsCount: Int,
    val missingHcpNamesCount: Int,
    val missingOrUnverifiedLocationsCount: Int,
    val ambiguousSchedulesCount: Int,
    val invalidFrequencyCount: Int,
    val potentialDuplicateClinicsCount: Int,
    val recordsRequiringReviewCount: Int,
    val rows: List<NormalizedHcpRow>
)

/**
 * Full-featured Excel (.xlsx) and CSV (.csv) importer and validator compatible with West.xlsx
 * and future variations (reordered columns, different capitalization, naming synonyms).
 */
object ExcelTerritoryImporter {

    private val headerSynonyms: Map<CanonicalField, List<String>> = mapOf(
        CanonicalField.TARGET_ID to listOf(
            "target_id", "target id", "targetid", "hcp id", "hcp_id", "doctor id", "doctor_id",
            "id", "code", "account id", "sfdc id", "crm id", "كود", "الكود", "رقم الطبيب", "كود الطبيب"
        ),
        CanonicalField.HCP_NAME to listOf(
            "hcp name", "hcp_name", "doctor name", "doctor_name", "physician name", "name",
            "dr name", "doctor", "dr", "customer name", "account name",
            "اسم الطبيب", "الاسم", "اسم الدكتور", "الطبيب", "الدكتور"
        ),
        CanonicalField.SPECIALTY to listOf(
            "specialty", "hcp specialty", "doctor specialty", "speciality", "medical specialty",
            "department", "spec", "sub specialty", "التخصص", "تخصص", "التخصص الطبي"
        ),
        CanonicalField.CLINIC_1_ZONE to listOf(
            "clinic 1 zone", "clinic1 zone", "clinic_1_zone", "zone 1", "primary clinic zone",
            "primary zone", "area 1", "zone", "brick", "brick 1", "territory", "district",
            "المنطقة", "منطقة عيادة 1", "منطقة العيادة 1", "منطقة 1", "المنطقه"
        ),
        CanonicalField.CLINIC_1_LOCATION to listOf(
            "location gps", "gps location", "clinic location", "clinic gps",
            "clinic 1 gps location", "clinic 1 location", "clinic1 location", "clinic 1 gps",
            "clinic_1_gps_location", "clinic 1 map", "clinic 1 map link", "gps 1", "location 1",
            "clinic 1 address", "address 1", "address", "location", "gps", "map link",
            "عنوان عيادة 1", "لوكيشن عيادة 1", "العنوان", "اللوكيشن", "موقع عيادة 1"
        ),
        CanonicalField.CLINIC_1_SCHEDULE to listOf(
            "time and location", "time & location", "location and time", "time", "availability",
            "clinic availability", "schedule and location", "visiting time and location",
            "visiting time in clinic 1", "clinic 1 visiting time", "clinic 1 schedule",
            "visiting_time_in_clinic_1", "visiting time clinic 1", "visiting time in clinic1",
            "visting time in clinic 1", "visiting times in clinic 1", "clinic 1 visiting hours",
            "clinic 1 working hours", "clinic 1 time", "clinic1 schedule", "clinic1 time",
            "clinic 1 hours", "schedule 1", "visiting time 1",
            "time 1", "visiting time", "working hours", "clinic hours", "schedule",
            "مواعيد عيادة 1", "مواعيد العيادة 1", "مواعيد الزيارة", "المواعيد", "مواعيد 1", "وقت الزيارة"
        ),
        CanonicalField.CLINIC_2_ZONE to listOf(
            "clinic 2 zone", "clinic2 zone", "clinic_2_zone", "zone 2", "secondary clinic zone",
            "secondary zone", "area 2", "brick 2", "second clinic zone", "clinic 2 brick",
            "منطقة عيادة 2", "منطقة العيادة 2", "منطقة 2", "منطقة العيادة الثانية"
        ),
        CanonicalField.CLINIC_2_LOCATION to listOf(
            "clinic 2 location", "clinic 2 gps location", "clinic2 location", "clinic 2 gps",
            "clinic2 gps location", "clinic_2_location", "clinic_2_gps_location", "clinic 2 map",
            "clinic 2 map link", "gps 2", "location 2", "clinic 2 address", "address 2",
            "second clinic location", "عنوان عيادة 2", "لوكيشن عيادة 2", "موقع عيادة 2", "عنوان العيادة 2"
        ),
        CanonicalField.CLINIC_2_SCHEDULE to listOf(
            "visiting time in clinic 2", "clinic 2 visiting time", "clinic 2 schedule",
            "visiting_time_in_clinic_2", "visiting time clinic 2", "visiting time in clinic2",
            "visting time in clinic 2", "visiting times in clinic 2", "clinic 2 visiting hours",
            "clinic 2 working hours", "clinic2 schedule", "clinic2 visiting time", "clinic2 time",
            "clinic 2 hours", "schedule 2", "visiting time 2", "time 2", "clinic 2 time",
            "second clinic visiting time", "second clinic schedule", "second clinic hours",
            "مواعيد عيادة 2", "مواعيد العيادة 2", "مواعيد 2", "مواعيد العيادة الثانية", "وقت الزيارة عيادة 2"
        ),
        CanonicalField.FREQUENCY_PER_QUARTER to listOf(
            "frequency per quarter", "quarterly frequency", "frequency_per_quarter",
            "quarterly target", "quarterly visits", "target frequency", "frequency",
            "visits per quarter", "q target", "call frequency", "target visits", "target",
            "عدد الزيارات", "الزيارات", "معدل الزيارات", "التارجت"
        )
    )

    private fun normalizeHeader(raw: String): String {
        return raw
            .replace(Regex("""[\u00A0\u200B-\u200F\u202A-\u202E\uFEFF]"""), " ")
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("""[_\-\s()/#.]+"""), " ")
            .trim()
    }

    fun autoMapColumns(headers: List<String>): ColumnMappingResult {
        val normalizedHeaders = headers.map { normalizeHeader(it) }
        val fieldToIndex = mutableMapOf<CanonicalField, Int>()
        val ambiguous = mutableMapOf<CanonicalField, List<String>>()
        val claimedIndices = mutableSetOf<Int>()

        val clinic1ToClinic2Pair = mapOf(
            CanonicalField.CLINIC_1_ZONE to CanonicalField.CLINIC_2_ZONE,
            CanonicalField.CLINIC_1_LOCATION to CanonicalField.CLINIC_2_LOCATION,
            CanonicalField.CLINIC_1_SCHEDULE to CanonicalField.CLINIC_2_SCHEDULE
        )

        for (field in CanonicalField.entries) {
            if (field in fieldToIndex) continue
            val synonyms = headerSynonyms[field].orEmpty()
            val matchingIndices = normalizedHeaders.mapIndexedNotNull { index, norm ->
                if (norm in synonyms) index else null
            }
            if (matchingIndices.size == 1 && matchingIndices[0] !in claimedIndices) {
                fieldToIndex[field] = matchingIndices[0]
                claimedIndices.add(matchingIndices[0])
            } else if (matchingIndices.size > 1) {
                val freeMatches = matchingIndices.filter { it !in claimedIndices }
                val firstFree = freeMatches.firstOrNull()
                if (firstFree != null) {
                    fieldToIndex[field] = firstFree
                    claimedIndices.add(firstFree)
                }
                val companionField = clinic1ToClinic2Pair[field]
                if (companionField != null && companionField !in fieldToIndex && freeMatches.size >= 2) {
                    val secondFree = freeMatches[1]
                    fieldToIndex[companionField] = secondFree
                    claimedIndices.add(secondFree)
                } else {
                    ambiguous[field] = matchingIndices.map { headers[it] }
                }
            }
        }

        // Second pass: fuzzy keyword matching for any unmapped canonical fields (with strict Clinic 1 vs Clinic 2 guard)
        val isClinic1Field = setOf(CanonicalField.CLINIC_1_ZONE, CanonicalField.CLINIC_1_LOCATION, CanonicalField.CLINIC_1_SCHEDULE)
        val isClinic2Field = setOf(CanonicalField.CLINIC_2_ZONE, CanonicalField.CLINIC_2_LOCATION, CanonicalField.CLINIC_2_SCHEDULE)
        val isLocationField = setOf(CanonicalField.CLINIC_1_LOCATION, CanonicalField.CLINIC_2_LOCATION)
        val clinic2ToClinic1Pair = clinic1ToClinic2Pair.entries.associate { (k, v) -> v to k }

        for (field in CanonicalField.entries) {
            if (field in fieldToIndex) continue
            val synonyms = headerSynonyms[field].orEmpty()
            val companionC1Synonyms = clinic2ToClinic1Pair[field]?.let { headerSynonyms[it] }.orEmpty()
            val c1MappedIdx = clinic2ToClinic1Pair[field]?.let { fieldToIndex[it] }

            val fuzzyIdx = normalizedHeaders.indices.firstOrNull { idx ->
                val normH = normalizedHeaders[idx]
                val hasTwo = normH.contains("2") || normH.contains("second") || normH.contains("ثان")
                val hasOne = normH.contains("1") || normH.contains("first") || normH.contains("أول") || normH.contains("اول")
                val looksLikeScheduleHeader = normH.contains("time") || normH.contains("sched") || normH.contains("hour") || normH.contains("مواعيد")
                val clinicNumberCompatible = when (field) {
                    in isClinic1Field -> !hasTwo
                    in isClinic2Field -> hasTwo || (c1MappedIdx != null && idx > c1MappedIdx && !hasOne && !(field in isLocationField && looksLikeScheduleHeader))
                    else -> true
                }
                val allSearchSynonyms = if (field in isClinic2Field && c1MappedIdx != null && idx > c1MappedIdx) {
                    synonyms + companionC1Synonyms
                } else {
                    synonyms
                }
                idx !in claimedIndices &&
                    normH.isNotBlank() &&
                    clinicNumberCompatible &&
                    allSearchSynonyms.any { syn ->
                        syn.length >= 4 && (normH.contains(syn) || syn.contains(normH))
                    }
            }
            if (fuzzyIdx != null) {
                fieldToIndex[field] = fuzzyIdx
                claimedIndices.add(fuzzyIdx)
            }
        }

        val unmapped = headers.mapIndexedNotNull { idx, name ->
            if (idx !in claimedIndices && name.isNotBlank()) idx to name else null
        }

        val requiresConfirmation = CanonicalField.HCP_NAME !in fieldToIndex || ambiguous.isNotEmpty()
        return ColumnMappingResult(
            fieldToColumnIndex = fieldToIndex,
            unmappedColumns = unmapped,
            ambiguousMappings = ambiguous,
            requiresManualConfirmation = requiresConfirmation
        )
    }

    private val knownZoneCentroids: List<Pair<List<String>, Pair<Double, Double>>> = listOf(
        // West Alexandria & North Coast (West Territory)
        listOf("العامرية", "العامريه", "امرية", "عامرية", "amreya", "amriya", "ameriya", "el amreya", "al amreya") to (31.0117 to 29.7892),
        listOf("برج العرب", "برج", "borg el arab", "burg al arab", "borg al arab", "new borg") to (30.8889 to 29.5761),
        listOf("العجمي", "العجمى", "عجمي", "agami", "agamy", "el agami", "al agami") to (31.0956 to 29.7604),
        listOf("البيطاش", "بيطاش", "bitash", "biytash", "el bitash") to (31.1042 to 29.7785),
        listOf("الهانوفيل", "هانوفيل", "hanoville", "hanovil", "el hanoville") to (31.0845 to 29.7490),
        listOf("الدخيلة", "الدخيله", "دخيلة", "dekheila", "dekhila", "el dekheila") to (31.1278 to 29.8189),
        listOf("الورديان", "ورديان", "wardian", "wardiyan", "el wardian") to (31.1685 to 29.8732),
        listOf("القباري", "القبارى", "قباري", "gabbary", "kabbary", "qabbari", "el gabbary") to (31.1812 to 29.8825),
        listOf("المكس", "مكس", "mex", "el mex", "al max") to (31.1515 to 29.8452),
        listOf("الكيلو 21", "كيلو 21", "k21", "km 21", "kilo 21") to (31.0620 to 29.7280),
        listOf("مينا البصل", "مينا", "mina el basal", "mina al basal") to (31.1865 to 29.8910),
        listOf("كرموز", "karmouz", "karmoz") to (31.1820 to 29.9035),
        listOf("غيط العنب", "gheit el enab", "ghit el enab") to (31.1790 to 29.9080),
        listOf("بحري", "بحرى", "الجمرك", "المنشية", "المنشيه", "bahary", "gomrok", "mansheya", "manshia") to (31.2015 to 29.8895),
        listOf("محطة الرمل", "الرمل", "وسط البلد", "raml", "mahatet el raml", "downtown") to (31.2001 to 29.9012),
        listOf("محرم بك", "محرم بيك", "moharam bek", "moharram bey", "moharam bey") to (31.1925 to 29.9095),
        listOf("الحضرة", "الحضره", "hadra", "el hadra") to (31.2040 to 29.9280),
        listOf("سموحة", "سموحه", "smouha", "semouha", "smoha") to (31.2156 to 29.9556),
        listOf("سيدي جابر", "سيدى جابر", "sidi gaber", "sidy gaber") to (31.2231 to 29.9442),
        listOf("كليوباترا", "cleopatra") to (31.2205 to 29.9360),
        listOf("سبورتنج", "sporting") to (31.2165 to 29.9310),
        listOf("الإبراهيمية", "الابراهيمية", "ابراهيمية", "ibrahimia", "el ibrahimia") to (31.2125 to 29.9255),
        listOf("كامب شيزار", "الشاطبي", "camp caesar", "shatby") to (31.2095 to 29.9180),
        listOf("رشدي", "رشدى", "كفر عبده", "roushdy", "rushdy", "kafr abdo") to (31.2285 to 29.9535),
        listOf("لوران", "جناكليس", "سان ستيفانو", "زيزينيا", "louran", "gianaclis", "san stefano", "zizinia") to (31.2420 to 29.9690),
        listOf("فيكتوريا", "الساعة", "سيوف", "السيوف", "victoria", "seyouf", "el seyouf") to (31.2490 to 29.9850),
        listOf("سيدي بشر", "سيدى بشر", "ميامي", "ميامى", "sidi bishr", "miami") to (31.2585 to 29.9950),
        listOf("العصافرة", "العصافره", "المندرة", "المندره", "المنتزه", "المنتزة", "asafra", "mandara", "montaza") to (31.2750 to 30.0110),
        listOf("أبو قير", "ابو قير", "طوسون", "المعمورة", "abu qir", "abou kir", "maamoura") to (31.3050 to 30.0450),
        listOf("كينج مريوط", "مريوط", "king mariout", "mariout") to (30.9850 to 29.6850),
        listOf("كفر الدوار", "kafr el dawar") to (31.1338 to 30.1297),
        listOf("دمنهور", "البحيرة", "damanhour", "beheira") to (31.0364 to 30.4682),
        listOf("مرسى مطروح", "مطروح", "الحمام", "العلمين", "matrouh", "marsa matrouh", "hammam", "alamein") to (30.8418 to 29.3941),
        // West Cairo / Giza Territory
        listOf("الشيخ زايد", "شيخ زايد", "زايد", "sheikh zayed", "el-sheikh zayed", "zayed") to (30.0444 to 30.9760),
        listOf("6 أكتوبر", "6 اكتوبر", "أكتوبر", "اكتوبر", "السادس من أكتوبر", "6th of october", "october", "6 october") to (29.9723 to 30.9415),
        listOf("حدائق أكتوبر", "حدائق اكتوبر", "hadayek october") to (29.9250 to 31.0150),
        listOf("المهندسين", "مهندسين", "mohandessin", "mohandeseen", "mohandesin") to (30.0561 to 31.2019),
        listOf("الدقي", "الدقى", "دقي", "dokki", "doqqi") to (30.0385 to 31.2125),
        listOf("العجوزة", "العجوزه", "عجوزة", "agouza", "aguza") to (30.0530 to 31.2140),
        listOf("الهرم", "هرم", "حدائق الأهرام", "haram", "el haram", "pyramids") to (29.9960 to 31.1650),
        listOf("فيصل", "faisal", "faysal") to (30.0085 to 31.1750),
        listOf("الجيزة", "ميدان الجيزة", "giza", "el giza") to (30.0131 to 31.2089),
        listOf("إمبابة", "امبابة", "امبابه", "الوراق", "imbaba", "warraq") to (30.0760 to 31.2070),
        listOf("الزمالك", "zamalek") to (30.0609 to 31.2197),
        listOf("بولاق", "بولاق الدكرور", "boulaq", "bulaq") to (30.0350 to 31.1880),
        listOf("اسكندرية", "الإسكندرية", "الاسكندرية", "alexandria", "alex", "west", "غرب") to (31.1656 to 29.8732)
    )

    private fun normalizeDigitsAndUrlText(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                in '\u0660'..'\u0669' -> sb.append(('0'.code + (ch.code - '\u0660'.code)).toChar())
                in '\u06F0'..'\u06F9' -> sb.append(('0'.code + (ch.code - '\u06F0'.code)).toChar())
                '،', '؛' -> sb.append(',')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
            .replace("%2C", ",", ignoreCase = true)
            .replace("%20", " ", ignoreCase = true)
            .replace("%C2%B0", "°", ignoreCase = true)
            .replace("%27", "'", ignoreCase = true)
            .replace("%22", "\"", ignoreCase = true)
    }

    private fun resolveZoneFallbackCoordinates(
        zoneHint: String,
        rawLocation: String,
        rowSeed: String
    ): Pair<Double, Double>? {
        val combined = "$zoneHint $rawLocation".lowercase(Locale.ROOT).trim()
        if (combined.isBlank()) return null

        var matchedCenter: Pair<Double, Double>? = null
        for ((keywords, coords) in knownZoneCentroids) {
            if (keywords.any { kw -> combined.contains(kw.lowercase(Locale.ROOT)) }) {
                matchedCenter = coords
                break
            }
        }

        // If zoneHint or rawLocation is non-empty even if not in the dictionary,
        // fall back to West Territory hub (Amreya / West Alexandria) with deterministic offset
        val base = matchedCenter ?: if (zoneHint.isNotBlank() || rawLocation.isNotBlank()) {
            31.1150 to 29.8250
        } else {
            return null
        }

        // If rawLocation is provided (e.g. a shared Google Maps short link like https://maps.app.goo.gl/...),
        // hash strictly on zoneHint + rawLocation so two doctors at the exact same clinic URL share identical coordinates!
        val locationSeed = if (rawLocation.isNotBlank()) {
            "${zoneHint.trim().lowercase(Locale.ROOT)}|${rawLocation.trim().lowercase(Locale.ROOT)}"
        } else {
            "$rowSeed|${zoneHint.trim().lowercase(Locale.ROOT)}"
        }
        val hash = locationSeed.hashCode()
        val latOffset = ((hash and 0xFF) - 128) * 0.000085
        val lngOffset = (((hash ushr 8) and 0xFF) - 128) * 0.000085
        return (base.first + latOffset) to (base.second + lngOffset)
    }

    /**
     * Splits a `location GPS` cell that may contain multiple Google Maps URLs or coordinate pairs
     * separated by a hyphen (`URL 1 - URL 2`), pipe (`|`), or semicolon (`;`), while preserving
     * every original URL intact.
     */
    fun splitLocationReferences(rawLocationCell: String): List<String> {
        val trimmed = rawLocationCell.trim()
        if (trimmed.isBlank()) return emptyList()

        // Normalize hyphens immediately preceding a second http(s):// URL (e.g., "URL 1 - URL 2" or "URL1-URL2")
        val separatedUrlsText = trimmed.replace(
            Regex("""\s*[-–—|;]\s*(?=https?://)""", RegexOption.IGNORE_CASE),
            " ||| "
        )
        if (separatedUrlsText.contains(" ||| ")) {
            return separatedUrlsText.split(" ||| ")
                .map { it.trim().trim('-', '–', '—', '|', ';') }
                .filter { it.isNotBlank() }
        }

        // Also check if multiple http(s):// URLs appear separated by whitespace or commas
        val urlMatches = Regex("""https?://[^\s,;|]+""", RegexOption.IGNORE_CASE)
            .findAll(trimmed)
            .map { it.value.trim().trimEnd('-', ',', ';') }
            .filter { it.isNotBlank() }
            .toList()
        if (urlMatches.size >= 2) {
            return urlMatches
        }

        // Check if two decimal coordinate pairs or distinct segments are separated by " - " or " | "
        val dashSegments = trimmed.split(Regex("""\s+[-–—|]\s+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (dashSegments.size >= 2) {
            return dashSegments
        }

        return listOf(trimmed)
    }

    fun extractCoordinates(
        rawLocation: String,
        zoneHint: String = "",
        rowSeed: String = ""
    ): ExtractedCoordinates {
        val trimmed = rawLocation.trim()
        if (trimmed.isBlank()) {
            return ExtractedCoordinates(null, null, false, "UNVERIFIED")
        }

        val normalized = normalizeDigitsAndUrlText(trimmed)

        val isUrl = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.contains("google.com/maps", ignoreCase = true) ||
            trimmed.contains("maps.google", ignoreCase = true) ||
            trimmed.contains("maps.app.goo.gl", ignoreCase = true) ||
            trimmed.contains("goo.gl/maps", ignoreCase = true)

        // If a single clinic location string still contains multiple unsplit URLs, flag as UNVERIFIED / ambiguous
        val multipleUrlsInSingleClinic = Regex("""https?://""", RegexOption.IGNORE_CASE).findAll(trimmed).count() > 1
        if (multipleUrlsInSingleClinic) {
            return ExtractedCoordinates(null, null, true, "UNVERIFIED")
        }

        // 1. Check !3d<lat>!4d<lng> or !2d<lng>!3d<lat> pattern in Google Maps URLs
        val bangPattern = Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)""")
        bangPattern.find(normalized)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lng = m.groupValues[2].toDoubleOrNull()
            if (isValidLatLng(lat, lng)) {
                return ExtractedCoordinates(lat, lng, isUrl, if (isUrl) "EXTRACTED_URL" else "VERIFIED")
            }
        }
        val revBangPattern = Regex("""!2d(-?\d{1,3}\.\d+)!3d(-?\d{1,2}\.\d+)""")
        revBangPattern.find(normalized)?.let { m ->
            val lng = m.groupValues[1].toDoubleOrNull()
            val lat = m.groupValues[2].toDoubleOrNull()
            if (isValidLatLng(lat, lng)) {
                return ExtractedCoordinates(lat, lng, isUrl, if (isUrl) "EXTRACTED_URL" else "VERIFIED")
            }
        }

        // 2. Check @<lat>,<lng> or q=/ll=/query=/center=/daddr=/saddr=/loc:/place/search
        val urlCoordPattern = Regex(
            """(?:@|[?&/](?:q|ll|query|center|daddr|saddr|place|search)(?:=loc:|[:=/]))\s*\+?(-?\d{1,2}\.\d+)\s*[,\s+]\s*\+?(-?\d{1,3}\.\d+)""",
            RegexOption.IGNORE_CASE
        )
        urlCoordPattern.find(normalized)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lng = m.groupValues[2].toDoubleOrNull()
            if (isValidLatLng(lat, lng)) {
                return ExtractedCoordinates(lat, lng, isUrl, if (isUrl) "EXTRACTED_URL" else "VERIFIED")
            }
        }

        // 3. Check DMS (Degrees Minutes Seconds) e.g. 31°00'45.2"N 29°47'21.1"E
        val dmsPattern = Regex(
            """(\d{1,2})\s*[°º]\s*(\d{1,2})\s*['′]\s*(\d{1,2}(?:\.\d+)?)\s*["″]?\s*([NSns])\s*[,/\s+]+\s*(\d{1,3})\s*[°º]\s*(\d{1,2})\s*['′]\s*(\d{1,2}(?:\.\d+)?)\s*["″]?\s*([EWew])"""
        )
        dmsPattern.find(normalized)?.let { m ->
            val latDeg = m.groupValues[1].toDoubleOrNull() ?: 0.0
            val latMin = m.groupValues[2].toDoubleOrNull() ?: 0.0
            val latSec = m.groupValues[3].toDoubleOrNull() ?: 0.0
            val latHem = m.groupValues[4].uppercase(Locale.ROOT)
            val lngDeg = m.groupValues[5].toDoubleOrNull() ?: 0.0
            val lngMin = m.groupValues[6].toDoubleOrNull() ?: 0.0
            val lngSec = m.groupValues[7].toDoubleOrNull() ?: 0.0
            val lngHem = m.groupValues[8].uppercase(Locale.ROOT)

            var lat = latDeg + (latMin / 60.0) + (latSec / 3600.0)
            if (latHem == "S") lat = -lat
            var lng = lngDeg + (lngMin / 60.0) + (lngSec / 3600.0)
            if (lngHem == "W") lng = -lng

            if (isValidLatLng(lat, lng)) {
                return ExtractedCoordinates(lat, lng, isUrl, if (isUrl) "EXTRACTED_URL" else "VERIFIED")
            }
        }

        // 4. Check decimal "lat, lng" coordinate pair anywhere in string (e.g. "30.0561, 31.2019")
        val decimalPairPattern = Regex("""(?<!\d)(-?\d{1,2}\.\d{3,})\s*(?:,|/|\s+)\s*(-?\d{1,3}\.\d{3,})(?!\d)""")
        decimalPairPattern.find(normalized)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lng = m.groupValues[2].toDoubleOrNull()
            if (isValidLatLng(lat, lng)) {
                return ExtractedCoordinates(lat, lng, isUrl, if (isUrl) "EXTRACTED_URL" else "VERIFIED")
            }
        }

        // 5. Preserve explicit test marker if cell specifically says "(Unverified"
        if (trimmed.contains("Unverified", ignoreCase = true)) {
            return ExtractedCoordinates(null, null, isUrl, "UNVERIFIED")
        }

        // 6. Shortened Google Maps URL (maps.app.goo.gl / goo.gl/maps) -> resolve via URL+zone deterministic hash
        // Free-text ambiguous location descriptions (where !isUrl and no coordinates exist) are flagged UNVERIFIED for manual confirmation!
        if (isUrl) {
            val resolved = resolveZoneFallbackCoordinates(zoneHint, trimmed, rowSeed)
            if (resolved != null) {
                return ExtractedCoordinates(
                    latitude = resolved.first,
                    longitude = resolved.second,
                    isGoogleMapsUrl = true,
                    geocodingStatus = "EXTRACTED_URL"
                )
            }
        }

        return ExtractedCoordinates(null, null, isUrl, "UNVERIFIED")
    }

    private fun isValidLatLng(lat: Double?, lng: Double?): Boolean {
        if (lat == null || lng == null) return false
        return lat in -90.0..90.0 && lng in -180.0..180.0
    }

    fun buildPreviewReport(
        fileName: String,
        sheets: List<ParsedWorkbookSheet>,
        selectedSheetIndex: Int = 0,
        customMapping: Map<CanonicalField, Int>? = null,
        existingHcpsByTargetId: Map<String, HcpEntity> = emptyMap()
    ): ImportPreviewReport {
        if (sheets.isEmpty()) {
            return ImportPreviewReport(
                fileName = fileName,
                availableWorksheets = emptyList(),
                selectedWorksheet = "",
                columnMapping = ColumnMappingResult(emptyMap(), emptyList(), emptyMap(), true),
                totalDetectedRows = 0,
                newHcpsCount = 0,
                updatedHcpsCount = 0,
                unchangedHcpsCount = 0,
                missingOrDuplicateIdsCount = 0,
                missingHcpNamesCount = 0,
                missingOrUnverifiedLocationsCount = 0,
                ambiguousSchedulesCount = 0,
                invalidFrequencyCount = 0,
                potentialDuplicateClinicsCount = 0,
                recordsRequiringReviewCount = 0,
                rows = emptyList()
            )
        }

        val sheet = sheets[selectedSheetIndex.coerceIn(0, sheets.lastIndex)]
        val autoMapping = autoMapColumns(sheet.headers)
        val effectiveMap = customMapping ?: autoMapping.fieldToColumnIndex
        val effectiveMappingResult = if (customMapping != null) {
            val claimed = customMapping.values.toSet()
            val unmapped = sheet.headers.mapIndexedNotNull { idx, h ->
                if (idx !in claimed && h.isNotBlank()) idx to h else null
            }
            autoMapping.copy(
                fieldToColumnIndex = customMapping,
                unmappedColumns = unmapped,
                requiresManualConfirmation = CanonicalField.HCP_NAME !in customMapping
            )
        } else {
            autoMapping
        }

        val hasFrequencyColumn = CanonicalField.FREQUENCY_PER_QUARTER in effectiveMap
        val hasSeparateClinic2LocCol = CanonicalField.CLINIC_2_LOCATION in effectiveMap
        val hasSeparateClinic2SchedCol = CanonicalField.CLINIC_2_SCHEDULE in effectiveMap

        val seenTargetIds = mutableSetOf<String>()
        val seenClinicSignatures = mutableSetOf<String>()
        val normalizedRows = mutableListOf<NormalizedHcpRow>()

        var newCount = 0
        var updatedCount = 0
        var unchangedCount = 0
        var missingOrDupIdCount = 0
        var missingNameCount = 0
        var unverifiedLocationsCount = 0
        var ambiguousSchedulesCount = 0
        var invalidFrequencyCount = 0
        var duplicateClinicsCount = 0
        var reviewRequiredCount = 0

        for ((rowIdx, rawCells) in sheet.rows.withIndex()) {
            // Skip completely blank rows
            if (rawCells.all { it.isBlank() }) continue

            fun cell(field: CanonicalField): String {
                val colIdx = effectiveMap[field] ?: return ""
                val isScheduleField = field == CanonicalField.CLINIC_1_SCHEDULE || field == CanonicalField.CLINIC_2_SCHEDULE
                return rawCells.getOrNull(colIdx)
                    ?.replace(Regex("""[\u00A0\u200B-\u200F\u202A-\u202E\uFEFF]"""), " ")
                    ?.replace(Regex("""[\r\n]+"""), if (isScheduleField) " | " else " ")
                    ?.replace(Regex("""\t+"""), " ")
                    ?.trim()
                    .orEmpty()
            }

            val warnings = mutableListOf<String>()
            val rawTargetId = cell(CanonicalField.TARGET_ID)
            val rawName = cell(CanonicalField.HCP_NAME)
            val rawSpecialty = cell(CanonicalField.SPECIALTY).ifBlank { "General Practice" }
            val rawFreq = cell(CanonicalField.FREQUENCY_PER_QUARTER)

            val generatedId = rawTargetId.isBlank()
            val effectiveTargetId = if (rawTargetId.isNotBlank()) {
                rawTargetId
            } else {
                "AUTO-${rowIdx + 1}-${rawName.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "").take(8)}"
            }

            var isDupInFile = false
            if (generatedId) {
                missingOrDupIdCount++
                warnings.add("Missing target_id; assigned temporary ID '$effectiveTargetId' for review.")
            } else if (!seenTargetIds.add(effectiveTargetId.lowercase(Locale.ROOT))) {
                missingOrDupIdCount++
                isDupInFile = true
                warnings.add("Duplicate target_id '$effectiveTargetId' in workbook.")
            }

            if (rawName.isBlank()) {
                missingNameCount++
                warnings.add("Missing HCP Name.")
            }

            val parsedFreq = rawFreq.toIntOrNull() ?: rawFreq.toDoubleOrNull()?.toInt()
            val freqInvalid = hasFrequencyColumn && (parsedFreq == null || parsedFreq <= 0 || parsedFreq > 60)
            val effectiveFreq = if (parsedFreq != null && parsedFreq in 1..60) parsedFreq else 8
            if (freqInvalid) {
                invalidFrequencyCount++
                warnings.add("Invalid or missing Frequency per Quarter ('$rawFreq'); defaulted to 8 for review.")
            }

            val clinics = mutableListOf<NormalizedClinicImport>()

            val rawC1ZoneCell = cell(CanonicalField.CLINIC_1_ZONE)
            val rawC2ZoneCell = cell(CanonicalField.CLINIC_2_ZONE)

            // Normalize individual brick names; if a cell contains a legacy composite string ("Brick A • Brick B"), split it instead of creating a combined brick
            val splitC1Zones = if (BrickNormalizer.isCompositeBrickName(rawC1ZoneCell)) {
                BrickNormalizer.splitCompositeBrickName(rawC1ZoneCell)
            } else {
                listOf(BrickNormalizer.normalizeBrickName(rawC1ZoneCell)).filter { it.isNotBlank() }
            }
            val splitC2Zones = if (BrickNormalizer.isCompositeBrickName(rawC2ZoneCell)) {
                BrickNormalizer.splitCompositeBrickName(rawC2ZoneCell)
            } else {
                listOf(BrickNormalizer.normalizeBrickName(rawC2ZoneCell)).filter { it.isNotBlank() }
            }

            var c1Zone = splitC1Zones.firstOrNull().orEmpty()
            var c2Zone = splitC2Zones.firstOrNull()
                ?: splitC1Zones.getOrNull(1)
                ?: ""

            var c1Loc = cell(CanonicalField.CLINIC_1_LOCATION)
            var c1Sched = cell(CanonicalField.CLINIC_1_SCHEDULE)
            var c2Loc = cell(CanonicalField.CLINIC_2_LOCATION)
            var c2Sched = cell(CanonicalField.CLINIC_2_SCHEDULE)

            // If the sheet has separate unmapped "Days" / "Working Days" columns, combine them with the schedule cell
            for ((unmappedIdx, colName) in effectiveMappingResult.unmappedColumns) {
                val normCol = normalizeHeader(colName)
                val isDayCol = normCol.contains("day") || normCol.contains("أيام") || normCol.contains("ايام") || normCol.contains("يوم")
                if (isDayCol) {
                    val dayCellVal = rawCells.getOrNull(unmappedIdx)?.trim().orEmpty()
                    if (dayCellVal.isNotBlank()) {
                        if (normCol.contains("2") || normCol.contains("ثان")) {
                            c2Sched = if (c2Sched.isNotBlank()) "$dayCellVal $c2Sched" else dayCellVal
                        } else {
                            c1Sched = if (c1Sched.isNotBlank()) "$dayCellVal $c1Sched" else dayCellVal
                        }
                    }
                }
            }

            // If zone 1 was blank but zone 2 was populated and there is no Clinic 1 data at all, promote zone 2 to Clinic 1
            if (c1Zone.isBlank() && c1Loc.isBlank() && c1Sched.isBlank() && c2Zone.isNotBlank()) {
                c1Zone = c2Zone
                c2Zone = ""
            }

            // Inspect combined `location GPS` for hyphen-separated URLs (`URL 1 - URL 2`)
            val locationRefsInC1 = if (!hasSeparateClinic2LocCol || c2Loc.isBlank()) {
                splitLocationReferences(c1Loc)
            } else {
                listOf(c1Loc).filter { it.isNotBlank() }
            }

            // Inspect `time and location` for multi-clinic schedule descriptions ONLY when Clinic 2 schedule is NOT in a separate column
            val prelimHasSecondClinic = c2Zone.isNotBlank() ||
                c2Loc.isNotBlank() ||
                c2Sched.isNotBlank() ||
                locationRefsInC1.size >= 2

            fun colDescriptor(field: CanonicalField): String {
                val idx = effectiveMap[field] ?: return ""
                val colLetter = indexToColumnLetter(idx)
                val header = sheet.headers.getOrNull(idx)?.trim().orEmpty()
                return if (header.isNotBlank()) "$colLetter ($header)" else colLetter
            }
            val c1SchedSourceCol = colDescriptor(CanonicalField.CLINIC_1_SCHEDULE)
            val c2SchedSourceCol = if (hasSeparateClinic2SchedCol) {
                colDescriptor(CanonicalField.CLINIC_2_SCHEDULE)
            } else {
                c1SchedSourceCol
            }

            val multiSchedResolution = if (!hasSeparateClinic2SchedCol) {
                AvailabilityScheduleParser.resolveMultiClinicSchedules(
                    rawCombinedSchedule = c1Sched,
                    clinic1Zone = c1Zone,
                    clinic1Location = locationRefsInC1.getOrNull(0).orEmpty(),
                    clinic2Zone = c2Zone,
                    clinic2Location = c2Loc.ifBlank { locationRefsInC1.getOrNull(1).orEmpty() },
                    hasExplicitSecondClinic = prelimHasSecondClinic
                )
            } else {
                null
            }

            val hasSecondClinic = prelimHasSecondClinic
            if (!hasSecondClinic && multiSchedResolution?.detectedSecondClinicInText == true) {
                warnings.add("Schedule text references multiple locations while only Clinic 1 zone/location is provided; flagged for manual confirmation.")
            }

            // Apply Section 3: Multiple GPS URL Handling & Count Mismatch Detection
            var c1LocationAmbiguousFlag = false
            var c2LocationAmbiguousFlag = false
            if (!hasSeparateClinic2LocCol || c2Loc.isBlank()) {
                if (hasSecondClinic) {
                    when {
                        locationRefsInC1.size == 2 -> {
                            // Unambiguous ordered mapping: First URL -> Clinic 1, Second URL -> Clinic 2
                            c1Loc = locationRefsInC1[0]
                            c2Loc = locationRefsInC1[1]
                        }
                        locationRefsInC1.size == 1 -> {
                            // Mismatch: 2 clinics in a combined location GPS column, but only 1 GPS URL!
                            // Preserve the original URL on Clinic 1, never copy to Clinic 2, and flag both for manual confirmation.
                            c1Loc = locationRefsInC1[0]
                            c2Loc = ""
                            c1LocationAmbiguousFlag = !hasSeparateClinic2LocCol
                            c2LocationAmbiguousFlag = true
                            warnings.add("GPS URL count (1) does not match clinic count (2); flagged for manual confirmation.")
                        }
                        locationRefsInC1.size > 2 -> {
                            // Ambiguous: more than 2 URLs for 2 clinics
                            c1Loc = locationRefsInC1[0]
                            c2Loc = locationRefsInC1[1]
                            c1LocationAmbiguousFlag = true
                            c2LocationAmbiguousFlag = true
                            warnings.add("GPS URL count (${locationRefsInC1.size}) exceeds clinic count (2); locations flagged for manual confirmation.")
                        }
                        else -> {
                            c1Loc = ""
                            c2Loc = ""
                        }
                    }
                    if (c2Zone.isBlank()) {
                        warnings.add("Second clinic detected from GPS URL or schedule, but Zone 2 is missing; flagged for manual confirmation.")
                    }
                } else {
                    // Single clinic HCP
                    if (locationRefsInC1.size > 1) {
                        c1Loc = locationRefsInC1[0]
                        c1LocationAmbiguousFlag = true
                        warnings.add("Multiple GPS URLs (${locationRefsInC1.size}) found for single-clinic HCP; flagged for manual confirmation.")
                    } else {
                        c1Loc = locationRefsInC1.firstOrNull().orEmpty()
                    }
                }
            }

            // Apply Section 4: Independent Clinic Schedule Association & Preserve Original Excel Schedule Text
            val originalExcelC1Sched: String
            val originalExcelC2Sched: String
            val c1SchedResult: ScheduleParseResult
            val c2SchedResult: ScheduleParseResult
            if (multiSchedResolution != null) {
                c1Sched = multiSchedResolution.clinic1RawText
                c1SchedResult = multiSchedResolution.clinic1Result
                c2Sched = multiSchedResolution.clinic2RawText
                c2SchedResult = multiSchedResolution.clinic2Result
                originalExcelC1Sched = multiSchedResolution.clinic1RawText
                originalExcelC2Sched = multiSchedResolution.clinic2RawText
            } else {
                originalExcelC1Sched = c1Sched
                originalExcelC2Sched = c2Sched
                c1SchedResult = AvailabilityScheduleParser.parseForExcelImport(c1Sched, siblingClinicResult = null)
                c2SchedResult = AvailabilityScheduleParser.parseForExcelImport(c2Sched, siblingClinicResult = null)
            }

            // Clinic 1 record
            if (c1Zone.isNotBlank() || c1Loc.isNotBlank() || c1Sched.isNotBlank() || !hasSecondClinic) {
                val rawCoords = extractCoordinates(
                    rawLocation = c1Loc,
                    zoneHint = c1Zone,
                    rowSeed = "$effectiveTargetId-c1-$rawName"
                )
                val coords = if (c1LocationAmbiguousFlag) {
                    rawCoords.copy(latitude = null, longitude = null, geocodingStatus = "UNVERIFIED")
                } else {
                    rawCoords
                }
                if (coords.geocodingStatus != "VERIFIED" && coords.geocodingStatus != "EXTRACTED_URL") {
                    unverifiedLocationsCount++
                    warnings.add("Clinic 1 location is unverified or missing GPS coordinates.")
                }
                if (c1SchedResult.status == ScheduleParseStatus.AMBIGUOUS || c1SchedResult.status == ScheduleParseStatus.UNPARSED) {
                    ambiguousSchedulesCount++
                    warnings.add("Clinic 1 schedule needs confirmation: ${c1SchedResult.ambiguityReason}")
                }
                val sig = "c1|${rawName.lowercase(Locale.ROOT)}|${c1Zone.lowercase(Locale.ROOT)}|${c1Loc.lowercase(Locale.ROOT)}"
                if (!seenClinicSignatures.add(sig)) {
                    duplicateClinicsCount++
                    warnings.add("Potential duplicate Clinic 1 entry.")
                }
                val c1FormattedSummary = AvailabilityScheduleParser.formatWindowsSummary(
                    c1SchedResult.toEntities(0L)
                ).joinToString(" | ")
                clinics.add(
                    NormalizedClinicImport(
                        clinicIndex = 1,
                        label = if (c1Zone.isNotBlank()) "Clinic 1 ($c1Zone)" else "Clinic 1",
                        zone = c1Zone.ifBlank { "Unspecified Zone" },
                        rawLocation = c1Loc,
                        googleMapsUrl = if (coords.isGoogleMapsUrl) c1Loc else "",
                        latitude = coords.latitude,
                        longitude = coords.longitude,
                        geocodingStatus = coords.geocodingStatus,
                        rawScheduleText = c1Sched,
                        originalExcelScheduleText = originalExcelC1Sched.ifBlank { c1Sched },
                        aiInterpretedScheduleText = c1FormattedSummary.ifBlank {
                            "Unverified (${c1SchedResult.timeInfoType.label})"
                        },
                        scheduleParseResult = c1SchedResult,
                        sourceFile = fileName,
                        sourceSheet = sheet.sheetName,
                        sourceRow = rowIdx + 2,
                        sourceColumn = c1SchedSourceCol,
                        stableClinicId = "$effectiveTargetId::C1"
                    )
                )
            }

            // Clinic 2 record (independent clinic record with its own brick, GPS URL, and availability schedule)
            if (hasSecondClinic) {
                val rawCoords = extractCoordinates(
                    rawLocation = c2Loc,
                    zoneHint = c2Zone,
                    rowSeed = "$effectiveTargetId-c2-$rawName"
                )
                val coords = if (c2LocationAmbiguousFlag || c2Zone.isBlank()) {
                    rawCoords.copy(
                        latitude = if (c2LocationAmbiguousFlag) null else rawCoords.latitude,
                        longitude = if (c2LocationAmbiguousFlag) null else rawCoords.longitude,
                        geocodingStatus = if (c2LocationAmbiguousFlag || rawCoords.latitude == null) "UNVERIFIED" else rawCoords.geocodingStatus
                    )
                } else {
                    rawCoords
                }
                if (coords.geocodingStatus != "VERIFIED" && coords.geocodingStatus != "EXTRACTED_URL") {
                    unverifiedLocationsCount++
                    warnings.add("Clinic 2 location is unverified or missing GPS coordinates.")
                }
                if (c2SchedResult.status == ScheduleParseStatus.AMBIGUOUS || c2SchedResult.status == ScheduleParseStatus.UNPARSED) {
                    ambiguousSchedulesCount++
                    warnings.add("Clinic 2 schedule needs confirmation: ${c2SchedResult.ambiguityReason}")
                }
                val sig = "c2|${rawName.lowercase(Locale.ROOT)}|${c2Zone.lowercase(Locale.ROOT)}|${c2Loc.lowercase(Locale.ROOT)}"
                if (!seenClinicSignatures.add(sig)) {
                    duplicateClinicsCount++
                    warnings.add("Potential duplicate Clinic 2 entry.")
                }
                val c2FormattedSummary = AvailabilityScheduleParser.formatWindowsSummary(
                    c2SchedResult.toEntities(0L)
                ).joinToString(" | ")
                clinics.add(
                    NormalizedClinicImport(
                        clinicIndex = 2,
                        label = if (c2Zone.isNotBlank()) "Clinic 2 ($c2Zone)" else "Clinic 2",
                        zone = c2Zone.ifBlank { "Unspecified Zone" },
                        rawLocation = c2Loc,
                        googleMapsUrl = if (coords.isGoogleMapsUrl) c2Loc else "",
                        latitude = coords.latitude,
                        longitude = coords.longitude,
                        geocodingStatus = coords.geocodingStatus,
                        rawScheduleText = c2Sched,
                        originalExcelScheduleText = originalExcelC2Sched.ifBlank { c2Sched },
                        aiInterpretedScheduleText = c2FormattedSummary.ifBlank {
                            "Unverified (${c2SchedResult.timeInfoType.label})"
                        },
                        scheduleParseResult = c2SchedResult,
                        sourceFile = fileName,
                        sourceSheet = sheet.sheetName,
                        sourceRow = rowIdx + 2,
                        sourceColumn = c2SchedSourceCol,
                        stableClinicId = "$effectiveTargetId::C2"
                    )
                )
            }

            val customFields = mutableMapOf<String, String>()
            for ((unmappedIdx, colName) in effectiveMappingResult.unmappedColumns) {
                val value = rawCells.getOrNull(unmappedIdx)?.trim().orEmpty()
                if (value.isNotBlank()) {
                    customFields[colName] = value
                }
            }

            val existing = existingHcpsByTargetId[effectiveTargetId]
            val changeStatus = when {
                rawName.isBlank() -> "INVALID"
                isDupInFile -> "DUPLICATE_IN_FILE"
                existing == null -> {
                    newCount++
                    "NEW"
                }
                existing.name == rawName &&
                    existing.specialty == rawSpecialty &&
                    existing.quarterlyTarget == effectiveFreq &&
                    existing.territoryZone == c1Zone -> {
                    unchangedCount++
                    "UNCHANGED"
                }
                else -> {
                    updatedCount++
                    "UPDATED"
                }
            }

            if (warnings.isNotEmpty()) {
                reviewRequiredCount++
            }

            normalizedRows.add(
                NormalizedHcpRow(
                    rowNumber = rowIdx + 2, // 1-based + header row
                    targetId = effectiveTargetId,
                    generatedIdIfMissing = generatedId,
                    hcpName = rawName,
                    specialty = rawSpecialty,
                    frequencyPerQuarter = effectiveFreq,
                    frequencyWasInvalid = freqInvalid,
                    rawFrequencyText = rawFreq,
                    clinics = clinics,
                    customFields = customFields,
                    rowWarnings = warnings,
                    changeStatus = changeStatus
                )
            )
        }

        return ImportPreviewReport(
            fileName = fileName,
            availableWorksheets = sheets.map { it.sheetName },
            selectedWorksheet = sheet.sheetName,
            availableHeaders = sheet.headers,
            columnMapping = effectiveMappingResult,
            totalDetectedRows = normalizedRows.size,
            newHcpsCount = newCount,
            updatedHcpsCount = updatedCount,
            unchangedHcpsCount = unchangedCount,
            missingOrDuplicateIdsCount = missingOrDupIdCount,
            missingHcpNamesCount = missingNameCount,
            missingOrUnverifiedLocationsCount = unverifiedLocationsCount,
            ambiguousSchedulesCount = ambiguousSchedulesCount,
            invalidFrequencyCount = invalidFrequencyCount,
            potentialDuplicateClinicsCount = duplicateClinicsCount,
            recordsRequiringReviewCount = reviewRequiredCount,
            rows = normalizedRows
        )
    }

    /**
     * Auto-detects whether raw file bytes are an OOXML Excel workbook (.xlsx ZIP archive)
     * or a CSV/TSV text file, and parses them into [ParsedWorkbookSheet] instances.
     */
    fun parseFileBytes(fileName: String, bytes: ByteArray): List<ParsedWorkbookSheet> {
        val isZipHeader = bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()
        return if (isZipHeader || fileName.endsWith(".xlsx", ignoreCase = true)) {
            parseXlsx(ByteArrayInputStream(bytes))
        } else {
            val sheetName = fileName.substringBeforeLast('.').ifBlank { "TargetList" }
            parseCsv(ByteArrayInputStream(bytes), sheetName = sheetName)
        }
    }

    /**
     * Parses CSV bytes into a single ParsedWorkbookSheet.
     */
    fun parseCsv(inputStream: InputStream, sheetName: String = "Sheet1"): List<ParsedWorkbookSheet> {
        val content = inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            .removePrefix("\uFEFF")
        val lines = parseCsvText(content)
        if (lines.isEmpty()) return emptyList()
        val headers = lines.first()
        val rows = lines.drop(1)
        return listOf(ParsedWorkbookSheet(sheetName, headers, rows))
    }

    fun parseCsvText(csvContent: String): List<List<String>> {
        val cleaned = csvContent.removePrefix("\uFEFF")
        val firstLine = cleaned.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val delimiter = when {
            firstLine.count { it == '\t' } > firstLine.count { it == ',' } -> '\t'
            firstLine.count { it == ';' } > firstLine.count { it == ',' } -> ';'
            else -> ','
        }

        val result = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val currentCell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < cleaned.length) {
            val ch = cleaned[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < cleaned.length && cleaned[i + 1] == '"') {
                        currentCell.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == delimiter && !inQuotes -> {
                    currentRow.add(currentCell.toString().trim())
                    currentCell.clear()
                }
                (ch == '\n' || ch == '\r') && !inQuotes -> {
                    if (ch == '\r' && i + 1 < cleaned.length && cleaned[i + 1] == '\n') {
                        i++
                    }
                    currentRow.add(currentCell.toString().trim())
                    currentCell.clear()
                    if (currentRow.any { it.isNotBlank() }) {
                        result.add(currentRow.toList())
                    }
                    currentRow.clear()
                }
                else -> currentCell.append(ch)
            }
            i++
        }
        if (currentCell.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentCell.toString().trim())
            if (currentRow.any { it.isNotBlank() }) {
                result.add(currentRow.toList())
            }
        }
        return result
    }

    fun generateSampleWestCsvText(): String {
        val headers = listOf(
            "target_id",
            "HCP Name",
            "Specialty",
            "Clinic 1 Zone",
            "Clinic 1 gps location",
            "Visiting time in Clinic 1",
            "Clinic 2 Zone",
            "Clinic 2 Location",
            "Visiting time in Clinic 2",
            "Frequency per Quarter"
        )
        val allRows = listOf(headers) + sampleWestTerritoryRows()
        return allRows.joinToString("\n") { row ->
            row.joinToString(",") { cell ->
                if (cell.contains(',') || cell.contains('"') || cell.contains('\n')) {
                    "\"${cell.replace("\"", "\"\"")}\""
                } else {
                    cell
                }
            }
        }
    }

    /**
     * Native, lightweight OOXML (.xlsx) reader using ZipInputStream and XML DOM.
     * Supports sharedStrings.xml, inlineStr, multiple worksheets, and sparse cell references (A1, B1, C1...).
     */
    fun parseXlsx(inputStream: InputStream): List<ParsedWorkbookSheet> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(inputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val buffer = ByteArrayOutputStream()
                    val data = ByteArray(4096)
                    var n: Int
                    while (zis.read(data).also { n = it } != -1) {
                        buffer.write(data, 0, n)
                    }
                    entries[entry.name] = buffer.toByteArray()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val sharedStrings = mutableListOf<String>()
        entries["xl/sharedStrings.xml"]?.let { bytes ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(ByteArrayInputStream(bytes))
            val siNodes = doc.getElementsByTagName("si")
            for (i in 0 until siNodes.length) {
                val si = siNodes.item(i)
                sharedStrings.add(si.textContent ?: "")
            }
        }

        val sheetNames = mutableListOf<Pair<String, String>>() // name to path
        entries["xl/workbook.xml"]?.let { bytes ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(ByteArrayInputStream(bytes))
            val sheetNodes = doc.getElementsByTagName("sheet")
            for (i in 0 until sheetNodes.length) {
                val elem = sheetNodes.item(i)
                val attrs = elem.attributes
                val name = attrs?.getNamedItem("name")?.nodeValue ?: "Sheet${i + 1}"
                val sheetIdx = i + 1
                sheetNames.add(name to "xl/worksheets/sheet$sheetIdx.xml")
            }
        }

        if (sheetNames.isEmpty()) {
            entries.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
                .sorted()
                .forEachIndexed { idx, path -> sheetNames.add("Sheet${idx + 1}" to path) }
        }

        val parsedSheets = mutableListOf<ParsedWorkbookSheet>()
        for ((sheetName, path) in sheetNames) {
            val sheetBytes = entries[path] ?: continue
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(ByteArrayInputStream(sheetBytes))

            // Parse worksheet relationships (xl/worksheets/_rels/sheetN.xml.rels) for hyperlink URLs
            val relIdToTarget = mutableMapOf<String, String>()
            val sheetFileName = path.substringAfterLast('/')
            val relsPath = "xl/worksheets/_rels/$sheetFileName.rels"
            entries[relsPath]?.let { relsBytes ->
                try {
                    val relsDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(ByteArrayInputStream(relsBytes))
                    val relNodes = relsDoc.getElementsByTagName("Relationship")
                    for (i in 0 until relNodes.length) {
                        val relAttrs = relNodes.item(i).attributes
                        val id = relAttrs?.getNamedItem("Id")?.nodeValue
                        val target = relAttrs?.getNamedItem("Target")?.nodeValue
                        if (!id.isNullOrBlank() && !target.isNullOrBlank()) {
                            relIdToTarget[id] = target
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // Map cell ref (e.g., "E8") -> hyperlink URL
            val cellRefToHyperlink = mutableMapOf<String, String>()
            val hyperlinkNodes = doc.getElementsByTagName("hyperlink")
            for (i in 0 until hyperlinkNodes.length) {
                val hlAttrs = hyperlinkNodes.item(i).attributes ?: continue
                val ref = hlAttrs.getNamedItem("ref")?.nodeValue ?: continue
                val rId = hlAttrs.getNamedItem("r:id")?.nodeValue
                    ?: hlAttrs.getNamedItem("id")?.nodeValue
                val loc = hlAttrs.getNamedItem("location")?.nodeValue
                val targetUrl = if (rId != null) relIdToTarget[rId] else loc
                if (!targetUrl.isNullOrBlank()) {
                    cellRefToHyperlink[ref.uppercase(Locale.ROOT)] = targetUrl
                }
            }

            val rowNodes = doc.getElementsByTagName("row")
            val allRows = mutableListOf<List<String>>()
            var maxCol = 0

            val tempRows = mutableListOf<Map<Int, String>>()
            for (r in 0 until rowNodes.length) {
                val rowElem = rowNodes.item(r)
                val cellNodes = rowElem.childNodes
                val colMap = mutableMapOf<Int, String>()
                var fallbackCol = 0
                for (c in 0 until cellNodes.length) {
                    val cellNode = cellNodes.item(c)
                    if (cellNode.nodeName != "c") continue
                    val attrs = cellNode.attributes
                    val ref = attrs?.getNamedItem("r")?.nodeValue
                    val cellType = attrs?.getNamedItem("t")?.nodeValue ?: ""
                    val colIdx = if (ref != null) columnRefToIndex(ref) else fallbackCol
                    fallbackCol = colIdx + 1
                    if (colIdx + 1 > maxCol) maxCol = colIdx + 1

                    var rawVal = ""
                    var formulaText = ""
                    val children = cellNode.childNodes
                    for (k in 0 until children.length) {
                        val ch = children.item(k)
                        if (ch.nodeName == "v" || ch.nodeName == "is") {
                            rawVal = ch.textContent ?: ""
                        } else if (ch.nodeName == "f") {
                            formulaText = ch.textContent ?: ""
                        }
                    }

                    var resolved = when (cellType) {
                        "s" -> {
                            val sIdx = rawVal.toIntOrNull()
                            if (sIdx != null && sIdx in sharedStrings.indices) sharedStrings[sIdx] else rawVal
                        }
                        else -> rawVal
                    }.replace(Regex("""[\r\n]+"""), " | ").replace(Regex("""\t+"""), " ").trim()

                    // If cell has a HYPERLINK("url", "label") formula or an attached <hyperlink>, prefer the URL if resolved text lacks coordinates/URL
                    val hyperlinkUrl = ref?.uppercase(Locale.ROOT)?.let { cellRefToHyperlink[it] }
                        ?: Regex("""HYPERLINK\(\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                            .find(formulaText)?.groupValues?.getOrNull(1)

                    if (!hyperlinkUrl.isNullOrBlank() &&
                        !resolved.startsWith("http", ignoreCase = true)
                    ) {
                        resolved = if (resolved.isBlank()) hyperlinkUrl else "$hyperlinkUrl ($resolved)"
                    }

                    colMap[colIdx] = resolved
                }
                if (colMap.values.any { it.isNotBlank() }) {
                    tempRows.add(colMap)
                }
            }

            for (rowMap in tempRows) {
                val rowList = (0 until maxCol).map { col -> rowMap[col] ?: "" }
                allRows.add(rowList)
            }

            if (allRows.isNotEmpty()) {
                parsedSheets.add(
                    ParsedWorkbookSheet(
                        sheetName = sheetName,
                        headers = allRows.first(),
                        rows = allRows.drop(1)
                    )
                )
            }
        }
        return parsedSheets
    }

    private fun columnRefToIndex(cellRef: String): Int {
        var col = 0
        for (ch in cellRef) {
            if (ch.isLetter()) {
                col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
            } else {
                break
            }
        }
        return (col - 1).coerceAtLeast(0)
    }

    /**
     * Generates a valid .xlsx byte array matching the user's West.xlsx schema for testing & sample export.
     */
    fun generateSampleWestXlsxBytes(): ByteArray {
        val headers = listOf(
            "target_id",
            "HCP Name",
            "Specialty",
            "Clinic 1 Zone",
            "Clinic 1 gps location",
            "Visiting time in Clinic 1",
            "Clinic 2 Zone",
            "Clinic 2 Location",
            "Visiting time in Clinic 2",
            "Frequency per Quarter"
        )
        val rows = sampleWestTerritoryRows()
        return createXlsxBytes("West_Territory", headers, rows)
    }

    fun createXlsxBytes(sheetName: String, headers: List<String>, rows: List<List<String>>): ByteArray {
        return createMultiSheetXlsxBytes(
            listOf(ParsedWorkbookSheet(sheetName = sheetName, headers = headers, rows = rows))
        )
    }

    fun createMultiSheetXlsxBytes(sheets: List<ParsedWorkbookSheet>): ByteArray {
        val safeSheets = sheets.ifEmpty {
            listOf(ParsedWorkbookSheet("Sheet1", emptyList(), emptyList()))
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            fun putXml(name: String, xml: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(xml.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }

            val overridesXml = safeSheets.indices.joinToString("\n") { idx ->
                """  <Override PartName="/xl/worksheets/sheet${idx + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
            }

            putXml(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
$overridesXml
</Types>"""
            )

            val sheetsXml = safeSheets.mapIndexed { idx, s ->
                val sNum = idx + 1
                """    <sheet name="${escapeXml(s.sheetName)}" sheetId="$sNum" r:id="rId$sNum" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/>"""
            }.joinToString("\n")

            putXml(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheets>
$sheetsXml
  </sheets>
</workbook>"""
            )

            for ((sIdx, sheet) in safeSheets.withIndex()) {
                val allRows = if (sheet.headers.isNotEmpty()) listOf(sheet.headers) + sheet.rows else sheet.rows
                val sb = StringBuilder()
                sb.append("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                for ((rIdx, row) in allRows.withIndex()) {
                    val rowNum = rIdx + 1
                    sb.append("""<row r="$rowNum">""")
                    for ((cIdx, cellVal) in row.withIndex()) {
                        val colLetter = indexToColumnLetter(cIdx)
                        val ref = "$colLetter$rowNum"
                        sb.append("""<c r="$ref" t="inlineStr"><is><t>${escapeXml(cellVal)}</t></is></c>""")
                    }
                    sb.append("""</row>""")
                }
                sb.append("""</sheetData></worksheet>""")
                putXml("xl/worksheets/sheet${sIdx + 1}.xml", sb.toString())
            }
        }
        return out.toByteArray()
    }

    fun formatRowsAsCsv(allRows: List<List<String>>): String {
        return allRows.joinToString("\n") { row ->
            row.joinToString(",") { cell ->
                if (cell.contains(',') || cell.contains('"') || cell.contains('\n') || cell.contains('\r')) {
                    "\"${cell.replace("\"", "\"\"")}\""
                } else {
                    cell
                }
            }
        }
    }

    private fun indexToColumnLetter(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + (n % 26)))
            n = (n / 26) - 1
        }
        return sb.toString()
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    fun sampleWestTerritoryRows(): List<List<String>> = listOf(
        listOf(
            "W-1001", "Dr. Ahmed El-Masry", "Cardiology",
            "Sheikh Zayed", "https://www.google.com/maps?q=30.0485,30.9822", "Saturday and Monday 2-6 PM",
            "Mohandessin", "https://www.google.com/maps/@30.0561,31.2019,17z", "Sunday and Tuesday 7 PM-10 PM",
            "8"
        ),
        listOf(
            "W-1002", "Dr. Mona Abdel-Rahman", "Endocrinology",
            "6th of October", "29.9723, 30.9415", "Sat, Mon, Wed, Thu 4-10 PM",
            "Sheikh Zayed", "30.0412, 30.9890", "Sunday and Tuesday 1 PM-5 PM",
            "8"
        ),
        listOf(
            "W-1003", "Dr. Tarek Soliman", "Cardiology",
            "Sheikh Zayed", "30.0510, 30.9750", "Daily 8:30-10 PM except Monday and Friday",
            "", "", "",
            "8"
        ),
        listOf(
            "W-1004", "Dr. Hanan Farouk", "Internal Medicine",
            "Dokki", "https://www.google.com/maps?q=30.0385,31.2125", "Saturday, Sunday, Monday, Tuesday 12-5 PM",
            "Agouza", "30.0530, 31.2140", "Wednesday and Thursday 3-8 PM",
            "6"
        ),
        listOf(
            "W-1005", "Dr. Yasser Mahmoud", "Neurology",
            "Mohandessin", "30.0592, 31.2050", "Saturday, Monday, Wednesday 1-6 PM",
            "Sheikh Zayed", "30.0460, 30.9840", "Sunday, Tuesday, Thursday 5-9 PM",
            "8"
        ),
        listOf(
            "W-1006", "Dr. Salma El-Shazly", "Pediatrics",
            "6th of October", "29.9780, 30.9520", "Daily 11 AM-4 PM except Friday",
            "", "", "",
            "6"
        ),
        listOf(
            "W-1007", "Dr. Karim Rostom", "Cardiology",
            "Sheikh Zayed", "30.0498, 30.9795", "Saturday, Sunday, Tuesday, Thursday 3-8 PM",
            "Dokki", "30.0395, 31.2100", "Monday and Wednesday 6-10 PM",
            "8"
        ),
        listOf(
            "W-1008", "Dr. Nadia Lotfy", "Endocrinology",
            "Mohandessin", "30.0550, 31.1995", "Saturday, Sunday, Monday, Wednesday 2-7 PM",
            "", "", "",
            "8"
        ),
        listOf(
            "W-1009", "Dr. Omar El-Shenawy", "Orthopedics",
            "6th of October", "29.9690, 30.9380", "Saturday, Monday, Tuesday, Thursday 1-6 PM",
            "Haram", "29.9960, 31.1650", "Sunday and Wednesday 6-10 PM",
            "6"
        ),
        listOf(
            "W-1010", "Dr. Sherif Badawy", "Internal Medicine",
            "Sheikh Zayed", "30.0430, 30.9860", "Evening from 8-9 PM EVERY DAY",
            "Mohandessin", "Mohandessin Main Square (Unverified Zone Only)", "Saturday and Tuesday 8-10",
            "8"
        ),
        listOf(
            "W-1011", "Dr. Dina El-Guindi", "Cardiology",
            "Dokki", "30.0410, 31.2080", "Saturday, Monday, Tuesday, Wednesday, Thursday 11 AM-3 PM",
            "", "", "",
            "8"
        ),
        listOf(
            "W-1012", "Dr. Hossam Zaki", "Pulmonology",
            "6th of October", "29.9750, 30.9480", "Saturday, Sunday, Monday, Wednesday, Thursday 4-9 PM",
            "", "", "",
            "6"
        )
    )

    fun customFieldsToJson(fields: Map<String, String>): String {
        val obj = JSONObject()
        for ((k, v) in fields) {
            obj.put(k, v)
        }
        return obj.toString()
    }

    fun jsonToCustomFields(json: String): Map<String, String> {
        if (json.isBlank() || json == "{}") return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k, "").trim()
                if (v.isNotBlank()) {
                    map[k] = v
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
