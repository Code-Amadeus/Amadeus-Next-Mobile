package com.amadeus.mobile.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.sqrt

/**
 * 端侧识别：替代 amadeus-next 的 Apple Speech helper，对控制面说同一套消息
 * （status / recognized{text, is_final} / error，带 recognition_id）。断句与超时由控制面判断。
 */
class Recognizer(private val context: Context, private val emit: (JSONObject) -> Unit) {
    companion object {
        private const val TAG = "Recognizer"
        private const val RATE = 16_000
    }

    private var engine: OnlineRecognizer? = null
    @Volatile private var session: Thread? = null
    @Volatile private var finishing = false
    @Volatile private var cancelled = false

    private fun engine(): OnlineRecognizer = engine ?: OnlineRecognizer(
        context.assets,
        OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = "asr/model.int8.onnx"),
                tokens = "asr/tokens.txt",
                numThreads = 2,
                provider = "cpu",
            ),
        ),
    ).also { engine = it }

    @Synchronized
    fun command(message: JSONObject) {
        val id = message.optInt("recognition_id")
        Log.i(TAG, "command ${message.optString("command")} #$id")
        when (message.optString("command")) {
            "start" -> start(id)
            "finish" -> finishing = true
            "stop" -> { cancelled = true; session?.interrupt() }
        }
    }

    private fun send(id: Int, fields: JSONObject) = emit(fields.put("recognition_id", id))

    @SuppressLint("MissingPermission")
    private fun start(id: Int) {
        if (session?.isAlive == true) { cancelled = true; session?.interrupt(); session?.join(1_000) }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            send(id, JSONObject().put("type", "error").put("error", "麦克风权限未授予，请到设置里允许"))
            return
        }
        finishing = false
        cancelled = false
        session = Thread({ run(id) }, "asr").apply { start() }
    }

    @SuppressLint("MissingPermission")
    private fun run(id: Int) {
        val loadStart = System.currentTimeMillis()
        val recognizer = try { engine().also { Log.i(TAG, "engine ready in ${System.currentTimeMillis() - loadStart} ms") } } catch (e: Throwable) {
            Log.e(TAG, "init failed", e)
            send(id, JSONObject().put("type", "error").put("error", "识别模型加载失败：${e.message}"))
            return
        }
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_COMMUNICATION 让系统挂上回声消除，外放她的声音时不会被识别成主人说话
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, RATE / 5))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            send(id, JSONObject().put("type", "error").put("error", "麦克风无法打开"))
            record.release()
            return
        }
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null
        val stream = recognizer.createStream()
        var last = ""
        try {
            record.startRecording()
            send(id, JSONObject().put("type", "status").put("status", "listening"))
            val buffer = ShortArray(RATE / 10)
            while (!cancelled && !finishing) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                stream.acceptWaveform(FloatArray(n) { buffer[it] / 32768f }, RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.trim()
                if (text != last) {
                    last = text
                    send(id, JSONObject().put("type", "recognized").put("text", text).put("is_final", false))
                }
            }
            record.stop()
            if (cancelled) return
            send(id, JSONObject().put("type", "status").put("status", "processing"))
            // 尾部补 0.3 s 静音，让模型吐出最后几个字
            stream.acceptWaveform(FloatArray(RATE * 3 / 10), RATE)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            send(id, JSONObject().put("type", "recognized").put("text", recognizer.getResult(stream).text.trim()).put("is_final", true))
        } catch (e: Exception) {
            if (!cancelled) send(id, JSONObject().put("type", "error").put("error", "识别失败：${e.message}"))
        } finally {
            stream.release()
            aec?.release()
            record.release()
            send(id, JSONObject().put("type", "status").put("status", "idle"))
        }
    }
}

/**
 * TTS 的 PCM 播放（24 kHz 单声道 16 bit）。按 generation 丢弃过期回合的音频；
 * 播放进度回报给控制面（回合何时排空）与前端（口型能量）。
 */
class Player(private val report: (type: String, key: JSONObject, rms: Float) -> Unit) {
    companion object {
        private const val RATE = 24_000
        private const val LEVEL_FRAMES = RATE / 25
    }

    private sealed class Item {
        class Begin(val key: JSONObject) : Item()
        class Pcm(val generation: Int, val bytes: ByteArray) : Item()
        class End(val generation: Int) : Item()
        object Reset : Item()
    }

    private val queue = LinkedBlockingQueue<Item>()
    @Volatile private var generation = -1
    private var key = JSONObject()
    private var track: AudioTrack? = null
    private var written = 0L
    private var playing = false

    init { Thread(::loop, "tts-player").apply { isDaemon = true; start() } }

    fun event(event: JSONObject, body: ByteArray) {
        val gen = event.optInt("generation", -1)
        when (event.optString("type")) {
            "begin" -> if (event.optBoolean("speech")) { generation = gen; queue.put(Item.Begin(event)) }
            "audio" -> if (gen == generation && body.isNotEmpty()) queue.put(Item.Pcm(gen, body))
            "input-ended" -> if (gen == generation) queue.put(Item.End(gen))
            "reset" -> { generation = -1; queue.clear(); queue.put(Item.Reset) }
        }
    }

    private fun track(): AudioTrack = track ?: AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE / 5 * 2))
        .build().also { track = it }

    private fun loop() {
        var trailing: Byte? = null
        while (true) {
            when (val item = queue.take()) {
                is Item.Begin -> {
                    key = item.key
                    trailing = null
                    track().apply { pause(); flush(); play() }
                    written = 0
                    playing = false
                }
                is Item.Pcm -> {
                    if (item.generation != generation) continue
                    // 网络分片可能把一个 16 bit 样本拆在两块里
                    val bytes = trailing?.let { byteArrayOf(it) + item.bytes } ?: item.bytes
                    val even = bytes.size and 1.inv()
                    trailing = if (even < bytes.size) bytes[even] else null
                    if (!playing) { playing = true; report("playing", key, 0f) }
                    var offset = 0
                    while (offset < even && item.generation == generation) {
                        val chunk = minOf(LEVEL_FRAMES * 2, even - offset)
                        report("level", key, rms(bytes, offset, chunk))
                        val n = track().write(bytes, offset, chunk)
                        if (n < 0) { report("error", key, 0f); break }
                        offset += n
                        written += n / 2
                    }
                }
                is Item.End -> {
                    if (item.generation != generation) continue
                    // 等缓冲里的样本真正播完才算排空
                    val t = track()
                    val deadline = System.currentTimeMillis() + 5_000
                    while (t.playbackHeadPosition.toLong() and 0xffffffffL < written && System.currentTimeMillis() < deadline && item.generation == generation) Thread.sleep(20)
                    report("level", key, 0f)
                    report("ended", key, 0f)
                    playing = false
                }
                Item.Reset -> {
                    track?.apply { pause(); flush() }
                    if (playing) report("level", key, 0f)
                    playing = false
                }
            }
        }
    }

    private fun rms(bytes: ByteArray, offset: Int, length: Int): Float {
        var sum = 0.0
        var i = offset
        while (i + 1 < offset + length) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort() / 32768.0
            sum += sample * sample
            i += 2
        }
        return sqrt(sum / maxOf(1, length / 2)).toFloat()
    }
}
