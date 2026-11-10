package ir.sadegh.assistant

sealed class Cmd {
    data class Call(val targets: List<String>) : Cmd()
    data class Open(val targets: List<String>) : Cmd()
    data class Torch(val on: Boolean) : Cmd()
    data class Timer(val seconds: Int) : Cmd()
    data class Search(val q: String) : Cmd()
    data class Volume(val up: Boolean) : Cmd()
    object Battery : Cmd()
    object Time : Cmd()
    object Unknown : Cmd()
}

object Parser {
    private enum class K { CALL, OPEN, TORCH, TIMER, SEARCH, VOLUME, BATTERY, TIME, NONE }

    private val callW = setOf("call", "calling", "dial", "کال", "کل", "کول", "تماس", "زنگ", "تلفن", "فون")
    private val openW = setOf("open", "launch", "run", "start", "اوپن", "باز", "بازکن", "اجرا", "استارت")
    private val searchW = setOf("جستجو", "سرچ", "search", "google", "گوگل")
    private val upW = setOf("بیشتر", "زیاد", "بالا", "up", "افزایش", "بلند", "بیار")
    private val downW = setOf("کم", "پایین", "down", "کاهش", "کمتر")
    private val offW = setOf("خاموش", "off", "قطع")
    private val stopW = setOf(
        "با", "به", "رو", "را", "بزن", "بزنی", "بگیر", "بگیری", "کن", "بکن", "کنی", "کنید", "لطفا", "برای",
        "برام", "من", "منو", "میشه", "میخوام", "بخوام", "یه", "یک", "برنامه", "اپلیکیشن", "اپ", "نرم", "افزار",
        "شماره", "مخاطب", "در", "درباره", "دنبال", "please", "to", "the", "with", "my", "for", "me", "app",
        "application", "contact", "number", "about", "for"
    )
    private val numW = mapOf(
        "یک" to 1, "دو" to 2, "سه" to 3, "چهار" to 4, "پنج" to 5, "شش" to 6, "هفت" to 7, "هشت" to 8,
        "نه" to 9, "ده" to 10, "یازده" to 11, "دوازده" to 12, "پانزده" to 15, "بیست" to 20, "سی" to 30,
        "چهل" to 40, "پنجاه" to 50, "شصت" to 60,
        "one" to 1, "two" to 2, "three" to 3, "five" to 5, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30
    )

    private fun words(t: String) = t.split(" ").filter { it.isNotBlank() && !Text.isWake(it) }

    private fun kind(w: List<String>): K = when {
        ("چراغ" in w && "قوه" in w) || "فلش" in w || "flashlight" in w || "torch" in w -> K.TORCH
        "تایمر" in w || "timer" in w -> K.TIMER
        w.any { it in searchW } -> K.SEARCH
        "باتری" in w || "battery" in w -> K.BATTERY
        "صدا" in w || "ولوم" in w || "volume" in w -> K.VOLUME
        w.any { it in callW } -> K.CALL
        w.any { it in openW } -> K.OPEN
        "ساعت" in w || "time" in w -> K.TIME
        else -> K.NONE
    }

    /** نام هدف (مخاطب/برنامه/عبارت جستجو) بدون فعل‌ها و کلمات اضافه */
    fun target(text: String): String =
        words(text).filter { it !in callW && it !in openW && it !in searchW && it !in stopW }.joinToString(" ")

    private fun seconds(w: List<String>): Int {
        var n = 0
        for (x in w) { val v = x.toIntOrNull() ?: numW[x]; if (v != null) { n = v; break } }
        val mul = when {
            w.any { it == "ثانیه" || it == "second" || it == "seconds" } -> 1
            w.any { it == "ساعت" || it == "hour" || it == "hours" } -> 3600
            else -> 60
        }
        return n * mul
    }

    fun parse(cands: List<String>): Cmd {
        val ws = cands.map { words(it) }
        val kinds = ws.map { kind(it) }
        val k = kinds.firstOrNull { it != K.NONE } ?: return Cmd.Unknown
        val idx = kinds.indexOf(k)
        return when (k) {
            K.TORCH -> Cmd.Torch(ws.none { w -> w.any { it in offW } })
            K.TIMER -> Cmd.Timer(seconds(ws[idx]))
            K.BATTERY -> Cmd.Battery
            K.TIME -> Cmd.Time
            K.VOLUME -> when {
                ws[idx].any { it in upW } -> Cmd.Volume(true)
                ws[idx].any { it in downW } -> Cmd.Volume(false)
                else -> Cmd.Unknown
            }
            K.SEARCH -> cands.map { target(it) }.firstOrNull { it.isNotBlank() }?.let { Cmd.Search(it) } ?: Cmd.Unknown
            else -> {
                // گزینه‌های بدون فعل هم به‌عنوان نام هدف حساب می‌شوند (گاهی «کال» از قلم می‌افتد)
                val t = cands.indices.filter { kinds[it] == k || kinds[it] == K.NONE }
                    .map { target(cands[it]) }.filter { it.isNotBlank() }.distinct()
                if (k == K.CALL) Cmd.Call(t) else Cmd.Open(t)
            }
        }
    }
}
