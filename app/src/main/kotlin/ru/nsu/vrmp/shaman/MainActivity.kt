package ru.nsu.vrmp.shaman

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ru.nsu.vrmp.shaman.core.recognition.Gesture

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialExpressiveTheme(motionScheme = MotionScheme.expressive()) {
                Scaffold { padding ->
                    PlaceholderScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

/** Temporary start screen; replaced by the game screen in #7. */
@Composable
fun PlaceholderScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Пульт шамана", style = MaterialTheme.typography.headlineMedium)
        Text("Gestures: " + Gesture.entries.joinToString { it.name })
    }
}

@Preview
@Composable
private fun PlaceholderScreenPreview() {
    MaterialExpressiveTheme { PlaceholderScreen() }
}
