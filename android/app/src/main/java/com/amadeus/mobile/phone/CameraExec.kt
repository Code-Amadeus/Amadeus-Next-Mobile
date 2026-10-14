package com.amadeus.mobile.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 摄像头：绑定到前台服务的生命周期。系统不允许时（权限、后台限制）如实返回 camera_denied。
 * 产物存到控制面工作区的 media/，模型可以之后用 read 再看。
 */
class CameraExec(private val context: Context, private val owner: LifecycleOwner, private val workspace: File) {
    private val main = ContextCompat.getMainExecutor(context)

    class Photo(val jpeg: ByteArray, val path: String)

    private fun selector(facing: String?) =
        if (facing == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private fun requirePermission(permission: String) {
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            throw VerbException("camera_denied", "permission $permission is not granted; ask the user to allow it in Amadeus settings")
        }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())

    private fun <T> onMain(block: () -> T): T {
        val future = CompletableFuture<T>()
        main.execute { runCatching(block).fold(future::complete, future::completeExceptionally) }
        return future.get(10, TimeUnit.SECONDS)
    }

    fun snap(facing: String?, maxWidth: Int): Photo {
        requirePermission(Manifest.permission.CAMERA)
        val provider = ProcessCameraProvider.getInstance(context).get(10, TimeUnit.SECONDS)
        val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        try {
            onMain { provider.unbindAll(); provider.bindToLifecycle(owner, selector(facing), capture) }
        } catch (e: Exception) {
            throw VerbException("camera_denied", "camera unavailable: ${e.cause?.message ?: e.message}")
        }
        try {
            val future = CompletableFuture<Bitmap>()
            capture.takePicture(main, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val raw = image.toBitmap()
                    image.close()
                    future.complete(if (rotation == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true))
                }

                override fun onError(exception: ImageCaptureException) {
                    future.completeExceptionally(VerbException("camera_denied", "capture failed: ${exception.message}"))
                }
            })
            val full = try { future.get(15, TimeUnit.SECONDS) } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
            val scaled = if (full.width > maxWidth) Bitmap.createScaledBitmap(full, maxWidth, full.height * maxWidth / full.width, true) else full
            val jpeg = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            val relative = "media/photo-${stamp()}.jpg"
            File(workspace, relative).apply { parentFile!!.mkdirs() }.writeBytes(jpeg)
            return Photo(jpeg, relative)
        } finally {
            onMain { provider.unbindAll() }
        }
    }

    @SuppressLint("MissingPermission")
    fun clip(facing: String?, durationMs: Long, includeAudio: Boolean): String {
        requirePermission(Manifest.permission.CAMERA)
        val withAudio = includeAudio && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val provider = ProcessCameraProvider.getInstance(context).get(10, TimeUnit.SECONDS)
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
        val capture = VideoCapture.withOutput(recorder)
        try {
            onMain { provider.unbindAll(); provider.bindToLifecycle(owner, selector(facing), capture) }
        } catch (e: Exception) {
            throw VerbException("camera_denied", "camera unavailable: ${e.cause?.message ?: e.message}")
        }
        val relative = "media/clip-${stamp()}.mp4"
        val file = File(workspace, relative).apply { parentFile!!.mkdirs() }
        val finished = CompletableFuture<VideoRecordEvent.Finalize>()
        try {
            val recording = onMain {
                capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
                    .apply { if (withAudio) withAudioEnabled() }
                    .start(main) { event -> if (event is VideoRecordEvent.Finalize) finished.complete(event) }
            }
            Thread.sleep(durationMs)
            onMain { recording.stop() }
            val result = finished.get(15, TimeUnit.SECONDS)
            if (result.hasError()) throw VerbException("camera_denied", "recording failed (${result.error})")
            return relative
        } finally {
            onMain { provider.unbindAll() }
        }
    }
}
