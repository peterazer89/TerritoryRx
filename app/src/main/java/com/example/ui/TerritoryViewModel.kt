package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.model.AvailabilityWindowEntity
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ClinicEntity
import com.example.data.model.DailyPlanWithStops
import com.example.data.model.HcpEntity
import com.example.data.model.HcpWithDetails
import com.example.data.model.ImportLogEntity
import com.example.data.model.ImportMappingProfileEntity
import com.example.data.model.EMPTY_PREFERRED_SPECIALTIES_MESSAGE
import com.example.data.model.PlannedStopEntity
import com.example.data.model.SpecialtyNormalizer
import com.example.data.model.SyncConflict
import com.example.data.model.WEEKLY_HCP_RESTRICTION_MESSAGE
import com.example.data.model.WeeklyHcpBlockInfo
import com.example.data.model.WorkSettings
import com.example.data.repository.CloudSyncRepository
import com.example.data.repository.ScheduleMigrationPreview
import com.example.data.repository.TerritoryRepository
import com.example.domain.parser.ScheduleValidator
import com.example.domain.ai.AiCommandIntent
import com.example.domain.ai.AiPlannerAssistant
import com.example.domain.ai.ChatUiState
import com.example.domain.ai.ChatbotRolePreset
import com.example.domain.ai.GeminiChatModelTier
import com.example.domain.ai.StructuredPlanningCommand
import com.example.domain.importer.CanonicalField
import com.example.domain.importer.ExcelTerritoryImporter
import com.example.domain.importer.ImportMode
import com.example.domain.importer.ImportPreviewReport
import com.example.domain.optimizer.OptimizationOutcome
import com.example.domain.optimizer.RouteOptimizerEngine
import com.example.domain.optimizer.WeeklyPlanResult
import com.example.domain.parser.AvailabilityScheduleParser
import com.example.domain.parser.ScheduleParseResult
import com.example.domain.parser.ScheduleParseStatus
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import android.util.Log
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ShiftPreset(
    val label: String,
    val startMinute: Int,
    val endMinute: Int,
    val timingDisplay: String
) {
    AFTERNOON_EVENING("☀️ 1 PM – 9 PM", 13 * 60, 21 * 60, "1:00 PM – 9:00 PM"),
    EVENING("🌙 5 PM – 10 PM", 17 * 60, 22 * 60, "5:00 PM – 10:00 PM"),
    AFTERNOON("🌤️ 1 PM – 6 PM", 13 * 60, 18 * 60, "1:00 PM – 6:00 PM"),
    MORNING("🌅 9 AM – 2 PM", 9 * 60, 14 * 60, "9:00 AM – 2:00 PM"),
    CUSTOM("✍️ Custom", 13 * 60, 21 * 60, "Custom Hours")
}

