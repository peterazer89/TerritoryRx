package com.example.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TableView
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.DailyPlanWithStops
import com.example.data.model.HcpWithDetails
import com.example.data.model.ImportLogEntity
import com.example.data.model.SyncConflict
import com.example.data.model.WorkSettings
import com.example.data.repository.ScheduleMigrationPreview
import com.example.domain.importer.CanonicalField
import com.example.domain.importer.ImportMode
import com.example.domain.importer.ImportPreviewReport
import com.example.domain.optimizer.RouteOptimizerEngine
import com.example.domain.parser.AvailabilityScheduleParser
import com.example.domain.parser.ParsingMethod
import com.example.domain.parser.ScheduleValidationStatus
import com.example.domain.parser.ScheduleValidator
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusAmberSoft
import com.example.ui.theme.StatusCrimson
import com.example.ui.theme.StatusCrimsonSoft
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.StatusEmeraldSoft

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportAndSettingsScreen(
    importPreview: ImportPreviewReport?,
    selectedImportMode: ImportMode,
    importLogs: List<ImportLogEntity>,
    settings: WorkSettings,
    syncConflicts: List<SyncConflict>,
    hcps: List<HcpWithDetails> = emptyList(),
    availableSpecialties: List<String> = emptyList(),
    activePlan: DailyPlanWithStops? = null,
    isAiReadingExcel: Boolean = false,
    aiExcelSummary: String? = null,
    initialSection: Int = 0,
    showSectionSwitcher: Boolean = false,
    themeMode: String = "SYSTEM",
    onSetThemeMode: (String) -> Unit = {},
    onShowOpeningPoster: () -> Unit = {},
    onAiReadExcel: () -> Unit = {},
    onAuditAllSchedules: () -> Unit = {},
    scheduleMigrationPreview: ScheduleMigrationPreview? = null,
    onPreviewScheduleMigration: () -> Unit = {},
    onDismissScheduleMigrationPreview: () -> Unit = {},
    onApplyScheduleMigration: (Boolean) -> Unit = {},
    onSelectImportMode: (ImportMode) -> Unit,
    onPreviewSampleWestXlsx: (Boolean) -> Unit,
    onPreviewSampleCsv: () -> Unit = {},
    onPreviewRawCsv: (String, String) -> Unit = { _, _ -> },
    onUpdateColumnMapping: (CanonicalField, Int?) -> Unit = { _, _ -> },
    onPreviewUri: (Uri, String) -> Unit,
    onSelectWorksheet: (String) -> Unit,
    onConfirmImport: () -> Unit,
    onRollbackImport: (ImportLogEntity) -> Unit,
    onSaveSettings: (WorkSettings) -> Unit,
    onSyncCloud: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    var activeSection by remember(initialSection) { mutableIntStateOf(initialSection) }
    var showAuditDiscrepanciesOnly by remember { mutableStateOf(false) }
    var showScheduleAuditExpanded by remember { mutableStateOf(true) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            var displayName = "TerritoryWorkbook.xlsx"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    displayName = cursor.getString(nameIndex) ?: displayName
                }
            }
            onPreviewUri(uri, displayName)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(modifier = Modifier.height(2.dp)) }

        if (showSectionSwitcher) {
            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = activeSection == 0,
                        onClick = { activeSection = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        modifier = Modifier.testTag("tab_excel_import")
                    ) {
                        Text("Excel / CSV Import")
                    }
                    SegmentedButton(
                        selected = activeSection == 1,
                        onClick = { activeSection = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        modifier = Modifier.testTag("tab_work_settings")
                    ) {
                        Text("Work & Cloud Settings")
                    }
                }
            }
        }

        if (activeSection == 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.TableView,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                  }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "HCP Target List Importer",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Upload .xlsx or .csv doctor lists with AI schedule & data parsing",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        // Explicit mode selection: Add to previous data vs Replace previous data
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Choose Import Destination:",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val isAppend = selectedImportMode == ImportMode.APPEND_MERGE ||
                                        selectedImportMode == ImportMode.UPDATE_EXISTING ||
                                        selectedImportMode == ImportMode.REVIEW_AND_SYNC ||
                                        selectedImportMode == ImportMode.ADD_NEW_ONLY
                                    val isReplace = selectedImportMode == ImportMode.REPLACE_ALL

                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isAppend) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(
                                            1.dp,
                                            if (isAppend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { onSelectImportMode(ImportMode.APPEND_MERGE) }
                                            .testTag("import_mode_append_card")
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = isAppend,
                                                    onClick = { onSelectImportMode(ImportMode.APPEND_MERGE) },
                                                    modifier = Modifier.size(24.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "Add to Previous",
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "Keep existing doctors and add/update from this sheet",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isReplace) {
                                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                                        } else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(
                                            1.dp,
                                            if (isReplace) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { onSelectImportMode(ImportMode.REPLACE_ALL) }
                                            .testTag("import_mode_replace_card")
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = isReplace,
                                                    onClick = { onSelectImportMode(ImportMode.REPLACE_ALL) },
                                                    modifier = Modifier.size(24.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "Replace Previous",
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "Start fresh: clear previous list & load this sheet only",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { filePickerLauncher.launch("*/*") },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.testTag("import_pick_file_btn")
                            ) {
                                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Upload .xlsx / .csv")
                            }
                            FilledTonalButton(
                                onClick = onAiReadExcel,
                                enabled = !isAiReadingExcel,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.testTag("import_ai_read_excel_btn")
                            ) {
                                if (isAiReadingExcel) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isAiReadingExcel) "AI Reading Sheet..." else "AI Read Sheet & Times")
                            }
                            Button(
                                onClick = onConfirmImport,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.testTag("import_confirm_execute_btn")
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Import", fontWeight = FontWeight.Bold)
                            }
                        }

                        if (importPreview != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Description,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${importPreview.fileName} • ${importPreview.totalDetectedRows} HCPs • Sheet: ${importPreview.selectedWorksheet}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (importPreview.availableWorksheets.size > 1) {
                                        Row(
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            importPreview.availableWorksheets.forEach { sheet ->
                                                FilterChip(
                                                    selected = sheet == importPreview.selectedWorksheet,
                                                    onClick = { onSelectWorksheet(sheet) },
                                                    label = { Text(sheet) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (!aiExcelSummary.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = StatusEmeraldSoft,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("ai_excel_summary_banner")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = StatusEmerald,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = aiExcelSummary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StatusEmerald,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Re-Import & Merge Strategy",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ImportMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = selectedImportMode == mode,
                                    onClick = { onSelectImportMode(mode) },
                                    label = { Text(mode.title) }
                                )
                            }
                        }
                    }
                }
            }

            // Section 9: Excel-to-Application Schedule Audit Comparison
            if (hcps.isNotEmpty()) {
                item {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("excel_schedule_audit_card"),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            data class FourStageClinicAudit(
                                val hcpWithDetails: HcpWithDetails,
                                val clinicIndex: Int,
                                val zone: String,
                                val originalExcel: String,
                                val parserStage: String,
                                val dbStage: String,
                                val optimizerStage: String,
                                val mismatchStage: String, // NONE, PARSER, DATABASE, OPTIMIZER, MANUAL_OVERRIDE
                                val mismatchDetail: String,
                                val isFlagged: Boolean
                            )

                            val allClinicAudits = remember(hcps, activePlan) {
                                hcps.flatMap { hcpWithDetails ->
                                    hcpWithDetails.clinics.sortedBy { it.clinic.clinicIndex }.map { cw ->
                                        val c = cw.clinic
                                        val origExcel = c.originalExcelScheduleText.ifBlank { c.rawScheduleText }.trim()
                                        val effectiveExcelInput = origExcel.takeIf { it != "Sat–Thu 2:00 PM – 7:00 PM" }.orEmpty()

                                        val freshParsed = if (effectiveExcelInput.isNotBlank()) {
                                            ScheduleValidator.validate(
                                                AvailabilityScheduleParser.parseForExcelImport(effectiveExcelInput)
                                            )
                                        } else {
                                            null
                                        }
                                        val parserSig = freshParsed?.windows
                                            ?.map { Triple(it.dayOfWeek, it.startMinuteOfDay, it.endMinuteOfDay to it.endsNextDay) }
                                            ?.distinct()
                                            ?.sortedWith(compareBy({ it.first }, { it.second }, { it.third.first }))
                                            .orEmpty()
                                        val parserSummary = when {
                                            freshParsed == null -> "No Excel schedule text"
                                            !freshParsed.clarificationRequired && freshParsed.windows.isNotEmpty() -> {
                                                val ents = freshParsed.toEntities(c.id, isConfirmed = true)
                                                val human = AvailabilityScheduleParser.formatWindowsSummary(ents).joinToString(" | ")
                                                "$human (${freshParsed.classification.displayLabel})"
                                            }
                                            else -> "${freshParsed.classification.displayLabel}: ${freshParsed.ambiguityReason.ifBlank { freshParsed.validationStatus.badgeLabel }}"
                                        }

                                        val confirmedWins = cw.windows.filter { it.isConfirmed }
                                        val dbSig = confirmedWins
                                            .map { Triple(it.dayOfWeek, it.startMinuteOfDay, it.endMinuteOfDay to it.endsNextDay) }
                                            .distinct()
                                            .sortedWith(compareBy({ it.first }, { it.second }, { it.third.first }))
                                        val isDbValid = confirmedWins.isNotEmpty() &&
                                            c.userConfirmedSchedule &&
                                            c.timeInfoType == "CLINIC_AVAILABILITY_RANGE" &&
                                            c.scheduleParseStatus != "AMBIGUOUS" &&
                                            c.scheduleParseStatus != "UNPARSED" &&
                                            c.validationStatus != "INVALID"

                                        val sourceLabel = if (c.isManualUserConfirmed) {
                                            ParsingMethod.USER_CONFIRMED.displayLabel
                                        } else {
                                            runCatching { ParsingMethod.valueOf(c.parsingMethod).displayLabel }
                                                .getOrElse { ParsingMethod.DETERMINISTIC.displayLabel }
                                        }
                                        val time24h = if (confirmedWins.isNotEmpty()) {
                                            confirmedWins
                                                .distinctBy { Triple(it.startMinuteOfDay, it.endMinuteOfDay, it.endsNextDay) }
                                                .joinToString(" | ") { w ->
                                                    val nextDayTag = if (w.endsNextDay) " (+1d)" else ""
                                                    "${AvailabilityScheduleParser.formatMinute24Hour(w.startMinuteOfDay)}–${AvailabilityScheduleParser.formatMinute24Hour(w.endMinuteOfDay)}$nextDayTag"
                                                }
                                        } else ""

                                        val dbSummary = if (isDbValid) {
                                            val formatted = AvailabilityScheduleParser.formatWindowsSummary(confirmedWins).joinToString(" | ")
                                            "$formatted ($time24h • $sourceLabel)"
                                        } else {
                                            val statusBadge = runCatching {
                                                ScheduleValidationStatus.valueOf(c.validationStatus).badgeLabel
                                            }.getOrElse { "Needs clarification" }
                                            "$statusBadge (${c.scheduleAmbiguityReason.ifBlank { "No valid availability windows" }})"
                                        }

                                        val matchingStop = activePlan?.stops?.firstOrNull {
                                            it.hcpId == hcpWithDetails.hcp.id && it.clinicId == c.id
                                        }
                                        val planDow = activePlan?.plan?.dateIso?.let {
                                            runCatching { RouteOptimizerEngine.parseIsoDayOfWeek(it) }.getOrNull()
                                        }
                                        val stopMatchesDb = if (matchingStop != null && planDow != null) {
                                            confirmedWins.any { w ->
                                                w.dayOfWeek == planDow &&
                                                    matchingStop.visitStartMinute >= w.startMinuteOfDay &&
                                                    matchingStop.visitEndMinute <= w.effectiveSameDayEndMinute()
                                            }
                                        } else true

                                        val optimizerUsedSummary = when {
                                            matchingStop != null && stopMatchesDb -> {
                                                val s = AvailabilityScheduleParser.formatMinuteOfDay(matchingStop.visitStartMinute)
                                                val e = AvailabilityScheduleParser.formatMinuteOfDay(matchingStop.visitEndMinute)
                                                "Scheduled in Active Plan ($s – $e) inside confirmed DB window"
                                            }
                                            matchingStop != null && !stopMatchesDb -> {
                                                val s = AvailabilityScheduleParser.formatMinuteOfDay(matchingStop.visitStartMinute)
                                                val e = AvailabilityScheduleParser.formatMinuteOfDay(matchingStop.visitEndMinute)
                                                "Mismatch: Scheduled ($s – $e) differs from DB windows"
                                            }
                                            isDbValid -> {
                                                "Eligible from DB (${AvailabilityScheduleParser.formatWindowsSummary(confirmedWins).joinToString(" | ")})"
                                            }
                                            else -> {
                                                "Excluded by Optimizer (No confirmed DB schedule)"
                                            }
                                        }

                                        val (mismatchStage, mismatchDetail, isFlagged) = when {
                                            matchingStop != null && !stopMatchesDb -> Triple(
                                                "OPTIMIZER",
                                                "Active stop time falls outside stored DB availability windows.",
                                                true
                                            )
                                            c.isManualUserConfirmed && freshParsed != null && !freshParsed.clarificationRequired && dbSig != parserSig -> Triple(
                                                "MANUAL_OVERRIDE",
                                                "Protected user-confirmed schedule intentionally overrides Excel.",
                                                false
                                            )
                                            freshParsed != null && !freshParsed.clarificationRequired && !c.isManualUserConfirmed && (dbSig != parserSig || !isDbValid) -> Triple(
                                                "DATABASE",
                                                "Stored DB windows differ from latest parser rules. Run Safe Migration to sync.",
                                                true
                                            )
                                            freshParsed == null || freshParsed.clarificationRequired || !isDbValid -> Triple(
                                                "PARSER",
                                                freshParsed?.ambiguityReason?.ifBlank { "Requires user clarification or end time." }
                                                    ?: "No schedule text present in Excel.",
                                                true
                                            )
                                            else -> Triple("NONE", "", false)
                                        }

                                        FourStageClinicAudit(
                                            hcpWithDetails = hcpWithDetails,
                                            clinicIndex = c.clinicIndex,
                                            zone = c.zone,
                                            originalExcel = effectiveExcelInput.ifBlank { "None in Excel" },
                                            parserStage = parserSummary,
                                            dbStage = dbSummary,
                                            optimizerStage = optimizerUsedSummary,
                                            mismatchStage = mismatchStage,
                                            mismatchDetail = mismatchDetail,
                                            isFlagged = isFlagged
                                        )
                                    }
                                }
                            }

                            val flaggedCount = allClinicAudits.count { it.isFlagged }
                            val displayedAudits = if (showAuditDiscrepanciesOnly) {
                                allClinicAudits.filter { it.isFlagged }
                            } else {
                                allClinicAudits
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "4-Stage Schedule Audit (${allClinicAudits.size} Clinics)",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Excel -> Parser -> Database -> Optimizer discrepancy detector",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                OutlinedButton(
                                    onClick = { showScheduleAuditExpanded = !showScheduleAuditExpanded },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("toggle_schedule_audit_btn")
                                ) {
                                    Text(if (showScheduleAuditExpanded) "Collapse" else "Expand")
                                }
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = !showAuditDiscrepanciesOnly,
                                    onClick = { showAuditDiscrepanciesOnly = false },
                                    label = { Text("All Clinics (${allClinicAudits.size})") },
                                    modifier = Modifier.testTag("audit_filter_all_chip")
                                )
                                FilterChip(
                                    selected = showAuditDiscrepanciesOnly,
                                    onClick = { showAuditDiscrepanciesOnly = true },
                                    label = { Text("Discrepancies ($flaggedCount)") },
                                    modifier = Modifier.testTag("audit_filter_discrepancies_chip")
                                )
                                FilledTonalButton(
                                    onClick = onAuditAllSchedules,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("audit_all_schedules_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Audit Schedules")
                                }
                                OutlinedButton(
                                    onClick = onPreviewScheduleMigration,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("preview_safe_migration_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Safe Migration")
                                }
                            }

                            if (showScheduleAuditExpanded) {
                                displayedAudits.take(40).forEach { audit ->
                                    val badgeLabel = when (audit.mismatchStage) {
                                        "NONE" -> "4-Stage Match"
                                        "MANUAL_OVERRIDE" -> "User Confirmed"
                                        "DATABASE" -> "Diff at Database"
                                        "OPTIMIZER" -> "Diff at Optimizer"
                                        else -> "Diff at Parser"
                                    }
                                    val isWarningCard = audit.isFlagged
                                    val badgeBg = if (isWarningCard) StatusAmberSoft else StatusEmeraldSoft
                                    val badgeFg = if (isWarningCard) StatusAmber else StatusEmerald

                                    Surface(
                                        color = if (isWarningCard) StatusAmberSoft.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isWarningCard) StatusAmber else MaterialTheme.colorScheme.outlineVariant
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "${audit.hcpWithDetails.hcp.name} • Clinic ${audit.clinicIndex} (${audit.zone})",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                StatusBadgePill(
                                                    text = badgeLabel,
                                                    backgroundColor = badgeBg,
                                                    contentColor = badgeFg
                                                )
                                            }
                                            Text(
                                                text = "1. Excel Source: \"${audit.originalExcel}\"",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "2. Parser Engine: ${audit.parserStage}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = "3. Database Stored: ${audit.dbStage}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            Text(
                                                text = "4. Optimizer Status: ${audit.optimizerStage}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (audit.optimizerStage.startsWith("Excluded") || audit.optimizerStage.startsWith("Mismatch")) StatusAmber else StatusEmerald,
                                                fontWeight = FontWeight.Medium
                                            )
                                            if (audit.mismatchDetail.isNotBlank()) {
                                                Text(
                                                    text = "Stage Note (${audit.mismatchStage}): ${audit.mismatchDetail}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isWarningCard) StatusAmber else MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (scheduleMigrationPreview != null) {
                item {
                    AlertDialog(
                        onDismissRequest = onDismissScheduleMigrationPreview,
                        shape = RoundedCornerShape(20.dp),
                        title = {
                            Text(
                                text = "Safe Schedule Migration Preview",
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Inspected ${scheduleMigrationPreview.totalClinicsInspected} clinics: " +
                                        "${scheduleMigrationPreview.candidatesToUpdate.size} to update, " +
                                        "${scheduleMigrationPreview.protectedUserConfirmedCount} user-confirmed preserved, " +
                                        "${scheduleMigrationPreview.unchangedCount} unchanged.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "All HCP IDs, clinic IDs, GPS coordinates, bricks, and visit history are preserved.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusEmerald,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (scheduleMigrationPreview.candidatesToUpdate.isEmpty()) {
                                    Text(
                                        text = "All auto-parsed clinics already match the latest rules.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    scheduleMigrationPreview.candidatesToUpdate.take(6).forEach { cand ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    text = "${cand.hcpName} • ${cand.clinicLabel}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "Excel: \"${cand.originalExcelText}\"",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                                Text(
                                                    text = "Before: ${cand.beforeSummary} -> After: ${cand.afterSummary}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                    if (scheduleMigrationPreview.candidatesToUpdate.size > 6) {
                                        Text(
                                            text = "+ ${scheduleMigrationPreview.candidatesToUpdate.size - 6} more clinic(s)...",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = onDismissScheduleMigrationPreview) {
                                Text("Close")
                            }
                        },
                        confirmButton = {
                            if (scheduleMigrationPreview.candidatesToUpdate.isNotEmpty()) {
                                Button(
                                    onClick = { onApplyScheduleMigration(false) },
                                    modifier = Modifier.testTag("confirm_apply_safe_migration_btn")
                                ) {
                                    Text("Apply Migration")
                                }
                            }
                        }
                    )
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Import Audit Log & Rollback (${importLogs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            items(importLogs, key = { it.id }) { log ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "#${log.id} ${log.fileName} (${log.worksheetName})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Mode: ${log.importMode} • Added: ${log.addedHcps}, Updated: ${log.updatedHcps}, Unchanged: ${log.unchangedHcps}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (log.isRolledBack) {
                            StatusBadgePill(
                                text = "Rolled Back",
                                backgroundColor = StatusAmberSoft,
                                contentColor = StatusAmber
                            )
                        } else if (log.addedHcps > 0) {
                            OutlinedButton(
                                onClick = { onRollbackImport(log) },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Rollback")
                            }
                        }
                    }
                }
            }
        } else {
            item {
                WorkSettingsFormCard(
                    settings = settings,
                    syncConflicts = syncConflicts,
                    themeMode = themeMode,
                    onSetThemeMode = onSetThemeMode,
                    onShowOpeningPoster = onShowOpeningPoster,
                    onSaveSettings = onSaveSettings,
                    onSyncCloud = onSyncCloud,
                    onSignOut = onSignOut
                )
            }
        }

        item { Spacer(modifier = Modifier.height(28.dp)) }
    }
}

@Composable
private fun SettingsSectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun WorkSettingsFormCard(
    settings: WorkSettings,
    syncConflicts: List<SyncConflict>,
    themeMode: String = "SYSTEM",
    onSetThemeMode: (String) -> Unit = {},
    onShowOpeningPoster: () -> Unit = {},
    onSaveSettings: (WorkSettings) -> Unit,
    onSyncCloud: () -> Unit,
    onSignOut: () -> Unit
) {
    var dailyTargetText by remember(settings) { mutableStateOf(settings.dailyHcpTarget.toString()) }
    var homeAddressText by remember(settings) { mutableStateOf(settings.homeAddress) }
    var returnHomeReq by remember(settings) { mutableStateOf(settings.returnHomeRequired) }
    var visitDurationText by remember(settings) { mutableStateOf(settings.defaultVisitDurationMinutes.toString()) }
    var sameLocationTransitionText by remember(settings) { mutableStateOf(settings.sameLocationTransitionMinutes.toString()) }
    var safetyBufferText by remember(settings) { mutableStateOf(settings.trafficSafetyBufferPercent.toString()) }
    var remainingWorkdaysText by remember(settings) { mutableStateOf(settings.remainingQuarterWorkdays.toString()) }
    var overdueDaysText by remember(settings) { mutableStateOf(settings.overdueThresholdDays.toString()) }
    var blankOverwrites by remember(settings) { mutableStateOf(settings.blankCellOverwritesVerified) }
    var selectedWeekendDays by remember(settings) { mutableStateOf(settings.weekendDaysIso) }
    var selectedFirstDayIso by remember(settings) { mutableStateOf(settings.firstDayOfWeekIso) }
    var weekendsDropdownExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 1. Appearance & Welcome Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SettingsSectionHeader(
                    icon = Icons.Default.DarkMode,
                    title = "Appearance",
                    subtitle = "Theme mode & welcome screen",
                    trailing = {
                        OutlinedButton(
                            onClick = onShowOpeningPoster,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("settings_view_opening_poster_btn")
                        ) {
                            Text("Opening Page")
                        }
                    }
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val modes = listOf(
                        "SYSTEM" to "System",
                        "LIGHT" to "Light",
                        "DARK" to "Dark"
                    )
                    modes.forEachIndexed { idx, (code, label) ->
                        SegmentedButton(
                            selected = themeMode == code,
                            onClick = { onSetThemeMode(code) },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = modes.size),
                            modifier = Modifier.testTag("theme_mode_$code")
                        ) {
                            Text(label, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        // 2. Workweek & Calendar Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SettingsSectionHeader(
                    icon = Icons.Default.CalendarMonth,
                    title = "Workweek & Calendar",
                    subtitle = "Choose weekend days and week start"
                )

                val allDays = remember {
                    listOf(
                        7 to "Sunday",
                        1 to "Monday",
                        2 to "Tuesday",
                        3 to "Wednesday",
                        4 to "Thursday",
                        5 to "Friday",
                        6 to "Saturday"
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Weekend Days",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        StatusBadgePill(
                            text = "${selectedWeekendDays.size} off",
                            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { weekendsDropdownExpanded = true }
                                .testTag("settings_weekend_dropdown_btn")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 13.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val weekendSummary = allDays
                                    .filter { it.first in selectedWeekendDays }
                                    .joinToString(", ") { it.second }
                                    .ifBlank { "None (All 7 Days Active)" }
                                Text(
                                    text = weekendSummary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                )
                                Icon(
                                    imageVector = if (weekendsDropdownExpanded) Icons.Default.ExpandLess else Icons.Default.ArrowDropDown,
                                    contentDescription = "Open Weekend Days Droplist",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = weekendsDropdownExpanded,
                            onDismissRequest = { weekendsDropdownExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.86f)
                        ) {
                            allDays.forEach { (iso, label) ->
                                val isWeekend = iso in selectedWeekendDays
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = isWeekend,
                                                onCheckedChange = null
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (isWeekend) "$label (Weekend)" else "$label (Workday)",
                                                fontWeight = if (isWeekend) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedWeekendDays = if (isWeekend) {
                                            selectedWeekendDays - iso
                                        } else {
                                            selectedWeekendDays + iso
                                        }
                                    },
                                    modifier = Modifier.testTag("weekend_day_chip_$iso")
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Done",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                onClick = { weekendsDropdownExpanded = false }
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "First Day of Calendar Week",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(7 to "Sunday", 6 to "Saturday", 1 to "Monday").forEach { (iso, label) ->
                            FilterChip(
                                selected = selectedFirstDayIso == iso,
                                onClick = { selectedFirstDayIso = iso },
                                label = {
                                    Text(
                                        text = label,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("first_day_week_chip_$iso")
                            )
                        }
                    }
                }
                // Section 6: Weekly Visit Frequency Info inside Workweek & Calendar
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("weekly_visit_frequency_card")
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Weekly Visit Frequency",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            StatusBadgePill(
                                text = "Mandatory Rule Active",
                                backgroundColor = StatusEmeraldSoft,
                                contentColor = StatusEmerald
                            )
                        }
                        Text(
                            text = "Each HCP can be scheduled only once per calendar week, regardless of clinic.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val firstDayLabel = when (selectedFirstDayIso) {
                            6 -> "Saturday"
                            7 -> "Sunday"
                            1 -> "Monday"
                            else -> "Sunday"
                        }
                        Text(
                            text = "First day of the week: $firstDayLabel • Weekly restriction is enabled by default across all clinics.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 3. Route & Visit Rules Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsSectionHeader(
                    icon = Icons.Default.Tune,
                    title = "Route & Visit Rules",
                    subtitle = "Targets, visit buffers & home base"
                )

                val numericKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = dailyTargetText,
                        onValueChange = { dailyTargetText = it },
                        label = { Text("Daily Target", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        suffix = { Text("HCPs") },
                        keyboardOptions = numericKeyboard,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = visitDurationText,
                        onValueChange = { visitDurationText = it },
                        label = { Text("Visit Duration", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        suffix = { Text("min") },
                        keyboardOptions = numericKeyboard,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = sameLocationTransitionText,
                        onValueChange = { sameLocationTransitionText = it },
                        label = { Text("Same-Clinic Gap", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        suffix = { Text("min") },
                        keyboardOptions = numericKeyboard,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = safetyBufferText,
                        onValueChange = { safetyBufferText = it },
                        label = { Text("Traffic Buffer", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        suffix = { Text("%") },
                        keyboardOptions = numericKeyboard,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = remainingWorkdaysText,
                        onValueChange = { remainingWorkdaysText = it },
                        label = { Text("Workdays Left", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        suffix = { Text("d") },
                        keyboardOptions = numericKeyboard,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = overdueDaysText,
                        onValueChange = { overdueDaysText = it },
                        label = { Text("Overdue After", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        suffix = { Text("d") },
                        keyboardOptions = numericKeyboard,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = homeAddressText,
                    onValueChange = { homeAddressText = it },
                    label = { Text("Home Base Address") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                SettingsSwitchRow(
                    title = "Return-to-Home Leg",
                    subtitle = "Include drive back home at end of shift",
                    checked = returnHomeReq,
                    onCheckedChange = { returnHomeReq = it }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                SettingsSwitchRow(
                    title = "Blank Cells Overwrite",
                    subtitle = "Allow blank Excel cells to clear verified hours",
                    checked = blankOverwrites,
                    onCheckedChange = { blankOverwrites = it }
                )

                Spacer(modifier = Modifier.height(2.dp))

                Button(
                    onClick = {
                        val updatedWorkingDays = (1..7).filter { it !in selectedWeekendDays }.toSet()
                            .ifEmpty { setOf(7, 1, 2, 3) }
                        onSaveSettings(
                            settings.copy(
                                dailyHcpTarget = dailyTargetText.toIntOrNull() ?: 8,
                                homeAddress = homeAddressText,
                                returnHomeRequired = returnHomeReq,
                                defaultVisitDurationMinutes = visitDurationText.toIntOrNull() ?: 15,
                                sameLocationTransitionMinutes = (sameLocationTransitionText.toIntOrNull() ?: 0).coerceAtLeast(0),
                                trafficSafetyBufferPercent = safetyBufferText.toIntOrNull() ?: 10,
                                remainingQuarterWorkdays = remainingWorkdaysText.toIntOrNull() ?: 42,
                                overdueThresholdDays = overdueDaysText.toIntOrNull() ?: 14,
                                blankCellOverwritesVerified = blankOverwrites,
                                weekendDaysIso = selectedWeekendDays,
                                workingDaysIso = updatedWorkingDays,
                                firstDayOfWeekIso = selectedFirstDayIso,
                                enforceWeeklyHcpVisitLimit = true
                            )
                        )
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("save_work_settings_btn")
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Settings", fontWeight = FontWeight.Bold)
                }
            }
        }

        // 4. Cloud Sync & Account Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsSectionHeader(
                    icon = Icons.Default.CloudSync,
                    title = "Cloud Sync & Account",
                    subtitle = "Offline-first sync & session management"
                )

                if (syncConflicts.isNotEmpty()) {
                    syncConflicts.forEach { c ->
                        Surface(
                            color = StatusCrimsonSoft,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Sync Conflict: ${c.title}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = StatusCrimson
                                )
                                Text(text = c.localSummary, style = MaterialTheme.typography.bodySmall)
                                Text(text = c.remoteSummary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = onSyncCloud,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sync Now", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onSignOut,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .testTag("sign_out_btn")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sign Out", maxLines = 1)
                    }
                }
            }
        }
    }
}

