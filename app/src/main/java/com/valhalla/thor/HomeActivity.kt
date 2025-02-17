package com.valhalla.thor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.Modifier
import com.valhalla.thor.ui.home.HomePage
import com.valhalla.thor.ui.theme.ThorTheme

class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ThorTheme {
                HomePage(
                    modifier = Modifier
                ) {
                    finish()
                }
            }
        }
    }
}
