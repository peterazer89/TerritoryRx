package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.data.model.ClinicEntity
import com.example.data.model.DailyPlanWithStops
import com.example.data.model.EMPTY_PREFERRED_SPECIALTIES_MESSAGE
import com.example.data.model.HcpWithDetails
import com.example.data.model.PlannedStopEntity
import com.example.data.model.SpecialtyNormalizer
import com.example.data.model.WEEKLY_HCP_RESTRICTION_MESSAGE
import com.example.data.model.WeeklyHcpBlockInfo
import com.example.data.model.WorkSettings
import com.example.data.model.buildManualUnselectedSpecialtyWarning
import com.example.domain.optimizer.OptimizationOutcome
import com.example.domain.optimizer.RouteOptimizerEngine
import com.example.domain.optimizer.WeeklyPlanResult
import com.example.domain.parser.AvailabilityScheduleParser
import com.example.ui.ShiftPreset
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusAmberSoft
import com.example.ui.theme.StatusCrimson
import com.example.ui.theme.StatusCrimsonSoft
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.StatusEmeraldSoft
import com.example.ui.theme.StatusIndigo
import com.example.ui.theme.StatusIndigoSoft

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlannerScreen(
    selectedDateIso: String,
    alternatives: List<DailyPlanWithStops>,
    activePlan: DailyPlanWithStops?,
    weeklyPlanResult: WeeklyPlanResult?,
    settings: WorkSettings,
    hcps: List<HcpWithDetails> = emptyList(),
    targetVisits: Int = 8,
    selectedShift: ShiftPreset = ShiftPreset.AFTERNOON_EVENING,
    shiftStartMinute: Int = 13 * 60,
    shiftEndMinute: Int = 21 * 60,
    selectedBricks: Set<String> = emptySet(),
    availableBricks: List<String> = emptyList(),
    aiPlanningBriefing: String? = null,
    isAiPlanningBriefingLoading: Boolean = false,
    lastOptimizationOutcome: OptimizationOutcome? = null,
    weeklyBlockedByHcpId: Map<Long, WeeklyHcpBlockInfo> = emptyMap(),
    onSetTargetVisits: (Int) -> Unit = {},
    onSelectShift: (ShiftPreset) -> Unit = {},
    onSetCustomWorkTiming: (String, String) -> Unit = { _, _ -> },
    onSetCustomWorkMinutes: (Int, Int) -> Unit = { _, _ -> },
    onToggleBrick: (String) -> Unit = {},
    onSelectAllBricks: () -> Unit = {},
    onRefreshAiBriefing: () -> Unit = {},
    onStepDate: (Int) -> Unit,
    onSelectDate: (String) -> Unit,
    onOptimizeDay: () -> Unit,
    onOptimizeWeek: () -> Unit,
    onGenerateAiPlan: () -> Unit = onOptimizeDay,
    onSelectPlanVariant: (Long) -> Unit,
    onMarkStopStatus: (DailyPlanWithStops, PlannedStopEntity, String) -> Unit,
    onToggleLock: (PlannedStopEntity) -> Unit,
    onMoveStopOrder: (DailyPlanWithStops, PlannedStopEntity, Int) -> Unit,
    onUpdateStopDuration: (DailyPlanWithStops, PlannedStopEntity, Int) -> Unit = { _, _, _ -> },
    onSwitchStopClinic: (DailyPlanWithStops, PlannedStopEntity, ClinicEntity) -> Unit = { _, _, _ -> },
    onAddHcpFromListToPlan: (HcpWithDetails, ClinicEntity?) -> Unit = { _, _ -> },
    onNavigateToPlanningPriorities: () -> Unit = {},
    onSaveManualTarget: (
        targetId: String,
        name: String,
        specialty: String,
        zone: String,
        quarterlyTarget: Int,
        priorityScore: Int,
        clinicScheduleText: String,
        notes: String
    ) -> Unit = { _, _, _, _, _, _, _, _ -> }
) {
    var horizonTab by remember { mutableIntStateOf(0) } // 0 = Daily, 1 = Weekly
    var showComparisonTable by remember { mutableStateOf(false) }
    var showAddHcpDialog by remember { mutableStateOf(false) }
    var showAiStrategyDialog by remember { mutableStateOf(false) }
    val hcpsById = remember(hcps) { hcps.associateBy { it.hcp.id } }

    val dayOfWeek = remember(selectedDateIso) {
        RouteOptimizerEngine.parseIsoDayOfWeek(selectedDateIso)
    }
    val dayName = remember(dayOfWeek) {
        AvailabilityScheduleParser.dayName(dayOfWeek)
    }

    if (showAddHcpDialog) {
        val currentPlannedHcpIds = remember(activePlan) {
            activePlan?.stops?.map { it.hcpId }?.toSet().orEmpty()
        }
        AddHcpFromCurrentListDialog(
            hcps = hcps,
            alreadyPlannedHcpIds = currentPlannedHcpIds,
            weeklyBlockedByHcpId = weeklyBlockedByHcpId,
            preferredSpecialtiesRanked = settings.preferredSpecialtiesRanked,
            availableBricks = availableBricks,
            onDismiss = { showAddHcpDialog = false },
            onAddHcpToPlan = { hcpItem, chosenClinic ->
                onAddHcpFromListToPlan(hcpItem, chosenClinic)
            }
        )
    }

    if (showAiStrategyDialog) {
        AiRouteStrategyDialog(
            briefing = aiPlanningBriefing,
            isLoading = isAiPlanningBriefingLoading,
            hasPlan = activePlan != null,
            onRefresh = onRefreshAiBriefing,
            onGeneratePlan = {
                if (horizonTab == 0) onGenerateAiPlan() else onOptimizeWeek()
            },
            onDismiss = { showAiStrategyDialog = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(modifier = Modifier.height(2.dp)) }

        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = horizonTab == 0,
                    onClick = { horizonTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    modifier = Modifier.weight(1f).testTag("planner_daily_tab")
                ) {
                    Text("Daily Route", maxLines = 1, softWrap = false)
                }
                SegmentedButton(
                    selected = horizonTab == 1,
                    onClick = {
                        horizonTab = 1
                        if (weeklyPlanResult == null) {
                            onOptimizeWeek()
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    modifier = Modifier.weight(1f).testTag("planner_weekly_tab")
                ) {
                    Text("Weekly 5-Day", maxLines = 1, softWrap = false)
                }
            }
        }

        if (horizonTab == 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(42.dp)
                        ) {
                            IconButton(
                                onClick = { onStepDate(-1) },
                                modifier = Modifier.testTag("planner_prev_date_btn")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous Date")
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "$dayName • $selectedDateIso",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Hours: ${AvailabilityScheduleParser.formatMinuteOfDay(shiftStartMinute)} – ${AvailabilityScheduleParser.formatMinuteOfDay(shiftEndMinute)} • Goal: $targetVisits Visits",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(42.dp)
                        ) {
                            IconButton(
                                onClick = { onStepDate(1) },
                                modifier = Modifier.testTag("planner_next_date_btn")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next Date")
                            }
                        }
                    }
                }
            }

            item {
                PlanningControlsCard(
                    settings = settings,
                    targetVisits = targetVisits,
                    selectedShift = selectedShift,
                    shiftStartMinute = shiftStartMinute,
                    shiftEndMinute = shiftEndMinute,
                    selectedBricks = selectedBricks,
                    availableBricks = availableBricks,
                    hasActivePlan = activePlan != null && activePlan.stops.isNotEmpty(),
                    onSetTargetVisits = onSetTargetVisits,
                    onSelectShift = onSelectShift,
                    onSetCustomWorkTiming = onSetCustomWorkTiming,
                    onSetCustomWorkMinutes = onSetCustomWorkMinutes,
                    onToggleBrick = onToggleBrick,
                    onSelectAllBricks = onSelectAllBricks
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { onGenerateAiPlan() },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1.3f)
                            .heightIn(min = 48.dp)
                            .testTag("planner_ai_plan_btn")
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "AI Route Plan",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    FilledTonalButton(
                        onClick = { showAddHcpDialog = true },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("planner_add_hcp_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add HCP from List", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Add HCP",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            if (alternatives.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Route Options (${alternatives.size})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                FilterChip(
                                    selected = showComparisonTable,
                                    onClick = { showComparisonTable = !showComparisonTable },
                                    label = { Text(if (showComparisonTable) "Hide Matrix" else "Compare A/B/C") }
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                alternatives.forEach { alt ->
                                    val isSelected = alt.plan.id == activePlan?.plan?.id
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { onSelectPlanVariant(alt.plan.id) },
                                        label = {
                                            Text(
                                                "${alt.plan.variantLabel} (${alt.plan.uniqueHcpsCount} stops, ${alt.plan.totalDrivingMinutes}m drive)"
                                            )
                                        },
                                        modifier = Modifier.testTag("select_plan_${alt.plan.variantCode}")
                                    )
                                }
                            }

                            if (showComparisonTable && alternatives.size > 1) {
                                Spacer(modifier = Modifier.height(10.dp))
                                AlternativePlanComparisonTable(
                                    alternatives = alternatives,
                                    activePlanId = activePlan?.plan?.id,
                                    targetHcps = settings.dailyHcpTarget,
                                    onSelectPlan = onSelectPlanVariant
                                )
                            }
                        }
                    }
                }
            }

            if (activePlan != null) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("planner_validator_banner"),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Active Route Summary",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                StatusBadgePill(
                                    text = "${activePlan.stops.sumOf { it.remainingQuarterlyVisits }} Rem. Qtr",
                                    backgroundColor = StatusIndigoSoft,
                                    contentColor = StatusIndigo
                                )
                            }
                            Text(
                                text = "Shift Window: ${AvailabilityScheduleParser.formatMinuteOfDay(activePlan.plan.startMinuteOfDay)} – ${AvailabilityScheduleParser.formatMinuteOfDay(activePlan.plan.endMinuteOfDay)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )

                            // Structured 4-Column KPI Grid (identical size & single-line text across Visits, Distance, Drive, Gap Wait)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(60.dp),
                                    color = StatusEmeraldSoft,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 4.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "${activePlan.stops.size}/$targetVisits",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = StatusEmerald,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        Text(
                                            text = "Visits",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StatusEmerald,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(60.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 4.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "${"%.1f".format(activePlan.plan.totalDistanceKm)} km",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        Text(
                                            text = "Distance",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(60.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 4.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "${activePlan.plan.totalDrivingMinutes}m",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        Text(
                                            text = "Drive",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(60.dp),
                                    color = if (activePlan.plan.totalWaitingMinutes == 0) StatusEmeraldSoft else StatusAmberSoft,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 4.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "${activePlan.plan.totalWaitingMinutes}m",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (activePlan.plan.totalWaitingMinutes == 0) StatusEmerald else StatusAmber,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        Text(
                                            text = "Gap Wait",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (activePlan.plan.totalWaitingMinutes == 0) StatusEmerald else StatusAmber,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Plan Generation Summary
                            val activeHcpItems = hcps.filter { it.hcp.isActive && !it.hcp.isDeleted }
                            val weeklyExcludedCount = lastOptimizationOutcome?.weeklyRestrictedExcluded
                                ?: weeklyBlockedByHcpId.keys.count { id -> activePlan.stops.none { it.hcpId == id } }
                            val scheduleExcludedCount = lastOptimizationOutcome?.availabilityOrScheduleExcluded
                                ?: (activeHcpItems.size - activePlan.stops.size - weeklyExcludedCount).coerceAtLeast(0)
                            val eligibleConsideredCount = lastOptimizationOutcome?.eligibleHcpsConsidered
                                ?: activePlan.stops.size
                            val targetAchieved = activePlan.stops.size >= targetVisits

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("plan_generation_transparency_card")
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Plan Generation Summary",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        StatusBadgePill(
                                            text = if (targetAchieved) "Target Achieved (${activePlan.stops.size}/$targetVisits)"
                                            else "Partial Target (${activePlan.stops.size}/$targetVisits)",
                                            backgroundColor = if (targetAchieved) StatusEmeraldSoft else StatusAmberSoft,
                                            contentColor = if (targetAchieved) StatusEmerald else StatusAmber
                                        )
                                    }
                                    Text(
                                        text = "Eligible considered: $eligibleConsideredCount • Weekly excluded: $weeklyExcludedCount • Schedule/availability excluded: $scheduleExcludedCount",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Depart at ${AvailabilityScheduleParser.formatMinuteOfDay(activePlan.plan.startMinuteOfDay)}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                itemsIndexed(
                    items = activePlan.stops,
                    key = { _, stop -> stop.id.takeIf { it != 0L } ?: stop.stopOrder.toLong() }
                ) { index, stop ->
                    val hcpClinics = hcpsById[stop.hcpId]?.clinics?.map { it.clinic }.orEmpty()
                    val selectionReason = lastOptimizationOutcome?.stopSelectionReasons?.get(stop.hcpId)
                        ?: buildDefaultStopReason(stop)
                    PlannedStopTimelineCard(
                        stop = stop,
                        isFirst = index == 0,
                        isLast = index == activePlan.stops.lastIndex,
                        availableClinicsForHcp = hcpClinics,
                        selectionReason = selectionReason,
                        onMoveUp = { onMoveStopOrder(activePlan, stop, -1) },
                        onMoveDown = { onMoveStopOrder(activePlan, stop, 1) },
                        onToggleLock = { onToggleLock(stop) },
                        onComplete = { onMarkStopStatus(activePlan, stop, "COMPLETED") },
                        onCancel = { onMarkStopStatus(activePlan, stop, "CANCELLED") },
                        onNotCompleted = { onMarkStopStatus(activePlan, stop, "NOT_COMPLETED") },
                        onUpdateDuration = { newMin -> onUpdateStopDuration(activePlan, stop, newMin) },
                        onSwitchClinic = { newClinic -> onSwitchStopClinic(activePlan, stop, newClinic) }
                    )
                }

                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Finish Shift by ${AvailabilityScheduleParser.formatMinuteOfDay(activePlan.plan.endMinuteOfDay)}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            } else {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No daily route generated for $selectedDateIso yet.",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "We'll match doctor visiting windows, minimize travel, and stay within your work timing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = onOptimizeDay,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.testTag("planner_empty_generate_btn")
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("✨ Generate Route with AI")
                            }
                        }
                    }
                }
            }
        } else {
            item {
                WeeklyHorizonSection(
                    weeklyResult = weeklyPlanResult,
                    targetHcps = settings.dailyHcpTarget,
                    onSelectDay = { dateIso ->
                        onSelectDate(dateIso)
                        horizonTab = 0
                    },
                    onRegenerateWeek = onOptimizeWeek
                )
            }
        }

        item { Spacer(modifier = Modifier.height(28.dp)) }
    }
}

