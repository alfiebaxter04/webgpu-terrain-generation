package com.example.webgpu_terrain_generation

data class SliderValues(val min: Float, val max: Float, val default: Float)

object TerrainParamsConstants {
    val MAP_SIZE_RANGE = SliderValues(min = 64f, max = 2304f, default = 1024f)
    val SCALE_RANGE = SliderValues(min = 100f, max = 500f, default = 250f)
    val OCTAVES_RANGE = SliderValues(min = 1f, max = 12f, default = 8f)
    val PERSISTENCE_RANGE = SliderValues(min = 0.1f, max = 1.0f, default = 0.50f)
    val LACUNARITY_RANGE = SliderValues(min = 1.1f, max = 4.0f, default = 2.0f)
}
