package io.github.xororz.localdream.ui.screens

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.xororz.localdream.navigation.Screen
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePortalScreen(navController: NavController) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Global performance state
    var globalThreads by remember { mutableIntStateOf(4) }
    var globalGpuLayers by remember { mutableIntStateOf(16) }
    var activeCategory by remember { mutableStateOf("All") }

    // Read total RAM dynamically
    val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    actManager.getMemoryInfo(memInfo)
    val totalGb = memInfo.totalMem.toDouble() / (1024 * 1024 * 1024)

    // Calculate smart hardware recommendation
    val recommendedThreads = 4
    val recommendedLayers = if (totalGb <= 4.5) 0 else if (totalGb <= 6.5) 16 else 32
    val recommendationMessage = if (totalGb <= 4.5) {
        "Device RAM: %.1f GB (Low Memory). Running entirely on CPU with 4 threads is recommended to prevent system-level Out-of-Memory crashes.".format(totalGb)
    } else {
        "Device RAM: %.1f GB (High Memory). We recommend CPU Threads = 4, GPU Offload = %d layers for optimal hybrid performance.".format(totalGb, recommendedLayers)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "airound Local AI Hub",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.History.route) }) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 💡 Smart Hardware Auto-Recommendation Box (Immediately Visible!)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Recommendation",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "Smart Hardware Recommendation",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            recommendationMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // ⚙️ Thread Count & GPU Layers configuration (Accessible right at launch!)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Global Performance Controls",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    // Threads Slider
                    Column {
                        Text("CPU Threads: $globalThreads", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = globalThreads.toFloat(),
                            onValueChange = { globalThreads = it.roundToInt() },
                            valueRange = 1f..8f,
                            steps = 6,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // GPU Offload Slider
                    Column {
                        Text("GPU Offload Layers: $globalGpuLayers", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = globalGpuLayers.toFloat(),
                            onValueChange = { globalGpuLayers = it.roundToInt() },
                            valueRange = 0f..32f,
                            steps = 31,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 🧭 Modality Sections List
            Text(
                "Select AI Modality Workspace",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            PortalCategoryCard(
                title = "🎨 Image Generation Studio",
                description = "Generate outstanding artwork locally using Stable Diffusion, SDXL, and Flux GGUF. Supports custom Unfiltered / Uncensored models & LoRAs.",
                icon = Icons.Default.Brush,
                onClick = { navController.navigate(Screen.ModelList.route) }
            )

            PortalCategoryCard(
                title = "💬 General Intelligent Chat",
                description = "Have private, lightning-fast discussions with local LLM general chat assistants. Recommends: Qwen2.5-1.5B (unfiltered) or Llama-3.2-1B.",
                icon = Icons.Default.Chat,
                onClick = { navController.navigate(Screen.Chat.createRoute("qwen_chat_1b")) }
            )

            PortalCategoryCard(
                title = "💻 Code Engineering Workspace",
                description = "Get private, offline software development help and auto-completions. Recommends: Qwen2.5-Coder-1.5B.",
                icon = Icons.Default.Code,
                onClick = { navController.navigate(Screen.Chat.createRoute("llama_chat_3b")) }
            )

            PortalCategoryCard(
                title = "🎙️ Speech-to-Text Studio",
                description = "Transcribe voice recordings into text offline instantly. Recommends: whisper-tiny-q4_0.",
                icon = Icons.Default.Mic,
                onClick = { navController.navigate(Screen.Voice.route) }
            )

            PortalCategoryCard(
                title = "🎵 Music & Sound Generator",
                description = "Craft original soundtrack melodies and voice synthesis locally.",
                icon = Icons.Default.MusicNote,
                onClick = { navController.navigate(Screen.Voice.route) }
            )
        }
    }
}

@Composable
fun PortalCategoryCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Open",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
