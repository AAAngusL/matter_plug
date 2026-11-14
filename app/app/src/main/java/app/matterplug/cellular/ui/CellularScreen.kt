package app.matterplug.cellular.ui

import android.Manifest
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.matterplug.R
import app.matterplug.cellular.CellularReader
import app.matterplug.cellular.logic.Layer
import app.matterplug.cellular.logic.LayerResult
import java.util.Date

/** Cellular Check, its own screen from the ⋮ menu (section 10). The same screen whichever plug is shown. */
@Composable
fun CellularScreen(onBack: () -> Unit, viewModel: CellularViewModel = viewModel()) {
    BackHandler(onBack = onBack)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val intervalMin by viewModel.intervalMin.collectAsStateWithLifecycle()
    val number by viewModel.numberText.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) viewModel.enable()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
            Text(stringResource(R.string.cellular_title), style = MaterialTheme.typography.headlineMedium)
        }
        val s = settings ?: return@Column

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cellular_switch), modifier = Modifier.weight(1f))
            Switch(checked = s.enabled, onCheckedChange = { on ->
                when {
                    !on -> viewModel.disable()
                    CellularReader.hasPermission(context) -> viewModel.enable()
                    else -> permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                }
            })
        }
        if (permissionDenied) {
            Text(stringResource(R.string.cellular_permission_denied), color = MaterialTheme.colorScheme.error)
        }

        OutlinedTextField(
            value = number,
            onValueChange = viewModel::setOwnNumber,
            label = { Text(stringResource(R.string.cellular_own_number)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Note(stringResource(R.string.cellular_own_number_note))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cellular_sms_test_at), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                TimePickerDialog(context, { _, h, m -> viewModel.setTestTime(h, m) },
                    s.smsTestTime.hour, s.smsTestTime.minute, DateFormat.is24HourFormat(context)).show()
            }) { Text(timeFormat.format(Date(minutesToday(s.smsTestMinutes)))) }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cellular_check_every), modifier = Modifier.weight(1f))
            Text(intervalMin?.let { stringResource(R.string.cellular_check_every_value, it) } ?: "")
        }

        OutlinedButton(onClick = viewModel::runNow, enabled = s.enabled) { Text(stringResource(R.string.cellular_run_now)) }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        for (layer in Layer.entries) LayerLine(layer, status.results[layer], status.lastRunAt?.let { timeFormat.format(Date(it)) })

        val last = status.lastRunAt
        val next = status.nextRunAt
        Text(
            when {
                last == null -> stringResource(R.string.cellular_no_run)
                next != null && s.enabled -> stringResource(R.string.cellular_last_next, timeFormat.format(Date(last)), timeFormat.format(Date(next)))
                else -> stringResource(R.string.cellular_last, timeFormat.format(Date(last)))
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** "SIM   Pass   10:15", with the detail or reason below. The SMS layer says "not tested yet" until C3 runs it. */
@Composable
private fun LayerLine(layer: Layer, result: LayerResult?, time: String?) {
    val (word, detail, problem) = when (result) {
        is LayerResult.Pass -> Triple(stringResource(R.string.cellular_pass), result.detail, false)
        is LayerResult.Fail -> Triple(stringResource(R.string.cellular_fail), result.reason, true)
        is LayerResult.Skipped -> Triple(stringResource(R.string.cellular_skipped), stringResource(R.string.cellular_skipped_because, layerName(result.because)), false)
        LayerResult.NotDue, null -> Triple("—", stringResource(R.string.cellular_not_tested), false)
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(layerName(layer), modifier = Modifier.width(110.dp), style = MaterialTheme.typography.titleSmall)
            Text(word, modifier = Modifier.width(72.dp),
                color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (result is LayerResult.Pass || result is LayerResult.Fail || result is LayerResult.Skipped) Text(time ?: "")
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 110.dp))
    }
}

@Composable
private fun layerName(layer: Layer): String = stringResource(
    when (layer) {
        Layer.Sim -> R.string.cellular_layer_sim
        Layer.Registration -> R.string.cellular_layer_registration
        Layer.Signal -> R.string.cellular_layer_signal
        Layer.Sms -> R.string.cellular_layer_sms
    }
)

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Today's wall-clock time [minutes] after midnight, as epoch millis, so the device's time format can show it. */
private fun minutesToday(minutes: Int): Long =
    java.time.LocalDate.now().atTime(minutes / 60, minutes % 60).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
