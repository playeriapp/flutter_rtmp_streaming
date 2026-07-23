package com.app.rtmp_streaming

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.camera2.CameraAccessException
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import androidx.annotation.RequiresApi
import com.app.rtmp_streaming.CameraPermissions.ResolutionPreset
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.SpriteGestureController
import com.pedro.encoder.input.gl.render.filters.BasicDeformationFilterRender
import com.pedro.encoder.input.gl.render.filters.BeautyFilterRender
import com.pedro.encoder.input.gl.render.filters.BlackFilterRender
import com.pedro.encoder.input.gl.render.filters.BlurFilterRender
import com.pedro.encoder.input.gl.render.filters.BrightnessFilterRender
import com.pedro.encoder.input.gl.render.filters.CartoonFilterRender
import com.pedro.encoder.input.gl.render.filters.ChromaFilterRender
import com.pedro.encoder.input.gl.render.filters.ChromaticAberrationFilterRender
import com.pedro.encoder.input.gl.render.filters.CircleFilterRender
import com.pedro.encoder.input.gl.render.filters.ColorFilterRender
import com.pedro.encoder.input.gl.render.filters.ContrastFilterRender
import com.pedro.encoder.input.gl.render.filters.CropFilterRender
import com.pedro.encoder.input.gl.render.filters.DistortedTvFilterRender
import com.pedro.encoder.input.gl.render.filters.DuotoneFilterRender
import com.pedro.encoder.input.gl.render.filters.EarlyBirdFilterRender
import com.pedro.encoder.input.gl.render.filters.EdgeDetectionFilterRender
import com.pedro.encoder.input.gl.render.filters.ExposureFilterRender
import com.pedro.encoder.input.gl.render.filters.FireFilterRender
import com.pedro.encoder.input.gl.render.filters.GammaFilterRender
import com.pedro.encoder.input.gl.render.filters.GlitchFilterRender
import com.pedro.encoder.input.gl.render.filters.GreyScaleFilterRender
import com.pedro.encoder.input.gl.render.filters.HalftoneLinesFilterRender
import com.pedro.encoder.input.gl.render.filters.Image70sFilterRender
import com.pedro.encoder.input.gl.render.filters.LamoishFilterRender
import com.pedro.encoder.input.gl.render.filters.MoneyFilterRender
import com.pedro.encoder.input.gl.render.filters.NegativeFilterRender
import com.pedro.encoder.input.gl.render.filters.NoiseFilterRender
import com.pedro.encoder.input.gl.render.filters.PixelatedFilterRender
import com.pedro.encoder.input.gl.render.filters.PolygonizationFilterRender
import com.pedro.encoder.input.gl.render.filters.RGBSaturationFilterRender
import com.pedro.encoder.input.gl.render.filters.RainbowFilterRender
import com.pedro.encoder.input.gl.render.filters.RippleFilterRender
import com.pedro.encoder.input.gl.render.filters.RotationFilterRender
import com.pedro.encoder.input.gl.render.filters.SaturationFilterRender
import com.pedro.encoder.input.gl.render.filters.SepiaFilterRender
import com.pedro.encoder.input.gl.render.filters.SharpnessFilterRender
import com.pedro.encoder.input.gl.render.filters.SnowFilterRender
import com.pedro.encoder.input.gl.render.filters.TemperatureFilterRender
import com.pedro.encoder.input.gl.render.filters.ZebraFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.GifObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.input.video.CameraHelper.Facing.BACK
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.util.streamclient.RtmpStreamClient
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import com.pedro.library.view.OpenGlView
import com.pedro.library.util.BitrateAdapter
import java.io.*


