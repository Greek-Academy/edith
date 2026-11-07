package com.edith.rokid.tracking

/**
 * 追跡中の1つの顔（トラック）。edith_mobile/src/tracking/trackManager.ts の Track を移植。
 * 同一トラックである限り人物を固定（sticky）し、見失うまで「不明」に落とさない。
 */
class Track(val id: Int, box: FaceBox) {
    var box: FaceBox = box
    var shown: FaceBox = box
    var name: String? = null
    var marker: String? = null
    var lastSeen: Long = System.currentTimeMillis()
    var lastId: Long? = null
    var identifying: Boolean = false
}
