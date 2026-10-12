package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Command-line "other phone" for device reliability checks (scripts/emulator-reliability-check.sh).
 * It runs outside the emulator, so the app under test can be locked, killed or rebooted while its
 * peer keeps working. State comes from a file written by the ReliabilityScenario setup step.
 *
 *   java -cp <unit test classpath> io.github.ammarqaisar11a55.relaysms.PeerToolKt <state.json> sync
 *   ... <state.json> send <sender> <text>
 *   ... <state.json> awaiting
 */
fun main(args: Array<String>) {
    val code = runBlocking {
        // The saved address is the relay as seen from the emulator; this tool runs on the host.
        val phone = restoreTestPhone(File(args[0]).readText()) { it.replace("10.0.2.2", "127.0.0.1") }
        when (args.getOrNull(1)) {
            "sync" -> {
                println("OUTCOME ${phone.sync.syncOnce()}")
                for (m in phone.received.values) println("MSG ${m.sender}|${m.partCount}|${m.body}")
            }
            "send" -> println("SEND ${phone.forward(IncomingSms(args[2], args[3], System.currentTimeMillis(), 1))}")
            "awaiting" -> phone.link.refresh().let { println("AWAITING ${it.awaitingPeer} ${it.state} online=${it.peerOnline}") }
            else -> {
                System.err.println("usage: PeerTool <state.json> sync|send <sender> <text>|awaiting")
                return@runBlocking 2
            }
        }
        0
    }
    exitProcess(code)
}
