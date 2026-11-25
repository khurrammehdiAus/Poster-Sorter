package com.khurram.postersorter

import android.content.Context
import android.os.Environment
import java.io.File

object Storage {
    private val root: File get() = Environment.getExternalStorageDirectory()

    private val candidates: List<String> = listOf(
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
        "WhatsApp/Media/WhatsApp Images",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images",
    )

    /** Where each kind of clutter is moved: Pictures/Siftly/Posters, /Forwards, /Memes. */
    fun folderFor(c: Category): File = File(root, "Pictures/Siftly/${c.folder}")

    val duplicatesDir: File get() = File(root, "Pictures/Siftly/Duplicates")

    /** Siftly's own folders, left out when sifting the gallery. */
    fun excludedRoots(): List<String> =
        listOf(File(root, "Pictures/Siftly").path, File(root, "Pictures/Event Posters").path)

    /** Folders whose images are examples to learn from, and what they're examples of. */
    fun exampleFolders(): List<Pair<File, Category>> =
        Category.clutter.map { folderFor(it) to it } +
            (File(root, "Pictures/Event Posters") to Category.POSTER) // from Poster Sorter

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
