package dev.buhanzaz.rwms.driver.core.sync

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.driver.core.database.DriverSessionEntity
import dev.buhanzaz.rwms.driver.core.network.DriverContextDto
import dev.buhanzaz.rwms.driver.core.network.DriverGroupSummaryDto
import dev.buhanzaz.rwms.driver.core.network.DriverIdentityDto
import dev.buhanzaz.rwms.driver.core.network.DriverOfflineLeaseDto
import dev.buhanzaz.rwms.driver.core.network.DriverKpiPaletteDto
import dev.buhanzaz.rwms.driver.core.network.DriverKpiRangeDto
import org.junit.Test

class LeaseActivationTest {
    private val context = DriverContextDto(
        driver = DriverIdentityDto(
            id = "123e4567-e89b-12d3-a456-426614174000",
            warehouseId = "123e4567-e89b-12d3-a456-426614174001",
            login = "driver",
            displayName = "Рабочий",
        ),
        groups = emptyList(),
        qualifications = emptyList(),
        categories = emptyList(),
        kpiPalette = null,
        serverTime = "2026-07-25T10:00:00Z",
        revision = 9,
        offlineLease = DriverOfflineLeaseDto(
            id = "123e4567-e89b-12d3-a456-426614174002",
            issuedAt = "2026-07-25T10:00:00Z",
            expiresAt = "2026-07-26T10:00:00Z",
            syncRevision = 9,
        ),
    )

    @Test
    fun `failure before feed retains prior lease rather than activating context lease`() {
        val old = DriverSessionEntity(
            userId = context.driver.id,
            displayName = "Старое имя",
            login = "old",
            warehouseId = context.driver.warehouseId,
            leaseId = "123e4567-e89b-12d3-a456-426614174003",
            leaseExpiresAtEpochMillis = 1_000L,
            serverEpochMillis = 500L,
            elapsedRealtimeAtSyncMillis = 100L,
            revision = 1,
            feedEtag = "old-etag",
            cacheHidden = false,
            updatedAtEpochMillis = 1L,
        )

        val staged = stagedSession(old, context, now = 2L)

        assertThat(staged.leaseId).isEqualTo(old.leaseId)
        assertThat(staged.leaseExpiresAtEpochMillis).isEqualTo(old.leaseExpiresAtEpochMillis)
        assertThat(staged.feedEtag).isEqualTo("old-etag")
    }

    @Test
    fun `lease is activated only after a full feed succeeds`() {
        val activated = activatedSession(
            previous = null,
            context = context,
            now = 2L,
            elapsedRealtimeMillis = 3L,
        )

        assertThat(activated.leaseId).isEqualTo(context.offlineLease.id)
        assertThat(activated.leaseExpiresAtEpochMillis).isEqualTo(1_785_060_000_000L)
        assertThat(activated.cacheHidden).isFalse()
    }

    @Test
    fun `manager selected group and availability are persisted from context`() {
        val disabledContext = context.copy(
            currentGroup = DriverGroupSummaryDto(
                id = "group-current",
                name = "Смена 1",
                driverClassId = "class-1",
                driverClassName = "Ремонтники",
            ),
            operationalAvailability = "DISABLED",
        )

        val session = activatedSession(
            previous = null,
            context = disabledContext,
            now = 2L,
            elapsedRealtimeMillis = 3L,
        )

        assertThat(session.currentGroupId).isEqualTo("group-current")
        assertThat(session.currentGroupName).isEqualTo("Смена 1")
        assertThat(session.operationalAvailability).isEqualTo("DISABLED")
    }

    @Test
    fun `server KPI palette is persisted exactly and null removes it`() {
        val palette = DriverKpiPaletteDto(
            ranges = listOf(DriverKpiRangeDto(100, 80, "#16803A")),
            overdueColor = "#C62828",
        )

        val withPalette = activatedSession(
            null,
            context.copy(kpiPalette = palette),
            now = 2L,
            elapsedRealtimeMillis = 10L,
        )
        val withoutPalette = activatedSession(
            withPalette,
            context.copy(kpiPalette = null),
            now = 3L,
            elapsedRealtimeMillis = 11L,
        )

        assertThat(withPalette.kpiPaletteJson).contains("#16803A")
        assertThat(withPalette.kpiPaletteJson).contains("#C62828")
        assertThat(withoutPalette.kpiPaletteJson).isNull()
    }

    @Test
    fun `hidden cache never sends its old feed validator`() {
        val hidden = DriverSessionEntity(
            userId = context.driver.id,
            displayName = "Рабочий",
            login = "driver",
            warehouseId = context.driver.warehouseId,
            leaseId = null,
            leaseExpiresAtEpochMillis = null,
            serverEpochMillis = null,
            elapsedRealtimeAtSyncMillis = null,
            revision = 1,
            feedEtag = "obsolete",
            cacheHidden = true,
            updatedAtEpochMillis = 1,
        )

        assertThat(hidden.feedEtagForValidatedProjection()).isNull()
        assertThat(hidden.copy(cacheHidden = false).feedEtagForValidatedProjection()).isEqualTo("obsolete")
    }
}
