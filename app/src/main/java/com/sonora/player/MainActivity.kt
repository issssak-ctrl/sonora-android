package com.sonora.player

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val BG = Color(0xFF050506)
private val SurfaceDark = Color(0xFF1A1A1D)
private val Accent = Color(0xFF90A8E8)
private val White = Color(0xFFF4F1FA)
private data class Track(val uri: Uri, val title: String, val subtitle: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = BG, surface = SurfaceDark, onSurface = White, onBackground = White)) {
                SonoraApp()
            }
        }
    }
}

@Composable
private fun SonoraApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("sonora_v2", Context.MODE_PRIVATE) }
    val imageLoader = remember {
        ImageLoader.Builder(context).components {
            if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }.build()
    }
    var gifUri by remember { mutableStateOf(prefs.getString("gif", null)?.let(Uri::parse)) }
    var folderUri by remember { mutableStateOf(prefs.getString("folder", null)?.let(Uri::parse)) }
    var gifMode by remember { mutableStateOf(prefs.getString("gif_mode", "cover") ?: "cover") }
    var dim by remember { mutableFloatStateOf(prefs.getFloat("dim", 0.35f)) }
    var screen by remember { mutableStateOf("library") }
    var category by remember { mutableStateOf("Все треки") }
    var searchFilter by remember { mutableStateOf("Все") }
    var libraryTab by remember { mutableStateOf("Песни") }
    var query by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf(emptyList<Track>()) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var player by remember { mutableStateOf<MediaController?>(null) }
    val future = remember {
        MediaController.Builder(context, SessionToken(context, android.content.ComponentName(context, PlaybackService::class.java))).buildAsync()
    }
    DisposableEffect(future) {
        future.addListener(Runnable {
            runCatching {
                player = future.get()
                player?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        selectedIndex = player?.currentMediaItemIndex?.coerceAtLeast(0) ?: 0
                    }
                })
            }
        }, context.mainExecutor)
        onDispose { MediaController.releaseFuture(future) }
    }
    LaunchedEffect(player) {
        while (isActive) {
            player?.let {
                playing = it.isPlaying
                position = it.currentPosition.coerceAtLeast(0)
                duration = it.duration.takeIf { d -> d > 0 } ?: 0
                selectedIndex = it.currentMediaItemIndex.coerceAtLeast(0)
            }
            delay(500)
        }
    }
    fun loadFolder(uri: Uri) {
        folderUri = uri
        prefs.edit().putString("folder", uri.toString()).apply()
        scope.launch {
            val found = withContext(Dispatchers.IO) { scanAudioFiles(context, uri) }
            tracks = found
            player?.setMediaItems(found.map {
                MediaItem.Builder().setUri(it.uri).setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).setArtist(it.subtitle).build()).build()
            })
            player?.prepare()
            selectedIndex = 0
        }
    }
    LaunchedEffect(player, folderUri) {
        val uri = folderUri
        if (uri != null && player != null && tracks.isEmpty()) loadFolder(uri)
    }
    val gifPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            gifUri = uri
            prefs.edit().putString("gif", uri.toString()).apply()
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            loadFolder(uri)
        }
    }
    val current = tracks.getOrNull(selectedIndex)
    Box(Modifier.fillMaxSize().background(BG)) {
        if (screen == "player" && gifMode == "fullscreen" && gifUri != null) {
            AsyncImage(model = gifUri, imageLoader = imageLoader, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = dim), Color.Black.copy(alpha = 0.55f), BG.copy(alpha = 0.90f)))))
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (screen != "library") IconButton(onClick = { screen = "library" }) { Icon(Icons.Default.ArrowBack, "Назад", tint = White) }
                Column(Modifier.weight(1f)) {
                    Text("SONORA", fontWeight = FontWeight.Black, fontSize = 24.sp, letterSpacing = 4.sp, color = White)
                    Text(when(screen) { "player" -> "NOW PLAYING"; "settings" -> "НАСТРОЙКИ"; "queue" -> "ОЧЕРЕДЬ"; "search" -> "ПОИСК"; "equalizer" -> "ЗВУК"; else -> "БИБЛИОТЕКА" }, color = Accent, fontSize = 10.sp, letterSpacing = 2.sp)
                }
                IconButton(onClick = { screen = "settings" }) { Icon(Icons.Default.Settings, "Настройки", tint = White) }
            }
            when(screen) {
                "library", "search" -> {
                    if (screen == "library" && category == "Все треки") {
                        Text("Библиотека", Modifier.padding(start = 22.dp, top = 12.dp, bottom = 12.dp), color = White, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                            val sections = listOf("Все треки", "Папки", "Альбомы", "Исполнители", "Жанры", "Очередь")
                            itemsIndexed(sections) { _, item ->
                                Row(Modifier.fillMaxWidth().clickable { if (item == "Очередь") screen = "queue" else category = item }.padding(horizontal = 24.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(when(item) { "Папки" -> Icons.Default.Folder; "Альбомы" -> Icons.Default.Album; "Исполнители" -> Icons.Default.Person; "Жанры" -> Icons.Default.Category; "Очередь" -> Icons.Default.QueueMusic; else -> Icons.Default.LibraryMusic }, null, tint = Accent, modifier = Modifier.size(29.dp))
                                    Spacer(Modifier.width(24.dp)); Text(item, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                                    Spacer(Modifier.weight(1f)); Icon(Icons.Default.ChevronRight, null, tint = White.copy(alpha = .45f))
                                }
                            }
                            item { OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Выбрать папку с музыкой") } }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (screen == "library") IconButton(onClick = { category = "Все треки" }) { Icon(Icons.Default.ArrowBack, "Назад", tint = White) }
                            Text(if (screen == "search") "Поиск" else category, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                        if (screen == "search") {
                            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Название или исполнитель") }, shape = RoundedCornerShape(28.dp))
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Все", "Треки", "Альбомы", "Исполнители").forEach { filter -> FilterChip(selected = searchFilter == filter, onClick = { searchFilter = filter }, label = { Text(filter) }) }
                            }
                        }
                        val filtered = tracks.withIndex().filter { it.value.title.contains(query, true) || it.value.subtitle.contains(query, true) }
                        val groupMode = if (screen == "search") when(searchFilter) { "Альбомы" -> "Альбомы"; "Исполнители" -> "Исполнители"; else -> "Все треки" } else category
                        val groups = when(groupMode) {
                            "Исполнители" -> filtered.groupBy { it.value.subtitle }.map { it.key to it.value.first().index }
                            "Альбомы" -> filtered.groupBy { it.value.subtitle }.map { it.key to it.value.first().index }
                            "Папки" -> filtered.groupBy { it.value.uri.toString().substringBeforeLast('/') }.map { "Папка · ${it.value.size} треков" to it.value.first().index }
                            "Жанры" -> listOf("Все жанры · ${filtered.size} треков" to (filtered.firstOrNull()?.index ?: 0))
                            else -> emptyList()
                        }
                        if (tracks.isEmpty()) {
                            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.LibraryMusic, null, Modifier.size(60.dp), tint = Accent)
                                Spacer(Modifier.height(12.dp)); Text("Музыка ещё не добавлена")
                                Spacer(Modifier.height(12.dp)); Button(onClick = { folderPicker.launch(null) }) { Text("Выбрать папку") }
                            }
                        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 10.dp)) {
                            if (groups.isEmpty()) itemsIndexed(filtered) { _, item -> TrackRow(item.value.title, item.value.subtitle, item.index == selectedIndex) { player?.seekTo(item.index, 0); player?.play(); screen = "player" } }
                            else itemsIndexed(groups) { _, item -> TrackRow(item.first, groupMode, false) { player?.seekTo(item.second, 0); player?.play(); screen = "player" } }
                        }
                    }
                    if (tracks.isNotEmpty()) MiniPlayer(current, playing, onPlay = { if (playing) player?.pause() else player?.play() }, onOpen = { screen = "player" })
                    SonoraNavigation(selected = if (screen == "search") "search" else "library", onNavigate = { target ->
                        when(target) { "library" -> { screen = "library"; category = "Все треки" }; "equalizer" -> screen = "equalizer"; "search" -> screen = "search"; "settings" -> screen = "settings" }
                    })
                }
                "player" -> {
                    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.weight(0.7f))
                        if (gifMode != "fullscreen" || gifUri == null) {
                            Box(Modifier.fillMaxWidth(.82f).aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF4D3279), SurfaceDark, BG))), contentAlignment = Alignment.Center) {
                                if (gifUri != null && gifMode == "cover") AsyncImage(model = gifUri, imageLoader = imageLoader, contentDescription = "GIF обложка", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                else Icon(Icons.Default.GraphicEq, null, Modifier.size(100.dp), tint = Accent)
                            }
                        } else Spacer(Modifier.weight(1f))
                        Spacer(Modifier.weight(0.5f))
                        Text(current?.title ?: "Выбери композицию", fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, color = White)
                        Spacer(Modifier.height(6.dp))
                        Text(current?.subtitle ?: "SONORA", color = White.copy(alpha = .7f), maxLines = 1)
                        Spacer(Modifier.height(20.dp))
                        Slider(value = if (duration > 0) (position.toFloat()/duration).coerceIn(0f,1f) else 0f, onValueChange = { if (duration > 0) player?.seekTo((it*duration).toLong()) }, enabled = duration > 0)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatTime(position), color = White.copy(alpha = .65f), fontSize = 12.sp)
                            Text(formatTime(duration), color = White.copy(alpha = .65f), fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { shuffle = !shuffle; player?.shuffleModeEnabled = shuffle }) { Icon(Icons.Default.Shuffle, "Перемешать", tint = if (shuffle) Accent else White) }
                            IconButton(onClick = { player?.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, "Назад", Modifier.size(36.dp), tint = White) }
                            IconButton(onClick = { if (playing) player?.pause() else player?.play() }, modifier = Modifier.size(72.dp).clip(CircleShape).background(Accent)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Воспроизвести", Modifier.size(40.dp), tint = BG) }
                            IconButton(onClick = { player?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, "Далее", Modifier.size(36.dp), tint = White) }
                            IconButton(onClick = { repeat = when(repeat) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }; player?.repeatMode = repeat }) { Icon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Повтор", tint = if (repeat != Player.REPEAT_MODE_OFF) Accent else White) }
                        }
                        Spacer(Modifier.height(22.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = { screen = "queue" }) { Icon(Icons.Default.QueueMusic, null); Spacer(Modifier.width(8.dp)); Text("Очередь") }
                            TextButton(onClick = { screen = "settings" }) { Icon(Icons.Default.Tune, null); Spacer(Modifier.width(8.dp)); Text("GIF и вид") }
                        }
                        Spacer(Modifier.weight(0.5f))
                    }
                }
                "queue" -> {
                    Text("Очередь воспроизведения", Modifier.padding(20.dp), fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    LazyColumn { itemsIndexed(tracks) { index, track -> TrackRow(track.title, track.subtitle, index == selectedIndex) { player?.seekTo(index, 0); player?.play(); screen = "player" } } }
                }
                "equalizer" -> {
                    Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Эквалайзер", fontSize = 29.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(20.dp))
                        Icon(Icons.Default.Equalizer, null, Modifier.size(80.dp), tint = Accent)
                        Spacer(Modifier.height(20.dp))
                        Text("Звуковой эквалайзер появится в следующем этапе", color = White)
                        Spacer(Modifier.height(8.dp))
                        Text("В этой версии звуковой сигнал не изменяется. Мы не показываем фиктивные ползунки эквалайзера.", color = White.copy(alpha = .65f))
                        Spacer(Modifier.weight(1f))
                    }
                    SonoraNavigation(selected = "equalizer", onNavigate = { screen = it; if (it == "library") category = "Все треки" })
                }
                "settings" -> {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Внешний вид", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("Режим GIF", color = Accent, fontWeight = FontWeight.SemiBold)
                        listOf("off" to "Обычная обложка (без GIF)", "cover" to "GIF вместо обложки", "fullscreen" to "GIF на весь экран").forEach { (value, label) ->
                            Row(Modifier.fillMaxWidth().clickable { gifMode = value; prefs.edit().putString("gif_mode", value).apply() }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = gifMode == value, onClick = { gifMode = value; prefs.edit().putString("gif_mode", value).apply() })
                                Text(label)
                            }
                        }
                        Button(onClick = { gifPicker.launch(arrayOf("image/gif")) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(8.dp)); Text("Выбрать GIF") }
                        OutlinedButton(onClick = { gifUri = null; prefs.edit().remove("gif").apply() }, modifier = Modifier.fillMaxWidth()) { Text("Убрать GIF") }
                        Text("Затемнение фона: ${(dim * 100).toInt()}%")
                        Slider(value = dim, onValueChange = { dim = it; prefs.edit().putFloat("dim", it).apply() }, valueRange = 0f..0.85f)
                        HorizontalDivider()
                        Text("Музыкальная библиотека", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Изменить папку музыки") }
                        Text("Эквалайзер: запланирован для следующего обновления. Сейчас используется стандартная обработка звука Android.", color = White.copy(alpha = .65f), fontSize = 13.sp)
                        Text("SONORA 3.0 · preview", color = Accent)
                    }
                    SonoraNavigation(selected = "settings", onNavigate = { screen = it; if (it == "library") category = "Все треки" })
                }
            }
        }
    }
}

