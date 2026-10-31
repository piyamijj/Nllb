package com.piyamijj.tercuman.nllb.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.piyamijj.tercuman.nllb.BuildConfig
import com.piyamijj.tercuman.nllb.LangMode
import com.piyamijj.tercuman.nllb.MainViewModel
import com.piyamijj.tercuman.nllb.TranscriptEntry
import com.piyamijj.tercuman.nllb.UiState

@Composable
fun HomeScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current

    SideEffect { view.keepScreenOn = state.listening }

    var showInfo by remember { mutableStateOf(false) }
    var pendingEnroll by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingEnroll?.let { vm.enroll(it); pendingEnroll = null } ?: vm.start()
        } else {
            pendingEnroll = null
            vm.showError("Mikrofon izni olmadan dinleyemem.")
        }
    }

    LaunchedEffect(state.entries.size) {
        if (state.entries.isNotEmpty()) {
            listState.animateScrollToItem(state.entries.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Header(onInfo = { showInfo = true })

        ModeRow(mode = state.mode, onSelect = vm::setMode)

        EnrollRow(
            enrolledMe = state.enrolledMe,
            enrolledOther = state.enrolledOther,
            enrolling = state.enrolling,
            enabled = !state.loading,
            onEnroll = { name ->
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    vm.enroll(name)
                } else {
                    pendingEnroll = name
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onReset = vm::resetSpeakers,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.entries.isEmpty()) {
                EmptyState(listening = state.listening)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.entries, key = { it.id }) { entry ->
                        EntryCard(
                            entry,
                            pendingTranslation = state.translating &&
                                entry.translation.isBlank(),
                        )
                    }
                }
            }
        }

        state.error?.let { message ->
            ErrorCard(message = message, onDismiss = vm::dismissError)
        }

        BottomBar(
            state = state,
            onToggle = {
                if (state.listening) {
                    vm.stop()
                } else {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) vm.start()
                    else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onClear = vm::clear,
        )
    }

    if (showInfo) {
        val speakers = listOfNotNull(
            "Ben".takeIf { state.enrolledMe },
            "Karşı".takeIf { state.enrolledOther },
        ).joinToString(", ").ifEmpty { "kayıt yok" }
        InfoDialog(
            context = context,
            bundled = vm.modelsBundled(),
            speakers = speakers,
            onDismiss = { showInfo = false },
        )
    }
}

