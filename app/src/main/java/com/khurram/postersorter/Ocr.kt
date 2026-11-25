package com.khurram.postersorter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlin.math.max

object OcrData {
    private val wanted = listOf("eng", "urd", "ara")

    /** Copies bundled language files to app storage. Returns (dataPath, "eng+urd+ara"). */
    fun prepare(context: Context): Pair<String, String> {
        val dir = File(context.filesDir, "tessdata").apply { mkdirs() }
        val bundled = context.assets.list("tessdata")?.toSet() ?: emptySet()
        val langs = mutableListOf<String>()
        for (lang in wanted) {
            val name = "$lang.traineddata"
            if (name !in bundled) continue
            val out = File(dir, name)
            if (!out.exists() || out.length() == 0L) {
                context.assets.open("tessdata/$name").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            langs += lang
        }
        return context.filesDir.absolutePath to (langs.ifEmpty { listOf("eng") }.joinToString("+"))
    }
}

/** One text reader. Not thread-safe: use one per worker. */
class OcrWorker(dataPath: String, langs: String) {
    private val api = TessBaseAPI()
    val ok: Boolean = api.init(dataPath, langs, TessBaseAPI.OEM_LSTM_ONLY)

    init {
        if (ok) api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
    }

    /** Words read with good confidence, in any script. */
    fun words(file: File): List<String> {
        if (!ok) return emptyList()
        val bmp = decode(file) ?: return emptyList()
        val out = mutableListOf<String>()
        try {
            api.setImage(bmp)
            api.getUTF8Text()
            val it = api.getResultIterator() ?: return out
            val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
            it.begin()
            do {
                val w = it.getUTF8Text(level)?.trim() ?: continue
                val conf = it.confidence(level)
                if (conf >= 60f && w.count { ch -> ch.isLetterOrDigit() } >= 2) out += w
            } while (it.next(level))
            it.delete()
        } finally {
            api.clear()
            bmp.recycle()
        }
        return out
    }

    fun close() = api.recycle()

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val biggest = max(bounds.outWidth, bounds.outHeight)
        if (biggest <= 0) return null
        var sample = 1
        while (biggest / (sample * 2) >= 1000) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.path, opts)
    }
}
