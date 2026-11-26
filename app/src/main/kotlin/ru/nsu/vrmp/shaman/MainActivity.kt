package ru.nsu.vrmp.shaman

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import ru.nsu.vrmp.shaman.ui.game.GameRoute
import ru.nsu.vrmp.shaman.ui.theme.ShamanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShamanTheme(darkTheme = true) {
                Scaffold { padding ->
                    GameRoute(Modifier.padding(padding))
                }
            }
        }
    }
}
