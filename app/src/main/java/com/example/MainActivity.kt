package com.example

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableView
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.TerritoryViewModel
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.signOutUser
import com.example.ui.screens.HcpDirectoryScreen
import com.example.ui.screens.ImportAndSettingsScreen
import com.example.ui.screens.OpeningPosterScreen
import com.example.ui.screens.PlannerScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.StatusCrimson
import com.example.ui.theme.StatusEmerald
import com.example.util.configureAppCheck
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val prefs = remember {
                context.getSharedPreferences("territory_rx_ui_prefs", Context.MODE_PRIVATE)
            }
            var themeMode by rememberSaveable {
                mutableStateOf(prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM")
            }
            val systemDark = isSystemInDarkTheme()
            val isDarkTheme = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> systemDark
            }
            val onUpdateThemeMode: (String) -> Unit = { newMode ->
                themeMode = newMode
                prefs.edit().putString("theme_mode", newMode).apply()
            }

            MyApplicationTheme(darkTheme = isDarkTheme) {
                TerritoryRxAppRoot(
                    isDarkTheme = isDarkTheme,
                    themeMode = themeMode,
                    onSetThemeMode = onUpdateThemeMode,
                    onToggleDarkMode = {
                        val next = if (isDarkTheme) "LIGHT" else "DARK"
                        onUpdateThemeMode(next)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerritoryRxAppRoot(
    isDarkTheme: Boolean = false,
    themeMode: String = "SYSTEM",
    onSetThemeMode: (String) -> Unit = {},
    onToggleDarkMode: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showOpeningPoster by rememberSaveable { mutableStateOf(true) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    if (showOpeningPoster) {
        OpeningPosterScreen(
            isDarkMode = isDarkTheme,
            onToggleDarkMode = onToggleDarkMode,
            onEnterApp = { targetTab ->
                selectedTab = targetTab.coerceIn(0, 3)
                showOpeningPoster = false
            }
        )
        return
    }

    var isAuthenticated by remember {
        mutableStateOf(
            runCatching { FirebaseAuth.getInstance().currentUser != null }.getOrDefault(false)
        )
    }

    if (!isAuthenticated) {
        AuthScreen(
            onSignInSuccess = {
                isAuthenticated = true
            }
        )
        return
    }

    val viewModel: TerritoryViewModel = viewModel()

    val selectedDateIso by viewModel.selectedDateIso.collectAsStateWithLifecycle()
    val alternatives by viewModel.plansForSelectedDate.collectAsStateWithLifecycle()
    val activePlan by viewModel.activePlanForSelectedDate.collectAsStateWithLifecycle()
    val weeklyPlanResult by viewModel.weeklyPlanResult.collectAsStateWithLifecycle()
    val hcps by viewModel.hcpsWithDetails.collectAsStateWithLifecycle()
    val settings by viewModel.workSettings.collectAsStateWithLifecycle()
    val importPreview by viewModel.importPreviewReport.collectAsStateWithLifecycle()
    val selectedImportMode by viewModel.selectedImportMode.collectAsStateWithLifecycle()
    val importLogs by viewModel.importLogs.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val syncConflicts by viewModel.syncConflicts.collectAsStateWithLifecycle()
    val isAiReadingExcel by viewModel.isAiReadingExcel.collectAsStateWithLifecycle()
    val aiExcelSummary by viewModel.aiExcelSummary.collectAsStateWithLifecycle()
    val targetVisits by viewModel.targetVisits.collectAsStateWithLifecycle()
    val selectedShift by viewModel.selectedShift.collectAsStateWithLifecycle()
    val shiftStartMinute by viewModel.shiftStartMinute.collectAsStateWithLifecycle()
    val shiftEndMinute by viewModel.shiftEndMinute.collectAsStateWithLifecycle()
    val selectedBricks by viewModel.selectedBricks.collectAsStateWithLifecycle()
    val availableBricks by viewModel.availableBricks.collectAsStateWithLifecycle()
    val aiPlanningBriefing by viewModel.aiPlanningBriefing.collectAsStateWithLifecycle()
    val isAiPlanningBriefingLoading by viewModel.isAiPlanningBriefingLoading.collectAsStateWithLifecycle()
    val scheduleMigrationPreview by viewModel.scheduleMigrationPreview.collectAsStateWithLifecycle()
    val lastOptimizationOutcome by viewModel.lastOptimizationOutcome.collectAsStateWithLifecycle()
    val weeklyBlockedByHcpId by viewModel.weeklyBlockedByHcpId.collectAsStateWithLifecycle()
    val availableSpecialties by viewModel.availableSpecialties.collectAsStateWithLifecycle()

    if (selectedTab != 0) {
        BackHandler {
            selectedTab = 0
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showOpeningPoster = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.secondary
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalPharmacy,
                                contentDescription = "Open Territory Rx Welcome Poster",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = when (selectedTab) {
                                    0 -> stringResource(R.string.planner_title)
                                    1 -> stringResource(R.string.directory_title)
                                    2 -> stringResource(R.string.import_title)
                                    else -> stringResource(R.string.settings_title)
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                actions = {
                    if (isBusy) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Working...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = onToggleDarkMode,
                        modifier = Modifier.testTag("top_bar_dark_mode_toggle")
                    ) {
                        Icon(
                            imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = if (isDarkTheme) "Switch to Light Mode" else "Switch to Dark Mode",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    val navItemColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Route, contentDescription = "Planner") },
                        label = { Text("Planner", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) },
                        colors = navItemColors,
                        modifier = Modifier.testTag("nav_planner")
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.MedicalServices, contentDescription = "HCPs") },
                        label = { Text("HCPs (${hcps.size})", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) },
                        colors = navItemColors,
                        modifier = Modifier.testTag("nav_directory")
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.TableView, contentDescription = "Import") },
                        label = { Text("Import", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) },
                        colors = navItemColors,
                        modifier = Modifier.testTag("nav_import_settings")
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) },
                        colors = navItemColors,
                        modifier = Modifier.testTag("nav_settings")
                    )
                }
            }
        }
    ) { innerPadding ->
        LaunchedEffect(bannerMessage?.timestamp) {
            if (bannerMessage != null) {
                delay(3000L)
                viewModel.dismissBanner()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> PlannerScreen(
                        selectedDateIso = selectedDateIso,
                        alternatives = alternatives,
                        activePlan = activePlan,
                        weeklyPlanResult = weeklyPlanResult,
                        settings = settings,
                        hcps = hcps,
                        targetVisits = targetVisits,
                        selectedShift = selectedShift,
                        shiftStartMinute = shiftStartMinute,
                        shiftEndMinute = shiftEndMinute,
                        selectedBricks = selectedBricks,
                        availableBricks = availableBricks,
                        aiPlanningBriefing = aiPlanningBriefing,
                        isAiPlanningBriefingLoading = isAiPlanningBriefingLoading,
                        lastOptimizationOutcome = lastOptimizationOutcome,
                        weeklyBlockedByHcpId = weeklyBlockedByHcpId,
                        onSetTargetVisits = { viewModel.setTargetVisits(it) },
                        onSelectShift = { viewModel.selectShift(it) },
                        onSetCustomWorkTiming = { from, to -> viewModel.setCustomWorkTiming(from, to) },
                        onSetCustomWorkMinutes = { startM, endM -> viewModel.setCustomWorkMinutes(startM, endM) },
                        onToggleBrick = { viewModel.toggleBrick(it) },
                        onSelectAllBricks = { viewModel.selectAllBricks() },
                        onRefreshAiBriefing = { viewModel.generateAiPlanningBriefing() },
                        onStepDate = { viewModel.stepSelectedDate(it) },
                        onSelectDate = { viewModel.selectDate(it) },
                        onOptimizeDay = { viewModel.optimizeSelectedDay() },
                        onOptimizeWeek = { viewModel.optimizeWeek() },
                        onGenerateAiPlan = { viewModel.optimizeSelectedDay() },
                        onSelectPlanVariant = { viewModel.selectPlanVariant(it) },
                        onMarkStopStatus = { plan, stop, status ->
                            viewModel.markStopExecution(plan, stop, status)
                        },
                        onToggleLock = { viewModel.toggleLockOnStop(it) },
                        onMoveStopOrder = { plan, stop, dir ->
                            viewModel.moveStopOrder(plan, stop, dir)
                        },
                        onUpdateStopDuration = { plan, stop, newMin ->
                            viewModel.updateStopDuration(plan, stop, newMin)
                        },
                        onSwitchStopClinic = { plan, stop, newClinic ->
                            viewModel.switchStopClinic(plan, stop, newClinic)
                        },
                        onAddHcpFromListToPlan = { hcpItem, chosenClinic ->
                            viewModel.addHcpToCurrentPlan(hcpItem, chosenClinic)
                        },
                        onNavigateToPlanningPriorities = {
                            selectedTab = 3
                        },
                        onSaveManualTarget = { targetId, name, specialty, zone, qtrTarget, priority, schedule, notes ->
                            viewModel.saveManualHcpTarget(
                                targetId = targetId,
                                name = name,
                                specialty = specialty,
                                quarterlyTarget = qtrTarget,
                                priorityScore = priority,
                                zone = zone,
                                rawLocation = notes.ifBlank { zone },
                                rawScheduleText = schedule
                            )
                        }
                    )
                    1 -> HcpDirectoryScreen(
                        hcps = hcps,
                        onSaveClinicSchedule = { clinic, windows ->
                            viewModel.saveClinicAvailability(clinic, windows)
                        },
                        onRequestAiScheduleDisambiguation = { clinic, callback ->
                            viewModel.requestAiScheduleDisambiguation(clinic, callback)
                        },
                        onVerifyClinicCoordinates = { clinic, lat, lng ->
                            viewModel.verifyClinicCoordinates(clinic, lat, lng)
                        },
                        onUpdateBaselineHistory = { hcp, baseline, qtr, incomplete ->
                            viewModel.updateHcpBaselineHistory(hcp, baseline, qtr, incomplete)
                        },
                        onSaveManualTarget = { targetId, name, specialty, zone, qtrTarget, priority, schedule, notes ->
                            viewModel.saveManualHcpTarget(
                                targetId = targetId,
                                name = name,
                                specialty = specialty,
                                quarterlyTarget = qtrTarget,
                                priorityScore = priority,
                                zone = zone,
                                rawLocation = notes.ifBlank { zone },
                                rawScheduleText = schedule
                            )
                        },
                        onArchiveTarget = { hcp ->
                            viewModel.archiveHcpTarget(hcp)
                        },
                        onArchiveMultipleTargets = { hcpList ->
                            viewModel.archiveMultipleHcpTargets(hcpList)
                        },
                        onSaveFullHcpCardEdits = { hcp, name, specialty, qtrTarget, baseline, qtr, incomplete, clinic, cZone, cLoc, cSched, wins, lat, lng ->
                            viewModel.saveFullHcpCardEdits(
                                hcp = hcp,
                                updatedName = name,
                                updatedSpecialty = specialty,
                                updatedQuarterlyTarget = qtrTarget,
                                baselineVisits = baseline,
                                quarterLabel = qtr,
                                historyIncomplete = incomplete,
                                clinic = clinic,
                                updatedClinicZone = cZone,
                                updatedClinicLocation = cLoc,
                                updatedClinicRawSchedule = cSched,
                                updatedWindows = wins,
                                updatedLat = lat,
                                updatedLng = lng
                            )
                        },
                        onAddClinicToHcp = { hcp, zone, loc, sched ->
                            viewModel.addClinicToHcp(hcp, zone, loc, sched)
                        }
                    )
                    2 -> ImportAndSettingsScreen(
                        importPreview = importPreview,
                        selectedImportMode = selectedImportMode,
                        importLogs = importLogs,
                        settings = settings,
                        syncConflicts = syncConflicts,
                        hcps = hcps,
                        availableSpecialties = availableSpecialties,
                        activePlan = activePlan,
                        isAiReadingExcel = isAiReadingExcel,
                        aiExcelSummary = aiExcelSummary,
                        initialSection = 0,
                        showSectionSwitcher = false,
                        themeMode = themeMode,
                        onSetThemeMode = onSetThemeMode,
                        onShowOpeningPoster = { showOpeningPoster = true },
                        onAiReadExcel = { viewModel.readExcelDataAndSchedulesWithAi(autoTriggered = false) },
                        onAuditAllSchedules = { viewModel.auditAllImportedSchedules() },
                        scheduleMigrationPreview = scheduleMigrationPreview,
                        onPreviewScheduleMigration = { viewModel.previewScheduleMigration() },
                        onDismissScheduleMigrationPreview = { viewModel.dismissScheduleMigrationPreview() },
                        onApplyScheduleMigration = { allowOverwrite -> viewModel.applyScheduleMigration(allowOverwrite) },
                        onSelectImportMode = { viewModel.setImportMode(it) },
                        onPreviewSampleWestXlsx = { immediate ->
                            viewModel.previewSampleWestXlsx(executeImmediately = immediate)
                        },
                        onPreviewSampleCsv = { viewModel.previewSampleWestCsv() },
                        onPreviewRawCsv = { csvText, name -> viewModel.previewRawCsvText(csvText, name) },
                        onUpdateColumnMapping = { field, colIdx ->
                            viewModel.updateCustomColumnMapping(field, colIdx)
                        },
                        onPreviewUri = { uri, name -> viewModel.previewUriImport(uri, name) },
                        onSelectWorksheet = { viewModel.selectWorksheetInPreview(it) },
                        onConfirmImport = { viewModel.confirmPreviewImport() },
                        onRollbackImport = { viewModel.rollbackImportLog(it) },
                        onSaveSettings = { viewModel.updateWorkSettings(it) },
                        onSyncCloud = { viewModel.syncWithCloud() },
                        onSignOut = {
                            scope.launch {
                                signOutUser(context)
                                isAuthenticated = false
                            }
                        }
                    )
                    else -> ImportAndSettingsScreen(
                        importPreview = importPreview,
                        selectedImportMode = selectedImportMode,
                        importLogs = importLogs,
                        settings = settings,
                        syncConflicts = syncConflicts,
                        hcps = hcps,
                        availableSpecialties = availableSpecialties,
                        activePlan = activePlan,
                        isAiReadingExcel = isAiReadingExcel,
                        aiExcelSummary = aiExcelSummary,
                        initialSection = 1,
                        showSectionSwitcher = false,
                        themeMode = themeMode,
                        onSetThemeMode = onSetThemeMode,
                        onShowOpeningPoster = { showOpeningPoster = true },
                        onAiReadExcel = { viewModel.readExcelDataAndSchedulesWithAi(autoTriggered = false) },
                        onAuditAllSchedules = { viewModel.auditAllImportedSchedules() },
                        scheduleMigrationPreview = scheduleMigrationPreview,
                        onPreviewScheduleMigration = { viewModel.previewScheduleMigration() },
                        onDismissScheduleMigrationPreview = { viewModel.dismissScheduleMigrationPreview() },
                        onApplyScheduleMigration = { allowOverwrite -> viewModel.applyScheduleMigration(allowOverwrite) },
                        onSelectImportMode = { viewModel.setImportMode(it) },
                        onPreviewSampleWestXlsx = { immediate ->
                            viewModel.previewSampleWestXlsx(executeImmediately = immediate)
                        },
                        onPreviewSampleCsv = { viewModel.previewSampleWestCsv() },
                        onPreviewRawCsv = { csvText, name -> viewModel.previewRawCsvText(csvText, name) },
                        onUpdateColumnMapping = { field, colIdx ->
                            viewModel.updateCustomColumnMapping(field, colIdx)
                        },
                        onPreviewUri = { uri, name -> viewModel.previewUriImport(uri, name) },
                        onSelectWorksheet = { viewModel.selectWorksheetInPreview(it) },
                        onConfirmImport = { viewModel.confirmPreviewImport() },
                        onRollbackImport = { viewModel.rollbackImportLog(it) },
                        onSaveSettings = { viewModel.updateWorkSettings(it) },
                        onSyncCloud = { viewModel.syncWithCloud() },
                        onSignOut = {
                            scope.launch {
                                signOutUser(context)
                                isAuthenticated = false
                            }
                        }
                    )
                }
            }

            AnimatedVisibility(
                visible = bannerMessage != null,
                enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                bannerMessage?.let { msg ->
                    val accentColor = if (msg.isError) StatusCrimson else StatusEmerald
                    val opaqueSurface = accentColor
                        .copy(alpha = 0.14f)
                        .compositeOver(MaterialTheme.colorScheme.surface)
                    val iconBadgeBg = accentColor
                        .copy(alpha = 0.22f)
                        .compositeOver(opaqueSurface)

                    Surface(
                        color = opaqueSurface,
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = 6.dp,
                        shadowElevation = 12.dp,
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .widthIn(max = 520.dp)
                            .fillMaxWidth()
                            .testTag("app_status_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = iconBadgeBg,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (msg.isError) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = msg.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = accentColor,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (msg.message.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = msg.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        lineHeight = 17.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(
                                onClick = { viewModel.dismissBanner() }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss Notification",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

