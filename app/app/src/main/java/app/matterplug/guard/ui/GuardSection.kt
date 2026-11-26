package app.matterplug.guard.ui

import app.matterplug.alerts.ui.TestState
import android.Manifest
import android.content.ActivityNotFoundException
import android.text.format.DateFormat
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.matterplug.R
import app.matterplug.guard.GuardPermissions
import app.matterplug.alerts.logic.AlertAddress
import app.matterplug.battery.PowerWords
import app.matterplug.alerts.logic.AlertKind
import app.matterplug.alerts.logic.DeliveryError
import app.matterplug.alerts.logic.TestOutcome
import app.matterplug.guard.logic.GuardSettings
import app.matterplug.guard.logic.PercentInput
import app.matterplug.guard.logic.PermissionPrompt
import app.matterplug.alerts.logic.tokenOverPlainHttp
import app.matterplug.heartbeat.ui.HeartbeatSection
import app.matterplug.ui.LabeledSwitch
import java.util.Date
import kotlin.math.roundToInt

/** The Battery Guard section of the plug screen (BATTERY-GUARD.md section 10). Shows nothing until the stored values are read. */
@Composable
fun GuardSection(modifier: Modifier = Modifier, viewModel: GuardViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val hasPlug by viewModel.hasPlug.collectAsStateWithLifecycle()
    val address by viewModel.endpoint.addressText.collectAsStateWithLifecycle()
    val tokenText by viewModel.endpoint.tokenText.collectAsStateWithLifecycle()
    val addressError by viewModel.endpoint.addressError.collectAsStateWithLifecycle()
    val testState by viewModel.endpoint.testState.collectAsStateWithLifecycle()
    val disableState by viewModel.disableState.collectAsStateWithLifecycle()
    val prompt by viewModel.prompt.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The launcher and the effects come before the early return below, so they are always composed in the same order.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.promptDone()      // granted or not: go on to the next prompt
    }
    LaunchedEffect(prompt) {
        val p = prompt ?: return@LaunchedEffect
        viewModel.promptConsumed()
        when (p) {
            PermissionPrompt.Notifications -> try {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (e: ActivityNotFoundException) {
                Log.w("Guard", "notification permission dialog not available", e)
                viewModel.promptDone()
            }
            PermissionPrompt.Exemption -> {
                try {
                    context.startActivity(GuardPermissions.ignoreBatteryOptimizationsIntent(context))
                } catch (e: ActivityNotFoundException) {
                    Log.w("Guard", "battery optimisation dialog not available", e)
                }
                viewModel.promptDone()
            }
        }
    }

    // The permission can be changed in the system settings; look again when the screen comes back.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val exactAllowed = remember(resumeTick, settings, status.lastCheckAt) { GuardPermissions.canScheduleExact(context) }

    val s = settings ?: return

    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }   // follows the device's 12 or 24-hour setting
    val lines = StatusText.lines(
        status = status,
        enabled = s.enabled,
        exactAlarmsAllowed = exactAllowed,
        formatTime = { timeFormat.format(Date(it)) },
    )

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledSwitch(
            label = stringResource(R.string.guard_switch),
            checked = s.enabled,
            // Disabled while the plug is being read or switched, so the switch cannot be toggled twice.
            enabled = hasPlug == true && (disableState == DisableState.Idle || disableState == DisableState.TurnOnFailed),
            onChange = viewModel::setEnabled,
        )
        if (hasPlug == false) Text(stringResource(R.string.guard_pair_first), style = MaterialTheme.typography.bodySmall)
        when (disableState) {
            DisableState.Reading -> Note(stringResource(R.string.guard_disable_checking))
            DisableState.TurningOn -> Note(stringResource(R.string.guard_disable_turning_on))
            DisableState.TurnOnFailed -> Text(
                stringResource(R.string.guard_disable_turn_on_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            else -> {}
        }

        // The ranges depend on the other value, so a pair that breaks the 10-point gap cannot be produced.
        // Turn on at the far left, Turn off at the far right. The ranges depend on the other value, so a pair that
        // breaks the 10-point gap cannot be saved.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            PercentField(stringResource(R.string.guard_turn_on_at), s.low, GuardSettings.lowRange(s.high), viewModel::setLow)
            PercentField(stringResource(R.string.guard_turn_off_at), s.high, GuardSettings.highRange(s.low), viewModel::setHigh)
        }

        IntervalPicker(stringResource(R.string.guard_check_every), s.intervalMin, viewModel::setInterval)

        LabeledSwitch(stringResource(R.string.guard_send_alerts), s.alertsEnabled, true, viewModel::setAlertsEnabled)
        LabeledSwitch(stringResource(R.string.guard_send_status), s.statusEnabled, true, viewModel::setStatusEnabled)
        OutlinedTextField(
            value = address,
            onValueChange = viewModel.endpoint::setAddress,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.guard_alert_address)) },
            placeholder = { Text(stringResource(R.string.guard_alert_address_hint)) },
            singleLine = true,
            isError = addressError,
            supportingText = if (addressError) {
                { Text(stringResource(R.string.guard_address_error)) }
            } else null,
        )

        var tokenShown by remember { mutableStateOf(false) }      // hidden by default; not kept across a restart
        OutlinedTextField(
            value = tokenText,
            onValueChange = viewModel.endpoint::setToken,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.guard_alert_token)) },
            singleLine = true,
            visualTransformation = if (tokenShown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { tokenShown = !tokenShown }) {
                    Text(stringResource(if (tokenShown) R.string.guard_token_hide else R.string.guard_token_show))
                }
            },
        )
        if (tokenOverPlainHttp(tokenText, address)) {
            Text(
                stringResource(R.string.guard_token_http_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        HeartbeatSection()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Needs a valid address only: works with "Send alerts" off.
            OutlinedButton(
                onClick = viewModel::sendTestAlert,
                enabled = AlertAddress.isValid(address) && testState != TestState.Sending,
            ) { Text(stringResource(R.string.guard_send_test)) }
            OutlinedButton(onClick = viewModel::checkNow, enabled = s.enabled) { Text(stringResource(R.string.guard_check_now)) }
        }
        TestResultText(testState)

        for (line in lines) StatusLineText(line)
        Note(stringResource(R.string.guard_bottom_note))
    }

    if (disableState == DisableState.AskTurnOn) {
        AlertDialog(
            onDismissRequest = viewModel::disableCancel,
            text = { Text(stringResource(R.string.guard_disable_dialog_text)) },
            confirmButton = { TextButton(onClick = viewModel::disableTurnOn) { Text(stringResource(R.string.guard_disable_turn_on)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::disableLeaveOff) { Text(stringResource(R.string.guard_disable_leave_off)) }
                    TextButton(onClick = viewModel::disableCancel) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
}

/** "200 OK", the status code, or the error, next to the test button. */
@Composable
private fun TestResultText(state: TestState) {
    val (text, isError) = when (state) {
        TestState.Idle -> return
        TestState.Sending -> stringResource(R.string.guard_test_sending) to false
        is TestState.Done -> when (val o = state.outcome) {
            TestOutcome.Ok -> stringResource(R.string.guard_test_ok) to false
            is TestOutcome.Http -> stringResource(R.string.guard_http_status, o.code) to true
            is TestOutcome.Failed -> errorText(o.error) to true
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun errorText(e: DeliveryError.Parsed): String = when (e) {
    DeliveryError.Parsed.NoConnection -> stringResource(R.string.guard_err_no_connection)
    DeliveryError.Parsed.Timeout -> stringResource(R.string.guard_err_timeout)
    DeliveryError.Parsed.BadAddress -> stringResource(R.string.guard_err_bad_address)
    DeliveryError.Parsed.Tls -> stringResource(R.string.guard_err_tls)
    DeliveryError.Parsed.Rejected -> stringResource(R.string.guard_err_rejected)
    is DeliveryError.Parsed.Http -> stringResource(R.string.guard_http_status, e.code)
    is DeliveryError.Parsed.Other -> stringResource(R.string.guard_err_other, e.name)
}

/**
 * "<label> [ 20 ]%". Digits only. The value is saved when the user presses Done or leaves the field, and only when it is
 * inside [range]; otherwise the box turns red with the allowed range and nothing is saved.
 */
@Composable
private fun PercentField(label: String, value: Int, range: IntRange, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val valid = PercentInput.isValid(text, range)
    val commit = { PercentInput.toCommit(text, range, value)?.let(onCommit); Unit }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label)
            val borderColor = if (valid) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
            BasicTextField(
                value = text,
                onValueChange = { new -> if (new.length <= 3 && new.all { it.isDigit() }) text = new },
                modifier = Modifier
                    .width(52.dp)
                    .height(36.dp)
                    .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                    .onFocusChanged { if (!it.isFocused) commit() },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                decorationBox = { inner -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { inner() } },
            )
            Text("%")
        }
        if (!valid) {
            Text(
                stringResource(R.string.guard_percent_error, range.first, range.last),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun IntervalPicker(label: String, value: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(stringResource(R.string.guard_minutes, value)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (m in GuardSettings.INTERVALS) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.guard_minutes, m)) },
                        onClick = { open = false; onSelect(m) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One string per PowerWords value: the same words the status report sends. */
private fun powerWordsRes(w: PowerWords): Int = when (w) {
    PowerWords.OnBattery -> R.string.guard_on_battery
    PowerWords.Charging -> R.string.guard_power_charging
    PowerWords.Full -> R.string.guard_power_full
    PowerWords.NotCharging -> R.string.guard_power_not_charging
    PowerWords.Discharging -> R.string.guard_power_discharging
    PowerWords.PluggedIn -> R.string.guard_plugged_in
}

@Composable
private fun StatusLineText(line: StatusLine) {
    val text = when (line) {
        StatusLine.NoCheckYet -> stringResource(R.string.guard_status_no_check)
        is StatusLine.LastCheck -> {
            val next = line.nextTime?.let { stringResource(R.string.guard_status_next_check, it) } ?: ""
            if (line.level == null) stringResource(R.string.guard_status_last_check_unreadable, line.time, next)
            else stringResource(
                R.string.guard_status_last_check, line.time, line.level,
                stringResource(powerWordsRes(line.power?.words ?: PowerWords.OnBattery)), next,
            )
        }
        is StatusLine.LastSwitch -> stringResource(
            R.string.guard_status_last_switch,
            stringResource(if (line.on) R.string.guard_switch_on else R.string.guard_switch_off), line.level, line.time,
        )
        StatusLine.NoAlerts -> stringResource(R.string.guard_alerts_none)
        is StatusLine.PlugReached -> when (line.on) {
            true -> stringResource(R.string.guard_plug_reached_on, line.time)
            false -> stringResource(R.string.guard_plug_reached_off, line.time)
            null -> stringResource(R.string.guard_plug_reached, line.time)
        }
        is StatusLine.PlugMissed -> stringResource(R.string.guard_plug_missed, line.sinceTime)
        is StatusLine.Alert -> when (line.kind) {
            AlertKind.NotCharging -> stringResource(R.string.guard_alert_not_charging, line.sinceTime, line.lastSentTime)
            AlertKind.PlugUnreachable -> stringResource(
                when (line.wantedOn) {
                    false -> R.string.guard_alert_unreachable_off
                    true -> R.string.guard_alert_unreachable_on
                    null -> R.string.guard_alert_unreachable_read      // started from a plug read (PLUG-WATCH.md)
                },
                line.sinceTime, line.lastSentTime,
            )
            AlertKind.GuardDegraded -> stringResource(
                R.string.guard_alert_degraded, line.detail ?: "", line.sinceTime, line.lastSentTime,
            )
            else -> stringResource(R.string.guard_alert_other, line.sinceTime, line.lastSentTime)
        }
        is StatusLine.DeliveryNone -> stringResource(R.string.guard_delivery_none) + waiting(line.waiting)
        is StatusLine.DeliveryOk -> stringResource(R.string.guard_delivery_ok, line.time, waiting(line.waiting))
        is StatusLine.DeliveryFailed -> {
            val err = line.error      // a DeliveryError token (shown as text), or null
            if (err.isNullOrBlank()) stringResource(R.string.guard_delivery_failed, line.time, waiting(line.waiting))
            else stringResource(R.string.guard_delivery_failed_reason, line.time, errorText(DeliveryError.parse(err)), waiting(line.waiting))
        }
        is StatusLine.RunError -> stringResource(R.string.guard_run_error, line.text)
        StatusLine.ExactAlarmWarning -> stringResource(R.string.guard_exact_alarm_warning)
    }
    val isProblem = line is StatusLine.DeliveryFailed || line is StatusLine.RunError ||
        line == StatusLine.ExactAlarmWarning || line is StatusLine.Alert
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun waiting(n: Int): String = if (n > 0) stringResource(R.string.guard_delivery_waiting, n) else ""
