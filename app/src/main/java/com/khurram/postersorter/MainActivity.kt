package com.khurram.postersorter

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(this)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) { App(vm) }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }
}

@Composable
fun App(vm: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                !vm.hasPermission -> PermissionScreen()
                vm.phase == Phase.SCANNING -> ScanningScreen(vm)
                vm.phase == Phase.RESULTS -> ResultsScreen(vm)
                else -> HomeScreen(vm)
            }
        }
    }
}

@Composable
fun PermissionScreen() {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Poster Sorter", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            "To find posters in your WhatsApp images and move them, this app needs " +
                "permission to manage files. Everything happens on your phone. Nothing is uploaded."
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            )
            try {
                ctx.startActivity(intent)
            } catch (e: Exception) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }) { Text("Give permission") }
        Spacer(Modifier.height(12.dp))
        Text(
            "Turn on \"Allow access to manage all files\", then come back to this app.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun HomeScreen(vm: MainViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Poster Sorter", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Finds event posters in your WhatsApp images so you can clear them out, without touching photos of family and friends.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (vm.sources.isEmpty()) {
                    Text("Couldn't find your WhatsApp Images folder.", fontWeight = FontWeight.Bold)
                } else {
                    Text("${vm.imageCount} WhatsApp images found", style = MaterialTheme.typography.titleMedium)
                    Text("Sent images are left alone.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Button(
            onClick = vm::startScan,
            enabled = vm.imageCount > 0 && !vm.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Scan for posters") }
        Text(
            "The first scan takes 1–3 seconds per image, so keep the app open. " +
                "Images already checked are remembered, so later scans are quick. " +
                "Reads English, Urdu and Arabic.",
            style = MaterialTheme.typography.bodySmall,
        )
        LearningCard(vm)
        if (vm.undoCount > 0) {
            OutlinedButton(
                onClick = vm::undoMoves,
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Undo moves (${vm.undoCount} images back to WhatsApp)") }
        }
        Text(
            "Moved posters go to Pictures › Event Posters. Check them in your Gallery, then delete them there.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun ScanningScreen(vm: MainViewModel) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Reading images…", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { if (vm.progressTotal > 0) vm.progressDone / vm.progressTotal.toFloat() else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text("${vm.progressDone} of ${vm.progressTotal}")
        if (vm.languages.isNotEmpty()) {
            Text("Languages: ${vm.languages.replace("+", ", ")}", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Text("Keep this screen open.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = vm::cancelScan) { Text("Stop") }
    }
}

@Composable
fun ResultsScreen(vm: MainViewModel) {
    val tabs = listOf(
        Decision.POSTER to "Posters",
        Decision.CHECK to "Not sure",
        Decision.KEEP to "Kept",
    )
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<Item?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val current = vm.items.filter { it.decision == tabs[tab].first }
    val selectedCount = vm.items.count { it.selected }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::goHome) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Results", style = MaterialTheme.typography.titleLarge)
        }
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, (decision, label) ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text("$label (${vm.items.count { it.decision == decision }})") },
                )
            }
        }
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { current.forEach { it.set(true) } }) { Text("Select all") }
            TextButton(onClick = { current.forEach { it.set(false) } }) { Text("Select none") }
        }
        if (tabs[tab].first == Decision.KEEP && current.isNotEmpty()) {
            Text(
                "Most likely posters are shown first. Select any it missed — it learns from them.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (current.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Nothing here")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(4.dp),
            ) {
                items(current, key = { it.file.path }) { item ->
                    Tile(item, onLongPress = { viewing = item })
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    "$selectedCount selected · tap to select, hold to view",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = vm::moveSelected,
                        enabled = selectedCount > 0 && !vm.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Move to Event Posters") }
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        enabled = selectedCount > 0 && !vm.busy,
                    ) { Text("Delete") }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $selectedCount images?") },
            text = { Text("They'll be permanently deleted from your phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSelected()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    viewing?.let { item ->
        Dialog(
            onDismissRequest = { viewing = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color.Black).clickable { viewing = null },
            ) {
                AsyncImage(
                    model = item.file,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Button(
                    onClick = { item.set(!item.selected) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
                ) { Text(if (item.selected) "Unselect" else "Select") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(item: Item, onLongPress: () -> Unit) {
    Box(
        Modifier
            .padding(3.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = { item.set(!item.selected) },
                onLongClick = onLongPress,
            ),
    ) {
        AsyncImage(
            model = item.file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.selected) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }
        Checkbox(
            checked = item.selected,
            onCheckedChange = { item.set(it) },
            colors = CheckboxDefaults.colors(uncheckedColor = Color.White),
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
fun LearningCard(vm: MainViewModel) {
    var confirmReset by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Learning", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            val counts = "${vm.learnedPosters} posters and ${vm.learnedOthers} other images"
            if (vm.learningActive) {
                Text("Learned from $counts. Your choices now shape the results.")
            } else {
                Text(
                    "Learns from what you move, delete and unselect. So far: $counts. " +
                        "It starts using them after ${Model.MIN_EACH} of each.",
                )
            }
            if (vm.learnedPosters + vm.learnedOthers > 0) {
                TextButton(onClick = { confirmReset = true }) { Text("Reset learning") }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset learning?") },
            text = { Text("The app forgets what it learned from your choices and goes back to its starting rules. Your images aren't affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    vm.resetLearning()
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