@Composable
private fun AlternativePlanComparisonTable(
    alternatives: List<DailyPlanWithStops>,
    activePlanId: Long?,
    targetHcps: Int,
    onSelectPlan: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Option",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1.8f)
            )
            Text(
                text = "Visits",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(0.8f)
            )
            Text(
                text = "Drive",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(0.8f)
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        alternatives.forEach { alt ->
            val isSelected = alt.plan.id == activePlanId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent
                    )
                    .clickable { onSelectPlan(alt.plan.id) }
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = alt.plan.variantLabel,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1.8f)
                )
                Text(
                    text = "${alt.plan.uniqueHcpsCount}/$targetHcps",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(0.8f)
                )
                Text(
                    text = "${alt.plan.totalDrivingMinutes}m",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(0.8f)
                )
            }
        }
    }
}

private fun buildDefaultStopReason(stop: PlannedStopEntity): String {
    val reasons = mutableListOf<String>()
    if (stop.remainingQuarterlyVisits >= 4) {
        reasons.add("higher remaining visit requirements (${stop.remainingQuarterlyVisits} left)")
    }
    if (stop.priorityScore >= 75) {
        reasons.add("high specialty priority (${stop.specialty})")
    }
    if (stop.distanceKmFromPrev <= 3.0) {
        reasons.add("an efficient route (${"%.1f".format(stop.distanceKmFromPrev)} km leg)")
    }
    return if (reasons.isNotEmpty()) {
        "Prioritized because of ${reasons.joinToString(" and ")}."
    } else {
        "Selected for verified clinic window and balanced territory routing."
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlannedStopTimelineCard(
    stop: PlannedStopEntity,
    isFirst: Boolean,
    isLast: Boolean,
    availableClinicsForHcp: List<ClinicEntity> = emptyList(),
    selectionReason: String = "",
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleLock: () -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onNotCompleted: () -> Unit,
    onUpdateDuration: (Int) -> Unit = {},
    onSwitchClinic: (ClinicEntity) -> Unit = {}
) {
    val context = LocalContext.current
    val hasConflict = stop.conflictReason.isNotBlank()
    var showDurationControls by remember { mutableStateOf(false) }
    val isSameBuildingSequential = !isFirst &&
        (stop.trafficSource == "SAME_LOCATION_SEQUENTIAL" || stop.distanceKmFromPrev < 0.05)

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("planned_stop_card_${stop.stopOrder}"),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (hasConflict) StatusCrimsonSoft.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Travel leg pill (shows Same-Location Sequential 0m transition when applicable)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (isSameBuildingSequential) StatusEmeraldSoft
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isSameBuildingSequential) Icons.Default.Storefront else Icons.Default.DirectionsCar,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isSameBuildingSequential) StatusEmerald else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSameBuildingSequential) {
                            "Same Building • Sequential (${stop.travelMinutesFromPrev}m transition)"
                        } else {
                            "Depart ${AvailabilityScheduleParser.formatMinuteOfDay(stop.departureFromPrevMinute)} • Drive ${stop.travelMinutesFromPrev}m (${"%.1f".format(stop.distanceKmFromPrev)} km)"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSameBuildingSequential) StatusEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSameBuildingSequential) FontWeight.Bold else FontWeight.Medium
                    )
                }
                if (stop.waitingMinutes > 0) {
                    Surface(
                        color = StatusAmber.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Wait ${stop.waitingMinutes}m",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = StatusAmber,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Stop header: Order badge + Doctor Name + Specialty/Brick (No "PLANNED" pill)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
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
                        Text(
                            text = "${stop.stopOrder}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stop.hcpName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "${stop.specialty} • ${formatClinicZone(stop.clinicLabel, stop.zone)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (stop.executionStatus != "PLANNED") {
                    Spacer(modifier = Modifier.width(8.dp))
                    val (statusBg, statusFg) = when (stop.executionStatus) {
                        "COMPLETED" -> StatusEmeraldSoft to StatusEmerald
                        "CANCELLED", "NOT_COMPLETED" -> StatusCrimsonSoft to StatusCrimson
                        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                    }
                    StatusBadgePill(
                        text = stop.executionStatus,
                        backgroundColor = statusBg,
                        contentColor = statusFg
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Visit & Clinic Window Box
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Visit: ${AvailabilityScheduleParser.formatMinuteOfDay(stop.visitStartMinute)} – ${AvailabilityScheduleParser.formatMinuteOfDay(stop.visitEndMinute)}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { showDurationControls = !showDurationControls }
                                .testTag("edit_duration_pill_${stop.stopOrder}")
                        ) {
                            Text(
                                text = "${stop.visitDurationMinutes}m ✎",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    if (showDurationControls) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Duration:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            listOf(10, 15, 20, 30).forEach { dur ->
                                FilterChip(
                                    selected = stop.visitDurationMinutes == dur,
                                    onClick = {
                                        onUpdateDuration(dur)
                                        showDurationControls = false
                                    },
                                    label = { Text("${dur}m") }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Storefront,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Clinic Window: ${AvailabilityScheduleParser.formatMinuteOfDay(stop.windowStartMinute)} – ${AvailabilityScheduleParser.formatMinuteOfDay(stop.windowEndMinute)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (stop.remainingQuarterlyVisits > 0) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = StatusIndigoSoft
                            ) {
                                Text(
                                    text = "${stop.remainingQuarterlyVisits} visits left",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusIndigo,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    // Multi-clinic switcher when HCP has > 1 clinic
                    if (availableClinicsForHcp.size > 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Switch Clinic:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            availableClinicsForHcp.forEach { clin ->
                                val isCurrentClinic = clin.id == stop.clinicId
                                FilterChip(
                                    selected = isCurrentClinic,
                                    onClick = {
                                        if (!isCurrentClinic) onSwitchClinic(clin)
                                    },
                                    label = {
                                        Text(
                                            text = "C${clin.clinicIndex}: ${clin.zone}",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                )
                            }
                        }
                    }
                    if (selectionReason.isNotBlank()) {
                        Text(
                            text = selectionReason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            if (hasConflict) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = StatusCrimsonSoft,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Conflict: ${stop.conflictReason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusCrimson,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Clear, high-contrast 2-row action bar on HCP stop cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (!isFirst) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(
                            1.dp,
                            if (!isFirst) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        IconButton(
                            onClick = onMoveUp,
                            enabled = !isFirst,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "Move Stop Up",
                                tint = if (!isFirst) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (!isLast) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(
                            1.dp,
                            if (!isLast) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        IconButton(
                            onClick = onMoveDown,
                            enabled = !isLast,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "Move Stop Down",
                                tint = if (!isLast) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (stop.isLocked) StatusAmberSoft else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        border = BorderStroke(
                            1.dp,
                            if (stop.isLocked) StatusAmber else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        IconButton(
                            onClick = onToggleLock,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = if (stop.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = if (stop.isLocked) "Unlock Stop" else "Lock Stop",
                                tint = if (stop.isLocked) StatusAmber else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                if (stop.executionStatus != "COMPLETED") {
                    Button(
                        onClick = onComplete,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StatusEmerald,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .testTag("complete_stop_btn_${stop.stopOrder}")
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Complete", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (stop.executionStatus == "PLANNED") {
                    OutlinedButton(
                        onClick = onCancel,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = StatusCrimsonSoft.copy(alpha = 0.75f),
                            contentColor = StatusCrimson
                        ),
                        border = BorderStroke(1.5.dp, StatusCrimson.copy(alpha = 0.65f)),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .testTag("cancel_stop_btn_${stop.stopOrder}")
                    ) {
                        Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cancel", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = onNotCompleted,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = StatusAmberSoft.copy(alpha = 0.75f),
                            contentColor = StatusAmber
                        ),
                        border = BorderStroke(1.5.dp, StatusAmber.copy(alpha = 0.65f)),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                    ) {
                        Icon(Icons.Default.EventBusy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Missed", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
                OutlinedButton(
                    onClick = {
                        val uriStr = if (stop.latitude != null && stop.longitude != null) {
                            "geo:${stop.latitude},${stop.longitude}?q=${stop.latitude},${stop.longitude}(${Uri.encode(stop.hcpName)})"
                        } else {
                            "geo:0,0?q=${Uri.encode(stop.zone)}"
                        }
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)))
                        } catch (_: Exception) {
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Maps", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun WeeklyHorizonSection(
    weeklyResult: WeeklyPlanResult?,
    targetHcps: Int,
    onSelectDay: (String) -> Unit,
    onRegenerateWeek: () -> Unit
) {
    if (weeklyResult == null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Generate a multi-day territory schedule balancing sparse clinic days and dense clinic days.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRegenerateWeek,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Generate Weekly Schedule")
                }
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Text(
                    text = "Weekly Territory Horizon (from ${weeklyResult.weekStartIso})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${weeklyResult.totalPlannedVisits} Visits • ${weeklyResult.uniqueHcpsCovered} Unique HCPs • ${weeklyResult.totalDrivingMinutes}m Total Drive",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        weeklyResult.dayPlans.forEach { daySummary ->
            val best = daySummary.selectedPlan

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectDay(daySummary.dateIso) },
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${daySummary.dayOfWeekName} (${daySummary.dateIso})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        StatusBadgePill(
                            text = "${best.plan.uniqueHcpsCount}/$targetHcps visits • ${best.plan.totalDutySpanMinutes}m",
                            backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    best.stops.forEach { stop ->
                        Text(
                            text = "#${stop.stopOrder} ${AvailabilityScheduleParser.formatMinuteOfDay(stop.visitStartMinute)} • ${stop.hcpName} (${stop.clinicLabel}, ${stop.zone})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                    if (best.plan.infeasibilityReasonsText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = best.plan.infeasibilityReasonsText,
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusAmber
                        )
                    }
                }
            }
        }
    }
}

fun formatClinicZone(clinicLabel: String, zone: String): String {
    var cleaned = clinicLabel.trim()
    if (cleaned.length > 20) {
        val half = cleaned.length / 2
        for (len in 8..half) {
            val sub = cleaned.substring(0, len)
            val rest = cleaned.substring(len).trim()
            if (rest.startsWith(sub)) {
                cleaned = sub + " " + rest.removePrefix(sub).trim()
                break
            }
        }
    }
    cleaned = cleaned.replace(Regex("""-\s*\)"""), ")")
    cleaned = cleaned.replace(Regex("""\(\s*-"""), "(")
    val cleanZone = zone.trim().replace(Regex("""[()]+"""), "").trim()
    if (cleanZone.isNotBlank() && !cleaned.contains(cleanZone, ignoreCase = true)) {
        return "$cleaned • $cleanZone"
    }
    return cleaned
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanningControlsCard(
    settings: WorkSettings,
    targetVisits: Int,
    selectedShift: ShiftPreset,
    shiftStartMinute: Int,
    shiftEndMinute: Int,
    selectedBricks: Set<String>,
    availableBricks: List<String>,
    hasActivePlan: Boolean = false,
    onSetTargetVisits: (Int) -> Unit,
    onSelectShift: (ShiftPreset) -> Unit,
    onSetCustomWorkTiming: (String, String) -> Unit,
    onSetCustomWorkMinutes: (Int, Int) -> Unit,
    onToggleBrick: (String) -> Unit,
    onSelectAllBricks: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(!hasActivePlan) }
    val formattedStart = remember(shiftStartMinute) {
        AvailabilityScheduleParser.formatMinuteOfDay(shiftStartMinute)
    }
    val formattedEnd = remember(shiftEndMinute) {
        AvailabilityScheduleParser.formatMinuteOfDay(shiftEndMinute)
    }
    var fromText by remember(shiftStartMinute) { mutableStateOf(formattedStart) }
    var toText by remember(shiftEndMinute) { mutableStateOf(formattedEnd) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("planner_controls_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with Collapse/Expand toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
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
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Work Timing & Bricks",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val brickLabel = if (selectedBricks.isEmpty()) "All Bricks" else "${selectedBricks.size} Brick(s)"
                        Text(
                            text = "$formattedStart – $formattedEnd • $targetVisits Visits • $brickLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier.clickable { isExpanded = !isExpanded }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isExpanded) "Hide" else "Edit",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse Planning Parameters" else "Expand Planning Parameters",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            if (isExpanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Quick policy status pills (Return Home, Same-Location Sequential, Default Visit Duration)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusBadgePill(
                        text = "Default Visit: ${settings.defaultVisitDurationMinutes}m",
                        backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                    StatusBadgePill(
                        text = "Same-Location: Sequential (${settings.sameLocationTransitionMinutes}m)",
                        backgroundColor = StatusEmeraldSoft,
                        contentColor = StatusEmerald
                    )
                    StatusBadgePill(
                        text = if (settings.returnHomeRequired) "Start/Return: Home" else "Start: Home (No Return)",
                        backgroundColor = StatusIndigoSoft,
                        contentColor = StatusIndigo
                    )
                }

                // 1. Timing of Work (From .. To ..)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "1. Timing of Work (From .. To ..)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "$formattedStart – $formattedEnd",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Custom From .. To .. Text Inputs + Apply button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = fromText,
                            onValueChange = { fromText = it },
                            label = { Text("From") },
                            placeholder = { Text("1:00 PM") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("work_timing_from_input")
                        )

                        OutlinedTextField(
                            value = toText,
                            onValueChange = { toText = it },
                            label = { Text("To") },
                            placeholder = { Text("9:00 PM") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("work_timing_to_input")
                        )

                        Button(
                            onClick = { onSetCustomWorkTiming(fromText, toText) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("apply_work_timing_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Apply Work Timing",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Set", fontWeight = FontWeight.Bold)
                        }
                    }

                    // Quick Preset Chips
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ShiftPreset.values().filter { it != ShiftPreset.CUSTOM }.forEach { preset ->
                            val isSelected = selectedShift == preset &&
                                shiftStartMinute == preset.startMinute &&
                                shiftEndMinute == preset.endMinute
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectShift(preset) },
                                label = {
                                    Text(
                                        text = preset.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                leadingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("shift_chip_${preset.name.lowercase()}")
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // 2. Visits Per Day
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "2. Number of Visits",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.weight(1f)
                        )

                        // Stepper (- / Visits / +)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.size(36.dp)
                            ) {
                                IconButton(
                                    onClick = { onSetTargetVisits((targetVisits - 1).coerceAtLeast(1)) },
                                    enabled = targetVisits > 1,
                                    modifier = Modifier.testTag("decrease_target_visits_btn")
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = "Decrease Visits", modifier = Modifier.size(16.dp))
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "$targetVisits Visits",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }

                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            ) {
                                IconButton(
                                    onClick = { onSetTargetVisits((targetVisits + 1).coerceAtMost(20)) },
                                    enabled = targetVisits < 20,
                                    modifier = Modifier.testTag("increase_target_visits_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Increase Visits (+)",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Quick selector chips + (+) button beside number of visits
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(4, 6, 8, 10, 12).forEach { count ->
                            val isSelected = targetVisits == count
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSetTargetVisits(count) },
                                label = {
                                    Text(
                                        text = "$count",
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .height(32.dp)
                                .clickable(enabled = targetVisits < 20) {
                                    onSetTargetVisits((targetVisits + 1).coerceAtMost(20))
                                }
                                .testTag("quick_add_visit_plus_btn")
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "+",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // 3. Bricks to Visit (Multi-Choice Droplist)
                var bricksDropdownExpanded by remember { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "3. Choose Bricks / Zones",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (selectedBricks.isEmpty()) "All Bricks Active" else "${selectedBricks.size} Selected",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { bricksDropdownExpanded = true }
                                .testTag("planner_bricks_dropdown_btn")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val selectedText = if (selectedBricks.isEmpty()) {
                                        "All Bricks (${availableBricks.size})"
                                    } else {
                                        selectedBricks.joinToString(" • ")
                                    }
                                    Text(
                                        text = selectedText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Icon(
                                    imageVector = if (bricksDropdownExpanded) Icons.Default.ExpandLess else Icons.Default.ArrowDropDown,
                                    contentDescription = "Open Bricks Multi-Choice Droplist",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = bricksDropdownExpanded,
                            onDismissRequest = { bricksDropdownExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.86f)
                        ) {
                            val allSelected = selectedBricks.isEmpty()
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = allSelected,
                                            onCheckedChange = null
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "All Bricks (${availableBricks.size})",
                                            fontWeight = if (allSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                },
                                onClick = {
                                    onSelectAllBricks()
                                }
                            )
                            HorizontalDivider()
                            availableBricks.forEach { brick ->
                                val isBrickSelected = selectedBricks.contains(brick)
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = isBrickSelected,
                                                onCheckedChange = null
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = brick,
                                                fontWeight = if (isBrickSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    },
                                    onClick = {
                                        onToggleBrick(brick)
                                    }
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
                                onClick = { bricksDropdownExpanded = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiRouteStrategyDialog(
    briefing: String?,
    isLoading: Boolean,
    hasPlan: Boolean,
    onRefresh: () -> Unit,
    onGeneratePlan: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "AI Route Strategy & Plan",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
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
                if (isLoading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Analyzing doctor call objectives & territory routing...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (!briefing.isNullOrBlank()) {
                    PlannerMarkdownText(
                        text = briefing,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Text(
                        text = if (hasPlan) {
                            "Tap 'Refresh Strategy' below to generate tactical route insights and call objectives."
                        } else {
                            "No active route yet. Tap 'Generate Route Plan' below to build your route and strategy."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onRefresh, enabled = !isLoading) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Refresh Strategy")
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onGeneratePlan) {
                    Text("Generate Route")
                }
                Button(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}

@Composable
private fun AddHcpFromCurrentListDialog(
    hcps: List<HcpWithDetails>,
    alreadyPlannedHcpIds: Set<Long>,
    weeklyBlockedByHcpId: Map<Long, WeeklyHcpBlockInfo> = emptyMap(),
    preferredSpecialtiesRanked: List<String> = emptyList(),
    availableBricks: List<String>,
    onDismiss: () -> Unit,
    onAddHcpToPlan: (HcpWithDetails, ClinicEntity?) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedBrickFilter by remember { mutableStateOf<String?>(null) }
    var weeklyWarningMessage by remember { mutableStateOf<String?>(null) }
    var nonPreferredWarningMessage by remember { mutableStateOf<String?>(null) }
    val selectedClinicByHcpId = remember { mutableStateMapOf<Long, ClinicEntity>() }
    val addedInSessionIds = remember { mutableStateListOf<Long>() }

    val filteredList = remember(hcps, searchQuery, selectedBrickFilter) {
        hcps.filter { item ->
            val matchesSearch = searchQuery.isBlank() ||
                item.hcp.name.contains(searchQuery, ignoreCase = true) ||
                item.hcp.specialty.contains(searchQuery, ignoreCase = true) ||
                item.hcp.targetId.contains(searchQuery, ignoreCase = true) ||
                item.clinics.any { it.clinic.zone.contains(searchQuery, ignoreCase = true) }

            val matchesBrick = selectedBrickFilter == null ||
                item.clinics.any {
                    com.example.data.model.BrickNormalizer.normalizeBrickKey(it.clinic.zone) ==
                        com.example.data.model.BrickNormalizer.normalizeBrickKey(selectedBrickFilter!!)
                }

            matchesSearch && matchesBrick
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column {
                Text(
                    text = "Add HCP from Current List",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Select doctors from your active directory (${hcps.size} HCPs) to add to today's route",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (weeklyWarningMessage != null) {
                    Surface(
                        color = StatusCrimsonSoft,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, StatusCrimson.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("weekly_restriction_dialog_warning")
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = weeklyWarningMessage!!,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = StatusCrimson,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { weeklyWarningMessage = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = StatusCrimson,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                if (nonPreferredWarningMessage != null) {
                    Surface(
                        color = StatusAmberSoft,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, StatusAmber),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("non_preferred_specialty_dialog_warning")
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = nonPreferredWarningMessage!!,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = StatusAmber,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { nonPreferredWarningMessage = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = StatusAmber,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search Doctor, Specialty, Brick...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("planner_add_hcp_search_input")
                )

                if (availableBricks.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedBrickFilter == null,
                            onClick = { selectedBrickFilter = null },
                            label = { Text("All (${hcps.size})", style = MaterialTheme.typography.labelSmall) }
                        )
                        availableBricks.forEach { brick ->
                            FilterChip(
                                selected = selectedBrickFilter == brick,
                                onClick = {
                                    selectedBrickFilter = if (selectedBrickFilter == brick) null else brick
                                },
                                label = { Text(brick, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredList, key = { it.hcp.id }) { item ->
                        val isAlreadyInRoute = item.hcp.id in alreadyPlannedHcpIds || item.hcp.id in addedInSessionIds
                        val weeklyBlock = weeklyBlockedByHcpId[item.hcp.id]
                        val isWeeklyBlocked = weeklyBlock != null && !isAlreadyInRoute
                        val chosenClinic = selectedClinicByHcpId[item.hcp.id]
                            ?: selectedBrickFilter?.let { filterBrick ->
                                item.clinics.firstOrNull {
                                    com.example.data.model.BrickNormalizer.normalizeBrickKey(it.clinic.zone) ==
                                        com.example.data.model.BrickNormalizer.normalizeBrickKey(filterBrick)
                                }?.clinic
                            }
                            ?: item.clinics.firstOrNull()?.clinic

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = when {
                                isAlreadyInRoute -> StatusEmeraldSoft.copy(alpha = 0.5f)
                                isWeeklyBlocked -> StatusAmberSoft.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            },
                            border = BorderStroke(
                                1.dp,
                                when {
                                    isAlreadyInRoute -> StatusEmerald.copy(alpha = 0.5f)
                                    isWeeklyBlocked -> StatusAmber.copy(alpha = 0.5f)
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.hcp.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        val zoneText = chosenClinic?.zone?.takeIf { it.isNotBlank() }
                                            ?: item.hcp.territoryZone
                                        Text(
                                            text = "${item.hcp.specialty} • $zoneText • Rem: ${item.coverage.remainingVisits} visits",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (weeklyBlock != null && !isAlreadyInRoute) {
                                            Text(
                                                text = "Already ${weeklyBlock.blockingStatus.lowercase()} this week (${weeklyBlock.blockingDateIso})",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = StatusAmber
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    when {
                                        isAlreadyInRoute -> {
                                            Surface(
                                                color = StatusEmeraldSoft,
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = StatusEmerald,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "In Route",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = StatusEmerald
                                                    )
                                                }
                                            }
                                        }
                                        isWeeklyBlocked -> {
                                            OutlinedButton(
                                                onClick = {
                                                    weeklyWarningMessage = WEEKLY_HCP_RESTRICTION_MESSAGE
                                                    onAddHcpToPlan(item, chosenClinic)
                                                },
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusAmber),
                                                border = BorderStroke(1.dp, StatusAmber),
                                                modifier = Modifier.testTag("planner_weekly_blocked_hcp_item_${item.hcp.targetId}")
                                            ) {
                                                Text("This Week", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        else -> {
                                            FilledTonalButton(
                                                onClick = {
                                                    weeklyWarningMessage = null
                                                    nonPreferredWarningMessage = null
                                                    addedInSessionIds.add(item.hcp.id)
                                                    onAddHcpToPlan(item, chosenClinic)
                                                },
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.testTag("planner_add_hcp_item_${item.hcp.targetId}")
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Add", fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }

                                if (item.clinics.size > 1) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Clinic:",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        item.clinics.forEach { cw ->
                                            val isSelectedClinic = chosenClinic?.id == cw.clinic.id
                                            FilterChip(
                                                selected = isSelectedClinic,
                                                onClick = { selectedClinicByHcpId[item.hcp.id] = cw.clinic },
                                                label = {
                                                    Text(
                                                        text = "C${cw.clinic.clinicIndex}: ${cw.clinic.zone}",
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun PlannerMarkdownText(
    text: String,
    color: Color
) {
    val annotated = remember(text) {
        buildAnnotatedString {
            val lines = text.split("\n")
            lines.forEachIndexed { lIdx, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("### ")) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(trimmed.removePrefix("### "))
                    }
                } else {
                    val parts = line.split("**")
                    parts.forEachIndexed { index, part ->
                        if (index % 2 == 1) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(part)
                            }
                        } else {
                            val italicParts = part.split("*")
                            italicParts.forEachIndexed { iIdx, iPart ->
                                if (iIdx % 2 == 1) {
                                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                        append(iPart)
                                    }
                                } else {
                                    append(iPart)
                                }
                            }
                        }
                    }
                }
                if (lIdx < lines.size - 1) {
                    append("\n")
                }
            }
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        color = color
    )
}
