package com.manhwavoice.reader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manhwavoice.reader.AppController
import com.manhwavoice.reader.FishCatalog
import com.manhwavoice.reader.FishTts
import com.manhwavoice.reader.FishVoice
import com.manhwavoice.reader.Prefs
import com.manhwavoice.reader.Voices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VoiceScreen(c: AppController, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(if (c.isFish) 1 else 0) }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable { c.stopAudio(); onBack() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.ArrowBack, contentDescription = "Назад", tint = Palette.TextMain) }
            Spacer(Modifier.width(6.dp))
            Text("Выбор голоса", color = Palette.TextMain, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        Segmented(listOf("📱 Голос телефона", "🐟 Fish Audio"), tab) { c.stopAudio(); tab = it }
        Spacer(Modifier.height(12.dp))
        if (tab == 0) SystemVoicesTab(c) else FishTab(c)
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Surface)
            .padding(4.dp)
    ) {
        options.forEachIndexed { i, s ->
            val sel = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (sel) Palette.brand else androidx.compose.ui.graphics.SolidColor(Color.Transparent))
                    .clickable { onSelect(i) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(s, color = if (sel) Color.White else Palette.TextDim, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            }
        }
    }
}

// ---------------- Голос телефона ----------------

@Composable
private fun SystemVoicesTab(c: AppController) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (c.isFish) item {
            Banner("Сейчас читает голос Fish Audio", "Выберите голос ниже — и чтение переключится на голос телефона.")
        }
        item {
            SectionCard("Движок синтеза речи", "⚙") {
                val current = c.systemEnginePref
                RadioRow("По умолчанию", c.engineLabel(null).removePrefix("По умолчанию").removePrefix(" · "), current == null) { c.chooseSystemEngine(null) }
                c.engines.forEach { e ->
                    RadioRow(e.label, e.name, current == e.name) { c.chooseSystemEngine(e.name) }
                }
            }
        }
        item {
            SectionCard("Русские голоса", "🗣") {
                val chosen = if (c.isFish) "—" else c.systemVoicePref
                if (!c.ttsOk) {
                    Text("Синтез речи загружается… Если долго ничего не происходит — установите русский голос.", color = Palette.TextDim, fontSize = 14.sp)
                } else {
                    RadioRow("Стандартный", "как в настройках телефона", chosen == null) { c.chooseSystemVoice(null) }
                    if (c.systemVoices.isEmpty()) {
                        Text("В этом движке нет русских голосов. Скачайте их или выберите другой движок.", color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                    c.systemVoices.forEachIndexed { i, v ->
                        val d = Voices.describe(v, i)
                        RadioRow(d.substringBefore("\n"), d.substringAfter("\n"), chosen == v.name) { c.chooseSystemVoice(v.name) }
                    }
                }
            }
        }
        item {
            SectionCard("Звучание", "🎚") {
                ValueSlider("Высота голоса", c.int(Prefs.KEY_PITCH, 100), 50..200, { "$it%" }) { c.setInt(Prefs.KEY_PITCH, it) }
                Spacer(Modifier.height(6.dp))
                GradientButton("▶  Прослушать", { c.previewSystem() }, Modifier.fillMaxWidth(), height = 48.dp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("Скачать голоса", { c.installVoices() }, Modifier.weight(1f), height = 48.dp)
                SoftButton("Настройки синтеза", { c.openTtsSettings() }, Modifier.weight(1f), height = 48.dp)
            }
        }
        item {
            Text(
                "Больше русских голосов без интернета — бесплатный движок RHVoice из Google Play.",
                color = Palette.TextDim, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun RadioRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Palette.SurfaceHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .background(if (selected) Palette.brand else androidx.compose.ui.graphics.SolidColor(Palette.Outline)),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(if (selected) 8.dp else 16.dp).clip(CircleShape).background(if (selected) Color.White else Palette.Surface))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.TextMain, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Banner(title: String, text: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Palette.SurfaceHigh,
        border = BorderStroke(1.dp, Palette.Amber.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Palette.Amber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(text, color = Palette.TextMain, fontSize = 13.sp)
        }
    }
}

// ---------------- Fish Audio ----------------

@Composable
private fun FishTab(c: AppController) {
    val scope = rememberCoroutineScope()
    val key = c.fishKey
    var query by rememberSaveable { mutableStateOf("") }
    var onlyRu by rememberSaveable { mutableStateOf(true) }
    var sort by rememberSaveable { mutableStateOf(FishCatalog.Sort.SCORE) }
    val voices = remember { mutableStateListOf<FishVoice>() }
    var page by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun loadPage() {
        if (loading || key.isEmpty()) return
        loading = true
        error = null
        val r = withContext(Dispatchers.IO) { runCatching { FishCatalog.list(key, page + 1, query, sort, onlyRu) } }
        r.onSuccess { p ->
            voices.addAll(p.items.filter { v -> voices.none { it.id == v.id } })
            page++
            hasMore = p.hasMore
        }.onFailure {
            error = (it as? FishTts.FishError)?.message ?: "нет связи с Fish Audio"
        }
        loading = false
    }

    // Новый поиск / фильтр / сортировка — загрузить каталог заново (с небольшой задержкой при вводе)
    LaunchedEffect(key, onlyRu, sort, query) {
        if (key.isEmpty()) return@LaunchedEffect
        delay(400)
        voices.clear()
        page = 0
        hasMore = true
        loading = false
        loadPage()
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Box(Modifier.padding(horizontal = 16.dp)) { FishSwitchCard(c) } }
        item { Box(Modifier.padding(horizontal = 16.dp)) { KeyCard(c) } }
        if (key.isNotEmpty()) {
            item { Box(Modifier.padding(horizontal = 16.dp)) { CurrentFishVoice(c) } }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(6.dp))
                    Text("Каталог голосов", color = Palette.TextMain, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Голоса с fish.audio — нажмите ▶, чтобы послушать", color = Palette.TextDim, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Поиск: рассказчик, аниме, девушка…", color = Palette.TextDim) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Palette.TextDim) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Palette.Violet,
                            unfocusedBorderColor = Palette.Outline,
                            focusedContainerColor = Palette.Surface,
                            unfocusedContainerColor = Palette.Surface,
                            focusedTextColor = Palette.TextMain,
                            unfocusedTextColor = Palette.TextMain,
                            cursorColor = Palette.Pink
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleChip("🇷🇺 Русские", onlyRu) { onlyRu = !onlyRu }
                        FishCatalog.Sort.values().forEach { s -> ToggleChip(s.label, sort == s) { sort = s } }
                    }
                }
            }
            val selectedId = c.fishVoiceId
            items(voices, key = { it.id }) { v ->
                VoiceRow(
                    v = v,
                    selected = c.isFish && v.id == selectedId,
                    playing = c.playingId == v.id,
                    onPlay = { c.playVoice(v) },
                    onSelect = { c.selectFishVoice(v) }
                )
            }
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    when {
                        loading -> CircularProgressIndicator(color = Palette.Pink, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                        error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Не удалось загрузить: $error", color = Palette.Red, fontSize = 14.sp)
                            Spacer(Modifier.height(8.dp))
                            SoftButton("Повторить", { scope.launch { loadPage() } }, height = 44.dp)
                        }
                        voices.isEmpty() -> Text("Ничего не нашлось. Попробуйте другой запрос или выключите «Русские».", color = Palette.TextDim, fontSize = 14.sp)
                        hasMore -> SoftButton("Показать ещё", { scope.launch { loadPage() } }, height = 44.dp)
                        else -> Text("Это все голоса по запросу", color = Palette.TextDim, fontSize = 13.sp)
                    }
                }
            }
            item { Box(Modifier.padding(horizontal = 16.dp)) { ManualVoiceCard(c) } }
        }
    }
}

