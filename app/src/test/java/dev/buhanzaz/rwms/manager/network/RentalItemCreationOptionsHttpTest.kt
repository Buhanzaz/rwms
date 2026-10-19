package dev.buhanzaz.rwms.manager.network

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class RentalItemCreationOptionsHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var api: RwmsApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val moshi = Moshi.Builder()
            .add(ExplicitNullJsonAdapterFactory)
            .addLast(KotlinJsonAdapterFactory())
            .build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(RwmsApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `creation options use public asset route and parse canonical catalog values`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                    {
                      "newCategory": "Новая",
                      "usedCategories": ["ИТР", "Обычная"],
                      "rentalTypes": [
                        {
                          "id": "00000000-0000-0000-0000-000000000101",
                          "name": "БК-Санблок"
                        }
                      ],
                      "dimensions": [
                        {
                          "id": "00000000-0000-0000-0000-000000000201",
                          "name": "2.4x6"
                        }
                      ],
                      "finishings": [
                        {
                          "id": "00000000-0000-0000-0000-000000000301",
                          "name": "ПВХ"
                        }
                      ],
                      "characteristics": [
                        {
                          "id": "00000000-0000-0000-0000-000000000401",
                          "name": "Пластиковое окно"
                        }
                      ],
                      "typeDimensions": [
                        {
                          "typeId": "00000000-0000-0000-0000-000000000101",
                          "dimensionId": "00000000-0000-0000-0000-000000000201",
                          "sortOrder": 0
                        }
                      ]
                    }
                """.trimIndent(),
            ),
        )

        val result = api.rentalItemCreationOptions(
            warehouseId = "11111111-1111-1111-1111-111111111111",
        )

        assertThat(server.takeRequest().path).isEqualTo(
            "/api/asset/v1/rental-items/creation-options" +
                "?warehouseId=11111111-1111-1111-1111-111111111111",
        )
        assertThat(result.usedCategories).containsExactly("ИТР", "Обычная").inOrder()
        assertThat(result.rentalTypes.single().name).isEqualTo("БК-Санблок")
        assertThat(result.dimensions.single().name).isEqualTo("2.4x6")
        assertThat(result.finishings.single().name).isEqualTo("ПВХ")
        assertThat(result.characteristics.single().name).isEqualTo("Пластиковое окно")
        assertThat(result.typeDimensions.single().dimensionId)
            .isEqualTo("00000000-0000-0000-0000-000000000201")
    }

    @Test
    fun `rental item parses canonical characteristic catalog values`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                    {
                      "id": "00000000-0000-0000-0000-000000000001",
                      "version": 7,
                      "warehouseId": "00000000-0000-0000-0000-000000000002",
                      "number": "БЫТ-001",
                      "status": "AVAILABLE",
                      "rentalTypeId": "00000000-0000-0000-0000-000000000003",
                      "rentalType": "БК-1",
                      "dimensionId": "00000000-0000-0000-0000-000000000004",
                      "dimensions": "2.4x6",
                      "finishingId": "00000000-0000-0000-0000-000000000005",
                      "finishing": "ДВП",
                      "category": "Обычная",
                      "characteristics": [
                        {
                          "id": "00000000-0000-0000-0000-000000000006",
                          "name": "Пластиковое окно"
                        }
                      ],
                      "linoleum": true,
                      "generalComment": null,
                      "passport": {},
                      "tags": [],
                      "contents": [],
                      "activeOrderReservation": null,
                      "createdAt": "2026-07-28T17:00:00Z",
                      "updatedAt": "2026-07-28T17:00:00Z"
                    }
                """.trimIndent(),
            ),
        )

        val result = api.rentalItem("00000000-0000-0000-0000-000000000001")

        assertThat(server.takeRequest().path)
            .isEqualTo("/api/asset/v1/rental-items/00000000-0000-0000-0000-000000000001")
        assertThat(result.characteristics.single().name).isEqualTo("Пластиковое окно")
        assertThat(result.characteristics.single().id)
            .isEqualTo("00000000-0000-0000-0000-000000000006")
    }
}
