package dev.buhanzaz.rwms.driver.core.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UploadProgressTest {
    @Test
    fun `byte streaming never reports completion before gateway acknowledgement`() {
        assertThat(uploadProgressPercent(50, 100)).isEqualTo(50)
        assertThat(uploadProgressPercent(100, 100)).isEqualTo(99)
    }

    @Test
    fun `content byte progress stays in a monotonic upload stage`() {
        val start = uploadContentProgressPercent(0, 100)
        val middle = uploadContentProgressPercent(50, 100)
        val end = uploadContentProgressPercent(100, 100)

        assertThat(start).isEqualTo(15)
        assertThat(middle).isGreaterThan(start)
        assertThat(end).isEqualTo(95)
    }
}
