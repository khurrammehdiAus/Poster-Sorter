package com.khurram.postersorter

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

private fun DataOutputStream.writeFloats(a: FloatArray) {
    writeInt(a.size)
    for (v in a) writeFloat(v)
}

private fun DataInputStream.readFloats(): FloatArray {
    val n = readInt()
    require(n in 0..100_000)
    return FloatArray(n) { readFloat() }
}

/** Remembers each image's analysis, so images are only read once. */
class FeatureStore(context: Context) {
    private class Entry(val modified: Long, val analysis: Analysis)

    private val file = File(context.filesDir, "features_v3.bin")
    private val map = ConcurrentHashMap<String, Entry>()

    init {
        if (file.exists()) runCatching {
            DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
                while (true) {
                    val path = try { input.readUTF() } catch (e: EOFException) { break }
                    val modified = input.readLong()
                    val bucket = Bucket.entries[input.readInt()]
                    val guess = Category.entries[input.readInt()]
                    val feats = input.readFloats()
                    map[path] = Entry(modified, Analysis(feats, ScanResult(bucket, guess)))
                }
            }
        }
    }

    fun get(f: File): Analysis? = map[f.path]?.takeIf { it.modified == f.lastModified() }?.analysis

    fun put(f: File, a: Analysis) { map[f.path] = Entry(f.lastModified(), a) }

    fun save() {
        DataOutputStream(BufferedOutputStream(file.outputStream(), 1 shl 16)).use { out ->
            for ((path, e) in map) {
                if (!File(path).exists()) continue
                out.writeUTF(path)
                out.writeLong(e.modified)
                out.writeInt(e.analysis.heuristic.bucket.ordinal)
                out.writeInt(e.analysis.heuristic.guess.ordinal)
                out.writeFloats(e.analysis.features)
            }
        }
    }
}

/** label is a Category ordinal: 0 keep, 1 poster, 2 forward, 3 meme. */
class Sample(val label: Int, val weight: Float, val features: FloatArray)

/** Your choices: what each image was. Keyed by file name. */
class LabelStore(context: Context) {
    private val file = File(context.filesDir, "labels_v3.bin")
    private val legacyFile = File(context.filesDir, "labels.bin")
    private val map = ConcurrentHashMap<String, Sample>()

    /** Choices saved by the older Poster Sorter (1 = poster, 0 = not), waiting to be carried over. */
    private val legacy = ConcurrentHashMap<String, Pair<Int, Float>>()

    init {
        if (file.exists()) runCatching {
            DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
                while (true) {
                    val key = try { input.readUTF() } catch (e: EOFException) { break }
                    val label = input.readInt()
                    val weight = input.readFloat()
                    map[key] = Sample(label, weight, input.readFloats())
                }
            }
        }
        if (legacyFile.exists()) runCatching {
            DataInputStream(BufferedInputStream(legacyFile.inputStream(), 1 shl 16)).use { input ->
                while (true) {
                    val key = try { input.readUTF() } catch (e: EOFException) { break }
                    val label = if (input.readFloat() > 0.5f) 1 else 0
                    val weight = input.readFloat()
                    input.readFloats()
                    legacy[key] = label to weight
                }
            }
        }
    }

    fun has(key: String) = map.containsKey(key)

    /** A clear choice always replaces a weak guess, never the other way round. */
    fun put(key: String, s: Sample) {
        val old = map[key]
        if (old == null || s.weight >= old.weight || old.label == s.label) map[key] = s
    }

    /** Carries an older choice over to the new format once the image has been re-read. */
    fun migrate(key: String, features: FloatArray): Boolean {
        if (map.containsKey(key)) return false
        val (label, weight) = legacy[key] ?: return false
        map[key] = Sample(label, weight, features)
        return true
    }

    fun samples(): List<Sample> = map.values.toList()
    fun count(c: Category) = map.values.count { it.label == c.ordinal }

    fun save() {
        DataOutputStream(BufferedOutputStream(file.outputStream(), 1 shl 16)).use { out ->
            for ((key, s) in map) {
                out.writeUTF(key)
                out.writeInt(s.label)
                out.writeFloat(s.weight)
                out.writeFloats(s.features)
            }
        }
    }

    fun clear() {
        map.clear()
        legacy.clear()
        file.delete()
        legacyFile.delete()
    }
}

private const val K = 4 // keep, poster, forward, meme

