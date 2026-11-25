package com.khurram.postersorter

/** What an image is. KEEP must stay first and POSTER second (saved learning depends on it). */
enum class Category(val label: String, val folder: String) {
    KEEP("Keep", ""),
    POSTER("Poster", "Posters"),
    FORWARD("Forward", "Forwards"),
    MEME("Meme", "Memes");

    companion object {
        val clutter = listOf(POSTER, FORWARD, MEME)
    }
}

/** Which tab an image lands in. */
enum class Bucket { POSTER, FORWARD, MEME, CHECK, KEEP }

fun Category.bucket(): Bucket = when (this) {
    Category.POSTER -> Bucket.POSTER
    Category.FORWARD -> Bucket.FORWARD
    Category.MEME -> Bucket.MEME
    Category.KEEP -> Bucket.KEEP
}

/** Starting-rules verdict: a bucket, plus the most likely clutter type. */
data class ScanResult(val bucket: Bucket, val guess: Category)

/** What the text reader found in one image. */
class TextStats(
    val words: List<String>,   // confidently read words, any script
    val anyWords: Int,         // text-like words, even if poorly read
    val arabicWords: Int,      // Urdu/Arabic-script words, even if poorly read
    val digits: Int,
    val textArea: Float,       // fraction of the image covered by text
    val bandFraction: Float,   // share of the text in the top or bottom quarter (meme captions)
)

class Keywords(val event: Int, val forward: Int, val meme: Int)

/**
 * Starting rules, used before the app has learned from your choices.
 * - Posters: lots of text with dates, times, venues, prices.
 * - Forwards: greetings, chain messages, "share this", offers and links.
 * - Memes: a short caption at the top/bottom of a picture, or meme phrases.
 * Family photos have little or no text.
 */
object Classifier {
    private val event = Regex(
        """\b(tickets?|rsvp|venue|join us|invit\w*|presents?|live|event|festival|""" +
            """concert|workshop|seminar|fundraiser|dinner|gala|free entry|entry|""" +
            """register|registration|bookings?|book now|admission|doors open|""" +
            """program|programme|sponsor\w*|guest speaker|speaker|celebrat\w*|""" +
            """mon(day)?|tue(s|sday)?|wed(nesday)?|thu(rs|rsday)?|fri(day)?|""" +
            """sat(urday)?|sun(day)?|jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|""" +
            """june?|july?|aug(ust)?|sep(t|tember)?|oct(ober)?|nov(ember)?|""" +
            """dec(ember)?|venue|hall|centre|center)\b""" +
            """|\d{1,2}(:\d{2})?\s?(am|pm)\b|\$\d+|\b\d{1,2}(st|nd|rd|th)\b""" +
            "|دعوت|دعوة|تقریب|حفل|محفل|بتاریخ|التاريخ|بمقام|مكان|بوقت|الساعة|" +
            "بروز|ہال|قاعة|ٹکٹ|تذاكر|مہمان خصوصی|ضيف|مجلس|جلسہ|اجتماع|" +
            "پروگرام|میلاد|عرس|بجے|منعقد|زیر اہتمام|مدعو",
        RegexOption.IGNORE_CASE
    )

    private val forward = Regex(
        """\b(good (morning|night|evening|afternoon)|have a (nice|great|blessed)|""" +
            """jum+u?a mubarak|eid mubarak|ramadan (mubarak|kareem)|blessed|""" +
            """forward(ed)?|share this|send this|pass (it|this) on|must (watch|read)|""" +
            """viral|breaking news|click|link|subscribe|offer|discount|sale|""" +
            """limited time|winner|prize|loan|crypto|bitcoin|invest\w*|earn|""" +
            """thought of the day|quote|motivation\w*|whatsapp|http\S*|www\S*|""" +
            """bit\.ly|wa\.me|t\.me|youtube)\b|\d+\s?%""" +
            "|صبح بخیر|شب بخیر|جمعہ مبارک|جمعة مباركة|جمعه مبارک|عید مبارک|" +
            "رمضان مبارک|صباح الخير|مساء الخير|شیئر|فارورڈ|آگے بھیجیں|سبحان اللہ|" +
            "ماشاءاللہ|الحمدللہ|خوشخبری|آفر|رعایت",
        RegexOption.IGNORE_CASE
    )

    private val meme = Regex(
        """\b(when (you|your|u|ur|my)|me:|nobody:|pov|be like|lol|lmao|lmfao|meme|""" +
            """that moment|that feeling|literally|bro|mom:|dad:|teacher:|wife:|husband:|""" +
            """expectation|reality|me after|me trying|starter pack|""" +
            """no one:|everyone:|my face when|mfw|tfw)\b""",
        RegexOption.IGNORE_CASE
    )

    fun keywords(s: TextStats): Keywords {
        val text = s.words.joinToString(" ")
        return Keywords(
            event.findAll(text).count(),
            forward.findAll(text).count(),
            meme.findAll(text).count(),
        )
    }

    fun classify(s: TextStats, k: Keywords): ScanResult {
        val n = s.words.size
        val guess = when {
            k.forward > k.event && k.forward >= k.meme -> Category.FORWARD
            k.meme > k.event -> Category.MEME
            else -> Category.POSTER
        }
        val captionLayout = s.bandFraction >= 0.7f && n in 2..30 && s.textArea < 0.35f
        val bucket = when {
            (n >= 25 && k.event >= 2) || (n >= 10 && k.event >= 3) -> Bucket.POSTER
            k.forward >= 2 || (k.forward >= 1 && n < 25) -> Bucket.FORWARD
            (k.meme >= 1 && n <= 40) || captionLayout -> Bucket.MEME
            n >= 25 || (n >= 10 && k.event >= 2) -> Bucket.POSTER
            s.arabicWords >= 25 && s.textArea >= 0.05f -> Bucket.POSTER
            n >= 6 || k.event >= 2 || s.arabicWords >= 10 -> Bucket.CHECK
            else -> Bucket.KEEP
        }
        return ScanResult(bucket, if (captionLayout && bucket == Bucket.CHECK) Category.MEME else guess)
    }
}
