package com.procutstudio.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.procutstudio.editor.ui.navigation.ProCutNavHost
import com.procutstudio.editor.ui.theme.ProCutTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single activity host. Every screen is a composable destination, which keeps the activity tiny and
 * makes deep links / state restoration the navigation graph's job.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            ProCutTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProCutNavHost()
                }
            }
        }
    }
}
