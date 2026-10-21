package com.example.trashrobot

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.trashrobot.ui.theme.TrashRobotTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.abs

enum class AppMode {
    AUTOMATIC,
    MANUAL
}

class GyroTurnController(
    context: Context,
    private val hub: MouldKingHub6,
    private val isBleSupported: Boolean
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var currentHeading = 0f
    private var startHeading = 0f
    private var isTurning = false
    private var turnDirection = 0 // +1 = right, -1 = left
    private var onStatusUpdate: ((String) -> Unit)? = null

    init {
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun unregister() {
        sensorManager.unregisterListener(this)
    }

    fun forceStop() {
        if (isTurning) {
            isTurning = false
            if (isBleSupported) {
                hub.setChannel(0, 0f)
                hub.setChannel(1, 0f)
            }
            onStatusUpdate?.invoke("Gestoppt (Not-Aus)")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

            // Remap coordinate system for Landscape and Upright mounting (tilted forward):
            // AXIS_Y and AXIS_MINUS_X maps the upright landscape orientation to a horizontal heading plane.
            val remappedMatrix = FloatArray(9)
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_Y,
                SensorManager.AXIS_MINUS_X,
                remappedMatrix
            )

            val orientation = FloatArray(3)
            SensorManager.getOrientation(remappedMatrix, orientation)
            currentHeading = orientation[0] // heading in radians [-PI, PI]

            if (isTurning) {
                val rotatedRadians = angleDiff(startHeading, currentHeading)
                val targetRadians = Math.toRadians(45.0)

                val reached = if (turnDirection > 0) {
                    rotatedRadians >= targetRadians
                } else {
                    rotatedRadians <= -targetRadians
                }

                if (reached) {
                    stopTurning()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun startTurn45(direction: Int, statusCallback: (String) -> Unit) {
        if (isTurning) return
        turnDirection = direction
        startHeading = currentHeading
        isTurning = true
        onStatusUpdate = statusCallback

        val msg = if (direction > 0) "45° RECHTS läuft..." else "45° LINKS läuft..."
        onStatusUpdate?.invoke(msg)

        if (isBleSupported) {
            if (direction > 0) {
                // Drehen Rechts at 40% speed (0.4f)
                hub.setChannel(0, 0.4f)
                hub.setChannel(1, 0.4f)
            } else {
                // Drehen Links at 40% speed (-0.4f)
                hub.setChannel(0, -0.4f)
                hub.setChannel(1, -0.4f)
            }
        }
    }

    private fun stopTurning() {
        if (!isTurning) return
        isTurning = false
        if (isBleSupported) {
            hub.setChannel(0, 0f)
            hub.setChannel(1, 0f)
        }
        val msg = if (turnDirection > 0) "45° RECHTS beendet" else "45° LINKS beendet"
        onStatusUpdate?.invoke(msg)
    }

    private fun angleDiff(from: Float, to: Float): Float {
        var diff = to - from
        while (diff < -Math.PI) diff += (2 * Math.PI).toFloat()
        while (diff > Math.PI) diff -= (2 * Math.PI).toFloat()
        return diff
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var advertiser: MouldKingAdvertiser
    private lateinit var hub: MouldKingHub6

    var isBleSupported by mutableStateOf(true)
        private set

    var bleErrorMessage by mutableStateOf<String?>(null)
        private set

    var hasCameraPermission by mutableStateOf(false)
        private set

    fun requestPermissions() {
        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        requestPermissionLauncher.launch(permissionsToRequest)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA]
            ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        hasCameraPermission = cameraGranted
        Log.i("MainActivity", "Camera permission result: $cameraGranted")

        val advertiseGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_ADVERTISE]
                ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED)
        } else true

        val connectGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_CONNECT]
                ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        } else true

        Log.i("MainActivity", "Bluetooth permissions result: advertise=$advertiseGranted, connect=$connectGranted")

        if (advertiseGranted && connectGranted) {
            checkBluetoothState()
        } else {
            isBleSupported = false
            bleErrorMessage = "Bluetooth-Berechtigungen wurden verweigert."
            Log.e("MainActivity", bleErrorMessage!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        cameraExecutor = Executors.newSingleThreadExecutor()
        advertiser = MouldKingAdvertiser(applicationContext)
        hub = MouldKingHub6(deviceId = 0, advertiser = advertiser)

        updatePermissionsState()

        setContent {
            TrashRobotTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainAppScreen(
                        cameraExecutor = cameraExecutor,
                        hub = hub,
                        isBleSupported = isBleSupported,
                        bleErrorMessage = bleErrorMessage,
                        hasCameraPermission = hasCameraPermission,
                        onRequestPermissions = { requestPermissions() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun updatePermissionsState() {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missing = permissionsToRequest.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing) {
            requestPermissions()
        } else {
            checkBluetoothState()
        }
    }

    private fun checkBluetoothState() {
        val bluetoothManager = ContextCompat.getSystemService(this, BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter

        if (adapter == null) {
            isBleSupported = false
            bleErrorMessage = "Kein Bluetooth-Adapter auf diesem Gerät gefunden."
            Log.e("MainActivity", bleErrorMessage!!)
            return
        }

        if (!adapter.isEnabled) {
            isBleSupported = false
            bleErrorMessage = "Bluetooth ist ausgeschaltet. Bitte Bluetooth in den Einstellungen aktivieren."
            Log.e("MainActivity", bleErrorMessage!!)
            return
        }

        val leAdvertiser = adapter.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            isBleSupported = false
            bleErrorMessage = "BLE Advertising wird vom System nicht unterstützt."
            Log.e("MainActivity", bleErrorMessage!!)
            return
        }

        isBleSupported = true
        bleErrorMessage = null
        Log.i("MainActivity", "Bluetooth und BLE Advertising bereit.")
    }

    override fun onDestroy() {
        if (::hub.isInitialized && isBleSupported) {
            hub.disconnect()
        }
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}

@Composable
fun MainAppScreen(
    cameraExecutor: ExecutorService,
    hub: MouldKingHub6,
    isBleSupported: Boolean,
    bleErrorMessage: String?,
    hasCameraPermission: Boolean,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentMode by remember { mutableStateOf(AppMode.AUTOMATIC) }
    var emergencyStopHandler by remember { mutableStateOf<(() -> Unit)?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Compact Top Bar: Mode Switcher + ALLE MOTOREN STOPP button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = { currentMode = AppMode.AUTOMATIC },
                    colors = if (currentMode == AppMode.AUTOMATIC) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    } else {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    },
                    modifier = Modifier.weight(1f).height(38.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("Automatischer Modus", style = MaterialTheme.typography.bodyMedium)
                }

                Button(
                    onClick = { currentMode = AppMode.MANUAL },
                    colors = if (currentMode == AppMode.MANUAL) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    } else {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    },
                    modifier = Modifier.weight(1f).height(38.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("Manueller Modus", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Button(
                onClick = {
                    if (isBleSupported) {
                        for (i in 0 until 4) {
                            hub.setChannel(i, 0f)
                        }
                    }
                    emergencyStopHandler?.invoke()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(0.8f).height(38.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text("🚨 ALLE MOTOREN STOPP", color = MaterialTheme.colorScheme.onError, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (!isBleSupported) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "⚠️ ${bleErrorMessage ?: "BLE wird nicht unterstützt"}",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = onRequestPermissions) {
                        Text("Berechtigungen erneut anfordern")
                    }
                }
            }
        }

        // Content based on selected mode
        when (currentMode) {
            AppMode.AUTOMATIC -> {
                AutomaticModeScreen(
                    cameraExecutor = cameraExecutor,
                    hub = hub,
                    isBleSupported = isBleSupported,
                    hasCameraPermission = hasCameraPermission,
                    onRequestPermissions = onRequestPermissions,
                    onRegisterEmergencyStop = { handler ->
                        emergencyStopHandler = handler
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
            AppMode.MANUAL -> {
                ManualModeScreen(
                    cameraExecutor = cameraExecutor,
                    hub = hub,
                    isBleSupported = isBleSupported,
                    hasCameraPermission = hasCameraPermission,
                    onRequestPermissions = onRequestPermissions,
                    onRegisterEmergencyStop = { handler ->
                        emergencyStopHandler = handler
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
fun ManualModeScreen(
    cameraExecutor: ExecutorService,
    hub: MouldKingHub6,
    isBleSupported: Boolean,
    hasCameraPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onRegisterEmergencyStop: (() -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val imageCapture = remember { ImageCapture.Builder().build() }
    
    val gyroTurnController = remember { GyroTurnController(context, hub, isBleSupported) }
    DisposableEffect(gyroTurnController) {
        onRegisterEmergencyStop {
            gyroTurnController.forceStop()
        }
        onDispose {
            onRegisterEmergencyStop {}
            gyroTurnController.unregister()
        }
    }
    
    var capturedImage by remember { mutableStateOf<Bitmap?>(null) }
    var detectedObject by remember { mutableStateOf<Detection?>(null) }
    var objectDetector by remember { mutableStateOf<ObjectDetector?>(null) }
    var isLoadingModel by remember { mutableStateOf(true) }
    var isConnected by remember { mutableStateOf(false) }
    var actionStatus by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        isLoadingModel = true
        objectDetector = ObjectDetector(context, modelAssetName = "best.onnx")
        isLoadingModel = false
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // If not connected yet, show ONLY the connect button
        if (!isConnected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = {
                        if (isBleSupported) {
                            hub.connect()
                            isConnected = true
                        }
                    },
                    enabled = isBleSupported,
                    modifier = Modifier.fillMaxWidth().height(60.dp)
                ) {
                    Text("Mouldking Hub verbinden", style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            // Once connected, landscape 2-column layout: Left = Camera, Right = Square + Dpad
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // LEFT SIDE: Camera & Bild aufnehmen
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isLoadingModel || objectDetector == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else if (hasCameraPermission) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            if (capturedImage == null) {
                                AndroidView(
                                    factory = { ctx ->
                                        val previewView = PreviewView(ctx)
                                        val cameraProviderFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(ctx)
                                        cameraProviderFuture.addListener({
                                            val cameraProvider = cameraProviderFuture.get()
                                            val preview = androidx.camera.core.Preview.Builder().build().also {
                                                it.setSurfaceProvider(previewView.surfaceProvider)
                                            }
                                            try {
                                                cameraProvider.unbindAll()
                                                cameraProvider.bindToLifecycle(
                                                    lifecycleOwner,
                                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                                    preview,
                                                    imageCapture
                                                )
                                            } catch (e: Exception) {
                                                Log.e("CameraSection", "Use case binding failed", e)
                                            }
                                        }, ContextCompat.getMainExecutor(ctx))
                                        previewView
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                                Button(
                                    onClick = {
                                        takePhoto(imageCapture, cameraExecutor) { bitmap ->
                                            val detection = objectDetector?.detect(bitmap)
                                            capturedImage = drawDetection(bitmap, detection)
                                            detectedObject = detection
                                        }
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(8.dp)
                                ) {
                                    Text("Bild aufnehmen")
                                }
                            } else {
                                capturedImage?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = "Captured Image",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    detectedObject?.let {
                                        Text(
                                            text = "Erkannt: ${it.label} (${(it.confidence * 100).toInt()}%)",
                                            color = androidx.compose.ui.graphics.Color.Yellow,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    } ?: Text(
                                        text = "Kein Müll erkannt",
                                        color = androidx.compose.ui.graphics.Color.White,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Button(onClick = {
                                        capturedImage = null
                                        detectedObject = null
                                    }) {
                                        Text("Neues Bild aufnehmen")
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Button(onClick = onRequestPermissions) {
                                Text("Kamera-Berechtigung erteilen")
                            }
                        }
                    }

                    if (actionStatus.isNotEmpty()) {
                        Text(
                            text = "Status: $actionStatus",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // RIGHT SIDE: Square (Greifen, Öffnen, Heben, Senken) + Dpad (Vorwärts, Rückwärts, 45° Links, 45° Rechts) at 40% speed
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Manuelle Steuerung",
                        style = MaterialTheme.typography.titleMedium
                    )

                    // 1. Viereck für Greifen, Öffnen, Heben, Senken (40% speed)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = {
                                    actionStatus = "GREIFEN"
                                    coroutineScope.launch {
                                        if (isBleSupported) hub.setChannel(2, 0.4f) // Motor C: rechts 40%
                                        delay(1000L)
                                        if (isBleSupported) hub.setChannel(2, 0f)
                                        actionStatus = "GREIFEN beendet"
                                    }
                                },
                                enabled = isBleSupported,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("GREIFEN", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(
                                onClick = {
                                    actionStatus = "ÖFFNEN"
                                    coroutineScope.launch {
                                        if (isBleSupported) hub.setChannel(2, -0.4f) // Motor C: links 40%
                                        delay(1000L)
                                        if (isBleSupported) hub.setChannel(2, 0f)
                                        actionStatus = "ÖFFNEN beendet"
                                    }
                                },
                                enabled = isBleSupported,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("ÖFFNEN", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = {
                                    actionStatus = "HEBEN"
                                    coroutineScope.launch {
                                        if (isBleSupported) hub.setChannel(3, 1.0f) // Motor D: 100% Heben
                                        delay(5000L)
                                        if (isBleSupported) hub.setChannel(3, 0f)
                                        actionStatus = "HEBEN beendet"
                                    }
                                },
                                enabled = isBleSupported,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("HEBEN", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(
                                onClick = {
                                    actionStatus = "SENKEN"
                                    coroutineScope.launch {
                                        if (isBleSupported) hub.setChannel(3, -1.0f) // Motor D: -100% Senken
                                        delay(5000L)
                                        if (isBleSupported) hub.setChannel(3, 0f)
                                        actionStatus = "SENKEN beendet"
                                    }
                                },
                                enabled = isBleSupported,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("SENKEN", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 2. Steuerkreuz (Dpad) für Fahren & Drehen (Vorwärts, Rückwärts 5 Sek)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // VORWÄRTS 5 SEKUNDEN
                        Button(
                            onClick = {
                                actionStatus = "VORWÄRTS (5 Sek)"
                                coroutineScope.launch {
                                    if (isBleSupported) {
                                        hub.setChannel(0, -0.4f)
                                        hub.setChannel(1, 0.4f)
                                    }
                                    delay(5000L)
                                    if (isBleSupported) {
                                        hub.setChannel(0, 0f)
                                        hub.setChannel(1, 0f)
                                    }
                                    actionStatus = "VORWÄRTS beendet"
                                }
                            },
                            enabled = isBleSupported,
                            modifier = Modifier.width(180.dp)
                        ) {
                            Text("VORWÄRTS 5 SEKUNDEN", style = MaterialTheme.typography.bodySmall)
                        }

                        // Middle row: 45° LINKS und 45° RECHTS
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    gyroTurnController.startTurn45(-1) { status ->
                                        actionStatus = status
                                    }
                                },
                                enabled = isBleSupported,
                                modifier = Modifier.width(95.dp)
                            ) {
                                Text("45° LINKS", style = MaterialTheme.typography.bodySmall)
                            }

                            Button(
                                onClick = {
                                    gyroTurnController.startTurn45(1) { status ->
                                        actionStatus = status
                                    }
                                },
                                enabled = isBleSupported,
                                modifier = Modifier.width(95.dp)
                            ) {
                                Text("45° RECHTS", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        // RÜCKWÄRTS 5 SEKUNDEN
                        Button(
                            onClick = {
                                actionStatus = "RÜCKWÄRTS (5 Sek)"
                                coroutineScope.launch {
                                    if (isBleSupported) {
                                        hub.setChannel(0, 0.4f)
                                        hub.setChannel(1, -0.4f)
                                    }
                                    delay(5000L)
                                    if (isBleSupported) {
                                        hub.setChannel(0, 0f)
                                        hub.setChannel(1, 0f)
                                    }
                                    actionStatus = "RÜCKWÄRTS beendet"
                                }
                            },
                            enabled = isBleSupported,
                            modifier = Modifier.width(180.dp)
                        ) {
                            Text("RÜCKWÄRTS 5 SEKUNDEN", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun takePhoto(
    imageCapture: ImageCapture,
    executor: ExecutorService,
    onImageCaptured: (Bitmap) -> Unit
) {
    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = image.toBitmap()
                image.close()
                onImageCaptured(bitmap)
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("CameraScreen", "Photo capture failed: ${exception.message}", exception)
            }
        }
    )
}

private fun drawDetection(bitmap: Bitmap, detection: Detection?): Bitmap {
    val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(mutableBitmap)

    val imgWidth = bitmap.width.toFloat()
    val imgHeight = bitmap.height.toFloat()
    val centerX = imgWidth / 2f
    val tolerance = 100f
    val bottomThreshold = imgHeight * 0.85f

    // Overlay target zone (±100px around image center)
    val zonePaint = Paint().apply {
        color = Color.GREEN
        alpha = 40
        style = Paint.Style.FILL
    }
    canvas.drawRect(centerX - tolerance, 0f, centerX + tolerance, imgHeight, zonePaint)

    // Draw grey area at the bottom of the image ("graue Fläche unten im Bild")
    val greyAreaPaint = Paint().apply {
        color = Color.GRAY
        alpha = 140
        style = Paint.Style.FILL
    }
    canvas.drawRect(0f, bottomThreshold, imgWidth, imgHeight, greyAreaPaint)

    val greyAreaTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
    }
    canvas.drawText("GRAUE FLÄCHE (Zielzone Unten)", 20f, imgHeight - 20f, greyAreaTextPaint)

    val linePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    canvas.drawLine(centerX, 0f, centerX, imgHeight, linePaint)
    canvas.drawLine(centerX - tolerance, 0f, centerX - tolerance, imgHeight, linePaint)
    canvas.drawLine(centerX + tolerance, 0f, centerX + tolerance, imgHeight, linePaint)

    if (detection != null) {
        val boxCenterX = detection.boundingBox.centerX()
        val isCentered = abs(boxCenterX - centerX) <= tolerance
        val touchesBottom = detection.boundingBox.bottom >= bottomThreshold

        val boxColor = when {
            touchesBottom -> Color.MAGENTA
            isCentered -> Color.GREEN
            else -> Color.RED
        }

        val paint = Paint().apply {
            color = boxColor
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        val textPaint = Paint().apply {
            color = boxColor
            textSize = 36f
            isFakeBoldText = true
        }

        canvas.drawRect(detection.boundingBox, paint)

        val colorName = detection.visualProfile?.colorName ?: ""
        val aspect = detection.visualProfile?.aspectRatio?.let { String.format("%.1f", it) } ?: ""
        val extraInfo = if (colorName.isNotEmpty()) " [$colorName, Ratio: $aspect]" else ""

        canvas.drawText(
            "${detection.label} ${(detection.confidence * 100).toInt()}%$extraInfo",
            detection.boundingBox.left,
            (detection.boundingBox.top - 10f).coerceAtLeast(40f),
            textPaint
        )

        // Draw color swatch circle if visual profile is present
        detection.visualProfile?.let { prof ->
            val swatchPaint = Paint().apply {
                color = prof.dominantColorHex
                style = Paint.Style.FILL
            }
            val borderPaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            val radius = 16f
            val cx = detection.boundingBox.left - 24f
            val cy = (detection.boundingBox.top - 20f).coerceAtLeast(20f)
            if (cx > 20f) {
                canvas.drawCircle(cx, cy, radius, swatchPaint)
                canvas.drawCircle(cx, cy, radius, borderPaint)
            }
        }
    }

    return mutableBitmap
}

enum class AutoState {
    EXPLORING,
    ALIGNING,
    WAITING_BEFORE_APPROACH,
    APPROACHING,
    DRIVE_EXTRA_SECOND,
    CLOSING_GRIPPER,
    GRABBED_COMPLETED
}

@Composable
fun AutomaticModeScreen(
    cameraExecutor: ExecutorService,
    hub: MouldKingHub6,
    isBleSupported: Boolean,
    hasCameraPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onRegisterEmergencyStop: (() -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }

    var isConnected by remember { mutableStateOf(false) }
    var isAutoRunning by remember { mutableStateOf(false) }
    var isDriving by remember { mutableStateOf(false) }
    var autoState by remember { mutableStateOf(AutoState.EXPLORING) }
    var autoStatus by remember { mutableStateOf("Automatik bereit. Bitte starten.") }

    var lockedTargetLabel by remember { mutableStateOf<String?>(null) }
    var lockedDetection by remember { mutableStateOf<Detection?>(null) }

    var capturedImage by remember { mutableStateOf<Bitmap?>(null) }
    var detectedObject by remember { mutableStateOf<Detection?>(null) }
    var objectDetector by remember { mutableStateOf<ObjectDetector?>(null) }
    var isLoadingModel by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onRegisterEmergencyStop {
            isAutoRunning = false
            if (isBleSupported) {
                hub.setChannel(0, 0f)
                hub.setChannel(1, 0f)
                hub.setChannel(2, 0f)
                hub.setChannel(3, 0f)
            }
        }
        onDispose {
            onRegisterEmergencyStop {}
            isAutoRunning = false
            if (isBleSupported) {
                hub.setChannel(0, 0f)
                hub.setChannel(1, 0f)
                hub.setChannel(2, 0f)
                hub.setChannel(3, 0f)
            }
        }
    }

    LaunchedEffect(Unit) {
        isLoadingModel = true
        objectDetector = ObjectDetector(context, modelAssetName = "best.onnx")
        isLoadingModel = false
    }

    LaunchedEffect(isAutoRunning, isConnected) {
        if (isAutoRunning && isConnected) {
            autoState = AutoState.EXPLORING
            lockedTargetLabel = null
            lockedDetection = null
            autoStatus = "Erkundungsfahrt gestartet..."

            try {
                while (isActive && autoState != AutoState.GRABBED_COMPLETED) {
                    val startTime = System.currentTimeMillis()

                    when (autoState) {
                        AutoState.EXPLORING -> {
                            val bitmap = takePhotoSuspend(imageCapture, cameraExecutor)
                            if (bitmap != null) {
                                val detection = objectDetector?.detect(bitmap)
                                capturedImage = drawDetection(bitmap, detection)
                                detectedObject = detection

                                if (detection != null) {
                                    lockedTargetLabel = detection.label
                                    lockedDetection = detection
                                    autoState = AutoState.ALIGNING
                                    autoStatus = "[1/6] Müll erkannt (${detection.label} ${(detection.confidence * 100).toInt()}%). Fokussiere Objekt..."
                                } else {
                                    if (isBleSupported) {
                                        hub.setChannel(0, -0.5f)
                                        hub.setChannel(1, 0.5f)
                                        hub.setChannel(2, -1.0f) // Greifer Motor C 100% offen
                                    }
                                    isDriving = true
                                    autoStatus = "[1/6] Erkundungsfahrt: Fahre geradeaus (Greifer 100% offen, Fahren 50%)"
                                }
                            }
                        }

                        AutoState.ALIGNING -> {
                            val bitmap = takePhotoSuspend(imageCapture, cameraExecutor)
                            if (bitmap != null) {
                                val rawDetection = objectDetector?.detect(
                                    bitmap = bitmap,
                                    targetProfile = lockedDetection?.visualProfile,
                                    lastKnownBox = lockedDetection?.boundingBox,
                                    targetLabel = lockedTargetLabel
                                )
                                val detection = rawDetection ?: lockedDetection
                                if (rawDetection != null) {
                                    lockedDetection = rawDetection
                                }

                                capturedImage = drawDetection(bitmap, detection)
                                detectedObject = detection

                                if (detection == null) {
                                    autoState = AutoState.EXPLORING
                                    lockedTargetLabel = null
                                    lockedDetection = null
                                } else {
                                    val boxCenterX = detection.boundingBox.centerX()
                                    val imageCenterX = bitmap.width / 2f
                                    val diffX = boxCenterX - imageCenterX
                                    val tolerance = 100f
                                    val confidencePct = (detection.confidence * 100).toInt()

                                    if (abs(diffX) <= tolerance) {
                                        // Ausgerichtet -> ALLES STOPPEN
                                        if (isBleSupported) {
                                            hub.setChannel(0, 0f)
                                            hub.setChannel(1, 0f)
                                            hub.setChannel(2, 0f)
                                            hub.setChannel(3, 0f)
                                        }
                                        isDriving = false
                                        autoState = AutoState.WAITING_BEFORE_APPROACH
                                    } else if (diffX < -tolerance) {
                                        // Drehe Links
                                        if (isBleSupported) {
                                            hub.setChannel(0, -0.5f)
                                            hub.setChannel(1, -0.5f)
                                            hub.setChannel(2, -1.0f)
                                        }
                                        isDriving = true
                                        autoStatus = "[2/6] Ausrichten auf ${lockedTargetLabel ?: detection.label} ($confidencePct%): Drehe LINKS (Δx = ${diffX.toInt()}px)"
                                    } else {
                                        // Drehe Rechts
                                        if (isBleSupported) {
                                            hub.setChannel(0, 0.5f)
                                            hub.setChannel(1, 0.5f)
                                            hub.setChannel(2, -1.0f)
                                        }
                                        isDriving = true
                                        autoStatus = "[2/6] Ausrichten auf ${lockedTargetLabel ?: detection.label} ($confidencePct%): Drehe RECHTS (Δx = +${diffX.toInt()}px)"
                                    }
                                }
                            }
                        }

                        AutoState.WAITING_BEFORE_APPROACH -> {
                            if (isBleSupported) {
                                hub.setChannel(0, 0f)
                                hub.setChannel(1, 0f)
                                hub.setChannel(2, 0f)
                                hub.setChannel(3, 0f)
                            }
                            isDriving = false

                            for (secondsLeft in 5 downTo 1) {
                                autoStatus = "[3/6] Müll zentriert! Motoren gestoppt. Warten vor Anfahrt: ${secondsLeft}s..."
                                delay(1000L)
                                if (!isActive || !isAutoRunning) break
                            }

                            if (isActive && isAutoRunning) {
                                autoState = AutoState.APPROACHING
                            }
                        }

                        AutoState.APPROACHING -> {
                            val bitmap = takePhotoSuspend(imageCapture, cameraExecutor)
                            if (bitmap != null) {
                                val rawDetection = objectDetector?.detect(
                                    bitmap = bitmap,
                                    targetProfile = lockedDetection?.visualProfile,
                                    lastKnownBox = lockedDetection?.boundingBox,
                                    targetLabel = lockedTargetLabel
                                )
                                val detection = rawDetection ?: lockedDetection
                                if (rawDetection != null) {
                                    lockedDetection = rawDetection
                                }

                                capturedImage = drawDetection(bitmap, detection)
                                detectedObject = detection

                                val bottomThreshold = bitmap.height * 0.85f

                                if (detection != null && detection.boundingBox.bottom >= bottomThreshold) {
                                    // Graue Fläche unten berührt!
                                    autoState = AutoState.DRIVE_EXTRA_SECOND
                                } else {
                                    // Fahre auf Müll zu
                                    if (isBleSupported) {
                                        hub.setChannel(0, -0.5f)
                                        hub.setChannel(1, 0.5f)
                                        hub.setChannel(2, -1.0f) // Greifer Motor C 100% offen
                                    }
                                    isDriving = true
                                    autoStatus = "[4/6] Fahre auf ${lockedTargetLabel ?: "Müll"} zu (Unterkante: ${detection?.boundingBox?.bottom?.toInt() ?: 0} / ${bottomThreshold.toInt()} px)"
                                }
                            }
                        }

                        AutoState.DRIVE_EXTRA_SECOND -> {
                            if (isBleSupported) {
                                hub.setChannel(0, -0.5f)
                                hub.setChannel(1, 0.5f)
                                hub.setChannel(2, -1.0f) // Greifer Motor C 100% offen
                            }
                            isDriving = true
                            autoStatus = "[5/6] Graue Fläche berührt! Fahre 1s weiter zum Positionieren..."
                            delay(1000L)

                            autoState = AutoState.CLOSING_GRIPPER
                        }

                        AutoState.CLOSING_GRIPPER -> {
                            if (isBleSupported) {
                                hub.setChannel(0, 0f)
                                hub.setChannel(1, 0f)
                            }
                            isDriving = false
                            autoStatus = "[6/6] Schließe Greifer komplett um ${lockedTargetLabel ?: "Müll"} (Motor C 100%)..."

                            if (isBleSupported) {
                                hub.setChannel(2, 1.0f)
                            }
                            delay(2000L)

                            if (isBleSupported) {
                                hub.setChannel(2, 0f)
                            }
                            autoState = AutoState.GRABBED_COMPLETED
                        }

                        AutoState.GRABBED_COMPLETED -> {
                            if (isBleSupported) {
                                hub.setChannel(0, 0f)
                                hub.setChannel(1, 0f)
                                hub.setChannel(2, 0f)
                            }
                            isDriving = false
                            autoStatus = "✓ ${lockedTargetLabel ?: "Müll"} erfolgreich gegriffen! Vorgang abgeschlossen."
                        }
                    }

                    val elapsedTime = System.currentTimeMillis() - startTime
                    val remainingDelay = 1000L - elapsedTime
                    if (remainingDelay > 0 && autoState != AutoState.WAITING_BEFORE_APPROACH && autoState != AutoState.DRIVE_EXTRA_SECOND && autoState != AutoState.CLOSING_GRIPPER) {
                        delay(remainingDelay)
                    } else if (remainingDelay <= 0) {
                        delay(50L)
                    }
                }
            } finally {
                if (isBleSupported) {
                    hub.setChannel(0, 0f)
                    hub.setChannel(1, 0f)
                    hub.setChannel(2, 0f)
                }
                isDriving = false
            }
        } else {
            if (isBleSupported) {
                hub.setChannel(0, 0f)
                hub.setChannel(1, 0f)
                hub.setChannel(2, 0f)
            }
            isDriving = false
            autoState = AutoState.EXPLORING
            lockedTargetLabel = null
            lockedDetection = null
            if (!isAutoRunning) {
                autoStatus = "Automatik gestoppt"
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!isConnected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = {
                        if (isBleSupported) {
                            hub.connect()
                            isConnected = true
                        }
                    },
                    enabled = isBleSupported,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text("Mouldking Hub verbinden", style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            // Scroll-free 2-Column Landscape Layout
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // LEFT SIDE: Camera Preview & Controls
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Header Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Automatischer Modus",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = when (autoState) {
                                AutoState.EXPLORING -> "[1/6] Erkundung"
                                AutoState.ALIGNING -> "[2/6] Ausrichten"
                                AutoState.WAITING_BEFORE_APPROACH -> "[3/6] Warten"
                                AutoState.APPROACHING -> "[4/6] Anfahren"
                                AutoState.DRIVE_EXTRA_SECOND -> "[5/6] Positionieren"
                                AutoState.CLOSING_GRIPPER -> "[6/6] Greifen"
                                AutoState.GRABBED_COMPLETED -> "✓ Abgeschlossen"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Live Camera Preview
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        if (isLoadingModel || objectDetector == null) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        } else if (hasCameraPermission) {
                            AndroidView(
                                factory = { ctx ->
                                    val previewView = PreviewView(ctx)
                                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                    cameraProviderFuture.addListener({
                                        val cameraProvider = cameraProviderFuture.get()
                                        val preview = Preview.Builder().build().also {
                                            it.setSurfaceProvider(previewView.surfaceProvider)
                                        }
                                        try {
                                            cameraProvider.unbindAll()
                                            cameraProvider.bindToLifecycle(
                                                lifecycleOwner,
                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                preview,
                                                imageCapture
                                            )
                                        } catch (e: Exception) {
                                            Log.e("AutomaticMode", "Camera binding failed", e)
                                        }
                                    }, ContextCompat.getMainExecutor(ctx))
                                    previewView
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Button(onClick = onRequestPermissions) {
                                    Text("Kamera-Berechtigung erteilen")
                                }
                            }
                        }
                    }

                    // Start/Stop Toggle Button
                    Button(
                        onClick = {
                            isAutoRunning = !isAutoRunning
                        },
                        colors = if (isAutoRunning) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        } else {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        contentPadding = PaddingValues(vertical = 0.dp)
                    ) {
                        Text(
                            if (isAutoRunning) "AUTOMATIK STOPPEN" else "AUTOMATIK STARTEN",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    // Status Display Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDriving) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = if (isDriving) "Motorstatus: IN BEWEGUNG 🟢" else "Motorstatus: GESTOPPT 🔴",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = autoStatus,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // RIGHT SIDE: Evaluated Image & Target Details Card
                Column(
                    modifier = Modifier
                        .weight(0.9f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Kamera-Auswertung & Ziel",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        capturedImage?.let { img ->
                            Image(
                                bitmap = img.asImageBitmap(),
                                contentDescription = "Letztes ausgewertetes Bild",
                                modifier = Modifier.fillMaxSize()
                            )
                        } ?: run {
                            Text(
                                text = if (isAutoRunning) "Warte auf Erstes Bild..." else "Kein Bild aufgenommen",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                autoState == AutoState.GRABBED_COMPLETED -> MaterialTheme.colorScheme.primaryContainer
                                lockedTargetLabel != null -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.secondaryContainer
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (lockedTargetLabel != null) {
                                val detection = detectedObject
                                val pct = if (detection != null) (detection.confidence * 100).toInt() else 0
                                Text(
                                    text = "Fokussiertes Objekt: $lockedTargetLabel ($pct%)",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Phase: ${
                                        when (autoState) {
                                            AutoState.ALIGNING -> "Richte auf Objekt aus..."
                                            AutoState.WAITING_BEFORE_APPROACH -> "Ausgerichtet! Pause vor Anfahrt..."
                                            AutoState.APPROACHING -> "Fahre auf $lockedTargetLabel zu..."
                                            AutoState.DRIVE_EXTRA_SECOND -> "Zielzone berührt -> Positionieren..."
                                            AutoState.CLOSING_GRIPPER -> "Greifer schließt um $lockedTargetLabel..."
                                            AutoState.GRABBED_COMPLETED -> "Erfolgreich gegriffen!"
                                            else -> "Aktiv"
                                        }
                                    }",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                Text(
                                    text = "Kein Objekt fokussiert",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun takePhotoSuspend(
    imageCapture: ImageCapture,
    executor: ExecutorService
): Bitmap? = suspendCancellableCoroutine { continuation ->
    try {
        imageCapture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.toBitmap()
                    image.close()
                    if (continuation.isActive) {
                        continuation.resume(bitmap)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("AutomaticMode", "Photo capture failed: ${exception.message}", exception)
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        )
    } catch (e: Exception) {
        Log.e("AutomaticMode", "takePicture exception: ${e.message}", e)
        if (continuation.isActive) {
            continuation.resume(null)
        }
    }
}

