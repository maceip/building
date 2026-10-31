package fragments.components.agentelements.icons

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fragments.components.IconGeometry
import fragments.components.SourceIcon

private val IconSpinnerGeometry = listOf(
    IconGeometry.Circle(12f, 12f, 10f, opacity = 0.2f),
    IconGeometry.Path("M12 2C6.48 2 2 6.48 2 12"),
)

@Composable
fun IconSpinner(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color(0xFF8C8C8C)) {
    val transition = rememberInfiniteTransition(label = "agent icon spinner")
    val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900)), label = "rotation")
    SourceIcon(IconSpinnerGeometry, 24f, modifier.size(size).rotate(rotation), color, 3f)
}

@Composable
fun CheckIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(listOf(IconGeometry.Path("M5 12.75L10 19L19 5")), 24f, modifier.size(size), color, 2f)

@Composable
fun IconArrowRight(modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color = Color.White) =
    SourceIcon(listOf(IconGeometry.Path("M14 6L20 12L14 18"), IconGeometry.Path("M19 12H4")), 24f, modifier.size(size), color, 2f)

@Composable
fun IconDoubleChevronRight(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(listOf(IconGeometry.Path("M6 17L11 12L6 7M13 17L18 12L13 7")), 24f, modifier.size(size), color, 2f)

@Composable
fun FileExtIcon(filename: String, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val ext = filename.substringAfterLast('.', "").lowercase()
    fileExtensionGeometry(ext)?.let { SourceIcon(it, 32f, modifier.size(size), strokeWidth = 0f) }
}
