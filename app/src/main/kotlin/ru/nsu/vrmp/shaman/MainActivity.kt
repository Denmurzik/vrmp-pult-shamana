package ru.nsu.vrmp.shaman

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.nsu.vrmp.shaman.ui.experiment.ExperimentRoute
import ru.nsu.vrmp.shaman.ui.game.GameRoute
import ru.nsu.vrmp.shaman.ui.theme.ShamanTheme
import ru.nsu.vrmp.shaman.ui.training.TrainingRoute

enum class AppDestination(val label: String) {
    GAME("Игра"),
    TRAINING("Тренировка"),
    EXPERIMENT("Эксперимент"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShamanTheme(darkTheme = true) {
                ShamanRoot()
            }
        }
    }
}

@Composable
private fun ShamanRoot() {
    var destination by rememberSaveable { mutableStateOf(AppDestination.GAME) }

    Scaffold(
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppDestination.entries.forEach { item ->
                    val selected = destination == item
                    if (selected) {
                        Button(
                            onClick = { destination = item },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) {
                            Text(item.label)
                        }
                    } else {
                        FilledTonalButton(
                            onClick = { destination = item },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) {
                            Text(item.label)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (destination) {
                AppDestination.GAME -> GameRoute()
                AppDestination.TRAINING -> TrainingRoute()
                AppDestination.EXPERIMENT -> ExperimentRoute()
            }
        }
    }
}
