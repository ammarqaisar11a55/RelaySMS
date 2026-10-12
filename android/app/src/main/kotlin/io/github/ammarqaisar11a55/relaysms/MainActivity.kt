package io.github.ammarqaisar11a55.relaysms

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.ammarqaisar11a55.relaysms.notifications.MessageNotifier
import io.github.ammarqaisar11a55.relaysms.ui.navigation.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RelaySmsApp).container
        handleIntent(intent)
        setContent { AppRoot(container) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** A tapped notification may name the message to open. The id is only ever looked up locally. */
    private fun handleIntent(intent: Intent?) {
        val id = intent?.getStringExtra(MessageNotifier.EXTRA_OPEN_MESSAGE) ?: return
        intent.removeExtra(MessageNotifier.EXTRA_OPEN_MESSAGE)
        if (id.length <= 64) (application as RelaySmsApp).container.openMessageRequests.value = id
    }
}
