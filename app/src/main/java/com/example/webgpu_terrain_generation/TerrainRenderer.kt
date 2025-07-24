package com.example.webgpu_terrain_generation

import android.content.res.AssetManager
import android.dawn.BindGroupDescriptor
import android.dawn.BindGroupEntry
import android.dawn.Buffer
import android.dawn.BufferDescriptor
import android.dawn.BufferUsage
import android.dawn.Color
import android.dawn.ColorTargetState
import android.dawn.CompareFunction
import android.dawn.CullMode
import android.dawn.DepthStencilState
import android.dawn.Device
import android.dawn.Extent3D
import android.dawn.FragmentState
import android.dawn.LoadOp
import android.dawn.OptionalBool
import android.dawn.PrimitiveState
import android.dawn.RenderPassColorAttachment
import android.dawn.RenderPassDepthStencilAttachment
import android.dawn.RenderPassDescriptor
import android.dawn.RenderPipeline
import android.dawn.RenderPipelineDescriptor
import android.dawn.ShaderModuleDescriptor
import android.dawn.ShaderSourceWGSL
import android.dawn.StoreOp
import android.dawn.SurfaceConfiguration
import android.dawn.TextureDescriptor
import android.dawn.TextureFormat
import android.dawn.TextureUsage
import android.dawn.VertexState
import android.dawn.helper.asString
import androidx.compose.runtime.Composable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun TerrainRenderer(assets: AssetManager, params: TerrainParams) {
    GraphicsSurface() { webgpu, width, height, scope ->
        val surface = webgpu.webgpuSurface
        val device = webgpu.device

        surface.configure(
            SurfaceConfiguration(
                device = device,
                format = TextureFormat.RGBA8Unorm,
                width = width,
                height = height,
                usage = TextureUsage.RenderAttachment,
            )
        )

        val shader = loadShaderSource(assets, "shaders/shader.wgsl")
        val renderPipeline = createRenderPipeline(device, shader)
        val depthTextureView = createDepthTextureView(device, width, height)
        val cameraBuffer = createCameraBuffer(device)
        val renderPassDescriptor = createRenderPassDescriptor(surface, depthTextureView)

        val renderer =
            TerrainRendererImpl(
                assets,
                device,
                params,
                scope,
                surface,
                renderPipeline,
                cameraBuffer,
                renderPassDescriptor,
                width,
                height,
            )

        object : Renderer() {
            override suspend fun render(
                frameTimeNanos: Long,
                pitch: Float,
                yaw: Float,
                screenIsPressed: Boolean,
            ) {
                renderer.render(frameTimeNanos, pitch, yaw, screenIsPressed)
            }

            override fun close() {
                renderer.close()
            }
        }
    }
}

