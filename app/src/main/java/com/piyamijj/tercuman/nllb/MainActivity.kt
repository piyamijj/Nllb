package com.piyamijj.tercuman.nllb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.piyamijj.tercuman.nllb.ui.Bg
import com.piyamijj.tercuman.nllb.ui.HomeScreen

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val st = CrashLog.lastStage(this)
        val crash = CrashLog.read(this)
            ?: if (st != null && st != "done") "Uygulama şu aşamada aniden kapandı (yerel hata): " + st else null
        if (crash != null) {
            setContent {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0B1020))
                        .safeDrawingPadding()
                        .padding(16.dp)
                ) {
                    Text("Uygulama son açılışta çöktü. Bu metni gönderin:", color = Color.White)
                    Text(
                        crash.take(6000),
                        color = Color(0xFFFFB4AB),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp)
                    )
                    Button(onClick = {
                        CrashLog.clear(this@MainActivity); CrashLog.stage(this@MainActivity, "done")
                        recreate()
                    }) { Text("Sil ve tekrar dene") }
                }
            }
            return
        }

        setContent {
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) vm.stop()
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    HomeScreen(vm)
                }
            }
        }
    }
}
