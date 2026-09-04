package dev.buhanzaz.rwms.client.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Covers add/cancel expansion state, furniture assignment, and cart replacement payloads. */
class CustomerCartPolicyTest {
    @Test
    fun `cancel cabin removes its furniture but keeps other cart lines`() {
        val first = CustomerCartPolicy.toggleCabin(emptySet(), emptyMap(), "cabin-a")
        val second = CustomerCartPolicy.toggleCabin(first.selectedCabins, first.equipment, "cabin-b")
        val furnished = CustomerCartPolicy.withEquipment(
            selectedCabins = second.selectedCabins,
            equipment = second.equipment,
            key = EquipmentKey("cabin-a", "bed"),
            quantity = 2L,
            available = 4L,
        )

        val cancelled = CustomerCartPolicy.toggleCabin(
            furnished.selectedCabins,
            furnished.equipment,
            "cabin-a",
        )

        assertThat(cancelled.selectedCabins).containsExactly("cabin-b")
        assertThat(cancelled.equipment).isEmpty()
        assertThat(CustomerCartPolicy.toApiSelections(cancelled)).isEmpty()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `furniture cannot exceed warehouse availability`() {
        CustomerCartPolicy.withEquipment(
            selectedCabins = setOf("cabin-a"),
            equipment = emptyMap(),
            key = EquipmentKey("cabin-a", "bed"),
            quantity = 3L,
            available = 2L,
        )
    }

    @Test
    fun `one cabin duration change preserves the complete replacement`() {
        val terms = CustomerRentalTermPolicy.withCabinTerm(
            selectedCabins = setOf("cabin-a", "cabin-b"),
            existingTerms = mapOf("cabin-a" to 2L, "cabin-b" to 5L),
            cabinUnitId = "cabin-a",
            months = 8L,
        )

        assertThat(terms).containsExactly("cabin-a", 8L, "cabin-b", 5L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `term cannot be changed for a cabin outside the cart`() {
        CustomerRentalTermPolicy.withCabinTerm(
            selectedCabins = setOf("cabin-a"),
            existingTerms = emptyMap(),
            cabinUnitId = "cabin-b",
            months = 3L,
        )
    }
}
