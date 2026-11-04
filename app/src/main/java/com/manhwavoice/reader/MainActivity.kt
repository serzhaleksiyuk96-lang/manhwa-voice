package com.manhwavoice.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.manhwavoice.reader.ui.AppTheme
import com.manhwavoice.reader.ui.MainScreen
import com.manhwavoice.reader.ui.Palette
import com.manhwavoice.reader.ui.VoiceScreen

class MainActivity : ComponentActivity() {

    private lateinit var controller: AppController

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        controller = AppController(this)
        controller.initTts()
        controller.checkUpdates()
        setContent { AppTheme { AppRoot(controller) } }
    }

    override fun onResume() {
        super.onResume()
        controller.onResume()
    }

    override fun onPause() {
        controller.stopAudio()
        super.onPause()
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }
}

@Composable
private fun AppRoot(c: AppController) {
    var screen by rememberSaveable { mutableStateOf("main") }
    BackHandler(enabled = screen != "main") {
        c.stopAudio()
        screen = "main"
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .background(
                Brush.radialGradient(
                    colors = listOf(Palette.Violet.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(150f, 80f),
                    radius = 1100f
                )
            )
    ) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen"
        ) { s ->
            if (s == "main") MainScreen(c) { screen = "voices" }
            else VoiceScreen(c) { screen = "main" }
        }
    }
    c.offeredUpdate?.let { r ->
        AlertDialog(
            onDismissRequest = { c.dismissUpdate() },
            containerColor = Palette.Surface,
            title = { Text("Доступно обновление", color = Palette.TextMain, fontWeight = FontWeight.Bold) },
            text = {
                val mb = if (r.apkSize > 0) " (около ${r.apkSize / 1_000_000} МБ)" else ""
                Text("Вышла версия 1.${r.versionCode}. Скачать и установить$mb?\nНастройки и разрешения сохранятся.", color = Palette.TextMain)
            },
            confirmButton = { TextButton(onClick = { c.startUpdate(r) }) { Text("Обновить", color = Palette.Pink, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { c.dismissUpdate() }) { Text("Позже", color = Palette.TextDim) } }
        )
    }
}
