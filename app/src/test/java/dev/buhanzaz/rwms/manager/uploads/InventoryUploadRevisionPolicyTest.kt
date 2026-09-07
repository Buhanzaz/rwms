package dev.buhanzaz.rwms.manager.uploads

import com.google.common.truth.Truth.assertThat
import dev.buhanzaz.rwms.manager.network.InventoryFindingDto
import dev.buhanzaz.rwms.manager.network.ObservationDto
import dev.buhanzaz.rwms.manager.network.ObservationInput
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** Verifies fail-closed fence preparation for durable initial and repeat inspections. */
class InventoryUploadRevisionPolicyTest {
    @Test
    fun `rebases an initial queued inspection after an asset snapshot refresh`() {
        val rebased = command(expectedFindingRevision = 3).preparePendingInspectionFence(
            finding(findingRevision = 7),
        )

        assertThat(rebased.expectedFindingRevision).isEqualTo(7)
    }

    @Test
    fun `allows a source-created finding to finish its first inspection`() {
        val rebased = command(expectedFindingRevision = 1).preparePendingInspectionFence(
            finding(findingRevision = 2, mutationState = "SOURCE_CREATED"),
        )

        assertThat(rebased.expectedFindingRevision).isEqualTo(2)
    }

    @Test
    fun `keeps the exact fence for an unchanged queued supplement`() {
        val prepared = command(expectedFindingRevision = 4).preparePendingInspectionFence(
            finding(findingRevision = 4, inspection = "READY"),
        )

        assertThat(prepared.expectedFindingRevision).isEqualTo(4)
    }

    @Test
    fun `does not rebase a queued repeat inspection over newer server data`() {
        val failure = runCatching {
            command(expectedFindingRevision = 1).preparePendingInspectionFence(
                finding(findingRevision = 2, inspection = "READY"),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(InventoryUploadConflictException::class.java)
        assertThat(failure?.message).isEqualTo(INVENTORY_UPLOAD_INSPECTION_CHANGED_MESSAGE)
    }

    @Test
    fun `does not rebase while source asset creation is in flight`() {
        val failure = runCatching {
            command().preparePendingInspectionFence(
                finding(mutationState = "SOURCE_CREATE_PENDING"),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(InventoryUploadConflictException::class.java)
        assertThat(failure?.message).isEqualTo(INVENTORY_UPLOAD_MUTATION_IN_PROGRESS_MESSAGE)
    }

    @Test
    fun `fails closed when the queued cabin has left the active inventory`() {
        val failure = runCatching {
            requireActiveInventoryFindingForUpload(null)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).isEqualTo(INVENTORY_UPLOAD_FINDING_NOT_ACTIVE_MESSAGE)
    }

    @Test
    fun `retries one recognized inventory revision conflict`() {
        assertThat(
            shouldRetryInventoryRevisionConflict(
                inventoryVersionConflict(),
                retryCount = 0,
            ),
        ).isTrue()
    }

    @Test
    fun `does not retry a second or unrelated conflict`() {
        assertThat(
            shouldRetryInventoryRevisionConflict(
                inventoryVersionConflict(),
                retryCount = 1,
            ),
        ).isFalse()
        assertThat(
            shouldRetryInventoryRevisionConflict(
                inventoryVersionConflict(detail = "Inventory session is not active"),
                retryCount = 0,
            ),
        ).isFalse()
        assertThat(
            shouldRetryInventoryRevisionConflict(
                HttpException(
                    Response.error<Any>(
                        422,
                        """{"code":"INVENTORY_VALIDATION_FAILED"}""".toResponseBody(
                            "application/problem+json".toMediaType(),
                        ),
                    ),
                ),
                retryCount = 0,
            ),
        ).isFalse()
    }

    private fun command(expectedFindingRevision: Long = 1) = InventoryUploadCommand(
        inventoryId = "inventory-1",
        findingId = "finding-1",
        expectedFindingRevision = expectedFindingRevision,
        inspection = "WORK_STAGED",
        comment = "",
        passportObservation = ObservationInput("ABSENT", null),
        equipmentObservation = ObservationInput("ABSENT", null),
    )

    private fun finding(
        findingRevision: Long = 2,
        inspection: String = "NOT_INSPECTED",
        mutationState: String = "IDLE",
    ) = InventoryFindingDto(
        id = "finding-1",
        inventoryId = "inventory-1",
        findingRevision = findingRevision,
        origin = "UNEXPECTED_EXISTING",
        inspection = inspection,
        reconciliation = "MATCHED",
        displayCanonicalNumber = "011015",
        identityMatchKey = "011015",
        passportObservation = ObservationDto("ABSENT", null),
        equipmentObservation = ObservationDto("ABSENT", null),
        mutationState = mutationState,
        comment = "",
    )

    private fun inventoryVersionConflict(
        detail: String = "Inventory revision is stale",
    ) = HttpException(
        Response.error<Any>(
            409,
            """{"code":"INVENTORY_VERSION_CONFLICT","detail":"$detail"}""".toResponseBody(
                "application/problem+json".toMediaType(),
            ),
        ),
    )
}
