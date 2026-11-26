package app.matterplug.alerts.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.matterplug.R
import app.matterplug.alerts.logic.AlertAddress
import app.matterplug.alerts.logic.DeliveryError
import app.matterplug.alerts.logic.TestOutcome
import app.matterplug.alerts.logic.tokenOverPlainHttp

/** The address and token fields of the alert server, with the warning for a token that would travel over plain http. */
@Composable
fun AlertEndpointFields(editor: AlertEndpointEditor) {
    val address by editor.addressText.collectAsStateWithLifecycle()
    val tokenText by editor.tokenText.collectAsStateWithLifecycle()
    val addressError by editor.addressError.collectAsStateWithLifecycle()
    OutlinedTextField(
        value = address,
        onValueChange = editor::setAddress,
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
        onValueChange = editor::setToken,
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
}

/** "Send test alert": needs a valid address only, so it works with "Send alerts" off. [onSend] starts the test (the host knows the last battery). */
@Composable
fun TestAlertButton(editor: AlertEndpointEditor, onSend: () -> Unit) {
    val address by editor.addressText.collectAsStateWithLifecycle()
    val testState by editor.testState.collectAsStateWithLifecycle()
    OutlinedButton(onClick = onSend, enabled = AlertAddress.isValid(address) && testState != TestState.Sending) {
        Text(stringResource(R.string.guard_send_test))
    }
}

/** "200 OK", the status code, or the error, next to the test button. */
@Composable
fun TestAlertResult(editor: AlertEndpointEditor) {
    val state = editor.testState.collectAsStateWithLifecycle().value
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
fun errorText(e: DeliveryError.Parsed): String = when (e) {
    DeliveryError.Parsed.NoConnection -> stringResource(R.string.guard_err_no_connection)
    DeliveryError.Parsed.Timeout -> stringResource(R.string.guard_err_timeout)
    DeliveryError.Parsed.BadAddress -> stringResource(R.string.guard_err_bad_address)
    DeliveryError.Parsed.Tls -> stringResource(R.string.guard_err_tls)
    DeliveryError.Parsed.Rejected -> stringResource(R.string.guard_err_rejected)
    is DeliveryError.Parsed.Http -> stringResource(R.string.guard_http_status, e.code)
    is DeliveryError.Parsed.Other -> stringResource(R.string.guard_err_other, e.name)
}

