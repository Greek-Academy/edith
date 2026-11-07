package com.edith.rokid.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * マイク録音。edith_mobile/src/voice/speechInput.ts のMediaRecorderフォールバック経路に相当。
 * YodaOS(AOSP系)にGoogleの音声認識バックエンドが載っているか不明なため、
 * 確実な「録音してサーバ(stt_service経由のvoice_assistant_agent)へ送る」方式をprimaryにする。
 * WAV(PCM 16bit, 16kHz, mono)でエンコードして送る。
 */
class SpeechInput {
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val buffer = ByteArrayOutputStream()

    var listening: Boolean = false
        private set

    @Suppress("MissingPermission") // 呼び出し元(MainActivity)でRECORD_AUDIO権限を確認済み
    fun startRecording() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 2,
        )
        audioRecord = record
        buffer.reset()
        record.startRecording()
        listening = true

        val thread = Thread {
            val chunk = ByteArray(minBufferSize)
            while (listening) {
                val read = record.read(chunk, 0, chunk.size)
                if (read > 0) buffer.write(chunk, 0, read)
            }
        }
        recordingThread = thread
        thread.start()
    }

    /** 録音を止め、収録した音声をWAV(ByteArray)として返す。 */
    suspend fun stopRecording(): ByteArray = withContext(Dispatchers.IO) {
        listening = false
        recordingThread?.join()
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        pcmToWav(buffer.toByteArray(), SAMPLE_RATE)
    }

    companion object {
        private const val SAMPLE_RATE = 16000

        /** PCM 16bit mono の生データにWAVヘッダを付与する。 */
        private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val byteRate = sampleRate * 2
            val totalDataLen = pcm.size + 36

            fun writeInt(v: Int) {
                out.write(v and 0xff)
                out.write((v shr 8) and 0xff)
                out.write((v shr 16) and 0xff)
                out.write((v shr 24) and 0xff)
            }

            fun writeShort(v: Int) {
                out.write(v and 0xff)
                out.write((v shr 8) and 0xff)
            }

            out.write("RIFF".toByteArray())
            writeInt(totalDataLen)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            writeInt(16) // Subchunk1Size for PCM
            writeShort(1) // AudioFormat = PCM
            writeShort(1) // NumChannels = mono
            writeInt(sampleRate)
            writeInt(byteRate)
            writeShort(2) // BlockAlign
            writeShort(16) // BitsPerSample
            out.write("data".toByteArray())
            writeInt(pcm.size)
            out.write(pcm)
            return out.toByteArray()
        }
    }
}
