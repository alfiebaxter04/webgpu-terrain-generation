package com.example.webgpu_terrain_generation

import java.io.Closeable

abstract class Renderer : Closeable {
    open suspend fun render(
        frameTimeNanos: Long,
        pitch: Float,
        yaw: Float,
        screenIsPressed: Boolean,
    ) {}
}
