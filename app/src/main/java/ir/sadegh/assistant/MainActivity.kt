package ir.sadegh.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        status = TextView(this).apply {
            textSize = 18f
            text = if (Models.ready(this@MainActivity)) "مدل صوتی آماده است ✅. مراحل ۱ تا ۳ را انجام بده."
            else "برای شروع، مراحل را به ترتیب انجام بده."
        }
        fun btn(t: String, f: () -> Unit): Button = Button(this).apply { text = t; setOnClickListener { f() } }
        root.addView(status)
        root.addView(btn("۱) دانلود مدل صوتی فارسی (۵۳ مگابایت، فقط یک‌بار)") { download() })
        root.addView(btn("۲) دسترسی نمایش روی برنامه‌ها") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("۳) غیرفعال‌سازی بهینه‌سازی باتری") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        root.addView(btn("۴) شروع دستیار صادق") { start() })
        root.addView(btn("توقف دستیار") {
            stopService(Intent(this, AssistantService::class.java)); status.text = "صادق متوقف شد."
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun start() {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(p.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, AssistantService::class.java))
            status.text = if (Models.ready(this))
                "صادق فعال شد. بگو «صادق»، صدای بوق را که شنیدی دستورت را بگو: «کال مامان»"
            else "صادق فعال شد (حالت جایگزین، بدون مدل آفلاین). برای دقت بهتر مدل را دانلود کن و دوباره شروع بزن."
        } else status.text = "بدون دسترسی میکروفون کار نمی‌کند."
    }

    private fun download() {
        if (Models.ready(this)) { status.text = "مدل از قبل آماده است ✅"; return }
        status.text = "در حال دانلود…"
        Thread {
            try {
                val tmp = File(cacheDir, "model.zip")
                val c = URL(Models.URL).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                val total = c.contentLength.toLong(); var done = 0L; var last = -1
                c.inputStream.use { i ->
                    tmp.outputStream().use { o ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = i.read(buf); if (n < 0) break
                            o.write(buf, 0, n); done += n
                            val pc = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pc != last) { last = pc; runOnUiThread { status.text = "دانلود: $pc%" } }
                        }
                    }
                }
                runOnUiThread { status.text = "در حال استخراج…" }
                val base = filesDir.canonicalPath
                ZipInputStream(tmp.inputStream().buffered()).use { z ->
                    while (true) {
                        val en = z.nextEntry ?: break
                        val f = File(filesDir, en.name)
                        if (!f.canonicalPath.startsWith(base)) continue
                        if (en.isDirectory) f.mkdirs()
                        else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                    }
                }
                tmp.delete()
                runOnUiThread { status.text = if (Models.ready(this)) "مدل آماده شد ✅ حالا مرحله ۴" else "ساختار مدل درست نبود" }
            } catch (e: Exception) {
                runOnUiThread { status.text = "خطا در دانلود: ${e.message}" }
            }
        }.start()
    }
}
