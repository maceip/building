package fragments.agentui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class BrowserActionDescription(val thoughts: String, val actionResult: String)

/** The same current-step caption in an inline browser card and an expanded browser. */
@Composable
fun AgentBrowserDescriptionOverlay(
    description: BrowserActionDescription,
    modifier: Modifier = Modifier,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    if (description.thoughts.isBlank() && description.actionResult.isBlank()) return
    Column(
        modifier.fillMaxWidth().background(palette.accent.copy(alpha = 0.75f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (description.thoughts.isNotBlank()) BasicText(
            "Thoughts: ${description.thoughts}",
            style = TextStyle(color = Color.White, fontSize = 12.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (description.actionResult.isNotBlank()) BasicText(
            "Action result: ${description.actionResult}",
            style = TextStyle(color = Color.White.copy(alpha = 0.88f), fontSize = 11.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
