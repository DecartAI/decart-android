package ai.decart.sample

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.webrtc.*
import java.io.File

class MainActivity : ComponentActivity() {

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var client: RealTimeClient? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.values.all { it }) {
                initializeWebRTC()
                showUI()
            } else {
                Toast.makeText(this, "Camera and mic permissions required", Toast.LENGTH_LONG).show()
                finish()
            }
        }

        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (hasCamera && hasMic) {
            initializeWebRTC()
            showUI()
        } else {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ))
        }
    }

    private fun initializeWebRTC() {
        eglBase = EglBase.create()
        val eglContext = eglBase!!.eglBaseContext

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()
    }

    private fun startCamera() {
        val factory = peerConnectionFactory ?: return
        val egl = eglBase ?: return

        val enumerator = Camera2Enumerator(this)
        val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: return

        val capturer = enumerator.createCapturer(deviceName, null)
        videoCapturer = capturer

        videoSource = factory.createVideoSource(capturer.isScreencast)
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", egl.eglBaseContext)
        capturer.initialize(surfaceTextureHelper, applicationContext, videoSource!!.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        localVideoTrack = factory.createVideoTrack("local_video", videoSource)
        localVideoTrack?.setEnabled(true)
        localRenderer?.let { localVideoTrack?.addSink(it) }
    }

    private fun stopCamera() {
        localRenderer?.let { localVideoTrack?.removeSink(it) }
        localVideoTrack?.dispose()
        localVideoTrack = null
        try {
            videoCapturer?.stopCapture()
        } catch (_: Exception) {}
        videoCapturer?.dispose()
        videoCapturer = null
        videoSource?.dispose()
        videoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
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
    // Realtime tab — existing WebRTC UI (unchanged logic)
    // -------------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun RealtimeTab(apiKey: String) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

        var prompt by remember { mutableStateOf("") }
        var enhancePrompt by remember { mutableStateOf(true) }
        var selectedModel by remember { mutableStateOf(RealtimeModels.LUCY) }
        var connectionState by remember { mutableStateOf(ConnectionState.DISCONNECTED) }
        var modelMenuExpanded by remember { mutableStateOf(false) }
        var statusMessage by remember { mutableStateOf("Ready") }

        // Collect connection state from client
        val currentClient = client
        if (currentClient != null) {
            LaunchedEffect(currentClient) {
                currentClient.connectionState.collectLatest { state ->
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
            LaunchedEffect(currentClient) {
                currentClient.errors.collectLatest { error ->
                    statusMessage = "Error: ${error.message}"
                }
            }
        }

        val isConnected = connectionState == ConnectionState.CONNECTED ||
                connectionState == ConnectionState.GENERATING

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Video views
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Local camera
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color.DarkGray, RoundedCornerShape(8.dp))
                ) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).also { renderer ->
                                renderer.init(eglBase!!.eglBaseContext, null)
                                renderer.setMirror(true)
                                renderer.setEnableHardwareScaler(true)
                                localRenderer = renderer
                                // If camera already started, add sink
                                localVideoTrack?.addSink(renderer)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    Text(
                        "Local",
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // Remote AI output
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color.DarkGray, RoundedCornerShape(8.dp))
                ) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).also { renderer ->
                                renderer.init(eglBase!!.eglBaseContext, null)
                                renderer.setEnableHardwareScaler(true)
                                remoteRenderer = renderer
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    Text(
                        "AI Output",
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                        style = MaterialTheme.typography.labelSmall
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
                        try {
                            client?.setPrompt(prompt, enhancePrompt)
                            statusMessage = "Prompt sent"
                        } catch (e: Exception) {
                            statusMessage = "Error: ${e.message}"
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
                        // Disconnect
                        client?.disconnect()
                        client?.release()
                        client = null
                        stopCamera()
                        statusMessage = "Disconnected"
                        connectionState = ConnectionState.DISCONNECTED
                    } else {
                        if (apiKey.isBlank()) {
                            statusMessage = "Please enter an API key"
                            return@Button
                        }
                        statusMessage = "Starting camera..."
                        connectionState = ConnectionState.CONNECTING

                        coroutineScope.launch {
                            try {
                                // Start camera
                                startCamera()

                                // Create client
                                val rtClient = RealTimeClient(
                                    context = context,
                                    config = RealTimeClientConfig(
                                        apiKey = apiKey,
                                        baseUrl = "wss://api.decart.ai",
                                        logger = AndroidLogger(LogLevel.DEBUG)
                                    )
                                )
                                rtClient.initialize(eglBase)
                                client = rtClient

                                val initialPromptObj = if (prompt.isNotBlank()) {
                                    InitialPrompt(text = prompt, enhance = enhancePrompt)
                                } else null

                                val remote = remoteRenderer!!
                                rtClient.connect(
                                    localVideoTrack = localVideoTrack,
                                    options = ConnectOptions(
                                        model = selectedModel,
                                        onRemoteVideoTrack = { track ->
                                            track.addSink(remote)
                                        },
                                        initialPrompt = initialPromptObj
                                    )
                                )
                                statusMessage = "Connected!"
                            } catch (e: Exception) {
                                statusMessage = "Failed: ${e.message}"
                                connectionState = ConnectionState.DISCONNECTED
                                client?.release()
                                client = null
                                stopCamera()
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

        var selectedModel by remember { mutableStateOf(VideoModels.LUCY_2) }
        var modelMenuExpanded by remember { mutableStateOf(false) }
        var prompt by remember { mutableStateOf("") }
        var seed by remember { mutableStateOf("") }
        var enhancePrompt by remember { mutableStateOf(true) }
        var dataUri by remember { mutableStateOf<Uri?>(null) }
        var referenceImageUri by remember { mutableStateOf<Uri?>(null) }
        // Restyle: prompt vs reference image toggle
        var restyleUsePrompt by remember { mutableStateOf(true) }
        // Motion: trajectory JSON
        var trajectoryJson by remember { mutableStateOf("") }
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

        val imagePicker = rememberLauncherForActivityResult(
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

            // ---------- Data file picker (video or image depending on model) ----------
            val needsVideoFile = inputType == ModelInputType.VIDEO_EDIT || inputType == ModelInputType.VIDEO_RESTYLE
            val needsImageFile = inputType == ModelInputType.MOTION_VIDEO

            if (needsVideoFile) {
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
            }

            if (needsImageFile) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { imagePicker.launch("image/*") }) {
                        Text("Select Image")
                    }
                    Text(
                        text = dataUri?.lastPathSegment ?: "(none)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
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
                ModelInputType.MOTION_VIDEO -> false
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

            // ---------- Trajectory JSON (MOTION_VIDEO only) ----------
            if (inputType == ModelInputType.MOTION_VIDEO) {
                OutlinedTextField(
                    value = trajectoryJson,
                    onValueChange = { trajectoryJson = it },
                    label = { Text("Trajectory JSON") },
                    placeholder = { Text("""[{"frame":0,"x":0.5,"y":0.5},{"frame":14,"x":0.8,"y":0.3}]""") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5,
                    supportingText = { Text("Array of {frame, x, y} — coordinates 0–1") }
                )
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
                ModelInputType.MOTION_VIDEO -> false
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
                ModelInputType.MOTION_VIDEO -> dataUri != null && trajectoryJson.isNotBlank()
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
                                    ModelInputType.MOTION_VIDEO -> {
                                        val points = mutableListOf<TrajectoryPoint>()
                                        val arr = org.json.JSONArray(trajectoryJson)
                                        for (i in 0 until arr.length()) {
                                            val obj = arr.getJSONObject(i)
                                            points.add(TrajectoryPoint(
                                                frame = obj.getInt("frame"),
                                                x = obj.getDouble("x").toFloat(),
                                                y = obj.getDouble("y").toFloat(),
                                            ))
                                        }
                                        MotionVideoInput(
                                            data = FileInput.fromUri(dataUri!!),
                                            trajectory = points,
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

    override fun onDestroy() {
        super.onDestroy()
        client?.disconnect()
        client?.release()
        client = null
        stopCamera()
        localRenderer?.release()
        remoteRenderer?.release()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase?.release()
        eglBase = null
    }
}
