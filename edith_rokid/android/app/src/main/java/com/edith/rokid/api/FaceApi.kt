package com.edith.rokid.api

import com.edith.rokid.tracking.FaceBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Face API の識別結果。edith_mobile/src/api/faceApi.ts の IdentifyResult と同じ形。 */
data class IdentifyResult(
    val name: String,
    val similarity: Float,
    val info: JSONObject?,
    /** 別角度サンプルが自動学習（auto-enroll）で追加されたか。 */
    val sampleAdded: Boolean,
)

data class DetectResult(
    val width: Int,
    val height: Int,
    val faces: List<FaceBox>,
) {
    companion object {
        val EMPTY = DetectResult(0, 0, emptyList())
    }
}

private val JPEG = "image/jpeg".toMediaType()

/**
 * 顔の識別・登録クライアント。Face API(FastAPI, `/faces/...`)を直接呼ぶ。
 * edith_mobile/src/api/faceApi.ts と同じAPI契約。
 */
class FaceApi(private val baseUrl: String, private val client: OkHttpClient = OkHttpClient()) {

    /** 画像から人物を識別する。一致が無ければnull(404/422)。 */
    suspend fun identify(jpegBytes: ByteArray, autoEnroll: Boolean = true, source: String = "edith-rokid"): IdentifyResult? =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("auto_enroll", autoEnroll.toString())
                .addFormDataPart("source", source)
                .addFormDataPart("image", "frame.jpg", jpegBytes.toRequestBody(JPEG))
                .build()
            val request = Request.Builder().url("$baseUrl/faces/identify").post(body).build()

            client.newCall(request).execute().use { res ->
                // 404=一致なし / 422=顔が検出できない → どちらも「未識別(null)」として扱う。
                if (res.code == 404 || res.code == 422) return@withContext null
                if (!res.isSuccessful) throw IOException("identify failed: ${res.code} ${res.body?.string()}")
                val j = JSONObject(res.body!!.string())
                val person = j.getJSONObject("person")
                IdentifyResult(
                    name = person.getString("name"),
                    similarity = j.getDouble("similarity").toFloat(),
                    info = person.optJSONObject("info"),
                    sampleAdded = j.optBoolean("sample_added", false),
                )
            }
        }

    /** ライブ追跡用の高速な顔検出（枠のみ）。追跡ループを止めないためエラー時は空結果を返す。 */
    suspend fun detect(jpegBytes: ByteArray): DetectResult = withContext(Dispatchers.IO) {
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "frame.jpg", jpegBytes.toRequestBody(JPEG))
                .build()
            val request = Request.Builder().url("$baseUrl/faces/detect").post(body).build()
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return@withContext DetectResult.EMPTY
                val j = JSONObject(res.body!!.string())
                val facesJson = j.getJSONArray("faces")
                val faces = (0 until facesJson.length()).map { i ->
                    val f = facesJson.getJSONObject(i)
                    FaceBox(
                        x = f.getDouble("x").toFloat(),
                        y = f.getDouble("y").toFloat(),
                        w = f.getDouble("w").toFloat(),
                        h = f.getDouble("h").toFloat(),
                        score = f.optDouble("score", 0.0).toFloat(),
                    )
                }
                DetectResult(j.getInt("width"), j.getInt("height"), faces)
            }
        } catch (_: Exception) {
            DetectResult.EMPTY
        }
    }

    /** 画像と名前で人物を登録する（register_personの実処理）。 */
    suspend fun register(jpegBytes: ByteArray, name: String): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("name", name)
            .addFormDataPart("image", "frame.jpg", jpegBytes.toRequestBody(JPEG))
            .build()
        val request = Request.Builder().url("$baseUrl/faces/register").post(body).build()
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IOException("register failed: ${res.code} ${res.body?.string()}")
            JSONObject(res.body!!.string()).getString("id")
        }
    }
}
