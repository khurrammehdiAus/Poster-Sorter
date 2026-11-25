package com.khurram.postersorter

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object Storage {
    private val root: File get() = Environment.getExternalStorageDirectory()

    private val candidates: List<String> = listOf(
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
        "WhatsApp/Media/WhatsApp Images",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images",
    )

    val posterDir: File get() = File(root, "Pictures/Event Posters")

    /** WhatsApp image folders that exist on this phone. */
    fun sourceDirs(): List<File> = candidates.map { File(root, it) }.filter { it.isDirectory }

    private val exts = setOf("jpg", "jpeg", "png", "webp")

    /** Received images only (top level; skips the Sent and Private subfolders). */
    fun listImages(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in exts }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Moves a file into a folder without overwriting. Returns the new file, or null. */
    fun move(file: File, destDir: File): File? {
        destDir.mkdirs()
        var dest = File(destDir, file.name)
        var i = 1
        while (dest.exists()) {
            dest = File(destDir, "${file.nameWithoutExtension}_$i.${file.extension}")
            i++
        }
        if (file.renameTo(dest)) return dest
        return try {
            file.copyTo(dest)
            if (file.delete()) dest else { dest.delete(); null }
        } catch (e: Exception) {
            null
        }
    }
}

/** Remembers results so images aren't read twice. */
class ScanCache(context: Context) {
    private val file = File(context.filesDir, "scan_cache.tsv")
    private val map = ConcurrentHashMap<String, Pair<Long, ScanResult>>()

    init {
        if (file.exists()) {
            file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 5) {
                    val d = runCatching { Decision.valueOf(p[2]) }.getOrNull() ?: return@forEachLine
                    map[p[0]] = p[1].toLong() to ScanResult(d, p[3].toInt(), p[4].toInt())
                }
            }
        }
    }

    fun get(f: File): ScanResult? =
        map[f.path]?.takeIf { it.first == f.lastModified() }?.second

    fun put(f: File, r: ScanResult) { map[f.path] = f.lastModified() to r }

    fun remove(f: File) { map.remove(f.path) }

    fun save() {
        file.bufferedWriter().use { w ->
            for ((path, v) in map) {
                if (!File(path).exists()) continue
                val r = v.second
                w.write("$path\t${v.first}\t${r.decision}\t${r.words}\t${r.keywords}\n")
            }
        }
    }
}

/** Log of moves, so they can be undone. */
class MoveLog(context: Context) {
    private val file = File(context.filesDir, "moves.tsv")

    fun add(original: File, moved: File) = file.appendText("${original.path}\t${moved.path}\n")

    fun count(): Int = if (file.exists()) file.readLines().count { it.isNotBlank() } else 0

    /** Puts every logged file back. Returns how many were restored. */
    fun undoAll(): Int {
        if (!file.exists()) return 0
        var restored = 0
        for (line in file.readLines()) {
            val p = line.split('\t')
            if (p.size != 2) continue
            val orig = File(p[0])
            val moved = File(p[1])
            if (!moved.exists() || orig.exists()) continue
            orig.parentFile?.mkdirs()
            val ok = moved.renameTo(orig) || runCatching {
                moved.copyTo(orig); moved.delete()
            }.getOrDefault(false)
            if (ok) restored++
        }
        file.delete()
        return restored
    }

    fun clear() { file.delete() }
}
