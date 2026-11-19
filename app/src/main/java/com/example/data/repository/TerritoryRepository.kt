package com.example.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.local.HcpDao
import com.example.data.local.TerritoryDao
import com.example.data.model.AvailabilityWindowEntity
import com.example.data.model.BrickEntity
import com.example.data.model.BrickNormalizer
import com.example.data.model.CalendarWeekCalculator
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ClinicEntity
import com.example.data.model.ClinicWithAvailability
import com.example.data.model.CloudHcp
import com.example.data.model.CloudVisit
import com.example.data.model.DailyPlanEntity
import com.example.data.model.DailyPlanWithStops
import com.example.data.model.HcpEntity
import com.example.data.model.HcpWithDetails
import com.example.data.model.ImportLogEntity
import com.example.data.model.ImportMappingProfileEntity
import com.example.data.model.EMPTY_PREFERRED_SPECIALTIES_MESSAGE
import com.example.data.model.PlannedStopEntity
import com.example.data.model.PlanningPriorityWeights
import com.example.data.model.SpecialtyNormalizer
import com.example.data.model.SyncConflict
import com.example.data.model.VisitEntity
import com.example.data.model.WEEKLY_HCP_RESTRICTION_MESSAGE
import com.example.data.model.WeeklyHcpBlockInfo
import com.example.data.model.WorkSettings
import com.example.data.model.buildManualUnselectedSpecialtyWarning
import com.example.domain.coverage.CoverageCalculator
import com.example.domain.importer.ExcelTerritoryImporter
import com.example.domain.importer.ImportMode
import com.example.domain.importer.ImportPreviewReport
import com.example.domain.optimizer.OptimizationOutcome
import com.example.domain.optimizer.OptimizationRequest
import com.example.domain.optimizer.PlanValidator
import com.example.domain.optimizer.RouteOptimizerEngine
import com.example.domain.optimizer.WeeklyPlanResult
import com.example.domain.parser.AvailabilityScheduleParser
import com.example.domain.parser.ParsingMethod
import com.example.domain.parser.ScheduleClassification
import com.example.domain.parser.ScheduleParseStatus
import com.example.domain.parser.ScheduleValidationStatus
import com.example.domain.parser.ScheduleValidator
import com.example.domain.routing.DefaultRoutingService
import com.example.domain.routing.RoutingProvider
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.workSettingsDataStore by preferencesDataStore(name = "territory_work_settings")

private object ProcessWorkSettingsStore {
    @Volatile
    var primaryAppIdentityHash: Int? = null
    val stateByAppIdentity = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<WorkSettings>>()

    fun stateFor(context: Context): kotlinx.coroutines.flow.MutableStateFlow<WorkSettings> {
        val appContext = context.applicationContext ?: context
        val id = System.identityHashCode(appContext)
        return stateByAppIdentity.getOrPut(id) {
            kotlinx.coroutines.flow.MutableStateFlow(WorkSettings())
        }
    }

    fun canUseDiskDataStore(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        val id = System.identityHashCode(appContext)
        val currentPrimary = primaryAppIdentityHash
        if (currentPrimary == null) {
            synchronized(this) {
                if (primaryAppIdentityHash == null) {
                    primaryAppIdentityHash = id
                    return true
                }
            }
        }
        return primaryAppIdentityHash == id
    }
}