@Composable
private fun Header(onInfo: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Sesli Tercüman",
                    color = TextMain,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                VariantBadge(BuildConfig.VARIANT)
            }
            Text(
                text = "Çevrimdışı · Türkçe ⇄ Farsça · Faz 3",
                color = TextMuted,
                fontSize = 12.sp,
            )
        }
        TextButton(onClick = onInfo) {
            Text("Bilgi", color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun VariantBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Accent.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text = text, color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ModeRow(mode: LangMode, onSelect: (LangMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LangMode.entries.forEach { item ->
            val selected = item == mode
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) Accent.copy(alpha = 0.18f) else CardBg)
                    .border(
                        width = 1.dp,
                        color = if (selected) Accent else Color.Transparent,
                        shape = RoundedCornerShape(20.dp),
                    )
                    .clickable { onSelect(item) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = item.label,
                    color = if (selected) Accent else TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun EnrollRow(
    enrolledMe: Boolean,
    enrolledOther: Boolean,
    enrolling: String?,
    enabled: Boolean,
    onEnroll: (String) -> Unit,
    onReset: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EnrollChip(
                text = if (enrolledMe) "Ben ✓" else "Ben'i tanıt",
                active = enrolledMe,
                enabled = enabled && enrolling == null,
                onClick = { onEnroll("ben") },
            )
            EnrollChip(
                text = if (enrolledOther) "Karşı ✓" else "Karşı'yı tanıt",
                active = enrolledOther,
                enabled = enabled && enrolling == null,
                onClick = { onEnroll("karsi") },
            )
            if (enrolledMe || enrolledOther) {
                TextButton(onClick = onReset, enabled = enrolling == null) {
                    Text("Sıfırla", color = TextMuted, fontSize = 12.sp)
                }
            }
        }
        if (enrolling != null) {
            Text(
                text = "Kaydediliyor… normal konuşun (4 sn)",
                color = Accent,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun EnrollChip(text: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) Ok.copy(alpha = 0.14f) else CardBg)
            .border(
                width = 1.dp,
                color = if (active) Ok else Color.Transparent,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            color = when {
                !enabled -> Pending
                active -> Ok
                else -> TextMuted
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyState(listening: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (listening) "Dinliyorum…" else "Hazır",
            color = if (listening) Accent else TextMain,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (listening) {
                "Konuşmaya başla. Cümle bitince yazı burada belirecek."
            } else {
                "Başlat'a bas ve normal konuş.\nTürkçe ve Farsça otomatik algılanır."
            },
            color = TextMuted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EntryCard(entry: TranscriptEntry, pendingTranslation: Boolean) {
    val color = when (entry.lang) {
        "tr" -> TrColor
        "fa" -> FaColor
        else -> Pending
    }
    val label = when {
        entry.lang == "tr" -> "TR"
        entry.lang == "fa" -> "FA"
        entry.lang.isNotBlank() -> entry.lang.uppercase()
        else -> "?"
    }
    val speakerLabel = when (entry.speaker) {
        "ben" -> "Ben"
        "karsi" -> "Karşı"
        else -> null
    }
    val cost = if (entry.mtMs > 0) {
        "${entry.elapsedMs} ms + ${"%.1f".format(entry.mtMs / 1000.0)} sn"
    } else {
        "${entry.elapsedMs} ms"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(label, color)
                if (speakerLabel != null) {
                    Spacer(Modifier.width(6.dp))
                    Badge(speakerLabel, Ok)
                }
                Spacer(Modifier.weight(1f))
                Text(cost, color = TextMuted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(entry.text, color = TextMain, fontSize = 18.sp, lineHeight = 26.sp)
            if (entry.translation.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "→ " + entry.translation,
                    color = Accent,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
            } else if (pendingTranslation) {
                Spacer(Modifier.height(8.dp))
                Text("Çevriliyor…", color = TextMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Danger.copy(alpha = 0.14f))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = Color(0xFFFFC9C9),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
        )
        TextButton(onClick = onDismiss) {
            Text("Kapat", color = Danger, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BottomBar(state: UiState, onToggle: () -> Unit, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 6.dp, bottom = 10.dp)
    ) {
        when {
            state.loading -> {
                Text(state.loadingNote, color = Accent, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Accent,
                    trackColor = Color(0xFF1B2440),
                )
            }

            state.listening -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (state.speechActive || state.decoding) Ok else Accent)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = statusText(state),
                        color = if (state.speechActive || state.decoding) Ok else TextMuted,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LevelBar(state.level)
            }

            else -> {
                Text(
                    text = "Hazır · Whisper base · ${BuildConfig.VARIANT} · Piper",
                    color = TextMuted,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onToggle,
                modifier = Modifier.weight(1f).height(54.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = !state.loading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.listening) Danger else Accent,
                    contentColor = if (state.listening) Color.White else Color(0xFF06202A),
                    disabledContainerColor = CardBg,
                ),
            ) {
                Text(
                    text = if (state.listening) "Durdur" else "Başlat",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.height(54.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF2A3550)),
                enabled = state.entries.isNotEmpty(),
            ) {
                Text("Temizle", color = TextMuted, fontSize = 14.sp)
            }
        }
    }
}

private fun statusText(state: UiState): String = when {
    state.translating -> "Çevriliyor…"
    state.decoding -> "Yazıya dökülüyor…"
    state.speaking -> "Seslendiriliyor…"
    state.speechActive -> "Konuşma algılandı"
    else -> "Dinliyorum…"
}

@Composable
private fun LevelBar(level: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFF1B2440))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.coerceIn(0.02f, 1f))
                .clip(RoundedCornerShape(3.dp))
                .background(Accent)
        )
    }
}

@Composable
private fun InfoDialog(context: Context, bundled: Boolean, speakers: String, onDismiss: () -> Unit) {
    val device = remember { readDeviceInfo(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        title = { Text("Bilgi", color = TextMain, fontSize = 18.sp) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Kapat", color = Accent) }
        },
        text = {
            Column {
                InfoRow("Çeviri motoru", BuildConfig.MODEL_LABEL)
                InfoRow("Konuşma tanıma", "Whisper base int8")
                InfoRow("VAD", "Silero VAD")
                InfoRow("Seslendirme", "Piper (dfki / ganji)")
                InfoRow("Konuşmacı", speakers)
                InfoRow("Model dosyaları", if (bundled) "APK içinde ✓" else "eksik ✗")
                Spacer(Modifier.height(10.dp))
                InfoRow("Cihaz", device.model)
                InfoRow("Android", device.android)
                InfoRow("RAM", device.ram)
                InfoRow("Çekirdek", device.cores)
                InfoRow("ABI", device.abi)
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private data class DeviceInfo(
    val model: String,
    val android: String,
    val ram: String,
    val cores: String,
    val abi: String,
)

private fun readDeviceInfo(context: Context): DeviceInfo {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    val totalGb = mi.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
    val cores = Runtime.getRuntime().availableProcessors()

    return DeviceInfo(
        model = "${Build.MANUFACTURER} ${Build.MODEL}",
        android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        ram = String.format("%.1f GB", totalGb),
        cores = "$cores",
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "-",
    )
}
