package com.khurram.postersorter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlin.math.ln
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

/** Everything the app knows about one image. */
class Analysis(val features: FloatArray, val heuristic: ScanResult)

object Features {
    const val TEXT_DIMS = 8
    const val HEURISTIC_INDEX = 7

    fun build(s: TextStats, h: ScanResult, width: Int, height: Int, embedding: FloatArray): FloatArray {
        fun lg(v: Int) = ln(1f + v)
        val aspect = (height.toFloat() / max(1, width)).coerceIn(0.3f, 3f)
        val text = floatArrayOf(
            lg(s.words.size),
            lg(s.anyWords),
            lg(s.arabicWords),
            lg(h.keywords),
            lg(s.digits),
            s.textArea,
            ln(aspect),
            when (h.decision) {
                Decision.POSTER -> 1f
                Decision.CHECK -> 0.5f
                Decision.KEEP -> 0f
            },
        )
        return text + embedding
    }

    fun heuristicScore(f: FloatArray): Float = f[HEURISTIC_INDEX]
}

/** Reads text and recognises the look of an image. One per worker thread. */
class Analyzer(context: Context, dataPath: String, langs: String) {
    private val api = TessBaseAPI()
    val ok: Boolean = api.init(dataPath, langs, TessBaseAPI.OEM_LSTM_ONLY)

    private val embedder: ImageEmbedder? = try {
        val options = ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("mobilenet_v3_small.tflite").build())
            .setL2Normalize(true)
            .setQuantize(false)
            .setRunningMode(RunningMode.IMAGE)
            .build()
        ImageEmbedder.createFromOptions(context, options)
    } catch (t: Throwable) {
        null
    }

    init {
        if (ok) api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
    }

    fun analyze(file: File): Analysis? {
        if (!ok) return null
        val bmp = decode(file) ?: return null
        try {
            val stats = readText(bmp)
            val heuristic = Classifier.classify(stats)
            val embedding = embed(bmp)
            return Analysis(Features.build(stats, heuristic, bmp.width, bmp.height, embedding), heuristic)
        } finally {
            bmp.recycle()
        }
    }

    private fun readText(bmp: Bitmap): TextStats {
        val words = mutableListOf<String>()
        var any = 0
        var arabic = 0
        var digits = 0
        var area = 0L
        try {
            api.setImage(bmp)
            api.getUTF8Text()
            val it = api.getResultIterator()
            if (it != null) {
                val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                it.begin()
                do {
                    val w = it.getUTF8Text(level)?.trim()
                    if (w.isNullOrEmpty()) continue
                    val conf = it.confidence(level)
                    val letters = w.count { ch -> ch.isLetterOrDigit() }
                    if (conf >= 20f && letters >= 1) {
                        any++
                        val r = it.getBoundingRect(level)
                        area += r.width().toLong() * r.height().toLong()
                        if (w.any { ch -> isArabicScript(ch) }) arabic++
                    }
                    if (conf >= 40f) digits += w.count { ch -> ch.isDigit() }
                    if (conf >= 60f && letters >= 2) words += w
                } while (it.next(level))
                it.delete()
            }
        } finally {
            api.clear()
        }
        val imageArea = max(1L, bmp.width.toLong() * bmp.height.toLong())
        return TextStats(words, any, arabic, digits, (area.toFloat() / imageArea).coerceIn(0f, 1f))
    }

    private fun isArabicScript(ch: Char) =
        ch in '؀'..'ۿ' || ch in 'ݐ'..'ݿ' ||
            ch in 'ﭐ'..'﷿' || ch in 'ﹰ'..'﻿'

    private fun embed(bmp: Bitmap): FloatArray {
        val e = embedder ?: return FloatArray(0)
        return try {
            e.embed(BitmapImageBuilder(bmp).build())
                .embeddingResult().embeddings()[0].floatEmbedding()
        } catch (t: Throwable) {
            FloatArray(0)
        }
    }

    fun close() {
        api.recycle()
        embedder?.close()
    }

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
