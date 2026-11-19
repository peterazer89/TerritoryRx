package com.example.domain.ai

import android.util.Log
import com.example.data.model.ChatMessageEntity
import com.example.data.model.DailyPlanWithStops
import com.example.data.model.HcpWithDetails
import com.example.domain.importer.ImportPreviewReport
import com.example.domain.parser.AvailabilityScheduleParser
import com.example.domain.parser.ParsedTimeWindow
import com.example.domain.parser.ParsingMethod
import com.example.domain.parser.ScheduleClassification
import com.example.domain.parser.ScheduleParseResult
import com.example.domain.parser.ScheduleParseStatus
import com.example.domain.parser.ScheduleValidationStatus
import com.example.domain.parser.ScheduleValidator
import com.example.domain.parser.TimeInfoType
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "AiPlannerAssistant"

enum class GeminiChatModelTier(
    val modelId: String,
    val shortTitle: String,
    val subtitle: String
) {
    COMPLEX(
        modelId = "gemini-4-argon",
        shortTitle = "Complex (gemini-4-argon)",
        subtitle = "Deep territory strategy, multi-constraint optimization & clinical call analysis"
    ),
    GENERAL(
        modelId = "gemini-3.8-flash",
        shortTitle = "General (gemini-3.8-flash)",
        subtitle = "Balanced daily planning, route explanations & CSV/Excel import guidance"
    ),
    FAST(
        modelId = "gemini-3.1-flash-lite",
        shortTitle = "Fast (gemini-3.1-flash-lite)",
        subtitle = "Ultra-fast HCP lookups, clinic hours & quick status checks"
    )
}

enum class ChatbotRolePreset(
    val title: String,
    val systemRolePrompt: String
) {
    TERRITORY_STRATEGIST(
        title = "Territory Route Strategist",
        systemRolePrompt = """
            You are the TerritoryRx Senior Pharmaceutical Field Strategy & Route Optimization Advisor.
            Your role is to help medical representatives plan daily and weekly HCP visits across their territory (e.g., Sheikh Zayed, 6th of October, Mohandessin, Dokki, Agouza, Haram), maximize quarterly coverage targets, respect clinic visiting windows, and stay within the 8-hour (480-minute) continuous duty span.
            Always ground your answers in the representative's real Room database facts provided below. Never fabricate coordinates, driving times, or visit history.
        """.trimIndent()
    ),
    MEDICAL_CALL_COACH(
        title = "Medical Pre-Call Coach",
        systemRolePrompt = """
            You are the TerritoryRx Clinical Detailing & Pre-Call Preparation Coach for pharmaceutical medical representatives.
            Your role is to prepare concise, high-impact pre-call briefings tailored to each physician's specialty (Cardiology, Endocrinology, Internal Medicine, Neurology, Pediatrics, Orthopedics, Pulmonology), visit frequency target, and clinic schedule windows.
            Structure your responses with actionable clinical talking points, objection handling, and next-visit scheduling recommendations grounded in the territory database.
        """.trimIndent()
    ),
    DATA_IMPORT_AUDITOR(
        title = "CSV/Excel Data Auditor",
        systemRolePrompt = """
            You are the TerritoryRx Territory Data & Schedule Quality Auditor.
            Your role is to help medical representatives inspect uploaded CSV and Excel (.xlsx) target lists, resolve ambiguous clinic visiting schedules (such as '8-10' without AM/PM or 'Daily except Monday and Friday'), verify missing GPS coordinates, and maintain clean Room database records.
        """.trimIndent()
    )
}

sealed interface ChatUiState {
    data object Idle : ChatUiState
    data class Loading(val modelId: String) : ChatUiState
    data class Streaming(val partialText: String, val modelId: String, val tierLabel: String) : ChatUiState
    data class Success(val finalText: String, val modelId: String) : ChatUiState
    data class Error(val message: String, val attemptedModelId: String) : ChatUiState
}

enum class AiCommandIntent {
    PLAN_DAY,
    PLAN_WEEK,
    SHOW_ALTERNATIVES,
    EXPLAIN_PLAN,
    LIST_AT_RISK_HCPS
}

data class StructuredPlanningCommand(
    val intent: AiCommandIntent,
    val targetVisits: Int? = null,
    val specialtyFilter: String? = null,
    val excludedZone: String? = null,
    val prioritizeOverdue: Boolean = false,
    val explanationText: String = "",
    val sourceLabel: String = "Gemini 3.8 Flash (Structured JSON)"
)

class AiPlannerAssistant {

    private val commandSchema = Schema.obj(
        properties = mapOf(
            "intent" to Schema.enumeration(
                values = listOf("PLAN_DAY", "PLAN_WEEK", "SHOW_ALTERNATIVES", "EXPLAIN_PLAN", "LIST_AT_RISK_HCPS"),
                description = "Primary planning action requested by the representative"
            ),
            "targetVisits" to Schema.integer(description = "Requested daily visit target between 1 and 12"),
            "specialtyFilter" to Schema.string(description = "Medical specialty filter if specified, or empty string"),
            "excludedZone" to Schema.string(description = "Zone to avoid today if specified, or empty string"),
            "prioritizeOverdue" to Schema.boolean(description = "Whether to prioritize overdue HCPs"),
            "assistantSummary" to Schema.string(description = "Concise explanation grounded in optimizer parameters")
        ),
        optionalProperties = listOf("targetVisits", "specialtyFilter", "excludedZone")
    )

    private val scheduleSchema = Schema.obj(
        properties = mapOf(
            "daysOfWeekIso" to Schema.array(
                items = Schema.integer(description = "ISO day of week 1=Mon..7=Sun"),
                description = "List of ISO weekdays"
            ),
            "startMinuteOfDay" to Schema.integer(description = "Start minute from midnight 0..1439"),
            "endMinuteOfDay" to Schema.integer(description = "End minute from midnight 0..1439"),
            "reasoning" to Schema.string(description = "Explanation of how the ambiguous schedule text was interpreted")
        )
    )

