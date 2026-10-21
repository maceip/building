package dev.ide.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/**
 * Agent-first shape scale: soft 12–28px radii matching the redesign HTML cards, chips, pills, and sheets.
 */
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8),
    small = RoundedCornerShape(12),
    medium = RoundedCornerShape(16),
    large = RoundedCornerShape(20),
    extraLarge = RoundedCornerShape(28),
)
