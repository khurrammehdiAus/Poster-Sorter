package com.khurram.postersorter

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Does the sifting. Lives for the whole app process, so it keeps going while
 * the screen is off or you're in another app (ScanService keeps the process alive).
 */
object ScanEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    var running by mutableStateOf(false)
        private set
    var done by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    var stage by mutableStateOf("")
        private set
    var languages by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)

    /** Goes up by one each time a sift finishes; the screen builds results when it changes. */
    var finished by mutableIntStateOf(0)
        private set
    var consumed = 0

    // Results of the last sift.
    var files: List<File> = emptyList()
        private set
    var seeds: List<Pair<File, Category>> = emptyList()
        private set
    val results = ConcurrentHashMap<String, Analysis>()
    var dupGroups: List<DupGroup> = emptyList()
        private set

    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    fun start(context: Context, files: List<File>, seeds: List<Pair<File, Category>>, findDuplicates: Boolean) {
        if (running) return
        val app = context.applicationContext
        this.files = files
        this.seeds = seeds
        results.clear()
        dupGroups = emptyList()
        error = null
        done = 0
        total = files.size + seeds.size
        stage = "Reading images"
        running = true
        ContextCompat.startForegroundService(app, Intent(app, ScanService::class.java))
        job = scope.launch {
            try {
                run(app, findDuplicates)
                main { finished++ }
            } catch (e: CancellationException) {
                // stopped by you
            } catch (e: Throwable) {
                main { error = "Sifting stopped: ${e.message ?: e.javaClass.simpleName}" }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { running = false }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun run(app: Context, findDuplicates: Boolean) {
        val all = files + seeds.map { it.first }
        val (dataPath, langs) = OcrData.prepare(app)
        main { languages = langs }
        val store = FeatureStore(app)
        val hashes = HashStore(app)
        val hashMap = ConcurrentHashMap<String, Long>()

        val first = Analyzer(app, dataPath, langs)
        if (!first.ok) {
            first.close()
            throw IllegalStateException("the text reader couldn't start. Please reinstall the app")
        }

        val queue = Channel<File>(Channel.UNLIMITED)
        all.forEach { queue.trySend(it) }
        queue.close()
        val workers = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 3)
        val counter = AtomicInteger()

        try {
            coroutineScope {
                repeat(workers) { index ->
                    launch(Dispatchers.Default) {
                        val analyzer = if (index == 0) first else Analyzer(app, dataPath, langs)
                        try {
                            for (f in queue) {
                                ensureActive()
                                var a = store.get(f)
                                var h = hashes.get(f)
                                if (a == null) {
                                    val r = try { analyzer.analyze(f) } catch (e: Exception) { null }
                                    if (r != null) {
                                        a = r.first; h = r.second
                                        store.put(f, a); hashes.put(f, h)
                                    }
                                } else if (h == null && findDuplicates) {
                                    h = try { DHash.ofFile(f) } catch (e: Exception) { null }
                                    if (h != null) hashes.put(f, h)
                                }
                                if (a != null) results[f.path] = a
                                if (h != null) hashMap[f.path] = h
                                // Save progress regularly, so an interrupted sift resumes quickly.
                                if (counter.incrementAndGet() % 300 == 0) {
                                    store.flush(); hashes.save()
                                }
                                main { done++ }
                            }
                        } finally {
                            analyzer.close()
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                store.save()
                hashes.save()
            }
        }

        if (findDuplicates) {
            main { stage = "Looking for duplicates" }
            dupGroups = DupFinder.find(files, hashMap) { f ->
                results[f.path]?.features?.let {
                    if (it.size > Features.TEXT_DIMS) it.copyOfRange(Features.TEXT_DIMS, it.size) else null
                }
            }
        }
    }
}