@Composable
private fun FishSwitchCard(c: AppController) {
    if (c.isFish) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Palette.Surface,
            border = BorderStroke(1.dp, Palette.Green.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("✓", color = Palette.Green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Читает голос Fish Audio", color = Palette.TextMain, fontWeight = FontWeight.SemiBold)
                    Text("Нужен интернет. Без связи — голос телефона.", color = Palette.TextDim, fontSize = 12.sp)
                }
                Text(
                    "Выключить", color = Palette.TextDim, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { c.useFish(false) }.padding(8.dp)
                )
            }
        }
    } else {
        GradientButton("🐟  Читать голосом Fish Audio", { c.useFish(true) }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun KeyCard(c: AppController) {
    val key = c.fishKey
    SectionCard("API-ключ", "🔑") {
        if (key.isEmpty()) {
            Text(
                "Скопируйте ключ (из сообщения или с сайта fish.audio → API Keys) и нажмите «Вставить».",
                color = Palette.TextDim, fontSize = 14.sp
            )
        } else {
            Text(mask(key), color = Palette.TextMain, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        }
        c.fishStatus?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = if (it.startsWith("❌")) Palette.Red else Palette.Green, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GradientButton("📋  Вставить", { c.pasteKey() }, Modifier.weight(1f), height = 48.dp)
            if (key.isNotEmpty()) SoftButton("Проверить", { c.checkKey() }, Modifier.weight(1f), height = 48.dp)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Получить ключ", color = Palette.VioletLight, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { c.openUrl("https://fish.audio/app/api-keys/") }.padding(6.dp)
            )
            Spacer(Modifier.weight(1f))
            if (key.isNotEmpty()) Text(
                "Удалить", color = Palette.TextDim, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { c.clearKey() }.padding(6.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Бесплатная модель", color = Palette.TextMain, fontSize = 15.sp)
                Text("Без оплаты, но может работать медленнее", color = Palette.TextDim, fontSize = 12.sp)
            }
            Switch(
                checked = c.fishFree,
                onCheckedChange = { c.setFishFree(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.Violet, checkedThumbColor = Color.White)
            )
        }
        Text("Ключ хранится только на этом телефоне.", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun mask(k: String): String =
    if (k.length <= 12) "••••••" else k.take(8) + "••••••" + k.takeLast(4)

@Composable
private fun CurrentFishVoice(c: AppController) {
    val recents = c.recents()
    SectionCard("Выбранный голос", "🎧") {
        val title = c.prefs.getString(Prefs.KEY_FISH_VOICE_TITLE, null).also { c.tick }
        val id = c.fishVoiceId
        Row(verticalAlignment = Alignment.CenterVertically) {
            VoiceAvatar(c.prefs.getString(Prefs.KEY_FISH_VOICE_COVER, null), title ?: "Fish", 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title ?: if (id.isBlank()) "Голос по умолчанию" else "Свой голос", color = Palette.TextMain, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (id.isBlank()) "выберите голос в каталоге ниже" else id.take(12) + "…", color = Palette.TextDim, fontSize = 12.sp)
            }
            val pid = c.playingId
            PlayCircle(pid != null && recents.none { it.id == pid }, { c.previewCurrent() })
        }
        if (recents.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Недавние", color = Palette.TextDim, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recents, key = { it.id }) { r ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(68.dp).clip(RoundedCornerShape(12.dp)).clickable { c.selectFishVoice(r) }.padding(4.dp)
                    ) {
                        Box(
                            Modifier.clip(CircleShape)
                                .background(if (r.id == id && c.isFish) Palette.brand else androidx.compose.ui.graphics.SolidColor(Color.Transparent))
                                .padding(2.dp)
                        ) { VoiceAvatar(r.cover, r.title, 48.dp) }
                        Spacer(Modifier.height(4.dp))
                        Text(r.title, color = Palette.TextMain, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(v: FishVoice, selected: Boolean, playing: Boolean, onPlay: () -> Unit, onSelect: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) Palette.SurfaceHigh else Palette.Surface,
        border = BorderStroke(1.dp, if (selected) Palette.Violet else Palette.Outline.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            VoiceAvatar(v.cover, v.title, 54.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(v.title, color = Palette.TextMain, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        if (v.author.isNotBlank()) append(v.author).append(" · ")
                        append("❤ ").append(FishCatalog.compact(v.likes))
                        append("  ▶ ").append(FishCatalog.compact(v.uses))
                    },
                    color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (v.tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        v.tags.take(2).forEach { t ->
                            Text(
                                t, color = Palette.VioletLight, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Bg).padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            PlayCircle(playing, onPlay, size = 40.dp)
            Spacer(Modifier.width(8.dp))
            if (selected) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.Green.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Text("✓", color = Palette.Green, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Box(
                    Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.brand).clickable(onClick = onSelect).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Выбрать", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun ManualVoiceCard(c: AppController) {
    var open by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    Column {
        Text(
            if (open) "Скрыть" else "Есть ссылка на голос? Вставить вручную",
            color = Palette.VioletLight, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 8.dp)
        )
        AnimatedVisibility(open) {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("https://fish.audio/m/…", color = Palette.TextDim) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Palette.Violet,
                        unfocusedBorderColor = Palette.Outline,
                        focusedTextColor = Palette.TextMain,
                        unfocusedTextColor = Palette.TextMain
                    )
                )
                Spacer(Modifier.height(8.dp))
                GradientButton("Сохранить голос", {
                    if (text.isNotBlank()) {
                        c.setFishVoiceManually(text)
                        c.useFish(true)
                        c.toast("Голос сохранён")
                    }
                }, Modifier.fillMaxWidth(), height = 46.dp)
            }
        }
    }
}