class CameraNativeView(
    private var activity: Activity? = null,
    private var enableAudio: Boolean = false,
    private val preset: ResolutionPreset,
    private var cameraName: String,
    private var dartMessenger: DartMessenger? = null
) :
    PlatformView,
    SurfaceHolder.Callback,
    ConnectChecker {
    private val glView = OpenGlView(activity)
    private val rtmpCamera: RtmpCamera2
    private var isSurfaceCreated = false
    private var isSurfaceReady = false
    private var fps = 0
    private val aBitrate = 128 * 1000
    private val vBitrate = 1200 * 1000
    private val bitrateAdapter: BitrateAdapter
  val spriteGestureController = SpriteGestureController()
    /** 当前已设置的滤镜实例，removeFilter 必须用同一实例才能生效 */
    private var currentFilter: BaseFilterRender? = null
    private var currentFilterType: Int? = null

    /** Camera texture size currently opened by startPreview(). */
    private var activeCameraInputSize: Size? = null

    /** Automatic centered crop used when camera and encoder ratios differ. */
    private var automaticAspectCropFilter: CropFilterRender? = null
    /** RootEncoder 2.7.0+：下一帧编码使用 BT.709 色彩（在 prepare 前设置） */
    private var forceBt709Color: Boolean = false
    /** RootEncoder 2.7.0+：RTMP 周期 ping，用于 RTT（须在与 startStream 前对 RtmpStreamClient 设置） */
    private var rtmpShouldSendPings: Boolean = false
    /** 自定义音频码率（bps），在 prepareAudio 时使用 */
    private var customAudioBitrate: Int? = null
    /** 自定义视频帧率，在 prepareVideo / startPreview 时使用 */
    private var customVideoFps: Int? = null
    /** 自定义视频码率（bps），推流中可通过 setVideoBitrateOnFly 热更新 */
    private var customVideoBitrate: Int? = null
    /** 切后台前正在推流时，Surface 重建后自动恢复 */
    private var lastStreamUrl: String? = null
    private var lastStreamBitrate: Int? = null
    private var resumeStreamAfterSurfaceCreated = false
    /** 因 Surface 销毁暂停推流时，忽略 stopStream 触发的 onDisconnect */
    private var isRestoringFromSurfaceDestroy = false

    /** Main-thread gate for camera-preview and stream startup. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** True after startPreview() has been issued and until preview is ready. */
    private var previewStartRequested = false

    /** Prevents two RTMP start operations from preparing the encoder together. */
    private var streamStartInProgress = false

    /** Delayed readiness poll currently queued on the main looper. */
    private var pendingStreamStartRunnable: Runnable? = null

    /** Method-channel result waiting for native preview readiness. */
    private var pendingStreamStartResult: MethodChannel.Result? = null

    /** Guards delayed callbacks after PlatformView disposal. */
    private var isDisposed = false

    private val previewReadyTimeoutMs = 7000L
    private val previewPollIntervalMs = 100L

    init {
//        glView.isKeepAspectRatio = true
        glView.setAspectRatioMode(AspectRatioMode.Adjust)
        glView.holder.addCallback(this)
        rtmpCamera = RtmpCamera2(glView, this)
        rtmpCamera.streamClient.setReTries(10)
        rtmpCamera.setFpsListener { fps = it }
        bitrateAdapter = BitrateAdapter {
            rtmpCamera.setVideoBitrateOnFly(it)
        }.apply {
            setMaxBitrate(vBitrate + aBitrate)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
    Log.d("CameraNativeView", "surfaceCreated")
    // Do not start the camera here. The surface does not necessarily have its
    // final dimensions until surfaceChanged is called.
}

override fun surfaceChanged(
    holder: SurfaceHolder,
    format: Int,
    width: Int,
    height: Int
) {
    Log.d(
        "CameraNativeView",
        "surfaceChanged: ${width}x${height}, format=$format"
    )

    if (width <= 0 || height <= 0) {
        return
    }

    isSurfaceCreated = true

    // surfaceChanged can be called multiple times for the same surface.
    if (isSurfaceReady) {
        return
    }

    isSurfaceReady = true

    glView.post {
        if (isSurfaceCreated) {
            restorePreviewAfterSurfaceChange()
        }
    }
}

override fun surfaceDestroyed(holder: SurfaceHolder) {
    Log.d("CameraNativeView", "surfaceDestroyed")

    isSurfaceCreated = false
    isSurfaceReady = false
    previewStartRequested = false

    if (rtmpCamera.isStreaming) {
        resumeStreamAfterSurfaceCreated = true
        isRestoringFromSurfaceDestroy = true

        try {
            rtmpCamera.stopStream()
        } catch (e: Exception) {
            Log.e(
                "CameraNativeView",
                "stopStream on surfaceDestroyed failed",
                e
            )
            isRestoringFromSurfaceDestroy = false
            resumeStreamAfterSurfaceCreated = false
        }
    }

    if (rtmpCamera.isOnPreview) {
        try {
            rtmpCamera.stopCamera()
        } catch (e: Exception) {
            Log.e(
                "CameraNativeView",
                "stopCamera on surfaceDestroyed failed",
                e
            )
        }
    }
}

    override fun onConnectionStarted(url: String) {
        activity?.runOnUiThread {
            dartMessenger?.send(DartMessenger.EventType.WAIT, "connection wait")
        }
    }

    override fun onConnectionSuccess() {
        isRestoringFromSurfaceDestroy = false
        activity?.runOnUiThread {
            dartMessenger?.send(DartMessenger.EventType.SUCCESS, "connection success")
        }
    }

    override fun onNewBitrate(bitrate: Long) {
        bitrateAdapter.adaptBitrate(bitrate, rtmpCamera.getStreamClient().hasCongestion())
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onConnectionFailed(reason: String) {
        activity?.runOnUiThread { //Wait 5s and retry connect stream
            if (rtmpCamera.streamClient.reTry(5000, reason)) {
                dartMessenger?.send(DartMessenger.EventType.RTMP_RETRY, reason)
            } else {
                dartMessenger?.send(DartMessenger.EventType.RTMP_STOPPED, "Failed retry")
                isRestoringFromSurfaceDestroy = false
                rtmpCamera.stopStream()
            }
        }
    }

    override fun onDisconnect() {
        if (isRestoringFromSurfaceDestroy) {
            Log.d("CameraNativeView", "onDisconnect ignored during surface restore")
            return
        }
        activity?.runOnUiThread {
            dartMessenger?.sendCameraClosingEvent()
        }
    }

    override fun onAuthError() {
        activity?.runOnUiThread {
            dartMessenger?.send(DartMessenger.EventType.ERROR, "Auth error")
        }
    }

    override fun onAuthSuccess() {
    }

    private fun prepareAudioEncoder(): Boolean {
        if (!enableAudio) {
            return true
        }
        val bitrate = customAudioBitrate ?: aBitrate
        return rtmpCamera.prepareAudio(bitrate, 32000, true)
    }

    private fun prepareVideoEncoder(size: Size, bitrate: Int): Boolean {
        val fps = customVideoFps ?: 30
        val rotation = CameraHelper.getCameraOrientation(getActivity() ?: glView.context)

        Log.e(
            "PlayeriRTMP",
            "prepareVideo output=${size.width}x${size.height} " +
                "cameraInput=${activeCameraInputSize?.let { "${it.width}x${it.height}" } ?: "-"} " +
                "rotation=$rotation surface=${glView.width}x${glView.height}"
        )

        val prepared = rtmpCamera.prepareVideo(
            size.width,
            size.height,
            fps,
            bitrate,
            rotation
        )

        if (prepared) {
            configureAutomaticAspectCrop(
                activeCameraInputSize ?: size,
                size
            )

            Log.e(
                "PlayeriRTMP",
                "prepareVideo prepared=true " +
                    "stream=${rtmpCamera.streamWidth}x${rtmpCamera.streamHeight}"
            )
        }

        return prepared
    }

    /**
     * Center-crops the camera texture to the encoder aspect ratio.
     *
     * CropFilterRender arguments are percentages: x, y, width and height.
     */
    private fun configureAutomaticAspectCrop(
        cameraInput: Size,
        encoderOutput: Size
    ) {
        val gl = rtmpCamera.glInterface ?: return

        automaticAspectCropFilter?.let {
            try {
                gl.removeFilter(it)
            } catch (_: Exception) {
            }
        }
        automaticAspectCropFilter = null

        val sourceAspect =
            cameraInput.width.toDouble() / cameraInput.height.toDouble()
        val outputAspect =
            encoderOutput.width.toDouble() / encoderOutput.height.toDouble()

        if (kotlin.math.abs(sourceAspect - outputAspect) / outputAspect <= 0.01) {
            Log.e(
                "PlayeriRTMP",
                "aspectCrop not needed source=$sourceAspect output=$outputAspect"
            )
            return
        }

        val crop = CropFilterRender()

        if (sourceAspect < outputAspect) {
            val cropHeightPercent =
                (sourceAspect / outputAspect * 100.0).toFloat()
            val cropTopPercent = (100f - cropHeightPercent) / 2f

            crop.setCropArea(
                0f,
                cropTopPercent,
                100f,
                cropHeightPercent
            )

            Log.e(
                "PlayeriRTMP",
                "aspectCrop vertical top=$cropTopPercent " +
                    "height=$cropHeightPercent"
            )
        } else {
            val cropWidthPercent =
                (outputAspect / sourceAspect * 100.0).toFloat()
            val cropLeftPercent = (100f - cropWidthPercent) / 2f

            crop.setCropArea(
                cropLeftPercent,
                0f,
                cropWidthPercent,
                100f
            )

            Log.e(
                "PlayeriRTMP",
                "aspectCrop horizontal left=$cropLeftPercent " +
                    "width=$cropWidthPercent"
            )
        }

        gl.addFilter(0, crop)
        automaticAspectCropFilter = crop
    }

    fun prepareForVideoStreaming(result: MethodChannel.Result) {
        // Android 无需预准备音频，与 iOS 行为对齐为 no-op
        result.success(null)
    }

    fun getHasAudio(result: MethodChannel.Result) {
        result.success(!rtmpCamera.isAudioMuted)
    }

    fun setHasAudio(isEnable: Boolean?, result: MethodChannel.Result) {
        if (isEnable == null) {
            result.error("setHasAudio", "isEnable is required", null)
            return
        }
        try {
            if (isEnable) {
                rtmpCamera.enableAudio()
            } else {
                rtmpCamera.disableAudio()
            }
            result.success(null)
        } catch (e: Exception) {
            result.error("setHasAudio", e.message, null)
        }
    }

    fun getHasVideo(result: MethodChannel.Result) {
        val muted = rtmpCamera.glInterface?.isVideoMuted ?: false
        result.success(!muted)
    }

    fun setHasVideo(isEnable: Boolean?, result: MethodChannel.Result) {
        if (isEnable == null) {
            result.error("setHasVideo", "isEnable is required", null)
            return
        }
        try {
            val gl = rtmpCamera.glInterface
            if (gl == null) {
                result.error("setHasVideo", "OpenGL interface not available", null)
                return
            }
            if (isEnable) {
                gl.unMuteVideo()
            } else {
                gl.muteVideo()
            }
            result.success(null)
        } catch (e: Exception) {
            result.error("setHasVideo", e.message, null)
        }
    }

    fun setAudioSettings(bitrate: Int?, result: MethodChannel.Result) {
        if (bitrate == null) {
            result.error("setAudioSettings", "bitrate is required", null)
            return
        }
        customAudioBitrate = bitrate
        result.success(null)
    }

    fun setVideoSettings(
        bitrate: Int?,
        width: Int?,
        height: Int?,
        frameInterval: Int?,
        result: MethodChannel.Result
    ) {
        try {
            if (bitrate != null) {
                customVideoBitrate = bitrate
                if (rtmpCamera.isStreaming) {
                    rtmpCamera.setVideoBitrateOnFly(bitrate)
                }
            }
            if (frameInterval != null) {
                // RootEncoder 在推流中修改 I 帧间隔需重新 prepare，此处仅记录供文档说明
                Log.w("CameraNativeView", "setVideoSettings frameInterval ignored on Android during stream")
            }
            if (width != null && height != null && !rtmpCamera.isStreaming) {
                Log.w("CameraNativeView", "setVideoSettings width/height apply on next startVideoStreaming")
            }
            result.success(null)
        } catch (e: Exception) {
            result.error("setVideoSettings", e.message, null)
        }
    }

    fun setFrameRate(frameRate: Int?, result: MethodChannel.Result) {
        if (frameRate == null || frameRate <= 0) {
            result.error("setFrameRate", "frameRate must be > 0", null)
            return
        }
        customVideoFps = frameRate
        try {
            rtmpCamera.glInterface?.forceFpsLimit(frameRate)
            result.success(null)
        } catch (e: Exception) {
            result.error("setFrameRate", e.message, null)
        }
    }

    fun close() {
        Log.d("CameraNativeView", "close")
    }

    fun takePicture(filePath: String, result: MethodChannel.Result) {
        Log.d("CameraNativeView", "takePicture filePath: $filePath result: $result")
        val file: File = File(filePath)
        if (file.exists()) {
            result.error(
                "fileExists",
                "File at path '$filePath' already exists. Cannot overwrite.",
                null
            )
            return
        }
        glView.takePhoto {
            try {
                val outputStream: OutputStream = BufferedOutputStream(FileOutputStream(file))
                it.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                outputStream.close()
                view.post { result.success(null) }
            } catch (e: IOException) {
                result.error("IOError", "Failed saving image", null)
            }
        }
    }

    fun startVideoRecording(filePath: String?, result: MethodChannel.Result) {
        if (filePath == null) {
            result.error("fileExists", "Must specify a filePath.", null)
            return
        }

        val file = File(filePath)
        if (file.exists()) {
            result.error(
                "fileExists",
                "File at path '$filePath' already exists. Cannot overwrite.",
                null
            )
            return
        }
        Log.d("CameraNativeView", "startVideoRecording filePath: $filePath result: $result")


        /*if (rtmpCamera.isRecording || rtmpCamera.prepareAudio() && rtmpCamera.prepareVideo(
                streamingSize.videoFrameWidth,
                streamingSize.videoFrameHeight,
                streamingSize.videoBitRate
            )*/
        //判断如果不是视频流的话并且其用了音频
        try {
            if (!rtmpCamera.isStreaming) {
                val streamingSize = CameraUtils.computeBestPreviewSize(activity, cameraName, preset)
                val size = streamingSize["size"] as Size
                val bitrateRes = streamingSize["bitrate"] as Int
                rtmpCamera.forceBt709Color(forceBt709Color)
                if (prepareAudioEncoder() && prepareVideoEncoder(
                        size,
                        bitrateRes
                    )
                ) {
                    rtmpCamera.startRecord(filePath)
                }

            } else {
                rtmpCamera.startRecord(filePath)
            }
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("videoRecordingFailed", e.message, null)
        } catch (e: IOException) {
            result.error("videoRecordingFailed", e.message, null)
        }

    }


    fun startVideoStreaming(
        url: String?,
        bitrate: Int?,
        result: MethodChannel.Result
    ) {
        Log.d("CameraNativeView", "startVideoStreaming url: $url")

        if (url == null) {
            result.error(
                "startVideoStreaming",
                "Must specify a url.",
                null
            )
            return
        }

        if (rtmpCamera.isStreaming) {
            Log.d(
                "CameraNativeView",
                "startVideoStreaming ignored because stream is already active"
            )
            result.success(null)
            return
        }

        if (streamStartInProgress) {
            result.error(
                "videoStreamingFailed",
                "A streaming start is already in progress.",
                null
            )
            return
        }

        streamStartInProgress = true
        pendingStreamStartResult = result
        lastStreamUrl = url
        lastStreamBitrate = bitrate

        val deadlineMs =
            SystemClock.uptimeMillis() + previewReadyTimeoutMs

        waitForPreviewThenStartStream(
            url,
            bitrate,
            deadlineMs
        )
    }

    /**
     * Waits for Camera2/RootEncoder preview readiness before preparing the
     * encoder. Flutter frames only prove that the AndroidView exists; they do
     * not prove that the native camera has finished opening.
     */
    private fun waitForPreviewThenStartStream(
        url: String,
        bitrate: Int?,
        deadlineMs: Long
    ) {
        if (isDisposed) {
            finishPendingStreamStartError(
                "videoStreamingFailed",
                "Camera view was disposed before streaming could start."
            )
            return
        }

        if (!isSurfaceCreated || !isSurfaceReady) {
            scheduleStreamStartRetry(
                url,
                bitrate,
                deadlineMs,
                "surface not ready"
            )
            return
        }

        if (!rtmpCamera.isOnPreview) {
            if (!previewStartRequested) {
                val issued = startPreview(cameraName)

                if (!issued) {
                    scheduleStreamStartRetry(
                        url,
                        bitrate,
                        deadlineMs,
                        "preview start not issued"
                    )
                    return
                }
            }

            scheduleStreamStartRetry(
                url,
                bitrate,
                deadlineMs,
                "camera preview opening"
            )
            return
        }

        previewStartRequested = false
        pendingStreamStartRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        pendingStreamStartRunnable = null

        try {
            val streamingSize =
                CameraUtils.computeBestPreviewSize(
                    getActivity(),
                    cameraName,
                    preset
                )

            val size = streamingSize["size"] as Size
            val bitrateRes =
                customVideoBitrate
                    ?: bitrate
                    ?: (streamingSize["bitrate"] as Int)

            rtmpCamera.forceBt709Color(forceBt709Color)

            (rtmpCamera.streamClient as? RtmpStreamClient)
                ?.shouldSendPings(rtmpShouldSendPings)

            val prepared =
                rtmpCamera.isRecording ||
                    (
                        prepareAudioEncoder() &&
                            prepareVideoEncoder(
                                size,
                                bitrateRes
                            )
                        )

            if (!prepared) {
                finishPendingStreamStartError(
                    "videoStreamingFailed",
                    "Error preparing stream. This device cannot do it."
                )
                return
            }

            Log.e(
                "PlayeriRTMP",
                "native preview ready; starting RTMP stream"
            )

            rtmpCamera.startStream(url)
            finishPendingStreamStartSuccess()
        } catch (e: Exception) {
            Log.e(
                "CameraNativeView",
                "start stream after preview readiness failed",
                e
            )

            finishPendingStreamStartError(
                "videoStreamingFailed",
                e.message ?: "Failed to start video streaming."
            )
        }
    }

    private fun scheduleStreamStartRetry(
        url: String,
        bitrate: Int?,
        deadlineMs: Long,
        reason: String
    ) {
        if (SystemClock.uptimeMillis() >= deadlineMs) {
            finishPendingStreamStartError(
                "videoStreamingFailed",
                "Camera preview did not become ready in time."
            )
            return
        }

        if (pendingStreamStartRunnable != null) {
            return
        }

        Log.d(
            "PlayeriRTMP",
            "waiting for preview before stream: $reason"
        )

        val runnable = Runnable {
            pendingStreamStartRunnable = null

            waitForPreviewThenStartStream(
                url,
                bitrate,
                deadlineMs
            )
        }

        pendingStreamStartRunnable = runnable

        mainHandler.postDelayed(
            runnable,
            previewPollIntervalMs
        )
    }

    private fun finishPendingStreamStartSuccess() {
        pendingStreamStartRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        pendingStreamStartRunnable = null

        val result = pendingStreamStartResult
        pendingStreamStartResult = null
        streamStartInProgress = false

        result?.success(null)
    }

    private fun finishPendingStreamStartError(
        code: String,
        message: String
    ) {
        pendingStreamStartRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        pendingStreamStartRunnable = null

        val result = pendingStreamStartResult
        pendingStreamStartResult = null
        streamStartInProgress = false

        result?.error(
            code,
            message,
            null
        )
    }

    private fun cancelPendingStreamStart(
        message: String
    ) {
        if (
            !streamStartInProgress &&
            pendingStreamStartResult == null &&
            pendingStreamStartRunnable == null
        ) {
            return
        }

        finishPendingStreamStartError(
            "videoStreamingFailed",
            message
        )
    }

    fun startVideoRecordingAndStreaming(
        filePath: String?,
        url: String?,
        bitrate: Int?,
        result: MethodChannel.Result
    ) {
        if (filePath == null) {
            result.error("fileExists", "Must specify a filePath.", null)
            return
        }
        if (File(filePath).exists()) {
            result.error("fileExists", "File at path '$filePath' already exists.", null)
            return
        }
        if (url == null) {
            result.error("fileExists", "Must specify a url.", null)
            return
        }
        try {
            startVideoRecording(filePath, result)
            startVideoStreaming(url, bitrate, result)
          result.success(null)
        } catch (e: CameraAccessException) {
            result.error("videoRecordingFailed", e.message, null)
        } catch (e: IOException) {
            result.error("videoRecordingFailed", e.message, null)
        }
    }


    //开/关闪光灯
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun switchFlashLight(isEnable: Boolean?, result: MethodChannel.Result) {
        try {
            if(rtmpCamera.cameraFacing != BACK){
                result.error("switchFlashLightFailed", "camera is Not BACK", null)
                return
            }
             if (isEnable == null) {
                result.error("switchFlashLightFailed", "isEnable not empty.", null)
                return
            }
            if(isEnable == true){
                 rtmpCamera.enableLantern()
            }else{
                rtmpCamera.disableLantern()
            }
          result.success(null)
        } catch (e: CameraAccessException) {
            result.error("switchFlashLightFailed", e.message, null)
        } catch (e: IOException) {
            result.error("switchFlashLightFailed", e.message, null)
        }
    }

    //切换相机式
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun switchCamera(cameraId: String?, result: MethodChannel.Result) {

        try {
          if (cameraId == null) {
            result.error("cameraIdExist", "empty cameraId!", null)
            return
          }
          rtmpCamera.switchCamera(cameraId)
          cameraName = cameraId
          result.success(null)
        } catch (e: CameraAccessException) {
            result.error("switchCameraFailed", e.message, null)
        } catch (e: IOException) {
            result.error("switchCameraFailed", e.message, null)
        }


    }

    //开/关声音
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun switchAudio(isEnable: Boolean?,result: MethodChannel.Result) {
        try {
            if (isEnable == null) {
                result.error("switchAudioFailed", "empty isEnable!", null)
                return
            }
            if(isEnable == true){
                rtmpCamera.enableAudio()
            }else{
                rtmpCamera.disableAudio()
            }
          result.success(null)
        } catch (e: CameraAccessException) {
            result.error("switchAudioFailed", e.message, null)
        } catch (e: IOException) {
            result.error("switchAudioFailed", e.message, null)
        }
    }

    //设置滤镜
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun setFilter(type: Int?,filePath: String?, result: MethodChannel.Result) {
        try {
          if(type == null){
            result.error("setFilter", "type is empty", null)
            return
          }
            spriteGestureController.stopListener()
          when (type) {
            0 -> {
              val f = BasicDeformationFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            1 -> {
              val f = BeautyFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            2 -> {
              val f = BlackFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            3 -> {
              val f = BlurFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            4 -> {
              val f = BrightnessFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            5 -> {
              val f = CartoonFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            6 -> {
              if (filePath == null) {
                result.error("setFilter", "filePath Not Empty", null)
                return
              }
              val chromaFilterRender = ChromaFilterRender()
              rtmpCamera.glInterface?.setFilter(chromaFilterRender)
              chromaFilterRender.setImage(
                BitmapFactory.decodeFile(filePath)
              )
              currentFilter = chromaFilterRender
              currentFilterType = type
              result.success(null)
            }
            7 -> {
              val f = ChromaticAberrationFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            8 -> {
              val f = CircleFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            9 -> {
              val f = ColorFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            10 -> {
              val f = ContrastFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            11 -> {
              val f = CropFilterRender().apply {
                //crop center of the image with 40% of width and 40% of height
                setCropArea(30f, 30f, 40f, 40f)
              }
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            12 -> {
              val f = DistortedTvFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            13 -> {
              val f = DuotoneFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            14 -> {
              val f = EarlyBirdFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            15 -> {
              val f = EdgeDetectionFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            43 -> {
              val f = EdgeDetectionFilterRender(false)
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            16 -> {
              val f = ExposureFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            17 -> {
              val f = FireFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            18 -> {
              val f = GammaFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            19 -> {
              val f = GlitchFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            20 -> {
              if (filePath == null) {
                result.error("setFilter", "filePath Not Empty", null)
                return
              }
              val file = File(filePath)
              val inputStream = FileInputStream(file)
              val gifObjectFilterRender = GifObjectFilterRender()
              gifObjectFilterRender.setGif(inputStream)
              rtmpCamera.glInterface?.setFilter(gifObjectFilterRender)
              gifObjectFilterRender.setScale(50f, 50f)
              gifObjectFilterRender.setPosition(TranslateTo.BOTTOM)
              spriteGestureController.setBaseObjectFilterRender(gifObjectFilterRender)
              currentFilter = gifObjectFilterRender
              currentFilterType = type
              result.success(null)
            }
            21 -> {
              val f = GreyScaleFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            22 -> {
              val f = HalftoneLinesFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            23 -> {
              if (filePath == null) {
                result.error("setFilter", "filePath Not Empty", null)
                return
              }
              val imageObjectFilterRender = ImageObjectFilterRender()
              rtmpCamera.glInterface?.setFilter(imageObjectFilterRender)
              imageObjectFilterRender.setImage(
                BitmapFactory.decodeFile(filePath)
              )
              imageObjectFilterRender.setScale(50f, 50f)
              imageObjectFilterRender.setPosition(TranslateTo.RIGHT)
              spriteGestureController.setBaseObjectFilterRender(imageObjectFilterRender) //Optional
              spriteGestureController.setPreventMoveOutside(false)
              currentFilter = imageObjectFilterRender
              currentFilterType = type
              result.success(null)
            }
            24 -> {
              val f = Image70sFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            25 -> {
              val f = LamoishFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            26 -> {
              val f = MoneyFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            27 -> {
              val f = NegativeFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            28 -> {
              val f = NoiseFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            29 -> {
              val f = PixelatedFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            30 -> {
              val f = PolygonizationFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            31 -> {
              val f = RainbowFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            32 -> {
              val rgbSaturationFilterRender = RGBSaturationFilterRender()
              rtmpCamera.glInterface?.setFilter(rgbSaturationFilterRender)
              rgbSaturationFilterRender.setRGBSaturation(1f, 0.8f, 0.8f)
              currentFilter = rgbSaturationFilterRender
              currentFilterType = type
              result.success(null)
            }
            33 -> {
              val f = RippleFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            34 -> {
              val rotationFilterRender = RotationFilterRender()
              rtmpCamera.glInterface?.setFilter(rotationFilterRender)
              rotationFilterRender.rotation = 90
              currentFilter = rotationFilterRender
              currentFilterType = type
              result.success(null)
            }
            35 -> {
              val f = SaturationFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            36 -> {
              val f = SepiaFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            37 -> {
              val f = SharpnessFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            38-> {
              val f = SnowFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            39-> {
              if (filePath == null) {
                result.error("setFilter", "filePath Not Empty", null)
                return
              }
              val surfaceFilterRender =
                SurfaceFilterRender { surfaceTexture -> //You can render this filter with other api that draw in a surface. for example you can use VLC
                  val mediaPlayer = MediaPlayer()
                  mediaPlayer.setDataSource(filePath)
                  mediaPlayer.setSurface(Surface(surfaceTexture))
                  mediaPlayer.start()
                }
              rtmpCamera.glInterface?.setFilter(surfaceFilterRender)
              surfaceFilterRender.setScale(50f, 33.3f)
              spriteGestureController.setBaseObjectFilterRender(surfaceFilterRender)
              currentFilter = surfaceFilterRender
              currentFilterType = type
              result.success(null)
            }
            40 -> {
              val f = TemperatureFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            41 -> {
              val textObjectFilterRender = TextObjectFilterRender()
              rtmpCamera.glInterface?.setFilter(textObjectFilterRender)
              textObjectFilterRender.setText("Hello world", 22f, Color.RED)
              textObjectFilterRender.setScale(50f, 50f)
              textObjectFilterRender.setPosition(TranslateTo.CENTER)
              spriteGestureController.setBaseObjectFilterRender(textObjectFilterRender) //Optional
              currentFilter = textObjectFilterRender
              currentFilterType = type
              result.success(null)
            }
            42 -> {
              val f = ZebraFilterRender()
              rtmpCamera.glInterface?.setFilter(f)
              currentFilter = f
              currentFilterType = type
              result.success(null)
            }
            else -> {
              result.success(null)
            }
          }

        } catch (e: CameraAccessException) {
          result.error("setFilter", e.message, null)
        } catch (e: IOException) {
          result.error("setFilter", e.message, null)
        }
    }

    //移除滤镜：必须使用 setFilter 时缓存的同一滤镜实例，底层按对象引用比较
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun removeFilter(type: Int?, result: MethodChannel.Result) {
        try {
          if (type == null) {
            result.error("removeFilter", "type is empty", null)
            return
          }
          spriteGestureController.stopListener()
          val filterToRemove = currentFilter
          val filterType = currentFilterType
          if (filterToRemove != null && filterType == type) {
            rtmpCamera.glInterface?.removeFilter(filterToRemove)
            currentFilter = null
            currentFilterType = null
          }
          result.success(null)
        } catch (e: CameraAccessException) {
          result.error("removeFilter", e.message, null)
        } catch (e: IOException) {
          result.error("removeFilter", e.message, null)
        }
    }

    fun stopVideoRecordingOrStreaming(result: MethodChannel.Result) {
        cancelPendingStreamStart("Streaming start was cancelled.")
        try {
            resumeStreamAfterSurfaceCreated = false
            isRestoringFromSurfaceDestroy = false
            lastStreamUrl = null
            lastStreamBitrate = null
            rtmpCamera.apply {
                if (isStreaming) stopStream()
                if (isRecording) stopRecord()
            }
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("videoRecordingFailed", e.message, null)
        } catch (e: IllegalStateException) {
            result.error("videoRecordingFailed", e.message, null)
        }
    }

    fun stopVideoRecording(result: MethodChannel.Result) {
        try {
            rtmpCamera.apply {
                if (isRecording) stopRecord()
            }
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("stopVideoRecordingFailed", e.message, null)
        } catch (e: IllegalStateException) {
            result.error("stopVideoRecordingFailed", e.message, null)
        }
    }

    fun stopVideoStreaming(result: MethodChannel.Result) {
        cancelPendingStreamStart("Streaming start was cancelled.")
        try {
            resumeStreamAfterSurfaceCreated = false
            isRestoringFromSurfaceDestroy = false
            lastStreamUrl = null
            lastStreamBitrate = null
            rtmpCamera.apply { 
                if (isStreaming) stopStream()
            }
            result.success(null)
        } catch (e: CameraAccessException) {
            result.error("stopVideoStreamingFailed", e.message, null)
        } catch (e: IllegalStateException) {
            result.error("stopVideoStreamingFailed", e.message, null)
        }
    }

    fun pauseVideoRecording(result: MethodChannel.Result) {
        try {
            if (!rtmpCamera.isRecording) {
                result.error("pauseVideoRecording", "没有正在录制的视频", null)
                return
            }
            rtmpCamera.pauseRecord();
          result.success(null)
        } catch (e: CameraAccessException) {
            result.error("pauseVideoRecording", e.message, null)
            return
        } catch (e: IllegalStateException) {
            result.error("pauseVideoRecording", e.message, null)
            return
        }

    }

    fun resumeVideoRecording(result: MethodChannel.Result) {
        try {
            if (!rtmpCamera.isRecording) {
                result.error("resumeVideoRecording", "没有正在录制的视频", null)
                return
            }
            rtmpCamera.resumeRecord()
          result.success(null)
        } catch (e: CameraAccessException) {
            result.error("resumeVideoRecording", e.message, null)
            return
        } catch (e: IllegalStateException) {
            result.error("resumeVideoRecording", e.message, null)
            return
        }

    }

    fun startPreview(cameraNameArg: String? = null): Boolean {
        val targetCamera = if (cameraNameArg.isNullOrEmpty()) {
            cameraName
        } else {
            cameraNameArg
        }

        cameraName = targetCamera

        if (isDisposed) {
            return false
        }

        Log.d(
            "CameraNativeView",
            "startPreview: preset=$preset camera=$targetCamera " +
                "surface=${glView.width}x${glView.height}"
        )

        if (!isSurfaceCreated || !isSurfaceReady) {
            Log.d(
                "CameraNativeView",
                "startPreview skipped: surface not ready"
            )
            return false
        }

        if (rtmpCamera.isOnPreview) {
            previewStartRequested = false
            return true
        }

        if (previewStartRequested) {
            Log.d(
                "CameraNativeView",
                "startPreview already requested; waiting for Camera2"
            )
            return true
        }

        previewStartRequested = true

        return try {
            val size =
                CameraUtils.computeBestCameraInputSize(
                    getActivity(),
                    cameraName,
                    preset
                )

            activeCameraInputSize = size

            Log.e(
                "PlayeriRTMP",
                "starting camera input=${size.width}x${size.height}, " +
                    "preset=$preset surface=${glView.width}x${glView.height}"
            )

            rtmpCamera.startPreview(
                targetCamera,
                size.width,
                size.height
            )

            true
        } catch (e: CameraAccessException) {
            previewStartRequested = false
            close()

            getActivity()?.runOnUiThread {
                dartMessenger?.send(
                    DartMessenger.EventType.ERROR,
                    "CameraAccessException"
                )
            }

            false
        } catch (e: Exception) {
            previewStartRequested = false

            Log.e(
                "CameraNativeView",
                "startPreview failed",
                e
            )

            getActivity()?.runOnUiThread {
                dartMessenger?.send(
                    DartMessenger.EventType.ERROR,
                    e.message ?: "startPreview failed"
                )
            }

            false
        }
    }

    private fun restorePreviewAfterSurfaceChange() {
        if (
            isDisposed ||
            !isSurfaceCreated ||
            !isSurfaceReady
        ) {
            return
        }

        if (
            resumeStreamAfterSurfaceCreated &&
            lastStreamUrl != null
        ) {
            resumeStreamAfterSurfaceChange()
            return
        }

        // Never stop/reopen a preview while the RTMP start gate is waiting for
        // that same preview. Doing so creates the intermittent startup race.
        if (rtmpCamera.isOnPreview) {
            previewStartRequested = false
            return
        }

        startPreview(cameraName)
    }

    private fun resumeStreamAfterSurfaceChange() {
        val url = lastStreamUrl ?: run {
            resumeStreamAfterSurfaceCreated = false
            isRestoringFromSurfaceDestroy = false
            return
        }
        resumeStreamAfterSurfaceCreated = false
        previewStartRequested = false
        try {
            if (rtmpCamera.isOnPreview) {
                rtmpCamera.stopCamera()
            }
            val streamingSize = CameraUtils.computeBestPreviewSize(getActivity(), cameraName, preset)
            val size = streamingSize["size"] as Size
            val bitrateRes = lastStreamBitrate ?: customVideoBitrate ?: (streamingSize["bitrate"] as Int)
            rtmpCamera.forceBt709Color(forceBt709Color)
            (rtmpCamera.streamClient as? RtmpStreamClient)?.shouldSendPings(rtmpShouldSendPings)
            val prepared = prepareAudioEncoder() && prepareVideoEncoder(size, bitrateRes)
            if (rtmpCamera.isRecording || prepared) {
                Log.d("CameraNativeView", "resumeStreamAfterSurfaceChange: $url")
                rtmpCamera.startStream(url)
            } else {
                isRestoringFromSurfaceDestroy = false
                getActivity()?.runOnUiThread {
                    dartMessenger?.send(
                        DartMessenger.EventType.RTMP_STOPPED,
                        "Failed to resume stream after background"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("CameraNativeView", "resumeStreamAfterSurfaceChange failed", e)
            isRestoringFromSurfaceDestroy = false
            getActivity()?.runOnUiThread {
                dartMessenger?.send(
                    DartMessenger.EventType.RTMP_STOPPED,
                    e.message ?: "Failed to resume stream after background"
                )
            }
        }
    }

    fun getStreamStatistics(result: MethodChannel.Result) {
        val ret = hashMapOf<String, Any>()
        ret["cacheSize"] = rtmpCamera.streamClient.getCacheSize()
        ret["sentAudioFrames"] = rtmpCamera.streamClient.getSentAudioFrames()
        ret["sentVideoFrames"] = rtmpCamera.streamClient.getSentVideoFrames()
        ret["droppedAudioFrames"] = rtmpCamera.streamClient.getDroppedAudioFrames()
        ret["droppedVideoFrames"] = rtmpCamera.streamClient.getDroppedVideoFrames()
        ret["bytesSend"] = rtmpCamera.streamClient.getBytesSend()
        ret["isAudioMuted"] = rtmpCamera.isAudioMuted
        ret["isVideoMuted"] = rtmpCamera.glInterface?.isVideoMuted ?: false
        ret["bitrate"] = rtmpCamera.bitrate
        ret["width"] = rtmpCamera.streamWidth
        ret["height"] = rtmpCamera.streamHeight
        ret["fps"] = fps
        val rtmpSc = rtmpCamera.streamClient as? RtmpStreamClient
        ret["rttMicros"] = rtmpSc?.getRtt() ?: 0
        result.success(ret)
    }

    fun setForceBt709Color(enabled: Boolean?, result: MethodChannel.Result) {
        if (enabled == null) {
            result.error("setForceBt709Color", "enabled is required", null)
            return
        }
        forceBt709Color = enabled
        try {
            rtmpCamera.forceBt709Color(enabled)
            result.success(null)
        } catch (e: Exception) {
            result.error("setForceBt709Color", e.message, null)
        }
    }

    fun setRtmpShouldSendPings(enabled: Boolean?, result: MethodChannel.Result) {
        if (enabled == null) {
            result.error("setRtmpShouldSendPings", "enabled is required", null)
            return
        }
        rtmpShouldSendPings = enabled
        result.success(null)
    }

    override fun getView(): View {
        return glView
    }

    override fun dispose() {
        isDisposed = true
        cancelPendingStreamStart(
            "Camera view was disposed before streaming could start."
        )

        mainHandler.removeCallbacksAndMessages(null)

        isSurfaceCreated = false
        isSurfaceReady = false
        previewStartRequested = false
        resumeStreamAfterSurfaceCreated = false
        isRestoringFromSurfaceDestroy = false
        lastStreamUrl = null
        lastStreamBitrate = null

        if (rtmpCamera.isOnPreview) {
            try {
                rtmpCamera.stopCamera()
            } catch (e: Exception) {
                Log.e(
                    "CameraNativeView",
                    "stopCamera during dispose failed",
                    e
                )
            }
        }

        automaticAspectCropFilter = null
        activeCameraInputSize = null
        activity = null
    }

    /** Activity 在 surfaceDestroyed 后仍有效；若引用丢失则用 glView 的 Context 兜底。 */
    private fun getActivity(): Activity? = activity ?: glView.context as? Activity
}
