package com.edith.rokid.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Agentがクライアントに要求するデバイス操作（capture_face等, PRD §20）。 */
data class AgentAction(
    val id: String,
    val type: String,
    val payload: JSONObject,
)

data class AgentReply(
    val sessionId: String,
    val message: String,
    val actions: List<AgentAction>,
    val transcript: String?,
)

private val JSON_MEDIA = "application/json".toMediaType()
private val AUDIO_WAV = "audio/wav".toMediaType()

/**
 * Voice Agent (`/v1/agent/...`) クライアント。
 * edith_mobile/src/api/voiceAgentApi.ts と同じAPI契約。
 */
class VoiceAgentApi(private val baseUrl: String, private val client: OkHttpClient = OkHttpClient()) {

    var sessionId: String? = null
        private set

    /** テキスト指示を送る（PRD §8.1）。 */
    suspend fun sendText(content: String): AgentReply = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("sessionId", sessionId)
            put("type", "text")
            put("content", content)
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA)
        val request = Request.Builder().url("$baseUrl/v1/agent/input").post(body).build()
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IOException("agent input failed: ${res.code} ${res.body?.string()}")
            parse(res.body!!.string())
        }
    }

    /** 音声を送る（PRD §8/§12, Push-to-Talk）。STTはサーバ側でローカル実行。 */
    suspend fun sendAudio(bytes: ByteArray, filename: String = "speech.wav"): AgentReply = withContext(Dispatchers.IO) {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("language", "ja")
            .addFormDataPart("audio", filename, bytes.toRequestBody(AUDIO_WAV))
        sessionId?.let { builder.addFormDataPart("sessionId", it) }
        val request = Request.Builder().url("$baseUrl/v1/agent/audio").post(builder.build()).build()
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IOException("agent audio failed: ${res.code} ${res.body?.string()}")
            parse(res.body!!.string())
        }
    }

    /** クライアントが実行したActionの結果を返す（PRD §8）。 */
    suspend fun reportActionResult(requestId: String, result: JSONObject) = withContext(Dispatchers.IO) {
        val body = result.toString().toRequestBody(JSON_MEDIA)
        val request = Request.Builder().url("$baseUrl/v1/agent/actions/$requestId/result").post(body).build()
        client.newCall(request).execute().close()
    }

    private fun parse(body: String): AgentReply {
        val j = JSONObject(body)
        sessionId = j.optString("sessionId", sessionId)
        val actionsJson = j.optJSONArray("actions")
        val actions = actionsJson?.let { arr ->
            (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                AgentAction(
                    id = a.getString("id"),
                    type = a.getString("type"),
                    payload = a.optJSONObject("payload") ?: JSONObject(),
                )
            }
        } ?: emptyList()
        return AgentReply(
            sessionId = sessionId ?: "",
            message = j.optString("message", ""),
            actions = actions,
            transcript = if (j.has("transcript") && !j.isNull("transcript")) j.getString("transcript") else null,
        )
    }
}
