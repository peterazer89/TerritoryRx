package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ChatMessageEntity
import com.example.domain.ai.ChatUiState
import com.example.domain.ai.ChatbotRolePreset
import com.example.domain.ai.GeminiChatModelTier
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusAmberSoft
import com.example.ui.theme.StatusCrimson
import com.example.ui.theme.StatusCrimsonSoft
import com.example.ui.theme.StatusEmerald
import com.example.ui.theme.StatusEmeraldSoft

@Composable
fun AiChatScreen(
    messages: List<ChatMessageEntity>,
    chatUiState: ChatUiState,
    selectedModelTier: GeminiChatModelTier,
    autoRouteByComplexity: Boolean,
    selectedRole: ChatbotRolePreset,
    onSelectModelTier: (GeminiChatModelTier) -> Unit,
    onToggleAutoRoute: (Boolean) -> Unit,
    onSelectRole: (ChatbotRolePreset) -> Unit,
    onSendMessage: (String, GeminiChatModelTier?) -> Unit,
    onClearHistory: () -> Unit,
    onDismissError: () -> Unit,
    onApplyAiPlan: () -> Unit = {}
) {
    var messageInput by remember { mutableStateOf("") }
    var lastUserPrompt by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val isGenerating = chatUiState is ChatUiState.Loading || chatUiState is ChatUiState.Streaming

    LaunchedEffect(messages.size, chatUiState) {
        val totalItems = messages.size + if (chatUiState is ChatUiState.Streaming || chatUiState is ChatUiState.Loading) 1 else 0
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // Clean, Friendly Header & Quick Strategy Prompts
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
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
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Gemini Territory Assistant",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "AI Route Planner & Field Strategist",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(
                        onClick = onClearHistory,
                        modifier = Modifier.testTag("ai_chat_clear_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear Chat History",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Easy Quick Actions for Field Reps
                Text(
                    text = "Quick Actions:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = {
                            onSendMessage("Generate an optimal visit plan for today with minimal travel time and high target coverage.", null)
                        },
                        label = { Text("🌟 Plan Today's Route") },
                        modifier = Modifier.testTag("ai_quick_plan_today")
                    )
                    AssistChip(
                        onClick = {
                            onSendMessage("Plan my 5-day weekly route coverage across West territory zones.", null)
                        },
                        label = { Text("📅 Plan 5-Day Week") },
                        modifier = Modifier.testTag("ai_quick_plan_week")
                    )
                    AssistChip(
                        onClick = {
                            onSendMessage("Analyze my highest-risk and overdue doctors in cardiology and internal medicine.", null)
                        },
                        label = { Text("⚠️ At-Risk Doctors") },
                        modifier = Modifier.testTag("ai_quick_at_risk")
                    )
                    AssistChip(
                        onClick = {
                            onSendMessage("Prepare clinical talking points and pre-call briefing for my upcoming visits.", null)
                        },
                        label = { Text("🩺 Doctor Call Prep") },
                        modifier = Modifier.testTag("ai_quick_call_prep")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Scrollable Multi-Turn Conversation Thread
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("ai_chat_message_list"),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatBubbleItem(message = msg, onApplyAiPlan = onApplyAiPlan)
            }

            if (chatUiState is ChatUiState.Loading) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.padding(end = 48.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Thinking with ${chatUiState.modelId}...",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            if (chatUiState is ChatUiState.Streaming) {
                item {
                    ChatBubbleItem(
                        message = ChatMessageEntity(
                            id = -1L,
                            role = "model",
                            content = chatUiState.partialText,
                            modelId = chatUiState.modelId,
                            taskTierLabel = "${chatUiState.tierLabel} • Streaming..."
                        ),
                        onApplyAiPlan = onApplyAiPlan
                    )
                }
            }

            if (chatUiState is ChatUiState.Error) {
                item {
                    Surface(
                        color = StatusCrimsonSoft,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, StatusCrimson.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_chat_error_card")
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Gemini Request Error (${chatUiState.attemptedModelId})",
                                style = MaterialTheme.typography.labelLarge,
                                color = StatusCrimson,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = chatUiState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (lastUserPrompt.isNotBlank()) {
                                    Button(
                                        onClick = {
                                            onDismissError()
                                            onSendMessage(lastUserPrompt, GeminiChatModelTier.GENERAL)
                                        },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Retry with gemini-3.8-flash")
                                    }
                                }
                                OutlinedButton(
                                    onClick = onDismissError,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Quick Prompt Suggestions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val quickPrompts = listOf(
                "Complex: Analyze my highest-risk cardiology HCPs and propose a 2-day coverage strategy" to GeminiChatModelTier.COMPLEX,
                "General: Summarize ambiguous clinic schedules from my uploaded CSV/Excel target list" to GeminiChatModelTier.GENERAL,
                "Fast: Quick clinic hours for Dr. Ahmed El-Masry?" to GeminiChatModelTier.FAST,
                "Complex: Draft a clinical pre-call briefing for my next scheduled visit" to GeminiChatModelTier.COMPLEX
            )
            quickPrompts.forEach { (promptText, suggestedTier) ->
                AssistChip(
                    onClick = {
                        if (!isGenerating) {
                            lastUserPrompt = promptText
                            onSendMessage(promptText, suggestedTier)
                        }
                    },
                    shape = RoundedCornerShape(50),
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    ),
                    border = null,
                    label = { Text(promptText, style = MaterialTheme.typography.bodySmall) }
                )
            }
        }

        // Message Composer Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = messageInput,
                onValueChange = { messageInput = it },
                placeholder = { Text("Ask ${selectedRole.title}...") },
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_chat_input_field"),
                shape = RoundedCornerShape(18.dp),
                maxLines = 3
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val trimmed = messageInput.trim()
                    if (trimmed.isNotEmpty() && !isGenerating) {
                        lastUserPrompt = trimmed
                        messageInput = ""
                        onSendMessage(trimmed, if (autoRouteByComplexity) null else selectedModelTier)
                    }
                },
                enabled = messageInput.isNotBlank() && !isGenerating,
                modifier = Modifier
                    .height(54.dp)
                    .testTag("ai_chat_send_btn"),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send Message")
            }
        }
    }
}

