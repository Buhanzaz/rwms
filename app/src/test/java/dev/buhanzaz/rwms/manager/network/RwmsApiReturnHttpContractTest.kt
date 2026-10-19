package dev.buhanzaz.rwms.manager.network

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class RwmsApiReturnHttpContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: RwmsApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(
                MoshiConverterFactory.create(
                    Moshi.Builder()
                        .add(ExplicitNullJsonAdapterFactory)
                        .addLast(KotlinJsonAdapterFactory())
                        .build(),
                ),
            )
            .build()
            .create(RwmsApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `return detail uses public URL and parses logistics document`() = runTest {
        val documentId = "11111111-1111-1111-1111-111111111111"
        val warehouseId = "22222222-2222-2222-2222-222222222222"
        val lineId = "33333333-3333-3333-3333-333333333333"
        val assetId = "44444444-4444-4444-4444-444444444444"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"$documentId","version":12,"documentType":"RETURN","state":"ARRIVED","warehouseId":"$warehouseId","lines":[{"id":"$lineId","version":3,"lineNumber":1,"assetId":"$assetId","assetVersion":8,"state":"RETURNED"}],"createdAt":"2026-07-28T10:00:00Z","updatedAt":"2026-07-28T10:05:00Z"}""",
                ),
        )

        val document = api.returnDocument(documentId)

        assertThat(document.id).isEqualTo(documentId)
        assertThat(document.version).isEqualTo(12)
        assertThat(document.documentType).isEqualTo("RETURN")
        assertThat(document.lines.single()).isEqualTo(
            LogisticsLineDto(
                id = lineId,
                version = 3,
                lineNumber = 1,
                assetId = assetId,
                assetVersion = 8,
                state = "RETURNED",
            ),
        )
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/api/logistics/v1/returns/$documentId")
    }
}
