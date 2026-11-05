package ai.longkaka.seller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ai.longkaka.seller.ui.SellerDashboard
import ai.longkaka.seller.ui.theme.SellerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SellerTheme {
                SellerDashboard()
            }
        }
    }
}
