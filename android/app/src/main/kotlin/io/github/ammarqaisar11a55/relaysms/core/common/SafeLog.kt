package io.github.ammarqaisar11a55.relaysms.core.common

import android.util.Log

/**
 * The only logging entry point in the app. Callers pass a fixed event name and, optionally, a
 * short machine code or number. Message bodies, senders, codes, keys and tokens must never be
 * passed here; the API takes no free-form objects and no Throwable messages to make that hard to
 * do by accident.
 */
object SafeLog {
    private const val TAG = "RelaySMS"

    fun event(name: String) {
        Log.i(TAG, name)
    }

    fun event(name: String, code: Enum<*>) {
        Log.i(TAG, "$name:${code.name}")
    }

    fun event(name: String, count: Int) {
        Log.i(TAG, "$name:$count")
    }

    /** Logs only the exception class, never its message (messages can embed user data). */
    fun failure(name: String, error: Throwable) {
        Log.w(TAG, "$name:${error.javaClass.simpleName}")
    }
}
