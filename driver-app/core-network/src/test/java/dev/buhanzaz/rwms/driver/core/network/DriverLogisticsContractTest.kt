package dev.buhanzaz.rwms.driver.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class DriverLogisticsContractTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `driver category decodes its stable logistics purpose`() {
        val category = json.decodeFromString<DriverCategoryDto>(
            """
            {
              "queueId":"queue-drivers",
              "name":"Водители",
              "type":"MOVEMENT",
              "queuePurpose":"LOGISTICS_DRIVER",
              "groupIds":[],
              "sortOrder":10,
              "audienceModes":["AVAILABLE","OPTIONAL_JOIN"],
              "resultPhotoMinCount":1
            }
            """.trimIndent(),
        )

        assertThat(category.queuePurpose).isEqualTo("LOGISTICS_DRIVER")
        assertThat(category.groupIds).isEmpty()
        assertThat(category.audienceModes).contains("OPTIONAL_JOIN")
    }

    @Test
    fun `driver feed entry decodes server owned driver audience`() {
        val entry = json.decodeFromString<DriverFeedEntryDto>(
            """
            {
              "entryId":"entry",
              "version":4,
              "taskId":"task",
              "routeIndex":0,
              "title":"Отгрузить бытовку",
              "unitNumber":"БТ-1",
              "taskText":null,
              "scheduledDate":"2026-08-10",
              "deadlineAt":null,
              "priority":3,
              "queuePosition":0,
              "status":"WAITING",
              "availabilityMode":"AVAILABLE",
              "plannedDurationMinutes":null,
              "activeStartedAt":null,
              "activeWorkSeconds":0,
              "assignments":[],
              "readyEvidenceCount":0,
              "resultPhotoMinCount":1,
              "driverAudience":{
                "mode":"ASSIGNED_DRIVER",
                "workerId":"11111111-1111-1111-1111-111111111111",
                "workerName":"Водитель"
              },
              "timerSnapshot":null
            }
            """.trimIndent(),
        )

        assertThat(entry.driverAudience?.mode).isEqualTo("ASSIGNED_DRIVER")
        assertThat(entry.driverAudience?.driverId)
            .isEqualTo("11111111-1111-1111-1111-111111111111")
    }

    @Test
    fun `driver source and canonical date-only logistics trip detail decode without duplicated domain state`() {
        val detail = json.decodeFromString<DriverTaskDetailDto>(
            """
            {
              "entryId":"44444444-4444-4444-8444-444444444444",
              "version":4,
              "taskId":"55555555-5555-4555-8555-555555555555",
              "source":{
                "type":"LOGISTICS_DRIVER_TASK",
                "sourceId":"66666666-6666-4666-8666-666666666666"
              },
              "routeIndex":0,
              "title":"Доставка бытовок",
              "description":null,
              "object":null,
              "taskText":null,
              "scheduledDate":"2026-08-11",
              "deadlineAt":null,
              "priority":3,
              "queuePosition":0,
              "status":"WAITING",
              "availabilityMode":"MANDATORY",
              "plannedDurationMinutes":null,
              "activeStartedAt":null,
              "activeWorkSeconds":0,
              "timerSnapshot":null,
              "audienceSelectors":[],
              "assignments":[],
              "works":[],
              "materials":[],
              "comments":[],
              "sourceMedia":[],
              "evidence":[],
              "relatedSteps":[],
              "resultPhotoMinCount":1,
              "completionAllowed":false
            }
            """.trimIndent(),
        )
        val response = json.decodeFromString<DriverTaskTripDetailsResponseDto>(
            """
            {
              "id":"66666666-6666-4666-8666-666666666666",
              "version":12,
              "warehouseId":"77777777-7777-4777-8777-777777777777",
              "tripDetails":{
                "taskNumber":"123",
                "tripNumber":2,
                "operationType":"SHIPMENT",
                "clientName":"ООО Стройка",
                "address":"Санкт-Петербург, Невский проспект, 1",
                "latitude":59.9343,
                "longitude":30.3351,
                "primaryContactName":"Иван",
                "primaryContactPhone":"+79990000001",
                "additionalContacts":[
                  {"name":"Анна","phone":"+79990000002"},
                  {"name":"Пётр","phone":"+79990000003"}
                ],
                "comment":"Позвонить за час",
                "desiredDeliveryWindows":[
                  {
                    "startDate":"2026-08-11",
                    "endDate":"2026-08-11"
                  },
                  {
                    "startDate":"2026-08-13",
                    "endDate":"2026-08-15"
                  }
                ],
                "scheduledDate":"2026-08-12",
                "cabins":[
                  {
                    "cabinId":"88888888-8888-4888-8888-888888888888",
                    "unitNumber":"БТ-101",
                    "desiredContents":[
                      {
                        "equipmentId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                        "equipmentName":"Кровать",
                        "quantity":4
                      }
                    ],
                    "actualContents":[
                      {
                        "equipmentId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                        "equipmentName":"Кровать",
                        "quantity":4,
                        "locationKind":"CABIN"
                      }
                    ],
                    "movementTaskCreated":true,
                    "movementTaskCompleted":true,
                    "contentReady":true
                  },
                  {
                    "cabinId":"99999999-9999-4999-8999-999999999999",
                    "unitNumber":"БТ-102",
                    "desiredContents":[
                      {
                        "equipmentId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                        "equipmentName":"Стол",
                        "quantity":1
                      }
                    ],
                    "actualContents":[
                      {
                        "equipmentId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                        "equipmentName":null,
                        "quantity":0,
                        "locationKind":"WAREHOUSE"
                      }
                    ],
                    "movementTaskCreated":true,
                    "movementTaskCompleted":false,
                    "contentReady":false
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        assertThat(detail.source?.type).isEqualTo("LOGISTICS_DRIVER_TASK")
        assertThat(detail.source?.sourceId)
            .isEqualTo("66666666-6666-4666-8666-666666666666")
        val trip = requireNotNull(response.tripDetails)
        assertThat(trip.additionalContacts.map { it.name }).containsExactly("Анна", "Пётр")
        assertThat(trip.desiredDeliveryWindows).hasSize(2)
        assertThat(trip.scheduledDate).isEqualTo("2026-08-12")
        assertThat(trip.cabins.map { it.unitNumber }).containsExactly("БТ-101", "БТ-102")
            .inOrder()
        assertThat(trip.cabins.first().desiredContents.single().quantity).isEqualTo(4)
        assertThat(trip.cabins.last().actualContents.single().equipmentName).isNull()
        assertThat(trip.cabins.last().contentReady).isFalse()
    }

    @Test
    fun `date-only trip detail ignores legacy time keys from an older server`() {
        val response = json.decodeFromString<DriverTaskTripDetailsResponseDto>(
            """
            {
              "tripDetails":{
                "taskNumber":"123",
                "tripNumber":1,
                "operationType":"SHIPMENT",
                "clientName":"ООО Стройка",
                "address":null,
                "latitude":null,
                "longitude":null,
                "primaryContactName":null,
                "primaryContactPhone":null,
                "additionalContacts":[],
                "comment":null,
                "desiredDeliveryWindows":[{
                  "startDate":"2026-08-11",
                  "endDate":"2026-08-11",
                  "timeFrom":"10:00:00",
                  "timeTo":"13:00:00"
                }],
                "scheduledDate":"2026-08-12",
                "scheduledTime":"11:30:00",
                "cabins":[]
              }
            }
            """.trimIndent(),
        )

        val trip = requireNotNull(response.tripDetails)
        assertThat(trip.desiredDeliveryWindows.single().startDate).isEqualTo("2026-08-11")
        assertThat(trip.scheduledDate).isEqualTo("2026-08-12")
    }

    @Test
    fun `complete action sends the selected ready evidence`() {
        val request = DriverActionRequestDto(
            operationId = "operation",
            action = "COMPLETE",
            expectedVersion = 8,
            driverGroupId = null,
            evidenceId = "evidence-ready",
            occurredAt = "2026-07-30T10:00:00Z",
            offlineLeaseId = "lease",
        )

        val payload = json.encodeToString(request)

        assertThat(payload).contains("\"action\":\"COMPLETE\"")
        assertThat(payload).contains("\"evidenceId\":\"evidence-ready\"")
        assertThat(payload).contains("\"workerGroupId\":null")
    }

    @Test
    fun `driver action keeps both required nullable keys and round trips exact values`() {
        val nullableRequest = DriverActionRequestDto(
            operationId = "11111111-1111-1111-1111-111111111111",
            action = "PAUSE",
            expectedVersion = 8,
            driverGroupId = null,
            evidenceId = null,
            occurredAt = "2026-08-09T10:00:00Z",
            offlineLeaseId = "22222222-2222-2222-2222-222222222222",
        )
        val nullablePayload = json.encodeToString(nullableRequest)
        val nullableObject = json.parseToJsonElement(nullablePayload).jsonObject

        assertThat(nullableObject.keys).containsExactly(
            "operationId",
            "action",
            "expectedVersion",
            "workerGroupId",
            "evidenceId",
            "occurredAt",
            "offlineLeaseId",
        )
        assertThat(nullablePayload).contains("\"workerGroupId\":null")
        assertThat(nullablePayload).contains("\"evidenceId\":null")
        assertThat(json.decodeFromString<DriverActionRequestDto>(nullablePayload))
            .isEqualTo(nullableRequest)

        val selectedRequest = nullableRequest.copy(
            action = "COMPLETE",
            driverGroupId = "33333333-3333-3333-3333-333333333333",
            evidenceId = "44444444-4444-4444-4444-444444444444",
        )
        assertThat(
            json.decodeFromString<DriverActionRequestDto>(json.encodeToString(selectedRequest)),
        ).isEqualTo(selectedRequest)
    }

    @Test
    fun `driver action rejects missing and additional contract properties`() {
        val canonical =
            """
            {
              "operationId":"11111111-1111-1111-1111-111111111111",
              "action":"PAUSE",
              "expectedVersion":8,
              "workerGroupId":null,
              "evidenceId":null,
              "occurredAt":"2026-08-09T10:00:00Z",
              "offlineLeaseId":"22222222-2222-2222-2222-222222222222"
            }
            """.trimIndent()
        val malformedPayloads = listOf(
            canonical.replace("\"workerGroupId\":null,", ""),
            canonical.dropLast(1) + ",\"internalOwner\":\"task-board\"}",
        )

        malformedPayloads.forEach { payload ->
            val failure = runCatching {
                json.decodeFromString<DriverActionRequestDto>(payload)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SerializationException::class.java)
        }
    }

    @Test
    fun `audience selector decodes notification policy`() {
        val selector = json.decodeFromString<AudienceSelectorDto>(
            """
            {
              "kind":"DRIVER_CLASS",
              "id":"class-slingers",
              "mode":"OPTIONAL_JOIN",
              "interruptOnTake":false,
              "notifyOnPrimaryTake":true
            }
            """.trimIndent(),
        )

        assertThat(selector.mode).isEqualTo("OPTIONAL_JOIN")
        assertThat(selector.notifyOnPrimaryTake).isTrue()
    }
}
