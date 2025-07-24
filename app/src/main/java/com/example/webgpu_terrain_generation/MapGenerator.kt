package com.example.webgpu_terrain_generation

import android.content.res.AssetManager
import android.dawn.BindGroupDescriptor
import android.dawn.BindGroupEntry
import android.dawn.Buffer
import android.dawn.BufferDescriptor
import android.dawn.BufferUsage
import android.dawn.ComputePassDescriptor
import android.dawn.ComputePipeline
import android.dawn.ComputePipelineDescriptor
import android.dawn.ComputeState
import android.dawn.Device
import android.dawn.ShaderModuleDescriptor
import android.dawn.ShaderSourceWGSL
import android.dawn.helper.asString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class MapGenerator(
    private val assets: AssetManager,
    private val device: Device,
    private val mapWidth: Int,
    private val mapHeight: Int,
    private val noiseScale: Float,
    private val octaves: Int,
    private val persistence: Float,
    private val lacunarity: Float,
    private val seed: Int,
) {

    lateinit var noiseHeightsBuffer: Buffer
        private set
    lateinit var moistureBuffer: Buffer
        private set
    lateinit var colorBuffer: Buffer
        private set

    private val computeNoisePipeline: ComputePipeline by lazy { createComputeNoisePipeline() }
    private val computeColorsPipeline: ComputePipeline by lazy { createComputeColorsPipeline() }

    suspend fun generateMap() {
        // Step 1: Compute Noise Heights
        noiseHeightsBuffer = createStorageBuffer("noiseHeightsBuffer")
        val paramsBuffer = createNoiseParamsBuffer()
        runComputePass(computeNoisePipeline, paramsBuffer, noiseHeightsBuffer)

        // Step 2: Compute Moisture
        moistureBuffer = createStorageBuffer("moistureBuffer")
        val moistureParamsBuffer = createNoiseParamsBuffer(moisture = true)
        runComputePass(computeNoisePipeline, moistureParamsBuffer, moistureBuffer)

        // Step 3: Compute Colors
        colorBuffer = createStorageBuffer("colorBuffer")
        val heightWidthBuffer = createHeightWidthBuffer()
        runColorComputePass(heightWidthBuffer, noiseHeightsBuffer, moistureBuffer, colorBuffer)
    }

    private fun runComputePass(pipeline: ComputePipeline, paramsBuffer: Buffer, outputBuffer: Buffer) {
        val bindGroup =
            device.createBindGroup(
                BindGroupDescriptor(
                    layout = pipeline.getBindGroupLayout(0),
                    entries =
                        arrayOf(
                            BindGroupEntry(binding = 0, buffer = paramsBuffer),
                            BindGroupEntry(binding = 1, buffer = outputBuffer),
                        ),
                )
            )

        val commandEncoder = device.createCommandEncoder()
        commandEncoder.run {
            with(beginComputePass(ComputePassDescriptor())) {
                setPipeline(pipeline)
                setBindGroup(0, bindGroup)
                dispatchWorkgroups((mapWidth + 7) / 8, (mapHeight + 7) / 8, 1)
                end()
            }
        }
        device.queue.submit(arrayOf(commandEncoder.finish()))
    }

    private fun runColorComputePass(
        heightWidthBuffer: Buffer,
        noiseBuffer: Buffer,
        moistureBuffer: Buffer,
        colorBuffer: Buffer,
    ) {
        val bindGroup =
            device.createBindGroup(
                BindGroupDescriptor(
                    layout = computeColorsPipeline.getBindGroupLayout(0),
                    entries =
                        arrayOf(
                            BindGroupEntry(binding = 0, buffer = heightWidthBuffer),
                            BindGroupEntry(binding = 1, buffer = noiseBuffer),
                            BindGroupEntry(binding = 2, buffer = colorBuffer),
                            BindGroupEntry(binding = 3, buffer = moistureBuffer),
                        ),
                )
            )

        val commandEncoder = device.createCommandEncoder()
        commandEncoder.run {
            with(beginComputePass(ComputePassDescriptor())) {
                setPipeline(computeColorsPipeline)
                setBindGroup(0, bindGroup)
                dispatchWorkgroups((mapWidth + 7) / 8, (mapHeight + 7) / 8, 1)
                end()
            }
        }
        device.queue.submit(arrayOf(commandEncoder.finish()))
    }

    private fun createComputeNoisePipeline(): ComputePipeline =
        createComputePipeline("compute noise pipeline", "shaders/compute_perlin.wgsl")

    private fun createComputeColorsPipeline(): ComputePipeline =
        createComputePipeline("compute colors pipeline", "shaders/compute_noise_to_colors.wgsl")

    private fun createComputePipeline(label: String, shaderPath: String): ComputePipeline =
        device.createComputePipeline(
            ComputePipelineDescriptor(
                label = label,
                compute =
                    ComputeState(
                        module =
                            device.createShaderModule(
                                ShaderModuleDescriptor(
                                    shaderSourceWGSL = ShaderSourceWGSL(code = assets.open(shaderPath).asString())
                                )
                            ),
                        entryPoint = "main",
                    ),
            )
        )

    private fun createNoiseParamsBuffer(moisture: Boolean = false): Buffer {
        val paramsBufferSize =
            (2 * Int.SIZE_BYTES) + // mapWidth, mapHeight
                    (2 * Float.SIZE_BYTES) + // noiseScale, persistence
                    (1 * Int.SIZE_BYTES) + // octaves
                    (1 * Float.SIZE_BYTES) + // lacunarity
                    (1 * Int.SIZE_BYTES) // seed

        val paramsByteBuffer =
            ByteBuffer.allocateDirect(paramsBufferSize).order(ByteOrder.nativeOrder())
        with(paramsByteBuffer) {
            putInt(mapWidth)
            putInt(mapHeight)
            if (moisture) {
                putFloat(randomFloat(TerrainParamsConstants.SCALE_RANGE.min, TerrainParamsConstants.SCALE_RANGE.max))
                putInt(randomInt(TerrainParamsConstants.OCTAVES_RANGE.min, TerrainParamsConstants.SCALE_RANGE.max))
                putFloat(randomFloat(TerrainParamsConstants.PERSISTENCE_RANGE.min, TerrainParamsConstants.PERSISTENCE_RANGE.max))
                putFloat(randomFloat(TerrainParamsConstants.LACUNARITY_RANGE.min, TerrainParamsConstants.LACUNARITY_RANGE.max))
            } else {
                putFloat(noiseScale)
                putInt(octaves)
                putFloat(persistence)
                putFloat(lacunarity)
            }
            putInt(if (moisture) seed + 1 else seed)
            flip()
        }

        return createUniformBuffer("paramsBuffer", paramsBufferSize.toLong(), paramsByteBuffer)
    }

    private fun createHeightWidthBuffer(): Buffer {
        val bufferSize = 2 * Int.SIZE_BYTES
        val heightWidthByteBuffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.nativeOrder())
        with(heightWidthByteBuffer) {
            putInt(mapWidth)
            putInt(mapHeight)
            flip()
        }
        return createUniformBuffer("heightWidthBuffer", bufferSize.toLong(), heightWidthByteBuffer)
    }

    private fun createUniformBuffer(label: String, size: Long, data: ByteBuffer): Buffer {
        val buffer =
            device.createBuffer(
                BufferDescriptor(label = label, size = size, usage = BufferUsage.Uniform or BufferUsage.CopyDst)
            )
        device.queue.writeBuffer(buffer, 0, data)
        return buffer
    }

    private fun createStorageBuffer(label: String): Buffer {
        val bufferSize = mapWidth * mapHeight * Float.SIZE_BYTES * 4
        return device.createBuffer(
            BufferDescriptor(
                label = label,
                size = bufferSize.toLong(),
                usage = BufferUsage.Storage or BufferUsage.CopySrc,
            )
        )
    }

    private fun randomFloat(min: Float, max: Float): Float =
        Random.nextFloat() * (max - min) + min

    private fun randomInt(min: Float, max: Float): Int =
        Random.nextInt(min.toInt(), max.toInt())
}