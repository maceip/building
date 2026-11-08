package fragments.agentui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
actual fun AgentEmbeddedWebView(
    controller: AgentEmbeddedBrowserController,
    modifier: Modifier,
) {
    DisposableEffect(controller) {
        controller.platformLoadUrl = { targetUrl ->
            controller.url = targetUrl
            controller.isLoading = false
        }
        onDispose {
            controller.platformLoadUrl = null
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0E1117))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = controller.title.ifEmpty { "Muse Browser" },
            color = Color(0xFFE6EDF3),
            fontSize = 13.sp,
        )
        Text(
            text = controller.url,
            color = Color(0xFF8B949E),
            fontSize = 11.sp,
        )
    }
}
