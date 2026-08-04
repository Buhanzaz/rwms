package dev.buhanzaz.rwms.worker.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test

class WorkerContextCompatibilityTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `context decodes with no selected current group and nullable KPI palette`() {
        val context = json.decodeFromString<WorkerContextDto>(legacyContextJson())

        assertThat(context.currentGroup).isNull()
        assertThat(context.operationalAvailability).isEqualTo("AVAILABLE")
        assertThat(context.kpiPalette).isNull()
    }

    @Test
    fun `context exposes manager selected current group and disabled state`() {
        val context = json.decodeFromString<WorkerContextDto>(
            legacyContextJson(
                extra = """
                  ,"currentGroup":{
                    "id":"group-current",
                    "name":"Смена 1",
                    "workerClassId":"class-1",
                    "workerClassName":"Ремонтники"
                  },
                  "operationalAvailability":"DISABLED"
                """.trimIndent(),
            ),
        )

        assertThat(context.currentGroup?.id).isEqualTo("group-current")
        assertThat(context.operationalAvailability).isEqualTo("DISABLED")
    }

    @Test
    fun `context exposes the exact server KPI palette`() {
        val context = json.decodeFromString<WorkerContextDto>(
            legacyContextJson(
                extra = """
                  ,"kpiPalette":{
                    "ranges":[
                      {"fromPercent":100,"toPercent":80,"color":"#16803A"},
                      {"fromPercent":79,"toPercent":50,"color":"#D99B00"}
                    ],
                    "overdueColor":"#C62828"
                  }
                """.trimIndent(),
                includeNullPalette = false,
            ),
        )

        assertThat(context.kpiPalette?.ranges).hasSize(2)
        assertThat(context.kpiPalette?.ranges?.first()?.fromPercent).isEqualTo(100)
        assertThat(context.kpiPalette?.overdueColor).isEqualTo("#C62828")
    }

    private fun legacyContextJson(extra: String = "", includeNullPalette: Boolean = true): String =
        """
        {
          "worker":{
            "id":"worker-1",
            "warehouseId":"warehouse-1",
            "login":"worker",
            "displayName":"Рабочий"
          },
          "groups":[],
          "qualifications":[],
          "categories":[],
          ${if (includeNullPalette) "\"kpiPalette\":null," else ""}
          "serverTime":"2026-07-30T10:00:00Z",
          "revision":1,
          "offlineLease":{
            "id":"lease-1",
            "issuedAt":"2026-07-30T10:00:00Z",
            "expiresAt":"2026-07-31T10:00:00Z",
            "syncRevision":1
          }
          $extra
        }
        """.trimIndent()
}
