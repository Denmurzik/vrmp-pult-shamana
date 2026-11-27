package ru.nsu.vrmp.shaman.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import ru.nsu.vrmp.shaman.core.math.Vec3
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

internal data class CameraPreview(val result: CameraResult, val bitmap: Bitmap, val luma: Double)

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
internal class HandCamera(
    context: Context,
    private val owner: LifecycleOwner,
    private val onPreview: (CameraPreview) -> Unit,
    private val onError: () -> Unit,
) : AutoCloseable {
    private val app = context.applicationContext
    private val main = ContextCompat.getMainExecutor(app)
    private val worker = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean()
    private val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
    private var provider: ProcessCameraProvider? = null

    // MediaPipe and pending images belong to the single analysis worker.
    private var model: HandLandmarker? = null
    private var pending: PendingImage? = null

    @MainThread
    fun start() {
        worker.execute {
            if (closed.get()) return@execute
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setResultListener { result, returnedImage ->
                    returnedImage.close()
                    enqueueResult {
                        val points = result.landmarks().firstOrNull().orEmpty().map {
                            Vec3(it.x().toDouble(), it.y().toDouble(), it.z().toDouble())
                        }
                        deliver(result.timestampMs(), points)
                    }
                }
                .setErrorListener { fail() }
                .build()
            runCatching { HandLandmarker.createFromOptions(app, options) }
                .onSuccess { loaded ->
                    model = loaded
                    main.execute { bindCamera() }
                }
                .onFailure { fail() }
        }
    }

    @MainThread
    private fun bindCamera() {
        if (closed.get()) return
        val future = ProcessCameraProvider.getInstance(app)
        future.addListener({
            if (!closed.get()) {
                runCatching {
                    val cameraProvider = future.get()
                    val camera = cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                    provider = cameraProvider
                    val source = Camera2CameraInfo.from(camera.cameraInfo)
                        .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
                    val pipeline = CameraFramePipeline(source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)
                    analysis.setAnalyzer(worker) { capture(it, pipeline) }
                }.onFailure { fail() }
            }
        }, main)
    }

    private fun capture(image: ImageProxy, pipeline: CameraFramePipeline) {
        try {
            if (closed.get()) return
            val capture = pipeline.offer(image.imageInfo.timestamp, SystemClock.elapsedRealtimeNanos()) ?: return
            releasePending()
            val brightness = luma(image)
            val raw = image.toBitmap()
            val rotation = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            val bitmap = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, rotation, true)
            if (raw !== bitmap) raw.recycle()
            val input = BitmapImageBuilder(bitmap).build()
            pending = PendingImage(input, bitmap, brightness, pipeline)
            checkNotNull(model).detectAsync(input, capture.taskMillis)
        } catch (_: RuntimeException) {
            pipeline.cancelPending()
            releasePending()
            fail()
        } finally {
            image.close()
        }
    }

    private fun deliver(taskMillis: Long, points: List<Vec3>) {
        val image = pending ?: return
        val result = image.pipeline.result(taskMillis, points) ?: return
        val preview = try {
            // MediaPipe recycles its input bitmap on close. Compose owns this copy.
            val bitmap = checkNotNull(image.bitmap.copy(Bitmap.Config.ARGB_8888, false))
            CameraPreview(result, bitmap, image.luma)
        } catch (_: RuntimeException) {
            fail()
            return
        } finally {
            releasePending()
        }
        main.execute { if (!closed.get()) onPreview(preview) }
    }

    private fun releasePending() {
        val image = pending ?: return
        pending = null
        image.input.close()
    }

    private fun enqueueResult(action: () -> Unit) {
        if (closed.get()) return
        try {
            worker.execute { if (!closed.get()) action() }
        } catch (_: RejectedExecutionException) {
            // The route closed between the callback and submission.
        }
    }

    private fun luma(image: ImageProxy): Double {
        val plane = image.planes.first()
        val buffer = plane.buffer
        var sum = 0
        var count = 0
        for (row in 0 until image.height step 16) {
            for (column in 0 until image.width step 16) {
                val index = buffer.position() + row * plane.rowStride + column * plane.pixelStride
                if (index < buffer.limit()) {
                    sum += buffer.get(index).toInt() and 255
                    count++
                }
            }
        }
        return if (count == 0) 0.0 else sum.toDouble() / count
    }

    private fun fail() {
        main.execute { if (!closed.get()) onError() }
    }

    @MainThread
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        analysis.clearAnalyzer()
        provider?.unbind(analysis)
        worker.execute {
            try {
                model?.close()
            } finally {
                releasePending()
            }
        }
        worker.shutdown()
    }

    private data class PendingImage(
        val input: MPImage,
        val bitmap: Bitmap,
        val luma: Double,
        val pipeline: CameraFramePipeline,
    )
}
