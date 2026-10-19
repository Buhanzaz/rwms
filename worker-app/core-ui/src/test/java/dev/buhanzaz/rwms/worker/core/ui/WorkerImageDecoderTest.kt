package dev.buhanzaz.rwms.worker.core.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Verifies the memory bound shared by worker thumbnails and the full-screen pager. */
class WorkerImageDecoderTest {
    @Test
    fun `large camera image is sampled below full screen pixel budget`() {
        assertThat(workerBitmapSampleSize(12_000, 9_000, 12_000_000)).isEqualTo(4)
    }

    @Test
    fun `small image remains at native resolution`() {
        assertThat(workerBitmapSampleSize(1_920, 1_080, 12_000_000)).isEqualTo(1)
    }

    @Test
    fun `camera confirmation budget samples a high resolution capture`() {
        assertThat(workerBitmapSampleSize(8_000, 6_000, 2_000_000)).isEqualTo(8)
    }
}
