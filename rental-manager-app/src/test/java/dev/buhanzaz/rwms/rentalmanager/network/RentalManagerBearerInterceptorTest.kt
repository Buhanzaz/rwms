package dev.buhanzaz.rwms.rentalmanager.network

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class RentalManagerBearerInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `401 retries exactly once and preserves idempotency key`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val client = client { forceRefresh, rejected ->
            if (forceRefresh) {
                assertThat(rejected).isEqualTo("expired-token")
                "fresh-token"
            } else {
                "expired-token"
            }
        }
        val request = Request.Builder()
            .url(server.url("/api/logistics/v1/clients"))
            .header("Idempotency-Key", COMMAND_ID)
            .build()

        client.newCall(request).execute().use { response ->
            assertThat(response.code).isEqualTo(200)
        }

        assertThat(server.requestCount).isEqualTo(2)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertThat(first.getHeader("Authorization")).isEqualTo("Bearer expired-token")
        assertThat(second.getHeader("Authorization")).isEqualTo("Bearer fresh-token")
        assertThat(first.getHeader("Idempotency-Key")).isEqualTo(COMMAND_ID)
        assertThat(second.getHeader("Idempotency-Key")).isEqualTo(COMMAND_ID)
    }

    @Test
    fun `second unauthorized response is returned without an infinite retry`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        val client = client { forceRefresh, _ -> if (forceRefresh) "fresh-token" else "expired-token" }

        client.newCall(Request.Builder().url(server.url("/api/test")).build())
            .execute()
            .use { response -> assertThat(response.code).isEqualTo(401) }

        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `transient refresh failure is propagated without a false logout response`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val client = client { forceRefresh, _ ->
            if (forceRefresh) throw IOException("refresh unavailable")
            "expired-token"
        }

        val failure = runCatching {
            client.newCall(Request.Builder().url(server.url("/api/test")).build())
                .execute()
                .use { }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `assistant client never replays a rejected turn and spans the server stream window`() {
        server.enqueue(MockResponse().setResponseCode(401))
        var refreshAttempts = 0
        val client = rentalManagerAssistantClient(
            RentalManagerBearerInterceptor(
                freshAccessToken = { forceRefresh, _ ->
                    if (forceRefresh) refreshAttempts += 1
                    "expired-token"
                },
                retryUnauthorized = false,
            ),
        )

        client.newCall(Request.Builder().url(server.url("/api/assistant/v1/conversations/id/turns")).build())
            .execute()
            .use { response -> assertThat(response.code).isEqualTo(401) }

        assertThat(server.requestCount).isEqualTo(1)
        assertThat(refreshAttempts).isEqualTo(0)
        assertThat(client.retryOnConnectionFailure).isFalse()
        assertThat(client.readTimeoutMillis).isEqualTo(130_000)
        assertThat(client.callTimeoutMillis).isEqualTo(135_000)
    }

    private fun client(tokens: suspend (Boolean, String?) -> String?): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(RentalManagerBearerInterceptor(freshAccessToken = tokens))
            .build()
}

private const val COMMAND_ID = "11111111-1111-1111-1111-111111111111"
