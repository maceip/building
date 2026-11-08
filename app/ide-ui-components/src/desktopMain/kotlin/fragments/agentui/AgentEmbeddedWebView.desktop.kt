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
import androidx.compose.ui.text.font.FontFamily
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
            if (controller.title.isEmpty()) {
                controller.title = targetUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')
            }
            controller.isLoading = false
            if (controller.lastDomSnapshot.isEmpty()) {
                controller.lastDomSnapshot = "URL: $targetUrl\nTitle: ${controller.title}\nInteractive WebView active."
            }
        }
        controller.platformLoadHtml = { html, baseUrl ->
            if (baseUrl != null) controller.url = baseUrl
            controller.isLoading = false
            controller.lastDomSnapshot = html.take(600)
        }
        controller.platformReload = {
            controller.isLoading = false
        }
        if (controller.url.isNotEmpty()) {
            controller.isLoading = false
            if (controller.title.isEmpty()) {
                controller.title = controller.url.removePrefix("https://").removePrefix("http://").substringBefore('/')
            }
        }
        onDispose {
            controller.platformLoadUrl = null
            controller.platformLoadHtml = null
            controller.platformReload = null
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
            text = controller.title.ifEmpty { "Muse Embedded Browser" },
            color = Color(0xFFE6EDF3),
            fontSize = 13.sp,
        )
        Text(
            text = controller.url,
            color = Color(0xFF8B949E),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (controller.lastDomSnapshot.isNotEmpty()) {
            Text(
                text = controller.lastDomSnapshot.take(360),
                color = Color(0xFFC9D1D9),
                fontSize = 11.sp,
            )
        }
    }
}