private class TerrainRendererImpl(
    assets: AssetManager,
    private val device: Device,
    private val params: TerrainParams,
    private val scope: CoroutineScope,
    private val surface: android.dawn.Surface,
    private val renderPipeline: RenderPipeline,
    private val cameraBuffer: Buffer,
    private val renderPassDescriptor: RenderPassDescriptor,
    private val width: Int,
    private val height: Int,
) {

    private var mapGenerator: MapGenerator =
        MapGenerator(
            assets,
            device,
            params.mapSize,
            params.mapSize,
            params.noiseScale,
            params.octaves,
            params.persistence,
            params.lacunarity,
            params.seed,
        )

    private var cameraPosX = 0f
    private var cameraPosY = 200f
    private var cameraPosZ = 0f

    private var generationStarted = false
    @Volatile private var mapReady = false

    suspend fun render(frameTimeNanos: Long, pitch: Float, yaw: Float, screenIsPressed: Boolean) {
        ensureMapGenerated()

        if (!mapReady) {
            clearScreen()
            return
        }

        updateCameraPosition(pitch, yaw, screenIsPressed)

        val cameraData = createCameraData(pitch, yaw)
        device.queue.writeBuffer(cameraBuffer, 0, cameraData)

        val commandEncoder = device.createCommandEncoder()
        renderPassDescriptor.colorAttachments[0].view = surface.getCurrentTexture().texture.createView()

        val renderBindGroup =
            device.createBindGroup(
                BindGroupDescriptor(
                    layout = renderPipeline.getBindGroupLayout(0),
                    entries =
                        arrayOf(
                            BindGroupEntry(binding = 0, buffer = cameraBuffer),
                            BindGroupEntry(binding = 1, buffer = mapGenerator.noiseHeightsBuffer),
                            BindGroupEntry(binding = 2, buffer = mapGenerator.colorBuffer),
                        ),
                )
            )

        commandEncoder.run {
            with(beginRenderPass(renderPassDescriptor)) {
                setPipeline(renderPipeline)
                setBindGroup(0, renderBindGroup)
                val numIndices = (params.mapSize - 1) * (params.mapSize - 1) * 6
                draw(numIndices, 1, 0, 0)
                end()
            }
        }

        device.queue.submit(arrayOf(commandEncoder.finish()))
        surface.present()
    }

    private fun ensureMapGenerated() {
        if (!generationStarted) {
            generationStarted = true
            scope.launch {
                mapGenerator.generateMap()
                mapReady = true
            }
        }
    }

    private fun clearScreen() {
        val commandEncoder = device.createCommandEncoder()
        renderPassDescriptor.colorAttachments[0].view = surface.getCurrentTexture().texture.createView()
        commandEncoder.run { with(beginRenderPass(renderPassDescriptor)) { end() } }
        device.queue.submit(arrayOf(commandEncoder.finish()))
        surface.present()
    }

    private fun updateCameraPosition(pitch: Float, yaw: Float, screenIsPressed: Boolean) {
        val forwardX = cos(pitch) * sin(yaw)
        val forwardY = sin(pitch)
        val forwardZ = -cos(pitch) * cos(yaw)

        val len = sqrt(forwardX * forwardX + forwardY * forwardY + forwardZ * forwardZ)

        if (len > 0.0f && screenIsPressed) {
            val normalizedForwardX = forwardX / len
            val normalizedForwardY = forwardY / len
            val normalizedForwardZ = forwardZ / len

            val speed = 1.3f

            cameraPosX += normalizedForwardX * speed
            cameraPosY += normalizedForwardY * speed
            cameraPosZ += normalizedForwardZ * speed
        }
    }

    private fun createCameraData(pitch: Float, yaw: Float): ByteBuffer {
        val aspectRatio =
            if (height.roundDownToNearestMultipleOf(64) > 0)
                width.roundDownToNearestMultipleOf(64).toFloat() /
                        height.roundDownToNearestMultipleOf(64).toFloat()
            else 1.0f

        val cameraData = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        with(cameraData) {
            putFloat(pitch)
            putFloat(yaw)
            putFloat(aspectRatio)
            putFloat(cameraPosX)
            putFloat(cameraPosY)
            putFloat(cameraPosZ)
            putFloat(params.mapSize.toFloat())
            putFloat(params.mapSize.toFloat())
        }
        cameraData.flip()
        return cameraData
    }

    fun close() {
        // Add any cleanup logic here if needed
    }
}

private fun createRenderPipeline(device: Device, shader: ShaderSourceWGSL): RenderPipeline =
    device.createRenderPipeline(
        RenderPipelineDescriptor(
            label = "main render pipeline",
            vertex =
                VertexState(
                    module = device.createShaderModule(ShaderModuleDescriptor(shaderSourceWGSL = shader)),
                    entryPoint = "vs",
                ),
            primitive = PrimitiveState(cullMode = CullMode.Back),
            depthStencil =
                DepthStencilState(
                    format = TextureFormat.Depth24PlusStencil8,
                    depthWriteEnabled = OptionalBool.True,
                    depthCompare = CompareFunction.Less,
                ),
            fragment =
                FragmentState(
                    module = device.createShaderModule(ShaderModuleDescriptor(shaderSourceWGSL = shader)),
                    entryPoint = "fs",
                    targets = arrayOf(ColorTargetState(format = TextureFormat.RGBA8Unorm)),
                ),
        )
    )

private fun createDepthTextureView(device: Device, width: Int, height: Int) =
    device
        .createTexture(
            TextureDescriptor(
                usage = TextureUsage.RenderAttachment,
                size = Extent3D(width, height),
                format = TextureFormat.Depth24PlusStencil8,
            )
        )
        .createView()

private fun createCameraBuffer(device: Device): Buffer =
    device.createBuffer(
        BufferDescriptor(
            label = "camera buffer",
            size = (8 * Float.SIZE_BYTES).toLong(),
            usage = BufferUsage.Uniform or BufferUsage.CopyDst,
        )
    )

private fun createRenderPassDescriptor(
    surface: android.dawn.Surface,
    depthTextureView: android.dawn.TextureView,
) =
    RenderPassDescriptor(
        colorAttachments =
            arrayOf(
                RenderPassColorAttachment(
                    loadOp = LoadOp.Clear,
                    storeOp = StoreOp.Store,
                    clearValue = Color(0.529, 0.808, 0.922, 1.0),
                    view = surface.getCurrentTexture().texture.createView(),
                )
            ),
        depthStencilAttachment =
            RenderPassDepthStencilAttachment(
                view = depthTextureView,
                depthLoadOp = LoadOp.Clear,
                depthStoreOp = StoreOp.Store,
                depthClearValue = 1.0f,
                stencilLoadOp = LoadOp.Clear,
                stencilStoreOp = StoreOp.Store,
            ),
    )

private fun loadShaderSource(assets: AssetManager, path: String): ShaderSourceWGSL =
    ShaderSourceWGSL(code = assets.open(path).asString())

fun Int.roundDownToNearestMultipleOf(multiple: Int): Int = (this / multiple) * multiple