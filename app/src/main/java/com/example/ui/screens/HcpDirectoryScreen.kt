package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AvailabilityWindowEntity
import com.example.data.model.ClinicEntity
import com.example.data.model.ClinicWithAvailability
import com.example.data.model.HcpEntity
import com.example.data.model.HcpWithDetails
import com.example.domain.parser.AvailabilityScheduleParser
import com.example.domain.parser.ParsingMethod
import com.example.domain.parser.ScheduleClassification
import com.example.domain.parser.ScheduleParseResult
import com.example.domain.parser.ScheduleValidationStatus
import com.example.domain.parser.ScheduleValidator
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusAmberSoft
import com.example.ui.theme.StatusCrimson
import com.example.ui.theme.StatusCrimsonSoft
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.StatusEmeraldSoft
import kotlin.math.roundToInt

@Composable
fun HcpDirectoryScreen(
    hcps: List<HcpWithDetails>,
    onSaveClinicSchedule: (ClinicEntity, List<AvailabilityWindowEntity>) -> Unit,
    onRequestAiScheduleDisambiguation: (ClinicEntity, (ScheduleParseResult) -> Unit) -> Unit,
    onVerifyClinicCoordinates: (ClinicEntity, Double, Double) -> Unit,
    onUpdateBaselineHistory: (HcpEntity, Int, String, Boolean) -> Unit,
    onSaveManualTarget: (
        targetId: String,
        name: String,
        specialty: String,
        zone: String,
        quarterlyTarget: Int,
        priorityScore: Int,
        clinicScheduleText: String,
        notes: String
    ) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onArchiveTarget: (HcpEntity) -> Unit = {},
    onArchiveMultipleTargets: (List<HcpEntity>) -> Unit = { list -> list.forEach(onArchiveTarget) },
    onSaveFullHcpCardEdits: (
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
    ) -> Unit = { hcp, _, _, _, baseline, qtr, incomplete, clinic, _, _, _, windows, _, _ ->
        onUpdateBaselineHistory(hcp, baseline, qtr, incomplete)
        if (clinic != null) {
            onSaveClinicSchedule(clinic, windows)
        }
    },
    onAddClinicToHcp: (
        hcp: HcpEntity,
        zone: String,
        locationUrlOrAddress: String,
        rawScheduleText: String
    ) -> Unit = { _, _, _, _ -> }
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") }
    var selectedHcpForDetail by remember { mutableStateOf<HcpWithDetails?>(null) }
    var showAddTargetDialog by remember { mutableStateOf(false) }
    var showRemoveHcpsDialog by remember { mutableStateOf(false) }

    val filteredHcps = remember(hcps, searchQuery, selectedFilter) {
        hcps.filter { item ->
            val matchesQuery = searchQuery.isBlank() ||
                item.hcp.name.contains(searchQuery, ignoreCase = true) ||
                item.hcp.targetId.contains(searchQuery, ignoreCase = true) ||
                item.hcp.specialty.contains(searchQuery, ignoreCase = true) ||
                item.clinics.any { it.clinic.zone.contains(searchQuery, ignoreCase = true) }

            val matchesFilter = when (selectedFilter) {
                "OVERDUE" -> item.coverage.isOverdue
                "HIGH_RISK" -> item.coverage.riskScore >= 60.0
                "MULTI_CLINIC" -> item.clinics.size > 1
                "MISSING_DATA", "NEEDS_REVIEW" -> item.clinics.any {
                    !it.clinic.userConfirmedSchedule ||
                        (it.clinic.geocodingStatus != "VERIFIED" && it.clinic.geocodingStatus != "EXTRACTED_URL")
                }
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }

    val overdueCount = remember(hcps) { hcps.count { it.coverage.isOverdue } }
    val highRiskCount = remember(hcps) { hcps.count { it.coverage.riskScore >= 60.0 } }
    val missingGpsCount = remember(hcps) {
        hcps.count { item ->
            item.clinics.any { it.clinic.geocodingStatus != "VERIFIED" && it.clinic.geocodingStatus != "EXTRACTED_URL" }
        }
    }
    val unverifiedScheduleCount = remember(hcps) {
        hcps.count { item -> item.clinics.any { !it.clinic.userConfirmedSchedule } }
    }

    val liveSelectedHcp = remember(hcps, selectedHcpForDetail) {
        selectedHcpForDetail?.let { sel -> hcps.find { it.hcp.id == sel.hcp.id } ?: sel }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(6.dp))

        // Compact Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text(
                    text = "Search Doctor, Specialty, or Brick...",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search HCPs",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(
                        onClick = { searchQuery = "" },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear Search",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("directory_search_input"),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Action Buttons Row: Add HCP & Remove HCPs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { showAddTargetDialog = true },
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("add_hcp_target_btn"),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add HCP", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Add HCP",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = { showRemoveHcpsDialog = true },
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("remove_hcps_btn"),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StatusCrimson,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove HCPs",
                    tint = Color.White,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Remove HCPs",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val filters = listOf(
                "ALL" to "All (${hcps.size})",
                "OVERDUE" to "Overdue ($overdueCount)",
                "HIGH_RISK" to "High Coverage Risk ($highRiskCount)",
                "MULTI_CLINIC" to "Multi-Clinic",
                "MISSING_DATA" to "Missing Data"
            )
            filters.forEach { (key, label) ->
                FilterChip(
                    selected = selectedFilter == key,
                    onClick = { selectedFilter = key },
                    label = { Text(label) },
                    modifier = Modifier.testTag("directory_filter_$key")
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filteredHcps, key = { it.hcp.id }) { item ->
                HcpDirectoryCard(
                    item = item,
                    onClick = { selectedHcpForDetail = item },
                    onRemoveHcp = { onArchiveTarget(item.hcp) }
                )
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showAddTargetDialog) {
        AddHcpTargetDialog(
            nextSuggestedId = "W-${101 + hcps.size}",
            onDismiss = { showAddTargetDialog = false },
            onSave = { targetId, name, specialty, zone, qtrTarget, priority, schedule, notes ->
                onSaveManualTarget(targetId, name, specialty, zone, qtrTarget, priority, schedule, notes)
                showAddTargetDialog = false
            }
        )
    }

    if (showRemoveHcpsDialog) {
        RemoveHcpsMultiSelectDialog(
            hcps = hcps,
            onDismiss = { showRemoveHcpsDialog = false },
            onConfirmRemove = { selectedEntities ->
                onArchiveMultipleTargets(selectedEntities)
                showRemoveHcpsDialog = false
            }
        )
    }

    if (liveSelectedHcp != null) {
        HcpDetailAndScheduleEditorDialog(
            item = liveSelectedHcp,
            onDismiss = { selectedHcpForDetail = null },
            onSaveClinicSchedule = onSaveClinicSchedule,
            onRequestAiDisambiguation = onRequestAiScheduleDisambiguation,
            onVerifyCoordinates = onVerifyClinicCoordinates,
            onUpdateBaseline = onUpdateBaselineHistory,
            onSaveFullHcpCardEdits = onSaveFullHcpCardEdits,
            onAddClinicToHcp = onAddClinicToHcp,
            onArchiveTarget = { hcp ->
                onArchiveTarget(hcp)
                selectedHcpForDetail = null
            }
        )
    }
}

@Composable
private fun RemoveHcpsMultiSelectDialog(
    hcps: List<HcpWithDetails>,
    onDismiss: () -> Unit,
    onConfirmRemove: (List<HcpEntity>) -> Unit
) {
    var filterQuery by remember { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<Long>() }

    val filtered = remember(hcps, filterQuery) {
        hcps.filter { item ->
            filterQuery.isBlank() ||
                item.hcp.name.contains(filterQuery, ignoreCase = true) ||
                item.hcp.specialty.contains(filterQuery, ignoreCase = true) ||
                item.hcp.territoryZone.contains(filterQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column {
                Text(
                    text = "Remove HCPs from List",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Select one or more doctors to remove (${selectedIds.size} selected)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = filterQuery,
                    onValueChange = { filterQuery = it },
                    placeholder = { Text("Search doctor to remove...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            if (selectedIds.size == filtered.size) {
                                selectedIds.clear()
                            } else {
                                selectedIds.clear()
                                selectedIds.addAll(filtered.map { it.hcp.id })
                            }
                        }
                    ) {
                        Text(if (selectedIds.size == filtered.size && filtered.isNotEmpty()) "Clear Selection" else "Select All (${filtered.size})")
                    }
                    Text(
                        text = "${selectedIds.size} selected",
                        style = MaterialTheme.typography.labelMedium,
                        color = StatusCrimson,
                        fontWeight = FontWeight.Bold
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { it.hcp.id }) { item ->
                        val isSelected = selectedIds.contains(item.hcp.id)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) StatusCrimsonSoft.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) StatusCrimson else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isSelected) selectedIds.remove(item.hcp.id)
                                    else selectedIds.add(item.hcp.id)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            if (!selectedIds.contains(item.hcp.id)) selectedIds.add(item.hcp.id)
                                        } else {
                                            selectedIds.remove(item.hcp.id)
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.hcp.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${item.hcp.specialty} • ${item.hcp.territoryZone}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val toDelete = hcps.map { it.hcp }.filter { selectedIds.contains(it.id) }
                    onConfirmRemove(toDelete)
                },
                enabled = selectedIds.isNotEmpty(),
                modifier = Modifier.testTag("confirm_remove_selected_hcps_btn")
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Remove (${selectedIds.size})")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddHcpTargetDialog(
    nextSuggestedId: String,
    availableZones: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (
        targetId: String,
        name: String,
        specialty: String,
        zone: String,
        quarterlyTarget: Int,
        priorityScore: Int,
        clinicScheduleText: String,
        notes: String
    ) -> Unit
) {
    var targetId by remember { mutableStateOf(nextSuggestedId) }
    var name by remember { mutableStateOf("") }
    var specialty by remember { mutableStateOf("Cardiologist") }
    var zone by remember {
        mutableStateOf(availableZones.firstOrNull() ?: "العجمي")
    }
    var quarterlyTargetText by remember { mutableStateOf("8") }
    var priorityScoreText by remember { mutableStateOf("80") }
    val selectedDays = remember { mutableStateListOf(7, 2) } // Sun, Tue
    var fromTimeText by remember { mutableStateOf("5:00 PM") }
    var toTimeText by remember { mutableStateOf("8:00 PM") }
    var scheduleText by remember { mutableStateOf("Sun & Tue 5:00 PM - 8:00 PM") }
    var notes by remember { mutableStateOf("") }

    fun syncScheduleFromDayChipsAndTime() {
        val dayOrder = listOf(6, 7, 1, 2, 3, 4, 5) // Sat..Fri
        val ordered = dayOrder.filter { selectedDays.contains(it) }
        val daysPart = if (ordered.isEmpty()) {
            "Sat-Thu"
        } else {
            ordered.joinToString(" & ") { AvailabilityScheduleParser.dayShortName(it) }
        }
        val f = fromTimeText.trim().ifBlank { "5:00 PM" }
        val t = toTimeText.trim().ifBlank { "8:00 PM" }
        scheduleText = "$daysPart $f - $t"
    }

    val liveParsedPreview = remember(scheduleText) {
        val res = AvailabilityScheduleParser.parse(scheduleText)
        if (res.windows.isNotEmpty()) {
            AvailabilityScheduleParser.formatWindowsSummary(res.toEntities(0L, true))
        } else emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column {
                Text(
                    text = "Add Doctor (HCP) Manually",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Enter doctor details, brick, and clinic visiting hours",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Doctor Full Name *") },
                    placeholder = { Text("e.g., Dr. Karim Mansour") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_target_name_input"),
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = specialty,
                        onValueChange = { specialty = it },
                        label = { Text("Specialty") },
                        modifier = Modifier
                            .weight(1.3f)
                            .testTag("add_target_specialty_input"),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = quarterlyTargetText,
                        onValueChange = { quarterlyTargetText = it },
                        label = { Text("Visits / Qtr") },
                        modifier = Modifier.weight(0.8f),
                        singleLine = true
                    )
                }

                // Quick Specialty Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Cardiologist", "Internal Medicine", "Neurologist", "Pediatrician", "Orthopedic").forEach { spec ->
                        FilterChip(
                            selected = specialty.equals(spec, ignoreCase = true),
                            onClick = { specialty = spec },
                            label = { Text(spec, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                OutlinedTextField(
                    value = zone,
                    onValueChange = { zone = it },
                    label = { Text("Brick / Territory Zone") },
                    placeholder = { Text("e.g., العجمي / العامرية / الدخيلة") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_target_zone_input"),
                    singleLine = true
                )

                if (availableZones.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        availableZones.take(8).forEach { z ->
                            FilterChip(
                                selected = zone == z,
                                onClick = { zone = z },
                                label = { Text(z, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                HorizontalDivider()

                Text(
                    text = "Clinic Visiting Days & Hours",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val dayOrder = listOf(6 to "Sat", 7 to "Sun", 1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri")
                    dayOrder.forEach { (dow, shortLabel) ->
                        val isSelected = selectedDays.contains(dow)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) selectedDays.remove(dow) else selectedDays.add(dow)
                                syncScheduleFromDayChipsAndTime()
                            },
                            label = { Text(shortLabel, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = fromTimeText,
                        onValueChange = {
                            fromTimeText = it
                            syncScheduleFromDayChipsAndTime()
                        },
                        label = { Text("From") },
                        placeholder = { Text("5:00 PM") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = toTimeText,
                        onValueChange = {
                            toTimeText = it
                            syncScheduleFromDayChipsAndTime()
                        },
                        label = { Text("To") },
                        placeholder = { Text("8:00 PM") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = scheduleText,
                    onValueChange = { scheduleText = it },
                    label = { Text("Schedule Text (or type any format)") },
                    placeholder = { Text("e.g., Sunday - Sun 2-6 PM, Sat & Mon 4:7 PM") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (liveParsedPreview.isNotEmpty()) {
                    Surface(
                        color = StatusEmeraldSoft,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            liveParsedPreview.forEach { line ->
                                Text(
                                    text = "✓ $line",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusEmerald,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Google Maps Link or Address (Optional)") },
                    placeholder = { Text("https://maps.app.goo.gl/... or clinic address") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = targetId,
                        onValueChange = { targetId = it },
                        label = { Text("Target ID") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("add_target_id_input"),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = priorityScoreText,
                        onValueChange = { priorityScoreText = it },
                        label = { Text("Priority (1-100)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(
                            targetId.trim().ifBlank { nextSuggestedId },
                            name.trim(),
                            specialty.trim().ifBlank { "General Practice" },
                            zone.trim().ifBlank { "Territory Clinic" },
                            quarterlyTargetText.toIntOrNull()?.coerceIn(1, 24) ?: 8,
                            priorityScoreText.toIntOrNull()?.coerceIn(1, 100) ?: 80,
                            scheduleText.trim(),
                            notes.trim()
                        )
                    }
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("save_new_hcp_target_btn")
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Save HCP")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HcpDirectoryCard(
    item: HcpWithDetails,
    onClick: () -> Unit,
    onRemoveHcp: () -> Unit = {}
) {
    val context = LocalContext.current
    val progress = if (item.coverage.quarterlyTarget > 0) {
        (item.coverage.completedVisitsThisQuarter.toFloat() / item.coverage.quarterlyTarget.toFloat()).coerceIn(0f, 1f)
    } else 1f

    val initials = remember(item.hcp.name) {
        item.hcp.name
            .removePrefix("Dr.")
            .trim()
            .split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.take(1).uppercase() }
            .ifBlank { "DR" }
    }

    val cleanName = remember(item.hcp.name) {
        item.hcp.name.replace(Regex("""[\r\n\t]+"""), " ").trim()
    }
    val cleanSpecialty = remember(item.hcp.specialty) {
        item.hcp.specialty.replace(Regex("""[\r\n\t]+"""), " ").trim().ifBlank { "General Practice" }
    }
    val cleanZone = remember(item.hcp.territoryZone) {
        item.hcp.territoryZone.replace(Regex("""[\r\n\t]+"""), " ").trim()
    }
    val sortedClinics = remember(item.clinics) {
        item.clinics.sortedBy { it.clinic.clinicIndex }
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("hcp_card_${item.hcp.targetId}"),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Doctor Header: Full-width Avatar + Name & Specialty, then Visits Pill + Remove Button below
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.secondaryContainer
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = cleanName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val clinicCountText = if (sortedClinics.size > 1) "${sortedClinics.size} Clinics" else "1 Clinic"
                    Text(
                        text = "$cleanSpecialty • $clinicCountText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isTargetMet = item.coverage.completedVisitsThisQuarter >= item.coverage.quarterlyTarget
                StatusBadgePill(
                    text = "${item.coverage.completedVisitsThisQuarter}/${item.coverage.quarterlyTarget} Visits",
                    backgroundColor = if (isTargetMet) StatusEmeraldSoft else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    contentColor = if (isTargetMet) StatusEmerald else MaterialTheme.colorScheme.onPrimaryContainer
                )
                Surface(
                    onClick = onRemoveHcp,
                    shape = RoundedCornerShape(10.dp),
                    color = StatusCrimsonSoft,
                    border = BorderStroke(1.dp, StatusCrimson),
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("remove_hcp_btn_${item.hcp.targetId}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove ${cleanName}",
                            tint = StatusCrimson,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Remove",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusCrimson,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // 2. Slim Quarterly Progress Bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
            )

            // 3. Organized Clinic Locations & Visiting Times (Clinic 1, Clinic 2, ...)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sortedClinics.forEachIndexed { idx, cw ->
                    val c = cw.clinic
                    val clinicNumber = if (c.clinicIndex > 0) c.clinicIndex else (idx + 1)
                    val ownClinicWindows = remember(c.id, item.hcp.id, cw.windows) {
                        cw.windows.filter { it.clinicId == c.id && (it.hcpId == 0L || it.hcpId == item.hcp.id) }
                    }
                    val displayZone = c.zone
                        .replace(Regex("""[\r\n\t]+"""), " ")
                        .trim()
                        .takeIf { it.isNotBlank() && it != "Unspecified Zone" }
                        ?: cleanZone.ifBlank { "Territory Clinic" }

                    val cleanAddressText = remember(c.originalLocationText, c.locationDescription, displayZone) {
                        val locText = c.originalLocationText
                            .replace(Regex("""[\r\n\t]+"""), " ")
                            .trim()
                            .takeIf {
                                it.isNotBlank() &&
                                    !it.startsWith("http", ignoreCase = true) &&
                                    !it.equals("Unverified clinic location", ignoreCase = true) &&
                                    !it.equals(displayZone, ignoreCase = true)
                            }
                        locText ?: c.locationDescription.trim().takeIf {
                            it.isNotBlank() && !it.equals(displayZone, ignoreCase = true)
                        }.orEmpty()
                    }

                    val cleanRawSchedule = remember(c.rawScheduleText) {
                        c.rawScheduleText
                            .replace(Regex("""[\r\n\t]+"""), " ")
                            .trim()
                            .takeIf { it != "Sat–Thu 2:00 PM – 7:00 PM" }
                            .orEmpty()
                    }

                    val validationEnum = remember(
                        c.validationStatus,
                        c.scheduleParseStatus,
                        c.timeInfoType,
                        c.userConfirmedSchedule,
                        c.scheduleClassification,
                        ownClinicWindows
                    ) {
                        runCatching { ScheduleValidationStatus.valueOf(c.validationStatus) }.getOrNull() ?: when {
                            c.userConfirmedSchedule &&
                                c.scheduleParseStatus != "AMBIGUOUS" &&
                                c.scheduleParseStatus != "UNPARSED" &&
                                c.timeInfoType == "CLINIC_AVAILABILITY_RANGE" &&
                                ownClinicWindows.any { it.isConfirmed } -> ScheduleValidationStatus.VALID
                            c.scheduleClassification == ScheduleClassification.PARTIALLY_PARSED.name ->
                                ScheduleValidationStatus.INCOMPLETE
                            c.scheduleClassification == ScheduleClassification.NO_SCHEDULE_INFORMATION.name ->
                                ScheduleValidationStatus.NO_SCHEDULE
                            else -> ScheduleValidationStatus.NEEDS_CLARIFICATION
                        }
                    }

                    val isScheduleVerified = validationEnum == ScheduleValidationStatus.VALID &&
                        c.userConfirmedSchedule &&
                        c.scheduleParseStatus != "AMBIGUOUS" &&
                        c.scheduleParseStatus != "UNPARSED" &&
                        c.timeInfoType == "CLINIC_AVAILABILITY_RANGE" &&
                        ownClinicWindows.any { it.isConfirmed }

                    val cleanScheduleGroups = remember(ownClinicWindows, isScheduleVerified) {
                        val confirmedWindows = ownClinicWindows.filter { it.isConfirmed }
                        if (isScheduleVerified && confirmedWindows.isNotEmpty()) {
                            AvailabilityScheduleParser.formatCleanScheduleGroups(confirmedWindows)
                        } else {
                            emptyList()
                        }
                    }

                    val parsingSourceLabel = remember(c.parsingMethod, c.isManualUserConfirmed, c.scheduleParseStatus) {
                        when {
                            c.isManualUserConfirmed -> ParsingMethod.USER_CONFIRMED.displayLabel
                            else -> runCatching { ParsingMethod.valueOf(c.parsingMethod).displayLabel }
                                .getOrElse {
                                    if (c.scheduleParseStatus == "CONFIRMED") ParsingMethod.USER_CONFIRMED.displayLabel
                                    else ParsingMethod.DETERMINISTIC.displayLabel
                                }
                        }
                    }

                    val classificationLabel = remember(c.scheduleClassification, c.timeInfoType, isScheduleVerified) {
                        runCatching {
                            if (c.scheduleClassification.isNotBlank()) {
                                ScheduleClassification.valueOf(c.scheduleClassification).displayLabel
                            } else null
                        }.getOrNull() ?: when {
                            isScheduleVerified -> "Confirmed Visiting Schedule"
                            c.timeInfoType == "BOOKING_OR_CALL_TIME" -> "Call / Booking Note"
                            c.timeInfoType == "SPECIFIC_APPOINTMENT_TIME" -> "Single Appointment Time"
                            else -> "Needs Clarification"
                        }
                    }

                    val badgeText = when {
                        isScheduleVerified && (c.isManualUserConfirmed || c.parsingMethod == ParsingMethod.USER_CONFIRMED.name) ->
                            "Confirmed"
                        isScheduleVerified -> ScheduleValidationStatus.VALID.badgeLabel
                        validationEnum == ScheduleValidationStatus.INCOMPLETE -> ScheduleValidationStatus.INCOMPLETE.badgeLabel
                        else -> ScheduleValidationStatus.NEEDS_CLARIFICATION.badgeLabel
                    }

                    val normalized24HourSummary = remember(ownClinicWindows, c.exactStartMinute, c.exactEndMinute) {
                        val confirmedWindows = ownClinicWindows.filter { it.isConfirmed }
                        when {
                            confirmedWindows.isNotEmpty() -> {
                                confirmedWindows
                                    .distinctBy { Triple(it.startMinuteOfDay, it.endMinuteOfDay, it.endsNextDay) }
                                    .joinToString(" | ") { w ->
                                        val nextDayTag = if (w.endsNextDay) " (+1 day)" else ""
                                        "${AvailabilityScheduleParser.formatMinute24Hour(w.startMinuteOfDay)}–${AvailabilityScheduleParser.formatMinute24Hour(w.endMinuteOfDay)}$nextDayTag"
                                    }
                            }
                            c.exactStartMinute != null && c.exactEndMinute != null -> {
                                "${AvailabilityScheduleParser.formatMinute24Hour(c.exactStartMinute)}–${AvailabilityScheduleParser.formatMinute24Hour(c.exactEndMinute)}"
                            }
                            c.exactStartMinute != null -> {
                                "${AvailabilityScheduleParser.formatMinute24Hour(c.exactStartMinute)} (start time only)"
                            }
                            else -> "Not defined"
                        }
                    }

                    val hasMapDestination = c.googleMapsUrl.isNotBlank() ||
                        c.originalLocationText.startsWith("http", ignoreCase = true) ||
                        (c.latitude != null && c.longitude != null)

                    var showScheduleDetails by remember(c.id) { mutableStateOf(false) }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Top Row of Clinic Sub-Card: Clinic Badge + Brick/Zone + Maps Action
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = if (clinicNumber == 1) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = "Clinic $clinicNumber",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (clinicNumber == 1) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onTertiaryContainer
                                            },
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = displayZone,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                if (hasMapDestination) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.clickable {
                                            val directUrl = c.googleMapsUrl.ifBlank {
                                                c.originalLocationText.takeIf { it.startsWith("http", ignoreCase = true) }.orEmpty()
                                            }
                                            val uri = if (directUrl.isNotBlank()) {
                                                Uri.parse(directUrl)
                                            } else {
                                                Uri.parse("geo:${c.latitude},${c.longitude}?q=${c.latitude},${c.longitude}(${Uri.encode("$cleanName - Clinic $clinicNumber")})")
                                            }
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                            }
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                                contentDescription = "Open Clinic $clinicNumber in Google Maps",
                                                modifier = Modifier.size(13.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "Maps",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }

                            // Clean Clinic Visiting Schedule Summary + Friendly Validation Badge
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        imageVector = if (isScheduleVerified) Icons.Default.Schedule else Icons.Default.Warning,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(14.dp),
                                        tint = if (isScheduleVerified) StatusEmerald else StatusAmber
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    if (cleanScheduleGroups.isNotEmpty()) {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            cleanScheduleGroups.forEach { (daysLine, timeLine) ->
                                                Text(
                                                    text = daysLine,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = timeLine,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (cleanAddressText.isNotBlank()) {
                                                Text(
                                                    text = "Address: $cleanAddressText",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = cleanRawSchedule.ifBlank { "No clinic schedule" },
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isScheduleVerified) MaterialTheme.colorScheme.onSurface else StatusAmber,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (!isScheduleVerified && c.bookingInstructions.isNotBlank()) {
                                                Text(
                                                    text = c.bookingInstructions,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            if (cleanAddressText.isNotBlank()) {
                                                Text(
                                                    text = "Address: $cleanAddressText",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))
                                StatusBadgePill(
                                    text = badgeText,
                                    backgroundColor = if (isScheduleVerified) StatusEmeraldSoft else StatusAmberSoft,
                                    contentColor = if (isScheduleVerified) StatusEmerald else StatusAmber
                                )
                            }

                            // Expandable Schedule Details Section
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showScheduleDetails = !showScheduleDetails }
                                    .padding(vertical = 2.dp)
                                    .testTag("schedule_details_toggle_${item.hcp.targetId}_${clinicNumber}"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Schedule Details",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    imageVector = if (showScheduleDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (showScheduleDetails) "Hide Schedule Details" else "Show Schedule Details",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            if (showScheduleDetails) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("schedule_details_body_${item.hcp.targetId}_${clinicNumber}")
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        val preservedExcel = c.originalExcelScheduleText.ifBlank { cleanRawSchedule }
                                        Text(
                                            text = "Original Excel: ${if (preservedExcel.isNotBlank()) "\"$preservedExcel\"" else "None"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        val interpretedSummary = if (cleanScheduleGroups.isNotEmpty()) {
                                            cleanScheduleGroups.joinToString("; ") { "${it.first}: ${it.second}" }
                                        } else {
                                            "Not confirmed"
                                        }
                                        Text(
                                            text = "Parsed Schedule: $interpretedSummary ($normalized24HourSummary)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Classification: $classificationLabel",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Source: $parsingSourceLabel",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Validation Status: ${validationEnum.badgeLabel}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isScheduleVerified) StatusEmerald else StatusAmber,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (c.locationDescription.isNotBlank()) {
                                            Text(
                                                text = "Location Note: ${c.locationDescription}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (c.bookingInstructions.isNotBlank()) {
                                            Text(
                                                text = "Booking/Call Note: ${c.bookingInstructions}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Medium,
                                                color = StatusAmber
                                            )
                                        }
                                        if (!isScheduleVerified && c.scheduleAmbiguityReason.isNotBlank()) {
                                            Text(
                                                text = "Note: ${c.scheduleAmbiguityReason}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = StatusAmber
                                            )
                                        }
                                        if (c.sourceFile.isNotBlank() || c.sourceColumn.isNotBlank()) {
                                            val rowSuffix = if (c.sourceRow > 0) " • Row ${c.sourceRow}" else ""
                                            val colSuffix = if (c.sourceColumn.isNotBlank()) " • Col ${c.sourceColumn}" else ""
                                            val sheetSuffix = if (c.sourceSheet.isNotBlank()) " (${c.sourceSheet}$rowSuffix$colSuffix)" else "$rowSuffix$colSuffix"
                                            Text(
                                                text = "Excel Origin: ${c.sourceFile.ifBlank { "Sheet" }}$sheetSuffix • Clinic ID #${c.id}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HcpDetailAndScheduleEditorDialog(
    item: HcpWithDetails,
    onDismiss: () -> Unit,
    onSaveClinicSchedule: (ClinicEntity, List<AvailabilityWindowEntity>) -> Unit,
    onRequestAiDisambiguation: (ClinicEntity, (ScheduleParseResult) -> Unit) -> Unit,
    onVerifyCoordinates: (ClinicEntity, Double, Double) -> Unit,
    onUpdateBaseline: (HcpEntity, Int, String, Boolean) -> Unit,
    onSaveFullHcpCardEdits: (
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
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
    onAddClinicToHcp: (
        hcp: HcpEntity,
        zone: String,
        locationUrlOrAddress: String,
        rawScheduleText: String
    ) -> Unit = { _, _, _, _ -> },
    onArchiveTarget: (HcpEntity) -> Unit = {}
) {
    val context = LocalContext.current
    val sortedClinics = remember(item.clinics) {
        item.clinics.sortedBy { it.clinic.clinicIndex }
    }
    var selectedClinicId by remember(item.hcp.id) {
        mutableStateOf(sortedClinics.firstOrNull()?.clinic?.id ?: 0L)
    }
    val activeClinicWithWindows: ClinicWithAvailability? = sortedClinics.firstOrNull { it.clinic.id == selectedClinicId }
        ?: sortedClinics.firstOrNull()
    val activeClinic: ClinicEntity? = activeClinicWithWindows?.clinic
    val safeTabIdx = sortedClinics.indexOfFirst { it.clinic.id == activeClinic?.id }.coerceAtLeast(0)
    val activeClinicOwnWindows = remember(activeClinic?.id, item.hcp.id, activeClinicWithWindows?.windows) {
        activeClinicWithWindows?.windows?.filter {
            activeClinic != null && it.clinicId == activeClinic.id && (it.hcpId == 0L || it.hcpId == item.hcp.id)
        }.orEmpty()
    }

    // Doctor-level editable state
    var editableHcpName by remember(item.hcp.id) { mutableStateOf(item.hcp.name) }
    var editableSpecialty by remember(item.hcp.id) { mutableStateOf(item.hcp.specialty) }
    var editableQuarterlyTargetText by remember(item.hcp.id) { mutableStateOf(item.hcp.quarterlyTarget.toString()) }
    var baselineCountText by remember(item.hcp.id) { mutableStateOf(item.hcp.importedBaselineVisits.toString()) }
    var baselineQuarterText by remember(item.hcp.id) { mutableStateOf(item.hcp.baselineQuarterLabel) }
    var historyIncompleteFlag by remember(item.hcp.id) { mutableStateOf(item.hcp.historyIncomplete) }

    // Active Clinic editable state (keyed strictly by activeClinic?.id so switching clinics loads ONLY that clinic's data)
    val editableWindows = remember(activeClinic?.id, activeClinicOwnWindows) {
        mutableStateListOf<AvailabilityWindowEntity>().apply {
            addAll(activeClinicOwnWindows)
        }
    }
    var editableClinicZone by remember(activeClinic?.id) {
        mutableStateOf(activeClinic?.zone?.takeIf { it != "Unspecified Zone" }.orEmpty())
    }
    var editableClinicLocation by remember(activeClinic?.id) {
        mutableStateOf(activeClinic?.originalLocationText?.ifBlank { activeClinic.googleMapsUrl }.orEmpty())
    }
    var quickRangeText by remember(activeClinic?.id) {
        val initialRaw = activeClinic?.rawScheduleText
            ?.takeIf { it.isNotBlank() && it != "Sat–Thu 2:00 PM – 7:00 PM" }
            ?: activeClinicOwnWindows.takeIf { it.isNotEmpty() }?.let {
                AvailabilityScheduleParser.formatWindowsSummary(it).joinToString(" | ")
            }
            .orEmpty()
        mutableStateOf(initialRaw)
    }
    var latText by remember(activeClinic?.id, activeClinic?.latitude) {
        mutableStateOf(activeClinic?.latitude?.toString() ?: "")
    }
    var lngText by remember(activeClinic?.id, activeClinic?.longitude) {
        mutableStateOf(activeClinic?.longitude?.toString() ?: "")
    }

    val selectedBuilderDays = remember(activeClinic?.id) {
        mutableStateListOf(1)
    }
    var newStartHour by remember(activeClinic?.id) { mutableStateOf("4 PM") }
    var newEndHour by remember(activeClinic?.id) { mutableStateOf("7 PM") }

    // Optional inline "+ Add Clinic" form state
    var showAddClinicInline by remember { mutableStateOf(false) }
    var newClinicZone by remember { mutableStateOf("") }
    var newClinicLoc by remember { mutableStateOf("") }
    var newClinicSched by remember { mutableStateOf("Sunday and Tuesday 5-8 PM") }

    fun resolveFinalWindowsForActiveClinic(): List<AvailabilityWindowEntity> {
        val cid = activeClinic?.id ?: return emptyList()
        val typed = quickRangeText.trim()
        val originalRaw = activeClinic.rawScheduleText.trim()
        val originalWindows = activeClinicOwnWindows

        // If user edited quickRangeText and didn't manually change editableWindows, parse quickRangeText
        val windowsUnchangedFromInitial = editableWindows.size == originalWindows.size &&
            editableWindows.zip(originalWindows).all { (a, b) ->
                a.dayOfWeek == b.dayOfWeek &&
                    a.startMinuteOfDay == b.startMinuteOfDay &&
                    a.endMinuteOfDay == b.endMinuteOfDay &&
                    a.endsNextDay == b.endsNextDay
            }
        if (typed.isNotBlank() && (editableWindows.isEmpty() || (typed != originalRaw && windowsUnchangedFromInitial))) {
            val parsed = ScheduleValidator.validate(AvailabilityScheduleParser.parse(typed))
            if (parsed.windows.isNotEmpty()) {
                return parsed.toEntities(
                    clinicId = cid,
                    isConfirmed = true,
                    hcpId = item.hcp.id,
                    originalExcelText = activeClinic.originalExcelScheduleText.ifBlank { typed },
                    sourceSheet = activeClinic.sourceSheet,
                    sourceRow = activeClinic.sourceRow,
                    sourceColumn = activeClinic.sourceColumn
                )
            }
        }
        return editableWindows.map { it.copy(clinicId = cid, hcpId = item.hcp.id) }
    }

    fun persistCurrentEdits() {
        val finalWins = resolveFinalWindowsForActiveClinic()
        val parsedLat = latText.trim().toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
        val parsedLng = lngText.trim().toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
        onSaveFullHcpCardEdits(
            item.hcp,
            editableHcpName.trim().ifBlank { item.hcp.name },
            editableSpecialty.trim().ifBlank { item.hcp.specialty },
            editableQuarterlyTargetText.toIntOrNull()?.coerceIn(1, 60) ?: item.hcp.quarterlyTarget,
            baselineCountText.toIntOrNull()?.coerceIn(0, 60) ?: 0,
            baselineQuarterText.ifBlank { "2026-Q4" },
            historyIncompleteFlag,
            activeClinic,
            editableClinicZone.trim().ifBlank { activeClinic?.zone.orEmpty() },
            editableClinicLocation.trim(),
            quickRangeText.trim(),
            finalWins,
            parsedLat,
            parsedLng
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onArchiveTarget(item.hcp) },
                    modifier = Modifier.testTag("archive_hcp_target_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = StatusCrimson,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Archive", color = StatusCrimson)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    persistCurrentEdits()
                    onDismiss()
                },
                modifier = Modifier.testTag("save_hcp_card_changes_btn")
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Save Changes", fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Column {
                Text(
                    text = editableHcpName.ifBlank { item.hcp.name },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${editableSpecialty.ifBlank { item.hcp.specialty }} • ${sortedClinics.size} Clinic(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Editable Doctor Profile & Quarterly Target
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Doctor Profile & Quarterly Target",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedTextField(
                            value = editableHcpName,
                            onValueChange = { editableHcpName = it },
                            label = { Text("Doctor Name") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("edit_hcp_name_input"),
                            singleLine = true
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = editableSpecialty,
                                onValueChange = { editableSpecialty = it },
                                label = { Text("Specialty") },
                                modifier = Modifier
                                    .weight(1.3f)
                                    .testTag("edit_hcp_specialty_input"),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = editableQuarterlyTargetText,
                                onValueChange = { editableQuarterlyTargetText = it },
                                label = { Text("Qtr Target") },
                                modifier = Modifier
                                    .weight(0.7f)
                                    .testTag("edit_hcp_target_input"),
                                singleLine = true
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = baselineCountText,
                                onValueChange = { baselineCountText = it },
                                label = { Text("Prior Visits") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = baselineQuarterText,
                                onValueChange = { baselineQuarterText = it },
                                label = { Text("Quarter") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = historyIncompleteFlag,
                                onCheckedChange = { historyIncompleteFlag = it }
                            )
                            Text(
                                text = "Mark history incomplete",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            FilledTonalButton(
                                onClick = { persistCurrentEdits() }
                            ) {
                                Text("Save")
                            }
                        }
                    }
                }

                // 2. Clinic Selector Tabs (Clinic 1, Clinic 2, + Add Clinic)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    sortedClinics.forEachIndexed { idx, cw ->
                        val cNum = if (cw.clinic.clinicIndex > 0) cw.clinic.clinicIndex else (idx + 1)
                        val zoneShort = cw.clinic.zone.takeIf { it.isNotBlank() && it != "Unspecified Zone" } ?: "Clinic"
                        FilterChip(
                            selected = !showAddClinicInline && cw.clinic.id == activeClinic?.id,
                            onClick = {
                                showAddClinicInline = false
                                selectedClinicId = cw.clinic.id
                            },
                            label = { Text("Clinic $cNum • $zoneShort") },
                            modifier = Modifier.testTag("select_clinic_tab_$cNum")
                        )
                    }
                    FilterChip(
                        selected = showAddClinicInline,
                        onClick = { showAddClinicInline = !showAddClinicInline },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Add Clinic") },
                        modifier = Modifier.testTag("add_second_clinic_chip")
                    )
                }

                if (showAddClinicInline) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Add Clinic ${sortedClinics.size + 1}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            OutlinedTextField(
                                value = newClinicZone,
                                onValueChange = { newClinicZone = it },
                                label = { Text("Clinic Brick / Zone") },
                                placeholder = { Text("e.g., العجمي / الدخيلة / العامرية") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = newClinicSched,
                                onValueChange = { newClinicSched = it },
                                label = { Text("Visiting Time in Clinic ${sortedClinics.size + 1}") },
                                placeholder = { Text("e.g., Sunday and Tuesday 6-9 PM") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = newClinicLoc,
                                onValueChange = { newClinicLoc = it },
                                label = { Text("Google Maps Link or Address") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { showAddClinicInline = false }) {
                                    Text("Cancel")
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Button(
                                    onClick = {
                                        onAddClinicToHcp(
                                            item.hcp,
                                            newClinicZone.trim(),
                                            newClinicLoc.trim(),
                                            newClinicSched.trim()
                                        )
                                        newClinicZone = ""
                                        newClinicLoc = ""
                                        showAddClinicInline = false
                                    }
                                ) {
                                    Text("Save New Clinic")
                                }
                            }
                        }
                    }
                }

                // 3. Active Clinic Editor
                if (activeClinic != null && !showAddClinicInline) {
                    val cNum = if (activeClinic.clinicIndex > 0) activeClinic.clinicIndex else (safeTabIdx + 1)

                    val preservedExcelText = activeClinic.originalExcelScheduleText.ifBlank { activeClinic.rawScheduleText }
                    val editorClassLabel = runCatching {
                        if (activeClinic.scheduleClassification.isNotBlank()) {
                            ScheduleClassification.valueOf(activeClinic.scheduleClassification).displayLabel
                        } else null
                    }.getOrNull() ?: "Clinic Schedule"
                    val editorSourceLabel = if (activeClinic.isManualUserConfirmed) {
                        ParsingMethod.USER_CONFIRMED.displayLabel
                    } else {
                        runCatching { ParsingMethod.valueOf(activeClinic.parsingMethod).displayLabel }
                            .getOrElse { ParsingMethod.DETERMINISTIC.displayLabel }
                    }
                    val editorValidationLabel = runCatching {
                        ScheduleValidationStatus.valueOf(activeClinic.validationStatus).badgeLabel
                    }.getOrElse { if (activeClinic.userConfirmedSchedule) "Valid" else "Needs clarification" }
                    if (preservedExcelText.isNotBlank() || activeClinic.aiInterpretedScheduleText.isNotBlank() || activeClinic.bookingInstructions.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = "Excel Schedule Source of Truth (Clinic $cNum)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Original Excel: \"${preservedExcelText.ifBlank { "None" }}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "$editorClassLabel • $editorSourceLabel • $editorValidationLabel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (activeClinic.bookingInstructions.isNotBlank()) {
                                    Text(
                                        text = "Booking/Call Note: ${activeClinic.bookingInstructions}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StatusAmber,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                if (activeClinic.aiInterpretedScheduleText.isNotBlank()) {
                                    Text(
                                        text = "Interpreted: \"${activeClinic.aiInterpretedScheduleText}\"",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (!activeClinic.userConfirmedSchedule ||
                        activeClinic.scheduleParseStatus == "AMBIGUOUS" ||
                        activeClinic.scheduleParseStatus == "UNPARSED" ||
                        activeClinic.timeInfoType != "CLINIC_AVAILABILITY_RANGE" ||
                        activeClinic.scheduleAmbiguityReason.isNotBlank()
                    ) {
                        Surface(
                            color = StatusAmberSoft.copy(alpha = 0.75f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, StatusAmber),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("clinic_schedule_confirmation_alert_$cNum")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = StatusAmber,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Clinic $cNum Schedule Requires Confirmation",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = StatusAmber
                                    )
                                }
                                if (activeClinic.rawScheduleText.isNotBlank()) {
                                    Text(
                                        text = "Original text: \"${activeClinic.rawScheduleText}\"",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                if (activeClinic.scheduleAmbiguityReason.isNotBlank()) {
                                    Text(
                                        text = activeClinic.scheduleAmbiguityReason,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Clinic $cNum Brick & Visiting Schedule",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            OutlinedTextField(
                                value = editableClinicZone,
                                onValueChange = { editableClinicZone = it },
                                label = { Text("Clinic $cNum Brick / Zone") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("edit_clinic_zone_input"),
                                singleLine = true
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = quickRangeText,
                                    onValueChange = { updatedText ->
                                        quickRangeText = updatedText
                                        val liveParsed = AvailabilityScheduleParser.parse(updatedText)
                                        if (liveParsed.windows.isNotEmpty()) {
                                            editableWindows.clear()
                                            editableWindows.addAll(
                                                liveParsed.toEntities(
                                                    clinicId = activeClinic.id,
                                                    isConfirmed = true,
                                                    hcpId = item.hcp.id
                                                )
                                            )
                                        }
                                    },
                                    label = { Text("Visiting Time in Clinic $cNum (e.g. Sun & Tue 5-8 PM)") },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("quick_schedule_format_input"),
                                    singleLine = true
                                )
                                FilledTonalButton(
                                    onClick = {
                                        val parsed = AvailabilityScheduleParser.parse(quickRangeText)
                                        if (parsed.windows.isNotEmpty()) {
                                            editableWindows.clear()
                                            editableWindows.addAll(
                                                parsed.toEntities(
                                                    clinicId = activeClinic.id,
                                                    isConfirmed = true,
                                                    hcpId = item.hcp.id
                                                )
                                            )
                                        }
                                    },
                                    modifier = Modifier.testTag("parse_quick_schedule_btn")
                                ) {
                                    Text("Parse")
                                }
                            }
                        }
                    }

                    Text(
                        text = "Clinic $cNum Weekly Windows (${editableWindows.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    editableWindows.forEachIndexed { idx, win ->
                        val overnightSuffix = if (win.endsNextDay) " (+1 day)" else ""
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${AvailabilityScheduleParser.dayName(win.dayOfWeek)}: ${AvailabilityScheduleParser.formatMinuteOfDay(win.startMinuteOfDay)} – ${AvailabilityScheduleParser.formatMinuteOfDay(win.endMinuteOfDay)}$overnightSuffix",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                            IconButton(
                                onClick = {
                                    editableWindows.removeAt(idx)
                                    quickRangeText = AvailabilityScheduleParser.formatWindowsSummary(editableWindows).joinToString(" | ")
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Window", modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                selectedBuilderDays.clear()
                                selectedBuilderDays.addAll(listOf(6, 7, 1, 2, 3, 4, 5))
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("editor_select_all_days_btn")
                        ) {
                            Text("Select All", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = {
                                val excludeSet = selectedBuilderDays.toSet()
                                editableWindows.removeAll { it.dayOfWeek in excludeSet }
                                quickRangeText = AvailabilityScheduleParser.formatWindowsSummary(editableWindows).joinToString(" | ")
                            },
                            enabled = selectedBuilderDays.isNotEmpty() && editableWindows.any { it.dayOfWeek in selectedBuilderDays },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("editor_exclude_days_btn")
                        ) {
                            Text("Exclude Selected", style = MaterialTheme.typography.labelSmall, color = StatusCrimson)
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(6, 7, 1, 2, 3, 4, 5).forEach { dow ->
                            val isSelected = dow in selectedBuilderDays
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (isSelected) {
                                        selectedBuilderDays.remove(dow)
                                    } else {
                                        selectedBuilderDays.add(dow)
                                    }
                                },
                                label = { Text(AvailabilityScheduleParser.dayShortName(dow)) },
                                modifier = Modifier.testTag("editor_day_chip_$dow")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newStartHour,
                            onValueChange = { newStartHour = it },
                            label = { Text("Start / Range") },
                            placeholder = { Text("4 PM or 4:7 PM") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = newEndHour,
                            onValueChange = { newEndHour = it },
                            label = { Text("End") },
                            placeholder = { Text("7 PM or 1 AM") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        FilledTonalButton(
                            onClick = {
                                val targetDays = selectedBuilderDays.distinct().ifEmpty { listOf(1) }
                                val rangeFromStart = AvailabilityScheduleParser.parseTimeRangeWindow(newStartHour)
                                val combinedRange = if (rangeFromStart == null) {
                                    AvailabilityScheduleParser.parseTimeRangeWindow("${newStartHour.trim()} - ${newEndHour.trim()}")
                                } else null
                                val startMin: Int?
                                val endMin: Int?
                                if (rangeFromStart != null) {
                                    startMin = rangeFromStart.startMinuteOfDay
                                    endMin = rangeFromStart.endMinuteOfDay
                                } else if (combinedRange != null) {
                                    startMin = combinedRange.startMinuteOfDay
                                    endMin = combinedRange.endMinuteOfDay
                                } else {
                                    startMin = parseHhMmToMinutes(newStartHour)
                                    endMin = parseHhMmToMinutes(newEndHour)
                                }
                                if (startMin != null && endMin != null) {
                                    val isOvernight = endMin < startMin && ((1440 - startMin) + endMin) in 15..720
                                    if (endMin > startMin || isOvernight) {
                                        for (dow in targetDays) {
                                            val candidate = AvailabilityWindowEntity(
                                                hcpId = item.hcp.id,
                                                clinicId = activeClinic.id,
                                                dayOfWeek = dow,
                                                startMinuteOfDay = startMin,
                                                endMinuteOfDay = endMin,
                                                endsNextDay = isOvernight,
                                                isConfirmed = true
                                            )
                                            val alreadyExists = editableWindows.any {
                                                it.dayOfWeek == candidate.dayOfWeek &&
                                                    it.startMinuteOfDay == candidate.startMinuteOfDay &&
                                                    it.endMinuteOfDay == candidate.endMinuteOfDay &&
                                                    it.endsNextDay == candidate.endsNextDay
                                            }
                                            if (!alreadyExists) {
                                                editableWindows.add(candidate)
                                            }
                                        }
                                        quickRangeText = AvailabilityScheduleParser.formatWindowsSummary(editableWindows).joinToString(" | ")
                                    }
                                }
                            },
                            modifier = Modifier.testTag("editor_add_window_btn")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Add")
                        }
                    }

                    Button(
                        onClick = {
                            val finalWins = resolveFinalWindowsForActiveClinic()
                            onSaveClinicSchedule(activeClinic, finalWins)
                            persistCurrentEdits()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("confirm_clinic_schedule_btn")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Confirm & Save Clinic $cNum Schedule")
                    }

                    HorizontalDivider()

                    Text(
                        text = "Clinic $cNum Location & GPS",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = editableClinicLocation,
                        onValueChange = { editableClinicLocation = it },
                        label = { Text("Google Maps URL or Address") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_clinic_location_input"),
                        singleLine = true
                    )
                    if (editableClinicLocation.startsWith("http", ignoreCase = true) || activeClinic.googleMapsUrl.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                val targetUrl = editableClinicLocation.takeIf { it.startsWith("http", ignoreCase = true) }
                                    ?: activeClinic.googleMapsUrl
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                                } catch (_: Exception) {
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Google Maps Link")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = latText,
                            onValueChange = { latText = it },
                            label = { Text("Latitude") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = lngText,
                            onValueChange = { lngText = it },
                            label = { Text("Longitude") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        FilledTonalButton(
                            onClick = {
                                val lat = latText.toDoubleOrNull()
                                val lng = lngText.toDoubleOrNull()
                                if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                                    onVerifyCoordinates(activeClinic, lat, lng)
                                }
                            }
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Verify")
                        }
                    }
                }
            }
        }
    )
}

private fun parseHhMmToMinutes(text: String): Int? {
    AvailabilityScheduleParser.parseSingleTimeToken(text, defaultMeridiem = "pm")?.let { return it }
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}
