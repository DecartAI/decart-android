package ai.decart.sample

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import ai.decart.sdk.*
import ai.decart.sdk.queue.*
import ai.decart.sdk.realtime.*
import io.livekit.android.compose.types.TrackReference
import io.livekit.android.room.track.Track
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.values.all { it }) {
                showUI()
            } else {
                Toast.makeText(this, "Camera and mic permissions required", Toast.LENGTH_LONG).show()
                finish()
            }
        }

        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (hasCamera && hasMic) {
            showUI()
        } else {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ))
        }
    }

    private fun showUI() {
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DecartSampleApp()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Top-level app composable with shared API key and tab navigation
    // -------------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun DecartSampleApp() {
        var apiKey by remember { mutableStateOf("your-api-key-here") }
        var selectedTab by remember { mutableIntStateOf(0) }
        val tabTitles = listOf("Realtime", "Video")

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Decart SDK Sample") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // Shared API key
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Tab row
                TabRow(selectedTabIndex = selectedTab) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }

                // Tab content
                when (selectedTab) {
                    0 -> RealtimeTab(apiKey)
                    1 -> QueueTab(apiKey)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Realtime tab — caller-owned local stream + LiveKit media UI
    // -------------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun RealtimeTab(apiKey: String) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

        var prompt by remember { mutableStateOf("") }
        var enhancePrompt by remember { mutableStateOf(true) }
        var selectedModel by remember { mutableStateOf(RealtimeModels.LUCY_2_1) }
        var connectionState by remember { mutableStateOf(ConnectionState.DISCONNECTED) }
        var modelMenuExpanded by remember { mutableStateOf(false) }
        var statusMessage by remember { mutableStateOf("Ready") }

        // Local preview is created lazily, independent of `apiKey` so typing
        // the API key does not churn LiveKit Rooms (each Room owns a native
        // PeerConnectionFactory + EglBase + capturer).
        // It is keyed only on the selected model's capture resolution.
        var localStream by remember { mutableStateOf<RealtimeMediaStream?>(null) }
        LaunchedEffect(selectedModel) {
            localStream?.dispose()
            localStream = null
            try {
                localStream = RealTimeClient.createLocalVideoStream(
                    context = context,
                    model = selectedModel,
                    facing = FacingMode.FRONT,
                    logger = AndroidLogger(LogLevel.DEBUG),
                )
            } catch (e: Exception) {
                Log.e("DecartSample", "Failed to start local preview", e)
                statusMessage = "Camera error: ${e.message}"
            }
        }

        // The realtime client itself is created lazily on Connect — also so
        // that typing the API key does not spin up anything heavy.
        var client by remember { mutableStateOf<RealTimeClient?>(null) }
        var remoteStream by remember { mutableStateOf<RealtimeMediaStream?>(null) }

        LaunchedEffect(client) {
            val c = client ?: return@LaunchedEffect
            c.connectionState.collectLatest { state ->
                connectionState = state
                statusMessage = when (state) {
                    ConnectionState.DISCONNECTED -> "Disconnected"
                    ConnectionState.CONNECTING -> "Connecting..."
                    ConnectionState.CONNECTED -> "Connected"
                    ConnectionState.GENERATING -> "Generating"
                    ConnectionState.RECONNECTING -> "Reconnecting..."
                }
            }
        }
        LaunchedEffect(client) {
            val c = client ?: return@LaunchedEffect
            c.errors.collectLatest { error ->
                statusMessage = "Error: ${error.message}"
            }
        }
        LaunchedEffect(client) {
            val c = client ?: return@LaunchedEffect
            c.remoteStreamUpdates.collectLatest { stream ->
                remoteStream = stream
            }
        }
        LaunchedEffect(client) {
            val c = client ?: return@LaunchedEffect
            c.localStreamUpdates.collectLatest { stream ->
                if (localStream !== stream) {
                    localStream = stream
                    remoteStream = null
                }
            }
        }
        LaunchedEffect(client) {
            val c = client ?: return@LaunchedEffect
            val sessionStartNs = System.nanoTime()
            var firstEncodedReported = false
            c.diagnostics.collect { event ->
                val tMs = "%.0f".format((System.nanoTime() - sessionStartNs) / 1_000_000.0)
                when (event) {
                    is DiagnosticEvent.PhaseTiming -> {
                        val d = event.data
                        val ms = "%.0f".format(d.durationMs)
                        val outcome = if (d.success) "ok" else "fail(${d.error ?: "?"})"
                        Log.i("DecartTiming", "t=${tMs}ms phase=${d.phase.name} ${ms}ms $outcome")
                    }
                    is DiagnosticEvent.FirstFrame -> {
                        val d = event.data
                        val ms = "%.0f".format(d.timeSinceConnectMs)
                        Log.i("DecartTiming", "t=${tMs}ms phase=FIRST_FRAME ${ms}ms ${d.width}x${d.height}")
                    }
                    is DiagnosticEvent.PublishStats -> {
                        val d = event.data
                        if (!firstEncodedReported && d.framesEncoded > 0) {
                            firstEncodedReported = true
                            Log.i(
                                "DecartTiming",
                                "t=${tMs}ms FIRST_FRAME_ENCODED framesEncoded=${d.framesEncoded} ${d.frameWidth}x${d.frameHeight} encoder=${d.encoderImplementation}",
                            )
                        }
                        Log.i(
                            "DecartTiming",
                            "t=${tMs}ms publish frames=${d.framesEncoded}(+${d.deltaFrames}) bytes=${d.bytesSent}(+${d.deltaBytes}) ${d.frameWidth}x${d.frameHeight} limit=${d.qualityLimitationReason ?: "-"}",
                        )
                    }
                    else -> Unit
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                client?.release()
                client = null
                localStream?.dispose()
                localStream = null
            }
        }

        val isConnected = connectionState == ConnectionState.CONNECTED ||
                connectionState == ConnectionState.GENERATING

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 16f)
                    .background(Color.Black, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
            ) {
                TrackItem(
                    trackReference = remoteStream?.toTrackReference(Track.Source.CAMERA),
                    videoTrack = remoteStream?.videoTrack,
                    room = remoteStream?.room ?: localStream?.room,
                    modifier = Modifier.fillMaxSize(),
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .fillMaxWidth(0.2f)
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                ) {
                    TrackItem(
                        trackReference = localStream?.toTrackReference(Track.Source.CAMERA),
                        videoTrack = localStream?.videoTrack,
                        room = localStream?.room,
                        mirror = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // State chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val stateColor = when (connectionState) {
                    ConnectionState.CONNECTED, ConnectionState.GENERATING -> Color(0xFF4CAF50)
                    ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Color(0xFFFFC107)
                    ConnectionState.DISCONNECTED -> Color(0xFFF44336)
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = stateColor.copy(alpha = 0.2f)
                ) {
                    Text(
                        connectionState.name,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = stateColor,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Text(
                    statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Model selector
            ExposedDropdownMenuBox(
                expanded = modelMenuExpanded,
                onExpandedChange = { modelMenuExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedModel.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false }
                ) {
                    RealtimeModels.all.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.name) },
                            onClick = {
                                selectedModel = model
                                modelMenuExpanded = false
                            }
                        )
                    }
                }
            }

            // Prompt + enhance
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                if (isConnected && prompt.isNotBlank()) {
                    Button(onClick = {
                        coroutineScope.launch {
                            try {
                                client?.setPrompt(prompt, enhancePrompt)
                                statusMessage = "Prompt sent"
                            } catch (e: Exception) {
                                statusMessage = "Error: ${e.message}"
                            }
                        }
                    }) {
                        Text("Send")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enhance Prompt")
                Switch(checked = enhancePrompt, onCheckedChange = { enhancePrompt = it })
            }

            // Connect/Disconnect
            Button(
                onClick = {
                    if (isConnected || connectionState == ConnectionState.CONNECTING) {
                        // The Room used by the SDK is the same one owned by
                        // the preview stream. Release the client first so it
                        // tears down its session, then dispose the preview
                        // (also disconnects the Room) and rebuild a fresh
                        // preview that re-runs the LiveKitVideoView factory.
                        val current = client
                        client = null
                        current?.disconnect()
                        current?.release()
                        localStream?.dispose()
                        localStream = null
                        remoteStream = null
                        statusMessage = "Disconnected"
                        connectionState = ConnectionState.DISCONNECTED
                        coroutineScope.launch {
                            try {
                                localStream = RealTimeClient.createLocalVideoStream(
                                    context = context,
                                    model = selectedModel,
                                    facing = FacingMode.FRONT,
                                    logger = AndroidLogger(LogLevel.DEBUG),
                                )
                            } catch (e: Exception) {
                                Log.e("DecartSample", "Failed to restart preview", e)
                                statusMessage = "Camera error: ${e.message}"
                            }
                        }
                    } else {
                        if (apiKey.isBlank()) {
                            statusMessage = "Please enter an API key"
                            return@Button
                        }
                        val preview = localStream
                        if (preview == null) {
                            statusMessage = "Camera not ready"
                            return@Button
                        }
                        statusMessage = "Connecting..."
                        connectionState = ConnectionState.CONNECTING

                        val rtClient = RealTimeClient(
                            context = context,
                            config = RealTimeClientConfig(
                                apiKey = apiKey,
                                baseUrl = "wss://api.decart.ai",
                                logger = AndroidLogger(LogLevel.WARN),
                            ),
                        )
                        client = rtClient

                        coroutineScope.launch {
                            try {
                                val initialPromptObj = if (prompt.isNotBlank()) {
                                    InitialPrompt(text = prompt, enhance = enhancePrompt)
                                } else null

                                rtClient.connect(
                                    options = ConnectOptions(
                                        model = selectedModel,
                                        initialPrompt = initialPromptObj,
                                        facing = FacingMode.FRONT,
                                        publishCamera = true,
                                        publishMicrophone = false,
                                    ),
                                    localStream = preview,
                                )
                                statusMessage = "Connected!"
                            } catch (e: Exception) {
                                statusMessage = "Failed: ${e.message}"
                                connectionState = ConnectionState.DISCONNECTED
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected || connectionState == ConnectionState.CONNECTING)
                        Color(0xFFF44336) else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    if (isConnected || connectionState == ConnectionState.CONNECTING)
                        "Disconnect" else "Connect"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Queue tab — batch video generation playground (model-adaptive UI)
    // -------------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun QueueTab(apiKey: String) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

        var selectedModel by remember { mutableStateOf(VideoModels.LUCY_2_1) }
        var modelMenuExpanded by remember { mutableStateOf(false) }
        var prompt by remember { mutableStateOf("") }
        var seed by remember { mutableStateOf("") }
        var enhancePrompt by remember { mutableStateOf(true) }
        var dataUri by remember { mutableStateOf<Uri?>(null) }
        var referenceImageUri by remember { mutableStateOf<Uri?>(null) }
        // Restyle: prompt vs reference image toggle
        var restyleUsePrompt by remember { mutableStateOf(true) }
        var isSubmitting by remember { mutableStateOf(false) }
        var statusText by remember { mutableStateOf("") }
        var errorText by remember { mutableStateOf("") }
        var resultPath by remember { mutableStateOf("") }
        var uploadProgress by remember { mutableFloatStateOf(0f) }
        var isUploading by remember { mutableStateOf(false) }

        val inputType = selectedModel.inputType

        // Reset media URIs when input type changes
        LaunchedEffect(inputType) {
            dataUri = null
            referenceImageUri = null
        }

        val videoPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri -> dataUri = uri }

        val refImagePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri -> referenceImageUri = uri }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Model selector
            ExposedDropdownMenuBox(
                expanded = modelMenuExpanded,
                onExpandedChange = { modelMenuExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedModel.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false }
                ) {
                    VideoModels.all.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.name) },
                            onClick = {
                                selectedModel = model
                                modelMenuExpanded = false
                            }
                        )
                    }
                }
            }

            // ---------- Data file picker ----------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { videoPicker.launch("video/*") }) {
                    Text("Select Video")
                }
                Text(
                    text = dataUri?.lastPathSegment ?: "(none)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            // ---------- Restyle mode toggle (prompt vs reference image) ----------
            if (inputType == ModelInputType.VIDEO_RESTYLE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = restyleUsePrompt,
                        onClick = { restyleUsePrompt = true },
                        label = { Text("Prompt") }
                    )
                    FilterChip(
                        selected = !restyleUsePrompt,
                        onClick = { restyleUsePrompt = false },
                        label = { Text("Reference Image") }
                    )
                }
            }

            // ---------- Prompt field ----------
            val showPrompt = when (inputType) {
                ModelInputType.VIDEO_EDIT -> true
                ModelInputType.VIDEO_RESTYLE -> restyleUsePrompt
            }
            if (showPrompt) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Prompt") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
            }

            // ---------- Reference image picker (VIDEO_EDIT optional, RESTYLE when using ref image) ----------
            if (inputType == ModelInputType.VIDEO_EDIT) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { refImagePicker.launch("image/*") }) {
                        Text("Reference Image")
                    }
                    Text(
                        text = referenceImageUri?.lastPathSegment ?: "(optional)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (inputType == ModelInputType.VIDEO_RESTYLE && !restyleUsePrompt) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { refImagePicker.launch("image/*") }) {
                        Text("Reference Image")
                    }
                    Text(
                        text = referenceImageUri?.lastPathSegment ?: "(required)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ---------- Seed ----------
            OutlinedTextField(
                value = seed,
                onValueChange = { seed = it },
                label = { Text("Seed (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            // ---------- Enhance prompt toggle ----------
            val showEnhance = when (inputType) {
                ModelInputType.VIDEO_EDIT -> true
                ModelInputType.VIDEO_RESTYLE -> restyleUsePrompt
            }
            if (showEnhance) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enhance Prompt")
                    Switch(checked = enhancePrompt, onCheckedChange = { enhancePrompt = it })
                }
            }

            // ---------- Submit button ----------
            val canSubmit = !isSubmitting && apiKey.isNotBlank() && when (inputType) {
                ModelInputType.VIDEO_EDIT -> dataUri != null
                ModelInputType.VIDEO_RESTYLE -> dataUri != null && (if (restyleUsePrompt) prompt.isNotBlank() else referenceImageUri != null)
            }

            Button(
                onClick = {
                    coroutineScope.launch {
                        isSubmitting = true
                        isUploading = true
                        uploadProgress = 0f
                        statusText = "Uploading..."
                        errorText = ""
                        resultPath = ""

                        try {
                            val decartClient = DecartClient(
                                context = context,
                                config = DecartClientConfig(
                                    apiKey = apiKey,
                                    logLevel = LogLevel.DEBUG,
                                )
                            )

                            try {
                                val jobInput: QueueJobInput = when (inputType) {
                                    ModelInputType.VIDEO_EDIT -> VideoEditInput(
                                        prompt = prompt,
                                        data = FileInput.fromUri(dataUri!!),
                                        referenceImage = referenceImageUri?.let { FileInput.fromUri(it) },
                                        seed = seed.toIntOrNull(),
                                        enhancePrompt = enhancePrompt,
                                    )
                                    ModelInputType.VIDEO_RESTYLE -> if (restyleUsePrompt) {
                                        VideoRestyleInput(
                                            data = FileInput.fromUri(dataUri!!),
                                            prompt = prompt,
                                            seed = seed.toIntOrNull(),
                                            enhancePrompt = enhancePrompt,
                                        )
                                    } else {
                                        VideoRestyleInput(
                                            data = FileInput.fromUri(dataUri!!),
                                            referenceImage = FileInput.fromUri(referenceImageUri!!),
                                            seed = seed.toIntOrNull(),
                                        )
                                    }
                                }

                                decartClient.queue.submitAndObserve(
                                    model = selectedModel,
                                    input = jobInput,
                                    onUploadProgress = { bytesWritten, totalBytes ->
                                        if (totalBytes > 0) {
                                            uploadProgress = bytesWritten.toFloat() / totalBytes
                                        }
                                    },
                                ).collect { update ->
                                    when (update) {
                                        is QueueJobResult.InProgress -> {
                                            isUploading = false
                                            statusText = "Status: ${update.status.name}..."
                                        }
                                        is QueueJobResult.Completed -> {
                                            statusText = "Completed! Saving..."
                                            val file = File(
                                                context.getExternalFilesDir(null),
                                                "decart_output_${System.currentTimeMillis()}.mp4"
                                            )
                                            file.writeBytes(update.data)
                                            resultPath = file.absolutePath
                                            statusText = "Done!"
                                        }
                                        is QueueJobResult.Failed -> {
                                            errorText = "Job failed: ${update.error}"
                                            statusText = ""
                                        }
                                    }
                                }
                            } finally {
                                decartClient.release()
                            }
                        } catch (e: Exception) {
                            if (resultPath.isBlank()) {
                                errorText = "Error: ${e.message ?: "Unknown error"}"
                                statusText = ""
                            }
                        } finally {
                            isSubmitting = false
                            isUploading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSubmit
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Submit Job")
                }
            }

            // Upload progress bar
            if (isUploading && isSubmitting) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Uploading... ${(uploadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { uploadProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Status display
            if (statusText.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        statusText,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // Error display
            if (errorText.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        errorText,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // Result display + video player
            if (resultPath.isNotBlank()) {
                Surface(
                    color = Color(0xFF1B5E20).copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "Saved to: $resultPath",
                        modifier = Modifier.padding(12.dp),
                        color = Color(0xFF4CAF50),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Inline video player
                key(resultPath) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                android.widget.VideoView(ctx).apply {
                                    setVideoPath(resultPath)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        start()
                                    }
                                    setOnErrorListener { _, what, extra ->
                                        errorText = "Playback error: $what/$extra"
                                        true
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Bottom spacing for scroll
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun RealtimeMediaStream.toTrackReference(defaultSource: Track.Source): TrackReference? {
    val room = room ?: return null
    val track = videoTrack

    val participant = if (id == RealtimeMediaStream.LOCAL_STREAM_ID) {
        room.localParticipant
    } else {
        room.remoteParticipants.values.firstOrNull { participant ->
            participant.trackPublications.values.any { publication ->
                publication.track === track || publication.source == defaultSource
            }
        } ?: room.remoteParticipants.values.firstOrNull()
    } ?: return null

    val publication = participant.trackPublications.values.firstOrNull { publication ->
        publication.track === track
    } ?: participant.getTrackPublication(defaultSource)

    return TrackReference(
        participant = participant,
        publication = publication,
        source = publication?.source ?: defaultSource,
    )
}
