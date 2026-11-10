package com.edith.rokid

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.edith.rokid.api.AgentAction
import com.edith.rokid.api.FaceApi
import com.edith.rokid.api.VoiceAgentApi
import com.edith.rokid.camera.GlassesCameraSource
import com.edith.rokid.config.ApiConfig
import com.edith.rokid.databinding.ActivityMainBinding
import com.edith.rokid.hud.HudState
import com.edith.rokid.tracking.Track
import com.edith.rokid.tracking.TrackManager
import com.edith.rokid.vision.ClientFaceDetector
import com.edith.rokid.voice.SpeechInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val DETECT_INTERVAL_MS = 200L
private const val IDENTIFY_INTERVAL_MS = 1200L
private const val RELEARN_MS = 4000L

/**
 * E.D.I.T.H Rokidのメイン画面。edith_mobile/src/app.ts(App)をKotlin/Android向けに移植したもの。
 * Rokidグラス本体のカメラ(標準Camera2 API)で顔検出・追跡・識別し、HUDに重畳表示する。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var config: ApiConfig = ApiConfig()
    private lateinit var faceApi: FaceApi
    private lateinit var agentApi: VoiceAgentApi

    private var camera: GlassesCameraSource? = null
    private var detector: ClientFaceDetector? = null
    private val tracks = TrackManager()
    private val speech = SpeechInput()

    private var detectJob: Job? = null
    private var identifyJob: Job? = null
    private var identifyingPass = false

    private val logLines = mutableListOf<String>()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) {
            lifecycleScope.launch { initCamera() }
        } else {
            addLog("⚠️ カメラ権限が許可されていません")
        }
        if (granted[Manifest.permission.RECORD_AUDIO] != true) {
            addLog("⚠️ マイク権限が許可されていません（音声入力は使えません）")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // android:keepScreenOn/turnScreenOnはmanifestの<activity>では効かないため、
        // Windowフラグで明示的にスリープを抑止する。
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = ApiConfig.load(this)
        faceApi = FaceApi(config.faceBaseUrl)
        agentApi = VoiceAgentApi(config.agentBaseUrl)

        bindUi()
        requestPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun bindUi() {
        binding.btnSend.setOnClickListener { send(binding.textInput.text.toString()) }
        binding.textInput.setOnEditorActionListener { _, actionId, event ->
            val isSend = actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isSend) {
                send(binding.textInput.text.toString())
                true
            } else {
                false
            }
        }
        binding.btnIdentify.setOnClickListener { lifecycleScope.launch { manualIdentify() } }
        binding.btnMic.setOnClickListener { lifecycleScope.launch { toggleMic() } }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
    }

    // --- カメラ初期化・ループ ---

    private suspend fun initCamera() {
        try {
            val cam = GlassesCameraSource(this)
            cam.startBackgroundThread()
            cam.open()
            camera = cam
            addLog("📷 カメラ起動（グラス本体カメラ）")

            detector = withContext(Dispatchers.Default) { ClientFaceDetector(this@MainActivity) }
            startLoops()
        } catch (e: Exception) {
            addLog("⚠️ カメラ初期化に失敗: ${e.message}")
        }
    }

    private fun startLoops() {
        detectJob?.cancel()
        identifyJob?.cancel()
        detectJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                runDetectTick()
                delay(DETECT_INTERVAL_MS)
            }
        }
        identifyJob = lifecycleScope.launch {
            while (isActive) {
                runIdentifyPass()
                delay(IDENTIFY_INTERVAL_MS)
            }
        }
    }

    private suspend fun runDetectTick() {
        val cam = camera ?: return
        val det = detector ?: return
        if (!cam.isLive) return
        try {
            val jpeg = cam.capture()
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
            val raw = det.detect(bitmap, System.currentTimeMillis())
            tracks.ingest(raw)
        } catch (_: Exception) {
            // 追跡ループは止めない
        }
        val faces = tracks.tick()
        withContext(Dispatchers.Main) {
            binding.hudView.frameWidth = cam.frameWidth
            binding.hudView.frameHeight = cam.frameHeight
            binding.hudView.faces = faces
            binding.hudView.state = computeHudState()
        }
    }

    private fun computeHudState(): HudState {
        val total = tracks.tracks.size
        val named = tracks.tracks.count { it.name != null }
        return when {
            total == 0 -> HudState(status = "SCANNING")
            named == 0 -> HudState(status = "DETECTED")
            total == 1 -> HudState(status = "TARGET LOCKED", name = tracks.tracks[0].name, locked = true)
            else -> HudState(status = "$named / $total 人 認識", locked = true)
        }
    }

    /** 名前が必要な各トラックの顔を切り出して個別に識別する。edith_mobile/src/app.ts の runIdentifyPass 相当。 */
    private suspend fun runIdentifyPass() {
        val cam = camera ?: return
        if (identifyingPass || tracks.tracks.isEmpty() || !cam.isLive) return
        identifyingPass = true
        try {
            val now = System.currentTimeMillis()
            for (t in tracks.tracks.toList()) {
                if (t.identifying) continue
                if (t.name != null && t.lastId != null && now - t.lastId!! < RELEARN_MS) continue
                identifyTrack(cam, t, now)
            }
        } finally {
            identifyingPass = false
        }
    }

    private suspend fun identifyTrack(cam: GlassesCameraSource, t: Track, now: Long) {
        t.identifying = true
        t.lastId = now
        try {
            val crop = try {
                cam.captureCrop(t.box)
            } catch (_: Exception) {
                return
            }
            val r = faceApi.identify(crop, autoEnroll = true)
            if (r == null) {
                t.name?.let { name ->
                    try {
                        faceApi.register(crop, name)
                        addLog("📚 苦手な角度を学習: $name")
                    } catch (_: Exception) {
                        // 追跡ループは止めない
                    }
                }
                return
            }
            if (r.sampleAdded) addLog("📚 角度サンプルを自動学習: ${r.name}")
            if (t.name == null) {
                t.name = r.name
                addLog("識別: ${r.name} (${(r.similarity * 100).toInt()}%)")
            }
        } catch (_: Exception) {
            // 追跡ループは止めない
        } finally {
            t.identifying = false
        }
    }

    /** 「今すぐ識別」ボタン。 */
    private suspend fun manualIdentify() {
        val cam = camera
        try {
            if (tracks.tracks.isEmpty()) {
                if (cam == null || !cam.isLive) {
                    addLog("フレームを取得できませんでした")
                    return
                }
                val bytes = cam.capture()
                val r = faceApi.identify(bytes, autoEnroll = true)
                addLog(if (r == null) "識別: 一致なし" else "識別: ${r.name}")
            } else {
                runIdentifyPass()
            }
        } catch (e: Exception) {
            addLog("エラー: ${e.message}")
        }
    }

    // --- テキスト/音声指示 ---

    private fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        binding.textInput.setText("")
        addLog("🗣 $content")
        lifecycleScope.launch {
            try {
                val reply = agentApi.sendText(content)
                handleReply(reply.message, reply.actions)
            } catch (e: Exception) {
                addLog("エラー: ${e.message}")
            }
        }
    }

    private fun sendAudio(bytes: ByteArray) {
        lifecycleScope.launch {
            try {
                val reply = agentApi.sendAudio(bytes)
                reply.transcript?.let { addLog("🗣 $it") }
                handleReply(reply.message, reply.actions)
            } catch (e: Exception) {
                addLog("エラー: ${e.message}")
            }
        }
    }

    private suspend fun handleReply(message: String, actions: List<AgentAction>) {
        addLog("🤖 $message")
        val cam = camera
        for (action in actions) {
            when (action.type) {
                "capture_face" -> {
                    val name = action.payload.optString("name", "")
                    addLog("📷 capture_face → $name を登録中…")
                    if (cam == null || !cam.isLive) {
                        addLog("フレームを取得できませんでした")
                        continue
                    }
                    try {
                        val bytes = cam.capture()
                        val personId = faceApi.register(bytes, name)
                        agentApi.reportActionResult(action.id, JSONObject().put("success", true).put("personId", personId))
                        addLog("✅ 登録完了: $name ($personId)")
                    } catch (e: Exception) {
                        agentApi.reportActionResult(action.id, JSONObject().put("success", false))
                        addLog("⚠️ 登録に失敗: ${e.message}")
                    }
                }
                "capture_labeled_face" -> {
                    val marker = action.payload.optString("marker", "")
                    val name = action.payload.optString("name", "")
                    try {
                        val ok = registerLabeled(marker, name)
                        agentApi.reportActionResult(
                            action.id,
                            JSONObject().put("success", ok).put("marker", marker).put("name", name),
                        )
                        addLog(if (ok) "✅ 「$marker」→ $name として登録" else "⚠️ 識別子「$marker」の顔が見つかりません")
                    } catch (e: Exception) {
                        agentApi.reportActionResult(action.id, JSONObject().put("success", false).put("marker", marker))
                        addLog("⚠️ 登録に失敗: ${e.message}")
                    }
                }
            }
        }
    }

    /** 識別子(A,B..)のトラックの顔を切り出し、指定名で登録して以後identifiedにする。 */
    private suspend fun registerLabeled(marker: String, name: String): Boolean {
        if (name.isEmpty()) return false
        val cam = camera ?: throw IllegalStateException("フレームを取得できませんでした")
        var track = tracks.findByMarker(marker)
        if (track == null) {
            val unnamed = tracks.unnamedTracks()
            if (unnamed.size == 1) {
                track = unnamed[0]
                addLog("（識別子「$marker」は未検出だが未識別の顔が1つなのでそれを使用）")
            }
        }
        if (!cam.isLive) throw IllegalStateException("フレームを取得できませんでした")

        if (track == null || tracks.tracks.size <= 1) {
            val bytes = cam.capture()
            faceApi.register(bytes, name)
            track?.name = name
            return true
        }

        try {
            val crop = cam.captureCrop(track.box)
            faceApi.register(crop, name)
        } catch (_: Exception) {
            val bytes = cam.capture()
            faceApi.register(bytes, name)
        }
        track.name = name
        return true
    }

    // --- 音声入力 ---

    private suspend fun toggleMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            addLog("⚠️ マイク権限がありません")
            return
        }
        if (speech.listening) {
            val bytes = speech.stopRecording()
            sendAudio(bytes)
        } else {
            try {
                speech.startRecording()
            } catch (e: Exception) {
                addLog("⚠️ マイクを使用できません: ${e.message}")
            }
        }
    }

    // --- 接続先設定 ---

    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val agentInput = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(config.agentBaseUrl)
            hint = "Voice Agent URL"
        }
        val faceInput = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(config.faceBaseUrl)
            hint = "Face API URL"
        }
        container.addView(TextView(this).apply { text = "Voice Agent URL" })
        container.addView(agentInput)
        container.addView(TextView(this).apply { text = "Face API URL"; setPadding(0, 24, 0, 0) })
        container.addView(faceInput)

        AlertDialog.Builder(this)
            .setTitle("接続先")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val agentUrl = agentInput.text.toString().trim().ifEmpty { ApiConfig.DEFAULT_AGENT_URL }
                val faceUrl = faceInput.text.toString().trim().ifEmpty { ApiConfig.DEFAULT_FACE_URL }
                config = ApiConfig(agentUrl, faceUrl)
                ApiConfig.save(this, config)
                faceApi = FaceApi(faceUrl)
                agentApi = VoiceAgentApi(agentUrl)
                addLog("🔧 接続先を更新: agent=$agentUrl face=$faceUrl")
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // --- ログ ---

    private fun addLog(line: String) {
        runOnUiThread {
            logLines.add(0, line)
            if (logLines.size > 20) logLines.removeAt(logLines.size - 1)
            binding.logView.text = logLines.take(3).joinToString("\n")
        }
    }

    override fun onDestroy() {
        detectJob?.cancel()
        identifyJob?.cancel()
        camera?.close()
        camera?.stopBackgroundThread()
        detector?.close()
        super.onDestroy()
    }
}
