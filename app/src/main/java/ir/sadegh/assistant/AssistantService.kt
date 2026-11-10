package ir.sadegh.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.SearchManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * معماری:
 *  ۱) شنیدن «صادق» با Vosk (آفلاین، بدون بوق، کم‌مصرف)  — اگر مدل دانلود نشده باشد: حالت جایگزین با Google
 *  ۲) بعد از بیدار شدن: یک بوق کوتاه و گوش دادن به دستور با موتور Google (دقیق‌تر)
 *  ۳) اجرای دستور و برگشت به حالت ۱
 */
class AssistantService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFa = false
    private var running = false
    private var speaking = false
    private var cmdMode = false
    private var followKind: String? = null
    private var awaitingUntil = 0L
    private var model: Model? = null
    private var vosk: SpeechService? = null
    private var voskOn = false
    private val pending = HashMap<String, (() -> Unit)?>()
    private val nextRun = Runnable { next() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        if (!running) { running = true; initTts(); loadVosk() }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sadegh", "Sadegh", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "sadegh")
            .setContentTitle("صادق در حال گوش دادن است")
            .setContentText("بگو «صادق»، مکث کن، بعد دستورت را بگو")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    // ---------- Vosk: کلمه‌ی بیدارباش آفلاین ----------
    private fun loadVosk() {
        val dir = File(filesDir, Models.DIR)
        if (!Models.ready(this)) { startWake(); return }
        Thread {
            try {
                val m = Model(dir.absolutePath)
                val r = Recognizer(m, 16000f)
                r.setMaxAlternatives(5)
                h.post {
                    if (!running) return@post
                    model = m
                    try { vosk = SpeechService(r, 16000f) } catch (e: Throwable) { vosk = null }
                    startWake()
                }
            } catch (e: Throwable) { h.post { startWake() } }
        }.start()
    }

    private val voskL = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(s: String?) {}
        override fun onResult(s: String?) = check(s)
        override fun onFinalResult(s: String?) {}
        override fun onError(e: Exception?) { voskOn = false; restart(1500) }
        override fun onTimeout() {}
    }

    /** نتیجه‌ی نهایی Vosk (بعد از پایان جمله). اگر بعد از «صادق» دستوری هم بود، همان‌جا اجرا می‌شود. */
    private fun check(json: String?) {
        if (!voskOn || json == null) return
        val o = JSONObject(json)
        val texts = mutableListOf<String>()
        o.optJSONArray("alternatives")?.let { a -> for (i in 0 until a.length()) texts.add(a.getJSONObject(i).optString("text")) }
        if (texts.isEmpty()) texts.add(o.optString("text"))
        val wakeC = texts.map { Text.norm(it) }.filter { c -> c.isNotBlank() && c.split(" ").any { Text.isWake(it) } }
        if (wakeC.isEmpty()) return
        voskOn = false
        vosk?.stop()
        val rest = wakeC.filter { c -> c.split(" ").any { !Text.isWake(it) } }
        if (rest.isEmpty() || Parser.parse(rest) is Cmd.Unknown) retryGoogle()
        else execute(rest, soft = true)
    }

    /** فقط «صادق» گفته شده یا دستور از Vosk مطمئن نبود: بوق و گوش دادن با Google */
    private fun retryGoogle() {
        beep()
        cmdMode = true
        h.postDelayed({ listenGoogle() }, 300)
    }

    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            h.postDelayed({ tg.release() }, 500)
        } catch (e: Exception) {}
    }

    private fun startWake() {
        cmdMode = false
        if (!running || speaking) return
        val v = vosk
        if (v != null) {
            if (!voskOn) { voskOn = true; try { v.startListening(voskL) } catch (e: Throwable) { voskOn = false; restart(2000) } }
        } else listenGoogle()
    }

    private fun next() {
        if (followKind != null) { cmdMode = true; listenGoogle() } else startWake()
    }

    private fun restart(ms: Long) { h.removeCallbacks(nextRun); h.postDelayed(nextRun, ms) }

    // ---------- Google: شنیدن دستور ----------
    private fun listenGoogle() {
        if (!running || speaking) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !SpeechRecognizer.isRecognitionAvailable(this)) { stopSelf(); return }
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra("android.speech.extra.ADDITIONAL_LANGUAGES", arrayOf("en-US"))
        }
        rec?.startListening(i)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(b: Bundle?) {}
        override fun onEvent(t: Int, b: Bundle?) {}
        override fun onError(e: Int) {
            val slow = e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == SpeechRecognizer.ERROR_NETWORK ||
                e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || e == SpeechRecognizer.ERROR_SERVER
            if (cmdMode) { cmdMode = false; followKind = null }
            restart(if (slow) 1500 else 250)
        }
        override fun onResults(b: Bundle?) {
            val l = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (l.isNullOrEmpty()) { cmdMode = false; followKind = null; restart(250) } else handle(l)
        }
    }

    private fun handle(raw: List<String>) {
        val cands = raw.map { Text.norm(it) }.filter { it.isNotBlank() }
        val fk = followKind
        if (cmdMode && fk != null) {
            cmdMode = false; followKind = null
            val t = cands.map { c -> Parser.target(c).ifBlank { c } }.distinct()
            if (fk == "call") doCall(t) else doOpen(t)
            return
        }
        if (cmdMode || SystemClock.elapsedRealtime() < awaitingUntil) {
            cmdMode = false; awaitingUntil = 0
            execute(cands); return
        }
        // حالت جایگزین (بدون Vosk): باید کلمه‌ی «صادق» در جمله باشد
        val withWake = cands.filter { c -> c.split(" ").any { Text.isWake(it) } }
        if (withWake.isEmpty()) { restart(150); return }
        if (withWake.none { c -> c.split(" ").any { !Text.isWake(it) } }) {
            awaitingUntil = SystemClock.elapsedRealtime() + 10000
            say("بله؟", "Yes?")
        } else execute(withWake)
    }

    // ---------- اجرای دستورها ----------
    private fun execute(cands: List<String>, soft: Boolean = false) {
        if (cands.isEmpty()) { restart(250); return }
        when (val c = Parser.parse(cands)) {
            is Cmd.Call -> doCall(c.targets, soft)
            is Cmd.Open -> doOpen(c.targets, soft)
            is Cmd.Torch -> torch(c.on)
            is Cmd.Timer -> timer(c.seconds)
            is Cmd.Search -> search(c.q)
            is Cmd.Volume -> {
                getSystemService(AudioManager::class.java).adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (c.up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI)
                restart(300)
            }
            Cmd.Battery -> {
                val p = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                say("باتری $p درصد است", "Battery is $p percent")
            }
            Cmd.Time -> {
                val t = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                say("ساعت $t است", "It's $t")
            }
            Cmd.Unknown -> say("متوجه نشدم. مثلاً بگو: کال مامان", "Sorry, I didn't get that")
        }
    }

    private fun torch(on: Boolean) {
        try {
            val cm = getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            if (id == null) { say("فلش پیدا نشد", "No flash found"); return }
            cm.setTorchMode(id, on)
            say(if (on) "چراغ قوه روشن شد" else "چراغ قوه خاموش شد", if (on) "Flashlight on" else "Flashlight off")
        } catch (e: Exception) { say("نتوانستم چراغ قوه را تغییر بدهم", "Flashlight error") }
    }

    private fun timer(sec: Int) {
        if (sec <= 0) { say("چند دقیقه؟ مثلاً بگو: تایمر پنج دقیقه", "How long?"); return }
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val m = sec / 60
        say("تایمر ${if (m > 0) "$m دقیقه" else "$sec ثانیه"} تنظیم شد", "Timer set") {
            try { startActivity(i) } catch (e: Exception) {}
        }
    }

    private fun search(q: String) {
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        say("در حال جستجو", "Searching") { try { startActivity(i) } catch (e: Exception) {} }
    }

    // ---------- تماس ----------
    private fun loadContacts(): LinkedHashMap<String, String>? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val m = LinkedHashMap<String, String>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val p = c.getString(1) ?: continue
                if (!m.containsKey(n)) m[n] = p
            }
        }
        return m
    }

    private fun doCall(targets: List<String>, soft: Boolean = false) {
        if (targets.isEmpty()) { followKind = "call"; say("با کی تماس بگیرم؟", "Who should I call?"); return }
        val contacts = loadContacts()
        if (contacts == null) { say("دسترسی به مخاطبین را نداری", "No contacts permission"); return }
        val scored = contacts.entries.map { e -> e to targets.maxOf { Match.score(it, e.key) } }
            .sortedByDescending { it.second }
        val top = scored.firstOrNull()
        if (top == null || top.second < 0.8) {
            if (soft) { retryGoogle(); return }
            say("مخاطبی با این نام پیدا نکردم", "I couldn't find that contact"); return
        }
        val second = scored.getOrNull(1)
        if (second != null && top.second < 1.0 && second.second >= top.second - 0.03) {
            if (soft) { retryGoogle(); return }
            val a = top.first.key; val b = second.first.key
            followKind = "call"
            say("چند مخاطب شبیه هم هستند: $a یا $b. کدام را بگیرم؟", "Several matches: $a or $b. Which one?"); return
        }
        val name = top.first.key; val num = top.first.value
        say("در حال تماس با $name", "Calling $name") { dial(num) }
    }

    private fun dial(num: String) {
        val ok = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (ok) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", num, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: Exception) { say("نتوانستم تماس بگیرم", "I couldn't place the call") }
    }

    // ---------- باز کردن برنامه ----------
    private val alias = mapOf(
        "دوربین" to "camera", "تنظیمات" to "settings", "گالری" to "gallery", "عکس ها" to "photos",
        "مرورگر" to "chrome", "نقشه" to "maps", "مخاطبین" to "contacts", "پیام ها" to "messages",
        "ماشین حساب" to "calculator", "تقویم" to "calendar", "ساعت" to "clock", "ایمیل" to "gmail"
    )

    private fun doOpen(targets: List<String>, soft: Boolean = false) {
        if (targets.isEmpty()) { followKind = "open"; say("کدام برنامه را باز کنم؟", "Which app?"); return }
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        var best: Pair<String, String>? = null; var bs = 0.0
        for (a in apps) for (t in targets) for (q in listOfNotNull(t, alias[t])) {
            val s = Match.score(q, a.first)
            if (s > bs) { bs = s; best = a }
        }
        val b = best
        if (b == null || bs < 0.78) { if (soft) { retryGoogle(); return }; say("برنامه‌ای با این نام پیدا نکردم", "I couldn't find that app"); return }
        val li = pm.getLaunchIntentForPackage(b.second)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (li == null) { say("نتوانستم باز کنم", "I couldn't open it"); return }
        say("در حال باز کردن ${b.first}", "Opening ${b.first}") { try { startActivity(li) } catch (e: Exception) {} }
    }

    // ---------- صدا ----------
    private fun initTts() {
        tts = TextToSpeech(this) { st ->
            val t = tts
            if (st == TextToSpeech.SUCCESS && t != null) {
                val r = t.setLanguage(Locale("fa", "IR"))
                ttsFa = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsFa) t.setLanguage(Locale.US)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { finished(id) }
                    override fun onError(id: String?) { finished(id) }
                })
                ttsReady = true
            }
        }
    }

    private fun finished(id: String?) = h.post {
        speaking = false
        val a = pending.remove(id)
        a?.invoke()
        restart(300)
    }

    private fun say(faText: String, enText: String, after: (() -> Unit)? = null) {
        val t = tts
        if (!ttsReady || t == null) { after?.invoke(); restart(300); return }
        speaking = true
        rec?.cancel()
        val id = System.nanoTime().toString()
        pending[id] = after
        t.speak(if (ttsFa) faText else enText, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        try { vosk?.shutdown() } catch (e: Throwable) {}
        try { model?.close() } catch (e: Throwable) {}
        rec?.destroy(); tts?.shutdown()
        super.onDestroy()
    }
}
