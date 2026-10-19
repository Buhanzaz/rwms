package dev.buhanzaz.rwms.manager.network

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Test

class MaintenanceReworkContractParsingTest {
    private val moshi = Moshi.Builder()
        .add(ExplicitNullJsonAdapterFactory)
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun `full chain candidate keeps source identity and canonical line`() {
        val response = requireNotNull(
            moshi.adapter(ReworkCandidatesDto::class.java).fromJson(
                """
                    {
                      "items":[{
                        "sourceRepairId":"11111111-1111-1111-1111-111111111111",
                        "sourceLineId":"22222222-2222-2222-2222-222222222222",
                        "lineageRootLineId":"33333333-3333-3333-3333-333333333333",
                        "line":{
                          "id":"22222222-2222-2222-2222-222222222222",
                          "lineType":"MATERIAL",
                          "description":"ДВП",
                          "unit":"лист",
                          "quantity":"2",
                          "unitPrice":"500.00",
                          "lineTotal":"1000.00",
                          "normativeMinutes":0,
                          "comment":null,
                          "mediaReferences":[]
                        }
                      }]
                    }
                """.trimIndent(),
            ),
        )

        val candidate = response.items.single()
        assertThat(candidate.lineageRootLineId)
            .isEqualTo("33333333-3333-3333-3333-333333333333")
        assertThat(candidate.line.description).isEqualTo("ДВП")
        assertThat(candidate.line.disposition).isNull()
    }

    @Test
    fun `repair line parses optional repeat lineage while legacy line stays compatible`() {
        val adapter = moshi.adapter(EstimateLineDto::class.java)
        val repeated = requireNotNull(
            adapter.fromJson(
                """
                    {
                      "id":"44444444-4444-4444-4444-444444444444",
                      "lineType":"WORK",
                      "description":"Замена ДВП",
                      "unit":"ед.",
                      "quantity":"1",
                      "unitPrice":"1000.00",
                      "lineTotal":"1000.00",
                      "normativeMinutes":30,
                      "comment":"Повторить",
                      "mediaReferences":[],
                      "disposition":"REPEAT",
                      "sourceRepairId":"11111111-1111-1111-1111-111111111111",
                      "sourceLineId":"22222222-2222-2222-2222-222222222222",
                      "lineageRootLineId":"33333333-3333-3333-3333-333333333333"
                    }
                """.trimIndent(),
            ),
        )
        val legacy = requireNotNull(
            adapter.fromJson(
                """
                    {
                      "id":"55555555-5555-5555-5555-555555555555",
                      "lineType":"WORK",
                      "description":"Старая работа",
                      "unit":"ед.",
                      "quantity":"1",
                      "unitPrice":"0.00",
                      "lineTotal":"0.00"
                    }
                """.trimIndent(),
            ),
        )

        assertThat(repeated.disposition).isEqualTo("REPEAT")
        assertThat(repeated.sourceLineId)
            .isEqualTo("22222222-2222-2222-2222-222222222222")
        assertThat(legacy.disposition).isNull()
    }
}
