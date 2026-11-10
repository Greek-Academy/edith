package com.edith.rokid.vision

import android.content.Context
import android.graphics.Bitmap
import com.edith.rokid.tracking.FaceBox
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector

/**
 * 端末内（グラス本体）の顔検出。edith_mobile/src/vision/faceDetector.ts と同じモデル
 * （blaze_face_short_range, アセットに同梱: assets/blaze_face_short_range.tflite）を使う。
 * 往復なしで滑らかに追従するため、クライアント内で完結させる。
 */
class ClientFaceDetector(context: Context) {
    private val detector: FaceDetector = FaceDetector.createFromOptions(
        context,
        FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .build(),
            )
            .setRunningMode(RunningMode.VIDEO)
            // デフォルト(0.5)だとグラスの距離感ではやや厳しいので下げて拾いやすくする。
            .setMinDetectionConfidence(0.3f)
            .build(),
    )

    /** 1フレームを検出する。[timestampMs]は単調増加している必要がある(MediaPipeの制約)。 */
    fun detect(bitmap: Bitmap, timestampMs: Long): List<FaceBox> {
        val image = BitmapImageBuilder(bitmap).build()
        val result = detector.detectForVideo(image, timestampMs)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        return result.detections().mapNotNull { d ->
            val bb = d.boundingBox()
            val score = d.categories().firstOrNull()?.score() ?: 0f
            FaceBox(
                x = bb.left / w,
                y = bb.top / h,
                w = bb.width() / w,
                h = bb.height() / h,
                score = score,
            )
        }
    }

    fun close() {
        detector.close()
    }

    companion object {
        // full_rangeも試したが、tasks-vision:0.10.14のグラフとテンソル形状が合わず
        // detectForVideo()が毎回例外(RET_CHECK dims[1]==num_boxes_)で失敗したため、
        // short_range(edith_mobileと同じ、2m程度を想定)に戻している。
        // 遠距離検出が必要になったら、ライブラリ側のバージョンアップ後に再検討する。
        private const val MODEL_ASSET = "blaze_face_short_range.tflite"
    }
}