    private val excelSheetExtractionSchema = Schema.obj(
        properties = mapOf(
            "parsedSchedules" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "clinicId" to Schema.string(description = "Exact unmodified clinicId from the input clinic object (e.g. W-1001::C1)"),
                        "hcpId" to Schema.string(description = "Exact unmodified hcpId (targetId) from the input clinic object"),
                        "rawText" to Schema.string(description = "Exact raw schedule text from the Excel sheet"),
                        "windows" to Schema.array(
                            items = Schema.obj(
                                properties = mapOf(
                                    "daysOfWeekIso" to Schema.array(
                                        items = Schema.integer(description = "ISO weekday 1=Mon, 2=Tue, 3=Wed, 4=Thu, 5=Fri, 6=Sat, 7=Sun")
                                    ),
                                    "startMinuteOfDay" to Schema.integer(description = "Start minute from midnight 0..1439"),
                                    "endMinuteOfDay" to Schema.integer(description = "End minute from midnight 0..1439"),
                                    "endsNextDay" to Schema.boolean(description = "True only for genuine overnight ranges crossing midnight e.g. 11 PM - 1 AM")
                                ),
                                optionalProperties = listOf("endsNextDay")
                            ),
                            description = "One or more time windows represented by the schedule text"
                        ),
                        "excludedDaysIso" to Schema.array(
                            items = Schema.integer(description = "Excluded ISO weekdays 1..7 if daily except certain days")
                        ),
                        "classification" to Schema.enumeration(
                            values = ScheduleClassification.entries.map { it.name },
                            description = "Exact schedule classification"
                        ),
                        "locationDescription" to Schema.string(description = "Clinic/landmark/area name separated from time if present"),
                        "confidence" to Schema.double(description = "Parser confidence 0.0..1.0"),
                        "ambiguityReason" to Schema.string(description = "If ambiguous or incomplete, explain why; empty otherwise")
                    ),
                    optionalProperties = listOf("excludedDaysIso", "classification", "locationDescription", "confidence", "ambiguityReason")
                ),
                description = "Parsed availability windows ONLY for unresolved clinic objects provided in the prompt, keyed by exact clinicId"
            ),
            "rowEnrichments" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "hcpId" to Schema.string(description = "Exact targetId of the HCP"),
                        "rowNumber" to Schema.integer(description = "Row number from the report"),
                        "specialty" to Schema.string(description = "Normalized medical specialty"),
                        "quarterlyTarget" to Schema.integer(description = "Quarterly target visits between 1 and 24"),
                        "clinic1Zone" to Schema.string(description = "Normalized Clinic 1 zone name"),
                        "clinic2Zone" to Schema.string(description = "Normalized Clinic 2 zone name")
                    ),
                    optionalProperties = listOf("hcpId", "clinic2Zone")
                ),
                description = "AI-normalized row attributes from the Excel sheet"
            ),
            "aiSheetSummary" to Schema.string(
                description = "Concise summary of all data and time formats extracted by AI from the Excel sheet"
            )
        )
    )

    private val commandModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = commandSchema
            }
        )
    }

    private val scheduleModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = scheduleSchema
            }
        )
    }

    private val excelExtractionModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = excelSheetExtractionSchema
            }
        )
    }

    suspend fun interpretNaturalLanguageCommand(
        userPrompt: String,
        currentPlan: DailyPlanWithStops?,
        hcps: List<HcpWithDetails>,
        allowRemoteAi: Boolean = true
    ): Result<StructuredPlanningCommand> {
        val trimmed = userPrompt.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Please enter a command or question."))
        }

        // Build grounded context from actual optimizer output and stored data (Section 15.3)
        val atRiskHcps = hcps.filter {
            it.coverage.riskLevel.name == "HIGH_RISK" || it.coverage.riskLevel.name == "CRITICAL_AT_RISK"
        }
        val overdueHcps = hcps.filter { it.coverage.isOverdue }
        val planContext = if (currentPlan != null) {
            "Current Plan (${currentPlan.plan.variantLabel}) on ${currentPlan.plan.dateIso}: " +
                "${currentPlan.stops.size} stops, ${currentPlan.plan.totalDrivingMinutes}m driving, " +
                "${currentPlan.plan.totalDutySpanMinutes}m duty span. " +
                "Feasibility details: ${currentPlan.plan.infeasibilityReasonsText}. " +
                "Validator: ${currentPlan.plan.validatorReportText}."
        } else {
            "No daily plan generated yet."
        }

        if (allowRemoteAi) {
            try {
                val fullPrompt = """
                    You are a pharmaceutical field planning assistant. Translate the user's request into structured parameters for the deterministic route optimizer, and answer any question using ONLY the grounded facts below. Never invent travel times, coordinates, or visits.
                    Grounded Territory Facts:
                    - Total active HCPs: ${hcps.size}
                    - Overdue HCPs (${overdueHcps.size}): ${overdueHcps.take(6).joinToString { "${it.hcp.name} (${it.hcp.specialty}, ${it.coverage.remainingVisits} rem)" }}
                    - At-Risk HCPs (${atRiskHcps.size}): ${atRiskHcps.take(6).joinToString { "${it.hcp.name} [${it.coverage.riskLevel.label}]" }}
                    - $planContext
                    
                    User command: "$trimmed"
                """.trimIndent()

                val response = withTimeout(60_000L) {
                    commandModel.generateContent(fullPrompt)
                }
                val rawJson = response.text
                if (!rawJson.isNullOrBlank()) {
                    val json = JSONObject(rawJson)
                    val intentStr = json.optString("intent", "PLAN_DAY")
                    val intent = runCatching { AiCommandIntent.valueOf(intentStr) }.getOrDefault(AiCommandIntent.PLAN_DAY)
                    val target = json.optInt("targetVisits", 0).takeIf { it in 1..12 }
                    val spec = json.optString("specialtyFilter", "").takeIf { it.isNotBlank() }
                    val zone = json.optString("excludedZone", "").takeIf { it.isNotBlank() }
                    val prioOverdue = json.optBoolean("prioritizeOverdue", false)
                    val summary = json.optString("assistantSummary", "Translated command into optimizer constraints.")

                    return Result.success(
                        StructuredPlanningCommand(
                            intent = intent,
                            targetVisits = target,
                            specialtyFilter = spec,
                            excludedZone = zone,
                            prioritizeOverdue = prioOverdue,
                            explanationText = summary,
                            sourceLabel = "Firebase AI Logic (gemini-3.8-flash)"
                        )
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "AI command interpretation timed out", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Firebase AI command call failed; using deterministic command parser", e)
            }
        }

        // Deterministic validated parser (keeps app 100% usable when offline or AI unavailable)
        return Result.success(parseCommandDeterministically(trimmed, currentPlan, hcps))
    }

    fun parseCommandDeterministically(
        prompt: String,
        currentPlan: DailyPlanWithStops?,
        hcps: List<HcpWithDetails>
    ): StructuredPlanningCommand {
        val lower = prompt.lowercase(Locale.ROOT)
        val numberWords = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
        )

        var targetVisits: Int? = null
        Regex("""\b(\d{1,2})\s*(?:doctors|hcps|visits)\b""").find(lower)?.let {
            val n = it.groupValues[1].toIntOrNull()
            if (n != null && n in 1..12) targetVisits = n
        }
        if (targetVisits == null) {
            for ((word, num) in numberWords) {
                if (Regex("""\b$word\s+(?:doctors|hcps|visits)\b""").containsMatchIn(lower)) {
                    targetVisits = num
                    break
                }
            }
        }

        val knownSpecialties = listOf(
            "cardiology" to "Cardiology",
            "cardiologist" to "Cardiology",
            "endocrinology" to "Endocrinology",
            "endocrinologist" to "Endocrinology",
            "neurology" to "Neurology",
            "neurologist" to "Neurology",
            "pediatrics" to "Pediatrics",
            "pediatrician" to "Pediatrics",
            "internal medicine" to "Internal Medicine",
            "orthopedics" to "Orthopedics",
            "pulmonology" to "Pulmonology"
        )
        val specialtyFilter = knownSpecialties.firstOrNull { (k, _) -> lower.contains(k) }?.second

        val knownZones = listOf("Sheikh Zayed", "Mohandessin", "Dokki", "6th of October", "Agouza", "Haram", "Maadi", "Zamalek")
        val excludedZone = if (lower.contains("avoid") || lower.contains("exclude") || lower.contains("skip")) {
            knownZones.firstOrNull { z -> lower.contains(z.lowercase(Locale.ROOT)) }
        } else {
            null
        }

        val prioritizeOverdue = lower.contains("overdue") || lower.contains("priority") || lower.contains("at risk")

        val intent = when {
            lower.contains("why") || lower.contains("could not") || lower.contains("couldn't") || lower.contains("explain") ->
                AiCommandIntent.EXPLAIN_PLAN
            lower.contains("risk") || lower.contains("missing their quarterly") || lower.contains("overdue") && lower.contains("which") ->
                AiCommandIntent.LIST_AT_RISK_HCPS
            lower.contains("week") || lower.contains("weekly") ->
                AiCommandIntent.PLAN_WEEK
            lower.contains("alternative") || lower.contains("three") && lower.contains("plan") || lower.contains("options") ->
                AiCommandIntent.SHOW_ALTERNATIVES
            else -> AiCommandIntent.PLAN_DAY
        }

        val explanation = when (intent) {
            AiCommandIntent.EXPLAIN_PLAN -> {
                if (currentPlan == null) {
                    "No daily plan is currently active. Tap 'Plan Today' to run the constraint solver."
                } else {
                    "Grounded Optimizer Explanation for ${currentPlan.plan.dateIso}: " +
                        "${currentPlan.plan.infeasibilityReasonsText} " +
                        "Duty span is ${currentPlan.plan.totalDutySpanMinutes}m / 480m max, " +
                        "with ${currentPlan.plan.totalDrivingMinutes}m driving (${currentPlan.plan.trafficFreshnessLabel}). " +
                        currentPlan.plan.validatorReportText
                }
            }
            AiCommandIntent.LIST_AT_RISK_HCPS -> {
                val atRisk = hcps.filter {
                    it.coverage.riskLevel.name == "HIGH_RISK" || it.coverage.riskLevel.name == "CRITICAL_AT_RISK" || it.coverage.isOverdue
                }.sortedByDescending { it.coverage.riskScore }
                if (atRisk.isEmpty()) {
                    "All active HCPs are currently on track for their quarterly coverage targets."
                } else {
                    "Found ${atRisk.size} overdue/at-risk HCP(s): " +
                        atRisk.take(5).joinToString("; ") {
                            "${it.hcp.name} (${it.hcp.specialty}, rem: ${it.coverage.remainingVisits}/${it.hcp.quarterlyTarget}, ${it.coverage.riskLevel.label})"
                        }
                }
            }
            AiCommandIntent.PLAN_WEEK ->
                "Configured weekly optimizer to allocate visits across working days while clustering zones and prioritizing remaining quarterly targets."
            AiCommandIntent.SHOW_ALTERNATIVES ->
                "Generated up to 3 diversified feasible plans (Plan A: Min Travel, Plan B: Overdue Coverage, Plan C: Low Waiting/High Robustness)."
            AiCommandIntent.PLAN_DAY -> buildString {
                append("Applied validated parameters to the daily route optimizer: ")
                append("target=${targetVisits ?: 8} visits")
                if (specialtyFilter != null) append(", specialty='$specialtyFilter'")
                if (excludedZone != null) append(", excluding zone='$excludedZone'")
                if (prioritizeOverdue) append(", boosting overdue HCPs")
                append(".")
            }
        }

        return StructuredPlanningCommand(
            intent = intent,
            targetVisits = targetVisits,
            specialtyFilter = specialtyFilter,
            excludedZone = excludedZone,
            prioritizeOverdue = prioritizeOverdue,
            explanationText = explanation,
            sourceLabel = "Validated Command Parser"
        )
    }

    /**
     * Proposes a structured schedule interpretation for an ambiguous Excel schedule string (Section 15.2).
     * Never marks the result as confirmed — always returns status = AMBIGUOUS so the user must review and confirm.
     */
    suspend fun proposeAmbiguousScheduleInterpretation(rawScheduleText: String): Result<ScheduleParseResult> {
        try {
            val prompt = """
                Parse this pharmaceutical clinic visiting schedule from Egypt into ISO weekdays (1=Monday..6=Saturday, 7=Sunday) and 24-hour minutes from midnight (e.g., 14:00 = 840, 20:00 = 1200, 22:00 = 1320).
                In Egypt, clinic hours like "8-10" without AM/PM are typically evening hours (8:00 PM to 10:00 PM = 1200 to 1320).
                Raw schedule text: "$rawScheduleText"
            """.trimIndent()

            val response = withTimeout(60_000L) {
                scheduleModel.generateContent(prompt)
            }
            val jsonText = response.text ?: throw IllegalStateException("Empty AI schedule response")
            val json = JSONObject(jsonText)
            val daysArr = json.getJSONArray("daysOfWeekIso")
            val startMin = json.getInt("startMinuteOfDay").coerceIn(0, 1439)
            val endMin = json.getInt("endMinuteOfDay").coerceIn(0, 1439)
            val reasoning = json.optString("reasoning", "AI proposed interpretation; requires user confirmation.")

            if (endMin <= startMin || daysArr.length() == 0) {
                throw IllegalStateException("AI proposed invalid time range ($startMin..$endMin)")
            }

            val windows = mutableListOf<ParsedTimeWindow>()
            for (i in 0 until daysArr.length()) {
                val d = daysArr.getInt(i)
                if (d in 1..7) {
                    windows.add(ParsedTimeWindow(d, startMin, endMin))
                }
            }

            val rawProposal = ScheduleParseResult(
                rawText = rawScheduleText,
                windows = windows,
                status = ScheduleParseStatus.AMBIGUOUS, // Requires explicit user confirmation!
                ambiguityReason = "AI Proposal ($reasoning) — Tap 'Confirm Schedule' to verify.",
                timeInfoType = TimeInfoType.CLINIC_AVAILABILITY_RANGE,
                normalizedDays = windows.map { it.dayOfWeek }.distinct().sorted(),
                exactStartMinute = startMin,
                exactEndMinute = endMin,
                classification = if (windows.map { it.dayOfWeek }.distinct().size == 7) {
                    ScheduleClassification.DAILY_DEFAULT_WITH_TIME
                } else {
                    ScheduleClassification.EXPLICIT_WEEKDAYS_AND_TIME
                },
                parsingMethod = ParsingMethod.AI_ASSISTED,
                parsingConfidence = 0.72
            )
            val validated = ScheduleValidator.validate(rawProposal).copy(
                status = ScheduleParseStatus.AMBIGUOUS,
                ambiguityReason = "AI Proposal ($reasoning) — Review and confirm to save."
            )
            return Result.success(validated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AI schedule disambiguation fallback used", e)
            val det = ScheduleValidator.validate(AvailabilityScheduleParser.parseForExcelImport(rawScheduleText))
            return Result.success(det)
        }
    }

    /**
     * Reads Excel sheet row data using a deterministic-first schedule parser architecture:
     * 1. Deterministic parser runs FIRST on every clinic schedule for weekdays, time ranges,
     *    AM/PM normalization, 24h conversion, daily schedules, and excluded weekdays.
     * 2. Gemini AI (`gemini-3.8-flash`) is invoked ONLY for rows/schedules where deterministic
     *    parsing cannot confidently understand the original text, or to normalize row specialties/zones.
     * 3. AI uncertainty responses NEVER override a deterministically parsed and validated schedule.
     */
    suspend fun enrichImportReportWithAi(
        report: ImportPreviewReport
    ): Result<Pair<ImportPreviewReport, String>> {
        // Stage 1-2: Deterministic schedule parser + Stage 4 Validator run FIRST for every clinic
        val preParsedRows = report.rows.map { row ->
            val detClinics = row.clinics.map { clinic ->
                val src = clinic.originalExcelScheduleText.ifBlank { clinic.rawScheduleText }
                val detRaw = if (clinic.scheduleParseResult.status == ScheduleParseStatus.PARSED_HIGH_CONFIDENCE ||
                    clinic.scheduleParseResult.status == ScheduleParseStatus.CONFIRMED
                ) {
                    clinic.scheduleParseResult
                } else {
                    AvailabilityScheduleParser.parseForExcelImport(
                        rawSchedule = src,
                        siblingClinicResult = null
                    )
                }
                val det = ScheduleValidator.validate(detRaw)
                clinic.copy(
                    originalExcelScheduleText = src,
                    scheduleParseResult = det
                )
            }
            row.copy(clinics = detClinics)
        }

        // Stage 3: Invoke Gemini ONLY for unresolved/complex schedules where deterministic parsing could not resolve cleanly.
        // Section 5: Pass each clinic's source information as a separate, explicitly identified object with exact clinicId and hcpId.
        data class UnresolvedClinicPromptInput(
            val clinicId: String,
            val hcpId: String,
            val clinicIndex: Int,
            val zone: String,
            val sourceColumn: String,
            val rawText: String
        )

        val unresolvedClinicInputs = preParsedRows.flatMap { r ->
            r.clinics.mapNotNull { c ->
                val cid = c.stableClinicId.ifBlank { "${r.targetId}::C${c.clinicIndex}" }
                val raw = c.rawScheduleText.trim()
                if (raw.isNotBlank() &&
                    c.scheduleParseResult.clarificationRequired &&
                    c.scheduleParseResult.classification != ScheduleClassification.NO_SCHEDULE_INFORMATION &&
                    c.scheduleParseResult.timeInfoType != TimeInfoType.BOOKING_OR_CALL_TIME
                ) {
                    UnresolvedClinicPromptInput(
                        clinicId = cid,
                        hcpId = r.targetId,
                        clinicIndex = c.clinicIndex,
                        zone = c.zone,
                        sourceColumn = c.sourceColumn,
                        rawText = raw
                    )
                } else {
                    null
                }
            }
        }
        val validClinicOwners: Map<String, UnresolvedClinicPromptInput> =
            unresolvedClinicInputs.associateBy { it.clinicId }

        val rowDigest = preParsedRows.take(40).joinToString("\n") { r ->
            val clinicsDigest = r.clinics.joinToString(" | ") { c ->
                val cid = c.stableClinicId.ifBlank { "${r.targetId}::C${c.clinicIndex}" }
                val stateLabel = if (c.scheduleParseResult.clarificationRequired) "NEEDS_AI_HELP" else "DETERMINISTIC_OK"
                "Clinic[clinicId='$cid', hcpId='${r.targetId}', clinicIndex=${c.clinicIndex}, zone='${c.zone}', col='${c.sourceColumn}', status=$stateLabel, sched='${c.rawScheduleText}']"
            }
            val customStr = if (r.customFields.isNotEmpty()) " custom=${r.customFields}" else ""
            "Row#${r.rowNumber}: hcpId='${r.targetId}', name='${r.hcpName}', spec='${r.specialty}', qTarget=${r.frequencyPerQuarter}, $clinicsDigest$customStr"
        }

        val aiScheduleProposalByClinicId = mutableMapOf<String, ScheduleParseResult>()
        val aiRowSpecMap = mutableMapOf<Int, String>()
        val aiRowTargetMap = mutableMapOf<Int, Int>()
        val aiRowC1ZoneMap = mutableMapOf<Int, String>()
        val aiRowC2ZoneMap = mutableMapOf<Int, String>()
        var aiSummaryText = ""

        if (unresolvedClinicInputs.isNotEmpty() || preParsedRows.any { it.specialty.isBlank() || it.clinics.any { c -> c.zone.isBlank() || c.zone == "Unspecified Zone" } }) {
            try {
                val unresolvedObjectsList = unresolvedClinicInputs.take(45).joinToString("\n") { item ->
                    "- { \"clinicId\": \"${item.clinicId}\", \"hcpId\": \"${item.hcpId}\", \"clinicIndex\": ${item.clinicIndex}, \"zone\": \"${item.zone}\", \"sourceColumn\": \"${item.sourceColumn}\", \"rawText\": \"${item.rawText}\" }"
                }
                val prompt = """
                    You are an expert medical representative Excel workbook & clinic schedule parser for Egypt (English & Arabic).
                    Deterministic rules already parsed all standard formats. Focus ONLY on the unresolved clinic objects below:
                    STRICT RULES:
                    1. Explicit weekdays present -> include ONLY those explicit weekdays. Never add extra weekdays.
                    2. Valid unambiguous time range WITHOUT weekdays -> default to EVERY DAY (ISO [1,2,3,4,5,6,7]) and classification=DAILY_DEFAULT_WITH_TIME.
                    3. Excluded days ("except Friday and Saturday" / "ما عدا ...") -> include everyday minus excluded days.
                    4. Single clock time with NO end time ("8 AM" alone) or vague text ("Morning", "8-10" without AM/PM) or call instructions -> DO NOT invent an end time or guess AM/PM! Leave windows empty and explain ambiguityReason.
                    5. CRITICAL CLINIC ASSOCIATION RULE: Every item in parsedSchedules MUST return the exact unmodified `clinicId` and `hcpId` from the input clinic object. Never invent, change, swap, or substitute `clinicId` values between Clinic 1 and Clinic 2.
                    - ISO weekdays: 1=Monday, 2=Tuesday, 3=Wednesday, 4=Thursday, 5=Friday, 6=Saturday, 7=Sunday.
                    - 12 AM = 0 minutes, 12 PM = 720, 2 PM = 840, 8 PM = 1200, 10 PM = 1320. Only set endsNextDay=true for genuine late-night ranges like 11 PM–1 AM.

                    Unresolved Clinic Schedule Objects (${unresolvedClinicInputs.size}):
                    $unresolvedObjectsList

                    Excel Sheet Rows:
                    $rowDigest
                """.trimIndent()

                val response = withTimeout(18_000L) {
                    excelExtractionModel.generateContent(prompt)
                }
                val rawJson = response.text
                if (!rawJson.isNullOrBlank()) {
                    val json = JSONObject(rawJson)
                    aiSummaryText = json.optString("aiSheetSummary", "")

                    val schedArr = json.optJSONArray("parsedSchedules")
                    if (schedArr != null) {
                        for (i in 0 until schedArr.length()) {
                            val item = schedArr.optJSONObject(i) ?: continue
                            val returnedClinicId = item.optString("clinicId", "").trim()
                            val returnedHcpId = item.optString("hcpId", "").trim()
                            val expectedInput = validClinicOwners[returnedClinicId]
                            // Section 5: Validate returned clinicId and hcpId against original input before saving.
                            if (expectedInput == null) {
                                Log.w(TAG, "Rejected AI schedule with unknown or invented clinicId='$returnedClinicId'")
                                continue
                            }
                            if (returnedHcpId.isNotBlank() && returnedHcpId != expectedInput.hcpId) {
                                Log.w(TAG, "Rejected AI schedule with mismatched hcpId='$returnedHcpId' for clinicId='$returnedClinicId' (expected '${expectedInput.hcpId}')")
                                continue
                            }
                            val rawText = expectedInput.rawText
                            val windowsJson = item.optJSONArray("windows")
                            val parsedWins = mutableListOf<ParsedTimeWindow>()
                            if (windowsJson != null) {
                                for (wIdx in 0 until windowsJson.length()) {
                                    val wObj = windowsJson.optJSONObject(wIdx) ?: continue
                                    val startMin = wObj.optInt("startMinuteOfDay", -1).coerceIn(0, 1439)
                                    val endMin = wObj.optInt("endMinuteOfDay", -1).coerceIn(0, 1439)
                                    val endsNextDay = wObj.optBoolean("endsNextDay", false)
                                    val daysJson = wObj.optJSONArray("daysOfWeekIso") ?: continue
                                    val validEnd = if (endsNextDay) (endMin != startMin) else (endMin > startMin)
                                    if (!validEnd) continue
                                    for (dIdx in 0 until daysJson.length()) {
                                        val d = daysJson.optInt(dIdx, 0)
                                        if (d in 1..7) {
                                            parsedWins.add(ParsedTimeWindow(d, startMin, endMin, endsNextDay))
                                        }
                                    }
                                }
                            }

                            val excludedArr = item.optJSONArray("excludedDaysIso")
                            val excludedDays = buildList {
                                if (excludedArr != null) {
                                    for (eIdx in 0 until excludedArr.length()) {
                                        val d = excludedArr.optInt(eIdx, 0)
                                        if (d in 1..7) add(d)
                                    }
                                }
                            }.distinct()

                            val clsName = item.optString("classification", "")
                            val classification = ScheduleClassification.entries.firstOrNull { it.name == clsName }
                                ?: if (parsedWins.isEmpty()) ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION
                                else if (excludedDays.isNotEmpty()) ScheduleClassification.DAILY_WITH_EXCEPTIONS
                                else if (parsedWins.map { it.dayOfWeek }.distinct().size == 7) ScheduleClassification.DAILY_DEFAULT_WITH_TIME
                                else ScheduleClassification.EXPLICIT_WEEKDAYS_AND_TIME

                            val locDesc = item.optString("locationDescription", "").trim()
                            val conf = item.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
                            val ambReason = item.optString("ambiguityReason", "").trim()

                            val distinctWins = parsedWins.distinct()
                            val candidateResult = ScheduleParseResult(
                                rawText = rawText,
                                windows = distinctWins,
                                status = if (distinctWins.isNotEmpty() && conf >= 0.78 && ambReason.isBlank()) {
                                    ScheduleParseStatus.PARSED_HIGH_CONFIDENCE
                                } else {
                                    ScheduleParseStatus.AMBIGUOUS
                                },
                                ambiguityReason = ambReason,
                                timeInfoType = if (distinctWins.isNotEmpty()) TimeInfoType.CLINIC_AVAILABILITY_RANGE else TimeInfoType.REQUIRES_MANUAL_CLARIFICATION,
                                normalizedDays = distinctWins.map { it.dayOfWeek }.distinct().sorted(),
                                exactStartMinute = distinctWins.minOfOrNull { it.startMinuteOfDay },
                                exactEndMinute = distinctWins.maxOfOrNull { it.endMinuteOfDay },
                                classification = classification,
                                parsingMethod = ParsingMethod.AI_ASSISTED,
                                parsingConfidence = conf,
                                excludedDays = excludedDays,
                                locationDescription = locDesc
                            )
                            // Stage 4: Programmatic validation gate
                            val validatedProposal = ScheduleValidator.validate(candidateResult)
                            aiScheduleProposalByClinicId[returnedClinicId] = validatedProposal
                        }
                    }

                    val rowsArr = json.optJSONArray("rowEnrichments")
                    if (rowsArr != null) {
                        for (i in 0 until rowsArr.length()) {
                            val item = rowsArr.optJSONObject(i) ?: continue
                            val rNum = item.optInt("rowNumber", -1)
                            if (rNum <= 0) continue
                            item.optString("specialty", "").trim().takeIf { it.isNotBlank() }?.let {
                                aiRowSpecMap[rNum] = it
                            }
                            item.optInt("quarterlyTarget", 0).takeIf { it in 1..60 }?.let {
                                aiRowTargetMap[rNum] = it
                            }
                            item.optString("clinic1Zone", "").trim().takeIf { it.isNotBlank() }?.let {
                                aiRowC1ZoneMap[rNum] = it
                            }
                            item.optString("clinic2Zone", "").trim().takeIf { it.isNotBlank() }?.let {
                                aiRowC2ZoneMap[rNum] = it
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "AI Excel enrichment skipped remote call; deterministic parser results preserved", e)
            }
        }

        // Apply AI extractions while strictly guaranteeing AI NEVER overrides deterministically validated schedules
        var updatedAmbiguousCount = 0
        var updatedReviewCount = 0

        val enrichedRows = preParsedRows.map { row ->
            val newSpecialty = aiRowSpecMap[row.rowNumber] ?: row.specialty
            val newFreq = aiRowTargetMap[row.rowNumber] ?: row.frequencyPerQuarter

            val updatedClinics = row.clinics.map { clinic ->
                val clinicKey = clinic.stableClinicId.ifBlank { "${row.targetId}::C${clinic.clinicIndex}" }
                val rawAiZone = when (clinic.clinicIndex) {
                    1 -> aiRowC1ZoneMap[row.rowNumber]
                    2 -> aiRowC2ZoneMap[row.rowNumber]
                    else -> null
                } ?: clinic.zone
                val aiZone = com.example.data.model.BrickNormalizer.splitCompositeBrickName(rawAiZone)
                    .firstOrNull()
                    ?: com.example.data.model.BrickNormalizer.normalizeBrickName(rawAiZone).ifBlank { clinic.zone }

                // Deterministic validated result is NEVER overridden by AI; AI proposal is matched strictly by clinicId
                val deterministicResult = clinic.scheduleParseResult
                val aiProposal = aiScheduleProposalByClinicId[clinicKey]

                // Adopt AI-assisted validated result ONLY when deterministic failed AND AI passed Stage 4 validation with high confidence
                val effectiveResult = if (!deterministicResult.clarificationRequired) {
                    deterministicResult
                } else if (aiProposal != null &&
                    aiProposal.validationStatus == ScheduleValidationStatus.VALID &&
                    aiProposal.windows.isNotEmpty() &&
                    aiProposal.parsingConfidence >= 0.82 &&
                    deterministicResult.classification != ScheduleClassification.PARTIALLY_PARSED &&
                    deterministicResult.timeInfoType != TimeInfoType.BOOKING_OR_CALL_TIME
                ) {
                    aiProposal
                } else if (aiProposal != null && aiProposal.locationDescription.isNotBlank() && deterministicResult.locationDescription.isBlank()) {
                    deterministicResult.copy(locationDescription = aiProposal.locationDescription)
                } else {
                    deterministicResult
                }

                val aiInterpretedSummary = when {
                    !effectiveResult.clarificationRequired -> {
                        AvailabilityScheduleParser.formatWindowsSummary(
                            effectiveResult.toEntities(0L, isConfirmed = true)
                        ).joinToString(" | ")
                    }
                    aiProposal != null && aiProposal.windows.isNotEmpty() -> {
                        val tempEntities = aiProposal.toEntities(0L, isConfirmed = false)
                        "AI Suggestion: " + AvailabilityScheduleParser.formatWindowsSummary(tempEntities).joinToString(" | ")
                    }
                    else -> {
                        "Needs clarification"
                    }
                }

                clinic.copy(
                    zone = aiZone,
                    label = if (aiZone.isNotBlank() && aiZone != "Unspecified Zone") {
                        "Clinic ${clinic.clinicIndex} ($aiZone)"
                    } else {
                        clinic.label
                    },
                    originalExcelScheduleText = clinic.originalExcelScheduleText.ifBlank { clinic.rawScheduleText },
                    aiInterpretedScheduleText = aiInterpretedSummary,
                    scheduleParseResult = effectiveResult,
                    stableClinicId = clinicKey
                )
            }

            val rowAmbCount = updatedClinics.count { it.scheduleParseResult.clarificationRequired }
            updatedAmbiguousCount += rowAmbCount
            val refreshedWarnings = if (rowAmbCount == 0) {
                row.rowWarnings.filterNot { it.contains("schedule needs confirmation", ignoreCase = true) }
            } else {
                row.rowWarnings
            }
            if (refreshedWarnings.isNotEmpty() || rowAmbCount > 0) {
                updatedReviewCount++
            }

            row.copy(
                specialty = newSpecialty,
                frequencyPerQuarter = newFreq,
                clinics = updatedClinics,
                rowWarnings = refreshedWarnings
            )
        }

        val enrichedReport = report.copy(
            ambiguousSchedulesCount = updatedAmbiguousCount,
            recordsRequiringReviewCount = updatedReviewCount,
            rows = enrichedRows
        )

        val totalWindows = enrichedRows.sumOf { r -> r.clinics.sumOf { it.scheduleParseResult.windows.size } }
        val totalClinics = enrichedRows.sumOf { it.clinics.size }
        val finalSummary = aiSummaryText.ifBlank {
            "Audited ${enrichedReport.totalDetectedRows} HCP rows and $totalClinics clinics across '${enrichedReport.fileName}': $totalWindows confirmed time windows parsed from Excel, $updatedAmbiguousCount schedule(s) requiring manual confirmation."
        }

        return Result.success(enrichedReport to finalSummary)
    }

    /**
     * Selects the appropriate Gemini model tier based on task complexity when autoRoute is enabled:
     * - gemini-4-argon for complex multi-constraint strategy, weekly optimization, or deep clinical reasoning
     * - gemini-3.8-flash for general planning, CSV/Excel import inspection, and route explanations
     * - gemini-3.1-flash-lite for fast lookups, quick status checks, and clinic hours queries
     */
    fun selectModelTierForPrompt(
        prompt: String,
        preferredTier: GeminiChatModelTier,
        autoRouteByComplexity: Boolean
    ): GeminiChatModelTier {
        if (!autoRouteByComplexity) return preferredTier
        val lower = prompt.lowercase(Locale.ROOT)
        return when {
            lower.contains("strategy") ||
                lower.contains("complex") ||
                lower.contains("analyze") ||
                lower.contains("weekly") ||
                lower.contains("pre-call") ||
                lower.contains("briefing") ||
                lower.contains("compare") ||
                lower.contains("root cause") ||
                prompt.length > 120 -> GeminiChatModelTier.COMPLEX
            lower.contains("quick") ||
                lower.contains("fast") ||
                lower.contains("hours for") ||
                lower.contains("when is") ||
                lower.contains("where is") ||
                (prompt.length < 42 && !lower.contains("plan") && !lower.contains("why")) -> GeminiChatModelTier.FAST
            else -> GeminiChatModelTier.GENERAL
        }
    }

    fun buildGroundedSystemInstruction(
        rolePreset: ChatbotRolePreset,
        selectedDateIso: String,
        currentPlan: DailyPlanWithStops?,
        hcps: List<HcpWithDetails>,
        latestImportReport: ImportPreviewReport?
    ): String {
        val overdueHcps = hcps.filter { it.coverage.isOverdue || it.coverage.riskScore >= 45.0 }
            .sortedByDescending { it.coverage.riskScore }
        val ambiguousClinics = hcps.flatMap { h ->
            h.clinics.filter { !it.clinic.userConfirmedSchedule }.map { "${h.hcp.name} (${it.clinic.zone}: '${it.clinic.rawScheduleText}')" }
        }
        val planSummary = if (currentPlan != null) {
            val stopSummary = currentPlan.stops.joinToString("; ") { s ->
                "#${s.stopOrder} ${s.hcpName} (${s.specialty}, ${s.zone}) ${AvailabilityScheduleParser.formatMinuteOfDay(s.visitStartMinute)}-${AvailabilityScheduleParser.formatMinuteOfDay(s.visitEndMinute)} [${s.executionStatus}]"
            }
            "Active Route on $selectedDateIso (${currentPlan.plan.variantLabel}): ${currentPlan.stops.size} stops, " +
                "${currentPlan.plan.totalDrivingMinutes}m driving, ${currentPlan.plan.totalDutySpanMinutes}m duty span. " +
                "Stops: $stopSummary. Diagnostics: ${currentPlan.plan.infeasibilityReasonsText}"
        } else {
            "No active route plan generated for $selectedDateIso yet."
        }

        val importSummary = if (latestImportReport != null) {
            "Latest CSV/Excel Import ('${latestImportReport.fileName}', sheet '${latestImportReport.selectedWorksheet}'): " +
                "${latestImportReport.totalDetectedRows} rows (${latestImportReport.newHcpsCount} new, ${latestImportReport.updatedHcpsCount} updated, " +
                "${latestImportReport.ambiguousSchedulesCount} ambiguous schedules, ${latestImportReport.missingOrUnverifiedLocationsCount} unverified locations)."
        } else {
            "No pending import preview."
        }

        val hcpRoster = hcps.take(15).joinToString("\n") { item ->
            val clinicInfo = item.clinics.joinToString(" | ") { c ->
                "${c.clinic.label}: '${c.clinic.rawScheduleText}' (${c.clinic.geocodingStatus})"
            }
            "- ${item.hcp.targetId}: ${item.hcp.name} (${item.hcp.specialty}), Target=${item.coverage.completedVisitsThisQuarter}/${item.hcp.quarterlyTarget}, " +
                "Risk=${item.coverage.riskScore.toInt()}/100 (${item.coverage.riskLevel.label}), Clinics=[$clinicInfo]"
        }

        return """
            ${rolePreset.systemRolePrompt}

            === MANDATORY MOBILE FORMATTING RULES (CRITICAL) ===
            1. DO NOT OUTPUT MARKDOWN TABLES. Do NOT use pipe characters ('|') or '|:---|' separator lines. Mobile phone screens are narrow, and tables wrap into an illegible, messy jumble.
            2. When listing or analyzing doctors (e.g., highest-risk HCPs, daily schedules, or territory audits), ALWAYS format each doctor as a clean, structured doctor card using bullet points and emojis:
               • **Doctor Full Name** (Specialty • Territory Zone)
                 - 🎯 Target: Completed/Total visits (Risk: X/100 • Status)
                 - 📍 Clinic: Clinic Label & Area
                 - 🕒 Visiting Hours: Exact window (e.g., 'Sat: 1:30 PM - 10:00 PM')
                 - 🗓️ Day Availability: e.g., '✅ Available on selected date' or '❌ Closed on selected date (Saturday Only)'
                 - 💡 Action: Field rep strategy advice
            3. Use generous line breaks between doctors so it is comfortable and quick to read on a handheld device.

            === LIVE ROOM DATABASE & TERRITORY CONTEXT ===
            Selected Date: $selectedDateIso
            Total Active HCPs in Room Database: ${hcps.size}
            High-Risk / Overdue HCPs (${overdueHcps.size}): ${overdueHcps.take(8).joinToString { "${it.hcp.name} (Risk ${it.coverage.riskScore.toInt()})" }}
            Clinics with Ambiguous Schedules (${ambiguousClinics.size}): ${ambiguousClinics.take(6).joinToString()}
            $planSummary
            $importSummary

            === HCP TARGET LIST ROSTER ===
            $hcpRoster
        """.trimIndent()
    }

    /**
     * Executes a multi-turn streaming chat turn using Firebase AI Logic (`startChat` + `sendMessageStream`)
     * with a 60-second coroutine timeout, full conversation history, and role-specific `systemInstruction`.
     */
    suspend fun streamMultiTurnChat(
        userMessage: String,
        priorHistory: List<ChatMessageEntity>,
        modelTier: GeminiChatModelTier,
        rolePreset: ChatbotRolePreset,
        selectedDateIso: String,
        currentPlan: DailyPlanWithStops?,
        hcps: List<HcpWithDetails>,
        latestImportReport: ImportPreviewReport?,
        onStreamingChunk: (String) -> Unit
    ): Result<String> {
        val systemInstructionText = buildGroundedSystemInstruction(
            rolePreset = rolePreset,
            selectedDateIso = selectedDateIso,
            currentPlan = currentPlan,
            hcps = hcps,
            latestImportReport = latestImportReport
        )

        val historyContents = priorHistory.takeLast(24).map { msg ->
            val normalizedRole = if (msg.role == "model") "model" else "user"
            content(role = normalizedRole) {
                text(msg.content)
            }
        }

        val generativeModel = Firebase.ai.generativeModel(
            modelName = modelTier.modelId,
            systemInstruction = content {
                text(systemInstructionText)
            }
        )

        val chat = generativeModel.startChat(history = historyContents)
        val accumulated = StringBuilder()

        return try {
            withTimeout(60_000L) {
                chat.sendMessageStream(userMessage).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        onStreamingChunk(accumulated.toString())
                    }
                }
            }
            val finalReply = accumulated.toString().trim()
            if (finalReply.isNotEmpty()) {
                Result.success(finalReply)
            } else {
                Result.failure(IllegalStateException("Model (${modelTier.modelId}) returned an empty response."))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Multi-turn chat timed out on model ${modelTier.modelId}", e)
            Result.failure(IllegalStateException("Generation timed out after 60 seconds (${modelTier.modelId}). Please try again."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Multi-turn chat failed on model ${modelTier.modelId}", e)
            Result.failure(e)
        }
    }

    /**
     * Generates a comprehensive, executive AI Route Strategy & Pre-Call Briefing for the Planner tab.
     * Combines route sequence analysis, brick clustering, specialty call tactics, and coverage metrics.
     */
    suspend fun generateDailyPlanningBriefing(
        selectedDateIso: String,
        plan: DailyPlanWithStops,
        hcps: List<HcpWithDetails>,
        targetVisits: Int,
        shiftTitle: String,
        selectedBricks: Set<String>
    ): String {
        val stopsCount = plan.stops.size
        val dutySpanHours = "%.1f".format(Locale.US, plan.plan.totalDutySpanMinutes / 60.0)
        val driveMinutes = plan.plan.totalDrivingMinutes
        val distanceKm = plan.plan.totalDistanceKm
        val brickLabel = if (selectedBricks.isEmpty()) "All Territory Bricks" else selectedBricks.joinToString(", ")

        val stopsRoster = plan.stops.joinToString("\n") { s ->
            "- #${s.stopOrder} Dr. ${s.hcpName} (${s.specialty}) at ${s.zone}: Visit ${AvailabilityScheduleParser.formatMinuteOfDay(s.visitStartMinute)}–${AvailabilityScheduleParser.formatMinuteOfDay(s.visitEndMinute)} (Clinic window: ${AvailabilityScheduleParser.formatMinuteOfDay(s.windowStartMinute)}–${AvailabilityScheduleParser.formatMinuteOfDay(s.windowEndMinute)}), Priority: ${s.priorityScore}, Target remaining: ${s.remainingQuarterlyVisits}"
        }

        val prompt = """
            You are the TerritoryRx Senior Pharmaceutical Field Strategy Advisor.
            Generate an executive strategic briefing for the medical representative's route on $selectedDateIso.
            
            WORK SETTINGS & ROUTE PARAMETERS:
            - Selected Shift: $shiftTitle
            - Target Visits: $targetVisits ($stopsCount planned)
            - Selected Bricks/Zones: $brickLabel
            - Total Drive Time: ${driveMinutes}m (${distanceKm} km)
            - Total Shift Duty Span: ${plan.plan.totalDutySpanMinutes}m (~$dutySpanHours hours)
            
            PLANNED STOPS:
            $stopsRoster
            
            FORMAT REQUIREMENTS:
            - DO NOT use markdown tables or pipe (|) columns!
            - Structure with clean sections:
              1. 🎯 **Executive Route Strategy** (zone clustering, sequence rationale, duty efficiency)
              2. 📋 **Doctor Call Objectives & Clinical Focus** (for the top 3-4 physicians, give their specialty focus, key clinical detailing point, and suggested action)
              3. ⏱️ **Timing & Shift Efficiency** (advice for arriving on time within clinic windows)
              4. 📈 **Territory Coverage Impact** (quarterly goal progress and high-priority recovery)
            - Keep tone professional, motivational, and concise.
        """.trimIndent()

        return try {
            withTimeout(25_000L) {
                val model = Firebase.ai.generativeModel(
                    modelName = GeminiChatModelTier.GENERAL.modelId,
                    systemInstruction = content {
                        text("You are an expert pharmaceutical field operations and territory route strategist. Provide clear, mobile-optimized clinical advice without markdown tables.")
                    }
                )
                val response = model.generateContent(prompt)
                val reply = response.text?.trim()
                if (!reply.isNullOrBlank()) {
                    reply
                } else {
                    buildGroundedFallbackBriefing(selectedDateIso, plan, targetVisits, shiftTitle, selectedBricks)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI planning briefing used intelligent local strategy synthesizer", e)
            buildGroundedFallbackBriefing(selectedDateIso, plan, targetVisits, shiftTitle, selectedBricks)
        }
    }

    private fun buildGroundedFallbackBriefing(
        selectedDateIso: String,
        plan: DailyPlanWithStops,
        targetVisits: Int,
        shiftTitle: String,
        selectedBricks: Set<String>
    ): String {
        val stops = plan.stops
        val brickText = if (selectedBricks.isEmpty()) "all territory sectors" else selectedBricks.joinToString(", ")
        val dutyHours = "%.1f".format(Locale.US, plan.plan.totalDutySpanMinutes / 60.0)

        val sb = StringBuilder()
        sb.append("### 🎯 Executive Route Strategy\n")
        sb.append("• **Sequence Optimization**: Today's route organizes **${stops.size} visits** across $brickText during the **$shiftTitle** shift.\n")
        sb.append("• **Drive Efficiency**: Total travel is **${plan.plan.totalDrivingMinutes}m** across **${plan.plan.totalDistanceKm} km**, maintaining a tight continuous duty span of **${plan.plan.totalDutySpanMinutes}m (~$dutyHours hours)** without broken split shifts.\n")
        sb.append("• **Geographic Clustering**: Stops are sequenced geographically to minimize backtracking across zones.\n\n")

        sb.append("### 📋 Doctor Call Objectives & Pre-Call Preparation\n")
        stops.take(4).forEach { s ->
            val clinicalTactic = when (s.specialty.lowercase(Locale.ROOT)) {
                "cardiology" -> "Present latest cardiovascular outcomes trial data and primary prevention efficacy."
                "internal medicine" -> "Emphasize patient tolerability profile, once-daily adherence, and broad metabolic indications."
                "endocrinology" -> "Highlight glycemic control metrics, HbA1c reductions, and renal safety parameters."
                "neurology" -> "Discuss rapid symptom relief, neuro-protective profiles, and minimal drug-drug interactions."
                "pediatrics" -> "Focus on palatable formulations, safety indices, and pediatric dosing convenience."
                else -> "Review quarterly prescription trends, reinforce clinical guidelines, and secure formulary commitment."
            }
            sb.append("• **Dr. ${s.hcpName}** (${s.specialty} • ${s.zone})\n")
            sb.append("  - 🕒 Planned Visit: ${AvailabilityScheduleParser.formatMinuteOfDay(s.visitStartMinute)} – ${AvailabilityScheduleParser.formatMinuteOfDay(s.visitEndMinute)} (Clinic window: ${AvailabilityScheduleParser.formatMinuteOfDay(s.windowStartMinute)} – ${AvailabilityScheduleParser.formatMinuteOfDay(s.windowEndMinute)})\n")
            sb.append("  - 🎯 Priority: Score ${s.priorityScore}/100 • ${s.remainingQuarterlyVisits} visit(s) remaining this quarter\n")
            sb.append("  - 💡 Detailing Focus: $clinicalTactic\n\n")
        }

        sb.append("### ⏱️ Shift & Timing Insights\n")
        sb.append("• **Shift Window**: Operating strictly within **$shiftTitle**, with initial departure at **${AvailabilityScheduleParser.formatMinuteOfDay(stops.firstOrNull()?.departureFromPrevMinute ?: 1020)}**.\n")
        sb.append("• **Clinic Alignment**: All ${stops.size} visits are timed to align with physician consultation hours, eliminating unproductive waiting time.\n\n")

        sb.append("### 📈 Territory Coverage Impact\n")
        val highPriorityCount = stops.count { it.priorityScore >= 75 }
        val quarterlyGain = stops.count { it.remainingQuarterlyVisits > 0 }
        sb.append("• Completing this schedule delivers **+$quarterlyGain completed visits** towards quarterly coverage quotas.\n")
        sb.append("• Successfully engages **$highPriorityCount top-tier priority prescribers** in the target territory.")

        return sb.toString()
    }
}
