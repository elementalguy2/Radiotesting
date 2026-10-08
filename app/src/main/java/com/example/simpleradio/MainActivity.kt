package com.example.simpleradio

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { RadioApp() }
            }
        }
    }
}

class RadioViewModel(app: Application) : AndroidViewModel(app) {
    private val store = FavoritesStore(app)

    var favorites by mutableStateOf(store.load())
        private set
    var results by mutableStateOf<List<Station>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var current by mutableStateOf<Station?>(null)
        private set
    var wantsPlay by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var nowPlaying by mutableStateOf<String?>(null)
        private set

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)
        override fun onPlayerError(error: PlaybackException) {
            message = "Couldn't play that stream"
        }
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(listener)
                refresh(c)
            } catch (e: Exception) {
                message = "Couldn't start the audio service"
            }
        }, ContextCompat.getMainExecutor(app))
    }

    private fun refresh(p: Player) {
        wantsPlay = p.playWhenReady &&
            p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED
        buffering = wantsPlay && p.playbackState == Player.STATE_BUFFERING
        val item = p.currentMediaItem
        if (item == null) {
            current = null
            nowPlaying = null
            return
        }
        val name = item.mediaMetadata.artist?.toString()
            ?: item.mediaMetadata.title?.toString() ?: item.mediaId
        if (current?.url != item.mediaId) {
            current = Station(name, item.mediaId, item.mediaMetadata.artworkUri?.toString())
        }
        val title = p.mediaMetadata.title?.toString()
        nowPlaying = title?.takeIf { it.isNotBlank() && it != name }
    }

    fun play(s: Station) {
        val c = controller ?: run {
            message = "Player isn't ready yet, try again"
            return
        }
        val meta = MediaMetadata.Builder()
            .setTitle(s.name)
            .setArtist(s.name)
            .setIsPlayable(true)
            .setIsBrowsable(false)
        s.iconUrl?.takeIf { it.startsWith("http") }?.let { meta.setArtworkUri(Uri.parse(it)) }
        val item = MediaItem.Builder()
            .setMediaId(s.url)
            .setUri(s.url)
            .setMediaMetadata(meta.build())
            .build()
        current = s
        nowPlaying = null
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (wantsPlay) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun isFavorite(s: Station) = favorites.any { it.url == s.url }

    fun toggleFavorite(s: Station) {
        favorites = if (isFavorite(s)) favorites.filterNot { it.url == s.url } else favorites + s
        store.save(favorites)
    }

    fun addUrl(name: String, url: String) {
        val label = name.ifBlank { Uri.parse(url).host ?: url }
        val station = Station(label, url)
        if (!isFavorite(station)) {
            favorites = favorites + station
            store.save(favorites)
        }
        play(station)
    }

    fun search(query: String) {
        viewModelScope.launch {
            searching = true
            try {
                results = RadioApi.search(query)
                if (results.isEmpty()) message = "No stations found"
            } catch (e: Exception) {
                message = "Search failed, check your connection"
            } finally {
                searching = false
            }
        }
    }

    override fun onCleared() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}

@Composable
fun RadioApp(vm: RadioViewModel = viewModel()) {
    val context = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    val remaining by SleepTimer.remaining.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    fun doSearch() {
        if (query.isNotBlank()) {
            tab = 1
            vm.search(query.trim())
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            vm.current?.let { st ->
                NowPlayingBar(
                    station = st,
                    subtitle = vm.nowPlaying ?: if (vm.buffering) "Connecting…" else "Live radio",
                    playing = vm.wantsPlay,
                    sleepLabel = remaining?.let { "%d:%02d".format(it / 60, it % 60) },
                    onToggle = { vm.togglePlay() },
                    onSleep = { showSleep = true },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Search stations") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                    trailingIcon = {
                        IconButton(onClick = { doSearch() }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    },
                )
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add stream URL")
                }
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Favorites") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Search") })
            }

            if (tab == 0) {
                StationList(
                    stations = vm.favorites,
                    currentUrl = vm.current?.url,
                    isFavorite = { vm.isFavorite(it) },
                    onPlay = { vm.play(it) },
                    onFavorite = { vm.toggleFavorite(it) },
                    emptyText = "No favorites yet.\nSearch for a station or tap + to add a stream URL.",
                )
            } else {
                if (vm.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                StationList(
                    stations = vm.results,
                    currentUrl = vm.current?.url,
                    isFavorite = { vm.isFavorite(it) },
                    onPlay = { vm.play(it) },
                    onFavorite = { vm.toggleFavorite(it) },
                    emptyText = "Type a name above and search.",
                )
            }
        }
    }

    if (showAdd) {
        AddUrlDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, url ->
                showAdd = false
                tab = 0
                vm.addUrl(name, url)
            },
        )
    }
    if (showSleep) {
        SleepDialog(
            running = remaining != null,
            onPick = { SleepTimer.start(it); showSleep = false },
            onCancelTimer = { SleepTimer.cancel(); showSleep = false },
            onDismiss = { showSleep = false },
        )
    }
}

@Composable
fun StationList(
    stations: List<Station>,
    currentUrl: String?,
    isFavorite: (Station) -> Boolean,
    onPlay: (Station) -> Unit,
    onFavorite: (Station) -> Unit,
    emptyText: String,
) {
    if (stations.isEmpty()) {
        Text(
            emptyText,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(stations) { st ->
            val selected = st.url == currentUrl
            ListItem(
                modifier = Modifier.clickable { onPlay(st) },
                colors = ListItemDefaults.colors(
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surface
                ),
                headlineContent = { Text(st.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = st.info?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                trailingContent = {
                    IconButton(onClick = { onFavorite(st) }) {
                        val fav = isFavorite(st)
                        Icon(
                            if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (fav) "Remove favorite" else "Add favorite",
                        )
                    }
                },
            )
        }
    }
}

@Composable
fun NowPlayingBar(
    station: Station,
    subtitle: String,
    playing: Boolean,
    sleepLabel: String?,
    onToggle: () -> Unit,
    onSleep: () -> Unit,
) {
    Surface(tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.navigationBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(station.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onSleep) { Text(sleepLabel ?: "Sleep") }
            Button(onClick = onToggle) { Text(if (playing) "Pause" else "Play") }
        }
    }
}

@Composable
fun AddUrlDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add stream URL") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Stream URL (http…)") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name.trim(), url.trim()) },
                enabled = url.trim().startsWith("http"),
            ) { Text("Add & play") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SleepDialog(
    running: Boolean,
    onPick: (Int) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                listOf(15, 30, 45, 60, 90).forEach { m ->
                    TextButton(onClick = { onPick(m) }) { Text("$m minutes") }
                }
            }
        },
        confirmButton = {
            if (running) TextButton(onClick = onCancelTimer) { Text("Turn off timer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
