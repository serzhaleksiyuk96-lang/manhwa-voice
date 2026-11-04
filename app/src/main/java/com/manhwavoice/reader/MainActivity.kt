package com.manhwavoice.reader

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
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
    private lateinit var updateStatus: TextView
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private lateinit var engineButton: Button
    private lateinit var voiceButton: Button
    private var checking = false
    private var downloading = false
    private var waitingPermissionFor: Updater.Release? = null

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

        text(getString(R.string.app_name), 24f, bold = true, top = 0)
        status = text("", 16f, top = 10)
        button("Открыть настройки спецвозможностей") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        text("Обновления", 18f, bold = true, top = 22)
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        text("Установлена версия $versionName", 14f, top = 2)
        updateStatus = text("", 15f, top = 2)
        button("Проверить обновления") { checkForUpdates() }

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
        engineButton = button("Движок: …") { chooseEngine() }
        voiceButton = button("Голос: …") { chooseVoice() }
        slider("Высота голоса", Prefs.KEY_PITCH, 50, 200, 100) { "$it%" }
        button("▶ Прослушать") { preview() }
        button("Скачать русские голоса") {
            runCatching { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                .onFailure { Toast.makeText(this, "Не удалось открыть установку голосов", Toast.LENGTH_LONG).show() }
        }
        button("Системные настройки синтеза речи") {
            runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        text(
            "Хочется больше голосов? Установите из Google Play бесплатный движок RHVoice " +
                "и скачайте в нём русские голоса — потом выберите его в «Движок».",
            13f, top = 6
        )
        initTts()

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

        checkForUpdates()
    }

    // ---------------- Обновления ----------------

    private fun checkForUpdates() {
        if (checking || downloading) return
        checking = true
        updateStatus.text = "Проверяю обновления…"
        Thread {
            val release = runCatching { Updater.fetchLatest() }.getOrNull()
            runOnUiThread {
                checking = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val current = Updater.currentVersion(this)
                when {
                    release == null -> updateStatus.text = "Не удалось проверить обновления (нет интернета?)"
                    release.versionCode > current -> {
                        updateStatus.text = "Доступна новая версия 1.${release.versionCode}"
                        askToUpdate(release)
                    }
                    else -> updateStatus.text = "✅ У вас последняя версия"
                }
            }
        }.start()
    }

    private fun askToUpdate(release: Updater.Release) {
        val mb = if (release.apkSize > 0) " (около ${release.apkSize / 1_000_000} МБ)" else ""
        AlertDialog.Builder(this)
            .setTitle("Доступно обновление")
            .setMessage("Вышла новая версия 1.${release.versionCode}. Скачать и установить$mb?\n\nНастройки и разрешения сохранятся.")
            .setPositiveButton("Обновить") { _, _ -> startUpdate(release) }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun startUpdate(release: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            // Один раз нужно разрешить приложению устанавливать обновления
            waitingPermissionFor = release
            Toast.makeText(this, "Разрешите «Установку неизвестных приложений» и вернитесь назад", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        if (downloading) return
        downloading = true
        updateStatus.text = "Скачиваю… 0%"
        Thread {
            try {
                val apk = Updater.download(this, release) { pct ->
                    runOnUiThread { updateStatus.text = "Скачиваю… $pct%" }
                }
                runOnUiThread { updateStatus.text = "Устанавливаю… подтвердите обновление в окне Android" }
                Updater.install(this, apk)
            } catch (e: Exception) {
                runOnUiThread { updateStatus.text = "Ошибка обновления: ${e.message}" }
            } finally {
                downloading = false
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        waitingPermissionFor?.let { release ->
            if (packageManager.canRequestPackageInstalls()) {
                waitingPermissionFor = null
                startUpdate(release)
            }
        }
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

    // ---------------- Голос ----------------

    private fun initTts() {
        tts?.shutdown()
        ttsOk = false
        val prefs = Prefs.get(this)
        tts = Voices.create(this, Voices.enginePref(prefs)) { st ->
            val t = tts
            ttsOk = st == TextToSpeech.SUCCESS && t != null
            if (ttsOk && t != null) {
                val r = t.setLanguage(Voices.RU)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Toast.makeText(this, "В этом движке нет русского голоса — нажмите «Скачать русские голоса» или выберите другой движок", Toast.LENGTH_LONG).show()
                }
            }
            runOnUiThread { refreshVoiceLabels() }
        }
    }

    private fun refreshVoiceLabels() {
        val prefs = Prefs.get(this)
        val t = tts
        val enginePkg = Voices.enginePref(prefs)
        val engines = t?.engines.orEmpty()
        val engineLabel = if (enginePkg == null) {
            val def = t?.defaultEngine
            "по умолчанию" + (engines.firstOrNull { it.name == def }?.label?.let { " ($it)" } ?: "")
        } else engines.firstOrNull { it.name == enginePkg }?.label ?: enginePkg
        engineButton.text = "Движок: $engineLabel"

        val voiceName = prefs.getString(Prefs.KEY_VOICE, null)
        val voices = if (ttsOk && t != null) Voices.russianVoices(t) else emptyList()
        val idx = voices.indexOfFirst { it.name == voiceName }
        voiceButton.text = when {
            voiceName == null || idx < 0 -> "Голос: стандартный"
            else -> "Голос: " + Voices.describe(voices[idx], idx).substringBefore("\n")
        }
    }

    private fun chooseEngine() {
        val t = tts ?: return
        val engines = t.engines
        val labels = listOf("По умолчанию (как в системе)") + engines.map { it.label }
        val prefs = Prefs.get(this)
        val current = Voices.enginePref(prefs)
        val checked = if (current == null) 0 else engines.indexOfFirst { it.name == current } + 1
        AlertDialog.Builder(this)
            .setTitle("Движок синтеза речи")
            .setSingleChoiceItems(labels.toTypedArray<CharSequence>(), checked) { dialog, which ->
                val e = prefs.edit().remove(Prefs.KEY_VOICE)
                if (which == 0) e.remove(Prefs.KEY_ENGINE) else e.putString(Prefs.KEY_ENGINE, engines[which - 1].name)
                e.apply()
                dialog.dismiss()
                initTts()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun chooseVoice() {
        val t = tts
        if (t == null || !ttsOk) {
            Toast.makeText(this, "Синтез речи ещё загружается, попробуйте через секунду", Toast.LENGTH_SHORT).show()
            return
        }
        val voices = Voices.russianVoices(t)
        if (voices.isEmpty()) {
            Toast.makeText(this, "В этом движке нет русских голосов. Нажмите «Скачать русские голоса» или выберите другой движок.", Toast.LENGTH_LONG).show()
            return
        }
        val prefs = Prefs.get(this)
        val labels = listOf("Стандартный") + voices.mapIndexed { i, v -> Voices.describe(v, i) }
        val currentName = prefs.getString(Prefs.KEY_VOICE, null)
        val checked = voices.indexOfFirst { it.name == currentName } + 1
        AlertDialog.Builder(this)
            .setTitle("Голос (нажмите, чтобы прослушать)")
            .setSingleChoiceItems(labels.toTypedArray<CharSequence>(), checked) { _, which ->
                if (which == 0) prefs.edit().remove(Prefs.KEY_VOICE).apply()
                else prefs.edit().putString(Prefs.KEY_VOICE, voices[which - 1].name).apply()
                preview()
            }
            .setPositiveButton("Готово") { _, _ -> refreshVoiceLabels() }
            .setOnDismissListener { refreshVoiceLabels() }
            .show()
    }

    private fun preview() {
        val t = tts
        if (t == null || !ttsOk) {
            Toast.makeText(this, "Синтез речи недоступен", Toast.LENGTH_LONG).show()
            return
        }
        Voices.apply(t, Prefs.get(this))
        t.speak("Хотите сказать, я, сильнейший рыцарь севера, сражён этим ничтожным выродком?!", TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
