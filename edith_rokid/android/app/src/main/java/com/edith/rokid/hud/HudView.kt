package com.edith.rokid.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.edith.rokid.tracking.FaceBox
import kotlin.math.max

private val CYAN = Color.parseColor("#00E5FF")
private val GREEN = Color.parseColor("#7CFF6B")

/**
 * HUDを描画するカスタムView（中央の状態バッジ＋各トラックの枠＋名前/markerラベル）。
 * edith_mobile/src/hud/hudCanvas.ts と同じ役割・同じcover変換ロジック。
 * カメラプレビューの実寸(`frameWidth`/`frameHeight`)はViewのサイズと異なるため、
 * `object-fit:cover`と同じ拡大率・オフセットで正規化座標を変換する。
 */
class HudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var state: HudState = HudState.IDLE
        set(value) {
            field = value
            invalidate()
        }
    var faces: List<FaceBox> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var mirror: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var frameWidth: Int = 0
        set(value) {
            field = value
            invalidate()
        }
    var frameHeight: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0xB3, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f
        textAlign = Paint.Align.LEFT
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }
    private val badgeSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        textAlign = Paint.Align.CENTER
        color = Color.argb(0xCC, 0xFF, 0xFF, 0xFF)
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0xB3, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private data class Cover(val scale: Float, val offsetX: Float, val offsetY: Float)

    private fun coverTransform(viewW: Int, viewH: Int): Cover {
        if (frameWidth <= 0 || frameHeight <= 0) return Cover(1f, 0f, 0f)
        val scale = max(viewW.toFloat() / frameWidth, viewH.toFloat() / frameHeight)
        val offsetX = (viewW - frameWidth * scale) / 2f
        val offsetY = (viewH - frameHeight * scale) / 2f
        return Cover(scale, offsetX, offsetY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        val cover = coverTransform(w, h)

        canvas.save()
        if (mirror) {
            canvas.scale(-1f, 1f, w / 2f, h / 2f)
        }
        for (face in faces) {
            drawFaceBox(canvas, cover, face)
        }
        canvas.restore()

        drawStatusBadge(canvas, w, state)
    }

    private fun drawFaceBox(canvas: Canvas, cover: Cover, box: FaceBox) {
        val x = box.x * frameWidth * cover.scale + cover.offsetX
        val y = box.y * frameHeight * cover.scale + cover.offsetY
        val bw = box.w * frameWidth * cover.scale
        val bh = box.h * frameHeight * cover.scale
        val locked = box.name != null
        val color = if (locked) GREEN else CYAN

        boxPaint.color = color
        canvas.drawRect(x, y, x + bw, y + bh, boxPaint)

        val label = box.name ?: box.marker?.let { "#$it" } ?: return
        val padding = 12f
        val textWidth = textPaint.measureText(label)
        val tagH = 44f
        val tagY = (y - tagH).coerceAtLeast(0f)

        canvas.drawRect(x, tagY, x + textWidth + padding * 2, tagY + tagH, tagBgPaint)
        textPaint.color = color

        if (mirror) {
            // ラベルは鏡像にしない（可読性のため個別に反転して描画）。
            canvas.save()
            canvas.scale(-1f, 1f, x + (textWidth + padding * 2) / 2f, 0f)
            canvas.drawText(label, x + padding, tagY + tagH - 12f, textPaint)
            canvas.restore()
        } else {
            canvas.drawText(label, x + padding, tagY + tagH - 12f, textPaint)
        }
    }

    private fun drawStatusBadge(canvas: Canvas, w: Int, state: HudState) {
        val text = if (state.name != null) "${state.status} — ${state.name}" else state.status
        val color = if (state.locked) GREEN else CYAN
        val textWidth = badgeTextPaint.measureText(text)
        val padX = 24f
        val badgeW = textWidth + padX * 2
        val badgeH = 68f
        val left = w / 2f - badgeW / 2f
        val top = 90f
        val rect = RectF(left, top, left + badgeW, top + badgeH)

        canvas.drawRoundRect(rect, 26f, 26f, badgeBgPaint)
        badgeStrokePaint.color = color
        canvas.drawRoundRect(rect, 26f, 26f, badgeStrokePaint)

        badgeTextPaint.color = color
        canvas.drawText(text, w / 2f, top + badgeH / 2f + 12f, badgeTextPaint)

        state.subInfo?.let {
            canvas.drawText(it, w / 2f, top + badgeH + 36f, badgeSubPaint)
        }
    }
}
