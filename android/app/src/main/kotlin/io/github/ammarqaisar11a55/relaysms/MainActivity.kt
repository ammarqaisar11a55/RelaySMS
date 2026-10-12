package io.github.ammarqaisar11a55.relaysms

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.github.ammarqaisar11a55.relaysms.security.WindowSecurity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.ammarqaisar11a55.relaysms.notifications.MessageNotifier
import io.github.ammarqaisar11a55.relaysms.ui.navigation.AppRoot

class MainActivity : FragmentActivity() {
    /** Screenshot protection for this window; see [WindowSecurity]. */
    val windowSecurity = WindowSecurity { secure ->
        runOnUiThread {
            if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RelaySmsApp).container
        // Protected until the stored preference says otherwise, so nothing flashes unprotected.
        windowSecurity.setSetting(true)
        lifecycleScope.launch {
            container.preferences.settings.map { it.blockScreenshots }.distinctUntilChanged().collect(windowSecurity::setSetting)
        }
        handleIntent(intent)
        setContent { AppRoot(container) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Test hook: delivers an intent exactly as Android does for a running activity. */
    @androidx.annotation.VisibleForTesting
    fun onNewIntentForTest(intent: Intent) = onNewIntent(intent)

    /** A tapped notification may name the message to open. The id is only ever looked up locally. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SENDTO) {
            // sms:0300123?body=hi — untrusted input; the compose screen validates before anything is sent.
            val to = intent.data?.schemeSpecificPart?.substringBefore('?').orEmpty().take(40)
            val body = (intent.getStringExtra("sms_body") ?: intent.data?.query?.substringAfter("body=", "")?.let(android.net.Uri::decode)).orEmpty().take(1000)
            (application as RelaySmsApp).container.composeRequests.value = to to body
            return
        }
        val id = intent?.getStringExtra(MessageNotifier.EXTRA_OPEN_MESSAGE) ?: return
        intent.removeExtra(MessageNotifier.EXTRA_OPEN_MESSAGE)
        if (id.length <= 64) (application as RelaySmsApp).container.openMessageRequests.value = id
    }
}
