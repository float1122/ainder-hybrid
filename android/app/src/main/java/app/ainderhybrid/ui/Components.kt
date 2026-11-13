package app.ainderhybrid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ainderhybrid.hybrid.Source

// Palette: ink + rose for the dating side, teal for "stays on device", amber for "cloud".
object Palette {
    val Ink = Color(0xFF1E1A24)
    val Rose = Color(0xFFD9466B)
    val Paper = Color(0xFFFBF7F4)
    val Device = Color(0xFF0F7B6C)
    val Cloud = Color(0xFFB4690E)
    val NoAi = Color(0xFF6B6570)
}

val LightColors = lightColorScheme(
    primary = Palette.Rose, onPrimary = Color.White,
    secondary = Palette.Device, background = Palette.Paper, surface = Color.White,
    onBackground = Palette.Ink, onSurface = Palette.Ink,
)
val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8FA8), secondary = Color(0xFF5FD3C1),
    background = Color(0xFF141118), surface = Color(0xFF1E1A24),
)

fun Source.color(): Color = when (this) {
    Source.ON_DEVICE -> Palette.Device
    Source.IN_CLOUD -> Palette.Cloud
    Source.NO_AI -> Palette.NoAi
}

/** Small text-only badge on every AI result: "On-device", "Cloud" or "No AI". */
@Composable
fun SourceBadge(source: Source, modifier: Modifier = Modifier) {
    Text(
        text = source.badge,
        color = source.color(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .testTag("badge-${source.name}")
            .border(1.dp, source.color(), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Labelled AI result with its source badge. */
@Composable
fun AiResultRow(label: String, source: Source, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            SourceBadge(source)
        }
        if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Marks private, on-device-only sections. */
@Composable
fun StaysOnPhone(modifier: Modifier = Modifier) {
    Text(
        "Stays on this phone",
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(Palette.Device, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
