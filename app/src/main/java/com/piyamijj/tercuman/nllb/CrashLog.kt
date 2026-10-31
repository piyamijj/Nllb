package com.piyamijj.tercuman.nllb

import android.app.Application
import android.content.Context
import java.io.File

/** Çöken uygulamanın hatasını dosyaya yazar; sonraki açılışta ekranda gösterilir. */
class TercumanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                CrashLog.file(this).writeText(
                    "Thread: ${t.name}\n" + android.util.Log.getStackTraceString(e)
                )
            }
            prev?.uncaughtException(t, e)
        }
    }
}

object CrashLog {
    fun file(c: Context) = File(c.filesDir, "last_crash.txt")
    fun read(c: Context): String? = runCatching {
        file(c).takeIf { it.isFile }?.readText()
    }.getOrNull()
    fun clear(c: Context) { runCatching { file(c).delete() } }
}
