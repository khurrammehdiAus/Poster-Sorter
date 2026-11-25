package com.khurram.postersorter

enum class Decision { POSTER, CHECK, KEEP }

data class ScanResult(val decision: Decision, val words: Int, val keywords: Int)

/**
 * Decides poster / not sure / keep from the words read in an image.
 * Posters are full of text (dates, times, venues, prices). Family photos
 * have little or none, even when a poster also shows people.
 */
object Classifier {
    // Raise these if family photos get caught, lower if posters slip through.
    private const val POSTER_WORDS = 25      // this many words = poster
    private const val POSTER_WORDS_KW = 10   // ...or this many plus 2+ event keywords
    private const val CHECK_WORDS = 6        // some text, not clearly a poster

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

    // Urdu / Arabic: invitation, event, gathering, date, venue, time, day, hall, tickets, guest
    private val urduArabic = Regex(
        "دعوت|دعوة|تقریب|حفل|محفل|بتاریخ|التاريخ|بمقام|مكان|بوقت|الساعة|" +
            "بروز|يوم|ہال|قاعة|ٹکٹ|تذاكر|مہمان خصوصی|ضيف"
    )

    fun classify(words: List<String>): ScanResult {
        val text = words.joinToString(" ")
        val kw = english.findAll(text).count() + urduArabic.findAll(text).count()
        val n = words.size
        val decision = when {
            n >= POSTER_WORDS || (n >= POSTER_WORDS_KW && kw >= 2) -> Decision.POSTER
            n >= CHECK_WORDS || kw >= 2 -> Decision.CHECK
            else -> Decision.KEEP
        }
        return ScanResult(decision, n, kw)
    }
}
