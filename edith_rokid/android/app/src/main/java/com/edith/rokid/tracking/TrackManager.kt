package com.edith.rokid.tracking

import kotlin.math.abs

/**
 * 複数人の顔トラックを管理する。
 * edith_mobile/src/tracking/trackManager.ts（最近傍マッチング・marker割当・sticky識別）を移植。
 */
class TrackManager(
    /** トラック対応付けの中心距離しきい値（正規化）。 */
    private val matchDist: Float = 0.2f,
    /** これ以上見失ったらトラック終了（ms）。 */
    private val lostGraceMs: Long = 1200L,
) {
    private val _tracks = mutableListOf<Track>()
    private var nextId = 1

    val tracks: List<Track> get() = _tracks

    fun clear() {
        _tracks.clear()
    }

    /** 検出した生の枠を既存トラックへ対応付け（最近傍）、新規/消失を反映する。 */
    fun ingest(raw: List<FaceBox>) {
        val now = System.currentTimeMillis()
        val usedRaw = mutableSetOf<Int>()

        for (t in _tracks) {
            var best = -1
            var bestD = matchDist
            for (i in raw.indices) {
                if (i in usedRaw) continue
                val d = abs(t.box.centerX - raw[i].centerX) + abs(t.box.centerY - raw[i].centerY)
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            if (best >= 0) {
                t.box = raw[best]
                t.lastSeen = now
                usedRaw.add(best)
            }
        }

        for (i in raw.indices) {
            if (i !in usedRaw) {
                val track = Track(nextId++, raw[i])
                track.marker = assignMarker()
                _tracks.add(track)
            }
        }

        _tracks.removeAll { now - it.lastSeen > lostGraceMs }
    }

    /** 現在使われていない最小の英字を識別子として割り当てる（A, B, ...）。 */
    private fun assignMarker(): String {
        val used = _tracks.mapNotNull { it.marker }.toSet()
        for (i in 0 until 26) {
            val c = ('A' + i).toString()
            if (c !in used) return c
        }
        return "#$nextId"
    }

    /** 毎フレーム：各トラックの表示枠を補間して返す（name/markerをラベル付き）。 */
    fun tick(): List<FaceBox> = _tracks.map { t ->
        t.shown = FaceBox.lerp(t.shown, t.box, 0.3f)
        t.shown.labeled(name = t.name, marker = if (t.name == null) t.marker else null)
    }

    fun findByMarker(marker: String): Track? {
        val m = marker.trim().uppercase()
        return _tracks.firstOrNull { (it.marker ?: "").uppercase() == m }
    }

    fun unnamedTracks(): List<Track> = _tracks.filter { it.name == null }
}
