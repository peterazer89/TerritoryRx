package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.PosterCyanGlow
import com.example.ui.theme.PosterDeepNavy
import com.example.ui.theme.PosterMidnightWave
import com.example.ui.theme.PosterRxBlue

@Composable
fun OpeningPosterScreen(
    isDarkMode: Boolean,
    onToggleDarkMode: () -> Unit,
    onEnterApp: (initialTab: Int) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PosterMidnightWave)
            .clickable { onEnterApp(0) }
            .testTag("opening_poster_screen")
    ) {
        // Full-screen Territory Rx AI Route Planning Poster Illustration
        Image(
            painter = painterResource(id = R.drawable.img_territory_rx_intro),
            contentDescription = "Territory Rx - Smarter planning. More visits. Greater impact. Powered by AI",
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        )

        // Subtle bottom-edge navy gradient to guarantee high contrast on all screen aspect ratios
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xCC04122B),
                            Color(0xF5030E22)
                        )
                    )
                )
        )

        // Top Brand Header matching the Territory Rx Poster
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 18.dp, start = 20.dp, end = 20.dp)
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                TerritoryRxPinLogo(modifier = Modifier.size(54.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                color = Color(0xFF071B42),
                                fontWeight = FontWeight.ExtraBold
                            )
                        ) {
                            append("Territory ")
                        }
                        withStyle(
                            SpanStyle(
                                color = Color(0xFF0A6BFF),
                                fontWeight = FontWeight.ExtraBold
                            )
                        ) {
                            append("Rx")
                        }
                    },
                    fontSize = 34.sp,
                    letterSpacing = (-0.5).sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Smarter planning. More visits.\nGreater impact.",
                color = Color(0xFF0F2952),
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            // "Powered by AI" Pill
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xFFE6F4FF).copy(alpha = 0.94f),
                border = BorderStroke(1.dp, Color(0xFF90CAF9)),
                shadowElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFF0A6BFF),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Powered by AI",
                        color = Color(0xFF071B42),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Bottom Wave Section: "Plan | Visit | Achieve" + Primary Action
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 14.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PosterWaveWordItem(
                    icon = Icons.Outlined.CalendarToday,
                    word = "Plan",
                    onClick = { onEnterApp(0) },
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(34.dp)
                        .background(Color.White.copy(alpha = 0.35f))
                )

                PosterWaveWordItem(
                    icon = Icons.Outlined.LocationOn,
                    word = "Visit",
                    onClick = { onEnterApp(1) },
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(34.dp)
                        .background(Color.White.copy(alpha = 0.35f))
                )

                PosterWaveWordItem(
                    icon = Icons.Outlined.TrackChanges,
                    word = "Achieve",
                    onClick = { onEnterApp(0) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = { onEnterApp(0) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PosterRxBlue,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("opening_enter_app_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = PosterCyanGlow,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Start AI Route Planning",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun TerritoryRxPinLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Outer blue map pin
        val pinPath = Path().apply {
            moveTo(w * 0.5f, h * 0.88f)
            cubicTo(w * 0.22f, h * 0.62f, w * 0.14f, h * 0.44f, w * 0.14f, h * 0.32f)
            cubicTo(w * 0.14f, h * 0.12f, w * 0.30f, h * 0.02f, w * 0.50f, h * 0.02f)
            cubicTo(w * 0.70f, h * 0.02f, w * 0.86f, h * 0.12f, w * 0.86f, h * 0.32f)
            cubicTo(w * 0.86f, h * 0.44f, w * 0.78f, h * 0.62f, w * 0.50f, h * 0.88f)
            close()
        }
        drawPath(
            path = pinPath,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF0096FF), Color(0xFF073B8A))
            )
        )
        // White inner circle
        val center = Offset(w * 0.50f, h * 0.33f)
        drawCircle(
            color = Color.White,
            radius = w * 0.22f,
            center = center
        )
        // Medical plus cross inside pin
        val arm = w * 0.11f
        val strokeW = w * 0.075f
        drawLine(
            color = Color(0xFF0A6BFF),
            start = Offset(center.x - arm, center.y),
            end = Offset(center.x + arm, center.y),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color(0xFF0A6BFF),
            start = Offset(center.x, center.y - arm),
            end = Offset(center.x, center.y + arm),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
        // Dynamic road swoosh at base of pin
        val swoosh = Path().apply {
            moveTo(w * 0.08f, h * 0.94f)
            quadraticTo(w * 0.32f, h * 0.72f, w * 0.82f, h * 0.56f)
        }
        drawPath(
            path = swoosh,
            color = Color.White,
            style = Stroke(width = w * 0.06f, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun PosterWaveWordItem(
    icon: ImageVector,
    word: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = word,
            tint = Color(0xFF38E5FF),
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = word,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false
        )
    }
}
