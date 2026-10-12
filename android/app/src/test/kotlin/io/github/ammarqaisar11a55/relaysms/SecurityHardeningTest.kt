package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.network.ApiResult
import io.github.ammarqaisar11a55.relaysms.core.network.RelayApi
import io.github.ammarqaisar11a55.relaysms.delivery.SendResult
import io.github.ammarqaisar11a55.relaysms.pairing.CredentialRotator
import io.github.ammarqaisar11a55.relaysms.pairing.LinkState
import io.github.ammarqaisar11a55.relaysms.pairing.RotationResult
import io.github.ammarqaisar11a55.relaysms.security.AppLock
import io.github.ammarqaisar11a55.relaysms.security.WindowSecurity
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import io.github.ammarqaisar11a55.relaysms.transport.SyncOutcome
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SecurityHardeningTest {
    @Test fun `app lock closes whenever the app leaves the screen`() {
        val lock = AppLock()
        assertTrue("locked at process start", lock.locked.value)
        lock.unlock()
        assertFalse(lock.locked.value)
        lock.onBackground()
        assertTrue(lock.locked.value)
    }

    @Test fun `window stays protected while the setting or any secret screen asks for it`() {
        val applied = mutableListOf<Boolean>()
        val w = WindowSecurity { applied += it }
        w.setSetting(false)
        assertEquals(false, applied.last())
        w.acquire()                 // pairing screen opens
        assertEquals(true, applied.last())
        w.acquire()
        w.release()
        assertEquals("still one secret screen open", true, applied.last())
        w.release()
        assertEquals(false, applied.last())
        w.release()                 // unbalanced release must not go negative
        w.acquire()
        assertEquals(true, applied.last())
        w.release()
        w.setSetting(true)
        assertEquals(true, applied.last())
    }

    @Test fun `the only logging entry point takes no free-form data`() {
        // SafeLog must keep accepting only fixed names, enums, counts and exception classes.
        val source = File("src/main/kotlin/io/github/ammarqaisar11a55/relaysms/core/common/SafeLog.kt").readText()
        val signatures = Regex("fun (\\w+)\\(([^)]*)\\)").findAll(source).map { it.groupValues[2] }.toList()
        assertEquals(listOf("name: String", "name: String, code: Enum<*>", "name: String, count: Int", "name: String, error: Throwable"), signatures)
        assertFalse("exception messages can contain user data", source.contains("error.message"))
        // And nothing else in the app writes to the log directly.
        val offenders = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.name != "SafeLog.kt" }
            .filter { f -> Regex("\\bLog\\.[vdiwe]\\(|println\\(|printStackTrace\\(").containsMatchIn(f.readText()) }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    // ---- credential rotation, against a real relay ----

    private val sms = IncomingSms("TESTBANK", "Synthetic rotation message", 1_790_000_500_000, 1)

    @Test fun `credential rotation keeps the pairing working and retires the old credential`() = runBlocking<Unit> {
        assumeRelay()
        val source = TestPhone(DeviceRole.SOURCE); val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)
        val before = companion.store.identity.value!!
        val rotator = CredentialRotator(companion.store, companion.apiFactory)
        assertEquals(RotationResult.NOT_DUE, rotator.rotateIfDue())
        assertEquals(RotationResult.ROTATED, rotator.rotateIfDue(force = true))
        val after = companion.store.identity.value!!
        assertNotEquals(before.token, after.token)
        assertEquals(before.deviceId, after.deviceId)
        assertTrue(after.tokenIssuedAtMillis >= before.registeredAtMillis)
        // The old credential was retired by the confirming request.
        assertEquals(401, (RelayApi(companion.client, before.serverUrl, before.token).me() as ApiResult.Error).status)
        // Everything keeps working with the new one.
        assertEquals(SendResult.ACCEPTED, source.forward(sms))
        assertEquals(SyncOutcome.COMPLETE, companion.sync.syncOnce())
        assertEquals(listOf(sms), companion.received.values.toList())
        assertEquals(LinkState.PAIRED, companion.link.refresh().state)
    }

    @Test fun `a rotation whose answer is lost does not lock the device out`() = runBlocking<Unit> {
        assumeRelay()
        val dropAnswer = AtomicInteger(1)
        // The relay processes the rotation, but the response never reaches the phone.
        val lossy = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().url.encodedPath.endsWith("/devices/me/token") && dropAnswer.getAndDecrement() > 0) {
                response.close()
                throw IOException("connection reset")
            }
            response
        }
        val source = TestPhone(DeviceRole.SOURCE)
        val companion = TestPhone(DeviceRole.COMPANION, RelayApi.newHttpClient().newBuilder().addInterceptor(lossy).retryOnConnectionFailure(false).build())
        pair(source, companion)
        val before = companion.store.identity.value!!
        val rotator = CredentialRotator(companion.store, companion.apiFactory)
        assertEquals(RotationResult.FAILED, rotator.rotateIfDue(force = true))
        assertEquals("the phone keeps the credential it had", before.token, companion.store.identity.value!!.token)
        // The old credential still works, so the device is not treated as revoked.
        assertEquals(LinkState.PAIRED, companion.link.refresh().state)
        source.forward(sms)
        assertEquals(SyncOutcome.COMPLETE, companion.sync.syncOnce())
        assertEquals(1, companion.received.size)
        // A later rotation succeeds normally.
        assertEquals(RotationResult.ROTATED, rotator.rotateIfDue(force = true))
        assertEquals(LinkState.PAIRED, companion.link.refresh().state)
    }

    @Test fun `rotation becomes due after the configured age and never runs unregistered`() = runBlocking<Unit> {
        assumeRelay()
        val lone = TestPhone(DeviceRole.COMPANION)
        assertEquals(RotationResult.NOT_REGISTERED, CredentialRotator(lone.store, lone.apiFactory).rotateIfDue(force = true))
        val source = TestPhone(DeviceRole.SOURCE); val companion = TestPhone(DeviceRole.COMPANION)
        pair(source, companion)
        var clock = System.currentTimeMillis()
        val rotator = CredentialRotator(companion.store, companion.apiFactory, now = { clock }, maxAgeMillis = 1000)
        assertEquals(RotationResult.NOT_DUE, rotator.rotateIfDue())
        clock += 60_000
        assertEquals(RotationResult.ROTATED, rotator.rotateIfDue())
        assertEquals(RotationResult.NOT_DUE, rotator.rotateIfDue())
    }
}
