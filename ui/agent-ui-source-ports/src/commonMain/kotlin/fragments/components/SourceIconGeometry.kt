package fragments.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

@Immutable
sealed interface IconGeometry {
    data class Path(val d: String, val filled: Boolean = false, val opacity: Float = 1f, val tint: Color? = null) : IconGeometry
    data class Circle(val cx: Float, val cy: Float, val radius: Float, val filled: Boolean = false, val opacity: Float = 1f, val tint: Color? = null) : IconGeometry
    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float, val radius: Float = 0f, val filled: Boolean = false, val opacity: Float = 1f, val tint: Color? = null) : IconGeometry
}

/** Renders the original registry SVG path data in Compose's Canvas coordinate system. */
@Composable
fun SourceIcon(
    geometry: List<IconGeometry>,
    viewBox: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    strokeWidth: Float = 1.75f,
) {
    val parsed = remember(geometry) {
        geometry.map { shape -> if (shape is IconGeometry.Path) PathParser().parsePathString(shape.d).toPath() else null }
    }
    Canvas(modifier) {
        scale(size.width / viewBox, size.height / viewBox, Offset.Zero) {
            geometry.forEachIndexed { index, shape ->
                when (shape) {
                    is IconGeometry.Path -> drawPath(parsed[index]!!, (shape.tint ?: color).copy(alpha = shape.opacity), style = if (shape.filled) androidx.compose.ui.graphics.drawscope.Fill else Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    is IconGeometry.Circle -> drawCircle((shape.tint ?: color).copy(alpha = shape.opacity), shape.radius, Offset(shape.cx, shape.cy), style = if (shape.filled) androidx.compose.ui.graphics.drawscope.Fill else Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    is IconGeometry.Rect -> drawRoundRect((shape.tint ?: color).copy(alpha = shape.opacity), Offset(shape.x, shape.y), Size(shape.width, shape.height), CornerRadius(shape.radius), style = if (shape.filled) androidx.compose.ui.graphics.drawscope.Fill else Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}
