package com.manhwavoice.reader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manhwavoice.reader.AppController
import com.manhwavoice.reader.Prefs
import java.util.Locale

@Composable
fun MainScreen(c: AppController, openVoices: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Header(c.versionName)
        StatusHero(c)
        VoiceCard(c, openVoices)
        ReadingCard(c)
        UpdateCard(c)
        HelpCard()
        Text(
            "Снимки экрана обрабатываются только на телефоне и никуда не отправляются.\n" +
                "При голосе Fish Audio на их сервер уходит только текст реплик.",
            color = Palette.TextDim,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun Header(version: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Palette.brand),
            contentAlignment = Alignment.Center
        ) { Text("🎧", fontSize = 26.sp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Озвучка манхвы", style = MaterialTheme.typography.headlineSmall, color = Palette.TextMain)
            Text("читает пузыри вслух и листает сама", color = Palette.TextDim, fontSize = 13.sp)
        }
        Pill("v$version")
    }
}

@Composable
private fun StatusHero(c: AppController) {
    val on = c.serviceEnabled
    var help by rememberSaveable { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(if (on) Palette.hero else Palette.heroOff)
    ) {
        // декоративные «пузыри» на фоне
        Box(Modifier.size(170.dp).offset(x = 230.dp, y = (-60).dp).clip(CircleShape).background(Color.White.copy(alpha = 0.07f)))
        Box(Modifier.size(90.dp).offset(x = 290.dp, y = 120.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.05f)))
        Column(Modifier.padding(22.dp)) {
            Text(if (on) "● ВКЛЮЧЕНО" else "● ВЫКЛЮЧЕНО", color = if (on) Color(0xFFBBF7D0) else Palette.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (on) "Готово к чтению" else "Служба выключена",
                color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (on) "Откройте главу в Opera на весь экран и нажмите ▶ на плавающей кнопке."
                else "Включите «Озвучка манхвы» в спецвозможностях — без этого плавающая кнопка не появится.",
                color = Color.White.copy(alpha = 0.88f), fontSize = 15.sp
            )
            Spacer(Modifier.height(16.dp))
            if (on) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "🔊 " + c.currentVoiceTitle(),
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.16f))
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .clickable { c.openAccessibility() },
                    contentAlignment = Alignment.Center
                ) { Text("Включить службу", color = Palette.VioletDeep, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (help) "Скрыть подсказку" else "Не даёт включить?",
                    color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { help = !help }.padding(vertical = 6.dp)
                )
                AnimatedVisibility(help) {
                    Column {
                        Text(
                            "На Android 13+ приложения не из Google Play по умолчанию не могут включать спецвозможности:\n" +
                                "1. Попробуйте включить службу — появится «Настройка ограничена».\n" +
                                "2. Откройте сведения о приложении → ⋮ → «Разрешить ограниченные настройки».\n" +
                                "3. Вернитесь в спецвозможности и включите службу.",
                            color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        SoftButton("Сведения о приложении", { c.openAppDetails() }, Modifier.fillMaxWidth(), height = 46.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceCard(c: AppController, openVoices: () -> Unit) {
    SectionCard("Голос", "🎙") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VoiceAvatar(c.currentVoiceCover(), c.currentVoiceTitle(), 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(c.currentVoiceTitle(), color = Palette.TextMain, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.currentVoiceSubtitle(), color = Palette.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            PlayCircle(c.playingId != null, { c.previewCurrent() })
        }
        Spacer(Modifier.height(16.dp))
        GradientButton("✨  Выбрать голос", openVoices, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ReadingCard(c: AppController) {
    SectionCard("Чтение", "📖") {
        ValueSlider("Скорость речи", c.int(Prefs.KEY_RATE, 100), 50..200, { "$it%" }) { c.setInt(Prefs.KEY_RATE, it) }
        ValueSlider("Шаг прокрутки", c.int(Prefs.KEY_SCROLL, 60), 40..75, { "$it% экрана" }) { c.setInt(Prefs.KEY_SCROLL, it) }
        ValueSlider("Пауза после текста", c.int(Prefs.KEY_PAUSE_TEXT, Prefs.DEFAULT_PAUSE_TEXT), 0..3000, ::seconds) { c.setInt(Prefs.KEY_PAUSE_TEXT, it) }
        ValueSlider("Пауза на кадрах без текста", c.int(Prefs.KEY_PAUSE_EMPTY, Prefs.DEFAULT_PAUSE_EMPTY), 200..4000, ::seconds) { c.setInt(Prefs.KEY_PAUSE_EMPTY, it) }
        Spacer(Modifier.height(6.dp))
        Text("Порядок реплик", color = Palette.TextMain, fontSize = 15.sp)
        Text(
            "Пузыри рядом друг с другом: в манхве и вебтунах читают слева направо, в японской манге — справа налево",
            color = Palette.TextDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        val rtl = c.bool(Prefs.KEY_RTL, false)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip("Манхва  →", !rtl) { c.setBool(Prefs.KEY_RTL, false) }
            ToggleChip("Манга  ←", rtl) { c.setBool(Prefs.KEY_RTL, true) }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Листать заранее", color = Palette.TextMain, fontSize = 15.sp)
                Text(
                    "Пока звучит последняя реплика, страница уже листается и распознаётся дальше — паузы между экранами почти нет",
                    color = Palette.TextDim, fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = c.bool(Prefs.KEY_EARLY_SCROLL, true),
                onCheckedChange = { c.setBool(Prefs.KEY_EARLY_SCROLL, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.Violet, checkedThumbColor = Color.White)
            )
        }
    }
}

private fun seconds(ms: Int) = String.format(Locale.US, "%.1f с", ms / 1000f)

@Composable
private fun UpdateCard(c: AppController) {
    SectionCard("Обновления", "🚀") {
        Text(c.updateStatus.ifBlank { "Версия ${c.versionName}" }, color = Palette.TextMain, fontSize = 15.sp)
        val pct = c.downloadPct
        if (pct != null) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                color = Palette.Pink,
                trackColor = Palette.Outline
            )
            Spacer(Modifier.height(4.dp))
            Text("$pct%", color = Palette.TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        SoftButton("Проверить обновления", { c.checkUpdates() }, Modifier.fillMaxWidth(), height = 48.dp)
    }
}

@Composable
private fun HelpCard() {
    var open by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        "Как пользоваться", "💡",
        trailing = { Text(if (open) "▲" else "▼", color = Palette.TextDim, modifier = Modifier.clickable { open = !open }.padding(8.dp)) }
    ) {
        val steps = listOf(
            "Откройте главу в Opera и включите полноэкранный режим.",
            "Нажмите на круглую кнопку ▶ — приложение начнёт читать и само листать вниз.",
            "Нажмите на кнопку ещё раз — пауза. Перетащите её пальцем, если мешает.",
            "Для японской манги выберите в настройках «Порядок реплик: Манга ←».",
            "Панели сайта сверху и снизу приложение замечает само после первой прокрутки и текст под ними не читает.",
            "Долгое нажатие — прочитать текущий экран заново.",
            "В конце страницы прозвучит «Конец главы» — откройте следующую и снова нажмите ▶."
        )
        val shown = if (open) steps else steps.take(2)
        shown.forEachIndexed { i, s ->
            Row(Modifier.padding(vertical = 5.dp)) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(Palette.SurfaceHigh),
                    contentAlignment = Alignment.Center
                ) { Text("${i + 1}", color = Palette.VioletLight, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                Text(s, color = Palette.TextMain, fontSize = 14.sp)
            }
        }
        if (!open) {
            Text(
                "Показать всё",
                color = Palette.VioletLight, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                modifier = Modifier.clickable { open = true }.padding(top = 6.dp)
            )
        }
    }
}
