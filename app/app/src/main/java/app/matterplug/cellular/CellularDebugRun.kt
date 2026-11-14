package app.matterplug.cellular

import android.Manifest
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import app.matterplug.cellular.logic.CellularVerdict
import app.matterplug.cellular.logic.CellularLog

/**
 * C1 only (docs/CELLULAR-CHECK.md section 12): in a debug build, logs one passive run under the tag "Cellular" when the
 * app opens, asking for the phone permission first. Replaced by the alarm and the Cellular Check screen in C2.
 */
@Composable
fun CellularDebugRun() {
    val context = LocalContext.current.applicationContext
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    val log = { Log.i("Cellular", CellularLog.line(CellularVerdict.passive(CellularReader.read(context)))) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { log() }
    LaunchedEffect(Unit) {
        if (CellularReader.hasPermission(context)) log() else launcher.launch(Manifest.permission.READ_PHONE_STATE)
    }
}