@Composable
private fun ChatBubbleItem(
    message: ChatMessageEntity,
    onApplyAiPlan: () -> Unit
) {
    val isUser = message.role == "user"
    val isPlanResponse = !isUser && (
        message.content.contains("plan", ignoreCase = true) ||
        message.content.contains("route", ignoreCase = true) ||
        message.content.contains("visit", ignoreCase = true) ||
        message.content.contains("stop", ignoreCase = true)
    )

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surface
            },
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 20.dp
            ),
            border = if (isUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = if (isUser) 0.dp else 1.dp,
            shadowElevation = 1.dp,
            modifier = if (isUser) Modifier.widthIn(max = 320.dp) else Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (isUser) Icons.Default.Person else Icons.Default.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (isUser) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    Text(
                        text = if (isUser) "Medical Rep" else message.taskTierLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    if (!isUser) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(50)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = message.modelId,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                FormattedMessageContent(
                    content = message.content,
                    isUser = isUser
                )

                if (isPlanResponse) {
                    Spacer(modifier = Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = onApplyAiPlan,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_bubble_apply_plan_btn")
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("View & Apply in Planner")
                    }
                }
            }
        }
    }
}

@Composable
private fun FormattedMessageContent(
    content: String,
    isUser: Boolean
) {
    if (isUser) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary
        )
        return
    }

    val (preLines, table, postLines) = remember(content) {
        splitContentAndTable(content)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (preLines.isNotBlank()) {
            MarkdownStyledText(
                text = preLines,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (table != null && table.rows.isNotEmpty()) {
            var showRawTable by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Doctors Analysis (${table.rows.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                AssistChip(
                    onClick = { showRawTable = !showRawTable },
                    label = { Text(if (showRawTable) "Card View" else "Table View", fontSize = 11.sp) },
                    modifier = Modifier.height(28.dp)
                )
            }

            if (!showRawTable) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    table.rows.forEach { rowCells ->
                        DoctorParsedCard(headers = table.headers, cells = rowCells)
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        // Headers
                        Row(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            table.headers.forEach { h ->
                                Text(
                                    text = h,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.widthIn(min = 90.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        // Rows
                        table.rows.forEachIndexed { rIdx, cells ->
                            Row(
                                modifier = Modifier
                                    .padding(vertical = 5.dp, horizontal = 4.dp)
                                    .background(
                                        if (rIdx % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f) else androidx.compose.ui.graphics.Color.Transparent,
                                        RoundedCornerShape(4.dp)
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                cells.forEachIndexed { cIdx, cell ->
                                    val header = table.headers.getOrNull(cIdx).orEmpty()
                                    Text(
                                        text = cleanCellText(cell),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (header.contains("name", ignoreCase = true)) FontWeight.SemiBold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.widthIn(min = 90.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (postLines.isNotBlank()) {
            MarkdownStyledText(
                text = postLines,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun DoctorParsedCard(headers: List<String>, cells: List<String>) {
    fun findCell(vararg keywords: String): String {
        for (kw in keywords) {
            val idx = headers.indexOfFirst { it.contains(kw, ignoreCase = true) }
            if (idx in cells.indices) {
                return cleanCellText(cells[idx])
            }
        }
        return ""
    }

    val doctorName = findCell("name", "hcp", "doctor").ifBlank { cleanCellText(cells.firstOrNull().orEmpty()) }
    val specialty = findCell("spec", "cardio")
    val risk = findCell("risk", "score")
    val progress = findCell("progress", "target")
    val location = findCell("location", "clinic", "zone")
    val window = findCell("window", "hour", "time")
    val availability = findCell("monday", "avail", "day")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = doctorName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (risk.isNotBlank()) {
                    val isCritical = risk.contains("crit", ignoreCase = true) || risk.contains("7", ignoreCase = true) || risk.contains("8", ignoreCase = true) || risk.contains("9", ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCritical) StatusCrimsonSoft else StatusAmberSoft
                    ) {
                        Text(
                            text = risk,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isCritical) StatusCrimson else StatusAmber,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (specialty.isNotBlank() || location.isNotBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                val subText = listOfNotNull(specialty.takeIf { it.isNotBlank() }, location.takeIf { it.isNotBlank() }).joinToString(" • ")
                Text(
                    text = subText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (progress.isNotBlank()) {
                    Text(
                        text = "🎯 $progress",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (window.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = "🕒 $window",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (availability.isNotBlank()) {
                Spacer(modifier = Modifier.height(5.dp))
                val isClosed = availability.contains("❌") || availability.contains("closed", ignoreCase = true) || availability.contains("only", ignoreCase = true)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isClosed) StatusCrimsonSoft.copy(alpha = 0.7f) else StatusEmeraldSoft.copy(alpha = 0.7f)
                ) {
                    Text(
                        text = availability,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (isClosed) StatusCrimson else StatusEmerald,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

private fun cleanCellText(text: String): String {
    return text.replace("**", "").replace("*", "").trim()
}

@Composable
private fun MarkdownStyledText(
    text: String,
    color: androidx.compose.ui.graphics.Color
) {
    val annotated = remember(text) {
        buildAnnotatedString {
            val parts = text.split("**")
            parts.forEachIndexed { index, part ->
                if (index % 2 == 1) {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(part.replace("*", ""))
                    }
                } else {
                    val italicParts = part.split("*")
                    italicParts.forEachIndexed { iIdx, iPart ->
                        if (iIdx % 2 == 1) {
                            withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) {
                                append(iPart)
                            }
                        } else {
                            append(iPart)
                        }
                    }
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

private data class ParsedTable(
    val headers: List<String>,
    val rows: List<List<String>>
)

private data class ContentSplit(
    val preLines: String,
    val table: ParsedTable?,
    val postLines: String
)

private fun splitContentAndTable(raw: String): ContentSplit {
    val lines = raw.lines()
    val pre = mutableListOf<String>()
    val tableLines = mutableListOf<String>()
    val post = mutableListOf<String>()

    var inTable = false
    var finishedTable = false

    for (line in lines) {
        val trimmed = line.trim()
        val isTableLine = (trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2)

        if (isTableLine && !finishedTable) {
            inTable = true
            tableLines.add(trimmed)
        } else if (inTable) {
            finishedTable = true
            inTable = false
            post.add(line)
        } else {
            pre.add(line)
        }
    }

    if (tableLines.size < 2) {
        return ContentSplit(raw, null, "")
    }

    val sepIdx = tableLines.indexOfFirst { it.contains("---") || it.contains(":-") || it.contains("-:") }
    if (sepIdx <= 0) {
        return ContentSplit(raw, null, "")
    }

    val headerCells = tableLines[sepIdx - 1].split("|")
        .map { cleanCellText(it) }
        .filter { it.isNotBlank() }

    val dataRows = mutableListOf<List<String>>()
    for (i in (sepIdx + 1)..tableLines.lastIndex) {
        val rawCells = tableLines[i].split("|").map { it.trim() }
        val contentCells = rawCells.filterIndexed { index, _ -> index > 0 && index <= headerCells.size }
        if (contentCells.isNotEmpty()) {
            dataRows.add(contentCells)
        }
    }

    val table = if (headerCells.isNotEmpty() && dataRows.isNotEmpty()) {
        ParsedTable(headerCells, dataRows)
    } else {
        null
    }

    return ContentSplit(
        preLines = pre.joinToString("\n").trim(),
        table = table,
        postLines = post.joinToString("\n").trim()
    )
}
