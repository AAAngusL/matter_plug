package app.matterplug.ui

import android.Manifest
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.matterplug.R
import app.matterplug.sms.AlertSms
import app.matterplug.sms.SmsOutcome
import app.matterplug.sms.SmsResult
import java.util.Date

/** A label and its switch on one line: what Battery Guard, Fixed IP and the console heartbeat all use for an on/off setting. */
@Composable
fun LabeledSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** "Last SMS: sent at 11:55 PM", or "failed at … (reason)" in the error colour; nothing before the first SMS. */
@Composable
fun LastSmsLine(last: SmsResult.Last?) {
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    last ?: return
    val ok = last.outcome == SmsOutcome.Sent
    Text(
        stringResource(if (ok) R.string.sms_last_sent else R.string.sms_last_failed, timeFormat.format(Date(last.atMs)), last.detail.orEmpty()),
        style = MaterialTheme.typography.bodySmall,
        color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
    )
}

/**
 * Returns a function that asks for the SEND_SMS permission (and [alsoAsk], which the caller does not need to be granted) and then calls
 * [onResult] with whether SMS may be sent. For a switch that turns on a feature that alerts by SMS.
 */
@Composable
fun rememberSmsPermissionRequest(alsoAsk: List<String> = emptyList(), onResult: (smsGranted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        onResult(result[Manifest.permission.SEND_SMS] ?: AlertSms.canSend(context))
    }
    return { launcher.launch((listOf(Manifest.permission.SEND_SMS) + alsoAsk).toTypedArray()) }
}
