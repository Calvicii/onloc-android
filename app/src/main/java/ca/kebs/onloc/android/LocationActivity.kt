package ca.kebs.onloc.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ca.kebs.onloc.android.models.Device
import ca.kebs.onloc.android.api.AuthApiService
import ca.kebs.onloc.android.api.DevicesApiService
import ca.kebs.onloc.android.api.LocationsApiService
import ca.kebs.onloc.android.models.Location
import ca.kebs.onloc.android.services.LocationCallbackManager
import ca.kebs.onloc.android.services.LocationForegroundService
import ca.kebs.onloc.android.ui.theme.OnlocAndroidTheme

class LocationActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val devicesApiService = DevicesApiService()
            val locationsApiService = LocationsApiService()
            val context = LocalContext.current
            val preferences = Preferences(context)

            var showBottomSheet by remember { mutableStateOf(false) }

            var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
            var devicesErrorMessage by remember { mutableStateOf("") }
            var selectedDeviceId by remember { mutableIntStateOf(preferences.getDeviceId()) }

            val credentials = preferences.getUserCredentials()
            val token = credentials.first
            val user = credentials.second
            val ip = preferences.getIP()

            if (token != null && ip != null) {
                devicesApiService.getDevices(ip, token) { foundDevices, errorMessage ->
                    if (foundDevices != null) {
                        devices = foundDevices
                    }
                    if (errorMessage != null) {
                        devicesErrorMessage = errorMessage
                    }
                }
            }

            // Permissions
            var notificationsGranted by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }

            var fineLocationGranted by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }

            var backgroundLocationGranted by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        notificationsGranted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        fineLocationGranted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        backgroundLocationGranted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            // Location service
            var isLocationServiceRunning by remember {
                mutableStateOf(preferences.getLocationServiceStatus())
            }

            var currentLocation by remember { mutableStateOf<Location?>(null) }
            LocationCallbackManager.callback = { location ->
                if (ip != null && token != null && location != null && selectedDeviceId != -1) {
                    val parsedLocation = Location.fromAndroidLocation(0, selectedDeviceId, location)
                    currentLocation = parsedLocation
                    locationsApiService.postLocation(ip, token, parsedLocation)
                }
            }

            OnlocAndroidTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Onloc") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.primary
                            ),
                            actions = {
                                TextButton(
                                    onClick = { showBottomSheet = true },
                                    enabled = !preferences.getLocationServiceStatus()
                                ) {
                                    if (selectedDeviceId == -1) {
                                        Text("Select a device")
                                    } else {
                                        val device = devices.find { it.id == selectedDeviceId }
                                        if (device != null) {
                                            Text(device.name)
                                        } else {
                                            Text(
                                                "Error",
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                                Avatar(context, preferences)
                            }
                        )
                    }
                ) { innerPadding ->
                    val scrollState = rememberScrollState()

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .verticalScroll(scrollState)
                    ) {
                        var canStartLocationService = true
                        var serviceStatus = if (isLocationServiceRunning) {
                            "Started"
                        } else {
                            "Stopped"
                        }
                        if (selectedDeviceId == -1) {
                            serviceStatus = "No device selected"
                            canStartLocationService = false
                        }
                        if (!fineLocationGranted || !backgroundLocationGranted) {
                            serviceStatus = "Required permissions missing"
                            canStartLocationService = false
                        }

                        val defaultPadding = 16.dp
                        Row(
                            modifier = Modifier
                                .padding(defaultPadding)
                                .height(32.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Service's status: $serviceStatus",
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = defaultPadding)
                        ) {
                            ElevatedCard(
                                elevation = CardDefaults.cardElevation(
                                    defaultElevation = 6.dp
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(defaultPadding)
                                ) {
                                    Text(text = "Accuracy: ${currentLocation?.accuracy}")
                                    Text(text = "Altitude: ${currentLocation?.altitude}")
                                    Text(text = "Altitude accuracy: ${currentLocation?.altitudeAccuracy}")
                                    Text(text = "Latitude: ${currentLocation?.latitude}")
                                    Text(text = "Longitude: ${currentLocation?.longitude}")

                                    Button(
                                        onClick = {
                                            if (isLocationServiceRunning) {
                                                stopLocationService(context, preferences)
                                                isLocationServiceRunning = false
                                                currentLocation = null
                                            } else {
                                                startLocationService(context, preferences)
                                                isLocationServiceRunning = true
                                            }
                                        },
                                        enabled = canStartLocationService,
                                        modifier = Modifier.padding(top = defaultPadding)
                                    ) {
                                        Text(text = if (isLocationServiceRunning) "Stop" else "Start")
                                    }
                                }
                            }
                        }

                        Permissions(notificationsGranted, fineLocationGranted, backgroundLocationGranted)

                        DeviceSelector(
                            preferences = preferences,
                            devices = devices,
                            selectedDeviceId = selectedDeviceId,
                            showBottomSheet = showBottomSheet,
                            onDismissBottomSheet = { showBottomSheet = false }
                        ) { id ->
                            selectedDeviceId = id
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Avatar(context: Context, preferences: Preferences) {
    var accountMenuExpanded by remember { mutableStateOf(false) }

    val authApiService = AuthApiService()

    val ip = preferences.getIP()
    val user = preferences.getUserCredentials().second

    IconButton(onClick = { accountMenuExpanded = true }) {
        Icon(
            Icons.Outlined.AccountCircle,
            contentDescription = "Account"
        )
        DropdownMenu(
            expanded = accountMenuExpanded,
            onDismissRequest = { accountMenuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Logout") },
                onClick = {
                    stopLocationService(context, preferences)

                    if (ip != null && user != null) {
                        authApiService.logout(ip, user.id)
                    }

                    preferences.deleteUserCredentials()
                    preferences.deleteDeviceId()

                    val intent = Intent(context, MainActivity::class.java)
                    intent.flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    context.startActivity(intent)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelector(
    preferences: Preferences,
    devices: List<Device>,
    selectedDeviceId: Int,
    showBottomSheet: Boolean,
    onDismissBottomSheet: () -> Unit,
    onDeviceSelected: (id: Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
    )

    if (showBottomSheet) {
        ModalBottomSheet(
            modifier = Modifier.fillMaxHeight(),
            sheetState = sheetState,
            onDismissRequest = onDismissBottomSheet
        ) {
            if (devices.isNotEmpty()) {
                LazyColumn {
                    items(devices) { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .selectable(
                                    selected = (device.id == selectedDeviceId),
                                    onClick = {
                                        onDeviceSelected(device.id)
                                        preferences.createDeviceId(device.id)
                                        onDismissBottomSheet()
                                    },
                                    role = Role.RadioButton
                                )
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (device.id == selectedDeviceId),
                                onClick = null
                            )
                            Text(
                                text = device.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "No device found.",
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
fun Permissions(notificationsGranted: Boolean, fineLocationGranted: Boolean, backgroundLocationGranted: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "Required Permissions",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(16.dp))

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) {}

        PermissionCard(
            name = "Notifications",
            description = "Allows the app to send notifications about the service's status.",
            isGranted = notificationsGranted,
            onGrantClick = {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        )

        PermissionCard(
            name = "Background Location",
            description = "Allows the app to share your device's location with the server even when the app is not in use.",
            isGranted = (fineLocationGranted && backgroundLocationGranted),
            onGrantClick = {
                if (!fineLocationGranted) {
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                } else if (!backgroundLocationGranted) {
                    permissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
        )
    }
}

@Composable
fun PermissionCard(name: String, description: String, isGranted: Boolean, onGrantClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        ElevatedCard(
            elevation = CardDefaults.cardElevation(
                defaultElevation = 6.dp
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            val defaultPadding = 16.dp
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(defaultPadding)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = defaultPadding)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = { onGrantClick() },
                    enabled = !isGranted,
                    modifier = Modifier.padding(defaultPadding)
                ) {
                    Text(if (isGranted) "Granted" else "Grant")
                }
            }
        }
    }
}

fun startLocationService(context: Context, preferences: Preferences) {
    preferences.createLocationServiceStatus(true)
    val serviceIntent = Intent(context, LocationForegroundService::class.java)
    context.startService(serviceIntent)
}

fun stopLocationService(context: Context, preferences: Preferences) {
    preferences.createLocationServiceStatus(false)
    val serviceIntent = Intent(context, LocationForegroundService::class.java)
    context.stopService(serviceIntent)
}