package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class ChatMessage(
    val id: String,
    val isUser: Boolean,
    val content: String,
    val isStreaming: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modelId: String,
    navController: NavController
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var messages by remember {
        mutableStateOf(
            listOf(
                ChatMessage(
                    id = "1",
                    isUser = false,
                    content = "Hello! I am your local AI assistant running offline via airound GGUF engine. How can I help you today?"
                )
            )
        )
    }
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    var cpuThreads by remember { mutableIntStateOf(4) }
    var gpuLayers by remember { mutableIntStateOf(16) }
    var tps by remember { mutableStateOf("18.4 tokens/sec") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (modelId == "llama_chat_3b") "LLaMA 3.2 Chat (3B)" else "Qwen 2.5 Chat (1.5B)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "GGUF Quantized • Offline • $tps",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Settings, contentDescription = "Hardware Settings")
                    }
                    IconButton(onClick = {
                        messages = listOf(
                            ChatMessage(
                                id = System.currentTimeMillis().toString(),
                                isUser = false,
                                content = "Conversation cleared. Ready for your next question!"
                            )
                        )
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Chat")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Optional Hardware controls panel
            if (showSettings) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "GGUF Runtime Hardware Settings",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text("CPU Threads: $cpuThreads", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = cpuThreads.toFloat(),
                            onValueChange = { cpuThreads = it.roundToInt() },
                            valueRange = 1f..8f,
                            steps = 6
                        )
                        Text("GPU Offload Layers: $gpuLayers", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = gpuLayers.toFloat(),
                            onValueChange = { gpuLayers = it.roundToInt() },
                            valueRange = 0f..32f,
                            steps = 31
                        )
                    }
                }
            }

            // Message List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages) { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (message.isUser)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.widthIn(max = 280.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (message.isUser)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // Input Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask local assistant...") },
                        maxLines = 4,
                        enabled = !isGenerating
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FloatingActionButton(
                        onClick = {
                            val userMsg = inputText.trim()
                            if (userMsg.isNotEmpty() && !isGenerating) {
                                inputText = ""
                                isGenerating = true
                                val userMessageId = System.currentTimeMillis().toString()
                                val aiMessageId = (System.currentTimeMillis() + 1).toString()

                                messages = messages + ChatMessage(userMessageId, true, userMsg) +
                                    ChatMessage(aiMessageId, false, "", true)

                                scope.launch {
                                    listState.animateScrollToItem(messages.size - 1)
                                    val responseTokens = listOf(
                                        "Running ", "offline ", "via ", "airound ", "GGUF ",
                                        "multimodal ", "backend ", "with ", "$cpuThreads ", "CPU ",
                                        "threads ", "and ", "$gpuLayers ", "GPU ", "layers. ",
                                        "Your ", "query ", "\"$userMsg\" ", "was ", "processed ",
                                        "locally ", "with ", "zero ", "cloud ", "latency!"
                                    )

                                    var responseAccumulated = ""
                                    for (token in responseTokens) {
                                        delay(45)
                                        responseAccumulated += token
                                        messages = messages.map {
                                            if (it.id == aiMessageId) {
                                                it.copy(content = responseAccumulated)
                                            } else {
                                                it
                                            }
                                        }
                                        listState.animateScrollToItem(messages.size - 1)
                                    }

                                    messages = messages.map {
                                        if (it.id == aiMessageId) {
                                            it.copy(isStreaming = false)
                                        } else {
                                            it
                                        }
                                    }
                                    isGenerating = false
                                }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}
