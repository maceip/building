package dev.ide.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.CaAccent
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalComposeUiApi::class)
class RareCraftTest {

    @Test
    fun themeDisablesMaterialMinimumInteractiveTouchTargetFloor() {
        var interactiveFloor: Dp? = null
        var darkBackground: Color? = null
        var darkOutlineVariant: Color? = null
        var darkPrimary: Color? = null

        ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
            CodeAssistTheme(dark = true, accent = CaAccent.Lime) {
                interactiveFloor = LocalMinimumInteractiveComponentSize.current
                darkBackground = MaterialTheme.colorScheme.background
                darkOutlineVariant = MaterialTheme.colorScheme.outlineVariant
                darkPrimary = MaterialTheme.colorScheme.primary
            }
        }.use { scene ->
            scene.render()
        }

        assertEquals(0.dp, interactiveFloor)
        assertEquals(Color(0xFF191A17), darkBackground)
        assertEquals(Color(0xFF2C2D27), darkOutlineVariant)
        assertEquals(Color(0xFFC8F169), darkPrimary)
    }

    @Test
    fun rareFolderVisiblyFansOutWhenExpanded() {
        val closed = renderPng(160, 140) {
            CodeAssistTheme(dark = true) {
                RareFolder(expanded = false, modifier = Modifier.size(112.dp, 104.dp))
            }
        }
        val expanded = renderPng(160, 140) {
            CodeAssistTheme(dark = true) {
                RareFolder(expanded = true, modifier = Modifier.size(112.dp, 104.dp))
            }
        }
        assertFalse(closed.contentEquals(expanded), "Expanded RareFolder must fan its inner sheets apart")
    }

    @Test
    fun rareDeleteButtonAndTaskRowRenderCompactStates() {
        val idle = renderPng(320, 220) {
            CodeAssistTheme(dark = true) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RareDeleteButton(expanded = false, onExpandedChange = {}, onConfirm = {})
                    RareTaskRow(label = "Verify workspace build", checked = false, onCheckedChange = {})
                    RareCounter(value = 14)
                    GlassSurface(shape = RoundedCornerShape(8.dp)) {
                        Text("Liquid glass", Modifier.padding(8.dp))
                    }
                }
            }
        }
        val active = renderPng(320, 220) {
            CodeAssistTheme(dark = true) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RareDeleteButton(expanded = true, onExpandedChange = {}, onConfirm = {})
                    RareTaskRow(label = "Verify workspace build", checked = true, onCheckedChange = {})
                    RareCounter(value = 42)
                    RareBounceNavigation(items = listOf("Projects", "Explore"), selectedIndex = 1, onSelect = {})
                }
            }
        }
        assertFalse(idle.contentEquals(active), "Active states of Rare controls must render distinct frames")
    }

    @Test
    fun renderComponentShowcaseSheet() {
        val png = renderPng(2100, 960) {
            CodeAssistTheme(dark = true, accent = CaAccent.Lime) {
                val c = MaterialTheme.colorScheme
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(c.background)
                        .padding(24.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Eyebrow("CARBON-ZINC · STEEL-COBALT · COMPACT RARE UI CRAFT")
                                Text(
                                    "Workspace Component Showcase",
                                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = c.onSurface,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CountFilterChip(label = "Projects", count = 12, selected = true, onClick = {})
                                CountFilterChip(label = "Updates", count = 3, selected = false, onClick = {})
                                PillChip(label = "Compose", selected = true, onClick = {}, leadingGlyph = CaSymbols.bolt)
                                PrimaryActionButton(label = "Create new app", glyph = CaSymbols.add, onClick = {})
                                SquareToneButton(glyph = CaSymbols.settings, contentDescription = "Settings", onClick = {})
                            }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            // Column 1: RareFolder (Closed vs Expanded) + Liquid Glass
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = c.surfaceContainerLow,
                                border = BorderStroke(1.dp, c.outlineVariant),
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Eyebrow("RARE FOLDER (CLOSED VS. FANNED SPRING)")
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            RareFolder(expanded = false, modifier = Modifier.size(96.dp, 88.dp))
                                            Text("Idle", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            RareFolder(expanded = true, modifier = Modifier.size(96.dp, 88.dp))
                                            Text("Hovered / Open", style = MaterialTheme.typography.labelSmall, color = c.primary)
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Eyebrow("LIQUID GLASS SURFACES (DIRECTIONAL DOME + SPECULAR RIM)")
                                    GlassSurface(
                                        material = GlassMaterial.Regular,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text("Floating Glass Chrome", style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                                            Spacer(Modifier.width(12.dp))
                                            MonoChip("1px rim · 45° dome")
                                        }
                                    }
                                }
                            }

                            // Column 2: RareTaskRow + RareDeleteButton + RareBounceNavigation
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = c.surfaceContainerLow,
                                border = BorderStroke(1.dp, c.outlineVariant),
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Eyebrow("RARE TASK ROWS (13-DOT RING + ANIMATED STRIKETHROUGH)")
                                    RareTaskRow(
                                        label = "Eliminate 48dp touch-target inflation",
                                        checked = true,
                                        onCheckedChange = {},
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    RareTaskRow(
                                        label = "Apply 1px carbon-zinc hairline borders",
                                        checked = true,
                                        onCheckedChange = {},
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    RareTaskRow(
                                        label = "Run live workspace verification",
                                        checked = false,
                                        onCheckedChange = {},
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Eyebrow("TWO-STAGE INLINE DELETE (IDLE VS. ROTATED LID -35°)")
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RareDeleteButton(expanded = false, onExpandedChange = {}, onConfirm = {})
                                        RareDeleteButton(expanded = true, onExpandedChange = {}, onConfirm = {})
                                        Spacer(Modifier.width(8.dp))
                                        RareBounceNavigation(
                                            items = listOf("Projects", "Explore"),
                                            selectedIndex = 0,
                                            onSelect = {},
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val outDir = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots")
        outDir.mkdirs()
        File(outDir, "rare-components-showcase.png").writeBytes(png)
    }

    private fun renderPng(width: Int, height: Int, content: @Composable () -> Unit): ByteArray =
        ImageComposeScene(width = width, height = height, density = Density(2f), content = content).use { scene ->
            scene.render(0L)
            for (frame in 1..20) scene.render(frame * 50_000_000L)
            scene.render(1_500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
}
