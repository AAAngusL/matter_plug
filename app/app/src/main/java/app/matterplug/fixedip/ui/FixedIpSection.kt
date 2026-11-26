package app.matterplug.fixedip.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.matterplug.R
import app.matterplug.fixedip.logic.DriftReason
import app.matterplug.fixedip.logic.FixedIpSettings
import app.matterplug.fixedip.logic.IpFormat
import app.matterplug.fixedip.logic.IpState
import app.matterplug.sms.AlertSms
import app.matterplug.ui.LabeledSwitch
import app.matterplug.ui.LastSmsLine
import app.matterplug.ui.rememberSmsPermissionRequest
import app.matterplug.fixedip.logic.PinnedNetwork
import java.util.Date

/** Fixed IP, the third tab under the plug switch (docs/FIXED-IP.md section 10). */
@Composable
fun FixedIpSection(modifier: Modifier = Modifier, viewModel: FixedIpViewModel = viewModel()) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    val guide by viewModel.guide.collectAsStateWithLifecycle()
    val smsText by viewModel.smsText.collectAsStateWithLifecycle()
    val backupText by viewModel.backupText.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.lock(true) else viewModel.permissionDenied()
    }
    val lastSms by viewModel.lastSms.collectAsStateWithLifecycle()
    var smsDenied by remember { mutableStateOf(false) }
    // The notification permission is asked for too (Android 13+), but only SMS is needed: without it the watch cannot alert.
    val askSms = rememberSmsPermissionRequest(if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()) { sms ->
        smsDenied = !sms
        if (sms) viewModel.setEnabled(true)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val s = screen ?: return@Column
        val cfg = settings ?: return@Column
        val r = s.reading

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stateText(s.state), color = stateColor(s.state), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = viewModel::checkNow) { Text(stringResource(R.string.fixedip_check_now)) }
        }
        Text(stringResource(R.string.fixedip_read_at, timeFormat.format(Date(s.readAtMs))), style = MaterialTheme.typography.bodySmall)

        val none = stringResource(R.string.fixedip_none)
        Line(R.string.fixedip_address, r.address?.let { IpFormat.addressWithPrefix(it, r.prefix) } ?: none)
        Line(R.string.fixedip_gateway, r.gateway ?: none)
        Line(R.string.fixedip_dns, r.dns.joinToString(", ").ifEmpty { none })
        Line(R.string.fixedip_network, r.ssid ?: stringResource(if (r.wifi) R.string.fixedip_ssid_not_allowed else R.string.fixedip_none))
        Line(R.string.fixedip_mac, r.mac ?: cfg.pinned?.mac?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.fixedip_mac_unreadable))
        if (s.macChanged) Text(stringResource(R.string.fixedip_mac_changed), color = MaterialTheme.colorScheme.error)

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        val pinned = cfg.pinned
        if (pinned == null) {
            OutlinedButton(onClick = {
                if (hasLocation(context)) viewModel.lock(true) else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }) { Text(stringResource(R.string.fixedip_lock)) }
            problem?.let { Text(stringResource(problemText(it)), color = MaterialTheme.colorScheme.error) }
        } else {
            Line(R.string.fixedip_pinned, "${pinned.address} · ${pinned.ssid}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::showGuide) { Text(stringResource(R.string.fixedip_show_guide)) }
                OutlinedButton(onClick = viewModel::unpin) { Text(stringResource(R.string.fixedip_unpin)) }
            }
        }
        OutlinedButton(onClick = { openWifiSettings(context) }) { Text(stringResource(R.string.fixedip_reconnect)) }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        LabeledSwitch(stringResource(R.string.fixedip_switch), cfg.enabled) { on ->
            when {
                !on -> viewModel.setEnabled(false)
                !cfg.canEnable -> smsDenied = false
                AlertSms.canSend(context) -> viewModel.setEnabled(true)
                else -> askSms()
            }
        }
        if (!cfg.canEnable && !cfg.enabled) Text(stringResource(R.string.fixedip_need_setup), style = MaterialTheme.typography.bodySmall)
        if (smsDenied) Text(stringResource(R.string.fixedip_sms_denied), color = MaterialTheme.colorScheme.error)
        LastSmsLine(lastSms)

        OutlinedTextField(
            value = smsText, onValueChange = viewModel::setSmsNumber, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.fixedip_sms_number)) },
            isError = smsText.isNotEmpty() && !FixedIpSettings.isValidNumber(smsText),
            supportingText = { if (smsText.isNotEmpty() && !FixedIpSettings.isValidNumber(smsText)) Text(stringResource(R.string.fixedip_number_error)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        OutlinedTextField(
            value = backupText, onValueChange = viewModel::setBackupNumber, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.fixedip_backup_number)) },
            isError = backupText.isNotEmpty() && !FixedIpSettings.isValidNumber(backupText),
            supportingText = {
                Text(stringResource(if (backupText.isNotEmpty() && !FixedIpSettings.isValidNumber(backupText)) R.string.fixedip_number_error
                    else R.string.fixedip_backup_hint))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )

        if (guide && pinned != null) GuideDialog(pinned, onMac = viewModel::setMac, onClose = viewModel::hideGuide)
    }
}

