package io.github.ammarqaisar11a55.relaysms

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSmsProcessor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Steps driven from the host by scripts/emulator-sms-check.sh, which injects SMS between steps
 * with the emulator console. Each step is skipped unless the script selects it, so the class is
 * inert in an ordinary test run.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorSmsScenario {
    private val args = InstrumentationRegistry.getArguments()
    private val container get() = ApplicationProvider.getApplicationContext<RelaySmsApp>().container

    private fun step(name: String) = assumeTrue(args.getString("step") == name)

    @Test fun configureSource(): Unit = runBlocking<Unit> {
        step("configure")
        container.resetCoordinator.resetEverything()
        container.preferences.setRole(DeviceRole.SOURCE)
        container.preferences.setForwardingEnabled(true)
        container.sourceStores.addFilter("TESTBANK", System.currentTimeMillis())
        Unit
    }

    @Test fun pauseForwarding(): Unit = runBlocking<Unit> {
        step("pause")
        container.preferences.setForwardingEnabled(false)
    }

    @Test fun assertState(): Unit = runBlocking<Unit> {
        step("assert")
        val stats = container.preferences.stats.first()
        val pending = container.database.outboxDao().nextPending(100)
        assertEquals("pending", args.getString("pending")!!.toInt(), pending.size)
        assertEquals("received", args.getString("received")!!.toInt(), stats.received)
        assertEquals("filtered", args.getString("filtered")!!.toInt(), stats.filtered)
        args.getString("lastBodyLength")?.let { expected ->
            val row = pending.last()
            val sms = Json.decodeFromString<IncomingSms>(
                String(container.localCipher.decrypt(row.payload!!, IncomingSmsProcessor.outboxContext(row.id)), Charsets.UTF_8),
            )
            assertEquals("body length", expected.toInt(), sms.body.length)
            assertEquals("sender", "TESTBANK", sms.sender)
        }
    }
}
