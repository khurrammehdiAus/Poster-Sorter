package com.khurram.postersorter

import android.app.Application
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class Phase { HOME, SCANNING, RESULTS }

class Item(
    val file: File,
    val features: FloatArray,
    val score: Float,
    val decision: Decision,
) {
    var selected by mutableStateOf(decision == Decision.POSTER)
    var touched = false

    fun set(value: Boolean) {
        selected = value
        touched = true
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val moveLog = MoveLog(app)
    private val labels by lazy { LabelStore(ctx) }
    private val modelFile get() = File(ctx.filesDir, "model.bin")
    private var model: Model? = null

    var hasPermission by mutableStateOf(false)
    var phase by mutableStateOf(Phase.HOME)
    var sources by mutableStateOf<List<File>>(emptyList())
    var imageCount by mutableIntStateOf(0)
    var progressDone by mutableIntStateOf(0)
    var progressTotal by mutableIntStateOf(0)
    var languages by mutableStateOf("")
    var undoCount by mutableIntStateOf(0)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var learnedPosters by mutableIntStateOf(0)
    var learnedOthers by mutableIntStateOf(0)
    var learningActive by mutableStateOf(false)
    val items = mutableStateListOf<Item>()

    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                model = Model.load(modelFile)
                labels.samples()
            }
            updateLearningStats()
        }
    }

    private fun updateLearningStats() {
        learnedPosters = labels.posters()
        learnedOthers = labels.others()
        learningActive = model != null
    }

    fun refresh() {
        hasPermission = Environment.isExternalStorageManager()
        if (!hasPermission) return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { Storage.sourceDirs() }
            val count = withContext(Dispatchers.IO) { found.sumOf { Storage.listImages(it).size } }
            sources = found
            imageCount = count
            undoCount = withContext(Dispatchers.IO) { moveLog.count() }
        }
    }

    fun startScan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            phase = Phase.SCANNING
            items.clear()
            progressDone = 0
            val files = withContext(Dispatchers.IO) { sources.flatMap { Storage.listImages(it) } }
            // Posters you've already moved are examples to learn from.
            val seeds = withContext(Dispatchers.IO) {
                Storage.listImages(Storage.posterDir).filter { !labels.has(it.name) }
            }
            val all = files + seeds
            progressTotal = all.size

            val (dataPath, langs) = withContext(Dispatchers.IO) { OcrData.prepare(ctx) }
            languages = langs
            val first = withContext(Dispatchers.Default) { Analyzer(ctx, dataPath, langs) }
            if (!first.ok) {
                first.close()
                message = "The text reader couldn't start. Please reinstall the app."
                phase = Phase.HOME
                return@launch
            }

            val store = withContext(Dispatchers.IO) { FeatureStore(ctx) }
            val results = ConcurrentHashMap<String, Analysis>()
            val queue = Channel<File>(Channel.UNLIMITED)
            all.forEach { queue.trySend(it) }
            queue.close()
            val workers = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 3)

            try {
                coroutineScope {
                    repeat(workers) { index ->
                        launch(Dispatchers.Default) {
                            val analyzer = if (index == 0) first else Analyzer(ctx, dataPath, langs)
                            try {
                                for (f in queue) {
                                    ensureActive()
                                    val a = store.get(f) ?: try {
                                        analyzer.analyze(f)?.also { store.put(f, it) }
                                    } catch (e: Exception) {
                                        null
                                    }
                                    if (a != null) results[f.path] = a
                                    withContext(Dispatchers.Main) { progressDone++ }
                                }
                            } finally {
                                analyzer.close()
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { store.save() }
                phase = Phase.HOME
                throw e
            }

            withContext(Dispatchers.IO) { store.save() }

            // Learn from the Event Posters folder.
            val seeded = seeds.mapNotNull { f -> results[f.path]?.let { f to it } }
            if (seeded.isNotEmpty()) {
                seeded.forEach { (f, a) -> labels.put(f.name, Sample(1f, 1f, a.features)) }
                retrain()
            }

            val m = model
            val built = files.mapNotNull { f ->
                results[f.path]?.let { a ->
                    val s = m?.score(a.features) ?: Features.heuristicScore(a.features)
                    val d = when {
                        s >= 0.6f -> Decision.POSTER
                        s >= 0.35f -> Decision.CHECK
                        else -> Decision.KEEP
                    }
                    Item(f, a.features, s, d)
                }
            }.sortedByDescending { it.score }
            items.addAll(built)
            phase = Phase.RESULTS
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        phase = Phase.HOME
        message = "Stopped. Images already read are remembered, so the next scan picks up quickly."
    }

    fun goHome() {
        phase = Phase.HOME
        items.clear()
        refresh()
    }

    /**
     * Learns from what you did: images you moved or deleted are posters;
     * images you unselected aren't. Images you never touched in the Kept
     * tab count only a little, in case you didn't look at them.
     */
    private suspend fun learnFrom(actedOn: List<Item>) {
        val acted = actedOn.toSet()
        val untouchedKept = items.filter {
            it !in acted && !it.selected && !it.touched && it.decision == Decision.KEEP
        }.shuffled().take(300).toSet()
        withContext(Dispatchers.Default) {
            for (item in actedOn) labels.put(item.file.name, Sample(1f, 1f, item.features))
            for (item in items) {
                if (item in acted || item.selected) continue
                val weight = when {
                    item.touched -> 1f
                    item.decision == Decision.CHECK -> 0.5f
                    item.decision == Decision.POSTER -> 0.7f
                    item in untouchedKept -> 0.15f
                    else -> continue
                }
                labels.put(item.file.name, Sample(0f, weight, item.features))
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
        val selected = items.filter { it.selected }
        if (selected.isEmpty() || busy) return
        viewModelScope.launch {
            busy = true
            val moved = withContext(Dispatchers.IO) {
                selected.mapNotNull { item ->
                    Storage.move(item.file, Storage.posterDir)?.let { dest ->
                        moveLog.add(item.file, dest)
                        item to dest
                    }
                }
            }
            scanMedia(moved.flatMap { listOf(it.first.file.path, it.second.path) })
            learnFrom(moved.map { it.first })
            items.removeAll(moved.map { it.first }.toSet())
            undoCount = withContext(Dispatchers.IO) { moveLog.count() }
            busy = false
            val failed = selected.size - moved.size
            message = "Moved ${moved.size} to Pictures › Event Posters" +
                (if (failed > 0) " ($failed couldn't be moved)" else "") +
                ". Learned from your choices — scan again for better results."
        }
    }

    fun deleteSelected() {
        val selected = items.filter { it.selected }
        if (selected.isEmpty() || busy) return
        viewModelScope.launch {
            busy = true
            val deleted = withContext(Dispatchers.IO) { selected.filter { it.file.delete() } }
            scanMedia(deleted.map { it.file.path })
            learnFrom(deleted)
            items.removeAll(deleted.toSet())
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
            message = "Put $n images back in WhatsApp"
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
