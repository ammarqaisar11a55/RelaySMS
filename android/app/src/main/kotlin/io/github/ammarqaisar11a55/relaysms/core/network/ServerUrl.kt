package io.github.ammarqaisar11a55.relaysms.core.network

import java.net.URI
import java.net.URISyntaxException

/**
 * Validates and normalises a relay server address.
 *
 * HTTPS is mandatory. Plain HTTP is accepted only when [allowCleartextLocal] is true (debug
 * builds) and the host is a local development address.
 */
object ServerUrl {
    sealed interface Result {
        data class Valid(val url: String) : Result
        data class Invalid(val reason: String) : Result
    }

    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun parse(input: String, allowCleartextLocal: Boolean): Result {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return Result.Invalid("Enter the address of your relay server.")
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return Result.Invalid("That is not a valid address.")
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (scheme == null || host.isNullOrEmpty()) {
            return Result.Invalid("Include the full address, for example https://relay.example.com")
        }
        if (uri.userInfo != null) return Result.Invalid("The address must not contain a user name or password.")
        if (uri.rawQuery != null || uri.rawFragment != null) {
            return Result.Invalid("The address must not contain a query or fragment.")
        }
        when (scheme) {
            "https" -> Unit
            "http" -> if (!(allowCleartextLocal && host in LOCAL_HOSTS)) {
                return Result.Invalid("The relay server must use https://")
            }
            else -> return Result.Invalid("The address must start with https://")
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return Result.Valid("$scheme://$host$port$path")
    }

    /** Converts a validated base URL to its WebSocket equivalent. */
    fun toWebSocketUrl(baseUrl: String, path: String): String {
        val ws = when {
            baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
            baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
            else -> throw IllegalArgumentException("Unsupported scheme")
        }
        return ws + path
    }
}
