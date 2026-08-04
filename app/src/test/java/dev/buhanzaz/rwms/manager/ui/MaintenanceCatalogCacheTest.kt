package dev.buhanzaz.rwms.manager.ui

import android.content.Context
import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.CatalogLinkDto
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import java.time.LocalDate
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
class MaintenanceCatalogCacheTest {
    private lateinit var context: Context
    private lateinit var cache: MaintenanceCatalogCache

    @Before
    fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication().applicationContext
        cache = MaintenanceCatalogCache(context)
        cache.clear()
    }

    @After
    fun tearDown() = runBlocking {
        cache.clear()
    }

    @Test
    fun `round trips catalog data including null node and link fields`() = runBlocking {
        val catalog = CachedMaintenanceCatalog(
            warehouseId = "warehouse-1",
            revision = ActiveMaintenanceCatalogRevision("catalog-1", 7),
            activeVersionEtag = "W/\"catalog-7\"",
            nodes = listOf(
                CatalogNodeDto(
                    id = "node-1",
                    catalogVersionId = "catalog-1",
                    nodeType = "WORK",
                    name = "Repair work",
                    active = true,
                    parentNodeId = null,
                    furnitureCategory = false,
                    furnitureEquipment = null,
                    unit = null,
                    unitPrice = null,
                    durationMinutes = 30,
                    includeInEstimate = true,
                    commonItem = false,
                    showInMainMenu = true,
                    canvasX = null,
                    canvasY = null,
                    routing = null,
                    comment = null,
                ),
            ),
            links = listOf(
                CatalogLinkDto(
                    id = "link-1",
                    catalogVersionId = "catalog-1",
                    fromNodeId = "node-1",
                    toNodeId = "node-2",
                    linkType = "CONTAINS",
                    sourceAnchor = null,
                    targetAnchor = null,
                    sortOrder = 1,
                ),
            ),
        )

        cache.write(catalog)

        assertThat(cache.read()).isEqualTo(catalog)
        val payload = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(CATALOG_PAYLOAD_KEY, null)
        assertThat(payload).contains("\"warehouseId\":\"warehouse-1\"")
        assertThat(payload).contains("\"parentNodeId\":null")
        assertThat(payload).contains("\"sourceAnchor\":null")
    }

    @Test
    fun `records attempt slot and clears both cache values`() = runBlocking {
        val slot = LocalDate.of(2026, 7, 27)

        cache.markAttemptSlot(slot)

        assertThat(cache.lastAttemptSlot()).isEqualTo(slot)
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(ATTEMPT_SLOT_KEY, "not-a-date")
            .commit()
        assertThat(cache.lastAttemptSlot()).isNull()

        cache.write(sampleCatalog())
        cache.markAttemptSlot(slot)
        cache.clear()

        assertThat(cache.read()).isNull()
        assertThat(cache.lastAttemptSlot()).isNull()
    }

    @Test
    fun `returns null for corrupt catalog payload`() = runBlocking {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(CATALOG_PAYLOAD_KEY, "{not-json")
            .commit()

        assertThat(cache.read()).isNull()
    }

    @Test
    fun `does not reuse snapshots written before warehouse scoping existed`() = runBlocking {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(
                CATALOG_PAYLOAD_KEY,
                """{"revision":{"id":"catalog-1","version":1},"nodes":[],"links":[]}""",
            )
            .commit()

        assertThat(cache.read()).isNull()
    }

    private fun sampleCatalog() = CachedMaintenanceCatalog(
        warehouseId = "warehouse-1",
        revision = ActiveMaintenanceCatalogRevision("catalog-1", 1),
        nodes = emptyList(),
        links = emptyList(),
    )

    private companion object {
        const val PREFERENCES_NAME = "rwms_manager_maintenance_catalog"
        const val CATALOG_PAYLOAD_KEY = "catalog_payload"
        const val ATTEMPT_SLOT_KEY = "attempt_slot"
    }
}
