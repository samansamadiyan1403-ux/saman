package ir.sadegh.assistant

import kotlin.math.max

object Text {
    private val fa = mapOf(
        'ا' to "a", 'ب' to "b", 'پ' to "p", 'ت' to "t", 'ث' to "s", 'ج' to "j", 'چ' to "ch",
        'ح' to "h", 'خ' to "kh", 'د' to "d", 'ذ' to "z", 'ر' to "r", 'ز' to "z", 'ژ' to "zh",
        'س' to "s", 'ش' to "sh", 'ص' to "s", 'ض' to "z", 'ط' to "t", 'ظ' to "z", 'ع' to "a",
        'غ' to "gh", 'ف' to "f", 'ق' to "gh", 'ک' to "k", 'گ' to "g", 'ل' to "l", 'م' to "m",
        'ن' to "n", 'و' to "v", 'ه' to "h", 'ی' to "y", 'ئ' to "y", 'ء' to ""
    )

    /** یکسان‌سازی حروف عربی/فارسی، ارقام، حذف اعراب و علائم */
    fun norm(s: String): String {
        val sb = StringBuilder()
        for (c0 in s.lowercase()) {
            val c = when (c0) {
                'ي', 'ى' -> 'ی'
                'ك' -> 'ک'
                'ۀ', 'ة' -> 'ه'
                'آ', 'أ', 'إ' -> 'ا'
                'ؤ' -> 'و'
                in '۰'..'۹' -> ('0' + (c0 - '۰'))
                in '٠'..'٩' -> ('0' + (c0 - '٠'))
                else -> c0
            }
            when {
                c in '\u064B'..'\u065F' || c == '\u0640' -> {}
                c.isLetterOrDigit() -> sb.append(c)
                else -> sb.append(' ')   // شامل نیم‌فاصله و علائم
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    /** تبدیل فارسی و لاتین به یک الفبای آوایی مشترک */
    fun phon(s: String): String {
        val sb = StringBuilder()
        for (c in s) { val m = fa[c]; if (m != null) sb.append(m) else sb.append(c) }
        return sb.toString().replace("ch", "#").replace("c", "k").replace("#", "ch")
            .replace("ph", "f").replace("w", "v").replace("q", "gh").replace("x", "ks")
    }

    /** اسکلت صامت‌ها: maman و مامان هر دو می‌شوند mn */
    fun skel(p: String): String =
        p.replace(Regex("[aeiouy\\s]"), "").replace(Regex("(.)\\1+"), "\$1")

    fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    fun sim(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - lev(a, b).toDouble() / max(a.length, b.length)
    }

    fun isWake(w: String): Boolean { val k = skel(phon(w)); return k == "sdgh" || k == "sdg" }
}

object Match {
    /** شباهت آوایی بین چیزی که گفته شد و یک نام (مخاطب/برنامه)، بین ۰ تا ۱ */
    fun score(query: String, name: String): Double {
        val qn = Text.norm(query); val nn = Text.norm(name)
        if (qn.isEmpty() || nn.isEmpty()) return 0.0
        if (qn == nn) return 1.0
        val qp = Text.phon(qn); val np = Text.phon(nn)
        var best = max(Text.sim(qp, np), 0.95 * Text.sim(Text.skel(qp), Text.skel(np)))
        for (w in nn.split(" ")) {
            val wp = Text.phon(w)
            best = max(best, 0.9 * max(Text.sim(qp, wp), 0.95 * Text.sim(Text.skel(qp), Text.skel(wp))))
        }
        return best
    }
}
