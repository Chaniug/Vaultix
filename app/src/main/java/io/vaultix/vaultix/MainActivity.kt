package io.vaultix.vaultix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.vaultix.vaultix.ui.VaultixApp
import io.vaultix.vaultix.ui.theme.VaultixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VaultixTheme {
                VaultixApp()
            }
        }
    }
}