/** The guide of docs/FIXED-IP.md section 5: the four steps, each value with a copy button. The app does not check the router. */
@Composable
private fun GuideDialog(pinned: PinnedNetwork, onMac: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var macText by remember(pinned.mac) { mutableStateOf(pinned.mac) }
    val macOk = IpFormat.mac(macText) != null
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.fixedip_guide_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.fixedip_guide_step1))
                OutlinedButton(onClick = { openWifiSettings(context) }) { Text(stringResource(R.string.fixedip_guide_open_wifi)) }
                Text(stringResource(R.string.fixedip_guide_step2))
                Text(stringResource(R.string.fixedip_guide_step3))
                OutlinedTextField(
                    value = macText, onValueChange = { macText = it; onMac(it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.fixedip_mac)) },
                    isError = macText.isNotEmpty() && !macOk,
                    supportingText = { Text(stringResource(if (macText.isNotEmpty() && !macOk) R.string.fixedip_guide_mac_error else R.string.fixedip_guide_mac_hint)) },
                )
                CopyRow(R.string.fixedip_guide_mac_value, IpFormat.mac(macText).orEmpty().ifEmpty { null })
                CopyRow(R.string.fixedip_guide_ip_value, pinned.address)
                Text(stringResource(R.string.fixedip_guide_step4))
                Text(stringResource(R.string.fixedip_guide_no_check), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.ok)) } },
    )
}

@Composable
private fun CopyRow(label: Int, value: String?) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
            Text(value ?: stringResource(R.string.fixedip_none))
        }
        if (value != null) TextButton(onClick = { copy(context, value) }) { Text(stringResource(R.string.fixedip_copy)) }
    }
}

@Composable
private fun Line(label: Int, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(label), modifier = Modifier.width(120.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f))
    }
}

private fun hasLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** The app cannot turn Wi-Fi off and on itself; this opens Android's Wi-Fi settings (section 10). */
private fun openWifiSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Fixed IP", text))
}

private fun problemText(p: LockProblem): Int = when (p) {
    LockProblem.PermissionDenied -> R.string.fixedip_problem_permission
    LockProblem.NoWifi -> R.string.fixedip_problem_no_wifi
    LockProblem.NoAddress -> R.string.fixedip_problem_no_address
    LockProblem.NoSsid -> R.string.fixedip_problem_no_ssid
}

@Composable
private fun stateText(state: IpState): String = when (state) {
    IpState.NotPinned -> stringResource(R.string.fixedip_state_not_pinned)
    IpState.Match -> stringResource(R.string.fixedip_state_match)
    is IpState.Drift -> stringResource(
        if (state.reason == DriftReason.GatewayChanged) R.string.fixedip_state_gateway_changed else R.string.fixedip_state_address_changed)
    IpState.OtherNetwork -> stringResource(R.string.fixedip_state_other_network)
    is IpState.NoWifi -> stringResource(R.string.fixedip_state_no_wifi)
}

@Composable
private fun stateColor(state: IpState): Color = when (state) {
    is IpState.Drift, is IpState.NoWifi -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}
