package com.edith.rokid.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import com.edith.rokid.tracking.FaceBox
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * グラス本体のカメラを取得する層（edith_mobile/src/camera/cameraSource.ts の CameraSource に相当）。
 *
 * RokidAIAssistant(https://github.com/zero2005x/RokidAIAssistant)の実績に基づき、
 * Rokid公式SDK(CXR-S)は使わず、**標準のandroid.hardware.camera2 API**でグラスのカメラに直接アクセスする
 * （グラス本体はAndroid 12ベースの通常機のため）。カメラIDは
 * `LENS_FACING_EXTERNAL`(グラス本体のARカメラ) → `LENS_FACING_BACK` → `LENS_FACING_FRONT` の優先順位で選ぶ。
 *
 * プレビュー表示用の[TextureView]と、顔検出・識別用の連続フレーム取得を同じCameraCaptureSessionの
 * 2つの出力先（プレビューSurface＋YUV ImageReader）として同時に流す。「現在のフレーム」は
 * 都度の静止画撮影ではなく、このYUVストリームの最新フレームをJPEG化して使う
 * （edith_mobileがcanvasで`<video>`の現在のフレームを取得するのと同じ考え方）。
 */
class GlassesCameraSource(
    private val context: Context,
    private val textureView: TextureView,
    private val previewWidth: Int = 1280,
    private val previewHeight: Int = 720,
) {
    val frameWidth: Int get() = previewWidth
    val frameHeight: Int get() = previewHeight

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    @Volatile
    private var latestJpeg: ByteArray? = null

    @Volatile
    private var lastConvertAtMs: Long = 0L

    val isLive: Boolean get() = cameraDevice != null && captureSession != null

    fun startBackgroundThread() {
        val thread = HandlerThread("edith-camera").also { it.start() }
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }

    /** グラスのカメラ(external優先)を開き、プレビュー＋解析ストリームを開始する。 */
    suspend fun open() {
        val cameraId = selectCameraId()
            ?: throw IllegalStateException("利用可能なカメラがありません")

        val reader = ImageReader.newInstance(previewWidth, previewHeight, ImageFormat.YUV_420_888, 2)
        reader.setOnImageAvailableListener({ r ->
            // カメラは~30fpsでフレームを出し続けるが、実際にJPEGが必要なのは検出/識別ループ
            // (最短でも200ms間隔)だけなので、変換自体をその頻度に間引く。
            // 毎フレームYUV->NV21->JPEG変換すると発熱の主因になる。
            r.acquireLatestImage()?.use { image ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastConvertAtMs >= CONVERT_INTERVAL_MS) {
                    lastConvertAtMs = now
                    latestJpeg = imageToJpeg(image)
                }
            }
        }, backgroundHandler)
        imageReader = reader

        val device = openCameraDevice(cameraId)
        cameraDevice = device

        val texture = textureView.surfaceTexture
            ?: throw IllegalStateException("SurfaceTextureが準備できていません")
        texture.setDefaultBufferSize(previewWidth, previewHeight)
        val previewSurface = Surface(texture)

        captureSession = createCaptureSession(device, previewSurface, reader.surface)
    }

    private fun selectCameraId(): String? {
        val ids = cameraManager.cameraIdList
        fun idsWithFacing(facing: Int) = ids.filter { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        }
        // Rokidグラスでは「external」がグラス本体のARカメラであることが多い（RokidAIAssistant参考）。
        return idsWithFacing(CameraCharacteristics.LENS_FACING_EXTERNAL).firstOrNull()
            ?: idsWithFacing(CameraCharacteristics.LENS_FACING_BACK).firstOrNull()
            ?: idsWithFacing(CameraCharacteristics.LENS_FACING_FRONT).firstOrNull()
            ?: ids.firstOrNull()
    }

    @Suppress("MissingPermission") // 呼び出し元(MainActivity)でCAMERA権限を確認済み
    private suspend fun openCameraDevice(cameraId: String): CameraDevice =
        suspendCancellableCoroutine { cont ->
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("カメラオープン失敗: error=$error"))
                }
            }, backgroundHandler)
        }

    private suspend fun createCaptureSession(
        device: CameraDevice,
        previewSurface: Surface,
        readerSurface: Surface,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        device.createCaptureSession(
            listOf(previewSurface, readerSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        addTarget(readerSurface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }
                    session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                    if (cont.isActive) cont.resume(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("キャプチャセッションの構成に失敗しました"))
                }
            },
            backgroundHandler,
        )
    }

    /** 現在のフレームをJPEGバイト列として取得する(識別・登録・検出に送る)。 */
    fun capture(): ByteArray = latestJpeg ?: throw IllegalStateException("フレームを取得できませんでした")

    /** トラックの枠(正規化)から、余白付きで顔領域を切り出す。 */
    fun captureCrop(box: FaceBox, margin: Float = 0.4f, quality: Int = 90): ByteArray {
        val full = capture()
        val bitmap = BitmapFactory.decodeByteArray(full, 0, full.size)
            ?: throw IllegalStateException("画像をデコードできませんでした")
        val w = bitmap.width
        val h = bitmap.height
        val mx = box.w * w * margin
        val my = box.h * h * margin
        val x = ((box.x * w) - mx).toInt().coerceIn(0, w - 1)
        val y = ((box.y * h) - my).toInt().coerceIn(0, h - 1)
        var cw = ((box.w * w) + mx * 2).toInt()
        var ch = ((box.h * h) + my * 2).toInt()
        if (x + cw > w) cw = w - x
        if (y + ch > h) ch = h - y
        if (cw < 8 || ch < 8) throw IllegalStateException("顔の切り出しに失敗しました（領域が小さすぎます）")

        val crop = Bitmap.createBitmap(bitmap, x, y, cw, ch)
        val out = ByteArrayOutputStream()
        crop.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    fun close() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        latestJpeg = null
    }

    companion object {
        /** JPEG変換の最小間隔(ms)。検出ループ(200ms)より少し短い程度で十分。 */
        private const val CONVERT_INTERVAL_MS = 150L

        /** YUV_420_888 の Image を NV21 経由でJPEGバイト列に変換する。 */
        private fun imageToJpeg(image: Image, quality: Int = 85): ByteArray {
            val nv21 = yuv420ToNv21(image)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
            return out.toByteArray()
        }

        private fun yuv420ToNv21(image: Image): ByteArray {
            val width = image.width
            val height = image.height
            val ySize = width * height
            val nv21 = ByteArray(ySize + ySize / 2)

            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            var pos = 0
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }

            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            val uRowStride = uPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val vRowStride = vPlane.rowStride
            val vPixelStride = vPlane.pixelStride
            val chromaHeight = height / 2
            val chromaWidth = width / 2

            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaWidth) {
                    val vIndex = row * vRowStride + col * vPixelStride
                    val uIndex = row * uRowStride + col * uPixelStride
                    nv21[pos++] = vBuffer.get(vIndex)
                    nv21[pos++] = uBuffer.get(uIndex)
                }
            }
            return nv21
        }
    }
}

/** TextureViewのSurfaceTextureが準備できるまで待つ。Camera2+TextureViewの定石パターン。 */
suspend fun TextureView.awaitSurfaceTexture(): SurfaceTexture {
    surfaceTexture?.let { return it }
    return suspendCancellableCoroutine { cont ->
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (cont.isActive) cont.resume(surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }
}
