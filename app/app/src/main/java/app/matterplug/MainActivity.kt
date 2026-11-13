package app.matterplug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.matterplug.data.Plug
import app.matterplug.data.PlugRepository
import app.matterplug.data.resolve
import kotlinx.coroutines.launch
import app.matterplug.matter.ChipController
import app.matterplug.pairing.PairingScreen
import app.matterplug.plug.PlugScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // M1 check: the Matter library must load without an UnsatisfiedLinkError.
        try {
            Log.i(TAG, "Matter controller: ${ChipController.get(applicationContext)}")
        } catch (t: Throwable) {
            Log.e(TAG, "Matter library failed to load", t)
        }

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) { AppRoot() }
                }
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}

/** Empty list shows pairing; otherwise the selected plug, with a switcher to change plug or add another. */
@Composable
private fun AppRoot() {
    val context = LocalContext.current.applicationContext
    val repository = remember { PlugRepository(context) }
    val scope = rememberCoroutineScope()
    val plugs: List<Plug>? by repository.plugs.collectAsStateWithLifecycle(initialValue = null)
    val selectedId: Long? by repository.selectedNodeId.collectAsStateWithLifecycle(initialValue = null)
    val chargerId: Long? by repository.chargerNodeId.collectAsStateWithLifecycle(initialValue = null)
    var showUnreachableNotice by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }

    val list = plugs
    when {
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        list.isEmpty() -> PairingScreen()
        adding -> PairingScreen(onExit = { adding = false })
        else -> {
            val plug = list.resolve(selectedId)!!
            PlugScreen(
                plug = plug,
                plugs = list,
                isCharger = plug.nodeId == list.resolve(chargerId)!!.nodeId,
                onSelect = { scope.launch { repository.select(it.nodeId) } },
                onAdd = { adding = true },
                onUseAsCharger = { scope.launch { repository.setCharger(plug.nodeId) } },
                onRemoved = { unreachable -> showUnreachableNotice = unreachable }
            )
        }
    }

    if (showUnreachableNotice) {
        AlertDialog(
            onDismissRequest = { showUnreachableNotice = false },
            text = { Text(stringResource(R.string.plug_removed_unreachable)) },
            confirmButton = { TextButton(onClick = { showUnreachableNotice = false }) { Text(stringResource(R.string.ok)) } }
        )
    }
}
