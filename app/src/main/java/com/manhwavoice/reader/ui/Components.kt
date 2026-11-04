package com.manhwavoice.reader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** Карточка раздела: градиентный значок, заголовок и содержимое. */
@Composable
fun SectionCard(
    title: String,
    emoji: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Palette.Surface,
        border = BorderStroke(1.dp, Palette.Outline.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Palette.brand),
                    contentAlignment = Alignment.Center
                ) { Text(emoji, fontSize = 17.sp) }
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.TextMain,
                    modifier = Modifier.weight(1f)
                )
                trailing?.invoke()
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/** Главная кнопка с градиентом. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 52.dp
) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) Palette.brand else SolidColor(Palette.Outline))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
    }
}

/** Спокойная кнопка (второстепенное действие). */
@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 52.dp) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.SurfaceHigh)
            .border(1.dp, Palette.Outline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Palette.TextMain, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
    }
}

/** Небольшая плашка со значением. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = Palette.VioletLight) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.SurfaceHigh)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

/** Переключаемый «чип» (фильтр). */
@Composable
fun ToggleChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Palette.brand else SolidColor(Palette.Surface))
            .border(1.dp, if (selected) Color.Transparent else Palette.Outline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            color = if (selected) Color.White else Palette.TextDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** Ползунок с подписью и значением. */
@Composable
fun ValueSlider(label: String, value: Int, range: IntRange, format: (Int) -> String, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Palette.TextMain, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Pill(format(value))
        }
        Slider(
            value = value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Palette.Pink,
                activeTrackColor = Palette.Violet,
                inactiveTrackColor = Palette.Outline
            )
        )
    }
}

/** Круглая кнопка «прослушать / остановить». */
@Composable
fun PlayCircle(playing: Boolean, onClick: () -> Unit, size: Dp = 44.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (playing) Palette.brand else SolidColor(Palette.SurfaceHigh))
            .border(1.dp, if (playing) Color.Transparent else Palette.Outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (playing) Text("■", color = Color.White, fontSize = 15.sp)
        else Icon(Icons.Filled.PlayArrow, contentDescription = "Прослушать", tint = Palette.TextMain)
    }
}

// ---------------- Картинки из интернета (обложки голосов) ----------------

private val imageCache = LruCache<String, ImageBitmap>(120)

private fun loadBitmap(url: String, maxSide: Int = 192): ImageBitmap? {
    val conn = URL(url).openConnection() as HttpURLConnection
    return try {
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "ManhwaVoice-Android")
        if (conn.responseCode != 200) return null
        val bytes = conn.inputStream.use { it.readBytes() }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scale = maxSide.toFloat() / maxOf(raw.width, raw.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).roundToInt().coerceAtLeast(1), (raw.height * scale).roundToInt().coerceAtLeast(1), true) else raw
        bmp.asImageBitmap()
    } catch (e: Exception) {
        null
    } finally {
        conn.disconnect()
    }
}

/** Круглая аватарка голоса: обложка из интернета или первая буква на градиенте. */
@Composable
fun VoiceAvatar(url: String?, title: String, size: Dp) {
    var image by remember(url) { mutableStateOf(url?.let { imageCache.get(it) }) }
    LaunchedEffect(url) {
        if (url != null && image == null) {
            val loaded = withContext(Dispatchers.IO) { loadBitmap(url) }
            if (loaded != null) {
                imageCache.put(url, loaded)
                image = loaded
            }
        }
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(Palette.brand),
        contentAlignment = Alignment.Center
    ) {
        val img = image
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape)
            )
        } else {
            Text(
                title.trim().firstOrNull()?.uppercase() ?: "🎙",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.4f).sp,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}
