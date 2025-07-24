package com.example.webgpu_terrain_generation

import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.dawn.helper.WebGpu
import android.dawn.helper.createWebGpu

@Composable
fun GraphicsSurface(
    makeRenderer: (webGpu: WebGpu, width: Int, height: Int, scope: CoroutineScope) -> Renderer
) {
    var pitch by remember { mutableFloatStateOf(0f) }
    var yaw by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    var screenIsPressed by remember { mutableStateOf(false) }

    AndroidExternalSurface(
        modifier =
            Modifier.pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        yaw += dragAmount.x * 0.003f
                        pitch += dragAmount.y * 0.003f
                        pitch = pitch.coerceIn(-1.57f, 1.57f)
                        screenIsPressed = true
                    },
                    onDragEnd = { screenIsPressed = false },
                    onDragCancel = { screenIsPressed = false },
                )
            }
    ) {
        onSurface { surface, width, height ->
            val webgpu = createWebGpu(surface)
            val renderer = makeRenderer(webgpu, width, height, scope)
            var closing = false

            surface.onDestroyed {
                closing = true
                renderer.close()
                webgpu.close()
            }

            suspend fun nextFrame() {
                withFrameNanos { frameTimeNanos ->
                    launch {
                        if (!closing) {
                            renderer.render(frameTimeNanos, pitch, yaw, screenIsPressed)
                            nextFrame()
                        }
                    }
                }
            }
            nextFrame()
        }
    }
}