/** A small model trained on your choices (softmax regression over the four categories). */
class Model(
    private val mean: FloatArray,
    private val std: FloatArray,
    private val w: Array<FloatArray>,
    private val b: FloatArray,
    val trainedOn: Int,
) {
    fun predict(x: FloatArray): FloatArray? {
        if (x.size != mean.size) return null
        val z = FloatArray(K) { k ->
            var s = b[k]
            val wk = w[k]
            for (i in x.indices) s += wk[i] * (x[i] - mean[i]) / std[i]
            s
        }
        return softmax(z)
    }

    /** Blends the learned guess with the starting rules; trusts learning more as it grows. */
    fun score(x: FloatArray): FloatArray {
        val h = Features.heuristicFrom(x)
        val p = predict(x) ?: return h
        val a = min(0.85f, trainedOn / (trainedOn + 30f))
        return FloatArray(K) { a * p[it] + (1 - a) * h[it] }
    }

    fun save(file: File) {
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { out ->
            out.writeFloats(mean); out.writeFloats(std)
            for (k in 0 until K) out.writeFloats(w[k])
            out.writeFloats(b); out.writeInt(trainedOn)
        }
    }

    companion object {
        const val MIN_EACH = 5

        private fun softmax(z: FloatArray): FloatArray {
            val m = z.max()
            val e = FloatArray(z.size) { exp((z[it] - m).coerceAtLeast(-30f)) }
            val sum = e.sum()
            return FloatArray(z.size) { e[it] / sum }
        }

        fun load(file: File): Model? = if (!file.exists()) null else runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                val mean = input.readFloats()
                val std = input.readFloats()
                val w = Array(K) { input.readFloats() }
                Model(mean, std, w, input.readFloats(), input.readInt())
            }
        }.getOrNull()

        /** Needs 5 "keep" choices and 5 clutter choices to start. */
        fun ready(keep: Int, clutter: Int) = keep >= MIN_EACH && clutter >= MIN_EACH

        fun train(all: List<Sample>): Model? {
            if (all.isEmpty()) return null
            val dim = all.groupingBy { it.features.size }.eachCount().maxByOrNull { it.value }!!.key
            val data = all.filter { it.features.size == dim && it.label in 0 until K }.takeLast(3000)
            val keep = data.count { it.label == 0 }
            if (!ready(keep, data.size - keep)) return null

            val n = data.size
            val mean = FloatArray(dim)
            val std = FloatArray(dim)
            for (s in data) for (i in 0 until dim) mean[i] += s.features[i] / n
            for (s in data) for (i in 0 until dim) {
                val d = s.features[i] - mean[i]; std[i] += d * d / n
            }
            for (i in 0 until dim) std[i] = sqrt(std[i]).coerceAtLeast(1e-3f)
            val x = Array(n) { r -> FloatArray(dim) { i -> (data[r].features[i] - mean[i]) / std[i] } }

            // Balance the categories so none dominates.
            val classWeight = FloatArray(K)
            for (s in data) classWeight[s.label] += s.weight
            val present = classWeight.count { it > 0f }
            val sw = FloatArray(n) { data[it].weight / (present * classWeight[data[it].label]) }

            val w = Array(K) { FloatArray(dim) }
            val b = FloatArray(K)
            val lr = 0.5f
            val lambda = 0.02f
            val grad = Array(K) { FloatArray(dim) }
            val gb = FloatArray(K)
            val z = FloatArray(K)
            repeat(150) {
                for (g in grad) g.fill(0f)
                gb.fill(0f)
                for (r in 0 until n) {
                    val xr = x[r]
                    for (k in 0 until K) {
                        var s = b[k]
                        val wk = w[k]
                        for (i in 0 until dim) s += wk[i] * xr[i]
                        z[k] = s
                    }
                    val p = softmax(z)
                    for (k in 0 until K) {
                        val g = (p[k] - if (data[r].label == k) 1f else 0f) * sw[r]
                        if (g == 0f) continue
                        val gk = grad[k]
                        for (i in 0 until dim) gk[i] += g * xr[i]
                        gb[k] += g
                    }
                }
                for (k in 0 until K) {
                    val wk = w[k]
                    val gk = grad[k]
                    for (i in 0 until dim) wk[i] -= lr * (gk[i] + lambda * wk[i])
                    b[k] -= lr * gb[k]
                }
            }
            return Model(mean, std, w, b, n)
        }
    }
}
