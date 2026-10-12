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
 *   ... <state.json> request-send <to> <text>   (as companion: ask the app, as source, to send an SMS)
 *   ... <state.json> pair-source <file with the pairing text shown by the app>
 */
fun main(args: Array<String>) {
    val code = runBlocking {
        // The saved address is the relay as seen from the emulator; this tool runs on the host.
        if (args.getOrNull(1) == "pair-source") return@runBlocking pairAsSource(File(args[0]), File(args[2]).readText().trim())
        val phone = restoreTestPhone(File(args[0]).readText()) { it.replace("10.0.2.2", "127.0.0.1") }
        when (args.getOrNull(1)) {
            "sync" -> {
                println("OUTCOME ${phone.sync.syncOnce()}")
                for (m in phone.received.values) println("MSG ${m.sender}|${m.partCount}|${m.body}")
                for (r in phone.sendResults) println("RESULT ${r.requestId} ${r.status} ${r.reason ?: "-"}")
            }
            "send" -> println("SEND ${phone.forward(IncomingSms(args[2], args[3], System.currentTimeMillis(), 1))}")
            "request-send" -> companionRequest(phone, args[2], args[3]).let { println("REQUEST ${it.first} ${it.second}") }
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

/**
 * Pairs as a new source phone with an app that is showing a pairing code, prints the comparison
 * code, confirms, waits for the app's confirmation and saves this phone's state.
 */
private suspend fun pairAsSource(stateFile: File, pairingText: String): Int {
    val phone = TestPhone(io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole.SOURCE)
    // The code names the relay as the emulator sees it; this tool reaches it on the host.
    val payload = io.github.ammarqaisar11a55.relaysms.pairing.PairingProtocol.decodeQr(pairingText, true)
        ?: return 3.also { System.err.println("not a pairing code") }
    val local = io.github.ammarqaisar11a55.relaysms.pairing.PairingProtocol.encodeQr(payload.copy(serverUrl = payload.serverUrl.replace("10.0.2.2", "127.0.0.1")))
    phone.pairing.reviewScanned(local)
    phone.pairing.redeemAsSource(null)
    val verify = phone.pairing.state.value as? io.github.ammarqaisar11a55.relaysms.pairing.PairingState.Verify
        ?: return 4.also { System.err.println("redeem failed: ${phone.pairing.state.value}") }
    println("CODE ${verify.safetyCode}")
    System.out.flush()
    phone.pairing.confirm()
    if (phone.pairing.state.value !is io.github.ammarqaisar11a55.relaysms.pairing.PairingState.Completed) {
        System.err.println("pairing did not complete: ${phone.pairing.state.value}")
        return 5
    }
    stateFile.writeText(phone.exportState())
    println("PAIRED")
    return 0
}

private suspend fun companionRequest(phone: TestPhone, to: String, text: String) = phone.requestSend(to, text)
