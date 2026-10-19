package dev.buhanzaz.rwms.client.ui

import dev.buhanzaz.rwms.client.data.CabinFacets
import dev.buhanzaz.rwms.client.data.CabinFilters
import dev.buhanzaz.rwms.client.data.CustomerCabin
import dev.buhanzaz.rwms.client.data.CustomerWarehouse

/** Ephemeral public reads; a guest has no customer identity, inquiry, selection or cart. */
data class CustomerGuestCatalogState(
    val warehouses: List<CustomerWarehouse> = emptyList(),
    val selectedWarehouse: CustomerWarehouse? = null,
    val facets: CabinFacets = CabinFacets(),
    val filters: CabinFilters = CabinFilters(),
    val cabins: List<CustomerCabin> = emptyList(),
    val cabinPage: Long = 0,
    val cabinTotalPages: Long = 0,
    val busy: Boolean = false,
    val error: String? = null,
)