data class UiBannerMessage(
    val title: String,
    val message: String,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

class TerritoryViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val cloudSyncRepo: CloudSyncRepository? = runCatching {
        val firestore = Firebase.firestore(
            application.getString(R.string.firestore_database_id)
        )
        CloudSyncRepository(firestore)
    }.getOrNull()
    private val aiAssistant = AiPlannerAssistant()

    val repository = TerritoryRepository(
        context = application,
        dao = database.territoryDao(),
        hcpDao = database.hcpDao(),
        cloudSyncRepository = cloudSyncRepo
    )

    val workSettings: StateFlow<WorkSettings> = repository.workSettingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WorkSettings()
    )

    val hcpsWithDetails: StateFlow<List<HcpWithDetails>> = repository.hcpsWithDetailsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allDailyPlans: StateFlow<List<DailyPlanWithStops>> = repository.dailyPlansWithStopsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val importLogs: StateFlow<List<ImportLogEntity>> = repository.importLogsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _selectedDateIso = MutableStateFlow(defaultMondayDateIso())
    val selectedDateIso: StateFlow<String> = _selectedDateIso.asStateFlow()

    val plansForSelectedDate: StateFlow<List<DailyPlanWithStops>> = combine(
        allDailyPlans,
        _selectedDateIso
    ) { plans, dateIso ->
        plans.filter { it.plan.dateIso == dateIso }.sortedBy { it.plan.variantCode }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val activePlanForSelectedDate: StateFlow<DailyPlanWithStops?> = combine(
        plansForSelectedDate,
        _selectedDateIso
    ) { plans, _ ->
        plans.firstOrNull { it.plan.isSelected } ?: plans.firstOrNull()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    private val _weeklyPlanResult = MutableStateFlow<WeeklyPlanResult?>(null)
    val weeklyPlanResult: StateFlow<WeeklyPlanResult?> = _weeklyPlanResult.asStateFlow()

    private val _lastOptimizationOutcome = MutableStateFlow<OptimizationOutcome?>(null)
    val lastOptimizationOutcome: StateFlow<OptimizationOutcome?> = _lastOptimizationOutcome.asStateFlow()

    private val _weeklyBlockedByHcpId = MutableStateFlow<Map<Long, WeeklyHcpBlockInfo>>(emptyMap())
    val weeklyBlockedByHcpId: StateFlow<Map<Long, WeeklyHcpBlockInfo>> = _weeklyBlockedByHcpId.asStateFlow()

    private val _importPreviewReport = MutableStateFlow<ImportPreviewReport?>(null)
    val importPreviewReport: StateFlow<ImportPreviewReport?> = _importPreviewReport.asStateFlow()

    private val _scheduleMigrationPreview = MutableStateFlow<ScheduleMigrationPreview?>(null)
    val scheduleMigrationPreview: StateFlow<ScheduleMigrationPreview?> = _scheduleMigrationPreview.asStateFlow()

    private val _selectedImportMode = MutableStateFlow(ImportMode.UPDATE_EXISTING)
    val selectedImportMode: StateFlow<ImportMode> = _selectedImportMode.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _bannerMessage = MutableStateFlow<UiBannerMessage?>(null)
    val bannerMessage: StateFlow<UiBannerMessage?> = _bannerMessage.asStateFlow()

    private val _lastAiInterpretation = MutableStateFlow<StructuredPlanningCommand?>(null)
    val lastAiInterpretation: StateFlow<StructuredPlanningCommand?> = _lastAiInterpretation.asStateFlow()

    private val _syncConflicts = MutableStateFlow<List<SyncConflict>>(emptyList())
    val syncConflicts: StateFlow<List<SyncConflict>> = _syncConflicts.asStateFlow()

    val chatMessages: StateFlow<List<ChatMessageEntity>> = repository.chatMessagesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _chatUiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val chatUiState: StateFlow<ChatUiState> = _chatUiState.asStateFlow()

    private val _selectedChatModelTier = MutableStateFlow(GeminiChatModelTier.COMPLEX)
    val selectedChatModelTier: StateFlow<GeminiChatModelTier> = _selectedChatModelTier.asStateFlow()

    private val _autoRouteChatModel = MutableStateFlow(true)
    val autoRouteChatModel: StateFlow<Boolean> = _autoRouteChatModel.asStateFlow()

    private val _selectedChatRole = MutableStateFlow(ChatbotRolePreset.TERRITORY_STRATEGIST)
    val selectedChatRole: StateFlow<ChatbotRolePreset> = _selectedChatRole.asStateFlow()

    private val _isAiReadingExcel = MutableStateFlow(false)
    val isAiReadingExcel: StateFlow<Boolean> = _isAiReadingExcel.asStateFlow()

    private val _aiExcelSummary = MutableStateFlow<String?>(null)
    val aiExcelSummary: StateFlow<String?> = _aiExcelSummary.asStateFlow()

    private val _targetVisits = MutableStateFlow(8)
    val targetVisits: StateFlow<Int> = _targetVisits.asStateFlow()

    private val _selectedShift = MutableStateFlow(ShiftPreset.AFTERNOON_EVENING)
    val selectedShift: StateFlow<ShiftPreset> = _selectedShift.asStateFlow()

    private val _shiftStartMinute = MutableStateFlow(ShiftPreset.AFTERNOON_EVENING.startMinute)
    val shiftStartMinute: StateFlow<Int> = _shiftStartMinute.asStateFlow()

    private val _shiftEndMinute = MutableStateFlow(ShiftPreset.AFTERNOON_EVENING.endMinute)
    val shiftEndMinute: StateFlow<Int> = _shiftEndMinute.asStateFlow()

    private val _selectedBricks = MutableStateFlow<Set<String>>(emptySet())
    val selectedBricks: StateFlow<Set<String>> = _selectedBricks.asStateFlow()

    private val _aiPlanningBriefing = MutableStateFlow<String?>(null)
    val aiPlanningBriefing: StateFlow<String?> = _aiPlanningBriefing.asStateFlow()

    private val _isAiPlanningBriefingLoading = MutableStateFlow(false)
    val isAiPlanningBriefingLoading: StateFlow<Boolean> = _isAiPlanningBriefingLoading.asStateFlow()

    val availableBricks: StateFlow<List<String>> = combine(
        repository.activeBricksFlow,
        hcpsWithDetails
    ) { registeredBricks, hcpList ->
        val activeClinicZones = hcpList.flatMap { h ->
            h.clinics.map { it.clinic.zone }
        }
        val sourceZones = if (activeClinicZones.isNotEmpty()) {
            activeClinicZones
        } else {
            registeredBricks
        }
        com.example.data.model.BrickNormalizer.extractUniqueIndividualBricks(sourceZones)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val availableSpecialties: StateFlow<List<String>> = repository.availableSpecialtiesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SpecialtyNormalizer.DEFAULT_INITIAL_SPECIALTIES
    )

    private var cachedRawWorkbookBytes: ByteArray? = null
    private var cachedFileName: String = "West.xlsx"

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val existingHcps = database.territoryDao().getAllActiveHcps()
                if (existingHcps.isEmpty()) {
                    val sampleBytes = ExcelTerritoryImporter.generateSampleWestXlsxBytes()
                    cachedRawWorkbookBytes = sampleBytes
                    cachedFileName = "West.xlsx"
                    val sheets = ExcelTerritoryImporter.parseXlsx(ByteArrayInputStream(sampleBytes))
                    val preview = ExcelTerritoryImporter.buildPreviewReport(
                        fileName = "West.xlsx",
                        sheets = sheets,
                        selectedSheetIndex = 0,
                        existingHcpsByTargetId = emptyMap()
                    )
                    _importPreviewReport.value = preview
                    repository.executeImport(
                        report = preview,
                        mode = ImportMode.UPDATE_EXISTING,
                        blankOverwritesVerified = false
                    )
                    repository.generateAndSaveDailyPlans(_selectedDateIso.value)
                } else {
                    // Step 0: Database migration for previously generated composite bricks & brick registry rebuild
                    val migratedCount = repository.migrateCompositeBricksAndRebuildRegistry()

                    // Self-heal any previously imported clinics whose coordinates or schedules were unverified/outdated,
                    // while strictly preserving user-edited (CONFIRMED) clinic schedules and intentionally flagged
                    // AMBIGUOUS multi-clinic schedules, and recovering any Clinic 2 data stored in customFieldsJson.
                    val allHcpsById = database.territoryDao().getAllActiveHcps().associateBy { it.id }
                    val initialClinics = database.territoryDao().getAllClinics().toMutableList()
                    val clinicsByHcp = initialClinics.groupBy { it.hcpId }.mapValues { it.value.toMutableList() }.toMutableMap()
                    val allWindowsByClinic = database.territoryDao().getAllAvailabilityWindows().groupBy { it.clinicId }
                    val batchedNewWindows = mutableListOf<AvailabilityWindowEntity>()
                    var healedCount = migratedCount

                    // Step A: Recover any Clinic 2 data from customFieldsJson if an HCP had unmapped Clinic 2 columns
                    for ((hcpId, hcp) in allHcpsById) {
                        val customMap = ExcelTerritoryImporter.jsonToCustomFields(hcp.customFieldsJson)
                        if (customMap.isEmpty()) continue

                        var recC2Sched = ""
                        var recC2Loc = ""
                        var recC2Zone = ""
                        for ((rawKey, rawVal) in customMap) {
                            val k = rawKey.lowercase(Locale.ROOT).trim()
                            val isC2 = k.contains("2") || k.contains("second") || k.contains("ثان")
                            if (isC2 && rawVal.isNotBlank()) {
                                when {
                                    k.contains("time") || k.contains("sched") || k.contains("hour") || k.contains("مواعيد") || k.contains("وقت") ->
                                        if (recC2Sched.isBlank()) recC2Sched = rawVal
                                    k.contains("loc") || k.contains("gps") || k.contains("map") || k.contains("address") || k.contains("لوكيشن") || k.contains("عنوان") || k.contains("موقع") ->
                                        if (recC2Loc.isBlank()) recC2Loc = rawVal
                                    k.contains("zone") || k.contains("brick") || k.contains("area") || k.contains("منطق") ->
                                        if (recC2Zone.isBlank()) recC2Zone = rawVal
                                }
                            }
                        }

                        val hcpClinics = clinicsByHcp.getOrPut(hcpId) { mutableListOf() }
                        val existingC2 = hcpClinics.firstOrNull { it.clinicIndex == 2 }
                        if (existingC2 == null && (recC2Sched.isNotBlank() || recC2Loc.isNotBlank() || recC2Zone.isNotBlank())) {
                            val effectiveZone = com.example.data.model.BrickNormalizer.normalizeBrickName(
                                recC2Zone.ifBlank { hcp.territoryZone.substringBefore("•").trim().ifBlank { "Unspecified Zone" } }
                            )
                            val coords = ExcelTerritoryImporter.extractCoordinates(
                                rawLocation = recC2Loc,
                                zoneHint = effectiveZone,
                                rowSeed = "${hcp.targetId}-c2-${hcp.name}"
                            )
                            val parsedSched = ScheduleValidator.validate(
                                AvailabilityScheduleParser.parseForExcelImport(recC2Sched, siblingClinicResult = null)
                            )
                            val isConfirmed = !parsedSched.clarificationRequired && parsedSched.windows.isNotEmpty()
                            val newC2 = ClinicEntity(
                                hcpId = hcpId,
                                clinicIndex = 2,
                                label = if (effectiveZone.isNotBlank() && effectiveZone != "Unspecified Zone") "Clinic 2 ($effectiveZone)" else "Clinic 2",
                                zone = effectiveZone,
                                originalLocationText = recC2Loc,
                                googleMapsUrl = if (coords.isGoogleMapsUrl) recC2Loc else "",
                                latitude = coords.latitude,
                                longitude = coords.longitude,
                                geocodingStatus = coords.geocodingStatus,
                                rawScheduleText = recC2Sched,
                                scheduleParseStatus = parsedSched.status.name,
                                scheduleAmbiguityReason = parsedSched.ambiguityReason,
                                userConfirmedSchedule = isConfirmed,
                                lastVerifiedEpochMs = if (isConfirmed) System.currentTimeMillis() else null
                            )
                            val newCid = database.territoryDao().insertClinic(newC2)
                            val savedC2 = newC2.copy(id = newCid)
                            hcpClinics.add(savedC2)
                            initialClinics.add(savedC2)
                            if (isConfirmed) {
                                batchedNewWindows.addAll(parsedSched.toEntities(newCid, isConfirmed = true))
                            }
                            healedCount++
                        }
                    }

                    // Step B: Self-heal coordinates and recover Clinic 2 custom-field schedules, then run full deterministic schedule audit
                    for (clinic in initialClinics) {
                        runCatching {
                            var updatedClinic = clinic
                            var changed = false

                            val hcp = allHcpsById[clinic.hcpId]
                            val customMap = hcp?.let { ExcelTerritoryImporter.jsonToCustomFields(it.customFieldsJson) }.orEmpty()
                            val recoveredSchedFromCustom = if (clinic.clinicIndex == 2) {
                                customMap.entries.firstOrNull { (k, v) ->
                                    val kl = k.lowercase(Locale.ROOT)
                                    (kl.contains("2") || kl.contains("second") || kl.contains("ثان")) &&
                                        (kl.contains("time") || kl.contains("sched") || kl.contains("hour") || kl.contains("مواعيد") || kl.contains("وقت")) &&
                                        v.isNotBlank()
                                }?.value.orEmpty()
                            } else {
                                ""
                            }

                            val needsCoords = (clinic.latitude == null || clinic.longitude == null) &&
                                clinic.googleMapsUrl.isNotBlank()
                            if (needsCoords && !clinic.originalLocationText.contains("Unverified", ignoreCase = true)) {
                                val resolved = ExcelTerritoryImporter.extractCoordinates(
                                    rawLocation = clinic.googleMapsUrl.ifBlank { clinic.originalLocationText },
                                    zoneHint = clinic.zone,
                                    rowSeed = "${clinic.hcpId}-${clinic.clinicIndex}-${clinic.label}"
                                )
                                if (resolved.latitude != null && resolved.longitude != null) {
                                    updatedClinic = updatedClinic.copy(
                                        latitude = resolved.latitude,
                                        longitude = resolved.longitude,
                                        geocodingStatus = resolved.geocodingStatus
                                    )
                                    changed = true
                                }
                            }

                            val wasReplacedByGenericFallback = !clinic.isManualUserConfirmed &&
                                (clinic.rawScheduleText == "Sat–Thu 2:00 PM – 7:00 PM" || clinic.rawScheduleText.isBlank())
                            if (recoveredSchedFromCustom.isNotBlank() && wasReplacedByGenericFallback) {
                                updatedClinic = updatedClinic.copy(
                                    rawScheduleText = recoveredSchedFromCustom,
                                    originalExcelScheduleText = clinic.originalExcelScheduleText.ifBlank { recoveredSchedFromCustom }
                                )
                                changed = true
                            }

                            if (changed) {
                                database.territoryDao().updateClinic(updatedClinic)
                                healedCount++
                            }
                        }.onFailure { e ->
                            Log.w("TerritoryViewModel", "Skipped clinic self-heal for clinic ${clinic.id}", e)
                        }
                    }

                    val scheduleAudit = repository.auditAllImportedSchedulesAndCorrectFalseAmbiguity()
                    healedCount += scheduleAudit.correctedFalseAmbiguityCount

                    if (healedCount > 0) {
                        repository.rebuildBrickRegistry()
                    }
                    val existingPlansForSelected = database.territoryDao().getPlansForDate(_selectedDateIso.value)
                    val needsReoptimize = existingPlansForSelected.isEmpty() ||
                        healedCount > 0 ||
                        existingPlansForSelected.any { it.totalDutySpanMinutes > 540 || it.endMinuteOfDay >= 1380 }
                    if (needsReoptimize) {
                        repository.generateAndSaveDailyPlans(
                            dateIso = _selectedDateIso.value,
                            allowedZones = _selectedBricks.value,
                            targetOverride = _targetVisits.value,
                            shiftStartMinute = _shiftStartMinute.value,
                            shiftEndMinute = _shiftEndMinute.value
                        )
                    }
                }
                val existingChat = repository.getAllChatMessages()
                if (existingChat.isEmpty()) {
                    repository.appendChatMessage(
                        ChatMessageEntity(
                            role = "model",
                            content = "Welcome to TerritoryRx Multi-Turn Gemini Assistant! I am grounded in your Room database (12 West Territory HCPs, clinic schedules, GPS locations, and active route plans).\n\n" +
                                "• **gemini-4-argon**: Complex multi-constraint route strategy & pre-call clinical briefings\n" +
                                "• **gemini-3.8-flash**: General territory planning & CSV/Excel import auditing\n" +
                                "• **gemini-3.1-flash-lite**: Fast field lookups & clinic hours checks\n\n" +
                                "How can I help optimize your territory visits today?",
                            modelId = GeminiChatModelTier.COMPLEX.modelId,
                            taskTierLabel = GeminiChatModelTier.COMPLEX.shortTitle
                        )
                    )
                }
            } catch (t: Throwable) {
                Log.e("TerritoryViewModel", "Startup initialization failed safely", t)
            }
        }
    }

    fun selectDate(dateIso: String) {
        _selectedDateIso.value = dateIso
        ensurePlanForDate(dateIso)
    }

    fun stepSelectedDate(daysDelta: Int) {
        val parts = _selectedDateIso.value.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size == 3) {
            val cal = Calendar.getInstance()
            cal.set(parts[0], parts[1] - 1, parts[2], 12, 0, 0)
            cal.add(Calendar.DAY_OF_MONTH, daysDelta)
            val nextIso = String.format(
                Locale.US,
                "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
            _selectedDateIso.value = nextIso
            ensurePlanForDate(nextIso)
        }
    }

    private suspend fun refreshWeeklyBlockedForSelectedDate(dateIso: String = _selectedDateIso.value) {
        val blocked = repository.getWeeklyBlockedHcpsForDate(
            dateIso = dateIso,
            includeSelectedPlanOnSameDate = true
        )
        _weeklyBlockedByHcpId.value = blocked
    }

    private fun ensurePlanForDate(dateIso: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = database.territoryDao().getPlansForDate(dateIso)
            val needsReoptimize = existing.isEmpty() ||
                existing.any { it.totalDutySpanMinutes > 540 || it.endMinuteOfDay >= 1380 }
            if (needsReoptimize && database.territoryDao().getAllActiveHcps().isNotEmpty()) {
                val outcome = repository.generateAndSaveDailyPlans(
                    dateIso = dateIso,
                    allowedZones = _selectedBricks.value,
                    targetOverride = _targetVisits.value,
                    shiftStartMinute = _shiftStartMinute.value,
                    shiftEndMinute = _shiftEndMinute.value
                )
                _lastOptimizationOutcome.value = outcome
            }
            refreshWeeklyBlockedForSelectedDate(dateIso)
        }
    }

    fun dismissBanner() {
        _bannerMessage.value = null
    }

    fun setImportMode(mode: ImportMode) {
        _selectedImportMode.value = mode
    }

    fun previewSampleWestXlsx(executeImmediately: Boolean = false) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val existingById = withContext(Dispatchers.IO) {
                    database.territoryDao().getAllActiveHcps().associateBy { it.targetId }
                }
                val bytes = withContext(Dispatchers.IO) {
                    ExcelTerritoryImporter.generateSampleWestXlsxBytes()
                }
                cachedRawWorkbookBytes = bytes
                cachedFileName = "West.xlsx"
                val report = withContext(Dispatchers.IO) {
                    val sheets = ExcelTerritoryImporter.parseXlsx(ByteArrayInputStream(bytes))
                    ExcelTerritoryImporter.buildPreviewReport(
                        fileName = "West.xlsx",
                        sheets = sheets,
                        selectedSheetIndex = 0,
                        existingHcpsByTargetId = existingById
                    )
                }
                _importPreviewReport.value = report
                if (executeImmediately) {
                    val settings = workSettings.value
                    withContext(Dispatchers.IO) {
                        repository.executeImport(
                            report = report,
                            mode = _selectedImportMode.value,
                            blankOverwritesVerified = settings.blankCellOverwritesVerified
                        )
                        repository.generateAndSaveDailyPlans(_selectedDateIso.value)
                    }
                    _bannerMessage.value = UiBannerMessage(
                        title = "West.xlsx Loaded & Optimized",
                        message = "Imported ${report.totalDetectedRows} HCP rows and generated daily route plans."
                    )
                } else {
                    _bannerMessage.value = UiBannerMessage(
                        title = "West.xlsx Preview Ready",
                        message = "Detected ${report.totalDetectedRows} rows (${report.newHcpsCount} new, ${report.ambiguousSchedulesCount} ambiguous schedules). Review below and tap Confirm Import."
                    )
                }
            } catch (e: Exception) {
                _bannerMessage.value = UiBannerMessage(
                    title = "Preview Failed",
                    message = e.localizedMessage ?: "Unable to parse sample West.xlsx",
                    isError = true
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun previewUriImport(uri: Uri, displayName: String) {
        viewModelScope.launch {
            _isBusy.value = true
            var shouldRunAiEnrichment = false
            try {
                val app = getApplication<Application>()
                val bytes = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalArgumentException("Could not open selected file.")
                }
                cachedRawWorkbookBytes = bytes
                cachedFileName = displayName
                val existingById = withContext(Dispatchers.IO) {
                    database.territoryDao().getAllActiveHcps().associateBy { it.targetId }
                }
                val report = withContext(Dispatchers.IO) {
                    val sheets = ExcelTerritoryImporter.parseFileBytes(displayName, bytes)
                    ExcelTerritoryImporter.buildPreviewReport(
                        fileName = displayName,
                        sheets = sheets,
                        selectedSheetIndex = 0,
                        existingHcpsByTargetId = existingById
                    )
                }
                _importPreviewReport.value = report
                if (report.totalDetectedRows > 0 && !report.columnMapping.requiresManualConfirmation) {
                    val settings = workSettings.value
                    val log = withContext(Dispatchers.IO) {
                        val importLog = repository.executeImport(
                            report = report,
                            mode = _selectedImportMode.value,
                            blankOverwritesVerified = settings.blankCellOverwritesVerified
                        )
                        repository.generateAndSaveDailyPlans(_selectedDateIso.value)
                        importLog
                    }
                    _bannerMessage.value = UiBannerMessage(
                        title = "Imported '$displayName' (${report.totalDetectedRows} HCPs)",
                        message = "Added ${log.addedHcps} new, updated ${log.updatedHcps}, preserved ${log.unchangedHcps}. Daily route plans updated!"
                    )
                    shouldRunAiEnrichment = true
                } else {
                    _bannerMessage.value = UiBannerMessage(
                        title = "Sheet Ready to Import",
                        message = "Detected ${report.totalDetectedRows} HCP rows in '$displayName'. Tap Import when ready."
                    )
                    shouldRunAiEnrichment = report.totalDetectedRows > 0
                }
            } catch (e: Exception) {
                _bannerMessage.value = UiBannerMessage(
                    title = "File Import Error",
                    message = e.localizedMessage ?: "Failed to read workbook.",
                    isError = true
                )
            } finally {
                // Always clear Working... immediately after local import & route generation!
                _isBusy.value = false
            }

            if (shouldRunAiEnrichment) {
                readExcelDataAndSchedulesWithAi(autoTriggered = true)
            }
        }
    }

    /**
     * Uses Gemini AI (`gemini-3.8-flash` via Firebase AI Logic) to read all Excel sheet row data
     * and normalize all clinic time schedule formats without blocking the main UI or keeping `Working...` stuck.
     */
    fun readExcelDataAndSchedulesWithAi(autoTriggered: Boolean = false) {
        val currentReport = _importPreviewReport.value ?: return
        if (_isAiReadingExcel.value) return
        viewModelScope.launch {
            _isAiReadingExcel.value = true
            try {
                val res = withContext(Dispatchers.IO) {
                    aiAssistant.enrichImportReportWithAi(currentReport)
                }
                res.onSuccess { (enrichedReport, summary) ->
                    _importPreviewReport.value = enrichedReport
                    _aiExcelSummary.value = summary
                    val settings = workSettings.value
                    withContext(Dispatchers.IO) {
                        repository.executeImport(
                            report = enrichedReport,
                            mode = _selectedImportMode.value,
                            blankOverwritesVerified = settings.blankCellOverwritesVerified
                        )
                        repository.generateAndSaveDailyPlans(_selectedDateIso.value)
                    }
                    if (!autoTriggered) {
                        _bannerMessage.value = UiBannerMessage(
                            title = "AI Excel & Schedule Extraction Complete",
                            message = summary
                        )
                    }
                }
            } catch (_: Exception) {
            } finally {
                _isAiReadingExcel.value = false
            }
        }
    }

    fun previewSampleWestCsv() {
        val sampleCsv = ExcelTerritoryImporter.generateSampleWestCsvText()
        previewRawCsvText(sampleCsv, "West_Target_List.csv")
    }

    fun previewRawCsvText(csvText: String, fileName: String = "Uploaded_Target_List.csv") {
        if (csvText.isBlank()) return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val bytes = csvText.toByteArray(StandardCharsets.UTF_8)
                cachedRawWorkbookBytes = bytes
                cachedFileName = fileName
                val existingById = withContext(Dispatchers.IO) {
                    database.territoryDao().getAllActiveHcps().associateBy { it.targetId }
                }
                val report = withContext(Dispatchers.IO) {
                    val sheets = ExcelTerritoryImporter.parseCsv(ByteArrayInputStream(bytes), sheetName = "CSV_Target_List")
                    ExcelTerritoryImporter.buildPreviewReport(
                        fileName = fileName,
                        sheets = sheets,
                        selectedSheetIndex = 0,
                        existingHcpsByTargetId = existingById
                    )
                }
                _importPreviewReport.value = report
                _bannerMessage.value = UiBannerMessage(
                    title = "CSV Target List Parsed",
                    message = "Detected ${report.totalDetectedRows} HCP rows in '$fileName'. Review column mappings and tap Confirm Import."
                )
            } catch (e: Exception) {
                _bannerMessage.value = UiBannerMessage(
                    title = "CSV Parse Error",
                    message = e.localizedMessage ?: "Unable to parse CSV content.",
                    isError = true
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun updateCustomColumnMapping(field: CanonicalField, columnIndex: Int?) {
        val currentReport = _importPreviewReport.value ?: return
        val bytes = cachedRawWorkbookBytes ?: return
        val updatedMapping = currentReport.columnMapping.fieldToColumnIndex.toMutableMap()
        if (columnIndex == null || columnIndex < 0) {
            updatedMapping.remove(field)
        } else {
            updatedMapping[field] = columnIndex
        }
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val existingById = withContext(Dispatchers.IO) {
                    database.territoryDao().getAllActiveHcps().associateBy { it.targetId }
                }
                val report = withContext(Dispatchers.IO) {
                    val sheets = ExcelTerritoryImporter.parseFileBytes(cachedFileName, bytes)
                    val idx = sheets.indexOfFirst { it.sheetName == currentReport.selectedWorksheet }.coerceAtLeast(0)
                    ExcelTerritoryImporter.buildPreviewReport(
                        fileName = cachedFileName,
                        sheets = sheets,
                        selectedSheetIndex = idx,
                        customMapping = updatedMapping,
                        existingHcpsByTargetId = existingById
                    )
                }
                _importPreviewReport.value = report
                withContext(Dispatchers.IO) {
                    val mapJson = updatedMapping.entries.joinToString(",") { "${it.key.name}:${it.value}" }
                    repository.saveImportMappingProfile(
                        ImportMappingProfileEntity(
                            profileName = cachedFileName,
                            headerSignature = currentReport.availableHeaders.joinToString("|"),
                            fieldToColumnJson = mapJson
                        )
                    )
                }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun selectWorksheetInPreview(sheetName: String) {
        val bytes = cachedRawWorkbookBytes ?: return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val existingById = withContext(Dispatchers.IO) {
                    database.territoryDao().getAllActiveHcps().associateBy { it.targetId }
                }
                val report = withContext(Dispatchers.IO) {
                    val sheets = ExcelTerritoryImporter.parseFileBytes(cachedFileName, bytes)
                    val idx = sheets.indexOfFirst { it.sheetName == sheetName }.coerceAtLeast(0)
                    ExcelTerritoryImporter.buildPreviewReport(
                        fileName = cachedFileName,
                        sheets = sheets,
                        selectedSheetIndex = idx,
                        existingHcpsByTargetId = existingById
                    )
                }
                _importPreviewReport.value = report
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun confirmPreviewImport() {
        val report = _importPreviewReport.value
        if (report == null) {
            _bannerMessage.value = UiBannerMessage(
                title = "No File Selected Yet",
                message = "Please tap 'Upload .xlsx / .csv' first to choose your workbook.",
                isError = true
            )
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val settings = workSettings.value
                val log = withContext(Dispatchers.IO) {
                    repository.executeImport(
                        report = report,
                        mode = _selectedImportMode.value,
                        blankOverwritesVerified = settings.blankCellOverwritesVerified
                    )
                }
                withContext(Dispatchers.IO) {
                    repository.generateAndSaveDailyPlans(_selectedDateIso.value)
                }
                _bannerMessage.value = UiBannerMessage(
                    title = "Import Completed (${log.importMode})",
                    message = "Added ${log.addedHcps} new HCPs, updated ${log.updatedHcps}, preserved ${log.unchangedHcps}. Visit history intact."
                )
            } catch (e: Exception) {
                _bannerMessage.value = UiBannerMessage(
                    title = "Import Error",
                    message = e.localizedMessage ?: "Failed to save imported records.",
                    isError = true
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun rollbackImportLog(log: ImportLogEntity) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                withContext(Dispatchers.IO) {
                    repository.rollbackImport(log)
                }
                _bannerMessage.value = UiBannerMessage(
                    title = "Import Rolled Back",
                    message = "Reverted import #${log.id} (${log.fileName})."
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun auditAllImportedSchedules() {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val summary = withContext(Dispatchers.IO) {
                    val result = repository.auditAllImportedSchedulesAndCorrectFalseAmbiguity()
                    if (result.correctedFalseAmbiguityCount > 0) {
                        repository.generateAndSaveDailyPlans(
                            dateIso = _selectedDateIso.value,
                            allowedZones = _selectedBricks.value,
                            targetOverride = _targetVisits.value,
                            shiftStartMinute = _shiftStartMinute.value,
                            shiftEndMinute = _shiftEndMinute.value
                        )
                    }
                    result
                }
                _bannerMessage.value = UiBannerMessage(
                    title = "Schedule Audit Complete",
                    message = "Audited ${summary.totalClinicsAudited} clinics: corrected ${summary.correctedFalseAmbiguityCount} false ambiguities; ${summary.confirmedCount} verified (${summary.genuineClarificationCount} still require genuine clarification)."
                )
            } catch (e: Exception) {
                _bannerMessage.value = UiBannerMessage(
                    title = "Audit Failed",
                    message = e.localizedMessage ?: "Unable to audit clinic schedules.",
                    isError = true
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun previewScheduleMigration() {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val preview = withContext(Dispatchers.IO) {
                    repository.previewScheduleMigration()
                }
                _scheduleMigrationPreview.value = preview
                if (preview.candidatesToUpdate.isEmpty()) {
                    _bannerMessage.value = UiBannerMessage(
                        title = "Schedules Already Up to Date",
                        message = "Inspected ${preview.totalClinicsInspected} clinics (${preview.protectedUserConfirmedCount} manual edits protected). No migration changes needed."
                    )
                }
            } catch (e: Exception) {
                _bannerMessage.value = UiBannerMessage(
                    title = "Migration Preview Failed",
                    message = e.localizedMessage ?: "Unable to inspect schedules for migration.",
                    isError = true
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun dismissScheduleMigrationPreview() {
        _scheduleMigrationPreview.value = null
    }

    fun applyScheduleMigration(allowOverwriteUserConfirmed: Boolean = false) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val summary = withContext(Dispatchers.IO) {
                    val res = repository.applyScheduleMigration(allowOverwriteUserConfirmed)
                    repository.generateAndSaveDailyPlans(
                        dateIso = _selectedDateIso.value,
                        allowedZones = _selectedBricks.value,
                        targetOverride = _targetVisits.value,
                        shiftStartMinute = _shiftStartMinute.value,
                        shiftEndMinute = _shiftEndMinute.value
                    )
                    res
                }
                _scheduleMigrationPreview.value = null
                _bannerMessage.value = UiBannerMessage(
                    title = "Safe Migration Applied",
                    message = "Updated ${summary.correctedFalseAmbiguityCount} outdated schedule(s) across ${summary.totalClinicsAudited} clinics; protected manual edits, GPS, bricks, and visit history."
                )
            } catch (e: Exception) {
                _bannerMessage.value = UiBannerMessage(
                    title = "Safe Migration Failed",
                    message = e.localizedMessage ?: "Unable to apply schedule migration.",
                    isError = true
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun setTargetVisits(count: Int) {
        _targetVisits.value = count.coerceIn(1, 20)
        optimizeSelectedDay()
    }

    fun selectShift(shift: ShiftPreset) {
        _selectedShift.value = shift
        if (shift != ShiftPreset.CUSTOM) {
            _shiftStartMinute.value = shift.startMinute
            _shiftEndMinute.value = shift.endMinute
        }
        optimizeSelectedDay()
    }

    fun setCustomWorkMinutes(startMin: Int, endMin: Int) {
        val validStart = startMin.coerceIn(6 * 60, 21 * 60)
        val validEnd = endMin.coerceIn(validStart + 60, 23 * 60)
        _shiftStartMinute.value = validStart
        _shiftEndMinute.value = validEnd
        _selectedShift.value = ShiftPreset.CUSTOM
        optimizeSelectedDay()
    }

    fun setCustomWorkTiming(fromText: String, toText: String) {
        val parsedStart = parseUserTimeText(fromText, isEndOfShift = false) ?: _shiftStartMinute.value
        val rawEnd = parseUserTimeText(toText, isEndOfShift = true) ?: _shiftEndMinute.value
        val adjustedEnd = if (rawEnd <= parsedStart) {
            if (rawEnd + 12 * 60 > parsedStart && rawEnd + 12 * 60 <= 23 * 60 + 30) {
                rawEnd + 12 * 60
            } else {
                (parsedStart + 240).coerceAtMost(23 * 60)
            }
        } else {
            rawEnd
        }
        setCustomWorkMinutes(parsedStart, adjustedEnd)
    }

    private fun parseUserTimeText(raw: String, isEndOfShift: Boolean): Int? {
        val arabicIndic = "٠١٢٣٤٥٦٧٨٩"
        val easternArabic = "۰۱۲۳۴۵۶۷۸۹"
        val normalized = buildString {
            for (ch in raw) {
                val idx1 = arabicIndic.indexOf(ch)
                val idx2 = easternArabic.indexOf(ch)
                when {
                    idx1 >= 0 -> append(('0'.code + idx1).toChar())
                    idx2 >= 0 -> append(('0'.code + idx2).toChar())
                    else -> append(ch)
                }
            }
        }.trim().lowercase(Locale.US)
        if (normalized.isBlank()) return null

        val isPm = normalized.contains("pm") || normalized.contains("p.m") || normalized.contains("م") || normalized.contains("مساء")
        val isAm = normalized.contains("am") || normalized.contains("a.m") || normalized.contains("ص") || normalized.contains("صباح")

        val regex = Regex("""(\d{1,2})(?:\s*[:.]\s*(\d{1,2}))?""")
        val match = regex.find(normalized) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()?.coerceIn(0, 59) ?: 0

        if (hour > 23) return null
        when {
            isPm && hour in 1..11 -> hour += 12
            isAm && hour == 12 -> hour = 0
            !isPm && !isAm -> {
                // Smart field medical rep convention:
                // 1..7 without AM/PM -> 1:00 PM .. 7:00 PM (13:00..19:00)
                // 8..11 at end of shift -> 8:00 PM .. 11:00 PM (20:00..23:00)
                if (hour in 1..7) {
                    hour += 12
                } else if (isEndOfShift && hour in 8..11) {
                    hour += 12
                }
            }
        }
        return (hour * 60 + minute).coerceIn(360, 23 * 60 + 30)
    }

    fun toggleBrick(brick: String) {
        val current = _selectedBricks.value.toMutableSet()
        if (current.contains(brick)) {
            current.remove(brick)
        } else {
            current.add(brick)
        }
        _selectedBricks.value = current
        optimizeSelectedDay()
    }

    fun selectAllBricks() {
        _selectedBricks.value = emptySet()
        optimizeSelectedDay()
    }

    fun currentShiftDisplayLabel(): String {
        val s = AvailabilityScheduleParser.formatMinuteOfDay(_shiftStartMinute.value)
        val e = AvailabilityScheduleParser.formatMinuteOfDay(_shiftEndMinute.value)
        return "$s – $e"
    }

    fun optimizeSelectedDay() {
        viewModelScope.launch {
            _isBusy.value = true
            _isAiPlanningBriefingLoading.value = true
            try {
                val startM = _shiftStartMinute.value
                val endM = _shiftEndMinute.value
                val timingLabel = currentShiftDisplayLabel()
                val outcome = withContext(Dispatchers.IO) {
                    val out = repository.generateAndSaveDailyPlans(
                        dateIso = _selectedDateIso.value,
                        allowedZones = _selectedBricks.value,
                        targetOverride = _targetVisits.value,
                        shiftStartMinute = startM,
                        shiftEndMinute = endM
                    )
                    refreshWeeklyBlockedForSelectedDate(_selectedDateIso.value)
                    out
                }
                _lastOptimizationOutcome.value = outcome
                val best = outcome.alternatives.firstOrNull()
                if (best != null && best.stops.isNotEmpty()) {
                    val diag = outcome.infeasibleReasons.joinToString(" ")
                    val brickNotice = if (_selectedBricks.value.isNotEmpty()) " [Bricks: ${_selectedBricks.value.joinToString()}]" else ""
                    _bannerMessage.value = UiBannerMessage(
                        title = "Generated ${outcome.alternatives.size} Validated Plans (${best.stops.size} Visits)",
                        message = "${best.plan.variantLabel}: ${best.stops.size} visits, ${best.plan.totalDutySpanMinutes}m duty span ($timingLabel)$brickNotice. $diag".trim()
                    )
                    // Generate AI strategy briefing for this plan
                    generateAiPlanningBriefing(best)
                } else {
                    val reason = outcome.infeasibleReasons
                        .filter { it.isNotBlank() }
                        .joinToString(" • ")
                        .ifBlank { "No HCP clinics matched the selected day, shift window, and active filters." }
                    _bannerMessage.value = UiBannerMessage(
                        title = "No Feasible Route Found",
                        message = reason,
                        isError = true
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TerritoryViewModel", "Daily route optimization failed for date=${_selectedDateIso.value}", e)
                _bannerMessage.value = UiBannerMessage(
                    title = "Optimization Failed",
                    message = formatOptimizationFailureMessage(e),
                    isError = true
                )
            } finally {
                _isBusy.value = false
                _isAiPlanningBriefingLoading.value = false
            }
        }
    }

    fun generateAiPlanningBriefing(specificPlan: DailyPlanWithStops? = null) {
        val plan = specificPlan ?: activePlanForSelectedDate.value ?: return
        viewModelScope.launch {
            _isAiPlanningBriefingLoading.value = true
            try {
                val briefing = withContext(Dispatchers.IO) {
                    aiAssistant.generateDailyPlanningBriefing(
                        selectedDateIso = _selectedDateIso.value,
                        plan = plan,
                        hcps = hcpsWithDetails.value,
                        targetVisits = _targetVisits.value,
                        shiftTitle = currentShiftDisplayLabel(),
                        selectedBricks = _selectedBricks.value
                    )
                }
                _aiPlanningBriefing.value = briefing
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("TerritoryViewModel", "AI planning briefing fallback used", e)
            } finally {
                _isAiPlanningBriefingLoading.value = false
            }
        }
    }

    fun optimizeWeek() {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.generateWeeklySchedule(_selectedDateIso.value)
                }
                _weeklyPlanResult.value = result
                if (result.dayPlans.isEmpty()) {
                    val reason = result.diagnostics
                        .filter { it.isNotBlank() }
                        .joinToString(" • ")
                        .ifBlank { "No eligible HCP visits could be scheduled across this workweek." }
                    _bannerMessage.value = UiBannerMessage(
                        title = "No Weekly Route Generated",
                        message = reason,
                        isError = true
                    )
                } else {
                    _bannerMessage.value = UiBannerMessage(
                        title = "Weekly Schedule Generated",
                        message = "Planned ${result.totalPlannedVisits} total visits (${result.uniqueHcpsCovered} unique HCPs) across ${result.dayPlans.size} workdays."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("TerritoryViewModel", "Weekly route optimization failed for date=${_selectedDateIso.value}", e)
                _bannerMessage.value = UiBannerMessage(
                    title = "Weekly Optimization Failed",
                    message = formatOptimizationFailureMessage(e),
                    isError = true
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun selectPlanVariant(planId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.selectPlanVariant(_selectedDateIso.value, planId)
            }
            _bannerMessage.value = UiBannerMessage(
                title = "Route Option Selected",
                message = "Switched active route option for ${_selectedDateIso.value}."
            )
        }
    }

    fun markStopExecution(
        planWithStops: DailyPlanWithStops,
        stop: PlannedStopEntity,
        status: String,
        notes: String = ""
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                withContext(Dispatchers.IO) {
                    repository.recordStopExecution(
                        planWithStops = planWithStops,
                        stop = stop,
                        newStatus = status,
                        notes = notes,
                        reoptimizeRemaining = (status == "CANCELLED" || status == "NOT_COMPLETED")
                    )
                    refreshWeeklyBlockedForSelectedDate(_selectedDateIso.value)
                }
                val actionDesc = when (status) {
                    "COMPLETED" -> "Visit marked COMPLETED and credited toward quarterly target."
                    "CANCELLED" -> "Visit marked CANCELLED (does not count toward coverage; HCP remains eligible later this week). Remaining day re-optimized."
                    else -> "Visit marked NOT COMPLETED. Remaining day re-optimized."
                }
                _bannerMessage.value = UiBannerMessage(
                    title = "${stop.hcpName} ($status)",
                    message = actionDesc
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun toggleLockOnStop(stop: PlannedStopEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.toggleStopLock(stop)
            }
            val newState = if (stop.isLocked) "Unlocked" else "Locked"
            _bannerMessage.value = UiBannerMessage(
                title = "Stop $newState",
                message = "${stop.hcpName} is now ${newState.lowercase(Locale.ROOT)} in the route."
            )
        }
    }

    fun moveStopOrder(planWithStops: DailyPlanWithStops, stop: PlannedStopEntity, direction: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.reorderStopAndRevalidate(planWithStops, stop, direction)
            }
            val dirLabel = if (direction < 0) "Moved Up" else "Moved Down"
            _bannerMessage.value = UiBannerMessage(
                title = "${stop.hcpName} ($dirLabel)",
                message = "Updated stop sequence and recalculated travel timings."
            )
        }
    }

    fun updateStopDuration(planWithStops: DailyPlanWithStops, stop: PlannedStopEntity, durationMinutes: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.updateStopDurationAndRevalidate(planWithStops, stop, durationMinutes)
            }
            _bannerMessage.value = UiBannerMessage(
                title = "Visit Duration Updated (${durationMinutes}m)",
                message = "Updated ${stop.hcpName} visit duration to ${durationMinutes}m and revalidated route."
            )
        }
    }

    fun switchStopClinic(planWithStops: DailyPlanWithStops, stop: PlannedStopEntity, newClinic: ClinicEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val dow = RouteOptimizerEngine.parseIsoDayOfWeek(planWithStops.plan.dateIso)
                val wins = database.territoryDao().getWindowsForClinic(newClinic.id).filter { it.dayOfWeek == dow }
                val win = wins.firstOrNull()
                repository.changeStopClinicAndRevalidate(
                    planWithStops = planWithStops,
                    stop = stop,
                    newClinic = newClinic,
                    newWindowStartMinute = win?.startMinuteOfDay,
                    newWindowEndMinute = win?.endMinuteOfDay
                )
            }
            _bannerMessage.value = UiBannerMessage(
                title = "Clinic Switched (${newClinic.label})",
                message = "Switched ${stop.hcpName} to ${newClinic.label} (${newClinic.zone}) and revalidated route."
            )
        }
    }

    fun saveClinicAvailability(
        clinic: ClinicEntity,
        windows: List<AvailabilityWindowEntity>,
        updatedRawScheduleText: String? = null,
        updatedZone: String? = null,
        updatedLocationText: String? = null,
        updatedLat: Double? = null,
        updatedLng: Double? = null
    ) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.updateClinicScheduleAndConfirm(
                    clinic = clinic,
                    updatedWindows = windows,
                    updatedRawScheduleText = updatedRawScheduleText,
                    updatedZone = updatedZone,
                    updatedLocationText = updatedLocationText,
                    updatedLat = updatedLat,
                    updatedLng = updatedLng
                )
                repository.generateAndSaveDailyPlans(
                    dateIso = _selectedDateIso.value,
                    allowedZones = _selectedBricks.value,
                    targetOverride = _targetVisits.value,
                    shiftStartMinute = _shiftStartMinute.value,
                    shiftEndMinute = _shiftEndMinute.value
                )
            }
            val effectiveZone = updatedZone?.takeIf { it.isNotBlank() } ?: clinic.zone
            _bannerMessage.value = UiBannerMessage(
                title = "Clinic Schedule & Details Saved",
                message = "Updated Clinic ${clinic.clinicIndex} ($effectiveZone) and refreshed daily route plans."
            )
        }
    }

    fun saveFullHcpCardEdits(
        hcp: HcpEntity,
        updatedName: String,
        updatedSpecialty: String,
        updatedQuarterlyTarget: Int,
        baselineVisits: Int,
        quarterLabel: String,
        historyIncomplete: Boolean,
        clinic: ClinicEntity?,
        updatedClinicZone: String,
        updatedClinicLocation: String,
        updatedClinicRawSchedule: String,
        updatedWindows: List<AvailabilityWindowEntity>,
        updatedLat: Double?,
        updatedLng: Double?
    ) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.saveFullHcpAndClinicEdits(
                    hcp = hcp,
                    updatedName = updatedName,
                    updatedSpecialty = updatedSpecialty,
                    updatedQuarterlyTarget = updatedQuarterlyTarget,
                    baselineCompletedCount = baselineVisits,
                    quarterLabel = quarterLabel,
                    historyIncomplete = historyIncomplete,
                    clinic = clinic,
                    updatedClinicZone = updatedClinicZone,
                    updatedClinicLocation = updatedClinicLocation,
                    updatedClinicRawSchedule = updatedClinicRawSchedule,
                    updatedWindows = updatedWindows,
                    updatedLat = updatedLat,
                    updatedLng = updatedLng
                )
                repository.generateAndSaveDailyPlans(
                    dateIso = _selectedDateIso.value,
                    allowedZones = _selectedBricks.value,
                    targetOverride = _targetVisits.value,
                    shiftStartMinute = _shiftStartMinute.value,
                    shiftEndMinute = _shiftEndMinute.value
                )
            }
            _bannerMessage.value = UiBannerMessage(
                title = "Saved Changes for ${updatedName.ifBlank { hcp.name }}",
                message = "Doctor profile, clinic schedule windows, and route planner updated."
            )
        }
    }

    fun addClinicToHcp(
        hcp: HcpEntity,
        zone: String,
        locationUrlOrAddress: String,
        rawScheduleText: String
    ) {
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                val c = repository.addClinicToHcp(hcp, zone, locationUrlOrAddress, rawScheduleText)
                repository.generateAndSaveDailyPlans(
                    dateIso = _selectedDateIso.value,
                    allowedZones = _selectedBricks.value,
                    targetOverride = _targetVisits.value,
                    shiftStartMinute = _shiftStartMinute.value,
                    shiftEndMinute = _shiftEndMinute.value
                )
                c
            }
            _bannerMessage.value = UiBannerMessage(
                title = "Added ${added.label}",
                message = "Added Clinic ${added.clinicIndex} (${added.zone}) for ${hcp.name}."
            )
        }
    }

    fun requestAiScheduleDisambiguation(
        clinic: ClinicEntity,
        onResult: (ScheduleParseResult) -> Unit
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val res = withContext(Dispatchers.IO) {
                    aiAssistant.proposeAmbiguousScheduleInterpretation(clinic.rawScheduleText)
                }
                res.onSuccess { suggestion ->
                    onResult(suggestion)
                    _bannerMessage.value = UiBannerMessage(
                        title = "AI Schedule Suggestion Ready",
                        message = "${suggestion.ambiguityReason} Please review and tap Save Changes to apply."
                    )
                }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun verifyClinicCoordinates(clinic: ClinicEntity, lat: Double, lng: Double) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.verifyClinicCoordinates(clinic, lat, lng)
                repository.generateAndSaveDailyPlans(
                    dateIso = _selectedDateIso.value,
                    allowedZones = _selectedBricks.value,
                    targetOverride = _targetVisits.value,
                    shiftStartMinute = _shiftStartMinute.value,
                    shiftEndMinute = _shiftEndMinute.value
                )
            }
            _bannerMessage.value = UiBannerMessage(
                title = "Coordinates Verified",
                message = "Updated ${clinic.label} (${clinic.zone}) to %.5f, %.5f.".format(Locale.US, lat, lng)
            )
        }
    }

    fun updateHcpBaselineHistory(
        hcp: HcpEntity,
        baselineVisits: Int,
        quarterLabel: String,
        historyIncomplete: Boolean
    ) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.updateHcpBaselineHistory(hcp, baselineVisits, quarterLabel, historyIncomplete)
                repository.generateAndSaveDailyPlans(
                    dateIso = _selectedDateIso.value,
                    allowedZones = _selectedBricks.value,
                    targetOverride = _targetVisits.value,
                    shiftStartMinute = _shiftStartMinute.value,
                    shiftEndMinute = _shiftEndMinute.value
                )
            }
            _bannerMessage.value = UiBannerMessage(
                title = "Baseline Coverage Updated",
                message = "Set baseline visits for ${hcp.name} to $baselineVisits in $quarterLabel."
            )
        }
    }

    fun saveManualHcpTarget(
        targetId: String,
        name: String,
        specialty: String,
        quarterlyTarget: Int,
        priorityScore: Int,
        zone: String,
        rawLocation: String,
        rawScheduleText: String
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val saved = withContext(Dispatchers.IO) {
                    val created = repository.createOrUpdateHcpTarget(
                        targetId = targetId,
                        name = name,
                        specialty = specialty,
                        quarterlyTarget = quarterlyTarget,
                        priorityScore = priorityScore,
                        zone = zone,
                        rawLocation = rawLocation,
                        rawScheduleText = rawScheduleText
                    )
                    repository.generateAndSaveDailyPlans(
                        dateIso = _selectedDateIso.value,
                        allowedZones = _selectedBricks.value,
                        targetOverride = _targetVisits.value,
                        shiftStartMinute = _shiftStartMinute.value,
                        shiftEndMinute = _shiftEndMinute.value
                    )
                    created
                }
                _bannerMessage.value = UiBannerMessage(
                    title = "Added HCP (${saved.name})",
                    message = "Saved ${saved.name} (${saved.specialty}, $zone) with ${saved.quarterlyTarget} quarterly visits and updated route planner."
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun addHcpToCurrentPlan(
        hcpWithDetails: HcpWithDetails,
        preferredClinic: ClinicEntity? = null
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val addResult = withContext(Dispatchers.IO) {
                    val res = repository.addHcpStopToPlan(
                        dateIso = _selectedDateIso.value,
                        hcpWithDetails = hcpWithDetails,
                        preferredClinic = preferredClinic,
                        shiftStartMinute = _shiftStartMinute.value,
                        shiftEndMinute = _shiftEndMinute.value,
                        allowWeeklyOverride = false
                    )
                    refreshWeeklyBlockedForSelectedDate(_selectedDateIso.value)
                    res
                }
                val clinicName = preferredClinic?.label
                    ?: hcpWithDetails.clinics.firstOrNull()?.clinic?.label
                    ?: "Clinic 1"
                _bannerMessage.value = UiBannerMessage(
                    title = "Added ${hcpWithDetails.hcp.name} to Route",
                    message = "Added ${hcpWithDetails.hcp.name} ($clinicName) to ${_selectedDateIso.value} route and recalculated timings."
                )
            } catch (e: IllegalStateException) {
                _bannerMessage.value = UiBannerMessage(
                    title = "Weekly Visit Restriction",
                    message = e.message ?: WEEKLY_HCP_RESTRICTION_MESSAGE,
                    isError = true
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun archiveHcpTarget(hcp: HcpEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.archiveHcpTarget(hcp.id)
                val out = repository.generateAndSaveDailyPlans(
                    dateIso = _selectedDateIso.value,
                    allowedZones = _selectedBricks.value,
                    targetOverride = _targetVisits.value,
                    shiftStartMinute = _shiftStartMinute.value,
                    shiftEndMinute = _shiftEndMinute.value
                )
                _lastOptimizationOutcome.value = out
                refreshWeeklyBlockedForSelectedDate(_selectedDateIso.value)
            }
            _bannerMessage.value = UiBannerMessage(
                title = "HCP Removed",
                message = "${hcp.name} removed from your active territory list."
            )
        }
    }

    fun archiveMultipleHcpTargets(hcpsToRemove: List<HcpEntity>) {
        if (hcpsToRemove.isEmpty()) return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                withContext(Dispatchers.IO) {
                    repository.archiveHcpTargets(hcpsToRemove.map { it.id })
                    val out = repository.generateAndSaveDailyPlans(
                        dateIso = _selectedDateIso.value,
                        allowedZones = _selectedBricks.value,
                        targetOverride = _targetVisits.value,
                        shiftStartMinute = _shiftStartMinute.value,
                        shiftEndMinute = _shiftEndMinute.value
                    )
                    _lastOptimizationOutcome.value = out
                    refreshWeeklyBlockedForSelectedDate(_selectedDateIso.value)
                }
                _bannerMessage.value = UiBannerMessage(
                    title = "Removed ${hcpsToRemove.size} HCP(s)",
                    message = "Removed ${hcpsToRemove.size} selected doctor(s) from the active HCP list and updated route plans."
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun updateWorkSettings(newSettings: WorkSettings) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                repository.updateWorkSettings(newSettings)
                val out = if (database.territoryDao().getAllActiveHcps().isNotEmpty()) {
                    repository.generateAndSaveDailyPlans(
                        dateIso = _selectedDateIso.value,
                        allowedZones = _selectedBricks.value,
                        targetOverride = _targetVisits.value,
                        shiftStartMinute = _shiftStartMinute.value,
                        shiftEndMinute = _shiftEndMinute.value
                    )
                } else {
                    null
                }
                refreshWeeklyBlockedForSelectedDate(_selectedDateIso.value)
                out
            }
            if (outcome != null) {
                _lastOptimizationOutcome.value = outcome
            }
            _bannerMessage.value = UiBannerMessage(
                title = "Work Settings Saved",
                message = "Updated workday, shift, and route rules successfully."
            )
        }
    }

    fun executeNaturalLanguageCommand(command: String) {
        if (command.isBlank()) return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val currentHcps = hcpsWithDetails.value
                val currentPlan = activePlanForSelectedDate.value

                val cmdResult = withContext(Dispatchers.IO) {
                    aiAssistant.interpretNaturalLanguageCommand(
                        userPrompt = command,
                        currentPlan = currentPlan,
                        hcps = currentHcps,
                        allowRemoteAi = true
                    )
                }

                val parsedCmd = cmdResult.getOrNull() ?: return@launch
                _lastAiInterpretation.value = parsedCmd

                when (parsedCmd.intent) {
                    AiCommandIntent.PLAN_WEEK -> {
                        val weekRes = withContext(Dispatchers.IO) {
                            repository.generateWeeklySchedule(_selectedDateIso.value)
                        }
                        _weeklyPlanResult.value = weekRes
                        _bannerMessage.value = UiBannerMessage(
                            title = "Weekly Optimizer Applied (${parsedCmd.sourceLabel})",
                            message = parsedCmd.explanationText
                        )
                    }
                    AiCommandIntent.EXPLAIN_PLAN, AiCommandIntent.LIST_AT_RISK_HCPS -> {
                        _bannerMessage.value = UiBannerMessage(
                            title = "Grounded Assistant (${parsedCmd.sourceLabel})",
                            message = parsedCmd.explanationText
                        )
                    }
                    else -> {
                        val excludedZones = parsedCmd.excludedZone?.takeIf { it.isNotBlank() }?.let { setOf(it) }.orEmpty()
                        val prioritizedIds = if (parsedCmd.prioritizeOverdue) {
                            currentHcps.filter { it.coverage.isOverdue }.map { it.hcp.id }.toSet()
                        } else {
                            emptySet()
                        }

                        val outcome = withContext(Dispatchers.IO) {
                            repository.generateAndSaveDailyPlans(
                                dateIso = _selectedDateIso.value,
                                prioritizedHcpIds = prioritizedIds,
                                excludedZones = excludedZones,
                                specialtyFilter = parsedCmd.specialtyFilter,
                                targetOverride = parsedCmd.targetVisits
                            )
                        }
                        val best = outcome.alternatives.firstOrNull()
                        _bannerMessage.value = UiBannerMessage(
                            title = "Command Applied & Validated (${parsedCmd.sourceLabel})",
                            message = "${parsedCmd.explanationText} -> ${best?.plan?.uniqueHcpsCount ?: 0} stops validated."
                        )
                    }
                }
            } catch (e: Exception) {
                _bannerMessage.value = UiBannerMessage(
                    title = "Assistant Command Error",
                    message = e.localizedMessage ?: "Failed to apply command.",
                    isError = true
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun syncWithCloud() {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.synchronizeWithCloud()
                }
                result.fold(
                    onSuccess = { (syncedCount, conflicts) ->
                        _syncConflicts.value = conflicts
                        if (conflicts.isEmpty()) {
                            _bannerMessage.value = UiBannerMessage(
                                title = "Cloud Sync Complete",
                                message = "Synchronized $syncedCount record(s) with Cloud Firestore."
                            )
                        } else {
                            _bannerMessage.value = UiBannerMessage(
                                title = "Sync Completed with ${conflicts.size} Conflict(s)",
                                message = "Synced $syncedCount record(s); ${conflicts.size} conflict(s) flagged for review.",
                                isError = true
                            )
                        }
                    },
                    onFailure = { err ->
                        _bannerMessage.value = UiBannerMessage(
                            title = "Cloud Sync Offline / Deferred",
                            message = err.localizedMessage ?: "Local changes remain queued in Room.",
                            isError = true
                        )
                    }
                )
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun setChatModelTier(tier: GeminiChatModelTier) {
        _selectedChatModelTier.value = tier
    }

    fun setAutoRouteChatModel(autoRoute: Boolean) {
        _autoRouteChatModel.value = autoRoute
    }

    fun setChatRole(role: ChatbotRolePreset) {
        _selectedChatRole.value = role
    }

    fun dismissChatError() {
        if (_chatUiState.value is ChatUiState.Error) {
            _chatUiState.value = ChatUiState.Idle
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearChatHistory()
            repository.appendChatMessage(
                ChatMessageEntity(
                    role = "model",
                    content = "Conversation history cleared. Role active: **${_selectedChatRole.value.title}**. Ready for your next question!",
                    modelId = _selectedChatModelTier.value.modelId,
                    taskTierLabel = _selectedChatModelTier.value.shortTitle
                )
            )
            _chatUiState.value = ChatUiState.Idle
        }
    }

    fun sendChatMessage(
        userText: String,
        overrideTier: GeminiChatModelTier? = null
    ) {
        val trimmed = userText.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch {
            val activeTier = overrideTier ?: aiAssistant.selectModelTierForPrompt(
                prompt = trimmed,
                preferredTier = _selectedChatModelTier.value,
                autoRouteByComplexity = _autoRouteChatModel.value
            )
            _selectedChatModelTier.value = activeTier

            val priorHistory = withContext(Dispatchers.IO) {
                repository.getAllChatMessages()
            }

            withContext(Dispatchers.IO) {
                repository.appendChatMessage(
                    ChatMessageEntity(
                        role = "user",
                        content = trimmed,
                        modelId = activeTier.modelId,
                        taskTierLabel = activeTier.shortTitle
                    )
                )
            }

            _chatUiState.value = ChatUiState.Loading(activeTier.modelId)

            val result = aiAssistant.streamMultiTurnChat(
                userMessage = trimmed,
                priorHistory = priorHistory,
                modelTier = activeTier,
                rolePreset = _selectedChatRole.value,
                selectedDateIso = _selectedDateIso.value,
                currentPlan = activePlanForSelectedDate.value,
                hcps = hcpsWithDetails.value,
                latestImportReport = _importPreviewReport.value,
                onStreamingChunk = { partial ->
                    _chatUiState.value = ChatUiState.Streaming(
                        partialText = partial,
                        modelId = activeTier.modelId,
                        tierLabel = activeTier.shortTitle
                    )
                }
            )

            result.fold(
                onSuccess = { replyText ->
                    withContext(Dispatchers.IO) {
                        repository.appendChatMessage(
                            ChatMessageEntity(
                                role = "model",
                                content = replyText,
                                modelId = activeTier.modelId,
                                taskTierLabel = activeTier.shortTitle
                            )
                        )
                    }
                    _chatUiState.value = ChatUiState.Success(
                        finalText = replyText,
                        modelId = activeTier.modelId
                    )
                },
                onFailure = { err ->
                    _chatUiState.value = ChatUiState.Error(
                        message = err.localizedMessage ?: "Gemini chat request failed (${activeTier.modelId}).",
                        attemptedModelId = activeTier.modelId
                    )
                }
            )
        }
    }

    companion object {
        fun formatOptimizationFailureMessage(error: Throwable): String {
            var root: Throwable = error
            val seen = mutableSetOf<Throwable>()
            while (root.cause != null && root.cause !== root && seen.add(root)) {
                root = root.cause!!
            }
            val detail = root.localizedMessage?.trim().takeIf { !it.isNullOrBlank() }
                ?: error.localizedMessage?.trim().takeIf { !it.isNullOrBlank() }
                ?: root::class.java.simpleName
            return when (root) {
                is java.io.IOException ->
                    "Storage or network error during route planning: $detail. Please check connectivity or saved settings and try again."
                is IllegalArgumentException ->
                    "Invalid planning input: $detail. Please verify your shift hours, dates, or clinic schedules."
                is IllegalStateException ->
                    "Route planner encountered an invalid state: $detail."
                else ->
                    "Could not complete route optimization (${root::class.java.simpleName}): $detail."
            }
        }

        private fun defaultMondayDateIso(): String {
            val cal = Calendar.getInstance()
            return String.format(
                Locale.US,
                "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
        }
    }
}
