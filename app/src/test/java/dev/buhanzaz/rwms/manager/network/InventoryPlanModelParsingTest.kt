package dev.buhanzaz.rwms.manager.network

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Test

class InventoryPlanModelParsingTest {
    private val adapter = Moshi.Builder()
        .add(ExplicitNullJsonAdapterFactory)
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(InventoryFindingDto::class.java)

    @Test
    fun `saved inventory finding parses source cover priority and frozen positions`() {
        val finding = requireNotNull(
            adapter.fromJson(
                """
                    {
                      "id":"11111111-1111-1111-1111-111111111111",
                      "inventoryId":"22222222-2222-2222-2222-222222222222",
                      "findingRevision":4,
                      "origin":"EXPECTED",
                      "inspection":"WORK_STAGED",
                      "reconciliation":"MATCHED",
                      "assetId":"33333333-3333-3333-3333-333333333333",
                      "assetVersion":7,
                      "displayCanonicalNumber":"БЫТ-001",
                      "identityMatchKey":"БЫТ-001",
                      "passportObservation":{"presence":"ABSENT","value":null},
                      "equipmentObservation":{"presence":"ABSENT","value":null},
                      "mutationState":"IDLE",
                      "comment":"Осмотрено",
                      "inspectionSource":"INVENTORY",
                      "coverMediaId":"44444444-4444-4444-4444-444444444444",
                      "frozenPlan":{
                        "mode":"MANUAL",
                        "catalogVersionId":"55555555-5555-5555-5555-555555555555",
                        "fingerprintSha256":"${"a".repeat(64)}",
                        "priority":2,
                        "lines":[{
                          "id":"66666666-6666-6666-6666-666666666666",
                          "sourceKind":"CATALOG",
                          "lineType":"WORK",
                          "catalogVersionId":"55555555-5555-5555-5555-555555555555",
                          "catalogNodeId":"77777777-7777-7777-7777-777777777777",
                          "description":"Замена ДВП",
                          "normalizedDescription":"замена двп",
                          "unit":"ед.",
                          "quantity":"1",
                          "unitPriceMinor":150000,
                          "normativeMinutes":"45",
                          "groupComment":null
                        }],
                        "stages":[]
                      },
                      "media":[{
                        "mediaId":"44444444-4444-4444-4444-444444444444",
                        "generation":2
                      }]
                    }
                """.trimIndent(),
            ),
        )

        assertThat(finding.inspectionSource).isEqualTo("INVENTORY")
        assertThat(finding.coverMediaId)
            .isEqualTo("44444444-4444-4444-4444-444444444444")
        assertThat(finding.frozenPlan?.priority).isEqualTo(2)
        assertThat(finding.frozenPlan?.lines?.single()?.description).isEqualTo("Замена ДВП")
    }
}
