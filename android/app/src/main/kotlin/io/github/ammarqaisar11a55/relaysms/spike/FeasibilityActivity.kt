package io.github.ammarqaisar11a55.relaysms.spike

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

/** Phase Zero screen: request RECEIVE_SMS with consent and show the non-sensitive spike result. */
class FeasibilityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SpikeResultStore(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var granted by remember { mutableStateOf(hasSmsPermission()) }
                    var refresh by remember { mutableIntStateOf(0) }
                    val launcher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { granted = it }
                    Column(
                        Modifier.safeDrawingPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("RelaySMS feasibility check", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "This build only checks whether this phone lets RelaySMS observe incoming " +
                                "SMS events. It records a counter and a time. It never stores or shows " +
                                "the sender or the text of any message.",
                        )
                        Text("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                        Text("RECEIVE_SMS permission: " + if (granted) "GRANTED" else "NOT GRANTED")
                        Button(onClick = { launcher.launch(Manifest.permission.RECEIVE_SMS) }, enabled = !granted) {
                            Text("Allow RelaySMS to receive SMS")
                        }
                        @Suppress("UNUSED_EXPRESSION") refresh
                        Text("SMS events detected: ${store.eventCount}")
                        if (store.lastEventAtMillis > 0) {
                            Text(
                                "Last event: " + DateFormat.getDateTimeInstance().format(Date(store.lastEventAtMillis)) +
                                    " (${store.lastPartCount} part(s))",
                            )
                        }
                        OutlinedButton(onClick = { granted = hasSmsPermission(); refresh++ }) { Text("Refresh") }
                        OutlinedButton(onClick = { store.reset(); refresh++ }) { Text("Reset counter") }
                    }
                }
            }
        }
    }

    private fun hasSmsPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
}
