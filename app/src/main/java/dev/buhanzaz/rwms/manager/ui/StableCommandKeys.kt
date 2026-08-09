package dev.buhanzaz.rwms.manager.ui

import java.util.UUID

/**
 * Defines manager UI or local cache state; it does not own a server-side business transition.
 */
internal class StableCommandKeys(
    private val generate: () -> String = { UUID.randomUUID().toString() },
) {
    private val keys = mutableMapOf<String, String>()

    fun key(signature: String): String = keys.getOrPut(signature, generate)

    fun complete(signature: String) {
        keys.remove(signature)
    }

    fun clear() {
        keys.clear()
    }
}
