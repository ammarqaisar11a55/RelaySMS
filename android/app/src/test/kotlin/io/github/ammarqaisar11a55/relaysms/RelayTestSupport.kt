package io.github.ammarqaisar11a55.relaysms

import kotlinx.coroutines.CoroutineScope
import org.junit.Assume.assumeTrue

/** URL of a real relay started by scripts/with-relay.sh, or empty when not provided. */
val relayTestUrl: String get() = System.getProperty("relay.test.url").orEmpty()

fun assumeRelay() = assumeTrue("Set RELAY_TEST_URL to run relay integration tests", relayTestUrl.isNotEmpty())

suspend fun CoroutineScope.pair(source: TestPhone, companion: TestPhone): String = pair(source, companion, relayTestUrl)
