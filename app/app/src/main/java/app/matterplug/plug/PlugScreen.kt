package app.matterplug.plug

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.matterplug.R
import app.matterplug.data.Plug
import app.matterplug.guard.ui.GuardSection
import app.matterplug.matter.PlugState

/**
 * The plug screen (spec 7.2). Talks to the plug only between ON_START and ON_STOP.
 * The title is the plug switcher: it lists every plug and ends with "Add plug".
 * Battery Guard belongs to the Charger plug, so its settings show only on that plug; other plugs offer to take it over.
 * [onRemoved] gets true when the plug could not be reached while removing it.
 */
@Composable
fun PlugScreen(
    plug: Plug,
    plugs: List<Plug>,
    isCharger: Boolean,
    onSelect: (Plug) -> Unit,
    onAdd: () -> Unit,
    onUseAsCharger: () -> Unit,
    onRemoved: (unreachable: Boolean) -> Unit
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel: PlugViewModel = viewModel(
        key = "plug-${plug.nodeId}",
        factory = PlugViewModel.Factory(application, plug)
    )
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onScreenStart()
                Lifecycle.Event.ON_STOP -> viewModel.onScreenStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenStop()
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var switcherOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { switcherOpen = true }) {
                    Text(
                        plug.name + " ▾",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DropdownMenu(expanded = switcherOpen, onDismissRequest = { switcherOpen = false }) {
                    plugs.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(if (p.nodeId == plug.nodeId) "✓  ${p.name}" else "     ${p.name}") },
                            onClick = { switcherOpen = false; if (p.nodeId != plug.nodeId) onSelect(p) }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("＋  " + stringResource(R.string.switcher_add)) },
                        onClick = { switcherOpen = false; onAdd() }
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_rename)) },
                        onClick = { menuOpen = false; renaming = true }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_remove)) },
                        onClick = { menuOpen = false; viewModel.remove(onRemoved) }
                    )
                }
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),   // G2: scrolls so the Battery Guard section fits
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val pending = ui.pending
            when (val s = ui.plug) {
                PlugState.Connecting -> SwitchRow(
                    checked = false, enabled = false, onChange = null,
                    status = stringResource(R.string.plug_connecting), busy = false
                )
                is PlugState.Online -> {
                    val text = when (pending) {
                        null -> if (s.isOn) R.string.plug_on else R.string.plug_off
                        true -> R.string.plug_turning_on
                        false -> R.string.plug_turning_off
                    }
                    SwitchRow(
                        checked = s.isOn, enabled = pending == null, onChange = { viewModel.toggle() },
                        status = stringResource(text), busy = pending != null
                    )
                }
                PlugState.Offline -> StatusText(stringResource(R.string.plug_offline))   // no toggle, no position
            }
            message?.let {
                val text = when (it) { PlugMessage.NotResponding -> R.string.err_not_responding }
                Text(
                    stringResource(text),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            if (isCharger) {
                GuardSection(Modifier.padding(top = 24.dp))
            } else {
                OutlinedButton(onClick = onUseAsCharger, modifier = Modifier.padding(top = 24.dp)) {
                    Text(stringResource(R.string.guard_use_this_plug))
                }
            }
        }
    }

    if (renaming) RenameDialog(plug.name, onDismiss = { renaming = false }) {
        viewModel.rename(it)
        renaming = false
    }
}

/** The switch with its status word ("On", "Off", "Turning on…") on the right, in one row. */
@Composable
private fun SwitchRow(checked: Boolean, enabled: Boolean, onChange: (() -> Unit)?, status: String, busy: Boolean) {
    Row(
        Modifier.padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Switch(checked = checked, onCheckedChange = onChange?.let { f -> { _: Boolean -> f() } }, enabled = enabled)
        Text(status, style = MaterialTheme.typography.titleLarge)
        if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
