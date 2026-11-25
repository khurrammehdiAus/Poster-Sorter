package com.khurram.postersorter

import android.content.Context
import android.provider.MediaStore
import java.io.File

/** One folder of images on the phone. */
class Album(val dir: File, val name: String, val files: List<File>) {
    val key: String get() = dir.path
    val count: Int get() = files.size
}

object Gallery {
    private val exts = setOf("jpg", "jpeg", "png", "webp")

    /** Every folder with images, biggest first. Leaves out Siftly's own folders. */
    fun albums(context: Context): List<Album> {
        val byDir = LinkedHashMap<String, MutableSet<String>>()
        runCatching {
            val uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            @Suppress("DEPRECATION")
            val col = MediaStore.Images.Media.DATA
            context.contentResolver.query(uri, arrayOf(col), null, null, null)?.use { c ->
                val i = c.getColumnIndexOrThrow(col)
                while (c.moveToNext()) {
                    val path = c.getString(i) ?: continue
                    if (path.substringAfterLast('.', "").lowercase() !in exts) continue
                    val parent = path.substringBeforeLast('/', "")
                    if (parent.isNotEmpty()) byDir.getOrPut(parent) { mutableSetOf() }.add(path)
                }
            }
        }
        // WhatsApp folders, even if the phone's gallery index misses them.
        for (d in Storage.sourceDirs()) {
            val set = byDir.getOrPut(d.path) { mutableSetOf() }
            Storage.listImages(d).forEach { set.add(it.path) }
        }
        val excluded = Storage.excludedRoots()
        return byDir
            .filter { (dir, _) -> excluded.none { dir == it || dir.startsWith("$it/") } }
            .map { (dir, paths) ->
                val files = paths.map(::File).filter { it.isFile }.sortedByDescending { it.lastModified() }
                Album(File(dir), displayName(File(dir)), files)
            }
            .filter { it.files.isNotEmpty() }
            .sortedByDescending { it.count }
    }

    fun displayName(dir: File?): String {
        if (dir == null) return ""
        val p = dir.path
        return when {
            p.contains("WhatsApp Business") ->
                if (dir.name == "Sent") "WhatsApp Business (sent)" else "WhatsApp Business"
            p.contains("WhatsApp Images") ->
                if (dir.name == "Sent") "WhatsApp (sent)" else "WhatsApp"
            p.endsWith("DCIM/Camera") -> "Camera"
            else -> dir.name
        }
    }
}
