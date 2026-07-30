package dev.buhanzaz.rwms.manager.network

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Test

class InventoryConflictModelParsingTest {
    private val adapter = Moshi.Builder()
        .add(ExplicitNullJsonAdapterFactory)
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(InventoryFindingDto::class.java)

    @Test
    fun `finding parses semantic baseline current snapshots and resolution`() {
        val finding = requireNotNull(
            adapter.fromJson(
                """
                    {
                      "id":"11111111-1111-1111-1111-111111111111",
                      "inventoryId":"22222222-2222-2222-2222-222222222222",
                      "findingRevision":7,
                      "origin":"EXPECTED",
                      "inspection":"READY",
                      "reconciliation":"CONFLICT",
                      "assetId":"33333333-3333-3333-3333-333333333333",
                      "assetVersion":10,
                      "displayCanonicalNumber":"БЫТ-001",
                      "identityMatchKey":"БЫТ-001",
                      "passportObservation":{"presence":"ABSENT","value":null},
                      "equipmentObservation":{"presence":"ABSENT","value":null},
                      "mutationState":"IDLE",
                      "comment":"",
                      "inspectionBaseline":{
                        "assetId":"33333333-3333-3333-3333-333333333333",
                        "assetVersion":9,
                        "warehouseId":"44444444-4444-4444-4444-444444444444",
                        "status":"FREE",
                        "displayCanonicalNumber":"БЫТ-001",
                        "tenantSnapshot":null,
                        "passportSnapshot":{"finishing":"ДВП"},
                        "contentsSnapshot":[{"name":"Стул","quantity":1}],
                        "repairsSnapshot":[]
                      },
                      "currentSnapshot":{
                        "assetId":"33333333-3333-3333-3333-333333333333",
                        "assetVersion":10,
                        "warehouseId":"44444444-4444-4444-4444-444444444444",
                        "status":"REPAIR",
                        "displayCanonicalNumber":"БЫТ-001",
                        "tenantSnapshot":null,
                        "passportSnapshot":{"finishing":"ОСБ"},
                        "contentsSnapshot":[{"name":"Стул","quantity":2}],
                        "repairsSnapshot":[{
                          "repairId":"55555555-5555-5555-5555-555555555555",
                          "rootRepairId":"55555555-5555-5555-5555-555555555555",
                          "origin":"DIRECT",
                          "kind":"PRIMARY",
                          "executionState":"QUEUED",
                          "acceptanceState":"NOT_READY",
                          "planFingerprintSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                        }]
                      },
                      "conflicts":[{
                        "code":"STATUS_CHANGED",
                        "message":"Статус изменился",
                        "expected":"FREE",
                        "actual":"REPAIR"
                      }],
                      "conflictResolution":{
                        "strategy":"KEEP_INSPECTION",
                        "reason":"Проверено на месте",
                        "resolvedAt":"2026-07-27T18:00:00Z"
                      },
                      "media":[]
                    }
                """.trimIndent(),
            ),
        )

        assertThat(finding.inspectionBaseline?.passportSnapshot)
            .containsEntry("finishing", "ДВП")
        assertThat(finding.currentSnapshot?.contentsSnapshot).hasSize(1)
        assertThat(finding.currentSnapshot?.repairsSnapshot?.single()?.executionState)
            .isEqualTo("QUEUED")
        assertThat(finding.conflictResolution?.strategy).isEqualTo("KEEP_INSPECTION")
        assertThat(finding.conflictResolution?.reason).isEqualTo("Проверено на месте")
    }
}
