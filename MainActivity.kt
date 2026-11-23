package com.brayan.videosplitter

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

private fun fmt(us: Long): String {
    val s = us / 1_000_000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%dh %02dmin %02ds".format(h, m, sec) else "%dmin %02ds".format(m, sec)
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var uri by remember { mutableStateOf<Uri?>(null) }
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var byDuration by remember { mutableStateOf(true) }
    var valueText by remember { mutableStateOf("90") }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }

    view.keepScreenOn = running

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) {
            uri = u
            info = runCatching { VideoSplitter.readInfo(ctx, u) }.getOrNull()
            status = ""
            progress = 0f
        }
    }

    val n = valueText.toLongOrNull() ?: 0L
    val duration = info?.durationUs ?: 0L
    val segmentUs: Long = when {
        n <= 0 || duration <= 0 -> 0L
        byDuration -> n * 1_000_000L
        else -> duration / n
    }
    val estParts = if (segmentUs > 0) ceil(duration.toDouble() / segmentUs).toInt() else 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Découpeur Vidéo", style = MaterialTheme.typography.headlineMedium)

        Button(
            onClick = { picker.launch("video/*") },
            enabled = !running,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (uri == null) "Choisir une vidéo" else "Changer de vidéo") }

        info?.let {
            Text(it.name, style = MaterialTheme.typography.titleMedium)
            Text("Durée : ${fmt(it.durationUs)}")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = byDuration,
                onClick = { byDuration = true },
                label = { Text("Par durée (secondes)") },
                enabled = !running
            )
            FilterChip(
                selected = !byDuration,
                onClick = { byDuration = false },
                label = { Text("Par nombre de parties") },
                enabled = !running
            )
        }

        OutlinedTextField(
            value = valueText,
            onValueChange = { valueText = it.filter { c -> c.isDigit() }.take(6) },
            label = { Text(if (byDuration) "Secondes par partie" else "Nombre de parties") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = !running,
            modifier = Modifier.fillMaxWidth()
        )

        if (byDuration) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 30, 60, 90).forEach { s ->
                    FilterChip(
                        selected = valueText == s.toString(),
                        onClick = { valueText = s.toString() },
                        label = { Text("$s s") },
                        enabled = !running
                    )
                }
            }
        }

        if (estParts > 0) {
            Text("≈ $estParts parties de ~${segmentUs / 1_000_000} s chacune")
        }

        Button(
            onClick = {
                val u = uri ?: return@Button
                val name = info?.name ?: "video"
                job = scope.launch {
                    running = true
                    progress = 0f
                    status = "Analyse de la vidéo…"
                    try {
                        val count = withContext(Dispatchers.IO) {
                            val self = coroutineContext[Job]
                            VideoSplitter.split(
                                ctx, u, segmentUs, name,
                                onProgress = { d, t ->
                                    progress = d.toFloat() / t
                                    status = "Partie $d / $t"
                                },
                                isCancelled = { self?.isActive == false }
                            )
                        }
                        status = "Terminé : $count partie(s) dans Films/DecoupeurVideo"
                    } catch (e: CancellationException) {
                        status = "Annulé"
                    } catch (e: Exception) {
                        status = "Erreur : ${e.message}"
                    } finally {
                        running = false
                    }
                }
            },
            enabled = !running && uri != null && segmentUs > 0,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Découper") }

        if (running) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) {
                Text("Annuler")
            }
        }

        if (status.isNotEmpty()) Text(status)
    }
}
