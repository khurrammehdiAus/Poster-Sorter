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

class Item(val file: File, val decision: Decision) {
    var selected by mutableStateOf(decision == Decision.POSTER)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val moveLog = MoveLog(app)

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
    val items = mutableStateListOf<Item>()

    private var scanJob: Job? = null

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
            progressTotal = files.size

            val (dataPath, langs) = withContext(Dispatchers.IO) { OcrData.prepare(ctx) }
            languages = langs
            val test = withContext(Dispatchers.Default) { OcrWorker(dataPath, langs) }
            if (!test.ok) {
                test.close()
                message = "The text reader couldn't start. Please reinstall the app."
                phase = Phase.HOME
                return@launch
            }

            val cache = withContext(Dispatchers.IO) { ScanCache(ctx) }
            val results = ConcurrentHashMap<String, ScanResult>()
            val queue = Channel<File>(Channel.UNLIMITED)
            files.forEach { queue.trySend(it) }
            queue.close()
            val workers = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 3)

            try {
                coroutineScope {
                    repeat(workers) { index ->
                        launch(Dispatchers.Default) {
                            val ocr = if (index == 0) test else OcrWorker(dataPath, langs)
                            try {
                                for (f in queue) {
                                    ensureActive()
                                    val r = cache.get(f) ?: run {
                                        val words = try { ocr.words(f) } catch (e: Exception) { emptyList() }
                                        Classifier.classify(words).also { cache.put(f, it) }
                                    }
                                    results[f.path] = r
                                    withContext(Dispatchers.Main) { progressDone++ }
                                }
                            } finally {
                                ocr.close()
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { cache.save() }
                phase = Phase.HOME
                throw e
            }

            withContext(Dispatchers.IO) { cache.save() }
            items.addAll(files.mapNotNull { f -> results[f.path]?.let { Item(f, it.decision) } })
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
            scan(moved.flatMap { listOf(it.first.file.path, it.second.path) })
            items.removeAll(moved.map { it.first }.toSet())
            undoCount = withContext(Dispatchers.IO) { moveLog.count() }
            busy = false
            val failed = selected.size - moved.size
            message = "Moved ${moved.size} to Pictures › Event Posters" +
                if (failed > 0) " ($failed couldn't be moved)" else ""
        }
    }

    fun deleteSelected() {
        val selected = items.filter { it.selected }
        if (selected.isEmpty() || busy) return
        viewModelScope.launch {
            busy = true
            val deleted = withContext(Dispatchers.IO) { selected.filter { it.file.delete() } }
            scan(deleted.map { it.file.path })
            items.removeAll(deleted.toSet())
            busy = false
            message = "Deleted ${deleted.size} images"
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

    /** Tells the Gallery that files moved, so it updates. */
    private fun scan(paths: List<String>) {
        if (paths.isNotEmpty()) {
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
        }
    }
}
