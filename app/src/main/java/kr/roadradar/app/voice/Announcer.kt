package kr.roadradar.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 음성 안내. 프로토타입은 Android 표준 TextToSpeech를 쓰고,
 * 실차 출시 버전에서는 Pleos TextToSpeech SDK(Gleo AI)로 교체한다.
 * 주행 중 안내는 단계가 올라갈 때만, 한 문장으로 한다.
 */
class Announcer(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pending: String? = null
    var enabled = true

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.KOREAN
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        ready = true
        pending?.let { speak(it) }; pending = null
    }

    fun speak(text: String) {
        if (!enabled) return
        if (!ready) { pending = text; return }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "roadradar-${System.currentTimeMillis()}")
    }

    fun shutdown() { tts.stop(); tts.shutdown() }
}
