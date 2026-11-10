package com.edith.rokid.config

import android.content.Context

/**
 * バックエンドのベースURL。edith_mobile/src/config/apiConfig.ts の ApiConfig と同じ役割。
 * 実行時は接続先設定画面でSharedPreferencesへ保存した値が優先される。
 */
data class ApiConfig(
    val agentBaseUrl: String = DEFAULT_AGENT_URL,
    val faceBaseUrl: String = DEFAULT_FACE_URL,
) {
    companion object {
        const val DEFAULT_AGENT_URL = "https://agent.under18.dev"
        const val DEFAULT_FACE_URL = "https://face.unischool.jp"

        private const val PREFS = "edith.apiConfig"
        private const val KEY_AGENT = "agentBaseUrl"
        private const val KEY_FACE = "faceBaseUrl"

        fun load(context: Context): ApiConfig {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return ApiConfig(
                agentBaseUrl = prefs.getString(KEY_AGENT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_AGENT_URL,
                faceBaseUrl = prefs.getString(KEY_FACE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_FACE_URL,
            )
        }

        fun save(context: Context, config: ApiConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_AGENT, config.agentBaseUrl)
                .putString(KEY_FACE, config.faceBaseUrl)
                .apply()
        }
    }
}
