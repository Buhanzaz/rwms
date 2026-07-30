package dev.buhanzaz.rwms.worker.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test

class WorkerContextCompatibilityTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `legacy context decodes with no selected current group`() {
        val context = json.decodeFromString<WorkerContextDto>(legacyContextJson())

        assertThat(context.currentGroup).isNull()
        assertThat(context.operationalAvailability).isEqualTo("AVAILABLE")
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

    private fun legacyContextJson(extra: String = ""): String =
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
