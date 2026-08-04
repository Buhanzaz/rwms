package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.EstimatePageDto
import dev.buhanzaz.rwms.manager.network.RepairPageDto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ManagerReadCacheTest {
    private lateinit var cache: ManagerReadCache
    private val firstScope = ManagerReadCacheScope("manager-a", "warehouse-a")
    private val secondScope = ManagerReadCacheScope("manager-b", "warehouse-a")

    @Before
    fun setUp() = runBlocking {
        cache = ManagerReadCache(RuntimeEnvironment.getApplication().applicationContext)
        cache.clear(firstScope)
        cache.clear(secondScope)
    }

    @After
    fun tearDown() = runBlocking {
        cache.clear(firstScope)
        cache.clear(secondScope)
    }

    @Test
    fun `read snapshots are isolated by account and warehouse and can be cleared per scope`() =
        runBlocking {
            val first = CachedMaintenanceRead(
                estimates = EstimatePageDto(
                    items = emptyList(),
                    page = 0,
                    size = 200,
                    totalElements = 0,
                ),
                estimatesEtag = "W/\"estimate-a\"",
                repairs = RepairPageDto(
                    items = emptyList(),
                    page = 0,
                    size = 200,
                    totalElements = 0,
                ),
                repairsEtag = "W/\"repair-a\"",
                assetLabels = mapOf("asset-a" to "A-001"),
            )
            val second = CachedMaintenanceRead(
                estimatesEtag = "W/\"estimate-b\"",
                repairsEtag = "W/\"repair-b\"",
                assetLabels = mapOf("asset-b" to "B-001"),
            )

            cache.writeMaintenance(firstScope, first)

            assertThat(cache.readMaintenance(firstScope)).isEqualTo(first)
            assertThat(cache.readMaintenance(secondScope)).isNull()

            cache.writeMaintenance(secondScope, second)
            cache.clear(firstScope)

            assertThat(cache.readMaintenance(firstScope)).isNull()
            assertThat(cache.readMaintenance(secondScope)).isEqualTo(second)
        }
}
