package fragments.agentui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One semantic icon vocabulary for the kit; duplicate source exports resolve here. */
enum class AgentIconId {
    Check, Close, Pause, Blocked, Alert, Dot, Chevron, Retry, Shield,
    ArrowDown, ChevronDown, Info, ChevronLeft, Search, Copy, Plus,
    Sun, Moon, Monitor, Thumb, Dots, Play, PauseBars, Volume, Muted,
    Expand, Stop, Menu, Sidebar, Send, Spark, File, Image, ArrowUp,
    Minus, Ban, External, Tool, Terminal, Clock, Comment, Undo, Spinner,
    ArrowRight, DoubleChevronRight, FileExtension, Globe,
}

@Composable
fun AgentIcon(
    id: AgentIconId,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    color: Color = AgentUiTheme.palette.text,
    filename: String = "",
) {
    when (id) {
        AgentIconId.Globe -> Canvas(modifier.size(size)) {
            val line = this.size.minDimension * 0.075f
            drawCircle(color, radius = this.size.minDimension / 2f - line / 2f, style = Stroke(line))
            drawOval(color, topLeft = Offset(this.size.width * 0.28f, line / 2f),
                size = Size(this.size.width * 0.44f, this.size.height - line), style = Stroke(line))
            drawLine(color, Offset(line / 2f, this.size.height / 2f),
                Offset(this.size.width - line / 2f, this.size.height / 2f), line)
        }
        AgentIconId.Check -> fragments.components.creance.icons.CheckIcon(modifier, size, color)
        AgentIconId.Close -> fragments.components.creance.icons.CrossIcon(modifier, size, color)
        AgentIconId.Pause -> fragments.components.creance.icons.PauseIcon(modifier, size, color)
        AgentIconId.Blocked -> fragments.components.creance.icons.BlockedIcon(modifier, size, color)
        AgentIconId.Alert -> fragments.components.creance.icons.AlertIcon(modifier, size, color)
        AgentIconId.Dot -> fragments.components.creance.icons.DotIcon(modifier, size, color)
        AgentIconId.Chevron -> fragments.components.creance.icons.ChevronIcon(modifier, size, color)
        AgentIconId.Retry -> fragments.components.creance.icons.RetryIcon(modifier, size, color)
        AgentIconId.Shield -> fragments.components.creance.icons.ShieldIcon(modifier, size, color)
        AgentIconId.ArrowDown -> fragments.components.creance.icons.ArrowDownIcon(modifier, size, color)
        AgentIconId.ChevronDown -> fragments.components.creance.icons.ChevronDownIcon(modifier, size, color)
        AgentIconId.Info -> fragments.components.creance.icons.InfoIcon(modifier, size, color)
        AgentIconId.ChevronLeft -> fragments.components.creance.icons.ChevronLeftIcon(modifier, size, color)
        AgentIconId.Search -> fragments.components.creance.icons.SearchIcon(modifier, size, color)
        AgentIconId.Copy -> fragments.components.creance.icons.CopyIcon(modifier, size, color)
        AgentIconId.Plus -> fragments.components.creance.icons.PlusIcon(modifier, size, color)
        AgentIconId.Sun -> fragments.components.creance.icons.SunIcon(modifier, size, color)
        AgentIconId.Moon -> fragments.components.creance.icons.MoonIcon(modifier, size, color)
        AgentIconId.Monitor -> fragments.components.creance.icons.MonitorIcon(modifier, size, color)
        AgentIconId.Thumb -> fragments.components.creance.icons.ThumbIcon(modifier, size, color)
        AgentIconId.Dots -> fragments.components.creance.icons.DotsIcon(modifier, size, color)
        AgentIconId.Play -> fragments.components.creance.icons.PlayIcon(modifier, size, color)
        AgentIconId.PauseBars -> fragments.components.creance.icons.PauseBarsIcon(modifier, size, color)
        AgentIconId.Volume -> fragments.components.creance.icons.VolumeIcon(modifier, size, color)
        AgentIconId.Muted -> fragments.components.creance.icons.MutedIcon(modifier, size, color)
        AgentIconId.Expand -> fragments.components.creance.icons.ExpandIcon(modifier, size, color)
        AgentIconId.Stop -> fragments.components.creance.icons.StopIcon(modifier, size, color)
        AgentIconId.Menu -> fragments.components.creance.icons.MenuIcon(modifier, size, color)
        AgentIconId.Sidebar -> fragments.components.creance.icons.SidebarIcon(modifier, size, color)
        AgentIconId.Send -> fragments.components.creance.icons.SendIcon(modifier, size, color)
        AgentIconId.Spark -> fragments.components.creance.icons.SparkIcon(modifier, size, color)
        AgentIconId.File -> fragments.components.creance.icons.FileIcon(modifier, size, color)
        AgentIconId.Image -> fragments.components.creance.icons.ImageIcon(modifier, size, color)
        AgentIconId.ArrowUp -> fragments.components.creance.icons.ArrowUpIcon(modifier, size, color)
        AgentIconId.Minus -> fragments.components.creance.icons.MinusIcon(modifier, size, color)
        AgentIconId.Ban -> fragments.components.signoff.icons.BanIcon(modifier, size, color)
        AgentIconId.External -> fragments.components.signoff.icons.ExternalIcon(modifier, size, color)
        AgentIconId.Tool -> fragments.components.signoff.icons.ToolIcon(modifier, size, color)
        AgentIconId.Terminal -> fragments.components.signoff.icons.TerminalIcon(modifier, size, color)
        AgentIconId.Clock -> fragments.components.signoff.icons.ClockIcon(modifier, size, color)
        AgentIconId.Comment -> fragments.components.signoff.icons.CommentIcon(modifier, size, color)
        AgentIconId.Undo -> fragments.components.signoff.icons.UndoIcon(modifier, size, color)
        AgentIconId.Spinner -> fragments.components.signoff.icons.SpinnerIcon(modifier, size, color)
        AgentIconId.ArrowRight -> fragments.components.agentelements.icons.IconArrowRight(modifier, size, color)
        AgentIconId.DoubleChevronRight -> fragments.components.agentelements.icons.IconDoubleChevronRight(modifier, size, color)
        AgentIconId.FileExtension -> fragments.components.agentelements.icons.FileExtIcon(filename, modifier, size)
    }
}
