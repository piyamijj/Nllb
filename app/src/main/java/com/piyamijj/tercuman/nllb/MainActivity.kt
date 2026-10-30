package com.piyamijj.tercuman.nllb

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val Bg = Color(0xFF0B1020)
private val CardBg = Color(0xFF141B33)
private val Accent = Color(0xFF4FD1C5)
private val TextMain = Color(0xFFE8EEF9)
private val TextMuted = Color(0xFF8FA0BF)
private val Ok = Color(0xFF4ADE80)
private val Pending = Color(0xFF64748B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    HomeScreen()
                }
            }
        }
    }
}

@Composable
private fun HomeScreen() {
    val context = LocalContext.current

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    val device = remember { readDeviceInfo(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Sesli Tercüman",
                color = TextMain,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(10.dp))
            VariantBadge(BuildConfig.VARIANT)
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "Çevrimdışı · Türkçe ⇄ Farsça · Faz 0 iskeleti",
            color = TextMuted,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(20.dp))
        SectionCard(title = "Çeviri motoru") {
            InfoRow("Model", BuildConfig.MODEL_LABEL)
            InfoRow("Çalışma modu", "Tamamen cihazda (internet yok)")
            InfoRow("Mimari", "arm64-v8a · int8")
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(title = "Cihaz") {
            InfoRow("Model", device.model)
            InfoRow("Android", device.android)
            InfoRow("RAM", device.ram)
            InfoRow("Çekirdek", device.cores)
            InfoRow("ABI", device.abi)
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(title = "Boru hattı") {
            StatusRow("Arayüz (Compose)", true, "çalışıyor")
            StatusRow(
                "Mikrofon izni",
                micGranted,
                if (micGranted) "izin verildi" else "izin bekliyor"
            )
            StatusRow("Konuşma tanıma (Whisper base)", false, "Faz 1")
            StatusRow("Çeviri (${BuildConfig.VARIANT})", false, "Faz 2")
            StatusRow("Seslendirme (Piper)", false, "Faz 3")
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                if (!micGranted) {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = Color(0xFF06202A)
            )
        ) {
            Text(
                text = if (micGranted) "Mikrofon hazır ✓" else "Mikrofon iznini test et",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = "Bu sürüm yalnızca kurulum ve arayüz testidir. " +
                "Model dosyaları bir sonraki sürümde gömülecek.",
            color = TextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun VariantBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Accent.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = TextMuted, fontSize = 13.sp)
        Text(
            text = value,
            color = TextMain,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun StatusRow(label: String, done: Boolean, note: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (done) Ok else Pending)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = if (done) TextMain else TextMuted,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = note,
            color = if (done) Ok else Pending,
            fontSize = 11.sp
        )
    }
}

private data class DeviceInfo(
    val model: String,
    val android: String,
    val ram: String,
    val cores: String,
    val abi: String
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
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "-"
    )
}
