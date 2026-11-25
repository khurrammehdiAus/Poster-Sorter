package com.khurram.postersorter

import android.app.Application
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Phase { HOME, RESULTS }

/**
 * One image in the results.
 * bucket = the tab it was sorted into; assigned = what you say it is
 * (starts as Siftly's best guess); selected = clear it out.
 */
class Item(
    val file: File,
    val features: FloatArray,
    val clutterScore: Float,
    val bucket: Bucket,
    guess: Category,
) {
    var assigned by mutableStateOf(if (bucket == Bucket.KEEP || bucket == Bucket.CHECK) guess else Category.entries[bucket.ordinal + 1])
    var selected by mutableStateOf(bucket in setOf(Bucket.POSTER, Bucket.FORWARD, Bucket.MEME))
    var touched = false

    fun set(value: Boolean) {
        selected = value
        touched = true
    }

    fun assign(c: Category) {
        touched = true
        if (c == Category.KEEP) {
            selected = false
        } else {
            assigned = c
            selected = true
        }
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val prefs = app.getSharedPreferences("siftly", Context.MODE_PRIVATE)
    private val moveLog = MoveLog(app)
    private val labels by lazy { LabelStore(ctx) }
    private val modelFile get() = File(ctx.filesDir, "model_v3.bin")
    private var model: Model? = null

    var hasPermission by mutableStateOf(false)
    var phase by mutableStateOf(Phase.HOME)
    var albums by mutableStateOf<List<Album>>(emptyList())
    var selectedAlbums by mutableStateOf<Set<String>>(emptySet())
    var findDuplicates by mutableStateOf(prefs.getBoolean("findDuplicates", true))
    var loadingAlbums by mutableStateOf(false)
    var undoCount by mutableIntStateOf(0)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var learned by mutableStateOf(mapOf<Category, Int>())
    var learningActive by mutableStateOf(false)
    val items = mutableStateListOf<Item>()
    val dupGroups = mutableStateListOf<DupGroup>()

    val imageCount: Int get() = albums.filter { it.key in selectedAlbums }.sumOf { it.count }

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                model = Model.load(modelFile)
                labels.samples()
            }
            updateLearningStats()
        }
        // Build results whenever a sift finishes (even one that ran while the app was closed).
        viewModelScope.launch {
            snapshotFlow { ScanEngine.finished }.collect { n ->
                if (n > ScanEngine.consumed) {
                    ScanEngine.consumed = n
                    buildResults()
                }
            }
        }
        viewModelScope.launch {
            snapshotFlow { ScanEngine.error }.collect { e ->
                if (e != null) { message = e; ScanEngine.error = null }
            }
        }
    }

    private fun updateLearningStats() {
        learned = Category.entries.associateWith { labels.count(it) }
        learningActive = model != null
    }

    fun refresh() {
        hasPermission = Environment.isExternalStorageManager()
        if (!hasPermission) return
        viewModelScope.launch {
            loadingAlbums = true
            val found = withContext(Dispatchers.IO) { Gallery.albums(ctx) }
            albums = found
            // First time: every album. After that: what you chose, plus any new albums.
            val saved = prefs.getStringSet("albums", null)
            val known = prefs.getStringSet("knownAlbums", null)
            selectedAlbums = if (saved == null) {
                found.map { it.key }.toSet()
            } else {
                found.map { it.key }.filter { it in saved || (known != null && it !in known) }.toSet()
            }
            prefs.edit().putStringSet("knownAlbums", found.map { it.key }.toSet()).apply()
            loadingAlbums = false
            undoCount = withContext(Dispatchers.IO) { moveLog.count() }
        }
    }

    fun setAlbums(keys: Set<String>) {
        selectedAlbums = keys
        prefs.edit().putStringSet("albums", keys).apply()
    }

    fun toggleDuplicates(on: Boolean) {
        findDuplicates = on
        prefs.edit().putBoolean("findDuplicates", on).apply()
    }

    fun startScan() {
        if (ScanEngine.running) return
        viewModelScope.launch {
            items.clear()
            dupGroups.clear()
            val chosen = albums.filter { it.key in selectedAlbums }
            val files = chosen.flatMap { it.files }.distinctBy { it.path }
            // Images you've already sorted into Siftly's folders are examples to learn from.
            val seeds = withContext(Dispatchers.IO) {
                Storage.exampleFolders().flatMap { (dir, c) ->
                    Storage.listImages(dir).filter { !labels.has(it.name) }.map { it to c }
                }
            }
            ScanEngine.start(ctx, files, seeds, findDuplicates)
        }
    }

    fun cancelScan() {
        ScanEngine.cancel()
        message = "Stopped. Images already read are remembered, so the next sift picks up quickly."
    }

    private suspend fun buildResults() {
        val files = ScanEngine.files
        val results = ScanEngine.results

        // Learn from the example folders, and carry over older choices.
        var changed = false
        for ((f, c) in ScanEngine.seeds) {
            results[f.path]?.let { labels.put(f.name, Sample(c.ordinal, 1f, it.features)); changed = true }
        }
        for (f in files) {
            results[f.path]?.let { if (labels.migrate(f.name, it.features)) changed = true }
        }
        if (changed) retrain()

        val m = model
        val built = withContext(Dispatchers.Default) {
            files.mapNotNull { f ->
                results[f.path]?.let { a ->
                    val p = m?.score(a.features) ?: Features.heuristicFrom(a.features)
                    val best = Category.clutter.maxBy { p[it.ordinal] }
                    val clutter = 1f - p[0]
                    val bucket = when {
                        p[best.ordinal] >= 0.55f -> best.bucket()
                        clutter >= 0.35f -> Bucket.CHECK
                        else -> Bucket.KEEP
                    }
                    Item(f, a.features, clutter, bucket, best)
                }
            }.sortedByDescending { it.clutterScore }
        }
        items.clear()
        items.addAll(built)
        dupGroups.clear()
        dupGroups.addAll(ScanEngine.dupGroups)
        phase = Phase.RESULTS
    }

    fun goHome() {
        phase = Phase.HOME
        items.clear()
        dupGroups.clear()
        refresh()
    }

    /** Everything selected, across all tabs, each with the folder it should go to. */
    private fun selection(): List<Pair<File, File>> {
        val out = LinkedHashMap<String, Pair<File, File>>()
        for (item in items) if (item.selected) out[item.file.path] = item.file to Storage.folderFor(item.assigned)
        for (g in dupGroups) for (m in g.members) {
            if (m.selected && m.file.path !in out) out[m.file.path] = m.file to Storage.duplicatesDir
        }
        return out.values.toList()
    }

    val selectedCount: Int
        get() = (items.filter { it.selected }.map { it.file.path } +
            dupGroups.flatMap { g -> g.members.filter { it.selected }.map { it.file.path } }).toSet().size

    /** Takes handled images out of every tab. */
    private fun removeFromResults(paths: Set<String>) {
        items.removeAll { it.file.path in paths }
        val updated = dupGroups.mapNotNull { g ->
            val left = g.members.filter { it.file.path !in paths }
            if (left.size >= 2) (if (left.size == g.members.size) g else DupGroup(left)) else null
        }
        dupGroups.clear()
        dupGroups.addAll(updated)
    }

    /**
     * Learns from what you did: images you cleared out are examples of the
     * type you gave them; images you unselected are ones to keep. Images you
     * never touched in the Kept tab count only a little, in case you didn't
     * look at them.
     */
    private suspend fun learnFrom(handled: Set<String>) {
        val acted = items.filter { it.file.path in handled && it.selected }
        val actedSet = acted.toSet()
        val untouchedKept = items.filter {
            it !in actedSet && !it.selected && !it.touched && it.bucket == Bucket.KEEP
        }.shuffled().take(300).toSet()
        val snapshot = items.toList()
        withContext(Dispatchers.Default) {
            for (item in acted) {
                labels.put(item.file.name, Sample(item.assigned.ordinal, 1f, item.features))
            }
            for (item in snapshot) {
                if (item in actedSet || item.selected) continue
                val weight = when {
                    item.touched -> 1f
                    item.bucket == Bucket.CHECK -> 0.5f
                    item.bucket != Bucket.KEEP -> 0.7f
                    item in untouchedKept -> 0.15f
                    else -> continue
                }
                labels.put(item.file.name, Sample(Category.KEEP.ordinal, weight, item.features))
            }
        }
        retrain()
    }

    private suspend fun retrain() {
        withContext(Dispatchers.Default) {
            labels.save()
            val m = Model.train(labels.samples())
            if (m != null) {
                m.save(modelFile)
                model = m
            }
        }
        updateLearningStats()
    }

    fun resetLearning() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                labels.clear()
                modelFile.delete()
            }
            model = null
            updateLearningStats()
            message = "Learning reset. Back to the starting rules."
        }
    }

    fun moveSelected() {
        val chosen = selection()
        if (chosen.isEmpty() || busy) return
        viewModelScope.launch {
            busy = true
            val moved = withContext(Dispatchers.IO) {
                chosen.mapNotNull { (file, dir) ->
                    Storage.move(file, dir)?.let { dest ->
                        moveLog.add(file, dest)
                        file to dest
                    }
                }
            }
            scanMedia(moved.flatMap { listOf(it.first.path, it.second.path) })
            val paths = moved.map { it.first.path }.toSet()
            learnFrom(paths)
            removeFromResults(paths)
            undoCount = withContext(Dispatchers.IO) { moveLog.count() }
            busy = false
            val failed = chosen.size - moved.size
            message = "Moved ${moved.size} to Pictures › Siftly" +
                (if (failed > 0) " ($failed couldn't be moved)" else "") +
                ". Learned from your choices."
        }
    }

    fun deleteSelected() {
        val chosen = selection()
        if (chosen.isEmpty() || busy) return
        viewModelScope.launch {
            busy = true
            val deleted = withContext(Dispatchers.IO) { chosen.map { it.first }.filter { it.delete() } }
            scanMedia(deleted.map { it.path })
            val paths = deleted.map { it.path }.toSet()
            learnFrom(paths)
            removeFromResults(paths)
            busy = false
            message = "Deleted ${deleted.size} images. Learned from your choices."
        }
    }

    fun undoMoves() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            val n = withContext(Dispatchers.IO) { moveLog.undoAll() }
            busy = false
            message = "Put $n images back where they were"
            refresh()
        }
    }

    /** Tells the Gallery that files changed, so it updates. */
    private fun scanMedia(paths: List<String>) {
        if (paths.isNotEmpty()) {
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
        }
    }
}
