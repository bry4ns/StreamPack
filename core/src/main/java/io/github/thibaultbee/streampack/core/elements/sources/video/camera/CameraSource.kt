/*
 * Copyright (C) 2021 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.core.elements.sources.video.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import io.github.thibaultbee.streampack.core.elements.sources.video.AbstractPreviewableSource
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.controllers.CameraController
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.cameraManager
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.cameras
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isFpsSupported
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraDispatcherProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraSizes
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CameraSurface
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.utils.CaptureRequestWithTargetsBuilder
import io.github.thibaultbee.streampack.core.elements.processing.video.DefaultSurfaceProcessorFactory
import io.github.thibaultbee.streampack.core.elements.processing.video.VideoFilterRegistry
import io.github.thibaultbee.streampack.core.elements.processing.video.ISurfaceProcessorInternal
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.pipelines.DispatcherProvider
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.utils.time.Timebase
import io.github.thibaultbee.streampack.core.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Camera source implementation.
 *
 * Based on Camera2 API.
 */
internal class CameraSource(
    private val context: Context,
    override val cameraId: String,
    dispatcherProvider: CameraDispatcherProvider,
) : ICameraSourceInternal, ICameraSource, AbstractPreviewableSource() {
    private val defaultDispatcher = dispatcherProvider.default
    private val coroutineScope = CoroutineScope(defaultDispatcher)

    private val manager = context.cameraManager
    private val characteristics = manager.getCameraCharacteristics(cameraId)

    private val controller = CameraController(
        manager,
        characteristics,
        dispatcherProvider,
        cameraId,
        onCaptureRequestBuilder = {
            defaultCaptureRequest(this)
        }
    )

    override val settings by lazy {
        CameraSettings(
            coroutineScope,
            characteristics,
            controller
        )
    }

    override val timebase =
        if (characteristics[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE] == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
            Timebase.REALTIME
        } else {
            Timebase.UPTIME
        }


    override val infoProviderFlow = MutableStateFlow(CameraInfoProvider(manager, cameraId))

    // States
    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    private val _isPreviewingFlow = MutableStateFlow(false)
    override val isPreviewingFlow = _isPreviewingFlow.asStateFlow()

    private val streamMutex = Mutex()

    private var configuredResolution = Size(1920, 1080)
    private var previewProcessor: ISurfaceProcessorInternal? = null
    private var previewInputSurface: Surface? = null
    private var previewOutputSurface: SurfaceOutput? = null

    init {
        val deviceCameras = manager.cameras
        require(deviceCameras.contains(cameraId)) {
            "Camera $cameraId is not available. Available cameras: ${
                deviceCameras.joinToString(", ")
            }"
        }

        coroutineScope.launch {
            controller.isActiveFlow.collect { isAvailable ->
                if (!isAvailable) {
                    _isStreamingFlow.emit(false)
                    _isPreviewingFlow.emit(false)
                }
            }
        }
    }

    private fun defaultCaptureRequest(
        captureRequest: CaptureRequestWithTargetsBuilder
    ) {
        captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        captureRequest.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        captureRequest.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        captureRequest.set(CaptureRequest.CONTROL_AE_LOCK, false)
        captureRequest.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            captureRequest.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
        }
        captureRequest.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        captureRequest.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        captureRequest.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_FAST)
        captureRequest.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_FAST)
        captureRequest.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)

        if (settings.focus.availableAutoModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            captureRequest.set(
                CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
        } else if (settings.focus.availableAutoModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            captureRequest.set(
                CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }
    }

    override suspend fun hasPreview() = withContext(defaultDispatcher) {
        controller.hasOutput(PREVIEW_NAME)
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun setPreview(surface: Surface) {
        withContext(defaultDispatcher) {
            if (isPreviewingFlow.value) {
                Logger.w(TAG, "Trying to set preview while previewing")
            }
            val processor = previewProcessor ?: DefaultSurfaceProcessorFactory(VideoFilterRegistry.state)
                .create(io.github.thibaultbee.streampack.core.elements.utils.av.video.DynamicRangeProfile.sdr, DispatcherProvider())
                .also { previewProcessor = it }
            previewOutputSurface?.let { processor.removeOutputSurface(it) }
            previewInputSurface?.let { controller.removeOutput(PREVIEW_NAME); processor.removeInputSurface(it) }
            val input = processor.createInputSurface(configuredResolution, timebase)
            previewInputSurface = input
            controller.addOutput(CameraSurface(PREVIEW_NAME, input))
            val infoProvider = infoProviderFlow.value
            val previewInfoProvider = object : ISourceInfoProvider {
                override val rotationDegrees: Int get() = infoProvider.rotationDegrees
                override val isMirror: Boolean get() = infoProvider.isMirror
                override fun getRelativeRotationDegrees(targetRotation: Int, requiredMirroring: Boolean): Int =
                    infoProvider.getRelativeRotationDegrees(targetRotation, requiredMirroring)
                override fun getSurfaceSize(targetResolution: Size): Size = configuredResolution
            }
            val displaySize = currentDisplaySize()
            val output = SurfaceOutput(
                targetSurface = surface,
                targetResolution = displaySize,
                targetRotation = currentDisplayRotation(),
                isStreaming = { isPreviewingFlow.value },
                sourceResolution = configuredResolution,
                needMirroring = false,
                sourceInfoProvider = previewInfoProvider,
                viewportOffsetX = -(displaySize.width / 24),
            )
            previewOutputSurface = output
            processor.addOutputSurface(output)
        }
    }

    private fun currentDisplayRotation(): Int {
        @Suppress("DEPRECATION")
        return (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    }

    private fun currentDisplaySize(): Size {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        return if (width >= height) Size(width, height) else Size(height, width)
    }

    @SuppressLint("MissingPermission")
    override suspend fun resetPreviewImpl() {
        withContext(defaultDispatcher) {
            controller.removeOutput(PREVIEW_NAME)
            previewOutputSurface?.let { previewProcessor?.removeOutputSurface(it) }
            previewInputSurface?.let { previewProcessor?.removeInputSurface(it) }
            previewProcessor?.release()
            previewOutputSurface = null
            previewInputSurface = null
            previewProcessor = null
        }
    }

    override suspend fun getOutput() = controller.getOutput(STREAM_NAME)?.surface

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun setOutput(surface: Surface) {
        if (isStreamingFlow.value) {
            Logger.w(TAG, "Trying to set output while streaming")
        }
        controller.addOutput(CameraSurface(STREAM_NAME, surface))
    }

    @SuppressLint("MissingPermission")
    override suspend fun resetOutputImpl() {
        executeIfCameraPermission {
            controller.removeOutput(STREAM_NAME)
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun configure(config: VideoSourceConfig) {
        configuredResolution = config.resolution
        if (!settings.characteristics.isFpsSupported(config.fps)) {
            Logger.w(TAG, "Camera $cameraId does not support ${config.fps} fps")
        }

        try {
            controller.setDynamicRangeProfile(config.dynamicRangeProfile)
        } catch (t: Throwable) {
            Logger.w(TAG, "Failed to set dynamic range profile: ${t.message}")
        }
        try {
            controller.setFps(config.fps)
        } catch (t: Throwable) {
            Logger.w(TAG, "Failed to set fps: ${t.message}")
        }
    }


    override fun <T> getPreviewSize(targetSize: Size, targetClass: Class<T>): Size {
        return CameraSizes.getPreviewOutputSize(
            manager.getCameraCharacteristics(cameraId),
            targetSize,
            targetClass
        )
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun startPreview() {
        withContext(defaultDispatcher) {
            if (isPreviewingFlow.value) {
                Logger.w(TAG, "Camera is already previewing")
            }
            controller.addTarget(PREVIEW_NAME)
            _isPreviewingFlow.emit(true)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopPreview() {
        withContext(defaultDispatcher) {
            if (!isPreviewingFlow.value) {
                Logger.w(TAG, "Camera is not previewing")
                return@withContext
            }

            try {
                executeIfCameraPermission {
                    controller.removeTarget(PREVIEW_NAME)
                }
            } catch (t: Throwable) {
                Logger.w(TAG, "Failed to stop preview: $t")
            } finally {
                _isPreviewingFlow.emit(false)
            }
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun startStream() = streamMutex.withLock {
        if (isStreamingFlow.value) {
            Logger.w(TAG, "Camera is already streaming")
            return
        }
        controller.addTarget(STREAM_NAME)
        _isStreamingFlow.emit(true)
        controller.muteVibrationAndSound()
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopStream() = streamMutex.withLock {
        if (!isStreamingFlow.value) {
            Logger.w(TAG, "Camera is not streaming")
            return
        }

        try {
            controller.unmuteVibrationAndSound()
            executeIfCameraPermission {
                controller.removeTarget(STREAM_NAME)
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "Failed to stop stream: $t")
        } finally {
            _isStreamingFlow.emit(false)
        }
    }

    override suspend fun release() {
        controller.unmuteVibrationAndSound()
        controller.release()
    }

    private suspend fun executeIfCameraPermission(block: suspend () -> Unit) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            block()
        }
    }

    override fun toString(): String {
        return "CameraSource(id=$cameraId)"
    }

    companion object {
        private const val TAG = "CameraSource"

        private const val PREVIEW_NAME = "preview"
        private const val STREAM_NAME = "stream"
    }
}
