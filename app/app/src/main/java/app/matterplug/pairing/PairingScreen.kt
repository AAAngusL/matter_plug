package app.matterplug.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.matterplug.R
import app.matterplug.matter.PairingStage

/**
 * Pairing flow of spec section 7.1. Shown when the plug list is empty, and from "Add plug" in the plug switcher.
 * [onExit] is null for the first plug (nowhere to go back to); otherwise it leaves the screen, on Cancel or after saving.
 */
@Composable
fun PairingScreen(onExit: (() -> Unit)? = null, viewModel: PairingViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Cancel is offered before pairing starts and after it failed; never while it runs or once the plug is paired.
    val canLeave = onExit != null && (state is PairingState.EnterCode || state is PairingState.EnterWifi || state is PairingState.Failed)
    val leave = { viewModel.abandon(); onExit?.invoke(); Unit }
    BackHandler(enabled = canLeave, onBack = leave)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (val s = state) {
            is PairingState.EnterCode -> EnterCodeStep(viewModel, s)
            is PairingState.EnterWifi -> EnterWifiStep(viewModel, s)
            is PairingState.Running -> RunningStep(viewModel, s)
            is PairingState.Failed -> FailedStep(viewModel, s)
            is PairingState.NamePlug -> NamePlugStep(viewModel, onSaved = { onExit?.invoke() })
        }
        if (canLeave) TextButton(onClick = leave) { Text(stringResource(R.string.cancel)) }
    }
}

// ---- EnterCode ----

@Composable
private fun EnterCodeStep(viewModel: PairingViewModel, state: PairingState.EnterCode) {
    val context = LocalContext.current
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var cameraAsked by rememberSaveable { mutableStateOf(false) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
    }
    val scanning = hasCamera && cameraGranted && !state.typing

    // Ask for the camera once, when the scan view is first wanted.
    LaunchedEffect(state.typing, cameraGranted) {
        if (hasCamera && !cameraGranted && !state.typing && !cameraAsked) {
            cameraAsked = true
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineMedium)

    if (scanning) {
        Text(stringResource(R.string.pair_scan_hint))
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            QrScanner(onCode = { viewModel.submitCode(it) }, modifier = Modifier.fillMaxSize())
        }
        TextButton(onClick = { viewModel.setTyping(true) }) { Text(stringResource(R.string.pair_type_instead)) }
    } else {
        if (!state.typing && cameraAsked && !cameraGranted) {
            Text(stringResource(R.string.pair_camera_denied))
        }
        var text by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.pair_code_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { viewModel.submitCode(text) }, enabled = text.isNotBlank()) {
            Text(stringResource(R.string.pair_continue))
        }
        if (hasCamera && cameraGranted) {
            TextButton(onClick = { viewModel.setTyping(false) }) { Text(stringResource(R.string.pair_scan_instead)) }
        }
    }

    if (state.notMatterCode) {
        Text(stringResource(R.string.err_not_matter_code), color = MaterialTheme.colorScheme.error)
    }
}

// ---- EnterWifi ----

