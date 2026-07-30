package dev.buhanzaz.rwms.manager.ui

import java.util.UUID

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
