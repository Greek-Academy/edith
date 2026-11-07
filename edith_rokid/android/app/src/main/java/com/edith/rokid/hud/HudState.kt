package com.edith.rokid.hud

/**
 * グラスHUDに表示する状態。edith_mobile/src/hud/hudState.ts と同じ形。
 */
data class HudState(
    val status: String = "SCANNING",
    val name: String? = null,
    val subInfo: String? = null,
    val locked: Boolean = false,
) {
    companion object {
        val IDLE = HudState()
    }
}
