package com.khurram.postersorter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** A 64-bit fingerprint of how an image looks; copies and resized copies match. */
object DHash {
    fun of(bmp: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        var h = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (lum(s.getPixel(x, y)) > lum(s.getPixel(x + 1, y))) h = h or (1L shl bit)
            bit++
        }
        if (s !== bmp) s.recycle()
        return h
    }

    private fun lum(c: Int) = Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114

    fun ofFile(f: File): Long? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val biggest = max(bounds.outWidth, bounds.outHeight)
        if (biggest <= 0) return null
        var sample = 1
        while (biggest / (sample * 2) >= 128) sample *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try { of(b) } finally { b.recycle() }
    }
}

/** Remembers fingerprints so images aren't re-read. */
class HashStore(context: Context) {
    private val file = File(context.filesDir, "hashes_v1.bin")
    private val map = ConcurrentHashMap<String, Pair<Long, Long>>() // path -> (modified, hash)

    init {
        if (file.exists()) runCatching {
            DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
                while (true) {
                    val path = try { input.readUTF() } catch (e: EOFException) { break }
                    map[path] = input.readLong() to input.readLong()
                }
            }
        }
    }

    fun get(f: File): Long? = map[f.path]?.takeIf { it.first == f.lastModified() }?.second
    fun put(f: File, h: Long) { map[f.path] = f.lastModified() to h }

    @Synchronized
    fun save() {
        DataOutputStream(BufferedOutputStream(file.outputStream(), 1 shl 16)).use { out ->
            for ((path, v) in map) {
                if (!File(path).exists()) continue
                out.writeUTF(path); out.writeLong(v.first); out.writeLong(v.second)
            }
        }
    }
}

class DupMember(val file: File, val best: Boolean, selected: Boolean, val sameAsBest: Boolean) {
    var selected by mutableStateOf(selected)
}

/** A set of copies. The first member is the one Siftly suggests keeping. */
class DupGroup(val members: List<DupMember>) {
    val id: String = members.first().file.path
    val exact: Boolean get() = members.all { it.best || it.sameAsBest }
}

object DupFinder {
    private const val MAX_DISTANCE = 6       // fingerprint bits that may differ
    private const val MIN_SIMILARITY = 0.92f // how alike the image-recognition says they must be

    fun find(files: List<File>, hashes: Map<String, Long>, embedding: (File) -> FloatArray?): List<DupGroup> {
        val list = files.filter { (hashes[it.path] ?: 0L) != 0L } // 0 = blank image
        val n = list.size
        if (n < 2) return emptyList()
        val h = LongArray(n) { hashes[list[it].path]!! }
        val parent = IntArray(n) { it }
        fun root(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }

        val emb = arrayOfNulls<FloatArray>(n)
        // Any two fingerprints within 7 bits share at least one of these 8 bytes exactly.
        for (band in 0 until 8) {
            val buckets = HashMap<Int, MutableList<Int>>()
            for (i in 0 until n) buckets.getOrPut(((h[i] ushr (band * 8)) and 0xFF).toInt()) { mutableListOf() }.add(i)
            for (bucket in buckets.values) {
                if (bucket.size < 2) continue
                for (a in 0 until bucket.size) for (b in a + 1 until bucket.size) {
                    val i = bucket[a]
                    val j = bucket[b]
                    if (root(i) == root(j)) continue
                    val d = java.lang.Long.bitCount(h[i] xor h[j])
                    if (d > MAX_DISTANCE) continue
                    val ei = emb[i] ?: embedding(list[i]).also { emb[i] = it ?: FloatArray(0) }
                    val ej = emb[j] ?: embedding(list[j]).also { emb[j] = it ?: FloatArray(0) }
                    val alike = if (ei != null && ej != null && ei.isNotEmpty() && ei.size == ej.size) {
                        var dot = 0f
                        for (k in ei.indices) dot += ei[k] * ej[k]
                        dot >= MIN_SIMILARITY
                    } else d <= 3
                    if (alike) parent[root(i)] = root(j)
                }
            }
        }

        return (0 until n).groupBy { root(it) }.values
            .filter { it.size >= 2 }
            .map { idx -> makeGroup(idx.map { list[it] }) }
            .sortedByDescending { it.members.size }
    }

    /** Picks the best copy: biggest picture, then biggest file, then not from WhatsApp, then oldest. */
    private fun makeGroup(files: List<File>): DupGroup {
        val ranked = files.sortedWith(
            compareByDescending<File> { pixels(it) }
                .thenByDescending { it.length() }
                .thenBy { if (it.path.contains("WhatsApp")) 1 else 0 }
                .thenBy { it.lastModified() }
        )
        val bestMd5 = md5(ranked.first())
        val members = ranked.mapIndexed { i, f ->
            val same = i > 0 && f.length() == ranked.first().length() && md5(f) == bestMd5
            // Exact copies of the kept one are pre-selected; merely similar ones aren't.
            DupMember(f, best = i == 0, selected = same, sameAsBest = same)
        }
        return DupGroup(members)
    }

    private fun pixels(f: File): Long {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return o.outWidth.toLong() * o.outHeight.toLong()
    }

    private fun md5(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
