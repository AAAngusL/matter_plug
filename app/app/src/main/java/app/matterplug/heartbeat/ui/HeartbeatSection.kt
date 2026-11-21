package app.matterplug.heartbeat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.matterplug.R
import app.matterplug.fixedip.IpSms
import app.matterplug.heartbeat.logic.HeartbeatSettings

/** The autodialer heartbeat: a switch and the number of missed checks, under the alert address in the Battery Guard tab. */
@Composable
fun HeartbeatSection(modifier: Modifier = Modifier, viewModel: HeartbeatViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hasNumber by viewModel.hasNumber.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var smsDenied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        smsDenied = !granted
        if (granted) viewModel.setEnabled(true)
    }
    val s = settings ?: return

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.heartbeat_switch), modifier = Modifier.weight(1f))
            Switch(checked = s.enabled, onCheckedChange = { on ->
                when {
                    !on -> viewModel.setEnabled(false)
                    !hasNumber -> smsDenied = false
                    IpSms.canSend(context) -> viewModel.setEnabled(true)
                    else -> launcher.launch(Manifest.permission.SEND_SMS)
                }
            })
        }
        Text(stringResource(R.string.heartbeat_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!hasNumber && !s.enabled) Text(stringResource(R.string.heartbeat_need_number), style = MaterialTheme.typography.bodySmall)
        if (smsDenied) Text(stringResource(R.string.heartbeat_sms_denied), color = MaterialTheme.colorScheme.error)

        var open by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.heartbeat_limit), modifier = Modifier.weight(1f))
            Box {
                OutlinedButton(onClick = { open = true }) { Text(s.limit.toString()) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (n in HeartbeatSettings.LIMITS) {
                        DropdownMenuItem(text = { Text(n.toString()) }, onClick = { open = false; viewModel.setLimit(n) })
                    }
                }
            }
        }
    }
}
