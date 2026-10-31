// Generated from react-agent-ui/components/signoff-ui/lib/icons.tsx by ui/tools/generate_source_icons.py.
package fragments.components.signoff.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fragments.components.IconGeometry
import fragments.components.SourceIcon

private val CheckIconGeometry = listOf(
        IconGeometry.Path("M5 12.5 9.5 17 19 7.5", false, 1f)
    )

private val XIconGeometry = listOf(
        IconGeometry.Path("M6.5 6.5l11 11M17.5 6.5l-11 11", false, 1f)
    )

private val ChevronIconGeometry = listOf(
        IconGeometry.Path("M9 6l6 6-6 6", false, 1f)
    )

private val BanIconGeometry = listOf(
        IconGeometry.Circle(12f, 12f, 8.5f, false, 1f),
        IconGeometry.Path("M6 6l12 12", false, 1f)
    )

private val AlertIconGeometry = listOf(
        IconGeometry.Path("M12 3.5 21.5 20h-19L12 3.5Z", false, 1f),
        IconGeometry.Path("M12 10v4.5M12 17.25v.25", false, 1f)
    )

private val ShieldIconGeometry = listOf(
        IconGeometry.Path("M12 3 19.5 6v5.5c0 4.6-3.1 8-7.5 9.5-4.4-1.5-7.5-4.9-7.5-9.5V6L12 3Z", false, 1f),
        IconGeometry.Path("M12 8.5v4M12 15.5v.25", false, 1f)
    )

private val SparkIconGeometry = listOf(
        IconGeometry.Path("M12 3.5c.6 4.4 2.6 6.9 7.5 8.5-4.9 1.6-6.9 4.1-7.5 8.5-.6-4.4-2.6-6.9-7.5-8.5 4.9-1.6 6.9-4.1 7.5-8.5Z", false, 1f)
    )

private val CopyIconGeometry = listOf(
        IconGeometry.Rect(8.5f, 8.5f, 12f, 12f, 2.5f, false, 1f),
        IconGeometry.Path("M15.5 5.5v-.5a2 2 0 0 0-2-2H5.5a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h.5", false, 1f)
    )

private val ExternalIconGeometry = listOf(
        IconGeometry.Path("M14 4h6v6M20 4l-9 9", false, 1f),
        IconGeometry.Path("M18 14v4.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 4 18.5v-11A1.5 1.5 0 0 1 5.5 6H10", false, 1f)
    )

private val FileIconGeometry = listOf(
        IconGeometry.Path("M14 3.5H7A1.5 1.5 0 0 0 5.5 5v14A1.5 1.5 0 0 0 7 20.5h10a1.5 1.5 0 0 0 1.5-1.5V8L14 3.5Z", false, 1f),
        IconGeometry.Path("M14 3.5V8h4.5", false, 1f)
    )

private val ImageIconGeometry = listOf(
        IconGeometry.Rect(3f, 3f, 18f, 18f, 2f, false, 1f),
        IconGeometry.Circle(9f, 9f, 2f, false, 1f),
        IconGeometry.Path("m21 15-3.1-3.1a2 2 0 0 0-2.8 0L6 21", false, 1f)
    )

private val ToolIconGeometry = listOf(
        IconGeometry.Path("M13 3 5.5 13.5h6L10.5 21 18.5 10.5h-6L13 3Z", false, 1f)
    )

private val TerminalIconGeometry = listOf(
        IconGeometry.Rect(3f, 4.5f, 18f, 15f, 2.5f, false, 1f),
        IconGeometry.Path("M7.5 9.5 10.5 12l-3 2.5M12.5 15h4", false, 1f)
    )

private val ClockIconGeometry = listOf(
        IconGeometry.Circle(12f, 12f, 8.5f, false, 1f),
        IconGeometry.Path("M12 7.5V12l3 2", false, 1f)
    )

private val ArrowUpIconGeometry = listOf(
        IconGeometry.Path("M12 19V5M6 11l6-6 6 6", false, 1f)
    )

private val ArrowDownIconGeometry = listOf(
        IconGeometry.Path("M12 5v14M6 13l6 6 6-6", false, 1f)
    )

private val CommentIconGeometry = listOf(
        IconGeometry.Path("M5 5h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-8l-4 3.5V16H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1Z", false, 1f)
    )

private val UndoIconGeometry = listOf(
        IconGeometry.Path("M9 14 4 9l5-5", false, 1f),
        IconGeometry.Path("M4 9h10.5a5.5 5.5 0 0 1 0 11H11", false, 1f)
    )

private val SpinnerIconGeometry = listOf(
        IconGeometry.Circle(12f, 12f, 8.5f, false, 0.25f),
        IconGeometry.Path("M12 3.5a8.5 8.5 0 0 1 8.5 8.5", false, 1f)
    )

@Composable
fun CheckIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(CheckIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun XIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(XIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun ChevronIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(ChevronIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun BanIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(BanIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun AlertIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(AlertIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun ShieldIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(ShieldIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun SparkIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(SparkIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun CopyIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(CopyIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun ExternalIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(ExternalIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun FileIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(FileIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun ImageIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(ImageIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun ToolIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(ToolIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun TerminalIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(TerminalIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun ClockIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(ClockIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun ArrowUpIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(ArrowUpIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun ArrowDownIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(ArrowDownIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun CommentIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(CommentIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun UndoIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(UndoIconGeometry, 24f, modifier.size(size), color, 2f)

@Composable
fun SpinnerIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Color.White) =
    SourceIcon(SpinnerIconGeometry, 24f, modifier.size(size), color, 2f)
