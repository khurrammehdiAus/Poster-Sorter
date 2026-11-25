package com.khurram.postersorter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

// Siftly brand colours
object Brand {
    val Teal = Color(0xFF0E4B5A)
    val Mint = Color(0xFF34D399)
    val Coral = Color(0xFFFF7A59)
    val Cream = Color(0xFFFFF7EE)
    val Ink = Color(0xFF102A33)
}

private val LightColors: ColorScheme = lightColorScheme(
    primary = Brand.Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFE4),
    onPrimaryContainer = Brand.Ink,
    secondary = Brand.Mint,
    onSecondary = Brand.Ink,
    tertiary = Brand.Coral,
    onTertiary = Color.White,
    background = Brand.Cream,
    onBackground = Brand.Ink,
    surface = Brand.Cream,
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFF2E8DC),
    onSurfaceVariant = Color(0xFF4A5A60),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Brand.Mint,
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF1C5A4B),
    onPrimaryContainer = Color(0xFFCDEFE4),
    secondary = Color(0xFF7CC7D6),
    tertiary = Brand.Coral,
    background = Color(0xFF0B1F25),
    onBackground = Color(0xFFE6EEF0),
    surface = Color(0xFF0B1F25),
    onSurface = Color(0xFFE6EEF0),
    surfaceVariant = Color(0xFF173640),
    onSurfaceVariant = Color(0xFFB7C7CC),
)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
                App(vm)
            }
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
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
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
fun BrandHeader(size: Int = 56) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(size.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Siftly", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            Text(
                "Keep the memories. Sift the clutter.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        BrandHeader()
        Spacer(Modifier.height(28.dp))
        Text(
            "Siftly finds posters, forwards and memes in your WhatsApp images, so you can " +
                "clear them out without touching photos of family and friends."
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "To move files it needs permission to manage files. Everything happens on your phone. Nothing is uploaded.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                )
                try {
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Give permission") }
        Spacer(Modifier.height(12.dp))
        Text(
            "Turn on \"Allow access to manage all files\", then come back to Siftly.",
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
        BrandHeader()
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(16.dp)) {
                if (vm.sources.isEmpty()) {
                    Text("Couldn't find your WhatsApp Images folder.", fontWeight = FontWeight.Bold)
                } else {
                    Text("${vm.imageCount}", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("WhatsApp images to sift", style = MaterialTheme.typography.titleMedium)
                    Text("Images you've sent are left alone.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Button(
            onClick = vm::startScan,
            enabled = vm.imageCount > 0 && !vm.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Sift my images", fontSize = 17.sp) }
        Text(
            "Finds event posters, forwards (greetings, chain messages, ads) and memes. " +
                "Reads English, Urdu and Arabic. The first sift takes 1–3 seconds per image, " +
                "so keep the app open. Later sifts are quick.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            "Cleared images go to Pictures › Siftly › Posters, Forwards and Memes. " +
                "Check them in your Gallery, then delete them there.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun LearningCard(vm: MainViewModel) {
    var confirmReset by remember { mutableStateOf(false) }
    val l = vm.learned
    val keep = l[Category.KEEP] ?: 0
    val clutter = Category.clutter.sumOf { l[it] ?: 0 }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Learning", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            val counts = "${l[Category.POSTER] ?: 0} posters, ${l[Category.FORWARD] ?: 0} forwards, " +
                "${l[Category.MEME] ?: 0} memes and $keep keepers"
            if (vm.learningActive) {
                Text("Learned from $counts. Your choices now shape the results.")
            } else {
                Text(
                    "Learns from what you clear, re-label and unselect. So far: $counts. " +
                        "It starts using them after ${Model.MIN_EACH} keepers and ${Model.MIN_EACH} clutter images.",
                )
            }
            if (keep + clutter > 0) {
                TextButton(onClick = { confirmReset = true }) { Text("Reset learning") }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset learning?") },
            text = { Text("Siftly forgets what it learned from your choices and goes back to its starting rules. Your images aren't affected.") },
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
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(88.dp))
        Spacer(Modifier.height(20.dp))
        Text("Sifting…", style = MaterialTheme.typography.titleLarge)
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
        Bucket.POSTER to "Posters",
        Bucket.FORWARD to "Forwards",
        Bucket.MEME to "Memes",
        Bucket.CHECK to "Not sure",
        Bucket.KEEP to "Kept",
    )
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<Item?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val current = vm.items.filter { it.bucket == tabs[tab].first }
    val selectedCount = vm.items.count { it.selected }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
            IconButton(onClick = vm::goHome) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text("Results", style = MaterialTheme.typography.titleLarge)
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, (bucket, label) ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text("$label (${vm.items.count { it.bucket == bucket }})") },
                )
            }
        }
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { current.forEach { it.set(true) } }) { Text("Select all") }
            TextButton(onClick = { current.forEach { it.set(false) } }) { Text("Select none") }
        }
        val hint = when (tabs[tab].first) {
            Bucket.KEEP -> "Most likely clutter is shown first. Select anything it missed — it learns from it."
            Bucket.CHECK -> "Siftly isn't sure about these. Tap to clear, hold to view and label."
            else -> null
        }
        if (hint != null && current.isNotEmpty()) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (current.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Nothing here", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    "$selectedCount selected · tap to select, hold to view and label",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = vm::moveSelected,
                        enabled = selectedCount > 0 && !vm.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Move to Siftly folders") }
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        enabled = selectedCount > 0 && !vm.busy,
                    ) { Text("Delete", color = Brand.Coral) }
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
                }) { Text("Delete", color = Brand.Coral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    viewing?.let { item -> Viewer(item, onClose = { viewing = null }) }
}

/** Full-size view, with buttons to say what the image is. */
@Composable
fun Viewer(item: Item, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable { onClose() }) {
            AsyncImage(
                model = item.file,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("This is a…", color = Color.White)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in listOf(Category.POSTER, Category.FORWARD, Category.MEME, Category.KEEP)) {
                        val chosen = if (c == Category.KEEP) !item.selected else item.selected && item.assigned == c
                        Button(
                            onClick = { item.assign(c) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (chosen) (if (c == Category.KEEP) Brand.Mint else Brand.Coral) else Color(0xCC1E2A2E),
                                contentColor = if (chosen && c == Category.KEEP) Brand.Ink else Color.White,
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) { Text(if (c == Category.KEEP) "Keeper" else c.label) }
                    }
                }
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
            .clip(RoundedCornerShape(10.dp))
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
            Text(
                item.assigned.label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Brand.Coral, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Checkbox(
            checked = item.selected,
            onCheckedChange = { item.set(it) },
            colors = CheckboxDefaults.colors(
                checkedColor = Brand.Coral,
                uncheckedColor = Color.White,
                checkmarkColor = Color.White,
            ),
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}
