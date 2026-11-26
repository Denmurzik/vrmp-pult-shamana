package ru.nsu.vrmp.shaman

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ru.nsu.vrmp.shaman.ui.calibration.CalibrationRoute
import ru.nsu.vrmp.shaman.ui.dataset.DatasetRoute
import ru.nsu.vrmp.shaman.ui.experiment.ExperimentRoute
import ru.nsu.vrmp.shaman.ui.game.GameRoute
import ru.nsu.vrmp.shaman.ui.theme.ShamanTheme
import ru.nsu.vrmp.shaman.ui.training.TrainingRoute

enum class AppDestination(val label: String) {
    GAME("Игра"),
    TRAINING("Тренировка"),
    CALIBRATION("Калибровка"),
    EXPERIMENT("Эксперимент"),
    DATASET("Датасет"),
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
            PrimaryScrollableTabRow(
                selectedTabIndex = destination.ordinal,
            ) {
                AppDestination.entries.forEach { item ->
                    Tab(
                        selected = destination == item,
                        onClick = { destination = item },
                        text = { Text(item.label) },
                    )
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
                AppDestination.CALIBRATION -> CalibrationRoute()
                AppDestination.EXPERIMENT -> ExperimentRoute()
                AppDestination.DATASET -> DatasetRoute()
            }
        }
    }
}
