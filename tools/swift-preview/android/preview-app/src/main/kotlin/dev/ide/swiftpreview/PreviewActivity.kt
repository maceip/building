package dev.ide.swiftpreview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = runCatching { assets.open("preview-title.txt").bufferedReader().use { it.readText() } }
            .getOrDefault("Swift preview")
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Column(Modifier.fillMaxSize().background(Color(0xFFF2F2F7)).systemBarsPadding().imePadding().padding(20.dp)) {
                    Text("BUILDING", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF636366))
                    Spacer(Modifier.height(8.dp))
                    Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("Running on Android · Cupertino", fontSize = 14.sp, color = Color(0xFF636366))
                    Spacer(Modifier.height(24.dp))
                    SwiftPreview(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(Color.White))
                    Spacer(Modifier.height(12.dp))
                    Text("Compiled Swift → native Compose view", fontSize = 12.sp, color = Color(0xFF636366))
                }
            }
        }
    }
}
