package com.sonora.player

import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

private val Ink = Color(0xFF090B12)
private val Purple = Color(0xFFC49AFF)
private val Ivory = Color(0xFFF1EEE7)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Purple,
                background = Ink,
                surface = Color(0xFF171A26),
                onBackground = Ivory,
                onSurface = Ivory
            )) {
                SonoraApp()
            }
        }
    }
}

private data class Track(val uri: Uri, val title: String, val subtitle: String)

@Composable
private fun SonoraApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                else add(GifDecoder.Factory())
            }
            .build()
    }

    var gifUri by remember { mutableStateOf<Uri?>(null) }
    var tracks by remember { mutableStateOf(emptyList<Track>()) }
    var selectedFolder by remember { mutableStateOf<Uri?>(null) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var isShuffle by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var dim by remember { mutableFloatStateOf(0.45f) }
    var showLibrary by remember { mutableStateOf(false) }
    var favoriteUris by remember { mutableStateOf(setOf<String>()) }
    var player by remember { mutableStateOf<MediaController?>(null) }

    val controllerFuture: ListenableFuture<MediaController> = remember {
        MediaController.Builder(
            context,
            SessionToken(context, android.content.ComponentName(context, PlaybackService::class.java))
        ).buildAsync()
    }

    DisposableEffect(controllerFuture) {
        val listener = Runnable {
            runCatching {
                player = controllerFuture.get()
                player?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        selectedIndex = player?.currentMediaItemIndex ?: 0
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        duration = player?.duration?.coerceAtLeast(0L) ?: 0L
                    }
                })
            }
        }
        controllerFuture.addListener(listener, context.mainExecutor)
        onDispose {
            MediaController.releaseFuture(controllerFuture)
            player = null
        }
    }

    LaunchedEffect(player) {
        while (isActive) {
            player?.let {
                position = it.currentPosition.coerceAtLeast(0L)
                duration = it.duration.takeIf { d -> d > 0 } ?: 0L
                isPlaying = it.isPlaying
                selectedIndex = it.currentMediaItemIndex.coerceAtLeast(0)
            }
            delay(500)
        }
    }

    val gifPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            gifUri = uri
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            selectedFolder = uri
            scope.launch {
                val found = scanAudioFiles(context, uri)
                tracks = found
                if (found.isNotEmpty()) {
                    val items = found.map { track ->
                        MediaItem.Builder()
                            .setUri(track.uri)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(track.title)
                                    .setArtist(track.subtitle)
                                    .build()
                            ).build()
                    }
                    player?.setMediaItems(items)
                    player?.prepare()
                    selectedIndex = 0
                }
            }
        }
    }

    val current = tracks.getOrNull(selectedIndex)
    Box(Modifier.fillMaxSize().background(Ink)) {
        if (gifUri != null) {
            AsyncImage(
                model = gifUri,
                imageLoader = imageLoader,
                contentDescription = "GIF background",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        listOf(Color(0xFF25214A), Color(0xFF11172B), Ink)
                    )
                )
            )
        }

        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.28f),
                    0.42f to Color.Black.copy(alpha = dim),
                    1f to Color(0xFF05060B).copy(alpha = 0.97f)
                )
            )
        )

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("SONORA", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, color = Ivory)
                    Text("YOUR MUSIC · YOUR ANIMATION", fontSize = 9.sp, letterSpacing = 1.3.sp, color = Purple)
                }
                Row {
                    IconButton(onClick = { showLibrary = !showLibrary }) {
                        Icon(Icons.Default.MusicNote, contentDescription = "Библиотека", tint = Ivory)
                    }
                    IconButton(onClick = { gifPicker.launch(arrayOf("image/gif")) }) {
                        Icon(Icons.Default.Image, contentDescription = "Выбрать GIF", tint = Purple)
                    }
                }
            }

            if (showLibrary) {
                Text(
                    text = if (selectedFolder == null) "Музыкальная папка не выбрана" else "Треков найдено: ${tracks.size}",
                    color = Ivory, modifier = Modifier.padding(top = 8.dp), fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.FolderOpen, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Выбрать папку")
                    }
                    OutlinedButton(onClick = { showLibrary = false }) { Text("Закрыть") }
                }
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 220.dp)) {
                    itemsIndexed(tracks) { index, track ->
                        TextButton(
                            onClick = {
                                selectedIndex = index
                                player?.seekTo(index, 0L)
                                player?.play()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(track.title, color = if (index == selectedIndex) Purple else Ivory, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.subtitle, color = Ivory.copy(alpha = 0.65f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Surface(color = Color.Black.copy(alpha = 0.30f), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(
                        current?.title ?: if (tracks.isEmpty()) "Твоя музыка начинается здесь" else "Выбери трек",
                        color = Ivory, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        current?.subtitle ?: if (tracks.isEmpty()) "Выбери папку с музыкой" else "SONORA",
                        color = Ivory.copy(alpha = 0.75f), fontSize = 14.sp
                    )

                    Spacer(Modifier.height(12.dp))
                    Slider(
                        value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { fraction ->
                            if (duration > 0) player?.seekTo((fraction * duration).toLong())
                        },
                        enabled = duration > 0,
                        colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(position), color = Ivory.copy(alpha = 0.75f), fontSize = 11.sp)
                        Text(formatTime(duration), color = Ivory.copy(alpha = 0.75f), fontSize = 11.sp)
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            isShuffle = !isShuffle
                            player?.shuffleModeEnabled = isShuffle
                        }) {
                            Icon(Icons.Default.Shuffle, null, tint = if (isShuffle) Purple else Ivory.copy(alpha = 0.7f))
                        }
                        IconButton(onClick = { player?.seekToPreviousMediaItem() }, enabled = tracks.isNotEmpty()) {
                            Icon(Icons.Default.SkipPrevious, null, tint = Ivory, modifier = Modifier.size(32.dp))
                        }
                        IconButton(
                            onClick = { if (player?.isPlaying == true) player?.pause() else player?.play() },
                            modifier = Modifier.size(68.dp).clip(CircleShape).background(Purple),
                            enabled = tracks.isNotEmpty()
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Воспроизвести/пауза",
                                tint = Color(0xFF171020),
                                modifier = Modifier.size(38.dp)
                            )
                        }
                        IconButton(onClick = { player?.seekToNextMediaItem() }, enabled = tracks.isNotEmpty()) {
                            Icon(Icons.Default.SkipNext, null, tint = Ivory, modifier = Modifier.size(32.dp))
                        }
                        IconButton(onClick = {
                            repeatMode = when (repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                            player?.repeatMode = repeatMode
                        }) {
                            Icon(Icons.Default.Repeat, null, tint = if (repeatMode != Player.REPEAT_MODE_OFF) Purple else Ivory.copy(alpha = 0.7f))
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { gifPicker.launch(arrayOf("image/gif")) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Image, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (gifUri == null) "Выбрать GIF" else "Изменить GIF", maxLines = 1)
                        }
                        OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Папка музыки", maxLines = 1)
                        }
                    }

                    var showDimControl by remember { mutableStateOf(false) }
                    TextButton(onClick = { showDimControl = !showDimControl }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(if (showDimControl) "Скрыть настройки фона" else "Настроить затемнение GIF", color = Purple, fontSize = 12.sp)
                    }
                    if (showDimControl) {
                        Text("Затемнение фона: ${(dim * 100).toInt()}%", color = Ivory, fontSize = 12.sp)
                        Slider(value = dim, onValueChange = { dim = it }, valueRange = 0f..0.85f)
                    }
                }
            }
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
                        result.add(Track(uri, name.substringBeforeLast('.', name), mime.ifBlank { "Audio file" }))
                    }
                }
            }
        }
    }
    visit(rootId, 0)
    return result.sortedBy { it.title.lowercase() }
}