@Composable
private fun SonoraNavigation(selected: String, onNavigate: (String) -> Unit) {
    Surface(color = SurfaceDark, shape = RoundedCornerShape(25.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            listOf(
                Triple("library", Icons.Default.GridView, "Библиотека"),
                Triple("equalizer", Icons.Default.Equalizer, "Эквалайзер"),
                Triple("search", Icons.Default.Search, "Поиск"),
                Triple("settings", Icons.Default.Menu, "Меню")
            ).forEach { (id, icon, label) ->
                IconButton(onClick = { onNavigate(id) }) {
                    Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp), tint = if (selected == id) White else White.copy(alpha = .48f))
                }
            }
        }
    }
}

@Composable
private fun TrackRow(title: String, subtitle: String, active: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceDark), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = Accent) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (active) Accent else White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = White.copy(alpha = .58f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.MoreVert, null, tint = White.copy(alpha = .55f))
    }
}

@Composable
private fun MiniPlayer(track: Track?, playing: Boolean, onPlay: () -> Unit, onOpen: () -> Unit) {
    Surface(color = SurfaceDark, shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.GraphicEq, null, tint = Accent, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track?.title ?: "SONORA", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(track?.subtitle ?: "", maxLines = 1, color = White.copy(alpha = .65f), fontSize = 12.sp)
            }
            IconButton(onClick = onPlay) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Пауза/пуск", tint = Accent) }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

private fun scanAudioFiles(context: android.content.Context, treeUri: Uri): List<Track> {
    val result = mutableListOf<Track>()
    val resolver = context.contentResolver
    val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return emptyList()

    fun visit(documentId: String, depth: Int) {
        if (depth > 12 || result.size > 2000) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idCol)
                    val name = cursor.getString(nameCol) ?: "Unknown track"
                    val mime = cursor.getString(mimeCol) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        visit(id, depth + 1)
                    } else if (
                        mime.startsWith("audio/") ||
                        name.substringAfterLast('.', "").lowercase() in setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma")
                    ) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        val metadata = runCatching {
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(context, uri)
                                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                                (title to artist)
                            } finally { retriever.release() }
                        }.getOrNull()
                        result.add(Track(uri, metadata?.first?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.', name), metadata?.second?.takeIf { it.isNotBlank() } ?: "Неизвестный исполнитель"))
                    }
                }
            }
        }
    }
    visit(rootId, 0)
    return result.sortedBy { it.title.lowercase() }
}
