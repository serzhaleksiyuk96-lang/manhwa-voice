package com.manhwavoice.reader

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var status: TextView
    private var testTts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.get(this)
        val dp = resources.displayMetrics.density
        val pad = (18 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 2)
        }
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        }
        setContentView(scroll)

        fun text(s: String, size: Float = 15f, bold: Boolean = false, top: Int = 8) = TextView(this).apply {
            text = s
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (top * dp).toInt(), 0, (4 * dp).toInt())
            root.addView(this)
        }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply {
            text = s
            isAllCaps = false
            setOnClickListener { onClick() }
            root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun slider(title: String, key: String, min: Int, max: Int, def: Int, fmt: (Int) -> String) {
            val label = text("", 15f, top = 14)
            val bar = SeekBar(this).apply {
                this.min = min
                this.max = max
                progress = prefs.getInt(key, def)
            }
            label.text = "$title: ${fmt(bar.progress)}"
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    label.text = "$title: ${fmt(value)}"
                    prefs.edit().putInt(key, value).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            root.addView(bar)
        }

        text("Озвучка манхвы", 24f, bold = true, top = 0)
        status = text("", 16f, top = 10)
        button("Открыть настройки спецвозможностей") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        text("Как пользоваться", 18f, bold = true, top = 22)
        text(
            "1. Включите службу «Озвучка манхвы» в спецвозможностях (кнопка выше). " +
                "Если Android пишет, что настройка ограничена, — см. ниже.\n" +
                "2. Откройте главу в Opera и включите полноэкранный режим.\n" +
                "3. Нажмите на круглую кнопку ▶ — приложение начнёт читать и само листать вниз.\n" +
                "4. Нажатие на кнопку ещё раз — пауза. Кнопку можно перетащить пальцем.\n" +
                "5. Долгое нажатие — забыть прочитанное (экран прочитается заново).\n" +
                "6. Когда страница кончится, прозвучит «Конец главы». Откройте следующую и снова нажмите ▶."
        )

        text("Настройки", 18f, bold = true, top = 22)
        slider("Скорость речи", Prefs.KEY_RATE, 50, 200, 100) { "$it%" }
        slider("Шаг прокрутки", Prefs.KEY_SCROLL, 40, 75, 60) { "$it% экрана" }
        slider("Пауза после текста", Prefs.KEY_PAUSE_TEXT, 0, 3000, 300) { String.format(Locale.US, "%.1f с", it / 1000f) }
        slider("Пауза на кадрах без текста", Prefs.KEY_PAUSE_EMPTY, 200, 4000, 800) { String.format(Locale.US, "%.1f с", it / 1000f) }

        text("Голос", 18f, bold = true, top = 22)
        button("Проверить голос") { testVoice() }
        button("Настройки синтеза речи (выбрать голос)") {
            runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        button("Скачать русский голос") {
            runCatching { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                .onFailure { Toast.makeText(this, "Не удалось открыть установку голосов", Toast.LENGTH_LONG).show() }
        }

        text("Если службу не дают включить", 18f, bold = true, top = 22)
        text(
            "На Android 13 и новее приложения, установленные не из Google Play, по умолчанию не могут " +
                "включать спецвозможности. Чтобы разрешить:\n" +
                "1. Попробуйте один раз включить службу — появится сообщение «Настройка ограничена».\n" +
                "2. Откройте Настройки → Приложения → «Озвучка манхвы».\n" +
                "3. Нажмите ⋮ (три точки вверху справа) → «Разрешить ограниченные настройки».\n" +
                "4. Вернитесь в спецвозможности и включите службу."
        )
        button("Открыть сведения о приложении") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.parse("package:$packageName"))
            )
        }

        text(
            "Снимки экрана обрабатываются только на телефоне и никуда не отправляются.",
            13f, top = 22
        )
    }

    override fun onResume() {
        super.onResume()
        status.text = if (isServiceEnabled())
            "✅ Служба включена — кнопка ▶ должна быть на экране"
        else
            "❌ Служба выключена — включите её в спецвозможностях"
    }

    private fun isServiceEnabled(): Boolean {
        if (ReaderService.instance != null) return true
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, ReaderService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun testVoice() {
        testTts?.shutdown()
        testTts = TextToSpeech(this) { st ->
            val t = testTts ?: return@TextToSpeech
            if (st != TextToSpeech.SUCCESS) {
                Toast.makeText(this, "Синтез речи недоступен", Toast.LENGTH_LONG).show()
                return@TextToSpeech
            }
            val r = t.setLanguage(Locale.forLanguageTag("ru-RU"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Русский голос не установлен — нажмите «Скачать русский голос»", Toast.LENGTH_LONG).show()
                return@TextToSpeech
            }
            t.setSpeechRate(Prefs.rate(Prefs.get(this)))
            t.speak("Привет! Вот так будет звучать озвучка манхвы.", TextToSpeech.QUEUE_FLUSH, null, "test")
        }
    }

    override fun onDestroy() {
        testTts?.shutdown()
        super.onDestroy()
    }
}
