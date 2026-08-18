package dev.buhanzaz.rwms.manager.network

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Verifies that gateway authentication retries cannot turn a refresh outage into a logout. */
class BearerTokenInterceptorTest {
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
    fun `transient refresh failure is propagated instead of returning unauthorized`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val client = OkHttpClient.Builder()
            .addInterceptor(
                BearerTokenInterceptor { forceRefresh, rejectedAccessToken ->
                    if (!forceRefresh) {
                        "expired-token"
                    } else {
                        assertThat(rejectedAccessToken).isEqualTo("expired-token")
                        throw IOException("refresh unavailable")
                    }
                },
            )
            .build()

        val failure = runCatching {
            client.newCall(Request.Builder().url(server.url("/api/test")).build())
                .execute()
                .use { }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure).hasMessageThat().contains("refresh unavailable")
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(server.takeRequest().getHeader("Authorization"))
            .isEqualTo("Bearer expired-token")
    }

    @Test
    fun `unauthorized request is retried once with refreshed token`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val client = OkHttpClient.Builder()
            .addInterceptor(
                BearerTokenInterceptor { forceRefresh, rejectedAccessToken ->
                    if (forceRefresh) {
                        assertThat(rejectedAccessToken).isEqualTo("expired-token")
                        "fresh-token"
                    } else {
                        "expired-token"
                    }
                },
            )
            .build()

        client.newCall(Request.Builder().url(server.url("/api/test")).build())
            .execute()
            .use { response -> assertThat(response.code).isEqualTo(200) }

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(server.takeRequest().getHeader("Authorization"))
            .isEqualTo("Bearer expired-token")
        assertThat(server.takeRequest().getHeader("Authorization"))
            .isEqualTo("Bearer fresh-token")
    }
}
