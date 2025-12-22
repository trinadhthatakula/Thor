package com.valhalla.thor.presentation.installer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import com.valhalla.thor.presentation.theme.ThorTheme

class PortableInstallerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ThorTheme {
                PortableInstaller(
                    onDismiss = { finishAffinity() }
                )
            }
        }
    }
}

