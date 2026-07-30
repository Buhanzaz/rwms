package dev.buhanzaz.rwms.manager.ui

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.LogisticsDocumentDto
import org.junit.Test

class ReturnInspectionPolicyTest {
    @Test
    fun `only inspection-required return permits completion actions`() {
        assertThat(document(RETURN_INSPECTION_REQUIRED).returnInspectionActionError()).isNull()
        assertThat(document("REGISTERING").returnInspectionActionError())
            .contains("регистрируется")
        assertThat(document("RECONCILIATION_REQUIRED").returnInspectionActionError())
            .contains("сверки")
        assertThat(document("ACCEPTING").returnInspectionActionError())
            .contains("обрабатывается")
    }

    private fun document(state: String) = LogisticsDocumentDto(
        id = "return-1",
        version = 1,
        documentType = "RETURN",
        state = state,
        warehouseId = "warehouse-1",
        lines = emptyList(),
        createdAt = "2026-07-28T00:00:00Z",
        updatedAt = "2026-07-28T00:00:00Z",
    )
}