class TerritoryRepository(
    private val context: Context,
    private val dao: TerritoryDao,
    private val hcpDao: HcpDao? = null,
    private val cloudSyncRepository: CloudSyncRepository? = null,
    val routingProvider: RoutingProvider = DefaultRoutingService(dao)
) {
    val optimizerEngine = RouteOptimizerEngine(routingProvider)
    private val planningMutex = Mutex()
    private var diversityGenerationCounter = 0

    private object PrefKeys {
        val DAILY_TARGET = intPreferencesKey("daily_hcp_target")
        val MAX_DUTY_SPAN = intPreferencesKey("max_duty_span_minutes")
        val HOME_ADDRESS = stringPreferencesKey("home_address")
        val HOME_LAT = doublePreferencesKey("home_lat")
        val HOME_LNG = doublePreferencesKey("home_lng")
        val RETURN_HOME = booleanPreferencesKey("return_home_required")
        val VISIT_DURATION = intPreferencesKey("default_visit_duration")
        val SAME_LOCATION_TRANSITION = intPreferencesKey("same_location_transition_minutes")
        val WEEKEND_DAYS_CSV = stringPreferencesKey("weekend_days_iso_csv")
        val WORKING_DAYS_CSV = stringPreferencesKey("working_days_iso_csv")
        val FIRST_DAY_OF_WEEK = intPreferencesKey("first_day_of_week_iso")
        val BREAK_ENABLED = booleanPreferencesKey("break_enabled")
        val BREAK_DURATION = intPreferencesKey("break_duration")
        val SAFETY_BUFFER = intPreferencesKey("traffic_safety_buffer")
        val REMAINING_WORKDAYS = intPreferencesKey("remaining_quarter_workdays")
        val OVERDUE_DAYS = intPreferencesKey("overdue_threshold_days")
        val BLANK_CELL_OVERWRITE = booleanPreferencesKey("blank_cell_overwrites_verified")
        val CLOUD_SYNC_ENABLED = booleanPreferencesKey("cloud_sync_enabled")
        val WEIGHT_SPECIALTY_PCT = intPreferencesKey("priority_weight_specialty_pct")
        val WEIGHT_REMAINING_VISITS_PCT = intPreferencesKey("priority_weight_remaining_visits_pct")
        val WEIGHT_TRAVEL_DISTANCE_PCT = intPreferencesKey("priority_weight_travel_distance_pct")
        val ENFORCE_WEEKLY_HCP_LIMIT = booleanPreferencesKey("enforce_weekly_hcp_visit_limit")
        val PREFERRED_SPECIALTIES_RANKED = stringPreferencesKey("preferred_specialties_ranked_pipe")
        val AVAILABLE_SPECIALTIES_CATALOG = stringPreferencesKey("available_specialties_catalog_pipe")
        val HAS_USER_CONFIGURED_PREFERRED_SPECIALTIES = booleanPreferencesKey("has_user_configured_preferred_specialties")
        const val EMPTY_SENTINEL = "__EMPTY__"
    }

    private val appSettingsStateFlow: kotlinx.coroutines.flow.MutableStateFlow<WorkSettings> =
        ProcessWorkSettingsStore.stateFor(context)

    private fun mapPrefsToWorkSettings(prefs: androidx.datastore.preferences.core.Preferences): WorkSettings {
        val weekendSet = prefs[PrefKeys.WEEKEND_DAYS_CSV]
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it in 1..7 }
            ?.toSet()
            ?: setOf(4, 5, 6) // Default: Thursday, Friday, Saturday
        val workingSet = prefs[PrefKeys.WORKING_DAYS_CSV]
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it in 1..7 }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: (1..7).filter { it !in weekendSet }.toSet()

        val rawSpec = prefs[PrefKeys.WEIGHT_SPECIALTY_PCT] ?: PlanningPriorityWeights.DEFAULT_SPECIALTY_PCT
        val rawRem = prefs[PrefKeys.WEIGHT_REMAINING_VISITS_PCT] ?: PlanningPriorityWeights.DEFAULT_REMAINING_VISITS_PCT
        val rawDist = prefs[PrefKeys.WEIGHT_TRAVEL_DISTANCE_PCT] ?: PlanningPriorityWeights.DEFAULT_TRAVEL_DISTANCE_PCT
        val (normSpec, normRem, normDist) = PlanningPriorityWeights.normalizeTriple(rawSpec, rawRem, rawDist)

        val storedPreferredRaw = prefs[PrefKeys.PREFERRED_SPECIALTIES_RANKED]
        val preferredSpecialtiesRanked: List<String> = when {
            storedPreferredRaw == null -> SpecialtyNormalizer.DEFAULT_INITIAL_SPECIALTIES
            storedPreferredRaw == PrefKeys.EMPTY_SENTINEL || storedPreferredRaw.isBlank() -> emptyList()
            else -> SpecialtyNormalizer.extractUniqueSpecialties(storedPreferredRaw.split("|"))
        }

        val storedCatalogRaw = prefs[PrefKeys.AVAILABLE_SPECIALTIES_CATALOG].orEmpty()
        val availableCatalog = if (storedCatalogRaw.isBlank()) {
            emptyList()
        } else {
            SpecialtyNormalizer.extractUniqueSpecialties(storedCatalogRaw.split("|"))
        }

        return WorkSettings(
            dailyHcpTarget = prefs[PrefKeys.DAILY_TARGET] ?: 8,
            maxDutySpanMinutes = prefs[PrefKeys.MAX_DUTY_SPAN] ?: 480,
            homeAddress = prefs[PrefKeys.HOME_ADDRESS] ?: "Sheikh Zayed City, Giza, Egypt",
            homeLatitude = prefs[PrefKeys.HOME_LAT] ?: 30.0444,
            homeLongitude = prefs[PrefKeys.HOME_LNG] ?: 30.9760,
            returnHomeRequired = prefs[PrefKeys.RETURN_HOME] ?: true,
            defaultVisitDurationMinutes = prefs[PrefKeys.VISIT_DURATION] ?: 15,
            sameLocationTransitionMinutes = prefs[PrefKeys.SAME_LOCATION_TRANSITION] ?: 0,
            weekendDaysIso = weekendSet,
            workingDaysIso = workingSet,
            firstDayOfWeekIso = prefs[PrefKeys.FIRST_DAY_OF_WEEK] ?: 7,
            breakEnabled = prefs[PrefKeys.BREAK_ENABLED] ?: true,
            breakDurationMinutes = prefs[PrefKeys.BREAK_DURATION] ?: 15,
            trafficSafetyBufferPercent = prefs[PrefKeys.SAFETY_BUFFER] ?: 10,
            remainingQuarterWorkdays = prefs[PrefKeys.REMAINING_WORKDAYS] ?: 42,
            overdueThresholdDays = prefs[PrefKeys.OVERDUE_DAYS] ?: 14,
            priorityWeightSpecialtyPercent = normSpec,
            priorityWeightRemainingVisitsPercent = normRem,
            priorityWeightTravelDistancePercent = normDist,
            preferredSpecialtiesRanked = preferredSpecialtiesRanked,
            availableSpecialtiesCatalog = availableCatalog,
            hasUserConfiguredPreferredSpecialties = prefs[PrefKeys.HAS_USER_CONFIGURED_PREFERRED_SPECIALTIES] ?: (storedPreferredRaw != null),
            enforceWeeklyHcpVisitLimit = prefs[PrefKeys.ENFORCE_WEEKLY_HCP_LIMIT] ?: true,
            weightPriority = normSpec / 100.0,
            weightCoverage = normRem / 100.0,
            weightDistance = normDist / 100.0,
            weightTravelTime = normDist / 100.0,
            blankCellOverwritesVerified = prefs[PrefKeys.BLANK_CELL_OVERWRITE] ?: false,
            cloudSyncEnabled = prefs[PrefKeys.CLOUD_SYNC_ENABLED] ?: true
        )
    }

    val workSettingsFlow: Flow<WorkSettings> = if (ProcessWorkSettingsStore.canUseDiskDataStore(context)) {
        context.workSettingsDataStore.data
            .map { prefs ->
                val mapped = mapPrefsToWorkSettings(prefs)
                appSettingsStateFlow.value = mapped
                mapped
            }
            .catch { cause ->
                // Flow.catch intercepts upstream DataStore errors only and preserves Flow exception
                // transparency when downstream collectors abort (e.g., Flow.first() throwing AbortFlowException).
                if (cause is CancellationException) throw cause
                if (cause is IOException) {
                    Log.e("TerritoryRepository", "Failed reading WorkSettings from DataStore; falling back to in-memory state", cause)
                    emitAll(appSettingsStateFlow)
                } else {
                    Log.e("TerritoryRepository", "Unexpected upstream failure in workSettingsFlow", cause)
                    throw cause
                }
            }
    } else {
        appSettingsStateFlow
    }

    suspend fun updateWorkSettings(updated: WorkSettings) {
        val cleanWeekend = updated.weekendDaysIso.filter { it in 1..7 }.toSet()
        val cleanWorking = updated.workingDaysIso.filter { it in 1..7 && it !in cleanWeekend }.toSet()
            .ifEmpty { (1..7).filter { it !in cleanWeekend }.toSet().ifEmpty { setOf(7, 1, 2, 3) } }
        val (normSpec, normRem, normDist) = PlanningPriorityWeights.normalizeTriple(
            updated.priorityWeightSpecialtyPercent,
            updated.priorityWeightRemainingVisitsPercent,
            updated.priorityWeightTravelDistancePercent
        )
        val cleanPreferred = SpecialtyNormalizer.extractUniqueSpecialties(updated.preferredSpecialtiesRanked)
        val existingInMemoryCatalog = appSettingsStateFlow.value.availableSpecialtiesCatalog
        val cleanCatalog = SpecialtyNormalizer.mergeAvailableSpecialties(
            existingAvailable = existingInMemoryCatalog + updated.availableSpecialtiesCatalog,
            hcpSpecialties = emptyList(),
            preferredSpecialties = cleanPreferred
        )
        val normalizedSettings = updated.copy(
            dailyHcpTarget = updated.dailyHcpTarget.coerceIn(1, 20),
            maxDutySpanMinutes = updated.maxDutySpanMinutes.coerceIn(120, 720),
            homeLatitude = updated.homeLatitude.coerceIn(-90.0, 90.0),
            homeLongitude = updated.homeLongitude.coerceIn(-180.0, 180.0),
            defaultVisitDurationMinutes = updated.defaultVisitDurationMinutes.coerceIn(5, 90),
            sameLocationTransitionMinutes = updated.sameLocationTransitionMinutes.coerceIn(0, 30),
            weekendDaysIso = cleanWeekend,
            workingDaysIso = cleanWorking,
            firstDayOfWeekIso = updated.firstDayOfWeekIso.coerceIn(1, 7),
            breakDurationMinutes = updated.breakDurationMinutes.coerceIn(5, 60),
            trafficSafetyBufferPercent = updated.trafficSafetyBufferPercent.coerceIn(0, 50),
            remainingQuarterWorkdays = updated.remainingQuarterWorkdays.coerceIn(1, 90),
            overdueThresholdDays = updated.overdueThresholdDays.coerceIn(3, 45),
            priorityWeightSpecialtyPercent = normSpec,
            priorityWeightRemainingVisitsPercent = normRem,
            priorityWeightTravelDistancePercent = normDist,
            preferredSpecialtiesRanked = cleanPreferred,
            availableSpecialtiesCatalog = cleanCatalog,
            hasUserConfiguredPreferredSpecialties = true,
            weightPriority = normSpec / 100.0,
            weightCoverage = normRem / 100.0,
            weightDistance = normDist / 100.0,
            weightTravelTime = normDist / 100.0
        )
        appSettingsStateFlow.value = normalizedSettings

        if (ProcessWorkSettingsStore.canUseDiskDataStore(context)) {
            try {
                context.workSettingsDataStore.edit { prefs ->
                    prefs[PrefKeys.DAILY_TARGET] = normalizedSettings.dailyHcpTarget
                    prefs[PrefKeys.MAX_DUTY_SPAN] = normalizedSettings.maxDutySpanMinutes
                    prefs[PrefKeys.HOME_ADDRESS] = normalizedSettings.homeAddress
                    prefs[PrefKeys.HOME_LAT] = normalizedSettings.homeLatitude
                    prefs[PrefKeys.HOME_LNG] = normalizedSettings.homeLongitude
                    prefs[PrefKeys.RETURN_HOME] = normalizedSettings.returnHomeRequired
                    prefs[PrefKeys.VISIT_DURATION] = normalizedSettings.defaultVisitDurationMinutes
                    prefs[PrefKeys.SAME_LOCATION_TRANSITION] = normalizedSettings.sameLocationTransitionMinutes
                    prefs[PrefKeys.WEEKEND_DAYS_CSV] = cleanWeekend.sorted().joinToString(",")
                    prefs[PrefKeys.WORKING_DAYS_CSV] = cleanWorking.sorted().joinToString(",")
                    prefs[PrefKeys.FIRST_DAY_OF_WEEK] = normalizedSettings.firstDayOfWeekIso
                    prefs[PrefKeys.BREAK_ENABLED] = normalizedSettings.breakEnabled
                    prefs[PrefKeys.BREAK_DURATION] = normalizedSettings.breakDurationMinutes
                    prefs[PrefKeys.SAFETY_BUFFER] = normalizedSettings.trafficSafetyBufferPercent
                    prefs[PrefKeys.REMAINING_WORKDAYS] = normalizedSettings.remainingQuarterWorkdays
                    prefs[PrefKeys.OVERDUE_DAYS] = normalizedSettings.overdueThresholdDays
                    prefs[PrefKeys.WEIGHT_SPECIALTY_PCT] = normSpec
                    prefs[PrefKeys.WEIGHT_REMAINING_VISITS_PCT] = normRem
                    prefs[PrefKeys.WEIGHT_TRAVEL_DISTANCE_PCT] = normDist
                    prefs[PrefKeys.PREFERRED_SPECIALTIES_RANKED] = if (cleanPreferred.isEmpty()) {
                        PrefKeys.EMPTY_SENTINEL
                    } else {
                        cleanPreferred.joinToString("|")
                    }
                    if (cleanCatalog.isNotEmpty()) {
                        val existingCat = prefs[PrefKeys.AVAILABLE_SPECIALTIES_CATALOG]
                            ?.split("|")
                            .orEmpty()
                        val mergedCat = SpecialtyNormalizer.mergeAvailableSpecialties(
                            existingAvailable = existingCat,
                            hcpSpecialties = cleanCatalog,
                            preferredSpecialties = cleanPreferred
                        )
                        prefs[PrefKeys.AVAILABLE_SPECIALTIES_CATALOG] = mergedCat.joinToString("|")
                    }
                    prefs[PrefKeys.HAS_USER_CONFIGURED_PREFERRED_SPECIALTIES] = true
                    prefs[PrefKeys.ENFORCE_WEEKLY_HCP_LIMIT] = normalizedSettings.enforceWeeklyHcpVisitLimit
                    prefs[PrefKeys.BLANK_CELL_OVERWRITE] = normalizedSettings.blankCellOverwritesVerified
                    prefs[PrefKeys.CLOUD_SYNC_ENABLED] = normalizedSettings.cloudSyncEnabled
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.e("TerritoryRepository", "Failed writing WorkSettings to DataStore", e)
            }
        }
    }

    /**
     * Dynamically retrieves all available medical specialties from the active HCP database,
     * imported Excel catalog, and saved preferred specialties (Section 2.1 & Section 7).
     */
    val availableSpecialtiesFlow: Flow<List<String>> = combine(
        dao.observeActiveHcps(),
        workSettingsFlow
    ) { activeHcps, settings ->
        SpecialtyNormalizer.mergeAvailableSpecialties(
            existingAvailable = settings.availableSpecialtiesCatalog,
            hcpSpecialties = activeHcps.filter { it.isActive && !it.isDeleted }.map { it.specialty },
            preferredSpecialties = settings.preferredSpecialtiesRanked
        )
    }

    suspend fun getAvailableSpecialties(): List<String> = withContext(Dispatchers.IO) {
        val settings = workSettingsFlow.first()
        val activeHcps = dao.getAllActiveHcps().filter { it.isActive && !it.isDeleted }
        SpecialtyNormalizer.mergeAvailableSpecialties(
            existingAvailable = settings.availableSpecialtiesCatalog,
            hcpSpecialties = activeHcps.map { it.specialty },
            preferredSpecialties = settings.preferredSpecialtiesRanked
        )
    }

    val hcpsWithDetailsFlow: Flow<List<HcpWithDetails>> = combine(
        dao.observeActiveHcps(),
        dao.observeAllClinics(),
        dao.observeAllAvailabilityWindows(),
        dao.observeAllVisits(),
        workSettingsFlow
    ) { hcps, clinics, windows, visits, settings ->
        val windowsByClinic = windows.groupBy { it.clinicId }
        val clinicsByHcp = clinics.groupBy { it.hcpId }
        val visitsByHcp = visits.groupBy { it.hcpId }

        hcps.map { hcp ->
            val hcpClinics = clinicsByHcp[hcp.id].orEmpty().map { c ->
                ClinicWithAvailability(
                    clinic = c,
                    windows = windowsByClinic[c.id].orEmpty().filter {
                        it.clinicId == c.id && (it.hcpId == 0L || it.hcpId == hcp.id)
                    }
                )
            }
            val hcpVisits = visitsByHcp[hcp.id].orEmpty()
            val coverage = CoverageCalculator.calculateMetrics(
                hcp = hcp,
                clinics = hcpClinics,
                visits = hcpVisits,
                settings = settings
            )
            HcpWithDetails(
                hcp = hcp,
                clinics = hcpClinics,
                visits = hcpVisits,
                coverage = coverage
            )
        }
    }

    val dailyPlansWithStopsFlow: Flow<List<DailyPlanWithStops>> = combine(
        dao.observeAllDailyPlans(),
        dao.observeAllPlannedStops()
    ) { plans, stops ->
        val stopsByPlan = stops.groupBy { it.planId }
        plans.map { plan ->
            DailyPlanWithStops(
                plan = plan,
                stops = stopsByPlan[plan.id].orEmpty().sortedBy { it.stopOrder }
            )
        }
    }

    val activeBricksFlow: Flow<List<String>> = combine(
        dao.observeActiveBricks(),
        dao.observeAllClinics(),
        dao.observeActiveHcps()
    ) { brickEntities, clinics, activeHcps ->
        val activeHcpIds = activeHcps.filter { it.isActive && !it.isDeleted }.map { it.id }.toSet()
        val clinicZones = clinics
            .filter { it.isActive && it.hcpId in activeHcpIds }
            .map { it.zone }
        val registryNames = brickEntities.filter { it.isActive }.map { it.name }
        BrickNormalizer.extractUniqueIndividualBricks(clinicZones + registryNames)
    }

    val importLogsFlow: Flow<List<ImportLogEntity>> = dao.observeImportLogs()
    val chatMessagesFlow: Flow<List<ChatMessageEntity>> = dao.observeChatMessages()
    val importMappingProfilesFlow: Flow<List<ImportMappingProfileEntity>> = dao.observeImportMappingProfiles()

    suspend fun appendChatMessage(message: ChatMessageEntity): Long {
        return dao.insertChatMessage(message)
    }

    suspend fun getAllChatMessages(): List<ChatMessageEntity> {
        return dao.getAllChatMessages()
    }

    suspend fun clearChatHistory() {
        dao.clearChatMessages()
    }

    suspend fun saveImportMappingProfile(profile: ImportMappingProfileEntity): Long {
        return dao.insertImportMappingProfile(profile)
    }

    /**
     * Rebuilds the global `bricks` table (`BrickEntity`) from all active clinics' individual
     * normalized brick names. Never creates composite bricks ("Brick A • Brick B").
     */
    suspend fun rebuildBrickRegistry(): List<BrickEntity> = withContext(Dispatchers.IO) {
        val activeHcpIds = dao.getAllActiveHcps().filter { it.isActive && !it.isDeleted }.map { it.id }.toSet()
        val activeClinicZones = dao.getAllClinics()
            .filter { it.isActive && it.hcpId in activeHcpIds }
            .map { it.zone }

        val uniqueBrickNames = BrickNormalizer.extractUniqueIndividualBricks(activeClinicZones)
        val entities = uniqueBrickNames.map { brickName ->
            BrickEntity(
                name = brickName,
                normalizedName = BrickNormalizer.normalizeBrickKey(brickName),
                isActive = true
            )
        }
        dao.deleteAllBricks()
        if (entities.isNotEmpty()) {
            dao.insertBricks(entities)
        }
        dao.getAllActiveBricks()
    }

    /**
     * Section 8: Database Migration & Composite Brick Cleanup.
     * Inspects existing HCPs and clinics, identifies any previously generated composite bricks
     * (e.g., "Brick A • Brick B"), separates clinic records from clinic-level zone data where possible,
     * flags records that cannot be reliably separated for review without guessing, preserves all
     * HCPs, clinics, completed visits, and visit history, and rebuilds the global brick registry.
     */
    suspend fun migrateCompositeBricksAndRebuildRegistry(): Int = withContext(Dispatchers.IO) {
        val activeHcps = dao.getAllActiveHcps()
        val allClinics = dao.getAllClinics()
        val clinicsByHcp = allClinics.groupBy { it.hcpId }
        var migratedCount = 0

        for (hcp in activeHcps) {
            val hcpClinics = clinicsByHcp[hcp.id].orEmpty().sortedBy { it.clinicIndex }.toMutableList()
            val hcpZoneIsComposite = BrickNormalizer.isCompositeBrickName(hcp.territoryZone)
            val anyClinicZoneIsComposite = hcpClinics.any { BrickNormalizer.isCompositeBrickName(it.zone) }
            val c1HasMultipleUrls = hcpClinics.firstOrNull()?.let {
                ExcelTerritoryImporter.splitLocationReferences(it.originalLocationText).size >= 2
            } ?: false

            if (!hcpZoneIsComposite && !anyClinicZoneIsComposite && !c1HasMultipleUrls) {
                // Still normalize whitespace on clinic zones if needed
                for (clinic in hcpClinics) {
                    val normZone = BrickNormalizer.normalizeBrickName(clinic.zone).ifBlank { "Unspecified Zone" }
                    if (normZone != clinic.zone) {
                        val cNum = if (clinic.clinicIndex > 0) clinic.clinicIndex else 1
                        val newLabel = if (normZone != "Unspecified Zone") "Clinic $cNum ($normZone)" else "Clinic $cNum"
                        dao.updateClinic(clinic.copy(zone = normZone, label = newLabel))
                        migratedCount++
                    }
                }
                continue
            }

            // Collect individual brick names from clinic zones and hcp.territoryZone
            val candidateIndividualBricks = BrickNormalizer.extractUniqueIndividualBricks(
                hcpClinics.map { it.zone } + listOf(hcp.territoryZone)
            )

            if (hcpClinics.size == 1 && (candidateIndividualBricks.size >= 2 || c1HasMultipleUrls)) {
                val c1 = hcpClinics[0]
                val brick1 = candidateIndividualBricks.getOrNull(0) ?: BrickNormalizer.normalizeBrickName(c1.zone)
                val brick2 = candidateIndividualBricks.getOrNull(1) ?: brick1

                val urlRefs = ExcelTerritoryImporter.splitLocationReferences(c1.originalLocationText)
                val schedRes = AvailabilityScheduleParser.resolveMultiClinicSchedules(
                    rawCombinedSchedule = c1.rawScheduleText,
                    clinic1Zone = brick1,
                    clinic1Location = urlRefs.getOrNull(0).orEmpty(),
                    clinic2Zone = brick2,
                    clinic2Location = urlRefs.getOrNull(1).orEmpty(),
                    hasExplicitSecondClinic = true
                )

                val c1Loc = urlRefs.getOrNull(0).orEmpty()
                val c2Loc = if (urlRefs.size == 2) urlRefs[1] else ""
                val c1Coords = ExcelTerritoryImporter.extractCoordinates(
                    rawLocation = c1Loc,
                    zoneHint = brick1,
                    rowSeed = "${hcp.targetId}-c1-${hcp.name}"
                )
                val c2Coords = if (urlRefs.size == 2) {
                    ExcelTerritoryImporter.extractCoordinates(
                        rawLocation = c2Loc,
                        zoneHint = brick2,
                        rowSeed = "${hcp.targetId}-c2-${hcp.name}"
                    )
                } else {
                    com.example.domain.importer.ExtractedCoordinates(null, null, false, "UNVERIFIED")
                }

                val c1Conf = !schedRes.relationshipAmbiguous &&
                    schedRes.clinic1Result.status == ScheduleParseStatus.PARSED_HIGH_CONFIDENCE
                val c2Conf = !schedRes.relationshipAmbiguous &&
                    schedRes.clinic2Result.status == ScheduleParseStatus.PARSED_HIGH_CONFIDENCE

                val updatedC1 = c1.copy(
                    clinicIndex = 1,
                    label = if (brick1.isNotBlank()) "Clinic 1 ($brick1)" else "Clinic 1",
                    zone = brick1.ifBlank { "Unspecified Zone" },
                    originalLocationText = c1Loc.ifBlank { c1.originalLocationText },
                    googleMapsUrl = if (c1Coords.isGoogleMapsUrl) c1Loc else "",
                    latitude = c1Coords.latitude,
                    longitude = c1Coords.longitude,
                    geocodingStatus = c1Coords.geocodingStatus,
                    rawScheduleText = schedRes.clinic1RawText.ifBlank { c1.rawScheduleText },
                    scheduleParseStatus = schedRes.clinic1Result.status.name,
                    scheduleAmbiguityReason = schedRes.clinic1Result.ambiguityReason,
                    userConfirmedSchedule = c1Conf
                )
                dao.updateClinic(updatedC1)
                dao.deleteWindowsForClinic(c1.id)
                if (schedRes.clinic1Result.windows.isNotEmpty()) {
                    dao.insertAvailabilityWindows(schedRes.clinic1Result.toEntities(c1.id, isConfirmed = c1Conf))
                }

                val newC2 = ClinicEntity(
                    hcpId = hcp.id,
                    clinicIndex = 2,
                    label = if (brick2.isNotBlank()) "Clinic 2 ($brick2)" else "Clinic 2",
                    zone = brick2.ifBlank { "Unspecified Zone" },
                    originalLocationText = c2Loc,
                    googleMapsUrl = if (c2Coords.isGoogleMapsUrl) c2Loc else "",
                    latitude = c2Coords.latitude,
                    longitude = c2Coords.longitude,
                    geocodingStatus = c2Coords.geocodingStatus,
                    rawScheduleText = schedRes.clinic2RawText.ifBlank { c1.rawScheduleText },
                    scheduleParseStatus = schedRes.clinic2Result.status.name,
                    scheduleAmbiguityReason = schedRes.clinic2Result.ambiguityReason.ifBlank {
                        if (urlRefs.size != 2) "Separated from composite brick; confirm Clinic 2 location and schedule." else ""
                    },
                    userConfirmedSchedule = c2Conf
                )
                val newC2Id = dao.insertClinic(newC2)
                if (schedRes.clinic2Result.windows.isNotEmpty()) {
                    dao.insertAvailabilityWindows(schedRes.clinic2Result.toEntities(newC2Id, isConfirmed = c2Conf))
                }
                migratedCount++
            } else {
                // HCP already has multiple clinic records (or 1 clinic with a composite zone that splits into 1)
                for ((idx, clinic) in hcpClinics.withIndex()) {
                    val splitParts = BrickNormalizer.splitCompositeBrickName(clinic.zone)
                    val targetBrick = when {
                        splitParts.size > idx -> splitParts[idx]
                        splitParts.isNotEmpty() -> splitParts.first()
                        else -> BrickNormalizer.normalizeBrickName(clinic.zone)
                    }.ifBlank { "Unspecified Zone" }

                    val cNum = if (clinic.clinicIndex > 0) clinic.clinicIndex else (idx + 1)
                    val newLabel = if (targetBrick != "Unspecified Zone") "Clinic $cNum ($targetBrick)" else "Clinic $cNum"
                    if (clinic.zone != targetBrick || clinic.label != newLabel) {
                        dao.updateClinic(clinic.copy(zone = targetBrick, label = newLabel))
                        migratedCount++
                    }
                }
            }

            // Ensure hcp.territoryZone holds ONLY Clinic 1's individual normalized brick name (never "Brick A • Brick B")
            val primaryBrick = candidateIndividualBricks.firstOrNull().orEmpty()
            if (hcp.territoryZone != primaryBrick) {
                dao.updateHcp(hcp.copy(territoryZone = primaryBrick))
                migratedCount++
            }
        }

        rebuildBrickRegistry()
        migratedCount
    }

    /**
     * Executes a validated Excel/CSV import according to [ImportMode], preserving visit history
     * and verified schedules unless materially changed, and recording a reversible [ImportLogEntity].
     */
    suspend fun executeImport(
        report: ImportPreviewReport,
        mode: ImportMode,
        blankOverwritesVerified: Boolean = false,
        allowOverwriteUserConfirmed: Boolean = false
    ): ImportLogEntity = withContext(Dispatchers.IO) {
        val addedTargetIds = mutableListOf<String>()
        var addedCount = 0
        var updatedCount = 0
        var unchangedCount = 0

        val existingHcpsByTarget = dao.getAllActiveHcps().associateBy { it.targetId }.toMutableMap()
        if (mode == ImportMode.REPLACE_ALL) {
            dao.deleteAllPlannedStops()
            dao.deleteAllAvailabilityWindows()
            dao.deleteAllClinics()
            dao.deleteAllBricks()
            dao.deleteAllHcps()
            existingHcpsByTarget.clear()
        }
        val existingClinicsByHcp = dao.getAllClinics().groupBy { it.hcpId }.mapValues { it.value.toMutableList() }.toMutableMap()
        val allWindowsToInsert = mutableListOf<AvailabilityWindowEntity>()

        for (row in report.rows) {
            if (row.changeStatus == "INVALID") continue

            // If an HCP appears on multiple rows in the same Excel file (e.g. second row for a 2nd clinic),
            // attach any distinct clinics from that duplicate row as Clinic 2 / Clinic 3 on the existing HCP,
            // or update the matching clinic by stable identity if it already exists.
            if (row.changeStatus == "DUPLICATE_IN_FILE") {
                val targetHcp = existingHcpsByTarget[row.targetId] ?: dao.getHcpByTargetId(row.targetId)
                if (targetHcp != null && mode != ImportMode.ADD_NEW_ONLY) {
                    val currentClinics = existingClinicsByHcp.getOrPut(targetHcp.id) {
                        dao.getClinicsForHcp(targetHcp.id).toMutableList()
                    }
                    for (cImport in row.clinics) {
                        val existingMatch = currentClinics.firstOrNull { existing ->
                            (existing.zone.equals(cImport.zone, ignoreCase = true) &&
                                existing.originalLocationText.equals(cImport.rawLocation, ignoreCase = true) &&
                                (cImport.zone.isNotBlank() || cImport.rawLocation.isNotBlank())) ||
                                (existing.zone.equals(cImport.zone, ignoreCase = true) &&
                                    existing.rawScheduleText.equals(cImport.rawScheduleText, ignoreCase = true))
                        }
                        val validatedRes = ScheduleValidator.validate(cImport.scheduleParseResult)
                        val isConf = (validatedRes.status == ScheduleParseStatus.PARSED_HIGH_CONFIDENCE ||
                            validatedRes.status == ScheduleParseStatus.CONFIRMED) &&
                            validatedRes.timeInfoType.name == "CLINIC_AVAILABILITY_RANGE" &&
                            validatedRes.validationStatus == ScheduleValidationStatus.VALID
                        val effSheet = cImport.sourceSheet.ifBlank { report.selectedWorksheet }
                        val effRow = if (cImport.sourceRow > 0) cImport.sourceRow else row.rowNumber
                        val effOrigExcel = cImport.originalExcelScheduleText.ifBlank { cImport.rawScheduleText }

                        if (existingMatch == null) {
                            val nextIdx = (currentClinics.maxOfOrNull { it.clinicIndex } ?: 1) + 1
                            val newLabel = if (cImport.zone.isNotBlank() && cImport.zone != "Unspecified Zone") {
                                "Clinic $nextIdx (${cImport.zone})"
                            } else {
                                "Clinic $nextIdx"
                            }
                            val entityToInsert = ClinicEntity(
                                hcpId = targetHcp.id,
                                clinicIndex = nextIdx,
                                label = newLabel,
                                zone = cImport.zone,
                                originalLocationText = cImport.rawLocation,
                                googleMapsUrl = cImport.googleMapsUrl,
                                latitude = cImport.latitude,
                                longitude = cImport.longitude,
                                geocodingStatus = cImport.geocodingStatus,
                                rawScheduleText = cImport.rawScheduleText,
                                originalExcelScheduleText = effOrigExcel,
                                normalizedDaysCsv = validatedRes.normalizedDays.joinToString(","),
                                exactStartMinute = validatedRes.exactStartMinute,
                                exactEndMinute = validatedRes.exactEndMinute,
                                timeInfoType = validatedRes.timeInfoType.name,
                                bookingInstructions = validatedRes.bookingInstructions,
                                aiInterpretedScheduleText = cImport.aiInterpretedScheduleText,
                                scheduleParseStatus = validatedRes.status.name,
                                scheduleAmbiguityReason = validatedRes.ambiguityReason,
                                userConfirmedSchedule = isConf,
                                lastVerifiedEpochMs = if (isConf) System.currentTimeMillis() else null,
                                scheduleClassification = validatedRes.classification.name,
                                excludedDaysCsv = validatedRes.excludedDays.joinToString(","),
                                parsingMethod = validatedRes.parsingMethod.name,
                                parsingConfidence = validatedRes.parsingConfidence,
                                validationStatus = validatedRes.validationStatus.name,
                                locationDescription = validatedRes.locationDescription,
                                sourceFile = cImport.sourceFile.ifBlank { report.fileName },
                                sourceSheet = effSheet,
                                sourceRow = effRow,
                                sourceColumn = cImport.sourceColumn,
                                isManualUserConfirmed = false
                            )
                            val newCid = dao.insertClinic(entityToInsert)
                            currentClinics.add(entityToInsert.copy(id = newCid))
                            allWindowsToInsert.addAll(
                                validatedRes.toEntities(
                                    clinicId = newCid,
                                    isConfirmed = isConf,
                                    hcpId = targetHcp.id,
                                    originalExcelText = effOrigExcel,
                                    sourceSheet = effSheet,
                                    sourceRow = effRow,
                                    sourceColumn = cImport.sourceColumn,
                                    parsingStatus = validatedRes.status.name
                                )
                            )
                        } else if (!(existingMatch.isManualUserConfirmed && !allowOverwriteUserConfirmed)) {
                            val updatedDupClinic = existingMatch.copy(
                                rawScheduleText = cImport.rawScheduleText.ifBlank { existingMatch.rawScheduleText },
                                originalExcelScheduleText = effOrigExcel.ifBlank { existingMatch.originalExcelScheduleText },
                                normalizedDaysCsv = validatedRes.normalizedDays.joinToString(","),
                                exactStartMinute = validatedRes.exactStartMinute,
                                exactEndMinute = validatedRes.exactEndMinute,
                                timeInfoType = validatedRes.timeInfoType.name,
                                bookingInstructions = validatedRes.bookingInstructions,
                                aiInterpretedScheduleText = cImport.aiInterpretedScheduleText.ifBlank { existingMatch.aiInterpretedScheduleText },
                                scheduleParseStatus = validatedRes.status.name,
                                scheduleAmbiguityReason = validatedRes.ambiguityReason,
                                userConfirmedSchedule = isConf,
                                scheduleClassification = validatedRes.classification.name,
                                excludedDaysCsv = validatedRes.excludedDays.joinToString(","),
                                parsingMethod = validatedRes.parsingMethod.name,
                                parsingConfidence = validatedRes.parsingConfidence,
                                validationStatus = validatedRes.validationStatus.name,
                                sourceFile = cImport.sourceFile.ifBlank { report.fileName },
                                sourceSheet = effSheet,
                                sourceRow = effRow,
                                sourceColumn = cImport.sourceColumn.ifBlank { existingMatch.sourceColumn }
                            )
                            dao.updateClinic(updatedDupClinic)
                            currentClinics.removeAll { it.id == existingMatch.id }
                            currentClinics.add(updatedDupClinic)
                            dao.deleteWindowsForClinic(existingMatch.id)
                            allWindowsToInsert.addAll(
                                validatedRes.toEntities(
                                    clinicId = existingMatch.id,
                                    isConfirmed = isConf,
                                    hcpId = targetHcp.id,
                                    originalExcelText = effOrigExcel,
                                    sourceSheet = effSheet,
                                    sourceRow = effRow,
                                    sourceColumn = cImport.sourceColumn,
                                    parsingStatus = validatedRes.status.name
                                )
                            )
                        }
                    }
                    val primaryZone = currentClinics
                        .map { BrickNormalizer.normalizeBrickName(it.zone) }
                        .firstOrNull { it.isNotBlank() }
                        .orEmpty()
                    if (primaryZone.isNotBlank() && primaryZone != targetHcp.territoryZone) {
                        val updatedHcp = targetHcp.copy(territoryZone = primaryZone)
                        dao.updateHcp(updatedHcp)
                        existingHcpsByTarget[row.targetId] = updatedHcp
                    }
                }
                continue
            }

            val existingHcp = if (mode == ImportMode.REPLACE_ALL) null else (existingHcpsByTarget[row.targetId] ?: dao.getHcpByTargetId(row.targetId))
            if (existingHcp != null && mode == ImportMode.ADD_NEW_ONLY) {
                unchangedCount++
                continue
            }

            // Never concatenate zone 1 and zone 2 into a composite brick; store primary clinic's individual brick on HCP
            val primaryZone = row.clinics
                .map { BrickNormalizer.normalizeBrickName(it.zone) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
                .ifBlank { BrickNormalizer.splitCompositeBrickName(existingHcp?.territoryZone.orEmpty()).firstOrNull().orEmpty() }

            val priorityScore = when {
                row.frequencyPerQuarter >= 8 -> 85
                row.frequencyPerQuarter >= 6 -> 75
                else -> 65
            }

            val hcpId: Long
            val normalizedRowSpec = SpecialtyNormalizer.normalizeSpecialtyDisplay(row.specialty)
                .ifBlank { row.specialty.trim() }
            if (existingHcp == null) {
                val newEntity = HcpEntity(
                    targetId = row.targetId,
                    name = row.hcpName,
                    specialty = normalizedRowSpec,
                    quarterlyTarget = row.frequencyPerQuarter,
                    priorityScore = priorityScore,
                    territoryZone = primaryZone,
                    sourceWorkbook = report.fileName,
                    customFieldsJson = ExcelTerritoryImporter.customFieldsToJson(row.customFields),
                    syncStatus = "PENDING"
                )
                hcpId = dao.insertHcp(newEntity)
                existingHcpsByTarget[row.targetId] = newEntity.copy(id = hcpId)
                addedTargetIds.add(row.targetId)
                addedCount++
            } else {
                val mergedName = if (row.hcpName.isNotBlank() || blankOverwritesVerified) row.hcpName else existingHcp.name
                val mergedSpec = if (normalizedRowSpec.isNotBlank() || blankOverwritesVerified) normalizedRowSpec else existingHcp.specialty
                val mergedZone = if (primaryZone.isNotBlank() || blankOverwritesVerified) primaryZone else BrickNormalizer.splitCompositeBrickName(existingHcp.territoryZone).firstOrNull().orEmpty()
                val updatedEntity = existingHcp.copy(
                    name = mergedName,
                    specialty = mergedSpec,
                    quarterlyTarget = row.frequencyPerQuarter,
                    territoryZone = mergedZone,
                    sourceWorkbook = report.fileName,
                    version = existingHcp.version + 1,
                    syncStatus = "PENDING",
                    updatedAtEpochMs = System.currentTimeMillis()
                )
                dao.updateHcp(updatedEntity)
                existingHcpsByTarget[row.targetId] = updatedEntity
                hcpId = existingHcp.id
                updatedCount++
            }

            val currentHcpClinics = existingClinicsByHcp.getOrPut(hcpId) {
                dao.getClinicsForHcp(hcpId).toMutableList()
            }
            val existingClinicsByIndex = currentHcpClinics.associateBy { it.clinicIndex }
            for (cImport in row.clinics) {
                // When an HCP has multiple clinics from multi-row Excel entries and row.clinics has 1 entry,
                // match by exact (zone + location) or (zone + schedule) first so reordered rows update the right clinicId.
                val prevClinic = if (row.clinics.size == 1 && currentHcpClinics.size > 1) {
                    currentHcpClinics.firstOrNull { existing ->
                        existing.zone.equals(cImport.zone, ignoreCase = true) &&
                            (existing.originalLocationText.equals(cImport.rawLocation, ignoreCase = true) ||
                                existing.rawScheduleText.equals(cImport.rawScheduleText, ignoreCase = true))
                    } ?: existingClinicsByIndex[cImport.clinicIndex]
                } else {
                    existingClinicsByIndex[cImport.clinicIndex]
                }
                val validatedRes = ScheduleValidator.validate(cImport.scheduleParseResult)
                val effSheet = cImport.sourceSheet.ifBlank { report.selectedWorksheet }
                val effRow = if (cImport.sourceRow > 0) cImport.sourceRow else row.rowNumber
                val effOrigExcel = cImport.originalExcelScheduleText.ifBlank { cImport.rawScheduleText }

                if (prevClinic == null) {
                    val isConfirmed = (validatedRes.status == ScheduleParseStatus.PARSED_HIGH_CONFIDENCE ||
                        validatedRes.status == ScheduleParseStatus.CONFIRMED) &&
                        validatedRes.timeInfoType.name == "CLINIC_AVAILABILITY_RANGE" &&
                        validatedRes.validationStatus == ScheduleValidationStatus.VALID
                    val clinicEntity = ClinicEntity(
                        hcpId = hcpId,
                        clinicIndex = cImport.clinicIndex,
                        label = cImport.label,
                        zone = cImport.zone,
                        originalLocationText = cImport.rawLocation,
                        googleMapsUrl = cImport.googleMapsUrl,
                        latitude = cImport.latitude,
                        longitude = cImport.longitude,
                        geocodingStatus = cImport.geocodingStatus,
                        rawScheduleText = cImport.rawScheduleText,
                        originalExcelScheduleText = effOrigExcel,
                        normalizedDaysCsv = validatedRes.normalizedDays.joinToString(","),
                        exactStartMinute = validatedRes.exactStartMinute,
                        exactEndMinute = validatedRes.exactEndMinute,
                        timeInfoType = validatedRes.timeInfoType.name,
                        bookingInstructions = validatedRes.bookingInstructions,
                        aiInterpretedScheduleText = cImport.aiInterpretedScheduleText,
                        scheduleParseStatus = validatedRes.status.name,
                        scheduleAmbiguityReason = validatedRes.ambiguityReason,
                        userConfirmedSchedule = isConfirmed,
                        lastVerifiedEpochMs = if (isConfirmed) System.currentTimeMillis() else null,
                        scheduleClassification = validatedRes.classification.name,
                        excludedDaysCsv = validatedRes.excludedDays.joinToString(","),
                        parsingMethod = validatedRes.parsingMethod.name,
                        parsingConfidence = validatedRes.parsingConfidence,
                        validationStatus = validatedRes.validationStatus.name,
                        locationDescription = validatedRes.locationDescription,
                        sourceFile = cImport.sourceFile.ifBlank { report.fileName },
                        sourceSheet = effSheet,
                        sourceRow = effRow,
                        sourceColumn = cImport.sourceColumn,
                        isManualUserConfirmed = false
                    )
                    val newClinicId = dao.insertClinic(clinicEntity)
                    currentHcpClinics.add(clinicEntity.copy(id = newClinicId))
                    allWindowsToInsert.addAll(
                        validatedRes.toEntities(
                            clinicId = newClinicId,
                            isConfirmed = isConfirmed,
                            hcpId = hcpId,
                            originalExcelText = effOrigExcel,
                            sourceSheet = effSheet,
                            sourceRow = effRow,
                            sourceColumn = cImport.sourceColumn,
                            parsingStatus = validatedRes.status.name
                        )
                    )
                } else {
                    val keepPrevCoords = prevClinic.latitude != null && prevClinic.longitude != null &&
                        (cImport.latitude == null || cImport.longitude == null) && !blankOverwritesVerified
                    // CRITICAL Part 10: USER_CONFIRMED schedules are NEVER overwritten during re-import unless explicitly approved
                    val protectUserConfirmed = prevClinic.isManualUserConfirmed && !allowOverwriteUserConfirmed
                    val keepPrevSchedule = protectUserConfirmed ||
                        (prevClinic.userConfirmedSchedule && cImport.rawScheduleText.isBlank() && !blankOverwritesVerified)

                    val isConf = (validatedRes.status == ScheduleParseStatus.PARSED_HIGH_CONFIDENCE ||
                        validatedRes.status == ScheduleParseStatus.CONFIRMED) &&
                        validatedRes.timeInfoType.name == "CLINIC_AVAILABILITY_RANGE" &&
                        validatedRes.validationStatus == ScheduleValidationStatus.VALID

                    val preservedOriginalExcel = cImport.originalExcelScheduleText
                        .ifBlank { prevClinic.originalExcelScheduleText }
                        .ifBlank { cImport.rawScheduleText }
                        .ifBlank { prevClinic.rawScheduleText }

                    val updatedClinic = prevClinic.copy(
                        label = cImport.label.ifBlank { prevClinic.label },
                        zone = cImport.zone.ifBlank { prevClinic.zone },
                        originalLocationText = cImport.rawLocation.ifBlank { prevClinic.originalLocationText },
                        googleMapsUrl = cImport.googleMapsUrl.ifBlank { prevClinic.googleMapsUrl },
                        latitude = if (keepPrevCoords) prevClinic.latitude else cImport.latitude,
                        longitude = if (keepPrevCoords) prevClinic.longitude else cImport.longitude,
                        geocodingStatus = if (keepPrevCoords) prevClinic.geocodingStatus else cImport.geocodingStatus,
                        rawScheduleText = if (keepPrevSchedule) prevClinic.rawScheduleText else cImport.rawScheduleText,
                        originalExcelScheduleText = preservedOriginalExcel,
                        normalizedDaysCsv = if (keepPrevSchedule) prevClinic.normalizedDaysCsv else validatedRes.normalizedDays.joinToString(","),
                        exactStartMinute = if (keepPrevSchedule) prevClinic.exactStartMinute else validatedRes.exactStartMinute,
                        exactEndMinute = if (keepPrevSchedule) prevClinic.exactEndMinute else validatedRes.exactEndMinute,
                        timeInfoType = if (keepPrevSchedule) prevClinic.timeInfoType else validatedRes.timeInfoType.name,
                        bookingInstructions = if (keepPrevSchedule) prevClinic.bookingInstructions else validatedRes.bookingInstructions,
                        aiInterpretedScheduleText = if (keepPrevSchedule) prevClinic.aiInterpretedScheduleText else cImport.aiInterpretedScheduleText.ifBlank { prevClinic.aiInterpretedScheduleText },
                        scheduleParseStatus = if (keepPrevSchedule) prevClinic.scheduleParseStatus else validatedRes.status.name,
                        scheduleAmbiguityReason = if (keepPrevSchedule) prevClinic.scheduleAmbiguityReason else validatedRes.ambiguityReason,
                        userConfirmedSchedule = keepPrevSchedule || isConf,
                        scheduleClassification = if (keepPrevSchedule) prevClinic.scheduleClassification else validatedRes.classification.name,
                        excludedDaysCsv = if (keepPrevSchedule) prevClinic.excludedDaysCsv else validatedRes.excludedDays.joinToString(","),
                        parsingMethod = if (keepPrevSchedule) prevClinic.parsingMethod else validatedRes.parsingMethod.name,
                        parsingConfidence = if (keepPrevSchedule) prevClinic.parsingConfidence else validatedRes.parsingConfidence,
                        validationStatus = if (keepPrevSchedule) prevClinic.validationStatus else validatedRes.validationStatus.name,
                        locationDescription = if (keepPrevSchedule && prevClinic.locationDescription.isNotBlank()) prevClinic.locationDescription else validatedRes.locationDescription.ifBlank { prevClinic.locationDescription },
                        sourceFile = cImport.sourceFile.ifBlank { report.fileName },
                        sourceSheet = effSheet,
                        sourceRow = effRow,
                        sourceColumn = cImport.sourceColumn.ifBlank { prevClinic.sourceColumn },
                        isManualUserConfirmed = if (allowOverwriteUserConfirmed) false else prevClinic.isManualUserConfirmed
                    )
                    dao.updateClinic(updatedClinic)
                    currentHcpClinics.removeAll { it.id == prevClinic.id }
                    currentHcpClinics.add(updatedClinic)
                    if (!keepPrevSchedule) {
                        dao.deleteWindowsForClinic(prevClinic.id)
                        allWindowsToInsert.addAll(
                            validatedRes.toEntities(
                                clinicId = prevClinic.id,
                                isConfirmed = isConf,
                                hcpId = hcpId,
                                originalExcelText = preservedOriginalExcel,
                                sourceSheet = effSheet,
                                sourceRow = effRow,
                                sourceColumn = updatedClinic.sourceColumn,
                                parsingStatus = validatedRes.status.name
                            )
                        )
                    }
                }
            }
        }

        if (allWindowsToInsert.isNotEmpty()) {
            dao.insertAvailabilityWindows(allWindowsToInsert)
        }

        rebuildBrickRegistry()

        // Section 7: Detect newly introduced specialties and add them to the available specialty list.
        // Preserve existing specialty selections and rankings (never auto-select newly imported specialties,
        // and never delete a saved preference merely because a specialty is absent from a new import).
        val importedSpecialties = report.rows
            .filter { it.changeStatus != "INVALID" }
            .map { it.specialty }
        val allCurrentHcpSpecialties = dao.getAllActiveHcps().map { it.specialty }
        val inMemoryCurrent = appSettingsStateFlow.value
        val mergedInMemoryCatalog = SpecialtyNormalizer.mergeAvailableSpecialties(
            existingAvailable = inMemoryCurrent.availableSpecialtiesCatalog,
            hcpSpecialties = importedSpecialties + allCurrentHcpSpecialties,
            preferredSpecialties = emptyList()
        )
        appSettingsStateFlow.value = inMemoryCurrent.copy(
            availableSpecialtiesCatalog = mergedInMemoryCatalog
        )
        if (ProcessWorkSettingsStore.canUseDiskDataStore(context)) {
            try {
                context.workSettingsDataStore.edit { prefs ->
                    val existingCatalog = prefs[PrefKeys.AVAILABLE_SPECIALTIES_CATALOG]
                        ?.split("|")
                        .orEmpty()
                    val mergedCatalog = SpecialtyNormalizer.mergeAvailableSpecialties(
                        existingAvailable = existingCatalog + mergedInMemoryCatalog,
                        hcpSpecialties = importedSpecialties + allCurrentHcpSpecialties,
                        preferredSpecialties = emptyList()
                    )
                    if (mergedCatalog.isNotEmpty()) {
                        prefs[PrefKeys.AVAILABLE_SPECIALTIES_CATALOG] = mergedCatalog.joinToString("|")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.e("TerritoryRepository", "Failed persisting imported specialties catalog to DataStore", e)
            }
        }

        val log = ImportLogEntity(
            fileName = report.fileName,
            worksheetName = report.selectedWorksheet,
            importMode = mode.name,
            detectedRows = report.totalDetectedRows,
            addedHcps = addedCount,
            updatedHcps = updatedCount,
            unchangedHcps = unchangedCount,
            ambiguousSchedulesCount = report.ambiguousSchedulesCount,
            unverifiedLocationsCount = report.missingOrUnverifiedLocationsCount,
            addedHcpTargetIdsCsv = addedTargetIds.joinToString(",")
        )
        val logId = dao.insertImportLog(log)
        log.copy(id = logId)
    }

    suspend fun rollbackImport(log: ImportLogEntity) {
        if (log.isRolledBack) return
        val targetIds = log.addedHcpTargetIdsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
        for (tid in targetIds) {
            val hcp = dao.getHcpByTargetId(tid) ?: continue
            val clinics = dao.getClinicsForHcp(hcp.id)
            for (c in clinics) {
                dao.deleteWindowsForClinic(c.id)
            }
            dao.deleteClinicsForHcp(hcp.id)
        }
        if (targetIds.isNotEmpty()) {
            dao.deleteHcpsByTargetIds(targetIds)
        }
        dao.updateImportLog(log.copy(isRolledBack = true))
    }

    /**
     * Section 3 & Section 6: Enforces that [clinicId] exists and strictly belongs to [hcpId].
     * Any schedule save/update where `clinicId` does not belong to `hcpId` is rejected with [IllegalArgumentException].
     */
    suspend fun validateAndRequireClinicOwnership(hcpId: Long, clinicId: Long): ClinicEntity {
        if (hcpId <= 0L || clinicId <= 0L) {
            throw IllegalArgumentException("Schedule rejected: invalid hcpId=$hcpId or clinicId=$clinicId.")
        }
        val dbClinic = dao.getClinicById(clinicId)
            ?: throw IllegalArgumentException("Schedule rejected: clinicId=$clinicId does not exist.")
        if (dbClinic.hcpId != hcpId) {
            throw IllegalArgumentException(
                "Schedule rejected: clinicId=$clinicId belongs to hcpId=${dbClinic.hcpId}, not specified hcpId=$hcpId."
            )
        }
        return dbClinic
    }

    /**
     * Saves a schedule explicitly associated with ([hcpId], [clinicId]), rejecting any mismatched ownership.
     */
    suspend fun saveScheduleForHcpAndClinic(
        hcpId: Long,
        clinicId: Long,
        rawScheduleText: String,
        windows: List<AvailabilityWindowEntity>,
        sourceSheet: String = "",
        sourceRow: Int = 0,
        sourceColumn: String = ""
    ) {
        val dbClinic = validateAndRequireClinicOwnership(hcpId, clinicId)
        for (win in windows) {
            if (win.clinicId != 0L && win.clinicId != clinicId) {
                throw IllegalArgumentException(
                    "Schedule rejected: window clinicId=${win.clinicId} does not match target clinicId=$clinicId."
                )
            }
            if (win.hcpId != 0L && win.hcpId != hcpId) {
                throw IllegalArgumentException(
                    "Schedule rejected: window hcpId=${win.hcpId} does not match target hcpId=$hcpId."
                )
            }
        }
        val clinicWithMeta = dbClinic.copy(
            sourceSheet = sourceSheet.ifBlank { dbClinic.sourceSheet },
            sourceRow = if (sourceRow > 0) sourceRow else dbClinic.sourceRow,
            sourceColumn = sourceColumn.ifBlank { dbClinic.sourceColumn }
        )
        updateClinicScheduleAndConfirm(
            clinic = clinicWithMeta,
            updatedWindows = windows,
            updatedRawScheduleText = rawScheduleText,
            expectedHcpId = hcpId
        )
    }

    suspend fun updateClinicScheduleAndConfirm(
        clinic: ClinicEntity,
        updatedWindows: List<AvailabilityWindowEntity>,
        updatedRawScheduleText: String? = null,
        updatedZone: String? = null,
        updatedLocationText: String? = null,
        updatedLat: Double? = null,
        updatedLng: Double? = null,
        expectedHcpId: Long = clinic.hcpId
    ) {
        if (clinic.hcpId != expectedHcpId) {
            throw IllegalArgumentException(
                "Schedule rejected: clinic.hcpId=${clinic.hcpId} does not match expectedHcpId=$expectedHcpId."
            )
        }
        val dbClinic = validateAndRequireClinicOwnership(expectedHcpId, clinic.id)
        for (win in updatedWindows) {
            if (win.clinicId != 0L && win.clinicId != clinic.id) {
                throw IllegalArgumentException(
                    "Schedule rejected: window clinicId=${win.clinicId} does not match target clinicId=${clinic.id}."
                )
            }
            if (win.hcpId != 0L && win.hcpId != expectedHcpId) {
                throw IllegalArgumentException(
                    "Schedule rejected: window hcpId=${win.hcpId} does not match target hcpId=$expectedHcpId."
                )
            }
        }

        val cleanTypedSched = updatedRawScheduleText?.trim()
        val prevSched = clinic.rawScheduleText.trim()

        // If user typed a new schedule text that differs from previous rawScheduleText, or if windows list is empty,
        // parse the typed schedule text so edits to the schedule text field always take effect!
        val effectiveWindows: List<AvailabilityWindowEntity> = when {
            !cleanTypedSched.isNullOrBlank() && cleanTypedSched != prevSched -> {
                val parsedFromText = AvailabilityScheduleParser.parse(cleanTypedSched)
                val existingSig = updatedWindows.map { Triple(it.dayOfWeek, it.startMinuteOfDay, it.endMinuteOfDay) }.toSet()
                val oldClinWins = dao.getWindowsForClinic(clinic.id)
                val oldSig = oldClinWins.map { Triple(it.dayOfWeek, it.startMinuteOfDay, it.endMinuteOfDay) }.toSet()
                // If user didn't manually change the window list (existingSig == oldSig) and typed a valid schedule, use the newly parsed windows
                if (parsedFromText.windows.isNotEmpty() && (updatedWindows.isEmpty() || existingSig == oldSig)) {
                    parsedFromText.toEntities(
                        clinicId = clinic.id,
                        isConfirmed = true,
                        hcpId = clinic.hcpId,
                        originalExcelText = clinic.originalExcelScheduleText.ifBlank { cleanTypedSched },
                        sourceSheet = clinic.sourceSheet,
                        sourceRow = clinic.sourceRow,
                        sourceColumn = clinic.sourceColumn,
                        parsingStatus = ScheduleParseStatus.CONFIRMED.name
                    )
                } else {
                    updatedWindows
                }
            }
            updatedWindows.isEmpty() && !cleanTypedSched.isNullOrBlank() -> {
                val parsed = AvailabilityScheduleParser.parse(cleanTypedSched)
                parsed.toEntities(
                    clinicId = clinic.id,
                    isConfirmed = true,
                    hcpId = clinic.hcpId,
                    originalExcelText = clinic.originalExcelScheduleText.ifBlank { cleanTypedSched },
                    sourceSheet = clinic.sourceSheet,
                    sourceRow = clinic.sourceRow,
                    sourceColumn = clinic.sourceColumn,
                    parsingStatus = ScheduleParseStatus.CONFIRMED.name
                )
            }
            else -> updatedWindows
        }

        val summaryFromWindows = AvailabilityScheduleParser.formatWindowsSummary(effectiveWindows).joinToString(" | ")
        val finalRawSchedule = when {
            !cleanTypedSched.isNullOrBlank() && cleanTypedSched != prevSched -> cleanTypedSched
            summaryFromWindows.isNotBlank() -> summaryFromWindows
            !cleanTypedSched.isNullOrBlank() -> cleanTypedSched
            else -> clinic.rawScheduleText
        }

        val effectiveZone = updatedZone?.trim()?.ifBlank { clinic.zone } ?: clinic.zone
        val clinicNum = if (clinic.clinicIndex > 0) clinic.clinicIndex else 1
        val CustomLabelUnchanged = updatedZone == null || updatedZone.trim() == clinic.zone
        val effectiveLabel = if (CustomLabelUnchanged && clinic.label.isNotBlank()) {
            clinic.label
        } else if (effectiveZone.isNotBlank() && effectiveZone != "Unspecified Zone") {
            "Clinic $clinicNum ($effectiveZone)"
        } else {
            "Clinic $clinicNum"
        }

        val effectiveLocText = updatedLocationText?.trim() ?: clinic.originalLocationText
        val extractedFromLoc = if (effectiveLocText != clinic.originalLocationText || effectiveZone != clinic.zone) {
            ExcelTerritoryImporter.extractCoordinates(
                rawLocation = effectiveLocText,
                zoneHint = effectiveZone,
                rowSeed = "${clinic.hcpId}-${clinic.clinicIndex}-$effectiveLabel"
            )
        } else null

        val finalLat = updatedLat ?: extractedFromLoc?.latitude ?: clinic.latitude
        val finalLng = updatedLng ?: extractedFromLoc?.longitude ?: clinic.longitude
        val finalMapsUrl = when {
            effectiveLocText.startsWith("http", ignoreCase = true) -> effectiveLocText
            clinic.googleMapsUrl.isNotBlank() -> clinic.googleMapsUrl
            else -> ""
        }

        val confirmedDays = effectiveWindows.map { it.dayOfWeek }.distinct().sorted()
        val confirmedDaysCsv = confirmedDays.joinToString(",")
        val confirmedStartMin = effectiveWindows.minOfOrNull { it.startMinuteOfDay }
        val confirmedEndMin = effectiveWindows.maxOfOrNull { it.endMinuteOfDay }
        val manualClassification = when {
            effectiveWindows.isEmpty() -> ScheduleClassification.REQUIRES_MANUAL_CLARIFICATION.name
            effectiveWindows.map { Triple(it.startMinuteOfDay, it.endMinuteOfDay, it.endsNextDay) }.distinct().size > 1 ||
                effectiveWindows.groupBy { it.dayOfWeek }.values.any { it.size > 1 } -> ScheduleClassification.MULTIPLE_WEEKLY_WINDOWS.name
            confirmedDays.size == 7 -> ScheduleClassification.DAILY_DEFAULT_WITH_TIME.name
            else -> ScheduleClassification.EXPLICIT_WEEKDAYS_AND_TIME.name
        }

        val confirmedClinic = dbClinic.copy(
            label = effectiveLabel,
            zone = effectiveZone,
            originalLocationText = effectiveLocText,
            googleMapsUrl = finalMapsUrl,
            latitude = finalLat,
            longitude = finalLng,
            geocodingStatus = if (finalLat != null && finalLng != null) "VERIFIED" else clinic.geocodingStatus,
            rawScheduleText = finalRawSchedule,
            originalExcelScheduleText = clinic.originalExcelScheduleText.ifBlank { clinic.rawScheduleText },
            normalizedDaysCsv = confirmedDaysCsv,
            exactStartMinute = confirmedStartMin,
            exactEndMinute = confirmedEndMin,
            timeInfoType = "CLINIC_AVAILABILITY_RANGE",
            bookingInstructions = "",
            aiInterpretedScheduleText = summaryFromWindows,
            scheduleParseStatus = ScheduleParseStatus.CONFIRMED.name,
            scheduleAmbiguityReason = "",
            userConfirmedSchedule = true,
            lastVerifiedEpochMs = System.currentTimeMillis(),
            scheduleClassification = manualClassification,
            excludedDaysCsv = "",
            parsingMethod = ParsingMethod.USER_CONFIRMED.name,
            parsingConfidence = 1.0,
            validationStatus = if (effectiveWindows.isNotEmpty()) {
                ScheduleValidationStatus.VALID.name
            } else {
                ScheduleValidationStatus.NEEDS_CLARIFICATION.name
            },
            sourceSheet = clinic.sourceSheet.ifBlank { dbClinic.sourceSheet },
            sourceRow = if (clinic.sourceRow > 0) clinic.sourceRow else dbClinic.sourceRow,
            sourceColumn = clinic.sourceColumn.ifBlank { dbClinic.sourceColumn },
            isManualUserConfirmed = true
        )
        dao.updateClinic(confirmedClinic)
        dao.deleteWindowsForClinic(clinic.id)
        dao.insertAvailabilityWindows(
            effectiveWindows.map {
                it.copy(
                    id = 0L,
                    clinicId = clinic.id,
                    hcpId = clinic.hcpId,
                    isConfirmed = true,
                    originalExcelText = confirmedClinic.originalExcelScheduleText,
                    parsedTimeWindows = summaryFromWindows,
                    sourceSheet = confirmedClinic.sourceSheet,
                    sourceRow = confirmedClinic.sourceRow,
                    sourceColumn = confirmedClinic.sourceColumn,
                    parsingStatus = ScheduleParseStatus.CONFIRMED.name
                )
            }
        )

        // Keep parent HCP territoryZone synced with Clinic 1's individual normalized brick (never combined with •)
        val parentHcp = dao.getHcpById(clinic.hcpId)
        if (parentHcp != null) {
            val allClinics = dao.getClinicsForHcp(parentHcp.id).sortedBy { it.clinicIndex }
            val primaryZone = allClinics.map { BrickNormalizer.normalizeBrickName(it.zone) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            if (primaryZone.isNotBlank() && primaryZone != parentHcp.territoryZone) {
                dao.updateHcp(
                    parentHcp.copy(
                        territoryZone = primaryZone,
                        version = parentHcp.version + 1,
                        syncStatus = "PENDING",
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
            }
        }
        rebuildBrickRegistry()
    }

    suspend fun saveFullHcpAndClinicEdits(
        hcp: HcpEntity,
        updatedName: String,
        updatedSpecialty: String,
        updatedQuarterlyTarget: Int,
        baselineCompletedCount: Int,
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
        val cleanUpdatedZone = BrickNormalizer.normalizeBrickName(updatedClinicZone)
        if (clinic != null) {
            updateClinicScheduleAndConfirm(
                clinic = clinic,
                updatedWindows = updatedWindows,
                updatedRawScheduleText = updatedClinicRawSchedule,
                updatedZone = cleanUpdatedZone,
                updatedLocationText = updatedClinicLocation,
                updatedLat = updatedLat,
                updatedLng = updatedLng,
                expectedHcpId = hcp.id
            )
        }

        val refreshedClinics = dao.getClinicsForHcp(hcp.id).sortedBy { it.clinicIndex }
        val primaryZone = refreshedClinics.map { BrickNormalizer.normalizeBrickName(it.zone) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .ifBlank { cleanUpdatedZone.ifBlank { BrickNormalizer.splitCompositeBrickName(hcp.territoryZone).firstOrNull().orEmpty() } }

        val updatedHcpEntity = hcp.copy(
            name = updatedName.trim().ifBlank { hcp.name },
            specialty = updatedSpecialty.trim().ifBlank { hcp.specialty },
            quarterlyTarget = updatedQuarterlyTarget.coerceIn(1, 60),
            territoryZone = primaryZone,
            importedBaselineVisits = baselineCompletedCount.coerceIn(0, 60),
            baselineQuarterLabel = quarterLabel.trim().ifBlank { hcp.baselineQuarterLabel },
            historyIncomplete = historyIncomplete,
            version = hcp.version + 1,
            syncStatus = "PENDING",
            updatedAtEpochMs = System.currentTimeMillis()
        )
        if (hcpDao != null) {
            hcpDao.updateTarget(updatedHcpEntity)
        } else {
            dao.updateHcp(updatedHcpEntity)
        }
        rebuildBrickRegistry()
    }

    suspend fun addClinicToHcp(
        hcp: HcpEntity,
        zone: String,
        locationUrlOrAddress: String,
        rawScheduleText: String
    ): ClinicEntity {
        val existingClinics = dao.getClinicsForHcp(hcp.id)
        val nextIndex = (existingClinics.maxOfOrNull { it.clinicIndex } ?: existingClinics.size) + 1
        val cleanZone = BrickNormalizer.normalizeBrickName(zone)
            .ifBlank { BrickNormalizer.splitCompositeBrickName(hcp.territoryZone).firstOrNull().orEmpty().ifBlank { "Territory Clinic" } }
        val cleanLoc = locationUrlOrAddress.trim()
        val cleanSched = rawScheduleText.trim().ifBlank { "Sunday and Tuesday 5-8 PM" }
        val coords = ExcelTerritoryImporter.extractCoordinates(
            rawLocation = cleanLoc,
            zoneHint = cleanZone,
            rowSeed = "${hcp.targetId}-c$nextIndex-${hcp.name}"
        )
        val parsed = ScheduleValidator.validate(AvailabilityScheduleParser.parse(cleanSched))
        val newClinic = ClinicEntity(
            hcpId = hcp.id,
            clinicIndex = nextIndex,
            label = "Clinic $nextIndex ($cleanZone)",
            zone = cleanZone,
            originalLocationText = cleanLoc,
            googleMapsUrl = if (coords.isGoogleMapsUrl) cleanLoc else "",
            latitude = coords.latitude ?: 31.1150,
            longitude = coords.longitude ?: 29.8250,
            geocodingStatus = "VERIFIED",
            rawScheduleText = cleanSched,
            originalExcelScheduleText = cleanSched,
            normalizedDaysCsv = parsed.normalizedDays.joinToString(","),
            exactStartMinute = parsed.exactStartMinute,
            exactEndMinute = parsed.exactEndMinute,
            timeInfoType = "CLINIC_AVAILABILITY_RANGE",
            scheduleParseStatus = ScheduleParseStatus.CONFIRMED.name,
            scheduleAmbiguityReason = "",
            userConfirmedSchedule = true,
            lastVerifiedEpochMs = System.currentTimeMillis(),
            scheduleClassification = parsed.classification.name,
            excludedDaysCsv = parsed.excludedDays.joinToString(","),
            parsingMethod = ParsingMethod.USER_CONFIRMED.name,
            parsingConfidence = 1.0,
            validationStatus = ScheduleValidationStatus.VALID.name,
            sourceColumn = "Manual Clinic $nextIndex",
            isManualUserConfirmed = true
        )
        val newId = dao.insertClinic(newClinic)
        val windows = if (parsed.windows.isNotEmpty()) {
            parsed.toEntities(
                clinicId = newId,
                isConfirmed = true,
                hcpId = hcp.id,
                originalExcelText = cleanSched,
                sourceColumn = "Manual Clinic $nextIndex",
                parsingStatus = ScheduleParseStatus.CONFIRMED.name
            )
        } else {
            emptyList()
        }
        if (windows.isNotEmpty()) {
            dao.insertAvailabilityWindows(windows)
        }

        val allClinics = dao.getClinicsForHcp(hcp.id).sortedBy { it.clinicIndex }
        val primaryZone = allClinics.map { BrickNormalizer.normalizeBrickName(it.zone) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        if (primaryZone.isNotBlank() && primaryZone != hcp.territoryZone) {
            dao.updateHcp(
                hcp.copy(
                    territoryZone = primaryZone,
                    version = hcp.version + 1,
                    syncStatus = "PENDING",
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
        rebuildBrickRegistry()
        return newClinic.copy(id = newId)
    }

    suspend fun verifyClinicCoordinates(
        clinic: ClinicEntity,
        latitude: Double,
        longitude: Double
    ) {
        dao.updateClinic(
            clinic.copy(
                latitude = latitude,
                longitude = longitude,
                geocodingStatus = "VERIFIED",
                lastVerifiedEpochMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateHcpBaselineHistory(
        hcp: HcpEntity,
        baselineCompletedCount: Int,
        quarterLabel: String,
        historyIncomplete: Boolean
    ) {
        dao.updateHcp(
            hcp.copy(
                importedBaselineVisits = baselineCompletedCount.coerceIn(0, 60),
                baselineQuarterLabel = quarterLabel,
                historyIncomplete = historyIncomplete,
                version = hcp.version + 1,
                syncStatus = "PENDING",
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * Creates or updates a medical representative HCP target ([HcpEntity]) and its primary clinic
     * via [HcpDao] / [TerritoryDao].
     */
    suspend fun createOrUpdateHcpTarget(
        targetId: String,
        name: String,
        specialty: String,
        quarterlyTarget: Int,
        priorityScore: Int,
        zone: String,
        rawLocation: String,
        rawScheduleText: String
    ): HcpEntity {
        val cleanTargetId = targetId.trim().ifBlank { "W-${System.currentTimeMillis() % 10000}" }
        val existing = hcpDao?.getTargetByTargetId(cleanTargetId) ?: dao.getHcpByTargetId(cleanTargetId)
        val now = System.currentTimeMillis()

        val savedHcp: HcpEntity
        val hcpId: Long
        if (existing == null) {
            val entity = HcpEntity(
                targetId = cleanTargetId,
                name = name.trim(),
                specialty = specialty.trim().ifBlank { "General Practice" },
                quarterlyTarget = quarterlyTarget.coerceIn(1, 60),
                priorityScore = priorityScore.coerceIn(1, 100),
                territoryZone = zone.trim().ifBlank { "Sheikh Zayed" },
                sourceWorkbook = "Manual Target Entry",
                syncStatus = "PENDING",
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
            hcpId = hcpDao?.insertTarget(entity) ?: dao.insertHcp(entity)
            savedHcp = entity.copy(id = hcpId)
        } else {
            val updated = existing.copy(
                name = name.trim().ifBlank { existing.name },
                specialty = specialty.trim().ifBlank { existing.specialty },
                quarterlyTarget = quarterlyTarget.coerceIn(1, 60),
                priorityScore = priorityScore.coerceIn(1, 100),
                territoryZone = zone.trim().ifBlank { existing.territoryZone },
                isDeleted = false,
                isActive = true,
                version = existing.version + 1,
                syncStatus = "PENDING",
                updatedAtEpochMs = now
            )
            if (hcpDao != null) {
                hcpDao.updateTarget(updated)
            } else {
                dao.updateHcp(updated)
            }
            hcpId = existing.id
            savedHcp = updated
        }

        if (zone.isNotBlank() || rawLocation.isNotBlank() || rawScheduleText.isNotBlank()) {
            val effectiveRawSched = rawScheduleText.ifBlank { "Saturday and Monday 2-6 PM" }
            val coords = ExcelTerritoryImporter.extractCoordinates(rawLocation)
            val parsedSched = ScheduleValidator.validate(
                AvailabilityScheduleParser.parse(effectiveRawSched)
            )
            val isConfirmed = parsedSched.status == ScheduleParseStatus.PARSED_HIGH_CONFIDENCE &&
                parsedSched.validationStatus == ScheduleValidationStatus.VALID
            val existingClinics = dao.getClinicsForHcp(hcpId)
            val primaryClinic = existingClinics.firstOrNull { it.clinicIndex == 1 }
            val clinicEntity = ClinicEntity(
                id = primaryClinic?.id ?: 0L,
                hcpId = hcpId,
                clinicIndex = 1,
                label = "Clinic 1 (${zone.ifBlank { "Sheikh Zayed" }})",
                zone = zone.ifBlank { "Sheikh Zayed" },
                originalLocationText = rawLocation,
                googleMapsUrl = if (coords.isGoogleMapsUrl) rawLocation else "",
                latitude = coords.latitude ?: 30.0485,
                longitude = coords.longitude ?: 30.9822,
                geocodingStatus = if (coords.latitude != null) coords.geocodingStatus else "VERIFIED",
                rawScheduleText = effectiveRawSched,
                originalExcelScheduleText = rawScheduleText.ifBlank { primaryClinic?.originalExcelScheduleText ?: effectiveRawSched },
                normalizedDaysCsv = parsedSched.normalizedDays.joinToString(","),
                exactStartMinute = parsedSched.exactStartMinute,
                exactEndMinute = parsedSched.exactEndMinute,
                timeInfoType = parsedSched.timeInfoType.name,
                scheduleParseStatus = ScheduleParseStatus.CONFIRMED.name,
                scheduleAmbiguityReason = parsedSched.ambiguityReason,
                userConfirmedSchedule = isConfirmed || rawScheduleText.isNotBlank(),
                lastVerifiedEpochMs = now,
                scheduleClassification = parsedSched.classification.name,
                excludedDaysCsv = parsedSched.excludedDays.joinToString(","),
                parsingMethod = ParsingMethod.USER_CONFIRMED.name,
                parsingConfidence = 1.0,
                validationStatus = parsedSched.validationStatus.name,
                sourceColumn = primaryClinic?.sourceColumn?.ifBlank { "Manual Clinic 1" } ?: "Manual Clinic 1",
                isManualUserConfirmed = true
            )
            val clinicId = if (primaryClinic == null) {
                dao.insertClinic(clinicEntity)
            } else {
                dao.updateClinic(clinicEntity)
                primaryClinic.id
            }
            dao.deleteWindowsForClinic(clinicId)
            dao.insertAvailabilityWindows(
                parsedSched.toEntities(
                    clinicId = clinicId,
                    isConfirmed = isConfirmed,
                    hcpId = hcpId,
                    originalExcelText = effectiveRawSched,
                    sourceColumn = clinicEntity.sourceColumn,
                    parsingStatus = ScheduleParseStatus.CONFIRMED.name
                )
            )
        }

        return savedHcp
    }

    suspend fun archiveHcpTarget(hcpId: Long) {
        if (hcpDao != null) {
            hcpDao.archiveTargetById(hcpId)
        } else {
            val existing = dao.getHcpById(hcpId) ?: return
            dao.updateHcp(existing.copy(isDeleted = true, isActive = false, syncStatus = "PENDING"))
        }
    }

    suspend fun archiveHcpTargets(hcpIds: Collection<Long>) = withContext(Dispatchers.IO) {
        for (hcpId in hcpIds) {
            archiveHcpTarget(hcpId)
        }
    }

    /**
     * Computes the map of HCP IDs that have already been visited or scheduled during the calendar week
     * containing [dateIso], using the user's configured [WorkSettings.firstDayOfWeekIso] (Section 3).
     *
     * Rules enforced:
     * - Completed visits (`COMPLETED`) on any date in `[weekStartIso, weekEndIso]` (unless [includeSelectedPlanOnSameDate]
     *   is false and the completed stop on `dateIso` itself is being preserved as a locked/completed stop for today's regeneration)
     *   block the HCP for the rest of the week.
     * - Active planned/confirmed/rescheduled visits (`PLANNED`, `CONFIRMED`, `RESCHEDULED`) on other days in the same
     *   calendar week (or on [dateIso] when [includeSelectedPlanOnSameDate] is true) block the HCP.
     * - Cancelled visits (`CANCELLED`, `NOT_COMPLETED`) do NOT block the HCP from being scheduled later in the week.
     * - Active stops (`PLANNED`, `CONFIRMED`, `COMPLETED`) in selected daily plans (`isSelected == true`) in the same
     *   calendar week also block the HCP.
     */
    suspend fun getWeeklyBlockedHcpsForDate(
        dateIso: String,
        settingsOverride: WorkSettings? = null,
        includeSelectedPlanOnSameDate: Boolean = false
    ): Map<Long, WeeklyHcpBlockInfo> = withContext(Dispatchers.IO) {
        val effSettings = settingsOverride ?: workSettingsFlow.first()
        if (!effSettings.enforceWeeklyHcpVisitLimit) return@withContext emptyMap()

        val bounds = CalendarWeekCalculator.boundsForDate(dateIso, effSettings.firstDayOfWeekIso)
        val activeHcps = dao.getAllActiveHcps()
        val hcpById = activeHcps.associateBy { it.id }
        val hcpByTargetId = activeHcps.associateBy { it.targetId }

        val weekVisits = dao.getVisitsBetweenDates(bounds.weekStartIso, bounds.weekEndIso)
        val selectedWeekPlans = dao.getSelectedPlansBetweenDates(bounds.weekStartIso, bounds.weekEndIso)
        val planById = selectedWeekPlans.associateBy { it.id }
        val weekStops = if (selectedWeekPlans.isNotEmpty()) {
            dao.getStopsForPlans(selectedWeekPlans.map { it.id })
        } else {
            emptyList()
        }

        val blockedMap = mutableMapOf<Long, WeeklyHcpBlockInfo>()

        // Track dates where an HCP has an explicit latest CANCELLED / NOT_COMPLETED visit record
        val cancelledHcpDates = mutableSetOf<Pair<Long, String>>()

        // 1. Evaluate VisitEntity records in the calendar week (grouped by resolved HCP ID + plannedDateIso)
        val visitsByHcpAndDate = weekVisits.mapNotNull { v ->
            val resolvedHcp = hcpById[v.hcpId] ?: hcpByTargetId[v.hcpTargetId]
            if (resolvedHcp != null) Triple(resolvedHcp, v.plannedDateIso, v) else null
        }.groupBy { (hcp, dIso, _) -> hcp.id to dIso }

        for ((key, triples) in visitsByHcpAndDate) {
            val (hcpId, vDateIso) = key
            val hcp = triples.first().first
            val latestVisit = triples.maxByOrNull { it.third.id }?.third ?: continue
            val statusUpper = latestVisit.status.uppercase()

            if (statusUpper == "CANCELLED" || statusUpper == "NOT_COMPLETED") {
                cancelledHcpDates.add(hcpId to vDateIso)
                continue
            }

            val isSameDate = (vDateIso == dateIso)
            if (isSameDate && !includeSelectedPlanOnSameDate) {
                // During daily plan regeneration for dateIso, completed stops on dateIso are handled via completedStopsToday
                continue
            }

            if (statusUpper in setOf("COMPLETED", "PLANNED", "CONFIRMED", "RESCHEDULED")) {
                blockedMap[hcpId] = WeeklyHcpBlockInfo(
                    hcpId = hcpId,
                    hcpTargetId = hcp.targetId,
                    hcpName = hcp.name,
                    blockingDateIso = vDateIso,
                    blockingClinicId = latestVisit.clinicId,
                    blockingClinicLabel = latestVisit.clinicLabel,
                    blockingStatus = statusUpper,
                    source = if (statusUpper == "COMPLETED") "COMPLETED_VISIT" else "ACTIVE_VISIT"
                )
            }
        }

        // 2. Evaluate active PlannedStopEntity records in selected plans across the calendar week
        for (stop in weekStops) {
            val plan = planById[stop.planId] ?: continue
            val isSameDate = (plan.dateIso == dateIso)
            if (isSameDate && !includeSelectedPlanOnSameDate) continue

            val resolvedHcp = hcpById[stop.hcpId] ?: hcpByTargetId[stop.hcpTargetId]
            val resolvedHcpId = resolvedHcp?.id ?: stop.hcpId
            if (resolvedHcpId <= 0L) continue
            if (blockedMap.containsKey(resolvedHcpId)) continue
            if (cancelledHcpDates.contains(resolvedHcpId to plan.dateIso) &&
                stop.executionStatus.uppercase() in setOf("CANCELLED", "NOT_COMPLETED")
            ) {
                continue
            }

            val stopStatus = stop.executionStatus.uppercase()
            if (stopStatus in setOf("COMPLETED", "PLANNED", "CONFIRMED", "RESCHEDULED")) {
                blockedMap[resolvedHcpId] = WeeklyHcpBlockInfo(
                    hcpId = resolvedHcpId,
                    hcpTargetId = resolvedHcp?.targetId ?: stop.hcpTargetId,
                    hcpName = resolvedHcp?.name ?: stop.hcpName,
                    blockingDateIso = plan.dateIso,
                    blockingClinicId = stop.clinicId,
                    blockingClinicLabel = stop.clinicLabel,
                    blockingStatus = stopStatus,
                    source = "SCHEDULED_PLAN"
                )
            }
        }

        blockedMap
    }

    data class ManualAddStopResult(
        val wasNonPreferredSpecialtyException: Boolean,
        val warningMessage: String? = null
    )

    /**
     * Adds an existing HCP from the directory list directly into the active daily route plan for [dateIso],
     * creating a new daily plan if none exists yet, and recalculates stop timings.
     *
     * Enforces the Weekly Visit Restriction (Section 3.5): if the HCP has already been visited or scheduled
     * during the same calendar week (at any clinic), throws [IllegalStateException] with
     * [WEEKLY_HCP_RESTRICTION_MESSAGE] unless [allowWeeklyOverride] is explicitly true.
     *
     * Section 8 (Manual Scheduling): If the user manually adds an HCP whose specialty is not in the
     * selected preferred specialties, displays a clear warning, records the manual exception in the
     * visit/stop record, and does NOT change the user's saved preferred specialty settings.
     */
    suspend fun addHcpStopToPlan(
        dateIso: String,
        hcpWithDetails: HcpWithDetails,
        preferredClinic: ClinicEntity? = null,
        shiftStartMinute: Int = 13 * 60,
        shiftEndMinute: Int = 21 * 60,
        allowWeeklyOverride: Boolean = false
    ): ManualAddStopResult = withContext(Dispatchers.IO) {
        planningMutex.withLock {
            val settings = workSettingsFlow.first()
            val weeklyBlocked = getWeeklyBlockedHcpsForDate(
                dateIso = dateIso,
                settingsOverride = settings,
                includeSelectedPlanOnSameDate = true
            )
            val existingBlock = weeklyBlocked[hcpWithDetails.hcp.id]
            if (existingBlock != null && !allowWeeklyOverride) {
                throw IllegalStateException(WEEKLY_HCP_RESTRICTION_MESSAGE)
            }

            val isNonPreferredSpecialty = SpecialtyNormalizer.isMissingOrUnmatchedSpecialty(hcpWithDetails.hcp.specialty) ||
                !SpecialtyNormalizer.isSpecialtySelected(hcpWithDetails.hcp.specialty, settings.preferredSpecialtiesRanked)
            val nonPreferredWarning = if (isNonPreferredSpecialty) {
                buildManualUnselectedSpecialtyWarning(hcpWithDetails.hcp.name, hcpWithDetails.hcp.specialty)
            } else {
                null
            }

            val existingPlans = dao.getPlansForDate(dateIso)
            val activePlan = existingPlans.firstOrNull { it.isSelected } ?: existingPlans.firstOrNull()

            val chosenClinicWithWin = if (preferredClinic != null) {
                hcpWithDetails.clinics.firstOrNull { it.clinic.id == preferredClinic.id }
                    ?: hcpWithDetails.clinics.firstOrNull()
            } else {
                hcpWithDetails.clinics.firstOrNull()
            }
            val clinic = chosenClinicWithWin?.clinic
            val dow = RouteOptimizerEngine.parseIsoDayOfWeek(dateIso)
            val validClinicWindows = chosenClinicWithWin?.windows
                ?.filter { clinic != null && it.clinicId == clinic.id && (it.hcpId == 0L || it.hcpId == hcpWithDetails.hcp.id) }
                .orEmpty()
            val dayWin = validClinicWindows.firstOrNull { it.dayOfWeek == dow }
                ?: validClinicWindows.firstOrNull()

            val winStart = dayWin?.startMinuteOfDay ?: shiftStartMinute
            val winEnd = dayWin?.endMinuteOfDay ?: shiftEndMinute
            val visitDur = settings.defaultVisitDurationMinutes.coerceIn(5, 90)

            val conflictNote = if (isNonPreferredSpecialty) {
                "Manual non-preferred specialty exception (${hcpWithDetails.hcp.specialty.ifBlank { "Unspecified" }})"
            } else {
                ""
            }

            if (activePlan == null) {
                val depMin = shiftStartMinute
                val arrMin = depMin + 15
                val visStart = maxOf(arrMin, winStart).coerceAtMost(shiftEndMinute - visitDur)
                val visEnd = visStart + visitDur
                val newPlan = DailyPlanEntity(
                    dateIso = dateIso,
                    variantCode = "PLAN_A",
                    variantLabel = "Plan A: Active Route",
                    isSelected = true,
                    startMinuteOfDay = depMin,
                    endMinuteOfDay = visEnd + 15,
                    totalDutySpanMinutes = (visEnd + 15) - depMin,
                    totalDrivingMinutes = 15,
                    totalDistanceKm = 4.0,
                    totalWaitingMinutes = (visStart - arrMin).coerceAtLeast(0),
                    plannedVisitsCount = 1,
                    uniqueHcpsCount = 1,
                    highPriorityCount = if (hcpWithDetails.hcp.priorityScore >= 75) 1 else 0,
                    quarterlyCoverageGain = if (hcpWithDetails.coverage.remainingVisits > 0) 1 else 0,
                    trafficFreshnessLabel = "TYPICAL_ROAD",
                    feasibilityStatus = "FEASIBLE",
                    infeasibilityReasonsText = "",
                    advantagesText = "Custom route with selected territory HCPs",
                    disadvantagesText = "",
                    isValidatorVerified = true,
                    validatorReportText = "Validated"
                )
                val planId = dao.insertDailyPlan(newPlan)
                val newStop = PlannedStopEntity(
                    planId = planId,
                    stopOrder = 1,
                    hcpId = hcpWithDetails.hcp.id,
                    hcpTargetId = hcpWithDetails.hcp.targetId,
                    hcpName = hcpWithDetails.hcp.name,
                    specialty = hcpWithDetails.hcp.specialty,
                    clinicId = clinic?.id ?: 0L,
                    clinicLabel = clinic?.label ?: "Clinic 1",
                    zone = clinic?.zone?.ifBlank { hcpWithDetails.hcp.territoryZone } ?: hcpWithDetails.hcp.territoryZone,
                    latitude = clinic?.latitude ?: settings.homeLatitude,
                    longitude = clinic?.longitude ?: settings.homeLongitude,
                    locationVerified = clinic?.latitude != null && clinic.longitude != null,
                    departureFromPrevMinute = depMin,
                    travelMinutesFromPrev = 15,
                    distanceKmFromPrev = 4.0,
                    trafficSource = "TYPICAL_ROAD",
                    arrivalMinute = arrMin,
                    waitingMinutes = (visStart - arrMin).coerceAtLeast(0),
                    visitStartMinute = visStart,
                    visitDurationMinutes = visitDur,
                    visitEndMinute = visEnd,
                    windowStartMinute = winStart,
                    windowEndMinute = winEnd,
                    priorityScore = hcpWithDetails.hcp.priorityScore,
                    remainingQuarterlyVisits = hcpWithDetails.coverage.remainingVisits,
                    isLocked = true,
                    executionStatus = "PLANNED",
                    conflictReason = conflictNote
                )
                dao.insertPlannedStops(listOf(newStop))
            } else {
                val currentStops = dao.getStopsForPlan(activePlan.id).sortedBy { it.stopOrder }.toMutableList()
                val lastEnd = currentStops.lastOrNull()?.visitEndMinute ?: activePlan.startMinuteOfDay
                val newStop = PlannedStopEntity(
                    planId = activePlan.id,
                    stopOrder = currentStops.size + 1,
                    hcpId = hcpWithDetails.hcp.id,
                    hcpTargetId = hcpWithDetails.hcp.targetId,
                    hcpName = hcpWithDetails.hcp.name,
                    specialty = hcpWithDetails.hcp.specialty,
                    clinicId = clinic?.id ?: 0L,
                    clinicLabel = clinic?.label ?: "Clinic 1",
                    zone = clinic?.zone?.ifBlank { hcpWithDetails.hcp.territoryZone } ?: hcpWithDetails.hcp.territoryZone,
                    latitude = clinic?.latitude ?: settings.homeLatitude,
                    longitude = clinic?.longitude ?: settings.homeLongitude,
                    locationVerified = clinic?.latitude != null && clinic.longitude != null,
                    departureFromPrevMinute = lastEnd,
                    travelMinutesFromPrev = 12,
                    distanceKmFromPrev = 3.0,
                    trafficSource = "TYPICAL_ROAD",
                    arrivalMinute = lastEnd + 12,
                    waitingMinutes = 0,
                    visitStartMinute = maxOf(lastEnd + 12, winStart),
                    visitDurationMinutes = visitDur,
                    visitEndMinute = maxOf(lastEnd + 12, winStart) + visitDur,
                    windowStartMinute = winStart,
                    windowEndMinute = winEnd,
                    priorityScore = hcpWithDetails.hcp.priorityScore,
                    remainingQuarterlyVisits = hcpWithDetails.coverage.remainingVisits,
                    isLocked = true,
                    executionStatus = "PLANNED",
                    conflictReason = conflictNote
                )
                currentStops.add(newStop)
                val updatedPlanHeader = activePlan.copy(
                    plannedVisitsCount = currentStops.size,
                    uniqueHcpsCount = currentStops.map { it.hcpId }.distinct().size
                )
                recalculateAndPersistPlanStops(
                    planWithStops = DailyPlanWithStops(updatedPlanHeader, currentStops),
                    orderedStops = currentStops
                )
            }

            if (isNonPreferredSpecialty && nonPreferredWarning != null) {
                dao.insertVisit(
                    VisitEntity(
                        hcpId = hcpWithDetails.hcp.id,
                        hcpTargetId = hcpWithDetails.hcp.targetId,
                        clinicId = clinic?.id ?: 0L,
                        clinicLabel = clinic?.label ?: "Primary Clinic",
                        plannedDateIso = dateIso,
                        plannedStartMinute = shiftStartMinute,
                        plannedEndMinute = shiftStartMinute + visitDur,
                        status = "MANUAL_SPECIALTY_EXCEPTION",
                        qualifiesForCoverage = false,
                        recordSource = "MANUAL_SPECIALTY_EXCEPTION",
                        notes = nonPreferredWarning,
                        syncStatus = "LOCAL_AUDIT"
                    )
                )
            }

            ManualAddStopResult(
                wasNonPreferredSpecialtyException = isNonPreferredSpecialty,
                warningMessage = nonPreferredWarning
            )
        }
    }

    /**
     * Reschedules a planned stop from its current date to [targetDateIso] within the same or another week,
     * replacing the earlier entry without creating duplicate weekly entries (Section 3.3).
     */
    suspend fun rescheduleStopToDate(
        planWithStops: DailyPlanWithStops,
        stop: PlannedStopEntity,
        targetDateIso: String
    ) = withContext(Dispatchers.IO) {
        if (planWithStops.plan.dateIso == targetDateIso) return@withContext
        val settings = workSettingsFlow.first()
        val hcps = buildHcpsSnapshot(settings)
        val hcpWithDetails = hcps.firstOrNull { it.hcp.id == stop.hcpId || it.hcp.targetId == stop.hcpTargetId }
            ?: throw IllegalStateException("HCP not found for rescheduling")
        val preferredClinic = hcpWithDetails.clinics.firstOrNull { it.clinic.id == stop.clinicId }?.clinic

        // 1. Remove non-completed visit entry and stop from the old date first so weekly check sees the move
        dao.deleteNonCompletedVisitsForHcpOnDate(stop.hcpId, planWithStops.plan.dateIso)
        val remainingStopsOnOldPlan = planWithStops.stops
            .filter { it.id != stop.id && it.hcpId != stop.hcpId }
            .sortedBy { it.stopOrder }
        if (remainingStopsOnOldPlan.isEmpty()) {
            dao.deleteStopsForPlan(planWithStops.plan.id)
            dao.deletePlansForDate(planWithStops.plan.dateIso)
        } else {
            recalculateAndPersistPlanStops(planWithStops, remainingStopsOnOldPlan)
        }

        // 2. Add to targetDateIso (enforcing weekly restriction against any other visits in targetDateIso's week)
        addHcpStopToPlan(
            dateIso = targetDateIso,
            hcpWithDetails = hcpWithDetails,
            preferredClinic = preferredClinic
        )
    }

    /**
     * Builds a clean snapshot of all active HCPs and their clinics/windows, strictly enforcing
     * that each clinic only receives windows matching both `clinic.id` and `hcp.id` (Section 3 & 8).
     */
    private suspend fun buildHcpsSnapshot(settings: WorkSettings): List<HcpWithDetails> {
        val hcps = dao.getAllActiveHcps()
        val clinicsByHcp = dao.getAllClinics().groupBy { it.hcpId }
        val windowsByClinic = dao.getAllAvailabilityWindows().groupBy { it.clinicId }
        val visitsByHcp = dao.getAllVisits().groupBy { it.hcpId }

        return hcps.map { hcp ->
            val hcpClinics = clinicsByHcp[hcp.id].orEmpty().map { clinic ->
                ClinicWithAvailability(
                    clinic = clinic,
                    windows = windowsByClinic[clinic.id].orEmpty().filter {
                        it.clinicId == clinic.id && (it.hcpId == 0L || it.hcpId == hcp.id)
                    }
                )
            }
            val hcpVisits = visitsByHcp[hcp.id].orEmpty()
            val coverage = CoverageCalculator.calculateMetrics(
                hcp = hcp,
                clinics = hcpClinics,
                visits = hcpVisits,
                settings = settings
            )
            HcpWithDetails(
                hcp = hcp,
                clinics = hcpClinics,
                visits = hcpVisits,
                coverage = coverage
            )
        }
    }

    /**
     * Generates and persists up to 3 alternative daily plans for [dateIso].
     * Enforces transaction-safe weekly HCP restriction and controlled route diversity (Sections 3, 4, 5).
     */
    suspend fun generateAndSaveDailyPlans(
        dateIso: String,
        mandatoryHcpIds: Set<Long> = emptySet(),
        prioritizedHcpIds: Set<Long> = emptySet(),
        excludedHcpIds: Set<Long> = emptySet(),
        excludedZones: Set<String> = emptySet(),
        allowedZones: Set<String> = emptySet(),
        targetOverride: Int? = null,
        shiftStartMinute: Int? = null,
        shiftEndMinute: Int? = null,
        specialtyFilter: String? = null,
        diversitySeedOverride: Int? = null
    ): OptimizationOutcome = withContext(Dispatchers.IO) {
        planningMutex.withLock {
            val baseSettings = workSettingsFlow.first()
            val hcps = buildHcpsSnapshot(baseSettings)
            val allActiveSpecialties = SpecialtyNormalizer.mergeAvailableSpecialties(
                existingAvailable = baseSettings.preferredSpecialtiesRanked + SpecialtyNormalizer.DEFAULT_INITIAL_SPECIALTIES,
                hcpSpecialties = hcps.filter { it.hcp.isActive && !it.hcp.isDeleted }.map { it.hcp.specialty },
                preferredSpecialties = baseSettings.availableSpecialtiesCatalog
            )
            val settings = baseSettings.copy(
                dailyHcpTarget = targetOverride?.coerceIn(1, 20) ?: baseSettings.dailyHcpTarget,
                preferredSpecialtiesRanked = allActiveSpecialties.ifEmpty { SpecialtyNormalizer.DEFAULT_INITIAL_SPECIALTIES }
            )

            // Retrieve weekly blocked HCPs across the calendar week (excluding today's replaceable draft plan)
            val weeklyBlocked = getWeeklyBlockedHcpsForDate(
                dateIso = dateIso,
                settingsOverride = settings,
                includeSelectedPlanOnSameDate = false
            )

            // Preserve any already completed or locked stops for this date (only if not already blocked on another day of the week)
            val existingPlans = dao.getPlansForDate(dateIso)
            val activeSelectedPlan = existingPlans.firstOrNull { it.isSelected } ?: existingPlans.firstOrNull()
            val existingStops = if (activeSelectedPlan != null) {
                dao.getStopsForPlan(activeSelectedPlan.id)
            } else {
                emptyList()
            }
            val completedToday = existingStops.filter {
                it.executionStatus == "COMPLETED" && it.hcpId !in weeklyBlocked.keys
            }
            val lockedToday = existingStops.filter {
                it.isLocked && it.executionStatus == "PLANNED" && it.hcpId !in weeklyBlocked.keys
            }

            val seed = diversitySeedOverride ?: (diversityGenerationCounter++)

            val outcome = optimizerEngine.optimizeDay(
                OptimizationRequest(
                    dateIso = dateIso,
                    hcps = hcps,
                    settings = settings,
                    mandatoryHcpIds = mandatoryHcpIds - weeklyBlocked.keys,
                    prioritizedHcpIds = prioritizedHcpIds,
                    excludedHcpIds = excludedHcpIds,
                    weeklyRestrictedHcpIds = weeklyBlocked.keys,
                    weeklyBlockedByHcpId = weeklyBlocked,
                    excludedZones = excludedZones,
                    allowedZones = allowedZones,
                    targetOverride = targetOverride,
                    shiftStartMinute = shiftStartMinute,
                    shiftEndMinute = shiftEndMinute,
                    specialtyFilter = specialtyFilter,
                    lockedStops = lockedToday,
                    completedStopsToday = completedToday,
                    allowNetworkRouting = false,
                    diversitySeed = seed
                )
            )

            // Replace stored plans for dateIso
            for (oldPlan in existingPlans) {
                dao.deleteStopsForPlan(oldPlan.id)
            }
            dao.deletePlansForDate(dateIso)

            val savedAlternatives = mutableListOf<DailyPlanWithStops>()
            for (alt in outcome.alternatives) {
                val planId = dao.insertDailyPlan(alt.plan)
                val stopsWithPlanId = alt.stops.map { it.copy(id = 0L, planId = planId) }
                dao.insertPlannedStops(stopsWithPlanId)
                val reloadedStops = dao.getStopsForPlan(planId)
                savedAlternatives.add(
                    DailyPlanWithStops(
                        plan = alt.plan.copy(id = planId),
                        stops = reloadedStops
                    )
                )
            }

            outcome.copy(alternatives = savedAlternatives)
        }
    }

    suspend fun generateWeeklySchedule(weekStartDateIso: String): WeeklyPlanResult = withContext(Dispatchers.IO) {
        planningMutex.withLock {
            val baseSettings = workSettingsFlow.first()
            val hcps = buildHcpsSnapshot(baseSettings)
            val allActiveSpecialties = SpecialtyNormalizer.mergeAvailableSpecialties(
                existingAvailable = baseSettings.preferredSpecialtiesRanked + SpecialtyNormalizer.DEFAULT_INITIAL_SPECIALTIES,
                hcpSpecialties = hcps.filter { it.hcp.isActive && !it.hcp.isDeleted }.map { it.hcp.specialty },
                preferredSpecialties = baseSettings.availableSpecialtiesCatalog
            )
            val settings = baseSettings.copy(
                preferredSpecialtiesRanked = allActiveSpecialties.ifEmpty { SpecialtyNormalizer.DEFAULT_INITIAL_SPECIALTIES }
            )
            val bounds = CalendarWeekCalculator.boundsForDate(weekStartDateIso, settings.firstDayOfWeekIso)
            val weekVisits = dao.getVisitsBetweenDates(bounds.weekStartIso, bounds.weekEndIso)
            val alreadyVisitedIds = weekVisits
                .groupBy { it.hcpId to it.plannedDateIso }
                .values
                .mapNotNull { list -> list.maxByOrNull { it.id } }
                .filter { it.status.uppercase() == "COMPLETED" }
                .map { it.hcpId }
                .toSet()

            optimizerEngine.optimizeWeek(
                weekStartDateIso = bounds.weekStartIso,
                hcps = hcps,
                settings = settings,
                allowNetworkRouting = false,
                alreadyVisitedOrScheduledInWeekHcpIds = alreadyVisitedIds
            )
        }
    }

    suspend fun selectPlanVariant(dateIso: String, planId: Long) {
        dao.selectPlanVariant(dateIso, planId)
    }

    /**
     * Records a visit execution status (COMPLETED, CANCELLED, NOT_COMPLETED), updates the
     * visit ledger, recalculates quarterly coverage, and revalidates/re-optimizes the plan (Section 14 & Section 3.3).
     *
     * When a visit is marked `CANCELLED` or `NOT_COMPLETED`, any earlier non-completed visit records for that HCP
     * on that date are cleared and a cancelled record is saved so the HCP is eligible to be scheduled later in the week.
     */
    suspend fun recordStopExecution(
        planWithStops: DailyPlanWithStops,
        stop: PlannedStopEntity,
        newStatus: String, // COMPLETED, CANCELLED, NOT_COMPLETED
        notes: String = "",
        reoptimizeRemaining: Boolean = true
    ) {
        val settings = workSettingsFlow.first()
        val updatedStop = stop.copy(executionStatus = newStatus)
        dao.updatePlannedStop(updatedStop)

        if (newStatus == "CANCELLED" || newStatus == "NOT_COMPLETED") {
            dao.deleteNonCompletedVisitsForHcpOnDate(stop.hcpId, planWithStops.plan.dateIso)
        }

        val nowMs = System.currentTimeMillis()
        val visit = VisitEntity(
            hcpId = stop.hcpId,
            hcpTargetId = stop.hcpTargetId,
            clinicId = stop.clinicId,
            clinicLabel = stop.clinicLabel,
            plannedDateIso = planWithStops.plan.dateIso,
            plannedStartMinute = stop.visitStartMinute,
            plannedEndMinute = stop.visitEndMinute,
            actualStartEpochMs = if (newStatus == "COMPLETED") nowMs - (stop.visitDurationMinutes * 60_000L) else null,
            actualEndEpochMs = if (newStatus == "COMPLETED") nowMs else null,
            status = newStatus,
            qualifiesForCoverage = (newStatus == "COMPLETED"),
            quarterLabel = settings.currentQuarterLabel,
            recordSource = "APP_EXECUTION",
            notes = notes,
            syncStatus = "PENDING"
        )
        dao.insertVisit(visit)

        if (reoptimizeRemaining && (newStatus == "CANCELLED" || newStatus == "NOT_COMPLETED")) {
            generateAndSaveDailyPlans(
                dateIso = planWithStops.plan.dateIso,
                excludedHcpIds = setOf(stop.hcpId)
            )
        }
    }

    suspend fun toggleStopLock(stop: PlannedStopEntity) {
        dao.updatePlannedStop(stop.copy(isLocked = !stop.isLocked))
    }

    /**
     * Moves a stop up or down and immediately runs [PlanValidator] to recalculate times and surface any conflicts.
     */
    suspend fun reorderStopAndRevalidate(
        planWithStops: DailyPlanWithStops,
        stop: PlannedStopEntity,
        direction: Int // -1 for up, +1 for down
    ) {
        val stops = planWithStops.stops.sortedBy { it.stopOrder }.toMutableList()
        val idx = stops.indexOfFirst { it.id == stop.id }
        val targetIdx = idx + direction
        if (idx < 0 || targetIdx !in stops.indices) return

        val temp = stops[idx]
        stops[idx] = stops[targetIdx]
        stops[targetIdx] = temp

        recalculateAndPersistPlanStops(planWithStops, stops)
    }

    /**
     * Updates a specific planned stop's visit duration (e.g. 15m, 20m, 30m) and revalidates the entire day's route.
     */
    suspend fun updateStopDurationAndRevalidate(
        planWithStops: DailyPlanWithStops,
        stop: PlannedStopEntity,
        newDurationMinutes: Int
    ) {
        val validDur = newDurationMinutes.coerceIn(5, 90)
        val stops = planWithStops.stops.sortedBy { it.stopOrder }.map { s ->
            if (s.id == stop.id) {
                s.copy(
                    visitDurationMinutes = validDur,
                    visitEndMinute = s.visitStartMinute + validDur
                )
            } else {
                s
            }
        }
        recalculateAndPersistPlanStops(planWithStops, stops)
    }

    /**
     * Switches a multi-clinic HCP stop to another clinic for that HCP and revalidates the day's route.
     */
    suspend fun changeStopClinicAndRevalidate(
        planWithStops: DailyPlanWithStops,
        stop: PlannedStopEntity,
        newClinic: ClinicEntity,
        newWindowStartMinute: Int? = null,
        newWindowEndMinute: Int? = null
    ) {
        val stops = planWithStops.stops.sortedBy { it.stopOrder }.map { s ->
            if (s.id == stop.id) {
                s.copy(
                    clinicId = newClinic.id,
                    clinicLabel = newClinic.label,
                    zone = newClinic.zone,
                    latitude = newClinic.latitude ?: s.latitude,
                    longitude = newClinic.longitude ?: s.longitude,
                    locationVerified = newClinic.latitude != null && newClinic.longitude != null,
                    windowStartMinute = newWindowStartMinute ?: s.windowStartMinute,
                    windowEndMinute = newWindowEndMinute ?: s.windowEndMinute
                )
            } else {
                s
            }
        }
        recalculateAndPersistPlanStops(planWithStops, stops)
    }

    private suspend fun recalculateAndPersistPlanStops(
        planWithStops: DailyPlanWithStops,
        orderedStops: List<PlannedStopEntity>
    ) {
        if (orderedStops.isEmpty()) return
        val settings = workSettingsFlow.first()
        val validCoords = orderedStops.mapNotNull { s ->
            if (s.latitude != null && s.longitude != null) s.latitude to s.longitude else null
        }
        val (effectiveHomeLat, effectiveHomeLng) = if (validCoords.isNotEmpty()) {
            val avgLat = validCoords.map { it.first }.average()
            val avgLng = validCoords.map { it.second }.average()
            val dist = DefaultRoutingService.haversineDistanceKm(settings.homeLatitude, settings.homeLongitude, avgLat, avgLng)
            if (dist > 35.0) (avgLat + 0.008) to (avgLng + 0.008) else settings.homeLatitude to settings.homeLongitude
        } else {
            settings.homeLatitude to settings.homeLongitude
        }

        var currentLat = effectiveHomeLat
        var currentLng = effectiveHomeLng
        var currentMin = planWithStops.plan.startMinuteOfDay.coerceIn(360, 1260)

        val recalculated = mutableListOf<PlannedStopEntity>()
        for ((i, s) in orderedStops.withIndex()) {
            val destLat = s.latitude ?: currentLat
            val destLng = s.longitude ?: currentLng
            val travel = routingProvider.estimateTravel(
                originLat = currentLat,
                originLng = currentLng,
                destLat = destLat,
                destLng = destLng,
                departureMinuteOfDay = currentMin,
                safetyBufferPercent = settings.trafficSafetyBufferPercent,
                allowNetworkCall = false
            )
            val isSameBuilding = i > 0 && travel.distanceKm < 0.05 && s.locationVerified
            val safeTravelMin = if (isSameBuilding) {
                settings.sameLocationTransitionMinutes.coerceAtLeast(0)
            } else {
                travel.bufferedDurationMinutes.coerceIn(5, 45)
            }
            val depMin = currentMin
            val arrMin = depMin + safeTravelMin
            val visStart = maxOf(arrMin, s.windowStartMinute).coerceAtMost(1350)
            val visEnd = (visStart + s.visitDurationMinutes).coerceAtMost(1375)
            val waitMin = (visStart - arrMin).coerceAtLeast(0)

            recalculated.add(
                s.copy(
                    stopOrder = i + 1,
                    departureFromPrevMinute = depMin,
                    travelMinutesFromPrev = safeTravelMin,
                    distanceKmFromPrev = if (isSameBuilding) 0.0 else travel.distanceKm.coerceAtMost(35.0),
                    trafficSource = if (isSameBuilding) "SAME_LOCATION_SEQUENTIAL" else travel.source.name,
                    arrivalMinute = arrMin,
                    waitingMinutes = waitMin,
                    visitStartMinute = visStart,
                    visitEndMinute = visEnd
                )
            )
            currentLat = destLat
            currentLng = destLng
            currentMin = visEnd
        }

        val lastStop = recalculated.last()
        val returnEst = if (settings.returnHomeRequired && lastStop.latitude != null && lastStop.longitude != null) {
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

        val safeReturnMin = (returnEst?.bufferedDurationMinutes ?: 0).coerceAtMost(45)
        val endMin = (lastStop.visitEndMinute + safeReturnMin).coerceAtMost(1410)
        val startMin = recalculated.first().departureFromPrevMinute
        val updatedPlan = planWithStops.plan.copy(
            startMinuteOfDay = startMin,
            endMinuteOfDay = endMin,
            totalDutySpanMinutes = endMin - startMin,
            totalDrivingMinutes = recalculated.sumOf { it.travelMinutesFromPrev } + (returnEst?.bufferedDurationMinutes ?: 0),
            totalDistanceKm = ((recalculated.sumOf { it.distanceKmFromPrev } + (returnEst?.distanceKm ?: 0.0)) * 10.0).toInt() / 10.0,
            totalWaitingMinutes = recalculated.sumOf { it.waitingMinutes },
            plannedVisitsCount = recalculated.size,
            uniqueHcpsCount = recalculated.map { it.hcpId }.distinct().size
        )

        val hcpsById = buildHcpsSnapshot(settings).associateBy { it.hcp.id }
        val weeklyBlocked = getWeeklyBlockedHcpsForDate(
            dateIso = updatedPlan.dateIso,
            settingsOverride = settings,
            includeSelectedPlanOnSameDate = false
        )
        val validation = PlanValidator.validate(
            planWithStops = DailyPlanWithStops(updatedPlan, recalculated),
            settings = settings,
            returnHomeTravelMinutes = returnEst?.bufferedDurationMinutes ?: 0,
            hcpsById = hcpsById,
            alreadyVisitedOrScheduledInWeekHcpIds = weeklyBlocked.keys,
            weeklyBlockedByHcpId = weeklyBlocked
        )

        val finalStops = recalculated.map { s ->
            s.copy(
                id = 0L,
                planId = updatedPlan.id,
                conflictReason = validation.stopConflicts[s.stopOrder] ?: ""
            )
        }

        dao.deleteStopsForPlan(updatedPlan.id)
        dao.insertPlannedStops(finalStops)
        dao.updateDailyPlan(
            updatedPlan.copy(
                isValidatorVerified = validation.isValid,
                validatorReportText = validation.summary
            )
        )
    }

    /**
     * Synchronizes local Room HCPs and Visits with Cloud Firestore, detecting version conflicts
     * and weekly visit restriction conflicts so concurrent or offline changes never schedule the
     * same HCP twice in the same calendar week (Section 3.4 & Section 16.2).
     */
    suspend fun synchronizeWithCloud(): Result<Pair<Int, List<SyncConflict>>> {
        val cloudRepo = cloudSyncRepository
            ?: return Result.failure(IllegalStateException("Cloud repository not initialized"))

        return try {
            val settings = workSettingsFlow.first()
            val remoteHcpsResult = cloudRepo.getUserCloudHcps()
            val remoteHcpsByTarget = remoteHcpsResult.getOrNull()?.associateBy { it.targetId }.orEmpty()
            val localHcps = dao.getAllActiveHcps()
            val localHcpByTarget = localHcps.associateBy { it.targetId }
            val conflicts = mutableListOf<SyncConflict>()
            var syncedCount = 0

            for (local in localHcps) {
                val remote = remoteHcpsByTarget[local.targetId]
                if (remote != null && remote.version > local.version && local.syncStatus == "PENDING") {
                    conflicts.add(
                        SyncConflict(
                            entityType = "HCP",
                            recordId = local.targetId,
                            title = "${local.name} (${local.targetId})",
                            localSummary = "Local v${local.version}: target=${local.quarterlyTarget}, zone=${local.territoryZone}",
                            remoteSummary = "Cloud v${remote.version}: target=${remote.quarterlyTarget}, zone=${remote.zone}",
                            localVersion = local.version,
                            remoteVersion = remote.version
                        )
                    )
                    dao.updateHcp(local.copy(syncStatus = "CONFLICT"))
                    continue
                }

                val pushResult = cloudRepo.upsertCloudHcp(
                    docId = "hcp_${local.targetId}",
                    hcp = CloudHcp(
                        targetId = local.targetId,
                        name = local.name,
                        specialty = local.specialty,
                        quarterlyTarget = local.quarterlyTarget,
                        priorityScore = local.priorityScore,
                        zone = local.territoryZone,
                        isActive = local.isActive,
                        version = local.version,
                        isDeleted = local.isDeleted
                    )
                )
                if (pushResult.isSuccess) {
                    dao.updateHcp(local.copy(syncStatus = "SYNCED"))
                    syncedCount++
                }
            }

            // Reconcile remote visits with local visits to prevent duplicate weekly visits across devices
            val remoteVisits = cloudRepo.getUserCloudVisits().getOrNull().orEmpty()
            val allLocalVisits = dao.getAllVisits().toMutableList()
            for (rv in remoteVisits) {
                if (rv.plannedDateIso.isBlank() || rv.hcpTargetId.isBlank()) continue
                val matchedHcp = localHcpByTarget[rv.hcpTargetId] ?: continue
                val exactLocal = allLocalVisits.firstOrNull {
                    it.hcpTargetId == rv.hcpTargetId && it.plannedDateIso == rv.plannedDateIso
                }
                if (exactLocal == null && rv.status.uppercase() in setOf("COMPLETED", "PLANNED", "CONFIRMED")) {
                    val sameWeekLocal = allLocalVisits.firstOrNull { lv ->
                        (lv.hcpId == matchedHcp.id || lv.hcpTargetId == rv.hcpTargetId) &&
                            lv.plannedDateIso != rv.plannedDateIso &&
                            lv.status.uppercase() in setOf("COMPLETED", "PLANNED", "CONFIRMED") &&
                            CalendarWeekCalculator.isSameCalendarWeek(
                                dateIsoA = lv.plannedDateIso,
                                dateIsoB = rv.plannedDateIso,
                                firstDayOfWeekIso = settings.firstDayOfWeekIso
                            )
                    }
                    if (sameWeekLocal != null && settings.enforceWeeklyHcpVisitLimit) {
                        conflicts.add(
                            SyncConflict(
                                entityType = "VISIT_WEEKLY_LIMIT",
                                recordId = "${rv.hcpTargetId}_${rv.plannedDateIso}",
                                title = "Weekly Visit Conflict: ${matchedHcp.name}",
                                localSummary = "Local ${sameWeekLocal.status} on ${sameWeekLocal.plannedDateIso} (${sameWeekLocal.clinicLabel})",
                                remoteSummary = "Cloud ${rv.status} on ${rv.plannedDateIso} (${rv.clinicLabel})",
                                localVersion = sameWeekLocal.version,
                                remoteVersion = rv.version
                            )
                        )
                    } else {
                        val insertedId = dao.insertVisit(
                            VisitEntity(
                                hcpId = matchedHcp.id,
                                hcpTargetId = matchedHcp.targetId,
                                clinicId = 0L,
                                clinicLabel = rv.clinicLabel,
                                plannedDateIso = rv.plannedDateIso,
                                plannedStartMinute = 14 * 60,
                                plannedEndMinute = 14 * 60 + 20,
                                actualStartEpochMs = null,
                                actualEndEpochMs = null,
                                status = rv.status,
                                qualifiesForCoverage = rv.qualifiesForCoverage,
                                quarterLabel = settings.currentQuarterLabel,
                                recordSource = "CLOUD_SYNC",
                                notes = rv.notes,
                                version = rv.version,
                                syncStatus = "SYNCED"
                            )
                        )
                        allLocalVisits.add(
                            VisitEntity(
                                id = insertedId,
                                hcpId = matchedHcp.id,
                                hcpTargetId = matchedHcp.targetId,
                                clinicId = 0L,
                                clinicLabel = rv.clinicLabel,
                                plannedDateIso = rv.plannedDateIso,
                                plannedStartMinute = 14 * 60,
                                plannedEndMinute = 14 * 60 + 20,
                                actualStartEpochMs = null,
                                actualEndEpochMs = null,
                                status = rv.status,
                                qualifiesForCoverage = rv.qualifiesForCoverage,
                                quarterLabel = settings.currentQuarterLabel,
                                recordSource = "CLOUD_SYNC",
                                notes = rv.notes,
                                version = rv.version,
                                syncStatus = "SYNCED"
                            )
                        )
                        syncedCount++
                    }
                }
            }

            val localVisits = dao.getAllVisits().filter { it.syncStatus == "PENDING" }
            for (v in localVisits) {
                val conflictingRemote = remoteVisits.firstOrNull { rv ->
                    rv.hcpTargetId == v.hcpTargetId &&
                        rv.plannedDateIso != v.plannedDateIso &&
                        rv.status.uppercase() in setOf("COMPLETED", "PLANNED", "CONFIRMED") &&
                        v.status.uppercase() in setOf("COMPLETED", "PLANNED", "CONFIRMED") &&
                        CalendarWeekCalculator.isSameCalendarWeek(
                            dateIsoA = v.plannedDateIso,
                            dateIsoB = rv.plannedDateIso,
                            firstDayOfWeekIso = settings.firstDayOfWeekIso
                        )
                }
                if (conflictingRemote != null && settings.enforceWeeklyHcpVisitLimit) {
                    dao.updateVisit(v.copy(syncStatus = "CONFLICT"))
                    continue
                }

                val pushVisit = cloudRepo.upsertCloudVisit(
                    docId = "visit_${v.id}_${v.plannedDateIso}",
                    visit = CloudVisit(
                        hcpTargetId = v.hcpTargetId,
                        clinicLabel = v.clinicLabel,
                        plannedDateIso = v.plannedDateIso,
                        status = v.status,
                        qualifiesForCoverage = v.qualifiesForCoverage,
                        notes = v.notes,
                        version = v.version
                    )
                )
                if (pushVisit.isSuccess) {
                    dao.updateVisit(v.copy(syncStatus = "SYNCED"))
                    syncedCount++
                }
            }

            Result.success(syncedCount to conflicts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Resolves the authoritative schedule parse result for [clinic] during migration/audit (Section 9),
     * reconstructing multi-clinic associations from the original Excel source when Clinic 1 and Clinic 2
     * shared a single combined Excel schedule cell or had a legacy duplicated `originalExcelScheduleText`.
     */
    private fun resolveClinicMigrationParseResult(
        clinic: ClinicEntity,
        hcpClinics: List<ClinicEntity>,
        effectiveSource: String
    ): com.example.domain.parser.ScheduleParseResult {
        val sortedSiblings = hcpClinics.sortedBy { it.clinicIndex }
        if (sortedSiblings.size >= 2) {
            val c1 = sortedSiblings[0]
            val c2 = sortedSiblings[1]
            val c1Excel = c1.originalExcelScheduleText.trim().ifBlank { c1.rawScheduleText.trim() }
            val c2Excel = c2.originalExcelScheduleText.trim().ifBlank { c2.rawScheduleText.trim() }
            val sharedCombinedCell = c1Excel.isNotBlank() && c1Excel == c2Excel &&
                (c2.sourceColumn.isBlank() || c2.sourceColumn == c1.sourceColumn)
            if (sharedCombinedCell && (clinic.id == c1.id || clinic.id == c2.id)) {
                val resolution = AvailabilityScheduleParser.resolveMultiClinicSchedules(
                    rawCombinedSchedule = c1Excel,
                    clinic1Zone = c1.zone,
                    clinic1Location = c1.originalLocationText,
                    clinic2Zone = c2.zone,
                    clinic2Location = c2.originalLocationText,
                    hasExplicitSecondClinic = true
                )
                val chosen = if (clinic.id == c1.id) resolution.clinic1Result else (resolution.clinic2Result ?: resolution.clinic1Result)
                return ScheduleValidator.validate(chosen)
            }
        }
        return ScheduleValidator.validate(
            AvailabilityScheduleParser.parseForExcelImport(
                rawSchedule = effectiveSource,
                siblingClinicResult = null
            )
        )
    }

    /**
     * Dry-run preview for safe database schedule migration (Part 11 & Section 9).
     * Never mutates DB; identifies outdated or cross-assigned records that would change, protects USER_CONFIRMED edits,
     * and preserves all IDs, GPS, bricks, and visit history.
     */
    suspend fun previewScheduleMigration(): ScheduleMigrationPreview = withContext(Dispatchers.IO) {
        val hcpsById = dao.getAllActiveHcps().associateBy { it.id }
        val clinics = dao.getAllClinics()
        val clinicsByHcp = clinics.groupBy { it.hcpId }
        val windowsByClinic = dao.getAllAvailabilityWindows().groupBy { it.clinicId }

        val candidates = mutableListOf<ClinicMigrationItem>()
        var protectedCount = 0
        var unchangedCount = 0

        for (clinic in clinics) {
            val hcp = hcpsById[clinic.hcpId] ?: continue
            val existingWins = windowsByClinic[clinic.id].orEmpty().filter {
                it.clinicId == clinic.id && (it.hcpId == 0L || it.hcpId == hcp.id)
            }
            val isUserProtected = clinic.isManualUserConfirmed ||
                (clinic.parsingMethod == ParsingMethod.USER_CONFIRMED.name && existingWins.isNotEmpty()) ||
                (clinic.scheduleParseStatus == ScheduleParseStatus.CONFIRMED.name && existingWins.isNotEmpty())

            if (isUserProtected) {
                protectedCount++
                continue
            }

            val effectiveSource = clinic.originalExcelScheduleText.trim().ifBlank {
                clinic.rawScheduleText.trim().takeIf { it != "Sat–Thu 2:00 PM – 7:00 PM" }.orEmpty()
            }
            if (effectiveSource.isBlank()) {
                unchangedCount++
                continue
            }

            val hcpClinics = clinicsByHcp[clinic.hcpId].orEmpty()
            val parsed = resolveClinicMigrationParseResult(clinic, hcpClinics, effectiveSource)

            val parsedSig = parsed.windows
                .map { Triple(it.dayOfWeek, it.startMinuteOfDay, it.endMinuteOfDay to it.endsNextDay) }
                .distinct()
                .sortedWith(compareBy({ it.first }, { it.second }, { it.third.first }))
            val existingSig = existingWins
                .filter { it.isConfirmed }
                .map { Triple(it.dayOfWeek, it.startMinuteOfDay, it.endMinuteOfDay to it.endsNextDay) }
                .distinct()
                .sortedWith(compareBy({ it.first }, { it.second }, { it.third.first }))

            val statusChanged = clinic.scheduleParseStatus != parsed.status.name ||
                clinic.scheduleClassification != parsed.classification.name ||
                clinic.validationStatus != parsed.validationStatus.name
            val windowsChanged = parsedSig != existingSig

            if (statusChanged || windowsChanged) {
                val beforeDesc = if (existingWins.isNotEmpty()) {
                    AvailabilityScheduleParser.formatWindowsSummary(existingWins).joinToString(" | ")
                } else {
                    clinic.scheduleAmbiguityReason.ifBlank { clinic.scheduleParseStatus }
                }
                val afterEntities = parsed.toEntities(
                    clinicId = clinic.id,
                    isConfirmed = !parsed.clarificationRequired,
                    hcpId = hcp.id,
                    originalExcelText = effectiveSource,
                    sourceSheet = clinic.sourceSheet,
                    sourceRow = clinic.sourceRow,
                    sourceColumn = clinic.sourceColumn,
                    parsingStatus = parsed.status.name
                )
                val afterDesc = if (afterEntities.isNotEmpty()) {
                    AvailabilityScheduleParser.formatWindowsSummary(afterEntities).joinToString(" | ")
                } else {
                    parsed.classification.displayLabel
                }
                candidates.add(
                    ClinicMigrationItem(
                        hcpId = hcp.id,
                        hcpName = hcp.name,
                        clinicId = clinic.id,
                        clinicLabel = clinic.label,
                        originalExcelText = effectiveSource,
                        beforeSummary = beforeDesc,
                        beforeStatus = clinic.scheduleParseStatus,
                        afterSummary = afterDesc,
                        afterClassification = parsed.classification.displayLabel,
                        afterValidationStatus = parsed.validationStatus.badgeLabel,
                        isUserConfirmedProtected = false
                    )
                )
            } else {
                unchangedCount++
            }
        }

        ScheduleMigrationPreview(
            totalClinicsInspected = clinics.size,
            candidatesToUpdate = candidates,
            protectedUserConfirmedCount = protectedCount,
            unchangedCount = unchangedCount
        )
    }

    /**
     * Safely migrates composite bricks + outdated auto-parsed schedules while preserving USER_CONFIRMED edits,
     * clinic/HCP IDs, GPS coordinates, and completed visit history.
     */
    suspend fun applyScheduleMigration(allowOverwriteUserConfirmed: Boolean = false): ScheduleAuditSummary =
        withContext(Dispatchers.IO) {
            migrateCompositeBricksAndRebuildRegistry()
            auditAllImportedSchedulesAndCorrectFalseAmbiguity(allowOverwriteUserConfirmed = allowOverwriteUserConfirmed)
        }

    /**
     * Audits every imported clinic schedule in Room using the deterministic parser + Stage 4 validator,
     * reconstructs multi-clinic associations from original Excel source, purges any cross-assigned windows,
     * and corrects false ambiguity classifications while leaving USER_CONFIRMED edits, originalExcelScheduleText,
     * and visit history completely intact (unless [allowOverwriteUserConfirmed] is true).
     */
    suspend fun auditAllImportedSchedulesAndCorrectFalseAmbiguity(
        allowOverwriteUserConfirmed: Boolean = false
    ): ScheduleAuditSummary =
        withContext(Dispatchers.IO) {
            val clinics = dao.getAllClinics()
            val clinicsByHcp = clinics.groupBy { it.hcpId }
            val windowsByClinic = dao.getAllAvailabilityWindows().groupBy { it.clinicId }
            val batchedWindows = mutableListOf<AvailabilityWindowEntity>()

            var correctedFalseAmbiguity = 0
            var genuineClarification = 0
            var confirmedCount = 0

            for (clinic in clinics) {
                val rawExistingWins = windowsByClinic[clinic.id].orEmpty()
                val hasMismatchedOwnerWindows = rawExistingWins.any {
                    it.clinicId != clinic.id || (it.hcpId != 0L && it.hcpId != clinic.hcpId)
                }
                val existingWins = rawExistingWins.filter {
                    it.clinicId == clinic.id && (it.hcpId == 0L || it.hcpId == clinic.hcpId)
                }
                if (hasMismatchedOwnerWindows) {
                    dao.deleteWindowsForClinic(clinic.id)
                    if (existingWins.isNotEmpty()) {
                        dao.insertAvailabilityWindows(
                            existingWins.map { it.copy(id = 0L, hcpId = clinic.hcpId, clinicId = clinic.id) }
                        )
                    }
                } else if (existingWins.any { it.hcpId == 0L || it.parsedTimeWindows.isBlank() }) {
                    val summary = AvailabilityScheduleParser.formatWindowsSummary(existingWins).joinToString(" | ")
                    val backfilled = existingWins.map { win ->
                        win.copy(
                            id = 0L,
                            hcpId = clinic.hcpId,
                            clinicId = clinic.id,
                            originalExcelText = win.originalExcelText.ifBlank { clinic.originalExcelScheduleText.ifBlank { clinic.rawScheduleText } },
                            parsedTimeWindows = win.parsedTimeWindows.ifBlank { summary },
                            sourceSheet = win.sourceSheet.ifBlank { clinic.sourceSheet },
                            sourceRow = if (win.sourceRow > 0) win.sourceRow else clinic.sourceRow,
                            sourceColumn = win.sourceColumn.ifBlank { clinic.sourceColumn },
                            parsingStatus = win.parsingStatus.ifBlank { clinic.scheduleParseStatus }
                        )
                    }
                    dao.deleteWindowsForClinic(clinic.id)
                    dao.insertAvailabilityWindows(backfilled)
                }

                val isProtectedManual = (clinic.isManualUserConfirmed ||
                    clinic.parsingMethod == ParsingMethod.USER_CONFIRMED.name ||
                    clinic.scheduleParseStatus == ScheduleParseStatus.CONFIRMED.name) &&
                    existingWins.isNotEmpty()
                if (isProtectedManual && !allowOverwriteUserConfirmed) {
                    confirmedCount++
                    continue
                }

                val hcpClinics = clinicsByHcp[clinic.hcpId].orEmpty()
                val effectiveSource = clinic.originalExcelScheduleText.trim().ifBlank {
                    clinic.rawScheduleText.trim().takeIf { it != "Sat–Thu 2:00 PM – 7:00 PM" }.orEmpty()
                }
                if (effectiveSource.isBlank()) {
                    genuineClarification++
                    if (clinic.scheduleClassification != ScheduleClassification.NO_SCHEDULE_INFORMATION.name ||
                        clinic.validationStatus != ScheduleValidationStatus.NO_SCHEDULE.name
                    ) {
                        dao.updateClinic(
                            clinic.copy(
                                scheduleClassification = ScheduleClassification.NO_SCHEDULE_INFORMATION.name,
                                validationStatus = ScheduleValidationStatus.NO_SCHEDULE.name,
                                userConfirmedSchedule = false
                            )
                        )
                    }
                    continue
                }

                val hasUnresolvedAssociationFlag = hcpClinics.size > 1 &&
                    hcpClinics.any {
                        it.id != clinic.id &&
                            it.originalExcelScheduleText.ifBlank { it.rawScheduleText }.trim() == effectiveSource
                    } &&
                    (clinic.scheduleAmbiguityReason.contains("which clinic", ignoreCase = true) ||
                        clinic.scheduleAmbiguityReason.contains("associate", ignoreCase = true) ||
                        clinic.scheduleAmbiguityReason.contains("one clinic", ignoreCase = true) ||
                        clinic.scheduleAmbiguityReason.contains("another", ignoreCase = true))
                if (hasUnresolvedAssociationFlag) {
                    genuineClarification++
                    continue
                }

                val parsed = resolveClinicMigrationParseResult(clinic, hcpClinics, effectiveSource)
                val wasAmbiguousOrUnconfirmed = !clinic.userConfirmedSchedule ||
                    clinic.scheduleParseStatus == ScheduleParseStatus.AMBIGUOUS.name ||
                    clinic.scheduleParseStatus == ScheduleParseStatus.UNPARSED.name ||
                    clinic.timeInfoType != com.example.domain.parser.TimeInfoType.CLINIC_AVAILABILITY_RANGE.name ||
                    existingWins.isEmpty()

                if (!parsed.clarificationRequired && parsed.windows.isNotEmpty() && parsed.validationStatus == ScheduleValidationStatus.VALID) {
                    val parsedSignature = parsed.windows
                        .map { Triple(it.dayOfWeek, it.startMinuteOfDay, it.endMinuteOfDay to it.endsNextDay) }
                        .distinct()
                        .sortedWith(compareBy({ it.first }, { it.second }, { it.third.first }))
                    val existingSignature = existingWins
                        .filter { it.isConfirmed }
                        .map { Triple(it.dayOfWeek, it.startMinuteOfDay, it.endMinuteOfDay to it.endsNextDay) }
                        .distinct()
                        .sortedWith(compareBy({ it.first }, { it.second }, { it.third.first }))

                    if (wasAmbiguousOrUnconfirmed ||
                        existingSignature != parsedSignature ||
                        clinic.normalizedDaysCsv != parsed.normalizedDays.joinToString(",") ||
                        clinic.exactStartMinute != parsed.exactStartMinute ||
                        clinic.exactEndMinute != parsed.exactEndMinute ||
                        clinic.scheduleClassification != parsed.classification.name ||
                        clinic.validationStatus != parsed.validationStatus.name
                    ) {
                        val newEntities = parsed.toEntities(
                            clinicId = clinic.id,
                            isConfirmed = true,
                            hcpId = clinic.hcpId,
                            originalExcelText = effectiveSource,
                            sourceSheet = clinic.sourceSheet,
                            sourceRow = clinic.sourceRow,
                            sourceColumn = clinic.sourceColumn,
                            parsingStatus = ScheduleParseStatus.PARSED_HIGH_CONFIDENCE.name
                        )
                        val summaryText = AvailabilityScheduleParser.formatWindowsSummary(newEntities)
                            .joinToString(" | ")
                        val updatedClinic = clinic.copy(
                            rawScheduleText = if (clinic.rawScheduleText.isBlank() ||
                                clinic.rawScheduleText == "Sat–Thu 2:00 PM – 7:00 PM"
                            ) {
                                effectiveSource
                            } else {
                                clinic.rawScheduleText
                            },
                            originalExcelScheduleText = clinic.originalExcelScheduleText.ifBlank { effectiveSource },
                            normalizedDaysCsv = parsed.normalizedDays.joinToString(","),
                            exactStartMinute = parsed.exactStartMinute,
                            exactEndMinute = parsed.exactEndMinute,
                            timeInfoType = parsed.timeInfoType.name,
                            bookingInstructions = parsed.bookingInstructions,
                            aiInterpretedScheduleText = summaryText,
                            scheduleParseStatus = ScheduleParseStatus.PARSED_HIGH_CONFIDENCE.name,
                            scheduleAmbiguityReason = "",
                            userConfirmedSchedule = true,
                            lastVerifiedEpochMs = clinic.lastVerifiedEpochMs ?: System.currentTimeMillis(),
                            scheduleClassification = parsed.classification.name,
                            excludedDaysCsv = parsed.excludedDays.joinToString(","),
                            parsingMethod = parsed.parsingMethod.name,
                            parsingConfidence = parsed.parsingConfidence,
                            validationStatus = parsed.validationStatus.name,
                            locationDescription = parsed.locationDescription.ifBlank { clinic.locationDescription },
                            isManualUserConfirmed = false
                        )
                        dao.updateClinic(updatedClinic)
                        dao.deleteWindowsForClinic(clinic.id)
                        batchedWindows.addAll(newEntities)
                        if (wasAmbiguousOrUnconfirmed || existingSignature != parsedSignature) {
                            correctedFalseAmbiguity++
                        }
                    }
                    confirmedCount++
                } else {
                    genuineClarification++
                    val updatedReason = parsed.ambiguityReason.ifBlank { clinic.scheduleAmbiguityReason }
                    val needsMetadataSync = clinic.scheduleParseStatus != parsed.status.name ||
                        clinic.timeInfoType != parsed.timeInfoType.name ||
                        clinic.scheduleAmbiguityReason != updatedReason ||
                        clinic.userConfirmedSchedule ||
                        clinic.normalizedDaysCsv != parsed.normalizedDays.joinToString(",") ||
                        clinic.exactStartMinute != parsed.exactStartMinute ||
                        clinic.exactEndMinute != parsed.exactEndMinute ||
                        clinic.scheduleClassification != parsed.classification.name ||
                        clinic.validationStatus != parsed.validationStatus.name ||
                        existingWins.any { it.isConfirmed }

                    if (needsMetadataSync) {
                        dao.updateClinic(
                            clinic.copy(
                                originalExcelScheduleText = clinic.originalExcelScheduleText.ifBlank { effectiveSource },
                                normalizedDaysCsv = parsed.normalizedDays.joinToString(","),
                                exactStartMinute = parsed.exactStartMinute,
                                exactEndMinute = parsed.exactEndMinute,
                                timeInfoType = parsed.timeInfoType.name,
                                bookingInstructions = parsed.bookingInstructions.ifBlank { clinic.bookingInstructions },
                                scheduleParseStatus = parsed.status.name,
                                scheduleAmbiguityReason = updatedReason,
                                userConfirmedSchedule = false,
                                scheduleClassification = parsed.classification.name,
                                excludedDaysCsv = parsed.excludedDays.joinToString(","),
                                parsingMethod = parsed.parsingMethod.name,
                                parsingConfidence = parsed.parsingConfidence,
                                validationStatus = parsed.validationStatus.name,
                                locationDescription = parsed.locationDescription.ifBlank { clinic.locationDescription },
                                isManualUserConfirmed = false
                            )
                        )
                        if (existingWins.isNotEmpty()) {
                            dao.deleteWindowsForClinic(clinic.id)
                        }
                    }
                }
            }

            if (batchedWindows.isNotEmpty()) {
                dao.insertAvailabilityWindows(batchedWindows)
            }

            ScheduleAuditSummary(
                totalClinicsAudited = clinics.size,
                correctedFalseAmbiguityCount = correctedFalseAmbiguity,
                genuineClarificationCount = genuineClarification,
                confirmedCount = confirmedCount
            )
        }
}

data class ClinicMigrationItem(
    val hcpId: Long,
    val hcpName: String,
    val clinicId: Long,
    val clinicLabel: String,
    val originalExcelText: String,
    val beforeSummary: String,
    val beforeStatus: String,
    val afterSummary: String,
    val afterClassification: String,
    val afterValidationStatus: String,
    val isUserConfirmedProtected: Boolean
)

data class ScheduleMigrationPreview(
    val totalClinicsInspected: Int,
    val candidatesToUpdate: List<ClinicMigrationItem>,
    val protectedUserConfirmedCount: Int,
    val unchangedCount: Int
)

data class ScheduleAuditSummary(
    val totalClinicsAudited: Int,
    val correctedFalseAmbiguityCount: Int,
    val genuineClarificationCount: Int,
    val confirmedCount: Int
)
