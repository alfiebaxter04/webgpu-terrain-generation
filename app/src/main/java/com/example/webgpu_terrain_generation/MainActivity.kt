package com.example.webgpu_terrain_generation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import java.util.Random

data class TerrainParams(
    val mapSize: Int,
    val noiseScale: Float,
    val octaves: Int,
    val persistence: Float,
    val lacunarity: Float,
    val seed: Int,
    val id: Int = 0,
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        setContent { MainScreen() }
    }

    private fun hideSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
fun MainScreen() {
    var showSettings by remember { mutableStateOf(true) }
    var terrainParamsId by remember { mutableIntStateOf(0) }

    val paramsState = rememberTerrainParamsState()

    val currentParams =
        remember(terrainParamsId) {
            TerrainParams(
                paramsState.mapSize.roundDownToNearestMultipleOf(64),
                paramsState.noiseScale,
                paramsState.octaves,
                paramsState.persistence,
                paramsState.lacunarity,
                paramsState.seed,
                terrainParamsId,
            )
        }

    Box(modifier = Modifier.fillMaxSize()) {
        key(terrainParamsId) { TerrainRenderer(LocalContext.current.assets, currentParams) }

        Row(modifier = Modifier.fillMaxSize()) {
            if (showSettings) {
                SettingsPanel(
                    paramsState = paramsState,
                    onRegenerateSeed = {
                        paramsState.seed = Random().nextInt()
                        terrainParamsId++
                    },
                    onUpdate = { terrainParamsId++ },
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                IconButton(
                    onClick = { showSettings = !showSettings },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }
        }
    }
}

class TerrainParamsState {
    var mapSize by mutableIntStateOf(1024)
    var noiseScale by mutableFloatStateOf(TerrainParamsConstants.SCALE_RANGE.default)
    var octaves by mutableIntStateOf(TerrainParamsConstants.OCTAVES_RANGE.default.toInt())
    var persistence by mutableFloatStateOf(TerrainParamsConstants.PERSISTENCE_RANGE.default)
    var lacunarity by mutableFloatStateOf(TerrainParamsConstants.LACUNARITY_RANGE.default)
    var seed by mutableIntStateOf(Random().nextInt())
}

@Composable fun rememberTerrainParamsState(): TerrainParamsState = remember { TerrainParamsState() }

@Composable
fun SettingsPanel(
    paramsState: TerrainParamsState,
    onRegenerateSeed: () -> Unit,
    onUpdate: () -> Unit,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxHeight().width(300.dp),
    ) {
        LazyColumn(modifier = Modifier.padding(16.dp).fillMaxHeight()) {
            item {
                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                    SliderControl(
                        "Map Size",
                        paramsState.mapSize.toFloat(),
                        TerrainParamsConstants.MAP_SIZE_RANGE.min..TerrainParamsConstants.MAP_SIZE_RANGE.max,
                    ) {
                        paramsState.mapSize = it.toInt()
                    }
                    SliderControl(
                        "Scale",
                        paramsState.noiseScale,
                        TerrainParamsConstants.SCALE_RANGE.min..TerrainParamsConstants.SCALE_RANGE.max,
                    ) {
                        paramsState.noiseScale = it
                    }
                    SliderControl(
                        "Octaves",
                        paramsState.octaves.toFloat(),
                        TerrainParamsConstants.OCTAVES_RANGE.min..TerrainParamsConstants.OCTAVES_RANGE.max,
                        0,
                    ) {
                        paramsState.octaves = it.toInt()
                    }
                    SliderControl(
                        "Persistence",
                        paramsState.persistence,
                        TerrainParamsConstants.PERSISTENCE_RANGE.min..TerrainParamsConstants.PERSISTENCE_RANGE
                            .max,
                    ) {
                        paramsState.persistence = it
                    }
                    SliderControl(
                        "Lacunarity",
                        paramsState.lacunarity,
                        TerrainParamsConstants.LACUNARITY_RANGE.min..TerrainParamsConstants.LACUNARITY_RANGE.max,
                    ) {
                        paramsState.lacunarity = it
                    }
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(
                        onClick = onRegenerateSeed,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                    ) {
                        Text("Regenerate Seed", color = Color.White)
                    }
                    Button(
                        onClick = onUpdate,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Blue),
                    ) {
                        Text("Update", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun SliderControl(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    decimals: Int = 2,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, color = Color.White)
            Text(String.format(Locale.US, "%.${decimals}f", value), color = Color.White)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}
