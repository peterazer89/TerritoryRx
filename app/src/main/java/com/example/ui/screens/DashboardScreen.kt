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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TableView
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.data.model.DailyPlanWithStops
import com.example.data.model.HcpWithDetails
import com.example.data.model.WorkSettings
import com.example.domain.ai.StructuredPlanningCommand
import com.example.domain.optimizer.RouteOptimizerEngine
import com.example.domain.parser.AvailabilityScheduleParser
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusAmberSoft
import com.example.ui.theme.StatusCrimson
import com.example.ui.theme.StatusCrimsonSoft
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.StatusEmeraldSoft
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    selectedDateIso: String,
    activePlan: DailyPlanWithStops?,
    hcps: List<HcpWithDetails>,
    settings: WorkSettings,
    lastAiInterpretation: StructuredPlanningCommand?,
    onStepDate: (Int) -> Unit,
    onOptimizeDay: () -> Unit,
    onOptimizeWeek: () -> Unit,
    onLoadSampleWestXlsx: () -> Unit,
    onNavigateToPlanner: () -> Unit,
    onNavigateToImport: () -> Unit,
    onNavigateToDirectory: () -> Unit,
    onNavigateToAiChat: () -> Unit = {},
    onExecuteCommand: (String) -> Unit,
    onSyncCloud: () -> Unit
) {
    val context = LocalContext.current
    var commandInput by remember { mutableStateOf("") }

    val dayOfWeek = remember(selectedDateIso) {
        RouteOptimizerEngine.parseIsoDayOfWeek(selectedDateIso)
    }
    val dayLabel = remember(dayOfWeek) {
        AvailabilityScheduleParser.dayName(dayOfWeek)
    }

    val plannedCount = activePlan?.plan?.uniqueHcpsCount ?: 0
    val targetCount = settings.dailyHcpTarget.coerceAtLeast(1)
    val completedCount = activePlan?.stops?.count { it.executionStatus == "COMPLETED" } ?: 0
    val drivingMinutes = activePlan?.plan?.totalDrivingMinutes ?: 0
    val dutySpanMinutes = activePlan?.plan?.totalDutySpanMinutes ?: 0
    val remainingDutyMinutes = (settings.maxDutySpanMinutes - dutySpanMinutes).coerceAtLeast(0)
    val nextStop = activePlan?.stops
        ?.sortedBy { it.stopOrder }
        ?.firstOrNull { it.executionStatus == "PLANNED" }

    val overdueHcps = remember(hcps) {
        hcps.filter { it.coverage.isOverdue || it.coverage.riskScore >= 45.0 }
            .sortedByDescending { it.coverage.riskScore }
    }
    val pendingSyncCount = remember(hcps) {
        hcps.count { it.hcp.syncStatus != "SYNCED" }
    }
    val ambiguousCount = remember(hcps) {
        hcps.sumOf { h -> h.clinics.count { !it.clinic.userConfirmedSchedule } }
    }

    val shiftProgress = (plannedCount.toFloat() / targetCount.toFloat()).coerceIn(0f, 1f)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Spacer(modifier = Modifier.height(2.dp)) }

        // Hero Header Card with Warm Botanical Gradient, Date Stepper & Shift Telemetry
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
                                )
                            )
                        )
                        .padding(22.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Surface(
                                color = Color.White.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = "WEST TERRITORY • FIELD COMPANION",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "$dayLabel, $selectedDateIso",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(
                                    color = Color.White.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(16.dp)
                                )
                        ) {
                            IconButton(
                                onClick = { onStepDate(-1) },
                                modifier = Modifier.testTag("dashboard_prev_day_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = "Previous Day",
                                    tint = Color.White
                                )
                            }
                            IconButton(
                                onClick = { onStepDate(1) },
                                modifier = Modifier.testTag("dashboard_next_day_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "Next Day",
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HeroMetricTile(
                            modifier = Modifier.weight(1f),
                            label = "Daily Visits",
                            value = "$plannedCount / ${settings.dailyHcpTarget}",
                            subtext = "$completedCount completed"
                        )
                        HeroMetricTile(
                            modifier = Modifier.weight(1f),
                            label = "Shift Span",
                            value = "${dutySpanMinutes}m",
                            subtext = "${remainingDutyMinutes}m remaining"
                        )
                        HeroMetricTile(
                            modifier = Modifier.weight(1f),
                            label = "Drive Time",
                            value = "${drivingMinutes}m",
                            subtext = activePlan?.plan?.trafficFreshnessLabel ?: "Typical traffic"
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Friendly Shift Goal Progress Bar
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Daily Route Capacity",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.88f)
                            )
                            Text(
                                text = "${(shiftProgress * 100).roundToInt()}% planned",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { shiftProgress },
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.22f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(7.dp)
                                .clip(RoundedCornerShape(50))
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusBadgePill(
                            text = if (activePlan?.plan?.isValidatorVerified == true) {
                                "✓ Route Verified"
                            } else {
                                "Ready to Optimize"
                            },
                            backgroundColor = Color.White.copy(alpha = 0.20f),
                            contentColor = Color.White
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (pendingSyncCount > 0) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.18f),
                            modifier = Modifier
                                .clickable(enabled = pendingSyncCount > 0) { onSyncCloud() }
                                .testTag("dashboard_sync_pill")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudSync,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (pendingSyncCount == 0) "Cloud Synced" else "$pendingSyncCount Pending Sync • Tap to Sync",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                        if (ambiguousCount > 0) {
                            StatusBadgePill(
                                text = "$ambiguousCount Schedule(s) to Review",
                                backgroundColor = StatusAmberSoft,
                                contentColor = StatusAmber
                            )
                        }
                    }
                }
            }
        }

        if (pendingSyncCount > 0) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSyncCloud() }
                        .testTag("dashboard_sync_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudSync,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "$pendingSyncCount Doctors Ready to Sync",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Tap to back up all territory targets to Cloud Firestore",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onSyncCloud,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("dashboard_sync_now_btn")
                        ) {
                            Text("Sync Now")
                        }
                    }
                }
            }
        }

        // Next Scheduled Appointment Concierge Card
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToPlanner() },
                shape = RoundedCornerShape(22.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Schedule,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Up Next on Your Route",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Tap card to open full timeline & map",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (nextStop != null) {
                            StatusBadgePill(
                                text = "Stop #${nextStop.stopOrder}",
                                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (nextStop == null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "No pending visits scheduled for $selectedDateIso yet. Tap 'Optimize Day' below to build your route.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = nextStop.hcpName,
                                            style = MaterialTheme.typography.titleLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${nextStop.specialty} • ${nextStop.clinicLabel} (${nextStop.zone}) • #${nextStop.hcpTargetId}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            StatusBadgePill(
                                                text = "${AvailabilityScheduleParser.formatMinuteOfDay(nextStop.visitStartMinute)} – ${AvailabilityScheduleParser.formatMinuteOfDay(nextStop.visitEndMinute)}",
                                                backgroundColor = StatusEmeraldSoft,
                                                contentColor = StatusEmerald
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.DirectionsCar,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = "${nextStop.travelMinutesFromPrev}m drive",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Clinic Open: ${AvailabilityScheduleParser.formatMinuteOfDay(nextStop.windowStartMinute)} – ${AvailabilityScheduleParser.formatMinuteOfDay(nextStop.windowEndMinute)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            val uriStr = if (nextStop.latitude != null && nextStop.longitude != null) {
                                                "geo:${nextStop.latitude},${nextStop.longitude}?q=${nextStop.latitude},${nextStop.longitude}(${Uri.encode(nextStop.hcpName)})"
                                            } else {
                                                "geo:0,0?q=${Uri.encode(nextStop.zone)}"
                                            }
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr))
                                            try {
                                                context.startActivity(intent)
                                            } catch (_: Exception) {
                                            }
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.testTag("dashboard_navigate_next_stop_btn")
                                    ) {
                                        Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Navigate")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Friendly Quick Actions Hub
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Quick Field Actions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Build routes, import doctor lists, or consult your AI assistant",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOptimizeDay,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("dashboard_optimize_day_btn")
                        ) {
                            Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Optimize Day")
                        }
                        FilledTonalButton(
                            onClick = {
                                onOptimizeWeek()
                                onNavigateToPlanner()
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("dashboard_optimize_week_btn")
                        ) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Optimize Week")
                        }
                        FilledTonalButton(
                            onClick = onLoadSampleWestXlsx,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("dashboard_load_sample_btn")
                        ) {
                            Icon(Icons.Default.TableView, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Load West.xlsx")
                        }
                        OutlinedButton(
                            onClick = onNavigateToImport,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("dashboard_import_excel_btn")
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import Excel/CSV")
                        }
                        FilledTonalButton(
                            onClick = onNavigateToAiChat,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("dashboard_open_ai_chat_btn")
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Gemini Chat")
                        }
                        OutlinedButton(
                            onClick = onSyncCloud,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("dashboard_sync_cloud_btn")
                        ) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync Cloud")
                        }
                    }
                }
            }
        }

        // AI & Deterministic Natural-Language Command Bar
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
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Smart Route Assistant",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tell the planner what you need in plain English",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = commandInput,
                            onValueChange = { commandInput = it },
                            placeholder = { Text("e.g., Plan 8 cardiology visits avoiding Sheikh Zayed") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dashboard_ai_command_input"),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (commandInput.isNotBlank()) {
                                    onExecuteCommand(commandInput)
                                }
                            },
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("dashboard_ai_command_send_btn"),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Apply Command")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val suggestions = listOf(
                            "Prioritize overdue doctors today",
                            "Avoid Sheikh Zayed today",
                            "Focus on Cardiology",
                            "Why couldn't 8 visits be scheduled?"
                        )
                        suggestions.forEach { chipPrompt ->
                            AssistChip(
                                onClick = {
                                    commandInput = chipPrompt
                                    onExecuteCommand(chipPrompt)
                                },
                                shape = RoundedCornerShape(50),
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                ),
                                border = null,
                                label = { Text(chipPrompt, style = MaterialTheme.typography.bodySmall) }
                            )
                        }
                    }

                    if (lastAiInterpretation != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Applied (${lastAiInterpretation.sourceLabel}): ${lastAiInterpretation.explanationText}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }

        // Quarterly Coverage Risk & Overdue HCP Alerts Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Doctors Needing Attention",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Prioritized by quarterly visit deficit & overdue days",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.clickable { onNavigateToDirectory() }
                ) {
                    Text(
                        text = "View All (${hcps.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        items(overdueHcps.take(6), key = { it.hcp.id }) { item ->
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
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToDirectory() },
                shape = RoundedCornerShape(18.dp),
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
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = initials,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = item.hcp.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "#${item.hcp.targetId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${item.hcp.specialty} • ${item.hcp.territoryZone}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = item.coverage.riskFormulaExplanation,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        val scoreInt = item.coverage.riskScore.roundToInt()
                        val isHighRisk = scoreInt >= 65
                        StatusBadgePill(
                            text = "Risk $scoreInt/100",
                            backgroundColor = if (isHighRisk) StatusCrimsonSoft else StatusAmberSoft,
                            contentColor = if (isHighRisk) StatusCrimson else StatusAmber
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${item.coverage.completedVisitsThisQuarter}/${item.coverage.quarterlyTarget} visits",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun HeroMetricTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    subtext: String
) {
    Column(
        modifier = modifier
            .background(
                color = Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtext,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.80f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StatusBadgePill(
    text: String,
    backgroundColor: Color,
    contentColor: Color
) {
    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.28f))
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

