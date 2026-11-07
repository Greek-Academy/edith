package com.edith.rokid.tracking

/**
 * 顔の枠。座標は入力画像に対する正規化値[0,1]（x,y=左上, w,h=幅高）。
 * edith_mobile/src/hud/faceBox.ts と同じ形。
 */
data class FaceBox(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val score: Float,
    val name: String? = null,
    val marker: String? = null,
) {
    val centerX: Float get() = x + w / 2
    val centerY: Float get() = y + h / 2

    fun labeled(name: String? = this.name, marker: String? = this.marker): FaceBox =
        copy(name = name, marker = marker)

    companion object {
        /** 追従を滑らかにするための線形補間（ラベルはbを引き継ぐ）。 */
        fun lerp(a: FaceBox, b: FaceBox, t: Float): FaceBox = FaceBox(
            x = a.x + (b.x - a.x) * t,
            y = a.y + (b.y - a.y) * t,
            w = a.w + (b.w - a.w) * t,
            h = a.h + (b.h - a.h) * t,
            score = b.score,
            name = b.name,
            marker = b.marker,
        )
    }
}
