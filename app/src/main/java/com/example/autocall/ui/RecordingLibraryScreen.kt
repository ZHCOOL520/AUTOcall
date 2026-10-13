package com.example.autocall.ui

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.autocall.AutoCallViewModel
import com.example.autocall.LanguageManager
import com.example.autocall.record.RecordingItem
import com.example.autocall.record.RecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ★ Task 2.9 / 修 B41：录音库页面。
 *
 * 数据来自 [RecordingStore.listAll]（扫描私有目录 + sidecar 元数据）。
 * 支持：播放（`STREAM_MUSIC`，不进入通话）、分享（`ACTION_SEND`，经 FileProvider）、
 * 删除（私有文件 + sidecar + MediaStore 公共副本）、跳转公共库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingLibraryScreen(
    viewModel: AutoCallViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<RecordingItem>>(emptyList()) }
    var playingPath by remember { mutableStateOf<String?>(null) }
    val playerRef = remember { mutableStateOf<MediaPlayer?>(null) }

    fun reload() {
        scope.launch {
            items = withContext(Dispatchers.IO) { RecordingStore.listAll(context) }
        }
    }

    fun stopPlayback() {
        playerRef.value?.runCatching { stop(); release() }
        playerRef.value = null
        playingPath = null
    }

    LaunchedEffect(Unit) { reload() }
    DisposableEffect(Unit) { onDispose { stopPlayback() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(LanguageManager.getString("recording_library.title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = LanguageManager.getString("common.back"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(LanguageManager.getString("recording_library.empty"))
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.fileName }) { item ->
                RecordingRow(
                    item = item,
                    durationText = AutoCallViewModel.formatDuration(item.durationSec),
                    isPlaying = playingPath == item.file.absolutePath,
                    onTogglePlay = {
                        if (playingPath == item.file.absolutePath) {
                            stopPlayback()
                        } else {
                            stopPlayback()
                            val mp = MediaPlayer()
                            runCatching {
                                mp.setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build()
                                )
                                mp.setDataSource(item.file.absolutePath)
                                mp.setOnCompletionListener { stopPlayback() }
                                mp.prepare()
                                mp.start()
                                playerRef.value = mp
                                playingPath = item.file.absolutePath
                            }.onFailure {
                                playerRef.value = null
                                playingPath = null
                                Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onShare = {
                        runCatching {
                            val uri = FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", item.file
                            )
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/mp4"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, null))
                        }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                    },
                    onDelete = {
                        if (playingPath == item.file.absolutePath) stopPlayback()
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { RecordingStore.delete(context, item) }
                            if (ok) {
                                Toast.makeText(context, LanguageManager.getString("recording_library.deleted"), Toast.LENGTH_SHORT).show()
                                reload()
                            }
                        }
                    },
                    onOpenPublic = {
                        val uriStr = item.publicUri
                        if (uriStr.isNullOrEmpty()) {
                            Toast.makeText(context, LanguageManager.getString("recording_library.no_public"), Toast.LENGTH_SHORT).show()
                        } else {
                            runCatching {
                                val view = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.parse(uriStr), "audio/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(view)
                            }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun RecordingRow(
    item: RecordingItem,
    durationText: String,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onOpenPublic: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.contactName.ifEmpty { item.phoneNumber }.ifEmpty { item.fileName },
                fontWeight = FontWeight.Bold
            )
            Text(
                "${item.startedAt}  ${durationText}  ${item.result}",
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onTogglePlay) {
                    Icon(
                        if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = LanguageManager.getString(
                            if (isPlaying) "recording_library.stop" else "recording_library.play"
                        )
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = LanguageManager.getString("recording_library.share"))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = LanguageManager.getString("recording_library.delete"))
                }
                TextButton(onClick = onOpenPublic) {
                    Text(LanguageManager.getString("recording_library.open_public"))
                }
            }
        }
    }
}
