package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StableCommandKeysTest {
    @Test
    fun `identical retry keeps key until effect succeeds`() {
        var sequence = 0
        val keys = StableCommandKeys { "key-${++sequence}" }

        assertThat(keys.key("inventory:resolve:session:4:CAB-1")).isEqualTo("key-1")
        assertThat(keys.key("inventory:resolve:session:4:CAB-1")).isEqualTo("key-1")
        assertThat(keys.key("inventory:resolve:session:4:CAB-2")).isEqualTo("key-2")

        keys.complete("inventory:resolve:session:4:CAB-1")

        assertThat(keys.key("inventory:resolve:session:4:CAB-1")).isEqualTo("key-3")
    }
}
