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

    private val file = File(context.filesDir, "features_v2.bin")
    private val map = ConcurrentHashMap<String, Entry>()

    init {
        if (file.exists()) runCatching {
            DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
                while (true) {
                    val path = try { input.readUTF() } catch (e: EOFException) { break }
                    val modified = input.readLong()
                    val decision = Decision.entries[input.readInt()]
                    val words = input.readInt()
                    val kw = input.readInt()
                    val feats = input.readFloats()
                    map[path] = Entry(modified, Analysis(feats, ScanResult(decision, words, kw)))
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
                out.writeInt(e.analysis.heuristic.decision.ordinal)
                out.writeInt(e.analysis.heuristic.words)
                out.writeInt(e.analysis.heuristic.keywords)
                out.writeFloats(e.analysis.features)
            }
        }
    }
}

class Sample(val label: Float, val weight: Float, val features: FloatArray)

/** Your choices: which images were posters and which weren't. */
class LabelStore(context: Context) {
    private val file = File(context.filesDir, "labels.bin")
    private val map = ConcurrentHashMap<String, Sample>()

    init {
        if (file.exists()) runCatching {
            DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
                while (true) {
                    val key = try { input.readUTF() } catch (e: EOFException) { break }
                    val label = input.readFloat()
                    val weight = input.readFloat()
                    map[key] = Sample(label, weight, input.readFloats())
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

    fun samples(): List<Sample> = map.values.toList()
    fun posters() = map.values.count { it.label > 0.5f }
    fun others() = map.values.count { it.label <= 0.5f }

    fun save() {
        DataOutputStream(BufferedOutputStream(file.outputStream(), 1 shl 16)).use { out ->
            for ((key, s) in map) {
                out.writeUTF(key)
                out.writeFloat(s.label)
                out.writeFloat(s.weight)
                out.writeFloats(s.features)
            }
        }
    }

    fun clear() {
        map.clear()
        file.delete()
    }
}

/** A small model trained on your choices (logistic regression). */
class Model(
    private val mean: FloatArray,
    private val std: FloatArray,
    private val w: FloatArray,
    private val b: Float,
    val trainedOn: Int,
) {
    fun predict(x: FloatArray): Float? {
        if (x.size != w.size) return null
        var z = b
        for (i in w.indices) z += w[i] * (x[i] - mean[i]) / std[i]
        return sigmoid(z)
    }

    /** Blends learned guess with the starting rules; trusts learning more as it grows. */
    fun score(x: FloatArray): Float {
        val h = Features.heuristicScore(x)
        val p = predict(x) ?: return h
        val a = min(0.85f, trainedOn / (trainedOn + 30f))
        return a * p + (1 - a) * h
    }

    fun save(file: File) {
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { out ->
            out.writeFloats(mean); out.writeFloats(std); out.writeFloats(w)
            out.writeFloat(b); out.writeInt(trainedOn)
        }
    }

    companion object {
        const val MIN_EACH = 5

        private fun sigmoid(z: Float) = 1f / (1f + exp(-z.coerceIn(-30f, 30f)))

        fun load(file: File): Model? = if (!file.exists()) null else runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                Model(input.readFloats(), input.readFloats(), input.readFloats(), input.readFloat(), input.readInt())
            }
        }.getOrNull()

        fun train(all: List<Sample>): Model? {
            if (all.isEmpty()) return null
            val dim = all.groupingBy { it.features.size }.eachCount().maxByOrNull { it.value }!!.key
            val data = all.filter { it.features.size == dim }.takeLast(3000)
            val pos = data.count { it.label > 0.5f }
            val neg = data.size - pos
            if (pos < MIN_EACH || neg < MIN_EACH) return null

            val n = data.size
            val mean = FloatArray(dim)
            val std = FloatArray(dim)
            for (s in data) for (i in 0 until dim) mean[i] += s.features[i] / n
            for (s in data) for (i in 0 until dim) {
                val d = s.features[i] - mean[i]; std[i] += d * d / n
            }
            for (i in 0 until dim) std[i] = sqrt(std[i]).coerceAtLeast(1e-3f)
            val x = Array(n) { r -> FloatArray(dim) { i -> (data[r].features[i] - mean[i]) / std[i] } }
            val y = FloatArray(n) { data[it].label }

            // Balance posters vs others so neither side dominates.
            val posW = data.filter { it.label > 0.5f }.sumOf { it.weight.toDouble() }.toFloat()
            val negW = data.filter { it.label <= 0.5f }.sumOf { it.weight.toDouble() }.toFloat()
            val sw = FloatArray(n) {
                val s = data[it]
                s.weight * if (s.label > 0.5f) 0.5f / posW else 0.5f / negW
            }

            val w = FloatArray(dim)
            var b = 0f
            val lr = 0.5f
            val lambda = 0.02f
            val grad = FloatArray(dim)
            repeat(250) {
                grad.fill(0f)
                var gb = 0f
                for (r in 0 until n) {
                    val xr = x[r]
                    var z = b
                    for (i in 0 until dim) z += w[i] * xr[i]
                    val g = (sigmoid(z) - y[r]) * sw[r]
                    for (i in 0 until dim) grad[i] += g * xr[i]
                    gb += g
                }
                for (i in 0 until dim) w[i] -= lr * (grad[i] + lambda * w[i])
                b -= lr * gb
            }
            return Model(mean, std, w, b, n)
        }
    }
}
