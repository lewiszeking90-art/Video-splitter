package com.brayan.videosplitter

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.update

private val Primary = Color(0xFF12A150)
private val Dark = Color(0xFF0A5C3A)
private val Mint = Color(0xFFE6F6EC)
private val Bg = Color(0xFFF5FAF6)
private val Ink = Color(0xFF0F2A1E)
private val Muted = Color(0xFF5B7266)
private val ErrorRed = Color(0xFFB3261E)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Primary,
                    onPrimary = Color.White,
                    background = Bg,
                    surface = Color.White,
                    onSurface = Ink
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) { App() }
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%dh %02dmin %02ds".format(h, m, sec) else "%dmin %02ds".format(m, sec)
}

private fun shareFiles(ctx: Context, uris: List<Uri>) {
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    intent.type = "video/mp4"
    val clip = ClipData.newRawUri(null, uris[0])
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    intent.clipData = clip
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, "Partager / enregistrer les parties"))
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val st by SplitState.flow.collectAsState()

    var uri by remember { mutableStateOf<Uri?>(null) }
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var byDuration by remember { mutableStateOf(true) }
    var valueText by remember { mutableStateOf("90") }

    val running = st.running

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            uri = u
            info = runCatching { VideoSplitter.readInfo(ctx, u) }.getOrNull()
            SplitState.flow.value = SplitUi()
        }
    }

    val n = valueText.toLongOrNull() ?: 0L
    val plan = info?.let { VideoSplitter.plan(it.durationMs, byDuration, n) }

    fun startSplit() {
        val u = uri ?: return
        val p = plan ?: return
        val inf = info ?: return
        SplitState.flow.value = SplitUi(running = true, total = p.parts)
        val intent = Intent(ctx, SplitService::class.java)
            .putExtra(SplitService.EXTRA_URI, u.toString())
            .putExtra(SplitService.EXTRA_SEG_MS, p.segMs)
            .putExtra(SplitService.EXTRA_PARTS, p.parts)
            .putExtra(SplitService.EXTRA_DURATION_MS, inf.durationMs)
            .putExtra(SplitService.EXTRA_NAME, inf.name)
        ContextCompat.startForegroundService(ctx, intent)
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        startSplit()
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {

        // ---------- En-tête ----------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
                .background(Brush.linearGradient(listOf(Dark, Primary)))
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Primary, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.size(14.dp))
                Column {
                    Text("Découpeur Vidéo", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Des parties exactes, en un geste", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                }
            }
        }

        // ---------- Contenu ----------
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // Carte vidéo
            CardBox {
                Text("1 · Ta vidéo", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
                if (info == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Mint)
                            .clickable(enabled = !running) { picker.launch(arrayOf("video/*")) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Appuie pour choisir une vidéo", color = Dark, fontWeight = FontWeight.Medium)
                    }
                } else {
                    Text(info!!.name, color = Ink, fontWeight = FontWeight.Medium, maxLines = 2)
                    Spacer(Modifier.height(2.dp))
                    Text("Durée : ${fmt(info!!.durationMs)}", color = Muted, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !running) {
                        Text("Changer de vidéo", color = Primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Carte réglages
            CardBox {
                Text("2 · Comment découper ?", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Mint).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Segment("Par durée", byDuration, !running, Modifier.weight(1f)) {
                        byDuration = true; valueText = "90"
                    }
                    Segment("Par nombre", !byDuration, !running, Modifier.weight(1f)) {
                        byDuration = false; valueText = "3"
                    }
                }

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text(if (byDuration) "Secondes par partie" else "Nombre de parties") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !running,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        focusedLabelColor = Primary,
                        cursorColor = Primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val quick = if (byDuration) listOf(10, 30, 60, 90) else listOf(2, 3, 5, 10)
                    quick.forEach { q ->
                        FilterChip(
                            selected = valueText == q.toString(),
                            onClick = { valueText = q.toString() },
                            label = { Text(if (byDuration) "$q s" else "$q") },
                            enabled = !running,
                            shape = RoundedCornerShape(12.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Primary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                if (plan != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${plan.parts} partie(s) de ${plan.segMs / 1000} s",
                        color = Dark, fontWeight = FontWeight.SemiBold
                    )
                    Text("La dernière partie peut être plus courte.", color = Muted, fontSize = 13.sp)
                }
            }

            // Bouton découper
            Button(
                onClick = {
                    val needsPerm = Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    if (needsPerm) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else startSplit()
                },
                enabled = !running && uri != null && plan != null,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Primary,
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFC3D8CB),
                    disabledContentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth().height(58.dp)
            ) { Text("Découper la vidéo", fontSize = 17.sp, fontWeight = FontWeight.Bold) }

            // Progression
            if (running) {
                val frac = if (st.total > 0) (st.done + st.pct / 100f) / st.total else 0f
                CardBox {
                    Text("Découpe en cours…", fontWeight = FontWeight.SemiBold, color = Ink)
                    Spacer(Modifier.height(10.dp))
                    if (frac <= 0f) {
                        LinearProgressIndicator(
                            color = Primary, trackColor = Mint,
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { frac.coerceIn(0f, 1f) },
                            color = Primary, trackColor = Mint,
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Partie ${minOf(st.done + 1, st.total)} / ${st.total} · ${st.pct} %",
                        color = Muted, fontSize = 14.sp
                    )
                    Text(
                        "Tu peux quitter l'application : la découpe continue en arrière-plan.",
                        color = Muted, fontSize = 13.sp
                    )
                    TextButton(onClick = {
                        ctx.startService(
                            Intent(ctx, SplitService::class.java).setAction(SplitService.ACTION_CANCEL)
                        )
                    }) {
                        Text("Annuler", color = ErrorRed, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            st.error?.let {
                Text(it, color = ErrorRed, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    // ---------- Fenêtre « terminé » ----------
    if (st.finished) {
        AlertDialog(
            onDismissRequest = { SplitState.flow.update { it.copy(finished = false) } },
            containerColor = Color.White,
            shape = RoundedCornerShape(28.dp),
            icon = {
                Icon(Icons.Filled.CheckCircle, null, tint = Primary, modifier = Modifier.size(52.dp))
            },
            title = { Text("Découpe terminée !", fontWeight = FontWeight.Bold, color = Ink) },
            text = {
                Text(
                    "${st.results.size} partie(s) enregistrée(s) dans Films/DecoupeurVideo.\n\n" +
                        "Appuie sur Partager pour les envoyer ou les enregistrer où tu veux.",
                    color = Muted
                )
            },
            confirmButton = {
                Button(
                    onClick = { shareFiles(ctx, st.results) },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White)
                ) {
                    Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Partager", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { SplitState.flow.update { it.copy(finished = false) } }) {
                    Text("Fermer", color = Muted, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

@Composable
private fun CardBox(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color.White,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, Color(0xFFE3EFE7)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) { content() }
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Dark else Muted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