private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
private fun EnterWifiStep(viewModel: PairingViewModel, state: PairingState.EnterWifi) {
    val context = LocalContext.current
    val ssid by viewModel.ssid.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()

    // Android 9 to 11: Location is already needed for Bluetooth scanning, so ask now and prefill the Wi-Fi name (A9).
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.prefillWifiName()
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.prefillWifiName() else locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // Runs after the Bluetooth (or Location) permission dialog.
    val bleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        viewModel.start(result.isNotEmpty() && result.values.all { it })
    }

    Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineMedium)
    Text(stringResource(R.string.pair_wifi_intro))
    if (state.fiveGhzHint) {
        Text(stringResource(R.string.pair_wifi_5ghz_hint), color = MaterialTheme.colorScheme.tertiary)
    }
    OutlinedTextField(
        value = ssid,
        onValueChange = viewModel::setSsid,
        label = { Text(stringResource(R.string.pair_wifi_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = password,
        onValueChange = viewModel::setPassword,
        label = { Text(stringResource(R.string.pair_wifi_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    state.problem?.let { problem ->
        val message = when (problem) {
            StartProblem.BluetoothOff -> R.string.err_bluetooth_off
            StartProblem.LocationOff -> R.string.err_location_off
            StartProblem.BlePermissionDenied -> R.string.err_ble_permission_denied
        }
        Text(stringResource(message), color = MaterialTheme.colorScheme.error)
    }
    Button(
        enabled = ssid.isNotEmpty() && password.isNotEmpty(),
        onClick = {
            val missing = blePermissions().filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) viewModel.start(true) else bleLauncher.launch(missing.toTypedArray())
        }
    ) { Text(stringResource(R.string.pair_start)) }
}

// ---- Running ----

@Composable
private fun RunningStep(viewModel: PairingViewModel, state: PairingState.Running) {
    // The back button is disabled while pairing runs; Cancel is the way out.
    BackHandler(enabled = true) { }

    // Keep the screen on while pairing runs (same effect as FLAG_KEEP_SCREEN_ON).
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Text(stringResource(R.string.pair_running_title), style = MaterialTheme.typography.headlineMedium)
    PairingStage.entries.forEach { stage ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                when {
                    stage.ordinal < state.stage.ordinal -> Text("✓")
                    stage == state.stage -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> Text("·")
                }
            }
            Text(
                stringResource(stageLabel(stage)),
                style = if (stage == state.stage) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = viewModel::cancel, enabled = state.canCancel) { Text(stringResource(R.string.cancel)) }
}

private fun stageLabel(stage: PairingStage): Int = when (stage) {
    PairingStage.FindingPlug -> R.string.stage_finding_plug
    PairingStage.Connecting -> R.string.stage_connecting
    PairingStage.SecuringSession -> R.string.stage_securing_session
    PairingStage.Commissioning -> R.string.stage_commissioning
    PairingStage.JoiningWifi -> R.string.stage_joining_wifi
    PairingStage.FindingOnNetwork -> R.string.stage_finding_on_network
    PairingStage.Finishing -> R.string.stage_finishing
}

// ---- Failed ----

@Composable
private fun FailedStep(viewModel: PairingViewModel, state: PairingState.Failed) {
    Text(stringResource(R.string.pair_failed_title), style = MaterialTheme.typography.headlineMedium)
    Text(failureMessage(state.reason))
    Text(stringResource(R.string.reset_then_retry))
    Button(onClick = viewModel::tryAgain) { Text(stringResource(R.string.pair_try_again)) }
}

@Composable
private fun failureMessage(reason: FailureReason): String = when (reason) {
    FailureReason.NoOnOff -> stringResource(R.string.err_no_onoff)
    FailureReason.CodeMismatch -> stringResource(R.string.err_code_mismatch)
    FailureReason.Cancelled -> stringResource(R.string.pairing_cancelled)
    is FailureReason.AtStage -> when (reason.stage) {
        PairingStage.FindingPlug -> stringResource(R.string.err_no_plug_found)
        PairingStage.Connecting -> stringResource(R.string.err_ble_connect_failed)
        PairingStage.SecuringSession -> stringResource(R.string.err_code_rejected)
        PairingStage.JoiningWifi -> stringResource(R.string.err_wifi_join_failed)
        PairingStage.FindingOnNetwork -> stringResource(R.string.err_find_on_network)
        else -> stringResource(
            R.string.err_generic,
            stringResource(stageLabel(reason.stage)),
            reason.errorCode?.toString() ?: stringResource(R.string.error_code_none)
        )
    }
}

// ---- NamePlug ----

@Composable
private fun NamePlugStep(viewModel: PairingViewModel, onSaved: () -> Unit) {
    val name by viewModel.plugName.collectAsStateWithLifecycle()
    Text(stringResource(R.string.pair_name_title), style = MaterialTheme.typography.headlineMedium)
    OutlinedTextField(
        value = name,
        onValueChange = viewModel::setPlugName,
        label = { Text(stringResource(R.string.pair_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Button(onClick = { viewModel.savePlug(onSaved) }) { Text(stringResource(R.string.save)) }
}
