package dev.buhanzaz.rwms.manager.network

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Test

class InventoryCompletionModelParsingTest {
    private val adapter = Moshi.Builder()
        .add(ExplicitNullJsonAdapterFactory)
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(InventoryCompletionPreviewDto::class.java)

    @Test
    fun `completion preview parses frozen revisions hashes statistics and risks`() {
        val acknowledgementSha256 = "a".repeat(64)
        val validationSha256 = "b".repeat(64)
        val preview = requireNotNull(
            adapter.fromJson(
                """
                    {
                      "inventoryId":"11111111-1111-1111-1111-111111111111",
                      "sessionRevision":12,
                      "findingRevisions":[{
                        "findingId":"22222222-2222-2222-2222-222222222222",
                        "expectedFindingRevision":5
                      }],
                      "validationSha256":"$validationSha256",
                      "validatedAt":"2026-07-27T20:15:00Z",
                      "acknowledgementSha256":"$acknowledgementSha256",
                      "statistics":{
                        "expectedCount":10,
                        "inspectedCount":8,
                        "missingCount":1,
                        "readyCount":6,
                        "withWorkCount":2,
                        "addedCount":1,
                        "unexpectedExistingCount":1,
                        "conflictCount":0,
                        "workLineCount":3,
                        "materialLineCount":2,
                        "workTotalMinor":10000,
                        "materialTotalMinor":2500,
                        "grandTotalMinor":12500,
                        "roundingAdjustmentMinor":0,
                        "normativeMinutes":"45.5",
                        "durationSeconds":3600,
                        "aggregateLines":[{
                          "aggregationKind":"MANUAL",
                          "catalogVersionId":null,
                          "catalogNodeId":null,
                          "normalizedDescription":"краска",
                          "type":"MATERIAL",
                          "unit":"л",
                          "unitPriceMinor":500,
                          "quantity":"5",
                          "rowTotalMinor":2500
                        }]
                      },
                      "risks":[{
                        "findingId":"22222222-2222-2222-2222-222222222222",
                        "code":"MISSING"
                      }],
                      "validatedFindings":[]
                    }
                """.trimIndent(),
            ),
        )

        assertThat(preview.sessionRevision).isEqualTo(12)
        assertThat(preview.findingRevisions.single().expectedFindingRevision).isEqualTo(5)
        assertThat(preview.acknowledgementSha256).isEqualTo(acknowledgementSha256)
        assertThat(preview.validationSha256).isEqualTo(validationSha256)
        assertThat(preview.statistics.grandTotalMinor).isEqualTo(12_500)
        assertThat(preview.statistics.aggregateLines.single().quantity).isEqualTo("5")
        assertThat(preview.risks.single().code).isEqualTo("MISSING")
    }
}
