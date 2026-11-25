package com.khurram.postersorter

enum class Decision { POSTER, CHECK, KEEP }

data class ScanResult(val decision: Decision, val words: Int, val keywords: Int)

/** What the text reader found in one image. */
class TextStats(
    val words: List<String>,   // confidently read words, any script
    val anyWords: Int,         // text-like words, even if poorly read
    val arabicWords: Int,      // words in Urdu/Arabic script, even if poorly read
    val digits: Int,
    val textArea: Float,       // fraction of the image covered by text
)

/**
 * Starting rules, used before the app has learned from your choices.
 * Posters are full of text (dates, times, venues, prices). Family photos
 * have little or none, even when a poster also shows people.
 */
object Classifier {
    private const val POSTER_WORDS = 25
    private const val POSTER_WORDS_KW = 10
    private const val CHECK_WORDS = 6

    // Urdu text (especially Nastaliq) is often read poorly, so also count
    // Urdu/Arabic-script words even when the reader isn't sure of them.
    private const val POSTER_ARABIC = 25
    private const val CHECK_ARABIC = 10

    private val english = Regex(
        """\b(tickets?|rsvp|venue|join us|invit\w*|presents?|live|event|festival|""" +
            """concert|workshop|seminar|fundraiser|dinner|gala|free entry|entry|""" +
            """register|registration|bookings?|book now|admission|doors open|""" +
            """program|programme|sponsor\w*|guest speaker|speaker|celebrat\w*|""" +
            """mon(day)?|tue(s|sday)?|wed(nesday)?|thu(rs|rsday)?|fri(day)?|""" +
            """sat(urday)?|sun(day)?|jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|""" +
            """may|june?|july?|aug(ust)?|sep(t|tember)?|oct(ober)?|nov(ember)?|""" +
            """dec(ember)?|www|\.com|\.au)\b""" +
            """|\d{1,2}(:\d{2})?\s?(am|pm)\b|\$\d+|\b\d{1,2}(st|nd|rd|th)\b|@\w+""",
        RegexOption.IGNORE_CASE
    )

    private val urduArabic = Regex(
        "دعوت|دعوة|تقریب|حفل|محفل|بتاریخ|التاريخ|بمقام|مكان|بوقت|الساعة|" +
            "بروز|يوم|ہال|قاعة|ٹکٹ|تذاكر|مہمان خصوصی|ضيف|مجلس|جلسہ|اجتماع|" +
            "پروگرام|میلاد|عرس|شام|بجے|منعقد|زیر اہتمام|مدعو"
    )

    fun classify(s: TextStats): ScanResult {
        val text = s.words.joinToString(" ")
        val kw = english.findAll(text).count() + urduArabic.findAll(text).count()
        val n = s.words.size
        val decision = when {
            n >= POSTER_WORDS || (n >= POSTER_WORDS_KW && kw >= 2) -> Decision.POSTER
            s.arabicWords >= POSTER_ARABIC && s.textArea >= 0.05f -> Decision.POSTER
            n >= CHECK_WORDS || kw >= 2 || s.arabicWords >= CHECK_ARABIC -> Decision.CHECK
            else -> Decision.KEEP
        }
        return ScanResult(decision, n, kw)
    }
}
