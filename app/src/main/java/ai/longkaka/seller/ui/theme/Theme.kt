package ai.longkaka.seller.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SellerColors = darkColorScheme(
    primary = Color(0xFF22C7F2),
    onPrimary = Color(0xFF00131C),
    secondary = Color(0xFFFFC857),
    background = Color(0xFF071321),
    surface = Color(0xFF102238),
    surfaceVariant = Color(0xFF18324A),
    onBackground = Color(0xFFF2F7FC),
    onSurface = Color(0xFFF2F7FC)
)

@Composable
fun SellerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SellerColors, content = content)
}
