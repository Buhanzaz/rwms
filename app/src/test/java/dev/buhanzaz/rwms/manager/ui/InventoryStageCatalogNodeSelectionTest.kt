package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.CatalogNodeDto
import dev.buhanzaz.rwms.manager.network.RoutingSnapshotDto
import org.junit.Assert.assertThrows
import org.junit.Test

class InventoryStageCatalogNodeSelectionTest {
    private val selectedRouting = RoutingSnapshotDto(
        queueId = "repair-plumbing",
        queueName = "Сантехника",
        queueType = "REPAIR",
    )

    @Test
    fun `selects deterministic operational child when route is inherited from category`() {
        val parent = node(
            id = "route-parent",
            type = "CATEGORY",
            includeInEstimate = false,
        )
        val nodes = listOf(
            parent,
            node(id = "node-z", type = "WORK", parentId = parent.id, routing = null),
            node(id = "node-a", type = "MATERIAL", parentId = parent.id, routing = null),
            node(id = "node-0", type = "OPTION", parentId = parent.id, routing = null),
        )

        assertThat(inventoryStageCatalogNodeId(nodes, selectedRouting)).isEqualTo("node-a")
        assertThat(inventoryStageCatalogNodeId(nodes.reversed(), selectedRouting))
            .isEqualTo("node-a")
    }

    @Test
    fun `returns null when no operational node has selected route`() {
        val otherRouting = selectedRouting.copy(queueId = "repair-electrical")

        assertThat(inventoryStageCatalogNodeId(listOf(node(routing = otherRouting)), selectedRouting))
            .isNull()
    }

    @Test
    fun `excludes inactive nonoperational and differently routed nodes`() {
        val nodes = listOf(
            node(id = "inactive", active = false),
            node(id = "category", type = "CATEGORY"),
            node(id = "excluded", type = "MATERIAL", includeInEstimate = false),
            node(
                id = "wrong-route",
                routing = selectedRouting.copy(queueType = "MOVEMENT"),
            ),
            node(id = "valid", type = "WORK"),
        )

        assertThat(inventoryStageCatalogNodeId(nodes, selectedRouting)).isEqualTo("valid")
    }

    @Test
    fun `does not resolve a routing inherited through a parent cycle`() {
        val first = node(id = "first", parentId = "second", routing = null)
        val second = node(id = "second", parentId = first.id, routing = null)

        assertThat(inventoryStageCatalogNodeId(listOf(first, second), selectedRouting)).isNull()
    }

    @Test
    fun `resolves each manual work and material line against its own inherited route`() {
        val electricalRouting = selectedRouting.copy(
            queueId = "repair-electrical",
            queueName = "Электрика",
        )
        val plumbingParent = node(
            id = "plumbing-parent",
            type = "CATEGORY",
            includeInEstimate = false,
        )
        val electricalParent = node(
            id = "electrical-parent",
            type = "CATEGORY",
            includeInEstimate = false,
            routing = electricalRouting,
        )
        val nodes = listOf(
            plumbingParent,
            node(id = "plumbing-z", type = "WORK", parentId = plumbingParent.id, routing = null),
            node(id = "plumbing-a", type = "MATERIAL", parentId = plumbingParent.id, routing = null),
            electricalParent,
            node(id = "electrical-a", type = "WORK", parentId = electricalParent.id, routing = null),
        )

        val resolved = inventoryManualLineRoutingCatalogNodeIds(
            catalogNodes = nodes.reversed(),
            selectedRoutingByLineId = linkedMapOf(
                "manual-work" to selectedRouting,
                "manual-material" to selectedRouting,
                "manual-electrical-material" to electricalRouting,
            ),
        )

        assertThat(resolved["manual-work"]).isEqualTo("plumbing-a")
        assertThat(resolved["manual-material"]).isEqualTo("plumbing-a")
        assertThat(resolved["manual-electrical-material"]).isEqualTo("electrical-a")
    }

    @Test
    fun `rejects a manual line with no technical catalog node for its route`() {
        val otherRouting = selectedRouting.copy(queueId = "repair-electrical")

        val error = assertThrows(IllegalArgumentException::class.java) {
            inventoryManualLineRoutingCatalogNodeIds(
                catalogNodes = listOf(node(routing = otherRouting)),
                selectedRoutingByLineId = mapOf("manual-material" to selectedRouting),
            )
        }

        assertThat(error).hasMessageThat()
            .isEqualTo("Для пользовательской строки не найден маршрут каталога")
    }

    private fun node(
        id: String = "other",
        type: String = "WORK",
        active: Boolean = true,
        includeInEstimate: Boolean = true,
        parentId: String? = null,
        routing: RoutingSnapshotDto? = selectedRouting,
    ) = CatalogNodeDto(
        id = id,
        catalogVersionId = "catalog-1",
        nodeType = type,
        name = id,
        active = active,
        parentNodeId = parentId,
        unit = "шт.",
        unitPrice = "1.00",
        durationMinutes = 30,
        includeInEstimate = includeInEstimate,
        routing = routing,
    )
}
